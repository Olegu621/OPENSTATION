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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.openstation.launcher.market.MarketInstaller
import com.openstation.launcher.market.MarketPrefs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
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
import android.view.ViewOutlineProvider
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
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    companion object {
        private const val PKG_OPENSTATION_APPS = "__openstation_apps__"
        private const val PKG_YANDEX_MUSIC = "__ym__"

        @Volatile
        private var activeInstance: MainActivity? = null

        fun showWeatherOverlay(temp: Int, condition: String, conditionText: String, city: String) {
            val act = activeInstance ?: return
            act.runOnUiThread {
                act.weatherOverlay.showWeather(temp, condition, conditionText, city)
            }
        }

        fun refreshAppsFromService() {
            val act = activeInstance ?: return
            act.runOnUiThread {
                act.loadAndRenderApps()
            }
        }

        fun triggerStartupShow() {
            val act = activeInstance ?: return
            act.runOnUiThread {
                act.runStartupOrbShow()
            }
        }
    }

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable,
        val intent: Intent,
        val leanback: Boolean,
        val isVirtual: Boolean = false,
        val subtitle: String? = null
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
    private lateinit var ivBg: ImageView
    private lateinit var shellCol: LinearLayout
    private lateinit var blurCurtainHost: FrameLayout
    private lateinit var ivBlurCurtain: ImageView
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
    private lateinit var heroReplyHeader: LinearLayout
    private lateinit var heroStateDotBg: GradientDrawable
    private lateinit var tvHeroState: TextView
    private lateinit var tvHeroUser: TextView
    private lateinit var tvHeroReply: TextView
    private lateinit var tvHeroMicroHint: TextView
    private val appAccentCache = HashMap<String, Int>()

    // Screen 0: Modular Liquid Glass Home Widgets (OFF by default)
    private lateinit var wLeftCol: LinearLayout
    private lateinit var wRightCol: LinearLayout
    private lateinit var eqWidgetController: HomeEqWidgetController
    private lateinit var wBtCard: LinearLayout
    private lateinit var tvWidgetBtState: TextView
    private lateinit var vWidgetBtDotBg: GradientDrawable
    private lateinit var wWeatherCard: LinearLayout
    private lateinit var tvWidgetWeatherTemp: TextView
    private lateinit var tvWidgetWeatherCity: TextView
    private lateinit var tvWidgetWeatherDesc: TextView
    private lateinit var wRemoteCard: LinearLayout
    private lateinit var tvWidgetRemoteState: TextView
    private var btEnabledState = false
    private var btDiscoverableState = false
    private var startupShowActive = false
    @Volatile private var bootRevealStarted = false
    @Volatile private var returningFromExternalApp = false

    // Overlays: Apps Drawer (DPAD_DOWN), Home Customization (Long-press OK), Anchored App Menu & Info
    private lateinit var appsDrawerOverlay: FrameLayout
    private lateinit var drawerScrollView: ScrollView
    private lateinit var drawerAppsGrid: GridLayout
    private lateinit var tvDrawerStatusPill: TextView
    private lateinit var homeCustomOverlay: FrameLayout
    private lateinit var appContextOverlay: FrameLayout
    private lateinit var appInfoOverlay: FrameLayout
    private var appsDrawerOpen = false
    private var homeCustomOpen = false
    private var appContextOpen = false
    private var appInfoOpen = false
    private var isProgrammaticPageFocus = false
    private var activeAppEntries: List<AppEntry> = emptyList()
    private val drawerTileViews = LinkedHashMap<String, View>()
    private val screen1TileViews = LinkedHashMap<String, View>()
    private var reorderingPkg: String? = null
    private var okLongPressFired = false
    private var okLongPressAnchor: View? = null
    private val okLongPressRunnable = Runnable {
        okLongPressFired = true
        handleOkLongPress(okLongPressAnchor)
    }

    private lateinit var bottomDockBar: LinearLayout
    private lateinit var bottomDockBg: LiquidGlassKit.RefractiveGlassDrawable
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
    private lateinit var tvTopWeather: TextView
    private lateinit var tvStatusLine: TextView
    private lateinit var weatherOverlay: WeatherOverlayView

    // Screen 2: Duo Clock, QR & BORK Station 3D visual
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvIpLine: TextView
    private lateinit var tvStationTelemetry: TextView
    private lateinit var ivQr: ImageView
    private lateinit var ivStationRingGlow: View
    private var stationRingGlowBg: GradientDrawable? = null
    private lateinit var ivDialRing: ImageView
    private var dialAnimator: ObjectAnimator? = null
    private lateinit var btnOtaUpdate: TextView
    @Volatile private var otaLatestJson: JSONObject? = null
    private var otaDialog: AlertDialog? = null
    private var otaDialogStatusTv: TextView? = null
    private var otaDialogChangelogTv: TextView? = null
    private var otaDialogInstallBtn: android.widget.Button? = null
    private var lastOtaBtnKey = ""

    // Hardware quick-control tiles (Screen 2)
    private lateinit var tileRing: HwTileViews
    private lateinit var tileMute: HwTileViews
    private lateinit var tileRoute: HwTileViews
    private lateinit var tileEq: HwTileViews
    private lateinit var tileVis: HwTileViews
    private lateinit var tileCec: HwTileViews

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
    private var fwVer = "1.5.0"
    private var ringMode = "music"
    private var micMuted = false
    private var outputRoute = "auto"
    private var eqPreset = "balanced"
    private var eqBandsSynced = FloatArray(10)
    private var bassBoostSynced = 20
    private var assistantState = "idle"
    private var assistantEnabled = true
    private var volumePct = 50
    @Volatile private var daemonOnline = false
    @Volatile private var cachedAppsJson = ""
    private var lastQrIp = ""
    private var setupCompletedState: Boolean? = null
    private var wizardAutoOpenedThisRun = false

    // Cached formatters & diff keys to eliminate redundant main-thread allocations/relayouts
    private val ruLocale = Locale("ru")
    private val timeFmt by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val fullDateFmt by lazy { SimpleDateFormat("EEEE, d MMMM yyyy", ruLocale) }
    private val shortDateFmt by lazy { SimpleDateFormat("EEE, d MMM", ruLocale) }
    private val clockDate = Date()
    private var lastClockStr = ""
    private var lastShortDateStr = ""
    private var lastFullDateStr = ""
    @Volatile private var cachedLocalIp = "127.0.0.1"
    @Volatile private var lastIpDetectMs = 0L
    private var lastIpLineStr = ""
    private var lastStatusLineText = ""
    private var lastTelemetryText = ""
    private var lastOrbUiKey = ""
    private var lastPillsKey = ""
    private var lastGlowStateKey = ""
    private var lastRenderedMicMuted: Boolean? = null
    private var lastMusicChipStr = ""
    private var lastDockMusicStr = ""
    private var lastTrackLiked: Boolean? = null
    private var lastPlayPauseIconKey = ""

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

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateClock(force = true)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDaemonStatus()
            val active = (assistantState != "idle") || (LiveOrbState.assistantState != "idle") ||
                latestMusicSnap.playing || (currentPage == 2)
            handler.postDelayed(this, if (active) 2000L else 5000L)
        }
    }

    private val widgetEqTickRunnable = object : Runnable {
        override fun run() {
            if (::eqWidgetController.isInitialized && eqWidgetController.rootCard.visibility == View.VISIBLE && currentPage == 0) {
                val live = LiveOrbState.musicPlaying || LiveOrbState.assistantState != "idle"
                eqWidgetController.updateLiveSpectrum(LiveOrbState.rawBands, live)
                val delay = if (live) 45L else 220L
                handler.postDelayed(this, delay)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        window.setBackgroundDrawable(null)
        LiquidGlassKit.ensureInitialized(this)

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
        startBootRevealFlow()

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
        try {
            val tf = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            registerReceiver(clockReceiver, tf)
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent == null || intent.getBooleanExtra("go_home", false) || intent.hasCategory(Intent.CATEGORY_HOME)) {
            intent?.removeExtra("go_home")
            closeAllOverlays(immediate = true)
            if (::weatherOverlay.isInitialized && weatherOverlay.visibility == View.VISIBLE) {
                weatherOverlay.visibility = View.GONE
            }
            if (::page0View.isInitialized) {
                setPage(0, animate = true)
                settleFromExternalAppIfNeeded()
                btnHeroOrbClick.post {
                    if (currentPage == 0 && !appsDrawerOpen && !homeCustomOpen && !appContextOpen && !appInfoOpen) {
                        isProgrammaticPageFocus = true
                        try { btnHeroOrbClick.requestFocus() } finally { isProgrammaticPageFocus = false }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StationAudioService.setLauncherForegroundState(true)
        StationAudioService.ensureStarted(this)
        LiveOrbState.addListener(orbStateListener)
        val savedWall = LiquidGlassKit.loadSavedThemeId(this)
        if (savedWall != LiquidGlassKit.currentThemeId) {
            applyWallpaperTheme(savedWall, persist = false)
        }
        if (intent?.getBooleanExtra("go_home", false) == true) {
            intent.removeExtra("go_home")
            closeAllOverlays(immediate = true)
            setPage(0, animate = false)
        }
        settleFromExternalAppIfNeeded()
        updateNetworkHeader()
        updateClock()
        refreshMusicSnapshotFromService()
        renderOrbAssistantUi()
        syncHomeWidgetsUi()
        updateAnimationsState()
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(widgetEqTickRunnable)
        handler.post(clockRunnable)
        handler.post(pollRunnable)
        handler.post(widgetEqTickRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(okLongPressRunnable)
        LiveOrbState.removeListener(orbStateListener)
        StationAudioService.setLauncherForegroundState(false)
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(widgetEqTickRunnable)
        vinylAnimator?.pause()
        dialAnimator?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        handler.removeCallbacks(okLongPressRunnable)
        LiveOrbState.removeListener(orbStateListener)
        pageAnimator?.cancel()
        vinylAnimator?.cancel()
        dialAnimator?.cancel()
        handler.removeCallbacks(widgetEqTickRunnable)
        handler.removeCallbacks(otaDialogPollRunnable)
        try { otaDialog?.dismiss() } catch (_: Throwable) {}
        otaDialog = null
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(musicStateReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(clockReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (handleOverlayBackPressed()) return
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
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                handler.removeCallbacks(okLongPressRunnable)
                reorderingPkg = null
                closeAllOverlays(immediate = false)
                if (::weatherOverlay.isInitialized && weatherOverlay.visibility == View.VISIBLE) {
                    weatherOverlay.visibility = View.GONE
                }
                setPage(0, animate = true)
                btnHeroOrbClick.post {
                    if (currentPage == 0 && !appsDrawerOpen && !homeCustomOpen && !appContextOpen && !appInfoOpen) {
                        isProgrammaticPageFocus = true
                        try { btnHeroOrbClick.requestFocus() } finally { isProgrammaticPageFocus = false }
                    }
                }
            }
            return true
        }

        // Long-press OK handler (works identically on Station BLE remote, CEC remote & ADB keyevent)
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (reorderingPkg != null) {
                    finishReorderMode()
                    okLongPressFired = true
                    return true
                }
                if (event.repeatCount == 0) {
                    handler.removeCallbacks(okLongPressRunnable)
                    okLongPressFired = false
                    if (shouldArmOkLongPress(currentFocus)) {
                        okLongPressAnchor = currentFocus
                        handler.postDelayed(okLongPressRunnable, 460L)
                    } else {
                        okLongPressAnchor = null
                    }
                } else {
                    if (okLongPressFired) return true
                    if ((event.flags and KeyEvent.FLAG_LONG_PRESS) != 0 && shouldArmOkLongPress(currentFocus)) {
                        handler.removeCallbacks(okLongPressRunnable)
                        okLongPressFired = true
                        handleOkLongPress(currentFocus)
                        return true
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(okLongPressRunnable)
                if (okLongPressFired) {
                    okLongPressFired = false
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(okLongPressRunnable)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            // 1. Topmost modals & App Reordering interception
            if (appInfoOpen || (::appInfoOverlay.isInitialized && appInfoOverlay.visibility == View.VISIBLE)) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeAppInfoModal()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            if (appContextOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeAnchoredAppMenu()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            if (homeCustomOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeHomeCustomizationMenu()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            if (reorderingPkg != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveReorderingApp(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveReorderingApp(+1); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { moveReorderingApp(-5); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveReorderingApp(+5); return true }
                    KeyEvent.KEYCODE_BACK -> { finishReorderMode(); return true }
                }
            }
            if (appsDrawerOpen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        closeAppsDrawer()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val cur = currentFocus
                        val idx = activeAppEntries.indexOfFirst { drawerTileViews[it.pkg] === cur }
                        if (idx in 0 until 5 && (!::drawerScrollView.isInitialized || drawerScrollView.scrollY <= dp(8))) {
                            closeAppsDrawer()
                            return true
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            // 2. Main 3-screen ergonomic navigation
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (currentPage != 0) {
                        setPage(0, animate = true)
                        return true
                    }
                }
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
                else -> {
                    val cur = currentFocus
                    if (::tabViews.isInitialized) {
                        val tabIdx = tabViews.indexOfFirst { it === cur }
                        if (tabIdx >= 0) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (tabIdx < 2) switchToPageTabFocused(tabIdx + 1)
                                    else nudgeView(tabViews[tabIdx], dx = -dp(8).toFloat(), dy = 0f)
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (tabIdx > 0) switchToPageTabFocused(tabIdx - 1)
                                    else nudgeView(tabViews[tabIdx], dx = dp(8).toFloat(), dy = 0f)
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (tabIdx == 0) {
                                        btnHeroOrbClick.requestFocus()
                                    } else {
                                        focusPageControlDirect(currentPage)
                                    }
                                    return true
                                }
                            }
                        }
                    }

                    if (currentPage == 0 && ::eqWidgetController.isInitialized && eqWidgetController.handleKeyEvent(event, cur)) {
                        return true
                    }
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (currentPage == 0) {
                                if (cur != null && (
                                        (::eqWidgetController.isInitialized && eqWidgetController.isOwningFocus(cur)) ||
                                        (::wBtCard.isInitialized && cur === wBtCard) ||
                                        (::wWeatherCard.isInitialized && cur === wWeatherCard) ||
                                        (::wRemoteCard.isInitialized && cur === wRemoteCard)
                                    )
                                ) {
                                    btnHeroOrbClick.requestFocus()
                                    return true
                                }
                                openAppsDrawer()
                                return true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (currentPage == 0) {
                                if (cur === btnHeroOrbClick || cur == null) {
                                    if (!focusFirstVisibleHomeWidget()) {
                                        nudgeView(heroOrbView, dx = 0f, dy = -dp(12).toFloat())
                                    }
                                    return true
                                }
                            } else if (currentPage == 1 || currentPage == 2) {
                                val scrollRoot = if (currentPage == 1) page1Scroll else page2Scroll
                                val atTopHero = (currentPage == 1 && (
                                    cur === btnHomeOpen || cur === btnHomeWave ||
                                    (::btnHomePrev.isInitialized && cur === btnHomePrev.root) ||
                                    (::btnHomePlayPause.isInitialized && cur === btnHomePlayPause.root) ||
                                    (::btnHomeNext.isInitialized && cur === btnHomeNext.root) ||
                                    (::btnHomeLike.isInitialized && cur === btnHomeLike.root)
                                ))
                                val upNext = if (cur == null || atTopHero || cur === bottomDockBar || cur === tvStatusLine || cur === tvTopWeather) {
                                    null
                                } else {
                                    FocusFinder.getInstance().findNextFocus(scrollRoot, cur, View.FOCUS_UP)
                                }
                                if (upNext == null && ::tabViews.isInitialized) {
                                    isProgrammaticPageFocus = true
                                    try {
                                        tabViews[currentPage].requestFocus()
                                    } finally {
                                        isProgrammaticPageFocus = false
                                    }
                                    updateTabsAppearance(focusedIdx = currentPage)
                                    return true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (currentPage == 0) {
                                if (::wBtCard.isInitialized && cur === wBtCard) {
                                    btnHeroOrbClick.requestFocus()
                                    return true
                                }
                                switchToPageTabFocused(1)
                                return true
                            } else if (currentPage == 1) {
                                val next = if (cur == null || cur === bottomDockBar || cur === tvStatusLine || cur === tvTopWeather ||
                                    (::btnHomeOpen.isInitialized && cur === btnHomeOpen)
                                ) {
                                    null
                                } else {
                                    FocusFinder.getInstance().findNextFocus(page1Scroll, cur, View.FOCUS_RIGHT)
                                }
                                if (next == null) {
                                    switchToPageTabFocused(2)
                                    return true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (currentPage == 0) {
                                if ((::wWeatherCard.isInitialized && cur === wWeatherCard) ||
                                    (::wRemoteCard.isInitialized && cur === wRemoteCard)
                                ) {
                                    btnHeroOrbClick.requestFocus()
                                    return true
                                }
                                nudgeView(page0View, dx = dp(14).toFloat(), dy = 0f)
                                return true
                            } else if (currentPage == 2) {
                                val next = if (cur == null || cur === bottomDockBar || cur === tvStatusLine || cur === tvTopWeather) {
                                    null
                                } else {
                                    FocusFinder.getInstance().findNextFocus(page2Scroll, cur, View.FOCUS_LEFT)
                                }
                                if (next == null) {
                                    switchToPageTabFocused(1)
                                    return true
                                }
                            } else if (currentPage == 1) {
                                val next = if (cur == null || cur === bottomDockBar || cur === tvStatusLine || cur === tvTopWeather ||
                                    (::btnHomeWave.isInitialized && cur === btnHomeWave) ||
                                    (::btnHomePrev.isInitialized && cur === btnHomePrev.root)
                                ) {
                                    null
                                } else {
                                    FocusFinder.getInstance().findNextFocus(page1Scroll, cur, View.FOCUS_LEFT)
                                }
                                if (next == null) {
                                    switchToPageTabFocused(0)
                                    return true
                                }
                            }
                        }
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

        // 0. Atmospheric Dynamic Wallpaper Backdrop + Obsidian Gradient Scrim
        val bgLayerHost = FrameLayout(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ivBg = ImageView(this).apply {
            val wall = LiquidGlassKit.sharpWallpaper
            if (wall != null && !wall.isRecycled) {
                setImageBitmap(wall)
            } else {
                setImageResource(R.drawable.bg_tv_stage)
            }
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
                    Color.parseColor("#54050811"),
                    Color.parseColor("#18050811"),
                    Color.parseColor("#78050811")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        bgLayerHost.addView(ivBg)
        bgLayerHost.addView(scrimView)
        rootFrame.addView(bgLayerHost)

        // Main vertical container: Top Bar + 3-Screen Morphing Stage
        shellCol = LinearLayout(this).apply {
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
            maxLines = 1
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.03f
        }
        tvTopDate = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 11.5f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        }
        tvTopWeather = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            maxLines = 1
            isFocusable = true
            isClickable = true
            setPadding(dp(10), dp(3), dp(10), dp(3))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor("#2A0A101C"),
                focusTint = Color.parseColor("#66203048"),
                normalStroke = Color.parseColor("#14FFFFFF"),
                focusStroke = Color.parseColor("#B0FFFFFF"),
                refractStrength = 0.7f
            )
            visibility = View.GONE
            setOnClickListener {
                if (LiveOrbState.weatherValid) {
                    weatherOverlay.showWeather(
                        LiveOrbState.weatherTemp,
                        LiveOrbState.weatherCondition,
                        LiveOrbState.weatherConditionText,
                        LiveOrbState.weatherCity
                    )
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        }
        topLeft.addView(tvTopClock)
        topLeft.addView(tvTopDate)
        topLeft.addView(tvTopWeather)

        // Center: Spatial Glass Pill Switcher
        val tabsPillGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor("#38080D18"),
                focusTint = Color.parseColor("#54101B2E"),
                normalStroke = Color.parseColor("#18FFFFFF"),
                focusStroke = Color.parseColor("#88FFFFFF"),
                refractStrength = 0.9f
            )
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
                    if (hasFocus && !isProgrammaticPageFocus && currentPage != idx) {
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

        // Right: Compact Status Pill (Optical Spatial Glass)
        tvStatusLine = TextView(this).apply {
            text = "○ Подключение..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(5), dp(12), dp(5))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor("#2A080D18"),
                focusTint = Color.parseColor("#58172338"),
                normalStroke = Color.parseColor("#14FFFFFF"),
                focusStroke = Color.parseColor("#AAFFFFFF"),
                refractStrength = 0.8f
            )
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
        page1Scroll.setOnScrollChangeListener { _, _, _, _, _ -> LiquidGlassKit.refreshGlassTree(page1Scroll) }
        page2Scroll.setOnScrollChangeListener { _, _, _, _, _ -> LiquidGlassKit.refreshGlassTree(page2Scroll) }

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
            elevation = dp(18).toFloat()
            outlineProvider = null
            layoutParams = FrameLayout.LayoutParams(dp(320), dp(320))
        }
        rootFrame.addView(heroOrbView)

        rootFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPageVisuals(currentPagePos)
        }

        // ===== 5. BLURRED TRANSITION CURTAIN + FLAGSHIP OVERLAYS =====
        blurCurtainHost = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(24).toFloat()
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ivBlurCurtain = ImageView(this).apply {
            LiquidGlassKit.blurredWallpaper?.let { setImageBitmap(it) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val blurScrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#66040812"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        blurCurtainHost.addView(ivBlurCurtain)
        blurCurtainHost.addView(blurScrim)
        rootFrame.addView(blurCurtainHost)

        appsDrawerOverlay = buildAppsDrawerOverlay().apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(26).toFloat()
        }
        rootFrame.addView(appsDrawerOverlay)

        homeCustomOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(28).toFloat()
            isClickable = true
            isFocusable = false
            setOnClickListener { closeHomeCustomizationMenu() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(homeCustomOverlay)

        appContextOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(30).toFloat()
            isClickable = true
            isFocusable = false
            setOnClickListener { closeAnchoredAppMenu() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(appContextOverlay)

        appInfoOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(31).toFloat()
            isClickable = true
            isFocusable = false
            setOnClickListener { closeAppInfoModal() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(appInfoOverlay)

        weatherOverlay = WeatherOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = dp(34).toFloat()
        }
        rootFrame.addView(weatherOverlay)

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

        syncLayerFocusability()
        setPage(0, animate = false)
        btnHeroOrbClick.post {
            if (currentPage == 0 && !appsDrawerOpen && !homeCustomOpen && !appContextOpen && !appInfoOpen) {
                isProgrammaticPageFocus = true
                try { btnHeroOrbClick.requestFocus() } finally { isProgrammaticPageFocus = false }
            }
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

        // Borderless feathered radial focus glow behind AliceOrbView (zero circular edge line)
        val orbFocusSel = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(156).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#18FFFFFF"),
                    Color.parseColor("#06FFFFFF"),
                    Color.TRANSPARENT
                )
            })
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(156).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#24FFFFFF"),
                    Color.parseColor("#0AFFFFFF"),
                    Color.TRANSPARENT
                )
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
            setOnFocusChangeListener { _, hasFocus ->
                if (::heroOrbView.isInitialized && currentPage == 0) {
                    heroOrbView.animate()
                        .scaleX(if (hasFocus) 1.035f else 1f)
                        .scaleY(if (hasFocus) 1.035f else 1f)
                        .setDuration(140L)
                        .start()
                }
            }
            setOnClickListener { triggerAlice() }
            setOnLongClickListener {
                if (!okLongPressFired && !homeCustomOpen) showHomeCustomizationMenu()
                true
            }
            layoutParams = FrameLayout.LayoutParams(
                dp(320),
                dp(320),
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            )
        }
        page.addView(btnHeroOrbClick)

        // Spatial Glass Assistant Pill / Response Card cleanly below the 320dp Orb
        heroReplyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 20f,
                baseTint = Color.parseColor("#44080D18"),
                focusTint = Color.parseColor("#66111B2E"),
                normalStroke = Color.parseColor("#18FFFFFF"),
                focusStroke = Color.parseColor("#AAFFFFFF"),
                refractStrength = 0.95f
            )
            setPadding(dp(20), dp(9), dp(20), dp(10))
            isFocusable = false
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply {
                topMargin = dp(328)
            }
        }

        heroReplyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
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
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(8)
            }
        }
        tvHeroState = TextView(this).apply {
            text = "АЛИСА · ГОТОВА"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
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
        heroReplyHeader.addView(heroDot)
        heroReplyHeader.addView(tvHeroState)
        heroReplyHeader.addView(tvHeroUser)

        tvHeroReply = TextView(this).apply {
            text = "Скажите «Алиса» или нажмите OK"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            minLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1.08f)
        }
        heroReplyCard.addView(heroReplyHeader)
        heroReplyCard.addView(tvHeroReply)
        page.addView(heroReplyCard)

        // Modular Liquid Glass Home Widgets Flanking the Alice Sphere (ALL OFF by default)
        wLeftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                dp(284),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP
            ).apply {
                marginStart = dp(20)
                topMargin = dp(22)
            }
        }

        // Widget 1: Interactive Home Equalizer Console (Preset + Bass + 5 Frequency Bands)
        eqWidgetController = HomeEqWidgetController(
            context = this,
            onCyclePreset = { cycleEqPreset() },
            onBandsCommitted = { presetName, bands10, bass ->
                eqPreset = presetName
                eqBandsSynced = bands10.copyOf()
                bassBoostSynced = bass
                syncPills()
                val body = JSONObject()
                    .put("preset", presetName)
                    .put("bassBoost", bass)
                    .put("bands", JSONArray().apply { bands10.forEach { put(it.toDouble()) } })
                    .toString()
                postAsync("http://127.0.0.1:8080/api/audio/eq", body)
            },
            onNavigateOutUpOrDown = { dir ->
                if (dir > 0) {
                    if (::wBtCard.isInitialized && wBtCard.visibility == View.VISIBLE) {
                        wBtCard.requestFocus()
                    } else {
                        btnHeroOrbClick.requestFocus()
                    }
                    true
                } else {
                    btnHeroOrbClick.requestFocus()
                    true
                }
            }
        )
        val eqCard = eqWidgetController.buildView().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        wLeftCol.addView(eqCard)

        // Widget 2: Bluetooth Speaker Quick Card
        wBtCard = makeGlassWidgetCard("#A855F7") { toggleBtFromWidget() }
        val btHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        vWidgetBtDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#64748B"))
        }
        val btDot = View(this).apply {
            background = vWidgetBtDotBg
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
        }
        val tvBtHead = TextView(this).apply {
            text = "BLUETOOTH КОЛОНКА"
            setTextColor(Color.parseColor("#C084FC"))
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        btHeader.addView(btDot)
        btHeader.addView(tvBtHead)
        tvWidgetBtState = TextView(this).apply {
            text = "Выключен · Нажмите для пары"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        wBtCard.addView(btHeader)
        wBtCard.addView(tvWidgetBtState)
        wLeftCol.addView(wBtCard)
        page.addView(wLeftCol)

        wRightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                dp(248),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply {
                marginEnd = dp(20)
                topMargin = dp(22)
            }
        }

        // Widget 3: Weather Glass Card
        wWeatherCard = makeGlassWidgetCard("#94A3B8") {
            if (LiveOrbState.weatherValid) {
                weatherOverlay.showWeather(
                    LiveOrbState.weatherTemp,
                    LiveOrbState.weatherCondition,
                    LiveOrbState.weatherConditionText,
                    LiveOrbState.weatherCity
                )
            } else {
                Toast.makeText(this, "Спросите Алису: «Какая погода?»", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        tvWidgetWeatherCity = TextView(this).apply {
            text = "ПОГОДА СТАНЦИИ"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        tvWidgetWeatherTemp = TextView(this).apply {
            text = "--°"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        }
        tvWidgetWeatherDesc = TextView(this).apply {
            text = "Синхронизация..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        wWeatherCard.addView(tvWidgetWeatherCity)
        wWeatherCard.addView(tvWidgetWeatherTemp)
        wWeatherCard.addView(tvWidgetWeatherDesc)
        wRightCol.addView(wWeatherCard)

        // Widget 4: BLE Remote Finder Card
        wRemoteCard = makeGlassWidgetCard("#10D281") {
            postAsync("http://127.0.0.1:8080/api/remote/find", "{}")
            Toast.makeText(this, "Отправлен BLE-сигнал поиска пульта", Toast.LENGTH_SHORT).show()
        }
        val tvRemHead = TextView(this).apply {
            text = "ПУЛЬТ YNDX · ПОИСК"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        tvWidgetRemoteState = TextView(this).apply {
            text = "Нажмите — пульт запищит"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        wRemoteCard.addView(tvRemHead)
        wRemoteCard.addView(tvWidgetRemoteState)
        wRightCol.addView(wRemoteCard)
        page.addView(wRightCol)

        applyWidgetsVisibility()

        // Uncluttered Subtle Bottom Navigation Cue (non-focusable, quiet Spatial OLED aesthetic)
        tvHeroMicroHint = TextView(this).apply {
            text = "◄ ►  экраны    ·    ▼  приложения    ·    удерж. ОК  настройка"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
            letterSpacing = 0.04f
            alpha = 0.46f
            gravity = Gravity.CENTER
            isFocusable = false
            isClickable = true
            setOnClickListener { openAppsDrawer() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(14)
            }
        }
        page.addView(tvHeroMicroHint)

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
            setPadding(dp(26), dp(4), dp(26), dp(22))
        }

        // Spatial Glass Music Hero Card
        val musicHeroCard = FrameLayout(this).apply {
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 24f,
                baseTint = Color.parseColor("#54090E1A"),
                focusTint = Color.parseColor("#76131D30"),
                normalStroke = Color.parseColor("#18FFFFFF"),
                focusStroke = Color.parseColor("#A8FFFFFF"),
                refractStrength = 1.0f
            )
            clipToOutline = true
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(216)
            ).apply {
                bottomMargin = dp(16)
            }
        }

        val ivWaveBackdrop = ImageView(this).apply {
            setImageResource(R.drawable.hero_wave_texture)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.24f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val heroScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#C8080D18"),
                    Color.parseColor("#78080D18"),
                    Color.parseColor("#AA080D18")
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
            text = "ЯНДЕКС МУЗЫКА"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(1), Color.parseColor("#22FFFFFF"))
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
        heroBadgeRow.addView(tvHomeSourceBadge)
        heroBadgeRow.addView(tvHomeCodecBadge)
        heroBadgeRow.addView(tvHomeAuthBadge)
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
                setStroke(dp(1), Color.parseColor("#28FFFFFF"))
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
        btnHomePlayPause = makeIconBtn(R.drawable.ic_play, Color.WHITE) { sendMusicAction("PLAY_PAUSE") }
        btnHomeNext = makeIconBtn(R.drawable.ic_next, Color.WHITE) { sendMusicAction("NEXT") }
        btnHomeLike = makeIconBtn(R.drawable.ic_heart_outline, null) { sendMusicAction("LIKE") }

        ctrlRow.addView(btnHomeWave)
        listOf(btnHomePrev, btnHomePlayPause, btnHomeNext, btnHomeLike).forEach {
            it.root.layoutParams = LinearLayout.LayoutParams(dp(44), dp(36)).apply {
                marginEnd = dp(8)
            }
            ctrlRow.addView(it.root)
        }
        ctrlRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        btnHomeOpen = makeTextIconPill(R.drawable.ic_open_external, "Полный плеер", gold = false) {
            launchYandexMusic()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        }
        ctrlRow.addView(btnHomeOpen)
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
            setPadding(dp(26), dp(4), dp(26), dp(22))
        }

        val topSplit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1.0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(264)
            ).apply {
                bottomMargin = dp(16)
            }
        }

        // Left Card: Clock + QR + Clean Hardware Render
        val stationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 22f,
                baseTint = Color.parseColor("#54090E1A"),
                focusTint = Color.parseColor("#76131D30"),
                normalStroke = Color.parseColor("#18FFFFFF"),
                focusStroke = Color.parseColor("#A8FFFFFF"),
                refractStrength = 1.0f
            )
            elevation = dp(6).toFloat()
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
            maxLines = 1
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.02f
        }
        tvDate = TextView(this).apply {
            text = "..."
            setTextColor(Color.parseColor("#94A3B8"))
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
            setPadding(dp(4), dp(4), dp(4), dp(4))
            alpha = 0.94f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(10).toFloat()
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvQrLbl = TextView(this).apply {
            text = "Веб-пульт"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvIpLine = TextView(this).apply {
            text = "127.0.0.1:8080"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvQrSub = TextView(this).apply {
            text = "Откройте на телефоне"
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
        stationRingGlowBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#24FFFFFF"), Color.parseColor("#1838BDF8"))
        ).apply {
            shape = GradientDrawable.OVAL
        }
        ivStationRingGlow = View(this).apply {
            background = stationRingGlowBg
            layoutParams = FrameLayout.LayoutParams(dp(102), dp(102), Gravity.CENTER)
        }
        ivDialRing = ImageView(this).apply {
            setImageResource(R.drawable.sprite_bork_dial_ring)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER)
        }
        val ivStationHero = ImageView(this).apply {
            setImageResource(R.drawable.sprite_station_hero)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(116), dp(116), Gravity.CENTER)
        }
        stationVisualBox.addView(ivStationRingGlow)
        stationVisualBox.addView(ivDialRing)
        stationVisualBox.addView(ivStationHero)

        clockStationRow.addView(clockQrCol)
        clockStationRow.addView(stationVisualBox)
        stationCard.addView(clockStationRow)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        tvStationTelemetry = TextView(this).apply {
            text = "Ожидание системы..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnWizard = TextView(this).apply {
            text = "Мастер"
            setTextColor(Color.parseColor("#090D16"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(11), dp(4), dp(11), dp(4))
            setBackgroundResource(R.drawable.bg_pill_gold)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(90).start()
            }
            setOnClickListener { openSetupWizard(manual = true) }
        }
        val telemetryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnOtaUpdate = TextView(this).apply {
            text = "OTA"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundResource(R.drawable.bg_pill)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f).setDuration(90).start()
            }
            setOnClickListener { openOtaDialog() }
        }
        telemetryRow.addView(tvStationTelemetry)
        telemetryRow.addView(btnOtaUpdate)
        telemetryRow.addView(btnWizard)
        stationCard.addView(divider)
        stationCard.addView(telemetryRow)
        topSplit.addView(stationCard)

        // Right Card: 6 Consumer Quick Controls (3 rows x 2 columns)
        val hwPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 22f,
                baseTint = Color.parseColor("#54090E1A"),
                focusTint = Color.parseColor("#76131D30"),
                normalStroke = Color.parseColor("#18FFFFFF"),
                focusStroke = Color.parseColor("#A8FFFFFF"),
                refractStrength = 1.0f
            )
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f)
        }
        hwPanel.addView(makeSectionTitle("Быстрое управление", R.drawable.ic_hw_chip))

        tileRing = makeHwTile(R.drawable.ic_hw_ring, "Кольцо: Спектр") { cycleRingMode() }
        tileMute = makeHwTile(R.drawable.ic_hw_mic_on, "Мик: Вкл") { toggleMute() }
        tileRoute = makeHwTile(R.drawable.ic_hw_speaker, "Выход: Авто") { cycleOutputRoute() }
        tileEq = makeHwTile(R.drawable.ic_hw_eq, "EQ: Баланс") { cycleEqPreset() }
        val curVisName = when (LiveOrbState.visualizerStyle) {
            "dots", "particles" -> "3D Точки"
            "nexus", "anomaly" -> "Nexus 3D"
            "specterr" -> "Корона"
            "aurora" -> "Аврора"
            "bars" -> "Спектр"
            "wave" -> "Волна"
            "matrix" -> "Матрица"
            "fireflies" -> "Частицы"
            else -> "3D Точки"
        }
        tileVis = makeHwTile(R.drawable.ic_wave, "Визуал: $curVisName") { cycleVisualizerStyle() }
        tileCec = makeHwTile(R.drawable.ic_hw_tv, "ТВ: Вкл/Выкл") { toggleCec() }

        val hwGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val hwTiles = listOf(tileRing, tileMute, tileRoute, tileEq, tileVis, tileCec)
        for (r in 0 until 3) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    if (r < 2) bottomMargin = dp(8)
                }
            }
            for (c in 0 until 2) {
                val tile = hwTiles[r * 2 + c]
                tile.root.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    if (c == 0) marginEnd = dp(8)
                }
                rowLayout.addView(tile.root)
            }
            hwGrid.addView(rowLayout)
        }
        hwPanel.addView(hwGrid)
        topSplit.addView(hwPanel)
        col.addView(topSplit)

        // System Settings Dock (5 unified monochrome spatial categories)
        col.addView(makeSectionTitle("Настройки Android TV", R.drawable.ic_settings))
        val sysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(74)
            )
        }
        val sysItems = listOf(
            SysItem(R.drawable.ic_hw_wifi, false, "Wi-Fi и сеть") {
                openSettingsIntent(Settings.ACTION_WIFI_SETTINGS, "com.android.tv.settings")
            },
            SysItem(R.drawable.ic_hw_bluetooth, false, "Bluetooth") {
                openTvAccessories()
            },
            SysItem(R.drawable.ic_hw_eq, false, "Звук и аудио") {
                openSettingsActions("com.yandex.tv.settings.ACTION_SOUND", Settings.ACTION_SOUND_SETTINGS)
            },
            SysItem(R.drawable.ic_hw_apps, false, "Приложения") {
                openSettingsIntent(Settings.ACTION_APPLICATION_SETTINGS, "com.android.tv.settings")
            },
            SysItem(R.drawable.ic_settings, false, "Настройки TV") {
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
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            bottomDockBg = LiquidGlassKit.applyGlass(
                this,
                radiusDp = 22f,
                baseTint = Color.parseColor("#78080D18"),
                focusTint = Color.parseColor("#92121D32"),
                normalStroke = Color.parseColor("#24FFFFFF"),
                focusStroke = Color.parseColor("#B8FFFFFF"),
                refractStrength = 1.0f
            )
            elevation = dp(10).toFloat()
            setPadding(dp(12), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
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
    private fun switchToPageTabFocused(targetPage: Int) {
        val clamped = targetPage.coerceIn(0, 2)
        isProgrammaticPageFocus = true
        try {
            if (clamped == 0 && ::btnHeroOrbClick.isInitialized) {
                btnHeroOrbClick.requestFocus()
            } else if (::tabViews.isInitialized) {
                tabViews[clamped].requestFocus()
            }
            setPage(clamped, animate = true)
        } finally {
            isProgrammaticPageFocus = false
        }
        updateTabsAppearance(focusedIdx = if (clamped == 0) -1 else clamped)
    }

    private fun setPage(targetIdx: Int, animate: Boolean) {
        val clamped = targetIdx.coerceIn(0, 2)
        val prevPage = currentPage
        currentPage = clamped

        pageAnimator?.cancel()
        updateTabsAppearance()

        if (clamped == 1) {
            renderMusicSnapshot(latestMusicSnap, forceViews = true)
        } else if (clamped == 2) {
            updateClock(force = true)
            updateNetworkHeader(cachedLocalIp, force = true)
            syncPills(forceViews = true)
        }

        // Make involved pages visible during slide transition
        page0View.visibility = View.VISIBLE
        page1Scroll.visibility = View.VISIBLE
        page2Scroll.visibility = View.VISIBLE
        page0View.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        page1Scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        page2Scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // Move focus off outgoing page synchronously BEFORE finalizing visibility,
        // otherwise hiding the previously focused view triggers Android fallback focus to tabViews[0]!
        if (clamped != prevPage) {
            focusInitialControlOnPage(clamped, immediate = true)
        }

        if (!animate || abs(clamped - currentPagePos) < 0.005f) {
            currentPagePos = clamped.toFloat()
            applyPageVisuals(currentPagePos)
            focusInitialControlOnPage(clamped, immediate = true)
            finalizePageVisibility()
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
                    focusInitialControlOnPage(currentPage, immediate = true)
                    finalizePageVisibility()
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
        val showDock = (currentPage != 0) && (LiveOrbState.assistantState != "idle")
        bottomDockBar.visibility = if (showDock) View.VISIBLE else View.GONE
        syncLayerFocusability()
        updateAnimationsState()
    }

    private fun focusPageControlDirect(idx: Int) {
        if (appsDrawerOpen || homeCustomOpen || appContextOpen || appInfoOpen) return
        isProgrammaticPageFocus = true
        try {
            when (idx) {
                0 -> if (::btnHeroOrbClick.isInitialized) btnHeroOrbClick.requestFocus()
                1 -> if (::btnHomeWave.isInitialized) btnHomeWave.requestFocus()
                2 -> if (::tileRing.isInitialized) tileRing.root.requestFocus()
            }
        } finally {
            isProgrammaticPageFocus = false
        }
    }

    private fun focusInitialControlOnPage(idx: Int, immediate: Boolean = false) {
        val cur = currentFocus
        // Keep focus on tab switcher only if user switched screens via top tabs
        if (cur != null && ::tabViews.isInitialized && tabViews.any { it === cur }) return
        if (immediate) {
            focusPageControlDirect(idx)
        } else {
            handler.post {
                val nowCur = currentFocus
                if (nowCur != null && ::tabViews.isInitialized && tabViews.any { it === nowCur }) return@post
                focusPageControlDirect(idx)
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

        // 2. Bottom Dock Bar fade + rise into view ONLY when Alice is actively engaged on Screens 1 & 2
        val dockActive = (LiveOrbState.assistantState != "idle")
        val dockAlpha = if (dockActive) ((pagePos - 0.14f) / 0.86f).coerceIn(0f, 1f) else 0f
        bottomDockBar.alpha = dockAlpha
        bottomDockBar.translationY = (1f - dockAlpha) * dp(44).toFloat()
        if (dockAlpha > 0.01f) {
            if (bottomDockBar.visibility != View.VISIBLE) bottomDockBar.visibility = View.VISIBLE
        } else if (bottomDockBar.visibility != View.GONE && currentPage != 0) {
            bottomDockBar.visibility = View.GONE
        }

        // 3. Persistent Alice Sphere Morph: shrinks into dock when busy, smoothly dissolves on idle page slide
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
        heroOrbView.alpha = if (dockActive) 1f else (1f - dockT * 1.65f).coerceIn(0f, 1f)
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
                    shape.setColor(Color.parseColor(if (focused) "#48FFFFFF" else "#28FFFFFF"))
                    shape.setStroke(dp(1), Color.parseColor(if (focused) "#CCFFFFFF" else "#48FFFFFF"))
                    tv.setTextColor(Color.WHITE)
                }
                focused -> {
                    shape.setColor(Color.parseColor("#38FFFFFF"))
                    shape.setStroke(dp(1), Color.parseColor("#B8FFFFFF"))
                    tv.setTextColor(Color.WHITE)
                }
                else -> {
                    shape.setColor(Color.TRANSPARENT)
                    shape.setStroke(0, Color.TRANSPARENT)
                    tv.setTextColor(Color.parseColor("#8A99AD"))
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
        val uiKey = "$st|$assistantEnabled|$userTxt|$display"
        if (uiKey == lastOrbUiKey) return
        lastOrbUiKey = uiKey

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
                accent = if (assistantEnabled) Color.parseColor("#E2E8F0") else Color.parseColor("#64748B")
                borderColor = Color.parseColor("#18FFFFFF")
                stateLabel = if (assistantEnabled) "АЛИСА · ГОТОВА" else "АЛИСА · ВЫКЛЮЧЕНА"
                heroFallback = if (assistantEnabled) "Алиса готова · Нажмите OK" else "Микрофон выключен · Нажмите OK"
                dockFallback = "Скажите «Алиса» или нажмите, чтобы задать вопрос"
            }
        }

        val compactIdle = (st == "idle" && display.isEmpty() && userTxt.isEmpty())
        heroStateDotBg.setColor(accent)
        dockStateDotBg.setColor(accent)
        (heroReplyCard.background as? LiquidGlassKit.RefractiveGlassDrawable)?.setStrokeColors(
            borderColor,
            Color.parseColor("#B0FFFFFF")
        )
        bottomDockBg.setStrokeColors(borderColor, Color.parseColor("#B0FFFFFF"))

        tvHeroState.text = stateLabel
        tvHeroState.setTextColor(accent)
        tvDockState.text = stateLabel
        tvDockState.setTextColor(accent)

        if (compactIdle) {
            heroReplyHeader.visibility = View.GONE
            (heroReplyCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    heroReplyCard.layoutParams = lp
                }
            }
            heroReplyCard.setPadding(dp(20), dp(8), dp(20), dp(9))
            tvHeroReply.gravity = Gravity.CENTER_HORIZONTAL
            tvHeroReply.textSize = 13.5f
            tvHeroReply.text = heroFallback
            tvHeroReply.setTextColor(Color.parseColor("#CBD5E1"))
        } else {
            heroReplyHeader.visibility = View.VISIBLE
            (heroReplyCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val targetW = dp(620)
                if (lp.width != targetW) {
                    lp.width = targetW
                    heroReplyCard.layoutParams = lp
                }
            }
            heroReplyCard.setPadding(dp(22), dp(10), dp(22), dp(12))
            tvHeroReply.gravity = Gravity.START
            tvHeroReply.textSize = 15f

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
        }

        val dockBody = display.ifEmpty { dockFallback }
        tvDockReply.text = dockBody
        tvDockReply.setTextColor(
            if (display.isNotEmpty()) Color.parseColor("#F8FAFC") else Color.parseColor("#CBD5E1")
        )

        if (currentPage != 0) {
            val dockActive = (st != "idle")
            if (!dockActive && bottomDockBar.visibility == View.VISIBLE) {
                bottomDockBar.visibility = View.GONE
            } else if (dockActive && bottomDockBar.visibility != View.VISIBLE) {
                bottomDockBar.visibility = View.VISIBLE
            }
            applyPageVisuals(currentPagePos)
        }
    }

    // -----------------------------------------------------------------------------------------
    // REUSABLE UI FACTORIES
    // -----------------------------------------------------------------------------------------
    private fun makeSectionTitle(label: String, iconRes: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, 0, dp(8))
        val iv = ImageView(this@MainActivity).apply {
            val d = getDrawable(iconRes)?.mutate()
            d?.setTint(Color.parseColor("#94A3B8"))
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginEnd = dp(7)
            }
        }
        val tv = TextView(this@MainActivity).apply {
            text = label.uppercase(Locale.getDefault())
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
            letterSpacing = 0.09f
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(iv)
        addView(tv)
    }

    private fun makeHwTile(iconRes: Int, initialText: String, onClick: () -> Unit): HwTileViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(11), 0)
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 14f,
                baseTint = Color.parseColor("#3A0A1120"),
                focusTint = Color.parseColor("#7A1A2B48"),
                normalStroke = Color.parseColor("#16FFFFFF"),
                focusStroke = Color.parseColor("#B8FFFFFF"),
                refractStrength = 0.85f
            )
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(90).start()
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#E2E8F0"), PorterDuff.Mode.SRC_IN)
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
            setPadding(dp(6), dp(8), dp(6), dp(8))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 16f,
                baseTint = Color.parseColor("#3A0A1120"),
                focusTint = Color.parseColor("#7A1A2B48"),
                normalStroke = Color.parseColor("#16FFFFFF"),
                focusStroke = Color.parseColor("#B8FFFFFF"),
                refractStrength = 0.85f
            )
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(95).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                if (!isSprite) {
                    setColorFilter(Color.parseColor("#E2E8F0"), PorterDuff.Mode.SRC_IN)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                val iconSz = if (isSprite) dp(24) else dp(22)
                layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                    bottomMargin = dp(6)
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
    // APPS ORDER / HIDING PREFERENCES & SHARED GRID RENDERER
    // -----------------------------------------------------------------------------------------
    private fun getHiddenAppPackages(): MutableSet<String> {
        val prefs = getSharedPreferences("openstation_apps_prefs", MODE_PRIVATE)
        val raw = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        return HashSet(raw).apply { remove(PKG_OPENSTATION_APPS) }
    }

    private fun saveHiddenAppPackages(set: Set<String>) {
        val clean = HashSet(set).apply { remove(PKG_OPENSTATION_APPS) }
        getSharedPreferences("openstation_apps_prefs", MODE_PRIVATE)
            .edit()
            .putStringSet("hidden_apps", clean)
            .apply()
    }

    private fun getSavedAppOrder(): MutableList<String> {
        val prefs = getSharedPreferences("openstation_apps_prefs", MODE_PRIVATE)
        val raw = prefs.getString("app_order", null) ?: return ArrayList()
        return try {
            val arr = JSONArray(raw)
            ArrayList<String>(arr.length()).apply {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotBlank() && p != PKG_OPENSTATION_APPS) add(p)
                }
            }
        } catch (_: Throwable) {
            ArrayList()
        }
    }

    private fun saveAppOrder(order: List<String>) {
        val arr = JSONArray()
        order.filter { it.isNotBlank() && it != PKG_OPENSTATION_APPS }.distinct().forEach { arr.put(it) }
        getSharedPreferences("openstation_apps_prefs", MODE_PRIVATE)
            .edit()
            .putString("app_order", arr.toString())
            .apply()
    }

    private fun loadAndRenderApps() {
        val pm = packageManager
        val found = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, isLeanback: Boolean) {
            val qi = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list = pm.queryIntentActivities(qi, 0)
            for (ri in list) {
                val pkg = ri.activityInfo.packageName ?: continue
                if (pkg == packageName || pkg == "com.openstation.ime" || pkg == "com.openstation.market") continue
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

        val baseEntries = found.values.sortedWith(
            compareByDescending<AppEntry> { it.leanback }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )

        val appsEntry = AppEntry(
            pkg = PKG_OPENSTATION_APPS,
            label = "OPENSTATION APPS",
            icon = getDrawable(R.drawable.ic_launcher_open) ?: applicationInfo.loadIcon(pm),
            intent = Intent(this, MarketActivity::class.java),
            leanback = true,
            isVirtual = true,
            subtitle = "Магазин ТВ · APK"
        )
        val ymEntry = AppEntry(
            pkg = PKG_YANDEX_MUSIC,
            label = "Яндекс Музыка",
            icon = getDrawable(R.drawable.sprite_ym_emblem) ?: applicationInfo.loadIcon(pm),
            intent = Intent(this, YandexMusicActivity::class.java),
            leanback = true,
            isVirtual = true,
            subtitle = "Моя волна · Hi-Fi"
        )

        val savedOrder = getSavedAppOrder()
        val orderIdx = savedOrder.withIndex().associate { it.value to it.index }
        val combinedReorderable = listOf(ymEntry) + baseEntries
        val sortedReorderable = combinedReorderable.sortedWith(
            compareBy<AppEntry> { orderIdx[it.pkg] ?: (10000 + combinedReorderable.indexOf(it)) }
        )

        val hiddenSet = getHiddenAppPackages()
        val visible = listOf(appsEntry) + sortedReorderable.filterNot { hiddenSet.contains(it.pkg) }
        activeAppEntries = visible

        drawerTileViews.clear()
        screen1TileViews.clear()
        appsGrid.removeAllViews()
        if (::drawerAppsGrid.isInitialized) {
            drawerAppsGrid.removeAllViews()
        }

        val tileMargin = dp(5)
        val screenW = resources.displayMetrics.widthPixels - dp(52)
        val colW = maxOf(dp(150), (screenW / 5) - tileMargin * 2)

        for (app in visible) {
            addAppTileToGrid(appsGrid, app, colW, tileMargin, screen1TileViews)
            if (::drawerAppsGrid.isInitialized) {
                addAppTileToGrid(drawerAppsGrid, app, colW, tileMargin, drawerTileViews)
            }
        }

        if (::tvDrawerStatusPill.isInitialized) {
            tvDrawerStatusPill.text = when {
                reorderingPkg != null -> "ПЕРЕМЕЩЕНИЕ · ◄►▲▼ сдвиг · OK готово"
                hiddenSet.isNotEmpty() -> "${visible.size} прилож. · ${hiddenSet.size} скрыто"
                else -> "${visible.size} прилож. · удерж. OK меню"
            }
        }

        val arr = JSONArray()
        for (a in baseEntries) {
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

    private fun extractAppAccentColor(pkg: String, icon: Drawable): Int {
        appAccentCache[pkg]?.let { return it }
        var res = Color.parseColor("#334155")
        try {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            icon.setBounds(0, 0, 16, 16)
            icon.draw(c)
            var bestScore = -1f
            var bestColor = res
            val hsv = FloatArray(3)
            for (y in 2 until 14 step 2) {
                for (x in 2 until 14 step 2) {
                    val px = bmp.getPixel(x, y)
                    if (Color.alpha(px) < 180) continue
                    Color.colorToHSV(px, hsv)
                    if (hsv[2] > 0.22f && hsv[2] < 0.96f && hsv[1] > 0.18f) {
                        val score = hsv[1] * hsv[2]
                        if (score > bestScore) {
                            bestScore = score
                            bestColor = px
                        }
                    }
                }
            }
            bmp.recycle()
            if (bestScore > 0f) res = bestColor
        } catch (_: Throwable) {
        }
        appAccentCache[pkg] = res
        return res
    }

    private fun addAppTileToGrid(
        grid: GridLayout,
        app: AppEntry,
        colW: Int,
        tileMargin: Int,
        viewMap: MutableMap<String, View>
    ) {
        val isPinnedApps = (app.pkg == PKG_OPENSTATION_APPS)
        val isMoving = (reorderingPkg == app.pkg)
        val accent = extractAppAccentColor(app.pkg, app.icon)
        val tintR = (Color.red(accent) * 0.22f + 9f).toInt().coerceIn(0, 255)
        val tintG = (Color.green(accent) * 0.22f + 14f).toInt().coerceIn(0, 255)
        val tintB = (Color.blue(accent) * 0.22f + 24f).toInt().coerceIn(0, 255)
        val baseCardTint = when {
            isMoving -> Color.argb(148, 30, 58, 96)
            isPinnedApps -> Color.argb(136, 16, 36, 66)
            else -> Color.argb(115, tintR, tintG, tintB)
        }
        val focusCardTint = when {
            isPinnedApps -> Color.argb(190, 28, 62, 108)
            else -> Color.argb(176, minOf(255, tintR + 24), minOf(255, tintG + 28), minOf(255, tintB + 36))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(9))
            tag = app.pkg
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 18f,
                baseTint = baseCardTint,
                focusTint = focusCardTint,
                normalStroke = when {
                    isMoving -> Color.parseColor("#93C5FD")
                    isPinnedApps -> Color.parseColor("#447DD3FC")
                    else -> Color.parseColor("#18FFFFFF")
                },
                focusStroke = Color.parseColor("#CCFFFFFF"),
                refractStrength = if (isPinnedApps) 1.05f else 0.9f
            )
            elevation = dp(if (isMoving || isPinnedApps) 8 else 4).toFloat()
            isFocusable = true
            isClickable = true
            layoutParams = GridLayout.LayoutParams().apply {
                width = colW
                height = dp(96)
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
        }
        val iv = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(11).toFloat())
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val tvName = TextView(this).apply {
            text = app.label
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", if (isPinnedApps || app.isVirtual) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(7)
            }
        }
        card.addView(iv)
        card.addView(tvName)

        val showSub = isMoving || isPinnedApps || (app.subtitle != null && app.pkg != PKG_YANDEX_MUSIC)
        if (showSub) {
            val tvSub = TextView(this).apply {
                text = when {
                    isMoving -> "◄►▲▼ сдвиг"
                    else -> app.subtitle ?: ""
                }
                setTextColor(Color.parseColor(if (isMoving) "#93C5FD" else "#7DD3FC"))
                textSize = 9.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            card.addView(tvSub)
        }

        card.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(110)
                .start()
        }
        card.setOnClickListener { launchApp(app, card) }
        card.setOnLongClickListener {
            if (!okLongPressFired && !appContextOpen && reorderingPkg == null) {
                showAnchoredAppMenu(app, card)
            }
            true
        }
        viewMap[app.pkg] = card
        grid.addView(card)
    }

    private fun launchApp(app: AppEntry, sourceView: View? = null) {
        if (app.pkg == PKG_OPENSTATION_APPS) {
            launchMarket(sourceView)
            return
        }
        if (app.pkg == PKG_YANDEX_MUSIC) {
            launchYandexMusic(sourceView)
            return
        }
        launchWithGlassTransition(sourceView) {
            try {
                app.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(app.intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (t: Throwable) {
                Toast.makeText(this, "Не удалось запустить ${app.label}: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPackageName(pkg: String) {
        if (pkg == PKG_OPENSTATION_APPS) {
            launchMarket()
            return
        }
        if (pkg == PKG_YANDEX_MUSIC) {
            launchYandexMusic()
            return
        }
        launchWithGlassTransition(null) {
            try {
                val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    return@launchWithGlassTransition
                }
            } catch (_: Throwable) {}
            Toast.makeText(this, "Пакет $pkg недоступен", Toast.LENGTH_SHORT).show()
        }
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
    private fun updateClock(force: Boolean = false) {
        val tz = TimeZone.getDefault()
        timeFmt.timeZone = tz
        shortDateFmt.timeZone = tz
        fullDateFmt.timeZone = tz
        clockDate.time = System.currentTimeMillis()

        val style = LiveOrbState.clockStyle
        val timeStr = when (style) {
            "seconds" -> {
                val secFmt = SimpleDateFormat("HH:mm:ss", ruLocale).apply { timeZone = tz }
                secFmt.format(clockDate)
            }
            "binary" -> {
                val cal = java.util.Calendar.getInstance(tz)
                val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val m = cal.get(java.util.Calendar.MINUTE)
                Integer.toBinaryString(h).padStart(5, '0') + " : " + Integer.toBinaryString(m).padStart(6, '0')
            }
            else -> timeFmt.format(clockDate)
        }

        val shortDate = shortDateFmt.format(clockDate).replaceFirstChar { it.uppercase() }
        val fullDate = fullDateFmt.format(clockDate).replaceFirstChar { it.uppercase() }

        if (force || timeStr != lastClockStr) {
            lastClockStr = timeStr
            tvTopClock.text = timeStr
            tvClock.text = timeStr

            when (style) {
                "minimal" -> {
                    tvClock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                    tvClock.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    tvClock.setTextColor(Color.parseColor("#E2E8F0"))
                }
                "bold" -> {
                    tvClock.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    tvClock.setShadowLayer(8f, 0f, 3f, Color.parseColor("#40000000"))
                    tvClock.setTextColor(Color.WHITE)
                }
                "neon" -> {
                    tvClock.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    tvClock.setTextColor(Color.parseColor("#E0F2FE"))
                    tvClock.setShadowLayer(18f, 0f, 0f, Color.parseColor("#00D4FF"))
                }
                "retro" -> {
                    tvClock.typeface = Typeface.MONOSPACE
                    tvClock.setTextColor(Color.parseColor("#E2E8F0"))
                    tvClock.setShadowLayer(8f, 0f, 0f, Color.parseColor("#28FFFFFF"))
                }
                else -> {
                    tvClock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    tvClock.setTextColor(Color.WHITE)
                    tvClock.setShadowLayer(6f, 0f, 2f, Color.parseColor("#33000000"))
                }
            }
        }
        if (force || shortDate != lastShortDateStr) {
            lastShortDateStr = shortDate
            tvTopDate.text = shortDate
        }
        if (force || fullDate != lastFullDateStr) {
            lastFullDateStr = fullDate
            tvDate.text = fullDate
        }

        if (LiveOrbState.weatherValid) {
            val sign = if (LiveOrbState.weatherTemp > 0) "+" else ""
            tvTopWeather.text = "☀️ $sign${LiveOrbState.weatherTemp}°"
            tvTopWeather.visibility = View.VISIBLE
        } else {
            tvTopWeather.visibility = View.GONE
        }
    }

    private fun detectLocalIp(force: Boolean = false): String {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && cachedLocalIp != "127.0.0.1" && (nowMs - lastIpDetectMs) < 20_000L) {
            return cachedLocalIp
        }
        var result = "127.0.0.1"
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
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                            result = ip
                            cachedLocalIp = result
                            lastIpDetectMs = nowMs
                            return result
                        }
                        candidate = ip
                    }
                }
            }
            if (candidate.isNotEmpty()) result = candidate
        } catch (_: Throwable) {}
        cachedLocalIp = result
        lastIpDetectMs = nowMs
        return result
    }

    private fun updateNetworkHeader(ipOverride: String? = null, force: Boolean = false) {
        val ip = ipOverride ?: detectLocalIp(force)
        val url = "http://$ip:8080"
        val onlineNet = if (ip != "127.0.0.1") {
            true
        } else {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm.activeNetwork != null
            } catch (_: Throwable) { false }
        }
        val ipLine = if (onlineNet || ip != "127.0.0.1") {
            "$ip:8080"
        } else {
            "Локально: 127.0.0.1:8080"
        }
        if (force || ipLine != lastIpLineStr) {
            lastIpLineStr = ipLine
            tvIpLine.text = ipLine
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
                    val cfg = root.optJSONObject("config")
                    val audio = root.optJSONObject("audio")
                    val ringCfg = cfg?.optJSONObject("ledRing")
                    val audioCfg = cfg?.optJSONObject("audio")
                    val ast = root.optJSONObject("assistant")
                    val astCfg = cfg?.optJSONObject("assistant")

                    fwVer = root.optString("firmwareVersion", fwVer).ifBlank { fwVer }
                    ringMode = ringCfg?.optString("mode", ringMode) ?: ringMode
                    micMuted = audioCfg?.optBoolean("micMuted", micMuted) ?: micMuted
                    outputRoute = audioCfg?.optString("outputRoute", outputRoute) ?: outputRoute
                    eqPreset = audioCfg?.optString("eqPreset", eqPreset) ?: eqPreset
                    val eqBandsJson = audioCfg?.optJSONArray("eqBands") ?: audio?.optJSONArray("eqBands")
                    val eqBassBoost: Int? = when {
                        audioCfg?.has("bassBoost") == true -> audioCfg.optInt("bassBoost")
                        audio?.has("bassBoost") == true -> audio.optInt("bassBoost")
                        else -> null
                    }
                    volumePct = audio?.optInt("volumePercent", volumePct) ?: volumePct
                    assistantState = ast?.optString("state", "idle") ?: "idle"
                    assistantEnabled = astCfg?.optBoolean("enabled", true) ?: true
                    val setupDone = cfg?.optBoolean("setupCompleted", false) ?: false
                    val wasOffline = !daemonOnline
                    daemonOnline = true
                    if (wasOffline && cachedAppsJson.isNotEmpty()) {
                        httpPostJson("http://127.0.0.1:8080/api/apps/cache", cachedAppsJson)
                    }
                    val daemonMusic = root.optJSONObject("music")
                    val daemonOta = root.optJSONObject("ota")
                    val btObj = root.optJSONObject("bluetooth")
                    if (btObj != null) {
                        btEnabledState = btObj.optBoolean("enabled", false)
                        btDiscoverableState = btObj.optBoolean("discoverable", false)
                    }
                    val lw = root.optJSONObject("lastWeather")
                    if (lw != null && !LiveOrbState.weatherValid) {
                        val lwTemp = lw.optInt("tempC", Int.MIN_VALUE)
                        if (lwTemp != Int.MIN_VALUE) {
                            LiveOrbState.updateWeather(
                                true,
                                lwTemp,
                                lw.optString("condition", "clear"),
                                lw.optString("conditionText", ""),
                                lw.optString("city", "")
                            )
                        }
                    }

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
                    val statusStr = if (micMuted) {
                        "● Громкость $volumePct% · Мик выкл"
                    } else {
                        "● Громкость $volumePct%"
                    }
                    val astTxt = if (assistantState != "idle") "Алиса активна" else "Алиса готова"
                    val telStr = "● $astTxt · v$fwVer"

                    handler.post {
                        updateNetworkHeader(ip)
                        if (statusStr != lastStatusLineText) {
                            lastStatusLineText = statusStr
                            tvStatusLine.text = statusStr
                        }
                        if (telStr != lastTelemetryText) {
                            lastTelemetryText = telStr
                            tvStationTelemetry.text = telStr
                        }
                        val svcSnap = StationAudioService.instance?.musicPlayer?.getSnapshot()
                        if (svcSnap != null) {
                            if (svcSnap.seq != latestMusicSnap.seq || svcSnap.playing != latestMusicSnap.playing) {
                                renderMusicSnapshot(svcSnap)
                            }
                        } else if (daemonMusic != null && daemonMusic.optLong("seq", 0L) > 0L) {
                            renderMusicSnapshot(YmPlayerSnapshot.fromJson(daemonMusic))
                        }
                        applyOtaUi(daemonOta)
                        syncPills()
                        if (::eqWidgetController.isInitialized) {
                            eqWidgetController.syncFromDaemon(eqPreset, eqBandsJson, eqBassBoost)
                        }
                        syncHomeWidgetsUi()

                        val prevSetupDone = setupCompletedState
                        if (prevSetupDone == true && !setupDone) {
                            getSharedPreferences(SetupWizardActivity.PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .remove(SetupWizardActivity.KEY_WIZARD_DISMISSED_VER)
                                .apply()
                            wizardAutoOpenedThisRun = false
                        }
                        setupCompletedState = setupDone
                        if (!setupDone && !wizardAutoOpenedThisRun) {
                            val prefs = getSharedPreferences(SetupWizardActivity.PREFS_NAME, MODE_PRIVATE)
                            val dismissedVer = prefs.getInt(SetupWizardActivity.KEY_WIZARD_DISMISSED_VER, 0)
                            if (dismissedVer < SetupWizardActivity.CURRENT_WIZARD_VER) {
                                wizardAutoOpenedThisRun = true
                                openSetupWizard(manual = false)
                            }
                        }
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
            val s = "○ Подключение..."
            if (s != lastStatusLineText) {
                lastStatusLineText = s
                tvStatusLine.text = s
            }
            val t = "○ Ожидание системы..."
            if (t != lastTelemetryText) {
                lastTelemetryText = t
                tvStationTelemetry.text = t
            }
            syncPills()
        }
    }

    private fun syncPills(forceViews: Boolean = false) {
        if (currentPage == 2 || forceViews) {
            val pillsKey = "$ringMode|$micMuted|$outputRoute|$eqPreset"
            if (forceViews || pillsKey != lastPillsKey) {
                lastPillsKey = pillsKey
                val ringLabel = when (ringMode) {
                    "rainbow" -> "Радуга"
                    "solid" -> "Свет"
                    "breathe" -> "Дыхание"
                    "spinner" -> "Круг"
                    "spectrum", "music" -> "Спектр"
                    "off" -> "Выкл"
                    else -> ringMode
                }
                tileRing.label.text = "Кольцо: $ringLabel"
                tileMute.label.text = if (micMuted) "Мик: Выкл" else "Мик: Вкл"
                if (lastRenderedMicMuted != micMuted) {
                    lastRenderedMicMuted = micMuted
                    tileMute.icon.setImageResource(if (micMuted) R.drawable.ic_hw_mic_off else R.drawable.ic_hw_mic_on)
                }
                tileMute.root.isSelected = micMuted

                tileRoute.label.text = "Выход: " + when (outputRoute) {
                    "speakers" -> "Колонка"
                    "headphones" -> "Наушники"
                    "hdmi" -> "HDMI"
                    "both" -> "Вместе"
                    else -> "Авто"
                }
                tileEq.label.text = "EQ: " + when (eqPreset) {
                    "bass_boost" -> "Бас+"
                    "vocal" -> "Вокал"
                    "night" -> "Ночь"
                    "rock" -> "Рок"
                    "electronic" -> "Клуб"
                    "flat" -> "Обычный"
                    "custom" -> "Кастом"
                    else -> "Баланс"
                }
                val visName = when (LiveOrbState.visualizerStyle) {
                    "dots", "particles" -> "3D Точки"
                    "nexus", "anomaly" -> "Nexus 3D"
                    "specterr" -> "Корона"
                    "aurora" -> "Аврора"
                    "bars" -> "Спектр"
                    "wave" -> "Волна"
                    "matrix" -> "Матрица"
                    "fireflies" -> "Частицы"
                    else -> "3D Точки"
                }
                tileVis.label.text = "Визуал: $visName"
            }

            val astBusy = assistantState != "idle"
            val glowKey = "$astBusy|$ringMode"
            if (forceViews || glowKey != lastGlowStateKey) {
                lastGlowStateKey = glowKey
                val glowColors = when {
                    astBusy -> intArrayOf(Color.parseColor("#4438BDF8"), Color.parseColor("#33818CF8"))
                    ringMode == "off" -> intArrayOf(Color.parseColor("#0DFFFFFF"), Color.parseColor("#00FFFFFF"))
                    ringMode == "solid" -> intArrayOf(Color.parseColor("#2AFFFFFF"), Color.parseColor("#1494A3B8"))
                    else -> intArrayOf(Color.parseColor("#24FFFFFF"), Color.parseColor("#1838BDF8"))
                }
                stationRingGlowBg?.colors = glowColors
            }
        }
        renderOrbAssistantUi()
        updateAnimationsState()
    }

    private fun updateAnimationsState() {
        val runVinyl = (currentPage == 1) && latestMusicSnap.playing
        if (runVinyl) {
            vinylAnimator?.let {
                if (!it.isStarted) it.start() else if (it.isPaused) it.resume()
            }
        } else {
            vinylAnimator?.let { if (it.isRunning) it.pause() }
        }

        val runDial = (currentPage == 2) && (latestMusicSnap.playing || LiveOrbState.assistantState != "idle")
        if (runDial) {
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

    private fun renderMusicSnapshot(snap: YmPlayerSnapshot, forceViews: Boolean = false) {
        latestMusicSnap = snap
        LiveOrbState.musicPlaying = snap.playing

        val tr = snap.currentTrack
        val chipText = if (tr != null) {
            val prefix = if (snap.playing) "♫ " else "❚❚ "
            "$prefix${tr.artists} — ${tr.title}"
        } else {
            "Яндекс Музыка · Моя волна"
        }
        val dockStr = if (tr != null) chipText else ""
        if (dockStr != lastDockMusicStr) {
            lastDockMusicStr = dockStr
            tvDockMusic.text = dockStr
            tvDockMusic.visibility = if (dockStr.isNotEmpty()) View.VISIBLE else View.GONE
        }

        if (currentPage == 1 || forceViews) {
            tvHomeSourceBadge.text = snap.sourceLabel.uppercase(Locale.getDefault())

            tvHomeCodecBadge.visibility = View.GONE

            val err = snap.lastError
            val errClean = err.removePrefix("AUTH_REQUIRED:").trim()
            when {
                err.isNotBlank() -> {
                    tvHomeAuthBadge.text = "⚠ $errClean"
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#FCA5A5"))
                }
                snap.buffering -> {
                    tvHomeAuthBadge.text = "Загрузка потока..."
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#CBD5E1"))
                }
                snap.account.loggedIn -> {
                    val nm = snap.account.login.ifBlank { snap.account.fullName.ifBlank { "OK" } }
                    val plus = if (snap.account.hasPlus) " · Плюс" else ""
                    tvHomeAuthBadge.text = "● $nm$plus"
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#86EFAC"))
                }
                else -> {
                    tvHomeAuthBadge.text = "Нужен вход · нажмите «Плеер»"
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#94A3B8"))
                }
            }

            if (tr != null) {
                tvHomeTrackTitle.text = tr.title
                tvHomeTrackArtist.text = if (tr.album.isNotBlank()) "${tr.artists}  ·  ${tr.album}" else tr.artists
                val dur = maxOf(snap.durationMs, tr.durationMs)
                val pos = snap.positionMs.coerceAtLeast(0L)
                pbHomeTrack.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
                tvHomeTrackTime.text = "${formatTimeMs(pos)} / ${formatTimeMs(dur)}"

                if (forceViews || lastTrackLiked != tr.liked) {
                    lastTrackLiked = tr.liked
                    btnHomeLike.icon.setImageDrawable(
                        getDrawable(if (tr.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
                    )
                }

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
                if (forceViews || lastTrackLiked != false) {
                    lastTrackLiked = false
                    btnHomeLike.icon.setImageDrawable(getDrawable(R.drawable.ic_heart_outline))
                }
                if (lastHomeCoverUrl.isNotEmpty()) {
                    lastHomeCoverUrl = ""
                    setHomeCoverArt(null)
                }
            }

            val ppKey = "${snap.buffering}|${snap.playing}"
            if (forceViews || ppKey != lastPlayPauseIconKey) {
                lastPlayPauseIconKey = ppKey
                val ppIcon = getDrawable(
                    when {
                        snap.buffering -> R.drawable.ic_refresh
                        snap.playing -> R.drawable.ic_pause
                        else -> R.drawable.ic_play
                    }
                )?.mutate()?.apply {
                    setTint(Color.WHITE)
                }
                btnHomePlayPause.icon.setImageDrawable(ppIcon)
            }
        }
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

    private fun launchYandexMusic(sourceView: View? = null) {
        launchWithGlassTransition(sourceView) {
            try {
                val i = Intent(this, YandexMusicActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (t: Throwable) {
                Toast.makeText(this, "Не удалось открыть Яндекс Музыку: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchMarket(sourceView: View? = null) {
        launchWithGlassTransition(sourceView) {
            try {
                val i = Intent(this, MarketActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (t: Throwable) {
                Toast.makeText(this, "Не удалось открыть OPENSTATION APPS: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val otaDialogPollRunnable = object : Runnable {
        override fun run() {
            if (otaDialog?.isShowing != true) return
            thread(isDaemon = true) {
                try {
                    val conn = (URL("http://127.0.0.1:8080/api/ota/status").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 900
                        readTimeout = 1200
                    }
                    if (conn.responseCode == 200) {
                        val txt = conn.inputStream.bufferedReader().use { it.readText() }
                        val o = JSONObject(txt)
                        handler.post { applyOtaUi(o) }
                    }
                    conn.disconnect()
                } catch (_: Throwable) {}
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private fun applyOtaUi(ota: JSONObject?) {
        if (ota != null) otaLatestJson = ota
        val cur = ota ?: otaLatestJson ?: return
        val avail = cur.optBoolean("updateAvailable", false)
        val busy = cur.optBoolean("busy", false)
        val latestVer = cur.optString("latestVersion", "")
        val pct = cur.optInt("progressPct", 0)
        val key = "$avail|$busy|$latestVer|${if (busy) pct else -1}"
        if (key != lastOtaBtnKey && ::btnOtaUpdate.isInitialized) {
            lastOtaBtnKey = key
            when {
                busy -> {
                    btnOtaUpdate.text = "⏳ $pct%"
                    btnOtaUpdate.setBackgroundResource(R.drawable.bg_pill)
                    btnOtaUpdate.setTextColor(Color.parseColor("#38BDF8"))
                }
                avail -> {
                    btnOtaUpdate.text = if (latestVer.isNotBlank()) "⬇ v$latestVer" else "⬇ Обновить"
                    btnOtaUpdate.setBackgroundResource(R.drawable.bg_pill_gold)
                    btnOtaUpdate.setTextColor(Color.parseColor("#0B0F17"))
                }
                else -> {
                    btnOtaUpdate.text = "⬇ OTA"
                    btnOtaUpdate.setBackgroundResource(R.drawable.bg_pill)
                    btnOtaUpdate.setTextColor(Color.parseColor("#E2E8F0"))
                }
            }
        }
        refreshOtaDialogViews()
    }

    private fun refreshOtaDialogViews() {
        val dlg = otaDialog ?: return
        if (!dlg.isShowing) return
        val o = otaLatestJson
        val tv = otaDialogStatusTv
        val clTv = otaDialogChangelogTv
        val instBtn = otaDialogInstallBtn
        if (o == null) {
            tv?.text = "Проверка обновлений..."
            clTv?.visibility = View.GONE
            instBtn?.isEnabled = false
            return
        }
        val instVer = o.optString("installedVersion", "1.2.3")
        val latVer = o.optString("latestVersion", "")
        val repo = o.optString("repo", "Olegu621/OPENSTATION")
        val st = o.optString("state", "idle")
        val busy = o.optBoolean("busy", false)
        val avail = o.optBoolean("updateAvailable", false)
        val pct = o.optInt("progressPct", 0)
        val ptxt = o.optString("progressText", "")
        val err = o.optString("error", "")
        val chArr = o.optJSONArray("changedComponents")
        val comps = buildString {
            if (chArr != null && chArr.length() > 0) {
                for (i in 0 until chArr.length()) {
                    if (i > 0) append(", ")
                    val comp = when (val rawC = chArr.optString(i)) {
                        "daemon" -> "система"
                        "launcher" -> "ТВ-экран"
                        "ime" -> "клавиатура"
                        "bootanimation" -> "заставка"
                        else -> rawC
                    }
                    append(comp)
                }
            }
        }
        tv?.text = buildString {
            append("Установлена версия: v").append(instVer).append('\n')
            if (latVer.isNotBlank()) {
                append("Доступно на GitHub (").append(repo).append("): v").append(latVer)
                if (comps.isNotBlank()) append(" — ").append(comps)
                append('\n')
            }
            append('\n')
            when {
                st == "error" -> append("✗ Ошибка: ").append(err.ifBlank { ptxt })
                busy -> append("⏳ ").append(pct).append("% — ").append(ptxt)
                avail -> append("✨ Доступно обновление! Ваши настройки, ключи и аккаунт сохранятся.")
                st == "up_to_date" -> append("✓ ").append(ptxt.ifBlank { "Установлена актуальная версия" })
                else -> append("Нажмите «Проверить обновления» для поиска новой версии.")
            }
        }
        val cl = o.optString("changelog", "")
        if (cl.isNotBlank()) {
            clTv?.visibility = View.VISIBLE
            clTv?.text = "Что нового:\n$cl"
        } else {
            clTv?.visibility = View.GONE
        }
        instBtn?.isEnabled = !busy && (avail || st == "up_to_date")
        instBtn?.text = when {
            busy -> "Обновление ($pct%)..."
            avail && latVer.isNotBlank() -> "Установить v$latVer"
            else -> "Переустановить с GitHub"
        }
    }

    private fun openOtaDialog() {
        try { otaDialog?.dismiss() } catch (_: Throwable) {}
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
        }
        val statusTv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13.5f
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        val changelogTv = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12.5f
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        body.addView(statusTv)
        body.addView(changelogTv)
        otaDialogStatusTv = statusTv
        otaDialogChangelogTv = changelogTv

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("☁ Обновление прошивки")
            .setView(body)
            .setPositiveButton("Установить обновление", null)
            .setNeutralButton("Проверить обновления", null)
            .setNegativeButton("Закрыть") { d, _ -> d.dismiss() }
            .create()
        dlg.setOnDismissListener {
            handler.removeCallbacks(otaDialogPollRunnable)
            if (otaDialog === dlg) {
                otaDialog = null
                otaDialogStatusTv = null
                otaDialogChangelogTv = null
                otaDialogInstallBtn = null
            }
        }
        otaDialog = dlg
        dlg.show()

        val posBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        val neuBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
        otaDialogInstallBtn = posBtn
        posBtn?.setOnClickListener {
            val cur = otaLatestJson
            val force = !(cur?.optBoolean("updateAvailable", false) ?: false)
            posBtn.isEnabled = false
            postAsync("http://127.0.0.1:8080/api/ota/install", JSONObject().put("force", force).toString())
            Toast.makeText(this, "Запуск OTA с GitHub...", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(otaDialogPollRunnable)
            handler.postDelayed(otaDialogPollRunnable, 350L)
        }
        neuBtn?.setOnClickListener {
            postAsync("http://127.0.0.1:8080/api/ota/check", "{}")
            Toast.makeText(this, "Проверка GitHub...", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(otaDialogPollRunnable)
            handler.postDelayed(otaDialogPollRunnable, 250L)
        }

        refreshOtaDialogViews()
        handler.removeCallbacks(otaDialogPollRunnable)
        handler.post(otaDialogPollRunnable)
    }

    private fun openSetupWizard(manual: Boolean) {
        try {
            val i = Intent(this, SetupWizardActivity::class.java).apply {
                putExtra(SetupWizardActivity.EXTRA_MANUAL, manual)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось открыть мастер: ${t.message}", Toast.LENGTH_SHORT).show()
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
        val order = listOf("balanced", "bass_boost", "vocal", "night", "rock", "electronic", "flat")
        val next = order[(order.indexOf(eqPreset) + 1) % order.size]
        eqPreset = next
        if (::eqWidgetController.isInitialized) {
            eqWidgetController.setLocalPreset(next)
        }
        syncPills()
        syncHomeWidgetsUi()
        try {
            startForegroundService(Intent(this, StationAudioService::class.java).apply {
                action = StationAudioService.ACTION_SET_EQ
                putExtra(StationAudioService.EXTRA_PRESET, next)
            })
        } catch (_: Throwable) {}
        val body = JSONObject().put("audio", JSONObject().put("eqPreset", next)).toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun sendCec(cmd: String) {
        val body = JSONObject().put("cmd", cmd).toString()
        postAsync("http://127.0.0.1:8080/api/remote/cec", body)
        val msg = if (cmd == "standby") "Выключение ТВ отправлено" else "Включение ТВ отправлено"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private var cecState = false
    private fun toggleCec() {
        cecState = !cecState
        val cmd = if (cecState) "standby" else "wake"
        sendCec(cmd)
        tileCec.label.text = if (cecState) "ТВ: Выключен" else "ТВ: Включён"
    }

    private fun cycleVisualizerStyle() {
        val styles = listOf("dots", "nexus", "specterr", "aurora", "bars", "wave", "matrix", "fireflies")
        val shortNames = mapOf(
            "dots" to "3D Точки",
            "nexus" to "Nexus 3D",
            "specterr" to "Корона",
            "aurora" to "Аврора",
            "bars" to "Спектр",
            "wave" to "Волна",
            "matrix" to "Матрица",
            "fireflies" to "Частицы"
        )
        val cur = LiveOrbState.visualizerStyle
        val curIdx = styles.indexOf(cur).takeIf { it >= 0 } ?: 0
        val next = styles[(curIdx + 1) % styles.size]
        LiveOrbState.visualizerStyle = next
        LiveOrbState.previewUntilMs = System.currentTimeMillis() + 8000L
        val name = shortNames[next] ?: next
        tileVis.label.text = "Визуал: $name"
        Toast.makeText(this, "3D Визуализатор: $name", Toast.LENGTH_SHORT).show()
        val body = JSONObject()
            .put("ledScreen", JSONObject().put("visualizerStyle", next))
            .toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun makeGlassWidgetCard(accentHex: String, onClick: () -> Unit): LinearLayout {
        val accent = Color.parseColor(accentHex)
        val normStroke = Color.argb(86, Color.red(accent), Color.green(accent), Color.blue(accent))
        val focStroke = Color.argb(235, Color.red(accent), Color.green(accent), Color.blue(accent))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 18f,
                baseTint = Color.parseColor("#5C0B1324"),
                focusTint = Color.parseColor("#86142542"),
                normalStroke = normStroke,
                focusStroke = focStroke,
                refractStrength = 1.0f
            )
            elevation = dp(10).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(110).start()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleBtFromWidget() {
        btEnabledState = !btEnabledState
        btDiscoverableState = btEnabledState
        syncHomeWidgetsUi()
        postAsync("http://127.0.0.1:8080/api/bluetooth/toggle", "{}")
    }

    private fun showWidgetsConfigDialog() {
        showHomeCustomizationMenu()
    }

    private fun applyWidgetsVisibility() {
        val prefs = getSharedPreferences("openstation_widgets", MODE_PRIVATE)
        if (!prefs.getBoolean("widgets_v2_initialized", false)) {
            prefs.edit()
                .putBoolean("widgets_v2_initialized", true)
                .putBoolean("widget_eq", false)
                .putBoolean("widget_bt", false)
                .putBoolean("widget_weather", false)
                .putBoolean("widget_remote", false)
                .apply()
        }
        val showEq = prefs.getBoolean("widget_eq", false)
        val showBt = prefs.getBoolean("widget_bt", false)
        val showWeather = prefs.getBoolean("widget_weather", false)
        val showRemote = prefs.getBoolean("widget_remote", false)

        if (::eqWidgetController.isInitialized) {
            eqWidgetController.rootCard.visibility = if (showEq) View.VISIBLE else View.GONE
        }
        if (::wBtCard.isInitialized) wBtCard.visibility = if (showBt) View.VISIBLE else View.GONE
        if (::wWeatherCard.isInitialized) wWeatherCard.visibility = if (showWeather) View.VISIBLE else View.GONE
        if (::wRemoteCard.isInitialized) wRemoteCard.visibility = if (showRemote) View.VISIBLE else View.GONE
        if (::wLeftCol.isInitialized) wLeftCol.visibility = if (showEq || showBt) View.VISIBLE else View.GONE
        if (::wRightCol.isInitialized) wRightCol.visibility = if (showWeather || showRemote) View.VISIBLE else View.GONE
    }

    private fun syncHomeWidgetsUi() {
        if (::tvWidgetBtState.isInitialized && ::vWidgetBtDotBg.isInitialized) {
            when {
                btDiscoverableState -> {
                    vWidgetBtDotBg.setColor(Color.parseColor("#00D4FF"))
                    tvWidgetBtState.text = "Поиск пары: OpenStation Max"
                }
                btEnabledState -> {
                    vWidgetBtDotBg.setColor(Color.parseColor("#10D281"))
                    tvWidgetBtState.text = "Включена · Готова к музыке"
                }
                else -> {
                    vWidgetBtDotBg.setColor(Color.parseColor("#64748B"))
                    tvWidgetBtState.text = "Выключена · Нажмите"
                }
            }
        }
        if (::tvWidgetWeatherTemp.isInitialized && LiveOrbState.weatherValid) {
            val sign = if (LiveOrbState.weatherTemp > 0) "+" else ""
            tvWidgetWeatherCity.text = LiveOrbState.weatherCity.ifBlank { "ПОГОДА" }.uppercase(Locale.getDefault())
            tvWidgetWeatherTemp.text = "$sign${LiveOrbState.weatherTemp}°"
            tvWidgetWeatherDesc.text = LiveOrbState.weatherConditionText.ifBlank { "Обновлено" }
        }
        if (::tvWidgetRemoteState.isInitialized) {
            val st = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
            if (st != null && st.bonded) {
                val bat = if (st.batteryPct >= 0) " · ${st.batteryPct}%" else ""
                tvWidgetRemoteState.text = "${st.name}$bat · Нажмите (Зуммер)"
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // SYNCHRONIZED BOOT REVEAL + BLURRED GLASS TRANSITIONS + WALLPAPER ENGINE
    // -----------------------------------------------------------------------------------------
    private var ivDrawerBlurBg: ImageView? = null
    private var launchTimeoutRunnable: Runnable? = null

    private fun startBootRevealFlow() {
        if (bootRevealStarted) return
        bootRevealStarted = true

        shellCol.alpha = 0f
        shellCol.scaleX = 0.96f
        shellCol.scaleY = 0.96f
        shellCol.translationY = dp(18).toFloat()

        heroOrbView.alpha = 0f
        heroOrbView.scaleX = 0.68f
        heroOrbView.scaleY = 0.68f

        var revealed = false
        val safety = Runnable {
            if (!revealed) {
                revealed = true
                runSynchronizedDesktopReveal(fullReveal = false)
            }
        }
        handler.postDelayed(safety, 950L)

        thread(isDaemon = true, name = "BootRevealSync") {
            var coldFirstBoot = false
            try {
                val conn = (URL("http://127.0.0.1:8080/api/system/startup_show").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 750
                    readTimeout = 750
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(body)
                    coldFirstBoot = obj.optBoolean("ok", false) && !obj.optBoolean("alreadyShown", true)
                }
                conn.disconnect()
            } catch (_: Throwable) {}

            handler.post {
                if (!revealed) {
                    revealed = true
                    handler.removeCallbacks(safety)
                    runSynchronizedDesktopReveal(fullReveal = coldFirstBoot)
                }
            }
        }
    }

    private fun runSynchronizedDesktopReveal(fullReveal: Boolean) {
        if (!::heroOrbView.isInitialized || !::shellCol.isInitialized) return
        if (startupShowActive) return

        if (!fullReveal) {
            shellCol.animate().cancel()
            heroOrbView.animate().cancel()
            shellCol.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(easeOutCubic)
                .start()
            heroOrbView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(easeOutCubic)
                .start()
            return
        }

        startupShowActive = true
        setPage(0, animate = false)
        shellCol.animate().cancel()
        heroOrbView.animate().cancel()

        shellCol.alpha = 0f
        shellCol.scaleX = 0.96f
        shellCol.scaleY = 0.96f
        shellCol.translationY = dp(18).toFloat()

        heroOrbView.alpha = 0f
        heroOrbView.scaleX = 0.68f
        heroOrbView.scaleY = 0.68f

        heroOrbView.animate()
            .alpha(1f)
            .scaleX(1.09f)
            .scaleY(1.09f)
            .setDuration(820L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                heroOrbView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(980L)
                    .setInterpolator(easeOutCubic)
                    .start()
            }
            .start()

        handler.postDelayed({
            shellCol.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(980L)
                .setInterpolator(easeOutCubic)
                .start()
        }, 320L)

        if (::tvHeroState.isInitialized && ::tvHeroReply.isInitialized) {
            tvHeroState.text = "OPENSTATION MAX · ДОБРО ПОЖАЛОВАТЬ"
            tvHeroReply.text = "Жидкое стекло, акустика и матричный дисплей активны."
        }
        handler.postDelayed({
            startupShowActive = false
            renderOrbAssistantUi()
        }, 4400L)
    }

    private fun runStartupOrbShow() {
        runSynchronizedDesktopReveal(fullReveal = true)
    }

    private fun launchWithGlassTransition(anchorView: View?, launchBlock: () -> Unit) {
        if (!::blurCurtainHost.isInitialized) {
            launchBlock()
            return
        }
        launchTimeoutRunnable?.let { handler.removeCallbacks(it) }
        LiquidGlassKit.blurredWallpaper?.let { ivBlurCurtain.setImageBitmap(it) }

        closeAllOverlays(immediate = false)
        returningFromExternalApp = true

        blurCurtainHost.animate().cancel()
        shellCol.animate().cancel()
        heroOrbView.animate().cancel()

        blurCurtainHost.visibility = View.VISIBLE
        blurCurtainHost.alpha = 0f
        blurCurtainHost.animate()
            .alpha(1f)
            .setDuration(200L)
            .setInterpolator(easeOutCubic)
            .start()

        shellCol.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .alpha(0.32f)
            .setDuration(200L)
            .setInterpolator(easeOutCubic)
            .start()

        heroOrbView.animate()
            .alpha(0.24f)
            .setDuration(180L)
            .start()

        anchorView?.animate()
            ?.scaleX(1.10f)
            ?.scaleY(1.10f)
            ?.setDuration(150L)
            ?.setInterpolator(easeOutCubic)
            ?.start()

        val safety = Runnable {
            if (returningFromExternalApp && hasWindowFocus()) {
                settleFromExternalAppIfNeeded()
            }
        }
        launchTimeoutRunnable = safety
        handler.postDelayed(safety, 950L)

        handler.postDelayed({
            launchBlock()
        }, 145L)
    }

    private fun settleFromExternalAppIfNeeded() {
        if (!returningFromExternalApp) return
        returningFromExternalApp = false
        launchTimeoutRunnable?.let { handler.removeCallbacks(it) }
        launchTimeoutRunnable = null

        if (!::blurCurtainHost.isInitialized || !::shellCol.isInitialized) return
        LiquidGlassKit.blurredWallpaper?.let { ivBlurCurtain.setImageBitmap(it) }

        blurCurtainHost.animate().cancel()
        shellCol.animate().cancel()
        heroOrbView.animate().cancel()

        blurCurtainHost.visibility = View.VISIBLE
        blurCurtainHost.alpha = 0.90f
        shellCol.scaleX = 0.94f
        shellCol.scaleY = 0.94f
        shellCol.alpha = 0.35f

        shellCol.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(300L)
            .setInterpolator(easeOutCubic)
            .start()

        heroOrbView.animate()
            .alpha(1f)
            .setDuration(300L)
            .setInterpolator(easeOutCubic)
            .withEndAction { applyPageVisuals(currentPagePos) }
            .start()

        blurCurtainHost.animate()
            .alpha(0f)
            .setDuration(320L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                blurCurtainHost.visibility = View.GONE
            }
            .start()
    }

    private fun applyWallpaperTheme(themeId: String, persist: Boolean = true) {
        LiquidGlassKit.setWallpaper(this, themeId, persist)
        if (::ivBg.isInitialized) {
            LiquidGlassKit.sharpWallpaper?.let { ivBg.setImageBitmap(it) }
        }
        if (::ivBlurCurtain.isInitialized) {
            LiquidGlassKit.blurredWallpaper?.let { ivBlurCurtain.setImageBitmap(it) }
        }
        ivDrawerBlurBg?.let { iv ->
            LiquidGlassKit.blurredWallpaper?.let { iv.setImageBitmap(it) }
        }
        if (::rootFrame.isInitialized) {
            LiquidGlassKit.refreshGlassTree(rootFrame)
        }
    }

    // -----------------------------------------------------------------------------------------
    // APPS DRAWER OVERLAY (DPAD_DOWN on Home -> Frosted Grid with OPENSTATION APPS #1)
    // -----------------------------------------------------------------------------------------
    private fun buildAppsDrawerOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
        }
        val blurBg = ImageView(this).apply {
            LiquidGlassKit.blurredWallpaper?.let { setImageBitmap(it) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ivDrawerBlurBg = blurBg
        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#BA040812"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(blurBg)
        overlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(22), dp(26), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = "ПРИЛОЖЕНИЯ СТАНЦИИ"
            setTextColor(Color.WHITE)
            textSize = 20f
            letterSpacing = 0.06f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub = TextView(this).apply {
            text = "OPENSTATION APPS закреплён первым  ·  Удерживайте OK на плитке для меню (переместить, скрыть, удалить, инфо)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)

        tvDrawerStatusPill = TextView(this).apply {
            text = "Список приложений"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor("#38090F1A"),
                normalStroke = Color.parseColor("#24FFFFFF"),
                refractStrength = 0.85f
            )
            isFocusable = false
        }
        headerRow.addView(titleCol)
        headerRow.addView(tvDrawerStatusPill)
        panel.addView(headerRow)

        drawerScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        drawerAppsGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        drawerScrollView.addView(drawerAppsGrid)
        panel.addView(drawerScrollView)

        val footerHint = TextView(this).apply {
            text = "▲ или BACK — вернуться на рабочий стол с Алисой"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        panel.addView(footerHint)

        overlay.addView(panel)
        return overlay
    }

    private fun openAppsDrawer(focusPkg: String = PKG_OPENSTATION_APPS) {
        if (!::appsDrawerOverlay.isInitialized) return
        reorderingPkg = null
        appsDrawerOpen = true
        loadAndRenderApps()
        LiquidGlassKit.blurredWallpaper?.let { ivDrawerBlurBg?.setImageBitmap(it) }

        appsDrawerOverlay.animate().cancel()
        appsDrawerOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        appsDrawerOverlay.alpha = 0f
        appsDrawerOverlay.translationY = dp(48).toFloat()
        appsDrawerOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250L)
            .setInterpolator(easeOutCubic)
            .start()

        drawerScrollView.scrollTo(0, 0)
        val immediateTarget = drawerTileViews[focusPkg] ?: drawerTileViews[PKG_OPENSTATION_APPS]
        immediateTarget?.requestFocus()
        handler.post {
            if (appsDrawerOpen && !appContextOpen && !appInfoOpen) {
                val target = drawerTileViews[focusPkg] ?: drawerTileViews[PKG_OPENSTATION_APPS]
                target?.requestFocus()
            }
        }
    }

    private fun closeAppsDrawer(immediate: Boolean = false) {
        if (!::appsDrawerOverlay.isInitialized) return
        if (!appsDrawerOpen && appsDrawerOverlay.visibility != View.VISIBLE) return
        reorderingPkg = null
        appsDrawerOpen = false
        syncLayerFocusability()
        if (immediate) {
            appsDrawerOverlay.animate().cancel()
            appsDrawerOverlay.visibility = View.GONE
            appsDrawerOverlay.alpha = 0f
            appsDrawerOverlay.translationY = 0f
            return
        }
        focusPageControlDirect(currentPage)
        appsDrawerOverlay.animate().cancel()
        appsDrawerOverlay.animate()
            .alpha(0f)
            .translationY(dp(42).toFloat())
            .setDuration(200L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!appsDrawerOpen) {
                    appsDrawerOverlay.visibility = View.GONE
                    appsDrawerOverlay.translationY = 0f
                    syncLayerFocusability()
                    if (!homeCustomOpen && !appContextOpen && !appInfoOpen && currentPage == 0) {
                        focusPageControlDirect(0)
                    }
                }
            }
            .start()
    }

    // -----------------------------------------------------------------------------------------
    // HOME CUSTOMIZATION OVERLAY (Long-Press OK on Home: Wallpapers + Widgets + Hidden Apps)
    // -----------------------------------------------------------------------------------------
    private fun showHomeCustomizationMenu() {
        if (!::homeCustomOverlay.isInitialized) return
        homeCustomOverlay.removeAllViews()

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#A6030711"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        homeCustomOverlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 24f,
                baseTint = Color.parseColor("#D4080D18"),
                focusTint = Color.parseColor("#E010182C"),
                normalStroke = Color.parseColor("#24FFFFFF"),
                focusStroke = Color.parseColor("#AAFFFFFF"),
                refractStrength = 1.05f
            )
            elevation = dp(28).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                dp(740),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val tvHead = TextView(this).apply {
            text = "НАСТРОЙКА ГЛАВНОГО ЭКРАНА"
            setTextColor(Color.WHITE)
            textSize = 18f
            letterSpacing = 0.05f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub = TextView(this).apply {
            text = "Обои с оптическим преломлением Liquid Glass · Модульные виджеты рабочего стола"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(10))
        }
        panel.addView(tvHead)
        panel.addView(tvSub)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        body.addView(makeSectionTitle("Обои рабочего стола", R.drawable.ic_wave))

        val wallGrid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        var firstWallpaperBtn: View? = null
        val wallSwatches = ArrayList<Pair<String, TextView>>()
        val tileW = dp(220)
        LiquidGlassKit.THEMES.forEachIndexed { idx, theme ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isFocusable = true
                isClickable = true
                LiquidGlassKit.applyGlass(
                    this,
                    radiusDp = 14f,
                    baseTint = Color.parseColor("#44090E18"),
                    focusTint = Color.parseColor("#78162338"),
                    normalStroke = if (theme.id == LiquidGlassKit.currentThemeId) Color.parseColor("#99FFFFFF") else Color.parseColor("#1AFFFFFF"),
                    focusStroke = Color.parseColor("#CCFFFFFF")
                )
                layoutParams = GridLayout.LayoutParams().apply {
                    width = tileW
                    height = dp(52)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(90).start()
                }
            }
            if (idx == 0) firstWallpaperBtn = card

            val swatch = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.parseColor(theme.baseDarkHex),
                        Color.parseColor(theme.secondaryHex),
                        Color.parseColor(theme.primaryHex)
                    )
                ).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#88FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginEnd = dp(9)
                }
            }
            val txtCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvT = TextView(this).apply {
                text = theme.title
                setTextColor(Color.WHITE)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvS = TextView(this).apply {
                text = theme.subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 9.5f
                maxLines = 1
            }
            txtCol.addView(tvT)
            txtCol.addView(tvS)

            val tvMark = TextView(this).apply {
                text = if (theme.id == LiquidGlassKit.currentThemeId) "✓" else ""
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            wallSwatches.add(theme.id to tvMark)

            card.addView(swatch)
            card.addView(txtCol)
            card.addView(tvMark)
            card.setOnClickListener {
                applyWallpaperTheme(theme.id, persist = true)
                wallSwatches.forEach { (id, badge) ->
                    badge.text = if (id == theme.id) "✓" else ""
                }
                LiquidGlassKit.refreshGlassTree(homeCustomOverlay)
                Toast.makeText(this, "Обои: ${theme.title}", Toast.LENGTH_SHORT).show()
            }
            wallGrid.addView(card)
        }
        body.addView(wallGrid)

        body.addView(makeSectionTitle("Виджеты на главном экране", R.drawable.ic_hw_eq))

        val prefs = getSharedPreferences("openstation_widgets", MODE_PRIVATE)
        val widgetDefs = listOf(
            Triple("widget_eq", "Эквалайзер (Live EQ)", "Полосы БАС · 60Hz–14kHz"),
            Triple("widget_bt", "Bluetooth колонка", "Быстрое подключение телефона"),
            Triple("widget_weather", "Виджет погоды", "Температура и прогноз"),
            Triple("widget_remote", "Поиск BLE-пульта", "Заряд и звуковой сигнал")
        )

        val widgetsGrid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val wCardW = dp(336)
        for ((key, title, desc) in widgetDefs) {
            var enabled = prefs.getBoolean(key, false)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isClickable = true
                LiquidGlassKit.applyGlass(
                    this,
                    radiusDp = 14f,
                    baseTint = Color.parseColor("#44090E18"),
                    focusTint = Color.parseColor("#78162338"),
                    normalStroke = Color.parseColor("#1AFFFFFF"),
                    focusStroke = Color.parseColor("#CCFFFFFF")
                )
                layoutParams = GridLayout.LayoutParams().apply {
                    width = wCardW
                    height = dp(48)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(90).start()
                }
            }
            val txtCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvTitle = TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            val tvDesc = TextView(this).apply {
                text = desc
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 9.5f
                maxLines = 1
            }
            txtCol.addView(tvTitle)
            txtCol.addView(tvDesc)

            val pill = TextView(this).apply {
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            fun updatePill() {
                pill.text = if (enabled) "ВКЛ" else "ВЫКЛ"
                pill.setTextColor(Color.parseColor(if (enabled) "#090D16" else "#94A3B8"))
                pill.background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor(if (enabled) "#F1F5F9" else "#1E293B"))
                    if (!enabled) setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                }
            }
            updatePill()

            row.addView(txtCol)
            row.addView(pill)
            row.setOnClickListener {
                enabled = !enabled
                prefs.edit()
                    .putBoolean("widgets_v2_initialized", true)
                    .putBoolean(key, enabled)
                    .apply()
                updatePill()
                applyWidgetsVisibility()
                syncHomeWidgetsUi()
                handler.removeCallbacks(widgetEqTickRunnable)
                handler.post(widgetEqTickRunnable)
            }
            widgetsGrid.addView(row)
        }
        body.addView(widgetsGrid)
        scroll.addView(body)
        panel.addView(scroll)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        val hiddenCount = getHiddenAppPackages().size
        if (hiddenCount > 0) {
            val btnHidden = makeTextIconPill(R.drawable.ic_yaos_apps, "Скрытые ($hiddenCount)", gold = false) {
                showHiddenAppsMenu()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                    marginEnd = dp(8)
                }
            }
            actionsRow.addView(btnHidden)
        }

        val btnOpenDrawer = makeTextIconPill(R.drawable.ic_yaos_apps, "Приложения", gold = false) {
            closeHomeCustomizationMenu(immediate = true)
            openAppsDrawer()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(8)
            }
        }
        val btnDone = makeTextIconPill(R.drawable.ic_play, "Готово", gold = true) {
            closeHomeCustomizationMenu()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        }
        actionsRow.addView(btnOpenDrawer)
        actionsRow.addView(btnDone)
        panel.addView(actionsRow)

        homeCustomOverlay.addView(panel)
        homeCustomOpen = true
        homeCustomOverlay.animate().cancel()
        homeCustomOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        homeCustomOverlay.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        homeCustomOverlay.animate()
            .alpha(1f)
            .setDuration(200L)
            .setInterpolator(easeOutCubic)
            .start()
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(easeOutCubic)
            .start()

        (firstWallpaperBtn ?: btnDone).requestFocus()
        handler.post { if (homeCustomOpen && !appInfoOpen) (firstWallpaperBtn ?: btnDone).requestFocus() }
    }

    private fun closeHomeCustomizationMenu(immediate: Boolean = false) {
        if (!::homeCustomOverlay.isInitialized) return
        if (!homeCustomOpen && homeCustomOverlay.visibility != View.VISIBLE) return
        homeCustomOpen = false
        syncLayerFocusability()
        if (immediate) {
            homeCustomOverlay.animate().cancel()
            homeCustomOverlay.visibility = View.GONE
            homeCustomOverlay.alpha = 0f
            homeCustomOverlay.removeAllViews()
            return
        }
        focusPageControlDirect(currentPage)
        homeCustomOverlay.animate().cancel()
        homeCustomOverlay.animate()
            .alpha(0f)
            .setDuration(170L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!homeCustomOpen) {
                    homeCustomOverlay.visibility = View.GONE
                    homeCustomOverlay.removeAllViews()
                    syncLayerFocusability()
                    if (!appsDrawerOpen && !appContextOpen && !appInfoOpen && currentPage == 0) {
                        focusPageControlDirect(0)
                    }
                }
            }
            .start()
    }

    // -----------------------------------------------------------------------------------------
    // ANCHORED APP CONTEXT MENU + REORDER + HIDE + UNINSTALL + LIQUID GLASS APP INFO
    // -----------------------------------------------------------------------------------------
    private fun showAnchoredAppMenu(app: AppEntry, anchor: View) {
        if (!::appContextOverlay.isInitialized || !::rootFrame.isInitialized) return
        appContextOverlay.removeAllViews()
        appContextOverlay.tag = app.pkg

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#6602050E"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        appContextOverlay.addView(scrim)

        val isPinnedApps = (app.pkg == PKG_OPENSTATION_APPS)
        val isSysPkg = !app.isVirtual && try {
            val ai = packageManager.getApplicationInfo(app.pkg, 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) {
            false
        }

        val menuW = dp(258)
        val itemCountEst = 2 + (if (!isPinnedApps) 2 else 0) + (if (!app.isVirtual) 1 else 0)
        val menuEstH = dp(66 + itemCountEst * 42)

        val anchorLoc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        rootFrame.getLocationInWindow(rootLoc)
        val ax = anchorLoc[0] - rootLoc[0]
        val ay = anchorLoc[1] - rootLoc[1]
        val rootW = if (rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels
        val rootH = if (rootFrame.height > 0) rootFrame.height else resources.displayMetrics.heightPixels

        val placeRightX = ax + anchor.width + dp(10)
        val placeLeftX = ax - menuW - dp(10)
        val finalX = if (placeRightX + menuW <= rootW - dp(14)) {
            placeRightX
        } else {
            placeLeftX.coerceIn(dp(14), maxOf(dp(14), rootW - menuW - dp(14)))
        }
        val finalY = (ay - dp(4)).coerceIn(dp(16), maxOf(dp(16), rootH - menuEstH - dp(16)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 18f,
                baseTint = Color.parseColor("#D8090E1A"),
                focusTint = Color.parseColor("#E4121D32"),
                normalStroke = Color.parseColor("#28FFFFFF"),
                focusStroke = Color.parseColor("#CCFFFFFF"),
                refractStrength = 1.05f
            )
            elevation = dp(32).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                menuW,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = finalX
                topMargin = finalY
            }
        }

        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val headIc = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(9) }
        }
        val headCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvHeadTitle = TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvHeadTag = TextView(this).apply {
            text = when {
                isPinnedApps -> "Закреплено первым (#1)"
                app.isVirtual -> "Встроенный модуль"
                isSysPkg -> "Системное приложение"
                else -> app.pkg
            }
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headCol.addView(tvHeadTitle)
        headCol.addView(tvHeadTag)
        headRow.addView(headIc)
        headRow.addView(headCol)
        panel.addView(headRow)

        val div = View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(6)
            }
        }
        panel.addView(div)

        var firstBtn: View? = null
        fun addMenuAction(label: String, accentHex: String = "#F8FAFC", onAction: () -> Unit) {
            val btn = TextView(this).apply {
                text = label
                setTextColor(Color.parseColor(accentHex))
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isClickable = true
                LiquidGlassKit.applyGlass(
                    this,
                    radiusDp = 12f,
                    baseTint = Color.parseColor("#320E1526"),
                    focusTint = Color.parseColor("#90223452"),
                    normalStroke = Color.parseColor("#18FFFFFF"),
                    focusStroke = Color.parseColor("#CCFFFFFF"),
                    refractStrength = 0.75f
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(80).start()
                }
                setOnClickListener {
                    closeAnchoredAppMenu(immediate = true, restoreFocus = false)
                    onAction()
                }
            }
            if (firstBtn == null) firstBtn = btn
            panel.addView(btn)
        }

        addMenuAction("Открыть", "#FFFFFFFF") {
            launchApp(app, anchor)
        }
        if (!isPinnedApps) {
            addMenuAction("Изменить место (переместить)", "#7DD3FC") {
                startReorderMode(app)
            }
            addMenuAction("Скрыть приложение", "#CBD5E1") {
                hideAppPackage(app)
            }
        }
        if (!app.isVirtual) {
            if (!isSysPkg) {
                addMenuAction("Удалить приложение", "#FCA5A5") {
                    uninstallAppEntry(app)
                }
            } else {
                addMenuAction("Параметры пакета", "#FCA5A5") {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${app.pkg}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Throwable) {}
                }
            }
        }
        addMenuAction("О приложении", "#E2E8F0") {
            showAppInfoModal(app)
        }

        appContextOverlay.addView(panel)
        appContextOpen = true
        appContextOverlay.animate().cancel()
        appContextOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        appContextOverlay.alpha = 0f
        panel.scaleX = 0.90f
        panel.scaleY = 0.90f
        appContextOverlay.animate()
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(easeOutCubic)
            .start()
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(170L)
            .setInterpolator(easeOutCubic)
            .start()

        firstBtn?.requestFocus()
        handler.post { if (appContextOpen) firstBtn?.requestFocus() }
    }

    private fun closeAnchoredAppMenu(immediate: Boolean = false, restoreFocus: Boolean = true) {
        if (!::appContextOverlay.isInitialized) return
        if (!appContextOpen && appContextOverlay.visibility != View.VISIBLE) return
        val prevPkg = appContextOverlay.tag as? String
        appContextOpen = false
        syncLayerFocusability()
        if (immediate) {
            appContextOverlay.animate().cancel()
            appContextOverlay.visibility = View.GONE
            appContextOverlay.alpha = 0f
            appContextOverlay.removeAllViews()
            if (restoreFocus && prevPkg != null) focusAppTileByPkg(prevPkg)
            return
        }
        if (restoreFocus && prevPkg != null) focusAppTileByPkg(prevPkg)
        appContextOverlay.animate().cancel()
        appContextOverlay.animate()
            .alpha(0f)
            .setDuration(140L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!appContextOpen) {
                    appContextOverlay.visibility = View.GONE
                    appContextOverlay.removeAllViews()
                    syncLayerFocusability()
                    if (restoreFocus && prevPkg != null && !appInfoOpen) focusAppTileByPkg(prevPkg)
                }
            }
            .start()
    }

    private fun focusAppTileByPkg(pkg: String) {
        val findView = {
            if (appsDrawerOpen) {
                drawerTileViews[pkg] ?: drawerTileViews[PKG_OPENSTATION_APPS]
            } else {
                screen1TileViews[pkg] ?: screen1TileViews[PKG_OPENSTATION_APPS]
            }
        }
        findView()?.requestFocus()
        handler.post {
            if (!appContextOpen && !appInfoOpen) {
                findView()?.requestFocus()
            }
        }
    }

    private fun startReorderMode(app: AppEntry) {
        if (app.pkg == PKG_OPENSTATION_APPS) {
            Toast.makeText(this, "OPENSTATION APPS всегда закреплён первым", Toast.LENGTH_SHORT).show()
            return
        }
        reorderingPkg = app.pkg
        loadAndRenderApps()
        focusAppTileByPkg(app.pkg)
        Toast.makeText(this, "Перемещение: стрелки сдвинуть · OK сохранить", Toast.LENGTH_SHORT).show()
    }

    private fun moveReorderingApp(delta: Int) {
        val targetPkg = reorderingPkg ?: return
        val movable = activeAppEntries.drop(1).map { it.pkg }.toMutableList()
        val idx = movable.indexOf(targetPkg)
        if (idx < 0 || movable.size < 2) return
        val newIdx = (idx + delta).coerceIn(0, movable.lastIndex)
        if (newIdx == idx) return

        val moving = movable.removeAt(idx)
        movable.add(newIdx, moving)
        saveAppOrder(movable)
        loadAndRenderApps()
        handler.post {
            val tile = if (::appsDrawerOverlay.isInitialized && appsDrawerOverlay.visibility == View.VISIBLE) {
                drawerTileViews[targetPkg]
            } else {
                screen1TileViews[targetPkg]
            }
            tile?.requestFocus()
            tile?.parent?.requestChildFocus(tile, tile)
        }
    }

    private fun finishReorderMode() {
        val pkg = reorderingPkg ?: return
        reorderingPkg = null
        loadAndRenderApps()
        focusAppTileByPkg(pkg)
        Toast.makeText(this, "Расположение сохранено", Toast.LENGTH_SHORT).show()
    }

    private fun hideAppPackage(app: AppEntry) {
        if (app.pkg == PKG_OPENSTATION_APPS) {
            Toast.makeText(this, "Нельзя скрыть OPENSTATION APPS", Toast.LENGTH_SHORT).show()
            return
        }
        val hidden = getHiddenAppPackages()
        hidden.add(app.pkg)
        saveHiddenAppPackages(hidden)
        loadAndRenderApps()
        focusAppTileByPkg(PKG_OPENSTATION_APPS)
        Toast.makeText(this, "«${app.label}» скрыто (вернуть можно через удерж. OK на главном экране)", Toast.LENGTH_SHORT).show()
    }

    private fun unhideAppPackage(pkg: String) {
        val hidden = getHiddenAppPackages()
        hidden.remove(pkg)
        saveHiddenAppPackages(hidden)
        loadAndRenderApps()
    }

    private fun uninstallAppEntry(app: AppEntry) {
        if (app.isVirtual) return
        Toast.makeText(this, "Удаление ${app.label}...", Toast.LENGTH_SHORT).show()
        MarketInstaller.uninstallPackage(this, app.pkg, MarketPrefs.isPreferDaemonRoot(this)) { ok, msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (ok) {
                    loadAndRenderApps()
                    focusAppTileByPkg(PKG_OPENSTATION_APPS)
                }
            }
        }
    }

    private fun showHiddenAppsMenu() {
        if (!::appInfoOverlay.isInitialized) return
        appInfoOverlay.removeAllViews()

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#A6030711"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        appInfoOverlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 22f,
                baseTint = Color.parseColor("#D8080D18"),
                normalStroke = Color.parseColor("#28FFFFFF"),
                refractStrength = 1.05f
            )
            elevation = dp(34).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        val tvT = TextView(this).apply {
            text = "СКРЫТЫЕ ПРИЛОЖЕНИЯ"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvS = TextView(this).apply {
            text = "Нажмите на приложение, чтобы вернуть его в список"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(10))
        }
        panel.addView(tvT)
        panel.addView(tvS)

        val hidden = getHiddenAppPackages().toList().sorted()
        var firstFocus: View? = null
        val pm = packageManager
        for (pkg in hidden) {
            val lbl = when (pkg) {
                PKG_YANDEX_MUSIC -> "Яндекс Музыка"
                else -> try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
            }
            val row = makeTextIconPill(R.drawable.ic_yaos_apps, "Вернуть: $lbl", gold = false) {
                unhideAppPackage(pkg)
                Toast.makeText(this, "«$lbl» возвращено в список", Toast.LENGTH_SHORT).show()
                closeAppInfoModal()
                if (::homeCustomOverlay.isInitialized && homeCustomOverlay.visibility == View.VISIBLE) {
                    showHomeCustomizationMenu()
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                    bottomMargin = dp(6)
                }
            }
            if (firstFocus == null) firstFocus = row
            panel.addView(row)
        }

        val btnClose = makeTextIconPill(R.drawable.ic_play, "Закрыть", gold = true) {
            closeAppInfoModal()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                gravity = Gravity.END
                topMargin = dp(8)
            }
        }
        panel.addView(btnClose)
        appInfoOverlay.addView(panel)
        appInfoOverlay.tag = null
        appInfoOpen = true
        appInfoOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        appInfoOverlay.alpha = 1f
        (firstFocus ?: btnClose).requestFocus()
        handler.post { if (appInfoOpen) (firstFocus ?: btnClose).requestFocus() }
    }

    private fun showAppInfoModal(app: AppEntry) {
        if (!::appInfoOverlay.isInitialized) return
        appInfoOverlay.removeAllViews()
        appInfoOverlay.tag = app.pkg

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#A6030711"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        appInfoOverlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 22f,
                baseTint = Color.parseColor("#D8080D18"),
                normalStroke = Color.parseColor("#28FFFFFF"),
                refractStrength = 1.05f
            )
            elevation = dp(34).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val iv = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvPkg = TextView(this).apply {
            text = if (app.isVirtual) "Встроенный модуль OpenStation Max" else app.pkg
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11.5f
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvPkg)
        topRow.addView(iv)
        topRow.addView(titleCol)
        panel.addView(topRow)

        val details = ArrayList<Pair<String, String>>()
        if (app.pkg == PKG_OPENSTATION_APPS) {
            details.add("Модуль" to "OPENSTATION APPS (Каталог ТВ и установка APK)")
            details.add("Версия" to fwVer)
            details.add("Позиция" to "Закреплён #1 в списке приложений")
        } else if (app.pkg == PKG_YANDEX_MUSIC) {
            details.add("Модуль" to "Нативный Hi-Fi плеер Яндекс Музыки")
            details.add("Версия" to fwVer)
            details.add("Режим" to "Винил · Моя волна · DSP 10-band")
        } else {
            try {
                val pi = packageManager.getPackageInfo(app.pkg, 0)
                val ai = packageManager.getApplicationInfo(app.pkg, 0)
                val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val apkMb = try {
                    val f = java.io.File(ai.sourceDir)
                    if (f.exists()) String.format(Locale.US, "%.1f МБ", f.length() / (1024f * 1024f)) else "—"
                } catch (_: Throwable) { "—" }
                details.add("Версия" to "${pi.versionName ?: "—"} (${pi.longVersionCode})")
                details.add("Тип" to if (isSys) "Системный пакет" else "Установленное APK")
                details.add("Формат" to if (app.leanback) "Android TV (Leanback)" else "Обычное Android")
                details.add("Target SDK" to "API ${ai.targetSdkVersion}")
                details.add("Размер APK" to apkMb)
            } catch (_: Throwable) {
                details.add("Пакет" to app.pkg)
            }
        }

        for ((k, v) in details) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val tk = TextView(this).apply {
                text = k
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = TextView(this).apply {
                text = v
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tk)
            row.addView(tv)
            panel.addView(row)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        if (!app.isVirtual) {
            val btnSys = makeTextIconPill(R.drawable.ic_settings, "В системе", gold = false) {
                closeAppInfoModal()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.pkg}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Throwable) {}
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                    marginEnd = dp(8)
                }
            }
            btnRow.addView(btnSys)
        }
        val btnClose = makeTextIconPill(R.drawable.ic_play, "Закрыть", gold = true) {
            closeAppInfoModal()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        }
        btnRow.addView(btnClose)
        panel.addView(btnRow)

        appInfoOverlay.addView(panel)
        appInfoOpen = true
        appInfoOverlay.animate().cancel()
        appInfoOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        appInfoOverlay.alpha = 0f
        panel.scaleX = 0.93f
        panel.scaleY = 0.93f
        appInfoOverlay.animate().alpha(1f).setDuration(170L).setInterpolator(easeOutCubic).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(190L).setInterpolator(easeOutCubic).start()
        btnClose.requestFocus()
        handler.post { if (appInfoOpen) btnClose.requestFocus() }
    }

    private fun closeAppInfoModal(immediate: Boolean = false) {
        if (!::appInfoOverlay.isInitialized) return
        if (!appInfoOpen && appInfoOverlay.visibility != View.VISIBLE) return
        val prevPkg = appInfoOverlay.tag as? String
        appInfoOpen = false
        syncLayerFocusability()
        fun restoreFocusAfterInfo() {
            if (homeCustomOpen) {
                homeCustomOverlay.requestFocus(View.FOCUS_DOWN)
            } else if (prevPkg != null && (appsDrawerOpen || currentPage == 1)) {
                focusAppTileByPkg(prevPkg)
            }
        }
        if (immediate) {
            appInfoOverlay.animate().cancel()
            appInfoOverlay.visibility = View.GONE
            appInfoOverlay.alpha = 0f
            appInfoOverlay.removeAllViews()
            restoreFocusAfterInfo()
            return
        }
        restoreFocusAfterInfo()
        appInfoOverlay.animate().cancel()
        appInfoOverlay.animate()
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!appInfoOpen) {
                    appInfoOverlay.visibility = View.GONE
                    appInfoOverlay.removeAllViews()
                    syncLayerFocusability()
                    restoreFocusAfterInfo()
                }
            }
            .start()
    }

    private fun syncLayerFocusability() {
        val infoActive = appInfoOpen
        val ctxActive = appContextOpen
        val customActive = homeCustomOpen
        val drawerActive = appsDrawerOpen
        val anyOverlay = infoActive || ctxActive || customActive || drawerActive

        if (::shellCol.isInitialized) {
            shellCol.descendantFocusability =
                if (anyOverlay) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        if (::bottomDockBar.isInitialized) {
            bottomDockBar.descendantFocusability =
                if (anyOverlay || currentPage == 0) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        if (::appsDrawerOverlay.isInitialized) {
            appsDrawerOverlay.descendantFocusability =
                if (drawerActive && !infoActive && !ctxActive && !customActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::homeCustomOverlay.isInitialized) {
            homeCustomOverlay.descendantFocusability =
                if (customActive && !infoActive && !ctxActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::appContextOverlay.isInitialized) {
            appContextOverlay.descendantFocusability =
                if (ctxActive && !infoActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::appInfoOverlay.isInitialized) {
            appInfoOverlay.descendantFocusability =
                if (infoActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
    }

    private fun closeAllOverlays(immediate: Boolean = false) {
        closeAppInfoModal(immediate = immediate)
        closeAnchoredAppMenu(immediate = immediate, restoreFocus = false)
        closeHomeCustomizationMenu(immediate = immediate)
        closeAppsDrawer(immediate = immediate)
        syncLayerFocusability()
    }

    private fun handleOverlayBackPressed(): Boolean {
        if (appInfoOpen || (::appInfoOverlay.isInitialized && appInfoOverlay.visibility == View.VISIBLE)) {
            closeAppInfoModal()
            return true
        }
        if (appContextOpen) {
            closeAnchoredAppMenu()
            return true
        }
        if (homeCustomOpen) {
            closeHomeCustomizationMenu()
            return true
        }
        if (reorderingPkg != null) {
            finishReorderMode()
            return true
        }
        if (appsDrawerOpen) {
            closeAppsDrawer()
            return true
        }
        return false
    }

    private fun shouldArmOkLongPress(focused: View?): Boolean {
        if (reorderingPkg != null) return false
        if (appInfoOpen || appContextOpen || homeCustomOpen) return false
        if (appsDrawerOpen) {
            return findAppForView(focused) != null
        }
        if (currentPage == 0) {
            if (::eqWidgetController.isInitialized && eqWidgetController.isAdjusting()) return false
            return true
        }
        if (currentPage == 1 && findAppForView(focused) != null) return true
        return false
    }

    private fun handleOkLongPress(anchor: View?) {
        anchor?.isPressed = false
        anchor?.refreshDrawableState()
        if (appsDrawerOpen) {
            val app = findAppForView(anchor ?: currentFocus) ?: return
            showAnchoredAppMenu(app, anchor ?: currentFocus ?: return)
            return
        }
        if (currentPage == 1) {
            val app = findAppForView(anchor ?: currentFocus)
            if (app != null) {
                showAnchoredAppMenu(app, anchor ?: currentFocus ?: return)
                return
            }
        }
        if (currentPage == 0 && !homeCustomOpen) {
            showHomeCustomizationMenu()
        }
    }

    private fun findAppForView(v: View?): AppEntry? {
        val pkg = v?.tag as? String ?: return null
        return activeAppEntries.firstOrNull { it.pkg == pkg }
    }

    private fun focusFirstVisibleHomeWidget(): Boolean {
        if (::eqWidgetController.isInitialized && eqWidgetController.rootCard.visibility == View.VISIBLE) {
            eqWidgetController.focusDefault()
            return true
        }
        if (::wBtCard.isInitialized && wBtCard.visibility == View.VISIBLE) {
            wBtCard.requestFocus()
            return true
        }
        if (::wWeatherCard.isInitialized && wWeatherCard.visibility == View.VISIBLE) {
            wWeatherCard.requestFocus()
            return true
        }
        if (::wRemoteCard.isInitialized && wRemoteCard.visibility == View.VISIBLE) {
            wRemoteCard.requestFocus()
            return true
        }
        return false
    }

    private fun nudgeView(v: View?, dx: Float, dy: Float) {
        if (v == null) return
        v.animate().cancel()
        v.animate()
            .translationXBy(dx)
            .translationYBy(dy)
            .setDuration(90L)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                v.animate()
                    .translationXBy(-dx)
                    .translationYBy(-dy)
                    .setDuration(160L)
                    .setInterpolator(easeOutCubic)
                    .start()
            }
            .start()
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
