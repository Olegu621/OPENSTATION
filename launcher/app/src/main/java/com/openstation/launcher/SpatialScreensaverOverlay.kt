package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen Spatial Cinema Screensaver for OpenStation Max TV:
 * - Curated 5-world scenic gallery (Aurora Fjord, Bioluminescent Cove, Golden Dolomites,
 *   1080p60 Mist Forest video, and Music-Reactive Pulsar Nebula) with silky Ken Burns camera
 *   motion, smooth cross-dissolves, and auto-rotation or instant DPAD Left/Right switching.
 * - Real-time 60fps Choreographer atmospheric canvas (aurora waves, stardust, fireflies,
 *   and orbital audio harmonics tuned to LiveOrbState).
 * - Editorial asymmetric HUD with OLED burn-in drift protection: giant tabular HH:mm + :ss
 *   timepiece, frosted Date + Live Weather capsules (bottom-left), and adaptive-tinted
 *   Liquid Glass Now Playing dock with live 18-bar spectrum & album aura (bottom-right).
 */
class SpatialScreensaverOverlay(
    private val context: Context,
    private val onDismissRequested: () -> Unit
) {
    companion object {
        private const val PREFS_UI = "openstation_ui"
        private const val AUTO_CYCLE_MS = 90_000L
    }

    val rootOverlay: FrameLayout
    var isOpen: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE) }
    private val secFmt = SimpleDateFormat("ss", Locale("ru", "RU"))
    private val hmFmt = SimpleDateFormat("HH:mm", Locale("ru", "RU"))
    private val fullDateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru", "RU"))

    private lateinit var solidBackdrop: View
    private lateinit var videoBackground: VideoBackdropView
    private lateinit var fadeCurtain: View
    private lateinit var ivPhotoA: ImageView
    private lateinit var ivPhotoB: ImageView
    private lateinit var atmosphereView: ScreensaverAtmosphereView

    private lateinit var hudContainer: FrameLayout
    private lateinit var scenePillBox: LinearLayout
    private lateinit var tvSceneTitle: TextView
    private lateinit var tvSceneSub: TextView
    private val sceneDots = ArrayList<View>()
    private lateinit var assistantPill: LinearLayout
    private lateinit var tvAssistantPill: TextView

    private lateinit var clockCluster: LinearLayout
    private lateinit var tvBigClock: TextView
    private lateinit var tvClockSec: TextView
    private lateinit var tvDateBadge: TextView
    private lateinit var weatherPill: TextView

    private lateinit var musicCard: LinearLayout
    private lateinit var albumAuraView: View
    private lateinit var ivMusicCover: ImageView
    private lateinit var tvMusicBadge: TextView
    private lateinit var spectrumView: ScreensaverSpectrumView
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var pbMusic: ProgressBar
    private lateinit var tvMusicTime: TextView
    private lateinit var idleCuePill: TextView

    private var currentScene: ScreensaverScenes.SceneSpec = ScreensaverScenes.SCENES.first()
    private var activePhotoSlotA = true
    private val loadedBitmaps = HashMap<Int, Bitmap>()
    private var lastLoadedResId = 0
    private var albumAccentColor: Int = Color.parseColor("#38BDF8")
    private var customClockOverride: String? = null
    private var customDateOverride: String? = null
    private var customWeatherOverride: String? = null
    private var musicVisible = false
    private var burnInStep = 0
    private var lastWeatherFetchMs = 0L

    private val orbStateListener = LiveOrbState.StateListener {
        if (isOpen) {
            refreshLiveClockTelemetry()
        }
    }

    private val burnInDriftRunnable = object : Runnable {
        override fun run() {
            if (!isOpen) return
            burnInStep = (burnInStep + 1) % 8
            val angle = burnInStep * (PI.toFloat() / 4f)
            val dx = cos(angle) * dp(7).toFloat()
            val dy = sin(angle) * dp(5).toFloat()
            hudContainer.animate()
                .translationX(dx)
                .translationY(dy)
                .setDuration(4200L)
                .setInterpolator(MotionPrefs.easeInOutCurve)
                .start()
            handler.postDelayed(this, 18_000L)
        }
    }

    private val oneSecondTick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            refreshLiveClockTelemetry()
            handler.postDelayed(this, 1000L)
        }
    }

    private val autoCycleRunnable = object : Runnable {
        override fun run() {
            if (!isOpen) return
            if (getScenePreference() == ScreensaverScenes.SCENE_AUTO) {
                switchScene(ScreensaverScenes.stepVisualScene(currentScene.id, +1), saveChoice = false)
            }
            handler.postDelayed(this, AUTO_CYCLE_MS)
        }
    }

    private val dimChromeRunnable = Runnable {
        if (!isOpen) return@Runnable
        scenePillBox.animate().alpha(0.32f).setDuration(900L).start()
        idleCuePill.animate().alpha(0.24f).setDuration(900L).start()
    }

    init {
        rootOverlay = buildUi()
    }

    private fun dp(v: Int): Int = SpatialUiKit.dp(context, v)

    private fun getScenePreference(): String =
        ScreensaverScenes.normalizePreference(prefs.getString(ScreensaverScenes.PREF_KEY_SCENE, ScreensaverScenes.SCENE_AUTO))

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(context).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(38).toFloat()
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            SpatialUiKit.disableSystemHighlight(this)
            setBackgroundColor(Color.parseColor("#02040A"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                if (isOpen) onDismissRequested()
            }
        }

        // Layer 0: Video backdrop (for live 60fps mist forest) + solid noir floor behind photos/cosmos
        videoBackground = VideoBackdropView(context).apply {
            visibility = View.GONE
        }
        root.addView(videoBackground, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        solidBackdrop = View(context).apply {
            setBackgroundColor(Color.parseColor("#03060E"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(solidBackdrop)

        // Layer 1: Dual Ken Burns Photo Crossfade Views (hardware textured layers for zero CPU pan/zoom)
        ivPhotoA = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            alpha = 0f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ivPhotoB = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            alpha = 0f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(ivPhotoA)
        root.addView(ivPhotoB)

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
                    .setDuration(MotionPrefs.dur(context, 380L))
                    .setInterpolator(MotionPrefs.easeOutCurve)
                    .withEndAction {
                        if (isOpen) fadeCurtain.visibility = View.GONE
                    }
                    .start()
            }
        }

        // Layer 2: 60fps Atmospheric Light, Particles, Cinema Vignette & Audio Pulsar Renderer
        atmosphereView = ScreensaverAtmosphereView(context) { frameTimeSec, kenScale, kenPanX, kenPanY ->
            applyMotionFrame(frameTimeSec, kenScale, kenPanX, kenPanY)
        }
        root.addView(atmosphereView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Layer 3: Asymmetric Spatial Cinema HUD (inside Burn-in Drift Container)
        hudContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(hudContainer)

        // Top-Left: Subtle Assistant Live Status Whisper Pill
        assistantPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5C071122"))
                setStroke(dp(1), Color.parseColor("#6638BDF8"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(48)
                topMargin = dp(34)
            }
        }
        tvAssistantPill = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 13f
            typeface = YandexFonts.displayMedium(context)
            letterSpacing = 0.03f
        }
        assistantPill.addView(tvAssistantPill)
        hudContainer.addView(assistantPill)

        // Top-Right: Scenic World Capsule + Pagination Dots
        scenePillBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#44091120"), Color.parseColor("#2A050A14"))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#34FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                rightMargin = dp(48)
                topMargin = dp(32)
            }
        }
        tvSceneTitle = TextView(context).apply {
            text = "✦ Северное сияние"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 13.5f
            typeface = YandexFonts.displayBold(context)
            letterSpacing = 0.04f
            gravity = Gravity.END
        }
        tvSceneSub = TextView(context).apply {
            text = "◀ ▶ Сменить мир"
            setTextColor(Color.parseColor("#99CBD5E1"))
            textSize = 11f
            typeface = YandexFonts.textMedium(context)
            gravity = Gravity.END
            setPadding(0, dp(2), 0, dp(6))
        }
        val dotsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        ScreensaverScenes.SCENES.forEachIndexed { idx, _ ->
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    if (idx > 0) leftMargin = dp(6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#66FFFFFF"))
                }
            }
            sceneDots.add(dot)
            dotsRow.addView(dot)
        }
        scenePillBox.addView(tvSceneTitle)
        scenePillBox.addView(tvSceneSub)
        scenePillBox.addView(dotsRow)
        hudContainer.addView(scenePillBox)

        // Bottom-Left: Editorial StandBy Timepiece + Frosted Date & Weather Capsules
        clockCluster = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(44)
                bottomMargin = dp(40)
            }
        }

        val clockBaselineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            isBaselineAligned = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvBigClock = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#FAFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 108f)
            typeface = YandexFonts.displayMedium(context)
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
            isSingleLine = true
            maxLines = 1
            includeFontPadding = true
            setShadowLayer(dp(18).toFloat(), 0f, dp(4).toFloat(), Color.parseColor("#8C000000"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvClockSec = TextView(context).apply {
            text = "00"
            setTextColor(Color.parseColor("#DBE2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = YandexFonts.displayMedium(context)
            fontFeatureSettings = "tnum"
            letterSpacing = 0.03f
            isSingleLine = true
            maxLines = 1
            gravity = Gravity.CENTER
            minWidth = dp(52)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#420B1426"))
                setStroke(dp(1), Color.parseColor("#3BFFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(10)
                bottomMargin = dp(22)
            }
        }
        clockBaselineRow.addView(tvBigClock)
        clockBaselineRow.addView(tvClockSec)
        clockCluster.addView(clockBaselineRow)

        val metaPillsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        tvDateBadge = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            typeface = YandexFonts.displayBold(context)
            letterSpacing = 0.06f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#540A1224"), Color.parseColor("#38060B16"))
            ).apply {
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Color.parseColor("#3BFFFFFF"))
            }
        }
        weatherPill = TextView(context).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 12f
            typeface = YandexFonts.displayMedium(context)
            letterSpacing = 0.02f
            isSingleLine = true
            maxLines = 1
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#5E0B1A30"), Color.parseColor("#40071122"))
            ).apply {
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Color.parseColor("#527DD3FC"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
        }
        metaPillsRow.addView(tvDateBadge)
        metaPillsRow.addView(weatherPill)
        clockCluster.addView(metaPillsRow)
        hudContainer.addView(clockCluster)

        // Bottom-Right: Adaptive Album-Glow Liquid Glass Now Playing Card (sized to keep generous clearance from clock/weather pills)
        musicCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
            setPadding(dp(13), dp(12), dp(16), dp(12))
            elevation = dp(18).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                dp(432),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(44)
                bottomMargin = dp(40)
            }
        }

        val artHost = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                marginEnd = dp(14)
            }
        }
        albumAuraView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
        }
        val coverWrap = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
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
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        coverWrap.addView(ivMusicCover)
        coverWrap.addView(coverRim)
        artHost.addView(albumAuraView)
        artHost.addView(coverWrap)
        musicCard.addView(artHost)

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
            textSize = 10f
            typeface = YandexFonts.displayBold(context)
            letterSpacing = 0.05f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        spectrumView = ScreensaverSpectrumView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(13)).apply {
                marginEnd = dp(8)
            }
        }
        tvMusicTime = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10.5f
            fontFeatureSettings = "tnum"
        }
        badgeRow.addView(tvMusicBadge)
        badgeRow.addView(spectrumView)
        badgeRow.addView(tvMusicTime)

        tvMusicTitle = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
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
            textSize = 12.5f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(7))
        }
        pbMusic = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        }
        metaCol.addView(badgeRow)
        metaCol.addView(tvMusicTitle)
        metaCol.addView(tvMusicArtist)
        metaCol.addView(pbMusic)
        musicCard.addView(metaCol)
        hudContainer.addView(musicCard)

        // Subtle Footer Cue (Bottom-Right when idle)
        idleCuePill = TextView(context).apply {
            text = "◀ ▶ Сменить сцену  ·  Любая другая клавиша — выход"
            setTextColor(Color.parseColor("#CBE2E8F0"))
            textSize = 11.5f
            typeface = YandexFonts.displayMedium(context)
            letterSpacing = 0.04f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38060B16"))
                setStroke(dp(1), Color.parseColor("#24FFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(44)
                bottomMargin = dp(40)
            }
        }
        hudContainer.addView(idleCuePill)

        styleMusicCard(albumAccentColor)
        return root
    }

    private fun styleMusicCard(accent: Int) {
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)
        val tintTop = Color.argb(176, 8 + (r * 0.09f).toInt(), 12 + (g * 0.09f).toInt(), 24 + (b * 0.11f).toInt())
        val tintBot = Color.argb(198, 5 + (r * 0.05f).toInt(), 8 + (g * 0.05f).toInt(), 16 + (b * 0.06f).toInt())
        val rimColor = Color.argb(145, min(255, 110 + (r * 0.55f).toInt()), min(255, 120 + (g * 0.55f).toInt()), min(255, 140 + (b * 0.55f).toInt()))

        musicCard.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(tintTop, tintBot)
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), rimColor)
        }

        val glowCenter = Color.argb(135, r, g, b)
        val glowEdge = Color.argb(0, r, g, b)
        albumAuraView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(glowCenter, glowEdge)
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(52).toFloat()
            setColors(intArrayOf(glowCenter, Color.argb(55, r, g, b), glowEdge))
        }

        val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics)
        val track = GradientDrawable().apply {
            setColor(Color.parseColor("#2AFFFFFF"))
            cornerRadius = radiusPx
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(255, min(255, r + 45), min(255, g + 45), min(255, b + 45)), Color.parseColor("#FDE68A"))
        ).apply {
            cornerRadius = radiusPx
        }
        pbMusic.progressDrawable = LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true

        AnimatedSpriteHelper.setExclusiveVisibleRoot(rootOverlay)
        rootOverlay.animate().cancel()
        fadeCurtain.animate().cancel()
        fadeCurtain.visibility = View.GONE
        fadeCurtain.alpha = 1f

        val pref = getScenePreference()
        val startScene = if (pref == ScreensaverScenes.SCENE_AUTO) {
            val savedLast = prefs.getString("screensaver_last_auto_id", null)
            ScreensaverScenes.stepVisualScene(savedLast, +1).also {
                prefs.edit().putString("screensaver_last_auto_id", it.id).apply()
            }
        } else {
            ScreensaverScenes.findScene(pref)
        }

        applySceneVisuals(startScene, immediate = true)
        refreshLiveClockTelemetry()
        syncIdleCueVisibility()

        rootOverlay.visibility = View.VISIBLE
        rootOverlay.alpha = 0f
        rootOverlay.scaleX = 1.035f
        rootOverlay.scaleY = 1.035f
        rootOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MotionPrefs.dur(context, 360L))
            .setInterpolator(MotionPrefs.easeOutCurve)
            .withEndAction {
                if (isOpen) {
                    AnimatedSpriteHelper.syncAll()
                }
            }
            .start()

        atmosphereView.setScene(startScene)
        atmosphereView.setRunning(true)

        LiveOrbState.addListener(orbStateListener)
        fetchSilentWeatherIfNeeded()
        handler.removeCallbacks(oneSecondTick)
        handler.post(oneSecondTick)
        handler.removeCallbacks(autoCycleRunnable)
        handler.postDelayed(autoCycleRunnable, AUTO_CYCLE_MS)
        handler.removeCallbacks(burnInDriftRunnable)
        handler.postDelayed(burnInDriftRunnable, 12_000L)
        bumpSceneChromeReveal()

        rootOverlay.post {
            if (isOpen) rootOverlay.requestFocus()
        }
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen && rootOverlay.visibility != View.VISIBLE) return
        isOpen = false
        LiveOrbState.removeListener(orbStateListener)
        AnimatedSpriteHelper.setExclusiveVisibleRoot(null)
        handler.removeCallbacks(oneSecondTick)
        handler.removeCallbacks(autoCycleRunnable)
        handler.removeCallbacks(dimChromeRunnable)
        handler.removeCallbacks(burnInDriftRunnable)
        hudContainer.animate().cancel()
        hudContainer.translationX = 0f
        hudContainer.translationY = 0f
        rootOverlay.animate().cancel()
        fadeCurtain.animate().cancel()
        fadeCurtain.visibility = View.GONE
        videoBackground.setRunning(false)
        atmosphereView.setRunning(false)

        if (immediate) {
            rootOverlay.visibility = View.GONE
            rootOverlay.alpha = 0f
            rootOverlay.scaleX = 1f
            rootOverlay.scaleY = 1f
            return
        }

        rootOverlay.animate()
            .alpha(0f)
            .scaleX(1.025f)
            .scaleY(1.025f)
            .setDuration(MotionPrefs.dur(context, 260L))
            .setInterpolator(MotionPrefs.easeOutCurve)
            .withEndAction {
                if (!isOpen) {
                    rootOverlay.visibility = View.GONE
                    rootOverlay.scaleX = 1f
                    rootOverlay.scaleY = 1f
                }
            }
            .start()
    }

    private fun switchScene(next: ScreensaverScenes.SceneSpec, saveChoice: Boolean) {
        if (!isOpen) return
        if (saveChoice) {
            prefs.edit().putString(ScreensaverScenes.PREF_KEY_SCENE, next.id).apply()
        } else {
            prefs.edit().putString("screensaver_last_auto_id", next.id).apply()
        }
        applySceneVisuals(next, immediate = false)
        atmosphereView.setScene(next)
        bumpSceneChromeReveal()
    }

    private fun applySceneVisuals(scene: ScreensaverScenes.SceneSpec, immediate: Boolean) {
        currentScene = scene
        val pref = getScenePreference()
        val autoSuffix = if (pref == ScreensaverScenes.SCENE_AUTO) " · Авто" else ""
        tvSceneTitle.text = "✦ ${scene.title}$autoSuffix"
        tvSceneSub.text = scene.subtitle

        sceneDots.forEachIndexed { idx, dot ->
            val selected = ScreensaverScenes.SCENES.getOrNull(idx)?.id == scene.id
            (dot.background as? GradientDrawable)?.setColor(
                if (selected) Color.parseColor(scene.accentHex) else Color.parseColor("#4DFFFFFF")
            )
            dot.scaleX = if (selected) 1.35f else 1f
            dot.scaleY = if (selected) 1.35f else 1f
        }

        val resId = when (scene.id) {
            ScreensaverScenes.SCENE_AURORA -> R.drawable.saver_aurora
            ScreensaverScenes.SCENE_BIOLUMINESCENT -> R.drawable.saver_bioluminescent
            ScreensaverScenes.SCENE_DOLOMITES -> R.drawable.saver_dolomites
            else -> 0
        }

        if (scene.id == ScreensaverScenes.SCENE_MIST_FOREST) {
            rootOverlay.setBackgroundColor(Color.TRANSPARENT)
            solidBackdrop.animate().alpha(0f).setDuration(if (immediate) 0L else 600L).withEndAction {
                if (isOpen && currentScene.id == ScreensaverScenes.SCENE_MIST_FOREST) solidBackdrop.visibility = View.GONE
            }.start()
            ivPhotoA.animate().alpha(0f).setDuration(if (immediate) 0L else 650L).withEndAction {
                if (currentScene.id == ScreensaverScenes.SCENE_MIST_FOREST) ivPhotoA.visibility = View.GONE
            }.start()
            ivPhotoB.animate().alpha(0f).setDuration(if (immediate) 0L else 650L).withEndAction {
                if (currentScene.id == ScreensaverScenes.SCENE_MIST_FOREST) ivPhotoB.visibility = View.GONE
            }.start()

            fadeCurtain.alpha = 1f
            fadeCurtain.visibility = View.VISIBLE
            videoBackground.setRunning(true)
            fadeCurtain.postDelayed({
                if (isOpen && currentScene.id == ScreensaverScenes.SCENE_MIST_FOREST && fadeCurtain.visibility == View.VISIBLE) {
                    videoBackground.onFirstFrameReady?.invoke()
                }
            }, 420L)
            return
        }

        // Non-video scene: release hardware video decoder so GPU memory stays clean
        videoBackground.setRunning(false)
        fadeCurtain.visibility = View.GONE
        rootOverlay.setBackgroundColor(Color.parseColor("#02040A"))
        solidBackdrop.visibility = View.VISIBLE
        solidBackdrop.alpha = 1f

        if (resId == 0) {
            // Cosmos Pulse procedural shader backdrop
            ivPhotoA.animate().alpha(0f).setDuration(if (immediate) 0L else 700L).withEndAction {
                if (currentScene.id == ScreensaverScenes.SCENE_COSMOS_PULSE) ivPhotoA.visibility = View.GONE
            }.start()
            ivPhotoB.animate().alpha(0f).setDuration(if (immediate) 0L else 700L).withEndAction {
                if (currentScene.id == ScreensaverScenes.SCENE_COSMOS_PULSE) ivPhotoB.visibility = View.GONE
            }.start()
            return
        }

        val bmp = obtainScenicBitmap(resId)
        val showView = if (activePhotoSlotA) ivPhotoA else ivPhotoB
        val hideView = if (activePhotoSlotA) ivPhotoB else ivPhotoA
        activePhotoSlotA = !activePhotoSlotA

        showView.setImageBitmap(bmp)
        showView.visibility = View.VISIBLE
        if (immediate) {
            showView.alpha = 1f
            hideView.alpha = 0f
            hideView.visibility = View.GONE
        } else {
            showView.alpha = 0f
            showView.animate().alpha(1f).setDuration(850L).setInterpolator(MotionPrefs.easeInOutCurve).start()
            hideView.animate().alpha(0f).setDuration(850L).withEndAction {
                if (hideView.alpha <= 0.02f) hideView.visibility = View.GONE
            }.start()
        }
    }

    private fun obtainScenicBitmap(resId: Int): Bitmap? {
        loadedBitmaps[resId]?.takeIf { !it.isRecycled }?.let { return it }
        // Evict distant bitmaps if more than 1 cached to keep memory ultra-light (~4MB max)
        if (loadedBitmaps.size >= 2) {
            val toDrop = loadedBitmaps.keys.filter { it != resId && it != lastLoadedResId }
            toDrop.forEach { key ->
                loadedBitmaps.remove(key)?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            }
        }
        val decoded = try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeResource(context.resources, resId, opts)
        } catch (_: Throwable) { null }
        if (decoded != null) {
            loadedBitmaps[resId] = decoded
            lastLoadedResId = resId
        }
        return decoded
    }

    private fun applyMotionFrame(
        frameTimeSec: Float,
        kenScale: Float,
        kenPanX: Float,
        kenPanY: Float
    ) {
        if (!isOpen) return

        if (ivPhotoA.visibility == View.VISIBLE) {
            ivPhotoA.scaleX = kenScale
            ivPhotoA.scaleY = kenScale
            ivPhotoA.translationX = kenPanX
            ivPhotoA.translationY = kenPanY
        }
        if (ivPhotoB.visibility == View.VISIBLE) {
            ivPhotoB.scaleX = kenScale + 0.01f
            ivPhotoB.scaleY = kenScale + 0.01f
            ivPhotoB.translationX = -kenPanX * 0.7f
            ivPhotoB.translationY = -kenPanY * 0.7f
        }

        if (musicVisible) {
            spectrumView.invalidate()
            val pulse = 1.0f + LiveOrbState.musicBass * 0.16f + 0.03f * sin(frameTimeSec * 2.1f)
            albumAuraView.scaleX = pulse
            albumAuraView.scaleY = pulse
        }
    }

    private fun bumpSceneChromeReveal() {
        handler.removeCallbacks(dimChromeRunnable)
        scenePillBox.animate().cancel()
        idleCuePill.animate().cancel()
        scenePillBox.alpha = 1f
        if (!musicVisible) {
            idleCuePill.alpha = 0.88f
        }
        handler.postDelayed(dimChromeRunnable, 4200L)
    }

    private fun syncIdleCueVisibility() {
        idleCuePill.visibility = if (musicVisible) View.GONE else View.VISIBLE
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

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    switchScene(ScreensaverScenes.stepVisualScene(currentScene.id, -1), saveChoice = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    switchScene(ScreensaverScenes.stepVisualScene(currentScene.id, +1), saveChoice = true)
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            onDismissRequested()
        }
        return true
    }

    fun updateClockAndWeather(timeText: String, dateText: String, weatherSummary: String?) {
        customClockOverride = timeText.ifBlank { null }
        customDateOverride = dateText.ifBlank { null }
        customWeatherOverride = weatherSummary?.ifBlank { null }
        refreshLiveClockTelemetry()
    }

    private fun fetchSilentWeatherIfNeeded() {
        val nowMs = SystemClock.elapsedRealtime()
        if (LiveOrbState.weatherValid || (nowMs - lastWeatherFetchMs) < 120_000L) return
        lastWeatherFetchMs = nowMs
        thread(name = "SaverWeatherSync", isDaemon = true) {
            try {
                val conn = (URL("http://127.0.0.1:8080/api/weather?silent=1").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 3500
                }
                if (conn.responseCode == 200) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val j = org.json.JSONObject(txt)
                    val cur = j.optJSONObject("current") ?: j
                    val temp = cur.optInt("tempC", Int.MIN_VALUE)
                    if (temp != Int.MIN_VALUE) {
                        val cond = cur.optString("condition", "clear")
                        val desc = cur.optString("conditionText", "")
                        val city = j.optString("city", cur.optString("city", ""))
                        LiveOrbState.updateWeather(true, temp, cond, desc, city)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun refreshLiveClockTelemetry() {
        val now = Date()
        val hm = customClockOverride ?: hmFmt.format(now)
        if (tvBigClock.text?.toString() != hm) tvBigClock.text = hm
        val ss = secFmt.format(now)
        if (tvClockSec.text?.toString() != ss) tvClockSec.text = ss

        val dateFormatted = (customDateOverride ?: fullDateFmt.format(now)).uppercase(Locale("ru", "RU"))
        if (tvDateBadge.text?.toString() != dateFormatted) tvDateBadge.text = dateFormatted

        val weatherTxt = customWeatherOverride ?: ScreensaverScenes.formatWeatherPill(
            LiveOrbState.weatherValid,
            LiveOrbState.weatherTemp,
            LiveOrbState.weatherCondition,
            LiveOrbState.weatherConditionText,
            LiveOrbState.weatherCity
        )
        if (!weatherTxt.isNullOrBlank()) {
            if (weatherPill.text?.toString() != weatherTxt) weatherPill.text = weatherTxt
            if (weatherPill.visibility != View.VISIBLE) weatherPill.visibility = View.VISIBLE
        } else {
            if (weatherPill.visibility != View.GONE) weatherPill.visibility = View.GONE
        }

        val astState = LiveOrbState.assistantState
        if (astState in setOf("connecting", "listening", "thinking", "speaking")) {
            val label = when (astState) {
                "listening" -> "🎙 ${LiveOrbState.effectiveName()} слушает..."
                "thinking" -> "✦ ${LiveOrbState.effectiveName()} думает..."
                "speaking" -> "〰 ${LiveOrbState.effectiveName()} отвечает..."
                else -> "◌ Подключение ${LiveOrbState.effectiveName()}..."
            }
            if (tvAssistantPill.text?.toString() != label) tvAssistantPill.text = label
            if (assistantPill.visibility != View.VISIBLE) assistantPill.visibility = View.VISIBLE
        } else {
            if (assistantPill.visibility != View.GONE) assistantPill.visibility = View.GONE
        }
    }

    fun updateMusic(snap: YmPlayerSnapshot, coverBmp: Bitmap?) {
        val tr = snap.currentTrack
        if (tr == null) {
            musicVisible = false
            musicCard.visibility = View.GONE
            syncIdleCueVisibility()
            atmosphereView.setAlbumAccent(Color.TRANSPARENT)
            return
        }
        musicVisible = true
        musicCard.visibility = View.VISIBLE
        syncIdleCueVisibility()

        val stateLabel = when {
            snap.buffering -> "ЗАГРУЗКА"
            snap.playing -> "СЕЙЧАС ИГРАЕТ"
            else -> "НА ПАУЗЕ"
        }
        val dotColor = if (snap.playing) Color.parseColor("#86EFAC") else Color.parseColor("#E8C686")
        tvMusicBadge.text = "●  $stateLabel · ${snap.sourceLabel.uppercase(Locale.getDefault())}"
        tvMusicBadge.setTextColor(dotColor)
        tvMusicTitle.text = tr.title.ifBlank { "Без названия" }
        tvMusicArtist.text = if (tr.album.isNotBlank()) "${tr.artists} · ${tr.album}" else tr.artists

        val dur = max(0L, snap.durationMs)
        val pos = max(0L, snap.positionMs).coerceAtMost(max(1L, dur))
        pbMusic.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
        tvMusicTime.text = "${formatMs(pos)} / ${formatMs(dur)}"

        if (coverBmp != null && !coverBmp.isRecycled) {
            ivMusicCover.setImageBitmap(coverBmp)
            val sampled = extractDominantAccent(coverBmp)
            if (sampled != albumAccentColor) {
                albumAccentColor = sampled
                styleMusicCard(albumAccentColor)
                atmosphereView.setAlbumAccent(albumAccentColor)
                spectrumView.accentColor = albumAccentColor
            }
        } else {
            ivMusicCover.setSpriteDrawable(R.drawable.sprite_ym_emblem)
            albumAccentColor = Color.parseColor("#38BDF8")
            styleMusicCard(albumAccentColor)
            atmosphereView.setAlbumAccent(Color.TRANSPARENT)
            spectrumView.accentColor = albumAccentColor
        }
    }

    private fun extractDominantAccent(bmp: Bitmap): Int {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var wSum = 0L
        val stepX = max(1, bmp.width / 8)
        val stepY = max(1, bmp.height / 8)
        for (y in 0 until bmp.height step stepY) {
            for (x in 0 until bmp.width step stepX) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val chroma = maxC - minC
                val weight = 1 + (chroma * 2)
                rSum += r * weight
                gSum += g * weight
                bSum += b * weight
                wSum += weight
            }
        }
        if (wSum <= 0L) return Color.parseColor("#38BDF8")
        val raw = Color.rgb((rSum / wSum).toInt().coerceIn(0, 255), (gSum / wSum).toInt().coerceIn(0, 255), (bSum / wSum).toInt().coerceIn(0, 255))
        return ScreensaverScenes.boostSaturationForGlow(raw)
    }

    fun onHostResume() {
        if (isOpen) {
            if (currentScene.id == ScreensaverScenes.SCENE_MIST_FOREST) {
                videoBackground.setRunning(true)
            }
            atmosphereView.setRunning(true)
            handler.removeCallbacks(oneSecondTick)
            handler.post(oneSecondTick)
        }
    }

    fun onHostPause() {
        videoBackground.setRunning(false)
        atmosphereView.setRunning(false)
        handler.removeCallbacks(oneSecondTick)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        LiveOrbState.removeListener(orbStateListener)
        videoBackground.setRunning(false)
        atmosphereView.setRunning(false)
        isOpen = false
        rootOverlay.animate().cancel()
        loadedBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        loadedBitmaps.clear()
    }

    private fun formatMs(ms: Long): String {
        val tot = max(0L, ms / 1000L)
        return "%d:%02d".format(tot / 60L, tot % 60L)
    }

    /**
     * Live 20-bar miniature audio spectrum rendered directly inside the screensaver Now Playing card.
     */
    private class ScreensaverSpectrumView(context: Context) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val levels = FloatArray(14)
        var accentColor: Int = Color.parseColor("#38BDF8")

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val n = levels.size
            val slot = w / n
            val bw = max(2f, slot * 0.56f)
            val playing = LiveOrbState.musicPlaying
            val t = SystemClock.uptimeMillis() * 0.004f

            for (i in 0 until n) {
                val band = LiveOrbState.rawBands.getOrNull(i * 24 / n) ?: 0f
                val target = if (playing) {
                    (0.18f + band * 0.95f + 0.14f * (0.5f + 0.5f * sin(t + i * 0.65f))).coerceIn(0.14f, 1.0f)
                } else {
                    0.16f + 0.05f * (0.5f + 0.5f * sin(t * 0.5f + i * 0.5f))
                }
                levels[i] = levels[i] + (target - levels[i]) * 0.28f
                val bh = max(bw, levels[i] * h)
                val left = i * slot + (slot - bw) * 0.5f
                val top = (h - bh) * 0.5f

                val frac = i / (n - 1f)
                val gold = Color.parseColor("#FDE68A")
                val r = (Color.red(accentColor) * (1f - frac) + Color.red(gold) * frac).toInt()
                val g = (Color.green(accentColor) * (1f - frac) + Color.green(gold) * frac).toInt()
                val b = (Color.blue(accentColor) * (1f - frac) + Color.blue(gold) * frac).toInt()
                barPaint.color = Color.argb(if (playing) 235 else 130, r, g, b)
                canvas.drawRoundRect(left, top, left + bw, top + bh, bw * 0.5f, bw * 0.5f, barPaint)
            }
        }
    }

    /**
     * 60fps Choreographer-driven cinema atmosphere renderer:
     * vignette scrim, scene-tuned aurora curtains / stardust / bioluminescence / golden embers,
     * cosmic orbital audio pulsar, smooth Ken Burns math, and sub-pixel OLED burn-in drift.
     */
    private class ScreensaverAtmosphereView(
        context: Context,
        private val onFrame: (
            timeSec: Float,
            kenScale: Float,
            kenPanX: Float,
            kenPanY: Float
        ) -> Unit
    ) : View(context), Choreographer.FrameCallback {

        private data class Particle(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var size: Float,
            var phase: Float,
            var alpha: Float
        )

        private var running = false
        private var startMs = SystemClock.uptimeMillis()
        private var lastFrameMs = 0L
        private var scene: ScreensaverScenes.SceneSpec = ScreensaverScenes.SCENES.first()
        private var albumAccent: Int = Color.TRANSPARENT

        private val particles = Array(42) {
            val rnd = Random(1337 + it * 97)
            Particle(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                vx = (rnd.nextFloat() - 0.5f) * 0.012f,
                vy = -(0.004f + rnd.nextFloat() * 0.012f),
                size = 2.2f + rnd.nextFloat() * 4.5f,
                phase = rnd.nextFloat() * (2f * PI.toFloat()),
                alpha = 0.25f + rnd.nextFloat() * 0.65f
            )
        }

        private val bokehSprite: Bitmap by lazy {
            val sz = 64
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(
                sz * 0.5f, sz * 0.5f, sz * 0.5f,
                intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(145, 255, 255, 255),
                    Color.argb(36, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(sz * 0.5f, sz * 0.5f, sz * 0.5f, p)
            bmp
        }

        private var lastW = 0
        private var lastH = 0
        private var bottomScrimShader: Shader? = null
        private var topScrimShader: Shader? = null
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val particleRect = RectF()
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val ovalRect = RectF()
        private val smoothBands = FloatArray(32)

        private val shaderMatrix = Matrix()
        private var sceneAccentColor = Color.parseColor(scene.accentHex)
        private var sceneSecondaryColor = Color.parseColor(scene.secondaryHex)
        private var cachedShaderSceneId: String? = null
        private var auroraShader1: RadialGradient? = null
        private var auroraShader2: RadialGradient? = null
        private var cosmosWashShader: RadialGradient? = null
        private var cosmosCoreShader: RadialGradient? = null
        private var cachedMusicAccent = Color.TRANSPARENT
        private var musicHaloShader: RadialGradient? = null
        private var cachedParticleColor = Color.TRANSPARENT
        private var cachedParticleFilter: PorterDuffColorFilter? = null

        private fun rebuildSceneShaders() {
            if (cachedShaderSceneId == scene.id) return
            cachedShaderSceneId = scene.id
            val ar = Color.red(sceneAccentColor)
            val ag = Color.green(sceneAccentColor)
            val ab = Color.blue(sceneAccentColor)
            val sr = Color.red(sceneSecondaryColor)
            val sg = Color.green(sceneSecondaryColor)
            val sb = Color.blue(sceneSecondaryColor)

            auroraShader1 = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.argb(38, ar, ag, ab), Color.argb(14, sr, sg, sb), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            auroraShader2 = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.argb(28, sr, sg, sb), Color.argb(10, ar, ag, ab), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            cosmosWashShader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.argb(74, sr, sg, sb), Color.argb(46, ar, ag, ab), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            cosmosCoreShader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.argb(120, 224, 242, 254), Color.argb(72, ar, ag, ab), Color.argb(26, sr, sg, sb), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        fun setScene(next: ScreensaverScenes.SceneSpec) {
            scene = next
            sceneAccentColor = Color.parseColor(next.accentHex)
            sceneSecondaryColor = Color.parseColor(next.secondaryHex)
            cachedShaderSceneId = null
            invalidate()
        }

        fun setAlbumAccent(color: Int) {
            if (albumAccent == color) return
            albumAccent = color
            if (color == Color.TRANSPARENT) {
                cachedMusicAccent = Color.TRANSPARENT
                musicHaloShader = null
            } else if (cachedMusicAccent != color) {
                cachedMusicAccent = color
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                musicHaloShader = RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(Color.argb(66, r, g, b), Color.argb(18, r, g, b), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        fun setRunning(active: Boolean) {
            if (running == active) return
            running = active
            if (active) {
                startMs = SystemClock.uptimeMillis()
                lastFrameMs = 0L
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val nowMs = SystemClock.uptimeMillis()
            if (lastFrameMs == 0L || (nowMs - lastFrameMs) >= 32L) {
                lastFrameMs = nowMs
                val tSec = (nowMs - startMs) * 0.001f
                val dp = resources.displayMetrics.density
                val kenScale = 1.055f + 0.042f * sin(tSec * 0.21f)
                val kenPanX = sin(tSec * 0.17f) * 20f * dp
                val kenPanY = cos(tSec * 0.13f) * 10f * dp
                onFrame(tSec, kenScale, kenPanX, kenPanY)
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            lastW = w
            lastH = h
            if (w > 0 && h > 0) {
                bottomScrimShader = LinearGradient(
                    0f, h * 0.48f, 0f, h.toFloat(),
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.parseColor("#3803060E"),
                        Color.parseColor("#B602040B")
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                topScrimShader = LinearGradient(
                    0f, 0f, 0f, h * 0.25f,
                    intArrayOf(Color.parseColor("#5402040B"), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            rebuildSceneShaders()
            val t = (SystemClock.uptimeMillis() - startMs) * 0.001f
            val dp = resources.displayMetrics.density
            val bass = LiveOrbState.musicBass

            when (scene.id) {
                ScreensaverScenes.SCENE_COSMOS_PULSE -> drawCosmosPulsar(canvas, w, h, t, dp, bass, sceneAccentColor, sceneSecondaryColor)
                ScreensaverScenes.SCENE_AURORA -> drawAuroraRibbons(canvas, w, h, t)
                else -> {}
            }

            // Editorial edge vignette for crisp legibility over bright skies/snow
            topScrimShader?.let {
                scrimPaint.shader = it
                canvas.drawRect(0f, 0f, w, h * 0.25f, scrimPaint)
            }
            bottomScrimShader?.let {
                scrimPaint.shader = it
                canvas.drawRect(0f, h * 0.48f, w, h, scrimPaint)
            }

            // Music-reactive ambient glow halo in the bottom-right around the Now Playing dock
            if (LiveOrbState.musicPlaying) {
                musicHaloShader?.let { halo ->
                    val auraR = min(w, h) * (0.40f + bass * 0.14f)
                    val cx = w * 0.80f
                    val cy = h * 0.86f
                    shaderMatrix.setScale(auraR, auraR)
                    shaderMatrix.postTranslate(cx, cy)
                    halo.setLocalMatrix(shaderMatrix)
                    glowPaint.shader = halo
                    canvas.drawCircle(cx, cy, auraR, glowPaint)
                    glowPaint.shader = null
                }
            }

            // Living floating dust / bioluminescent sparks / sunlit embers
            val pColor = when (scene.id) {
                ScreensaverScenes.SCENE_DOLOMITES -> Color.parseColor("#FDE68A")
                ScreensaverScenes.SCENE_BIOLUMINESCENT -> Color.parseColor("#67E8F9")
                ScreensaverScenes.SCENE_AURORA -> Color.parseColor("#A7F3D0")
                ScreensaverScenes.SCENE_MIST_FOREST -> Color.parseColor("#D1FAE5")
                else -> Color.parseColor("#BAE6FD")
            }
            if (cachedParticleColor != pColor) {
                cachedParticleColor = pColor
                cachedParticleFilter = PorterDuffColorFilter(pColor, PorterDuff.Mode.SRC_IN)
            }
            particlePaint.colorFilter = cachedParticleFilter
            val speedScale = if (LiveOrbState.musicPlaying) (1f + bass * 1.4f) else 1f

            for (p in particles) {
                p.x += p.vx * 0.032f * speedScale
                p.y += p.vy * 0.032f * speedScale
                if (p.y < -0.04f) {
                    p.y = 1.04f
                    p.x = (p.x + 0.37f) % 1.0f
                }
                if (p.x < -0.02f) p.x += 1.04f
                if (p.x > 1.02f) p.x -= 1.04f

                val twinkle = 0.50f + 0.50f * sin(t * 1.4f + p.phase)
                val alphaByte = (p.alpha * twinkle * 195f).toInt().coerceIn(22, 210)
                val px = p.x * w
                val py = p.y * h
                val rad = p.size * dp * (1.8f + bass * 0.5f)

                particlePaint.alpha = alphaByte
                particleRect.set(px - rad, py - rad, px + rad, py + rad)
                canvas.drawBitmap(bokehSprite, null, particleRect, particlePaint)
            }
        }

        private fun drawAuroraRibbons(canvas: Canvas, w: Float, h: Float, t: Float) {
            auroraShader1?.let { sh ->
                val cx1 = w * (0.62f + 0.08f * sin(t * 0.19f))
                val cy1 = h * (0.24f + 0.04f * cos(t * 0.23f))
                val rx1 = w * 0.52f
                val ry1 = w * 0.23f
                shaderMatrix.setScale(rx1, ry1)
                shaderMatrix.postTranslate(cx1, cy1)
                sh.setLocalMatrix(shaderMatrix)
                glowPaint.shader = sh
                ovalRect.set(cx1 - rx1, cy1 - ry1, cx1 + rx1, cy1 + ry1)
                canvas.drawOval(ovalRect, glowPaint)
            }
            auroraShader2?.let { sh ->
                val cx2 = w * (0.38f + 0.07f * cos(t * 0.14f))
                val cy2 = h * (0.18f + 0.03f * sin(t * 0.21f))
                val rx2 = w * 0.41f
                val ry2 = w * 0.17f
                shaderMatrix.setScale(rx2, ry2)
                shaderMatrix.postTranslate(cx2, cy2)
                sh.setLocalMatrix(shaderMatrix)
                glowPaint.shader = sh
                ovalRect.set(cx2 - rx2, cy2 - ry2, cx2 + rx2, cy2 + ry2)
                canvas.drawOval(ovalRect, glowPaint)
            }
            glowPaint.shader = null
        }

        private fun drawCosmosPulsar(
            canvas: Canvas,
            w: Float,
            h: Float,
            t: Float,
            dp: Float,
            bass: Float,
            accent: Int,
            secondary: Int
        ) {
            val cx = w * 0.50f
            val cy = h * 0.40f
            val baseR = min(w, h) * 0.225f

            val ar = Color.red(accent)
            val ag = Color.green(accent)
            val ab = Color.blue(accent)
            val sr = Color.red(secondary)
            val sg = Color.green(secondary)
            val sb = Color.blue(secondary)

            cosmosWashShader?.let { wash ->
                val washR = min(w, h) * (0.64f + bass * 0.12f)
                shaderMatrix.setScale(washR, washR)
                shaderMatrix.postTranslate(cx, cy)
                wash.setLocalMatrix(shaderMatrix)
                glowPaint.shader = wash
                canvas.drawCircle(cx, cy, washR, glowPaint)
            }

            // Outer celestial orbital halo
            val orbitR = baseR * (1.26f + 0.04f * sin(t * 0.55f) + bass * 0.08f)
            ringPaint.color = Color.argb(95, ar, ag, ab)
            ringPaint.strokeWidth = 1.6f * dp
            canvas.drawCircle(cx, cy, orbitR, ringPaint)

            val tilt = 12f * sin(t * 0.25f)
            canvas.save()
            canvas.rotate(tilt, cx, cy)
            ovalRect.set(cx - orbitR * 1.24f, cy - orbitR * 0.52f, cx + orbitR * 1.24f, cy + orbitR * 0.52f)
            ringPaint.color = Color.argb(80, sr, sg, sb)
            ringPaint.strokeWidth = 1.3f * dp
            canvas.drawOval(ovalRect, ringPaint)
            canvas.restore()

            // 32-spoke radial audio harmonic corona
            val nRays = 32
            val playing = LiveOrbState.musicPlaying
            for (i in 0 until nRays) {
                val raw = LiveOrbState.rawBands.getOrNull(i * 24 / nRays) ?: 0f
                val target = if (playing) {
                    (0.15f + raw * 1.15f).coerceIn(0.1f, 1.3f)
                } else {
                    0.18f + 0.08f * sin(t * 1.1f + i * 0.35f)
                }
                smoothBands[i] = smoothBands[i] + (target - smoothBands[i]) * 0.24f

                val angle = (i.toFloat() / nRays) * (2f * PI.toFloat()) + t * 0.12f
                val rIn = baseR * 0.72f
                val rOut = rIn + smoothBands[i] * (baseR * 0.44f)
                val x1 = cx + cos(angle) * rIn
                val y1 = cy + sin(angle) * rIn
                val x2 = cx + cos(angle) * rOut
                val y2 = cy + sin(angle) * rOut

                val mix = (i % 8) / 7f
                rayPaint.strokeWidth = (2.6f + smoothBands[i] * 1.8f) * dp
                rayPaint.color = Color.argb(
                    (110 + (smoothBands[i] * 110).toInt()).coerceIn(70, 235),
                    (ar * (1f - mix) + sr * mix).toInt(),
                    (ag * (1f - mix) + sg * mix).toInt(),
                    (ab * (1f - mix) + sb * mix).toInt()
                )
                canvas.drawLine(x1, y1, x2, y2, rayPaint)
            }

            // Inner glowing glass core orb
            cosmosCoreShader?.let { core ->
                val coreR = baseR * 0.72f
                shaderMatrix.setScale(coreR, coreR)
                shaderMatrix.postTranslate(cx - baseR * 0.16f, cy - baseR * 0.18f)
                core.setLocalMatrix(shaderMatrix)
                glowPaint.shader = core
                canvas.drawCircle(cx, cy, baseR * 0.70f, glowPaint)
            }
            glowPaint.shader = null
        }

        override fun onDetachedFromWindow() {
            setRunning(false)
            super.onDetachedFromWindow()
        }
    }
}
