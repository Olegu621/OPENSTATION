package com.openstation.launcher

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.openstation.launcher.market.MarketInstaller
import com.openstation.launcher.market.MarketPrefs
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
import kotlin.random.Random

/**
 * Ambient launcher: one dashboard (clock, status chips, now playing, Alice orb, app tiles) plus
 * layered screens on top of it — station side sheet, all apps (with the OPENSTATION APPS store),
 * equalizer, timers/alarms, Alice phases, alarm ringing and the screensaver. Back always closes
 * the topmost layer, HOME returns to the dashboard with focus on Play. All UI is built in code.
 */
class MainActivity : Activity() {

    companion object {
        private const val API = "http://127.0.0.1:8080"
        private const val SCREENSAVER_IDLE_MS = 10 * 60_000L
        private const val PKG_OPENSTATION_APPS = "__openstation_apps__"
        private const val PKG_YANDEX_MUSIC = "__ym__"
        private const val APPS_PREFS = "openstation_apps_prefs"
        private const val MAX_FAV_TILES = 6
        private const val GRID_COLUMNS = 5
        private val ACTIVE_ASSISTANT_STATES = setOf("connecting", "listening", "thinking", "speaking", "error")

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
            act.runOnUiThread { act.loadAndRenderApps() }
        }

        fun triggerStartupShow() {
            val act = activeInstance ?: return
            act.runOnUiThread { act.runStartupReveal(fullShow = true) }
        }
    }

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable,
        val intent: Intent,
        val leanback: Boolean,
        val isVirtual: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    // ---- Layers (bottom -> top) ----
    private lateinit var rootFrame: FrameLayout
    private lateinit var dashboard: FrameLayout
    private lateinit var appsPanel: FrameLayout
    private lateinit var stationPanel: FrameLayout
    private lateinit var eqPanel: FrameLayout
    private lateinit var timersScreen: TimersScreen
    private lateinit var aliceLayer: FrameLayout
    private lateinit var alarmScreen: FrameLayout
    private lateinit var screensaver: FrameLayout
    private lateinit var weatherOverlay: WeatherOverlayView
    private val panelStack = ArrayList<ViewGroup>()
    private val panelReturnFocus = HashMap<ViewGroup, View?>()
    private var activeDialog: Dialog? = null

    // ---- Dashboard ----
    private lateinit var dashboardContent: LinearLayout
    private lateinit var tvClock: TextView
    private lateinit var tvDateLine: TextView
    private lateinit var chipTimer: LinearLayout
    private lateinit var tvChipTimer: TextView
    private lateinit var chipIpBg: GradientDrawable
    private lateinit var ivChipIp: ImageView
    private lateinit var tvChipIp: TextView
    private lateinit var chipMicBg: GradientDrawable
    private lateinit var ivChipMic: ImageView
    private lateinit var ivCover: ImageView
    private lateinit var authQrFrame: FrameLayout
    private lateinit var ivAuthQr: ImageView
    private lateinit var playerCol: LinearLayout
    private lateinit var authCol: LinearLayout
    private lateinit var tvSource: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var pbTrack: ProgressBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var btnPrev: FrameLayout
    private lateinit var btnPlay: FrameLayout
    private lateinit var btnNext: FrameLayout
    private lateinit var btnLike: FrameLayout
    private lateinit var btnConnect: TextView
    private lateinit var orbButton: FrameLayout
    private lateinit var orbView: AliceOrbView
    private lateinit var tvOrbTitle: TextView
    private lateinit var tvOrbHint: TextView
    private lateinit var tilesRow: LinearLayout
    private lateinit var tileStation: LinearLayout

    // ---- All apps (+ OPENSTATION APPS store, order, hidden apps, reorder mode) ----
    private lateinit var appsGrid: GridLayout
    private lateinit var appsBackPill: TextView
    private lateinit var btnHiddenApps: TextView
    private lateinit var tvAppsStatus: TextView
    private var appEntries: List<AppEntry> = emptyList()
    private var gridEntries: List<AppEntry> = emptyList()
    private val appTileViews = LinkedHashMap<String, View>()
    private var reorderingPkg: String? = null
    private var okLongPressFired = false
    private var okLongPressAnchor: View? = null
    private val okLongPressRunnable = Runnable {
        okLongPressFired = true
        handleOkLongPress(okLongPressAnchor)
    }

    // ---- Station panel ----
    private lateinit var tvPanelClock: TextView
    private lateinit var tvPanelDate: TextView
    private lateinit var ivQr: ImageView
    private lateinit var tvIpLine: TextView
    private lateinit var tvTelemetry: TextView
    private lateinit var rowRoute: LinearLayout
    private lateinit var tvRouteVal: TextView
    private lateinit var tvEqVal: TextView
    private lateinit var tvRingVal: TextView
    private lateinit var tvVisVal: TextView
    private lateinit var tvTimersVal: TextView
    private lateinit var tvOtaVal: TextView
    private lateinit var tvRemoteVal: TextView
    private lateinit var swMic: SwitchPill
    private lateinit var swCec: SwitchPill
    private lateinit var swBt: SwitchPill

    // ---- Equalizer panel (upstream HomeEqWidgetController) ----
    private lateinit var eqController: HomeEqWidgetController
    private lateinit var eqBackPill: TextView

    // ---- Alice layer ----
    private lateinit var aliceOrb: AliceOrbView
    private lateinit var aliceRingOuter: View
    private lateinit var aliceRingInner: View
    private lateinit var aliceRingInnerBg: GradientDrawable
    private lateinit var aliceCenterCol: LinearLayout
    private lateinit var tvAliceLabel: TextView
    private lateinit var tvAliceText: TextView
    private lateinit var aliceAnswerCol: LinearLayout
    private lateinit var tvAnswerLabel: TextView
    private lateinit var tvAnswerQuote: TextView
    private lateinit var tvAnswerText: TextView
    private lateinit var answerTrackCard: LinearLayout
    private lateinit var ivAnswerCover: ImageView
    private lateinit var tvAnswerTrack: TextView
    private lateinit var tvAnswerArtist: TextView
    private var alicePhaseAnswering: Boolean? = null

    // ---- Alarm / screensaver ----
    private lateinit var tvAlarmTime: TextView
    private lateinit var tvAlarmTitle: TextView
    private lateinit var tvAlarmSub: TextView
    private lateinit var btnSnooze: TextView
    private lateinit var ssContent: LinearLayout
    private lateinit var tvSsClock: TextView
    private lateinit var tvSsDate: TextView
    private lateinit var tvSsWeather: TextView
    private lateinit var ssTrack: LinearLayout
    private lateinit var ivSsCover: ImageView
    private lateinit var tvSsTrack: TextView
    private lateinit var tvSsArtist: TextView

    // ---- OTA dialog ----
    @Volatile private var otaLatestJson: JSONObject? = null
    private var otaDialog: Dialog? = null
    private var otaTitleTv: TextView? = null
    private var otaSubTv: TextView? = null
    private var otaBodyTv: TextView? = null
    private var otaProgress: ProgressBar? = null
    private var otaInstallBtn: TextView? = null
    private var lastOtaValKey = ""

    // ---- Live daemon state ----
    private var fwVer = "1.5.0"
    private var ringMode = "music"
    private var micMuted = false
    private var outputRoute = "auto"
    private var eqPreset = "balanced"
    private var assistantState = "idle"
    private var assistantEnabled = true
    private var aliceConfigured = true
    private var btEnabled = false
    private var btDiscoverable = false
    @Volatile private var daemonOnline = false
    @Volatile private var cachedAppsJson = ""
    private var lastQrIp = ""
    private var setupCompletedState: Boolean? = null
    private var wizardAutoOpenedThisRun = false
    private var cecState = false
    private var timers = JSONArray()
    private var timersFetchedAt = 0L
    private var alarms = JSONArray()
    private var dismissedAlarmId = -1
    private var dismissedAlarmAt = 0L
    private var lastRingingId = -1
    private var resumed = false
    private var lastInputMs = SystemClock.elapsedRealtime()
    private var orbDimmed = false
    private var authMode = false
    @Volatile private var bootRevealStarted = false
    private var startupShowActive = false

    // ---- Cached formatters & diff keys ----
    private val ruLocale = Locale("ru")
    private val timeFmt by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateFmt by lazy { SimpleDateFormat("EEEE, d MMMM", ruLocale) }
    private val clockDate = Date()
    private var lastClockStr = ""
    @Volatile private var cachedLocalIp = "127.0.0.1"
    @Volatile private var lastIpDetectMs = 0L
    private var lastOrbUiKey = ""
    private var lastPanelKey = ""
    private var lastTrackLiked: Boolean? = null
    private var lastPlayIconKey = ""
    private var lastCoverUrl = ""
    private var coverBitmap: Bitmap? = null
    private var latestMusicSnap = YmPlayerSnapshot()

    private val orbStateListener = LiveOrbState.StateListener { renderOrbAssistantUi() }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadAndRenderApps()
    }

    private val musicStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getStringExtra(YandexMusicPlayer.EXTRA_SNAPSHOT_JSON) ?: return
            try {
                renderMusicSnapshot(YmPlayerSnapshot.fromJson(JSONObject(raw)))
            } catch (_: Throwable) {}
        }
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock(force = true)
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
            val active = assistantState != "idle" || LiveOrbState.assistantState != "idle" ||
                latestMusicSnap.playing || panelStack.isNotEmpty() || timers.length() > 0
            handler.postDelayed(this, if (active) 2000L else 5000L)
        }
    }

    /** 1 s tick for locally extrapolated timer countdowns. */
    private val secondTicker = object : Runnable {
        override fun run() {
            renderTimerChip()
            if (timersScreen.visibility == View.VISIBLE) timersScreen.tick()
            if (timers.length() > 0) handler.postDelayed(this, 1000L)
        }
    }

    /** Live spectrum on the equalizer bars while the EQ panel is open. */
    private val eqTickRunnable = object : Runnable {
        override fun run() {
            if (eqPanel.visibility != View.VISIBLE) return
            val live = LiveOrbState.musicPlaying || LiveOrbState.assistantState != "idle"
            eqController.updateLiveSpectrum(LiveOrbState.rawBands, live)
            handler.postDelayed(this, if (live) 45L else 220L)
        }
    }

    private val idleRunnable = object : Runnable {
        override fun run() {
            maybeStartScreensaver()
            handler.postDelayed(this, 30_000L)
        }
    }

    /** Burn-in protection: shift screensaver content a few px every minute. */
    private val screensaverShift = object : Runnable {
        override fun run() {
            ssContent.translationX = Random.nextInt(-dp(24), dp(24) + 1).toFloat()
            ssContent.translationY = Random.nextInt(-dp(16), dp(16) + 1).toFloat()
            handler.postDelayed(this, 60_000L)
        }
    }

    private val hideAliceRunnable = Runnable { hideAliceLayer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        if (Theme.forwardIfGlass(this, com.openstation.launcher.glass.MainActivity::class.java)) return
        activeInstance = this
        window.setBackgroundDrawable(null)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        StationAudioService.ensureStarted(this)
        AliceOrbFrames.preloadAsync(this)

        buildUi()
        loadAndRenderApps()
        updateNetworkHeader()
        updateClock(force = true)
        refreshMusicSnapshotFromService()
        renderOrbAssistantUi()
        syncStationPanel(force = true)
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

    override fun onResume() {
        super.onResume()
        resumed = true
        lastInputMs = SystemClock.elapsedRealtime()
        StationAudioService.setLauncherForegroundState(true)
        StationAudioService.ensureStarted(this)
        LiveOrbState.addListener(orbStateListener)
        if (intent?.getBooleanExtra("go_home", false) == true) {
            intent.removeExtra("go_home")
            goHome()
        }
        updateNetworkHeader()
        updateClock(force = true)
        refreshMusicSnapshotFromService()
        renderOrbAssistantUi()
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(idleRunnable)
        handler.post(clockRunnable)
        handler.post(pollRunnable)
        handler.postDelayed(idleRunnable, 30_000L)
        if (eqPanel.visibility == View.VISIBLE) handler.post(eqTickRunnable)
        if (currentFocus == null) focusPrimary()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(okLongPressRunnable)
        LiveOrbState.removeListener(orbStateListener)
        StationAudioService.setLauncherForegroundState(false)
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(eqTickRunnable)
        hideScreensaver()
        super.onPause()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        LiveOrbState.removeListener(orbStateListener)
        handler.removeCallbacksAndMessages(null)
        try { otaDialog?.dismiss() } catch (_: Throwable) {}
        try { activeDialog?.dismiss() } catch (_: Throwable) {}
        otaDialog = null
        activeDialog = null
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(musicStateReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(clockReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    /** HOME pressed while we are the launcher (or "go_home" from the service). */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent == null || intent.getBooleanExtra("go_home", false) || intent.hasCategory(Intent.CATEGORY_HOME)) {
            intent?.removeExtra("go_home")
            goHome()
        }
    }

    /** Back to the dashboard: close every layer, dialog and mode, focus on Play (FocusMap). */
    private fun goHome() {
        handler.removeCallbacks(okLongPressRunnable)
        hideScreensaver()
        if (reorderingPkg != null) {
            reorderingPkg = null
            loadAndRenderApps()
        }
        try { activeDialog?.dismiss() } catch (_: Throwable) {}
        try { otaDialog?.dismiss() } catch (_: Throwable) {}
        weatherOverlay.visibility = View.GONE
        while (panelStack.isNotEmpty()) closeTopPanel(restoreFocus = false)
        if (alarmScreen.visibility != View.VISIBLE) btnPlay.post { focusPrimary() }
    }

    override fun onBackPressed() {
        when {
            aliceLayer.visibility == View.VISIBLE -> {
                if (assistantActive()) triggerAlice() else hideAliceLayer()
            }
            alarmScreen.visibility == View.VISIBLE -> Unit
            reorderingPkg != null -> finishReorderMode()
            panelStack.isNotEmpty() -> closeTopPanel()
            else -> focusPrimary()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lastInputMs = SystemClock.elapsedRealtime()
        if (screensaver.visibility == View.VISIBLE) {
            if (ev?.action == MotionEvent.ACTION_UP) hideScreensaver()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInputMs = SystemClock.elapsedRealtime()
        if (screensaver.visibility == View.VISIBLE) {
            val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            if (event.action == KeyEvent.ACTION_UP || volumeKey) hideScreensaver()
            if (!volumeKey) return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_DOWN) goHome()
            return true
        }

        // Long-press OK (works the same on the Station BLE remote, CEC remotes and adb keyevents):
        // app tiles open their menu, the orb cycles the visualizer.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (reorderingPkg != null) {
                    if (event.repeatCount == 0) finishReorderMode()
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
                    currentFocus?.isPressed = false
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(okLongPressRunnable)
        }

        if (event.action == KeyEvent.ACTION_DOWN && reorderingPkg != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveReorderingApp(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveReorderingApp(+1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { moveReorderingApp(-GRID_COLUMNS); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveReorderingApp(+GRID_COLUMNS); return true }
            }
        }

        if (panelStack.lastOrNull() === eqPanel && alarmScreen.visibility != View.VISIBLE &&
            eqController.handleKeyEvent(event, currentFocus)
        ) return true

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

    private fun matchLp() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun llp(w: Int, h: Int, weight: Float = 0f, block: LinearLayout.LayoutParams.() -> Unit = {}) =
        LinearLayout.LayoutParams(w, h, weight).apply(block)

    // =========================================================================================
    // BUILD
    // =========================================================================================
    private fun buildUi() {
        rootFrame = FrameLayout(this).apply { background = Theme.ambientBackground() }

        dashboard = buildDashboard()
        appsPanel = buildAppsPanel()
        stationPanel = buildStationPanel()
        eqPanel = buildEqPanel()
        timersScreen = TimersScreen(this) { path, body ->
            postAsync("$API$path", body)
            handler.postDelayed({ pollDaemonStatus() }, 450L)
        }.apply { visibility = View.GONE }
        aliceLayer = buildAliceLayer()
        alarmScreen = buildAlarmScreen()
        screensaver = buildScreensaver()
        weatherOverlay = WeatherOverlayView(this).apply { elevation = dpf(32) }

        rootFrame.addView(dashboard, matchLp())
        rootFrame.addView(appsPanel, matchLp())
        rootFrame.addView(stationPanel, matchLp())
        rootFrame.addView(eqPanel, matchLp())
        rootFrame.addView(timersScreen, matchLp())
        rootFrame.addView(aliceLayer, matchLp())
        rootFrame.addView(alarmScreen, matchLp())
        rootFrame.addView(screensaver, matchLp())
        rootFrame.addView(weatherOverlay, matchLp())
        setContentView(rootFrame)

        applyFocusLayers()
        btnPlay.post { focusPrimary() }
    }

    // -----------------------------------------------------------------------------------------
    // Dashboard (Main.dc.html)
    // -----------------------------------------------------------------------------------------
    private fun buildDashboard(): FrameLayout {
        val page = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(44), dp(56), dp(40))
        }
        dashboardContent = col
        page.addView(col, matchLp())

        // --- Top row: clock + date/weather | status chips ---
        val top = FrameLayout(this)
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvClock = label("--:--", 104f, Theme.TEXT, 300).apply { letterSpacing = -0.04f }
        tvDateLine = label("", 20f, Theme.alpha(Theme.TEXT, 0.78f), 500)
        clockCol.addView(tvClock)
        clockCol.addView(tvDateLine, llp(-2, -2) { topMargin = dp(10) })
        top.addView(clockCol)

        val chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chipTimer = makeChip(Theme.alpha(Theme.accent, 0.45f)).apply {
            visibility = View.GONE
            addView(icon(R.drawable.ic_timer, 16, Theme.TEXT), llp(dp(16), dp(16)) { marginEnd = dp(8) })
        }
        tvChipTimer = label("", 15f, Theme.TEXT, 700)
        chipTimer.addView(tvChipTimer)
        chips.addView(chipTimer, llp(-2, dp(36)) { marginEnd = dp(10) })

        val chipIp = makeChip(Theme.CHIP)
        chipIpBg = chipIp.background as GradientDrawable
        ivChipIp = icon(R.drawable.ic_hw_wifi, 16, Theme.alpha(Theme.TEXT, 0.8f))
        tvChipIp = label("Подключение…", 14f, Theme.alpha(Theme.TEXT, 0.8f))
        chipIp.addView(ivChipIp, llp(dp(16), dp(16)) { marginEnd = dp(8) })
        chipIp.addView(tvChipIp)
        chips.addView(chipIp, llp(-2, dp(36)) { marginEnd = dp(10) })

        chipMicBg = Theme.oval(Theme.CHIP)
        ivChipMic = icon(R.drawable.ic_hw_mic_on, 16, Theme.alpha(Theme.TEXT, 0.8f))
        val chipMic = FrameLayout(this).apply {
            background = chipMicBg
            addView(ivChipMic, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }
        chips.addView(chipMic, llp(dp(36), dp(36)))
        top.addView(chips, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { topMargin = dp(8) })
        col.addView(top, llp(-1, -2))

        // --- Middle: now playing card + Alice orb ---
        val middle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        middle.addView(buildNowPlayingCard(), llp(dp(700), -2) { marginEnd = dp(32) })
        middle.addView(buildOrbColumn(), llp(0, -2, 1f))
        col.addView(middle, llp(-1, 0, 1f))

        // --- Bottom: app tiles (filled by renderDashboardTiles) ---
        tilesRow = LinearLayout(this)
        tileStation = makeTile(R.drawable.ic_settings, "Станция") { openStationPanel() }.apply { id = View.generateViewId() }
        col.addView(tilesRow, llp(-1, dp(96)))
        return page
    }

    private fun makeChip(bg: Int): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        background = Theme.rounded(bg, dpf(18))
    }

    private fun buildNowPlayingCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = Theme.rounded(Color.argb(107, 12, 12, 18), dpf(28))
        }
        ivCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Theme.coverPlaceholder(dpf(18))
            clipToOutline = true
            elevation = dpf(12)
        }
        card.addView(ivCover, llp(dp(176), dp(176)) { marginEnd = dp(28) })

        ivAuthQr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        authQrFrame = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = Theme.rounded(Color.WHITE, dpf(16))
            addView(ivAuthQr, matchLp())
        }
        card.addView(authQrFrame, llp(dp(150), dp(150)) { marginEnd = dp(28) })

        // Player
        playerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvSource = eyebrow("Моя волна", Theme.accent, 13f)
        tvTitle = label("Моя волна", 32f, Theme.TEXT, 700).apply { letterSpacing = -0.015f }
        tvArtist = label("Нажмите ▶, чтобы начать", 18f, Theme.alpha(Theme.TEXT, 0.72f))
        playerCol.addView(tvSource)
        playerCol.addView(tvTitle, llp(-1, -2) { topMargin = dp(6) })
        playerCol.addView(tvArtist, llp(-1, -2) { topMargin = dp(6) })
        pbTrack = accentProgress(4)
        playerCol.addView(pbTrack, llp(-1, dp(4)) { topMargin = dp(16) })
        val times = LinearLayout(this)
        tvPos = label("0:00", 13f, Theme.alpha(Theme.TEXT, 0.6f))
        tvDur = label("0:00", 13f, Theme.alpha(Theme.TEXT, 0.6f))
        times.addView(tvPos, llp(0, -2, 1f))
        times.addView(tvDur)
        playerCol.addView(times, llp(-1, -2) { topMargin = dp(6) })

        val ctrl = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        btnPrev = circleButton(R.drawable.ic_prev, 48, 20, primary = false) { sendMusicAction("PREV") }
        btnPlay = circleButton(R.drawable.ic_play, 60, 22, primary = true) {
            if (latestMusicSnap.currentTrack == null) sendMusicServiceCmd("play_my_wave") else sendMusicAction("PLAY_PAUSE")
        }
        btnNext = circleButton(R.drawable.ic_next, 48, 20, primary = false) { sendMusicAction("NEXT") }
        btnLike = circleButton(R.drawable.ic_heart_outline, 48, 20, primary = false) { sendMusicAction("LIKE") }
        listOf(btnPrev, btnPlay, btnNext, btnLike).forEachIndexed { i, b ->
            b.id = View.generateViewId()
            b.nextFocusUpId = b.id
            ctrl.addView(b, llp(b.layoutParams.width, b.layoutParams.height) { if (i < 3) marginEnd = dp(12) })
        }
        btnPrev.nextFocusLeftId = btnPrev.id
        btnPrev.contentDescription = "Предыдущий"
        btnNext.contentDescription = "Следующий"
        btnLike.contentDescription = "Нравится"
        btnPlay.setOnLongClickListener { launchYandexMusic(); true }
        playerCol.addView(ctrl, llp(-2, -2) { topMargin = dp(14) })
        card.addView(playerCol, llp(0, -2, 1f))

        // Empty state: Yandex Music not authorised (EmptyTv.dc.html)
        authCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        authCol.addView(eyebrow("Музыка", Theme.accent, 13f))
        authCol.addView(label("Подключите Яндекс Музыку", 28f, Theme.TEXT, 700), llp(-1, -2) { topMargin = dp(10) })
        authCol.addView(label("Отсканируйте код телефоном и вставьте токен — так быстрее, чем печатать пультом.",
            17f, Theme.alpha(Theme.TEXT, 0.7f), lines = 3).apply { setLineSpacing(0f, 1.3f) }, llp(-1, -2) { topMargin = dp(10) })
        btnConnect = pillButton("Ввести на телевизоре", primary = true, heightDp = 48) { launchYandexMusic() }.apply {
            id = View.generateViewId()
            nextFocusUpId = id
            nextFocusLeftId = id
        }
        authCol.addView(btnConnect, llp(-2, dp(48)) { topMargin = dp(14) })
        card.addView(authCol, llp(0, -2, 1f))
        return card
    }

    private fun buildOrbColumn(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        orbView = AliceOrbView(this).apply {
            isClickable = false
            isFocusable = false
        }
        orbButton = FrameLayout(this).apply {
            id = View.generateViewId()
            contentDescription = "Алиса"
            background = Theme.focusable(Color.TRANSPARENT, 0f, dp(3), Color.WHITE,
                focusedFill = Theme.alpha(Color.WHITE, 0.06f), isOval = true)
            isFocusable = true
            isClickable = true
            setOnClickListener { triggerAlice() }
            setOnLongClickListener {
                if (!okLongPressFired) cycleVisualizerStyle()
                true
            }
            nextFocusUpId = id
            nextFocusRightId = id
            nextFocusLeftId = btnLike.id
            btnLike.nextFocusRightId = id
            btnConnect.nextFocusRightId = id
            addView(orbView, FrameLayout.LayoutParams(dp(196), dp(196), Gravity.CENTER))
        }
        col.addView(orbButton, llp(dp(232), dp(232)))
        tvOrbTitle = label("Скажите «Алиса»", 20f, Theme.TEXT, 600).apply { gravity = Gravity.CENTER }
        tvOrbHint = label("или нажмите кнопку микрофона на пульте", 15f, Theme.alpha(Theme.TEXT, 0.65f), lines = 2).apply {
            gravity = Gravity.CENTER
        }
        col.addView(tvOrbTitle, llp(-2, -2) { topMargin = dp(4) })
        col.addView(tvOrbHint, llp(-2, -2) { topMargin = dp(6) })
        return col
    }

    private fun makeTile(iconRes: Int, title: String, iconDrawable: Drawable? = null, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            // Icon left, title right: a 96dp-tall card with a tiny corner icon read as empty.
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Theme.focusable(Color.argb(107, 12, 12, 18), dpf(20), dp(3))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            val iv = if (iconDrawable != null) {
                ImageView(context).apply { setImageDrawable(iconDrawable); scaleType = ImageView.ScaleType.FIT_CENTER }
            } else {
                // Same 56dp footprint as app icons: glyph on a soft chip.
                icon(iconRes, 30, Theme.TEXT).apply {
                    background = GradientDrawable().apply { setColor(Color.argb(28, 255, 255, 255)); cornerRadius = dpf(16) }
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                }
            }
            addView(iv, llp(dp(56), dp(56)))
            addView(label(title, 17f, Theme.TEXT, 600, lines = 2), llp(0, -2, 1f) { marginStart = dp(14) })
        }

    private fun renderDashboardTiles() {
        val focusedIdx = (0 until tilesRow.childCount).indexOfFirst { tilesRow.getChildAt(it).isFocused }
        tilesRow.removeAllViews()
        val tiles = ArrayList<LinearLayout>()
        val favs = getFavoritePackages().mapNotNull { pkg ->
            if (pkg == PKG_YANDEX_MUSIC) pkg to null else appEntries.firstOrNull { it.pkg == pkg }?.let { pkg to it }
        }.take(MAX_FAV_TILES)
        if (favs.isNotEmpty()) {
            favs.forEach { (pkg, app) ->
                tiles.add(if (app == null) makeTile(R.drawable.ic_wave, "Яндекс Музыка") { launchYandexMusic() }
                          else makeTile(0, app.label, app.icon) { launchApp(app) })
            }
        } else autoTiles(tiles)
        tiles.add(makeTile(R.drawable.ic_yaos_apps, "Все приложения") { openAppsPanel() })
        layoutDashboardTiles(tiles, focusedIdx)
    }

    /** No favourites yet: Yandex Music + YouTube + Media Center when installed. */
    private fun autoTiles(tiles: ArrayList<LinearLayout>) {
        tiles.add(makeTile(R.drawable.ic_wave, "Яндекс Музыка") { launchYandexMusic() })
        val ytPkgs = listOf(
            "com.google.android.youtube.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
            "com.teamsmart.videomanager.tv", "com.google.android.youtube"
        )
        val real = appEntries.filterNot { it.isVirtual }
        val yt = ytPkgs.firstNotNullOfOrNull { p -> real.firstOrNull { it.pkg == p } }
            ?: real.firstOrNull { it.label.contains("YouTube", true) || it.label.contains("SmartTube", true) }
        if (yt != null) tiles.add(makeTile(R.drawable.ic_youtube, yt.label) { launchApp(yt) })
        val mc = real.firstOrNull {
            it.pkg == "org.xbmc.kodi" || it.label.contains("Media Center", true) || it.label.contains("Kodi", true)
        }
        if (mc != null) tiles.add(makeTile(R.drawable.ic_hw_display, mc.label) { launchApp(mc) })
    }

    private fun layoutDashboardTiles(tiles: List<LinearLayout>, focusedIdx: Int) {
        // Up to 4 + "Все приложения" + "Станция" keep the mockup's 200dp tiles; more share the row.
        val fixed = tiles.size <= 5
        tiles.forEach { t ->
            t.id = View.generateViewId()
            tilesRow.addView(t, if (fixed) llp(dp(200), -1) { marginEnd = dp(16) } else llp(0, -1, 1f) { marginEnd = dp(12) })
        }
        tiles.first().nextFocusLeftId = tiles.first().id
        if (fixed) tilesRow.addView(View(this), llp(0, 1, 1f))
        (tileStation.parent as? ViewGroup)?.removeView(tileStation)
        tilesRow.addView(tileStation, if (fixed) llp(dp(200), -1) else llp(0, -1, 1f))
        tileStation.nextFocusUpId = orbButton.id
        tileStation.nextFocusRightId = tileStation.id
        tileStation.nextFocusDownId = tileStation.id
        tiles.forEach { it.nextFocusDownId = it.id }
        orbButton.nextFocusDownId = tileStation.id
        listOf(btnPrev, btnPlay, btnNext, btnLike, btnConnect).forEach { it.nextFocusDownId = tiles[0].id }
        updatePrimaryFocusLinks()
        if (focusedIdx >= 0) tilesRow.getChildAt(focusedIdx.coerceAtMost(tilesRow.childCount - 1))?.requestFocus()
    }

    /** FocusMap.dc.html: "up" from any tile goes to the player's main button. */
    private fun updatePrimaryFocusLinks() {
        val primaryId = primaryFocusView().id
        for (i in 0 until tilesRow.childCount) {
            val v = tilesRow.getChildAt(i)
            if (v.isFocusable && v !== tileStation) v.nextFocusUpId = primaryId
        }
        orbButton.nextFocusLeftId = if (authMode) btnConnect.id else btnLike.id
    }

    private fun primaryFocusView(): View = if (authMode) btnConnect else btnPlay

    private fun focusPrimary() {
        // In touch mode requestFocus() is a no-op; the first D-pad press then restores the
        // "focused by default" view, so mark the primary button as that default too.
        btnPlay.isFocusedByDefault = !authMode
        btnConnect.isFocusedByDefault = authMode
        if (panelStack.isEmpty() && alarmScreen.visibility != View.VISIBLE) primaryFocusView().requestFocus()
    }

    // -----------------------------------------------------------------------------------------
    // Layer management (focus is confined to the topmost interactive layer)
    // -----------------------------------------------------------------------------------------
    private fun topLayer(): ViewGroup = when {
        alarmScreen.visibility == View.VISIBLE -> alarmScreen
        panelStack.isNotEmpty() -> panelStack.last()
        else -> dashboard
    }

    private fun applyFocusLayers() {
        val top = topLayer()
        for (l in listOf(dashboard, appsPanel, stationPanel, eqPanel, timersScreen, alarmScreen)) {
            l.descendantFocusability = if (l === top) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        // Panels are opaque: stop drawing (and animating the orb on) the dashboard underneath.
        dashboard.visibility = if (panelStack.isEmpty()) View.VISIBLE else View.INVISIBLE
    }

    private fun openPanel(panel: ViewGroup, focus: View) {
        if (panelStack.contains(panel)) return
        panelReturnFocus[panel] = currentFocus
        panelStack.add(panel)
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(180L).start()
        applyFocusLayers()
        focus.requestFocus()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun closeTopPanel(restoreFocus: Boolean = true) {
        val panel = panelStack.removeLastOrNull() ?: return
        panel.animate().cancel()
        panel.visibility = View.GONE
        if (panel === eqPanel) handler.removeCallbacks(eqTickRunnable)
        applyFocusLayers()
        val back = panelReturnFocus.remove(panel)
        if (!restoreFocus) return
        if (back != null && back.isAttachedToWindow && back.isShown) back.requestFocus() else focusPrimary()
    }

    private fun makeBackPill(): TextView = label("←  Назад", 15f, Theme.TEXT, 600).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(18), 0)
        background = Theme.focusable(Theme.BUTTON, dpf(20), dp(3))
        isFocusable = true
        isClickable = true
        setOnClickListener { onBackPressed() }
    }

    // -----------------------------------------------------------------------------------------
    // All apps: OPENSTATION APPS store pinned first, user order, hidden apps, reorder mode
    // -----------------------------------------------------------------------------------------
    private fun buildAppsPanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            visibility = View.GONE
            background = Theme.ambientBackground()
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(44), dp(56), 0)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        appsBackPill = makeBackPill()
        header.addView(appsBackPill, llp(-2, dp(40)) { marginEnd = dp(20) })
        header.addView(label("Все приложения", 34f, Theme.TEXT, 700), llp(0, -2, 1f))
        tvAppsStatus = label("", 15f, Theme.alpha(Theme.TEXT, 0.6f))
        header.addView(tvAppsStatus, llp(-2, -2) { marginEnd = dp(16) })
        btnHiddenApps = label("Скрытые", 15f, Theme.TEXT, 600).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = Theme.focusable(Theme.BUTTON, dpf(20), dp(3))
            isFocusable = true
            isClickable = true
            setOnClickListener { showHiddenAppsMenu() }
        }
        header.addView(btnHiddenApps, llp(-2, dp(40)))
        col.addView(header, llp(-1, -2) { bottomMargin = dp(24) })
        appsGrid = GridLayout(this).apply {
            columnCount = GRID_COLUMNS
            useDefaultMargins = false
        }
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(40))
            addView(appsGrid)
        }, llp(-1, 0, 1f))
        panel.addView(col, matchLp())
        return panel
    }

    private fun openAppsPanel() {
        val first = appsGrid.getChildAt(0) ?: return
        openPanel(appsPanel, first)
    }

    private fun getHiddenAppPackages(): MutableSet<String> {
        val raw = getSharedPreferences(APPS_PREFS, MODE_PRIVATE).getStringSet("hidden_apps", emptySet()) ?: emptySet()
        return HashSet(raw).apply { remove(PKG_OPENSTATION_APPS) }
    }

    private fun saveHiddenAppPackages(set: Set<String>) {
        getSharedPreferences(APPS_PREFS, MODE_PRIVATE).edit()
            .putStringSet("hidden_apps", HashSet(set).apply { remove(PKG_OPENSTATION_APPS) })
            .apply()
    }

    /** Favourites (insertion order) are shown as tiles on the dashboard instead of the auto picks. */
    private fun getFavoritePackages(): List<String> = try {
        val arr = JSONArray(getSharedPreferences(APPS_PREFS, MODE_PRIVATE).getString("favorites", "[]"))
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (_: Throwable) { emptyList() }

    private fun toggleFavorite(app: AppEntry) {
        val favs = getFavoritePackages().toMutableList()
        if (!favs.remove(app.pkg)) favs.add(app.pkg)
        getSharedPreferences(APPS_PREFS, MODE_PRIVATE).edit().putString("favorites", JSONArray(favs).toString()).apply()
        renderDashboardTiles()
    }

    private fun getSavedAppOrder(): List<String> {
        val raw = getSharedPreferences(APPS_PREFS, MODE_PRIVATE).getString("app_order", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() && it != PKG_OPENSTATION_APPS }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveAppOrder(order: List<String>) {
        val arr = JSONArray()
        order.filter { it.isNotBlank() && it != PKG_OPENSTATION_APPS }.distinct().forEach { arr.put(it) }
        getSharedPreferences(APPS_PREFS, MODE_PRIVATE).edit().putString("app_order", arr.toString()).apply()
    }

    private fun loadAndRenderApps() {
        val pm = packageManager
        val found = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, isLeanback: Boolean) {
            val qi = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(qi, 0)) {
                val pkg = ri.activityInfo.packageName ?: continue
                if (pkg == packageName || pkg == "com.openstation.ime" || pkg == "com.openstation.market") continue
                if (pkg.startsWith("com.yandex.") || pkg.startsWith("ru.yandex.")) continue
                val existing = found[pkg]
                if (existing != null && existing.leanback) continue
                val launch = (if (isLeanback) pm.getLeanbackLaunchIntentForPackage(pkg) else pm.getLaunchIntentForPackage(pkg))
                    ?: Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val lbl = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
                val ico = try { ri.loadIcon(pm) } catch (_: Throwable) { applicationInfo.loadIcon(pm) }
                found[pkg] = AppEntry(pkg, lbl, ico, launch, isLeanback)
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val baseEntries = found.values.sortedWith(
            compareByDescending<AppEntry> { it.leanback }.thenBy { it.label.lowercase(Locale.getDefault()) }
        )
        appEntries = baseEntries

        val storeEntry = AppEntry(
            PKG_OPENSTATION_APPS, "OPENSTATION APPS",
            getDrawable(R.drawable.ic_launcher_open) ?: applicationInfo.loadIcon(pm),
            Intent(this, MarketActivity::class.java), leanback = true, isVirtual = true
        )
        val ymEntry = AppEntry(
            PKG_YANDEX_MUSIC, "Яндекс Музыка",
            android.graphics.drawable.LayerDrawable(arrayOf(
                Theme.coverPlaceholder(dpf(14)),
                getDrawable(R.drawable.ic_music_note)!!.mutate().apply { setTint(Color.WHITE) }
            )).apply { val p = dp(14); setLayerInset(1, p, p, p, p) },
            Intent(this, YandexMusicActivity::class.java), leanback = true, isVirtual = true
        )
        val orderIdx = getSavedAppOrder().withIndex().associate { it.value to it.index }
        val reorderable = listOf(ymEntry) + baseEntries
        val sorted = reorderable.withIndex()
            .sortedBy { orderIdx[it.value.pkg] ?: (10_000 + it.index) }
            .map { it.value }
        val hidden = getHiddenAppPackages()
        gridEntries = listOf(storeEntry) + sorted.filterNot { hidden.contains(it.pkg) }

        val focusedPkg = appTileViews.entries.firstOrNull { it.value.isFocused }?.key
        appsGrid.removeAllViews()
        appTileViews.clear()
        val gap = dp(16)
        val colW = (resources.displayMetrics.widthPixels - dp(112 + 8) - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        gridEntries.forEachIndexed { idx, app ->
            val moving = app.pkg == reorderingPkg
            val title = if (moving) "◄ ${app.label} ►" else app.label
            val tile = makeTile(0, title, iconDrawable = app.icon) { launchApp(app) }.apply {
                tag = app.pkg
                if (moving) background = Theme.focusable(Theme.alpha(Theme.accent, 0.35f), dpf(20), dp(3), Color.WHITE)
                setOnLongClickListener {
                    if (!okLongPressFired && reorderingPkg == null) showAppMenu(app)
                    true
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = colW
                    height = dp(112)
                    setMargins(0, 0, if (idx % GRID_COLUMNS < GRID_COLUMNS - 1) gap else 0, gap)
                }
            }
            (tile.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = dp(40); height = dp(40) }
            appTileViews[app.pkg] = tile
            appsGrid.addView(tile)
        }
        btnHiddenApps.text = "Скрытые (${hidden.size})"
        btnHiddenApps.visibility = if (hidden.isNotEmpty()) View.VISIBLE else View.GONE
        tvAppsStatus.text = when {
            reorderingPkg != null -> "◄ ► ▲ ▼ — сдвиг · OK — готово"
            else -> "${gridEntries.size} прилож. · удерж. OK — меню"
        }
        renderDashboardTiles()
        if (focusedPkg != null && appsPanel.visibility == View.VISIBLE) appTileViews[focusedPkg]?.requestFocus()

        val arr = JSONArray()
        for (a in baseEntries) {
            arr.put(JSONObject().put("package", a.pkg).put("label", a.label).put("leanback", a.leanback))
        }
        cachedAppsJson = arr.toString()
        postAsync("$API/api/apps/cache", cachedAppsJson)
    }

    private fun focusAppTile(pkg: String) {
        val v = appTileViews[pkg] ?: appTileViews[PKG_OPENSTATION_APPS] ?: return
        v.requestFocus()
        handler.post { if (activeDialog == null) v.requestFocus() }
    }

    private fun shouldArmOkLongPress(focused: View?): Boolean =
        focused != null && (focused === orbButton || (focused.tag as? String)?.let { appTileViews[it] === focused } == true)

    private fun handleOkLongPress(anchor: View?) {
        anchor?.isPressed = false
        if (anchor === orbButton) {
            cycleVisualizerStyle()
            return
        }
        val pkg = anchor?.tag as? String ?: return
        gridEntries.firstOrNull { it.pkg == pkg }?.let { showAppMenu(it) }
    }

    private fun startReorderMode(app: AppEntry) {
        reorderingPkg = app.pkg
        loadAndRenderApps()
        focusAppTile(app.pkg)
    }

    private fun moveReorderingApp(delta: Int) {
        val pkg = reorderingPkg ?: return
        val movable = gridEntries.drop(1).map { it.pkg }.toMutableList()
        val idx = movable.indexOf(pkg)
        if (idx < 0 || movable.size < 2) return
        val newIdx = (idx + delta).coerceIn(0, movable.lastIndex)
        if (newIdx == idx) return
        movable.add(newIdx, movable.removeAt(idx))
        saveAppOrder(movable)
        loadAndRenderApps()
        handler.post { focusAppTile(pkg) }
    }

    private fun finishReorderMode() {
        val pkg = reorderingPkg ?: return
        reorderingPkg = null
        loadAndRenderApps()
        focusAppTile(pkg)
        Toast.makeText(this, "Расположение сохранено", Toast.LENGTH_SHORT).show()
    }

    private fun hideAppPackage(app: AppEntry) {
        saveHiddenAppPackages(getHiddenAppPackages().apply { add(app.pkg) })
        loadAndRenderApps()
        focusAppTile(PKG_OPENSTATION_APPS)
        Toast.makeText(this, "«${app.label}» скрыто · вернуть: «Скрытые» вверху экрана", Toast.LENGTH_SHORT).show()
    }

    private fun uninstallAppEntry(app: AppEntry) {
        Toast.makeText(this, "Удаление ${app.label}…", Toast.LENGTH_SHORT).show()
        MarketInstaller.uninstallPackage(this, app.pkg, MarketPrefs.isPreferDaemonRoot(this)) { ok, msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (ok) {
                    loadAndRenderApps()
                    focusAppTile(PKG_OPENSTATION_APPS)
                }
            }
        }
    }

    private fun isSystemPackage(pkg: String): Boolean = try {
        (packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Throwable) { true }

    private fun openAppSystemDetails(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {}
    }

    private fun showAppMenu(app: AppEntry) {
        val pinned = app.pkg == PKG_OPENSTATION_APPS
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Открыть" to { launchApp(app) })
        if (!pinned) {
            val fav = app.pkg in getFavoritePackages()
            items.add((if (fav) "Убрать с главного экрана" else "На главный экран") to { toggleFavorite(app) })
            items.add("Переместить" to { startReorderMode(app) })
            items.add("Скрыть" to { hideAppPackage(app) })
        }
        if (!app.isVirtual) {
            if (isSystemPackage(app.pkg)) {
                items.add("Параметры пакета" to { openAppSystemDetails(app.pkg) })
            } else {
                items.add("Удалить" to { uninstallAppEntry(app) })
            }
        }
        items.add("О приложении" to { showAppInfo(app) })
        showMenuDialog(app.label, if (pinned) "Магазин ТВ · APK · всегда первый" else null, items) { focusAppTile(app.pkg) }
    }

    private fun showHiddenAppsMenu() {
        val hidden = getHiddenAppPackages().toList().sorted()
        if (hidden.isEmpty()) return
        val items = hidden.map { pkg ->
            val lbl = when (pkg) {
                PKG_YANDEX_MUSIC -> "Яндекс Музыка"
                else -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
            }
            "Вернуть: $lbl" to {
                saveHiddenAppPackages(getHiddenAppPackages().apply { remove(pkg) })
                loadAndRenderApps()
            }
        }
        showMenuDialog("Скрытые приложения", "Выберите, что вернуть в список", items) {
            if (btnHiddenApps.isShown) btnHiddenApps.requestFocus() else focusAppTile(PKG_OPENSTATION_APPS)
        }
    }

    private fun showAppInfo(app: AppEntry) {
        val details = ArrayList<Pair<String, String>>()
        when (app.pkg) {
            PKG_OPENSTATION_APPS -> {
                details.add("Модуль" to "Каталог ТВ-приложений и установка APK")
                details.add("Версия" to fwVer)
            }
            PKG_YANDEX_MUSIC -> {
                details.add("Модуль" to "Нативный плеер Яндекс Музыки")
                details.add("Версия" to fwVer)
            }
            else -> try {
                val pi = packageManager.getPackageInfo(app.pkg, 0)
                val ai = packageManager.getApplicationInfo(app.pkg, 0)
                val apkMb = try {
                    val f = java.io.File(ai.sourceDir)
                    if (f.exists()) String.format(Locale.US, "%.1f МБ", f.length() / (1024f * 1024f)) else "—"
                } catch (_: Throwable) { "—" }
                details.add("Пакет" to app.pkg)
                details.add("Версия" to "${pi.versionName ?: "—"} (${pi.longVersionCode})")
                details.add("Тип" to if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "Системный пакет" else "Установленное APK")
                details.add("Формат" to if (app.leanback) "Android TV (Leanback)" else "Обычное Android")
                details.add("Target SDK" to "API ${ai.targetSdkVersion}")
                details.add("Размер APK" to apkMb)
            } catch (_: Throwable) {
                details.add("Пакет" to app.pkg)
            }
        }
        val card = dialogCard(app.label, null)
        for ((k, v) in details) {
            val row = LinearLayout(this)
            row.addView(label(k, 16f, Theme.TEXT_MUTED), llp(dp(140), -2))
            row.addView(label(v, 16f, Theme.TEXT, 600, lines = 2), llp(0, -2, 1f))
            card.addView(row, llp(-1, -2) { topMargin = dp(10) })
        }
        val dlg = Dialog(this)
        val btnRow = LinearLayout(this).apply { gravity = Gravity.END }
        if (!app.isVirtual) {
            btnRow.addView(pillButton("В системе", primary = false) {
                dlg.dismiss()
                openAppSystemDetails(app.pkg)
            }, llp(-2, dp(56)) { marginEnd = dp(12) })
        }
        val close = pillButton("Закрыть", primary = true) { dlg.dismiss() }
        btnRow.addView(close, llp(-2, dp(56)))
        card.addView(btnRow, llp(-1, -2) { topMargin = dp(24) })
        showThemedDialog(dlg, card, 640, close) { focusAppTile(app.pkg) }
    }

    // -----------------------------------------------------------------------------------------
    // Themed dialogs (menus, app info, OTA): centred card, focus confined, Back closes
    // -----------------------------------------------------------------------------------------
    private fun dialogCard(title: String, subtitle: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(36), dp(32), dp(36), dp(32))
        background = Theme.rounded(Theme.DIALOG, dpf(32))
        addView(label(title, 26f, Theme.TEXT, 700))
        if (subtitle != null) addView(label(subtitle, 16f, Theme.TEXT_MUTED, lines = 2), llp(-1, -2) { topMargin = dp(6) })
    }

    private fun showMenuDialog(title: String, subtitle: String?, items: List<Pair<String, () -> Unit>>, onClosed: () -> Unit) {
        val card = dialogCard(title, subtitle)
        val dlg = Dialog(this)
        var picked: (() -> Unit)? = null
        var first: View? = null
        items.forEach { (text, action) ->
            val row = label(text, 18f, Theme.TEXT, 600).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                background = Theme.focusable(Theme.ROW, dpf(16), dp(3))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    picked = action
                    dlg.dismiss()
                }
            }
            if (first == null) first = row
            card.addView(row, llp(-1, dp(56)) { topMargin = dp(10) })
        }
        showThemedDialog(dlg, card, 520, first) {
            val action = picked
            if (action != null) action() else onClosed()
        }
    }

    private fun showThemedDialog(dlg: Dialog, content: View, widthDp: Int, focus: View?, onDismiss: () -> Unit) {
        try { activeDialog?.dismiss() } catch (_: Throwable) {}
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(content)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
        }
        dlg.setOnDismissListener {
            if (activeDialog === dlg) activeDialog = null
            onDismiss()
        }
        activeDialog = dlg
        dlg.show()
        focus?.requestFocus()
    }

    // -----------------------------------------------------------------------------------------
    // Station side sheet (Settings.dc.html)
    // -----------------------------------------------------------------------------------------
    private fun buildStationPanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            visibility = View.GONE
            background = Theme.ambientBackground()
        }
        val split = LinearLayout(this)
        panel.addView(split, matchLp())

        // Left: back, clock, web remote QR, telemetry
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(44), dp(40), dp(44))
            setBackgroundColor(Color.argb(140, 6, 6, 10))
        }
        left.addView(makeBackPill(), llp(-2, dp(40)))
        left.addView(View(this), llp(1, 0, 1f))
        tvPanelClock = label("--:--", 72f, Theme.TEXT, 300).apply { letterSpacing = -0.04f }
        tvPanelDate = label("", 18f, Theme.alpha(Theme.TEXT, 0.75f))
        left.addView(tvPanelClock)
        left.addView(tvPanelDate, llp(-2, -2) { topMargin = dp(8); bottomMargin = dp(22) })
        val qrCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = Theme.rounded(Theme.SURFACE, dpf(22))
        }
        ivQr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        qrCard.addView(FrameLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = Theme.rounded(Color.WHITE, dpf(14))
            addView(ivQr, matchLp())
        }, llp(dp(112), dp(112)) { marginEnd = dp(18) })
        val qrText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qrText.addView(label("Веб-пульт", 18f, Theme.TEXT, 700))
        tvIpLine = label("127.0.0.1:8080", 15f, Theme.accent)
        qrText.addView(tvIpLine, llp(-2, -2) { topMargin = dp(6) })
        qrText.addView(label("Отсканируйте камерой телефона", 14f, Theme.alpha(Theme.TEXT, 0.65f), lines = 2),
            llp(-2, -2) { topMargin = dp(6) })
        qrCard.addView(qrText, llp(0, -2, 1f))
        left.addView(qrCard, llp(dp(440), -2))
        tvTelemetry = label("", 14f, Theme.alpha(Theme.TEXT, 0.6f))
        left.addView(tvTelemetry, llp(-2, -2) { topMargin = dp(22) })
        split.addView(left, llp(0, -1, 1f))

        // Right: sheet with the station controls
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(36), dp(36), dp(36))
        }
        sheet.addView(label("Станция", 30f, Theme.TEXT, 700).apply { letterSpacing = -0.015f })

        sheet.addView(sectionLabel("Звук и свет"))
        tvRouteVal = valueText()
        rowRoute = settingsRow(R.drawable.ic_hw_speaker, "Выход звука", tvRouteVal) { cycleOutputRoute() }
        sheet.addView(rowRoute, rowLp())
        tvEqVal = valueText()
        sheet.addView(settingsRow(R.drawable.ic_hw_eq, "Эквалайзер", tvEqVal) { openEqPanel() }, rowLp())
        tvRingVal = valueText()
        sheet.addView(settingsRow(R.drawable.ic_hw_ring, "Кольцо подсветки", tvRingVal) { cycleRingMode() }, rowLp())
        tvVisVal = valueText()
        sheet.addView(settingsRow(R.drawable.ic_wave, "Визуализатор", tvVisVal) { cycleVisualizerStyle() }, rowLp())

        sheet.addView(sectionLabel("Алиса и ТВ"))
        swMic = SwitchPill(this)
        sheet.addView(settingsRow(R.drawable.ic_hw_mic_on, "Микрофоны", swMic) { toggleMute() }, rowLp())
        swCec = SwitchPill(this)
        sheet.addView(settingsRow(R.drawable.ic_hw_tv, "Телевизор (HDMI-CEC)", swCec) { toggleCec() }, rowLp())

        sheet.addView(sectionLabel("Bluetooth"))
        swBt = SwitchPill(this)
        sheet.addView(settingsRow(R.drawable.ic_hw_bluetooth, "Bluetooth-колонка", swBt) { toggleBt() }, rowLp())
        tvRemoteVal = valueText()
        sheet.addView(settingsRow(R.drawable.ic_hw_keyboard, "Пульт", tvRemoteVal) { remoteAction() }, rowLp())

        sheet.addView(sectionLabel("Станция"))
        tvTimersVal = valueText()
        sheet.addView(settingsRow(R.drawable.ic_timer, "Таймеры и будильники", tvTimersVal) { openTimersScreen() }, rowLp())
        sheet.addView(settingsRow(R.drawable.ic_yaos_apps, "Магазин приложений", valueText()) { launchMarket() }, rowLp())
        tvOtaVal = valueText()
        sheet.addView(settingsRow(R.drawable.ic_refresh, "Обновление ПО", tvOtaVal) { openOtaDialog() }, rowLp())
        sheet.addView(settingsRow(R.drawable.ic_hw_chip, "Мастер настройки", valueText()) { openSetupWizard(manual = true) }, rowLp())
        sheet.addView(settingsRow(R.drawable.ic_hw_display, "Тема оформления", valueText().apply { text = "Ambient" }) {
            Theme.switchTo(this, glass = true)
        }, rowLp())

        sheet.addView(sectionLabel("Android TV"))
        val sysTiles = listOf(
            Triple(R.drawable.ic_hw_wifi, "Wi-Fi") { openSettingsIntent(Settings.ACTION_WIFI_SETTINGS, "com.android.tv.settings") },
            Triple(R.drawable.ic_hw_bluetooth, "Bluetooth") { openTvAccessories() },
            Triple(R.drawable.ic_hw_display, "Экран") { openSettingsIntent(Settings.ACTION_DISPLAY_SETTINGS, "com.android.tv.settings") },
            Triple(R.drawable.ic_hw_speaker, "Звук") {
                openSettingsActions("com.yandex.tv.settings.ACTION_SOUND", Settings.ACTION_SOUND_SETTINGS)
            },
            Triple(R.drawable.ic_hw_apps, "Приложения") { openSettingsIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "com.android.tv.settings") },
            Triple(R.drawable.ic_hw_info, "О системе") { openSettingsIntent(Settings.ACTION_DEVICE_INFO_SETTINGS, "com.android.tv.settings") },
            Triple(R.drawable.ic_settings, "Все настройки") { launchPackageName("com.android.tv.settings") }
        )
        sysTiles.chunked(4).forEach { chunk ->
            val row = LinearLayout(this)
            for (i in 0 until 4) {
                val item = chunk.getOrNull(i)
                val v: View = if (item == null) View(this) else LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = Theme.focusable(Theme.ROW, dpf(16), dp(3))
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { item.third() }
                    addView(icon(item.first, 20, Theme.TEXT))
                    addView(label(item.second, 13f, Theme.TEXT, 600).apply { gravity = Gravity.CENTER }, llp(-2, -2) { topMargin = dp(6) })
                }
                row.addView(v, llp(0, dp(72), 1f) { if (i < 3) marginEnd = dp(8) })
            }
            sheet.addView(row, rowLp())
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = ColorDrawable(Theme.SHEET)
            addView(sheet)
        }
        split.addView(scroll, llp(dp(520), -1))
        return panel
    }

    private fun sectionLabel(text: String): TextView = eyebrow(text).apply {
        layoutParams = llp(-2, -2) { topMargin = dp(18); bottomMargin = dp(8) }
    }

    private fun valueText(): TextView = label("", 16f, Theme.alpha(Theme.TEXT, 0.7f))

    private fun rowLp() = llp(-1, -2) { bottomMargin = dp(8) }

    private fun settingsRow(iconRes: Int, title: String, trailing: View, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(18), 0, dp(18), 0)
            background = Theme.focusable(Theme.ROW, dpf(16), dp(3))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            addView(icon(iconRes, 20, Theme.TEXT), llp(dp(20), dp(20)) { marginEnd = dp(14) })
            addView(label(title, 17f, Theme.TEXT, 600), llp(0, -2, 1f))
            addView(trailing)
        }

    private fun openStationPanel() {
        syncStationPanel(force = true)
        updateClock(force = true)
        updateNetworkHeader(cachedLocalIp)
        openPanel(stationPanel, rowRoute)
    }

    private fun openTimersScreen() {
        timersScreen.update(timers, timersFetchedAt, alarms)
        openPanel(timersScreen, timersScreen)
        timersScreen.focusFirst()
    }

    // -----------------------------------------------------------------------------------------
    // Equalizer panel: preset pill + bass + 5 bands, live spectrum (HomeEqWidgetController)
    // -----------------------------------------------------------------------------------------
    private fun buildEqPanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            visibility = View.GONE
            background = Theme.ambientBackground()
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(44), dp(56), dp(44))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        eqBackPill = makeBackPill()
        header.addView(eqBackPill, llp(-2, dp(40)) { marginEnd = dp(20) })
        header.addView(label("Эквалайзер", 34f, Theme.TEXT, 700))
        col.addView(header, llp(-1, -2) { bottomMargin = dp(32) })

        eqController = HomeEqWidgetController(
            context = this,
            onCyclePreset = { cycleEqPreset() },
            onBandsCommitted = { presetName, bands10, bass ->
                eqPreset = presetName
                syncStationPanel()
                val body = JSONObject()
                    .put("preset", presetName)
                    .put("bassBoost", bass)
                    .put("bands", JSONArray().apply { bands10.forEach { put(it.toDouble()) } })
                    .toString()
                postAsync("$API/api/audio/eq", body)
            },
            onNavigateOutUpOrDown = { dir ->
                if (dir < 0) eqBackPill.requestFocus()
                true
            }
        )
        col.addView(eqController.buildView(), llp(dp(720), -2))
        col.addView(label("OK на полосе — регулировка стрелками ▲ ▼ · «Назад» — выход", 15f, Theme.alpha(Theme.TEXT, 0.55f)),
            llp(-2, -2) { topMargin = dp(18) })
        panel.addView(col, matchLp())
        return panel
    }

    private fun openEqPanel() {
        openPanel(eqPanel, eqBackPill)
        eqController.focusDefault()
        handler.removeCallbacks(eqTickRunnable)
        handler.post(eqTickRunnable)
    }

    // -----------------------------------------------------------------------------------------
    // Alice phases (AliceTv.dc.html)
    // -----------------------------------------------------------------------------------------
    private fun buildAliceLayer(): FrameLayout {
        val layer = FrameLayout(this).apply {
            visibility = View.GONE
            background = Theme.glowBackground(0.5f, 0.45f, 0.42f)
        }
        aliceRingOuter = View(this).apply { background = Theme.oval(Color.TRANSPARENT, dp(2), Theme.alpha(Theme.accent, 0.25f)) }
        layer.addView(aliceRingOuter, FrameLayout.LayoutParams(dp(440), dp(440), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) })
        aliceRingInnerBg = Theme.oval(Color.TRANSPARENT)
        aliceRingInner = View(this).apply { background = aliceRingInnerBg }
        layer.addView(aliceRingInner, FrameLayout.LayoutParams(dp(360), dp(360), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(76) })
        aliceOrb = AliceOrbView(this).apply { compactGlowBoost = 0.4f }
        layer.addView(aliceOrb, FrameLayout.LayoutParams(dp(280), dp(280)))

        aliceCenterCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        tvAliceLabel = eyebrow("Слушаю…", Theme.accent, 15f).apply { letterSpacing = 0.13f }
        tvAliceText = label("", 40f, Theme.TEXT, 600, lines = 2).apply { gravity = Gravity.CENTER }
        aliceCenterCol.addView(tvAliceLabel)
        aliceCenterCol.addView(tvAliceText, llp(-2, -2) { topMargin = dp(12) })
        layer.addView(aliceCenterCol, FrameLayout.LayoutParams(dp(1000), -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(508) })

        aliceAnswerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tvAnswerLabel = eyebrow("Алиса отвечает", Theme.accent, 15f).apply { letterSpacing = 0.13f }
        tvAnswerQuote = label("", 20f, Theme.alpha(Theme.TEXT, 0.6f), lines = 2)
        tvAnswerText = label("", 36f, Theme.TEXT, 600, lines = 6).apply { setLineSpacing(0f, 1.2f) }
        aliceAnswerCol.addView(tvAnswerLabel)
        aliceAnswerCol.addView(tvAnswerQuote, llp(-1, -2) { topMargin = dp(24) })
        aliceAnswerCol.addView(tvAnswerText, llp(-1, -2) { topMargin = dp(24) })
        answerTrackCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(26), dp(14))
            background = Theme.rounded(Theme.SURFACE, dpf(20))
        }
        ivAnswerCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Theme.coverPlaceholder(dpf(12))
            clipToOutline = true
        }
        answerTrackCard.addView(ivAnswerCover, llp(dp(64), dp(64)) { marginEnd = dp(16) })
        val trackCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvAnswerTrack = label("", 18f, Theme.TEXT, 700)
        tvAnswerArtist = label("", 15f, Theme.alpha(Theme.TEXT, 0.7f))
        trackCol.addView(tvAnswerTrack)
        trackCol.addView(tvAnswerArtist, llp(-2, -2) { topMargin = dp(4) })
        answerTrackCard.addView(trackCol)
        aliceAnswerCol.addView(answerTrackCard, llp(-2, -2) { topMargin = dp(24) })
        layer.addView(aliceAnswerCol, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(96 + 300 + 72)
            rightMargin = dp(96)
        })

        layer.addView(label("Скажите «Стоп» или нажмите «Назад», чтобы прервать", 16f, Theme.alpha(Theme.TEXT, 0.55f)).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(36) })
        return layer
    }

    private fun showAlicePhase(st: String, userTxt: String, display: String) {
        val answering = st == "speaking" || st == "error" || st == "idle"
        if (alicePhaseAnswering != answering) {
            alicePhaseAnswering = answering
            aliceCenterCol.visibility = if (answering) View.GONE else View.VISIBLE
            aliceRingInner.visibility = if (answering) View.GONE else View.VISIBLE
            aliceAnswerCol.visibility = if (answering) View.VISIBLE else View.GONE
            aliceOrb.layoutParams = if (answering) {
                FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(96) }
            } else {
                FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(116) }
            }
        }
        if (!answering) {
            val listening = st != "thinking"
            aliceRingOuter.visibility = if (listening) View.VISIBLE else View.INVISIBLE
            if (listening) {
                aliceRingInnerBg.setStroke(dp(2), Theme.alpha(Theme.accent, 0.5f))
            } else {
                aliceRingInnerBg.setStroke(dp(3), Theme.alpha(Theme.accent, 0.7f), dpf(10), dpf(8))
            }
            tvAliceLabel.text = when (st) {
                "connecting" -> "ПОДКЛЮЧАЮСЬ…"
                "thinking" -> "ДУМАЮ…"
                else -> "СЛУШАЮ…"
            }
            tvAliceText.text = if (userTxt.isNotEmpty()) "«$userTxt»" else if (listening) "Говорите…" else "Формирую ответ…"
            tvAliceText.setTextColor(if (listening) Theme.TEXT else Theme.alpha(Theme.TEXT, 0.6f))
        } else {
            aliceRingOuter.visibility = View.GONE
            val err = st == "error"
            tvAnswerLabel.text = if (err) "АЛИСА · ОШИБКА" else "АЛИСА ОТВЕЧАЕТ"
            tvAnswerLabel.setTextColor(if (err) Theme.DANGER_FG else Theme.accent)
            tvAnswerQuote.text = if (userTxt.isNotEmpty()) "«$userTxt»" else ""
            tvAnswerQuote.visibility = if (userTxt.isNotEmpty()) View.VISIBLE else View.GONE
            tvAnswerText.text = display.ifBlank { if (err) "Не удалось выполнить запрос" else "Отвечаю…" }
            val tr = latestMusicSnap.currentTrack
            if (tr != null && latestMusicSnap.playing) {
                tvAnswerTrack.text = tr.title
                tvAnswerArtist.text = "${tr.artists} · ${latestMusicSnap.sourceLabel}"
                ivAnswerCover.setImageBitmap(coverBitmap)
                answerTrackCard.visibility = View.VISIBLE
            } else {
                answerTrackCard.visibility = View.GONE
            }
        }
        if (aliceLayer.visibility != View.VISIBLE) {
            aliceLayer.visibility = View.VISIBLE
            aliceLayer.alpha = 0f
            aliceLayer.animate().alpha(1f).setDuration(220L).start()
        }
    }

    private fun hideAliceLayer() {
        handler.removeCallbacks(hideAliceRunnable)
        if (aliceLayer.visibility != View.VISIBLE) return
        aliceLayer.animate().alpha(0f).setDuration(260L).withEndAction {
            aliceLayer.visibility = View.GONE
            alicePhaseAnswering = null
        }.start()
    }

    // -----------------------------------------------------------------------------------------
    // Alarm ringing (AlarmTv.dc.html)
    // -----------------------------------------------------------------------------------------
    private fun buildAlarmScreen(): FrameLayout {
        val layer = FrameLayout(this).apply {
            visibility = View.GONE
            background = Theme.glowBackground(0.5f, 0.3f, 0.55f)
            elevation = dpf(4)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val orb = FrameLayout(this)
        orb.addView(View(this).apply { background = Theme.oval(Theme.alpha(Theme.accent, 0.08f)) },
            FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER))
        orb.addView(View(this).apply { background = Theme.oval(Theme.alpha(Theme.accent, 0.18f)) },
            FrameLayout.LayoutParams(dp(186), dp(186), Gravity.CENTER))
        orb.addView(View(this).apply { background = Theme.oval(Theme.BG, dp(3), Theme.alpha(Theme.accent, 0.8f)) },
            FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        orb.addView(FrameLayout(this).apply {
            background = Theme.orbGradient(dpf(70))
            addView(icon(R.drawable.ic_timer, 44, Theme.ON_ACCENT), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        }, FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER))
        col.addView(orb, llp(dp(230), dp(230)))
        tvAlarmTime = label("07:30", 160f, Theme.TEXT, 200).apply { letterSpacing = -0.04f }
        col.addView(tvAlarmTime, llp(-2, -2) { topMargin = dp(4) })
        tvAlarmTitle = label("Будильник", 28f, Theme.TEXT, 600)
        col.addView(tvAlarmTitle, llp(-2, -2) { topMargin = dp(8) })
        tvAlarmSub = label("", 18f, Theme.alpha(Theme.TEXT, 0.65f))
        col.addView(tvAlarmSub, llp(-2, -2) { topMargin = dp(6) })
        val btns = LinearLayout(this)
        btnSnooze = pillButton("Отложить на 5 мин", primary = false, heightDp = 64) {
            dismissAlarm("$API/api/alarms/snooze", """{"minutes":5}""")
        }
        btns.addView(btnSnooze, llp(-2, dp(64)) { marginEnd = dp(16) })
        btns.addView(pillButton("Стоп", primary = true, heightDp = 64) { dismissAlarm("$API/api/alarms/stop", "{}") }.apply {
            setPadding(dp(44), 0, dp(44), 0)
        }, llp(-2, dp(64)))
        col.addView(btns, llp(-2, -2) { topMargin = dp(28) })
        layer.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(20) })
        layer.addView(label("Скажите «Алиса, стоп» или нажмите кнопку на станции", 16f, Theme.alpha(Theme.TEXT, 0.55f)).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(32) })
        return layer
    }

    private fun renderAlarm(ring: JSONObject?) {
        val now = SystemClock.elapsedRealtime()
        val show = ring != null && !(ring.optInt("id") == dismissedAlarmId && now - dismissedAlarmAt < 6000L)
        if (!show) {
            if (alarmScreen.visibility == View.VISIBLE) {
                alarmScreen.visibility = View.GONE
                applyFocusLayers()
                if (panelStack.isEmpty()) focusPrimary() else panelStack.last().requestFocus()
            }
            return
        }
        val r = ring ?: return
        tvAlarmTime.text = r.optString("time").ifBlank { timeFmt.format(Date()) }
        tvAlarmTitle.text = r.optString("label").let { if (it.isBlank()) "Будильник" else "Будильник · $it" }
        tvAlarmSub.text = when (r.optString("sound")) {
            "wave" -> "Играет Моя волна · громкость растёт плавно"
            "calm" -> "Играет спокойная волна · громкость растёт плавно"
            else -> "Звучит сигнал будильника"
        }
        if (alarmScreen.visibility != View.VISIBLE) {
            hideScreensaver()
            try { activeDialog?.dismiss() } catch (_: Throwable) {}
            alarmScreen.visibility = View.VISIBLE
            alarmScreen.alpha = 0f
            alarmScreen.animate().alpha(1f).setDuration(200L).start()
            applyFocusLayers()
            btnSnooze.requestFocus()
        }
    }

    private fun dismissAlarm(url: String, body: String) {
        dismissedAlarmId = lastRingingId
        dismissedAlarmAt = SystemClock.elapsedRealtime()
        postAsync(url, body)
        renderAlarm(null)
        handler.postDelayed({ pollDaemonStatus() }, 600L)
    }

    // -----------------------------------------------------------------------------------------
    // Screensaver (Screensaver.dc.html)
    // -----------------------------------------------------------------------------------------
    private fun buildScreensaver(): FrameLayout {
        val layer = FrameLayout(this).apply {
            visibility = View.GONE
            background = Theme.glowBackground(0.85f, 0.75f, 0.16f, base = 0xFF030305.toInt())
            elevation = dpf(8)
        }
        ssContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(120), dp(150), dp(120), dp(80))
        }
        tvSsClock = label("--:--", 220f, Theme.alpha(Theme.TEXT, 0.82f), 200).apply { letterSpacing = -0.045f }
        tvSsDate = label("", 30f, Theme.alpha(Theme.TEXT, 0.55f))
        tvSsWeather = label("", 26f, Theme.alpha(Theme.TEXT, 0.55f))
        ssContent.addView(tvSsClock)
        ssContent.addView(tvSsDate, llp(-2, -2) { topMargin = dp(14) })
        ssContent.addView(tvSsWeather, llp(-2, -2) { topMargin = dp(14) })
        ssContent.addView(View(this), llp(1, 0, 1f))
        ssTrack = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.6f
        }
        ivSsCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Theme.coverPlaceholder(dpf(8))
            clipToOutline = true
        }
        ssTrack.addView(ivSsCover, llp(dp(44), dp(44)) { marginEnd = dp(14) })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvSsTrack = label("", 16f, Theme.TEXT, 600)
        tvSsArtist = label("", 14f, Theme.alpha(Theme.TEXT, 0.7f))
        tcol.addView(tvSsTrack)
        tcol.addView(tvSsArtist)
        ssTrack.addView(tcol)
        ssContent.addView(ssTrack)
        layer.addView(ssContent, matchLp())
        return layer
    }

    /** 10 min without input, no music, Alice idle, no ringing alarm, no open dialog. */
    private fun maybeStartScreensaver() {
        val idle = SystemClock.elapsedRealtime() - lastInputMs >= SCREENSAVER_IDLE_MS
        if (!resumed || !idle || latestMusicSnap.playing || assistantActive() || activeDialog != null ||
            otaDialog != null || alarmScreen.visibility == View.VISIBLE || screensaver.visibility == View.VISIBLE
        ) return
        val tr = latestMusicSnap.currentTrack
        ssTrack.visibility = if (tr != null) View.VISIBLE else View.GONE
        if (tr != null) {
            tvSsTrack.text = tr.title
            tvSsArtist.text = tr.artists
            ivSsCover.setImageBitmap(coverBitmap)
        }
        updateClock(force = true)
        screensaver.visibility = View.VISIBLE
        screensaver.alpha = 0f
        screensaver.animate().alpha(1f).setDuration(800L).start()
        handler.removeCallbacks(screensaverShift)
        handler.post(screensaverShift)
    }

    private fun hideScreensaver() {
        handler.removeCallbacks(screensaverShift)
        lastInputMs = SystemClock.elapsedRealtime()
        if (screensaver.visibility != View.VISIBLE) return
        screensaver.animate().cancel()
        screensaver.visibility = View.GONE
    }

    // -----------------------------------------------------------------------------------------
    // Startup reveal (upstream "startup show"): soft fade-in, full orb show on a cold first boot
    // -----------------------------------------------------------------------------------------
    private fun startBootRevealFlow() {
        if (bootRevealStarted) return
        bootRevealStarted = true
        dashboardContent.alpha = 0f
        dashboardContent.translationY = dpf(18)
        orbView.alpha = 0f
        orbView.scaleX = 0.68f
        orbView.scaleY = 0.68f

        var revealed = false
        val safety = Runnable {
            if (!revealed) {
                revealed = true
                runStartupReveal(fullShow = false)
            }
        }
        handler.postDelayed(safety, 950L)

        thread(isDaemon = true, name = "BootRevealSync") {
            var coldFirstBoot = false
            try {
                val conn = (URL("$API/api/system/startup_show").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 750
                    readTimeout = 750
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    coldFirstBoot = obj.optBoolean("ok", false) && !obj.optBoolean("alreadyShown", true)
                }
                conn.disconnect()
            } catch (_: Throwable) {}
            handler.post {
                if (!revealed) {
                    revealed = true
                    handler.removeCallbacks(safety)
                    runStartupReveal(fullShow = coldFirstBoot)
                }
            }
        }
    }

    private fun runStartupReveal(fullShow: Boolean) {
        if (startupShowActive) return
        dashboardContent.animate().cancel()
        orbView.animate().cancel()
        if (!fullShow) {
            dashboardContent.animate().alpha(1f).translationY(0f).setDuration(280L).setInterpolator(easeOut).start()
            orbView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300L).setInterpolator(easeOut).start()
            return
        }
        startupShowActive = true
        goHome()
        dashboardContent.alpha = 0f
        dashboardContent.translationY = dpf(18)
        orbView.alpha = 0f
        orbView.scaleX = 0.68f
        orbView.scaleY = 0.68f
        orbView.animate().alpha(1f).scaleX(1.09f).scaleY(1.09f).setDuration(820L).setInterpolator(easeOut)
            .withEndAction {
                orbView.animate().scaleX(1f).scaleY(1f).setDuration(980L).setInterpolator(easeOut).start()
            }.start()
        handler.postDelayed({
            dashboardContent.animate().alpha(1f).translationY(0f).setDuration(980L).setInterpolator(easeOut).start()
        }, 320L)
        tvOrbTitle.text = "OpenStation Max"
        tvOrbHint.text = "Добро пожаловать"
        handler.postDelayed({
            startupShowActive = false
            lastOrbUiKey = ""
            renderOrbAssistantUi()
        }, 4400L)
    }

    // =========================================================================================
    // LIVE ASSISTANT STATE
    // =========================================================================================
    private fun assistantActive(): Boolean {
        val a = LiveOrbState.assistantState
        return (a != "idle" && a != "error") || (assistantState != "idle" && assistantState != "error")
    }

    private fun renderOrbAssistantUi() {
        val st = LiveOrbState.assistantState
        val userTxt = LiveOrbState.liveUserText.trim()
        val display = LiveOrbState.bestDisplayText()
        val uiKey = "$st|$assistantEnabled|$aliceConfigured|$userTxt|$display"
        if (uiKey == lastOrbUiKey) return
        lastOrbUiKey = uiKey

        // Dashboard caption + empty state (EmptyTv.dc.html: grey orb when Alice is not usable)
        if (!startupShowActive) {
            when {
                !assistantEnabled -> {
                    tvOrbTitle.text = "Алиса выключена"
                    tvOrbHint.text = "Включите ассистента в веб-пульте"
                }
                !aliceConfigured -> {
                    tvOrbTitle.text = "Алиса не настроена"
                    tvOrbHint.text = "Нужен ключ Gemini API · Меню станции › Мастер настройки"
                }
                else -> {
                    tvOrbTitle.text = "Скажите «Алиса»"
                    tvOrbHint.text = "или нажмите кнопку микрофона на пульте"
                }
            }
        }
        setOrbDimmed(!assistantEnabled || !aliceConfigured)

        if (st in ACTIVE_ASSISTANT_STATES) {
            handler.removeCallbacks(hideAliceRunnable)
            hideScreensaver()
            showAlicePhase(st, userTxt, display)
        } else if (aliceLayer.visibility == View.VISIBLE) {
            if (display.isNotBlank()) showAlicePhase("idle", userTxt, display)
            handler.removeCallbacks(hideAliceRunnable)
            handler.postDelayed(hideAliceRunnable, if (display.isNotBlank()) 3400L else 600L)
        }
    }

    private fun setOrbDimmed(dim: Boolean) {
        if (dim == orbDimmed) return
        orbDimmed = dim
        if (dim) {
            val grey = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            orbView.setLayerType(View.LAYER_TYPE_HARDWARE, grey)
        } else {
            orbView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        orbView.alpha = if (dim) 0.55f else 1f
    }

    // =========================================================================================
    // LAUNCHING
    // =========================================================================================
    private fun launchApp(app: AppEntry) {
        when (app.pkg) {
            PKG_OPENSTATION_APPS -> launchMarket()
            PKG_YANDEX_MUSIC -> launchYandexMusic()
            else -> try {
                app.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(app.intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "Не удалось запустить ${app.label}: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPackageName(pkg: String) {
        try {
            val i = packageManager.appLaunchIntent(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            }
        } catch (_: Throwable) {}
        Toast.makeText(this, "Пакет $pkg недоступен", Toast.LENGTH_SHORT).show()
    }

    private fun launchYandexMusic() {
        try {
            startActivity(Intent(this, YandexMusicActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось открыть Яндекс Музыку: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchMarket() {
        try {
            startActivity(Intent(this, MarketActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось открыть OPENSTATION APPS: ${t.message}", Toast.LENGTH_SHORT).show()
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

    // =========================================================================================
    // CLOCK, NETWORK & DAEMON STATUS
    // =========================================================================================
    private fun updateClock(force: Boolean = false) {
        val tz = TimeZone.getDefault()
        timeFmt.timeZone = tz
        dateFmt.timeZone = tz
        clockDate.time = System.currentTimeMillis()

        val style = LiveOrbState.clockStyle
        val timeStr = when (style) {
            "seconds" -> SimpleDateFormat("HH:mm:ss", ruLocale).apply { timeZone = tz }.format(clockDate)
            "binary" -> {
                val cal = java.util.Calendar.getInstance(tz)
                Integer.toBinaryString(cal.get(java.util.Calendar.HOUR_OF_DAY)).padStart(5, '0') + " : " +
                    Integer.toBinaryString(cal.get(java.util.Calendar.MINUTE)).padStart(6, '0')
            }
            else -> timeFmt.format(clockDate)
        }
        val date = dateFmt.format(clockDate).replaceFirstChar { it.uppercase() }
        val weather = if (LiveOrbState.weatherValid) {
            val sign = if (LiveOrbState.weatherTemp > 0) "+" else ""
            val cond = LiveOrbState.weatherConditionText.trim().replaceFirstChar { it.uppercase() }
            (if (cond.isNotEmpty()) "$cond, " else "") + "$sign${LiveOrbState.weatherTemp}°"
        } else ""

        if (force || timeStr != lastClockStr) {
            lastClockStr = timeStr
            tvClock.text = timeStr
            tvPanelClock.text = timeStr
            tvSsClock.text = timeStr
            tvClock.typeface = when (style) {
                "minimal" -> typefaceFor(200)
                "bold" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
                "retro" -> Typeface.MONOSPACE
                else -> typefaceFor(300)
            }
            if (style == "neon") tvClock.setShadowLayer(18f, 0f, 0f, Theme.accent) else tvClock.setShadowLayer(0f, 0f, 0f, 0)
        }
        tvDateLine.text = if (weather.isNotEmpty()) "$date · $weather" else date
        tvPanelDate.text = date
        tvSsDate.text = date
        tvSsWeather.text = weather
        tvSsWeather.visibility = if (weather.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun detectLocalIp(force: Boolean = false): String {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && cachedLocalIp != "127.0.0.1" && (nowMs - lastIpDetectMs) < 20_000L) return cachedLocalIp
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
                            cachedLocalIp = ip
                            lastIpDetectMs = nowMs
                            return ip
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

    private fun updateNetworkHeader(ipOverride: String? = null) {
        val ip = ipOverride ?: detectLocalIp()
        val online = ip != "127.0.0.1" || try {
            getSystemService(ConnectivityManager::class.java).activeNetwork != null
        } catch (_: Throwable) { false }
        tvIpLine.text = if (online) "$ip:8080" else "Локально: 127.0.0.1:8080"
        val chipColor = if (online) Theme.alpha(Theme.TEXT, 0.8f) else Theme.DANGER_FG
        tvChipIp.text = when {
            !online -> "Нет интернета"
            !daemonOnline -> "Запуск системы…"
            else -> ip
        }
        tvChipIp.setTextColor(chipColor)
        ivChipIp.setTintedIcon(R.drawable.ic_hw_wifi, chipColor)
        chipIpBg.setColor(if (online) Theme.CHIP else Theme.DANGER_BG)
        if (ip != lastQrIp && ip.isNotEmpty()) {
            lastQrIp = ip
            val bmp = QrGenerator.generateBitmap("http://$ip:8080", 220)
            if (bmp != null) {
                ivQr.setImageBitmap(bmp)
                ivAuthQr.setImageBitmap(bmp)
            }
        }
    }

    private fun pollDaemonStatus() {
        thread(isDaemon = true) {
            try {
                val conn = (URL("$API/api/status").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 800
                    readTimeout = 800
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    markDaemonOffline()
                    return@thread
                }
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject(txt)
                val cfg = root.optJSONObject("config")
                val audio = root.optJSONObject("audio")
                val audioCfg = cfg?.optJSONObject("audio")
                val ast = root.optJSONObject("assistant")
                val astCfg = cfg?.optJSONObject("assistant")
                val newFw = root.optString("firmwareVersion", fwVer).ifBlank { fwVer }
                val newRing = cfg?.optJSONObject("ledRing")?.optString("mode", ringMode) ?: ringMode
                val newMic = audioCfg?.optBoolean("micMuted", micMuted) ?: micMuted
                val newRoute = audioCfg?.optString("outputRoute", outputRoute) ?: outputRoute
                val newEq = audioCfg?.optString("eqPreset", eqPreset) ?: eqPreset
                val eqBandsJson = audioCfg?.optJSONArray("eqBands") ?: audio?.optJSONArray("eqBands")
                val eqBass: Int? = when {
                    audioCfg?.has("bassBoost") == true -> audioCfg.optInt("bassBoost")
                    audio?.has("bassBoost") == true -> audio.optInt("bassBoost")
                    else -> null
                }
                val newAstState = ast?.optString("state", "idle") ?: "idle"
                val newAstEnabled = astCfg?.optBoolean("enabled", true) ?: true
                // Status never carries the key itself (geminiApiKey is always ""), only geminiApiKeySet.
                val keySet = astCfg?.optBoolean("geminiApiKeySet", false) == true
                val newConfigured = astCfg == null || astCfg.optString("mode", "gemini") != "gemini" || keySet
                val setupDone = cfg?.optBoolean("setupCompleted", false) ?: false
                val newTimers = root.optJSONArray("timers") ?: JSONArray()
                val newAlarms = root.optJSONArray("alarms") ?: JSONArray()
                val ring = root.optJSONObject("ringingAlarm")
                val daemonMusic = root.optJSONObject("music")
                val daemonOta = root.optJSONObject("ota")
                val btObj = root.optJSONObject("bluetooth")
                val wasOffline = !daemonOnline
                daemonOnline = true
                if (wasOffline && cachedAppsJson.isNotEmpty()) httpPostJson("$API/api/apps/cache", cachedAppsJson)

                val lw = root.optJSONObject("lastWeather")
                if (lw != null && !LiveOrbState.weatherValid) {
                    val lwTemp = lw.optInt("tempC", Int.MIN_VALUE)
                    if (lwTemp != Int.MIN_VALUE) {
                        LiveOrbState.updateWeather(true, lwTemp, lw.optString("condition", "clear"),
                            lw.optString("conditionText", ""), lw.optString("city", ""))
                    }
                }

                // Fallback reconciliation if a UDP packet was missed or first open after boot
                if (ast != null) {
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val staleOrb = (LiveOrbState.lastActiveAtMs == 0L && LiveOrbState.lastReplyAtMs == 0L) ||
                        (newAstState == "idle" && LiveOrbState.assistantState != "idle" &&
                            (nowElapsed - LiveOrbState.lastActiveAtMs) > 3000L)
                    if (staleOrb) {
                        LiveOrbState.updateAssistantEvent(
                            state = newAstState,
                            liveUser = ast.optString("liveUserText", ""),
                            liveModel = ast.optString("liveModelText", ""),
                            lastModel = ast.optString("lastModelText", ""),
                            error = ast.optString("lastError", "")
                        )
                    }
                }
                val ip = detectLocalIp()

                handler.post {
                    VolumeHudView.routeLabel = VolumeHudView.routeLabelFor(newRoute)
                    fwVer = newFw
                    ringMode = newRing
                    micMuted = newMic
                    outputRoute = newRoute
                    eqPreset = newEq
                    assistantState = newAstState
                    assistantEnabled = newAstEnabled
                    aliceConfigured = newConfigured
                    timers = newTimers
                    timersFetchedAt = SystemClock.elapsedRealtime()
                    alarms = newAlarms
                    if (btObj != null) {
                        btEnabled = btObj.optBoolean("enabled", false)
                        btDiscoverable = btObj.optBoolean("discoverable", false)
                    }

                    updateNetworkHeader(ip)
                    val svcSnap = StationAudioService.instance?.musicPlayer?.getSnapshot()
                    if (svcSnap != null) {
                        if (svcSnap.seq != latestMusicSnap.seq || svcSnap.playing != latestMusicSnap.playing) {
                            renderMusicSnapshot(svcSnap)
                        }
                    } else if (daemonMusic != null && daemonMusic.optLong("seq", 0L) > 0L) {
                        renderMusicSnapshot(YmPlayerSnapshot.fromJson(daemonMusic))
                    }
                    applyOtaUi(daemonOta)
                    eqController.syncFromDaemon(eqPreset, eqBandsJson, eqBass)
                    syncStationPanel()
                    renderOrbAssistantUi()
                    renderTimers()
                    lastRingingId = ring?.optInt("id", -1) ?: -1
                    renderAlarm(ring)

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
                        if (prefs.getInt(SetupWizardActivity.KEY_WIZARD_DISMISSED_VER, 0) < SetupWizardActivity.CURRENT_WIZARD_VER) {
                            wizardAutoOpenedThisRun = true
                            openSetupWizard(manual = false)
                        }
                    }
                }
            } catch (_: Throwable) {
                markDaemonOffline()
            }
        }
    }

    private fun markDaemonOffline() {
        daemonOnline = false
        handler.post {
            updateNetworkHeader(cachedLocalIp)
            syncStationPanel()
        }
    }

    private fun renderTimers() {
        renderTimerChip()
        if (timersScreen.visibility == View.VISIBLE) timersScreen.update(timers, timersFetchedAt, alarms)
        handler.removeCallbacks(secondTicker)
        if (timers.length() > 0) handler.postDelayed(secondTicker, 1000L)
    }

    /** Status chip "Пицца · 04:59" for the timer that ends first. */
    private fun renderTimerChip() {
        var best: JSONObject? = null
        var bestRem = Int.MAX_VALUE
        for (i in 0 until timers.length()) {
            val t = timers.optJSONObject(i) ?: continue
            val rem = timerRemaining(t, timersFetchedAt)
            if (rem in 0 until bestRem) {
                best = t
                bestRem = rem
            }
        }
        if (best == null) {
            chipTimer.visibility = View.GONE
            return
        }
        val name = best.optString("label").ifBlank { "Таймер" }
        val more = if (timers.length() > 1) "  +${timers.length() - 1}" else ""
        tvChipTimer.text = "$name · ${formatDuration(bestRem)}$more"
        chipTimer.contentDescription = "Таймер: осталось ${formatDuration(bestRem)}"
        chipTimer.visibility = View.VISIBLE
    }

    private fun ringLabel() = when (ringMode) {
        "rainbow" -> "Радуга"
        "solid" -> "Статика"
        "breathe" -> "Дыхание"
        "spinner" -> "Круг"
        "spectrum", "music" -> "Спектр"
        "off" -> "Выкл"
        else -> ringMode
    }

    private fun visualizerName(style: String) = when (style) {
        "nexus", "anomaly" -> "Nexus 3D"
        "specterr" -> "Корона"
        "aurora" -> "Аврора"
        "bars" -> "Спектр"
        "wave" -> "Волна"
        "matrix" -> "Матрица"
        "fireflies" -> "Частицы"
        else -> "3D Точки"
    }

    /** Values on the station sheet + mic status chip. */
    private fun syncStationPanel(force: Boolean = false) {
        val enabledAlarms = (0 until alarms.length()).count { alarms.optJSONObject(it)?.optBoolean("enabled", true) == true }
        val remote = if (stationPanel.visibility == View.VISIBLE || force) {
            StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
        } else null
        val remoteKey = remote?.let { "${it.bonded}|${it.connected}|${it.name}|${it.batteryPct}" } ?: "-"
        val key = "$ringMode|$micMuted|$outputRoute|$eqPreset|$cecState|${LiveOrbState.visualizerStyle}|$fwVer|$daemonOnline|" +
            "$assistantState|${timers.length()}|$enabledAlarms|$btEnabled|$btDiscoverable|$remoteKey"
        if (!force && key == lastPanelKey) return
        lastPanelKey = key

        tvRingVal.text = ringLabel()
        tvRouteVal.text = when (outputRoute) {
            "speakers" -> "Колонка"
            "headphones" -> "Наушники"
            "hdmi" -> "HDMI"
            "both" -> "Вместе"
            else -> "Авто"
        }
        tvEqVal.text = when (eqPreset) {
            "bass_boost" -> "Бас+"
            "vocal" -> "Вокал"
            "night" -> "Ночь"
            "rock" -> "Рок"
            "electronic" -> "Клуб"
            "flat" -> "Обычный"
            "custom" -> "Свой"
            else -> "Баланс"
        }
        tvVisVal.text = visualizerName(LiveOrbState.visualizerStyle)
        swMic.checked = !micMuted
        swCec.checked = !cecState
        swBt.checked = btEnabled
        if (remote != null) {
            tvRemoteVal.text = when {
                remote.bonded -> remote.name.ifBlank { "Пульт" } + (if (remote.batteryPct >= 0) " · ${remote.batteryPct}%" else "")
                else -> "Подключить"
            }
        }
        tvTimersVal.text = when {
            timers.length() > 0 -> "Таймеров: ${timers.length()}"
            enabledAlarms > 0 -> "Будильников: $enabledAlarms"
            else -> "Нет"
        }
        val astTxt = if (assistantState != "idle") "Алиса активна" else "Алиса готова"
        val btTxt = if (btDiscoverable) " · Bluetooth: поиск пары" else ""
        tvTelemetry.text = if (daemonOnline) "v$fwVer · $astTxt$btTxt" else "Ожидание системы…"

        ivChipMic.setTintedIcon(
            if (micMuted) R.drawable.ic_hw_mic_off else R.drawable.ic_hw_mic_on,
            if (micMuted) Theme.DANGER_FG else Theme.alpha(Theme.TEXT, 0.8f)
        )
        chipMicBg.setColor(if (micMuted) Theme.DANGER_BG else Theme.CHIP)
        (ivChipMic.parent as View).contentDescription = if (micMuted) "Микрофон выключен" else "Микрофон включён"
        if (otaLatestJson == null) tvOtaVal.text = "v$fwVer"
    }

    // =========================================================================================
    // MUSIC
    // =========================================================================================
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

    private fun setCoverArt(bmp: Bitmap?) {
        coverBitmap = bmp
        ivCover.setImageBitmap(bmp)
        ivAnswerCover.setImageBitmap(bmp)
        ivSsCover.setImageBitmap(bmp)
    }

    private fun renderMusicSnapshot(snap: YmPlayerSnapshot) {
        latestMusicSnap = snap
        LiveOrbState.musicPlaying = snap.playing
        if (snap.playing) hideScreensaver()

        val tr = snap.currentTrack
        val needAuth = tr == null && !snap.account.loggedIn && StationAudioService.instance != null
        if (needAuth != authMode) {
            val hadPrimaryFocus = primaryFocusView().isFocused
            authMode = needAuth
            ivCover.visibility = if (needAuth) View.GONE else View.VISIBLE
            playerCol.visibility = if (needAuth) View.GONE else View.VISIBLE
            authQrFrame.visibility = if (needAuth) View.VISIBLE else View.GONE
            authCol.visibility = if (needAuth) View.VISIBLE else View.GONE
            updatePrimaryFocusLinks()
            if (hadPrimaryFocus || (dashboard.hasFocus() && currentFocus?.isShown != true)) focusPrimary()
        }

        tvSource.text = (if (snap.buffering) "Загрузка…" else snap.sourceLabel.ifBlank { "Яндекс Музыка" }).uppercase()
        if (tr != null) {
            tvTitle.text = tr.title
            tvArtist.text = tr.artists
            tvArtist.setTextColor(Theme.alpha(Theme.TEXT, 0.72f))
            val dur = maxOf(snap.durationMs, tr.durationMs)
            val pos = snap.positionMs.coerceAtLeast(0L)
            pbTrack.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
            tvPos.text = formatTimeMs(pos)
            tvDur.text = formatTimeMs(dur)
            if (lastTrackLiked != tr.liked) {
                lastTrackLiked = tr.liked
                btnLike.iconView.setTintedIcon(if (tr.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
                    if (tr.liked) Theme.accent else Theme.TEXT)
            }
            val coverUrl = tr.coverUrl(300)
            if (coverUrl != lastCoverUrl) {
                lastCoverUrl = coverUrl
                setCoverArt(null)
                val api = StationAudioService.instance?.musicPlayer?.api
                if (coverUrl.isNotBlank() && api != null) {
                    thread(isDaemon = true) {
                        val bmp = api.loadCoverBitmap(coverUrl, 300)
                        if (bmp != null) handler.post { if (lastCoverUrl == coverUrl) setCoverArt(bmp) }
                    }
                }
            }
        } else {
            tvTitle.text = "Моя волна"
            tvArtist.text = "Нажмите ▶, чтобы начать"
            pbTrack.progress = 0
            tvPos.text = formatTimeMs(0)
            tvDur.text = formatTimeMs(0)
            if (lastTrackLiked != false) {
                lastTrackLiked = false
                btnLike.iconView.setTintedIcon(R.drawable.ic_heart_outline, Theme.TEXT)
            }
            if (lastCoverUrl.isNotEmpty()) {
                lastCoverUrl = ""
                setCoverArt(null)
            }
        }
        if (snap.lastError.isNotBlank()) {
            tvArtist.text = "⚠ " + snap.lastError.removePrefix("AUTH_REQUIRED:").trim()
            tvArtist.setTextColor(Theme.DANGER_FG)
        }

        val ppKey = "${snap.buffering}|${snap.playing}"
        if (ppKey != lastPlayIconKey) {
            lastPlayIconKey = ppKey
            btnPlay.iconView.setTintedIcon(
                when {
                    snap.buffering -> R.drawable.ic_refresh
                    snap.playing -> R.drawable.ic_pause
                    else -> R.drawable.ic_play
                },
                Theme.ON_ACCENT
            )
            btnPlay.contentDescription = if (snap.playing) "Пауза" else "Воспроизведение"
        }
    }

    private fun formatTimeMs(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L)
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

    // =========================================================================================
    // OTA (Ota.dc.html)
    // =========================================================================================
    private val otaDialogPollRunnable = object : Runnable {
        override fun run() {
            if (otaDialog?.isShowing != true) return
            thread(isDaemon = true) {
                try {
                    val conn = (URL("$API/api/ota/status").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 900
                        readTimeout = 1200
                    }
                    if (conn.responseCode == 200) {
                        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
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
        val key = "$avail|$busy|$latestVer|${if (busy) pct else -1}|$fwVer"
        if (key != lastOtaValKey) {
            lastOtaValKey = key
            tvOtaVal.text = when {
                busy -> "Обновление $pct%"
                avail -> if (latestVer.isNotBlank()) "Доступна v$latestVer" else "Доступно"
                else -> "v$fwVer"
            }
            tvOtaVal.setTextColor(if (avail || busy) Theme.accent else Theme.alpha(Theme.TEXT, 0.7f))
        }
        refreshOtaDialogViews()
    }

    private fun refreshOtaDialogViews() {
        val dlg = otaDialog ?: return
        if (!dlg.isShowing) return
        val o = otaLatestJson
        val instBtn = otaInstallBtn
        if (o == null) {
            otaTitleTv?.text = "Обновление прошивки"
            otaSubTv?.text = "Проверка обновлений…"
            otaBodyTv?.text = ""
            instBtn?.isEnabled = false
            instBtn?.alpha = 0.5f
            return
        }
        val instVer = o.optString("installedVersion", fwVer)
        val latVer = o.optString("latestVersion", "")
        val st = o.optString("state", "idle")
        val busy = o.optBoolean("busy", false)
        val avail = o.optBoolean("updateAvailable", false)
        val pct = o.optInt("progressPct", 0)
        val ptxt = o.optString("progressText", "")
        val err = o.optString("error", "")
        val chArr = o.optJSONArray("changedComponents")
        val comps = (0 until (chArr?.length() ?: 0)).joinToString(", ") {
            when (val c = chArr!!.optString(it)) {
                "daemon" -> "система"
                "launcher" -> "ТВ-экран"
                "ime" -> "клавиатура"
                "bootanimation" -> "заставка"
                else -> c
            }
        }
        otaTitleTv?.text = when {
            busy -> "Устанавливаю обновление"
            avail -> "Доступно обновление"
            else -> "Обновление прошивки"
        }
        otaSubTv?.text = if (latVer.isNotBlank() && latVer != instVer) "v$instVer → v$latVer" else "Установлена v$instVer"
        otaBodyTv?.text = buildString {
            when {
                st == "error" -> append("✗ Ошибка: ").append(err.ifBlank { ptxt })
                busy -> append(pct).append("% — ").append(ptxt)
                avail -> append(if (comps.isNotBlank()) "Обновятся: $comps" else "Доступна новая версия")
                st == "up_to_date" -> append(ptxt.ifBlank { "Установлена актуальная версия" })
                else -> append("Нажмите «Проверить», чтобы найти новую версию.")
            }
            val lines = o.optString("changelog", "").lines().map { it.trim().trimStart('-', '*', '•').trim() }
                .filter { it.isNotEmpty() }.take(6)
            for (l in lines) append("\n• ").append(l)
        }
        otaProgress?.progress = if (busy) pct * 10 else 0
        val enabled = !busy && (avail || st == "up_to_date")
        instBtn?.isEnabled = enabled
        instBtn?.alpha = if (enabled) 1f else 0.5f
        instBtn?.text = when {
            busy -> "Обновление ($pct%)…"
            avail -> "Установить"
            else -> "Переустановить"
        }
    }

    private fun openOtaDialog() {
        try { otaDialog?.dismiss() } catch (_: Throwable) {}
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(40), dp(40), dp(40))
            background = Theme.rounded(Theme.DIALOG, dpf(32))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(this).apply {
            background = Theme.rounded(Theme.alpha(Theme.accent, 0.18f), dpf(18))
            addView(icon(R.drawable.ic_refresh, 26, Theme.accent), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, llp(dp(56), dp(56)) { marginEnd = dp(18) })
        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label("Обновление прошивки", 28f, Theme.TEXT, 700)
        val sub = label("", 16f, Theme.alpha(Theme.TEXT, 0.65f))
        headText.addView(title)
        headText.addView(sub, llp(-2, -2) { topMargin = dp(4) })
        head.addView(headText, llp(0, -2, 1f))
        card.addView(head)
        val body = label("", 17f, Theme.alpha(Theme.TEXT, 0.82f), lines = 9).apply { setLineSpacing(0f, 1.35f) }
        card.addView(body, llp(-1, -2) { topMargin = dp(24) })
        val progress = accentProgress(6)
        card.addView(progress, llp(-1, dp(6)) { topMargin = dp(24) })
        card.addView(label("Станция перезагрузится. Настройки и вход в Яндекс Музыку сохранятся.", 14f,
            Theme.alpha(Theme.TEXT, 0.6f), lines = 2), llp(-1, -2) { topMargin = dp(8) })

        val dlg = Dialog(this)
        val btnRow = LinearLayout(this).apply { gravity = Gravity.END }
        btnRow.addView(pillButton("Позже", primary = false) { dlg.dismiss() }, llp(-2, dp(56)) { marginEnd = dp(12) })
        btnRow.addView(pillButton("Проверить", primary = false) {
            postAsync("$API/api/ota/check", "{}")
            Toast.makeText(this, "Проверка GitHub…", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(otaDialogPollRunnable)
            handler.postDelayed(otaDialogPollRunnable, 250L)
        }, llp(-2, dp(56)) { marginEnd = dp(12) })
        val install = pillButton("Установить", primary = true) {}
        install.setOnClickListener {
            val force = otaLatestJson?.optBoolean("updateAvailable", false) != true
            install.isEnabled = false
            install.alpha = 0.5f
            postAsync("$API/api/ota/install", JSONObject().put("force", force).toString())
            Toast.makeText(this, "Запуск OTA с GitHub…", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(otaDialogPollRunnable)
            handler.postDelayed(otaDialogPollRunnable, 350L)
        }
        btnRow.addView(install, llp(-2, dp(56)))
        card.addView(btnRow, llp(-1, -2) { topMargin = dp(24) })

        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(card)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
        }
        dlg.setOnDismissListener {
            handler.removeCallbacks(otaDialogPollRunnable)
            if (otaDialog === dlg) {
                otaDialog = null
                otaTitleTv = null
                otaSubTv = null
                otaBodyTv = null
                otaProgress = null
                otaInstallBtn = null
            }
        }
        otaDialog = dlg
        otaTitleTv = title
        otaSubTv = sub
        otaBodyTv = body
        otaProgress = progress
        otaInstallBtn = install
        dlg.show()
        install.requestFocus()
        refreshOtaDialogViews()
        handler.removeCallbacks(otaDialogPollRunnable)
        handler.post(otaDialogPollRunnable)
    }

    // =========================================================================================
    // ACTIONS
    // =========================================================================================
    private fun openSetupWizard(manual: Boolean) {
        try {
            startActivity(Intent(this, SetupWizardActivity::class.java).apply {
                putExtra(SetupWizardActivity.EXTRA_MANUAL, manual)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось открыть мастер: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerAlice() {
        if (assistantActive()) {
            postAsync("$API/api/assistant/stop", "{}")
        } else {
            postAsync("$API/api/assistant/trigger", """{"reason":"launcher"}""")
        }
    }

    private fun cycleRingMode() {
        val order = listOf("music", "rainbow", "solid", "breathe", "spinner", "off")
        ringMode = order[(order.indexOf(ringMode) + 1) % order.size]
        syncStationPanel()
        postAsync("$API/api/config", JSONObject().put("ledRing", JSONObject().put("mode", ringMode)).toString())
    }

    private fun toggleMute() {
        micMuted = !micMuted
        syncStationPanel()
        postAsync("$API/api/config", JSONObject().put("audio", JSONObject().put("micMuted", micMuted)).toString())
    }

    private fun cycleOutputRoute() {
        val order = listOf("auto", "speakers", "headphones", "hdmi", "both")
        outputRoute = order[(order.indexOf(outputRoute) + 1) % order.size]
        VolumeHudView.routeLabel = VolumeHudView.routeLabelFor(outputRoute)
        syncStationPanel()
        postAsync("$API/api/audio/route", JSONObject().put("route", outputRoute).toString())
    }

    private fun cycleEqPreset() {
        val order = listOf("balanced", "bass_boost", "vocal", "night", "rock", "electronic", "flat")
        eqPreset = order[(order.indexOf(eqPreset) + 1) % order.size]
        eqController.setLocalPreset(eqPreset)
        syncStationPanel()
        try {
            startForegroundService(Intent(this, StationAudioService::class.java).apply {
                action = StationAudioService.ACTION_SET_EQ
                putExtra(StationAudioService.EXTRA_PRESET, eqPreset)
            })
        } catch (_: Throwable) {}
        postAsync("$API/api/config", JSONObject().put("audio", JSONObject().put("eqPreset", eqPreset)).toString())
    }

    private fun toggleCec() {
        cecState = !cecState
        val cmd = if (cecState) "standby" else "wake"
        postAsync("$API/api/remote/cec", JSONObject().put("cmd", cmd).toString())
        Toast.makeText(this, if (cmd == "standby") "Выключение ТВ отправлено" else "Включение ТВ отправлено", Toast.LENGTH_SHORT).show()
        syncStationPanel()
    }

    /** Bluetooth A2DP sink ("Bluetooth-колонка"): the daemon toggles the adapter + discoverability. */
    private fun toggleBt() {
        btEnabled = !btEnabled
        btDiscoverable = btEnabled
        syncStationPanel()
        postAsync("$API/api/bluetooth/toggle", "{}")
    }

    /** Paired Yandex BLE remote: beep to find it; otherwise start auto pairing. */
    private fun remoteAction() {
        val st = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
        if (st?.bonded == true) {
            postAsync("$API/api/remote/find", "{}")
            Toast.makeText(this, "Пульт запищит", Toast.LENGTH_SHORT).show()
        } else {
            postAsync("$API/api/remote/pair", "{}")
            Toast.makeText(this, "Ищу пульт поблизости…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleVisualizerStyle() {
        val styles = listOf("dots", "nexus", "specterr", "aurora", "bars", "wave", "matrix", "fireflies")
        val curIdx = styles.indexOf(LiveOrbState.visualizerStyle).takeIf { it >= 0 } ?: 0
        val next = styles[(curIdx + 1) % styles.size]
        LiveOrbState.visualizerStyle = next
        LiveOrbState.previewUntilMs = System.currentTimeMillis() + 8000L
        syncStationPanel()
        Toast.makeText(this, "Визуализатор: ${visualizerName(next)}", Toast.LENGTH_SHORT).show()
        postAsync("$API/api/config", JSONObject().put("ledScreen", JSONObject().put("visualizerStyle", next)).toString())
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
