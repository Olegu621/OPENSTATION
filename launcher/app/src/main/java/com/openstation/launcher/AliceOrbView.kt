package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Display
import android.view.View
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared thread-safe telemetry hub read by AliceOrbView instances (at VSync 60fps)
 * and listened to by MainActivity / AliceOverlayManager for transcript & state changes.
 */
object LiveOrbState {
    fun interface StateListener {
        fun onOrbStateChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<StateListener>()
    @Volatile
    private var notifyPending = false

    @Volatile var assistantState: String = "idle"
    @Volatile var liveUserText: String = ""
    @Volatile var liveModelText: String = ""
    @Volatile var lastModelText: String = ""
    @Volatile var lastError: String = ""
    @Volatile var lastActiveAtMs: Long = 0L
    @Volatile var lastReplyAtMs: Long = 0L

    @Volatile var ttsRms: Float = 0f
    @Volatile var micRms: Float = 0f

    @Volatile var musicPlaying: Boolean = false
    @Volatile var musicBass: Float = 0f
    @Volatile var musicMid: Float = 0f
    @Volatile var musicHigh: Float = 0f
    @Volatile var musicOverall: Float = 0f

    @Volatile var clockStyle: String = "classic"
    @Volatile var visualizerStyle: String = "pulse_bars"
    @Volatile var autoMusicVisualizer: Boolean = true
    @Volatile var previewUntilMs: Long = 0L
    @Volatile private var styleSyncedOnce: Boolean = false
    @Volatile var assistantName: String = "Алиса"
    @Volatile var assistantVoice: String = "Aoede"

    fun effectiveName(): String =
        assistantName.trim().ifEmpty { "Алиса" }

    fun assistantIsMale(): Boolean =
        AssistantPersonalities.isMaleVoice(assistantVoice)

    fun isGrammaticallyMale(): Boolean =
        assistantIsMale() || AssistantPersonalities.isLikelyMaleName(effectiveName())

    fun readyWord(caps: Boolean = false): String =
        if (isGrammaticallyMale()) (if (caps) "ГОТОВ" else "готов") else (if (caps) "ГОТОВА" else "готова")

    fun activeWord(): String =
        if (isGrammaticallyMale()) "активен" else "активна"

    fun offWord(): String =
        if (isGrammaticallyMale()) "выключен" else "выключена"

    fun updateAssistantPersona(name: String?, voice: String?) {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isNotEmpty()) {
            assistantName = cleanName
        }
        val cleanVoice = voice?.trim().orEmpty()
        if (cleanVoice.isNotEmpty()) {
            assistantVoice = cleanVoice
        }
    }

    @Volatile var weatherTemp: Int = 0
    @Volatile var weatherCondition: String = ""
    @Volatile var weatherConditionText: String = ""
    @Volatile var weatherCity: String = ""
    @Volatile var weatherValid: Boolean = false
    @Volatile var weatherTimestampMs: Long = 0L

    val rawBands = FloatArray(24)

    fun addListener(l: StateListener) {
        listeners.add(l)
    }

    fun removeListener(l: StateListener) {
        listeners.remove(l)
    }

    fun updateStyles(clock: String?, visualizer: String?, autoVis: Boolean?) {
        var changed = false
        if (!clock.isNullOrBlank() && clock != clockStyle) {
            clockStyle = clock
            changed = true
        }
        if (!visualizer.isNullOrBlank() && visualizer != visualizerStyle) {
            visualizerStyle = visualizer
            if (styleSyncedOnce) {
                previewUntilMs = System.currentTimeMillis() + 6500L
            }
            changed = true
        }
        styleSyncedOnce = true
        if (autoVis != null && autoVis != autoMusicVisualizer) {
            autoMusicVisualizer = autoVis
            changed = true
        }
        if (changed) notifyListenersDebounced()
    }

    fun updateWeather(valid: Boolean, temp: Int, cond: String, text: String, city: String) {
        weatherValid = valid
        weatherTemp = temp
        weatherCondition = cond
        weatherConditionText = text
        weatherCity = city
        weatherTimestampMs = android.os.SystemClock.elapsedRealtime()
        notifyListenersDebounced()
    }

    fun notifyListenersDebounced() {
        if (notifyPending) return
        notifyPending = true
        mainHandler.post {
            notifyPending = false
            for (l in listeners) {
                try {
                    l.onOrbStateChanged()
                } catch (_: Throwable) {}
            }
        }
    }

    fun updateAssistantEvent(
        state: String,
        liveUser: String,
        liveModel: String,
        lastModel: String,
        error: String
    ) {
        val prevState = assistantState
        val now = android.os.SystemClock.elapsedRealtime()
        assistantState = state
        if (state != "idle" && state != "error") {
            lastActiveAtMs = now
        }
        liveUserText = liveUser
        liveModelText = liveModel
        if (lastModel.isNotBlank()) {
            if (lastModel != lastModelText && (state != "idle" || prevState != "idle")) lastReplyAtMs = now
            lastModelText = lastModel
        } else if (liveModel.isNotBlank()) {
            if (liveModel != lastModelText) lastReplyAtMs = now
            lastModelText = liveModel
        } else if (state == "connecting" && prevState == "idle") {
            // New voice turn clears stale text from an older conversation
            lastModelText = ""
            lastError = ""
        }
        if (error.isNotBlank()) {
            if (error != lastError && (state != "idle" || prevState != "idle")) lastReplyAtMs = now
            lastError = error
        }
        if (state == "idle") {
            if (prevState != "idle" && (lastModelText.isNotBlank() || lastError.isNotBlank())) lastReplyAtMs = now
            ttsRms = 0f
            micRms = 0f
        }
        notifyListenersDebounced()
    }

    fun updateVoiceLevel(state: String, tts: Float, mic: Float) {
        val changed = (state.isNotEmpty() && state != assistantState)
        if (state.isNotEmpty()) {
            if (changed && state == "idle" && (lastModelText.isNotBlank() || lastError.isNotBlank())) {
                lastReplyAtMs = android.os.SystemClock.elapsedRealtime()
            }
            assistantState = state
            if (state != "idle" && state != "error") {
                lastActiveAtMs = android.os.SystemClock.elapsedRealtime()
            }
        }
        ttsRms = tts.coerceIn(0f, 1f)
        micRms = mic.coerceIn(0f, 1f)
        if (changed) notifyListenersDebounced()
    }

    fun updateMusicSpectrum(bands: FloatArray, playing: Boolean) {
        musicPlaying = playing
        if (bands.size < 24) return
        synchronized(rawBands) {
            val len = min(bands.size, rawBands.size)
            System.arraycopy(bands, 0, rawBands, 0, len)
        }
        var bass = 0f
        for (i in 0..4) bass += bands[i]
        bass /= 5f

        var mid = 0f
        for (i in 5..14) mid += bands[i]
        mid /= 10f

        var high = 0f
        for (i in 15..23) high += bands[i]
        high /= 9f

        musicBass = bass.coerceIn(0f, 1f)
        musicMid = mid.coerceIn(0f, 1f)
        musicHigh = high.coerceIn(0f, 1f)
        musicOverall = (bass * 0.45f + mid * 0.35f + high * 0.20f).coerceIn(0f, 1f)
    }

    fun bestDisplayText(): String {
        return AssistantDisplayPolicy.text(assistantState, liveModelText, lastModelText, liveUserText,
            lastError, lastReplyAtMs, android.os.SystemClock.elapsedRealtime())
    }
}

/**
 * Compatibility shim retained so existing startup callers do not fail if referenced.
 * The unified Obsidian Halftone 3D Sphere is rendered procedurally in real time at 60fps
 * without loading multi-megabyte WebP sprite sheets into RAM.
 */
object AliceOrbFrames {
    @Volatile var loaded: Boolean = true
        private set

    @Suppress("UNUSED_PARAMETER")
    fun preloadAsync(context: Context, onReady: (() -> Unit)? = null) {
        loaded = true
        if (onReady != null) {
            Handler(Looper.getMainLooper()).post(onReady)
        }
    }
}

/**
 * Signature 3D Obsidian Carbon & Silver Halftone Sphere View.
 * Used uniformly across Home Hero, Docked Mini Orb, Floating Assistant Overlay,
 * Setup Wizard, and OTA Completion screen — responding seamlessly to idle liquid
 * reflections, voice assistant telemetry, and live music FFT spectrum.
 */
class AliceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var totalTime = 0f
    private var lastNs = 0L
    private var lastDrawNs = 0L
    private var running = false
    private var animationEnabled = true
    private val powerManager: PowerManager? by lazy(LazyThreadSafetyMode.NONE) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    private val wakeCheckRunnable = Runnable {
        if (running) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // 0f on Screen 0 hero, 0..1f during morph -> 1f in docked/overlay bar
    @Volatile
    var compactGlowBoost: Float = 0f

    // Smoothed physics & reactive telemetry buffers (zero per-frame allocations)
    private val smoothedBands = FloatArray(24)
    private var curScale = 1.0f
    private var curEnergy = 0.14f
    private var curVoiceLevel = 0f
    private var curTintStrength = 0f
    private var curTintColor = Color.WHITE
    private var curAuraAlpha = 0f
    private var curAuraColor = Color.parseColor("#38BDF8")
    private var levitatePhase = 0f

    // Cached offscreen GPU bitmaps for the obsidian sphere shell + top-left glossy studio sheen
    private var cachedShellDim = 0
    private var shellBitmap: Bitmap? = null
    private var sheenBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shellDstRect = RectF()

    // Active-state subtle ambient glow behind sphere (unit RadialGradient + Matrix, cached)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraMatrix = Matrix()
    private val auraStops = floatArrayOf(0f, 0.56f, 1f)
    private var cachedAuraShader: RadialGradient? = null
    private var cachedAuraQKey = 0
    private val auraShaderCache = object : LinkedHashMap<Int, RadialGradient>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RadialGradient>?): Boolean = size > 24
    }

    // Preallocated Paint objects for halftone dot rendering
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && windowVisibility == VISIBLE && isAttachedToWindow) startAnim() else stopAnim()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && this.visibility == VISIBLE && isAttachedToWindow) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (running || !animationEnabled) return
        running = true
        lastNs = 0L
        removeCallbacks(wakeCheckRunnable)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopAnim() {
        running = false
        removeCallbacks(wakeCheckRunnable)
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun setAnimationEnabled(enabled: Boolean) {
        if (animationEnabled == enabled) return
        animationEnabled = enabled
        if (enabled && isAttachedToWindow && isShown && windowVisibility == VISIBLE) startAnim()
        else if (!enabled) stopAnim()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val disp = display
        val displayOff = disp != null && disp.state == Display.STATE_OFF
        val interactive = powerManager?.isInteractive ?: true
        if (!isShown || displayOff || !interactive) {
            lastNs = 0L
            removeCallbacks(wakeCheckRunnable)
            postDelayed(wakeCheckRunnable, 450L)
            return
        }

        val st = LiveOrbState.assistantState
        val pureIdle = (st == "idle" &&
            !LiveOrbState.musicPlaying &&
            LiveOrbState.musicOverall <= 0.045f &&
            compactGlowBoost < 0.01f &&
            System.currentTimeMillis() >= LiveOrbState.previewUntilMs)
        // Calm idle breathes gently at ~30fps to save Mali-G31 fillrate; active states run at 60fps
        if (pureIdle && lastDrawNs != 0L && (frameTimeNanos - lastDrawNs) < 28_000_000L) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }

        val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) * 1e-9f).coerceIn(0.004f, 0.14f)
        lastNs = frameTimeNanos
        lastDrawNs = frameTimeNanos
        stepPhysics(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stepPhysics(dt: Float) {
        totalTime += dt
        val st = LiveOrbState.assistantState
        val tts = LiveOrbState.ttsRms
        val mic = LiveOrbState.micRms
        val musicOn = LiveOrbState.musicPlaying || LiveOrbState.musicOverall > 0.045f
        val nowMs = System.currentTimeMillis()
        val isPreview = (nowMs < LiveOrbState.previewUntilMs)
        if (isPreview && !musicOn) {
            val phase = (nowMs % 2800L) / 2800f * (2f * PI.toFloat())
            LiveOrbState.musicBass = (0.55f + 0.35f * sin(phase)).coerceIn(0f, 1f)
            LiveOrbState.musicMid = (0.40f + 0.30f * cos(phase * 1.4f)).coerceIn(0f, 1f)
            LiveOrbState.musicHigh = (0.30f + 0.25f * sin(phase * 2.2f)).coerceIn(0f, 1f)
            LiveOrbState.musicOverall = (LiveOrbState.musicBass + LiveOrbState.musicMid) * 0.5f
        }

        val bass = LiveOrbState.musicBass
        val mTot = LiveOrbState.musicOverall

        // Smooth 24-band FFT spectrum for liquid silver acoustic ripples
        if (musicOn || isPreview) {
            val raw = LiveOrbState.rawBands
            val rawLen = raw.size
            val synthMode = isPreview && !musicOn
            for (b in 0 until 24) {
                val targetBand = if (synthMode) {
                    val w1 = 0.5f + 0.5f * sin(totalTime * 3.1f + b * 0.42f)
                    val w2 = 0.5f + 0.5f * cos(totalTime * 1.9f - b * 0.65f)
                    val bassW = max(0f, 1f - b / 10f) * 0.25f
                    (0.18f + w1 * 0.45f + w2 * 0.28f + bassW).coerceIn(0f, 1f)
                } else if (b < rawLen) {
                    raw[b].coerceIn(0f, 1f)
                } else {
                    0f
                }
                val prev = smoothedBands[b]
                val coeff = if (targetBand > prev) min(1f, dt * 20f) else min(1f, dt * 8.5f)
                smoothedBands[b] = (prev + (targetBand - prev) * coeff).coerceIn(0f, 1f)
            }
        } else {
            val decay = max(0f, 1f - dt * 5f)
            for (b in 0 until 24) {
                smoothedBands[b] *= decay
            }
        }

        val tgtScale: Float
        val tgtEnergy: Float
        val tgtVoice: Float
        val tgtTintStr: Float
        val tgtTintCol: Int
        val tgtAuraAlpha: Float
        val tgtAuraCol: Int

        when (st) {
            "connecting", "listening" -> {
                tgtScale = 1.015f + mic * 0.085f + sin(totalTime * 3.2f) * 0.012f
                tgtEnergy = (0.45f + mic * 0.55f).coerceIn(0f, 1f)
                tgtVoice = (0.35f + mic * 0.65f).coerceIn(0f, 1f)
                tgtTintStr = 0.34f + mic * 0.24f
                tgtTintCol = if (mic > 0.25f) Color.parseColor("#A7F3D0") else Color.parseColor("#BAE6FD")
                tgtAuraAlpha = 0.38f + mic * 0.36f
                tgtAuraCol = if (mic > 0.25f) Color.parseColor("#2DD4BF") else Color.parseColor("#38BDF8")
            }
            "thinking" -> {
                val osc = (sin(totalTime * 4.0f) + 1f) * 0.5f
                tgtScale = 1.01f + sin(totalTime * 7.2f) * 0.025f
                tgtEnergy = 0.68f + osc * 0.24f
                tgtVoice = 0.62f
                tgtTintStr = 0.30f
                tgtTintCol = if (osc > 0.5f) Color.parseColor("#FDE68A") else Color.parseColor("#DDD6FE")
                tgtAuraAlpha = 0.42f + osc * 0.22f
                tgtAuraCol = if (osc > 0.5f) Color.parseColor("#E8C686") else Color.parseColor("#A855F7")
            }
            "speaking" -> {
                tgtScale = 1.015f + tts * 0.095f
                tgtEnergy = (0.50f + tts * 0.50f).coerceIn(0f, 1f)
                tgtVoice = (0.40f + tts * 0.60f).coerceIn(0f, 1f)
                tgtTintStr = 0.26f + tts * 0.26f
                tgtTintCol = if (tts > 0.35f) Color.parseColor("#E0E7FF") else Color.parseColor("#BAE6FD")
                tgtAuraAlpha = 0.40f + tts * 0.38f
                tgtAuraCol = if (tts > 0.35f) Color.parseColor("#C084FC") else Color.parseColor("#38BDF8")
            }
            "error" -> {
                tgtScale = 0.99f + sin(totalTime * 6.5f) * 0.018f
                tgtEnergy = 0.48f
                tgtVoice = 0.35f
                tgtTintStr = 0.55f
                tgtTintCol = Color.parseColor("#FCA5A5")
                tgtAuraAlpha = 0.42f
                tgtAuraCol = Color.parseColor("#F87171")
            }
            else -> {
                if (musicOn || isPreview) {
                    tgtScale = 1.0f + bass * 0.065f + mTot * 0.022f
                    tgtEnergy = (0.30f + mTot * 0.70f).coerceIn(0f, 1f)
                    tgtVoice = 0f
                    tgtTintStr = 0.08f * mTot
                    tgtTintCol = Color.parseColor("#F1F5F9")
                    tgtAuraAlpha = 0.14f + bass * 0.20f
                    tgtAuraCol = Color.parseColor("#CBD5E1")
                } else {
                    // Pure monochrome matte obsidian & chrome liquid wave aesthetic
                    tgtScale = 1.0f + sin(totalTime * 1.15f) * 0.008f
                    tgtEnergy = 0.18f
                    tgtVoice = 0f
                    tgtTintStr = 0f
                    tgtTintCol = Color.WHITE
                    tgtAuraAlpha = compactGlowBoost.coerceIn(0f, 1f) * 0.14f
                    tgtAuraCol = Color.parseColor("#94A3B8")
                }
            }
        }

        val kFast = min(1f, dt * 13f)
        val kSmooth = min(1f, dt * 6.5f)
        curScale += (tgtScale - curScale) * kFast
        curEnergy += (tgtEnergy - curEnergy) * kFast
        curVoiceLevel += (tgtVoice - curVoiceLevel) * kFast
        curTintStrength += (tgtTintStr - curTintStrength) * kSmooth
        curTintColor = blendColor(curTintColor, tgtTintCol, min(1f, dt * 6.0f))
        curAuraAlpha += (tgtAuraAlpha - curAuraAlpha) * kSmooth
        curAuraColor = blendColor(curAuraColor, tgtAuraCol, min(1f, dt * 5.0f))

        levitatePhase = (levitatePhase + dt * 1.05f) % (2f * PI.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val cx = w * 0.5f
        val baseDim = min(w, h)
        val boost = compactGlowBoost.coerceIn(0f, 1f)
        val levitateAmp = if (boost > 0.85f) 0f else min(baseDim * 0.015f, resources.displayMetrics.density * 4.0f)
        val cy = h * 0.5f + sin(levitatePhase) * levitateAmp

        // Sphere radius occupies ~85% of container diameter, leaving clean shadow headroom
        val orbDiameter = baseDim * 0.85f * curScale
        val sphereR = orbDiameter * 0.5f

        // 1. Subtle active-state atmospheric glow behind the obsidian shell (off in pure idle)
        if (curAuraAlpha > 0.02f) {
            val auraR = min(baseDim * 0.50f, sphereR * (1.17f + curEnergy * 0.12f))
            val qA = ((curAuraAlpha * 165f).toInt().coerceIn(0, 185) + 4) and 0xF8
            val qR = (Color.red(curAuraColor) + 4) and 0xF8
            val qG = (Color.green(curAuraColor) + 4) and 0xF8
            val qB = (Color.blue(curAuraColor) + 4) and 0xF8
            if (qA > 4) {
                val qKey = (qA shl 24) or (qR shl 16) or (qG shl 8) or qB
                var shader = if (cachedAuraQKey == qKey) cachedAuraShader else null
                if (shader == null) {
                    shader = auraShaderCache[qKey]
                    if (shader == null) {
                        val cMid = Color.argb((qA * 0.44f).toInt(), qR, qG, qB)
                        shader = RadialGradient(
                            0f, 0f, 1f,
                            intArrayOf(qKey, cMid, Color.TRANSPARENT),
                            auraStops,
                            Shader.TileMode.CLAMP
                        )
                        auraShaderCache[qKey] = shader
                    }
                    cachedAuraShader = shader
                    cachedAuraQKey = qKey
                    auraPaint.shader = shader
                }
                auraMatrix.setScale(auraR, auraR)
                auraMatrix.postTranslate(cx, cy)
                shader.setLocalMatrix(auraMatrix)
                canvas.drawCircle(cx, cy, auraR, auraPaint)
            }
        }

        // 2. Glossy 3D Obsidian Sphere Shell + Soft Drop Shadow (pre-rendered GPU bitmap blit)
        ensureShellBitmaps(baseDim)
        val shellHalf = sphereR / 0.85f
        shellDstRect.set(cx - shellHalf, cy - shellHalf, cx + shellHalf, cy + shellHalf)
        shellBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, shellDstRect, bitmapPaint)
            }
        }

        // 3. Curved 3D Spherical Halftone Dot Matrix with Liquid Metallic Specular Reflections
        drawHalftoneDots(canvas, cx, cy, sphereR, boost)

        // 4. Subtle upper-left studio glass dome reflection glaze sitting smoothly above the dots
        sheenBitmap?.let { sheen ->
            if (!sheen.isRecycled) {
                canvas.drawBitmap(sheen, null, shellDstRect, bitmapPaint)
            }
        }
    }

    private fun ensureShellBitmaps(baseDim: Float) {
        val targetDim = (Math.ceil((baseDim / 32.0)) * 32).toInt().coerceIn(96, 288)
        if (cachedShellDim == targetDim &&
            shellBitmap != null && !shellBitmap!!.isRecycled &&
            sheenBitmap != null && !sheenBitmap!!.isRecycled
        ) {
            return
        }
        cachedShellDim = targetDim

        val shell = Bitmap.createBitmap(targetDim, targetDim, Bitmap.Config.ARGB_8888)
        val sheen = Bitmap.createBitmap(targetDim, targetDim, Bitmap.Config.ARGB_8888)
        val shellPixels = IntArray(targetDim * targetDim)
        val sheenPixels = IntArray(targetDim * targetDim)

        val half = targetDim * 0.5f
        val rPx = half * 0.85f
        val invR = 1f / max(1f, rPx)
        val invRSh = 1f / max(1f, rPx * 1.06f)
        val yShiftSh = rPx * 0.085f

        var pIdx = 0
        for (y in 0 until targetDim) {
            val dyPx = y - half
            val dy = dyPx * invR
            val dySh = (dyPx - yShiftSh) * invRSh
            for (x in 0 until targetDim) {
                val dxPx = x - half
                val dx = dxPx * invR
                val rr = hypot(dx, dy)

                // Soft black ambient drop shadow fading smoothly to transparent before bitmap border
                val dxSh = (dxPx - rPx * 0.02f) * invRSh
                val rrSh = hypot(dxSh, dySh)
                val shFalloff = if (rrSh < 1.16f) {
                    val edgeTaper = ((1.16f - rrSh) / 0.24f).coerceIn(0f, 1f)
                    kotlin.math.exp(-rrSh * rrSh * 2.15f) * edgeTaper
                } else {
                    0f
                }
                val shA = (shFalloff * 204f).toInt().coerceIn(0, 215)

                if (rr < 1.006f) {
                    val inside = ((1.004f - rr) * rPx * 0.72f).coerceIn(0f, 1f)
                    val rrClamped = min(1f, rr)
                    val nz = sqrt(max(0f, 1f - rrClamped * rrClamped))

                    // Studio directional volume + smooth upper-left key-light crescent
                    val ndotl = max(0f, dx * -0.48f + dy * -0.54f + nz * 0.69f)
                    val fresnel = (1f - nz).pow(2.7f)
                    val rimDir = (-(dx * 0.68f + dy * 0.73f)).coerceIn(0f, 1f)
                    val rimArc = kotlin.math.exp(-((rrClamped - 0.968f) / 0.040f).pow(2)) * rimDir.pow(1.15f) * 44f
                    val dSoft = hypot(dx + 0.34f, dy + 0.36f)
                    val softBox = 24f * kotlin.math.exp(-dSoft * dSoft * 7.8f)

                    val bodyLum = (3f + 11f * ndotl.pow(2.2f) + 18f * fresnel * (0.20f + 0.80f * rimDir) + softBox + rimArc)
                        .toInt().coerceIn(2, 92)

                    val outA = max(shA, (inside * 255f).toInt().coerceIn(0, 255))
                    // Premultiply clean transition across AA sphere rim
                    val rgb = (bodyLum * inside).toInt().coerceIn(0, 255)
                    shellPixels[pIdx] = Color.argb(outA, rgb, rgb, (rgb + 2).coerceAtMost(255))

                    // Subtle glossy dome glaze over the halftone dots on top-left
                    val glaze = (34f * kotlin.math.exp(-dSoft * dSoft * 9.5f) +
                        26f * kotlin.math.exp(-((rrClamped - 0.958f) / 0.044f).pow(2)) * rimDir.pow(1.45f)) * inside
                    val ga = glaze.toInt().coerceIn(0, 68)
                    sheenPixels[pIdx] = if (ga > 1) Color.argb(ga, 245, 248, 255) else 0
                } else {
                    shellPixels[pIdx] = if (shA > 1) Color.argb(shA, 0, 0, 0) else 0
                    sheenPixels[pIdx] = 0
                }
                pIdx++
            }
        }

        shell.setPixels(shellPixels, 0, targetDim, 0, 0, targetDim, targetDim)
        sheen.setPixels(sheenPixels, 0, targetDim, 0, 0, targetDim, targetDim)
        shellBitmap = shell
        sheenBitmap = sheen
    }

    private fun drawHalftoneDots(canvas: Canvas, cx: Float, cy: Float, sphereR: Float, boost: Float) {
        val isMini = (sphereR < 68f || boost > 0.48f)
        val musicOn = LiveOrbState.musicPlaying || LiveOrbState.musicOverall > 0.045f ||
            System.currentTimeMillis() < LiveOrbState.previewUntilMs
        val st = LiveOrbState.assistantState
        val assistantActive = (st != "idle")
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh
        val mTot = LiveOrbState.musicOverall

        // Lattice pitch at center is sphereR * (0.88f / 17f) = sphereR * 0.05176f
        // Base max dot radius = ~0.43 * pitch (~0.86x spacing diameter -> lush plump dots, crisp dark channels)
        val baseDotR = if (isMini) max(1.35f, sphereR * 0.045f) else max(1.55f, sphereR * 0.0226f)

        // Smoothly drifting 3D liquid metallic highlight lobes matching 8s reference loop
        val tWave = totalTime * if (st == "thinking") 2.15f else if (assistantActive) 1.35f else 0.7854f
        val lobe1X = cos(tWave) * 0.48f
        val lobe1Y = sin(tWave * 0.72f - 0.35f) * 0.48f - 0.10f
        val lobe2X = sin(tWave * 0.80f + 1.85f) * 0.56f
        val lobe2Y = cos(tWave + 0.90f) * 0.44f + 0.06f
        val globalBreath = 0.58f + 0.42f * sin(tWave + 0.25f) // gently breathes between moody & radiant peaks

        val voiceCenterPulse = if (assistantActive) curVoiceLevel * 0.92f else 0f
        val voiceRippleSpeed = if (st == "speaking") 6.5f else 4.2f

        val tintAmt = curTintStrength.coerceIn(0f, 0.75f)
        val tintR = Color.red(curTintColor)
        val tintG = Color.green(curTintColor)
        val tintB = Color.blue(curTintColor)

        val nxArr = HalftoneLattice.nx
        val nyArr = HalftoneLattice.ny
        val sxArr = HalftoneLattice.sx
        val syArr = HalftoneLattice.sy
        val szArr = HalftoneLattice.sz
        val r2dArr = HalftoneLattice.r2d
        val rimFadeArr = HalftoneLattice.rimFade
        val foreArr = HalftoneLattice.foreshort
        val baseShadeArr = HalftoneLattice.baseShade
        val bandArr = HalftoneLattice.bandIdx
        val seedArr = HalftoneLattice.seedPhase
        val miniKeepArr = HalftoneLattice.miniKeep
        val count = HalftoneLattice.count

        for (n in 0 until count) {
            if (isMini && !miniKeepArr[n]) continue

            val sx = sxArr[n]
            val sy = syArr[n]
            val sz = szArr[n]
            val nx = nxArr[n]
            val ny = nyArr[n]
            val r2d = r2dArr[n]

            // Liquid specular reflection intensity on 3D curved dome
            val d1 = (sx - lobe1X) * (sx - lobe1X) + (sy - lobe1Y) * (sy - lobe1Y)
            val d2 = (sx - lobe2X) * (sx - lobe2X) + (sy - lobe2Y) * (sy - lobe2Y)
            var caustic = (1.12f * kotlin.math.exp(-d1 * 7.0f) + 0.96f * kotlin.math.exp(-d2 * 6.5f)) *
                (0.32f + 0.68f * globalBreath)
            val liquidRipple = 0.11f * (0.5f + 0.5f * sin(sx * 3.7f + sy * 2.8f + tWave * 1.15f)) * sz

            var musicDisp = 0f
            if (musicOn) {
                val band = smoothedBands[bandArr[n]]
                val waveSym = 0.5f + 0.5f * sin(r2d * 7.2f - totalTime * 5.2f + seedArr[n])
                caustic += band * (0.78f + bass * 0.45f) + waveSym * (0.14f + mid * 0.25f) + high * 0.16f * mTot
                musicDisp = band * 0.036f + waveSym * (0.008f + bass * 0.014f)
            }

            if (assistantActive) {
                val radialWave = 0.5f + 0.5f * sin(r2d * 6.5f - totalTime * voiceRippleSpeed)
                val centerFocus = kotlin.math.exp(-r2d * r2d * 4.2f) * voiceCenterPulse
                caustic += centerFocus + radialWave * curEnergy * 0.34f
            }

            val inten = ((baseShadeArr[n] + liquidRipple + caustic) * rimFadeArr[n]).coerceIn(0f, 1f)
            val dr = baseDotR * foreArr[n] * (0.46f + 0.54f * inten.pow(0.65f))
            if (dr < 0.35f) continue

            val px = cx + nx * (1f + musicDisp) * sphereR
            val py = cy + ny * (1f + musicDisp) * sphereR

            val lum = (16f + 239f * inten.pow(0.88f)).coerceIn(0f, 255f)
            val alpha = (95f + 160f * inten.pow(0.55f) * rimFadeArr[n]).toInt().coerceIn(0, 255)

            // Apply ultra-subtle reactive tint primarily on brightest highlights during active voice states
            val effTint = tintAmt * inten
            val rCol = (lum * (1f - effTint) + (tintR * (lum / 255f)) * effTint).toInt().coerceIn(0, 255)
            val gCol = (lum * (1f - effTint) + (tintG * (lum / 255f)) * effTint).toInt().coerceIn(0, 255)
            val bCol = (lum * (1f - effTint) + (tintB * (lum / 255f)) * effTint).toInt().coerceIn(0, 255)

            // Subtle chrome bloom behind brightest specular dots (clean OLED light feel, skipped at 56dp)
            if (!isMini && inten > 0.72f) {
                val glowA = ((inten - 0.72f) / 0.28f * 58f).toInt().coerceIn(0, 64)
                if (glowA > 4) {
                    dotGlowPaint.color = Color.argb(glowA, rCol, gCol, bCol)
                    canvas.drawCircle(px, py, dr * 1.95f, dotGlowPaint)
                }
            }

            dotPaint.color = Color.argb(alpha, rCol, gCol, bCol)
            canvas.drawCircle(px, py, dr, dotPaint)
        }
    }

    private fun blendColor(c0: Int, c1: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        val r = (Color.red(c0) * inv + Color.red(c1) * k).toInt()
        val g = (Color.green(c0) * inv + Color.green(c1) * k).toInt()
        val b = (Color.blue(c0) * inv + Color.blue(c1) * k).toInt()
        return Color.rgb(r, g, b)
    }

    /**
     * Precomputed equidistant 3D spherical dome lattice (35x35 grid wrapped cleanly inside 0.88R
     * so the outer glossy obsidian casing remains cleanly visible around the halftone matrix).
     */
    private object HalftoneLattice {
        val count: Int
        val nx: FloatArray
        val ny: FloatArray
        val sx: FloatArray
        val sy: FloatArray
        val sz: FloatArray
        val r2d: FloatArray
        val rimFade: FloatArray
        val foreshort: FloatArray
        val baseShade: FloatArray
        val bandIdx: IntArray
        val seedPhase: FloatArray
        val miniKeep: BooleanArray

        init {
            val halfRange = 17
            val thetaMax = 1.02f
            val invSinMax = 1f / sin(thetaMax)
            val maxEnvelope = 0.880f

            val maxCap = (halfRange * 2 + 1) * (halfRange * 2 + 1)
            val tNx = FloatArray(maxCap)
            val tNy = FloatArray(maxCap)
            val tSx = FloatArray(maxCap)
            val tSy = FloatArray(maxCap)
            val tSz = FloatArray(maxCap)
            val tR2d = FloatArray(maxCap)
            val tRim = FloatArray(maxCap)
            val tFore = FloatArray(maxCap)
            val tBase = FloatArray(maxCap)
            val tBand = IntArray(maxCap)
            val tSeed = FloatArray(maxCap)
            val tMini = BooleanArray(maxCap)

            var idx = 0
            for (j in -halfRange..halfRange) {
                for (i in -halfRange..halfRange) {
                    val gx = i / halfRange.toFloat()
                    val gy = j / halfRange.toFloat()
                    val r = hypot(gx, gy)
                    if (r > 1.03f) continue

                    val angR = r * thetaMax
                    val sxVal: Float
                    val syVal: Float
                    val szVal: Float
                    if (r > 1e-5f) {
                        val sinR = sin(angR)
                        sxVal = (gx / r) * sinR
                        syVal = (gy / r) * sinR
                        szVal = cos(angR)
                    } else {
                        sxVal = 0f
                        syVal = 0f
                        szVal = 1f
                    }

                    val nxVal = sxVal * invSinMax * maxEnvelope
                    val nyVal = syVal * invSinMax * maxEnvelope
                    val r2 = hypot(nxVal, nyVal)
                    if (r2 > maxEnvelope) continue

                    val rim = ((maxEnvelope - r2) / 0.145f).coerceIn(0f, 1f).pow(0.75f)
                    val fore = 0.45f + 0.55f * szVal
                    val shade = 0.11f + 0.17f * max(0f, -sxVal * 0.45f - syVal * 0.50f + szVal * 0.72f)

                    val ang = atan2(gy, gx)
                    val bandRaw = (((ang + PI.toFloat()) / (2f * PI.toFloat())) * 24f + r * 6f).toInt()
                    val band = ((bandRaw % 24) + 24) % 24

                    tNx[idx] = nxVal
                    tNy[idx] = nyVal
                    tSx[idx] = sxVal
                    tSy[idx] = syVal
                    tSz[idx] = szVal
                    tR2d[idx] = r2
                    tRim[idx] = rim
                    tFore[idx] = fore
                    tBase[idx] = shade
                    tBand[idx] = band
                    tSeed[idx] = ((i * 73 + j * 139) and 0xFF) * 0.02454f
                    tMini[idx] = ((i and 1) == 0) && ((j and 1) == 0)
                    idx++
                }
            }

            count = idx
            nx = tNx.copyOf(idx)
            ny = tNy.copyOf(idx)
            sx = tSx.copyOf(idx)
            sy = tSy.copyOf(idx)
            sz = tSz.copyOf(idx)
            r2d = tR2d.copyOf(idx)
            rimFade = tRim.copyOf(idx)
            foreshort = tFore.copyOf(idx)
            baseShade = tBase.copyOf(idx)
            bandIdx = tBand.copyOf(idx)
            seedPhase = tSeed.copyOf(idx)
            miniKeep = tMini.copyOf(idx)
        }
    }
}
