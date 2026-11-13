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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
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
    @Volatile var visualizerStyle: String = "dots"
    @Volatile var autoMusicVisualizer: Boolean = true
    @Volatile var previewUntilMs: Long = 0L
    @Volatile private var styleSyncedOnce: Boolean = false

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
    private val visWavePathBottom = Path()
    private val visGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Specterr Reference Visualizer Engine & Smooth Spectrum Buffers (zero per-frame allocations)
    private val smoothedBands = FloatArray(24)
    private val specterrBars = 64
    private val specterrHeights = FloatArray(specterrBars)
    private val specterrPeaks = FloatArray(specterrBars)
    private val specterrPeakVel = FloatArray(specterrBars)
    private val halftoneBackingShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Color.argb(232, 3, 5, 10),
            Color.argb(214, 4, 6, 12),
            Color.argb(145, 5, 8, 15),
            Color.argb(38, 6, 10, 18),
            Color.TRANSPARENT
        ),
        floatArrayOf(0f, 0.56f, 0.78f, 0.92f, 1f),
        Shader.TileMode.CLAMP
    )
    private val halftoneBackingMatrix = Matrix()
    private val halftoneBackingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = halftoneBackingShader
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val warpParticles = Array(64) { idx ->
        // [0] = angle, [1] = distance (0.22..0.96), [2] = speed, [3] = size, [4] = color index
        val ang = (idx / 64f) * (2f * Math.PI.toFloat())
        val dist = 0.22f + (idx % 8) * 0.09f
        val spd = 0.36f + (idx % 5) * 0.16f
        val sz = 2.0f + (idx % 4) * 1.6f
        floatArrayOf(ang, dist, spd, sz, (idx % 4).toFloat())
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

        val nowMs = System.currentTimeMillis()
        val isPreview = (nowMs < LiveOrbState.previewUntilMs)
        val shouldMorph = (LiveOrbState.autoMusicVisualizer && musicOn && (st == "idle")) || isPreview
        if (isPreview && !musicOn) {
            val phase = (nowMs % 2800L) / 2800f * (2f * PI.toFloat())
            LiveOrbState.musicBass = (0.55f + 0.35f * sin(phase)).coerceIn(0f, 1f)
            LiveOrbState.musicMid = (0.40f + 0.30f * cos(phase * 1.4f)).coerceIn(0f, 1f)
            LiveOrbState.musicHigh = (0.30f + 0.25f * sin(phase * 2.2f)).coerceIn(0f, 1f)
            LiveOrbState.musicOverall = (LiveOrbState.musicBass + LiveOrbState.musicMid) * 0.5f
        }
        val tgtMorph = if (shouldMorph) 1f else 0f
        val morphRate = dt * (1f / 0.48f) // 480ms S-curve morph
        if (morphProgress < tgtMorph) {
            morphProgress = (morphProgress + morphRate).coerceAtMost(1f)
        } else if (morphProgress > tgtMorph) {
            morphProgress = (morphProgress - morphRate).coerceAtLeast(0f)
        }

        // Smoothed 24-band spectrum + Specterr 64-bar gravity physics (supports live music & preview)
        if (morphProgress > 0.005f || musicOn || isPreview) {
            val raw = LiveOrbState.rawBands
            val rawLen = raw.size
            val synthMode = isPreview && !musicOn
            for (b in 0 until 24) {
                val targetBand = if (synthMode) {
                    val wave1 = 0.5f + 0.5f * sin(totalTime * 3.1f + b * 0.42f)
                    val wave2 = 0.5f + 0.5f * cos(totalTime * 1.9f - b * 0.65f)
                    val bassWeight = max(0f, 1f - b / 10f) * 0.25f
                    (0.18f + wave1 * 0.45f + wave2 * 0.28f + bassWeight).coerceIn(0f, 1f)
                } else if (b < rawLen) {
                    raw[b].coerceIn(0f, 1f)
                } else {
                    0f
                }
                val prevB = smoothedBands[b]
                val coeff = if (targetBand > prevB) min(1f, dt * 20f) else min(1f, dt * 8.5f)
                smoothedBands[b] = (prevB + (targetBand - prevB) * coeff).coerceIn(0f, 1f)
            }

            val half = specterrBars / 2
            for (i in 0 until specterrBars) {
                // Symmetrical spectrum layout: low bass at center bottom, spreading to highs at top
                val distFromCenter = if (i < half) (half - 1 - i) else (i - half)
                val bandIdx = ((distFromCenter * 24) / half).coerceIn(0, 23)
                val rawVal = smoothedBands[bandIdx]
                val target = rawVal * (0.95f + bass * 0.55f + mTot * 0.22f)

                // Instant attack on beat, smooth exponential decay
                if (target > specterrHeights[i]) {
                    specterrHeights[i] = target.coerceAtMost(1.35f)
                } else {
                    specterrHeights[i] = (specterrHeights[i] - dt * 2.4f).coerceAtLeast(0.015f)
                }

                // Floating peak indicator caps with gravity
                if (specterrHeights[i] > specterrPeaks[i]) {
                    specterrPeaks[i] = specterrHeights[i]
                    specterrPeakVel[i] = 0.36f
                } else {
                    specterrPeakVel[i] -= dt * 2.5f
                    specterrPeaks[i] = (specterrPeaks[i] + specterrPeakVel[i] * dt).coerceAtLeast(specterrHeights[i])
                }
            }

            // Bounded Starfield Warp Particles (staying cleanly inside 0.96f circular margin)
            val warpSpdMult = (0.55f + bass * 2.6f + mTot * 1.2f)
            for (i in warpParticles.indices) {
                val p = warpParticles[i]
                p[1] += p[2] * warpSpdMult * dt * 0.65f
                if (p[1] > 0.96f) {
                    p[1] = 0.22f + (i % 6) * 0.03f
                    p[0] = (i * 137.5f) * (Math.PI.toFloat() / 180f) + totalTime * 0.25f
                }
            }
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

        // 2. Active-state luminescent energy arcs only (no static grey wireframe ring when idle)
        val boost = compactGlowBoost.coerceIn(0f, 1f)
        if (LiveOrbState.assistantState == "thinking" || LiveOrbState.assistantState == "speaking") {
            val ringAlpha = (72 + curEnergy * 135 + boost * 40).toInt().coerceIn(48, 235)
            val rimCol = blendColor(curGlowColor, Color.WHITE, 0.35f)
            ringPaint.strokeWidth = max(2.0f, baseDim * (0.0055f + boost * 0.007f))
            val arcR = halfOrb * 1.048f
            ringPaint.color = Color.argb(
                min(235, ringAlpha),
                Color.red(rimCol),
                Color.green(rimCol),
                Color.blue(rimCol)
            )
            dstRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
            val arcSweep = 72f + curEnergy * 55f
            canvas.drawArc(dstRect, rotDeg * 2.2f, arcSweep, false, ringPaint)
            canvas.drawArc(dstRect, rotDeg * 2.2f + 180f, arcSweep * 0.65f, false, ringPaint)
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

        // 4. Smooth morphing audio-reactive visualizer bounded strictly within circular view envelope
        if (morphProgress > 0.01f) {
            val safeVisR = min(halfOrb * 1.02f, baseDim * 0.455f)
            drawVisualizer(canvas, cx, cy, safeVisR, (morphProgress * 255f).toInt().coerceIn(0, 255))
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
            "specterr" -> drawSpecterrCircleVisualizer(canvas, cx, cy, radius, alpha)
            "nexus", "anomaly" -> drawNexusVisualizer(canvas, cx, cy, radius, alpha)
            "dots", "particles" -> drawHalftoneSphereVisualizer(canvas, cx, cy, radius, alpha)
            else -> drawHalftoneSphereVisualizer(canvas, cx, cy, radius, alpha)
        }
    }

    /**
     * Signature 3D Halftone Dotted Sphere Audio Visualizer:
     * - Monolith Obsidian translucent core backing fading seamlessly with zero hard border or square clip
     * - Spherical 3D dome lattice (25x25 core) with perspective depth taper & subtle 3D gyroscopic precession
     * - Acoustic FFT bands displace points radially along surface normals and swell harmonic crest sparkles
     * - Soft fading outer ambient halftone dot halo bounded strictly inside circular safe perimeter
     */
    private fun drawHalftoneSphereVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh
        val mTot = LiveOrbState.musicOverall
        val boost = compactGlowBoost.coerceIn(0f, 1f)
        val isMini = boost > 0.42f

        // Strict circular sizing so even outer halo + bass swell never touches the View box edges
        val sphereR = radius * if (isMini) (0.86f + bass * 0.05f) else (0.785f + bass * 0.035f)
        val alphaF = alpha / 255f

        // 1. Soft Obsidian depth scrim behind dots (eliminates noisy wallpaper contrast cleanly with zero edge outline)
        val bgR = sphereR * if (isMini) 1.08f else 1.24f
        halftoneBackingMatrix.reset()
        halftoneBackingMatrix.setScale(bgR, bgR)
        halftoneBackingMatrix.postTranslate(cx, cy)
        halftoneBackingShader.setLocalMatrix(halftoneBackingMatrix)
        halftoneBackingPaint.alpha = (alphaF * 238f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, bgR, halftoneBackingPaint)

        // 2. Subtle 3D gyroscopic tilt angles (smooth continuous living hologram)
        val rotY = sin(totalTime * 0.48f) * 0.095f + (mid - bass) * 0.04f
        val rotX = cos(totalTime * 0.37f) * 0.072f + (high - mid) * 0.03f
        val cosY = cos(rotY)
        val sinY = sin(rotY)
        val cosX = cos(rotX)
        val sinX = sin(rotX)

        val baseDotR = if (isMini) max(1.25f, sphereR * 0.042f) else max(1.6f, sphereR * 0.0165f)
        val ripplePhase = totalTime * 5.0f

        val uxArr = HalftoneLattice.ux
        val uyArr = HalftoneLattice.uy
        val uzArr = HalftoneLattice.uz
        val rArr = HalftoneLattice.rNorm
        val insideArr = HalftoneLattice.inside
        val bandArr = HalftoneLattice.bandIdx
        val seedArr = HalftoneLattice.seedPhase
        val sparkleArr = HalftoneLattice.sparkleMask
        val miniKeepArr = HalftoneLattice.miniKeep
        val count = HalftoneLattice.count

        // Pass A: Faint outer halftone field (skipped when docked to 56dp miniature)
        if (!isMini) {
            for (n in 0 until count) {
                if (insideArr[n]) continue
                val ux = uxArr[n]
                val uy = uyArr[n]
                val outerFade = uzArr[n] // 0..1 fade from rim outward to 1.23R
                val bandE = smoothedBands[bandArr[n]]

                // Subtle parallax shift synced softly with sphere tilt
                val disp = 1f + bandE * 0.025f + bass * 0.015f
                val px = cx + (ux * disp + sinY * 0.025f) * sphereR
                val py = cy + (uy * disp - sinX * 0.025f) * sphereR

                val a = (alphaF * outerFade * (26f + bass * 26f + bandE * 28f)).toInt().coerceIn(0, 255)
                if (a <= 2) continue
                dotPaint.color = Color.argb(a, 218, 226, 238)
                canvas.drawCircle(px, py, baseDotR * (0.48f + bandE * 0.24f), dotPaint)
            }
        }

        // Pass B: Crisp 3D Spherical Surface Dots
        for (n in 0 until count) {
            if (!insideArr[n]) continue
            if (isMini && !miniKeepArr[n]) continue

            val ux0 = uxArr[n]
            val uy0 = uyArr[n]
            val uz0 = uzArr[n]

            // Rotate normal in 3D space
            val x1 = ux0 * cosY + uz0 * sinY
            val z1 = -ux0 * sinY + uz0 * cosY
            val y2 = uy0 * cosX - z1 * sinX
            val z2 = uy0 * sinX + z1 * cosX
            if (z2 <= -0.02f) continue

            val depth = z2.coerceIn(0f, 1f)
            val depthVis = ((z2 + 0.02f) / 0.14f).coerceIn(0f, 1f)

            val bandIdx = bandArr[n]
            val bandNext = (bandIdx + 1) % 24
            val bandE = smoothedBands[bandIdx] * 0.72f + smoothedBands[bandNext] * 0.28f
            val rN = rArr[n]
            val wave = 0.5f + 0.5f * sin(rN * 6.0f - ripplePhase + seedArr[n])

            // Radial extrusion along surface normal + gentle harmonic wave
            val radialBoost = 1.0f + bandE * 0.072f + wave * (0.010f + mTot * 0.028f)
            // Subtle perspective bulge matches user's reference dome curvature
            val persp = 1.12f / (1.18f - depth * 0.10f)
            val px = cx + x1 * radialBoost * persp * sphereR
            val py = cy + y2 * radialBoost * persp * sphereR

            // Crisp foreshortening toward circular limb
            val limbTaper = 0.34f + 0.66f * sqrt(depth)
            val sparkle = sparkleArr[n]
            val melodicGlow = if (sparkle > 0f) sparkle * (0.42f + mTot * 0.58f) else 0f
            val energy = (bandE * 0.85f + wave * (0.14f + mid * 0.32f) + melodicGlow).coerceIn(0f, 1.45f)

            val crestSwell = if (energy > 0.44f) {
                1.0f + ((energy - 0.44f) * 1.45f).coerceAtMost(1.38f)
            } else {
                1.0f + energy * 0.22f
            }

            val dotR = baseDotR * limbTaper * crestSwell
            val coreAlpha = (alphaF * depthVis * (88f + 167f * depth.pow(0.45f))).toInt().coerceIn(0, 255)
            if (coreAlpha <= 3) continue

            // Optional soft crisp luminance bloom behind beat crests (clean OLED light feel)
            if (!isMini && crestSwell > 1.38f && depth > 0.18f) {
                val glowA = (coreAlpha * 0.24f * ((crestSwell - 1.38f) / 1.0f).coerceIn(0f, 1f)).toInt().coerceIn(0, 120)
                if (glowA > 4) {
                    dotGlowPaint.color = if (high > 0.48f && sparkle > 0f) {
                        Color.argb(glowA, 224, 242, 254)
                    } else {
                        Color.argb(glowA, 255, 255, 255)
                    }
                    canvas.drawCircle(px, py, dotR * 2.1f, dotGlowPaint)
                }
            }

            dotPaint.color = Color.argb(coreAlpha, 255, 255, 255)
            canvas.drawCircle(px, py, dotR, dotPaint)
        }
    }

    /**
     * Refined Obsidian × Sapphire × Champagne Corona Equalizer (zero square clip, zero flat black cutout)
     */
    private fun drawSpecterrCircleVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val bass = LiveOrbState.musicBass
        val high = LiveOrbState.musicHigh
        val mTot = LiveOrbState.musicOverall
        val boost = compactGlowBoost.coerceIn(0f, 1f)
        val alphaF = alpha / 255f

        val coreR = radius * (0.34f + bass * 0.048f + mTot * 0.02f)
        val maxBarReach = radius * 0.44f // Compact cohesive crown with comfortable breathing margin around card & box edges

        // 1. Soft Obsidian glass core + ambient Sapphire/Champagne bloom
        val bgR = radius * 0.98f
        halftoneBackingMatrix.reset()
        halftoneBackingMatrix.setScale(bgR, bgR)
        halftoneBackingMatrix.postTranslate(cx, cy)
        halftoneBackingShader.setLocalMatrix(halftoneBackingMatrix)
        halftoneBackingPaint.alpha = (alphaF * 185f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, bgR, halftoneBackingPaint)

        // 2. Bounded ambient orbital stardust inside circular perimeter (no edge chopping)
        if (boost < 0.45f) {
            visPaint.style = Paint.Style.FILL
            for (i in warpParticles.indices) {
                val p = warpParticles[i]
                val ang = p[0]
                val distFrac = p[1].coerceIn(0.24f, 0.96f)
                val px = cx + cos(ang) * (distFrac * radius)
                val py = cy + sin(ang) * (distFrac * radius)
                val fade = sin(((distFrac - 0.22f) / 0.74f).coerceIn(0f, 1f) * PI.toFloat()).coerceIn(0f, 1f)
                val pAlpha = (alphaF * fade * (90f + bass * 110f)).toInt().coerceIn(0, 255)
                val pSize = max(1.4f, radius * 0.011f * (0.6f + fade * 0.7f))
                visPaint.color = when (p[4].toInt()) {
                    0 -> Color.parseColor("#38BDF8")
                    1 -> Color.parseColor("#818CF8")
                    2 -> Color.parseColor("#E8C686")
                    else -> Color.parseColor("#F8FAFC")
                }
                visPaint.alpha = pAlpha
                canvas.drawCircle(px, py, pSize, visPaint)
            }
        }

        // 3. 64 Crisp Fine Symmetrical Corona Rays (dual-pass bloom + razor-fine core)
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeCap = Paint.Cap.ROUND
        visGlowPaint.style = Paint.Style.STROKE
        visGlowPaint.strokeCap = Paint.Cap.ROUND
        val barWidth = max(1.5f, radius * 0.012f)
        visPaint.strokeWidth = barWidth
        visGlowPaint.strokeWidth = barWidth * 2.5f

        val half = specterrBars / 2
        val rIn = coreR + radius * 0.025f
        for (i in 0 until specterrBars) {
            val ang = (i.toFloat() / specterrBars) * (2f * PI.toFloat()) - (PI.toFloat() / 2f)
            val normH = (specterrHeights[i] / 1.35f).coerceIn(0f, 1f)
            val h = normH * maxBarReach * 0.92f + radius * 0.025f
            val rOut = min(radius * 0.96f, rIn + h)

            val x0 = cx + cos(ang) * rIn
            val y0 = cy + sin(ang) * rIn
            val x1 = cx + cos(ang) * rOut
            val y1 = cy + sin(ang) * rOut

            val tColor = abs(i - half).toFloat() / half
            val col = blendMultiColor(tColor)
            visGlowPaint.color = col
            visGlowPaint.alpha = (alphaF * 48f).toInt().coerceIn(0, 255)
            canvas.drawLine(x0, y0, x1, y1, visGlowPaint)

            visPaint.color = col
            visPaint.alpha = alpha
            canvas.drawLine(x0, y0, x1, y1, visPaint)

            // Subtle floating peak bead strictly inside 0.97R
            if (boost < 0.45f) {
                val peakFrac = (specterrPeaks[i] / 1.35f).coerceIn(0f, 1f)
                val rPeak = min(radius * 0.975f, rIn + peakFrac * maxBarReach * 0.92f + radius * 0.042f)
                val px = cx + cos(ang) * rPeak
                val py = cy + sin(ang) * rPeak
                visPaint.style = Paint.Style.FILL
                visPaint.color = Color.parseColor("#F8FAFC")
                visPaint.alpha = (alphaF * 220f).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, max(1.3f, radius * 0.009f), visPaint)
                visPaint.style = Paint.Style.STROKE
            }
        }

        // 4. Translucent Luminous Core Sphere (replaces dead flat cutout)
        val coreGlow = Color.argb((alphaF * (70f + bass * 85f)).toInt().coerceIn(0, 255), 56, 189, 248)
        visPaint.style = Paint.Style.FILL
        visPaint.color = Color.argb((alphaF * 180f).toInt().coerceIn(0, 255), 11, 18, 34)
        canvas.drawCircle(cx, cy, coreR, visPaint)
        visPaint.color = coreGlow
        canvas.drawCircle(cx, cy, coreR * 0.74f, visPaint)

        visPaint.style = Paint.Style.STROKE
        visPaint.strokeWidth = max(1.6f, radius * 0.013f)
        visPaint.color = Color.parseColor("#38BDF8")
        visPaint.alpha = (alphaF * (175f + bass * 80f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, coreR, visPaint)

        visPaint.strokeWidth = max(1.0f, radius * 0.008f)
        visPaint.color = Color.parseColor("#E8C686")
        visPaint.alpha = (alphaF * (145f + high * 90f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, coreR * 0.80f, visPaint)
    }

    private fun drawBarsVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        drawSpecterrCircleVisualizer(canvas, cx, cy, radius, alpha)
    }

    private fun drawWaveVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh
        val mTot = LiveOrbState.musicOverall
        val alphaF = alpha / 255f
        val safeR = radius * 0.90f

        val bgR = radius * 0.98f
        halftoneBackingMatrix.reset()
        halftoneBackingMatrix.setScale(bgR, bgR)
        halftoneBackingMatrix.postTranslate(cx, cy)
        halftoneBackingShader.setLocalMatrix(halftoneBackingMatrix)
        halftoneBackingPaint.alpha = (alphaF * 185f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, bgR, halftoneBackingPaint)

        // Ambient particles strictly clipped inside sphere envelope
        visPaint.style = Paint.Style.FILL
        for (i in 0 until 24) {
            val t = (totalTime * (0.18f + (i % 4) * 0.06f) + i / 24f) % 1f
            val xFrac = (t * 2f - 1f)
            val env = sqrt(max(0f, 1f - xFrac * xFrac))
            val px = cx + xFrac * safeR * 0.92f
            val py = cy + sin(t * PI.toFloat() * 2f + i) * safeR * 0.42f * env
            val pSize = max(1.6f, radius * (0.010f + (i % 3) * 0.007f + high * 0.012f))
            visPaint.color = if (i % 2 == 0) Color.parseColor("#38BDF8") else Color.parseColor("#E8C686")
            visPaint.alpha = (alphaF * env * 170f).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, pSize, visPaint)
        }

        // Smooth circular-envelope dual harmonic waves
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeCap = Paint.Cap.ROUND
        visGlowPaint.style = Paint.Style.STROKE
        visGlowPaint.strokeCap = Paint.Cap.ROUND

        for (layer in 0..1) {
            visWavePath.reset()
            visWavePathBottom.reset()
            val stroke = if (layer == 0) max(2.2f, radius * 0.018f) else max(1.4f, radius * 0.011f)
            visPaint.strokeWidth = stroke
            visGlowPaint.strokeWidth = stroke * 2.6f

            val amp = safeR * (0.14f + (if (layer == 0) bass else mid) * 0.28f)
            val freq = 2.4f + layer * 1.1f
            val phase = totalTime * (2.6f + layer * 1.4f)

            val steps = 40
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val xFrac = (t * 2f - 1f)
                val x = cx + xFrac * safeR * 0.94f
                val env = sin(t * PI.toFloat()).pow(1.25f)
                val band = smoothedBands[((t * 23f).toInt()).coerceIn(0, 23)]
                val yOffset = abs(sin(t * freq * PI.toFloat() * 2f + phase) * amp * (0.65f + band * 0.65f)) * env
                val yTop = cy - yOffset
                val yBottom = cy + yOffset

                if (i == 0) {
                    visWavePath.moveTo(x, yTop)
                    visWavePathBottom.moveTo(x, yBottom)
                } else {
                    visWavePath.lineTo(x, yTop)
                    visWavePathBottom.lineTo(x, yBottom)
                }
            }

            val topCol = if (layer == 0) Color.parseColor("#38BDF8") else Color.parseColor("#818CF8")
            val botCol = if (layer == 0) Color.parseColor("#E8C686") else Color.parseColor("#F8FAFC")
            visGlowPaint.color = topCol
            visGlowPaint.alpha = (alphaF * 55f).toInt().coerceIn(0, 255)
            canvas.drawPath(visWavePath, visGlowPaint)
            visPaint.color = topCol
            visPaint.alpha = alpha
            canvas.drawPath(visWavePath, visPaint)

            visGlowPaint.color = botCol
            visGlowPaint.alpha = (alphaF * 48f).toInt().coerceIn(0, 255)
            canvas.drawPath(visWavePathBottom, visGlowPaint)
            visPaint.color = botCol
            visPaint.alpha = (alphaF * 225f).toInt().coerceIn(0, 255)
            canvas.drawPath(visWavePathBottom, visPaint)
        }

        // Delicate horizon ring
        visPaint.strokeWidth = max(1f, radius * 0.008f)
        visPaint.color = Color.argb((alphaF * (60f + mTot * 70f)).toInt().coerceIn(0, 255), 232, 198, 134)
        canvas.drawCircle(cx, cy, safeR * 0.96f, visPaint)
    }

    private fun drawFirefliesVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        visPaint.style = Paint.Style.FILL
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val alphaF = alpha / 255f

        for (i in 0 until 36) {
            val seed = (i * 137.5f)
            val ang = Math.toRadians((seed + totalTime * 18f * (0.8f + (i % 3) * 0.25f)).toDouble())
            val rFrac = (0.20f + ((i * 31) % 62) / 100f + bass * 0.14f).coerceAtMost(0.92f)
            val r = radius * rFrac
            val px = cx + cos(ang).toFloat() * r
            val py = cy + sin(ang).toFloat() * r

            val pulse = (sin(totalTime * 3.8f + i) + 1f) * 0.5f
            val pSize = max(2.2f, radius * (0.014f + pulse * 0.024f * (1f + mid)))

            val col = if (i % 2 == 0) Color.parseColor("#E8C686") else Color.parseColor("#38BDF8")
            visPaint.color = col
            visPaint.alpha = (alphaF * (42f + pulse * 48f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, pSize * 2.1f, visPaint)
            visPaint.alpha = (alphaF * (110f + pulse * 145f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, pSize, visPaint)
        }
    }

    private fun drawAuroraVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val mid = LiveOrbState.musicMid
        val high = LiveOrbState.musicHigh
        val bass = LiveOrbState.musicBass
        val alphaF = alpha / 255f
        visPaint.style = Paint.Style.STROKE
        visPaint.strokeWidth = max(3.5f, radius * (0.048f + high * 0.025f))
        visGlowPaint.style = Paint.Style.STROKE
        visGlowPaint.strokeWidth = visPaint.strokeWidth * 2.4f

        for (a in 0..2) {
            visWavePath.reset()
            val steps = 36
            val baseF = 0.58f + a * 0.11f
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val ang = Math.toRadians((t * 360.0 - 90.0))
                val mod = sin(t * (6f + a * 2f) + totalTime * (2.0f + a * 0.5f) + a * 1.4f) * (0.06f + mid * 0.10f)
                val waveR = radius * (baseF + mod + bass * 0.05f).coerceAtMost(0.92f)
                val x = cx + cos(ang).toFloat() * waveR
                val y = cy + sin(ang).toFloat() * waveR
                if (i == 0) visWavePath.moveTo(x, y) else visWavePath.lineTo(x, y)
            }
            visWavePath.close()

            val col = when (a) {
                0 -> Color.parseColor("#2DD4BF")
                1 -> Color.parseColor("#38BDF8")
                else -> Color.parseColor("#E8C686")
            }
            visGlowPaint.color = col
            visGlowPaint.alpha = (alphaF * 48f).toInt().coerceIn(0, 255)
            canvas.drawPath(visWavePath, visGlowPaint)
            visPaint.color = col
            visPaint.alpha = (alphaF * 185f).toInt().coerceIn(0, 255)
            canvas.drawPath(visWavePath, visPaint)
        }
    }

    private fun drawMatrixVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val alphaF = alpha / 255f
        val safeR = radius * 0.88f
        val bgR = radius * 0.98f
        halftoneBackingMatrix.reset()
        halftoneBackingMatrix.setScale(bgR, bgR)
        halftoneBackingMatrix.postTranslate(cx, cy)
        halftoneBackingShader.setLocalMatrix(halftoneBackingMatrix)
        halftoneBackingPaint.alpha = (alphaF * 200f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, bgR, halftoneBackingPaint)

        val save = canvas.save()
        visWavePath.reset()
        visWavePath.addCircle(cx, cy, safeR, Path.Direction.CW)
        canvas.clipPath(visWavePath)

        visPaint.style = Paint.Style.STROKE
        visPaint.strokeCap = Paint.Cap.ROUND
        visPaint.strokeWidth = max(2f, radius * 0.022f)

        val energy = LiveOrbState.musicOverall
        val cols = 12
        val span = safeR * 1.85f
        val colWidth = span / cols
        for (c in 0 until cols) {
            val x = cx - span * 0.5f + c * colWidth + colWidth * 0.5f
            val speed = radius * (0.45f + (c % 4) * 0.22f)
            val yHead = cy - safeR + ((totalTime * speed + c * safeR * 0.35f) % (safeR * 2.1f))
            val trailLen = safeR * (0.28f + energy * 0.35f)

            visPaint.color = Color.parseColor("#34D399")
            visPaint.alpha = (alphaF * 210f).toInt().coerceIn(0, 255)
            canvas.drawLine(x, yHead - trailLen, x, yHead, visPaint)

            visPaint.style = Paint.Style.FILL
            visPaint.color = Color.parseColor("#ECFDF5")
            canvas.drawCircle(x, yHead, max(2.2f, radius * 0.020f), visPaint)
            visPaint.style = Paint.Style.STROKE
        }
        canvas.restoreToCount(save)
    }

    private fun blendColor(c0: Int, c1: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        val r = (Color.red(c0) * inv + Color.red(c1) * k).toInt()
        val g = (Color.green(c0) * inv + Color.green(c1) * k).toInt()
        val b = (Color.blue(c0) * inv + Color.blue(c1) * k).toInt()
        return Color.rgb(r, g, b)
    }

    private fun blendMultiColor(t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return when {
            k < 0.35f -> blendColor(Color.parseColor("#38BDF8"), Color.parseColor("#818CF8"), k / 0.35f)
            k < 0.72f -> blendColor(Color.parseColor("#818CF8"), Color.parseColor("#E8C686"), (k - 0.35f) / 0.37f)
            else -> blendColor(Color.parseColor("#E8C686"), Color.parseColor("#FDE68A"), (k - 0.72f) / 0.28f)
        }
    }

    /**
     * Obsidian Titanium Geodesic 3D Visualizer (strictly bounded inside circular perimeter):
     * - Clean Sapphire & Champagne Gold 3D rotating wireframe icosahedron with FFT vertex relief
     * - Precision concentric gyroscopic reticles & radar scanner sweep
     * - Bounded ambient particles (never clipped against square frame)
     */
    private var nexusRotX = 0f
    private var nexusRotY = 0f
    private var nexusRotZ = 0f
    private var nexusScannerAngle = 0f
    private val nexusProjectedX = FloatArray(12)
    private val nexusProjectedY = FloatArray(12)
    private val nexusProjectedZ = FloatArray(12)

    private fun drawNexusVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val bass = LiveOrbState.musicBass
        val mid = LiveOrbState.musicMid
        val energy = LiveOrbState.musicOverall
        val alphaF = alpha / 255f

        nexusRotX += 0.010f + energy * 0.030f
        nexusRotY += 0.016f + bass * 0.048f
        nexusRotZ += 0.007f + mid * 0.022f
        nexusScannerAngle = (nexusScannerAngle + 0.032f + energy * 0.045f) % (2f * PI.toFloat())

        val cosX = cos(nexusRotX); val sinX = sin(nexusRotX)
        val cosY = cos(nexusRotY); val sinY = sin(nexusRotY)
        val cosZ = cos(nexusRotZ); val sinZ = sin(nexusRotZ)

        visPaint.style = Paint.Style.STROKE
        visPaint.strokeCap = Paint.Cap.ROUND

        val outerR = radius * (0.86f + bass * 0.045f)
        visPaint.color = Color.parseColor("#38BDF8")
        visPaint.strokeWidth = max(1.6f, radius * 0.014f)
        visPaint.alpha = (alphaF * 165f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outerR, visPaint)

        val innerR = radius * (0.60f + mid * 0.035f)
        visPaint.color = Color.parseColor("#E8C686")
        visPaint.strokeWidth = max(1.2f, radius * 0.010f)
        visPaint.alpha = (alphaF * 115f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, innerR, visPaint)

        val coreR = radius * (0.34f + bass * 0.06f)
        visPaint.strokeWidth = max(1.4f, radius * 0.012f)
        visPaint.alpha = (alphaF * 140f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, coreR, visPaint)

        // 8 Radar tick marks strictly bounded within 0.94R
        visPaint.color = Color.parseColor("#38BDF8")
        visPaint.strokeWidth = max(1.8f, radius * 0.016f)
        visPaint.alpha = (alphaF * 200f).toInt().coerceIn(0, 255)
        val tickLen = radius * 0.055f
        for (i in 0 until 8) {
            val a = (i * PI / 4.0).toFloat()
            val r0 = outerR - tickLen * 0.5f
            val r1 = outerR + tickLen * 0.5f
            canvas.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r1, cy + sin(a) * r1, visPaint)
        }

        // Sweep beam
        val beamX = cx + cos(nexusScannerAngle) * outerR
        val beamY = cy + sin(nexusScannerAngle) * outerR
        visPaint.strokeWidth = max(1.8f, radius * 0.018f)
        visPaint.color = Color.parseColor("#E8C686")
        visPaint.alpha = (alphaF * (170f + energy * 85f)).toInt().coerceIn(0, 255)
        canvas.drawLine(cx, cy, beamX, beamY, visPaint)

        for (t in 1..4) {
            val ta = nexusScannerAngle - t * 0.06f
            visPaint.strokeWidth = max(1f, radius * 0.010f)
            visPaint.alpha = (alphaF * (75f / t)).toInt().coerceIn(0, 255)
            canvas.drawLine(cx, cy, cx + cos(ta) * outerR, cy + sin(ta) * outerR, visPaint)
        }

        // 3D Geodesic projection (max vertex displacement bounded inside outerR)
        val sphereBaseR = radius * 0.48f
        for (i in 0 until 12) {
            val v = ICO_VERTS[i]
            val freqDisp = smoothedBands[(i * 3) % 24] * 0.52f + bass * 0.40f
            val r = sphereBaseR * (1.0f + freqDisp * 0.42f)

            val vx = v[0] * r; val vy = v[1] * r; val vz = v[2] * r
            val y1 = vy * cosX - vz * sinX; val z1 = vy * sinX + vz * cosX
            val x2 = vx * cosY + z1 * sinY; val z2 = -vx * sinY + z1 * cosY
            val x3 = x2 * cosZ - y1 * sinZ; val y3 = x2 * sinZ + y1 * cosZ

            val fov = radius * 4.0f
            val scale = fov / (fov + z2)
            nexusProjectedX[i] = cx + x3 * scale
            nexusProjectedY[i] = cy + y3 * scale
            nexusProjectedZ[i] = z2
        }

        visPaint.strokeWidth = max(1.5f, radius * 0.014f)
        for (edge in ICO_EDGES) {
            val u = edge[0]; val v = edge[1]
            val avgZ = (nexusProjectedZ[u] + nexusProjectedZ[v]) * 0.5f
            val zNorm = ((avgZ / (sphereBaseR * 1.4f)) + 1.0f) * 0.5f
            visPaint.color = blendColor(Color.parseColor("#38BDF8"), Color.parseColor("#E8C686"), zNorm)
            visPaint.alpha = (alphaF * (95f + zNorm * 150f)).toInt().coerceIn(0, 255)
            canvas.drawLine(nexusProjectedX[u], nexusProjectedY[u], nexusProjectedX[v], nexusProjectedY[v], visPaint)
        }

        visPaint.style = Paint.Style.FILL
        for (i in 0 until 12) {
            val zNorm = ((nexusProjectedZ[i] / (sphereBaseR * 1.4f)) + 1.0f) * 0.5f
            val nodeR = max(2.2f, radius * 0.018f) * (0.7f + zNorm * 0.5f + bass * 0.25f)
            visPaint.color = Color.parseColor("#38BDF8")
            visPaint.alpha = (alphaF * (90f + zNorm * 110f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(nexusProjectedX[i], nexusProjectedY[i], nodeR * 1.55f, visPaint)
            visPaint.color = Color.WHITE
            visPaint.alpha = (alphaF * (180f + zNorm * 75f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(nexusProjectedX[i], nexusProjectedY[i], nodeR, visPaint)
        }

        if (compactGlowBoost < 0.45f) {
            for (p in warpParticles) {
                val ang = p[0]
                val distFrac = p[1].coerceIn(0.22f, 0.94f)
                val px = cx + cos(ang) * (distFrac * radius)
                val py = cy + sin(ang) * (distFrac * radius)
                val pNorm = (distFrac / 0.94f).coerceIn(0f, 1f)
                visPaint.color = if (p[4].toInt() % 2 == 0) Color.parseColor("#38BDF8") else Color.parseColor("#E8C686")
                visPaint.alpha = (alphaF * sin(pNorm * PI.toFloat()) * 175f).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, max(1.4f, radius * 0.010f), visPaint)
            }
        }
    }

    private object HalftoneLattice {
        val count: Int
        val ux: FloatArray
        val uy: FloatArray
        val uz: FloatArray
        val rNorm: FloatArray
        val inside: BooleanArray
        val bandIdx: IntArray
        val seedPhase: FloatArray
        val sparkleMask: FloatArray
        val miniKeep: BooleanArray

        init {
            val halfRange = 15
            val stepDiv = 12.2f // Equator spans -12..+12 (25 dots across core diameter)
            val maxCap = (halfRange * 2 + 1) * (halfRange * 2 + 1)
            val tUx = FloatArray(maxCap)
            val tUy = FloatArray(maxCap)
            val tUz = FloatArray(maxCap)
            val tR = FloatArray(maxCap)
            val tIn = BooleanArray(maxCap)
            val tBand = IntArray(maxCap)
            val tSeed = FloatArray(maxCap)
            val tSparkle = FloatArray(maxCap)
            val tMini = BooleanArray(maxCap)

            var idx = 0
            for (j in -halfRange..halfRange) {
                for (i in -halfRange..halfRange) {
                    val gx = i / stepDiv
                    val gy = j / stepDiv
                    val r = hypot(gx, gy)
                    if (r > 1.23f) continue

                    val ang = atan2(gy, gx)
                    val bandRaw = (((ang + PI.toFloat()) / (2f * PI.toFloat())) * 24f + r * 6f).toInt()
                    val band = ((bandRaw % 24) + 24) % 24

                    if (r <= 1.0f) {
                        val theta = r * (PI.toFloat() * 0.5f)
                        if (r > 1e-4f) {
                            val sinT = sin(theta)
                            tUx[idx] = sinT * (gx / r)
                            tUy[idx] = sinT * (gy / r)
                        } else {
                            tUx[idx] = 0f
                            tUy[idx] = 0f
                        }
                        tUz[idx] = cos(theta)
                        tIn[idx] = true
                    } else {
                        // Gentle outer lensing continuation matching halftone field reference
                        val lensR = 1.0f + (r - 1.0f) * 1.06f
                        val scale = if (r > 1e-4f) lensR / r else 1f
                        tUx[idx] = gx * scale
                        tUy[idx] = gy * scale
                        tUz[idx] = ((1.23f - r) / 0.23f).coerceIn(0f, 1f).pow(1.75f)
                        tIn[idx] = false
                    }

                    tR[idx] = r
                    tBand[idx] = band
                    tSeed[idx] = ((i * 73 + j * 139) and 0xFF) * 0.02454f
                    val h = ((i * 374761 + j * 668265) xor (i * j))
                    tSparkle[idx] = if ((abs(h) % 23) == 0) 0.55f else if ((abs(h) % 41) == 0) 0.85f else 0f
                    tMini[idx] = (abs(i) % 2 == 0) && (abs(j) % 2 == 0)
                    idx++
                }
            }
            count = idx
            ux = tUx.copyOf(idx)
            uy = tUy.copyOf(idx)
            uz = tUz.copyOf(idx)
            rNorm = tR.copyOf(idx)
            inside = tIn.copyOf(idx)
            bandIdx = tBand.copyOf(idx)
            seedPhase = tSeed.copyOf(idx)
            sparkleMask = tSparkle.copyOf(idx)
            miniKeep = tMini.copyOf(idx)
        }
    }

    companion object {
        // Pre-computed normalized icosahedron coordinates: NORM = 1 / sqrt(1 + PHI^2) = 0.5257311f, PHI * NORM = 0.8506508f
        private val ICO_VERTS = arrayOf(
            floatArrayOf(-0.5257311f,  0.8506508f,  0.0f),
            floatArrayOf( 0.5257311f,  0.8506508f,  0.0f),
            floatArrayOf(-0.5257311f, -0.8506508f,  0.0f),
            floatArrayOf( 0.5257311f, -0.8506508f,  0.0f),
            floatArrayOf( 0.0f, -0.5257311f,  0.8506508f),
            floatArrayOf( 0.0f,  0.5257311f,  0.8506508f),
            floatArrayOf( 0.0f, -0.5257311f, -0.8506508f),
            floatArrayOf( 0.0f,  0.5257311f, -0.8506508f),
            floatArrayOf( 0.8506508f,  0.0f, -0.5257311f),
            floatArrayOf( 0.8506508f,  0.0f,  0.5257311f),
            floatArrayOf(-0.8506508f,  0.0f, -0.5257311f),
            floatArrayOf(-0.8506508f,  0.0f,  0.5257311f)
        )
        // 30 unique edges of an icosahedron connecting pairs with dist ~ 1.05
        private val ICO_EDGES = arrayOf(
            intArrayOf(0, 11), intArrayOf(0, 5), intArrayOf(0, 1), intArrayOf(0, 7), intArrayOf(0, 10),
            intArrayOf(1, 5), intArrayOf(1, 9), intArrayOf(1, 8), intArrayOf(1, 7),
            intArrayOf(2, 11), intArrayOf(2, 4), intArrayOf(2, 3), intArrayOf(2, 6), intArrayOf(2, 10),
            intArrayOf(3, 4), intArrayOf(3, 9), intArrayOf(3, 8), intArrayOf(3, 6),
            intArrayOf(4, 5), intArrayOf(4, 9), intArrayOf(4, 11),
            intArrayOf(5, 9), intArrayOf(5, 11),
            intArrayOf(6, 7), intArrayOf(6, 8), intArrayOf(6, 10),
            intArrayOf(7, 8), intArrayOf(7, 10),
            intArrayOf(8, 9), intArrayOf(10, 11)
        )
    }
}
