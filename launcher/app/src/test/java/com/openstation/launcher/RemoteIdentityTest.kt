package com.openstation.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdentityTest {
    @Test fun recognizesKnownNamesWithoutAcceptingGenericHidDevices() {
        assertTrue(RemoteIdentity.isYandexRemoteName("YNDX-0006"))
        assertTrue(RemoteIdentity.isYandexRemoteName("Yandex Remote"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Wireless Remote"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Yandex Station Max"))
        assertFalse(RemoteIdentity.isYandexRemoteName("Keyboard"))
        assertFalse(RemoteIdentity.isYandexRemoteName(null))
    }
}
