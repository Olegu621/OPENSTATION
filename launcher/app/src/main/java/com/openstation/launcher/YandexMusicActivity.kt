package com.openstation.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class YandexMusicActivity : Activity() {

    companion object {
        private const val TAG = "YandexMusicAct"
        const val ACTION_MUSIC_CMD = "com.openstation.launcher.MUSIC_CMD"
    }

    private data class IconBtnViews(
        val root: FrameLayout,
        val icon: ImageView
    )

    private val handler = Handler(Looper.getMainLooper())
    private var activeTab = "wave" // "wave", "search", "collection", "auth"
    private var collectionMode = "likes" // "likes", "chart"

    private lateinit var tvAcctBadge: TextView
    private lateinit var tabWave: LinearLayout
    private lateinit var tabSearch: LinearLayout
    private lateinit var tabCollection: LinearLayout
    private lateinit var tabAuth: LinearLayout

    // Hero Player views (BORK Vinyl × Duo Max)
    private lateinit var ivVinyl: ImageView
    private lateinit var ivCover: ImageView
    private lateinit var viewCoverRim: View
    private var vinylAnimator: ObjectAnimator? = null
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

    // Right column containers
    private lateinit var rightContainer: FrameLayout
    private lateinit var viewWave: LinearLayout
    private lateinit var viewSearch: LinearLayout
    private lateinit var viewCollection: LinearLayout
    private lateinit var viewAuth: ScrollView

    // Wave tab views
    private val moodBtns = LinkedHashMap<String, LinearLayout>()
    private lateinit var waveQueueList: LinearLayout
    private lateinit var tvWaveStatus: TextView

    // Search tab views
    private lateinit var etSearch: EditText
    private lateinit var searchList: LinearLayout
    private lateinit var tvSearchStatus: TextView

    // Collection tab views
    private lateinit var btnColLikes: LinearLayout
    private lateinit var btnColChart: LinearLayout
    private lateinit var btnColPlayAll: LinearLayout
    private lateinit var collectionList: LinearLayout
    private lateinit var tvColStatus: TextView

    // Auth & settings tab views
    private lateinit var tvAuthSummary: TextView
    private lateinit var btnAuthLogout: LinearLayout
    private lateinit var btnQualityNormal: TextView
    private lateinit var btnQualityLossless: TextView
    private lateinit var ivWebQr: ImageView
    private lateinit var tvWebHint: TextView

    private var lastRenderedCoverUrl = ""
    private var lastQueueSignature = ""
    private var collectionTracks: List<YmTrack> = emptyList()
    private var searchTracks: List<YmTrack> = emptyList()
    private var lastSnapshot: YmPlayerSnapshot = YmPlayerSnapshot()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonStr = intent?.getStringExtra(YandexMusicPlayer.EXTRA_SNAPSHOT_JSON) ?: return
            try {
                val snap = YmPlayerSnapshot.fromJson(org.json.JSONObject(jsonStr))
                renderSnapshot(snap)
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StationAudioService.ensureStarted(this)

        buildUi()
        handleIntentExtras(intent)

        try {
            registerReceiver(stateReceiver, IntentFilter(YandexMusicPlayer.ACTION_MUSIC_STATE))
        } catch (_: Throwable) {}

        refreshFromService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        StationAudioService.ensureStarted(this)
        refreshFromService()
        updateVinylAnimation()
    }

    override fun onPause() {
        vinylAnimator?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        vinylAnimator?.cancel()
        try { unregisterReceiver(stateReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun handleIntentExtras(intent: Intent?) {
        val tok = intent?.getStringExtra("oauth_token")
        if (!tok.isNullOrBlank()) {
            player()?.setOAuthToken(tok, autoResumeMyWave = false)
        }
        val tab = intent?.getStringExtra("tab")
        if (!tab.isNullOrBlank() && tab in listOf("wave", "search", "collection", "auth")) {
            switchTab(tab)
        }
    }

    private fun player(): YandexMusicPlayer? = StationAudioService.instance?.musicPlayer

    private fun refreshFromService() {
        val p = player()
        if (p != null) {
            renderSnapshot(p.getSnapshot())
        } else {
            handler.postDelayed({
                player()?.let { renderSnapshot(it.getSnapshot()) }
            }, 350L)
        }
    }

    private fun sendServiceCmd(cmd: String, extras: (Intent.() -> Unit)? = null) {
        val i = Intent(this, StationAudioService::class.java).apply {
            action = StationAudioService.ACTION_MUSIC_CMD
            putExtra("cmd", cmd)
            extras?.invoke(this)
        }
        try {
            startForegroundService(i)
        } catch (_: Throwable) {}
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val rootFrame = FrameLayout(this)

        // Wallpaper Backdrop + subtle dark acoustic scrim
        val ivBg = ImageView(this).apply {
            setImageResource(R.drawable.bg_tv_stage)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#66050811"),
                    Color.parseColor("#2A050811"),
                    Color.parseColor("#77050811")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(ivBg)
        rootFrame.addView(scrim)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(root)

        // ===== 1. TOP BAR =====
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ivBrandEmblem = ImageView(this).apply {
            setImageResource(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(10)
            }
        }
        val tvLogo = TextView(this).apply {
            text = "ЯНДЕКС МУЗЫКА"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 18.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
        }
        tvAcctBadge = makeTextPill("Проверка...") { switchTab("auth") }.apply {
            textSize = 11.5f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
        }
        brandCol.addView(ivBrandEmblem)
        brandCol.addView(tvLogo)
        brandCol.addView(tvAcctBadge)

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabWave = makeIconTextPill(R.drawable.ic_wave, "Моя волна", Color.parseColor("#E8C686")) { switchTab("wave") }
        tabSearch = makeIconTextPill(R.drawable.ic_search, "Поиск", Color.parseColor("#38BDF8")) { switchTab("search") }
        tabCollection = makeIconTextPill(R.drawable.ic_heart_filled, "Коллекция", null) { switchTab("collection") }
        tabAuth = makeIconTextPill(R.drawable.ic_settings, "Вход", Color.parseColor("#CBD5E1")) { switchTab("auth") }
        val btnBackHome = makeIconTextPill(R.drawable.ic_home, "Домой", Color.parseColor("#F1F5F9")) { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }

        listOf(tabWave, tabSearch, tabCollection, tabAuth, btnBackHome).forEach { tabsRow.addView(it) }

        topBar.addView(brandCol)
        topBar.addView(tabsRow)
        root.addView(topBar)

        // ===== 2. MAIN TWO-COLUMN CONTENT =====
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // --- LEFT: HERO VINYL PLAYER (BORK x Duo Max) ---
        val heroCardContainer = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f).apply {
                marginEnd = dp(14)
            }
        }

        val ivWaveHeaderBg = ImageView(this).apply {
            setImageResource(R.drawable.hero_wave_texture)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.28f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val heroFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#24080D16"),
                    Color.parseColor("#9A080D16"),
                    Color.parseColor("#E0080D16")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        heroCardContainer.addView(ivWaveHeaderBg)
        heroCardContainer.addView(heroFade)

        val heroScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        tvSourceBadge = TextView(this).apply {
            text = "МОЯ ВОЛНА"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2238BDF8"))
                setStroke(dp(1), Color.parseColor("#5538BDF8"))
                cornerRadius = dp(999).toFloat()
            }
        }
        tvCodecBadge = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26E8C686"))
                setStroke(dp(1), Color.parseColor("#55E8C686"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        badgesRow.addView(tvSourceBadge)
        badgesRow.addView(tvCodecBadge)

        // Turntable Vinyl + Sleeve Cover Composition
        val vinylStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(232), dp(170)).apply {
                bottomMargin = dp(12)
            }
        }
        ivVinyl = ImageView(this).apply {
            setImageResource(R.drawable.sprite_vinyl_disc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(156), dp(156), Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(72)
            }
        }
        ivCover = ImageView(this).apply {
            setImageResource(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            background = null
            layoutParams = FrameLayout.LayoutParams(dp(164), dp(164), Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(6)
            }
        }
        viewCoverRim = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#77E8C686"))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(dp(164), dp(164), Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(6)
            }
        }
        vinylStage.addView(ivVinyl)
        vinylStage.addView(ivCover)
        vinylStage.addView(viewCoverRim)

        tvTrackTitle = TextView(this).apply {
            text = "Готово к запуску"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        tvTrackArtist = TextView(this).apply {
            text = "Нажмите «Моя волна» или скажите Алисе"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(10))
        }

        progressTrack = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = getDrawable(R.drawable.bg_progress_gold)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply {
                bottomMargin = dp(4)
            }
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        tvTimePos = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvTimeDur = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            gravity = Gravity.END
        }
        timeRow.addView(tvTimePos)
        timeRow.addView(tvTimeDur)

        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        btnPrev = makeCtrlIconBtn(R.drawable.ic_prev, Color.WHITE) { doPlayerAction("PREV") }
        btnPlayPause = makeCtrlIconBtn(R.drawable.ic_play, Color.parseColor("#E8C686")) { doPlayerAction("PLAY_PAUSE") }
        btnNext = makeCtrlIconBtn(R.drawable.ic_next, Color.WHITE) { doPlayerAction("NEXT") }
        btnLike = makeCtrlIconBtn(R.drawable.ic_heart_outline, null) { doPlayerAction("LIKE") }
        btnDislike = makeCtrlIconBtn(R.drawable.ic_dislike, Color.parseColor("#94A3B8")) { doPlayerAction("DISLIKE") }
        listOf(btnPrev, btnPlayPause, btnNext, btnLike, btnDislike).forEachIndexed { idx, btn ->
            btn.root.layoutParams = LinearLayout.LayoutParams(dp(46), dp(40)).apply {
                if (idx < 4) marginEnd = dp(8)
            }
            ctrlRow.addView(btn.root)
        }

        btnMyWaveHero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundResource(R.drawable.bg_pill_gold)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(90).start()
            }
            setOnClickListener {
                startMyWave(lastSnapshot.waveMood)
            }
            val ivW = ImageView(this@YandexMusicActivity).apply {
                val d = getDrawable(R.drawable.ic_wave)?.mutate()
                d?.setTint(Color.parseColor("#0B0F17"))
                setImageDrawable(d)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(8)
                }
            }
            val tvW = TextView(this@YandexMusicActivity).apply {
                text = "МОЯ ВОЛНА"
                setTextColor(Color.parseColor("#0B0F17"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
            }
            addView(ivW)
            addView(tvW)
        }

        tvErrorLine = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        heroCard.addView(badgesRow)
        heroCard.addView(vinylStage)
        heroCard.addView(tvTrackTitle)
        heroCard.addView(tvTrackArtist)
        heroCard.addView(progressTrack)
        heroCard.addView(timeRow)
        heroCard.addView(ctrlRow)
        heroCard.addView(btnMyWaveHero)
        heroCard.addView(tvErrorLine)
        heroScroll.addView(heroCard)
        heroCardContainer.addView(heroScroll)

        // --- RIGHT: TABS CONTENT (Duo Max Frosted Glass Panel) ---
        rightContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card_duo_widget)
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
        root.addView(bodyRow)

        setContentView(rootFrame)
        switchTab(activeTab)
        btnMyWaveHero.post { btnMyWaveHero.requestFocus() }

        vinylAnimator = ObjectAnimator.ofFloat(ivVinyl, View.ROTATION, 0f, 360f).apply {
            duration = 9000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun buildWaveTab(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val moodTitle = TextView(this).apply {
            text = "НАСТРОЕНИЕ ВОЛНЫ"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, dp(7))
        }

        // All 5 3D Glass Medallion Mood Cards fit evenly across width without clipping!
        val moodRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
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
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(7))
                setBackgroundResource(R.drawable.bg_tile)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < moods.lastIndex) marginEnd = dp(6)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(90).start()
                }
                setOnClickListener { startMyWave(mood.key) }

                val iv = ImageView(this@YandexMusicActivity).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        bottomMargin = dp(4)
                    }
                }
                val tv = TextView(this@YandexMusicActivity).apply {
                    text = shortLabel
                    setTextColor(Color.parseColor("#F1F5F9"))
                    textSize = 11f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        val qHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        tvWaveStatus = TextView(this).apply {
            text = "Очередь воспроизведения"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRestartWave = makeIconTextPill(R.drawable.ic_refresh, "Перезапустить", Color.parseColor("#E8C686")) {
            startMyWave(lastSnapshot.waveMood)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        qHeader.addView(tvWaveStatus)
        qHeader.addView(btnRestartWave)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        waveQueueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(waveQueueList)

        panel.addView(moodTitle)
        panel.addView(moodRow)
        panel.addView(qHeader)
        panel.addView(scroll)
        return panel
    }

    private fun buildSearchTab(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        etSearch = EditText(this).apply {
            hint = "Исполнитель, трек или настроение..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC141E30"))
                setStroke(dp(1), Color.parseColor("#44E8C686"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
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
        val btnFind = makeIconTextPill(R.drawable.ic_search, "Найти", Color.parseColor("#38BDF8")) { performSearch() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        searchRow.addView(etSearch)
        searchRow.addView(btnFind)

        tvSearchStatus = TextView(this).apply {
            text = "Введите запрос или попросите Алису: «Алиса, включи Кино»"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(8))
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        searchList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(searchList)

        panel.addView(searchRow)
        panel.addView(tvSearchStatus)
        panel.addView(scroll)
        return panel
    }

    private fun buildCollectionTab(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        btnColLikes = makeIconTextPill(R.drawable.ic_heart_filled, "Мне нравится", null) {
            collectionMode = "likes"
            loadCollection()
        }
        btnColChart = makeIconTextPill(R.drawable.ic_chart, "Топ Чарт", null) {
            collectionMode = "chart"
            loadCollection()
        }
        btnColPlayAll = makeIconTextPill(R.drawable.ic_play, "Включить список", Color.parseColor("#E8C686")) {
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

        tvColStatus = TextView(this).apply {
            text = "Загрузка..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(8))
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        collectionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(collectionList)

        panel.addView(topRow)
        panel.addView(tvColStatus)
        panel.addView(scroll)
        return panel
    }

    private fun buildAuthTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tvH1 = TextView(this).apply {
            text = "АККАУНТ ЯНДЕКС МУЗЫКИ"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        tvAuthSummary = TextView(this).apply {
            text = "Проверка авторизации..."
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setPadding(0, 0, 0, dp(12))
        }

        val authBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        val btnBrowserLogin = makeIconTextPill(R.drawable.ic_open_external, "Войти через Яндекс", Color.parseColor("#38BDF8")) {
            openPassportOAuthDialog()
        }
        val btnManualToken = makeIconTextPill(R.drawable.ic_key, "OAuth токен", null) {
            promptOAuthTokenDialog()
        }
        btnAuthLogout = makeIconTextPill(R.drawable.ic_hw_mic_off, "Выйти", Color.parseColor("#FCA5A5")) {
            player()?.logout()
            Toast.makeText(this, "Вышли из аккаунта Яндекс Музыки", Toast.LENGTH_SHORT).show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        authBtns.addView(btnBrowserLogin)
        authBtns.addView(btnManualToken)
        authBtns.addView(btnAuthLogout)

        val tvH2 = TextView(this).apply {
            text = "КАЧЕСТВО АУДИО"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(6))
        }
        val qualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        btnQualityNormal = makeTextPill("Стандарт (MP3/AAC)") {
            player()?.let {
                it.api.setPreferredQuality("normal")
                it.emitSnapshot(forceDaemon = false)
            }
            Toast.makeText(this, "Качество: Стандарт (MP3/AAC)", Toast.LENGTH_SHORT).show()
        }
        btnQualityLossless = makeTextPill("Hi-Res (FLAC без потерь)") {
            player()?.let {
                it.api.setPreferredQuality("lossless")
                it.emitSnapshot(forceDaemon = false)
            }
            Toast.makeText(this, "Качество: FLAC / Hi-Res", Toast.LENGTH_SHORT).show()
        }
        qualRow.addView(btnQualityNormal)
        qualRow.addView(btnQualityLossless)

        val qrBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_tile)
        }
        val qrWrap = LinearLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(14)
            }
        }
        ivWebQr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrWrap.addView(ivWebQr)

        tvWebHint = TextView(this).apply {
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

    private fun switchTab(tab: String) {
        activeTab = tab
        viewWave.visibility = if (tab == "wave") View.VISIBLE else View.GONE
        viewSearch.visibility = if (tab == "search") View.VISIBLE else View.GONE
        viewCollection.visibility = if (tab == "collection") View.VISIBLE else View.GONE
        viewAuth.visibility = if (tab == "auth") View.VISIBLE else View.GONE

        tabWave.isSelected = (tab == "wave")
        tabSearch.isSelected = (tab == "search")
        tabCollection.isSelected = (tab == "collection")
        tabAuth.isSelected = (tab == "auth")

        when (tab) {
            "collection" -> loadCollection()
            "auth" -> updateWebQrInfo()
            "search" -> etSearch.post { etSearch.requestFocus() }
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
                val api = player()?.api ?: YandexMusicApi(this)
                val res = api.searchTracks(q, limit = 30)
                handler.post {
                    searchTracks = res
                    tvSearchStatus.text = if (res.isEmpty()) "По запросу «$q» ничего не найдено" else "Найдено треков: ${res.size}"
                    renderTrackList(searchList, res, activeTrackId = lastSnapshot.currentTrack?.id) { idx ->
                        val p = player()
                        if (p != null) {
                            p.playTrackList(res, idx, "search", "Поиск: $q")
                        }
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
        btnColLikes.isSelected = (collectionMode == "likes")
        btnColChart.isSelected = (collectionMode == "chart")
        tvColStatus.text = if (collectionMode == "chart") "Загружаем Чарт..." else "Загружаем «Мне нравится»..."
        collectionList.removeAllViews()

        thread(isDaemon = true) {
            try {
                val api = player()?.api ?: YandexMusicApi(this)
                val tracks: List<YmTrack>
                val title: String
                if (collectionMode == "chart") {
                    val pair = api.getChartTracks()
                    title = "${pair.first} (${pair.second.size} треков)"
                    tracks = pair.second
                } else {
                    if (!api.hasToken()) {
                        handler.post {
                            collectionTracks = emptyList()
                            tvColStatus.text = "Войдите в аккаунт во вкладке «Вход», чтобы видеть «Мне нравится» (или откройте Топ Чарт)."
                        }
                        return@thread
                    }
                    var acct = api.getCachedAccount()
                    if (!acct.loggedIn) acct = api.refreshAccount()
                    tracks = api.getLikedTracks(acct.uid, limit = 80)
                    title = if (tracks.isEmpty()) "Список «Мне нравится» пуст" else "Мне нравится · ${tracks.size} треков"
                }
                handler.post {
                    collectionTracks = tracks
                    tvColStatus.text = title
                    val srcLbl = if (collectionMode == "chart") "Топ Чарт" else "Мне нравится"
                    renderTrackList(collectionList, tracks, activeTrackId = lastSnapshot.currentTrack?.id) { idx ->
                        player()?.playTrackList(tracks, idx, collectionMode, srcLbl)
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    tvColStatus.text = "Ошибка: ${t.message}"
                }
            }
        }
    }

    private fun updateVinylAnimation() {
        if (lastSnapshot.playing) {
            vinylAnimator?.let {
                if (!it.isStarted) it.start() else if (it.isPaused) it.resume()
            }
        } else {
            vinylAnimator?.let { if (it.isRunning) it.pause() }
        }
    }

    private fun setCoverArt(bmp: Bitmap?) {
        if (bmp != null) {
            ivCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivCover.background = GradientDrawable().apply {
                setColor(Color.parseColor("#111927"))
                cornerRadius = dp(16).toFloat()
            }
            ivCover.setImageBitmap(bmp)
            viewCoverRim.visibility = View.VISIBLE
        } else {
            ivCover.scaleType = ImageView.ScaleType.FIT_CENTER
            ivCover.background = null
            ivCover.setImageResource(R.drawable.sprite_ym_emblem)
            viewCoverRim.visibility = View.GONE
        }
    }

    private fun renderSnapshot(s: YmPlayerSnapshot) {
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
            if (cUrl != lastRenderedCoverUrl) {
                lastRenderedCoverUrl = cUrl
                if (cUrl.isBlank()) {
                    setCoverArt(null)
                } else {
                    thread(isDaemon = true) {
                        val bmp = (player()?.api ?: YandexMusicApi(this)).loadCoverBitmap(cUrl, 400)
                        if (bmp != null) {
                            handler.post {
                                if (lastRenderedCoverUrl == cUrl) setCoverArt(bmp)
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

        val ppIcon = getDrawable(
            when {
                s.buffering -> R.drawable.ic_refresh
                s.playing -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
        )?.mutate()?.apply {
            setTint(Color.parseColor("#E8C686"))
        }
        btnPlayPause.icon.setImageDrawable(ppIcon)

        val liked = (tr?.liked == true)
        btnLike.icon.setImageDrawable(getDrawable(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline))
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

        // Auth tab summary & quality buttons
        tvAuthSummary.text = if (s.account.loggedIn) {
            "Вход выполнен: ${s.account.fullName.ifBlank { s.account.login }} (@${s.account.login}, uid=${s.account.uid})" +
                if (s.account.hasPlus) "\nСтатус: Яндекс Плюс активен (доступно Hi-Res и Моя волна)" else "\nСтатус: Базовый аккаунт"
        } else {
            "Аккаунт не подключён.\nЧтобы работала персональная «Моя волна» и «Мне нравится», войдите через Яндекс (QR/пароль) или вставьте OAuth-токен."
        }
        btnAuthLogout.visibility = if (s.account.loggedIn) View.VISIBLE else View.GONE
        btnQualityNormal.isSelected = (s.quality != "lossless")
        btnQualityLossless.isSelected = (s.quality == "lossless")
        updateVinylAnimation()
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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundResource(R.drawable.bg_tile)
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
                val ivPlay = ImageView(this).apply {
                    val d = getDrawable(R.drawable.ic_play)?.mutate()
                    d?.setTint(Color.parseColor("#E8C686"))
                    setImageDrawable(d)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                        marginStart = dp(6)
                        marginEnd = dp(12)
                    }
                }
                row.addView(ivPlay)
            } else {
                val tvIdx = TextView(this).apply {
                    text = "${idx + 1}"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    }
                }
                row.addView(tvIdx)
            }

            val txtCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvT = TextView(this).apply {
                text = tr.title
                setTextColor(Color.parseColor(if (isActive) "#E8C686" else "#F8FAFC"))
                textSize = 14f
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvA = TextView(this).apply {
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
                val ivH = ImageView(this).apply {
                    setImageResource(R.drawable.ic_heart_filled)
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                        marginEnd = dp(6)
                    }
                }
                row.addView(ivH)
            }

            val tvDur = TextView(this).apply {
                text = formatMs(tr.durationMs)
                setTextColor(Color.parseColor(if (tr.liked) "#F472B6" else "#94A3B8"))
                textSize = 12f
            }
            row.addView(tvDur)
            container.addView(row)
        }
    }

    private fun promptOAuthTokenDialog() {
        val et = EditText(this).apply {
            hint = "Вставьте токен (y0_...) или URL после входа"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
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
                    Toast.makeText(this, "Проверяем токен...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPassportOAuthDialog() {
        val dlgHolder = arrayOfNulls<AlertDialog>(1)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
        }

        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val cursorView = CursorOverlayView(this).apply {
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
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        fun tryExtractToken(urlStr: String?): Boolean {
            if (urlStr.isNullOrBlank()) return false
            if (urlStr.contains("access_token=")) {
                val tok = YandexMusicApi.normalizeOAuthToken(urlStr)
                if (tok.isNotEmpty()) {
                    try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                    player()?.setOAuthToken(tok, autoResumeMyWave = true)
                    handler.post {
                        Toast.makeText(this, "Авторизация Яндекс Музыки успешна!", Toast.LENGTH_SHORT).show()
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
            }
        }

        root.addView(wv)
        root.addView(cursorView)

        val topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0F172A"))
                cornerRadius = dp(10).toFloat()
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
        val tvCursorHint = TextView(this).apply {
            text = "MENU: курсор/клавиатура  ·  BACK: закрыть"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            setPadding(0, 0, dp(10), 0)
        }
        val btnClose = TextView(this).apply {
            text = "Закрыть"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundResource(R.drawable.bg_pill)
            setOnClickListener { dlgHolder[0]?.dismiss() }
        }
        topOverlay.addView(tvCursorHint)
        topOverlay.addView(btnClose)
        root.addView(topOverlay)

        root.post {
            cx = root.width * 0.5f
            cy = root.height * 0.5f
            cursorView.updatePos(cx, cy, cursorEnabled)
        }

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(root)
            .setOnDismissListener {
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
                Toast.makeText(this, if (cursorEnabled) "Курсор: ВКЛ" else "Режим ввода: ВКЛ", Toast.LENGTH_SHORT).show()
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
        dlg.show()
        wv.loadUrl(YandexMusicApi.OAUTH_AUTHORIZE_URL)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    doPlayerAction("PLAY_PAUSE")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    doPlayerAction("NEXT")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    doPlayerAction("PREV")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    doPlayerAction("STOP")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateWebQrInfo() {
        val ip = detectLocalIp()
        val url = "http://$ip:8080"
        tvWebHint.text = "Быстрый вход через веб-пульт ($url):\nНа телефоне или ПК откройте вкладку «Пульт, Музыка & ТВ» и вставьте OAuth-токен — плеер сразу подхватит аккаунт."
        QrGenerator.generateBitmap(url, dp(92))?.let { ivWebQr.setImageBitmap(it) }
    }

    private fun detectLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) return ip
                    }
                }
            }
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }

    private fun makeTextPill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#F1F5F9"))
        textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setBackgroundResource(R.drawable.bg_pill)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(6)
        }
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
        }
        setOnClickListener { onClick() }
    }

    private fun makeIconTextPill(iconRes: Int, label: String, iconTint: Int?, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(13), dp(6))
            setBackgroundResource(R.drawable.bg_pill)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(this@YandexMusicActivity).apply {
                val d = getDrawable(iconRes)?.mutate()
                if (d != null && iconTint != null) d.setTint(iconTint)
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                    marginEnd = dp(6)
                }
            }
            val tv = TextView(this@YandexMusicActivity).apply {
                text = label
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            }
            addView(iv)
            addView(tv)
        }

    private fun makeCtrlIconBtn(iconRes: Int, tintColor: Int?, onClick: () -> Unit): IconBtnViews {
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_pill)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(90).start()
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            val d = getDrawable(iconRes)?.mutate()
            if (d != null && tintColor != null) d.setTint(tintColor)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
        }
        root.addView(iv)
        return IconBtnViews(root, iv)
    }

    private fun formatMs(ms: Long): String {
        val tot = max(0L, ms / 1000L)
        val m = tot / 60L
        val s = tot % 60L
        return "%d:%02d".format(m, s)
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
