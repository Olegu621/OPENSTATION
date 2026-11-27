package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdentityTest {
    @Test fun recognizesKnownNamesWithoutAcceptingGenericHidDevices() {
        assertFalse(RemoteIdentity.isYandexRemoteName("YNDX-0006"))
        assertTrue(RemoteIdentity.isYandexRemoteName("Yandex Remote 2"))
        assertTrue(RemoteIdentity.isYandexRemoteName("Yandex Remote"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Wireless Remote"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Yandex Station Max"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Keyboard"))
        assertFalse(RemoteIdentity.isYandexRemoteName(null))
    }

    @Test fun preferredRemoteSwitchesToActuallyConnectedRemoteOverStaleSavedMac() {
        val candidates = listOf(
            RemoteIdentity.BondedRemoteCandidate("98:06:3A:81:A8:B0", "Yandex Remote", connected = false),
            RemoteIdentity.BondedRemoteCandidate("54:A9:C8:9A:A7:4F", "Yandex Remote 2", connected = true)
        )
        val picked = RemoteIdentity.pickPreferredRemote(candidates, "98:06:3A:81:A8:B0")
        assertEquals("54:A9:C8:9A:A7:4F", picked?.address)
    }

    @Test fun preferredRemoteFallsBackToSavedMacWhenNothingConnected() {
        val candidates = listOf(
            RemoteIdentity.BondedRemoteCandidate("54:A9:C8:9A:A7:4F", "Yandex Remote 2", connected = false),
            RemoteIdentity.BondedRemoteCandidate("98:06:3A:81:A8:B0", "Yandex Remote", connected = false)
        )
        val picked = RemoteIdentity.pickPreferredRemote(candidates, "98:06:3a:81:a8:b0")
        assertEquals("98:06:3A:81:A8:B0", picked?.address)
        assertNull(RemoteIdentity.pickPreferredRemote(emptyList(), "98:06:3A:81:A8:B0"))
    }

    @Test fun bleScanMatchesNameOrYandexHidVendorWithoutFalsePositives() {
        assertTrue(
            RemoteIdentity.isBleScanMatch(
                deviceName = null,
                recordName = "Yandex Remote 2",
                address = "54:A9:C8:9A:A7:4F",
                savedAddress = "",
                hasHidUuid = false,
                hasYandexMfgId = false
            )
        )
        assertTrue(
            RemoteIdentity.isBleScanMatch(
                deviceName = null,
                recordName = null,
                address = "54:A9:C8:9A:A7:4F",
                savedAddress = "",
                hasHidUuid = true,
                hasYandexMfgId = true
            )
        )
        assertFalse(
            RemoteIdentity.isBleScanMatch(
                deviceName = "YNDX-0006",
                recordName = "YNDX-0006",
                address = "11:22:33:44:55:66",
                savedAddress = "",
                hasHidUuid = false,
                hasYandexMfgId = true
            )
        )
        assertFalse(
            RemoteIdentity.isBleScanMatch(
                deviceName = "Wireless Remote",
                recordName = "Wireless Remote",
                address = "AA:BB:CC:DD:EE:FF",
                savedAddress = "",
                hasHidUuid = true,
                hasYandexMfgId = false
            )
        )
    }

    @Test fun reconnectBackoffExpandsAndCapsCleanly() {
        assertEquals(60_000L, RemoteIdentity.nextReconnectDelayMs(0, connected = true))
        assertEquals(8_000L, RemoteIdentity.nextReconnectDelayMs(0, connected = false))
        assertEquals(20_000L, RemoteIdentity.nextReconnectDelayMs(1, connected = false))
        assertEquals(45_000L, RemoteIdentity.nextReconnectDelayMs(2, connected = false))
        assertEquals(90_000L, RemoteIdentity.nextReconnectDelayMs(3, connected = false))
        assertEquals(300_000L, RemoteIdentity.nextReconnectDelayMs(4, connected = false))
        assertEquals(300_000L, RemoteIdentity.nextReconnectDelayMs(10, connected = false))
    }
}
