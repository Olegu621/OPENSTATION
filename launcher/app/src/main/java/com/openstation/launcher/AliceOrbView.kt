package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    @Volatile var visualizerStyle: String = "particles"
    @Volatile var autoMusicVisualizer: Boolean = true

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
            changed = true
        }
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
            lastModelText = lastModel
            lastReplyAtMs = now
        } else if (liveModel.isNotBlank()) {
            lastModelText = liveModel
            lastReplyAtMs = now
        } else if (state == "connecting" && prevState == "idle") {
            // New voice turn clears stale text from an older conversation
            lastModelText = ""
            lastError = ""
        }
        if (error.isNotBlank()) {
            lastError = error
            lastReplyAtMs = now
        }
        if (state == "idle") {
            ttsRms = 0f
            micRms = 0f
        }
        notifyListenersDebounced()
    }

    fun updateVoiceLevel(state: String, tts: Float, mic: Float) {
        val changed = (state.isNotEmpty() && state != assistantState)
        if (state.isNotEmpty()) {
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
        if (lastError.isNotBlank() && assistantState == "error") return "Ошибка: $lastError"
        val m = liveModelText.trim().ifEmpty { lastModelText.trim() }
        if (m.isNotEmpty()) return m
        val u = liveUserText.trim()
        if (u.isNotEmpty()) return "«$u»"
        if (lastError.isNotBlank() && (android.os.SystemClock.elapsedRealtime() - lastReplyAtMs) < 6000L) {
            return "Ошибка: $lastError"
        }
        return ""
    }
}

/**
 * Decodes and shares the 188-frame sphere sprite sheets across all views & overlay once per process.
 */
object AliceOrbFrames {
    private const val TAG = "AliceOrbFrames"

    @Volatile var loaded = false
        private set
    @Volatile private var loading = false

    var frameCount = 188
        private set
    var tileSize = 200
        private set
    var cols = 8
        private set
    var perSheet = 48
        private set
    var avgFrameMs = 66.7f
        private set

    var sheets: Array<Bitmap?> = emptyArray()
        private set
    var srcRects: Array<Rect> = emptyArray()
        private set

    private val lock = Any()
    private val readyListeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun preloadAsync(context: Context, onReady: (() -> Unit)? = null) {
        if (loaded) {
            if (onReady != null) mainHandler.post(onReady)
            return
        }
        if (onReady != null) readyListeners.add(onReady)
        synchronized(lock) {
            if (loaded || loading) return
            loading = true
        }
        val appCtx = context.applicationContext
        thread(name = "AliceOrbLoad", isDaemon = true) {
            try {
                loadInternal(appCtx)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed loading orb sprite sheets: ${t.message}", t)
            } finally {
                synchronized(lock) { loading = false }
                mainHandler.post {
                    val list = readyListeners.toList()
                    readyListeners.clear()
                    list.forEach { it() }
                }
            }
        }
    }

    private fun loadInternal(context: Context) {
        val am = context.assets
        val metaRaw = am.open("orb_meta.json").bufferedReader().use { it.readText() }
        val meta = JSONObject(metaRaw)
        val count = meta.optInt("frameCount", 188)
        val tSize = meta.optInt("tileSize", 200)
        val cCols = meta.optInt("cols", 8)
        val pSheet = meta.optInt("perSheet", 48)
        val avgMs = meta.optDouble("avgFrameMs", 66.7).toFloat()
        val sheetArr = meta.getJSONArray("sheets")

        val decoded = arrayOfNulls<Bitmap>(sheetArr.length())
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        for (i in 0 until sheetArr.length()) {
            val name = sheetArr.getString(i)
            am.open(name).use { ins ->
                decoded[i] = BitmapFactory.decodeStream(ins, null, opts)
            }
        }

        val rects = Array(count) { idx ->
            val local = idx % pSheet
            val col = local % cCols
            val row = local / cCols
            Rect(col * tSize, row * tSize, (col + 1) * tSize, (row + 1) * tSize)
        }

        frameCount = count
        tileSize = tSize
        cols = cCols
        perSheet = pSheet
        avgFrameMs = avgMs
        sheets = decoded
        srcRects = rects
        loaded = true
        Log.i(TAG, "Loaded $count orb frames across ${decoded.size} GPU sheets (${tSize}px)")
    }
}

/**
 * Animated 60fps fluid glass sphere backed by the 188-frame WebP sprite sheets,
 * featuring real-time hue shifting, speed modulation, and bass/voice-reactive pulsing.
 */
class AliceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var framePhase = 0f
    private var totalTime = 0f
    private var rotDeg = 0f
    private var lastNs = 0L
    private var activeHoldUntilNs = 0L
    private var lastBoost = 0f
    private var running = false

    // 0f on Screen 0 hero, 0..1f during morph -> 1f in docked/overlay bar for luminous gem visibility
    @Volatile
    var compactGlowBoost: Float = 0f

    // Music <-> Visualizer smooth S-curve morph progress (0f = sphere, 1f = visualizer)
    private var morphProgress = 0f
    private val visPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val visWavePath = Path()
    private val visParticles = Array(48) { idx ->
        val ang = (idx / 48f) * (2f * Math.PI.toFloat())
        floatArrayOf(ang, 0.45f + (idx % 6) * 0.09f, 0.8f + (idx % 4) * 0.4f, (idx % 7) * 0.8f) // ang, radFraction, speed, phase
    }

    // Smoothed physics state (for liquid morph between idle / music / listening / thinking / speaking)
    private var curSpeed = 0.78f
    private var curScale = 1.0f
    private var curEnergy = 0.08f
    private var curHue = 0f
    private var curSat = 1.05f
    private var curBright = 1.0f
    private var curGlowColor = Color.parseColor("#38BDF8")

    // Preallocated Paint/Matrix/LRU Shader objects (zero per-frame allocations in steady state)
    private val bmpPaint0 = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bmpPaint1 = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstRect = RectF()
    private val auraMatrix = Matrix()
    private val auraStops = floatArrayOf(0f, 0.56f, 1f)
    private var cachedAuraShader: RadialGradient? = null
    private var cachedAuraQKey = 0
    private val auraShaderCache = object : LinkedHashMap<Int, RadialGradient>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RadialGradient>?): Boolean = size > 24
    }

    // Hue/Sat/Bright filter state + bounded LRU cache to avoid per-frame JNI SkColorFilter allocations
    private val cm = ColorMatrix()
    private val satCm = ColorMatrix()
    private val brightArr = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    private val brightCm = ColorMatrix()
    private val hueMatArr = FloatArray(20)
    private var lastFilterKey = Int.MIN_VALUE
    private var cachedFilter: ColorMatrixColorFilter? = null
    private val filterCache = object : LinkedHashMap<Int, ColorMatrixColorFilter>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ColorMatrixColorFilter>?): Boolean = size > 64
    }

    init {
        AliceOrbFrames.preloadAsync(context) { invalidate() }
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
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopAnim() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val boost = compactGlowBoost
        val morphing = abs(boost - lastBoost) > 0.004f
        val st = LiveOrbState.assistantState
        val musicOn = LiveOrbState.musicPlaying || LiveOrbState.musicOverall > 0.045f
        val activeNow = (st != "idle") || musicOn || morphing
        if (activeNow) {
            activeHoldUntilNs = frameTimeNanos + 1_200_000_000L
        }
        val highRate = activeNow || frameTimeNanos < activeHoldUntilNs
        if (!highRate && lastNs != 0L) {
            val minStepNs = if (boost > 0.85f) 52_000_000L else 36_000_000L
            if ((frameTimeNanos - lastNs) < minStepNs) {
                // Calm idle: 1:2 VSync (~25fps) for hero orb, 1:3 (~16.7fps) when docked miniature
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
        }

        lastBoost = boost
        val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) * 1e-9f).coerceIn(0.004f, 0.08f)
        lastNs = frameTimeNanos
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
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh
        val mTot = LiveOrbState.musicOverall

        val tgtSpeed: Float
        val tgtScale: Float
        val tgtEnergy: Float
        val tgtHue: Float
        val tgtSat: Float
        val tgtBright: Float
        val glowHex: Int

        when (st) {
            "connecting", "listening" -> {
                // Electric cyan / aqua shifting into emerald peak as user speaks
                tgtHue = -52f - mic * 34f + sin(totalTime * 2.5f) * 10f
                tgtSpeed = 1.45f + mic * 1.45f
                tgtScale = 1.02f + mic * 0.14f + sin(totalTime * 3.4f) * 0.015f
                tgtEnergy = (0.38f + mic * 0.62f).coerceIn(0f, 1f)
                tgtSat = 1.24f
                tgtBright = 1.08f + mic * 0.34f
                glowHex = if (mic > 0.25f) Color.parseColor("#2DD4BF") else Color.parseColor("#38BDF8")
            }
            "thinking" -> {
                // Fast energetic vortex sweeping violet <-> champagne-gold
                val osc = (sin(totalTime * 4.2f) + 1f) * 0.5f
                tgtHue = 12f + osc * 118f
                tgtSpeed = 2.65f
                tgtScale = 1.02f + sin(totalTime * 8.5f) * 0.035f
                tgtEnergy = 0.62f + 0.22f * osc
                tgtSat = 1.32f
                tgtBright = 1.16f + 0.12f * osc
                glowHex = if (osc > 0.5f) Color.parseColor("#E8C686") else Color.parseColor("#A855F7")
            }
            "speaking" -> {
                // Radiant iridescent cyan <-> violet <-> warm magenta/gold lip-synced to ttsRms
                tgtHue = sin(totalTime * 2.1f) * 68f + tts * 36f
                tgtSpeed = 1.45f + tts * 1.70f
                tgtScale = 1.02f + tts * 0.16f
                tgtEnergy = (0.45f + tts * 0.55f).coerceIn(0f, 1f)
                tgtSat = 1.28f
                tgtBright = 1.10f + tts * 0.38f
                glowHex = if (tts > 0.32f) Color.parseColor("#C084FC") else Color.parseColor("#38BDF8")
            }
            "error" -> {
                tgtHue = 115f // shifts violet/blue sphere toward crimson-rose
                tgtSpeed = 1.15f
                tgtScale = 0.99f + sin(totalTime * 7f) * 0.025f
                tgtEnergy = 0.5f
                tgtSat = 1.25f
                tgtBright = 1.05f
                glowHex = Color.parseColor("#F87171")
            }
            else -> {
                if (musicOn) {
                    // Harmonic iridescent flow cycling through cyan -> violet -> magenta -> champagne gold
                    tgtHue = sin(totalTime * 0.72f + mid * 2.2f) * 85f + high * 55f
                    tgtSpeed = (0.95f + bass * 1.55f + mTot * 0.95f).coerceAtMost(2.9f)
                    tgtScale = 0.99f + bass * 0.145f + mTot * 0.035f
                    tgtEnergy = (0.24f + mTot * 0.76f).coerceIn(0f, 1f)
                    tgtSat = 1.14f + mTot * 0.25f
                    tgtBright = 1.02f + bass * 0.32f + high * 0.10f
                    glowHex = when {
                        bass > 0.58f -> Color.parseColor("#E8C686")
                        high > 0.45f -> Color.parseColor("#38BDF8")
                        else -> Color.parseColor("#818CF8")
                    }
                } else {
                    // Luminous slow ambient iridescent drift
                    tgtHue = sin(totalTime * 0.45f) * 32f
                    tgtSpeed = 0.78f
                    tgtScale = 0.99f + sin(totalTime * 1.6f) * 0.018f
                    tgtEnergy = 0.26f + (sin(totalTime * 1.6f) + 1f) * 0.05f
                    tgtSat = 1.16f
                    tgtBright = 1.12f + sin(totalTime * 1.6f) * 0.05f
                    glowHex = Color.parseColor("#6366F1")
                }
            }
        }

        val boost = compactGlowBoost.coerceIn(0f, 1f)
        val finalBright = tgtBright + boost * 0.24f
        val finalEnergy = (tgtEnergy + boost * 0.24f).coerceIn(0f, 1f)

        val shouldMorph = LiveOrbState.autoMusicVisualizer && musicOn && (st == "idle")
        val tgtMorph = if (shouldMorph) 1f else 0f
        val morphRate = dt * (1f / 0.48f) // 480ms S-curve morph
        if (morphProgress < tgtMorph) {
            morphProgress = (morphProgress + morphRate).coerceAtMost(1f)
        } else if (morphProgress > tgtMorph) {
            morphProgress = (morphProgress - morphRate).coerceAtLeast(0f)
        }

        val kFast = min(1f, dt * 13f)
        val kSmooth = min(1f, dt * 6.5f)
        curSpeed += (tgtSpeed - curSpeed) * kSmooth
        curScale += (tgtScale - curScale) * kFast
        curEnergy += (finalEnergy - curEnergy) * kFast
        curHue += (tgtHue - curHue) * min(1f, dt * 4.5f)
        curSat += (tgtSat - curSat) * kSmooth
        curBright += (finalBright - curBright) * kFast
        curGlowColor = blendColor(curGlowColor, glowHex, min(1f, dt * 5.0f))

        val nFrames = AliceOrbFrames.frameCount
        val nativeFps = 1000f / max(16f, AliceOrbFrames.avgFrameMs)
        framePhase = (framePhase + dt * nativeFps * curSpeed) % nFrames
        if (framePhase < 0f) framePhase += nFrames

        rotDeg = (rotDeg + dt * (5f + curSpeed * 9f)) % 360f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val cx = w * 0.5f
        val cy = h * 0.5f
        val baseDim = min(w, h)
        // Outer aura expands softly around the sphere inside view bounds (sphere itself ~85% of view dim)
        val orbDiameter = baseDim * 0.85f * curScale
        val halfOrb = orbDiameter * 0.5f
        val auraRadius = min(baseDim * 0.50f, halfOrb * (1.16f + curEnergy * 0.14f))

        // 1. Reactive ambient glow behind sphere (unit RadialGradient cached by quantized ARGB + Matrix transform)
        if (auraRadius > 2f) {
            val auraAlphaCore = ((68 + curEnergy * 125).toInt()).coerceIn(48, 195)
            val qA = ((auraAlphaCore + 4) and 0xF8).coerceIn(48, 200)
            val qR = ((Color.red(curGlowColor) + 4) and 0xF8).coerceIn(0, 248)
            val qG = ((Color.green(curGlowColor) + 4) and 0xF8).coerceIn(0, 248)
            val qB = ((Color.blue(curGlowColor) + 4) and 0xF8).coerceIn(0, 248)
            val qKey = (qA shl 24) or (qR shl 16) or (qG shl 8) or qB

            var shader = if (cachedAuraQKey == qKey) cachedAuraShader else null
            if (shader == null) {
                shader = auraShaderCache[qKey]
                if (shader == null) {
                    val cMid = Color.argb((qA * 0.46f).toInt(), qR, qG, qB)
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
            auraMatrix.setScale(auraRadius, auraRadius)
            auraMatrix.postTranslate(cx, cy)
            shader.setLocalMatrix(auraMatrix)
            canvas.drawCircle(cx, cy, auraRadius, auraPaint)
        }

        // 2. Precision BORK x Duo luminescent orbital rim ring + thinking/speaking energy arcs
        val boost = compactGlowBoost.coerceIn(0f, 1f)
        run {
            val ringR = halfOrb * 1.018f
            val ringAlpha = (54 + curEnergy * 130 + boost * 45).toInt().coerceIn(42, 225)
            val rimCol = blendColor(curGlowColor, Color.WHITE, 0.22f + boost * 0.15f)
            ringPaint.color = Color.argb(ringAlpha, Color.red(rimCol), Color.green(rimCol), Color.blue(rimCol))
            ringPaint.strokeWidth = max(2.2f, baseDim * (0.006f + boost * 0.008f))
            canvas.drawCircle(cx, cy, ringR, ringPaint)

            if (LiveOrbState.assistantState == "thinking" || LiveOrbState.assistantState == "speaking") {
                val arcR = halfOrb * 1.055f
                ringPaint.color = Color.argb(
                    min(235, ringAlpha + 40),
                    0xE8, 0xC6, 0x86
                )
                dstRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
                val arcSweep = 72f + curEnergy * 55f
                canvas.drawArc(dstRect, rotDeg * 2.2f, arcSweep, false, ringPaint)
                canvas.drawArc(dstRect, rotDeg * 2.2f + 180f, arcSweep * 0.65f, false, ringPaint)
            }
        }

        // 3. Draw animated sprite sheet frame(s) with bilinear crossfade + dynamic Hue/Sat/Bright
        if (!AliceOrbFrames.loaded || AliceOrbFrames.sheets.isEmpty()) {
            fallbackPaint.color = curGlowColor
            fallbackPaint.alpha = 180
            canvas.drawCircle(cx, cy, halfOrb * 0.88f, fallbackPaint)
            return
        }

        updateColorFilterIfNeeded()

        val nFrames = AliceOrbFrames.frameCount
        val perSheet = AliceOrbFrames.perSheet
        val f0 = floor(framePhase).toInt().coerceIn(0, nFrames - 1)
        val f1 = (f0 + 1) % nFrames
        val blend = (framePhase - f0).coerceIn(0f, 1f)

        // Notice in 200x200 tile the sphere occupies r ~95.5px out of 100px (diameter = 95.5% of tile)
        val drawHalf = halfOrb / 0.955f
        dstRect.set(cx - drawHalf, cy - drawHalf, cx + drawHalf, cy + drawHalf)

        val save = canvas.save()
        canvas.rotate(rotDeg, cx, cy)
        val sphereAlphaScale = ((1f - morphProgress) * 255f).toInt().coerceIn(0, 255)
        if (sphereAlphaScale > 0) {
            val idleSnap = if (LiveOrbState.assistantState == "idle" &&
                !LiveOrbState.musicPlaying &&
                LiveOrbState.musicOverall <= 0.045f) 0.22f else 0.14f
            if (boost > 0.78f || blend < idleSnap || blend > (1f - idleSnap)) {
                // Single-layer fast path (docked miniature orb or dominant keyframe -> halves Mali-G31 fillrate)
                val fi = if (blend >= 0.5f) f1 else f0
                val s = AliceOrbFrames.sheets[fi / perSheet]
                if (s != null && !s.isRecycled) {
                    bmpPaint0.alpha = sphereAlphaScale
                    bmpPaint0.colorFilter = cachedFilter
                    canvas.drawBitmap(s, AliceOrbFrames.srcRects[fi], dstRect, bmpPaint0)
                }
            } else {
                val s0 = AliceOrbFrames.sheets[f0 / perSheet]
                val s1 = AliceOrbFrames.sheets[f1 / perSheet]
                if (s0 != null && !s0.isRecycled) {
                    bmpPaint0.alpha = sphereAlphaScale
                    bmpPaint0.colorFilter = cachedFilter
                    canvas.drawBitmap(s0, AliceOrbFrames.srcRects[f0], dstRect, bmpPaint0)
                }
                if (s1 != null && !s1.isRecycled) {
                    bmpPaint1.alpha = ((blend * sphereAlphaScale)).toInt().coerceIn(0, 255)
                    bmpPaint1.colorFilter = cachedFilter
                    canvas.drawBitmap(s1, AliceOrbFrames.srcRects[f1], dstRect, bmpPaint1)
                }
            }
        }
        canvas.restoreToCount(save)

        // 4. Smooth morphing audio-reactive visualizer (particles / bars / wave / fireflies / aurora / matrix)
        if (morphProgress > 0.01f) {
            drawVisualizer(canvas, cx, cy, halfOrb, (morphProgress * 255f).toInt().coerceIn(0, 255))
        }
    }

    private fun updateColorFilterIfNeeded() {
        val qHue = Math.round(curHue / 3f).coerceIn(-250, 250)
        val qSat = Math.round(curSat / 0.06f).coerceIn(0, 255)
        val qBright = Math.round(curBright / 0.04f).coerceIn(0, 255)
        val key = ((qHue + 512) shl 16) or (qSat shl 8) or qBright
        if (cachedFilter != null && key == lastFilterKey) {
            return
        }
        lastFilterKey = key
        val existing = filterCache[key]
        if (existing != null) {
            cachedFilter = existing
            return
        }

        val hueDeg = qHue * 3f
        val satVal = qSat * 0.06f
        val brightVal = qBright * 0.04f

        // Luminance-preserving RGB hue rotation matrix
        val rad = hueDeg * (PI.toFloat() / 180f)
        val cosV = cos(rad)
        val sinV = sin(rad)
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f

        hueMatArr[0] = lumR + cosV * (1f - lumR) + sinV * (-lumR)
        hueMatArr[1] = lumG + cosV * (-lumG) + sinV * (-lumG)
        hueMatArr[2] = lumB + cosV * (-lumB) + sinV * (1f - lumB)
        hueMatArr[3] = 0f; hueMatArr[4] = 0f

        hueMatArr[5] = lumR + cosV * (-lumR) + sinV * 0.143f
        hueMatArr[6] = lumG + cosV * (1f - lumG) + sinV * 0.140f
        hueMatArr[7] = lumB + cosV * (-lumB) + sinV * (-0.283f)
        hueMatArr[8] = 0f; hueMatArr[9] = 0f

        hueMatArr[10] = lumR + cosV * (-lumR) + sinV * (-(1f - lumR))
        hueMatArr[11] = lumG + cosV * (-lumG) + sinV * lumG
        hueMatArr[12] = lumB + cosV * (1f - lumB) + sinV * lumB
        hueMatArr[13] = 0f; hueMatArr[14] = 0f

        hueMatArr[15] = 0f; hueMatArr[16] = 0f; hueMatArr[17] = 0f; hueMatArr[18] = 1f; hueMatArr[19] = 0f

        cm.set(hueMatArr)
        satCm.setSaturation(satVal)
        cm.postConcat(satCm)

        brightArr[0] = brightVal
        brightArr[6] = brightVal
        brightArr[12] = brightVal
        brightCm.set(brightArr)
        cm.postConcat(brightCm)

        val created = ColorMatrixColorFilter(cm)
        filterCache[key] = created
        cachedFilter = created
    }

    private fun drawVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        if (alpha <= 0) return
        when (LiveOrbState.visualizerStyle) {
            "bars" -> drawBarsVisualizer(canvas, cx, cy, radius, alpha)
            "wave" -> drawWaveVisualizer(canvas, cx, cy, radius, alpha)
            "fireflies" -> drawFirefliesVisualizer(canvas, cx, cy, radius, alpha)
            "aurora" -> drawAuroraVisualizer(canvas, cx, cy, radius, alpha)
            "matrix" -> drawMatrixVisualizer(canvas, cx, cy, radius, alpha)
            else -> drawParticlesVisualizer(canvas, cx, cy, radius, alpha) // "particles"
        }
    }

    private fun drawParticlesVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh
        val mTot = LiveOrbState.musicOverall

        visPaint.style = Paint.Style.FILL

        // Center pulsating core
        val coreR = radius * (0.28f + bass * 0.22f)
        visPaint.color = Color.parseColor("#38BDF8")
        visPaint.alpha = ((alpha * (0.35f + bass * 0.45f))).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, coreR, visPaint)

        // Orbital particle cloud
        for (i in visParticles.indices) {
            val p = visParticles[i]
            val ang = p[0] + totalTime * p[2] * (0.45f + mid * 0.8f)
            val radBase = radius * p[1] * (0.85f + bass * 0.45f)
            val wobble = sin(ang * 3f + totalTime * 2.5f) * radius * 0.08f
            val px = cx + cos(ang) * (radBase + wobble)
            val py = cy + sin(ang) * (radBase + wobble)

            val pSize = max(3f, radius * 0.024f * (0.8f + (i % 3) * 0.5f + high * 1.2f))
            val pCol = when (i % 3) {
                0 -> Color.parseColor("#00D4FF") // Cyan
                1 -> Color.parseColor("#A855F7") // Violet
                else -> Color.parseColor("#FACC15") // Champagne
            }
            visPaint.color = pCol
            visPaint.alpha = alpha
            canvas.drawCircle(px, py, pSize, visPaint)
        }
    }

    private fun drawBarsVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeCap = Paint.Cap.ROUND
        visPaint.strokeWidth = max(3f, radius * 0.045f)

        val bands = LiveOrbState.rawBands
        val n = 24
        val rInner = radius * 0.40f
        for (i in 0 until n) {
            val ang = Math.toRadians((i * (360.0 / n) - 90.0))
            val valB = if (i < bands.size) bands[i] else 0f
            val rOuter = rInner + valB * radius * 0.65f + radius * 0.04f

            val x0 = cx + cos(ang).toFloat() * rInner
            val y0 = cy + sin(ang).toFloat() * rInner
            val x1 = cx + cos(ang).toFloat() * rOuter
            val y1 = cy + sin(ang).toFloat() * rOuter

            visPaint.color = blendColor(Color.parseColor("#00D4FF"), Color.parseColor("#EC4899"), i / 24f)
            visPaint.alpha = alpha
            canvas.drawLine(x0, y0, x1, y1, visPaint)
        }
    }

    private fun drawWaveVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeWidth = max(2.5f, radius * 0.035f)

        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh

        for (w in 0..2) {
            visWavePath.reset()
            val amp = radius * (0.12f + (if (w == 0) bass else if (w == 1) mid else high) * 0.28f)
            val freq = 2f + w * 1.5f
            val phase = totalTime * (2f + w * 1.2f)
            val yBase = cy + (w - 1) * radius * 0.16f

            val steps = 30
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = cx - radius * 0.85f + t * radius * 1.7f
                val env = sin(t * Math.PI.toFloat()) // Window envelope
                val y = yBase + sin(t * freq * Math.PI.toFloat() * 2f + phase) * amp * env
                if (i == 0) visWavePath.moveTo(x, y) else visWavePath.lineTo(x, y)
            }

            visPaint.color = when (w) {
                0 -> Color.parseColor("#00D4FF")
                1 -> Color.parseColor("#818CF8")
                else -> Color.parseColor("#C084FC")
            }
            visPaint.alpha = alpha
            canvas.drawPath(visWavePath, visPaint)
        }
    }

    private fun drawFirefliesVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        visPaint.style = Paint.Style.FILL
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid

        for (i in 0 until 32) {
            val seed = (i * 137.5f)
            val ang = Math.toRadians((seed + totalTime * 18f * (0.8f + (i % 3) * 0.3f)).toDouble())
            val r = radius * (0.25f + ((i * 31) % 65) / 100f + bass * 0.25f)
            val px = cx + cos(ang).toFloat() * r
            val py = cy + sin(ang).toFloat() * r

            val pulse = (sin(totalTime * 4f + i) + 1f) * 0.5f
            val pSize = max(3f, radius * (0.018f + pulse * 0.035f * (1f + mid)))

            visPaint.color = if (i % 2 == 0) Color.parseColor("#FACC15") else Color.parseColor("#38BDF8")
            visPaint.alpha = ((alpha * (0.4f + pulse * 0.6f))).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, pSize, visPaint)
        }
    }

    private fun drawAuroraVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeWidth = max(5f, radius * 0.08f)
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh

        for (a in 0..1) {
            visWavePath.reset()
            val steps = 24
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val ang = Math.toRadians((t * 360.0 - 90.0))
                val waveR = radius * (0.6f + sin(t * 8f + totalTime * 2.2f + a * 1.5f) * (0.08f + mid * 0.14f))
                val x = cx + cos(ang).toFloat() * waveR
                val y = cy + sin(ang).toFloat() * waveR
                if (i == 0) visWavePath.moveTo(x, y) else visWavePath.lineTo(x, y)
            }
            visWavePath.close()

            visPaint.color = if (a == 0) Color.parseColor("#10B981") else Color.parseColor("#8B5CF6")
            visPaint.alpha = ((alpha * 0.7f)).toInt().coerceIn(0, 255)
            canvas.drawPath(visWavePath, visPaint)
        }
    }

    private fun drawMatrixVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeCap = Paint.Cap.ROUND
        visPaint.strokeWidth = max(2.5f, radius * 0.03f)

        val energy = LiveOrbState.musicOverall
        val cols = 12
        val colWidth = (radius * 1.6f) / cols
        for (c in 0 until cols) {
            val x = cx - radius * 0.8f + c * colWidth + colWidth * 0.5f
            val speed = 80f + (c % 4) * 45f
            val yHead = (cy - radius * 0.8f + (totalTime * speed + c * 38f) % (radius * 1.6f))
            val trailLen = radius * (0.25f + energy * 0.35f)

            visPaint.color = Color.parseColor("#22C55E") // Matrix green
            visPaint.alpha = alpha
            canvas.drawLine(x, yHead - trailLen, x, yHead, visPaint)

            // Bright head dot
            visPaint.style = Paint.Style.FILL
            visPaint.color = Color.parseColor("#86EFAC")
            canvas.drawCircle(x, yHead, max(3f, radius * 0.024f), visPaint)
            visPaint.style = Paint.Style.STROKE
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
}
