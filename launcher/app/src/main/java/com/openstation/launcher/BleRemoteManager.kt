package com.openstation.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Manages native Yandex Station BLE Remote (HID 0x1812) discovery,
 * automatic bonding, battery query, and physical buzzer ("Find Remote") alerts.
 */
class BleRemoteManager(private val context: Context) {

    companion object {
        private const val TAG = "BleRemoteMgr"
        private const val PREF_NAME = "openstation_remote_prefs"
        private const val KEY_REMOTE_MAC = "yndx_remote_mac"
        private const val KEY_REMOTE_NAME = "yndx_remote_name"

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
    private var scanTimeoutRunnable: Runnable? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var reconnectActive = false
    private var reconnectDelay = 5000L
    private val hidConnecting = AtomicBoolean(false)
    private val reconnectTask = object : Runnable {
        override fun run() {
            if (!reconnectActive) return
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect(30000L)
                return
            }
            val device = try { findTargetDevice() } catch (_: Throwable) { null }
            if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
                scheduleReconnect(30000L)
                return
            }
            if (scanning.get()) {
                scheduleReconnect(30000L)
                return
            }
            connectHid(adapter, device) { state, _ ->
                if (state == "connected") reconnectDelay = 5000L
                else reconnectDelay = (reconnectDelay * 2).coerceAtMost(300000L)
                scheduleReconnect(if (state == "connected") 30000L else reconnectDelay)
            }
        }
    }
    @Volatile
    private var lastBatteryPct: Int = -1

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isYandexRemoteName(name: String?): Boolean {
        return RemoteIdentity.isYandexRemoteName(name)
    }

    fun startReconnect() {
        handler.post {
            reconnectActive = true
            reconnectDelay = 5000L
            scheduleReconnect(5000L)
        }
    }

    fun stopReconnect() {
        reconnectActive = false
        handler.removeCallbacks(reconnectTask)
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (!reconnectActive) return
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, delayMs)
    }

    fun getRemoteStatus(): RemoteStatus {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                return RemoteStatus(false, false, "", "", lastBatteryPct)
            }
            val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""

            val gattConnected = try {
                bm?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
            val matchedConn = gattConnected.firstOrNull {
                isYandexRemoteName(it.name) || (savedMac.isNotEmpty() && it.address == savedMac)
            }
            if (matchedConn != null) {
                return RemoteStatus(
                    bonded = matchedConn.bondState == BluetoothDevice.BOND_BONDED,
                    connected = true,
                    name = matchedConn.name ?: prefs.getString(KEY_REMOTE_NAME, "Yandex Remote") ?: "Yandex Remote",
                    address = matchedConn.address,
                    batteryPct = lastBatteryPct
                )
            }

            val bonded = try {
                adapter.bondedDevices?.firstOrNull {
                    isYandexRemoteName(it.name) || (savedMac.isNotEmpty() && it.address == savedMac)
                }
            } catch (_: Throwable) {
                null
            }
            if (bonded != null) {
                val isConn = try {
                    val m = BluetoothDevice::class.java.getMethod("isConnected")
                    (m.invoke(bonded) as? Boolean) ?: false
                } catch (_: Throwable) {
                    false
                }
                RemoteStatus(
                    bonded = true,
                    connected = isConn,
                    name = bonded.name ?: "Yandex Remote",
                    address = bonded.address,
                    batteryPct = lastBatteryPct
                )
            } else {
                RemoteStatus(false, false, "", "", -1)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getRemoteStatus failed: ${t.message}")
            RemoteStatus(false, false, "", "", -1)
        }
    }

    private fun findTargetDevice(): BluetoothDevice? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
        val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""

        try {
            val connected = bm?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
            connected.firstOrNull {
                isYandexRemoteName(it.name) || (savedMac.isNotEmpty() && it.address == savedMac)
            }?.let { return it }
        } catch (_: Throwable) {}

        try {
            adapter.bondedDevices?.firstOrNull {
                isYandexRemoteName(it.name) || (savedMac.isNotEmpty() && it.address == savedMac)
            }?.let { return it }
        } catch (_: Throwable) {}

        if (savedMac.isNotEmpty()) {
            try {
                return adapter.getRemoteDevice(savedMac)
            } catch (_: Throwable) {}
        }
        return null
    }

    fun startAutoScanAndPair(
        durationMs: Long = 60000L,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        startAutoScanAndPairInternal(durationMs, onStatus, 0)
    }

    private fun startAutoScanAndPairInternal(
        durationMs: Long,
        onStatus: (state: String, detail: String) -> Unit,
        enableAttempts: Int
    ) {
        handler.post {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
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
                        startAutoScanAndPairInternal(durationMs, onStatus, enableAttempts + 1)
                    }, 1200L)
                    return@post
                }

                // If already bonded, trigger immediate GATT connect wakeup
                findTargetDevice()?.let { dev ->
                    if (dev.bondState == BluetoothDevice.BOND_BONDED) {
                        onStatus("pairing", "Подключаю сопряжённый пульт ${dev.name ?: "Yandex"}...")
                        connectHid(adapter, dev, onStatus)
                        return@post
                    }
                }

                startLeScanInternal(adapter, durationMs, onStatus)
            } catch (t: Throwable) {
                Log.w(TAG, "startAutoScanAndPair error", t)
                onStatus("error", "Ошибка поиска BLE-пульта: ${t.message}")
            }
        }
    }

    private fun startLeScanInternal(
        adapter: BluetoothAdapter,
        durationMs: Long,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        stopScan()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("error", "BLE-сканер не готов")
            return
        }

        scanning.set(true)
        onStatus("searching", "Ищу пульт. Удерживайте «Домой» и «−» до включения подсветки.")

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val name = dev.name ?: result.scanRecord?.deviceName ?: ""
                val savedMac = prefs.getString(KEY_REMOTE_MAC, "") ?: ""
                if (!isYandexRemoteName(name) && (savedMac.isEmpty() || dev.address != savedMac)) return

                if (!scanning.compareAndSet(true, false)) return
                stopScan()
                val label = name.ifBlank { "Yandex Remote" }
                Log.i(TAG, "Discovered BLE remote: $label [${dev.address}]")
                onStatus("found", "Найден пульт $label (${dev.address}), подключаю...")
                bondAndConnect(dev, label, onStatus)
            }

            override fun onScanFailed(errorCode: Int) {
                if (scanning.compareAndSet(true, false)) {
                    stopScan()
                    onStatus("error", "Ошибка BLE-сканера (код $errorCode)")
                }
            }
        }
        activeScanCallback = cb

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (t: Throwable) {
            scanning.set(false)
            activeScanCallback = null
            onStatus("error", "Нет разрешения на BLE-поиск (${t.message})")
            return
        }

        val timeout = Runnable {
            if (scanning.compareAndSet(true, false)) {
                stopScan()
                onStatus("not_found", "Пульт не найден. Удерживайте «Домой» и «−» до включения подсветки и повторите поиск.")
            }
        }
        scanTimeoutRunnable = timeout
        handler.postDelayed(timeout, durationMs.coerceIn(5000L, 60000L))
    }

    private fun bondAndConnect(
        device: BluetoothDevice,
        label: String,
        onStatus: (state: String, detail: String) -> Unit
    ) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) onStatus("error", "Bluetooth-адаптер недоступен")
            else connectHid(adapter, device, onStatus)
            return
        }

        unregisterBondReceiver()
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (dev.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        unregisterBondReceiver()
                        prefs.edit().putString(KEY_REMOTE_MAC, dev.address).putString(KEY_REMOTE_NAME, label).apply()
                        onStatus("pairing", "Пульт сопряжён, подключаю кнопки...")
                        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                            ?: BluetoothAdapter.getDefaultAdapter()
                        if (adapter == null) onStatus("error", "Bluetooth-адаптер недоступен")
                        else connectHid(adapter, dev, onStatus)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        unregisterBondReceiver()
                        onStatus("error", "Не удалось завершить сопряжение с $label")
                    }
                }
            }
        }
        bondReceiver = br
        context.registerReceiver(br, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        try {
            onStatus("pairing", "Сопрягаю пульт $label...")
            if (!device.createBond()) {
                unregisterBondReceiver()
                onStatus("error", "Не удалось начать сопряжение с $label")
            }
        } catch (t: Throwable) {
            unregisterBondReceiver()
            onStatus("error", "Ошибка createBond: ${t.message}")
        }
    }

    // Android 9 exposes HID host as profile 4, but its connect method is
    // hidden from the public SDK. A bonded GATT connection alone is not HID.
    private fun connectHid(adapter: BluetoothAdapter, device: BluetoothDevice,
                           onStatus: (String, String) -> Unit) {
        if (!hidConnecting.compareAndSet(false, true)) {
            onStatus("pairing", "Подключение пульта уже выполняется.")
            return
        }
        val profileId = 4
        val finished = AtomicBoolean(false)
        var profile: BluetoothProfile? = null
        fun finish(state: String, detail: String) {
            if (!finished.compareAndSet(false, true)) return
            hidConnecting.set(false)
            profile?.let { try { adapter.closeProfileProxy(profileId, it) } catch (_: Throwable) {} }
            onStatus(state, detail)
        }
        val timeout = Runnable { finish("error", "Пульт сопряжён, но подключение кнопок не подтвердилось. Нажмите кнопку пульта и повторите.") }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                if (finished.get()) {
                    try { adapter.closeProfileProxy(profileId, proxy) } catch (_: Throwable) {}
                    return
                }
                profile = proxy
                try {
                    if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                        handler.removeCallbacks(timeout)
                        finish("connected", "Пульт подключён. Проверьте кнопки.")
                        return
                    }
                    val connect = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    if (connect.invoke(proxy, device) != true) {
                        handler.removeCallbacks(timeout)
                        finish("error", "Android не смог начать подключение пульта.")
                        return
                    }
                    val poll = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            try {
                                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                                    handler.removeCallbacks(timeout)
                                    finish("connected", "Пульт подключён. Проверьте кнопки.")
                                } else handler.postDelayed(this, 500L)
                            } catch (t: Throwable) {
                                handler.removeCallbacks(timeout)
                                finish("error", "Не удалось проверить состояние пульта: ${t.message}")
                            }
                        }
                    }
                    handler.post(poll)
                } catch (t: Throwable) {
                    handler.removeCallbacks(timeout)
                    finish("error", "Не удалось подключить профиль пульта: ${t.message}")
                }
            }
            override fun onServiceDisconnected(p: Int) {
                handler.removeCallbacks(timeout)
                finish("error", "Служба пульта отключилась.")
            }
        }
        handler.postDelayed(timeout, 15000L)
        try {
            if (!adapter.getProfileProxy(context, listener, profileId)) {
                handler.removeCallbacks(timeout)
                finish("error", "Служба подключения пульта недоступна.")
            }
        } catch (t: Throwable) {
            handler.removeCallbacks(timeout)
            finish("error", "Не удалось открыть службу пульта: ${t.message}")
        }
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

                    // Try reading battery characteristic if present
                    val batChar = gatt.getService(UUID_BATTERY_SERVICE)?.getCharacteristic(UUID_BATTERY_LEVEL)
                    if (!buzz) {
                        if (batChar != null) gatt.readCharacteristic(batChar)
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(guard)
                            handler.postDelayed({ try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {} }, 600L)
                            onDone(true, "Пульт ${device.name ?: "Yandex Remote"} подключён!")
                        }
                        return
                    }

                    // Find Immediate Alert characteristic (0x1802 / 0x2A06) or fallback writable buzzer characteristic
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
                        alertChar.value = byteArrayOf(0x02) // High Alert buzzer
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
                        // Even without standard 0x1802, holding active GATT wake confirms link
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
                    onDone(false, "connectGatt ошибка: ${t.message}")
                }
                null
            }
        }
    }

    fun stopScan() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        scanning.set(false)
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {}
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        bondReceiver = null
    }

    fun release() {
        handler.post {
            stopScan()
            unregisterBondReceiver()
        }
    }
}
