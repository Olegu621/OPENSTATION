package com.openstation.launcher.market

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object MarketRepository {
    private const val CACHE_FILE = "market_catalog_cache.json"
    private const val ASSET_CATALOG = "market_catalog.json"
    private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024

    val PROTECTED_PACKAGES = setOf(
        "com.openstation.launcher",
        "com.openstation.ime",
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.shell",
        "com.android.providers.settings"
    )

    fun loadCatalog(context: Context, customUrl: String, forceRefresh: Boolean): MarketCatalog {
        val trimmedUrl = customUrl.trim()
        if (trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("http://")) {
            if (!forceRefresh) {
                readCache(context)?.let { cachedRaw ->
                    try {
                        return parseCatalog(cachedRaw, CatalogSource.CACHE)
                    } catch (_: Throwable) {
                    }
                }
            }
            try {
                val body = fetchText(trimmedUrl)
                val parsed = parseCatalog(body, CatalogSource.NETWORK)
                saveCache(context, body)
                return parsed
            } catch (e: Throwable) {
                readCache(context)?.let { cachedRaw ->
                    try {
                        val c = parseCatalog(cachedRaw, CatalogSource.CACHE)
                        return c.copy(warning = "Нет сети (${friendlyErr(e)}) — показан кэш")
                    } catch (_: Throwable) {
                    }
                }
                val demo = loadBundledDemo(context)
                return demo.copy(warning = "Не удалось загрузить URL (${friendlyErr(e)}) — показан встроенный каталог")
            }
        }
        return loadBundledDemo(context)
    }

    private fun loadBundledDemo(context: Context): MarketCatalog {
        val raw = context.assets.open(ASSET_CATALOG).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseCatalog(raw, CatalogSource.BUNDLED)
    }

    fun parseCatalog(raw: String, source: CatalogSource): MarketCatalog {
        val root = JSONObject(raw)
        val schema = root.optInt("schema", 1)
        if (schema != 1) {
            throw IllegalStateException("Неподдерживаемая версия каталога (schema=$schema)")
        }
        val ver = root.optString("catalogVersion", "1.0")
        val gen = root.optString("generatedAt", "")
        val arr = root.optJSONArray("apps") ?: JSONArray()
        val list = ArrayList<MarketApp>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pkg = obj.optString("package", "").trim()
            val name = obj.optString("name", "").trim()
            val dl = obj.optString("downloadUrl", "").trim()
            val sha = normalizeSha256(obj.optString("sha256", ""))
            if (pkg.isEmpty() || name.isEmpty() || dl.isEmpty()) continue
            if (!dl.startsWith("https://") && !dl.startsWith("http://")) continue
            if (sha.length != 64) continue // mandatory integrity verification

            val verCode = obj.optLong("versionCode", 0L)
            if (verCode <= 0L) continue

            list.add(
                MarketApp(
                    id = obj.optString("id", pkg).ifEmpty { pkg },
                    name = name,
                    packageName = pkg,
                    versionName = obj.optString("versionName", "1.0").ifEmpty { "1.0" },
                    versionCode = verCode,
                    description = obj.optString("description", "").trim(),
                    changelog = obj.optString("changelog", "").trim(),
                    category = obj.optString("category", "Приложения").trim().ifEmpty { "Приложения" },
                    iconUrl = obj.optString("iconUrl", "").trim(),
                    downloadUrl = dl,
                    sha256 = sha,
                    sizeBytes = obj.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                    minSdk = obj.optInt("minSdk", 21).coerceAtLeast(19),
                    accentHex = obj.optString("accentColor", "").trim()
                )
            )
        }
        return MarketCatalog(
            schema = schema,
            catalogVersion = ver,
            generatedAt = gen,
            apps = list,
            source = source
        )
    }

    fun getInstalledVersionCode(pm: PackageManager, packageName: String): Long {
        return try {
            val pi = pm.getPackageInfo(packageName, 0)
            packageVersionCode(pi)
        } catch (_: Throwable) {
            -1L
        }
    }

    fun getInstalledVersionName(pm: PackageManager, packageName: String): String {
        return try {
            pm.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun isSystemPackage(pm: PackageManager, packageName: String): Boolean {
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) {
            false
        }
    }

    fun queryInstalledApps(context: Context, includeSystem: Boolean): List<InstalledAppEntry> {
        val pm = context.packageManager
        val pkgs = pm.getInstalledPackages(0)
        val out = ArrayList<InstalledAppEntry>(pkgs.size)
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName ?: continue
            val sysFlag = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSys = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!includeSystem && sysFlag && !updatedSys && pkg != context.packageName) {
                continue
            }
            if (!includeSystem && (pkg.startsWith("com.android.cts") || pkg.startsWith("com.google.android.overlay"))) {
                continue
            }
            val leanback = pm.getLeanbackLaunchIntentForPackage(pkg)
            val normal = pm.getLaunchIntentForPackage(pkg)
            val launch = leanback ?: normal
            // When showing only user apps, still keep launchable / user-installed tools clean
            val label = try {
                ai.loadLabel(pm).toString().ifEmpty { pkg }
            } catch (_: Throwable) {
                pkg
            }
            val icon = try {
                ai.loadIcon(pm)
            } catch (_: Throwable) {
                null
            }
            out.add(
                InstalledAppEntry(
                    packageName = pkg,
                    label = label,
                    versionName = pi.versionName ?: "",
                    versionCode = packageVersionCode(pi),
                    isSystem = sysFlag,
                    isUpdatedSystem = updatedSys,
                    isProtected = PROTECTED_PACKAGES.contains(pkg) || (sysFlag && !updatedSys),
                    isTvApp = leanback != null,
                    icon = icon,
                    launchIntent = launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            )
        }
        return out.sortedWith(
            compareBy<InstalledAppEntry> { it.isSystem && !it.isUpdatedSystem }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    @Suppress("DEPRECATION")
    fun getInstalledSignatureDigests(pm: PackageManager, packageName: String): Set<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = pi.signingInfo ?: return emptySet()
                val signers = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                digestsFromSignatures(signers)
            } else {
                val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                digestsFromSignatures(pi.signatures)
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    @Suppress("DEPRECATION")
    fun inspectLocalApk(context: Context, apkFile: File): ApkInspection? {
        if (!apkFile.isFile || !apkFile.canRead() || apkFile.length() < 256L) return null
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val pi = try {
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } catch (_: Throwable) {
            null
        } ?: return null

        val ai = pi.applicationInfo
        var label = pi.packageName ?: apkFile.nameWithoutExtension
        var icon = context.applicationInfo.loadIcon(pm)
        if (ai != null) {
            ai.sourceDir = apkFile.absolutePath
            ai.publicSourceDir = apkFile.absolutePath
            try {
                label = ai.loadLabel(pm).toString().ifEmpty { label }
            } catch (_: Throwable) {
            }
            try {
                icon = ai.loadIcon(pm)
            } catch (_: Throwable) {
            }
        }
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && ai != null) {
            ai.minSdkVersion
        } else {
            21
        }
        val sigs = LinkedHashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null) {
            val si = pi.signingInfo
            val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs.addAll(digestsFromSignatures(arr))
        }
        if (sigs.isEmpty()) {
            sigs.addAll(digestsFromSignatures(pi.signatures))
        }
        return ApkInspection(
            packageName = pi.packageName ?: return null,
            label = label,
            versionName = pi.versionName ?: "1.0",
            versionCode = packageVersionCode(pi),
            minSdk = minSdk,
            sizeBytes = apkFile.length(),
            icon = icon,
            signatures = sigs
        )
    }

    fun doSignaturesOverlap(installed: Set<String>, incoming: Set<String>): Boolean {
        if (installed.isEmpty() || incoming.isEmpty()) return true // unknown -> let installer check
        return installed.any { incoming.contains(it) }
    }

    @Suppress("DEPRECATION")
    fun packageVersionCode(pi: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            pi.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun digestsFromSignatures(sigs: Array<android.content.pm.Signature>?): Set<String> {
        if (sigs == null || sigs.isEmpty()) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        val set = LinkedHashSet<String>(sigs.size)
        for (s in sigs) {
            val dig = md.digest(s.toByteArray())
            val sb = StringBuilder(dig.size * 2)
            for (b in dig) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
            set.add(sb.toString())
        }
        return set
    }

    fun normalizeSha256(raw: String): String {
        val clean = raw.lowercase(Locale.US).replace(Regex("[^0-9a-f]"), "")
        return if (clean.length == 64) clean else ""
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        if (bytes < 1024L) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f МБ", mb)
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
    }

    private fun fetchText(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 18_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OpenStationMarket/1.4")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val buf = ByteArray(8192)
            val baos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (baos.size() + n > MAX_CATALOG_BYTES) {
                        throw IllegalStateException("Каталог > 2 МБ")
                    }
                    baos.write(buf, 0, n)
                }
            }
            baos.toString("UTF-8")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun saveCache(context: Context, body: String) {
        try {
            FileOutputStream(File(context.filesDir, CACHE_FILE)).use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    private fun readCache(context: Context): String? {
        val f = File(context.filesDir, CACHE_FILE)
        if (!f.isFile) return null
        return try {
            FileInputStream(f).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun friendlyErr(e: Throwable): String {
        val m = e.message?.trim().orEmpty()
        return m.ifEmpty { e.javaClass.simpleName }
    }
}
