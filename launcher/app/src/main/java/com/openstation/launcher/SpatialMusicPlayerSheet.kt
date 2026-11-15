package com.openstation.launcher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Monolithic Spatial Liquid Glass Music Player controller shared seamlessly between:
 * 1) Inline slide-up Spatial sheet inside MainActivity (DPAD_DOWN on Music Stage or Cover click)
 * 2) Standalone YandexMusicActivity fallback (voice intents / direct app icon launches)
 */
class SpatialMusicPlayerSheet(
    private val context: Context,
    private val spatialBackdrop: SpatialGlassBackdropView?,
    private val closeLabel: String = "Свернуть",
    private val isEmbeddedOverlay: Boolean = true,
    private val onCoverBitmapLoaded: ((Bitmap?) -> Unit)? = null,
    private val onCloseRequested: () -> Unit
) {
    private data class IconBtnViews(
        val root: FrameLayout,
        val icon: ImageView
    )

    private val handler = Handler(Looper.getMainLooper())
    private val easeOutCubic get() = MotionPrefs.easeOutCurve
    private val focusSpring get() = MotionPrefs.focusCurve
    private val lensCleanups = ArrayList<() -> Unit>()

    val rootOverlay: FrameLayout
    var isOpen: Boolean = false
        private set

    var activeTab: String = "wave"
        private set
    private var collectionMode = "likes"

    private lateinit var tvAcctBadge: TextView
    private lateinit var tabWave: LinearLayout
    private lateinit var tabSearch: LinearLayout
    private lateinit var tabCollection: LinearLayout
    private lateinit var tabAuth: LinearLayout

    // Left Hero Player
    private lateinit var ivCover: ImageView
    private lateinit var viewCoverRim: View
    private lateinit var tvSourceBadge: TextView
    private lateinit var tvCodecBadge: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvTrackArtist: TextView
    private lateinit var progressTrack: ProgressBar
    private lateinit var tvTimePos: TextView
    private lateinit var tvTimeDur: TextView
    private lateinit var btnPrev: IconBtnViews
    private lateinit var btnPlayPause: IconBtnViews
    private lateinit var btnNext: IconBtnViews
    private lateinit var btnLike: IconBtnViews
    private lateinit var btnDislike: IconBtnViews
    private lateinit var btnMyWaveHero: LinearLayout
    private lateinit var tvErrorLine: TextView

    // Right Tab Stage
    private lateinit var viewWave: LinearLayout
    private lateinit var viewSearch: LinearLayout
    private lateinit var viewCollection: LinearLayout
    private lateinit var viewAuth: ScrollView

    // Wave tab
    private val moodBtns = LinkedHashMap<String, LinearLayout>()
    private lateinit var waveQueueList: LinearLayout
    private lateinit var tvWaveStatus: TextView

    // Search tab
    private lateinit var etSearch: EditText
    private lateinit var searchList: LinearLayout
    private lateinit var tvSearchStatus: TextView

    // Collection tab
    private lateinit var btnColLikes: LinearLayout
    private lateinit var btnColChart: LinearLayout
    private lateinit var btnColPlayAll: LinearLayout
    private lateinit var collectionList: ListView
    private var collectionRequestId = 0
    private lateinit var tvColStatus: TextView

    // Auth & Audio tab
    private lateinit var tvAuthSummary: TextView
    private lateinit var btnAuthLogout: LinearLayout
    private lateinit var btnQualityNormal: TextView
    private lateinit var ivWebQr: ImageView
    private lateinit var tvWebHint: TextView

    private var passportDialog: AlertDialog? = null
    private var oauthTokenDialog: AlertDialog? = null

    private var lastRenderedCoverUrl = ""
    private var lastQueueSignature = ""
    private var collectionTracks: List<YmTrack> = emptyList()
    private var searchTracks: List<YmTrack> = emptyList()
    var lastSnapshot: YmPlayerSnapshot = YmPlayerSnapshot()
        private set

    init {
        rootOverlay = buildUi()
        if (!isEmbeddedOverlay) {
            isOpen = true
            rootOverlay.visibility = View.VISIBLE
            rootOverlay.alpha = 1f
        }
    }

    private fun dp(v: Int): Int = SpatialUiKit.dp(context, v)
    private fun player(): YandexMusicPlayer? = StationAudioService.instance?.musicPlayer

    private fun sendServiceCmd(cmd: String, extras: (Intent.() -> Unit)? = null) {
        val i = Intent(context, StationAudioService::class.java).apply {
            action = StationAudioService.ACTION_MUSIC_CMD
            putExtra("cmd", cmd)
            extras?.invoke(this)
        }
        try {
            context.startForegroundService(i)
        } catch (_: Throwable) {}
    }

    private fun bindPanelLens(view: View, spec: SpatialLensSpec) {
        if (spatialBackdrop != null) {
            SpatialUiKit.disableSystemHighlight(view)
            lensCleanups.add(spatialBackdrop.registerLens(view, spec))
        } else {
            SpatialUiKit.disableSystemHighlight(view)
            view.background = SpatialUiKit.makeGlassSurface(
                context,
                radiusDp = spec.radiusDp,
                normalTop = Color.parseColor("#B80D1426"),
                normalBottom = Color.parseColor("#C4070B16"),
                focusedTop = Color.parseColor("#CC14223E"),
                focusedBottom = Color.parseColor("#D40A1224"),
                normalStroke = Color.parseColor("#32FFFFFF"),
                focusedStroke = Color.parseColor("#887DD3FC")
            )
        }
    }

    private fun buildUi(): FrameLayout {
        val rootFrame = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
            alpha = 0f
            elevation = dp(32).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(28), dp(14), dp(28), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(shell)

        // ===== 1. FLOATING SPATIAL TOP HEADER =====
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(14))
        }

        val brandCol = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ivBrandEmblem = ImageView(context).apply {
            setSpriteDrawable(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(11)
            }
        }
        val tvLogo = TextView(context).apply {
            text = "ЯНДЕКС МУЗЫКА"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 18.5f
            typeface = YandexFonts.displayBold(context)
            letterSpacing = 0.05f
        }
        tvAcctBadge = makeHeaderTextPill("Проверка...") { switchTab("auth") }.apply {
            textSize = 11.5f
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
        }
        brandCol.addView(ivBrandEmblem)
        brandCol.addView(tvLogo)
        brandCol.addView(tvAcctBadge)

        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        tabWave = makeHeaderIconPill(R.drawable.ic_wave, "Моя волна", Color.parseColor("#FDE68A")) { switchTab("wave") }
        tabSearch = makeHeaderIconPill(R.drawable.ic_search, "Поиск", Color.parseColor("#7DD3FC")) { switchTab("search") }
        tabCollection = makeHeaderIconPill(R.drawable.ic_heart_filled, "Коллекция", null) { switchTab("collection") }
        tabAuth = makeHeaderIconPill(R.drawable.ic_settings, "Вход", Color.parseColor("#CBD5E1")) { switchTab("auth") }
        val btnCloseSheet = makeHeaderIconPill(R.drawable.ic_home, closeLabel, Color.parseColor("#F1F5F9")) {
            onCloseRequested()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        listOf(tabWave, tabSearch, tabCollection, tabAuth, btnCloseSheet).forEach { tabsRow.addView(it) }

        topBar.addView(brandCol)
        topBar.addView(tabsRow)
        shell.addView(topBar)

        // ===== 2. MONOLITHIC TWO-COLUMN LIQUID GLASS STAGE =====
        val bodyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val slabSpec = SpatialLensSpec(
            radiusDp = 28f,
            baseTint = Color.parseColor("#32090E1C"),
            focusTint = Color.parseColor("#32090E1C"),
            bevelDp = 18f,
            refractDp = 14f,
            chromaDp = 2.4f,
            shadowDp = 24f,
            rimAlpha = 0.36f,
            focusRimAlpha = 0.36f,
            blurMix = 0.90f
        )

        // --- LEFT MONOLITH: HERO LIQUID GLASS PLAYER (Zero skeuomorphic vinyl) ---
        val heroCardContainer = FrameLayout(context).apply {
            bindPanelLens(this, slabSpec)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.41f).apply {
                marginEnd = dp(16)
            }
        }

        val heroBackdropClip = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val ivWaveHeaderBg = ImageView(context).apply {
            setSpriteDrawable(R.drawable.hero_wave_texture)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.24f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        heroBackdropClip.addView(ivWaveHeaderBg)
        heroCardContainer.addView(heroBackdropClip)

        val heroScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val heroCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val badgesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        tvSourceBadge = TextView(context).apply {
            text = "МОЯ ВОЛНА"
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 10.5f
            typeface = YandexFonts.displayBold(context)
            letterSpacing = 0.06f
            setPadding(dp(11), dp(4), dp(11), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2438BDF8"))
                setStroke(dp(1), Color.parseColor("#557DD3FC"))
                cornerRadius = dp(999).toFloat()
            }
        }
        tvCodecBadge = TextView(context).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 10.5f
            typeface = YandexFonts.displayBold(context)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#24E8C686"))
                setStroke(dp(1), Color.parseColor("#55E8C686"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        badgesRow.addView(tvSourceBadge)
        badgesRow.addView(tvCodecBadge)

        // Flagship Glass Album Cover Card (clean square, zero peeking vinyl)
        val coverStage = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = false
            setOnClickListener { doPlayerAction("PLAY_PAUSE") }
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                bottomMargin = dp(12)
            }
        }
        ivCover = ImageView(context).apply {
            setSpriteDrawable(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        viewCoverRim = View(context).apply {
            isFocusable = false
            isClickable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#2CFFFFFF"),
                    Color.parseColor("#04FFFFFF"),
                    Color.parseColor("#00000000"),
                    Color.parseColor("#18000000")
                )
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#52FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        coverStage.addView(ivCover)
        coverStage.addView(viewCoverRim)

        tvTrackTitle = TextView(context).apply {
            text = "Готово к запуску"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 19f
            typeface = YandexFonts.displayBold(context)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        tvTrackArtist = TextView(context).apply {
            text = "Нажмите «Моя волна» или скажите «${LiveOrbState.effectiveName()}»"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            typeface = YandexFonts.textMedium(context)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(10))
        }

        progressTrack = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = SpatialUiKit.makeFrostedProgressDrawable(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                bottomMargin = dp(4)
            }
        }
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        tvTimePos = TextView(context).apply {
            text = "0:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvTimeDur = TextView(context).apply {
            text = "0:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
        }
        timeRow.addView(tvTimePos)
        timeRow.addView(tvTimeDur)

        val ctrlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        btnPrev = makeMonolithCtrlIconBtn(R.drawable.ic_prev, Color.WHITE) { doPlayerAction("PREV") }
        btnPlayPause = makeMonolithCtrlIconBtn(R.drawable.ic_play, Color.parseColor("#FDE68A"), hero = true) { doPlayerAction("PLAY_PAUSE") }
        btnNext = makeMonolithCtrlIconBtn(R.drawable.ic_next, Color.WHITE) { doPlayerAction("NEXT") }
        btnLike = makeMonolithCtrlIconBtn(R.drawable.ic_heart_outline, null) { doPlayerAction("LIKE") }
        btnDislike = makeMonolithCtrlIconBtn(R.drawable.ic_dislike, Color.parseColor("#CBD5E1")) { doPlayerAction("DISLIKE") }
        val ctrlButtons = listOf(btnPrev, btnPlayPause, btnNext, btnLike, btnDislike)
        ctrlButtons.forEachIndexed { idx, btn ->
            val size = if (idx == 1) dp(48) else dp(42)
            btn.root.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (idx < ctrlButtons.lastIndex) marginEnd = dp(9)
            }
            ctrlRow.addView(btn.root)
        }

        // Spatial Liquid Glass "МОЯ ВОЛНА" CTA Pill (zero opaque yellow highlighter brick)
        btnMyWaveHero = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = SpatialUiKit.makeGlassAccentSurface(context, radiusDp = 999f)
            SpatialUiKit.disableSystemHighlight(this)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.035f else 1f).scaleY(if (hasFocus) 1.035f else 1f)
                    .setDuration(MotionPrefs.dur(context, 240L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener {
                startMyWave(lastSnapshot.waveMood)
            }
            val ivW = ImageView(context).apply {
                val d = context.getDrawable(R.drawable.ic_wave)?.mutate()
                d?.setTint(Color.parseColor("#FDE68A"))
                setImageDrawable(d)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(8)
                }
            }
            val tvW = TextView(context).apply {
                text = "МОЯ ВОЛНА"
                setTextColor(Color.parseColor("#FEF3C7"))
                textSize = 13.5f
                typeface = YandexFonts.displayBold(context)
                letterSpacing = 0.06f
            }
            addView(ivW)
            addView(tvW)
        }

        tvErrorLine = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        heroCard.addView(badgesRow)
        heroCard.addView(coverStage)
        heroCard.addView(tvTrackTitle)
        heroCard.addView(tvTrackArtist)
        heroCard.addView(progressTrack)
        heroCard.addView(timeRow)
        heroCard.addView(ctrlRow)
        heroCard.addView(btnMyWaveHero)
        heroCard.addView(tvErrorLine)
        heroScroll.addView(heroCard)
        heroCardContainer.addView(heroScroll)

        // --- RIGHT MONOLITH: QUEUE / MOODS / SEARCH / COLLECTION / AUTH ---
        val rightContainer = FrameLayout(context).apply {
            bindPanelLens(this, slabSpec)
            clipChildren = true
            clipToPadding = true
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.59f)
        }

        viewWave = buildWaveTab()
        viewSearch = buildSearchTab()
        viewCollection = buildCollectionTab()
        viewAuth = buildAuthTab()

        rightContainer.addView(viewWave)
        rightContainer.addView(viewSearch)
        rightContainer.addView(viewCollection)
        rightContainer.addView(viewAuth)

        bodyRow.addView(heroCardContainer)
        bodyRow.addView(rightContainer)
        shell.addView(bodyRow)

        switchTab(activeTab)
        return rootFrame
    }

    private fun buildWaveTab(): LinearLayout {
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val moodTitle = TextView(context).apply {
            text = "НАСТРОЕНИЕ ВОЛНЫ"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            letterSpacing = 0.09f
            typeface = YandexFonts.displayBold(context)
            setPadding(dp(2), 0, 0, dp(8))
        }

        val moodRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val moods = listOf(
            Triple(YmWaveMood.ANY, R.drawable.sprite_mood_wave, "Моя волна"),
            Triple(YmWaveMood.ENERGETIC, R.drawable.sprite_mood_energy, "Бодрая"),
            Triple(YmWaveMood.FUN, R.drawable.sprite_mood_fun, "Весёлая"),
            Triple(YmWaveMood.CALM, R.drawable.sprite_mood_calm, "Спокойная"),
            Triple(YmWaveMood.SAD, R.drawable.sprite_mood_sad, "Грустная")
        )
        moods.forEachIndexed { idx, (mood, iconRes, shortLabel) ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = SpatialUiKit.makeGlassSurface(context, radiusDp = 18f)
                SpatialUiKit.disableSystemHighlight(this)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < moods.lastIndex) marginEnd = dp(8)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.045f else 1f).scaleY(if (hasFocus) 1.045f else 1f)
                        .setDuration(MotionPrefs.dur(context, 220L)).setInterpolator(focusSpring).start()
                }
                setOnClickListener { startMyWave(mood.key) }

                val iv = ImageView(context).apply {
                    setSpriteDrawable(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        bottomMargin = dp(5)
                    }
                }
                val tv = TextView(context).apply {
                    text = shortLabel
                    setTextColor(Color.parseColor("#F1F5F9"))
                    textSize = 11f
                    typeface = YandexFonts.textMedium(context)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(iv)
                addView(tv)
            }
            moodBtns[mood.key] = card
            moodRow.addView(card)
        }

        val qHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(9))
        }
        tvWaveStatus = TextView(context).apply {
            text = "Очередь воспроизведения"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14.5f
            typeface = YandexFonts.displayBold(context)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRestartWave = makeMonolithIconTextPill(R.drawable.ic_refresh, "Перезапустить", Color.parseColor("#FDE68A")) {
            startMyWave(lastSnapshot.waveMood)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        qHeader.addView(tvWaveStatus)
        qHeader.addView(btnRestartWave)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        waveQueueList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(waveQueueList)

        panel.addView(moodTitle)
        panel.addView(moodRow)
        panel.addView(qHeader)
        panel.addView(scroll)
        return panel
    }

    private fun buildSearchTab(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        etSearch = EditText(context).apply {
            hint = "Исполнитель, трек или настроение..."
            setHintTextColor(Color.parseColor("#88CBD5E1"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(10), dp(14), dp(10))
            SpatialUiKit.disableSystemHighlight(this)
            background = SpatialUiKit.makeGlassSurface(
                context,
                radiusDp = 16f,
                normalTop = Color.parseColor("#24FFFFFF"),
                normalBottom = Color.parseColor("#16FFFFFF"),
                focusedTop = Color.parseColor("#38FFFFFF"),
                focusedBottom = Color.parseColor("#22FFFFFF"),
                normalStroke = Color.parseColor("#38FFFFFF"),
                focusedStroke = Color.parseColor("#A8BAE6FD")
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    performSearch()
                    true
                } else {
                    false
                }
            }
        }
        val btnFind = makeMonolithIconTextPill(R.drawable.ic_search, "Найти", Color.parseColor("#7DD3FC")) { performSearch() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        searchRow.addView(etSearch)
        searchRow.addView(btnFind)

        tvSearchStatus = TextView(context).apply {
            text = "Введите запрос или скажите «${LiveOrbState.effectiveName()}, включи Кино»"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(8))
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        searchList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(searchList)

        panel.addView(searchRow)
        panel.addView(tvSearchStatus)
        panel.addView(scroll)
        return panel
    }

    private fun buildCollectionTab(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        btnColLikes = makeMonolithIconTextPill(R.drawable.ic_heart_filled, "Мне нравится", null) {
            collectionMode = "likes"
            loadCollection()
        }
        btnColChart = makeMonolithIconTextPill(R.drawable.ic_chart, "Топ Чарт", null) {
            collectionMode = "chart"
            loadCollection()
        }
        btnColPlayAll = makeMonolithIconTextPill(R.drawable.ic_play, "Включить список", Color.parseColor("#FDE68A")) {
            if (collectionTracks.isNotEmpty()) {
                val lbl = if (collectionMode == "chart") "Топ Чарт" else "Мне нравится"
                player()?.playTrackList(collectionTracks, 0, collectionMode, lbl)
                switchTab("wave")
            }
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        topRow.addView(btnColLikes)
        topRow.addView(btnColChart)
        topRow.addView(btnColPlayAll)

        tvColStatus = TextView(context).apply {
            text = "Загрузка..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(8))
        }

        collectionList = ListView(context).apply {
            divider = null
            dividerHeight = dp(6)
            isVerticalScrollBarEnabled = true
            selector = GradientDrawable().apply {
                setColor(Color.parseColor("#347DD3FC"))
                setStroke(dp(2), Color.parseColor("#A0BAE6FD"))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        panel.addView(topRow)
        panel.addView(tvColStatus)
        panel.addView(collectionList)
        return panel
    }

    private fun buildAuthTab(): ScrollView {
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val tvH1 = TextView(context).apply {
            text = "АККАУНТ ЯНДЕКС МУЗЫКИ"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            letterSpacing = 0.09f
            typeface = YandexFonts.displayBold(context)
            setPadding(0, 0, 0, dp(6))
        }
        tvAuthSummary = TextView(context).apply {
            text = "Проверка авторизации..."
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13.5f
            setPadding(0, 0, 0, dp(12))
        }

        val authBtns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        val btnBrowserLogin = makeMonolithIconTextPill(R.drawable.ic_open_external, "Войти через Яндекс", Color.parseColor("#7DD3FC")) {
            openPassportOAuthDialog()
        }
        val btnManualToken = makeMonolithIconTextPill(R.drawable.ic_key, "OAuth токен", null) {
            promptOAuthTokenDialog()
        }
        btnAuthLogout = makeMonolithIconTextPill(R.drawable.ic_hw_mic_off, "Выйти", Color.parseColor("#FCA5A5")) {
            player()?.logout()
            Toast.makeText(context, "Вышли из аккаунта Яндекс Музыки", Toast.LENGTH_SHORT).show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        authBtns.addView(btnBrowserLogin)
        authBtns.addView(btnManualToken)
        authBtns.addView(btnAuthLogout)

        val tvH2 = TextView(context).apply {
            text = "КАЧЕСТВО АУДИО"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            letterSpacing = 0.09f
            typeface = YandexFonts.displayBold(context)
            setPadding(0, dp(4), 0, dp(6))
        }
        val qualRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        btnQualityNormal = makeMonolithTextPill("Стандарт (MP3/AAC)") {
            player()?.let {
                it.api.setPreferredQuality("normal")
                it.emitSnapshot(forceDaemon = false)
            }
            Toast.makeText(context, "Качество: Стандарт (MP3/AAC)", Toast.LENGTH_SHORT).show()
        }
        qualRow.addView(btnQualityNormal)

        val qrBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = SpatialUiKit.makeGlassSurface(context, radiusDp = 18f)
        }
        val qrWrap = LinearLayout(context).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(14)
            }
        }
        ivWebQr = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrWrap.addView(ivWebQr)

        tvWebHint = TextView(context).apply {
            text = "Быстрая отправка токена с телефона:\nОткройте веб-пульт колонки и перейдите на вкладку «Пульт, Музыка & ТВ»."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        qrBox.addView(qrWrap)
        qrBox.addView(tvWebHint)

        panel.addView(tvH1)
        panel.addView(tvAuthSummary)
        panel.addView(authBtns)
        panel.addView(tvH2)
        panel.addView(qualRow)
        panel.addView(qrBox)
        scroll.addView(panel)

        updateWebQrInfo()
        return scroll
    }

    fun open(initialTab: String? = null) {
        if (isOpen && isEmbeddedOverlay) {
            if (!initialTab.isNullOrBlank()) switchTab(initialTab)
            return
        }
        isOpen = true
        if (!initialTab.isNullOrBlank()) {
            switchTab(initialTab)
        }
        refreshFromService()
        updateWebQrInfo()

        rootOverlay.animate().cancel()
        rootOverlay.visibility = View.VISIBLE
        rootOverlay.alpha = 0f
        rootOverlay.translationY = dp(76).toFloat()
        rootOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionPrefs.dur(context, 480L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (isOpen) focusDefaultControl()
            }
            .start()
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen && rootOverlay.visibility != View.VISIBLE) return
        isOpen = false
        passportDialog?.dismiss()
        oauthTokenDialog?.dismiss()
        rootOverlay.animate().cancel()

        if (immediate) {
            rootOverlay.visibility = View.GONE
            rootOverlay.alpha = 0f
            rootOverlay.translationY = 0f
            return
        }
        rootOverlay.animate()
            .alpha(0f)
            .translationY(dp(64).toFloat())
            .setDuration(MotionPrefs.dur(context, 390L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!isOpen) {
                    rootOverlay.visibility = View.GONE
                    rootOverlay.translationY = 0f
                }
            }
            .start()
    }

    fun focusDefaultControl() {
        val target: View = when (activeTab) {
            "search" -> etSearch
            else -> btnMyWaveHero
        }
        target.post {
            if (isOpen) target.requestFocus()
        }
    }

    fun release() {
        passportDialog?.dismiss()
        oauthTokenDialog?.dismiss()
        lensCleanups.forEach { it() }
        lensCleanups.clear()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isOpen) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    onCloseRequested()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    doPlayerAction("PLAY_PAUSE")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    doPlayerAction("NEXT")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    doPlayerAction("PREV")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    doPlayerAction("STOP")
                    return true
                }
            }
        }
        return false
    }

    fun switchTab(tab: String) {
        activeTab = tab
        viewWave.visibility = if (tab == "wave") View.VISIBLE else View.GONE
        viewSearch.visibility = if (tab == "search") View.VISIBLE else View.GONE
        viewCollection.visibility = if (tab == "collection") View.VISIBLE else View.GONE
        viewAuth.visibility = if (tab == "auth") View.VISIBLE else View.GONE

        tabWave.isSelected = (tab == "wave")
        tabSearch.isSelected = (tab == "search")
        tabCollection.isSelected = (tab == "collection")
        tabAuth.isSelected = (tab == "auth")

        updateHeaderLensSelected(tabWave, tab == "wave", Color.parseColor("#6A4B3814"))
        updateHeaderLensSelected(tabSearch, tab == "search", Color.parseColor("#6216324E"))
        updateHeaderLensSelected(tabCollection, tab == "collection", Color.parseColor("#624A1A36"))
        updateHeaderLensSelected(tabAuth, tab == "auth", Color.parseColor("#62203048"))

        when (tab) {
            "collection" -> loadCollection()
            "auth" -> updateWebQrInfo()
            "search" -> etSearch.post { if (isOpen) etSearch.requestFocus() }
        }
    }

    private fun updateHeaderLensSelected(view: View, selected: Boolean, activeTint: Int) {
        if (spatialBackdrop != null) {
            val base = if (selected) activeTint else Color.parseColor("#2A0C1220")
            val rim = if (selected) 0.72f else 0.30f
            spatialBackdrop.updateLensSpec(view) { spec ->
                spec.copy(baseTint = base, rimAlpha = rim)
            }
        }
    }

    fun refreshFromService() {
        val p = player()
        if (p != null) {
            renderSnapshot(p.getSnapshot())
        } else {
            handler.postDelayed({
                player()?.let { renderSnapshot(it.getSnapshot()) }
            }, 350L)
        }
    }

    fun updateWebQrInfo() {
        if (!::ivWebQr.isInitialized || !::tvWebHint.isInitialized) return
        val ip = LocalNetworkAddress.find(context)
        val url = SetupAddress.webUrl(ip)
        (ivWebQr.parent as? View)?.visibility = if (url == null) View.GONE else View.VISIBLE
        if (url == null) {
            ivWebQr.setImageDrawable(null)
            tvWebHint.text = "Подключите станцию к сети, чтобы открыть веб-пульт на телефоне или ПК."
        } else {
            tvWebHint.text = "Быстрый вход через веб-пульт ($url):\nНа телефоне или ПК откройте вкладку «Пульт, Музыка & ТВ» и вставьте OAuth-токен — плеер сразу подхватит аккаунт."
            ivWebQr.setImageBitmap(QrGenerator.generateBitmap(url, dp(92)))
        }
    }

    fun renderSnapshot(s: YmPlayerSnapshot, cachedCoverBmp: Bitmap? = null) {
        lastSnapshot = s

        tvAcctBadge.text = when {
            s.account.loggedIn && s.account.login.isNotBlank() ->
                "● @${s.account.login}" + if (s.account.hasPlus) " · Плюс" else ""
            s.account.loggedIn -> "● Аккаунт подключён"
            else -> "○ Войти"
        }
        tvAcctBadge.isSelected = s.account.loggedIn

        tvSourceBadge.text = s.sourceLabel.uppercase()
        if (s.codecLabel.isNotBlank()) {
            tvCodecBadge.text = s.codecLabel
            tvCodecBadge.visibility = View.VISIBLE
        } else {
            tvCodecBadge.visibility = View.GONE
        }

        val tr = s.currentTrack
        if (tr != null) {
            tvTrackTitle.text = tr.title
            tvTrackArtist.text = if (tr.album.isNotBlank()) "${tr.artists} · ${tr.album}" else tr.artists

            val cUrl = tr.coverUrl(400)
            if (cachedCoverBmp != null && !cachedCoverBmp.isRecycled && lastRenderedCoverUrl != cUrl) {
                setCoverArt(cachedCoverBmp)
            }
            if (cUrl != lastRenderedCoverUrl) {
                lastRenderedCoverUrl = cUrl
                if (cUrl.isBlank()) {
                    setCoverArt(null)
                } else {
                    thread(isDaemon = true) {
                        val bmp = (player()?.api ?: YandexMusicApi(context)).loadCoverBitmap(cUrl, 400)
                        if (bmp != null) {
                            handler.post {
                                if (lastRenderedCoverUrl == cUrl) {
                                    setCoverArt(bmp)
                                    onCoverBitmapLoaded?.invoke(bmp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (lastRenderedCoverUrl.isNotEmpty()) {
                lastRenderedCoverUrl = ""
                setCoverArt(null)
            }
            tvTrackTitle.text = if (s.buffering) "Загрузка..." else "Готово к воспроизведению"
            tvTrackArtist.text = if (s.account.loggedIn) {
                "Нажмите «Моя волна» или выберите трек"
            } else {
                "Войдите в Яндекс Музыку во вкладке «Вход»"
            }
        }

        val dur = max(0L, s.durationMs)
        val pos = max(0L, s.positionMs).coerceAtMost(max(1L, dur))
        progressTrack.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
        tvTimePos.text = formatMs(pos)
        tvTimeDur.text = formatMs(dur)

        val ppIcon = context.getDrawable(
            when {
                s.buffering -> R.drawable.ic_refresh
                s.playing -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
        )?.mutate()?.apply {
            setTint(Color.parseColor("#FDE68A"))
        }
        btnPlayPause.icon.setImageDrawable(ppIcon)

        val liked = (tr?.liked == true)
        btnLike.icon.setImageDrawable(context.getDrawable(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline))
        btnLike.root.isSelected = liked

        if (s.lastError.isNotBlank()) {
            tvErrorLine.text = s.lastError.removePrefix("AUTH_REQUIRED:").trim()
            tvErrorLine.visibility = View.VISIBLE
        } else {
            tvErrorLine.visibility = View.GONE
        }

        for ((k, pill) in moodBtns) {
            pill.isSelected = (s.sourceType == "my_wave" && s.waveMood == k)
        }

        val qSig = "${tr?.id}|${s.queuePreview.joinToString(",") { it.id }}"
        if (qSig != lastQueueSignature) {
            lastQueueSignature = qSig
            tvWaveStatus.text = if (s.queuePreview.isEmpty()) {
                "Очередь пуста — нажмите «Моя волна»"
            } else {
                "${s.sourceLabel} · в очереди ${s.queueSize} треков"
            }
            renderTrackList(waveQueueList, s.queuePreview, activeTrackId = tr?.id) { idx ->
                val globalIdx = max(0, s.currentIndex) + idx
                player()?.playQueueIndex(globalIdx)
            }
        }

        tvAuthSummary.text = if (s.account.loggedIn) {
            "Вход выполнен: ${s.account.fullName.ifBlank { s.account.login }} (@${s.account.login}, uid=${s.account.uid})" +
                if (s.account.hasPlus) "\nСтатус: Яндекс Плюс активен (доступна Моя волна и полные треки)" else "\nСтатус: Базовый аккаунт"
        } else {
            "Аккаунт не подключён.\nЧтобы работала персональная «Моя волна» и «Мне нравится», войдите через Яндекс (QR/пароль) или вставьте OAuth-токен."
        }
        btnAuthLogout.visibility = if (s.account.loggedIn) View.VISIBLE else View.GONE
        btnQualityNormal.isSelected = true
    }

    fun setCoverArt(bmp: Bitmap?) {
        if (bmp != null && !bmp.isRecycled) {
            ivCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivCover.background = GradientDrawable().apply {
                setColor(Color.parseColor("#111927"))
                cornerRadius = dp(22).toFloat()
            }
            ivCover.setImageBitmap(bmp)
            viewCoverRim.visibility = View.VISIBLE
        } else {
            ivCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivCover.background = null
            ivCover.setSpriteDrawable(R.drawable.sprite_ym_emblem)
            viewCoverRim.visibility = View.VISIBLE
        }
    }

    private fun startMyWave(moodKey: String) {
        val p = player()
        if (p != null) {
            p.playMyWave(moodKey, "", openUiOnAuthNeeded = false)
        } else {
            sendServiceCmd("play_my_wave") { putExtra("mood", moodKey) }
        }
        switchTab("wave")
    }

    private fun doPlayerAction(act: String) {
        val p = player()
        if (p != null) {
            when (act) {
                "PLAY_PAUSE" -> p.togglePlayPause()
                "NEXT" -> p.next()
                "PREV" -> p.prev()
                "LIKE" -> p.toggleLikeCurrent()
                "DISLIKE" -> p.dislikeCurrent()
                "STOP" -> p.stop()
            }
        } else {
            sendServiceCmd("action") { putExtra("action", act) }
        }
    }

    private fun performSearch() {
        val q = etSearch.text?.toString()?.trim() ?: ""
        if (q.isEmpty()) return
        tvSearchStatus.text = "Ищем «$q»..."
        searchList.removeAllViews()
        thread(isDaemon = true) {
            try {
                val api = player()?.api ?: YandexMusicApi(context)
                val res = api.searchTracks(q, limit = 30)
                handler.post {
                    searchTracks = res
                    tvSearchStatus.text = if (res.isEmpty()) "По запросу «$q» ничего не найдено" else "Найдено треков: ${res.size}"
                    renderTrackList(searchList, res, activeTrackId = lastSnapshot.currentTrack?.id) { idx ->
                        player()?.playTrackList(res, idx, "search", "Поиск: $q")
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    tvSearchStatus.text = "Ошибка поиска: ${t.message}"
                }
            }
        }
    }

    private fun loadCollection() {
        val requestedMode = collectionMode
        val requestId = ++collectionRequestId
        btnColLikes.isSelected = (collectionMode == "likes")
        btnColChart.isSelected = (collectionMode == "chart")
        tvColStatus.text = if (collectionMode == "chart") "Загружаем Чарт..." else "Загружаем «Мне нравится»..."
        collectionTracks = emptyList()
        collectionList.adapter = null

        thread(isDaemon = true) {
            try {
                val api = player()?.api ?: YandexMusicApi(context)
                val tracks: List<YmTrack>
                val title: String
                if (requestedMode == "chart") {
                    val pair = api.getChartTracks()
                    title = "${pair.first} (${pair.second.size} треков)"
                    tracks = pair.second
                } else {
                    if (!api.hasToken()) {
                        handler.post {
                            if (requestId != collectionRequestId) return@post
                            collectionTracks = emptyList()
                            tvColStatus.text = "Войдите в аккаунт во вкладке «Вход», чтобы видеть «Мне нравится» (или откройте Топ Чарт)."
                        }
                        return@thread
                    }
                    var acct = api.getCachedAccount()
                    if (!acct.loggedIn) acct = api.refreshAccount()
                    val collection = api.getLikedCollection(acct.uid)
                    tracks = collection.tracks
                    title = when {
                        collection.total == 0 -> "Список «Мне нравится» пуст"
                        collection.total == null -> "Загружено треков: ${tracks.size}"
                        collection.total == tracks.size -> "Мне нравится · ${collection.total} треков"
                        else -> "Всего: ${collection.total} · загружено: ${tracks.size}"
                    }
                }
                handler.post {
                    if (requestId != collectionRequestId) return@post
                    collectionTracks = tracks
                    tvColStatus.text = title
                    val srcLbl = if (requestedMode == "chart") "Топ Чарт" else "Мне нравится"
                    collectionList.adapter = object : BaseAdapter() {
                        override fun getCount() = tracks.size
                        override fun getItem(position: Int) = tracks[position]
                        override fun getItemId(position: Int) = position.toLong()
                        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                            val row = (recycled as? LinearLayout) ?: LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(14), dp(10), dp(14), dp(10))
                                background = SpatialUiKit.makeGlassSurface(context, radiusDp = 16f)
                                SpatialUiKit.disableSystemHighlight(this)
                                addView(TextView(context).apply {
                                    setTextColor(Color.parseColor("#F8FAFC"))
                                    textSize = 14f
                                    typeface = YandexFonts.textMedium(context)
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                })
                                addView(TextView(context).apply {
                                    setTextColor(Color.parseColor("#94A3B8"))
                                    textSize = 12f
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                })
                            }
                            val track = tracks[position]
                            (row.getChildAt(0) as TextView).text = "${position + 1}. ${track.title}"
                            (row.getChildAt(1) as TextView).text = if (track.album.isBlank()) track.artists else "${track.artists} · ${track.album}"
                            row.isSelected = track.id == lastSnapshot.currentTrack?.id
                            return row
                        }
                    }
                    collectionList.setOnItemClickListener { _, _, idx, _ ->
                        player()?.playTrackList(tracks, idx, requestedMode, srcLbl)
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    if (requestId != collectionRequestId) return@post
                    tvColStatus.text = "Ошибка: ${t.message}"
                }
            }
        }
    }

    private fun renderTrackList(
        container: LinearLayout,
        tracks: List<YmTrack>,
        activeTrackId: String?,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        tracks.forEachIndexed { idx, tr ->
            val isActive = (tr.id == activeTrackId)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(9), dp(13), dp(9))
                background = SpatialUiKit.makeGlassSurface(context, radiusDp = 16f)
                SpatialUiKit.disableSystemHighlight(this)
                isSelected = isActive
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                setOnClickListener { onSelect(idx) }
            }

            if (isActive) {
                val ivPlay = ImageView(context).apply {
                    val d = context.getDrawable(R.drawable.ic_play)?.mutate()
                    d?.setTint(Color.parseColor("#FDE68A"))
                    setImageDrawable(d)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                        marginStart = dp(4)
                        marginEnd = dp(12)
                    }
                }
                row.addView(ivPlay)
            } else {
                val tvIdx = TextView(context).apply {
                    text = "${idx + 1}"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12.5f
                    typeface = YandexFonts.displayBold(context)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    }
                }
                row.addView(tvIdx)
            }

            val txtCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvT = TextView(context).apply {
                text = tr.title
                setTextColor(Color.parseColor(if (isActive) "#FDE68A" else "#F8FAFC"))
                textSize = 14f
                typeface = if (isActive) YandexFonts.displayBold(context) else YandexFonts.textMedium(context)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvA = TextView(context).apply {
                text = if (tr.album.isNotBlank()) "${tr.artists} · ${tr.album}" else tr.artists
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            txtCol.addView(tvT)
            txtCol.addView(tvA)
            row.addView(txtCol)

            if (tr.liked) {
                val ivH = ImageView(context).apply {
                    setImageResource(R.drawable.ic_heart_filled)
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                        marginEnd = dp(8)
                    }
                }
                row.addView(ivH)
            }

            val tvDur = TextView(context).apply {
                text = formatMs(tr.durationMs)
                setTextColor(Color.parseColor(if (tr.liked) "#F472B6" else "#94A3B8"))
                textSize = 12f
                fontFeatureSettings = "tnum"
            }
            row.addView(tvDur)
            container.addView(row)
        }
    }

    private fun promptOAuthTokenDialog() {
        oauthTokenDialog?.dismiss()
        val et = EditText(context).apply {
            hint = "Вставьте токен (y0_...) или URL после входа"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        oauthTokenDialog = AlertDialog.Builder(context)
            .setTitle("Вход по OAuth токену Яндекс Музыки")
            .setMessage("Вставьте OAuth-токен (можно также отправить с телефона через Веб-пульт колонки).")
            .setView(et)
            .setPositiveButton("Войти") { _, _ ->
                val raw = et.text?.toString()?.trim() ?: ""
                if (raw.isNotEmpty()) {
                    val p = player()
                    if (p != null) {
                        p.setOAuthToken(raw, autoResumeMyWave = false)
                    } else {
                        sendServiceCmd("oauth") { putExtra("token", raw) }
                    }
                    Toast.makeText(context, "Проверяем токен...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun openPassportOAuthDialog() {
        if (passportDialog?.isShowing == true) return
        val dlgHolder = arrayOfNulls<AlertDialog>(1)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
        }

        val wv = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val cursorView = CursorOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        var cursorEnabled = true
        var cx = 480f
        var cy = 270f
        var downTime = 0L

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.setInitialScale(66)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 78
        }

        fun tryExtractToken(urlStr: String?): Boolean {
            if (urlStr.isNullOrBlank()) return false
            if (urlStr.contains("access_token=")) {
                val tok = YandexMusicApi.normalizeOAuthToken(urlStr)
                if (tok.isNotEmpty()) {
                    try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                    player()?.setOAuthToken(tok, autoResumeMyWave = false)
                    handler.post {
                        Toast.makeText(context, "Авторизация Яндекс Музыки успешна!", Toast.LENGTH_SHORT).show()
                        dlgHolder[0]?.dismiss()
                    }
                    return true
                }
            }
            return false
        }

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (tryExtractToken(u)) return true
                return u.lowercase().startsWith(YandexMusicApi.OAUTH_REDIRECT_PREFIX)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (tryExtractToken(url)) return true
                return url?.lowercase()?.startsWith(YandexMusicApi.OAUTH_REDIRECT_PREFIX) == true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tryExtractToken(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tryExtractToken(url)
                view?.evaluateJavascript(
                    "(function(){try{var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=1120,initial-scale=0.66,minimum-scale=0.4,maximum-scale=2.0,user-scalable=yes';if(!window.__osZoom){window.__osZoom=0.76;}document.documentElement.style.zoom=String(window.__osZoom);}catch(e){}})();",
                    null
                )
            }
        }

        root.addView(wv)
        root.addView(cursorView)

        val topOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0F172A"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            }
        }
        val tvCursorHint = TextView(context).apply {
            text = "MENU: курсор  ·  BACK: закрыть"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            setPadding(0, 0, dp(8), 0)
        }
        val btnZoomOut = TextView(context).apply {
            text = "🔍-"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = SpatialUiKit.makeGlassSurface(context, radiusDp = 999f)
            setOnClickListener {
                wv.evaluateJavascript(
                    "(function(){window.__osZoom=Math.max(0.45,(window.__osZoom||0.76)-0.1);document.documentElement.style.zoom=String(window.__osZoom);})();",
                    null
                )
            }
        }
        val btnZoomIn = TextView(context).apply {
            text = "🔍+"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = SpatialUiKit.makeGlassSurface(context, radiusDp = 999f)
            setOnClickListener {
                wv.evaluateJavascript(
                    "(function(){window.__osZoom=Math.min(1.3,(window.__osZoom||0.76)+0.1);document.documentElement.style.zoom=String(window.__osZoom);})();",
                    null
                )
            }
        }
        val btnClose = TextView(context).apply {
            text = "Закрыть"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = SpatialUiKit.makeGlassSurface(context, radiusDp = 999f)
            setOnClickListener { dlgHolder[0]?.dismiss() }
        }
        topOverlay.addView(tvCursorHint)
        topOverlay.addView(btnZoomOut)
        topOverlay.addView(btnZoomIn)
        topOverlay.addView(btnClose)
        root.addView(topOverlay)

        root.post {
            cx = root.width * 0.5f
            cy = root.height * 0.5f
            cursorView.updatePos(cx, cy, cursorEnabled)
        }

        val dlg = AlertDialog.Builder(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(root)
            .setOnDismissListener {
                passportDialog = null
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Throwable) {}
            }
            .create()

        dlg.setOnKeyListener { _, keyCode, event ->
            val act = event.action
            if (keyCode == KeyEvent.KEYCODE_MENU && act == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cursorEnabled = !cursorEnabled
                cursorView.updatePos(cx, cy, cursorEnabled)
                Toast.makeText(context, if (cursorEnabled) "Курсор: ВКЛ" else "Режим ввода: ВКЛ", Toast.LENGTH_SHORT).show()
                return@setOnKeyListener true
            }
            if (cursorEnabled) {
                val w = max(1f, root.width.toFloat())
                val h = max(1f, root.height.toFloat())
                val step = dp(if (event.repeatCount > 2) 34 else if (event.repeatCount > 0) 22 else 14).toFloat()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (act == KeyEvent.ACTION_DOWN) { cx = max(4f, cx - step); cursorView.updatePos(cx, cy, true) }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (act == KeyEvent.ACTION_DOWN) { cx = min(w - 4f, cx + step); cursorView.updatePos(cx, cy, true) }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (act == KeyEvent.ACTION_DOWN) { cy = max(4f, cy - step); cursorView.updatePos(cx, cy, true) }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (act == KeyEvent.ACTION_DOWN) { cy = min(h - 4f, cy + step); cursorView.updatePos(cx, cy, true) }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (act == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            downTime = SystemClock.uptimeMillis()
                            val ev = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
                            wv.dispatchTouchEvent(ev)
                            ev.recycle()
                        } else if (act == KeyEvent.ACTION_UP) {
                            val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cx, cy, 0)
                            wv.dispatchTouchEvent(ev)
                            ev.recycle()
                        }
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        dlgHolder[0] = dlg
        passportDialog = dlg
        dlg.show()
        wv.loadUrl(YandexMusicApi.OAUTH_AUTHORIZE_URL)
    }

    private fun makeHeaderTextPill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor("#F1F5F9"))
        textSize = 12f
        typeface = YandexFonts.displayMedium(context)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        bindPanelLens(
            this,
            SpatialLensSpec(
                radiusDp = 999f,
                baseTint = Color.parseColor("#2A0C1220"),
                focusTint = Color.parseColor("#541A2C4A"),
                bevelDp = 12f,
                refractDp = 10f,
                chromaDp = 1.8f,
                shadowDp = 12f,
                rimAlpha = 0.30f,
                focusRimAlpha = 0.86f,
                blurMix = 0.88f
            )
        )
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(8)
        }
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.055f else 1f).scaleY(if (hasFocus) 1.055f else 1f)
                .setDuration(MotionPrefs.dur(context, 220L)).setInterpolator(focusSpring).start()
        }
        setOnClickListener { onClick() }
    }

    private fun makeHeaderIconPill(iconRes: Int, label: String, iconTint: Int?, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(15), dp(7))
            bindPanelLens(
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#2A0C1220"),
                    focusTint = Color.parseColor("#541A2C4A"),
                    bevelDp = 12f,
                    refractDp = 10f,
                    chromaDp = 1.8f,
                    shadowDp = 12f,
                    rimAlpha = 0.30f,
                    focusRimAlpha = 0.86f,
                    blurMix = 0.88f
                )
            )
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.055f else 1f).scaleY(if (hasFocus) 1.055f else 1f)
                    .setDuration(MotionPrefs.dur(context, 220L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(context).apply {
                val d = context.getDrawable(iconRes)?.mutate()
                if (d != null && iconTint != null) d.setTint(iconTint)
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                    marginEnd = dp(6)
                }
            }
            val tv = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 12.5f
                typeface = YandexFonts.displayMedium(context)
                maxLines = 1
            }
            addView(iv)
            addView(tv)
        }

    private fun makeMonolithTextPill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor("#F1F5F9"))
        textSize = 12.5f
        typeface = YandexFonts.displayMedium(context)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = SpatialUiKit.makeGlassSurface(context, radiusDp = 999f)
        SpatialUiKit.disableSystemHighlight(this)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(8)
        }
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(MotionPrefs.dur(context, 210L)).setInterpolator(focusSpring).start()
        }
        setOnClickListener { onClick() }
    }

    private fun makeMonolithIconTextPill(iconRes: Int, label: String, iconTint: Int?, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(14), dp(7))
            background = SpatialUiKit.makeGlassSurface(context, radiusDp = 999f)
            SpatialUiKit.disableSystemHighlight(this)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(MotionPrefs.dur(context, 210L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(context).apply {
                val d = context.getDrawable(iconRes)?.mutate()
                if (d != null && iconTint != null) d.setTint(iconTint)
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                    marginEnd = dp(6)
                }
            }
            val tv = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 12.5f
                typeface = YandexFonts.displayMedium(context)
                maxLines = 1
            }
            addView(iv)
            addView(tv)
        }

    private fun makeMonolithCtrlIconBtn(
        iconRes: Int,
        tintColor: Int?,
        hero: Boolean = false,
        onClick: () -> Unit
    ): IconBtnViews {
        val root = FrameLayout(context).apply {
            background = if (hero) {
                SpatialUiKit.makeGlassAccentSurface(context, radiusDp = 999f)
            } else {
                SpatialUiKit.makeGlassSurface(context, radiusDp = 999f)
            }
            SpatialUiKit.disableSystemHighlight(this)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(MotionPrefs.dur(context, 230L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(context).apply {
            val d = context.getDrawable(iconRes)?.mutate()
            if (d != null && tintColor != null) d.setTint(tintColor)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val iconSize = if (hero) dp(21) else dp(18)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        root.addView(iv)
        return IconBtnViews(root, iv)
    }

    private fun formatMs(ms: Long): String {
        val tot = max(0L, ms / 1000L)
        return "%d:%02d".format(tot / 60L, tot % 60L)
    }

    private class CursorOverlayView(ctx: Context) : View(ctx) {
        private var cx = 0f
        private var cy = 0f
        private var show = false
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCE8C686")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        init {
            isFocusable = false
            isClickable = false
        }

        fun updatePos(x: Float, y: Float, visible: Boolean) {
            cx = x
            cy = y
            show = visible
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!show) return
            canvas.drawCircle(cx, cy, 11f, outer)
            canvas.drawCircle(cx, cy, 4.5f, inner)
        }
    }
}
