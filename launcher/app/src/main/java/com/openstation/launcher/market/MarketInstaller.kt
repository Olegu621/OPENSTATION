package com.openstation.launcher.market

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object MarketInstaller {
    private const val DAEMON_BASE = "http://127.0.0.1:8080"
    private val mainHandler = Handler(Looper.getMainLooper())

    interface TaskListener {
        fun onStage(event: InstallStageEvent)
        fun onSuccess(message: String)
        fun onError(message: String, needsUnknownSourcesPermission: Boolean = false)
    }

    fun checkDaemonOnline(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$DAEMON_BASE/api/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 900
                readTimeout = 1200
            }
            conn.responseCode == 200
        } catch (_: Throwable) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    fun installFromCatalog(
        context: Context,
        app: MarketApp,
        preferDaemonRoot: Boolean,
        cancelled: AtomicBoolean,
        listener: TaskListener
    ) {
        val appCtx = context.applicationContext
        thread(name = "MarketDlInstall") {
            var tempApk: File? = null
            try {
                if (app.minSdk > Build.VERSION.SDK_INT) {
                    postError(listener, "Требуется Android SDK ${app.minSdk}+ (на станции SDK ${Build.VERSION.SDK_INT})")
                    return@thread
                }

                val apkDir = File(appCtx.filesDir, "market_apks").apply { mkdirs() }
                cleanupStaleFiles(apkDir)

                val needed = maxOf(app.sizeBytes * 2L + 32L * 1024L * 1024L, 64L * 1024L * 1024L)
                val avail = StatFs(apkDir.absolutePath).availableBytes
                if (avail < needed) {
                    postError(
                        listener,
                        "Недостаточно памяти: свободно ${MarketRepository.formatBytes(avail)}, нужно ~${MarketRepository.formatBytes(needed)}"
                    )
                    return@thread
                }

                val safeName = app.packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_") + "_" + app.versionCode + ".apk"
                val partFile = File(apkDir, "$safeName.part")
                val finalFile = File(apkDir, safeName)
                partFile.delete()
                finalFile.delete()
                tempApk = finalFile

                postStage(listener, InstallStageEvent("download", 0, 0L, app.sizeBytes, 0L))
                downloadWithProgress(app.downloadUrl, partFile, app.sizeBytes, cancelled) { dl, total, bps ->
                    val pct = if (total > 0L) ((dl * 100L) / total).toInt().coerceIn(0, 100) else 0
                    postStage(listener, InstallStageEvent("download", pct, dl, total, bps))
                }

                if (cancelled.get()) {
                    partFile.delete()
                    postError(listener, "Загрузка отменена")
                    return@thread
                }
                if (!partFile.renameTo(finalFile)) {
                    throw IllegalStateException("Не удалось сохранить временный APK")
                }

                postStage(listener, InstallStageEvent("verify", 100, finalFile.length(), finalFile.length(), 0L))
                if (app.sizeBytes > 0L && finalFile.length() != app.sizeBytes) {
                    throw IllegalStateException(
                        "Размер файла (${MarketRepository.formatBytes(finalFile.length())}) не совпадает с каталогом (${MarketRepository.formatBytes(app.sizeBytes)})"
                    )
                }

                val actualSha = computeSha256(finalFile, cancelled)
                if (cancelled.get()) {
                    postError(listener, "Загрузка отменена")
                    return@thread
                }
                if (!actualSha.equals(app.sha256, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 не совпадает! Установка отменена из соображений безопасности.")
                }

                val inspection = MarketRepository.inspectLocalApk(appCtx, finalFile)
                    ?: throw IllegalStateException("Скачанный файл не является корректным APK")
                if (inspection.packageName != app.packageName) {
                    throw IllegalStateException("Пакет внутри APK (${inspection.packageName}) не совпадает с каталогом (${app.packageName})")
                }
                val installedVer = MarketRepository.getInstalledVersionCode(appCtx.packageManager, app.packageName)
                if (installedVer >= 0L) {
                    val installedSigs = MarketRepository.getInstalledSignatureDigests(appCtx.packageManager, app.packageName)
                    if (!MarketRepository.doSignaturesOverlap(installedSigs, inspection.signatures)) {
                        throw IllegalStateException("Подпись APK не совпадает с уже установленной версией — обновление невозможно")
                    }
                }

                postStage(listener, InstallStageEvent("install", 100, finalFile.length(), finalFile.length(), 0L))
                performInstallSync(appCtx, finalFile, preferDaemonRoot, listener)
            } catch (e: Throwable) {
                val msg = e.message?.trim().orEmpty().ifEmpty { "Ошибка загрузки или установки" }
                postError(listener, msg)
            } finally {
                // Clean temp downloaded file only after daemon/PackageInstaller finished reading
                try {
                    if (tempApk != null && tempApk.exists()) {
                        // Wait briefly if fallback PackageInstaller queued session before deleting
                        if (!preferDaemonRoot) {
                            Thread.sleep(500)
                        }
                        tempApk.delete()
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun installLocalApk(
        context: Context,
        apkFile: File,
        preferDaemonRoot: Boolean,
        listener: TaskListener
    ) {
        val appCtx = context.applicationContext
        thread(name = "MarketLocalInstall") {
            try {
                if (!apkFile.isFile || !apkFile.canRead()) {
                    postError(listener, "Файл недоступен для чтения: ${apkFile.name}")
                    return@thread
                }
                val inspection = MarketRepository.inspectLocalApk(appCtx, apkFile)
                    ?: run {
                        postError(listener, "Файл не является корректным APK")
                        return@thread
                    }
                if (inspection.minSdk > Build.VERSION.SDK_INT) {
                    postError(listener, "APK требует Android SDK ${inspection.minSdk}+ (на станции SDK ${Build.VERSION.SDK_INT})")
                    return@thread
                }
                val installedVer = MarketRepository.getInstalledVersionCode(appCtx.packageManager, inspection.packageName)
                if (installedVer >= 0L) {
                    val installedSigs = MarketRepository.getInstalledSignatureDigests(appCtx.packageManager, inspection.packageName)
                    if (!MarketRepository.doSignaturesOverlap(installedSigs, inspection.signatures)) {
                        postError(listener, "Подпись APK отличается от установленной версии (${inspection.packageName}). Сначала удалите старую версию.")
                        return@thread
                    }
                }
                postStage(listener, InstallStageEvent("install", 100, apkFile.length(), apkFile.length(), 0L))
                performInstallSync(appCtx, apkFile, preferDaemonRoot, listener)
            } catch (e: Throwable) {
                postError(listener, e.message ?: "Ошибка установки APK")
            }
        }
    }

    fun uninstallPackage(
        context: Context,
        packageName: String,
        preferDaemonRoot: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        if (MarketRepository.PROTECTED_PACKAGES.contains(packageName)) {
            mainHandler.post { onDone(false, "Системный пакет прошивки защищён от удаления") }
            return
        }
        val appCtx = context.applicationContext
        thread(name = "MarketUninstall") {
            if (preferDaemonRoot && checkDaemonOnline()) {
                val (ok, msg) = callDaemonUninstall(packageName)
                if (ok) {
                    mainHandler.post { onDone(true, "Приложение удалено") }
                    return@thread
                }
                // Surface specific pm error unless daemon endpoint absent
                if (!msg.contains("unknown endpoint")) {
                    mainHandler.post { onDone(false, friendlyPmError(msg)) }
                    return@thread
                }
            }
            // Fallback: system uninstall dialog
            mainHandler.post {
                try {
                    val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appCtx.startActivity(intent)
                    onDone(true, "Открыт системный запрос удаления")
                } catch (e: Throwable) {
                    onDone(false, "Не удалось удалить приложение: ${e.message}")
                }
            }
        }
    }

    private fun performInstallSync(
        appCtx: Context,
        apkFile: File,
        preferDaemonRoot: Boolean,
        listener: TaskListener
    ) {
        if (preferDaemonRoot) {
            val (called, ok, output) = callDaemonInstallPath(apkFile.absolutePath)
            if (called) {
                if (ok) {
                    postSuccess(listener, "Установлено на станцию")
                } else {
                    postError(listener, friendlyPmError(output))
                }
                return
            }
        }

        // Fallback when daemon is offline or disabled in settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = try {
                appCtx.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                true
            }
            if (!canInstall) {
                postError(
                    listener,
                    "Служба openstationd недоступна, а в Android запрещена установка из неизвестных источников.",
                    needsUnknownSourcesPermission = true
                )
                return
            }
        }
        runPackageInstallerSession(appCtx, apkFile, listener)
    }

    private fun callDaemonInstallPath(path: String): Triple<Boolean, Boolean, String> {
        var conn: HttpURLConnection? = null
        return try {
            val payload = JSONObject().apply {
                put("path", path)
                put("deleteAfter", false)
            }.toString()
            conn = (URL("$DAEMON_BASE/api/apps/install_path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 95_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val obj = try {
                JSONObject(body)
            } catch (_: Throwable) {
                JSONObject()
            }
            val err = obj.optString("error", "")
            if (err == "unknown endpoint") {
                return Triple(false, false, err)
            }
            val ok = obj.optBoolean("ok", code in 200..299)
            val out = obj.optString("output", err.ifEmpty { body })
            Triple(true, ok, out)
        } catch (_: Throwable) {
            Triple(false, false, "")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun callDaemonUninstall(packageName: String): Pair<Boolean, String> {
        var conn: HttpURLConnection? = null
        return try {
            val payload = JSONObject().put("package", packageName).toString()
            conn = (URL("$DAEMON_BASE/api/apps/uninstall").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 50_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val obj = try {
                JSONObject(body)
            } catch (_: Throwable) {
                JSONObject()
            }
            val ok = obj.optBoolean("ok", false)
            val out = obj.optString("output", obj.optString("error", body))
            Pair(ok, out)
        } catch (e: Throwable) {
            Pair(false, e.message ?: "connection failed")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun runPackageInstallerSession(context: Context, apkFile: File, listener: TaskListener) {
        val finished = AtomicBoolean(false)
        MarketInstallReceiver.activeCallback = object : MarketInstallReceiver.Callback {
            override fun onPackageInstallerResult(success: Boolean, message: String) {
                if (finished.compareAndSet(false, true)) {
                    MarketInstallReceiver.activeCallback = null
                    if (success) postSuccess(listener, message) else postError(listener, message)
                }
            }
        }

        val pi = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(apkFile.length())
            }
            val sessionId = pi.createSession(params)
            session = pi.openSession(sessionId)
            FileInputStream(apkFile).use { input ->
                session.openWrite("base.apk", 0L, apkFile.length()).use { out ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    session.fsync(out)
                }
            }
            val intent = Intent(context, MarketInstallReceiver::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, piFlags)
            session.commit(pending.intentSender)
        } catch (e: Throwable) {
            try {
                session?.abandon()
            } catch (_: Throwable) {
            }
            if (finished.compareAndSet(false, true)) {
                MarketInstallReceiver.activeCallback = null
                postError(listener, e.message ?: "Ошибка PackageInstaller")
            }
        }
    }

    private fun downloadWithProgress(
        urlStr: String,
        outFile: File,
        expectedSize: Long,
        cancelled: AtomicBoolean,
        onProgress: (downloaded: Long, total: Long, bps: Long) -> Unit
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OpenStationMarket/1.4")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Ошибка загрузки HTTP $code")
            }
            val headerLen = conn.contentLengthLong
            val total = if (headerLen > 0L) headerLen else expectedSize
            val buf = ByteArray(65_536)
            var downloaded = 0L
            val tStart = System.currentTimeMillis()
            var lastNotify = 0L

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { out ->
                    while (!cancelled.get()) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n.toLong()
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= 120L) {
                            lastNotify = now
                            val elapsedMs = maxOf(100L, now - tStart)
                            val bps = (downloaded * 1000L) / elapsedMs
                            onProgress(downloaded, total, bps)
                        }
                    }
                    out.flush()
                }
            }
            val elapsedMs = maxOf(100L, System.currentTimeMillis() - tStart)
            onProgress(downloaded, maxOf(total, downloaded), (downloaded * 1000L) / elapsedMs)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun computeSha256(file: File, cancelled: AtomicBoolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65_536)
        FileInputStream(file).use { input ->
            while (!cancelled.get()) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val dig = md.digest()
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun cleanupStaleFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") || f.lastModified() < cutoff) {
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun friendlyPmError(raw: String): String {
        return when {
            raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || raw.contains("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES") ->
                "Конфликт подписи APK с установленной версией"
            raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "Установлена более новая версия этого приложения"
            raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                "Недостаточно свободного места на станции"
            raw.contains("INSTALL_FAILED_OLDER_SDK") ->
                "APK требует более новую версию Android"
            raw.contains("INSTALL_FAILED_INVALID_APK") ->
                "Файл APK повреждён"
            raw.contains("DELETE_FAILED_INTERNAL_ERROR") ->
                "Невозможно удалить системное приложение"
            else -> raw.trim().ifEmpty { "Сбой операции установки" }
        }
    }

    private fun postStage(l: TaskListener, e: InstallStageEvent) {
        mainHandler.post { l.onStage(e) }
    }

    private fun postSuccess(l: TaskListener, msg: String) {
        mainHandler.post { l.onSuccess(msg) }
    }

    private fun postError(l: TaskListener, msg: String, needsUnknownSourcesPermission: Boolean = false) {
        mainHandler.post { l.onError(msg, needsUnknownSourcesPermission) }
    }
}
