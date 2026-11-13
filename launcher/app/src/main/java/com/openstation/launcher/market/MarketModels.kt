package com.openstation.launcher.market

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable

data class MarketApp(
    val id: String,
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val description: String,
    val changelog: String,
    val category: String,
    val iconUrl: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    val accentHex: String
) {
    fun accentColorOrDefault(): Int {
        if (accentHex.isNotEmpty()) {
            try {
                return Color.parseColor(accentHex)
            } catch (_: Throwable) {
            }
        }
        val palette = intArrayOf(
            Color.parseColor("#38BDF8"),
            Color.parseColor("#A855F7"),
            Color.parseColor("#E8C686"),
            Color.parseColor("#34D399"),
            Color.parseColor("#FB7185"),
            Color.parseColor("#818CF8")
        )
        val idx = ((packageName.hashCode() and 0x7FFFFFFF) % palette.size)
        return palette[idx]
    }
}

enum class CatalogSource {
    NETWORK,
    CACHE,
    BUNDLED
}

data class MarketCatalog(
    val schema: Int,
    val catalogVersion: String,
    val generatedAt: String,
    val apps: List<MarketApp>,
    val source: CatalogSource,
    val warning: String = ""
)

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val isProtected: Boolean,
    val isTvApp: Boolean,
    val icon: Drawable?,
    val launchIntent: Intent?
)

data class ApkInspection(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val sizeBytes: Long,
    val icon: Drawable?,
    val signatures: Set<String>
)

data class InstallStageEvent(
    val stage: String, // "download", "verify", "install"
    val progressPct: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSec: Long
)

object MarketPrefs {
    const val DEFAULT_CATALOG_URL =
        "https://raw.githubusercontent.com/Olegu621/openstation-market-apps/main/catalog.json"

    private const val PREFS = "openstation_market_prefs"
    private const val KEY_CATALOG_URL = "catalog_url"
    private const val KEY_AUTO_UPDATE = "auto_check_updates"
    private const val KEY_PREFER_DAEMON_ROOT = "prefer_daemon_root"

    fun getCatalogUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CATALOG_URL)) {
            return DEFAULT_CATALOG_URL
        }
        return prefs.getString(KEY_CATALOG_URL, DEFAULT_CATALOG_URL)?.trim() ?: ""
    }

    fun setCatalogUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CATALOG_URL, url.trim())
            .apply()
    }

    fun getAutoCheckUpdates(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoCheckUpdates(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_UPDATE, enabled)
            .apply()
    }

    fun isPreferDaemonRoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFER_DAEMON_ROOT, true)

    fun setPreferDaemonRoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREFER_DAEMON_ROOT, enabled)
            .apply()
    }
}
