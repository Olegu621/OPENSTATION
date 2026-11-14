package com.openstation.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class StationAudioService : Service() {

    companion object {
        private const val TAG = "StationAudioSvc"
        private const val CHANNEL_ID = "openstation_hw_channel"
        private const val NOTIF_ID = 1001
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val ACTION_SET_EQ = "com.openstation.launcher.SET_EQ"
        const val EXTRA_PRESET = "preset"
        const val ACTION_MUSIC_CMD = "com.openstation.launcher.SERVICE_MUSIC_CMD"

        @Volatile
        var instance: StationAudioService? = null
            private set

        @Volatile
        var currentPreset: String = "balanced"
            private set

        @Volatile
        var eqBackend: String = "init"
            private set

        @Volatile
        var visualizerActive: Boolean = false
            private set

        @Volatile
        var isLauncherForeground: Boolean = false
            private set

        fun setLauncherForegroundState(fg: Boolean) {
            isLauncherForeground = fg
            instance?.overlayManager?.setLauncherForeground(fg)
        }

        fun ensureStarted(context: Context) {
            try {
                val i = Intent(context, StationAudioService::class.java)
                context.startForegroundService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed: ${t.message}")
            }
        }
    }

    @Volatile
    private var running = false
    @Volatile
    private var lastRemoteVolCmdMs = 0L
    @Volatile
    private var initialMusicPushed = false
    private lateinit var audioManager: AudioManager
    lateinit var musicPlayer: YandexMusicPlayer
        private set
    private var udpSocket: DatagramSocket? = null
    private var specUdpSocket: DatagramSocket? = null
    private var dynProc: DynamicsProcessing? = null
    private var legacyEq: Equalizer? = null
    private var legacyBass: BassBoost? = null
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayManager: AliceOverlayManager? = null
    private var volumeOverlayManager: VolumeOverlayManager? = null
    var bleRemoteManager: BleRemoteManager? = null
        private set
    @Volatile
    private var lastBtEventMs = 0L
    @Volatile
    private var lastBtEventKind = ""
    @Volatile
    private var zeroSpectrumSent = false
    private val volPushLock = Any()
    private var volPushThread: HandlerThread? = null
    private var volPushHandler: Handler? = null
    private val pendingVolPush = Runnable {
        if (running) pushVolumeStateToDaemon(showOsd = true)
    }

    private val bandFreqs = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private val btEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val devName = try { dev?.name ?: "Устройство" } catch (_: Throwable) { "Устройство" }
            val devType = try { dev?.type ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN } catch (_: Throwable) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
            if (bleRemoteManager?.isYandexRemoteName(devName) == true || devType == BluetoothDevice.DEVICE_TYPE_LE) {
                return
            }

            val evKind = when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
                "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" -> {
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> "connected"
                        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                        else -> ""
                    }
                }
                else -> ""
            }
            if (evKind.isEmpty()) return
            val nowMs = SystemClock.elapsedRealtime()
            if (evKind == lastBtEventKind && (nowMs - lastBtEventMs) < 1200L) return
            lastBtEventKind = evKind
            lastBtEventMs = nowMs

            if (evKind == "connected") {
                volumeOverlayManager?.showToastPill("BLUETOOTH ПОДКЛЮЧЁН", "$devName · Станция готова к воспроизведению", "#10D281", 3200L)
            } else {
                volumeOverlayManager?.showToastPill("BLUETOOTH ОТКЛЮЧЁН", devName, "#F87171", 2600L)
            }
            thread(isDaemon = true) {
                postJsonToDaemon("/api/bluetooth/device_event", JSONObject().put("event", evKind).put("device", devName))
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stream = intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) ?: -1
            if (stream == AudioManager.STREAM_MUSIC || stream == -1) {
                val fromDaemon = (SystemClock.elapsedRealtime() - lastRemoteVolCmdMs) < 900L
                if (fromDaemon) return
                showCurrentVolumeOverlay()
                val h = volPushHandler
                if (h != null) {
                    h.removeCallbacks(pendingVolPush)
                    h.postDelayed(pendingVolPush, 25L)
                } else {
                    thread(isDaemon = true) {
                        pushVolumeStateToDaemon(showOsd = true)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)
        musicPlayer = YandexMusicPlayer(this)
        AliceOrbFrames.preloadAsync(this)
        overlayManager = AliceOverlayManager(this).apply {
            setLauncherForeground(isLauncherForeground)
            start()
        }
        volumeOverlayManager = VolumeOverlayManager(this).apply { start() }
        bleRemoteManager = BleRemoteManager(this)
        bleRemoteManager?.startReconnect()
        startForegroundNotification()
        running = true
        volPushThread = HandlerThread("StationVolPush").apply { start() }.also {
            volPushHandler = Handler(it.looper)
        }
        try {
            val vf = IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(ACTION_STREAM_MUTE_CHANGED)
            }
            registerReceiver(volumeReceiver, vf)
        } catch (t: Throwable) {
            Log.w(TAG, "register volumeReceiver failed: ${t.message}")
        }
        try {
            val bf = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            }
            registerReceiver(btEventReceiver, bf)
        } catch (t: Throwable) {
            Log.w(TAG, "register btEventReceiver failed: ${t.message}")
        }
        initEqualizer()
        applyEqPreset(currentPreset)
        startUdpCommandLoop()
        startVisualizerLoop()
        startVolumeSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_EQ -> {
                val preset = intent.getStringExtra(EXTRA_PRESET) ?: "balanced"
                applyEqPreset(preset)
            }
            ACTION_MUSIC_CMD -> {
                val cmd = intent.getStringExtra("cmd") ?: ""
                when (cmd) {
                    "play_my_wave" -> musicPlayer.playMyWave(
                        intent.getStringExtra("mood") ?: "any",
                        intent.getStringExtra("seed") ?: "",
                        openUiOnAuthNeeded = true
                    )
                    "play_query" -> musicPlayer.playQuery(intent.getStringExtra("query") ?: "")
                    "action" -> handleMusicAction(intent.getStringExtra("action") ?: "")
                    "oauth" -> musicPlayer.setOAuthToken(
                        intent.getStringExtra("token") ?: "",
                        intent.getBooleanExtra("autoPlay", false)
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        bleRemoteManager?.stopReconnect()
        if (instance === this) instance = null
        try { overlayManager?.stop() } catch (_: Throwable) {}
        overlayManager = null
        try { volumeOverlayManager?.stop() } catch (_: Throwable) {}
        volumeOverlayManager = null
        try { bleRemoteManager?.release() } catch (_: Throwable) {}
        bleRemoteManager = null
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(btEventReceiver) } catch (_: Throwable) {}
        try { volPushHandler?.removeCallbacks(pendingVolPush) } catch (_: Throwable) {}
        try { volPushThread?.quitSafely() } catch (_: Throwable) {}
        volPushHandler = null
        volPushThread = null
        try { udpSocket?.close() } catch (_: Throwable) {}
        try { specUdpSocket?.close() } catch (_: Throwable) {}
        specUdpSocket = null
        try { musicPlayer.release() } catch (_: Throwable) {}
        releaseEffects()
        try { visualizer?.release() } catch (_: Throwable) {}
        visualizerActive = false
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setSmallIcon(R.drawable.ic_launcher_open)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun initEqualizer() {
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, bandFreqs.size,
                false, 0,
                false, 0,
                true
            ).build()
            val dp = DynamicsProcessing(0, 0, cfg)
            dp.enabled = true
            dynProc = dp
            eqBackend = "DynamicsProcessing(API28)"
            Log.i(TAG, "Initialized global DynamicsProcessing on session 0")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "DynamicsProcessing session 0 unavailable (${t.message}), trying Equalizer+BassBoost")
        }

        try {
            val eq = Equalizer(0, 0)
            eq.enabled = true
            legacyEq = eq
            val bb = BassBoost(0, 0)
            bb.enabled = true
            legacyBass = bb
            eqBackend = "Equalizer+BassBoost"
            Log.i(TAG, "Initialized legacy Equalizer + BassBoost on session 0")
        } catch (t: Throwable) {
            eqBackend = "disabled"
            Log.e(TAG, "Failed to init any global audio EQ effect: ${t.message}")
        }
    }

    private fun releaseEffects() {
        try { dynProc?.release() } catch (_: Throwable) {}
        dynProc = null
        try { legacyEq?.release() } catch (_: Throwable) {}
        legacyEq = null
        try { legacyBass?.release() } catch (_: Throwable) {}
        legacyBass = null
    }

    fun applyEqPreset(preset: String) {
        applyEq(preset, null, 20)
    }

    fun applyEq(preset: String, customBands: FloatArray? = null, bassBoostPct: Int = 20) {
        currentPreset = preset
        val gainsDb = when (preset) {
            "flat" -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            "bass_boost" -> floatArrayOf(4.5f, 4.0f, 3.0f, 1.5f, 0.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.0f)
            "vocal" -> floatArrayOf(-2.0f, -1.0f, 0.0f, 1.5f, 3.0f, 3.5f, 2.0f, 1.0f, 0.5f, -0.5f)
            "night" -> floatArrayOf(-3.0f, -2.5f, -1.5f, 0.0f, 1.0f, 1.0f, 0.5f, -0.5f, -2.0f, -3.0f)
            "rock" -> floatArrayOf(3.5f, 2.5f, 1.0f, -0.5f, -1.0f, 0.0f, 1.5f, 2.5f, 3.5f, 3.5f)
            "electronic" -> floatArrayOf(4.0f, 3.5f, 2.0f, 0.5f, -0.5f, 0.0f, 1.0f, 2.0f, 3.5f, 4.0f)
            "custom" -> customBands ?: floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            else -> floatArrayOf(1.0f, 1.5f, 1.0f, 0.5f, 0.0f, 0.0f, 0.5f, 1.0f, 1.5f, 1.5f) // "balanced"
        }

        try {
            dynProc?.let { dp ->
                val eq = DynamicsProcessing.Eq(true, true, bandFreqs.size)
                for (i in bandFreqs.indices) {
                    val g = if (i < gainsDb.size) gainsDb[i] else 0f
                    eq.setBand(i, DynamicsProcessing.EqBand(true, bandFreqs[i], g))
                }
                dp.setPreEqAllChannelsTo(eq)
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DynamicsProcessing apply error: ${t.message}")
        }

        try {
            legacyEq?.let { eq ->
                val num = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                for (b in 0 until num) {
                    val idx = min(gainsDb.size - 1, (b * gainsDb.size) / max(1, num))
                    val mb = (gainsDb[idx] * 100f).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
            }
            legacyBass?.let { bb ->
                if (bb.strengthSupported) {
                    val str = (bassBoostPct * 10).coerceIn(0, 1000)
                    bb.setStrength(str.toShort())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy EQ apply error: ${t.message}")
        }
    }

    private fun startUdpCommandLoop() {
        thread(name = "LauncherUdpCmd", isDaemon = true) {
            while (running) {
                var sock: DatagramSocket? = null
                try {
                    sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8086))
                    }
                    udpSocket = sock
                    val buf = ByteArray(4096)
                    while (running) {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        handleUdpJson(msg)
                    }
                } catch (t: Throwable) {
                    if (running) {
                        Log.w(TAG, "UDP 8086 listener restarting: ${t.message}")
                        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                    }
                } finally {
                    try { sock?.close() } catch (_: Throwable) {}
                    if (udpSocket === sock) udpSocket = null
                }
            }
        }
    }

    private fun handleUdpJson(raw: String) {
        try {
            val obj = JSONObject(raw)
            when (obj.optString("cmd")) {
                "eq" -> {
                    val preset = obj.optString("preset", "balanced")
                    val bb = obj.optInt("bassBoost", 20)
                    var customBands: FloatArray? = null
                    val arr = obj.optJSONArray("bands")
                    if (arr != null && arr.length() >= 10) {
                        customBands = FloatArray(10) { i -> arr.optDouble(i, 0.0).toFloat() }
                    }
                    applyEq(preset, customBands, bb)
                    Log.i(TAG, "Applied EQ via daemon -> $preset, bassBoost=$bb")
                }
                "style_sync" -> {
                    val clk = obj.optString("clockStyle", "")
                    val vis = obj.optString("visualizerStyle", "")
                    val autoVis = if (obj.has("autoMusicVisualizer")) obj.optBoolean("autoMusicVisualizer") else null
                    LiveOrbState.updateStyles(clk, vis, autoVis)
                    Log.i(TAG, "Synced styles: clock=$clk, vis=$vis, autoVis=$autoVis")
                }
                "weather_event" -> {
                    val valid = obj.optBoolean("valid", false)
                    val temp = obj.optInt("temp", 0)
                    val cond = obj.optString("condition", "sunny")
                    val text = obj.optString("conditionText", "")
                    val city = obj.optString("city", "")
                    LiveOrbState.updateWeather(valid, temp, cond, text, city)
                    if (valid) {
                        MainActivity.showWeatherOverlay(temp, cond, text, city)
                    }
                }
                "launch" -> {
                    val pkg = obj.optString("package")
                    if (pkg.isNotEmpty()) launchPackage(pkg)
                }
                "cec" -> {
                    val act = obj.optString("action")
                    Log.i(TAG, "CEC command notice: $act")
                    if (act == "wake" || act == "wakeup") {
                        wakeScreen()
                    }
                }
                "volume_step" -> {
                    val dir = obj.optInt("dir", 1)
                    val steps = obj.optInt("steps", 1).coerceIn(1, 5)
                    val showUi = obj.optBoolean("showUi", false)
                    val showOsd = obj.optBoolean("showOsd", true)
                    lastRemoteVolCmdMs = SystemClock.elapsedRealtime()
                    val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
                    val direction = if (dir >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    for (i in 0 until steps) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags)
                    }
                    lastRemoteVolCmdMs = SystemClock.elapsedRealtime()
                    if (showOsd) showCurrentVolumeOverlay()
                }
                "volume_set" -> {
                    val pct = obj.optInt("percent", 50).coerceIn(0, 100)
                    val showUi = obj.optBoolean("showUi", false)
                    val showOsd = obj.optBoolean("showOsd", true)
                    lastRemoteVolCmdMs = SystemClock.elapsedRealtime()
                    val maxV = max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    val idx = ((pct / 100.0) * maxV).roundToInt().coerceIn(0, maxV)
                    val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, flags)
                    lastRemoteVolCmdMs = SystemClock.elapsedRealtime()
                    if (showOsd) {
                        val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                        volumeOverlayManager?.showVolume(pct, muted)
                    }
                }
                "go_home" -> {
                    handler.post { bringLauncherHome() }
                }
                "find_remote" -> {
                    volumeOverlayManager?.showToastPill("ПОИСК ПУЛЬТА", "Отправляю BLE-сигнал зуммера на пульт...", "#E8C686", 2400L)
                    bleRemoteManager?.triggerBuzzer { ok, msg ->
                        volumeOverlayManager?.showToastPill(
                            if (ok) "ПУЛЬТ ОТКЛИКНУЛСЯ" else "ПУЛЬТ НЕ НАЙДЕН",
                            msg,
                            if (ok) "#E8C686" else "#F87171",
                            3500L
                        )
                    }
                }
                "pair_remote" -> {
                    bleRemoteManager?.startAutoScanAndPair(60000L) { st, detail ->
                        val col = when (st) {
                            "connected" -> "#10D281"
                            "error", "not_found" -> "#F87171"
                            else -> "#00D4FF"
                        }
                        volumeOverlayManager?.showToastPill("BLE ПУЛЬТ YANDEX", detail, col, 3200L)
                    }
                }
                "refresh_apps" -> {
                    handler.post { MainActivity.refreshAppsFromService() }
                }
                "startup_show" -> {
                    handler.post { MainActivity.triggerStartupShow() }
                }
                "bluetooth" -> {
                    val act = obj.optString("action", "discoverable")
                    val duration = obj.optInt("duration", 300)
                    handleBluetoothCommand(act, duration)
                }
                "launch_music" -> {
                    openYandexMusicActivity(obj.optString("tab", ""), obj.optBoolean("openLogin", false))
                }
                "play_my_wave" -> {
                    val mood = obj.optString("mood", "any")
                    val seed = obj.optString("seed", "")
                    val openUi = obj.optBoolean("openUi", false)
                    musicPlayer.playMyWave(mood, seed, openUiOnAuthNeeded = true)
                    if (openUi) openYandexMusicActivity("wave")
                }
                "play_query" -> {
                    val q = obj.optString("query", "")
                    val openUi = obj.optBoolean("openUi", false)
                    musicPlayer.playQuery(q, openUiOnAuthNeeded = true)
                    if (openUi) openYandexMusicActivity("search")
                }
                "music_oauth" -> {
                    val tok = obj.optString("token", "").trim()
                    val autoPlay = obj.optBoolean("autoPlay", false)
                    if (tok.isNotEmpty()) {
                        musicPlayer.setOAuthToken(tok, autoResumeMyWave = autoPlay)
                    }
                }
                "music_duck" -> {
                    val duck = obj.optBoolean("duck", false)
                    musicPlayer.setDuck(duck)
                }
                "music_action" -> {
                    val act = obj.optString("action", "")
                    if (act.isNotEmpty()) {
                        handleMusicAction(act)
                    }
                }
                "assistant_event" -> {
                    val st = obj.optString("state", "idle")
                    val lu = obj.optString("liveUser", "")
                    val lm = obj.optString("liveModel", "")
                    val lastM = obj.optString("lastModel", "")
                    val err = obj.optString("lastError", "")
                    LiveOrbState.updateAssistantEvent(st, lu, lm, lastM, err)
                }
                "voice_level" -> {
                    val st = obj.optString("state", "")
                    val tts = obj.optDouble("ttsRms", 0.0).toFloat()
                    val mic = obj.optDouble("micRms", 0.0).toFloat()
                    LiveOrbState.updateVoiceLevel(st, tts, mic)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bad UDP command: ${t.message}")
        }
    }

    private fun handleBluetoothCommand(action: String, durationSec: Int) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Log.w(TAG, "BluetoothAdapter is null on this device")
                return
            }

            when (action.lowercase()) {
                "enable", "discoverable", "on" -> {
                    if (!adapter.isEnabled) {
                        adapter.enable()
                        Log.i(TAG, "Bluetooth adapter enabling...")
                    }
                    try {
                        if (adapter.name != "OpenStation Max") {
                            adapter.name = "OpenStation Max"
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to set BT adapter name: ${e.message}")
                    }

                    // Set SCAN_MODE_CONNECTABLE_DISCOVERABLE via reflection
                    try {
                        val setScanMode = adapter.javaClass.getMethod(
                            "setScanMode",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        setScanMode.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, durationSec)
                        Log.i(TAG, "Bluetooth set to DISCOVERABLE for $durationSec sec")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Reflection setScanMode failed: ${e.message}")
                    }

                    handler.post {
                        volumeOverlayManager?.showToastPill(
                            "BLUETOOTH КОЛОНКА",
                            "Видима как OpenStation Max ($durationSec сек)",
                            "#00D4FF",
                            3500L
                        )
                    }
                }
                "disable", "off" -> {
                    try {
                        val setScanMode = adapter.javaClass.getMethod(
                            "setScanMode",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        setScanMode.invoke(adapter, BluetoothAdapter.SCAN_MODE_NONE, 0)
                    } catch (_: Throwable) {}
                    adapter.disable()
                    Log.i(TAG, "Bluetooth adapter disabled")
                    handler.post {
                        volumeOverlayManager?.showToastPill(
                            "BLUETOOTH ВЫКЛЮЧЕН",
                            "Режим беспроводной колонки отключён",
                            "#F87171",
                            2600L
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleBluetoothCommand error", t)
        }
    }

    private fun openYandexMusicActivity(tab: String = "", openLogin: Boolean = false) {
        try {
            val i = Intent(this, YandexMusicActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (tab.isNotBlank()) putExtra("tab", tab)
                if (openLogin) putExtra("open_login", true)
            }
            startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot launch YandexMusicActivity: ${t.message}")
        }
    }

    private fun handleMusicAction(rawAction: String) {
        when (rawAction.trim().uppercase()) {
            "PLAY", "PLAY_PAUSE", "TOGGLE" -> musicPlayer.togglePlayPause()
            "RESUME", "PLAY_ONLY" -> musicPlayer.play()
            "PAUSE" -> musicPlayer.pause()
            "STOP" -> musicPlayer.stop()
            "MOVE_FORWARD", "NEXT" -> musicPlayer.next()
            "MOVE_BACKWARD", "PREV", "PREVIOUS" -> musicPlayer.prev()
            "LIKE" -> musicPlayer.toggleLikeCurrent()
            "DISLIKE" -> musicPlayer.dislikeCurrent()
            "PLAY_LIKED", "LIKED" -> musicPlayer.playLikedTracks(openUiOnAuthNeeded = true)
            "PLAY_CHART", "CHART" -> musicPlayer.playChartTracks()
            "MY_WAVE", "PLAY_MY_WAVE", "WAVE" -> musicPlayer.playMyWave("any", "", openUiOnAuthNeeded = true)
            else -> Log.w(TAG, "Unknown music_action: $rawAction")
        }
    }

    fun setLauncherForeground(fg: Boolean) {
        overlayManager?.setLauncherForeground(fg)
    }

    private fun startVolumeSyncLoop() {
        thread(name = "VolumeStateSync", isDaemon = true) {
            while (running) {
                val ok = pushVolumeStateToDaemon(showOsd = false)
                if (ok && !initialMusicPushed) {
                    initialMusicPushed = true
                    musicPlayer.emitSnapshot(forceDaemon = true)
                    syncInitialAssistantState()
                }
                try {
                    Thread.sleep(if (ok) 15000L else 2000L)
                } catch (_: InterruptedException) {}
            }
        }
    }

    private fun showCurrentVolumeOverlay() {
        try {
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxV = max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            val pct = if (muted || cur <= 0) 0 else ((cur * 100.0) / maxV).roundToInt().coerceIn(1, 100)
            volumeOverlayManager?.showVolume(pct, muted)
        } catch (_: Throwable) {}
    }

    private fun bringLauncherHome() {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra("go_home", true)
            }
            startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "bringLauncherHome failed: ${t.message}")
        }
    }

    private fun postJsonToDaemon(path: String, body: JSONObject) {
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val conn = (URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 800
                readTimeout = 800
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {}
    }

    private fun syncInitialAssistantState() {
        try {
            val conn = (URL("http://127.0.0.1:8080/api/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 600
                readTimeout = 600
            }
            if (conn.responseCode == 200) {
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val ast = JSONObject(txt).optJSONObject("assistant")
                if (ast != null) {
                    LiveOrbState.updateAssistantEvent(
                        state = ast.optString("state", "idle"),
                        liveUser = ast.optString("liveUserText", ""),
                        liveModel = ast.optString("liveModelText", ""),
                        lastModel = ast.optString("lastModelText", ""),
                        error = ast.optString("lastError", "")
                    )
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}
    }

    private fun pushVolumeStateToDaemon(showOsd: Boolean): Boolean {
        synchronized(volPushLock) {
            return try {
                val t0 = lastRemoteVolCmdMs
                if ((SystemClock.elapsedRealtime() - t0) < 900L) return true
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxV = max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                if (lastRemoteVolCmdMs != t0 || (SystemClock.elapsedRealtime() - t0) < 900L) return true
                val pct = if (muted || cur <= 0) 0 else ((cur * 100.0) / maxV).roundToInt().coerceIn(1, 100)
                val payload = JSONObject()
                    .put("percent", pct)
                    .put("max", maxV)
                    .put("showOsd", showOsd)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val url = URL("http://127.0.0.1:8080/api/audio/volume_state")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 500
                    readTimeout = 500
                    setRequestProperty("Content-Type", "application/json")
                }
                if (lastRemoteVolCmdMs != t0 || (SystemClock.elapsedRealtime() - t0) < 900L) {
                    conn.disconnect()
                    return true
                }
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "openstation:cec_wake"
            )
            wl.acquire(2500L)
        } catch (_: Throwable) {}
    }

    private fun launchPackage(pkg: String) {
        try {
            var intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            if (intent == null) {
                intent = packageManager.getLaunchIntentForPackage(pkg)
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot launch $pkg: ${t.message}")
        }
    }

    private fun startVisualizerLoop() {
        thread(name = "VisualizerSpectrum", isDaemon = true) {
            val smooth = FloatArray(24)
            val bands = FloatArray(24)
            val zeroBands = FloatArray(24)
            val binStarts = IntArray(24)
            val binEnds = IntArray(24)
            val specBytes = ByteArray(24 * 4)
            val specBuf = ByteBuffer.wrap(specBytes).order(ByteOrder.LITTLE_ENDIAN)
            val specAddr = InetAddress.getByName("127.0.0.1")
            val specPkt = DatagramPacket(specBytes, specBytes.size, specAddr, 8087)

            var silentTicks = 100
            var visEnabled = false
            var fftBuf: ByteArray? = null

            var lastReportedPlaying: Boolean? = null
            fun sendUdpMusicState(playing: Boolean) {
                if (lastReportedPlaying == playing) return
                lastReportedPlaying = playing
                try {
                    val payload = JSONObject().put("cmd", "music_active").put("playing", playing).toString().toByteArray(Charsets.UTF_8)
                    val sock = specUdpSocket?.takeUnless { it.isClosed } ?: DatagramSocket().also { specUdpSocket = it }
                    val pkt = DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), 8087)
                    sock.send(pkt)
                } catch (_: Throwable) {}
            }

            fun sendUdpBands(arr: FloatArray) {
                try {
                    val sock = specUdpSocket?.takeUnless { it.isClosed } ?: DatagramSocket().also { specUdpSocket = it }
                    specBuf.clear()
                    for (b in 0 until 24) specBuf.putFloat(arr[b])
                    specPkt.length = specBytes.size
                    sock.send(specPkt)
                } catch (_: Throwable) {
                    // openstationd or loopback socket not ready yet
                }
            }

            try {
                while (running) {
                    if (visualizer == null) {
                        try {
                            val v = Visualizer(0)
                            val cap = min(1024, Visualizer.getCaptureSizeRange()[1])
                            v.captureSize = cap
                            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                            val startActive = musicPlayer.getSnapshot().playing ||
                                audioManager.isMusicActive ||
                                LiveOrbState.assistantState != "idle"
                            v.enabled = startActive
                            visEnabled = startActive

                            val halfBins = cap / 2
                            for (b in 0 until 24) {
                                val t0 = b / 24.0
                                val t1 = (b + 1) / 24.0
                                val b0 = max(1, Math.pow(halfBins.toDouble(), t0).toInt())
                                val b1 = max(b0 + 1, min(halfBins - 1, Math.pow(halfBins.toDouble(), t1).toInt()))
                                binStarts[b] = b0
                                binEnds[b] = b1
                            }

                            visualizer = v
                            fftBuf = ByteArray(cap)
                            visualizerActive = true
                            Log.i(TAG, "Audio Visualizer attached to session 0 (cap=$cap, enabled=$startActive)")
                        } catch (t: Throwable) {
                            visualizerActive = false
                            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                            continue
                        }
                    }

                    val v = visualizer
                    val fft = fftBuf
                    if (v != null && fft != null) {
                        val audioLikelyActive = musicPlayer.getSnapshot().playing ||
                            audioManager.isMusicActive ||
                            LiveOrbState.assistantState != "idle"

                        if (!visEnabled) {
                            if (audioLikelyActive) {
                                try {
                                    v.enabled = true
                                    visEnabled = true
                                    silentTicks = 0
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Visualizer resume error: ${t.message}")
                                    try { v.release() } catch (_: Throwable) {}
                                    visualizer = null
                                    visualizerActive = false
                                    visEnabled = false
                                }
                            } else {
                                try { Thread.sleep(250) } catch (_: InterruptedException) {}
                                continue
                            }
                        }

                        try {
                            val rc = v.getFft(fft)
                            if (rc == Visualizer.SUCCESS) {
                                var peak = 0f
                                for (b in 0 until 24) {
                                    val b0 = binStarts[b]
                                    val b1 = binEnds[b]
                                    var sum = 0f
                                    for (k in b0..b1) {
                                        val re = fft[2 * k].toFloat()
                                        val im = fft[2 * k + 1].toFloat()
                                        sum += sqrt(re * re + im * im)
                                    }
                                    val mag = sum / max(1, b1 - b0 + 1)
                                    val norm = (ln(1f + mag * 0.45f) / 3.4f).coerceIn(0f, 1f)
                                    val prev = smooth[b]
                                    val sm = if (norm > prev) {
                                        prev * 0.35f + norm * 0.65f
                                    } else {
                                        prev * 0.72f + norm * 0.28f
                                    }
                                    smooth[b] = sm
                                    bands[b] = sm
                                    if (sm > peak) peak = sm
                                }

                                val isPlaying = musicPlayer.getSnapshot().playing
                                sendUdpMusicState(isPlaying)
                                if (peak > 0.025f) {
                                    silentTicks = 0
                                    zeroSpectrumSent = false
                                    LiveOrbState.updateMusicSpectrum(bands, isPlaying)
                                    sendUdpBands(bands)
                                } else if (silentTicks < 6) {
                                    silentTicks++
                                    LiveOrbState.updateMusicSpectrum(bands, isPlaying)
                                    sendUdpBands(bands)
                                } else {
                                    silentTicks++
                                    if (!zeroSpectrumSent) {
                                        zeroSpectrumSent = true
                                        smooth.fill(0f)
                                        LiveOrbState.updateMusicSpectrum(zeroBands, isPlaying)
                                        sendUdpBands(zeroBands)
                                    } else if (isPlaying != LiveOrbState.musicPlaying) {
                                        LiveOrbState.musicPlaying = isPlaying
                                    }
                                    if (silentTicks >= 45 && !audioLikelyActive && visEnabled) {
                                        try {
                                            v.enabled = false
                                            visEnabled = false
                                        } catch (_: Throwable) {}
                                        try { Thread.sleep(250) } catch (_: InterruptedException) {}
                                        continue
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Visualizer capture error: ${t.message}")
                            try { v.release() } catch (_: Throwable) {}
                            visualizer = null
                            visualizerActive = false
                            visEnabled = false
                        }
                    }
                    try { Thread.sleep(45) } catch (_: InterruptedException) {}
                }
            } finally {
                try { specUdpSocket?.close() } catch (_: Throwable) {}
                specUdpSocket = null
            }
        }
    }
}
