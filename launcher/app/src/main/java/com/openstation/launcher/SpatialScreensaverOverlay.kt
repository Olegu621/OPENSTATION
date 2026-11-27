package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
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
import kotlin.math.max

/**
 * Full-screen Living Spatial Screensaver (triggered cleanly on DPAD_UP from Home):
 * - Hardware-decoded 1080p60 video with a static overlay for clock and music.
 * - Massive display clock ("на всё окно") with tabular date & live weather summary.
 * - Monolithic floating Liquid Glass Now Playing dock with album cover, title, artist, and progress.
 */
class SpatialScreensaverOverlay(
    private val context: Context,
    private val onDismissRequested: () -> Unit
) {
    val rootOverlay: FrameLayout
    var isOpen: Boolean = false
        private set

    private lateinit var videoBackground: VideoBackdropView
    private lateinit var fadeCurtain: View
    private lateinit var tvBigClock: TextView
    private lateinit var tvBigDateWeather: TextView
    private lateinit var musicCard: LinearLayout
    private lateinit var ivMusicCover: ImageView
    private lateinit var tvMusicBadge: TextView
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var pbMusic: ProgressBar
    private lateinit var tvMusicTime: TextView

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

        videoBackground = VideoBackdropView(context).apply {
            visibility = View.GONE
        }
        root.addView(videoBackground, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        fadeCurtain = View(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            alpha = 1f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(fadeCurtain)

        videoBackground.onFirstFrameReady = {
            if (isOpen && fadeCurtain.visibility == View.VISIBLE) {
                fadeCurtain.animate()
                    .alpha(0f)
                    .setDuration(MotionPrefs.dur(context, 340L))
                    .setInterpolator(MotionPrefs.easeOutCurve)
                    .withEndAction {
                        if (isOpen) fadeCurtain.visibility = View.GONE
                    }
                    .start()
            }
        }

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

        AnimatedSpriteHelper.setExclusiveVisibleRoot(rootOverlay)
        rootOverlay.animate().cancel()
        fadeCurtain.animate().cancel()
        fadeCurtain.visibility = View.GONE
        fadeCurtain.alpha = 1f
        videoBackground.visibility = View.GONE
        rootOverlay.setBackgroundColor(Color.BLACK)
        rootOverlay.visibility = View.VISIBLE
        rootOverlay.alpha = 0f
        rootOverlay.scaleX = 1.035f
        rootOverlay.scaleY = 1.035f
        rootOverlay.translationY = -dp(28).toFloat()
        rootOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(MotionPrefs.dur(context, 280L))
            .setInterpolator(MotionPrefs.easeOutCurve)
            .withEndAction {
                if (isOpen) {
                    fadeCurtain.alpha = 1f
                    fadeCurtain.visibility = View.VISIBLE
                    rootOverlay.setBackgroundColor(Color.TRANSPARENT)
                    videoBackground.setRunning(true)
                    AnimatedSpriteHelper.syncAll()
                    fadeCurtain.postDelayed({
                        if (isOpen && fadeCurtain.visibility == View.VISIBLE) {
                            videoBackground.onFirstFrameReady?.invoke()
                        }
                    }, 450L)
                }
            }
            .start()

        rootOverlay.post {
            if (isOpen) rootOverlay.requestFocus()
        }
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen && rootOverlay.visibility != View.VISIBLE) return
        isOpen = false
        AnimatedSpriteHelper.setExclusiveVisibleRoot(null)
        rootOverlay.animate().cancel()
        fadeCurtain.animate().cancel()
        fadeCurtain.visibility = View.GONE
        videoBackground.setRunning(false)

        if (immediate) {
            rootOverlay.visibility = View.GONE
            rootOverlay.alpha = 0f
            rootOverlay.translationY = 0f
            rootOverlay.scaleX = 1f
            rootOverlay.scaleY = 1f
            return
        }

        rootOverlay.setBackgroundColor(Color.parseColor("#E002050D"))
        rootOverlay.animate()
            .alpha(0f)
            .scaleX(1.03f)
            .scaleY(1.03f)
            .translationY(-dp(24).toFloat())
            .setDuration(MotionPrefs.dur(context, 280L))
            .setInterpolator(MotionPrefs.easeOutCurve)
            .withEndAction {
                if (!isOpen) {
                    rootOverlay.visibility = View.GONE
                    rootOverlay.setBackgroundColor(Color.BLACK)
                    rootOverlay.translationY = 0f
                    rootOverlay.scaleX = 1f
                    rootOverlay.scaleY = 1f
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
        if (isOpen) {
            videoBackground.setRunning(true)
        }
    }

    fun onHostPause() {
        videoBackground.setRunning(false)
    }

    fun release() {
        videoBackground.setRunning(false)
        isOpen = false
        rootOverlay.animate().cancel()
    }

    private fun formatMs(ms: Long): String {
        val tot = max(0L, ms / 1000L)
        return "%d:%02d".format(tot / 60L, tot % 60L)
    }

}
