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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.EditText
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
    private val miscRepairHelper by lazy { MiscRepairDialogHelper(this, handler) }
    private val easeOutCubic get() = MotionPrefs.easeOutCurve
    private val focusSpring get() = MotionPrefs.focusCurve

    // Root stage & Hardware OpenGL ES 3.0 Spatial Liquid Glass Compositor
    private lateinit var rootFrame: FrameLayout
    private lateinit var ivBgFallback: ImageView
    private lateinit var bgScrimFallback: View
    private lateinit var spatialBackdrop: SpatialGlassBackdropView
    private lateinit var shellCol: LinearLayout
    private lateinit var topBar: FrameLayout
    private lateinit var pagesStage: FrameLayout
    private lateinit var page0View: FrameLayout
    private lateinit var pageMusicView: FrameLayout
    private lateinit var btnControlTrigger: LinearLayout
    private lateinit var controlStatusDotBg: GradientDrawable

    // GPU Lens cleanup pools to prevent leaked View references across dynamic rebuilds
    private val staticLensCleanups = ArrayList<() -> Unit>()
    private val drawerLensCleanups = ArrayList<() -> Unit>()
    private val customOverlayLensCleanups = ArrayList<() -> Unit>()
    private val ctxOverlayLensCleanups = ArrayList<() -> Unit>()
    private val infoOverlayLensCleanups = ArrayList<() -> Unit>()

    // Dynamic GPU blur/dim scene animation
    private var sceneBlurFactor = 0f
    private var sceneDimFactor = 0.08f
    private var sceneCoverAlpha = 0f
    private var backdropBlurAnimator: ValueAnimator? = null
    @Volatile private var glBackdropReady = false
    @Volatile private var glBackdropFailed = false

    // Persistent Alice Sphere + On-Demand Hero Response Glass Pill
    private lateinit var heroOrbView: AliceOrbView
    private lateinit var btnHeroOrbClick: FrameLayout
    private lateinit var heroReplyCard: LinearLayout
    private lateinit var heroReplyHeader: LinearLayout
    private lateinit var heroStateDotBg: GradientDrawable
    private lateinit var tvHeroState: TextView
    private lateinit var tvHeroUser: TextView
    private lateinit var tvHeroReply: TextView
    private lateinit var heroNavHintsOverlay: FrameLayout
    private lateinit var tvHeroOkHint: TextView
    private val appAccentCache = HashMap<String, Int>()

    // Screen 0: Optional Modular Spatial Glass Home Widgets (OFF by default)
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

    // Overlays: Apps Grid (DPAD_DOWN), Control Center (DPAD_RIGHT/MENU), Modals & App Context Menu
    private lateinit var appsDrawerOverlay: FrameLayout
    private lateinit var drawerScrollView: ScrollView
    private lateinit var drawerScrollContent: LinearLayout
    private lateinit var drawerAppsGrid: GridLayout
    private lateinit var tvDrawerStatusPill: TextView
    private lateinit var controlCenter: SpatialControlCenter
    private lateinit var musicPlayerSheet: SpatialMusicPlayerSheet
    private lateinit var screensaverOverlay: SpatialScreensaverOverlay
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
    private var reorderingPkg: String? = null
    private var okLongPressFired = false
    private var okLongPressAnchor: View? = null
    private val okLongPressRunnable = Runnable {
        okLongPressFired = true
        handleOkLongPress(okLongPressAnchor)
    }

    // Spatial horizontal stage: 0 = Hero Sphere (center), 1 = Immersive Music (left: screenPos = -1f)
    private var currentPage = 0
    private var currentScreenPos = 0f
    private var pageAnimator: ValueAnimator? = null
    private lateinit var swipeDetector: GestureDetector

    // Top bar status & clock
    private lateinit var tvTopClock: TextView
    private lateinit var tvTopDate: TextView
    private lateinit var tvTopWeather: TextView
    private lateinit var tvStatusLine: TextView
    private lateinit var weatherOverlay: WeatherOverlayView

    // OTA & Dialog state (Control Center & Modals)
    @Volatile private var otaLatestJson: JSONObject? = null
    private var otaModalOpen = false
    private var otaModalStatusTv: TextView? = null
    private var otaModalChangelogTv: TextView? = null
    private var otaModalInstallBtn: TextView? = null
    private var assistantNameState = "Алиса"
    private var assistantVoiceState = "Aoede"
    private var assistantPromptState = ""
    private var assistantVadSilenceMsState = 400
    private var dnsModeState = ""
    private var dnsHostState = ""
    private var dnsAutoFailoverState = true
    private var dnsFailoverSummaryState = ""
    private var hwEqSupportedState = false
    private var hwEqProfileState = "original"
    private var hwEqDrcBypassedState = false
    private var lastTimersJson: JSONArray? = null
    private var lastAlarmsJson: JSONArray? = null
    private var lastRingingAlarmJson: JSONObject? = null
    private var lastTimersFetchedMs: Long = 0L
    private var timersScreenRef: TimersScreen? = null

    // Immersive Now Playing Screen (DPAD_LEFT from Home)
    private lateinit var ivHomeCover: ImageView
    private lateinit var coverGlassFrame: FrameLayout
    private lateinit var tvHomeSourceBadge: TextView
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
    private var lastHomeCoverUrl = ""
    private var latestCoverBmp: Bitmap? = null
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
    private var cecState = false
    @Volatile private var daemonOnline = false
    @Volatile private var cachedAppsJson = ""
    private var setupCompletedState: Boolean? = null
    private var wizardAutoOpenedThisRun = false

    // Cached formatters & diff keys to eliminate redundant main-thread allocations/relayouts
    private val ruLocale = Locale("ru")
    private val timeFmt by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val shortDateFmt by lazy { SimpleDateFormat("EEE, d MMM", ruLocale) }
    private val clockDate = Date()
    private var lastClockStr = ""
    private var lastShortDateStr = ""
    @Volatile private var cachedLocalIp = "127.0.0.1"
    @Volatile private var lastIpDetectMs = 0L
    private var lastIpLineStr = ""
    private var lastStatusLineText = ""
    private var lastOrbUiKey = ""
    private var lastPillsKey = ""
    private var lastTrackLiked: Boolean? = null
    private var lastPlayPauseIconKey = ""

    private val orbStateListener = LiveOrbState.StateListener {
        renderOrbAssistantUi()
        if (::eqWidgetController.isInitialized && eqWidgetController.rootCard.visibility == View.VISIBLE && currentPage == 0 &&
            (LiveOrbState.musicPlaying || LiveOrbState.assistantState != "idle")) {
            handler.removeCallbacks(widgetEqTickRunnable)
            handler.post(widgetEqTickRunnable)
        }
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
            val ccOpen = ::controlCenter.isInitialized && controlCenter.isOpen
            val active = (assistantState != "idle") || (LiveOrbState.assistantState != "idle") ||
                latestMusicSnap.playing || ccOpen || otaModalOpen
            handler.postDelayed(this, if (active) 2000L else 5000L)
        }
    }

    private val widgetEqTickRunnable = object : Runnable {
        override fun run() {
            if (::eqWidgetController.isInitialized && eqWidgetController.rootCard.visibility == View.VISIBLE && currentPage == 0) {
                val live = LiveOrbState.musicPlaying || LiveOrbState.assistantState != "idle"
                eqWidgetController.updateLiveSpectrum(LiveOrbState.rawBands, live)
                if (live || eqWidgetController.hasActiveSpectrumGlow()) {
                    handler.postDelayed(this, if (live) 45L else 120L)
                }
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
                    if (dx > 0) {
                        if (::controlCenter.isInitialized && controlCenter.isOpen) {
                            controlCenter.close()
                        } else if (currentPage == 0) {
                            setPage(1, animate = true)
                        }
                        return true
                    } else {
                        if (currentPage == 1) {
                            setPage(0, animate = true)
                        } else if (currentPage == 0 && !appsDrawerOpen && !homeCustomOpen && ::controlCenter.isInitialized && !controlCenter.isOpen) {
                            openControlCenter()
                        }
                        return true
                    }
                } else if (abs(dy) > dp(72) && abs(dy) > abs(dx) * 1.35f && abs(velocityY) > 250f) {
                    if (dy < 0 && currentPage == 0 && !appsDrawerOpen && (!::controlCenter.isInitialized || !controlCenter.isOpen)) {
                        openAppsDrawer()
                        return true
                    } else if (dy > 0 && appsDrawerOpen) {
                        closeAppsDrawer()
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
                    if (currentPage == 0 && !isAnyOverlayActive()) {
                        isProgrammaticPageFocus = true
                        try { btnHeroOrbClick.requestFocus() } finally { isProgrammaticPageFocus = false }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AliceOrbFrames.preloadAsync(this)
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.motionRateScale = MotionPrefs.glSmoothFactor(this)
            spatialBackdrop.onHostResume()
        }
        AnimatedSpriteHelper.syncAll(windowActive = true)
        if (::screensaverOverlay.isInitialized) {
            screensaverOverlay.onHostResume()
        }
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
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(widgetEqTickRunnable)
        handler.post(clockRunnable)
        handler.post(pollRunnable)
        handler.post(widgetEqTickRunnable)
        if (!isAnyOverlayActive() && currentPage == 0 && (currentFocus == null || currentFocus === btnControlTrigger)) {
            btnHeroOrbClick.post {
                if (!isAnyOverlayActive() && currentPage == 0) focusPageControlDirect(0)
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(okLongPressRunnable)
        LiveOrbState.removeListener(orbStateListener)
        StationAudioService.setLauncherForegroundState(false)
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(widgetEqTickRunnable)
        if (::screensaverOverlay.isInitialized) {
            screensaverOverlay.onHostPause()
        }
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.onHostPause()
        }
        AnimatedSpriteHelper.syncAll(windowActive = false)
        super.onPause()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        handler.removeCallbacks(okLongPressRunnable)
        LiveOrbState.removeListener(orbStateListener)
        pageAnimator?.cancel()
        backdropBlurAnimator?.cancel()
        handler.removeCallbacks(widgetEqTickRunnable)
        handler.removeCallbacks(otaDialogPollRunnable)
        otaModalOpen = false
        miscRepairHelper.dismissIfShowing()
        if (::screensaverOverlay.isInitialized) screensaverOverlay.release()
        if (::musicPlayerSheet.isInitialized) musicPlayerSheet.release()
        if (::controlCenter.isInitialized) controlCenter.release()
        clearLensCleanups(staticLensCleanups)
        clearLensCleanups(drawerLensCleanups)
        clearLensCleanups(customOverlayLensCleanups)
        clearLensCleanups(ctxOverlayLensCleanups)
        clearLensCleanups(infoOverlayLensCleanups)
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.releaseGl()
        }
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(musicStateReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(clockReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.motionRateScale = MotionPrefs.glSmoothFactor(this)
            spatialBackdrop.onHostResume()
        }
        AnimatedSpriteHelper.syncAll(windowActive = true)
        if (::screensaverOverlay.isInitialized) {
            screensaverOverlay.onHostResume()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::screensaverOverlay.isInitialized) {
            screensaverOverlay.onHostPause()
        }
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.onHostPause()
        }
        LiquidGlassKit.clearStagePhotoCache()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LiquidGlassKit.trimCachesForPressure(level, allowReleaseOrb = !hasWindowFocus())
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
                    if (currentPage == 0 && !isAnyOverlayActive()) {
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
            // 1. Screensaver & Topmost modals / App Reordering / Spatial Player Sheet interception
            if (::screensaverOverlay.isInitialized && screensaverOverlay.isOpen) {
                if (screensaverOverlay.handleKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }
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
            if (::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen) {
                if (musicPlayerSheet.handleKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }
            if (::controlCenter.isInitialized && controlCenter.isOpen) {
                if (controlCenter.handleKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }
            if (reorderingPkg != null) {
                val cols = if (::drawerAppsGrid.isInitialized) maxOf(1, drawerAppsGrid.columnCount) else 5
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveReorderingApp(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveReorderingApp(+1); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { moveReorderingApp(-cols); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveReorderingApp(+cols); return true }
                    KeyEvent.KEYCODE_BACK -> { finishReorderMode(); return true }
                }
            }
            if (appsDrawerOpen) {
                val cur = currentFocus
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        closeAppsDrawer()
                        return true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        val app = findAppForView(cur)
                        if (app != null && cur != null) {
                            showAnchoredAppMenu(app, cur)
                        } else {
                            openControlCenter()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val cols = if (::drawerAppsGrid.isInitialized) maxOf(1, drawerAppsGrid.columnCount) else 5
                        val idx = activeAppEntries.indexOfFirst { drawerTileViews[it.pkg] === cur }
                        if (idx in 0 until cols && (!::drawerScrollView.isInitialized || drawerScrollView.scrollY <= dp(8))) {
                            closeAppsDrawer()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (::drawerAppsGrid.isInitialized && isViewInside(cur, drawerAppsGrid)) {
                            val scroll = if (::drawerScrollView.isInitialized) drawerScrollView else null
                            if (focusGridDownPartialRow(drawerAppsGrid, scroll, cur)) return true
                            val downNext = FocusFinder.getInstance().findNextFocus(scroll ?: drawerAppsGrid, cur, View.FOCUS_DOWN)
                            if (downNext == null || downNext === cur) {
                                nudgeView(cur, dx = 0f, dy = -dp(6).toFloat())
                                return true
                            }
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            // 2. Spatial Star Navigation (Home = 0 center, Music = 1 left, Apps = down, Control Center = right)
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (currentPage != 0) {
                        switchToPageContentFocused(0)
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    openControlCenter()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (currentPage == 0) openControlCenter()
                    else switchToPageContentFocused(0)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (currentPage == 0) switchToPageContentFocused(1)
                    return true
                }
                else -> {
                    val cur = currentFocus
                    if (::tvTopWeather.isInitialized && cur === tvTopWeather) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (::btnControlTrigger.isInitialized) btnControlTrigger.requestFocus()
                                return true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { focusPageControlDirect(currentPage); return true }
                            KeyEvent.KEYCODE_DPAD_LEFT -> { nudgeView(tvTopWeather, dx = dp(6).toFloat(), dy = 0f); return true }
                            KeyEvent.KEYCODE_DPAD_UP -> { nudgeView(tvTopWeather, dx = 0f, dy = dp(4).toFloat()); return true }
                        }
                    }
                    if (::btnControlTrigger.isInitialized && cur === btnControlTrigger) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (::tvTopWeather.isInitialized && tvTopWeather.visibility == View.VISIBLE) {
                                    tvTopWeather.requestFocus()
                                } else {
                                    focusPageControlDirect(currentPage)
                                }
                                return true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { focusPageControlDirect(currentPage); return true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { openControlCenter(); return true }
                            KeyEvent.KEYCODE_DPAD_UP -> { nudgeView(btnControlTrigger, dx = 0f, dy = dp(4).toFloat()); return true }
                        }
                    }

                    if (currentPage == 0 && ::eqWidgetController.isInitialized && eqWidgetController.handleKeyEvent(event, cur)) {
                        return true
                    }
                    val eqOwnsCur = ::eqWidgetController.isInitialized && eqWidgetController.isOwningFocus(cur)
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (currentPage == 0) {
                                when {
                                    cur === wWeatherCard -> {
                                        if (isRemoteVisible()) wRemoteCard.requestFocus() else btnHeroOrbClick.requestFocus()
                                        return true
                                    }
                                    eqOwnsCur -> {
                                        if (isBtVisible()) wBtCard.requestFocus() else openAppsDrawer()
                                        return true
                                    }
                                    cur === wBtCard || cur === wRemoteCard -> {
                                        btnHeroOrbClick.requestFocus()
                                        return true
                                    }
                                    else -> {
                                        openAppsDrawer()
                                        return true
                                    }
                                }
                            } else if (currentPage == 1) {
                                openMusicPlayerSheet()
                                return true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (currentPage == 0) {
                                when {
                                    cur === wBtCard -> {
                                        if (isEqVisible()) eqWidgetController.focusDefault() else openScreensaver()
                                        return true
                                    }
                                    cur === wRemoteCard -> {
                                        if (isWeatherVisible()) wWeatherCard.requestFocus() else openScreensaver()
                                        return true
                                    }
                                    cur === wWeatherCard || eqOwnsCur || cur === btnHeroOrbClick || cur == null -> {
                                        openScreensaver()
                                        return true
                                    }
                                }
                            } else if (currentPage == 1) {
                                openScreensaver()
                                return true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (currentPage == 0) {
                                when {
                                    (::wWeatherCard.isInitialized && cur === wWeatherCard) ||
                                    (::wRemoteCard.isInitialized && cur === wRemoteCard) -> {
                                        btnHeroOrbClick.requestFocus()
                                        return true
                                    }
                                    cur === btnHeroOrbClick -> {
                                        if (!focusFirstLeftWidget()) {
                                            switchToPageContentFocused(1)
                                        }
                                        return true
                                    }
                                    else -> {
                                        switchToPageContentFocused(1)
                                        return true
                                    }
                                }
                            } else if (currentPage == 1) {
                                val next = if (cur == null || (::coverGlassFrame.isInitialized && cur === coverGlassFrame)) {
                                    null
                                } else {
                                    FocusFinder.getInstance().findNextFocus(pageMusicView, cur, View.FOCUS_LEFT)
                                }
                                if (next == null || next === cur) {
                                    nudgeView(cur ?: pageMusicView, dx = dp(10).toFloat(), dy = 0f)
                                    return true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (currentPage == 0) {
                                when {
                                    eqOwnsCur || (::wBtCard.isInitialized && cur === wBtCard) -> {
                                        btnHeroOrbClick.requestFocus()
                                        return true
                                    }
                                    cur === btnHeroOrbClick -> {
                                        if (!focusFirstRightWidget()) {
                                            openControlCenter()
                                        }
                                        return true
                                    }
                                    else -> {
                                        openControlCenter()
                                        return true
                                    }
                                }
                            } else if (currentPage == 1) {
                                val next = if (cur == null || (::btnHomeLike.isInitialized && cur === btnHomeLike.root)) {
                                    null
                                } else {
                                    FocusFinder.getInstance().findNextFocus(pageMusicView, cur, View.FOCUS_RIGHT)
                                }
                                if (next == null || next === cur) {
                                    switchToPageContentFocused(0)
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

    private fun focusTopBarOrNudge(cur: View?) {
        when {
            ::btnControlTrigger.isInitialized -> btnControlTrigger.requestFocus()
            ::tvTopWeather.isInitialized && tvTopWeather.visibility == View.VISIBLE -> tvTopWeather.requestFocus()
            else -> nudgeView(cur, dx = 0f, dy = dp(4).toFloat())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO) {
            loadAndRenderApps()
            Toast.makeText(this, "Список приложений обновлён", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun bindGlassLens(
        cleanupPool: MutableList<() -> Unit>,
        view: View,
        spec: SpatialLensSpec = SpatialLensSpec(),
        fallbackTint: Int = Color.parseColor("#660C1220")
    ) {
        if (::spatialBackdrop.isInitialized && !glBackdropFailed) {
            cleanupPool.add(spatialBackdrop.registerLens(view, spec))
        } else {
            LiquidGlassKit.applyGlass(
                view,
                radiusDp = spec.radiusDp,
                baseTint = fallbackTint,
                focusTint = Color.parseColor("#88162338"),
                normalStroke = Color.parseColor("#20FFFFFF"),
                focusStroke = Color.parseColor("#66BAE6FD"),
                refractStrength = 0.9f
            )
        }
    }

    private fun clearLensCleanups(pool: MutableList<() -> Unit>) {
        pool.forEach { it() }
        pool.clear()
    }

    private fun isAnyOverlayActive(): Boolean =
        appsDrawerOpen || homeCustomOpen || appContextOpen || appInfoOpen ||
            (::controlCenter.isInitialized && controlCenter.isOpen) ||
            (::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen) ||
            (::screensaverOverlay.isInitialized && screensaverOverlay.isOpen)

    private fun computeHeroOrbCenterX(w: Float, screenPos: Float, ccOpen: Boolean): Float {
        val pageOffset = (0f - screenPos) * w
        if (!ccOpen) return w * 0.5f + pageOffset
        val freeLeftW = maxOf(dp(260).toFloat(), w - dp(438).toFloat())
        return freeLeftW * 0.5f + pageOffset
    }

    private fun syncBackdropBlurForUiState(animate: Boolean = true) {
        val ccOpen = ::controlCenter.isInitialized && controlCenter.isOpen
        val musicSheetActive = ::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen
        val saverActive = ::screensaverOverlay.isInitialized && screensaverOverlay.isOpen
        AnimatedSpriteHelper.syncAll(windowActive = !saverActive)
        val otherOverlayActive = appsDrawerOpen || homeCustomOpen || appContextOpen || appInfoOpen || musicSheetActive || saverActive
        val overlayActive = ccOpen || otherOverlayActive
        val topTargetAlpha = if (otherOverlayActive) 0f else 1f
        val ctrlTriggerTargetAlpha = if (overlayActive) 0f else 1f
        val orbTargetAlpha = if (otherOverlayActive || currentPage != 0) 0f else 1f
        val orbTargetScale = when {
            otherOverlayActive -> 0.88f
            ccOpen -> 0.88f
            ::btnHeroOrbClick.isInitialized && btnHeroOrbClick.hasFocus() -> 1.045f
            else -> 1f
        }
        val replyBusy = startupShowActive || LiveOrbState.assistantState != "idle" ||
            LiveOrbState.bestDisplayText().isNotEmpty() || LiveOrbState.liveUserText.isNotBlank()
        val hintsTargetAlpha = if (!overlayActive && currentPage == 0 && !replyBusy) 1f else 0f

        if (::heroNavHintsOverlay.isInitialized) {
            heroNavHintsOverlay.animate().cancel()
            if (animate) {
                heroNavHintsOverlay.animate().alpha(hintsTargetAlpha)
                    .setDuration(MotionPrefs.dur(this, 360L)).setInterpolator(easeOutCubic).start()
            } else {
                heroNavHintsOverlay.alpha = hintsTargetAlpha
            }
        }

        if (::topBar.isInitialized) {
            topBar.animate().cancel()
            if (animate) {
                topBar.animate().alpha(topTargetAlpha)
                    .setDuration(MotionPrefs.dur(this, 400L)).setInterpolator(easeOutCubic).start()
            } else {
                topBar.alpha = topTargetAlpha
            }
        }
        if (::btnControlTrigger.isInitialized) {
            btnControlTrigger.animate().cancel()
            if (animate) {
                btnControlTrigger.animate().alpha(ctrlTriggerTargetAlpha)
                    .setDuration(MotionPrefs.dur(this, 380L)).setInterpolator(easeOutCubic).start()
            } else {
                btnControlTrigger.alpha = ctrlTriggerTargetAlpha
            }
        }
        if (::pagesStage.isInitialized) {
            val stageTargetAlpha = if (otherOverlayActive) 0f else 1f
            val stageTargetScale = if (otherOverlayActive) 0.96f else 1f
            val wantVisible = !otherOverlayActive
            if (wantVisible && pagesStage.visibility != View.VISIBLE) {
                pagesStage.visibility = View.VISIBLE
            }
            if (animate) {
                val alreadySettled = wantVisible == (pagesStage.visibility == View.VISIBLE) &&
                    kotlin.math.abs(pagesStage.alpha - stageTargetAlpha) < 0.01f &&
                    kotlin.math.abs(pagesStage.scaleX - stageTargetScale) < 0.005f
                if (!alreadySettled) {
                    pagesStage.animate().cancel()
                    pagesStage.animate()
                        .alpha(stageTargetAlpha)
                        .scaleX(stageTargetScale)
                        .scaleY(stageTargetScale)
                        .setDuration(MotionPrefs.dur(this, if (wantVisible) 420L else 360L))
                        .setInterpolator(easeOutCubic)
                        .withEndAction {
                            if (pagesStage.alpha < 0.02f) {
                                pagesStage.visibility = View.INVISIBLE
                            }
                        }
                        .start()
                }
            } else {
                pagesStage.animate().cancel()
                pagesStage.alpha = stageTargetAlpha
                pagesStage.scaleX = stageTargetScale
                pagesStage.scaleY = stageTargetScale
                pagesStage.visibility = if (wantVisible) View.VISIBLE else View.INVISIBLE
            }
        }
        if (::heroOrbView.isInitialized && (overlayActive || currentPage == 0)) {
            val w = (if (::rootFrame.isInitialized && rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels).toFloat()
            val heroSize = dp(304).toFloat()
            val targetX = computeHeroOrbCenterX(w, currentScreenPos, ccOpen) - heroSize * 0.5f
            heroOrbView.animate().cancel()
            if (orbTargetAlpha > 0.02f) {
                heroOrbView.wakeAnimation()
            }
            if (animate) {
                heroOrbView.animate()
                    .alpha(orbTargetAlpha)
                    .scaleX(orbTargetScale)
                    .scaleY(orbTargetScale)
                    .translationX(targetX)
                    .setDuration(MotionPrefs.dur(this, 480L))
                    .setInterpolator(easeOutCubic)
                    .start()
            } else {
                heroOrbView.alpha = orbTargetAlpha
                heroOrbView.scaleX = orbTargetScale
                heroOrbView.scaleY = orbTargetScale
                heroOrbView.translationX = targetX
            }
        }

        if (!::spatialBackdrop.isInitialized) return
        val (targetBlur, targetDim) = when {
            saverActive -> 0.30f to 0.20f
            homeCustomOpen || appContextOpen || appInfoOpen -> 0.94f to 0.50f
            musicSheetActive -> 0.96f to 0.52f
            ccOpen -> 0.90f to 0.50f
            appsDrawerOpen -> 0.90f to 0.42f
            currentPage == 1 -> 0.76f to 0.36f
            else -> 0.0f to 0.06f
        }
        val targetCover = if ((currentPage == 1 || musicSheetActive) && !appsDrawerOpen && !ccOpen && !saverActive && latestCoverBmp != null) 0.90f else 0f
        sceneCoverAlpha = targetCover
        val st = LiveOrbState.assistantState
        val orbE = when (st) {
            "listening", "speaking" -> 1.15f
            "thinking", "connecting" -> 0.75f
            else -> if (latestMusicSnap.playing) 0.45f else 0.14f
        }
        val orbCol = when (st) {
            "listening" -> Color.parseColor("#2DD4BF")
            "thinking" -> Color.parseColor("#E8C686")
            "speaking" -> Color.parseColor("#C084FC")
            "error" -> Color.parseColor("#F87171")
            else -> Color.parseColor("#38BDF8")
        }

        if (!animate) {
            backdropBlurAnimator?.cancel()
            sceneBlurFactor = targetBlur
            sceneDimFactor = targetDim
            spatialBackdrop.setSceneState(sceneBlurFactor, sceneDimFactor, orbE, orbCol, sceneCoverAlpha)
            return
        }
        backdropBlurAnimator?.cancel()
        val startBlur = sceneBlurFactor
        val startDim = sceneDimFactor
        backdropBlurAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MotionPrefs.dur(this@MainActivity, 520L)
            interpolator = easeOutCubic
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                sceneBlurFactor = lerp(startBlur, targetBlur, t)
                sceneDimFactor = lerp(startDim, targetDim, t)
                spatialBackdrop.setSceneState(sceneBlurFactor, sceneDimFactor, orbE, orbCol, sceneCoverAlpha)
            }
            start()
        }
    }

    private fun openControlCenter() {
        if (!::controlCenter.isInitialized || controlCenter.isOpen) return
        if (appsDrawerOpen) closeAppsDrawer(immediate = true)
        updateControlCenterMusic()
        updateControlCenterState()
        controlCenter.open()
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = true)
    }

    private fun buildUi() {
        clearLensCleanups(staticLensCleanups)
        rootFrame = FrameLayout(this)

        // 0. Fallback Hardware Wallpaper Layer (hidden cleanly once GPU GLES3 compositor is active)
        ivBgFallback = ImageView(this).apply {
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
        bgScrimFallback = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#32040710"),
                    Color.TRANSPARENT,
                    Color.parseColor("#44040710")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(ivBgFallback)
        rootFrame.addView(bgScrimFallback)

        // 0b. GPU OpenGL ES 3.0 Dual-Kawase & SDF Optical Lens Compositor
        spatialBackdrop = SpatialGlassBackdropView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            LiquidGlassKit.sharpWallpaper?.let { setWallpaperBitmap(it) }
            onGlStatusListener = { ready, failed ->
                glBackdropReady = ready
                glBackdropFailed = failed
                handler.post {
                    if (ready && !failed) {
                        ivBgFallback.visibility = View.GONE
                        bgScrimFallback.visibility = View.GONE
                    } else {
                        ivBgFallback.visibility = View.VISIBLE
                        bgScrimFallback.visibility = View.VISIBLE
                    }
                }
            }
        }
        rootFrame.addView(spatialBackdrop)

        // Main vertical container: Minimal Header + Spatial Star Stage
        shellCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ===== 1. ULTRA-MINIMAL TOP HEADER (Borderless Clock Left · Single Glass Capsule Right) =====
        topBar = FrameLayout(this).apply {
            setPadding(dp(34), dp(10), dp(34), dp(4))
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        }

        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        tvTopClock = TextView(this).apply {
            text = "--:--"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 25f
            maxLines = 1
            includeFontPadding = false
            typeface = YandexFonts.displayMedium(this@MainActivity)
            letterSpacing = 0.02f
            setShadowLayer(10f, 0f, 2f, Color.parseColor("#66000000"))
        }
        tvTopDate = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#D8E2F0"))
            alpha = 0.78f
            textSize = 12.5f
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 1
            setShadowLayer(6f, 0f, 1f, Color.parseColor("#55000000"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
        }
        tvTopWeather = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 1
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(5), dp(12), dp(5))
            bindGlassLens(
                staticLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#20090E1B"),
                    focusTint = Color.parseColor("#4C182946"),
                    bevelDp = 9f,
                    refractDp = 7f,
                    chromaDp = 1.6f,
                    shadowDp = 10f,
                    rimAlpha = 0.24f,
                    focusRimAlpha = 0.82f,
                    blurMix = 0.85f
                )
            )
            visibility = View.GONE
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.045f else 1f).scaleY(if (hasFocus) 1.045f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 230L)).setInterpolator(focusSpring).start()
            }
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
                marginStart = dp(12)
            }
        }
        topLeft.addView(tvTopClock)
        topLeft.addView(tvTopDate)
        topLeft.addView(tvTopWeather)

        // Right: Non-focusable Ambient Status Capsule (tappable via pointer, never highlights on D-pad)
        btnControlTrigger = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(6), dp(14), dp(6))
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            bindGlassLens(
                staticLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#20090E1B"),
                    focusTint = Color.parseColor("#20090E1B"),
                    bevelDp = 10f,
                    refractDp = 8f,
                    chromaDp = 1.8f,
                    shadowDp = 10f,
                    rimAlpha = 0.26f,
                    focusRimAlpha = 0.26f,
                    blurMix = 0.85f
                )
            )
            setOnClickListener { openControlCenter() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }
        controlStatusDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#64748B"))
        }
        val statusDot = View(this).apply {
            background = controlStatusDotBg
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(8)
            }
        }
        tvStatusLine = TextView(this).apply {
            text = "Ожидание"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        btnControlTrigger.addView(statusDot)
        btnControlTrigger.addView(tvStatusLine)

        topBar.addView(topLeft)
        topBar.addView(btnControlTrigger)
        shellCol.addView(topBar)

        // ===== 2. SPATIAL HORIZONTAL STAGE (0 = Center Sphere · 1 = Left Music) =====
        pagesStage = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        page0View = buildScreen0Hero()
        pageMusicView = buildScreenMusic()

        pagesStage.addView(page0View)
        pagesStage.addView(pageMusicView)
        shellCol.addView(pagesStage)
        rootFrame.addView(shellCol)

        // ===== 3. PERSISTENT CENTER ALICE SPHERE =====
        heroOrbView = AliceOrbView(this).apply {
            isClickable = false
            isFocusable = false
            elevation = dp(18).toFloat()
            outlineProvider = null
            layoutParams = FrameLayout.LayoutParams(dp(304), dp(304))
        }
        rootFrame.addView(heroOrbView)

        rootFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPageVisuals(currentScreenPos)
        }

        // ===== 4. APPS OVERLAY (DPAD_DOWN) + CONTROL CENTER (DPAD_RIGHT) + MODALS =====
        appsDrawerOverlay = buildAppsDrawerOverlay().apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(25).toFloat()
        }
        rootFrame.addView(appsDrawerOverlay)

        controlCenter = SpatialControlCenter(
            this,
            spatialBackdrop,
            object : SpatialControlCenter.Callbacks {
                override fun onCycleRing() = cycleRingMode()
                override fun onToggleMic() = toggleMute()
                override fun onCycleRoute() = cycleOutputRoute()
                override fun onCycleEq() = cycleEqPreset()
                override fun onCycleVisualizer() = cycleVisualizerStyle()
                override fun onToggleCec() = toggleCec()
                override fun onOpenMusicScreen() {
                    controlCenter.close(immediate = false)
                    openMusicPlayerSheet()
                }
                override fun onMusicPlayPause() = sendMusicAction("PLAY_PAUSE")
                override fun onMusicNext() = sendMusicAction("NEXT")
                override fun onOpenPersona() {
                    controlCenter.close(immediate = true)
                    showAssistantPersonaModal()
                }
                override fun onOpenHardwareEq() {
                    controlCenter.close(immediate = true)
                    showHardwareEqModal()
                }
                override fun onOpenTimers() {
                    controlCenter.close(immediate = true)
                    showTimersModal()
                }
                override fun onOpenRemoteSetup() {
                    controlCenter.close(immediate = true)
                    launchWithGlassTransition(null) {
                        startActivity(Intent(this@MainActivity, RemoteSetupActivity::class.java))
                    }
                }
                override fun onOpenWebQr() {
                    controlCenter.close(immediate = true)
                    showWebQrModal()
                }
                override fun onOpenCustomization() {
                    controlCenter.close(immediate = true)
                    showHomeCustomizationMenu()
                }
                override fun onOpenOta() {
                    controlCenter.close(immediate = true)
                    openOtaDialog()
                }
                override fun onOpenWizard() {
                    controlCenter.close(immediate = true)
                    openSetupWizard(manual = true)
                }
                override fun onOpenShellReset() {
                    controlCenter.close(immediate = false)
                    showShellResetModal()
                }
                override fun onOpenAndroidSettingsMenu() {
                    controlCenter.close(immediate = true)
                    showAndroidTvSettingsModal()
                }
                override fun onAnimationSpeedChanged(speed: MotionPrefs.Speed) {
                    if (::spatialBackdrop.isInitialized) {
                        spatialBackdrop.motionRateScale = MotionPrefs.glSmoothFactor(this@MainActivity)
                    }
                }
                override fun onUiStyleChanged(style: LiquidGlassKit.UiStyle) {
                    applyUiStyle(style)
                }
                override fun onClosed() {
                    syncLayerFocusability()
                    syncBackdropBlurForUiState(animate = true)
                    if (!homeCustomOpen && !appContextOpen && !appInfoOpen && !appsDrawerOpen &&
                        (!::musicPlayerSheet.isInitialized || !musicPlayerSheet.isOpen) &&
                        (!::screensaverOverlay.isInitialized || !screensaverOverlay.isOpen)
                    ) {
                        focusPageControlDirect(currentPage)
                    }
                }
            }
        )
        rootFrame.addView(controlCenter.rootOverlay)

        homeCustomOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            elevation = dp(29).toFloat()
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
            elevation = dp(31).toFloat()
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
            elevation = dp(33).toFloat()
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
            elevation = dp(35).toFloat()
        }
        rootFrame.addView(weatherOverlay)

        musicPlayerSheet = SpatialMusicPlayerSheet(
            context = this,
            spatialBackdrop = spatialBackdrop,
            closeLabel = "Свернуть",
            isEmbeddedOverlay = true,
            onCoverBitmapLoaded = { bmp ->
                if (bmp != null) setHomeCoverArt(bmp)
            },
            onCloseRequested = { closeMusicPlayerSheet() }
        )
        rootFrame.addView(musicPlayerSheet.rootOverlay)

        screensaverOverlay = SpatialScreensaverOverlay(
            context = this,
            onDismissRequested = { closeScreensaver() }
        )
        rootFrame.addView(screensaverOverlay.rootOverlay)

        setContentView(rootFrame)

        syncLayerFocusability()
        setPage(0, animate = false)
        btnHeroOrbClick.post {
            if (currentPage == 0 && !isAnyOverlayActive()) {
                isProgrammaticPageFocus = true
                try { btnHeroOrbClick.requestFocus() } finally { isProgrammaticPageFocus = false }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // SCREEN 0: Pure Minimalist Home Sphere (On-Demand Voice Card Only When Active)
    // -----------------------------------------------------------------------------------------
    private fun buildScreen0Hero(): FrameLayout {
        val page = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val orbFocusSel = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(152).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#18FFFFFF"),
                    Color.parseColor("#05FFFFFF"),
                    Color.TRANSPARENT
                )
            })
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(152).toFloat()
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
            isFocusedByDefault = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus ->
                if (::heroOrbView.isInitialized && currentPage == 0 && !isAnyOverlayActive()) {
                    heroOrbView.animate()
                        .scaleX(if (hasFocus) 1.045f else 1f)
                        .scaleY(if (hasFocus) 1.045f else 1f)
                        .setDuration(MotionPrefs.dur(this@MainActivity, 260L))
                        .setInterpolator(focusSpring)
                        .start()
                }
            }
            setOnClickListener { triggerAlice() }
            setOnLongClickListener {
                if (!okLongPressFired && !homeCustomOpen) showHomeCustomizationMenu()
                true
            }
            layoutParams = FrameLayout.LayoutParams(
                dp(304),
                dp(304),
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply {
                topMargin = dp(64)
            }
        }
        page.addView(btnHeroOrbClick)

        // Materializes softly with real optical refraction ONLY when voice assistant is engaged
        heroReplyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            bindGlassLens(
                staticLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 24f,
                    baseTint = Color.parseColor("#34090E1B"),
                    focusTint = Color.parseColor("#44121C32"),
                    bevelDp = 15f,
                    refractDp = 13f,
                    chromaDp = 2.5f,
                    shadowDp = 18f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.72f,
                    blurMix = 0.90f
                ),
                fallbackTint = Color.parseColor("#B0090E1B")
            )
            setPadding(dp(22), dp(11), dp(22), dp(12))
            isFocusable = false
            isClickable = false
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                dp(600),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply {
                topMargin = dp(378)
            }
        }

        heroReplyHeader = LinearLayout(this).apply {
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
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(8)
            }
        }
        tvHeroState = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#E2E8F0"))
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
            text = ""
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 3
            minLines = 1
            gravity = Gravity.START
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1.08f)
        }
        heroReplyCard.addView(heroReplyHeader)
        heroReplyCard.addView(tvHeroReply)
        page.addView(heroReplyCard)

        // Modular Home Widgets Flanking the Alice Sphere (ALL OFF by default)
        wLeftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                dp(274),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP
            ).apply {
                marginStart = dp(24)
                topMargin = dp(26)
            }
        }

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
                    if (isBtVisible()) {
                        wBtCard.requestFocus()
                    } else {
                        btnHeroOrbClick.requestFocus()
                    }
                    true
                } else {
                    focusTopBarOrNudge(eqWidgetController.rootCard)
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
            text = "Выключен · Нажмите"
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
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                dp(248),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply {
                marginEnd = dp(24)
                topMargin = dp(26)
            }
        }

        wWeatherCard = makeGlassWidgetCard("#94A3B8") {
            if (LiveOrbState.weatherValid) {
                weatherOverlay.showWeather(
                    LiveOrbState.weatherTemp,
                    LiveOrbState.weatherCondition,
                    LiveOrbState.weatherConditionText,
                    LiveOrbState.weatherCity
                )
            } else {
                Toast.makeText(this, "Спросите «${LiveOrbState.effectiveName()}»: «Какая погода?»", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        tvWidgetWeatherCity = TextView(this).apply {
            text = "ПОГОДА"
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
            typeface = YandexFonts.displayRegular(this@MainActivity)
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

        wRemoteCard = makeGlassWidgetCard("#10D281") {
            val st = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
            if (st != null && st.connected) {
                postAsync("http://127.0.0.1:8080/api/remote/find", "{}")
                Toast.makeText(this, "Отправлен BLE-сигнал поиска пульта", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, RemoteSetupActivity::class.java))
            }
        }
        val tvRemHead = TextView(this).apply {
            text = "ПУЛЬТ ДУ · ПОИСК"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        tvWidgetRemoteState = TextView(this).apply {
            text = "Нажмите для поиска"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        wRemoteCard.addView(tvRemHead)
        wRemoteCard.addView(tvWidgetRemoteState)
        wRightCol.addView(wRemoteCard)
        page.addView(wRightCol)

        heroNavHintsOverlay = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cueTop = makeNavCue("⌃  Заставка", Gravity.CENTER_HORIZONTAL or Gravity.TOP) {
            openScreensaver()
        }.apply {
            alpha = 0.44f
            textSize = 11f
            (layoutParams as FrameLayout.LayoutParams).topMargin = dp(18)
        }
        val cueLeft = makeNavCue("‹  Музыка", Gravity.START or Gravity.CENTER_VERTICAL) {
            switchToPageContentFocused(1)
        }.apply {
            (layoutParams as FrameLayout.LayoutParams).marginStart = dp(18)
        }
        val cueRight = makeNavCue("Управление  ›", Gravity.END or Gravity.CENTER_VERTICAL) {
            openControlCenter()
        }.apply {
            (layoutParams as FrameLayout.LayoutParams).marginEnd = dp(18)
        }
        tvHeroOkHint = makeNavCue("OK · ${LiveOrbState.effectiveName()}", Gravity.CENTER_HORIZONTAL or Gravity.TOP) {
            triggerAlice()
        }.apply {
            alpha = 0.50f
            textSize = 11.5f
            (layoutParams as FrameLayout.LayoutParams).topMargin = dp(378)
        }
        val cueBottom = makeNavCue("⌄  Приложения", Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM) {
            openAppsDrawer()
        }.apply {
            (layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(12)
        }
        heroNavHintsOverlay.addView(cueTop)
        heroNavHintsOverlay.addView(cueLeft)
        heroNavHintsOverlay.addView(cueRight)
        heroNavHintsOverlay.addView(tvHeroOkHint)
        heroNavHintsOverlay.addView(cueBottom)
        page.addView(heroNavHintsOverlay)

        applyWidgetsVisibility()
        return page
    }

    // -----------------------------------------------------------------------------------------
    // SCREEN 1 (LEFT): Immersive Spatial Now Playing (Zero Clutter · Dynamic Album Backdrop)
    // -----------------------------------------------------------------------------------------
    private fun buildScreenMusic(): FrameLayout {
        val page = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val stageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                dp(840),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                bottomMargin = dp(18)
            }
        }

        // Left: Flagship Spatial Liquid Glass Album Cover Card (tap/OK opens Player)
        coverGlassFrame = FrameLayout(this).apply {
            id = View.generateViewId()
            bindGlassLens(
                staticLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 28f,
                    baseTint = Color.parseColor("#2A0B101C"),
                    focusTint = Color.parseColor("#3E142038"),
                    bevelDp = 16f,
                    refractDp = 12f,
                    chromaDp = 2.0f,
                    shadowDp = 24f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.88f,
                    blurMix = 0.88f
                )
            )
            clipChildren = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat())
                }
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { openMusicPlayerSheet() }
            layoutParams = LinearLayout.LayoutParams(dp(256), dp(256)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        ivHomeCover = ImageView(this).apply {
            setSpriteDrawable(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.CENTER_CROP
            scaleX = 1.0f
            scaleY = 1.0f
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
        val coverGlassBevel = View(this).apply {
            isFocusable = false
            isClickable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#2AFFFFFF"),
                    Color.parseColor("#05FFFFFF"),
                    Color.parseColor("#00000000"),
                    Color.parseColor("#18000000")
                )
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val coverGlassBevelNormal = coverGlassBevel.background
        val coverGlassBevelFocus = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#42FFFFFF"),
                Color.parseColor("#05FFFFFF"),
                Color.parseColor("#00000000"),
                Color.parseColor("#18000000")
            )
        ).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(2), Color.parseColor("#AAFFFFFF"))
        }
        coverGlassFrame.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.045f else 1f).scaleY(if (hasFocus) 1.045f else 1f)
                .setDuration(MotionPrefs.dur(this@MainActivity, 240L)).setInterpolator(focusSpring).start()
            coverGlassBevel.background = if (hasFocus) coverGlassBevelFocus else coverGlassBevelNormal
        }
        coverGlassFrame.addView(ivHomeCover)
        coverGlassFrame.addView(coverGlassBevel)
        stageRow.addView(coverGlassFrame)

        // Right: Typography & Tactile Optical Transport Orbs
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(44)
            }
        }

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        tvHomeSourceBadge = TextView(this).apply {
            text = "ЯНДЕКС МУЗЫКА"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
        }
        tvHomeAuthBadge = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 11f
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        badgeRow.addView(tvHomeSourceBadge)
        badgeRow.addView(tvHomeAuthBadge)
        infoCol.addView(badgeRow)

        tvHomeTrackTitle = TextView(this).apply {
            text = "Моя волна"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = YandexFonts.displayBold(this@MainActivity)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1.04f)
            setShadowLayer(12f, 0f, 3f, Color.parseColor("#55000000"))
        }
        tvHomeTrackArtist = TextView(this).apply {
            text = "Нажмите «Моя волна» для запуска потока"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 15.5f
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(14))
        }
        infoCol.addView(tvHomeTrackTitle)
        infoCol.addView(tvHomeTrackArtist)

        pbHomeTrack = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = getDrawable(R.drawable.bg_progress_gold)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        }
        tvHomeTrackTime = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            typeface = YandexFonts.textMedium(this@MainActivity)
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        infoCol.addView(pbHomeTrack)
        infoCol.addView(tvHomeTrackTime)

        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        }
        btnHomeWave = makeTextIconPill(R.drawable.ic_wave, "Моя волна", accent = true) {
            sendMusicServiceCmd("play_my_wave")
        }.apply {
            id = View.generateViewId()
            nextFocusLeftId = coverGlassFrame.id
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                marginEnd = dp(12)
            }
        }
        coverGlassFrame.nextFocusRightId = btnHomeWave.id
        btnHomePrev = makeIconBtn(R.drawable.ic_prev, Color.WHITE) { sendMusicAction("PREV") }
        btnHomePlayPause = makeIconBtn(R.drawable.ic_play, Color.WHITE) { sendMusicAction("PLAY_PAUSE") }
        btnHomeNext = makeIconBtn(R.drawable.ic_next, Color.WHITE) { sendMusicAction("NEXT") }
        btnHomeLike = makeIconBtn(R.drawable.ic_heart_outline, null) { sendMusicAction("LIKE") }

        ctrlRow.addView(btnHomeWave)
        val transportButtons = listOf(btnHomePrev, btnHomePlayPause, btnHomeNext, btnHomeLike)
        transportButtons.forEachIndexed { idx, btn ->
            btn.root.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = if (idx < transportButtons.lastIndex) dp(10) else 0
            }
            ctrlRow.addView(btn.root)
        }
        infoCol.addView(ctrlRow)

        stageRow.addView(infoCol)
        page.addView(stageRow)

        val cueMusicTop = makeNavCue("⌃  Заставка", Gravity.CENTER_HORIZONTAL or Gravity.TOP) {
            openScreensaver()
        }.apply {
            alpha = 0.44f
            textSize = 11f
            (layoutParams as FrameLayout.LayoutParams).topMargin = dp(16)
        }
        val cueMusicBottom = makeNavCue("⌄  Плеер", Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM) {
            openMusicPlayerSheet()
        }.apply {
            alpha = 0.62f
            (layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(12)
        }
        page.addView(cueMusicTop)
        page.addView(cueMusicBottom)
        return page
    }

    private fun makeNavCue(label: String, gravity: Int, onTap: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#E2E8F0"))
        alpha = 0.56f
        textSize = 12f
        typeface = YandexFonts.displayMedium(this@MainActivity)
        letterSpacing = 0.04f
        setShadowLayer(10f, 0f, 2f, Color.parseColor("#88000000"))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isFocusable = false
        isClickable = true
        setOnClickListener { onTap() }
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity
        )
    }

    // -----------------------------------------------------------------------------------------
    // SPATIAL TRANSITION & CONTINUOUS SPHERE MORPHING MATH
    // -----------------------------------------------------------------------------------------
    private fun isViewInside(view: View?, container: ViewGroup?): Boolean {
        if (view == null || container == null) return false
        var cur: View? = view
        while (cur != null) {
            if (cur === container) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun isEqVisible(): Boolean =
        ::eqWidgetController.isInitialized && eqWidgetController.rootCard.visibility == View.VISIBLE

    private fun isBtVisible(): Boolean =
        ::wBtCard.isInitialized && wBtCard.visibility == View.VISIBLE

    private fun isWeatherVisible(): Boolean =
        ::wWeatherCard.isInitialized && wWeatherCard.visibility == View.VISIBLE

    private fun isRemoteVisible(): Boolean =
        ::wRemoteCard.isInitialized && wRemoteCard.visibility == View.VISIBLE

    private fun focusFirstLeftWidget(): Boolean {
        if (isEqVisible()) {
            eqWidgetController.focusDefault()
            return true
        }
        if (isBtVisible()) {
            wBtCard.requestFocus()
            return true
        }
        return false
    }

    private fun focusFirstRightWidget(): Boolean {
        if (isWeatherVisible()) {
            wWeatherCard.requestFocus()
            return true
        }
        if (isRemoteVisible()) {
            wRemoteCard.requestFocus()
            return true
        }
        return false
    }

    private fun focusGridDownPartialRow(grid: GridLayout, scroll: ScrollView?, cur: View?): Boolean {
        if (cur == null || grid.childCount == 0) return false
        val normalNext = FocusFinder.getInstance().findNextFocus(scroll ?: grid, cur, View.FOCUS_DOWN)
        if (normalNext != null && normalNext !== cur && isViewInside(normalNext, grid)) return false
        var cell: View? = cur
        while (cell != null && cell.parent !== grid) {
            cell = cell.parent as? View
        }
        val idx = if (cell != null) grid.indexOfChild(cell) else -1
        if (idx < 0) return false
        val cols = max(1, grid.columnCount)
        val lastIdx = grid.childCount - 1
        if ((idx / cols) < (lastIdx / cols)) {
            val target = grid.getChildAt(lastIdx) ?: return false
            target.requestFocus()
            scroll?.post {
                val rect = Rect(0, 0, target.width, target.height)
                target.requestRectangleOnScreen(rect, false)
            }
            return true
        }
        return false
    }

    private fun switchToPageContentFocused(targetPage: Int) {
        val clamped = targetPage.coerceIn(0, 1)
        val targetRoot: ViewGroup = if (clamped == 0) page0View else pageMusicView
        targetRoot.visibility = View.VISIBLE
        targetRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setPage(clamped, animate = true)
        focusPageControlDirect(clamped)
    }

    private fun setPage(targetIdx: Int, animate: Boolean) {
        val clamped = targetIdx.coerceIn(0, 1)
        val prevPage = currentPage
        currentPage = clamped
        val targetPos = if (clamped == 1) -1f else 0f

        pageAnimator?.removeAllListeners()
        pageAnimator?.cancel()

        if (clamped == 1) {
            renderMusicSnapshot(latestMusicSnap, forceViews = true)
        }

        page0View.visibility = View.VISIBLE
        pageMusicView.visibility = View.VISIBLE
        page0View.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        pageMusicView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        if (clamped != prevPage) {
            focusInitialControlOnPage(clamped, immediate = true)
            syncBackdropBlurForUiState(animate = true)
        }

        if (!animate || abs(targetPos - currentScreenPos) < 0.005f) {
            currentScreenPos = targetPos
            applyPageVisuals(currentScreenPos)
            focusInitialControlOnPage(clamped, immediate = true)
            finalizePageVisibility()
            return
        }

        pageAnimator = ValueAnimator.ofFloat(currentScreenPos, targetPos).apply {
            duration = MotionPrefs.dur(this@MainActivity, 540L)
            interpolator = easeOutCubic
            addUpdateListener { anim ->
                currentScreenPos = anim.animatedValue as Float
                applyPageVisuals(currentScreenPos)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentScreenPos = if (currentPage == 1) -1f else 0f
                    applyPageVisuals(currentScreenPos)
                    focusInitialControlOnPage(currentPage, immediate = true)
                    finalizePageVisibility()
                }
            })
            start()
        }
    }

    private fun finalizePageVisibility() {
        val active0 = (currentPage == 0)
        page0View.visibility = if (active0) View.VISIBLE else View.INVISIBLE
        page0View.descendantFocusability = if (active0) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS

        pageMusicView.visibility = if (!active0) View.VISIBLE else View.INVISIBLE
        pageMusicView.descendantFocusability = if (!active0) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS

        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = false)
    }

    private fun focusPageControlDirect(idx: Int) {
        if (isAnyOverlayActive()) return
        isProgrammaticPageFocus = true
        try {
            when (idx) {
                0 -> if (::btnHeroOrbClick.isInitialized) btnHeroOrbClick.requestFocus()
                1 -> if (::btnHomeWave.isInitialized) btnHomeWave.requestFocus()
            }
        } finally {
            isProgrammaticPageFocus = false
        }
    }

    private fun shouldKeepFocusOnPageSwitch(cur: View?, targetPage: Int): Boolean {
        if (cur == null) return false
        if (::tvTopWeather.isInitialized && cur === tvTopWeather) return true
        if (::btnControlTrigger.isInitialized && cur === btnControlTrigger) return true
        val targetRoot: ViewGroup = if (targetPage == 0) page0View else pageMusicView
        return isViewInside(cur, targetRoot)
    }

    private fun focusInitialControlOnPage(idx: Int, immediate: Boolean = false) {
        if (shouldKeepFocusOnPageSwitch(currentFocus, idx)) return
        if (immediate) {
            focusPageControlDirect(idx)
        } else {
            handler.post {
                if (shouldKeepFocusOnPageSwitch(currentFocus, idx)) return@post
                focusPageControlDirect(idx)
            }
        }
    }

    private fun applyPageVisuals(screenPos: Float) {
        val w = (if (rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels).toFloat()
        val topBarBottom = maxOf(dp(54).toFloat(), topBar.bottom.toFloat())

        // Spatial horizontal slide: Center Home = 0, Left Immersive Music = -1
        page0View.translationX = (0f - screenPos) * w
        pageMusicView.translationX = (-1f - screenPos) * w

        val homeAlpha = (1f + screenPos * 1.35f).coerceIn(0f, 1f)
        page0View.alpha = homeAlpha
        pageMusicView.alpha = (1f - abs(screenPos + 1f) * 0.55f).coerceIn(0f, 1f)

        val heroSize = dp(304).toFloat()
        val ccOpen = ::controlCenter.isInitialized && controlCenter.isOpen
        val cx = computeHeroOrbCenterX(w, screenPos, ccOpen)
        val cy = topBarBottom + dp(64) + heroSize * 0.5f
        heroOrbView.compactGlowBoost = 0f
        if (!startupShowActive && !returningFromExternalApp && (!isAnyOverlayActive() || ccOpen)) {
            heroOrbView.alpha = homeAlpha
            if (homeAlpha > 0.02f) heroOrbView.wakeAnimation()
        }
        heroOrbView.pivotX = heroSize * 0.5f
        heroOrbView.pivotY = heroSize * 0.5f
        heroOrbView.translationX = cx - heroSize * 0.5f
        heroOrbView.translationY = cy - heroSize * 0.5f
    }

    // -----------------------------------------------------------------------------------------
    // LIVE ASSISTANT / ORB UI TEXT SYNC (Shown Only When Active / Speaking)
    // -----------------------------------------------------------------------------------------
    private fun renderOrbAssistantUi() {
        val st = LiveOrbState.assistantState
        val effName = LiveOrbState.effectiveName()
        if (::tvHeroOkHint.isInitialized) {
            tvHeroOkHint.text = "OK · $effName"
        }
        val effMale = LiveOrbState.isGrammaticallyMale()
        val userTxt = if (st == "idle") "" else LiveOrbState.liveUserText.trim()
        val display = LiveOrbState.bestDisplayText()
        val uiKey = "$st|$assistantEnabled|$effName|$effMale|$userTxt|$display|$startupShowActive"
        if (uiKey == lastOrbUiKey) return
        lastOrbUiKey = uiKey

        val effCaps = effName.uppercase()
        val accent: Int
        val stateLabel: String
        val heroFallback: String

        when (st) {
            "connecting" -> {
                accent = Color.parseColor("#38BDF8")
                stateLabel = "$effCaps · ПОДКЛЮЧЕНИЕ"
                heroFallback = "Подключаю голосовой поток..."
            }
            "listening" -> {
                accent = Color.parseColor("#2DD4BF")
                stateLabel = "$effCaps · СЛУШАЮ"
                heroFallback = "Говорите, я внимательно слушаю..."
            }
            "thinking" -> {
                accent = Color.parseColor("#E8C686")
                stateLabel = "$effCaps · ДУМАЮ"
                heroFallback = if (userTxt.isNotEmpty()) "Обрабатываю: «$userTxt»" else "Формирую ответ..."
            }
            "speaking" -> {
                accent = Color.parseColor("#C084FC")
                stateLabel = "$effCaps · ОТВЕЧАЮ"
                heroFallback = "Отвечаю голосом..."
            }
            "error" -> {
                accent = Color.parseColor("#F87171")
                stateLabel = "$effCaps · ОШИБКА"
                heroFallback = "Не удалось выполнить запрос"
            }
            else -> {
                accent = if (assistantEnabled) Color.parseColor("#E2E8F0") else Color.parseColor("#64748B")
                val offUpper = if (effMale) "ВЫКЛЮЧЕН" else "ВЫКЛЮЧЕНА"
                stateLabel = if (assistantEnabled) "$effCaps · ${LiveOrbState.readyWord(caps = true)}" else "$effCaps · $offUpper"
                heroFallback = ""
            }
        }

        val showCard = startupShowActive || (st != "idle") || display.isNotEmpty() || userTxt.isNotEmpty()
        heroStateDotBg.setColor(accent)
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.updateLensSpec(heroReplyCard) { it.copy(accentTint = accent) }
        }

        if (!showCard) {
            if (heroReplyCard.visibility == View.VISIBLE) {
                heroReplyCard.animate().cancel()
                heroReplyCard.animate()
                    .alpha(0f)
                    .translationY(dp(8).toFloat())
                    .setDuration(MotionPrefs.dur(this, 360L))
                    .setInterpolator(easeOutCubic)
                    .withEndAction {
                        if (LiveOrbState.assistantState == "idle" &&
                            LiveOrbState.bestDisplayText().isEmpty() &&
                            !startupShowActive
                        ) {
                            heroReplyCard.visibility = View.GONE
                            heroReplyCard.translationY = 0f
                        }
                    }
                    .start()
            }
        } else {
            if (!startupShowActive) {
                tvHeroState.text = stateLabel
                tvHeroState.setTextColor(accent)
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
            if (heroReplyCard.visibility != View.VISIBLE || heroReplyCard.alpha < 0.99f) {
                heroReplyCard.animate().cancel()
                if (heroReplyCard.visibility != View.VISIBLE) {
                    heroReplyCard.alpha = 0f
                    heroReplyCard.translationY = dp(8).toFloat()
                    heroReplyCard.visibility = View.VISIBLE
                }
                heroReplyCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(MotionPrefs.dur(this, 420L))
                    .setInterpolator(easeOutCubic)
                    .start()
            }
        }
        syncBackdropBlurForUiState(animate = true)
    }

    // -----------------------------------------------------------------------------------------
    // REUSABLE SPATIAL GLASS FACTORIES
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
            typeface = YandexFonts.displayBold(this@MainActivity)
        }
        addView(iv)
        addView(tv)
    }

    private fun makeIconBtn(
        iconRes: Int,
        tintColor: Int?,
        cleanupPool: MutableList<() -> Unit> = staticLensCleanups,
        onClick: () -> Unit
    ): IconBtnViews {
        val box = FrameLayout(this).apply {
            bindGlassLens(
                cleanupPool,
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#2A0C1220"),
                    focusTint = Color.parseColor("#541A2C4A"),
                    bevelDp = 12f,
                    refractDp = 10f,
                    chromaDp = 2.0f,
                    shadowDp = 12f,
                    rimAlpha = 0.30f,
                    focusRimAlpha = 0.85f,
                    blurMix = 0.88f
                )
            )
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 230L)).setInterpolator(focusSpring).start()
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
        box.addView(iv)
        return IconBtnViews(box, iv)
    }

    private fun makeTextIconPill(
        iconRes: Int,
        label: String,
        accent: Boolean = false,
        cleanupPool: MutableList<() -> Unit> = customOverlayLensCleanups,
        onClick: () -> Unit
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(18), dp(6))
            val spec = if (accent) {
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#442B2210"),
                    focusTint = Color.parseColor("#6A403014"),
                    accentTint = Color.parseColor("#FBBF24"),
                    bevelDp = 12f,
                    refractDp = 10f,
                    chromaDp = 2.2f,
                    shadowDp = 14f,
                    rimAlpha = 0.42f,
                    focusRimAlpha = 0.90f,
                    blurMix = 0.86f
                )
            } else {
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#2A0C1220"),
                    focusTint = Color.parseColor("#541A2C4A"),
                    bevelDp = 12f,
                    refractDp = 10f,
                    chromaDp = 2.0f,
                    shadowDp = 12f,
                    rimAlpha = 0.28f,
                    focusRimAlpha = 0.85f,
                    blurMix = 0.88f
                )
            }
            bindGlassLens(cleanupPool, this, spec)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.055f else 1f).scaleY(if (hasFocus) 1.055f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 230L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(this@MainActivity).apply {
                val d = getDrawable(iconRes)?.mutate()
                d?.setTint(if (accent) Color.parseColor("#FDE68A") else Color.parseColor("#F1F5F9"))
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                    marginEnd = dp(7)
                }
            }
            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextColor(if (accent) Color.parseColor("#FEF3C7") else Color.parseColor("#F8FAFC"))
                textSize = 12.5f
                typeface = YandexFonts.displayBold(this@MainActivity)
                maxLines = 1
            }
            addView(iv)
            addView(tv)
        }

    private fun makeSysTile(
        iconRes: Int,
        isSprite: Boolean,
        label: String,
        cleanupPool: MutableList<() -> Unit> = customOverlayLensCleanups,
        onClick: () -> Unit
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            bindGlassLens(
                cleanupPool,
                this,
                SpatialLensSpec(
                    radiusDp = 20f,
                    baseTint = Color.parseColor("#2A0C1220"),
                    focusTint = Color.parseColor("#541A2C4A"),
                    bevelDp = 13f,
                    refractDp = 11f,
                    chromaDp = 2.0f,
                    shadowDp = 14f,
                    rimAlpha = 0.28f,
                    focusRimAlpha = 0.85f,
                    blurMix = 0.88f
                )
            )
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 220L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener { onClick() }

            val iv = ImageView(this@MainActivity).apply {
                if (isSprite) setSpriteDrawable(iconRes) else setImageResource(iconRes)
                if (!isSprite) {
                    setColorFilter(Color.parseColor("#E2E8F0"), PorterDuff.Mode.SRC_IN)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                val iconSz = if (isSprite) dp(28) else dp(24)
                layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                    bottomMargin = dp(8)
                }
            }
            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 11.5f
                typeface = YandexFonts.textMedium(this@MainActivity)
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
            subtitle = null
        )
        val ymEntry = AppEntry(
            pkg = PKG_YANDEX_MUSIC,
            label = "Яндекс Музыка",
            icon = AnimatedSpriteHelper.loadDrawable(this, R.drawable.sprite_ym_emblem) ?: applicationInfo.loadIcon(pm),
            intent = Intent(this, YandexMusicActivity::class.java),
            leanback = true,
            isVirtual = true,
            subtitle = null
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
        clearLensCleanups(drawerLensCleanups)
        if (::drawerAppsGrid.isInitialized) {
            drawerAppsGrid.removeAllViews()
            val cols = visible.size.coerceIn(1, 5)
            drawerAppsGrid.columnCount = cols
            if (::drawerScrollContent.isInitialized) {
                drawerScrollContent.gravity = if (visible.size <= 10) {
                    Gravity.CENTER
                } else {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
            }
            val tileMargin = dp(8)
            val colW = dp(152)
            for (app in visible) {
                addAppTileToGrid(drawerAppsGrid, app, colW, tileMargin, drawerTileViews)
            }
        }

        if (::tvDrawerStatusPill.isInitialized) {
            when {
                reorderingPkg != null -> {
                    tvDrawerStatusPill.text = "Перемещение · ◄►▲▼ сдвиг · OK готово"
                    tvDrawerStatusPill.visibility = View.VISIBLE
                }
                hiddenSet.isNotEmpty() -> {
                    tvDrawerStatusPill.text = "${visible.size} прилож. · ${hiddenSet.size} скрыто"
                    tvDrawerStatusPill.visibility = View.VISIBLE
                }
                else -> {
                    tvDrawerStatusPill.text = "${visible.size} прилож."
                    tvDrawerStatusPill.visibility = View.VISIBLE
                }
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
        var res = Color.parseColor("#7DD3FC")
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

        val baseCardTint = when {
            isMoving -> Color.parseColor("#46183058")
            isPinnedApps -> Color.parseColor("#2A0F1A30")
            else -> Color.parseColor("#220A101E")
        }
        val focusCardTint = when {
            isMoving -> Color.parseColor("#66204074")
            else -> Color.parseColor("#4A162442")
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(12))
            tag = app.pkg
            bindGlassLens(
                drawerLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 24f,
                    baseTint = baseCardTint,
                    focusTint = focusCardTint,
                    accentTint = if (isMoving) Color.parseColor("#93C5FD") else accent,
                    bevelDp = 15f,
                    refractDp = 12f,
                    chromaDp = 2.4f,
                    shadowDp = 16f,
                    rimAlpha = 0.28f,
                    focusRimAlpha = 0.90f,
                    blurMix = 0.88f,
                    clipParent = if (::drawerScrollView.isInitialized) drawerScrollView else null
                ),
                fallbackTint = baseCardTint
            )
            elevation = dp(if (isMoving || isPinnedApps) 8 else 4).toFloat()
            isFocusable = true
            isClickable = true
            layoutParams = GridLayout.LayoutParams().apply {
                width = colW
                height = dp(126)
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
        }
        val iv = ImageView(this).apply {
            setImageDrawable(app.icon)
            (app.icon as? Animatable)?.start()
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        val tvName = TextView(this).apply {
            text = app.label
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12.5f
            gravity = Gravity.CENTER
            typeface = YandexFonts.textMedium(this@MainActivity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        card.addView(iv)
        card.addView(tvName)

        if (isMoving) {
            val tvSub = TextView(this).apply {
                text = "◄►▲▼ сдвиг"
                setTextColor(Color.parseColor("#93C5FD"))
                textSize = 10f
                typeface = YandexFonts.displayBold(this@MainActivity)
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            card.addView(tvSub)
        }

        card.setOnFocusChangeListener { v, hasFocus ->
            v.elevation = dp(if (hasFocus) 10 else if (isMoving || isPinnedApps) 8 else 4).toFloat()
            v.animate()
                .scaleX(if (hasFocus) 1.07f else 1f)
                .scaleY(if (hasFocus) 1.07f else 1f)
                .setDuration(MotionPrefs.dur(this@MainActivity, 240L))
                .setInterpolator(focusSpring)
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
                val i = packageManager.appLaunchIntent(pkg)
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

    private var remoteConnectedTextState = "Bluetooth и пульт"

    // -----------------------------------------------------------------------------------------
    // CLOCK, NETWORK & DAEMON TELEMETRY
    // -----------------------------------------------------------------------------------------
    private fun updateClock(force: Boolean = false) {
        val tz = TimeZone.getDefault()
        timeFmt.timeZone = tz
        shortDateFmt.timeZone = tz
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

        if (force || timeStr != lastClockStr) {
            lastClockStr = timeStr
            tvTopClock.text = timeStr

            when (style) {
                "minimal" -> {
                    tvTopClock.typeface = YandexFonts.displayRegular(this)
                    tvTopClock.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    tvTopClock.setTextColor(Color.parseColor("#E2E8F0"))
                }
                "bold" -> {
                    tvTopClock.typeface = YandexFonts.displayBold(this)
                    tvTopClock.setShadowLayer(10f, 0f, 3f, Color.parseColor("#55000000"))
                    tvTopClock.setTextColor(Color.WHITE)
                }
                "neon" -> {
                    tvTopClock.typeface = YandexFonts.displayMedium(this)
                    tvTopClock.setTextColor(Color.parseColor("#E0F2FE"))
                    tvTopClock.setShadowLayer(18f, 0f, 0f, Color.parseColor("#00D4FF"))
                }
                "retro" -> {
                    tvTopClock.typeface = Typeface.MONOSPACE
                    tvTopClock.setTextColor(Color.parseColor("#E2E8F0"))
                    tvTopClock.setShadowLayer(8f, 0f, 0f, Color.parseColor("#33FFFFFF"))
                }
                else -> {
                    tvTopClock.typeface = YandexFonts.displayMedium(this)
                    tvTopClock.setTextColor(Color.WHITE)
                    tvTopClock.setShadowLayer(8f, 0f, 2f, Color.parseColor("#44000000"))
                }
            }
        }
        if (force || shortDate != lastShortDateStr) {
            lastShortDateStr = shortDate
            tvTopDate.text = shortDate
        }

        if (LiveOrbState.weatherValid) {
            val sign = if (LiveOrbState.weatherTemp > 0) "+" else ""
            tvTopWeather.text = "$sign${LiveOrbState.weatherTemp}°"
            tvTopWeather.visibility = View.VISIBLE
        } else {
            tvTopWeather.visibility = View.GONE
        }
        if (::screensaverOverlay.isInitialized) {
            val longDateFmt = SimpleDateFormat("EEEE, d MMMM", ruLocale).apply { timeZone = tz }
            val fullDate = longDateFmt.format(clockDate).replaceFirstChar { it.uppercase() }
            val weatherSummary = if (LiveOrbState.weatherValid) {
                val sign = if (LiveOrbState.weatherTemp > 0) "+" else ""
                val cond = LiveOrbState.weatherConditionText.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                "$sign${LiveOrbState.weatherTemp}°$cond"
            } else {
                null
            }
            screensaverOverlay.updateClockAndWeather(timeStr, fullDate, weatherSummary)
        }
    }

    private fun detectLocalIp(force: Boolean = false): String {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && cachedLocalIp != "127.0.0.1" && (nowMs - lastIpDetectMs) < 5_000L) {
            return cachedLocalIp
        }
        val result = LocalNetworkAddress.find(this).ifEmpty { "127.0.0.1" }
        cachedLocalIp = result
        lastIpDetectMs = nowMs
        return result
    }

    private fun updateNetworkHeader(ipOverride: String? = null, force: Boolean = false) {
        val ip = ipOverride ?: detectLocalIp(force)
        val url = SetupAddress.webUrl(ip)
        val ipLine = if (url != null) {
            "$ip:8080"
        } else {
            "Нет сети"
        }
        if (force || ipLine != lastIpLineStr) {
            lastIpLineStr = ipLine
            updateControlCenterState()
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
                    val postOtaPending = root.optBoolean("postOtaWizardPending", false)
                    val postOtaVer = root.optString("postOtaWizardVersion", "").trim()
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
                    val pName = (astCfg?.optString("name", "") ?: "").trim().ifEmpty { "Алиса" }
                    val pVoice = (astCfg?.optString("voiceName", "") ?: "").trim().ifEmpty { "Aoede" }
                    val pPrompt = astCfg?.optString("systemPrompt", "") ?: ""
                    assistantNameState = pName
                    assistantVoiceState = pVoice
                    assistantPromptState = pPrompt
                    assistantVadSilenceMsState = astCfg?.optInt("vadSilenceMs", assistantVadSilenceMsState) ?: assistantVadSilenceMsState
                    dnsAutoFailoverState = astCfg?.optBoolean("dnsFailoverEnabled", dnsAutoFailoverState) ?: dnsAutoFailoverState
                    dnsModeState = root.optString("privateDnsMode", dnsModeState)
                    dnsHostState = root.optString("privateDnsHost", dnsHostState)
                    val dfObj = root.optJSONObject("dnsFailover")
                    if (dfObj != null) {
                        val healthy = dfObj.optBoolean("healthy", true)
                        val lat = dfObj.optInt("latencyMs", -1)
                        val swTo = dfObj.optString("lastSwitchTo", "")
                        dnsFailoverSummaryState = when {
                            !healthy -> "⚠ DNS недоступен: ${dfObj.optString("lastError", "сбой DoT")}"
                            swTo.isNotBlank() && lat >= 0 -> "↻ Резерв: $swTo (${lat} мс)"
                            lat >= 0 && dnsHostState.isNotBlank() -> "✓ $dnsHostState (${lat} мс)"
                            else -> ""
                        }
                    }
                    LiveOrbState.updateAssistantPersona(pName, pVoice)

                    val hwEqObj = root.optJSONObject("hardwareEq")
                    hwEqSupportedState = hwEqObj?.optBoolean("supported", false) ?: false
                    hwEqProfileState = hwEqObj?.optString("profile", "original") ?: "original"
                    LiveOrbState.updateHardwareEq(hwEqProfileState, hwEqSupportedState)

                    val remObj = root.optJSONObject("remote")
                    val remConnArr = remObj?.optJSONArray("connected")
                    val remCount = remConnArr?.length() ?: 0
                    val remFirst = remConnArr?.optJSONObject(0)?.optString("name", "")?.trim() ?: ""
                    remoteConnectedTextState = when {
                        remCount > 0 && remFirst.isNotEmpty() -> remFirst
                        remCount > 0 -> "Подключён ($remCount)"
                        remObj?.optBoolean("scanning", false) == true -> "Поиск пульта..."
                        else -> "Не подключён"
                    }

                    val timersArr = root.optJSONArray("timers")
                    val alarmsArr = root.optJSONArray("alarms")
                    val ringingObj = root.optJSONObject("ringingAlarm")
                    val fetchedNowMs = SystemClock.elapsedRealtime()
                    lastTimersJson = timersArr
                    lastAlarmsJson = alarmsArr
                    lastRingingAlarmJson = ringingObj
                    lastTimersFetchedMs = fetchedNowMs

                    val setupDone = cfg?.optBoolean("setupCompleted", false) ?: false
                    val wasOffline = !daemonOnline
                    daemonOnline = true
                    if (wasOffline && cachedAppsJson.isNotEmpty()) {
                        httpPostJson("http://127.0.0.1:8080/api/apps/cache", cachedAppsJson)
                    }
                    val daemonMusic = root.optJSONObject("music")
                    val daemonOta = root.optJSONObject("ota")
                    val miscRepairObj = root.optJSONObject("miscRepair")
                    val screenVisStyle = cfg?.optJSONObject("ledScreen")?.optString("visualizerStyle", "") ?: ""
                    if (screenVisStyle.isNotEmpty() && visualizerStyles.any { it.first == screenVisStyle }) {
                        LiveOrbState.updateStyles(null, screenVisStyle, null)
                    }
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
                    val statusStr = if (micMuted) "$volumePct% · Мик выкл" else "$volumePct%"
                    val dotColor = Color.parseColor(if (micMuted) "#FDA4AF" else "#34D399")

                    handler.post {
                        updateNetworkHeader(ip)
                        renderOrbAssistantUi()
                        timersScreenRef?.update(lastTimersJson, lastTimersFetchedMs, lastAlarmsJson, lastRingingAlarmJson)
                        if (statusStr != lastStatusLineText) {
                            lastStatusLineText = statusStr
                            tvStatusLine.text = statusStr
                        }
                        if (::controlStatusDotBg.isInitialized) {
                            controlStatusDotBg.setColor(dotColor)
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
                        miscRepairHelper.handleDaemonState(miscRepairObj)
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
                        if (!wizardAutoOpenedThisRun) {
                            if (postOtaPending) {
                                wizardAutoOpenedThisRun = true
                                openSetupWizard(manual = false, postOtaVer = postOtaVer)
                            } else if (!setupDone) {
                                val prefs = getSharedPreferences(SetupWizardActivity.PREFS_NAME, MODE_PRIVATE)
                                val dismissedVer = prefs.getInt(SetupWizardActivity.KEY_WIZARD_DISMISSED_VER, 0)
                                if (dismissedVer < SetupWizardActivity.CURRENT_WIZARD_VER) {
                                    wizardAutoOpenedThisRun = true
                                    openSetupWizard(manual = false)
                                }
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
            val s = "Ожидание"
            if (s != lastStatusLineText) {
                lastStatusLineText = s
                tvStatusLine.text = s
            }
            if (::controlStatusDotBg.isInitialized) {
                controlStatusDotBg.setColor(Color.parseColor("#64748B"))
            }
            syncPills()
        }
    }

    private fun updateControlCenterState() {
        if (!::controlCenter.isInitialized) return
        val ringing = lastRingingAlarmJson
        val tCount = lastTimersJson?.length() ?: 0
        val aEnabled = (0 until (lastAlarmsJson?.length() ?: 0)).count {
            lastAlarmsJson?.optJSONObject(it)?.optBoolean("enabled", false) == true
        }
        val timersTxt = when {
            ringing != null -> "Звонит будильник!"
            tCount > 0 && aEnabled > 0 -> "Т: $tCount · Б: $aEnabled"
            tCount > 0 -> "Таймеры ($tCount)"
            aEnabled > 0 -> "Будильники ($aEnabled)"
            else -> "Нет активных"
        }
        val astStatus = when {
            !daemonOnline -> "Подключение к системе · v$fwVer"
            assistantState != "idle" -> "${LiveOrbState.effectiveName()} ${LiveOrbState.activeWord()} · v$fwVer"
            else -> "${LiveOrbState.effectiveName()} ${LiveOrbState.readyWord()} · v$fwVer"
        }
        controlCenter.syncState(
            telemetry = astStatus,
            micMuted = micMuted,
            ringMode = ringMode,
            outputRoute = outputRoute,
            eqPreset = eqPreset,
            visualizerStyle = LiveOrbState.visualizerStyle,
            visualizerLabel = visualizerLabel(LiveOrbState.visualizerStyle),
            cecStandby = cecState,
            assistantName = LiveOrbState.effectiveName(),
            hwEqSupported = hwEqSupportedState,
            hwEqProfile = hwEqProfileState,
            timersCountText = timersTxt,
            remoteConnectedText = remoteConnectedTextState,
            ipLine = lastIpLineStr,
            fwVer = fwVer,
            otaJson = otaLatestJson
        )
    }

    private fun updateControlCenterMusic() {
        if (!::controlCenter.isInitialized) return
        controlCenter.syncMusic(latestMusicSnap, latestCoverBmp)
    }

    private fun syncPills(forceViews: Boolean = false) {
        updateControlCenterState()
        renderOrbAssistantUi()
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
        latestCoverBmp = bmp
        if (bmp != null) {
            ivHomeCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivHomeCover.scaleX = 1.0f
            ivHomeCover.scaleY = 1.0f
            ivHomeCover.background = GradientDrawable().apply {
                setColor(Color.parseColor("#111927"))
                cornerRadius = dp(24).toFloat()
            }
            ivHomeCover.clipToOutline = true
            ivHomeCover.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat())
                }
            }
            ivHomeCover.setImageBitmap(bmp)
        } else {
            ivHomeCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivHomeCover.scaleX = 1.0f
            ivHomeCover.scaleY = 1.0f
            ivHomeCover.background = null
            ivHomeCover.clipToOutline = true
            ivHomeCover.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat())
                }
            }
            ivHomeCover.setSpriteDrawable(R.drawable.sprite_ym_emblem)
        }
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.setMusicCoverBitmap(bmp, showAmbient = true)
            syncBackdropBlurForUiState(animate = false)
        }
        if (::musicPlayerSheet.isInitialized && (musicPlayerSheet.isOpen || bmp != null)) {
            musicPlayerSheet.renderSnapshot(latestMusicSnap, cachedCoverBmp = bmp)
        }
        if (::screensaverOverlay.isInitialized) {
            screensaverOverlay.updateMusic(latestMusicSnap, bmp)
        }
        updateControlCenterMusic()
    }

    private fun renderMusicSnapshot(snap: YmPlayerSnapshot, forceViews: Boolean = false) {
        latestMusicSnap = snap
        LiveOrbState.musicPlaying = snap.playing

        val tr = snap.currentTrack
        updateControlCenterMusic()

        val shouldLoadCover = (currentPage == 1) || forceViews ||
            (::controlCenter.isInitialized && controlCenter.isOpen) ||
            (::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen) ||
            (::screensaverOverlay.isInitialized && screensaverOverlay.isOpen)
        if (tr != null && shouldLoadCover) {
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
        } else if (tr == null && lastHomeCoverUrl.isNotEmpty()) {
            lastHomeCoverUrl = ""
            setHomeCoverArt(null)
        }

        if (currentPage == 1 || forceViews) {
            tvHomeSourceBadge.text = snap.sourceLabel.uppercase(Locale.getDefault())

            val err = snap.lastError
            val errClean = err.removePrefix("AUTH_REQUIRED:").trim()
            when {
                err.isNotBlank() -> {
                    tvHomeAuthBadge.text = errClean
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#FCA5A5"))
                }
                snap.buffering -> {
                    tvHomeAuthBadge.text = "Загрузка потока..."
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#CBD5E1"))
                }
                snap.account.loggedIn -> {
                    val nm = snap.account.login.ifBlank { snap.account.fullName.ifBlank { "Аккаунт" } }
                    val plus = if (snap.account.hasPlus) " · Плюс" else ""
                    tvHomeAuthBadge.text = "$nm$plus"
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#86EFAC"))
                }
                else -> {
                    tvHomeAuthBadge.text = "Откройте обложку или ↓"
                    tvHomeAuthBadge.setTextColor(Color.parseColor("#94A3B8"))
                }
            }

            if (tr != null) {
                tvHomeTrackTitle.text = tr.title
                tvHomeTrackArtist.text = if (tr.album.isNotBlank()) "${tr.artists} · ${tr.album}" else tr.artists
                val dur = maxOf(snap.durationMs, tr.durationMs)
                val pos = snap.positionMs.coerceAtLeast(0L)
                if (dur > 0L) {
                    pbHomeTrack.visibility = View.VISIBLE
                    tvHomeTrackTime.visibility = View.VISIBLE
                    pbHomeTrack.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    tvHomeTrackTime.text = "${formatTimeMs(pos)} / ${formatTimeMs(dur)}"
                } else {
                    pbHomeTrack.visibility = View.INVISIBLE
                    tvHomeTrackTime.visibility = View.INVISIBLE
                }

                if (forceViews || lastTrackLiked != tr.liked) {
                    lastTrackLiked = tr.liked
                    btnHomeLike.icon.setImageDrawable(
                        getDrawable(if (tr.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
                    )
                }
            } else {
                tvHomeTrackTitle.text = "Моя волна"
                tvHomeTrackArtist.text = "Скажите «${LiveOrbState.effectiveName()}, включи музыку» или нажмите «Моя волна»"
                pbHomeTrack.visibility = View.INVISIBLE
                tvHomeTrackTime.visibility = View.INVISIBLE
                if (forceViews || lastTrackLiked != false) {
                    lastTrackLiked = false
                    btnHomeLike.icon.setImageDrawable(getDrawable(R.drawable.ic_heart_outline))
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
        if (::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen) {
            musicPlayerSheet.renderSnapshot(snap, cachedCoverBmp = latestCoverBmp)
        }
        if (::screensaverOverlay.isInitialized && screensaverOverlay.isOpen) {
            screensaverOverlay.updateMusic(snap, latestCoverBmp)
        }
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

    private fun openMusicPlayerSheet(initialTab: String? = null) {
        if (!::musicPlayerSheet.isInitialized) return
        if (::screensaverOverlay.isInitialized && screensaverOverlay.isOpen) {
            screensaverOverlay.close(immediate = true)
        }
        if (appsDrawerOpen) closeAppsDrawer(immediate = true)
        musicPlayerSheet.open(initialTab)
        musicPlayerSheet.renderSnapshot(latestMusicSnap, cachedCoverBmp = latestCoverBmp)
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = true)
    }

    private fun closeMusicPlayerSheet(immediate: Boolean = false) {
        if (!::musicPlayerSheet.isInitialized) return
        if (!musicPlayerSheet.isOpen && musicPlayerSheet.rootOverlay.visibility != View.VISIBLE) return
        musicPlayerSheet.close(immediate = immediate)
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = !immediate)
        if (!isAnyOverlayActive()) {
            focusPageControlDirect(currentPage)
        }
    }

    private fun openScreensaver() {
        if (!::screensaverOverlay.isInitialized || screensaverOverlay.isOpen) return
        updateClock(force = true)
        if (::spatialBackdrop.isInitialized) spatialBackdrop.onHostPause()
        screensaverOverlay.updateMusic(latestMusicSnap, latestCoverBmp)
        screensaverOverlay.open()
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = true)
    }

    private fun closeScreensaver(immediate: Boolean = false) {
        if (!::screensaverOverlay.isInitialized) return
        if (!screensaverOverlay.isOpen && screensaverOverlay.rootOverlay.visibility != View.VISIBLE) return
        screensaverOverlay.close(immediate = immediate)
        if (::spatialBackdrop.isInitialized) spatialBackdrop.onHostResume()
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = !immediate)
        if (!isAnyOverlayActive()) {
            focusPageControlDirect(currentPage)
        }
    }

    private fun launchYandexMusic(sourceView: View? = null) {
        if (::musicPlayerSheet.isInitialized) {
            openMusicPlayerSheet()
            return
        }
        launchWithGlassTransition(sourceView) {
            try {
                val i = Intent(this, YandexMusicActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
                overridePendingTransition(R.anim.spatial_sheet_in_up, R.anim.spatial_hold_fade_out)
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
            if (!otaModalOpen || !homeCustomOpen) return
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
        updateControlCenterState()
        refreshOtaDialogViews()
    }

    private fun refreshOtaDialogViews() {
        if (!otaModalOpen || !homeCustomOpen) return
        val o = otaLatestJson
        val tv = otaModalStatusTv
        val clTv = otaModalChangelogTv
        val instBtn = otaModalInstallBtn
        if (o == null) {
            tv?.text = "Проверка обновлений..."
            clTv?.visibility = View.GONE
            instBtn?.isEnabled = false
            return
        }
        val instVer = o.optString("installedVersion", fwVer)
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
                st == "error" -> append("Ошибка: ").append(err.ifBlank { ptxt })
                busy -> append("Выполняется: ").append(pct).append("% — ").append(ptxt)
                avail -> append("Доступно обновление! Ваши настройки, ключи и аккаунт сохранятся.")
                st == "up_to_date" -> append(ptxt.ifBlank { "Установлена актуальная версия" })
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
        instBtn?.alpha = if (instBtn?.isEnabled == true) 1f else 0.45f
        instBtn?.text = when {
            busy -> "Обновление ($pct%)..."
            avail && latVer.isNotBlank() -> "Установить v$latVer"
            else -> "Переустановить с GitHub"
        }
    }

    private fun openOtaDialog() {
        handler.removeCallbacks(otaDialogPollRunnable)
        clearLensCleanups(customOverlayLensCleanups)
        otaModalOpen = true

        val panel = makeModalGlassPanel(620)
        panel.addView(TextView(this).apply {
            text = "Обновление прошивки (OTA)"
            typeface = YandexFonts.displayBold(this@MainActivity)
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        })

        val statusTv = TextView(this).apply {
            typeface = YandexFonts.displayRegular(this@MainActivity)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13.5f
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        val changelogTv = TextView(this).apply {
            typeface = YandexFonts.displayRegular(this@MainActivity)
            setTextColor(Color.parseColor("#BAE6FD"))
            textSize = 12.5f
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        panel.addView(statusTv)
        panel.addView(changelogTv)

        val btnInstall = TextView(this).apply {
            text = "Установить обновление"
            typeface = YandexFonts.displayMedium(this@MainActivity)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            bindGlassLens(
                customOverlayLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#580284C7"),
                    focusTint = Color.parseColor("#9238BDF8"),
                    accentTint = Color.parseColor("#7DD3FC"),
                    bevelDp = 12f,
                    refractDp = 10f,
                    chromaDp = 2.0f,
                    shadowDp = 12f,
                    rimAlpha = 0.45f,
                    focusRimAlpha = 0.92f,
                    blurMix = 0.88f
                ),
                fallbackTint = Color.parseColor("#660284C7")
            )
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 210L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener {
                val cur = otaLatestJson
                val force = !(cur?.optBoolean("updateAvailable", false) ?: false)
                isEnabled = false
                alpha = 0.45f
                postAsync("http://127.0.0.1:8080/api/ota/install", JSONObject().put("force", force).toString())
                Toast.makeText(this@MainActivity, "Запуск OTA с GitHub...", Toast.LENGTH_SHORT).show()
                handler.removeCallbacks(otaDialogPollRunnable)
                handler.postDelayed(otaDialogPollRunnable, 350L)
            }
        }
        val btnCheck = makeTextIconPill(R.drawable.ic_refresh, "Проверить", accent = false, cleanupPool = customOverlayLensCleanups) {
            postAsync("http://127.0.0.1:8080/api/ota/check", "{}")
            Toast.makeText(this, "Проверка GitHub...", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(otaDialogPollRunnable)
            handler.postDelayed(otaDialogPollRunnable, 250L)
        }
        val btnClose = makeTextIconPill(R.drawable.ic_home, "Закрыть", accent = false, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu()
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        btnRow.addView(btnCheck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { rightMargin = dp(10) })
        btnRow.addView(btnInstall, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { rightMargin = dp(10) })
        btnRow.addView(btnClose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        panel.addView(btnRow)

        otaModalStatusTv = statusTv
        otaModalChangelogTv = changelogTv
        otaModalInstallBtn = btnInstall

        presentOverlayModal(panel, btnInstall)
        refreshOtaDialogViews()
        handler.post(otaDialogPollRunnable)
    }

    private fun openSetupWizard(manual: Boolean, postOtaVer: String = "") {
        try {
            val i = Intent(this, SetupWizardActivity::class.java).apply {
                putExtra(SetupWizardActivity.EXTRA_MANUAL, manual)
                if (postOtaVer.isNotEmpty()) {
                    putExtra(SetupWizardActivity.EXTRA_POST_OTA_VER, postOtaVer)
                }
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

    private fun toggleCec() {
        cecState = !cecState
        val cmd = if (cecState) "standby" else "wake"
        sendCec(cmd)
        syncPills()
    }

    private val visualizerStyles = listOf(
        "pulse_bars" to "Эквалайзер",
        "beat_wave" to "Аудио-волна",
        "bass_nova" to "Бас-нова",
        "mirror_meter" to "Зеркало",
        "spark_burst" to "Искры",
        "vu_plasma" to "VU-плазма",
        "track_title" to "Трек"
    )

    private fun visualizerLabel(style: String): String =
        visualizerStyles.firstOrNull { it.first == style }?.second ?: "Эквалайзер"

    private fun cycleVisualizerStyle() {
        val cur = LiveOrbState.visualizerStyle
        val curIdx = visualizerStyles.indexOfFirst { it.first == cur }.takeIf { it >= 0 } ?: 0
        val (next, name) = visualizerStyles[(curIdx + 1) % visualizerStyles.size]
        LiveOrbState.visualizerStyle = next
        LiveOrbState.previewUntilMs = System.currentTimeMillis() + 8000L
        syncPills()
        Toast.makeText(this, "Визуализация экранчика: $name", Toast.LENGTH_SHORT).show()
        val body = JSONObject()
            .put("ledScreen", JSONObject().put("visualizerStyle", next))
            .toString()
        postAsync("http://127.0.0.1:8080/api/config", body)
    }

    private fun makeGlassWidgetCard(accentHex: String, onClick: () -> Unit): LinearLayout {
        val accent = Color.parseColor(accentHex)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            bindGlassLens(
                staticLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 20f,
                    baseTint = Color.parseColor("#320B1324"),
                    focusTint = Color.parseColor("#54142542"),
                    accentTint = accent,
                    bevelDp = 13f,
                    refractDp = 11f,
                    chromaDp = 1.8f,
                    shadowDp = 14f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.85f,
                    blurMix = 0.88f
                ),
                fallbackTint = Color.parseColor("#5C0B1324")
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 210L)).setInterpolator(focusSpring).start()
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
            when {
                st != null && st.connected -> {
                    val bat = if (st.batteryPct >= 0) " · ${st.batteryPct}%" else ""
                    tvWidgetRemoteState.text = "✓ ${st.name}$bat · Подключён"
                }
                st != null && st.bonded -> {
                    tvWidgetRemoteState.text = "${st.name} · Не подключён"
                }
                else -> {
                    tvWidgetRemoteState.text = "Пульт не подключён · Найти"
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // SYNCHRONIZED BOOT REVEAL + SPATIAL BLUR TRANSITIONS + WALLPAPER ENGINE
    // -----------------------------------------------------------------------------------------
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
            tvHeroState.text = "OPENSTATION MAX"
            tvHeroReply.text = "Жидкое стекло, акустика и матричный дисплей активны."
            renderOrbAssistantUi()
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
        launchTimeoutRunnable?.let { handler.removeCallbacks(it) }
        closeAllOverlays(immediate = false)
        returningFromExternalApp = true

        shellCol.animate().cancel()
        heroOrbView.animate().cancel()

        if (::spatialBackdrop.isInitialized) {
            backdropBlurAnimator?.cancel()
            spatialBackdrop.setSceneState(
                backdropBlur = 1f,
                backdropDim = 0.48f,
                orbEnergy = 0.35f,
                orbColor = 0,
                musicAmbientAlpha = sceneCoverAlpha
            )
        }

        shellCol.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .alpha(0.32f)
            .setDuration(MotionPrefs.dur(this, 360L))
            .setInterpolator(easeOutCubic)
            .start()

        heroOrbView.animate()
            .alpha(0.24f)
            .setDuration(MotionPrefs.dur(this, 340L))
            .start()

        anchorView?.animate()
            ?.scaleX(1.09f)
            ?.scaleY(1.09f)
            ?.setDuration(MotionPrefs.dur(this, 240L))
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
        }, MotionPrefs.dur(this, 240L, 90L))
    }

    private fun settleFromExternalAppIfNeeded() {
        if (!returningFromExternalApp) return
        returningFromExternalApp = false
        launchTimeoutRunnable?.let { handler.removeCallbacks(it) }
        launchTimeoutRunnable = null

        if (!::shellCol.isInitialized) return
        shellCol.animate().cancel()
        heroOrbView.animate().cancel()

        shellCol.scaleX = 0.95f
        shellCol.scaleY = 0.95f
        shellCol.alpha = 0.35f

        shellCol.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(MotionPrefs.dur(this, 480L))
            .setInterpolator(easeOutCubic)
            .start()

        heroOrbView.animate()
            .alpha(1f)
            .setDuration(MotionPrefs.dur(this, 480L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                applyPageVisuals(currentScreenPos)
                syncBackdropBlurForUiState(animate = true)
            }
            .start()
    }

    private fun applyUiStyle(style: LiquidGlassKit.UiStyle, persist: Boolean = true) {
        LiquidGlassKit.setUiStyle(this, style, persist = persist)
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.refreshThemeMode()
        }
        if (::rootFrame.isInitialized) {
            LiquidGlassKit.refreshGlassTree(rootFrame)
        }
        if (::controlCenter.isInitialized) {
            controlCenter.syncUiStyle(style)
        }
    }

    private fun applyWallpaperTheme(themeId: String, persist: Boolean = true) {
        LiquidGlassKit.setWallpaper(this, themeId, persist)
        if (::ivBgFallback.isInitialized) {
            LiquidGlassKit.sharpWallpaper?.let { ivBgFallback.setImageBitmap(it) }
        }
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.setWallpaperBitmap(LiquidGlassKit.sharpWallpaper)
        }
        if (::rootFrame.isInitialized) {
            LiquidGlassKit.refreshGlassTree(rootFrame)
        }
    }

    // -----------------------------------------------------------------------------------------
    // APPS DRAWER OVERLAY (DPAD_DOWN on Home -> Optical Spatial App Grid)
    // -----------------------------------------------------------------------------------------
    private fun buildAppsDrawerOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#3802050E"))
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(36), dp(24), dp(36), dp(22))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val tvTitle = TextView(this).apply {
            text = "Приложения"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = YandexFonts.displayBold(this@MainActivity)
            setShadowLayer(12f, 0f, 3f, Color.parseColor("#55000000"))
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        tvDrawerStatusPill = TextView(this).apply {
            text = "Все приложения"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            typeface = YandexFonts.displayMedium(this@MainActivity)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            bindGlassLens(
                staticLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = Color.parseColor("#2A0C162A"),
                    focusTint = Color.parseColor("#44142848"),
                    bevelDp = 11f,
                    refractDp = 9f,
                    chromaDp = 1.6f,
                    shadowDp = 10f,
                    rimAlpha = 0.30f,
                    focusRimAlpha = 0.80f,
                    blurMix = 0.86f
                ),
                fallbackTint = Color.parseColor("#38090F1A")
            )
            isFocusable = false
        }
        headerRow.addView(tvTitle)
        headerRow.addView(spacer)
        headerRow.addView(tvDrawerStatusPill)
        panel.addView(headerRow)

        drawerScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = true
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        drawerScrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        drawerAppsGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(12), dp(8), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        drawerScrollContent.addView(drawerAppsGrid)
        drawerScrollView.addView(drawerScrollContent)
        panel.addView(drawerScrollView)

        overlay.addView(panel)
        return overlay
    }

    private fun openAppsDrawer(focusPkg: String = PKG_OPENSTATION_APPS) {
        if (!::appsDrawerOverlay.isInitialized) return
        reorderingPkg = null
        appsDrawerOpen = true
        loadAndRenderApps()
        syncBackdropBlurForUiState(animate = true)

        appsDrawerOverlay.animate().cancel()
        appsDrawerOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        appsDrawerOverlay.alpha = 0f
        appsDrawerOverlay.translationY = dp(52).toFloat()
        appsDrawerOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionPrefs.dur(this, 480L))
            .setInterpolator(easeOutCubic)
            .start()

        drawerScrollView.scrollTo(0, 0)
        val fallbackChild = if (drawerAppsGrid.childCount > 0) drawerAppsGrid.getChildAt(0) else null
        val immediateTarget = drawerTileViews[focusPkg] ?: drawerTileViews[PKG_OPENSTATION_APPS] ?: fallbackChild
        immediateTarget?.requestFocus()
        handler.post {
            if (appsDrawerOpen && !appContextOpen && !appInfoOpen) {
                val target = drawerTileViews[focusPkg]
                    ?: drawerTileViews[PKG_OPENSTATION_APPS]
                    ?: if (drawerAppsGrid.childCount > 0) drawerAppsGrid.getChildAt(0) else null
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
        syncBackdropBlurForUiState(animate = !immediate)

        if (::heroOrbView.isInitialized && currentPage == 0 && !homeCustomOpen && (!::controlCenter.isInitialized || !controlCenter.isOpen)) {
            heroOrbView.animate().alpha(1f)
                .setDuration(MotionPrefs.dur(this, 440L)).setInterpolator(easeOutCubic).start()
        }

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
            .translationY(dp(48).toFloat())
            .setDuration(MotionPrefs.dur(this, 400L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!appsDrawerOpen) {
                    appsDrawerOverlay.visibility = View.GONE
                    appsDrawerOverlay.translationY = 0f
                    syncLayerFocusability()
                    if (!isAnyOverlayActive() && currentPage == 0) {
                        focusPageControlDirect(0)
                    }
                }
            }
            .start()
    }

    // -----------------------------------------------------------------------------------------
    // HOME CUSTOMIZATION OVERLAY (Wallpapers + Widgets + Hidden Apps)
    // -----------------------------------------------------------------------------------------
    private fun showHomeCustomizationMenu() {
        if (!::homeCustomOverlay.isInitialized) return
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        homeCustomOverlay.removeAllViews()

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#3802050E"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        homeCustomOverlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
            setPadding(dp(24), dp(18), dp(24), dp(18))
            bindGlassLens(
                customOverlayLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 24f,
                    baseTint = Color.parseColor("#3E09101E"),
                    focusTint = Color.parseColor("#4410182C"),
                    bevelDp = 16f,
                    refractDp = 14f,
                    chromaDp = 2.6f,
                    shadowDp = 24f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.72f,
                    blurMix = 0.90f
                ),
                fallbackTint = Color.parseColor("#D4080D18")
            )
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                dp(740),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val tvHead = TextView(this).apply {
            text = "Оформление и виджеты"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = YandexFonts.displayBold(this@MainActivity)
        }
        val tvSub = TextView(this).apply {
            text = "Обои с оптическим преломлением Liquid Glass и модули рабочего стола"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            typeface = YandexFonts.displayRegular(this@MainActivity)
            setPadding(0, dp(2), 0, dp(10))
        }
        panel.addView(tvHead)
        panel.addView(tvSub)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }

        body.addView(makeSectionTitle("Тема интерфейса", R.drawable.ic_hw_display))
        val themeModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
        }
        val isClassicNow = LiquidGlassKit.isClassicUi()
        themeModeRow.addView(makeModalChoicePill("✦ Матовое стекло", !isClassicNow) {
            applyUiStyle(LiquidGlassKit.UiStyle.FROSTED)
            showHomeCustomizationMenu()
        })
        themeModeRow.addView(makeModalChoicePill("◈ Классическая", isClassicNow) {
            applyUiStyle(LiquidGlassKit.UiStyle.CLASSIC)
            showHomeCustomizationMenu()
        })
        body.addView(themeModeRow)

        body.addView(makeSectionTitle("Обои рабочего стола", R.drawable.ic_wave))

        val wallGrid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        var firstWallpaperBtn: View? = null
        val wallSwatches = ArrayList<Triple<String, View, TextView>>()
        val tileW = dp(216)
        LiquidGlassKit.THEMES.forEachIndexed { idx, theme ->
            val selectedTheme = theme.id == LiquidGlassKit.currentThemeId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isFocusable = true
                isClickable = true
                bindGlassLens(
                    customOverlayLensCleanups,
                    this,
                    SpatialLensSpec(
                        radiusDp = 14f,
                        baseTint = Color.parseColor("#2A0B1324"),
                        focusTint = Color.parseColor("#54162844"),
                        accentTint = if (selectedTheme) Color.parseColor("#38BDF8") else 0,
                        bevelDp = 11f,
                        refractDp = 10f,
                        chromaDp = 1.8f,
                        shadowDp = 10f,
                        rimAlpha = 0.28f,
                        focusRimAlpha = 0.85f,
                        blurMix = 0.88f,
                        clipParent = scroll
                    ),
                    fallbackTint = Color.parseColor("#44090E18")
                )
                layoutParams = GridLayout.LayoutParams().apply {
                    width = tileW
                    height = dp(52)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
                        .setDuration(MotionPrefs.dur(this@MainActivity, 210L)).setInterpolator(focusSpring).start()
                }
            }
            if (idx == 0) firstWallpaperBtn = card

            val swatch: View = if (theme.drawableRes != 0) {
                FrameLayout(this).apply {
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(9)
                    }
                    addView(ImageView(this@MainActivity).apply {
                        val thumb = LiquidGlassKit.decodeSwatchBitmap(this@MainActivity, theme.drawableRes, dp(48))
                        if (thumb != null) setImageBitmap(thumb) else setImageResource(theme.drawableRes)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    })
                }
            } else {
                View(this).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor(theme.baseDarkHex),
                            Color.parseColor(theme.secondaryHex),
                            Color.parseColor(theme.primaryHex)
                        )
                    ).apply {
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#48FFFFFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(9)
                    }
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
                typeface = YandexFonts.displayBold(this@MainActivity)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvS = TextView(this).apply {
                text = theme.subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 9.5f
                typeface = YandexFonts.textMedium(this@MainActivity)
                maxLines = 1
            }
            txtCol.addView(tvT)
            txtCol.addView(tvS)

            val tvMark = TextView(this).apply {
                text = if (selectedTheme) "✓" else ""
                setTextColor(Color.parseColor("#7DD3FC"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            wallSwatches.add(Triple(theme.id, card, tvMark))

            card.addView(swatch)
            card.addView(txtCol)
            card.addView(tvMark)
            card.setOnClickListener {
                applyWallpaperTheme(theme.id, persist = true)
                wallSwatches.forEach { (id, wCard, badge) ->
                    val act = (id == theme.id)
                    badge.text = if (act) "✓" else ""
                    if (::spatialBackdrop.isInitialized) {
                        spatialBackdrop.updateLensSpec(wCard) {
                            it.copy(accentTint = if (act) Color.parseColor("#38BDF8") else 0)
                        }
                    }
                }
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
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val wCardW = dp(330)
        for ((key, title, desc) in widgetDefs) {
            var enabled = prefs.getBoolean(key, false)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isClickable = true
                bindGlassLens(
                    customOverlayLensCleanups,
                    this,
                    SpatialLensSpec(
                        radiusDp = 14f,
                        baseTint = Color.parseColor("#2A0B1324"),
                        focusTint = Color.parseColor("#54162844"),
                        bevelDp = 11f,
                        refractDp = 10f,
                        chromaDp = 1.8f,
                        shadowDp = 10f,
                        rimAlpha = 0.28f,
                        focusRimAlpha = 0.85f,
                        blurMix = 0.88f,
                        clipParent = scroll
                    ),
                    fallbackTint = Color.parseColor("#44090E18")
                )
                layoutParams = GridLayout.LayoutParams().apply {
                    width = wCardW
                    height = dp(48)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f)
                        .setDuration(MotionPrefs.dur(this@MainActivity, 200L)).setInterpolator(focusSpring).start()
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
                typeface = YandexFonts.displayMedium(this@MainActivity)
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
                    setColor(Color.parseColor(if (enabled) "#E2E8F0" else "#1E293B"))
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
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(12), 0, 0)
        }

        val hiddenCount = getHiddenAppPackages().size
        if (hiddenCount > 0) {
            val btnHidden = makeTextIconPill(R.drawable.ic_yaos_apps, "Скрытые ($hiddenCount)", accent = false, cleanupPool = customOverlayLensCleanups) {
                showHiddenAppsMenu()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                    marginEnd = dp(8)
                }
            }
            actionsRow.addView(btnHidden)
        }

        val btnOpenDrawer = makeTextIconPill(R.drawable.ic_yaos_apps, "Приложения", accent = false, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu(immediate = true)
            openAppsDrawer()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginEnd = dp(8)
            }
        }
        val btnDone = makeTextIconPill(R.drawable.ic_play, "Готово", accent = true, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
        }
        actionsRow.addView(btnOpenDrawer)
        actionsRow.addView(btnDone)
        panel.addView(actionsRow)

        homeCustomOverlay.addView(panel)
        homeCustomOpen = true
        syncBackdropBlurForUiState(animate = true)
        homeCustomOverlay.animate().cancel()
        homeCustomOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        homeCustomOverlay.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        homeCustomOverlay.animate()
            .alpha(1f)
            .setDuration(MotionPrefs.dur(this, 400L))
            .setInterpolator(easeOutCubic)
            .start()
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MotionPrefs.dur(this, 440L))
            .setInterpolator(easeOutCubic)
            .start()

        (firstWallpaperBtn ?: btnDone).requestFocus()
        handler.post { if (homeCustomOpen && !appInfoOpen) (firstWallpaperBtn ?: btnDone).requestFocus() }
    }

    private fun closeHomeCustomizationMenu(immediate: Boolean = false) {
        if (!::homeCustomOverlay.isInitialized) return
        handler.removeCallbacks(otaDialogPollRunnable)
        otaModalOpen = false
        otaModalStatusTv = null
        otaModalChangelogTv = null
        otaModalInstallBtn = null
        timersScreenRef = null
        if (!homeCustomOpen && homeCustomOverlay.visibility != View.VISIBLE) return
        homeCustomOpen = false
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = !immediate)
        if (immediate) {
            homeCustomOverlay.animate().cancel()
            homeCustomOverlay.visibility = View.GONE
            homeCustomOverlay.alpha = 0f
            clearLensCleanups(customOverlayLensCleanups)
            homeCustomOverlay.removeAllViews()
            return
        }
        if (!isAnyOverlayActive()) {
            focusPageControlDirect(currentPage)
        }
        homeCustomOverlay.animate().cancel()
        homeCustomOverlay.animate()
            .alpha(0f)
            .setDuration(MotionPrefs.dur(this, 340L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!homeCustomOpen) {
                    homeCustomOverlay.visibility = View.GONE
                    clearLensCleanups(customOverlayLensCleanups)
                    homeCustomOverlay.removeAllViews()
                    syncLayerFocusability()
                    if (!isAnyOverlayActive() && currentPage == 0) {
                        focusPageControlDirect(0)
                    }
                }
            }
            .start()
    }

    private fun syncServiceChips() {
        updateControlCenterState()
    }

    private fun makeModalGlassPanel(widthDp: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(24), dp(18), dp(24), dp(18))
        bindGlassLens(
            customOverlayLensCleanups,
            this,
            SpatialLensSpec(
                radiusDp = 24f,
                baseTint = Color.parseColor("#3E09101E"),
                focusTint = Color.parseColor("#46121C30"),
                bevelDp = 16f,
                refractDp = 14f,
                chromaDp = 2.6f,
                shadowDp = 24f,
                rimAlpha = 0.34f,
                focusRimAlpha = 0.72f,
                blurMix = 0.90f
            ),
            fallbackTint = Color.parseColor("#D8080D18")
        )
        isClickable = true
        layoutParams = FrameLayout.LayoutParams(
            dp(widthDp),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

    private fun presentOverlayModal(panel: View, initialFocus: View?) {
        if (!::homeCustomOverlay.isInitialized) return
        homeCustomOverlay.removeAllViews()
        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#3802050E"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        homeCustomOverlay.addView(scrim)
        homeCustomOverlay.addView(panel)
        homeCustomOpen = true
        syncBackdropBlurForUiState(animate = true)
        homeCustomOverlay.animate().cancel()
        homeCustomOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        homeCustomOverlay.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        homeCustomOverlay.animate()
            .alpha(1f)
            .setDuration(MotionPrefs.dur(this, 400L))
            .setInterpolator(easeOutCubic)
            .start()
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MotionPrefs.dur(this, 440L))
            .setInterpolator(easeOutCubic)
            .start()
        initialFocus?.requestFocus()
        handler.post { if (homeCustomOpen && !appInfoOpen) initialFocus?.requestFocus() }
    }

    private fun makeModalSubHeading(title: String): TextView = TextView(this).apply {
        text = title.uppercase(Locale.getDefault())
        setTextColor(Color.parseColor("#E8C686"))
        textSize = 10.5f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(5))
    }

    private fun makeModalChoicePill(
        label: String,
        selected: Boolean,
        cleanupPool: MutableList<() -> Unit> = customOverlayLensCleanups,
        onClick: () -> Unit
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            typeface = YandexFonts.displayMedium(this@MainActivity)
            setTextColor(Color.parseColor(if (selected) "#FDE68A" else "#E2E8F0"))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            bindGlassLens(
                cleanupPool,
                this,
                SpatialLensSpec(
                    radiusDp = 999f,
                    baseTint = if (selected) Color.parseColor("#442B2210") else Color.parseColor("#2A0C1220"),
                    focusTint = if (selected) Color.parseColor("#6A403014") else Color.parseColor("#541A2C4A"),
                    accentTint = if (selected) Color.parseColor("#FBBF24") else 0,
                    bevelDp = 10f,
                    refractDp = 9f,
                    chromaDp = 1.6f,
                    shadowDp = 8f,
                    rimAlpha = if (selected) 0.48f else 0.25f,
                    focusRimAlpha = 0.88f,
                    blurMix = 0.86f
                ),
                fallbackTint = Color.parseColor("#44141D2F")
            )
            isSelected = selected
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f)
                    .setDuration(MotionPrefs.dur(this@MainActivity, 210L)).setInterpolator(focusSpring).start()
            }
            setOnClickListener { onClick() }
        }

    private fun showWebQrModal() {
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val panel = makeModalGlassPanel(520)

        panel.addView(TextView(this).apply {
            text = "Веб-пульт на телефоне"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = YandexFonts.displayBold(this@MainActivity)
        })
        panel.addView(TextView(this).apply {
            text = "Наведите камеру смартфона, чтобы управлять музыкой, подсветкой и настройками станции по Wi-Fi"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(14))
        })

        val ip = detectLocalIp(force = true)
        val url = SetupAddress.webUrl(ip)
        val qrSize = dp(210)

        if (url != null) {
            val qrCard = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(18).toFloat()
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(QrGenerator.generateBitmap(url, qrSize))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(qrSize, qrSize)
                })
            }
            panel.addView(qrCard)
            panel.addView(TextView(this).apply {
                text = url
                setTextColor(Color.parseColor("#BAE6FD"))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
        } else {
            panel.addView(TextView(this).apply {
                text = "Подключите станцию к Wi-Fi, чтобы получить адрес веб-панели."
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
            })
        }

        val btnClose = makeTextIconPill(R.drawable.ic_play, "Закрыть", accent = true, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                gravity = Gravity.END
                topMargin = dp(14)
            }
        }
        panel.addView(btnClose)
        presentOverlayModal(panel, btnClose)
    }

    private fun showAndroidTvSettingsModal() {
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val panel = makeModalGlassPanel(680)

        panel.addView(TextView(this).apply {
            text = "Настройки Android TV"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = YandexFonts.displayBold(this@MainActivity)
        })
        panel.addView(TextView(this).apply {
            text = "Системные разделы приставки и беспроводные параметры"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(14))
        })

        val sysGrid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val sysItems = listOf(
            SysItem(R.drawable.sprite_sys_wifi, true, "Wi-Fi и сеть") {
                closeHomeCustomizationMenu(immediate = true)
                openSettingsActions(Settings.ACTION_WIFI_SETTINGS)
            },
            SysItem(R.drawable.sprite_remote_hero, true, "Bluetooth и пульт") {
                closeHomeCustomizationMenu(immediate = true)
                openTvAccessories()
            },
            SysItem(R.drawable.ic_settings, false, "Звук и аудио") {
                closeHomeCustomizationMenu(immediate = true)
                openSettingsActions("com.yandex.tv.settings.ACTION_SOUND", Settings.ACTION_SOUND_SETTINGS)
            },
            SysItem(R.drawable.ic_settings, false, "Дата и время") {
                closeHomeCustomizationMenu(immediate = true)
                openSettingsActions(Settings.ACTION_DATE_SETTINGS)
            },
            SysItem(R.drawable.ic_yaos_apps, false, "Приложения TV") {
                closeHomeCustomizationMenu(immediate = true)
                openSettingsActions(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            },
            SysItem(R.drawable.ic_settings, false, "Все настройки TV") {
                closeHomeCustomizationMenu(immediate = true)
                launchPackageName("com.android.tv.settings")
            }
        )
        var firstTile: View? = null
        sysItems.forEachIndexed { idx, item ->
            val tile = makeSysTile(item.iconRes, item.isSprite, item.label, cleanupPool = customOverlayLensCleanups, onClick = item.onClick)
            if (idx == 0) firstTile = tile
            sysGrid.addView(tile, GridLayout.LayoutParams().apply {
                width = dp(200)
                height = dp(92)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        panel.addView(sysGrid)

        val curDnsLabel = if (dnsModeState == "hostname" && dnsHostState.isNotBlank()) dnsHostState else "Авто"
        val autoDnsTxt = if (dnsAutoFailoverState) "Автоподбор ВКЛ" else "Автоподбор ВЫКЛ"
        val btnDnsMgr = makeModalChoicePill("🛡 Private DNS и обход ($curDnsLabel · $autoDnsTxt)", false) {
            showDnsFailoverModal()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        panel.addView(btnDnsMgr)

        val btnClose = makeTextIconPill(R.drawable.ic_home, "Назад", accent = true, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                gravity = Gravity.END
                topMargin = dp(12)
            }
        }
        panel.addView(btnClose)
        presentOverlayModal(panel, firstTile ?: btnClose)
    }

    private fun updateModalChoicePillVisual(tv: TextView, sel: Boolean) {
        tv.isSelected = sel
        tv.setTextColor(Color.parseColor(if (sel) "#FDE68A" else "#E2E8F0"))
        if (::spatialBackdrop.isInitialized) {
            spatialBackdrop.updateLensSpec(tv) {
                it.copy(
                    baseTint = if (sel) Color.parseColor("#442B2210") else Color.parseColor("#2A0C1220"),
                    focusTint = if (sel) Color.parseColor("#6A403014") else Color.parseColor("#541A2C4A"),
                    accentTint = if (sel) Color.parseColor("#FBBF24") else 0,
                    rimAlpha = if (sel) 0.48f else 0.25f
                )
            }
        }
    }

    private fun showDnsFailoverModal() {
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val panel = makeModalGlassPanel(720)

        panel.addView(TextView(this).apply {
            text = "Private DNS (DoT) и автопереключение"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = YandexFonts.displayBold(this@MainActivity)
        })
        val tvDnsBanner = TextView(this).apply {
            val activeLabel = if (dnsModeState == "hostname" && dnsHostState.isNotBlank()) dnsHostState else "Авто"
            text = dnsFailoverSummaryState.ifBlank { "Активный DNS: $activeLabel · Замер задержки..." }
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        }
        panel.addView(tvDnsBanner)

        val grid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val pillMap = LinkedHashMap<String, TextView>()
        val baseEntries = mutableListOf(
            "" to "Авто (opportunistic)",
            "dns.comss.one" to "Comss Smart DNS (dns.comss.one)",
            "router.comss.one" to "Comss Router (router.comss.one)",
            "dns.malw.link" to "Malw Smart DNS (dns.malw.link)",
            "xbox-dns.ru" to "Xbox DNS (xbox-dns.ru)",
            "dns.xbox-dns.ru" to "Xbox DNS Alt (dns.xbox-dns.ru)",
            "freedns.controld.com" to "Control D Free (freedns.controld.com)",
            "dns.adguard-dns.com" to "AdGuard DNS (dns.adguard-dns.com)",
            "one.one.one.one" to "Cloudflare 1.1.1.1 (one.one.one.one)",
            "dns.google" to "Google DNS (dns.google)"
        )

        fun refreshHighlights(probes: List<DnsPresetUiItem>? = null) {
            val activeKey = if (dnsModeState == "hostname") dnsHostState else ""
            val byHost = probes?.associateBy { it.host }.orEmpty()
            pillMap.forEach { (hostKey, tv) ->
                updateModalChoicePillVisual(tv, hostKey.equals(activeKey, ignoreCase = true))
                val baseTitle = baseEntries.firstOrNull { it.first == hostKey }?.second ?: hostKey
                val pr = byHost[hostKey]
                if (pr != null && pr.tested) {
                    val lat = if (pr.ok) " · ${pr.latencyMs} мс ✓" else " · ✗"
                    tv.text = "${pr.name} ($hostKey)$lat"
                } else {
                    tv.text = baseTitle
                }
            }
        }

        fun applyDnsChoice(host: String) {
            tvDnsBanner.text = "⏳ Применяем DNS: ${host.ifBlank { "Авто" }}..."
            tvDnsBanner.setTextColor(Color.parseColor("#FDE68A"))
            thread(isDaemon = true) {
                val payload = JSONObject()
                    .put("mode", if (host.isBlank()) "opportunistic" else "hostname")
                    .put("host", host)
                    .toString()
                val res = DaemonApi.requestJson("/api/system/dns", "POST", payload, timeoutMs = 4500)
                handler.post {
                    val ok = res?.optBoolean("ok", false) == true
                    if (ok) {
                        dnsModeState = res?.optString("mode", dnsModeState).orEmpty()
                        dnsHostState = res?.optString("host", dnsHostState).orEmpty()
                        tvDnsBanner.text = "✓ Применён DNS: ${dnsHostState.ifBlank { dnsModeState }}"
                        tvDnsBanner.setTextColor(Color.parseColor("#6EE7B7"))
                    } else {
                        tvDnsBanner.text = "✗ Не удалось переключить DNS"
                        tvDnsBanner.setTextColor(Color.parseColor("#FCA5A5"))
                    }
                    refreshHighlights()
                }
            }
        }

        var firstFocus: View? = null
        baseEntries.forEachIndexed { idx, (hostKey, label) ->
            val selected = if (hostKey.isEmpty()) dnsModeState != "hostname" else (dnsModeState == "hostname" && dnsHostState.equals(hostKey, ignoreCase = true))
            val pill = makeModalChoicePill(label, selected) {
                applyDnsChoice(hostKey)
            }.apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            if (idx == 0) firstFocus = pill
            pillMap[hostKey] = pill
            grid.addView(pill, GridLayout.LayoutParams().apply {
                width = dp(328)
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        panel.addView(grid)

        fun runProbeOnModal() {
            tvDnsBanner.text = "⏳ Измерение задержки DoT DNS (порт 853)..."
            tvDnsBanner.setTextColor(Color.parseColor("#FDE68A"))
            thread(isDaemon = true) {
                val res = DaemonApi.requestJson(
                    "/api/system/dns/probe",
                    "POST",
                    JSONObject().put("timeoutMs", 1300).toString(),
                    timeoutMs = 7000
                )
                val parsed = DaemonApi.parseDnsPresets(res?.optJSONArray("presets"))
                handler.post {
                    if (parsed.isNotEmpty()) {
                        dnsModeState = res?.optString("mode", dnsModeState).orEmpty()
                        dnsHostState = res?.optString("host", dnsHostState).orEmpty()
                        val working = parsed.count { it.ok }
                        tvDnsBanner.text = "✓ Доступно $working из ${parsed.size} DNS · Активен: ${dnsHostState.ifBlank { "Авто" }}"
                        tvDnsBanner.setTextColor(Color.parseColor(if (working > 0) "#6EE7B7" else "#FCA5A5"))
                        refreshHighlights(parsed)
                    } else {
                        tvDnsBanner.text = "⚠ Ошибка проверки DNS"
                        tvDnsBanner.setTextColor(Color.parseColor("#FCA5A5"))
                    }
                }
            }
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnAutoSelect = makeModalChoicePill("⚡ Найти рабочий", false) {
            tvDnsBanner.text = "⏳ Подбор самого быстрого DNS..."
            tvDnsBanner.setTextColor(Color.parseColor("#FDE68A"))
            thread(isDaemon = true) {
                val res = DaemonApi.requestJson(
                    "/api/system/dns/failover",
                    "POST",
                    JSONObject().put("force", true).toString(),
                    timeoutMs = 8000
                )
                handler.post {
                    if (res?.optBoolean("ok", false) == true) {
                        dnsModeState = res.optString("mode", dnsModeState)
                        dnsHostState = res.optString("host", dnsHostState)
                        val lat = res.optInt("latencyMs", -1)
                        tvDnsBanner.text = "✓ Выбран рабочий DNS: ${dnsHostState.ifBlank { dnsModeState }}" +
                            if (lat >= 0) " (${lat} мс)" else ""
                        tvDnsBanner.setTextColor(Color.parseColor("#6EE7B7"))
                        runProbeOnModal()
                    } else {
                        tvDnsBanner.text = "✗ Подходящий DoT DNS не ответил"
                        tvDnsBanner.setTextColor(Color.parseColor("#FCA5A5"))
                    }
                }
            }
        }
        lateinit var btnAutoToggle: TextView
        btnAutoToggle = makeModalChoicePill(
            if (dnsAutoFailoverState) "Автоперекл.: ВКЛ" else "Автоперекл.: ВЫКЛ",
            dnsAutoFailoverState
        ) {
            dnsAutoFailoverState = !dnsAutoFailoverState
            btnAutoToggle.text = if (dnsAutoFailoverState) "Автоперекл.: ВКЛ" else "Автоперекл.: ВЫКЛ"
            updateModalChoicePillVisual(btnAutoToggle, dnsAutoFailoverState)
            postAsync(
                "http://127.0.0.1:8080/api/config",
                JSONObject().put("assistant", JSONObject().put("dnsFailoverEnabled", dnsAutoFailoverState)).toString()
            )
            Toast.makeText(
                this,
                if (dnsAutoFailoverState) "Автопереключение DNS включено" else "Автопереключение DNS выключено",
                Toast.LENGTH_SHORT
            ).show()
        }
        val btnProbe = makeModalChoicePill("📡 Пинг", false) { runProbeOnModal() }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val btnBack = makeTextIconPill(R.drawable.ic_home, "Назад", accent = true, cleanupPool = customOverlayLensCleanups) {
            showAndroidTvSettingsModal()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
        }
        actionsRow.addView(btnAutoSelect)
        actionsRow.addView(btnProbe)
        actionsRow.addView(btnAutoToggle)
        actionsRow.addView(spacer)
        actionsRow.addView(btnBack)
        panel.addView(actionsRow)

        presentOverlayModal(panel, firstFocus ?: btnAutoSelect)
        runProbeOnModal()
    }

    private fun showAssistantPersonaModal() {
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val panel = makeModalGlassPanel(690)
        panel.addView(TextView(this).apply {
            text = "Имя, голос и характер ассистента"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = YandexFonts.displayBold(this@MainActivity)
        })
        panel.addView(TextView(this).apply {
            text = "Выберите ключевое слово отклика (Wake Word), тембр голоса и стиль ответов"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(8))
        })

        val nameMap = LinkedHashMap<String, TextView>()
        val voiceMap = LinkedHashMap<String, TextView>()
        val promptMap = LinkedHashMap<String, TextView>()
        val vadMap = LinkedHashMap<Int, TextView>()
        var curPromptKey = AssistantPersonalities.keyForPrompt(assistantPromptState)

        fun updateChoiceVisual(tv: TextView, sel: Boolean) = updateModalChoicePillVisual(tv, sel)

        fun refreshHighlights() {
            val preset = listOf("Алиса", "Джарвис", "Пятница", "Макс")
                .firstOrNull { it.equals(assistantNameState, ignoreCase = true) }
            nameMap["custom"]?.text = if (preset == null) "Своё: $assistantNameState" else "Своё имя"
            nameMap.forEach { (k, tv) -> updateChoiceVisual(tv, k == (preset ?: "custom")) }
            voiceMap.forEach { (k, tv) -> updateChoiceVisual(tv, k == assistantVoiceState) }
            promptMap.forEach { (k, tv) -> updateChoiceVisual(tv, k == curPromptKey) }
            val nearestVad = vadMap.keys.minByOrNull { kotlin.math.abs(it - assistantVadSilenceMsState) } ?: 400
            vadMap.forEach { (k, tv) -> updateChoiceVisual(tv, k == nearestVad) }
        }

        fun applyName(newName: String) {
            val clean = newName.trim().ifEmpty { "Алиса" }
            assistantNameState = clean
            val patch = JSONObject().put("name", clean)
            val wantMale = AssistantPersonalities.isLikelyMaleName(clean)
            val curIsMale = AssistantPersonalities.isMaleVoice(assistantVoiceState)
            if (wantMale != curIsMale) {
                assistantVoiceState = AssistantPersonalities.defaultVoiceForName(clean)
                patch.put("voiceName", assistantVoiceState)
            }
            LiveOrbState.updateAssistantPersona(assistantNameState, assistantVoiceState)
            renderOrbAssistantUi()
            syncServiceChips()
            refreshHighlights()
            postAsync("http://127.0.0.1:8080/api/config", JSONObject().put("assistant", patch).toString())
            Toast.makeText(this, "Имя ассистента: «$clean»", Toast.LENGTH_SHORT).show()
        }

        panel.addView(makeModalSubHeading("Ключевое слово отклика (Wake Word)"))
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        var firstFocusView: View? = null
        listOf("Алиса", "Джарвис", "Пятница", "Макс").forEachIndexed { idx, n ->
            val pill = makeModalChoicePill(n, n.equals(assistantNameState, ignoreCase = true)) {
                applyName(n)
            }
            if (idx == 0) firstFocusView = pill
            nameMap[n] = pill
            nameRow.addView(pill)
        }
        val customBtn = makeModalChoicePill("Своё имя", false) {
            val et = EditText(this).apply {
                hint = "Например: Алиса, Джарвис, Ника..."
                setText(assistantNameState)
                setSingleLine(true)
            }
            AlertDialog.Builder(this)
                .setTitle("Своё имя ассистента")
                .setMessage("Ключевое слово Wake Word и фразы приветствия автоматически перестроятся.")
                .setView(et)
                .setPositiveButton("Сохранить") { _, _ ->
                    val entered = et.text?.toString()?.trim().orEmpty()
                    if (entered.isNotEmpty()) applyName(entered)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        nameMap["custom"] = customBtn
        nameRow.addView(customBtn)
        panel.addView(nameRow)

        panel.addView(makeModalSubHeading("Голос ассистента (Gemini Live TTS)"))
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        listOf(
            "Aoede" to "Aoede (жен.)",
            "Kore" to "Kore (жен.)",
            "Leda" to "Leda (живой)",
            "Puck" to "Puck (муж.)",
            "Charon" to "Charon (глуб.)",
            "Fenrir" to "Fenrir (энерг.)"
        ).forEach { (id, lbl) ->
            val pill = makeModalChoicePill(lbl, id == assistantVoiceState) {
                assistantVoiceState = id
                LiveOrbState.updateAssistantPersona(assistantNameState, id)
                renderOrbAssistantUi()
                refreshHighlights()
                postAsync(
                    "http://127.0.0.1:8080/api/config",
                    JSONObject().put("assistant", JSONObject().put("voiceName", id)).toString()
                )
                Toast.makeText(this, "Голос: $lbl", Toast.LENGTH_SHORT).show()
            }
            voiceMap[id] = pill
            voiceRow.addView(pill)
        }
        panel.addView(voiceRow)

        panel.addView(makeModalSubHeading("Характер ответов"))
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        AssistantPersonalities.presets.forEach { (key, label, fullPrompt) ->
            val pill = makeModalChoicePill(label, key == curPromptKey) {
                curPromptKey = key
                assistantPromptState = fullPrompt
                refreshHighlights()
                postAsync(
                    "http://127.0.0.1:8080/api/config",
                    JSONObject().put("assistant", JSONObject().put("systemPrompt", fullPrompt)).toString()
                )
                Toast.makeText(this, "Характер: $label", Toast.LENGTH_SHORT).show()
            }
            promptMap[key] = pill
            promptRow.addView(pill)
        }
        panel.addView(promptRow)

        panel.addView(makeModalSubHeading("Скорость ответа (пауза конца речи)"))
        val vadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        listOf(
            300 to "300 мс (молниеносно)",
            400 to "400 мс (быстро)",
            600 to "600 мс (стандарт)",
            900 to "900 мс (размеренно)"
        ).forEach { (msVal, lbl) ->
            val pill = makeModalChoicePill(lbl, msVal == assistantVadSilenceMsState) {
                assistantVadSilenceMsState = msVal
                refreshHighlights()
                postAsync(
                    "http://127.0.0.1:8080/api/config",
                    JSONObject().put("assistant", JSONObject().put("vadSilenceMs", msVal)).toString()
                )
                Toast.makeText(this, "Пауза конца речи: ${msVal} мс", Toast.LENGTH_SHORT).show()
            }
            vadMap[msVal] = pill
            vadRow.addView(pill)
        }
        panel.addView(vadRow)

        refreshHighlights()

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnDone = makeTextIconPill(R.drawable.ic_play, "Готово", accent = true, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
        }
        footer.addView(btnDone)
        panel.addView(footer)
        presentOverlayModal(panel, firstFocusView ?: btnDone)
    }

    private fun showHardwareEqModal() {
        if (!hwEqSupportedState) {
            Toast.makeText(this, "Аппаратный DSP SY6045S не обнаружен", Toast.LENGTH_SHORT).show()
            return
        }
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val panel = makeModalGlassPanel(650)
        panel.addView(TextView(this).apply {
            text = "Аппаратный DSP усилителя (SY6045S)"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = YandexFonts.displayBold(this@MainActivity)
        })
        panel.addView(TextView(this).apply {
            text = "24-битные I2C-фильтры усилителя Яндекс Станции Макс"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(8))
        })

        panel.addView(makeModalSubHeading("Профиль звучания"))
        val profMap = LinkedHashMap<String, TextView>()
        val profRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        var firstFocus: View? = null
        listOf(
            "original" to "Оригинал (Яндекс)",
            "hifi" to "Hi-Fi",
            "night" to "Ночь",
            "bass" to "Бас",
            "club" to "Клуб"
        ).forEachIndexed { idx, (id, lbl) ->
            val p = makeModalChoicePill(lbl, id == hwEqProfileState) {
                hwEqProfileState = id
                LiveOrbState.updateHardwareEq(id, hwEqSupportedState)
                syncServiceChips()
                profMap.forEach { (k, tv) ->
                    val sel = (k == hwEqProfileState)
                    tv.isSelected = sel
                    tv.setTextColor(Color.parseColor(if (sel) "#FDE68A" else "#E2E8F0"))
                    if (::spatialBackdrop.isInitialized) {
                        spatialBackdrop.updateLensSpec(tv) {
                            it.copy(
                                baseTint = if (sel) Color.parseColor("#442B2210") else Color.parseColor("#2A0C1220"),
                                focusTint = if (sel) Color.parseColor("#6A403014") else Color.parseColor("#541A2C4A"),
                                accentTint = if (sel) Color.parseColor("#FBBF24") else 0,
                                rimAlpha = if (sel) 0.48f else 0.25f
                            )
                        }
                    }
                }
                postAsync("http://127.0.0.1:8080/api/audio/hardware_eq", JSONObject().put("profile", id).toString())
                Toast.makeText(this, "DSP профиль: $lbl", Toast.LENGTH_SHORT).show()
            }
            if (idx == 0) firstFocus = p
            profMap[id] = p
            profRow.addView(p)
        }
        panel.addView(profRow)

        panel.addView(makeModalSubHeading("Аппаратная защита динамиков (DRC)"))
        val tvDrcInfo = TextView(this).apply {
            text = "Проверка состояния DRC..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(tvDrcInfo)

        lateinit var btnDrc: TextView
        fun renderDrcBtn() {
            if (hwEqDrcBypassedState) {
                tvDrcInfo.text = "Защита динамиков (DRC): ОТКЛЮЧЕНА (Bypass) — берегите громкость!"
                tvDrcInfo.setTextColor(Color.parseColor("#FCA5A5"))
                btnDrc.text = "Включить защиту DRC"
            } else {
                tvDrcInfo.text = "Защита динамиков (DRC): АКТИВНА (рекомендуется)"
                tvDrcInfo.setTextColor(Color.parseColor("#6EE7B7"))
                btnDrc.text = "Отключить защиту DRC (опасно)"
            }
        }
        btnDrc = makeModalChoicePill("Проверка...", false) {
            if (hwEqDrcBypassedState) {
                hwEqDrcBypassedState = false
                renderDrcBtn()
                postAsync("http://127.0.0.1:8080/api/audio/drc_bypass", JSONObject().put("bypass", false).toString())
                Toast.makeText(this, "Защита DRC включена", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Опасное отключение DRC (SY6045S)")
                    .setMessage(
                        "Внимание! Аппаратный лимитер DRC защищает вуфер и твитеры от перегрузки и физического повреждения на высокой громкости.\n\nПродолжить отключение DRC на свой страх и риск?"
                    )
                    .setPositiveButton("Отключить защиту") { _, _ ->
                        hwEqDrcBypassedState = true
                        renderDrcBtn()
                        postAsync("http://127.0.0.1:8080/api/audio/drc_bypass", JSONObject().put("bypass", true).toString())
                        Toast.makeText(this, "DRC Bypass активен", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        panel.addView(btnDrc)
        renderDrcBtn()

        thread(isDaemon = true) {
            try {
                val conn = (URL("http://127.0.0.1:8080/api/audio/drc_bypass").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 900
                    readTimeout = 900
                }
                if (conn.responseCode == 200) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val j = JSONObject(txt)
                    val woofer = j.optBoolean("wooferDrc", true)
                    val tweeters = j.optBoolean("tweetersDrc", true)
                    handler.post {
                        if (homeCustomOpen) {
                            hwEqDrcBypassedState = !(woofer && tweeters)
                            renderDrcBtn()
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {}
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnDone = makeTextIconPill(R.drawable.ic_play, "Закрыть", accent = true, cleanupPool = customOverlayLensCleanups) {
            closeHomeCustomizationMenu()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
        }
        footer.addView(btnDone)
        panel.addView(footer)
        presentOverlayModal(panel, firstFocus ?: btnDone)
    }

    private fun showTimersModal() {
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val screen = TimersScreen(
            this,
            post = { path, body ->
                thread(isDaemon = true) {
                    httpPostJson("http://127.0.0.1:8080$path", body)
                    pollDaemonStatus()
                }
            },
            onClose = { closeHomeCustomizationMenu() }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(820),
                dp(450),
                Gravity.CENTER
            )
        }
        presentOverlayModal(screen, null)
        timersScreenRef = screen
        screen.update(lastTimersJson, lastTimersFetchedMs, lastAlarmsJson, lastRingingAlarmJson)
        screen.focusFirst()
        thread(isDaemon = true) { pollDaemonStatus() }
    }

    // -----------------------------------------------------------------------------------------
    // ANCHORED APP CONTEXT MENU + REORDER + HIDE + UNINSTALL + LIQUID GLASS APP INFO
    // -----------------------------------------------------------------------------------------
    private fun showAnchoredAppMenu(app: AppEntry, anchor: View) {
        if (!::appContextOverlay.isInitialized || !::rootFrame.isInitialized) return
        clearLensCleanups(ctxOverlayLensCleanups)
        appContextOverlay.removeAllViews()
        appContextOverlay.tag = app.pkg

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#4802050E"))
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

        val menuW = dp(262)
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
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(10))
            bindGlassLens(
                ctxOverlayLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 18f,
                    baseTint = Color.parseColor("#4409101E"),
                    focusTint = Color.parseColor("#54121D32"),
                    bevelDp = 14f,
                    refractDp = 12f,
                    chromaDp = 2.2f,
                    shadowDp = 20f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.72f,
                    blurMix = 0.90f
                ),
                fallbackTint = Color.parseColor("#D8090E1A")
            )
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
            typeface = YandexFonts.displayBold(this@MainActivity)
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
            setBackgroundColor(Color.parseColor("#24FFFFFF"))
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
                typeface = YandexFonts.textMedium(this@MainActivity)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isClickable = true
                bindGlassLens(
                    ctxOverlayLensCleanups,
                    this,
                    SpatialLensSpec(
                        radiusDp = 12f,
                        baseTint = Color.parseColor("#240E1526"),
                        focusTint = Color.parseColor("#66223452"),
                        bevelDp = 10f,
                        refractDp = 8f,
                        chromaDp = 1.6f,
                        shadowDp = 8f,
                        rimAlpha = 0.22f,
                        focusRimAlpha = 0.85f,
                        blurMix = 0.85f
                    ),
                    fallbackTint = Color.parseColor("#440E1526")
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
                        .setDuration(MotionPrefs.dur(this@MainActivity, 210L)).setInterpolator(focusSpring).start()
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
            .setDuration(MotionPrefs.dur(this, 320L))
            .setInterpolator(easeOutCubic)
            .start()
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MotionPrefs.dur(this, 360L))
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
            clearLensCleanups(ctxOverlayLensCleanups)
            appContextOverlay.removeAllViews()
            if (restoreFocus && prevPkg != null) focusAppTileByPkg(prevPkg)
            return
        }
        if (restoreFocus && prevPkg != null) focusAppTileByPkg(prevPkg)
        appContextOverlay.animate().cancel()
        appContextOverlay.animate()
            .alpha(0f)
            .setDuration(MotionPrefs.dur(this, 290L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!appContextOpen) {
                    appContextOverlay.visibility = View.GONE
                    clearLensCleanups(ctxOverlayLensCleanups)
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
                null
            }
        }
        findView()?.requestFocus()
        handler.post {
            if (!appContextOpen && !appInfoOpen && appsDrawerOpen) {
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
                null
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
        Toast.makeText(this, "«${app.label}» скрыто (вернуть можно в Оформлении)", Toast.LENGTH_SHORT).show()
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
        clearLensCleanups(infoOverlayLensCleanups)
        appInfoOverlay.removeAllViews()

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#3E02050E"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        appInfoOverlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            bindGlassLens(
                infoOverlayLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 22f,
                    baseTint = Color.parseColor("#3E09101E"),
                    focusTint = Color.parseColor("#46121C30"),
                    bevelDp = 15f,
                    refractDp = 13f,
                    chromaDp = 2.4f,
                    shadowDp = 22f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.72f,
                    blurMix = 0.90f
                ),
                fallbackTint = Color.parseColor("#D8080D18")
            )
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        val tvT = TextView(this).apply {
            text = "Скрытые приложения"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = YandexFonts.displayBold(this@MainActivity)
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
            val row = makeTextIconPill(R.drawable.ic_yaos_apps, "Вернуть: $lbl", accent = false, cleanupPool = infoOverlayLensCleanups) {
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

        val btnClose = makeTextIconPill(R.drawable.ic_play, "Закрыть", accent = true, cleanupPool = infoOverlayLensCleanups) {
            closeAppInfoModal()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
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
        clearLensCleanups(infoOverlayLensCleanups)
        appInfoOverlay.removeAllViews()
        appInfoOverlay.tag = app.pkg

        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#3E02050E"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        appInfoOverlay.addView(scrim)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            bindGlassLens(
                infoOverlayLensCleanups,
                this,
                SpatialLensSpec(
                    radiusDp = 22f,
                    baseTint = Color.parseColor("#3E09101E"),
                    focusTint = Color.parseColor("#46121C30"),
                    bevelDp = 15f,
                    refractDp = 13f,
                    chromaDp = 2.4f,
                    shadowDp = 22f,
                    rimAlpha = 0.34f,
                    focusRimAlpha = 0.72f,
                    blurMix = 0.90f
                ),
                fallbackTint = Color.parseColor("#D8080D18")
            )
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
            (app.icon as? Animatable)?.start()
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
            typeface = YandexFonts.displayBold(this@MainActivity)
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
                typeface = YandexFonts.textMedium(this@MainActivity)
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
            val btnSys = makeTextIconPill(R.drawable.ic_settings, "В системе", accent = false, cleanupPool = infoOverlayLensCleanups) {
                closeAppInfoModal()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.pkg}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Throwable) {}
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                    marginEnd = dp(8)
                }
            }
            btnRow.addView(btnSys)
        }
        val btnClose = makeTextIconPill(R.drawable.ic_play, "Закрыть", accent = true, cleanupPool = infoOverlayLensCleanups) {
            closeAppInfoModal()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
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
        appInfoOverlay.animate().alpha(1f).setDuration(MotionPrefs.dur(this, 360L)).setInterpolator(easeOutCubic).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(MotionPrefs.dur(this, 400L)).setInterpolator(easeOutCubic).start()
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
            } else if (prevPkg != null && appsDrawerOpen) {
                focusAppTileByPkg(prevPkg)
            }
        }
        if (immediate) {
            appInfoOverlay.animate().cancel()
            appInfoOverlay.visibility = View.GONE
            appInfoOverlay.alpha = 0f
            clearLensCleanups(infoOverlayLensCleanups)
            appInfoOverlay.removeAllViews()
            restoreFocusAfterInfo()
            return
        }
        restoreFocusAfterInfo()
        appInfoOverlay.animate().cancel()
        appInfoOverlay.animate()
            .alpha(0f)
            .setDuration(MotionPrefs.dur(this, 320L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!appInfoOpen) {
                    appInfoOverlay.visibility = View.GONE
                    clearLensCleanups(infoOverlayLensCleanups)
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
        val ccActive = ::controlCenter.isInitialized && controlCenter.isOpen
        val musicActive = ::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen
        val saverActive = ::screensaverOverlay.isInitialized && screensaverOverlay.isOpen
        AnimatedSpriteHelper.syncAll(windowActive = !saverActive)
        val drawerActive = appsDrawerOpen
        val anyOverlay = infoActive || ctxActive || customActive || ccActive || musicActive || saverActive || drawerActive

        if (::shellCol.isInitialized) {
            shellCol.descendantFocusability =
                if (anyOverlay) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        if (::appsDrawerOverlay.isInitialized) {
            appsDrawerOverlay.descendantFocusability =
                if (drawerActive && !infoActive && !ctxActive && !customActive && !ccActive && !musicActive && !saverActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::controlCenter.isInitialized) {
            controlCenter.rootOverlay.descendantFocusability =
                if (ccActive && !infoActive && !ctxActive && !customActive && !saverActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::musicPlayerSheet.isInitialized) {
            musicPlayerSheet.rootOverlay.descendantFocusability =
                if (musicActive && !infoActive && !ctxActive && !customActive && !saverActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::screensaverOverlay.isInitialized) {
            screensaverOverlay.rootOverlay.descendantFocusability =
                if (saverActive) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (::homeCustomOverlay.isInitialized) {
            homeCustomOverlay.descendantFocusability =
                if (customActive && !infoActive && !ctxActive && !saverActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::appContextOverlay.isInitialized) {
            appContextOverlay.descendantFocusability =
                if (ctxActive && !infoActive && !saverActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
        if (::appInfoOverlay.isInitialized) {
            appInfoOverlay.descendantFocusability =
                if (infoActive && !saverActive) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
        }
    }

    private fun closeAllOverlays(immediate: Boolean = false) {
        if (::screensaverOverlay.isInitialized) screensaverOverlay.close(immediate = immediate)
        closeAppInfoModal(immediate = immediate)
        closeAnchoredAppMenu(immediate = immediate, restoreFocus = false)
        closeHomeCustomizationMenu(immediate = immediate)
        if (::musicPlayerSheet.isInitialized) musicPlayerSheet.close(immediate = immediate)
        if (::controlCenter.isInitialized) controlCenter.close(immediate = immediate)
        closeAppsDrawer(immediate = immediate)
        syncLayerFocusability()
        syncBackdropBlurForUiState(animate = !immediate)
    }

    private fun handleOverlayBackPressed(): Boolean {
        if (::screensaverOverlay.isInitialized && screensaverOverlay.isOpen) {
            closeScreensaver()
            return true
        }
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
        if (::musicPlayerSheet.isInitialized && musicPlayerSheet.isOpen) {
            closeMusicPlayerSheet()
            return true
        }
        if (::controlCenter.isInitialized && controlCenter.isOpen) {
            controlCenter.close()
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
        if (::controlCenter.isInitialized && controlCenter.isOpen) return false
        if (appsDrawerOpen) {
            return findAppForView(focused) != null
        }
        if (currentPage == 0) {
            if (::eqWidgetController.isInitialized && eqWidgetController.isAdjusting()) return false
            return true
        }
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
        if (currentPage == 0 && !homeCustomOpen && (!::controlCenter.isInitialized || !controlCenter.isOpen)) {
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
            .setDuration(MotionPrefs.dur(this, 160L))
            .setInterpolator(easeOutCubic)
            .withEndAction {
                v.animate()
                    .translationXBy(-dx)
                    .translationYBy(-dy)
                    .setDuration(MotionPrefs.dur(this, 260L))
                    .setInterpolator(easeOutCubic)
                    .start()
            }
            .start()
    }

    private fun showShellResetModal() {
        timersScreenRef = null
        otaModalOpen = false
        clearLensCleanups(customOverlayLensCleanups)
        val panel = makeModalGlassPanel(540)

        panel.addView(TextView(this).apply {
            text = "Сброс оболочки OpenStation"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = YandexFonts.displayBold(this@MainActivity)
        })
        panel.addView(TextView(this).apply {
            text = "Затрагивает только оболочку OpenStation Max и демон openstationd. Система Android TV, Wi-Fi, Bluetooth и установленные приложения не удаляются."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            typeface = YandexFonts.displayRegular(this@MainActivity)
            setPadding(0, dp(6), 0, dp(14))
        })

        var clearAuthAccounts = false
        lateinit var btnClearAuthToggle: TextView
        btnClearAuthToggle = makeModalChoicePill("Аккаунты Яндекс и ключи API: Сохранить", selected = false) {
            clearAuthAccounts = !clearAuthAccounts
            btnClearAuthToggle.isSelected = clearAuthAccounts
            btnClearAuthToggle.text = if (clearAuthAccounts) {
                "Аккаунты Яндекс и ключи API: Очистить"
            } else {
                "Аккаунты Яндекс и ключи API: Сохранить"
            }
            btnClearAuthToggle.setTextColor(Color.parseColor(if (clearAuthAccounts) "#FDE68A" else "#E2E8F0"))
        }
        (btnClearAuthToggle.layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            marginEnd = 0
            bottomMargin = dp(12)
        }
        panel.addView(btnClearAuthToggle)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnRestartOnly = makeModalChoicePill("Перезапустить оболочку", selected = false) {
            closeHomeCustomizationMenu()
            performSoftShellRestart()
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }

        val btnFullReset = makeModalChoicePill("Сбросить к исходным", selected = true) {
            closeHomeCustomizationMenu()
            performOpenStationShellReset(clearAuthAccounts)
        }.apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDA4AF"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }

        val btnCancel = makeModalChoicePill("Отмена", selected = false) {
            closeHomeCustomizationMenu()
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
        }

        btnRow.addView(btnRestartOnly)
        btnRow.addView(btnFullReset)
        btnRow.addView(btnCancel)
        panel.addView(btnRow)

        presentOverlayModal(panel, btnRestartOnly)
    }

    private fun performSoftShellRestart() {
        Toast.makeText(this, "Перезапуск оболочки OpenStation...", Toast.LENGTH_SHORT).show()
        thread(isDaemon = true) {
            httpPostJson("http://127.0.0.1:8080/api/system/restart_daemon", "{}")
            Thread.sleep(600L)
            handler.post {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(i)
                overridePendingTransition(R.anim.spatial_hold_fade_in, R.anim.spatial_hold_fade_out)
                finish()
            }
        }
    }

    private fun performOpenStationShellReset(clearAuth: Boolean) {
        Toast.makeText(this, "Сброс оболочки OpenStation...", Toast.LENGTH_LONG).show()
        thread(isDaemon = true) {
            val payload = JSONObject().put("clearAuth", clearAuth).toString()
            httpPostJson("http://127.0.0.1:8080/api/system/reset_shell", payload)

            val prefNames = arrayListOf(
                "openstation_ui",
                "openstation_apps_prefs",
                "openstation_widgets",
                "openstation_ble_remote",
                SetupWizardActivity.PREFS_NAME
            )
            if (clearAuth) {
                prefNames.add("ym_mod")
                StationAudioService.instance?.musicPlayer?.api?.clearAuth()
            }
            prefNames.forEach { name ->
                try {
                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
                } catch (_: Throwable) {}
            }
            MotionPrefs.setSpeed(this@MainActivity, MotionPrefs.Speed.SMOOTH)
            Thread.sleep(700L)
            handler.post {
                val wizardIntent = Intent(this@MainActivity, SetupWizardActivity::class.java).apply {
                    putExtra(SetupWizardActivity.EXTRA_MANUAL, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(wizardIntent)
                overridePendingTransition(R.anim.spatial_sheet_in_up, R.anim.spatial_hold_fade_out)
                finish()
            }
        }
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
