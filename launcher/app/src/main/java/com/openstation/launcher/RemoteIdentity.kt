package com.openstation.launcher

import java.util.Locale

internal object RemoteIdentity {
    fun isYandexRemoteName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.uppercase(Locale.ROOT)
        val vendor = normalized.contains("YNDX") || normalized.contains("YDX") || normalized.contains("YANDEX")
        return vendor && (normalized.contains("0006") || normalized.contains("REMOTE"))
    }
}
