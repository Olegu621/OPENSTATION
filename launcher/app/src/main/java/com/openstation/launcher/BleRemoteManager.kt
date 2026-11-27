package com.openstation.launcher

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Manages native Yandex Station BLE Remote (HID 0x1812) discovery,
 * re-bonding after remote resets, battery query, and physical buzzer alerts.
 */
class BleRemoteManager(private val context: Context) {

    companion object {
        private const val TAG = "BleRemoteMgr"
        private const val PREF_NAME = "openstation_remote_prefs"
        private const val KEY_REMOTE_MAC = "yndx_remote_mac"
        private const val KEY_REMOTE_NAME = "yndx_remote_name"

        private const val PROFILE_HID_HOST = 4
        private const val TRANSPORT_LE = 2
        private const val PRIORITY_AUTO_CONNECT = 1000
        private const val YANDEX_MFG_ID = 0x057A // 1402

        private val UUID_HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        private val UUID_IMMEDIATE_ALERT = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
        private val UUID_ALERT_LEVEL = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")
        private val UUID_LINK_LOSS = UUID.fromString("00001803-0000-1000-8000-00805f9b34fb")
        private val UUID_BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    data class RemoteStatus(
        val bonded: Boolean,
        val connected: Boolean,
        val name: String,
        val address: String,
        val batteryPct: Int = -1
    )

    private val handler = Handler(Looper.getMainLooper())
    private val scanning = AtomicBoolean(false)
    private var activeScanCallback: ScanCallback? = null
    private var activeScanStatusCb: ((state: String, detail: String) -> Unit)? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var scanConnectPollRunnable: Runnable? = null
    private val scanOpSeq = AtomicInteger(0)

    private var bondReceiver: BroadcastReceiver? = null
    private var bondTimeoutRunnable: Runnable? = null

    private var reconnectActive = false
    private var reconnectAttempts = 0
    private val connectSeq = AtomicInteger(0)
    private val hidConnecting = AtomicBoolean(false)
    @Volatile private var hidConnectedAddress: String? = null
    @Volatile private var hidProxy: BluetoothProfile? = null
    private var hidProxyRequested = false
    private val connectedAddresses = ConcurrentHashMap.newKeySet<String>()
    private var hidReceiverRegistered = false

    private val hidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val saved = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
                    val devName = safeDeviceName(device)
                    if (!isYandexRemoteName(devName) && (saved.isEmpty() || !device.address.equals(saved, ignoreCase = true))) {
                        return
                    }
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectedAddresses.add(device.address)
                            hidConnectedAddress = device.address
                            reconnectAttempts = 0
                            persistRemoteIdentity(device.address, devName.ifBlank { "Yandex Remote" })
                            val cb = activeScanStatusCb
                            if (scanning.get() && cb != null) {
                                stopScan()
                                cb("connected", "Пульт ${devName.ifBlank { "Yandex Remote" }} подключён!")
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            val wasConnected = connectedAddresses.remove(device.address) || hidConnectedAddress == device.address
                            if (hidConnectedAddress == device.address) hidConnectedAddress = null
                            // Never spam immediate reconnects when a direct connect attempt just timed out
                            // (that was what caused the 35s infinite reason=256 controller loop).
                            if (wasConnected && !hidConnecting.get() && !scanning.get() && bondReceiver == null) {
                                reconnectAttempts = 0
                                scheduleReconnect(8_000L)
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                        reconnectAttempts = 0
                        ensureHidProxy(getAdapter())
                        scheduleReconnect(2_000L)
                    } else {
                        connectedAddresses.clear()
                        hidConnectedAddress = null
                        hidProxy = null
                        hidProxyRequested = false
                    }
                }
            }
        }
    }

    private val reconnectTask = object : Runnable {
        override fun run() {
            if (!reconnectActive) return
            val adapter = getAdapter()
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect(30_000L)
                return
            }
            if (scanning.get() || bondReceiver != null) {
                scheduleReconnect(30_000L)
                return
            }
            ensureHidProxy(adapter)
            val device = try { findTargetDevice() } catch (_: Throwable) { null }
            if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
                reconnectAttempts = 0
                // Passive low-power listener for brand new unbonded setup.
                startLeScanInternal(adapter, 45_000L, { state, _ ->
                    when (state) {
                        "connected" -> scheduleReconnect(60_000L)
                        "not_found" -> scheduleReconnect(20_000L)
                        "error" -> scheduleReconnect(60_000L)
                    }
                }, ScanSettings.SCAN_MODE_LOW_POWER, cancelIdleConnect = false)
                scheduleReconnect(60_000L)
                return
            }

            if (isDeviceConnectedNow(adapter, device)) {
                reconnectAttempts = 0
                scheduleReconnect(RemoteIdentity.nextReconnectDelayMs(0, connected = true))
                return
            }

            // For BLE HOGP remotes, repeated active HidHost.connect() against a sleeping/offline
            // remote stalls Bluedroid's LE initiator (reason=256 every 35s). Try once after boot/disconnect,
            // then let Bluedroid passive whitelist autoconnect handle key-press wakeup.
            if (reconnectAttempts >= 1) {
                scheduleReconnect(RemoteIdentity.nextReconnectDelayMs(reconnectAttempts, connected = false))
                return
            }

            connectHid(adapter, device) { state, _ ->
                if (state == "connected") {
                    reconnectAttempts = 0
                    scheduleReconnect(RemoteIdentity.nextReconnectDelayMs(0, connected = true))
                } else {
                    reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10)
                    scheduleReconnect(RemoteIdentity.nextReconnectDelayMs(reconnectAttempts, connected = false))
                }
            }
        }
    }

    @Volatile
    private var lastBatteryPct: Int = -1

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getAdapter(): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private fun safeDeviceName(device: BluetoothDevice?): String {
        if (device == null) return ""
        return try { device.name.orEmpty() } catch (_: SecurityException) { "" }
    }

    private fun persistRemoteIdentity(address: String, name: String) {
        if (address.isBlank()) return
        val cleanName = name.ifBlank { "Yandex Remote" }
        prefs.edit().putString(KEY_REMOTE_MAC, address).putString(KEY_REMOTE_NAME, cleanName).apply()
    }

    private fun ensureHidProxy(adapter: BluetoothAdapter?) {
        if (adapter == null || !adapter.isEnabled || hidProxy != null || hidProxyRequested) return
        hidProxyRequested = true
        try {
            val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    hidProxy = proxy
                    syncConnectedFromProxy(proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    hidProxy = null
                    hidProxyRequested = false
                }
            }, PROFILE_HID_HOST)
            if (!ok) hidProxyRequested = false
        } catch (_: Throwable) {
            hidProxyRequested = false
        }
    }

    private fun syncConnectedFromProxy(proxy: BluetoothProfile?) {
        if (proxy == null) return
        try {
            val devs = proxy.connectedDevices ?: return
            for (d in devs) {
                val nm = safeDeviceName(d)
                val saved = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
                if (isYandexRemoteName(nm) || (saved.isNotEmpty() && d.address.equals(saved, ignoreCase = true))) {
                    connectedAddresses.add(d.address)
                    hidConnectedAddress = d.address
                    persistRemoteIdentity(d.address, nm)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun activeInputDeviceNames(): List<String> {
        val out = ArrayList<String>()
        try {
            val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return out
            for (id in im.inputDeviceIds) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.isVirtual) continue
                val nm = d.name.orEmpty()
                if (isYandexRemoteName(nm) || RemoteIdentity.isYandexVendorId(d.vendorId)) {
                    out.add(nm)
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun isDeviceConnectedNow(
        adapter: BluetoothAdapter?,
        device: BluetoothDevice,
        totalYandexBonded: Int = 1,
        activeInputs: List<String> = activeInputDeviceNames()
    ): Boolean {
        val proxy = hidProxy
        if (proxy != null) {
            try {
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    connectedAddresses.add(device.address)
                    hidConnectedAddress = device.address
                    return true
                }
            } catch (_: Throwable) {}
        }
        val devName = safeDeviceName(device)
        if (activeInputs.any { inputName ->
                inputName.equals(devName, ignoreCase = true) ||
                    (totalYandexBonded <= 1 && isYandexRemoteName(inputName))
            }
        ) {
            connectedAddresses.add(device.address)
            hidConnectedAddress = device.address
            return true
        }
        if (connectedAddresses.contains(device.address) || hidConnectedAddress == device.address) {
            // Confirm against GATT connection list if available when proxy hasn't bound yet
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val gattConnected = try { bm?.getConnectedDevices(BluetoothProfile.GATT) } catch (_: Throwable) { null }
            if (gattConnected != null && gattConnected.any { it.address == device.address }) {
                return true
            }
        }
        return false
    }

    fun isYandexRemoteName(name: String?): Boolean {
        return RemoteIdentity.isYandexRemoteName(name)
    }

    fun startReconnect() {
        handler.post {
            reconnectActive = true
            ensureHidProxy(getAdapter())
            if (!hidReceiverRegistered) {
                try {
                    val filter = IntentFilter().apply {
                        addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED")
                        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    }
                    context.registerReceiver(hidReceiver, filter)
                    hidReceiverRegistered = true
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to observe remote connection state", t)
                }
            }
            reconnectAttempts = 0
            scheduleReconnect(6_000L)
        }
    }

    fun stopReconnect() {
        handler.post {
            reconnectActive = false
            handler.removeCallbacks(reconnectTask)
            connectSeq.incrementAndGet()
            hidConnecting.set(false)
            stopScan()
            unregisterBondReceiver()
            if (hidReceiverRegistered) {
                try { context.unregisterReceiver(hidReceiver) } catch (_: Throwable) {}
                hidReceiverRegistered = false
            }
            val adapter = getAdapter()
            hidProxy?.let { p -> try { adapter?.closeProfileProxy(PROFILE_HID_HOST, p) } catch (_: Throwable) {} }
            hidProxy = null
            hidProxyRequested = false
            connectedAddresses.clear()
            hidConnectedAddress = null
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (!reconnectActive) return
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, delayMs)
    }

    fun getRemoteStatus(): RemoteStatus {
        return try {
            val adapter = getAdapter()
            ensureHidProxy(adapter)
            val device = findTargetDevice()
            if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
                RemoteStatus(false, false, "", "", -1)
            } else {
                val connected = isDeviceConnectedNow(adapter, device)
                val nm = safeDeviceName(device).ifBlank {
                    prefs.getString(KEY_REMOTE_NAME, "Yandex Remote") ?: "Yandex Remote"
                }
                RemoteStatus(
                    bonded = true,
                    connected = connected,
                    name = nm,
                    address = device.address,
                    batteryPct = lastBatteryPct
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getRemoteStatus failed: ${t.message}")
            RemoteStatus(false, false, "", "", -1)
        }
    }

    private fun collectBondedYandexRemotes(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val byAddr = LinkedHashMap<String, BluetoothDevice>()
        val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
        try {
            adapter.bondedDevices?.forEach { dev ->
                val nm = safeDeviceName(dev)
                if (isYandexRemoteName(nm) || (savedMac.isNotEmpty() && dev.address.equals(savedMac, ignoreCase = true))) {
                    byAddr[dev.address] = dev
                }
            }
        } catch (_: Throwable) {}
        if (savedMac.isNotEmpty() && !byAddr.containsKey(savedMac)) {
            try {
                adapter.getRemoteDevice(savedMac).takeIf { it.bondState == BluetoothDevice.BOND_BONDED }
                    ?.let { byAddr[it.address] = it }
            } catch (_: Throwable) {}
        }
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val connected = bm?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
            connected.forEach { dev ->
                if (dev.bondState == BluetoothDevice.BOND_BONDED && isYandexRemoteName(safeDeviceName(dev))) {
                    byAddr[dev.address] = dev
                }
            }
        } catch (_: Throwable) {}
        return byAddr.values.toList()
    }

    fun findTargetDevice(): BluetoothDevice? {
        val adapter = getAdapter() ?: return null
        val bonded = collectBondedYandexRemotes(adapter)
        if (bonded.isEmpty()) return null
        val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
        val activeInputs = activeInputDeviceNames()
        val candidates = bonded.map { dev ->
            val nm = safeDeviceName(dev).ifBlank { "Yandex Remote" }
            val connected = isDeviceConnectedNow(adapter, dev, bonded.size, activeInputs)
            RemoteIdentity.BondedRemoteCandidate(dev.address, nm, connected)
        }
        val picked = RemoteIdentity.pickPreferredRemote(candidates, savedMac) ?: return null
        val dev = bonded.firstOrNull { it.address.equals(picked.address, ignoreCase = true) } ?: return null
        if (picked.connected && !picked.address.equals(savedMac, ignoreCase = true)) {
            persistRemoteIdentity(picked.address, picked.name)
        }
        return dev
    }

    fun startAutoScanAndPair(
        durationMs: Long = 60000L,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        startAutoScanAndPair(durationMs, forceScan = false, onStatus = onStatus)
    }

    fun startAutoScanAndPair(
        durationMs: Long = 60000L,
        forceScan: Boolean,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        val seq = scanOpSeq.incrementAndGet()
        startAutoScanAndPairInternal(durationMs, forceScan, seq, onStatus, 0)
    }

    fun forgetBondedRemotes(onDone: (removedCount: Int, detail: String) -> Unit) {
        handler.post {
            scanOpSeq.incrementAndGet()
            connectSeq.incrementAndGet()
            hidConnecting.set(false)
            handler.removeCallbacks(reconnectTask)
            stopScan()
            unregisterBondReceiver()

            val adapter = getAdapter()
            val bonded = if (adapter != null) collectBondedYandexRemotes(adapter) else emptyList()
            var removed = 0
            for (dev in bonded) {
                disconnectHidQuietly(dev)
                if (invokeRemoveBond(dev)) removed++
            }
            connectedAddresses.clear()
            hidConnectedAddress = null
            reconnectAttempts = 0
            prefs.edit().remove(KEY_REMOTE_MAC).remove(KEY_REMOTE_NAME).apply()
            val msg = if (removed > 0) {
                "Память пультов очищена ($removed). Нажмите «Домой» + «−» для привязки."
            } else {
                "Список сопряжённых пультов пуст. Можно подключать пульт."
            }
            onDone(removed, msg)
        }
    }

    private fun startAutoScanAndPairInternal(
        durationMs: Long,
        forceScan: Boolean,
        seq: Int,
        onStatus: (state: String, detail: String) -> Unit,
        enableAttempts: Int
    ) {
        handler.post {
            if (seq != scanOpSeq.get()) return@post
            try {
                val adapter = getAdapter()
                if (adapter == null) {
                    onStatus("error", "Bluetooth-адаптер недоступен")
                    return@post
                }
                if (!adapter.isEnabled) {
                    if (enableAttempts >= 10) {
                        onStatus("error", "Bluetooth не включился. Проверьте настройки и повторите поиск.")
                        return@post
                    }
                    if (enableAttempts == 0 && adapter.state != BluetoothAdapter.STATE_TURNING_ON && !adapter.enable()) {
                        onStatus("error", "Не удалось включить Bluetooth для поиска пульта.")
                        return@post
                    }
                    onStatus("searching", "Включаю Bluetooth для поиска пульта...")
                    handler.postDelayed({
                        startAutoScanAndPairInternal(durationMs, forceScan, seq, onStatus, enableAttempts + 1)
                    }, 1200L)
                    return@post
                }

                ensureHidProxy(adapter)
                handler.removeCallbacks(reconnectTask)

                val existing = findTargetDevice()
                if (!forceScan && existing != null && existing.bondState == BluetoothDevice.BOND_BONDED && isDeviceConnectedNow(adapter, existing)) {
                    val nm = safeDeviceName(existing).ifBlank { "Yandex Remote" }
                    persistRemoteIdentity(existing.address, nm)
                    onStatus("connected", "Пульт $nm подключён!")
                    return@post
                }

                // Abort any stale connecting state on HID Host before starting LE scan so it doesn't
                // jam the shared BT controller during pairing discovery.
                for (dev in collectBondedYandexRemotes(adapter)) {
                    val state = try { hidProxy?.getConnectionState(dev) ?: -1 } catch (_: Throwable) { -1 }
                    if (state == BluetoothProfile.STATE_CONNECTING) {
                        disconnectHidQuietly(dev)
                    }
                }
                connectSeq.incrementAndGet()
                hidConnecting.set(false)

                val permWarning = checkScanPrerequisites()
                if (permWarning != null) {
                    Log.w(TAG, "BLE prerequisite check: $permWarning")
                }

                startLeScanInternal(adapter, durationMs, onStatus, ScanSettings.SCAN_MODE_LOW_LATENCY, cancelIdleConnect = true)
            } catch (t: Throwable) {
                Log.w(TAG, "startAutoScanAndPair error", t)
                onStatus("error", "Ошибка поиска BLE-пульта: ${extractErrorMessage(t)}")
            }
        }
    }

    private fun checkScanPrerequisites(): String? {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return "Не выдано разрешение ACCESS_FINE_LOCATION/COARSE_LOCATION"
        }
        val locMode = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0)
        } catch (_: Throwable) { 3 }
        if (locMode == Settings.Secure.LOCATION_MODE_OFF) {
            return "Системная геолокация выключена (location_mode=0)"
        }
        return null
    }

    private fun startLeScanInternal(
        adapter: BluetoothAdapter,
        durationMs: Long,
        onStatus: (state: String, detail: String) -> Unit,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
        cancelIdleConnect: Boolean = true
    ) {
        stopScan()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("error", "BLE-сканер не готов")
            return
        }

        scanning.set(true)
        activeScanStatusCb = onStatus
        onStatus("searching", "Ищу пульт. Удерживайте «Домой» и «−» до мигания индикатора.")

        val handledResult = AtomicBoolean(false)
        fun handleCandidate(result: ScanResult?) {
            val dev = result?.device ?: return
            val recordName = result.scanRecord?.deviceName
            val devName = safeDeviceName(dev)
            val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
            val hasHidUuid = result.scanRecord?.serviceUuids?.any { it.uuid == UUID_HID_SERVICE } == true
            val hasYandexMfg = result.scanRecord?.manufacturerSpecificData?.get(YANDEX_MFG_ID) != null

            if (!RemoteIdentity.isBleScanMatch(devName, recordName, dev.address, savedMac, hasHidUuid, hasYandexMfg)) {
                return
            }
            if (!handledResult.compareAndSet(false, true)) return
            if (!scanning.compareAndSet(true, false)) return

            stopScan()
            val label = (recordName?.takeIf { it.isNotBlank() } ?: devName).ifBlank { "Yandex Remote" }
            Log.i(TAG, "Discovered BLE remote: $label [${dev.address}] bondState=${dev.bondState}")
            onStatus("found", "Найден пульт $label (${dev.address}), сопрягаю...")
            bondAndConnect(dev, label, forceFreshRebond = true, onStatus = onStatus)
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                handleCandidate(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleCandidate(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (scanning.compareAndSet(true, false)) {
                    stopScan()
                    val reason = when (errorCode) {
                        SCAN_FAILED_ALREADY_STARTED -> "сканер уже запущен"
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "сбой регистрации GATT (нужен рестарт BT)"
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> "режим BLE не поддерживается"
                        else -> "код $errorCode"
                    }
                    onStatus("error", "Ошибка BLE-сканера ($reason)")
                    if (reconnectActive) scheduleReconnect(25_000L)
                }
            }
        }
        activeScanCallback = cb

        val settingsBuilder = ScanSettings.Builder().setScanMode(scanMode)
        try {
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        } catch (_: Throwable) {}

        // Non-empty ScanFilter list is REQUIRED on Android 9 TVs where LocationManager.isLocationEnabled()
        // returns false (no GPS provider), otherwise GattService immediately suspends unfiltered scans.
        val filters = listOf(ScanFilter.Builder().build())

        try {
            scanner.startScan(filters, settingsBuilder.build(), cb)
        } catch (t: Throwable) {
            scanning.set(false)
            activeScanCallback = null
            activeScanStatusCb = null
            val prereq = checkScanPrerequisites()
            val hint = prereq ?: extractErrorMessage(t)
            onStatus("error", "Нет доступа к BLE-поиску: $hint")
            return
        }

        // While scanning, also detect if an already-bonded remote woke up and connected via HOGP.
        if (cancelIdleConnect) {
            val poll = object : Runnable {
                override fun run() {
                    if (!scanning.get()) return
                    val current = try { findTargetDevice() } catch (_: Throwable) { null }
                    if (current != null && isDeviceConnectedNow(adapter, current)) {
                        val cbRef = activeScanStatusCb
                        stopScan()
                        val nm = safeDeviceName(current).ifBlank { "Yandex Remote" }
                        cbRef?.invoke("connected", "Пульт $nm подключён!")
                        return
                    }
                    handler.postDelayed(this, 1200L)
                }
            }
            scanConnectPollRunnable = poll
            handler.postDelayed(poll, 1200L)
        }

        val timeout = Runnable {
            if (scanning.compareAndSet(true, false)) {
                stopScan()
                onStatus("not_found", "Пульт не найден. Удерживайте «Домой» и «−» 5 сек до мигания и повторите.")
                if (reconnectActive) scheduleReconnect(15_000L)
            }
        }
        scanTimeoutRunnable = timeout
        handler.postDelayed(timeout, durationMs.coerceIn(5000L, 60000L))
    }

    private fun invokeRemoveBond(device: BluetoothDevice): Boolean {
        return try {
            val m = device.javaClass.getMethod("removeBond")
            (m.invoke(device) as? Boolean) ?: true
        } catch (t: Throwable) {
            Log.w(TAG, "removeBond failed for ${device.address}: ${extractErrorMessage(t)}")
            false
        }
    }

    private fun invokeCreateBondLe(device: BluetoothDevice): Boolean {
        try {
            val m = device.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
            val res = m.invoke(device, TRANSPORT_LE)
            if (res is Boolean) return res
        } catch (t: Throwable) {
            Log.d(TAG, "createBond(TRANSPORT_LE) fallback for ${device.address}: ${extractErrorMessage(t)}")
        }
        return try {
            device.createBond()
        } catch (t: Throwable) {
            Log.w(TAG, "createBond() failed: ${extractErrorMessage(t)}")
            false
        }
    }

    private fun disconnectHidQuietly(device: BluetoothDevice) {
        val proxy = hidProxy ?: return
        try {
            val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            m.invoke(proxy, device)
        } catch (_: Throwable) {}
    }

    private fun bondAndConnect(
        device: BluetoothDevice,
        label: String,
        forceFreshRebond: Boolean,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        val adapter = getAdapter()
        if (adapter == null) {
            onStatus("error", "Bluetooth-адаптер недоступен")
            return
        }

        // If the remote was reset (Домой + −), it advertises again in pairing mode with cleared LTK keys.
        // Keeping Android's stale BOND_BONDED entry makes HID fail forever; unbond cleanly first.
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            if (!forceFreshRebond) {
                connectHid(adapter, device, onStatus)
                return
            }
            Log.i(TAG, "Clearing stale bond for re-pairing remote ${device.address}")
            onStatus("pairing", "Обновляю ключи сопряжения с $label...")
            disconnectHidQuietly(device)
            unregisterBondReceiver()
            val removed = invokeRemoveBond(device)
            if (removed && device.bondState != BluetoothDevice.BOND_NONE) {
                val triggered = AtomicBoolean(false)
                val waitBr = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (dev.address != device.address) return
                        val st = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        if (st == BluetoothDevice.BOND_NONE && triggered.compareAndSet(false, true)) {
                            unregisterBondReceiver()
                            handler.postDelayed({ startFreshBond(adapter, device, label, onStatus) }, 300L)
                        }
                    }
                }
                bondReceiver = waitBr
                val waitTimeout = Runnable {
                    if (triggered.compareAndSet(false, true) && bondReceiver === waitBr) {
                        unregisterBondReceiver()
                        startFreshBond(adapter, device, label, onStatus)
                    }
                }
                bondTimeoutRunnable = waitTimeout
                try {
                    context.registerReceiver(waitBr, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                    handler.postDelayed(waitTimeout, 3000L)
                    return
                } catch (_: Throwable) {
                    unregisterBondReceiver()
                }
            }
            handler.postDelayed({ startFreshBond(adapter, device, label, onStatus) }, 300L)
            return
        }

        startFreshBond(adapter, device, label, onStatus)
    }

    private fun startFreshBond(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        label: String,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        unregisterBondReceiver()
        val done = AtomicBoolean(false)
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (dev.address != device.address) return
                        // Best-effort auto-confirm if consent dialog would block headless TV pairing
                        try {
                            dev.setPairingConfirmation(true)
                        } catch (_: SecurityException) {} catch (_: Throwable) {}
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (dev.address != device.address) return
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        when (state) {
                            BluetoothDevice.BOND_BONDED -> {
                                if (!done.compareAndSet(false, true)) return
                                unregisterBondReceiver()
                                persistRemoteIdentity(dev.address, label)
                                onStatus("pairing", "Пульт сопряжён, подключаю кнопки...")
                                connectHid(adapter, dev, onStatus)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                if (!done.compareAndSet(false, true)) return
                                unregisterBondReceiver()
                                onStatus("error", "Не удалось завершить сопряжение с $label. Удерживайте «Домой» и «−» снова.")
                            }
                        }
                    }
                }
            }
        }
        bondReceiver = br
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            }
            context.registerReceiver(br, filter)
            val timeout = Runnable {
                if (done.compareAndSet(false, true) && bondReceiver === br) {
                    unregisterBondReceiver()
                    onStatus("error", "Пульт не завершил сопряжение. Удерживайте «Домой» и «−» 5 сек и повторите.")
                }
            }
            bondTimeoutRunnable = timeout
            handler.postDelayed(timeout, 30_000L)

            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                if (done.compareAndSet(false, true)) {
                    unregisterBondReceiver()
                    persistRemoteIdentity(device.address, label)
                    onStatus("pairing", "Пульт сопряжён, подключаю кнопки...")
                    connectHid(adapter, device, onStatus)
                }
                return
            }

            onStatus("pairing", "Сопрягаю пульт $label...")
            if (device.bondState != BluetoothDevice.BOND_BONDING && !invokeCreateBondLe(device)) {
                if (done.compareAndSet(false, true)) {
                    unregisterBondReceiver()
                    onStatus("error", "Не удалось начать сопряжение с $label")
                }
            }
        } catch (t: Throwable) {
            if (done.compareAndSet(false, true)) {
                unregisterBondReceiver()
                onStatus("error", "Ошибка createBond: ${extractErrorMessage(t)}")
            }
        }
    }

    // Android 9 exposes HID host as profile 4; its connect method is @hide.
    // Right after bonding, HidHostService often enters STATE_CONNECTING on its own,
    // so calling connect() must tolerate both STATE_CONNECTING and asynchronous transitions.
    private fun connectHid(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        onStatus: (String, String) -> Unit
    ) {
        val seq = connectSeq.incrementAndGet()
        hidConnecting.set(true)
        val finished = AtomicBoolean(false)
        var ownedProxy: BluetoothProfile? = null
        var timeoutRunnable: Runnable? = null

        fun finish(state: String, detail: String) {
            if (!finished.compareAndSet(false, true)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            if (seq == connectSeq.get()) {
                hidConnecting.set(false)
            }
            if (state == "connected") {
                connectedAddresses.add(device.address)
                hidConnectedAddress = device.address
                persistRemoteIdentity(device.address, safeDeviceName(device))
            } else {
                connectedAddresses.remove(device.address)
                if (hidConnectedAddress == device.address) hidConnectedAddress = null
            }
            ownedProxy?.let { p ->
                if (p !== hidProxy) {
                    try { adapter.closeProfileProxy(PROFILE_HID_HOST, p) } catch (_: Throwable) {}
                }
            }
            onStatus(state, detail)
        }

        val timeout = Runnable {
            finish("error", "Пульт сопряжён, но кнопки ещё не ответили. Нажмите ОК на пульте и проверьте связь.")
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, 20_000L)

        fun runWithProxy(proxy: BluetoothProfile, tempProxy: Boolean) {
            if (finished.get() || seq != connectSeq.get()) {
                if (tempProxy) try { adapter.closeProfileProxy(PROFILE_HID_HOST, proxy) } catch (_: Throwable) {}
                finish("error", "Подключение прервано новым поиском.")
                return
            }
            if (tempProxy) ownedProxy = proxy
            try {
                try {
                    val setPriority = proxy.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    setPriority.invoke(proxy, device, PRIORITY_AUTO_CONNECT)
                } catch (_: Throwable) {}

                val curState = try { proxy.getConnectionState(device) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
                if (curState == BluetoothProfile.STATE_CONNECTED || isDeviceConnectedNow(adapter, device)) {
                    finish("connected", "Пульт ${safeDeviceName(device).ifBlank { "Yandex" }} подключён!")
                    return
                }

                // Only call connect() when completely disconnected; if Android already started
                // connecting after BOND_BONDED (STATE_CONNECTING), go straight to polling.
                if (curState == BluetoothProfile.STATE_DISCONNECTED) {
                    val connect = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    val invoked = connect.invoke(proxy, device)
                    if (invoked != true) {
                        val afterState = try { proxy.getConnectionState(device) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
                        if (afterState == BluetoothProfile.STATE_DISCONNECTED) {
                            finish("error", "Android отклонил подключение HID. Нажмите кнопку на пульте или переподключите его.")
                            return
                        }
                    }
                }

                val poll = object : Runnable {
                    override fun run() {
                        if (finished.get() || seq != connectSeq.get()) return
                        try {
                            val st = proxy.getConnectionState(device)
                            if (st == BluetoothProfile.STATE_CONNECTED || isDeviceConnectedNow(adapter, device)) {
                                finish("connected", "Пульт ${safeDeviceName(device).ifBlank { "Yandex" }} подключён!")
                            } else {
                                handler.postDelayed(this, 500L)
                            }
                        } catch (t: Throwable) {
                            finish("error", "Ошибка проверки HID: ${extractErrorMessage(t)}")
                        }
                    }
                }
                handler.postDelayed(poll, 350L)
            } catch (t: Throwable) {
                finish("error", "Не удалось подключить профиль пульта: ${extractErrorMessage(t)}")
            }
        }

        val existingProxy = hidProxy
        if (existingProxy != null) {
            runWithProxy(existingProxy, tempProxy = false)
            return
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                hidProxy = proxy
                hidProxyRequested = false
                runWithProxy(proxy, tempProxy = false)
            }

            override fun onServiceDisconnected(p: Int) {
                hidProxy = null
                hidProxyRequested = false
                finish("error", "Служба Bluetooth HID отключилась.")
            }
        }
        try {
            if (!adapter.getProfileProxy(context, listener, PROFILE_HID_HOST)) {
                finish("error", "Служба подключения HID-пульта недоступна.")
            }
        } catch (t: Throwable) {
            finish("error", "Не удалось открыть службу HID: ${extractErrorMessage(t)}")
        }
    }

    private fun extractErrorMessage(t: Throwable): String {
        val cause = (t as? InvocationTargetException)?.targetException ?: t.cause ?: t
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    fun triggerBuzzer(onResult: (ok: Boolean, message: String) -> Unit) {
        thread(name = "BleRemoteBuzzer", isDaemon = true) {
            val dev = findTargetDevice()
            if (dev == null) {
                onResult(false, "Пульт YNDX ещё не привязан. Сначала выполните поиск пульта.")
                return@thread
            }
            connectAndProbeGatt(dev, buzz = true, onDone = onResult)
        }
    }

    private fun connectAndProbeGatt(
        device: BluetoothDevice,
        buzz: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        handler.post {
            var gattRef: BluetoothGatt? = null
            val guard = Runnable {
                if (finished.compareAndSet(false, true)) {
                    try { gattRef?.disconnect(); gattRef?.close() } catch (_: Throwable) {}
                    onDone(false, if (buzz) "Пульт не вышел на связь по BLE (нажмите любую кнопку на пульте или проверьте батарейки)" else "Таймаут подключения GATT")
                }
            }
            handler.postDelayed(guard, 6500L)

            val gattCb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT connected to ${device.address}, discovering services...")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !finished.get()) {
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            try { gatt.close() } catch (_: Throwable) {}
                            onDone(false, "Соединение с пультом прервалось (status=$status)")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            try { gatt.close() } catch (_: Throwable) {}
                            onDone(false, "Ошибка GATT discoverServices ($status)")
                        }
                        return
                    }

                    val batChar = gatt.getService(UUID_BATTERY_SERVICE)?.getCharacteristic(UUID_BATTERY_LEVEL)
                    if (!buzz) {
                        if (batChar != null) gatt.readCharacteristic(batChar)
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 600L)
                            onDone(true, "Пульт ${safeDeviceName(device).ifBlank { "Yandex Remote" }} подключён!")
                        }
                        return
                    }

                    val alertSvc = gatt.getService(UUID_IMMEDIATE_ALERT) ?: gatt.getService(UUID_LINK_LOSS)
                    var alertChar: BluetoothGattCharacteristic? = alertSvc?.getCharacteristic(UUID_ALERT_LEVEL)
                    if (alertChar == null) {
                        for (svc in gatt.services) {
                            for (ch in svc.characteristics) {
                                val props = ch.properties
                                val isWrite = (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                                if (isWrite && ch.uuid == UUID_ALERT_LEVEL) {
                                    alertChar = ch
                                    break
                                }
                            }
                            if (alertChar != null) break
                        }
                    }

                    if (alertChar != null) {
                        alertChar.value = byteArrayOf(0x02)
                        alertChar.writeType = if ((alertChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        } else {
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }
                        val ok = gatt.writeCharacteristic(alertChar)
                        if (alertChar.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                            if (finished.compareAndSet(false, true)) {
                                handler.removeCallbacks(guard)
                                handler.postDelayed({
                                    try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {}
                                }, 1800L)
                                onDone(ok, if (ok) "Сигнал зуммера отправлен на пульт!" else "Не удалось записать команду зуммера")
                            }
                        }
                    } else {
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 900L)
                            onDone(true, "Связь с пультом активна (без аппаратного зуммера 0x1802 на этой ревизии пульта)")
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == UUID_BATTERY_LEVEL) {
                        val b = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                        if (b in 0..100) lastBatteryPct = b
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (buzz && finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(guard)
                        val ok = (status == BluetoothGatt.GATT_SUCCESS)
                        handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 1500L)
                        onDone(ok, if (ok) "Пульт пищит! (Сигнал отправлен)" else "Ошибка команды зуммера ($status)")
                    }
                }
            }

            gattRef = try {
                device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            } catch (t: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacks(guard)
                    onDone(false, "connectGatt ошибка: ${extractErrorMessage(t)}")
                }
                null
            }
        }
    }

    fun stopScan() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        scanConnectPollRunnable?.let { handler.removeCallbacks(it) }
        scanConnectPollRunnable = null
        activeScanStatusCb = null
        val cb = activeScanCallback ?: run {
            scanning.set(false)
            return
        }
        activeScanCallback = null
        scanning.set(false)
        try {
            getAdapter()?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {}
    }

    private fun unregisterBondReceiver() {
        bondTimeoutRunnable?.let { handler.removeCallbacks(it) }
        bondTimeoutRunnable = null
        bondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        bondReceiver = null
    }

    fun release() {
        handler.post {
            scanOpSeq.incrementAndGet()
            connectSeq.incrementAndGet()
            hidConnecting.set(false)
            stopScan()
            unregisterBondReceiver()
        }
    }
}
