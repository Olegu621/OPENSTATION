package com.openstation.launcher

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.FocusFinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable,
        val intent: Intent,
        val leanback: Boolean
    )

    private data class HwTileViews(
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView
    )

    private data class IconBtnViews(
        val root: FrameLayout,
        val icon: ImageView
    )

    private data class SysItem(
        val iconRes: Int,
        val isSprite: Boolean,
        val label: String,
        val onClick: () -> Unit
    )

    private val handler = Handler(Looper.getMainLooper())
    private val easeOutCubic = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    // Root stage & multi-screen morph architecture
    private lateinit var rootFrame: FrameLayout
    private lateinit var topBar: FrameLayout
    private lateinit var pagesStage: FrameLayout
    private lateinit var page0View: FrameLayout
    private lateinit var page1Scroll: ScrollView
    private lateinit var page2Scroll: ScrollView
    private lateinit var tabViews: Array<TextView>
    private lateinit var tabBgShapes: Array<GradientDrawable>

    // Persistent morphing Alice Sphere + Hero Response Card + Bottom Dock Bar
    private lateinit var heroOrbView: AliceOrbView
    private lateinit var btnHeroOrbClick: FrameLayout
    private lateinit var heroReplyCard: LinearLayout
    private lateinit var heroReplyCardBg: GradientDrawable
    private lateinit var heroStateDotBg: GradientDrawable
    private lateinit var tvHeroState: TextView
    private lateinit var tvHeroUser: TextView
    private lateinit var tvHeroReply: TextView
    private lateinit var btnHeroMusicChip: LinearLayout
    private lateinit var tvHeroMusicChip: TextView

    private lateinit var bottomDockBar: LinearLayout
    private lateinit var bottomDockBg: GradientDrawable
    private lateinit var dockStateDotBg: GradientDrawable
    private lateinit var tvDockState: TextView
    private lateinit var tvDockMusic: TextView
    private lateinit var tvDockReply: TextView
    private lateinit var btnDockHome: LinearLayout

    private var currentPage = 0
    private var currentPagePos = 0f
    private var pageAnimator: ValueAnimator? = null
    private lateinit var swipeDetector: GestureDetector

    // Top bar status & clock
    private lateinit var tvTopClock: TextView
    private lateinit var tvTopDate: TextView
    private lateinit var tvStatusLine: TextView

    // Screen 2: Duo Clock, QR & BORK Station 3D visual
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvIpLine: TextView
    private lateinit var tvStationTelemetry: TextView
    private lateinit var ivQr: ImageView
    private lateinit var ivStationRingGlow: View
    private lateinit var ivDialRing: ImageView
    private var dialAnimator: ObjectAnimator? = null

    // Hardware quick-control tiles (Screen 2)
    private lateinit var tileRing: HwTileViews
    private lateinit var tileMute: HwTileViews
    private lateinit var tileRoute: HwTileViews
    private lateinit var tileEq: HwTileViews
    private lateinit var tileCapMode: HwTileViews
    private lateinit var tileMicPause: HwTileViews
    private lateinit var tileCecWake: HwTileViews
    private lateinit var tileCecOff: HwTileViews

    // Screen 1: Apps grid & Native Yandex Music Vinyl Hero card
    private lateinit var appsGrid: GridLayout
    private lateinit var ivHomeVinyl: ImageView
    private lateinit var ivHomeCover: ImageView
    private lateinit var viewHomeCoverBorder: View
    private var vinylAnimator: ObjectAnimator? = null
    private lateinit var tvHomeSourceBadge: TextView
    private lateinit var tvHomeCodecBadge: TextView
    private lateinit var tvHomeAuthBadge: TextView
    private lateinit var tvHomeTrackTitle: TextView
    private lateinit var tvHomeTrackArtist: TextView
    private lateinit var pbHomeTrack: ProgressBar
    private lateinit var tvHomeTrackTime: TextView
    private lateinit var btnHomeWave: LinearLayout
    private lateinit var btnHomePrev: IconBtnViews
    private lateinit var btnHomePlayPause: IconBtnViews
    private lateinit var btnHomeNext: IconBtnViews
    private lateinit var btnHomeLike: IconBtnViews
    private lateinit var btnHomeOpen: LinearLayout
    private var lastHomeCoverUrl = ""
    private var latestMusicSnap = YmPlayerSnapshot()

    // Live daemon state
    private var ringMode = "music"
    private var micMuted = false
    private var outputRoute = "auto"
    private var eqPreset = "balanced"
    private var captureMode = "aaudio"
    private var capturePaused = false
    private var assistantState = "idle"
    private var assistantEnabled = true
    private var volumePct = 50
    @Volatile private var daemonOnline = false
    @Volatile private var cachedAppsJson = ""
    private var lastQrIp = ""

    private val orbStateListener = LiveOrbState.StateListener {
        renderOrbAssistantUi()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadAndRenderApps()
        }
    }

    private val musicStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getStringExtra(YandexMusicPlayer.EXTRA_SNAPSHOT_JSON) ?: return
            try {
                val snap = YmPlayerSnapshot.fromJson(JSONObject(raw))
                renderMusicSnapshot(snap)
            } catch (_: Throwable) {}
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 10_000L)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDaemonStatus()
            handler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        StationAudioService.ensureStarted(this)
        AliceOrbFrames.preloadAsync(this)

        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(72) && abs(dx) > abs(dy) * 1.35f && abs(velocityX) > 250f) {
                    if (dx < 0 && currentPage < 2) {
                        setPage(currentPage + 1, animate = true)
                        return true
                    } else if (dx > 0 && currentPage > 0) {
                        setPage(currentPage - 1, animate = true)
                        return true
                    }
                }
                return false
            }
        })

        buildUi()
        loadAndRenderApps()
        updateNetworkHeader()
        updateClock()
        refreshMusicSnapshotFromService()
        renderOrbAssistantUi()

        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, pf)
        try {
            registerReceiver(musicStateReceiver, IntentFilter(YandexMusicPlayer.ACTION_MUSIC_STATE))
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        StationAudioService.setLauncherForegroundState(true)
        StationAudioService.ensureStarted(this)
        LiveOrbState.addListener(orbStateListener)
        updateNetworkHeader()
        updateClock()
        refreshMusicSnapshotFromService()
        renderOrbAssistantUi()
        updateAnimationsState()
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.post(clockRunnable)
        handler.post(pollRunnable)
    }

    override fun onPause() {
        LiveOrbState.removeListener(orbStateListener)
        StationAudioService.setLauncherForegroundState(false)
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        vinylAnimator?.pause()
        dialAnimator?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        LiveOrbState.removeListener(orbStateListener)
        pageAnimator?.cancel()
        vinylAnimator?.cancel()
        dialAnimator?.cancel()
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(musicStateReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (currentPage != 0) {
            setPage(0, animate = true)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && swipeDetector.onTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (currentPage < 2) {
                        setPage(currentPage + 1, animate = true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (currentPage > 0) {
                        setPage(currentPage - 1, animate = true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val cur = currentFocus
                    if (currentPage == 0 && (cur === btnHeroOrbClick || cur === heroReplyCard)) {
                        setPage(1, animate = true)
                        return true
                    }
                    val root = window.decorView as? ViewGroup
                    val next = if (root != null) FocusFinder.getInstance().findNextFocus(root, cur, View.FOCUS_RIGHT) else null
                    val curIsTab = tabViews.any { it === cur }
                    if (!curIsTab && (cur == null || next == null) && currentPage < 2) {
                        setPage(currentPage + 1, animate = true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val cur = currentFocus
                    if (currentPage > 0 && cur === bottomDockBar) {
                        setPage(currentPage - 1, animate = true)
                        return true
                    }
                    val root = window.decorView as? ViewGroup
                    val next = if (root != null) FocusFinder.getInstance().findNextFocus(root, cur, View.FOCUS_LEFT) else null
                    val curIsTab = tabViews.any { it === cur }
                    if (!curIsTab && (cur == null || next == null) && currentPage > 0) {
                        setPage(currentPage - 1, animate = true)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            loadAndRenderApps()
            Toast.makeText(this, "Список приложений обновлён", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun buildUi() {
        rootFrame = FrameLayout(this)

        // 0. Atmospheric Wallpaper Backdrop + Obsidian Gradient Scrim
        val ivBg = ImageView(this).apply {
            setImageResource(R.drawable.bg_tv_stage)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val scrimView = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#6A050811"),
                    Color.parseColor("#24050811"),
                    Color.parseColor("#88050811")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(ivBg)
        rootFrame.addView(scrimView)

        // Main vertical container: Top Bar + 3-Screen Morphing Stage
        val shellCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ===== 1. MINIMAL TOP HEADER & SCREEN TABS =====
        topBar = FrameLayout(this).apply {
            setPadding(dp(26), dp(6), dp(26), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        }

        // Left: Minimal Clock + Date + Edition
        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        tvTopClock = TextView(this).apply {
            text = "--:--"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 19f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.03f
        }
        tvTopDate = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11.5f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        }
        topLeft.addView(tvTopClock)
        topLeft.addView(tvTopDate)

        // Center: 3-Screen Pill Switcher
        val tabsPillGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#990B1220"))
                setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val tabTitles = arrayOf("Сфера", "Музыка и ТВ", "Станция")
        val tmpTabs = ArrayList<TextView>(3)
        val tmpShapes = ArrayList<GradientDrawable>(3)
        tabTitles.forEachIndexed { idx, title ->
            val shape = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.TRANSPARENT)
            }
            tmpShapes.add(shape)
            val tv = TextView(this).apply {
                text = title
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.parseColor("#CBD5E1"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(5), dp(16), dp(5))
                background = shape
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(90).start()
                    if (hasFocus && !v.isInTouchMode && currentPage != idx) {
                        setPage(idx, animate = true)
                    } else {
                        updateTabsAppearance(focusedIdx = if (hasFocus) idx else -1)
                    }
                }
                setOnClickListener { setPage(idx, animate = true) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < 2) marginEnd = dp(4)
                }
            }
            tmpTabs.add(tv)
            tabsPillGroup.addView(tv)
        }
        tabViews = tmpTabs.toTypedArray()
        tabBgShapes = tmpShapes.toTypedArray()

        // Right: Compact Hardware Telemetry Pill
        tvStatusLine = TextView(this).apply {
            text = "○ openstationd..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#990F1727"))
                setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { setPage(2, animate = true) }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }

        topBar.addView(topLeft)
        topBar.addView(tabsPillGroup)
        topBar.addView(tvStatusLine)
        shellCol.addView(topBar)

        // ===== 2. MULTI-SCREEN PAGES STAGE =====
        pagesStage = FrameLayout(this).apply {
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        page0View = buildScreen0Hero()
        page1Scroll = buildScreen1MediaApps()
        page2Scroll = buildScreen2StationSettings()

        pagesStage.addView(page0View)
        pagesStage.addView(page1Scroll)
        pagesStage.addView(page2Scroll)
        shellCol.addView(pagesStage)
        rootFrame.addView(shellCol)

        // ===== 3. MORPHING BOTTOM DOCK BAR (Screens 1 & 2) =====
        bottomDockBar = buildBottomDockBar()
        rootFrame.addView(bottomDockBar)

        // ===== 4. PERSISTENT MORPHING ALICE SPHERE OVERLAY =====
        heroOrbView = AliceOrbView(this).apply {
            isClickable = false
            isFocusable = false
            elevation = dp(24).toFloat()
            outlineProvider = null
            layoutParams = FrameLayout.LayoutParams(dp(320), dp(320))
        }
        rootFrame.addView(heroOrbView)

        rootFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPageVisuals(currentPagePos)
        }

        setContentView(rootFrame)

        // Subtle animations for Screen 1 Vinyl & Screen 2 Dial
        vinylAnimator = ObjectAnimator.ofFloat(ivHomeVinyl, View.ROTATION, 0f, 360f).apply {
            duration = 9000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        dialAnimator = ObjectAnimator.ofFloat(ivDialRing, View.ROTATION, 0f, 360f).apply {
            duration = 28000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        setPage(0, animate = false)
        btnHeroOrbClick.post {
            if (currentPage == 0) btnHeroOrbClick.requestFocus()
        }
    }

    // -----------------------------------------------------------------------------------------
    // SCREEN 0: Ultra-Clean Hero Sphere + Assistant Response Text + Subtle Navigation Strip
    // -----------------------------------------------------------------------------------------
    private fun buildScreen0Hero(): FrameLayout {
        val page = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Focusable/clickable circular trigger sitting concentric behind the giant morphing AliceOrbView
        val orbFocusSel = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#14E8C686"))
                setStroke(dp(2), Color.parseColor("#AAE8C686"))
            })
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2438BDF8"))
                setStroke(dp(2), Color.parseColor("#FF38BDF8"))
            })
            addState(intArrayOf(), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            })
        }
        btnHeroOrbClick = FrameLayout(this).apply {
            background = orbFocusSel
            isFocusable = true
            isClickable = true
            setOnClickListener { triggerAlice() }
            layoutParams = FrameLayout.LayoutParams(
                dp(320),
                dp(320),
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            )
        }
        page.addView(btnHeroOrbClick)

        // Frosted BORK x Duo Assistant Response Box beneath the Giant Orb
        heroReplyCardBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.parseColor("#D60B111E"),
                Color.parseColor("#E0111A2E"),
                Color.parseColor("#D60B111E")
            )
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.parseColor("#4AE8C686"))
        }

        heroReplyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = heroReplyCardBg
            setPadding(dp(22), dp(10), dp(22), dp(12))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(90).start()
            }
            setOnClickListener { triggerAlice() }
            layoutParams = FrameLayout.LayoutParams(
                dp(700),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply {
                topMargin = dp(308)
            }
        }

        val replyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(5)
            }
        }

        heroStateDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#38BDF8"))
        }
        val heroDot = View(this).apply {
            background = heroStateDotBg
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
        }
        tvHeroState = TextView(this).apply {
            text = "АЛИСА · ГОТОВА"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11.5f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        tvHeroUser = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        replyHeader.addView(heroDot)
        replyHeader.addView(tvHeroState)
        replyHeader.addView(tvHeroUser)

        tvHeroReply = TextView(this).apply {
            text = "Скажите «Алиса» или нажмите OK для общения"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1.08f)
        }
        heroReplyCard.addView(replyHeader)
        heroReplyCard.addView(tvHeroReply)
        page.addView(heroReplyCard)

        // Minimal Bottom Strip on Screen 0 (Music glance + quick navigation to Screens 1 & 2)
        val footer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(28)
                rightMargin = dp(28)
                bottomMargin = dp(12)
            }
        }

        btnHeroMusicChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_pill)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(90).start()
            }
            setOnClickListener { setPage(1, animate = true) }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        val ivNote = ImageView(this).apply {
            setImageResource(R.drawable.ic_wave)
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                marginEnd = dp(7)
            }
        }
        tvHeroMusicChip = TextView(this).apply {
            text = "Яндекс Музыка · Моя волна"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(380)
        }
        btnHeroMusicChip.addView(ivNote)
        btnHeroMusicChip.addView(tvHeroMusicChip)

        val navRightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }
        val btnGoMedia = makeTextIconPill(R.drawable.ic_yaos_apps, "Музыка и приложения →", gold = false) {
            setPage(1, animate = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginEnd = dp(8)
            }
        }
        val btnGoSystem = makeTextIconPill(R.drawable.ic_settings, "Станция и настройки →", gold = false) {
            setPage(2, animate = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        }
        navRightGroup.addView(btnGoMedia)
        navRightGroup.addView(btnGoSystem)

        footer.addView(btnHeroMusicChip)
        footer.addView(navRightGroup)
        page.addView(footer)

        return page
    }

    // -----------------------------------------------------------------------------------------
    // SCREEN 1: BORK Vinyl × Yandex Music + All TV Apps Grid (above Morphing Bottom Dock)
    // -----------------------------------------------------------------------------------------
    private fun buildScreen1MediaApps(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(4), dp(26), dp(86))
        }

        // BORK Vinyl × Yandex Music Flagship Card
        val musicHeroCard = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(224)
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val ivWaveBackdrop = ImageView(this).apply {
            setImageResource(R.drawable.hero_wave_texture)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.50f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val heroScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#B0090F1B"),
                    Color.parseColor("#55090F1B"),
                    Color.parseColor("#88090F1B")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        musicHeroCard.addView(ivWaveBackdrop)
        musicHeroCard.addView(heroScrim)

        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val heroBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvHomeSourceBadge = TextView(this).apply {
            text = "ЯНДЕКС МУЗЫКА · МОЯ ВОЛНА"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26E8C686"))
                setStroke(dp(1), Color.parseColor("#66E8C686"))
                cornerRadius = dp(999).toFloat()
            }
        }
        tvHomeCodecBadge = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2238BDF8"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        tvHomeAuthBadge = TextView(this).apply {
            text = "Проверка входа..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        btnHomeOpen = makeTextIconPill(R.drawable.ic_open_external, "Полный плеер", gold = false) {
            launchYandexMusic()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        }
        heroBadgeRow.addView(tvHomeSourceBadge)
        heroBadgeRow.addView(tvHomeCodecBadge)
        heroBadgeRow.addView(tvHomeAuthBadge)
        heroBadgeRow.addView(btnHomeOpen)
        heroContent.addView(heroBadgeRow)

        val heroMidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val vinylFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(116))
        }
        ivHomeVinyl = ImageView(this).apply {
            setImageResource(R.drawable.sprite_vinyl_disc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(52)
            }
        }
        ivHomeCover = ImageView(this).apply {
            setImageResource(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER_VERTICAL)
        }
        viewHomeCoverBorder = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#77E8C686"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER_VERTICAL)
        }
        vinylFrame.addView(ivHomeVinyl)
        vinylFrame.addView(ivHomeCover)
        vinylFrame.addView(viewHomeCoverBorder)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            }
        }
        tvHomeTrackTitle = TextView(this).apply {
            text = "Моя волна — поток музыки"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvHomeTrackArtist = TextView(this).apply {
            text = "Скажите «Алиса, включи музыку» или жмите «Моя волна»"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(10))
        }
        pbHomeTrack = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = getDrawable(R.drawable.bg_progress_gold)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5))
        }
        tvHomeTrackTime = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        }
        metaCol.addView(tvHomeTrackTitle)
        metaCol.addView(tvHomeTrackArtist)
        metaCol.addView(pbHomeTrack)
        metaCol.addView(tvHomeTrackTime)

        heroMidRow.addView(vinylFrame)
        heroMidRow.addView(metaCol)
        heroContent.addView(heroMidRow)

        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnHomeWave = makeTextIconPill(R.drawable.ic_wave, "Моя волна", gold = true) {
            sendMusicServiceCmd("play_my_wave")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(10)
            }
        }
        btnHomePrev = makeIconBtn(R.drawable.ic_prev, Color.WHITE) { sendMusicAction("PREV") }
        btnHomePlayPause = makeIconBtn(R.drawable.ic_play, Color.parseColor("#E8C686")) { sendMusicAction("PLAY_PAUSE") }
        btnHomeNext = makeIconBtn(R.drawable.ic_next, Color.WHITE) { sendMusicAction("NEXT") }
        btnHomeLike = makeIconBtn(R.drawable.ic_heart_outline, null) { sendMusicAction("LIKE") }

        ctrlRow.addView(btnHomeWave)
        listOf(btnHomePrev, btnHomePlayPause, btnHomeNext, btnHomeLike).forEach {
            it.root.layoutParams = LinearLayout.LayoutParams(dp(44), dp(36)).apply {
                marginEnd = dp(8)
            }
            ctrlRow.addView(it.root)
        }
        val tvWaveTag = TextView(this).apply {
            text = "Винил-режим · Hi-Fi Audio"
            setTextColor(Color.parseColor("#8CA6C8"))
            textSize = 11f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ctrlRow.addView(tvWaveTag)
        heroContent.addView(ctrlRow)

        musicHeroCard.addView(heroContent)
        col.addView(musicHeroCard)

        // Applications Grid
        col.addView(makeSectionTitle("Приложения", R.drawable.ic_yaos_apps))
        appsGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(appsGrid)

        scroll.addView(col)
        return scroll
    }

    // -----------------------------------------------------------------------------------------
    // SCREEN 2: BORK × Duo Max Hardware Showcase + QR + Hardware Tiles + Android TV Settings
    // -----------------------------------------------------------------------------------------
    private fun buildScreen2StationSettings(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(4), dp(26), dp(86))
        }

        val topSplit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1.0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(256)
            ).apply {
                bottomMargin = dp(14)
            }
        }

        // Left Card: Clock + QR + BORK 3D Hardware Render + Rotating Dial Ring
        val stationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card_duo_widget)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f).apply {
                marginEnd = dp(14)
            }
        }

        val clockStationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val clockQrCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvClock = TextView(this).apply {
            text = "--:--"
            setTextColor(Color.WHITE)
            textSize = 42f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.02f
        }
        tvDate = TextView(this).apply {
            text = "..."
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(10))
        }

        val qrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val qrFrame = LinearLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(7).toFloat()
            }
        }
        ivQr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrFrame.addView(ivQr)
        val qrTxtCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val tvQrLbl = TextView(this).apply {
            text = "Веб-пульт"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvIpLine = TextView(this).apply {
            text = "station-max.local:8080"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvQrSub = TextView(this).apply {
            text = "Сканируй с телефона"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
        }
        qrTxtCol.addView(tvQrLbl)
        qrTxtCol.addView(tvIpLine)
        qrTxtCol.addView(tvQrSub)
        qrRow.addView(qrFrame)
        qrRow.addView(qrTxtCol)

        clockQrCol.addView(tvClock)
        clockQrCol.addView(tvDate)
        clockQrCol.addView(qrRow)

        val stationVisualBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(128))
        }
        ivStationRingGlow = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#4438BDF8"), Color.parseColor("#33E8C686"))
            ).apply {
                shape = GradientDrawable.OVAL
            }
            layoutParams = FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER)
        }
        ivDialRing = ImageView(this).apply {
            setImageResource(R.drawable.sprite_bork_dial_ring)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.78f
            layoutParams = FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER)
        }
        val ivStationHero = ImageView(this).apply {
            setImageResource(R.drawable.sprite_station_hero)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER)
        }
        stationVisualBox.addView(ivStationRingGlow)
        stationVisualBox.addView(ivDialRing)
        stationVisualBox.addView(ivStationHero)

        clockStationRow.addView(clockQrCol)
        clockStationRow.addView(stationVisualBox)
        stationCard.addView(clockStationRow)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2BE8C686"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        tvStationTelemetry = TextView(this).apply {
            text = "OpenStation Max · Ожидание телеметрии..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stationCard.addView(divider)
        stationCard.addView(tvStationTelemetry)
        topSplit.addView(stationCard)

        // Right Card: 8 Hardware Quick Controls (4 rows x 2 columns)
        val hwPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f)
        }
        hwPanel.addView(makeSectionTitle("Аппаратное управление OpenStation", R.drawable.ic_hw_chip))

        tileRing = makeHwTile(R.drawable.ic_hw_ring, "Кольцо: Спектр") { cycleRingMode() }
        tileMute = makeHwTile(R.drawable.ic_hw_mic_on, "Микрофоны: ВКЛ") { toggleMute() }
        tileRoute = makeHwTile(R.drawable.ic_hw_speaker, "Выход: Авто") { cycleOutputRoute() }
        tileEq = makeHwTile(R.drawable.ic_hw_eq, "EQ: Баланс") { cycleEqPreset() }
        tileCapMode = makeHwTile(R.drawable.ic_hw_chip, "Захват: AAudio") { toggleCaptureMode() }
        tileMicPause = makeHwTile(R.drawable.ic_hw_eq, "Поток: ВКЛ") { toggleCapturePause() }
        tileCecWake = makeHwTile(R.drawable.ic_hw_tv, "ТВ Вкл (CEC)") { sendCec("wake") }
        tileCecOff = makeHwTile(R.drawable.ic_hw_tv, "ТВ Выкл (CEC)") { sendCec("standby") }

        val hwGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val hwTiles = listOf(tileRing, tileMute, tileRoute, tileEq, tileCapMode, tileMicPause, tileCecWake, tileCecOff)
        for (r in 0 until 4) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    if (r < 3) bottomMargin = dp(6)
                }
            }
            for (c in 0 until 2) {
                val tile = hwTiles[r * 2 + c]
                tile.root.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    if (c == 0) marginEnd = dp(6)
                }
                rowLayout.addView(tile.root)
            }
            hwGrid.addView(rowLayout)
        }
        hwPanel.addView(hwGrid)
        topSplit.addView(hwPanel)
        col.addView(topSplit)

        // System Settings Dock
        col.addView(makeSectionTitle("Система и настройки Android TV", R.drawable.ic_settings))
        val sysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(68)
            )
        }
        val sysItems = listOf(
            SysItem(R.drawable.sprite_sys_wifi, true, "Wi-Fi и сеть") {
                openSettingsIntent(Settings.ACTION_WIFI_SETTINGS, "com.android.tv.settings")
            },
            SysItem(R.drawable.sprite_sys_bt, true, "Bluetooth") {
                openTvAccessories()
            },
            SysItem(R.drawable.sprite_sys_eq, true, "Звук и аудио") {
                openSettingsActions("com.yandex.tv.settings.ACTION_SOUND", Settings.ACTION_SOUND_SETTINGS)
            },
            SysItem(R.drawable.ic_hw_display, false, "Экран HDMI") {
                openSettingsActions("com.yandex.tv.settings.ACTION_DISPLAY", Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)
            },
            SysItem(R.drawable.ic_hw_apps, false, "Приложения") {
                openSettingsIntent(Settings.ACTION_APPLICATION_SETTINGS, "com.android.tv.settings")
            },
            SysItem(R.drawable.ic_hw_keyboard, false, "Клавиатура") {
                openSettingsIntent(Settings.ACTION_INPUT_METHOD_SETTINGS, "com.android.tv.settings")
            },
            SysItem(R.drawable.ic_hw_info, false, "О системе") {
                openSettingsIntent(Settings.ACTION_DEVICE_INFO_SETTINGS, "com.android.tv.settings")
            },
            SysItem(R.drawable.sprite_sys_display, true, "Настройки TV") {
                launchPackageName("com.android.tv.settings")
            }
        )
        sysItems.forEachIndexed { idx, item ->
            val card = makeSysTile(item.iconRes, item.isSprite, item.label, item.onClick)
            card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (idx < sysItems.lastIndex) marginEnd = dp(6)
            }
            sysRow.addView(card)
        }
        col.addView(sysRow)

        scroll.addView(col)
        return scroll
    }

    // -----------------------------------------------------------------------------------------
    // MORPHING BOTTOM DOCK BAR (Receives shrunken AliceOrbView + shows Assistant Text)
    // -----------------------------------------------------------------------------------------
    private fun buildBottomDockBar(): LinearLayout {
        bottomDockBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.parseColor("#E8090E1A"),
                Color.parseColor("#EE101A2E"),
                Color.parseColor("#E80A1120")
            )
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.parseColor("#66E8C686"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bottomDockBg
            elevation = dp(10).toFloat()
            setPadding(dp(12), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                bottomDockBg.setStroke(
                    dp(if (hasFocus) 2 else 1),
                    Color.parseColor(if (hasFocus) "#FFE8C686" else "#66E8C686")
                )
                v.animate().scaleX(if (hasFocus) 1.012f else 1f).scaleY(if (hasFocus) 1.012f else 1f).setDuration(90).start()
            }
            setOnClickListener { triggerAlice() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(68),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(26)
                rightMargin = dp(26)
                bottomMargin = dp(10)
            }
        }

        // Empty left socket (62dp wide) directly beneath where heroOrbView scales down to 56dp
        val orbSlotSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(52)).apply {
                marginEnd = dp(12)
            }
        }
        bar.addView(orbSlotSpacer)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dockStateDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#38BDF8"))
        }
        val dockDot = View(this).apply {
            background = dockStateDotBg
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(7)
            }
        }
        tvDockState = TextView(this).apply {
            text = "АЛИСА · ГОТОВА"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        tvDockMusic = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
        }
        metaRow.addView(dockDot)
        metaRow.addView(tvDockState)
        metaRow.addView(tvDockMusic)

        tvDockReply = TextView(this).apply {
            text = "Скажите «Алиса» или нажмите сюда для вопроса"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), dp(8), 0)
        }
        textCol.addView(metaRow)
        textCol.addView(tvDockReply)
        bar.addView(textCol)

        btnDockHome = makeTextIconPill(R.drawable.ic_wave, "Сфера", gold = false) {
            setPage(0, animate = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        }
        bar.addView(btnDockHome)

        return bar
    }

    // -----------------------------------------------------------------------------------------
    // SMOOTH PAGE TRANSITION & CONTINUOUS SPHERE MORPHING MATH
    // -----------------------------------------------------------------------------------------
    private fun setPage(targetIdx: Int, animate: Boolean) {
        val clamped = targetIdx.coerceIn(0, 2)
        val prevPage = currentPage
        currentPage = clamped

        pageAnimator?.cancel()
        updateTabsAppearance()

        // Make involved pages visible during slide transition
        page0View.visibility = View.VISIBLE
        page1Scroll.visibility = View.VISIBLE
        page2Scroll.visibility = View.VISIBLE
        page0View.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        page1Scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        page2Scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        if (!animate || abs(clamped - currentPagePos) < 0.005f) {
            currentPagePos = clamped.toFloat()
            applyPageVisuals(currentPagePos)
            finalizePageVisibility()
            focusInitialControlOnPage(clamped)
            return
        }

        pageAnimator = ValueAnimator.ofFloat(currentPagePos, clamped.toFloat()).apply {
            duration = if (abs(clamped - prevPage) > 1) 480L else 420L
            interpolator = easeOutCubic
            addUpdateListener { anim ->
                currentPagePos = anim.animatedValue as Float
                applyPageVisuals(currentPagePos)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentPagePos = currentPage.toFloat()
                    applyPageVisuals(currentPagePos)
                    finalizePageVisibility()
                    focusInitialControlOnPage(currentPage)
                }
            })
            start()
        }
    }

    private fun finalizePageVisibility() {
        val pages = arrayOf<ViewGroup>(page0View, page1Scroll, page2Scroll)
        pages.forEachIndexed { idx, v ->
            val active = (idx == currentPage)
            v.visibility = if (active) View.VISIBLE else View.INVISIBLE
            v.descendantFocusability = if (active) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (currentPage == 0) {
            bottomDockBar.visibility = View.GONE
            bottomDockBar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            bottomDockBar.visibility = View.VISIBLE
            bottomDockBar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun focusInitialControlOnPage(idx: Int) {
        val cur = currentFocus
        // Keep focus on tab switcher if user switched screens via top tabs
        if (cur != null && tabViews.any { it === cur }) return
        handler.post {
            when (idx) {
                0 -> btnHeroOrbClick.requestFocus()
                1 -> btnHomeWave.requestFocus()
                2 -> tileRing.root.requestFocus()
            }
        }
    }

    private fun applyPageVisuals(pagePos: Float) {
        val w = (if (rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels).toFloat()
        val h = (if (rootFrame.height > 0) rootFrame.height else resources.displayMetrics.heightPixels).toFloat()
        val topBarBottom = max(dp(46).toFloat(), topBar.bottom.toFloat())

        // 1. Horizontal slide + depth alpha across the 3 screens
        page0View.translationX = (0f - pagePos) * w
        page1Scroll.translationX = (1f - pagePos) * w
        page2Scroll.translationX = (2f - pagePos) * w

        page0View.alpha = (1f - pagePos * 1.15f).coerceIn(0f, 1f)
        page1Scroll.alpha = (1f - abs(pagePos - 1f) * 0.45f).coerceIn(0f, 1f)
        page2Scroll.alpha = (1f - abs(pagePos - 2f) * 0.45f).coerceIn(0f, 1f)

        // 2. Bottom Dock Bar fade + rise into view for Screens 1 & 2
        val dockAlpha = ((pagePos - 0.14f) / 0.86f).coerceIn(0f, 1f)
        bottomDockBar.alpha = dockAlpha
        bottomDockBar.translationY = (1f - dockAlpha) * dp(44).toFloat()
        if (dockAlpha > 0.01f && bottomDockBar.visibility != View.VISIBLE) {
            bottomDockBar.visibility = View.VISIBLE
        }

        // 3. Single Persistent Alice Sphere Morph: 320dp center -> 56dp left socket in bottomDockBar
        val dockT = pagePos.coerceIn(0f, 1f)
        val heroSize = dp(320).toFloat()
        val dockSize = dp(56).toFloat()

        val cx0 = w * 0.5f
        val cy0 = topBarBottom + heroSize * 0.5f
        val cx1 = dp(26 + 12 + 31).toFloat()
        val cy1 = h - dp(10 + 34).toFloat()

        val cx = lerp(cx0, cx1, dockT)
        val cy = lerp(cy0, cy1, dockT)
        val sc = lerp(1.0f, dockSize / heroSize, dockT)

        heroOrbView.compactGlowBoost = dockT
        heroOrbView.pivotX = heroSize * 0.5f
        heroOrbView.pivotY = heroSize * 0.5f
        heroOrbView.scaleX = sc
        heroOrbView.scaleY = sc
        heroOrbView.translationX = cx - heroSize * 0.5f
        heroOrbView.translationY = cy - heroSize * 0.5f
    }

    private fun updateTabsAppearance(focusedIdx: Int = -1) {
        tabViews.forEachIndexed { idx, tv ->
            val selected = (idx == currentPage)
            val focused = (idx == focusedIdx)
            val shape = tabBgShapes[idx]
            when {
                selected -> {
                    shape.setColor(Color.parseColor("#33E8C686"))
                    shape.setStroke(dp(1), Color.parseColor(if (focused) "#FFFDE68A" else "#99E8C686"))
                    tv.setTextColor(Color.parseColor("#F8FAFC"))
                }
                focused -> {
                    shape.setColor(Color.parseColor("#2438BDF8"))
                    shape.setStroke(dp(1), Color.parseColor("#AAE8C686"))
                    tv.setTextColor(Color.WHITE)
                }
                else -> {
                    shape.setColor(Color.TRANSPARENT)
                    shape.setStroke(0, Color.TRANSPARENT)
                    tv.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // LIVE ASSISTANT / ORB UI TEXT SYNC (Screen 0 Hero Card + Bottom Dock Bar)
    // -----------------------------------------------------------------------------------------
    private fun renderOrbAssistantUi() {
        val st = LiveOrbState.assistantState
        val userTxt = LiveOrbState.liveUserText.trim()
        val display = LiveOrbState.bestDisplayText()

        val accent: Int
        val borderColor: Int
        val stateLabel: String
        val heroFallback: String
        val dockFallback: String

        when (st) {
            "connecting" -> {
                accent = Color.parseColor("#38BDF8")
                borderColor = Color.parseColor("#8838BDF8")
                stateLabel = "АЛИСА · ПОДКЛЮЧЕНИЕ"
                heroFallback = "Подключаю голосовой поток..."
                dockFallback = "Подключаю голосовой поток..."
            }
            "listening" -> {
                accent = Color.parseColor("#2DD4BF")
                borderColor = Color.parseColor("#992DD4BF")
                stateLabel = "АЛИСА · СЛУШАЮ"
                heroFallback = "Говорите, я внимательно слушаю..."
                dockFallback = "Говорите, я внимательно слушаю..."
            }
            "thinking" -> {
                accent = Color.parseColor("#E8C686")
                borderColor = Color.parseColor("#AAE8C686")
                stateLabel = "АЛИСА · ДУМАЮ"
                heroFallback = if (userTxt.isNotEmpty()) "Обрабатываю: «$userTxt»" else "Формирую ответ..."
                dockFallback = if (userTxt.isNotEmpty()) "Обрабатываю: «$userTxt»" else "Формирую ответ..."
            }
            "speaking" -> {
                accent = Color.parseColor("#C084FC")
                borderColor = Color.parseColor("#AAC084FC")
                stateLabel = "АЛИСА · ОТВЕЧАЮ"
                heroFallback = "Отвечаю голосом..."
                dockFallback = "Отвечаю голосом..."
            }
            "error" -> {
                accent = Color.parseColor("#F87171")
                borderColor = Color.parseColor("#AAF87171")
                stateLabel = "АЛИСА · ОШИБКА"
                heroFallback = "Не удалось выполнить запрос"
                dockFallback = "Ошибка ассистента"
            }
            else -> {
                accent = if (assistantEnabled) Color.parseColor("#E8C686") else Color.parseColor("#64748B")
                borderColor = Color.parseColor("#4AE8C686")
                stateLabel = if (assistantEnabled) "АЛИСА · ГОТОВА" else "АЛИСА · ВЫКЛЮЧЕНА"
                heroFallback = "Скажите «Алиса» или нажмите OK для общения"
                dockFallback = "Скажите «Алиса» или нажмите, чтобы задать вопрос"
            }
        }

        heroStateDotBg.setColor(accent)
        dockStateDotBg.setColor(accent)
        heroReplyCardBg.setStroke(dp(1), borderColor)
        bottomDockBg.setStroke(dp(1), borderColor)

        tvHeroState.text = stateLabel
        tvHeroState.setTextColor(accent)
        tvDockState.text = stateLabel
        tvDockState.setTextColor(accent)

        if (userTxt.isNotEmpty() && display != "«$userTxt»") {
            tvHeroUser.text = "Вы: «$userTxt»"
            tvHeroUser.visibility = View.VISIBLE
        } else {
            tvHeroUser.text = ""
            tvHeroUser.visibility = View.GONE
        }

        val heroBody = display.ifEmpty { heroFallback }
        tvHeroReply.text = heroBody
        tvHeroReply.setTextColor(
            if (display.isNotEmpty()) Color.parseColor("#F8FAFC") else Color.parseColor("#CBD5E1")
        )

        val dockBody = display.ifEmpty { dockFallback }
        tvDockReply.text = dockBody
        tvDockReply.setTextColor(
            if (display.isNotEmpty()) Color.parseColor("#F8FAFC") else Color.parseColor("#CBD5E1")
        )
    }

    // -----------------------------------------------------------------------------------------
    // REUSABLE UI FACTORIES
    // -----------------------------------------------------------------------------------------
    private fun makeSectionTitle(label: String, iconRes: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, 0, dp(7))
        val iv = ImageView(this@MainActivity).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                marginEnd = dp(7)
            }
        }
        val tv = TextView(this@MainActivity).apply {
            text = label.uppercase(Locale.getDefault())
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(iv)
        addView(tv)
    }

    private fun makeHwTile(iconRes: Int, initialText: String, onClick: () -> Unit): HwTileViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundResource(R.drawable.bg_tile)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(90).start()
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(8)
            }
        }
        val tv = TextView(this).apply {
            text = initialText
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(iv)
        root.addView(tv)
        return HwTileViews(root, iv, tv)
    }

    private fun makeIconBtn(iconRes: Int, tintColor: Int?, onClick: () -> Unit): IconBtnViews {
        val box = FrameLayout(this).apply {
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
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        }
        box.addView(iv)
        return IconBtnViews(box, iv)
    }

    private fun makeTextIconPill(iconRes: Int, label: String, gold: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(14), dp(4))
            setBackgroundResource(if (gold) R.drawable.bg_pill_gold else R.drawable.bg_pill)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(90).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(this@MainActivity).apply {
                val d = getDrawable(iconRes)?.mutate()
                if (d != null && gold) d.setTint(Color.parseColor("#0B0F17"))
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginEnd = dp(6)
                }
            }
            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor(if (gold) "#0B0F17" else "#F1F5F9"))
                textSize = 11.5f
                typeface = if (gold) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            }
            addView(iv)
            addView(tv)
        }

    private fun makeSysTile(iconRes: Int, isSprite: Boolean, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setBackgroundResource(R.drawable.bg_tile)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(95).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val iconSz = if (isSprite) dp(26) else dp(22)
                layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                    bottomMargin = dp(5)
                }
            }
            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(iv)
            addView(tv)
        }

    // -----------------------------------------------------------------------------------------
    // APPS GRID & LAUNCHERS
    // -----------------------------------------------------------------------------------------
    private fun loadAndRenderApps() {
        val pm = packageManager
        val found = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, isLeanback: Boolean) {
            val qi = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list = pm.queryIntentActivities(qi, 0)
            for (ri in list) {
                val pkg = ri.activityInfo.packageName ?: continue
                if (pkg == packageName || pkg == "com.openstation.ime") continue
                if (pkg.startsWith("com.yandex.") || pkg.startsWith("ru.yandex.")) continue
                val existing = found[pkg]
                if (existing != null && existing.leanback) continue

                val launch = if (isLeanback) {
                    pm.getLeanbackLaunchIntentForPackage(pkg)
                } else {
                    pm.getLaunchIntentForPackage(pkg)
                } ?: Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val lbl = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
                val ico = try { ri.loadIcon(pm) } catch (_: Throwable) { applicationInfo.loadIcon(pm) }
                found[pkg] = AppEntry(pkg, lbl, ico, launch, isLeanback)
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val entries = found.values.sortedWith(
            compareByDescending<AppEntry> { it.leanback }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )

        appsGrid.removeAllViews()
        val tileMargin = dp(5)
        val screenW = resources.displayMetrics.widthPixels - dp(52)
        val colW = maxOf(dp(150), (screenW / 5) - tileMargin * 2)

        run {
            val ymCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_tile)
                isFocusable = true
                isClickable = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = colW
                    height = dp(80)
                    setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
                }
            }
            val ymIv = ImageView(this).apply {
                setImageResource(R.drawable.sprite_ym_emblem)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(10)
                }
            }
            val txtCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val ymName = TextView(this).apply {
                text = "Яндекс Музыка"
                setTextColor(Color.WHITE)
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val ymSub = TextView(this).apply {
                text = "Моя волна · Hi-Fi"
                setTextColor(Color.parseColor("#E8C686"))
                textSize = 10.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            txtCol.addView(ymName)
            txtCol.addView(ymSub)
            ymCard.addView(ymIv)
            ymCard.addView(txtCol)
            ymCard.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(100).start()
            }
            ymCard.setOnClickListener { launchYandexMusic() }
            appsGrid.addView(ymCard)
        }

        for (app in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_tile)
                isFocusable = true
                isClickable = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = colW
                    height = dp(80)
                    setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
                }
            }
            val iv = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(10)
                }
            }
            val txtCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvSub = TextView(this).apply {
                text = if (app.leanback) "Android TV" else app.pkg
                setTextColor(Color.parseColor(if (app.leanback) "#38BDF8" else "#64748B"))
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            txtCol.addView(tvName)
            txtCol.addView(tvSub)
            card.addView(iv)
            card.addView(txtCol)

            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(100).start()
            }
            card.setOnClickListener { launchApp(app) }
            card.setOnLongClickListener {
                showAppMenu(app)
                true
            }
            appsGrid.addView(card)
        }

        val arr = JSONArray()
        for (a in entries) {
            arr.put(
                JSONObject()
                    .put("package", a.pkg)
                    .put("label", a.label)
                    .put("leanback", a.leanback)
            )
        }
        val appsJson = arr.toString()
        cachedAppsJson = appsJson
        postAsync("http://127.0.0.1:8080/api/apps/cache", appsJson)
    }

    private fun launchApp(app: AppEntry) {
        try {
            app.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(app.intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось запустить ${app.label}: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPackageName(pkg: String) {
        try {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            }
        } catch (_: Throwable) {}
        Toast.makeText(this, "Пакет $pkg недоступен", Toast.LENGTH_SHORT).show()
    }

    private fun showAppMenu(app: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf("Открыть", "О приложении в системе", "Обновить список")) { _, which ->
                when (which) {
                    0 -> launchApp(app)
                    1 -> {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${app.pkg}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Throwable) {}
                    }
                    2 -> loadAndRenderApps()
                }
            }
            .show()
    }

    private fun openSettingsActions(vararg actions: String, fallbackPkg: String = "com.android.tv.settings") {
        for (act in actions) {
            try {
                startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {}
        }
        launchPackageName(fallbackPkg)
    }

    private fun openSettingsIntent(action: String, fallbackPkg: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            launchPackageName(fallbackPkg)
        }
    }

    private fun openTvAccessories() {
        val intents = listOf(
            Intent("com.google.android.intent.action.CONNECT_INPUT"),
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity")),
            Intent("android.bluetooth.devicepicker.action.LAUNCH"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"))
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Throwable) {}
        }
        launchPackageName("com.android.tv.settings")
    }

    // -----------------------------------------------------------------------------------------
    // CLOCK, NETWORK & DAEMON TELEMETRY
    // -----------------------------------------------------------------------------------------
    private fun updateClock() {
        val now = Date()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val fullDate = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru"))
            .format(now).replaceFirstChar { it.uppercase() }
        val shortDate = SimpleDateFormat("EEE, d MMM", Locale("ru"))
            .format(now).replaceFirstChar { it.uppercase() }

        tvTopClock.text = timeStr
        tvTopDate.text = shortDate
        tvClock.text = timeStr
        tvDate.text = fullDate
    }

    private fun detectLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            var candidate = ""
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) return ip
                        candidate = ip
                    }
                }
            }
            if (candidate.isNotEmpty()) return candidate
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }

    private fun updateNetworkHeader() {
        val ip = detectLocalIp()
        val url = "http://$ip:8080"
        val onlineNet = try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork != null
        } catch (_: Throwable) { false }
        tvIpLine.text = if (onlineNet || ip != "127.0.0.1") {
            "$ip:8080 · station-max.local"
        } else {
            "Локально: 127.0.0.1:8080"
        }
        if (ip != lastQrIp && ip.isNotEmpty()) {
            lastQrIp = ip
            val bmp = QrGenerator.generateBitmap(url, 180)
            if (bmp != null) ivQr.setImageBitmap(bmp)
        }
    }

    private fun pollDaemonStatus() {
        thread(isDaemon = true) {
            try {
                val conn = (URL("http://127.0.0.1:8080/api/status").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 800
                    readTimeout = 800
                }
                val code = conn.responseCode
                if (code == 200) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(txt)
                    val hw = root.optJSONObject("hardware")
                    val cfg = root.optJSONObject("config")
                    val audio = root.optJSONObject("audio")
                    val engine = audio?.optJSONObject("engine")
                    val ringCfg = cfg?.optJSONObject("ledRing")
                    val audioCfg = cfg?.optJSONObject("audio")
                    val ast = root.optJSONObject("assistant")
                    val astCfg = cfg?.optJSONObject("assistant")

                    val lux = hw?.optDouble("lux", 0.0) ?: 0.0
                    val temp = hw?.optDouble("socTempC", 0.0) ?: 0.0
                    val screenConn = hw?.optBoolean("fb5Present", false) ?: false

                    ringMode = ringCfg?.optString("mode", ringMode) ?: ringMode
                    micMuted = audioCfg?.optBoolean("micMuted", micMuted) ?: micMuted
                    outputRoute = audioCfg?.optString("outputRoute", outputRoute) ?: outputRoute
                    eqPreset = audioCfg?.optString("eqPreset", eqPreset) ?: eqPreset
                    captureMode = audioCfg?.optString("captureMode", captureMode) ?: captureMode
                    capturePaused = !(engine?.optBoolean("captureEnabled", true) ?: true)
                    volumePct = audio?.optInt("volumePercent", volumePct) ?: volumePct
                    assistantState = ast?.optString("state", "idle") ?: "idle"
                    assistantEnabled = astCfg?.optBoolean("enabled", true) ?: true
                    val wasOffline = !daemonOnline
                    daemonOnline = true
                    if (wasOffline && cachedAppsJson.isNotEmpty()) {
                        httpPostJson("http://127.0.0.1:8080/api/apps/cache", cachedAppsJson)
                    }
                    val daemonMusic = root.optJSONObject("music")

                    // Fallback reconciliation if UDP packet missed or first open after boot
                    if (ast != null) {
                        val nowElapsed = SystemClock.elapsedRealtime()
                        val staleOrb = (LiveOrbState.lastActiveAtMs == 0L && LiveOrbState.lastReplyAtMs == 0L) ||
                            (assistantState == "idle" && LiveOrbState.assistantState != "idle" &&
                                (nowElapsed - LiveOrbState.lastActiveAtMs) > 3000L)
                        if (staleOrb) {
                            LiveOrbState.updateAssistantEvent(
                                state = assistantState,
                                liveUser = ast.optString("liveUserText", ""),
                                liveModel = ast.optString("liveModelText", ""),
                                lastModel = ast.optString("lastModelText", ""),
                                error = ast.optString("lastError", "")
                            )
                        }
                    }

                    val ip = detectLocalIp()
                    handler.post {
                        updateNetworkHeader()
                        val scrStr = if (screenConn) "OLED ОК" else "OLED выкл"
                        tvStatusLine.text = String.format(
                            Locale.US,
                            "● Гр:%d%% · %.0f°C · %s",
                            volumePct, temp, ip
                        )
                        tvStationTelemetry.text = String.format(
                            Locale.US,
                            "● %.0f°C · %.1flx · %s · %s · EQ: %s",
                            temp, lux, scrStr,
                            if (StationAudioService.visualizerActive) "Спектр ОК" else "Спектр —",
                            StationAudioService.eqBackend
                        )
                        val svcSnap = StationAudioService.instance?.musicPlayer?.getSnapshot()
                        if (svcSnap != null) {
                            renderMusicSnapshot(svcSnap)
                        } else if (daemonMusic != null && daemonMusic.optLong("seq", 0L) > 0L) {
                            renderMusicSnapshot(YmPlayerSnapshot.fromJson(daemonMusic))
                        }
                        syncPills()
                    }
                } else {
                    conn.disconnect()
                    markDaemonOffline()
                }
                conn.disconnect()
            } catch (_: Throwable) {
                markDaemonOffline()
            }
        }
    }

    private fun markDaemonOffline() {
        daemonOnline = false
        handler.post {
            tvStatusLine.text = "○ openstationd:8080"
            tvStationTelemetry.text = "○ Ожидание openstationd (порт 8080)"
            syncPills()
        }
    }

    private fun syncPills() {
        val ringLabel = when (ringMode) {
            "rainbow" -> "Радуга"
            "solid" -> "Статика"
            "breathe" -> "Дыхание"
            "spinner" -> "Спиннер"
            "spectrum", "music" -> "Спектр"
            "off" -> "Выкл"
            else -> ringMode
        }
        tileRing.label.text = "Кольцо: $ringLabel"
        tileMute.label.text = if (micMuted) "Мик: MUTE" else "Мик: ВКЛ"
        tileMute.icon.setImageResource(if (micMuted) R.drawable.ic_hw_mic_off else R.drawable.ic_hw_mic_on)
        tileMute.root.isSelected = micMuted

        tileRoute.label.text = "Выход: " + when (outputRoute) {
            "speakers" -> "Динам."
            "headphones" -> "3.5мм"
            "hdmi" -> "HDMI"
            "both" -> "Все"
            else -> "Авто"
        }
        tileEq.label.text = "EQ: " + when (eqPreset) {
            "bass_boost" -> "Бас+"
            "vocal" -> "Вокал"
            "night" -> "Ночь"
            "flat" -> "Flat"
            else -> "Баланс"
        }
        tileCapMode.label.text = if (captureMode == "alsa_16ch") "Захват: 16ch" else "Захват: AAudio"
        tileMicPause.label.text = if (capturePaused) "Поток: ПАУЗА" else "Поток: ВКЛ"
        tileMicPause.root.isSelected = capturePaused

        val glowColors = when {
            assistantState != "idle" -> intArrayOf(Color.parseColor("#7700D4FF"), Color.parseColor("#66A855F7"))
            ringMode == "off" -> intArrayOf(Color.parseColor("#11FFFFFF"), Color.parseColor("#00FFFFFF"))
            ringMode == "solid" -> intArrayOf(Color.parseColor("#55E8C686"), Color.parseColor("#3338BDF8"))
            else -> intArrayOf(Color.parseColor("#4438BDF8"), Color.parseColor("#38E8C686"))
        }
        ivStationRingGlow.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, glowColors).apply {
            shape = GradientDrawable.OVAL
        }
        renderOrbAssistantUi()
        updateAnimationsState()
    }

    private fun updateAnimationsState() {
        if (latestMusicSnap.playing) {
            vinylAnimator?.let {
                if (!it.isStarted) it.start() else if (it.isPaused) it.resume()
            }
        } else {
            vinylAnimator?.let { if (it.isRunning) it.pause() }
        }

        if (latestMusicSnap.playing || LiveOrbState.assistantState != "idle") {
            dialAnimator?.let {
                if (!it.isStarted) it.start() else if (it.isPaused) it.resume()
            }
        } else {
            dialAnimator?.let { if (it.isRunning) it.pause() }
        }
    }

    // -----------------------------------------------------------------------------------------
    // MUSIC SNAPSHOT SYNC
    // -----------------------------------------------------------------------------------------
    private fun refreshMusicSnapshotFromService() {
        val p = StationAudioService.instance?.musicPlayer
        if (p != null) {
            renderMusicSnapshot(p.getSnapshot())
        } else {
            handler.postDelayed({
                StationAudioService.instance?.musicPlayer?.let { renderMusicSnapshot(it.getSnapshot()) }
            }, 350L)
        }
    }

    private fun setHomeCoverArt(bmp: android.graphics.Bitmap?) {
        if (bmp != null) {
            ivHomeCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivHomeCover.background = GradientDrawable().apply {
                setColor(Color.parseColor("#111927"))
                cornerRadius = dp(14).toFloat()
            }
            ivHomeCover.setImageBitmap(bmp)
            viewHomeCoverBorder.visibility = View.VISIBLE
        } else {
            ivHomeCover.scaleType = ImageView.ScaleType.FIT_CENTER
            ivHomeCover.background = null
            ivHomeCover.setImageResource(R.drawable.sprite_ym_emblem)
            viewHomeCoverBorder.visibility = View.GONE
        }
    }

    private fun renderMusicSnapshot(snap: YmPlayerSnapshot) {
        latestMusicSnap = snap
        LiveOrbState.musicPlaying = snap.playing
        tvHomeSourceBadge.text = snap.sourceLabel.uppercase(Locale.getDefault())

        if (snap.codecLabel.isNotBlank()) {
            tvHomeCodecBadge.text = snap.codecLabel
            tvHomeCodecBadge.visibility = View.VISIBLE
        } else {
            tvHomeCodecBadge.visibility = View.GONE
        }

        val err = snap.lastError
        val errClean = err.removePrefix("AUTH_REQUIRED:").trim()
        when {
            err.isNotBlank() -> {
                tvHomeAuthBadge.text = "⚠ $errClean"
                tvHomeAuthBadge.setTextColor(Color.parseColor("#FCA5A5"))
            }
            snap.buffering -> {
                tvHomeAuthBadge.text = "Загрузка потока..."
                tvHomeAuthBadge.setTextColor(Color.parseColor("#E8C686"))
            }
            snap.account.loggedIn -> {
                val nm = snap.account.login.ifBlank { snap.account.fullName.ifBlank { "OK" } }
                val plus = if (snap.account.hasPlus) " · Плюс" else ""
                tvHomeAuthBadge.text = "● $nm$plus"
                tvHomeAuthBadge.setTextColor(Color.parseColor("#6EE7B7"))
            }
            else -> {
                tvHomeAuthBadge.text = "Нужен вход · нажмите «Плеер»"
                tvHomeAuthBadge.setTextColor(Color.parseColor("#E8C686"))
            }
        }

        val tr = snap.currentTrack
        if (tr != null) {
            tvHomeTrackTitle.text = tr.title
            tvHomeTrackArtist.text = if (tr.album.isNotBlank()) "${tr.artists}  ·  ${tr.album}" else tr.artists
            val dur = maxOf(snap.durationMs, tr.durationMs)
            val pos = snap.positionMs.coerceAtLeast(0L)
            pbHomeTrack.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
            tvHomeTrackTime.text = "${formatTimeMs(pos)} / ${formatTimeMs(dur)}"

            val prefix = if (snap.playing) "♫ " else "❚❚ "
            val snippet = "$prefix${tr.artists} — ${tr.title}"
            tvHeroMusicChip.text = snippet
            tvDockMusic.text = snippet
            tvDockMusic.visibility = View.VISIBLE

            val likeDrawable = getDrawable(if (tr.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            btnHomeLike.icon.setImageDrawable(likeDrawable)

            val coverUrl = tr.coverUrl(300)
            if (coverUrl != lastHomeCoverUrl) {
                lastHomeCoverUrl = coverUrl
                if (coverUrl.isBlank()) {
                    setHomeCoverArt(null)
                } else {
                    val api = StationAudioService.instance?.musicPlayer?.api
                    if (api != null) {
                        thread(isDaemon = true) {
                            val bmp = api.loadCoverBitmap(coverUrl, 300)
                            if (bmp != null && lastHomeCoverUrl == coverUrl) {
                                handler.post {
                                    if (lastHomeCoverUrl == coverUrl) {
                                        setHomeCoverArt(bmp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            tvHomeTrackTitle.text = "Моя волна — поток музыки"
            tvHomeTrackArtist.text = "Скажите «Алиса, включи музыку» или жмите «Моя волна»"
            pbHomeTrack.progress = 0
            tvHomeTrackTime.text = "00:00 / 00:00"
            tvHeroMusicChip.text = "Яндекс Музыка · Моя волна"
            tvDockMusic.text = ""
            tvDockMusic.visibility = View.GONE
            btnHomeLike.icon.setImageDrawable(getDrawable(R.drawable.ic_heart_outline))
            if (lastHomeCoverUrl.isNotEmpty()) {
                lastHomeCoverUrl = ""
                setHomeCoverArt(null)
            }
        }

        val ppIcon = getDrawable(
            when {
                snap.buffering -> R.drawable.ic_refresh
                snap.playing -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
        )?.mutate()?.apply {
            setTint(Color.parseColor("#E8C686"))
        }
        btnHomePlayPause.icon.setImageDrawable(ppIcon)
        updateAnimationsState()
    }

    private fun formatTimeMs(ms: Long): String {
        val secTotal = (ms / 1000L).coerceAtLeast(0L)
        val m = secTotal / 60L
        val s = secTotal % 60L
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun sendMusicServiceCmd(cmd: String, extraAction: String = "") {
        val p = StationAudioService.instance?.musicPlayer
        if (p != null) {
            when (cmd) {
                "play_my_wave" -> p.playMyWave("any", "", openUiOnAuthNeeded = true)
                "action" -> sendMusicAction(extraAction)
            }
            return
        }
        try {
            startForegroundService(Intent(this, StationAudioService::class.java).apply {
                action = StationAudioService.ACTION_MUSIC_CMD
                putExtra("cmd", cmd)
                if (extraAction.isNotEmpty()) putExtra("action", extraAction)
            })
        } catch (t: Throwable) {
            Toast.makeText(this, "Ошибка музыки: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMusicAction(action: String) {
        val p = StationAudioService.instance?.musicPlayer
        if (p != null) {
            when (action) {
                "PLAY_PAUSE" -> p.togglePlayPause()
                "NEXT" -> p.next()
                "PREV" -> p.prev()
                "LIKE" -> p.toggleLikeCurrent()
            }
            return
        }
        sendMusicServiceCmd("action", action)
    }

    private fun launchYandexMusic() {
        try {
            val i = Intent(this, YandexMusicActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось открыть Яндекс Музыку: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerAlice() {
        val activeNow = (LiveOrbState.assistantState != "idle" && LiveOrbState.assistantState != "error") ||
            (assistantState != "idle" && assistantState != "error")
        if (activeNow) {
            postAsync("http://127.0.0.1:8080/api/assistant/stop", "{}")
        } else {
            postAsync("http://127.0.0.1:8080/api/assistant/trigger", """{"reason":"launcher"}""")
        }
    }

    private fun cycleRingMode() {
        val order = listOf("music", "rainbow", "solid", "breathe", "spinner", "off")
        val next = order[(order.indexOf(ringMode) + 1) % order.size]
        ringMode = next
        syncPills()
        val body = JSONObject().put("ledRing", JSONObject().put("mode", next)).toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun toggleMute() {
        micMuted = !micMuted
        syncPills()
        val body = JSONObject().put("audio", JSONObject().put("micMuted", micMuted)).toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun cycleOutputRoute() {
        val order = listOf("auto", "speakers", "headphones", "hdmi", "both")
        val next = order[(order.indexOf(outputRoute) + 1) % order.size]
        outputRoute = next
        syncPills()
        val body = JSONObject().put("route", next).toString()
        postAsync("http://127.0.0.1:8080/api/audio/route", body)
    }

    private fun cycleEqPreset() {
        val order = listOf("balanced", "bass_boost", "vocal", "night", "flat")
        val next = order[(order.indexOf(eqPreset) + 1) % order.size]
        eqPreset = next
        syncPills()
        try {
            startForegroundService(Intent(this, StationAudioService::class.java).apply {
                action = StationAudioService.ACTION_SET_EQ
                putExtra(StationAudioService.EXTRA_PRESET, next)
            })
        } catch (_: Throwable) {}
        val body = JSONObject().put("audio", JSONObject().put("eqPreset", next)).toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun toggleCaptureMode() {
        val next = if (captureMode == "aaudio") "alsa_16ch" else "aaudio"
        captureMode = next
        syncPills()
        val body = JSONObject().put("audio", JSONObject().put("captureMode", next)).toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun toggleCapturePause() {
        capturePaused = !capturePaused
        syncPills()
        val body = JSONObject().put("enabled", !capturePaused).toString()
        postAsync("http://127.0.0.1:8080/api/audio/capture_toggle", body)
    }

    private fun sendCec(cmd: String) {
        val body = JSONObject().put("cmd", cmd).toString()
        postAsync("http://127.0.0.1:8080/api/remote/cec", body)
        Toast.makeText(this, "HDMI-CEC: $cmd", Toast.LENGTH_SHORT).show()
    }

    private fun postAsync(url: String, jsonBody: String) {
        thread(isDaemon = true) { httpPostJson(url, jsonBody) }
    }

    private fun httpPostJson(url: String, jsonBody: String) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 900
                readTimeout = 900
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {}
    }
}
