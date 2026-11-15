package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen Living Spatial Screensaver (triggered cleanly on DPAD_UP from Home):
 * - Curated 1080p panoramic scenes with continuous 60fps Ken Burns parallax & smooth crossfades.
 * - Subtle living starlight / warm dust motes drifting across atmospheric vignette lighting.
 * - Massive display clock ("на всё окно") with tabular date & live weather summary.
 * - Monolithic floating Liquid Glass Now Playing dock with album cover, title, artist, and progress.
 */
class SpatialScreensaverOverlay(
    private val context: Context,
    private val onDismissRequested: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private val photoPool = intArrayOf(
        R.drawable.saver_aurora,
        R.drawable.saver_dolomites,
        R.drawable.saver_clouds_sunrise,
        R.drawable.saver_duo_milkyway,
        R.drawable.saver_bioluminescent,
        R.drawable.wall_nebula_desert,
        R.drawable.wall_nebula_forest,
        R.drawable.saver_clouds_twilight,
        R.drawable.wall_nebula_space,
        R.drawable.wall_nebula_field
    )

    val rootOverlay: FrameLayout
    var isOpen: Boolean = false
        private set

    private lateinit var imgBack: ImageView
    private lateinit var imgFront: ImageView
    private lateinit var particlesView: AmbientMotesView
    private lateinit var tvBigClock: TextView
    private lateinit var tvBigDateWeather: TextView
    private lateinit var musicCard: LinearLayout
    private lateinit var ivMusicCover: ImageView
    private lateinit var tvMusicBadge: TextView
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var pbMusic: ProgressBar
    private lateinit var tvMusicTime: TextView

    private var backBmp: Bitmap? = null
    private var frontBmp: Bitmap? = null
    private var curIdx = 0
    private var slideStartNs = 0L
    private var crossfadeActive = false
    private var crossfadeStartNs = 0L
    private var animRunning = false
    private var loadingNext = false
    private var loadingInitial = false
    private var slideGeneration = 0L

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animRunning || !isOpen) return
            stepKenBurns(frameTimeNanos)
            handler.removeCallbacks(stepRunnable)
            val delayMs = if (crossfadeActive) 24L else 42L
            handler.postDelayed(stepRunnable, delayMs)
        }
    }
    private val stepRunnable = Runnable {
        if (animRunning && isOpen) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    init {
        rootOverlay = buildUi()
    }

    private fun dp(v: Int): Int = SpatialUiKit.dp(context, v)

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(context).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(38).toFloat()
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            SpatialUiKit.disableSystemHighlight(this)
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                if (isOpen) onDismissRequested()
            }
        }

        imgBack = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        imgFront = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(imgBack)
        root.addView(imgFront)

        // Soft filmic vignette (top keeps clock crisp, bottom supports Now Playing card, center stays luminous)
        val vignette = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#7802050D"),
                    Color.parseColor("#1A030610"),
                    Color.parseColor("#0D030610"),
                    Color.parseColor("#8802050D")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(vignette)

        particlesView = AmbientMotesView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(particlesView)

        // Hero Oversized Display Clock + Date/Weather (top-center showcase)
        val clockBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply {
                topMargin = dp(44)
            }
        }
        tvBigClock = TextView(context).apply {
            text = "--:--"
            setTextColor(Color.parseColor("#FAFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 142f)
            typeface = YandexFonts.displayBold(context)
            fontFeatureSettings = "tnum"
            letterSpacing = -0.03f
            includeFontPadding = false
            setShadowLayer(dp(18).toFloat(), 0f, dp(4).toFloat(), Color.parseColor("#90000000"))
        }
        tvBigDateWeather = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#E6E2E8F0"))
            textSize = 19f
            typeface = YandexFonts.displayMedium(context)
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setShadowLayer(dp(10).toFloat(), 0f, dp(2).toFloat(), Color.parseColor("#90000000"))
            setPadding(dp(14), dp(4), dp(14), 0)
        }
        clockBox.addView(tvBigClock)
        clockBox.addView(tvBigDateWeather)
        root.addView(clockBox)

        // Monolithic Floating Liquid Glass Now Playing Card (bottom-center)
        musicCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#780A1120"), Color.parseColor("#54070B16"))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.parseColor("#58FFFFFF"))
            }
            elevation = dp(16).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                dp(600),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(38)
            }
        }

        val coverWrap = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                marginEnd = dp(16)
            }
        }
        ivMusicCover = ImageView(context).apply {
            setSpriteDrawable(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val coverRim = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        coverWrap.addView(ivMusicCover)
        coverWrap.addView(coverRim)
        musicCard.addView(coverWrap)

        val metaCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvMusicBadge = TextView(context).apply {
            text = "●  ЯНДЕКС МУЗЫКА"
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 10.5f
            typeface = YandexFonts.displayBold(context)
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvMusicTime = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            fontFeatureSettings = "tnum"
        }
        badgeRow.addView(tvMusicBadge)
        badgeRow.addView(tvMusicTime)

        tvMusicTitle = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 19f
            typeface = YandexFonts.displayBold(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(0, dp(2), 0, dp(1))
        }
        tvMusicArtist = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13.5f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(8))
        }
        pbMusic = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = SpatialUiKit.makeFrostedProgressDrawable(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        }

        metaCol.addView(badgeRow)
        metaCol.addView(tvMusicTitle)
        metaCol.addView(tvMusicArtist)
        metaCol.addView(pbMusic)
        musicCard.addView(metaCol)
        root.addView(musicCard)

        val exitCue = TextView(context).apply {
            text = "Нажмите любую клавишу чтобы вернуться"
            setTextColor(Color.parseColor("#7AFFFFFF"))
            textSize = 11.5f
            typeface = YandexFonts.displayMedium(context)
            letterSpacing = 0.04f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(11)
            }
        }
        root.addView(exitCue)
        return root
    }

    fun open() {
        if (isOpen) return
        isOpen = true

        curIdx = ((System.currentTimeMillis() / 17000L) % photoPool.size).toInt().coerceIn(0, photoPool.lastIndex)
        slideStartNs = System.nanoTime()
        crossfadeActive = false
        imgFront.alpha = 0f
        val generation = ++slideGeneration
        loadingNext = false
        loadingInitial = true
        if (backBmp == null) imgBack.setImageBitmap(LiquidGlassKit.sharpWallpaper)
        loadSlideIntoBack(photoPool[curIdx], generation)

        rootOverlay.animate().cancel()
        rootOverlay.visibility = View.VISIBLE
        rootOverlay.alpha = 0f
        rootOverlay.scaleX = 1.042f
        rootOverlay.scaleY = 1.042f
        rootOverlay.translationY = -dp(36).toFloat()
        rootOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(MotionPrefs.dur(context, 520L))
            .setInterpolator(MotionPrefs.easeOutCurve)
            .withEndAction {
                if (isOpen) AnimatedSpriteHelper.syncAll()
            }
            .start()

        startMotion()
        rootOverlay.post {
            if (isOpen) rootOverlay.requestFocus()
        }
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen && rootOverlay.visibility != View.VISIBLE) return
        isOpen = false
        ++slideGeneration
        loadingInitial = false
        loadingNext = false
        stopMotion()
        rootOverlay.animate().cancel()

        if (immediate) {
            rootOverlay.visibility = View.GONE
            rootOverlay.alpha = 0f
            rootOverlay.translationY = 0f
            rootOverlay.scaleX = 1f
            rootOverlay.scaleY = 1f
            releaseBitmaps()
            return
        }

        rootOverlay.animate()
            .alpha(0f)
            .scaleX(1.035f)
            .scaleY(1.035f)
            .translationY(-dp(30).toFloat())
            .setDuration(MotionPrefs.dur(context, 420L))
            .setInterpolator(MotionPrefs.easeOutCurve)
            .withEndAction {
                if (!isOpen) {
                    rootOverlay.visibility = View.GONE
                    rootOverlay.translationY = 0f
                    rootOverlay.scaleX = 1f
                    rootOverlay.scaleY = 1f
                    releaseBitmaps()
                }
            }
            .start()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isOpen) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            onDismissRequested()
        }
        return true
    }

    fun updateClockAndWeather(timeText: String, dateText: String, weatherSummary: String?) {
        tvBigClock.text = timeText
        tvBigDateWeather.text = if (!weatherSummary.isNullOrBlank()) {
            "$dateText  ·  $weatherSummary"
        } else {
            dateText
        }
    }

    fun updateMusic(snap: YmPlayerSnapshot, coverBmp: Bitmap?) {
        val tr = snap.currentTrack
        if (tr == null) {
            musicCard.visibility = View.GONE
            return
        }
        musicCard.visibility = View.VISIBLE
        val stateLabel = when {
            snap.buffering -> "ЗАГРУЗКА"
            snap.playing -> "СЕЙЧАС ИГРАЕТ"
            else -> "НА ПАУЗЕ"
        }
        val dotColor = if (snap.playing) Color.parseColor("#86EFAC") else Color.parseColor("#E8C686")
        tvMusicBadge.text = "●  $stateLabel · ${snap.sourceLabel.uppercase()}"
        tvMusicBadge.setTextColor(dotColor)
        tvMusicTitle.text = tr.title.ifBlank { "Без названия" }
        tvMusicArtist.text = if (tr.album.isNotBlank()) "${tr.artists} · ${tr.album}" else tr.artists

        val dur = max(0L, snap.durationMs)
        val pos = max(0L, snap.positionMs).coerceAtMost(max(1L, dur))
        pbMusic.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
        tvMusicTime.text = "${formatMs(pos)} / ${formatMs(dur)}"

        if (coverBmp != null && !coverBmp.isRecycled) {
            ivMusicCover.setImageBitmap(coverBmp)
        } else {
            ivMusicCover.setSpriteDrawable(R.drawable.sprite_ym_emblem)
        }
    }

    fun onHostResume() {
        if (isOpen) startMotion()
    }

    fun onHostPause() {
        stopMotion()
    }

    private fun startMotion() {
        if (animRunning) return
        animRunning = true
        handler.removeCallbacks(stepRunnable)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopMotion() {
        animRunning = false
        handler.removeCallbacks(stepRunnable)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun stepKenBurns(nowNs: Long) {
        val elapsedSec = (nowNs - slideStartNs) / 1_000_000_000f
        val cycleSec = 14.0f
        val dirSign = if (curIdx % 2 == 0) 1f else -1f
        val panProgress = min(1f, elapsedSec / (cycleSec + 2.0f))
        val smooth = (0.5f - 0.5f * cos(panProgress * PI.toFloat()))

        val backScale = if (dirSign > 0f) 1.03f + 0.09f * smooth else 1.12f - 0.09f * smooth
        val backTx = dirSign * (-dp(22) + dp(44) * smooth)
        val backTy = (if ((curIdx / 2) % 2 == 0) 1f else -1f) * (-dp(12) + dp(24) * smooth)
        imgBack.scaleX = backScale
        imgBack.scaleY = backScale
        imgBack.translationX = backTx
        imgBack.translationY = backTy

        if (elapsedSec >= 12.2f && !crossfadeActive && !loadingNext && !loadingInitial) {
            preloadNextSlide(nowNs)
        }

        if (crossfadeActive) {
            val fadeDur = 1.8f
            val ft = min(1f, (nowNs - crossfadeStartNs) / (fadeDur * 1_000_000_000f))
            val alphaCurve = ft * ft * (3f - 2f * ft)
            val nextDir = if ((curIdx + 1) % 2 == 0) 1f else -1f
            val fScale = if (nextDir > 0f) 1.03f + 0.02f * ft else 1.12f - 0.02f * ft
            imgFront.scaleX = fScale
            imgFront.scaleY = fScale
            imgFront.translationX = nextDir * (-dp(22) + dp(8) * ft)
            imgFront.translationY = (if (((curIdx + 1) / 2) % 2 == 0) 1f else -1f) * (-dp(12) + dp(4) * ft)
            imgFront.alpha = alphaCurve

            if (ft >= 1f) {
                crossfadeActive = false
                curIdx = (curIdx + 1) % photoPool.size
                slideStartNs = nowNs - 1_500_000_000L
                val oldBack = backBmp
                backBmp = frontBmp
                frontBmp = null
                imgBack.setImageBitmap(backBmp)
                imgBack.scaleX = imgFront.scaleX
                imgBack.scaleY = imgFront.scaleY
                imgBack.translationX = imgFront.translationX
                imgBack.translationY = imgFront.translationY
                imgFront.alpha = 0f
                imgFront.setImageDrawable(null)
                if (oldBack != null && !oldBack.isRecycled) oldBack.recycle()
            }
        }

        particlesView.stepAndInvalidate(nowNs)
    }

    private fun decodeResSampled(resId: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeResource(context.resources, resId, opts)
    } catch (_: Throwable) {
        null
    }

    private fun loadSlideIntoBack(resId: Int, generation: Long) {
        thread(isDaemon = true) {
            val decoded = decodeResSampled(resId)
            handler.post {
                if (!isOpen || generation != slideGeneration) {
                    decoded?.recycle()
                    return@post
                }
                loadingInitial = false
                slideStartNs = System.nanoTime()
                if (decoded != null) {
                    val old = backBmp
                    backBmp = decoded
                    imgBack.setImageBitmap(decoded)
                    if (old != null && old !== decoded && !old.isRecycled) old.recycle()
                }
            }
        }
    }

    private fun preloadNextSlide(nowNs: Long) {
        loadingNext = true
        val nextIdx = (curIdx + 1) % photoPool.size
        val nextRes = photoPool[nextIdx]
        val generation = slideGeneration
        thread(isDaemon = true) {
            val decoded = decodeResSampled(nextRes)
            handler.post {
                if (!isOpen || generation != slideGeneration) {
                    decoded?.recycle()
                    return@post
                }
                loadingNext = false
                if (decoded == null) return@post
                val oldFront = frontBmp
                frontBmp = decoded
                imgFront.setImageBitmap(decoded)
                imgFront.alpha = 0f
                crossfadeStartNs = max(nowNs, System.nanoTime())
                crossfadeActive = true
                if (oldFront != null && oldFront !== decoded && !oldFront.isRecycled) oldFront.recycle()
            }
        }
    }

    fun release() {
        isOpen = false
        ++slideGeneration
        loadingInitial = false
        loadingNext = false
        stopMotion()
        rootOverlay.animate().cancel()
        releaseBitmaps()
    }

    private fun releaseBitmaps() {
        imgBack.setImageDrawable(null)
        imgFront.setImageDrawable(null)
        backBmp?.let { if (!it.isRecycled) it.recycle() }
        frontBmp?.let { if (!it.isRecycled) it.recycle() }
        backBmp = null
        frontBmp = null
    }

    private fun formatMs(ms: Long): String {
        val tot = max(0L, ms / 1000L)
        return "%d:%02d".format(tot / 60L, tot % 60L)
    }

    /**
     * Ultra-lightweight drifting bokeh starlight overlay (zero per-frame allocations).
     */
    private class AmbientMotesView(context: Context) : View(context) {
        private val motesX = FloatArray(30)
        private val motesY = FloatArray(30)
        private val motesSpeed = FloatArray(30)
        private val motesSize = FloatArray(30)
        private val motesPhase = FloatArray(30)
        private var lastNs = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        init {
            for (i in motesX.indices) {
                val seed = (i * 2654435761L).toInt()
                motesX[i] = ((seed ushr 4) and 0x3FF) / 1024f
                motesY[i] = ((seed ushr 14) and 0x3FF) / 1024f
                motesSpeed[i] = 0.008f + ((i % 7) * 0.0022f)
                motesSize[i] = 1.6f + (i % 5) * 0.85f
                motesPhase[i] = (i * 0.9f)
            }
        }

        fun stepAndInvalidate(nowNs: Long) {
            if (lastNs == 0L) lastNs = nowNs
            val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.12f)
            lastNs = nowNs
            for (i in motesY.indices) {
                motesY[i] -= motesSpeed[i] * dt
                motesX[i] += sin(motesPhase[i] + nowNs * 1.2e-10f) * 0.002f * dt
                if (motesY[i] < -0.04f) motesY[i] = 1.04f
                if (motesX[i] < -0.04f) motesX[i] = 1.04f
                if (motesX[i] > 1.04f) motesX[i] = -0.04f
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val t = lastNs * 1e-9f
            for (i in motesX.indices) {
                val pulse = 0.45f + 0.55f * sin(t * 0.85f + motesPhase[i])
                val a = (28 + 38 * pulse).toInt().coerceIn(8, 72)
                paint.color = if (i % 3 == 0) Color.argb(a, 186, 230, 253) else Color.argb(a, 253, 230, 138)
                canvas.drawCircle(motesX[i] * w, motesY[i] * h, motesSize[i], paint)
            }
        }
    }
}
