package com.openstation.launcher

import java.util.Locale

internal object RemoteIdentity {
    private const val YANDEX_VENDOR_ID = 0x057A // 1402

    data class BondedRemoteCandidate(
        val address: String,
        val name: String,
        val connected: Boolean
    )

    fun isYandexRemoteName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.uppercase(Locale.ROOT)
        val vendor = normalized.contains("YNDX") || normalized.contains("YDX") || normalized.contains("YANDEX")
        return vendor && normalized.contains("REMOTE")
    }

    fun isBleScanMatch(
        deviceName: String?,
        recordName: String?,
        address: String,
        savedAddress: String,
        hasHidUuid: Boolean,
        hasYandexMfgId: Boolean
    ): Boolean {
        if (isYandexRemoteName(recordName) || isYandexRemoteName(deviceName)) {
            return true
        }
        if (savedAddress.isNotBlank() && address.equals(savedAddress, ignoreCase = true) && hasHidUuid) {
            return true
        }
        return hasHidUuid && hasYandexMfgId
    }

    fun isYandexVendorId(vendorId: Int): Boolean = vendorId == YANDEX_VENDOR_ID

    fun pickPreferredRemote(
        candidates: List<BondedRemoteCandidate>,
        savedAddress: String
    ): BondedRemoteCandidate? {
        if (candidates.isEmpty()) return null
        // Actually connected device always wins so switching remotes heals stale saved MACs.
        candidates.firstOrNull { it.connected }?.let { return it }
        if (savedAddress.isNotBlank()) {
            candidates.firstOrNull { it.address.equals(savedAddress, ignoreCase = true) }?.let { return it }
        }
        return candidates.firstOrNull()
    }

    fun nextReconnectDelayMs(attempt: Int, connected: Boolean): Long {
        if (connected) return 60_000L
        return when {
            attempt <= 0 -> 8_000L
            attempt == 1 -> 20_000L
            attempt == 2 -> 45_000L
            attempt == 3 -> 90_000L
            else -> 300_000L
        }
    }
}
