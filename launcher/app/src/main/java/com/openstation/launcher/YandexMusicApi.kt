package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class YandexMusicApi(private val context: Context) {

    companion object {
        private const val TAG = "YandexMusicApi"
        private const val API_BASE = "https://api.music.yandex.net"
        private const val CLIENT_HEADER = "YandexMusicDesktopAppWindows/5.86.0"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) YandexMusic/5.86.0 Chrome/126.0.0.0 Electron/31.0.0 Safari/537.36"
        private const val FILE_INFO_SECRET = "kzqU4XhfCaY6B6JTHODeq5"
        private const val DOWNLOAD_INFO_SALT = "XGRlBW9FXlekgbPrRHuSiA"
        private const val VIBE_FROM = "web-home-rup_main-radio-personal"
        const val OAUTH_CLIENT_ID = "97fe03033fa34407ac9bcf91d5afed5b"
        const val OAUTH_REDIRECT_PREFIX = "music-application://desktop/oauth"
        const val OAUTH_AUTHORIZE_URL =
            "https://oauth.yandex.ru/authorize?response_type=token&display=popup" +
                "&scope=music%3Acontent&scope=music%3Aread&scope=music%3Awrite" +
                "&client_id=$OAUTH_CLIENT_ID" +
                "&redirect_uri=music-application%3A%2F%2Fdesktop%2Foauth" +
                "&origin=music_desktop&language=ru"

        private val PREFS_NAME = "ym_mod"
        private const val KEY_OAUTH_TOKEN = "oauth_token"
        private const val KEY_UID = "account_uid"
        private const val KEY_LOGIN = "account_login"
        private const val KEY_NAME = "account_name"
        private const val KEY_PLUS = "account_plus"
        private const val KEY_QUALITY = "ym_quality"

        private val coverCache = object : LruCache<String, Bitmap>(32) {}

        fun normalizeOAuthToken(raw: String?): String {
            var s = (raw ?: "").trim().trim('"', '\'')
            if (s.isEmpty()) return ""
            if (s.contains("access_token=")) {
                val fragOrQuery = s.substringAfter("access_token=")
                s = fragOrQuery.substringBefore("&").substringBefore("#").trim()
            }
            if (s.lowercase(Locale.US).startsWith("oauth ")) {
                s = s.substring(6).trim()
            } else if (s.lowercase(Locale.US).startsWith("bearer ")) {
                s = s.substring(7).trim()
            }
            return s
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyTokensIfNeeded()
    }

    private fun migrateLegacyTokensIfNeeded() {
        val cur = getToken()
        if (cur.isNotEmpty()) return

        val pending = normalizeOAuthToken(prefs.getString("pending_oauth_token", null))
        if (pending.isNotEmpty()) {
            prefs.edit().putString(KEY_OAUTH_TOKEN, pending).remove("pending_oauth_token").apply()
            Log.i(TAG, "Migrated pending OAuth token")
            return
        }

        // Scan Chromium LevelDB once if user had previously signed into the old WebView UI
        try {
            val ldbDir = File(context.applicationInfo.dataDir, "app_webview/Local Storage/leveldb")
            if (ldbDir.isDirectory) {
                val files = ldbDir.listFiles { _, name -> name.endsWith(".log") || name.endsWith(".ldb") }
                val tokRegex = Regex("""y0_[A-Za-z0-9_-]{18,}""")
                if (files != null) {
                    for (f in files) {
                        if (f.length() > 2 * 1024 * 1024L) continue
                        val raw = f.readBytes()
                        val ascii = String(raw, Charsets.ISO_8859_1)
                        val idx = ascii.indexOf("oauth")
                        if (idx >= 0) {
                            val sub = ascii.substring(idx, minOf(ascii.length, idx + 4096))
                            val m = tokRegex.find(sub)
                            if (m != null) {
                                saveToken(m.value)
                                Log.i(TAG, "Imported OAuth token from Chromium LevelDB")
                                return
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LevelDB OAuth scan skipped: ${t.message}")
        }
    }

    fun getToken(): String = normalizeOAuthToken(prefs.getString(KEY_OAUTH_TOKEN, ""))

    fun hasToken(): Boolean = getToken().isNotEmpty()

    fun saveToken(rawToken: String) {
        val tok = normalizeOAuthToken(rawToken)
        if (tok.isNotEmpty()) {
            prefs.edit().putString(KEY_OAUTH_TOKEN, tok).apply()
        }
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_OAUTH_TOKEN)
            .remove("pending_oauth_token")
            .remove(KEY_UID)
            .remove(KEY_LOGIN)
            .remove(KEY_NAME)
            .remove(KEY_PLUS)
            .apply()
    }

    fun getPreferredQuality(): String =
        prefs.getString(KEY_QUALITY, "normal")?.let { if (it == "lossless") "lossless" else "normal" } ?: "normal"

    fun setPreferredQuality(q: String) {
        val norm = if (q == "lossless") "lossless" else "normal"
        prefs.edit().putString(KEY_QUALITY, norm).apply()
    }

    fun getCachedAccount(): YmAccount {
        val tok = getToken()
        val uid = prefs.getString(KEY_UID, "") ?: ""
        val login = prefs.getString(KEY_LOGIN, "") ?: ""
        val name = prefs.getString(KEY_NAME, "") ?: ""
        val plus = prefs.getBoolean(KEY_PLUS, false)
        return YmAccount(
            loggedIn = tok.isNotEmpty() && uid.isNotEmpty(),
            uid = uid,
            login = login,
            fullName = name,
            hasPlus = plus
        )
    }

    private fun saveAccount(acct: YmAccount) {
        prefs.edit()
            .putString(KEY_UID, acct.uid)
            .putString(KEY_LOGIN, acct.login)
            .putString(KEY_NAME, acct.fullName)
            .putBoolean(KEY_PLUS, acct.hasPlus)
            .apply()
    }

    data class ApiResult(val code: Int, val body: String)

    fun request(
        pathOrUrl: String,
        method: String = "GET",
        jsonBody: String? = null,
        includeAuth: Boolean = true
    ): ApiResult {
        val fullUrl = if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            pathOrUrl
        } else {
            val clean = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
            "$API_BASE$clean"
        }
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Yandex-Music-Client", CLIENT_HEADER)
            setRequestProperty("X-Yandex-Music-Frontend", "new")
            setRequestProperty("X-Yandex-Music-Without-Invocation-Info", "1")
            if (includeAuth) {
                val tok = getToken()
                if (tok.isNotEmpty()) {
                    setRequestProperty("Authorization", "OAuth $tok")
                }
            }
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return ApiResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun unwrapObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        val parsed = try { JSONTokener(raw).nextValue() } catch (_: Throwable) { null }
        val obj = parsed as? JSONObject ?: return null
        if (obj.has("result") && obj.has("invocationInfo")) {
            return obj.optJSONObject("result") ?: obj
        }
        return obj
    }

    private fun unwrapArray(raw: String): JSONArray? {
        if (raw.isBlank()) return null
        val parsed = try { JSONTokener(raw).nextValue() } catch (_: Throwable) { null }
        if (parsed is JSONArray) return parsed
        if (parsed is JSONObject && parsed.has("result")) {
            return parsed.optJSONArray("result")
        }
        return null
    }

    class AuthException(val statusCode: Int, message: String) : Exception(message)

    fun refreshAccount(): YmAccount {
        if (!hasToken()) return YmAccount(loggedIn = false)
        val r = request("/account/about", "GET")
        if (r.code == 401 || r.code == 403) {
            throw AuthException(r.code, "OAuth токен отклонён (HTTP ${r.code})")
        }
        if (r.code !in 200..299) {
            throw Exception("Ошибка проверки аккаунта: HTTP ${r.code}")
        }
        val obj = unwrapObject(r.body) ?: throw Exception("Пустой ответ /account/about")
        val uid = obj.optString("uid", "").ifBlank {
            obj.optLong("uid", 0L).let { if (it > 0L) it.toString() else "" }
        }
        val login = obj.optString("login", "").ifBlank {
            obj.optJSONObject("account")?.optString("login", "") ?: ""
        }
        val fullName = obj.optString("fullName", "").ifBlank {
            obj.optString("displayName", "").ifBlank { login }
        }
        val hasPlus = obj.optBoolean("hasPlus", false) ||
            (obj.optJSONObject("plus")?.optBoolean("hasPlus", false) ?: false)
        if (uid.isBlank() || uid == "0") {
            throw AuthException(401, "Аккаунт не авторизован (uid=$uid)")
        }
        val acct = YmAccount(
            loggedIn = true,
            uid = uid,
            login = login,
            fullName = fullName,
            hasPlus = hasPlus
        )
        saveAccount(acct)
        return acct
    }

    fun startMyWave(seeds: List<String>, queue: List<String> = emptyList()): YmRotorBatch {
        if (!hasToken()) throw AuthException(401, "Требуется вход в Яндекс Музыку")
        val finalSeeds = if (seeds.isEmpty()) listOf("user:onyourwave") else seeds
        val body = JSONObject()
            .put("seeds", JSONArray(finalSeeds))
            .put("queue", JSONArray(queue))
            .put("includeTracksInResponse", true)
            .put("includeWaveModel", true)
            .put("allowExplicit", true)
            .put("interactive", true)
            .toString()
        val r = request("/rotor/session/new", "POST", body)
        if (r.code == 401 || r.code == 403) {
            throw AuthException(r.code, "Требуется авторизация Яндекс Музыки (HTTP ${r.code})")
        }
        if (r.code !in 200..299) {
            throw Exception("Ошибка /rotor/session/new: HTTP ${r.code}")
        }
        val obj = unwrapObject(r.body) ?: throw Exception("Некорректный ответ /rotor/session/new")
        val sid = obj.optString("radioSessionId", "")
        if (sid.isBlank()) throw Exception("Отсутствует radioSessionId")
        val batchId = obj.optString("batchId", "")
        val seq = obj.optJSONArray("sequence")
        val tracks = parseSequenceTracks(seq, batchId)
        val promoBan = seq != null && seq.length() > 0 && tracks.isEmpty()
        return YmRotorBatch(sid, batchId, tracks, promoBan)
    }

    fun nextWaveTracks(
        sessionId: String,
        queue: List<String>,
        feedbacks: List<JSONObject> = emptyList()
    ): YmRotorBatch {
        val body = JSONObject()
            .put("queue", JSONArray(queue))
            .apply {
                if (feedbacks.isNotEmpty()) {
                    put("feedbacks", JSONArray(feedbacks))
                }
            }
            .toString()
        val r = request("/rotor/session/$sessionId/tracks", "POST", body)
        if (r.code == 401 || r.code == 403) {
            throw AuthException(r.code, "Сессия истекла (HTTP ${r.code})")
        }
        if (r.code !in 200..299) {
            throw Exception("Ошибка /rotor/session/tracks: HTTP ${r.code}")
        }
        val obj = unwrapObject(r.body) ?: throw Exception("Пустой ответ /rotor/session/tracks")
        val batchId = obj.optString("batchId", "")
        val seq = obj.optJSONArray("sequence")
        val tracks = parseSequenceTracks(seq, batchId)
        val promoBan = seq != null && seq.length() > 0 && tracks.isEmpty()
        return YmRotorBatch(sessionId, batchId, tracks, promoBan)
    }

    private fun parseSequenceTracks(seq: JSONArray?, batchId: String): List<YmTrack> {
        val out = ArrayList<YmTrack>()
        if (seq == null) return out
        for (i in 0 until seq.length()) {
            val item = seq.optJSONObject(i) ?: continue
            val tObj = item.optJSONObject("track") ?: item
            val liked = if (item.has("liked")) item.optBoolean("liked", false) else tObj.optBoolean("liked", false)
            val tr = YmTrack.fromApiJson(tObj, batchId = batchId, likedOverride = liked) ?: continue
            if (tr.title.equals("Промокод Upgrade", ignoreCase = true)) continue
            if (!tr.available) continue
            out.add(tr)
        }
        return out
    }

    fun buildFeedbackPayload(
        type: String,
        batchId: String?,
        trackKey: String?,
        playedSec: Double? = null,
        totalSec: Double? = null
    ): JSONObject {
        val ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
        val ev = JSONObject()
            .put("type", type)
            .put("timestamp", ts)
        if (type == "radioStarted") {
            ev.put("from", VIBE_FROM)
        } else {
            if (!trackKey.isNullOrBlank()) ev.put("trackId", trackKey)
            if (playedSec != null) ev.put("totalPlayedSeconds", ((playedSec * 1000.0).toLong() / 1000.0))
            if (totalSec != null && totalSec > 0.0) ev.put("trackLengthSeconds", ((totalSec * 1000.0).toLong() / 1000.0))
        }
        return JSONObject().apply {
            if (!batchId.isNullOrBlank() && type != "radioStarted") put("batchId", batchId)
            put("event", ev)
            put("from", VIBE_FROM)
        }
    }

    fun sendFeedback(sessionId: String, payload: JSONObject) {
        try {
            request("/rotor/session/$sessionId/feedback/", "POST", payload.toString())
        } catch (t: Throwable) {
            Log.d(TAG, "Rotor feedback failed (${t.message})")
        }
    }

    fun searchTracks(query: String, limit: Int = 30): List<YmTrack> {
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) return emptyList()
        val encQ = URLEncoder.encode(cleanQ, "UTF-8")

        val out = ArrayList<YmTrack>()
        val seenIds = HashSet<String>()

        fun addTrack(tObj: JSONObject?) {
            val tr = YmTrack.fromApiJson(tObj ?: return) ?: return
            if (!tr.available || tr.title.equals("Промокод Upgrade", ignoreCase = true)) return
            if (seenIds.add(tr.id)) out.add(tr)
        }

        // Primary: instant mixed search (desktop 5.86.0 endpoint)
        try {
            val url = "/search/instant/mixed?text=$encQ&type=track,ugc_track&filter=track" +
                "&withLikesCount=true&page=0&pageSize=$limit&withBestResults=true"
            val r = request(url, "GET")
            if (r.code in 200..299) {
                val obj = unwrapObject(r.body)
                if (obj != null) {
                    val best = obj.optJSONArray("bestResults")
                    if (best != null) {
                        for (i in 0 until best.length()) {
                            val item = best.optJSONObject(i) ?: continue
                            val typ = item.optString("type", "")
                            if (typ == "track" || typ == "ugc_track") {
                                addTrack(item.optJSONObject("track") ?: item)
                            }
                        }
                    }
                    val res = obj.optJSONArray("results")
                    if (res != null) {
                        for (i in 0 until res.length()) {
                            val item = res.optJSONObject(i) ?: continue
                            val typ = item.optString("type", "")
                            if (typ == "track" || typ == "ugc_track" || item.has("track")) {
                                addTrack(item.optJSONObject("track") ?: item)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "search/instant/mixed failed: ${t.message}")
        }

        // Fallback: classic /search?type=track
        if (out.isEmpty()) {
            try {
                val url = "/search?text=$encQ&type=track&page=0&pageSize=$limit"
                val r = request(url, "GET")
                if (r.code in 200..299) {
                    val obj = unwrapObject(r.body)
                    val tracksObj = obj?.optJSONObject("tracks")
                    val results = tracksObj?.optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            addTrack(results.optJSONObject(i))
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "classic /search failed: ${t.message}")
            }
        }

        return out
    }

    fun getChartTracks(): Pair<String, List<YmTrack>> {
        val r = request("/landing3/chart", "GET")
        if (r.code !in 200..299) throw Exception("Ошибка загрузки чарта: HTTP ${r.code}")
        val obj = unwrapObject(r.body) ?: throw Exception("Пустой ответ чарта")
        val chart = obj.optJSONObject("chart") ?: obj
        val title = chart.optString("title", "Топ Чарт Яндекс Музыки")
        val arr = chart.optJSONArray("tracks")
        val out = ArrayList<YmTrack>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val tObj = item.optJSONObject("track") ?: item
                YmTrack.fromApiJson(tObj)?.let { if (it.available) out.add(it) }
            }
        }
        return title to out
    }

    data class LikedCollection(val tracks: List<YmTrack>, val total: Int?)

    fun getLikedCollection(uid: String): LikedCollection {
        if (uid.isBlank() || !hasToken()) return LikedCollection(emptyList(), null)
        try {
            val r = request("/users/$uid/likes/tracks", "GET")
            if (r.code == 401 || r.code == 403) throw AuthException(r.code, "Требуется вход в Яндекс Музыку")
            if (r.code in 200..299) {
                val obj = unwrapObject(r.body)
                val library = obj?.optJSONObject("library") ?: obj
                val array = library?.optJSONArray("tracks")
                if (array != null) {
                    val ids = ArrayList<String>(array.length())
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id", "").ifBlank {
                            item.optLong("id", 0L).let { if (it > 0L) it.toString() else "" }
                        }
                        if (id.isNotEmpty()) ids.add(id)
                    }
                    val tracks = TrackBatchLoader.load(ids, 100,
                        { batch -> getTracksMeta(batch, likedOverride = true) },
                        { track -> track.id }).filter { it.available }
                    return LikedCollection(tracks, array.length())
                }
            }
        } catch (ae: AuthException) {
            throw ae
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to load full liked library: ${t.message}")
        }
        // If the full library endpoint is unavailable, preserve the existing
        // first-page fallback without claiming that its size is the total.
        return LikedCollection(getLikedTracks(uid, limit = 80), null)
    }

    fun getLikedTracks(uid: String, limit: Int = 80): List<YmTrack> {
        if (uid.isBlank() || !hasToken()) return emptyList()
        val out = ArrayList<YmTrack>()

        // Liked tracks playlist is built-in playlist kind=3
        try {
            val r = request("/users/$uid/playlists/3?richTracks=true&page=0&pageSize=$limit", "GET")
            if (r.code in 200..299) {
                val obj = unwrapObject(r.body)
                val arr = obj?.optJSONArray("tracks")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val tObj = item.optJSONObject("track") ?: item
                        YmTrack.fromApiJson(tObj, likedOverride = true)?.let {
                            if (it.available) out.add(it)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "playlists/3 failed: ${t.message}")
        }

        if (out.isEmpty()) {
            try {
                val r = request("/users/$uid/likes/tracks", "GET")
                if (r.code in 200..299) {
                    val obj = unwrapObject(r.body)
                    val lib = obj?.optJSONObject("library") ?: obj
                    val arr = lib?.optJSONArray("tracks")
                    if (arr != null && arr.length() > 0) {
                        val ids = ArrayList<String>()
                        for (i in 0 until minOf(arr.length(), limit)) {
                            val item = arr.optJSONObject(i) ?: continue
                            val id = item.optString("id", "").ifBlank {
                                item.optLong("id", 0L).let { if (it > 0L) it.toString() else "" }
                            }
                            if (id.isNotEmpty()) ids.add(id)
                        }
                        if (ids.isNotEmpty()) {
                            return getTracksMeta(ids, likedOverride = true)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "likes/tracks fallback failed: ${t.message}")
            }
        }
        return out
    }

    fun getTracksMeta(trackIds: List<String>, likedOverride: Boolean? = null): List<YmTrack> {
        if (trackIds.isEmpty()) return emptyList()
        val joined = URLEncoder.encode(trackIds.joinToString(","), "UTF-8")
        val r = request("/tracks?trackIds=$joined&removeDuplicates=false&withProgress=true", "GET")
        if (r.code !in 200..299) return emptyList()
        val arr = unwrapArray(r.body) ?: return emptyList()
        val out = ArrayList<YmTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            YmTrack.fromApiJson(obj, likedOverride = likedOverride)?.let { out.add(it) }
        }
        return out
    }

    fun setTrackLiked(uid: String, trackId: String, liked: Boolean): Boolean {
        if (uid.isBlank() || trackId.isBlank() || !hasToken()) return false
        val path = if (liked) {
            "/users/$uid/likes/tracks/add?track-id=${URLEncoder.encode(trackId, "UTF-8")}"
        } else {
            "/users/$uid/likes/tracks/${URLEncoder.encode(trackId, "UTF-8")}/remove"
        }
        val r = request(path, "POST", "")
        return r.code in 200..299
    }

    fun dislikeTrack(uid: String, trackId: String): Boolean {
        if (uid.isBlank() || trackId.isBlank() || !hasToken()) return false
        val r = request("/users/$uid/dislikes/tracks/add?track-id=${URLEncoder.encode(trackId, "UTF-8")}", "POST", "")
        return r.code in 200..299
    }

    fun resolveStream(trackId: String, forceNormalQuality: Boolean = false): YmStreamInfo {
        val qMode = if (forceNormalQuality) "normal" else getPreferredQuality()
        val codecs = if (qMode == "lossless") {
            listOf("flac", "flac-mp4", "mp3", "aac", "he-aac", "aac-mp4", "he-aac-mp4")
        } else {
            listOf("mp3", "aac", "aac-mp4", "he-aac")
        }
        val qualityParam = if (qMode == "lossless") "lossless" else "normal"

        // 1) Try get-file-info RAW first (direct HTTPS stream, lowest overhead)
        if (hasToken()) {
            tryFetchFileInfo(trackId, qualityParam, codecs, "raw", maxAttempts = 2)?.let { return it }
            // 2) Try get-file-info ENCRAW (supports Hi-Res FLAC/AAC AES-128-CTR decrypted via loopback proxy)
            val allCodecs = listOf("flac", "aac", "he-aac", "mp3", "flac-mp4", "aac-mp4", "he-aac-mp4")
            tryFetchFileInfo(
                trackId,
                if (qMode == "lossless") "lossless" else "normal",
                if (qMode == "lossless") allCodecs else codecs,
                "encraw",
                maxAttempts = 5
            )?.let { return it }
        }

        // 3) Fallback: classic /tracks/{id}/download-info
        return resolveClassicDownloadInfo(trackId)
    }

    private fun hmacSha256TruncB64(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(FILE_INFO_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return if (b64.isNotEmpty()) b64.dropLast(1) else ""
    }

    private fun tryFetchFileInfo(
        trackId: String,
        quality: String,
        codecs: List<String>,
        transport: String,
        maxAttempts: Int
    ): YmStreamInfo? {
        val cleanId = trackId.substringBefore(":")
        val codecsJoined = codecs.joinToString("")
        val codecsCsv = URLEncoder.encode(codecs.joinToString(","), "UTF-8")

        for (attempt in 0 until maxAttempts) {
            try {
                val ts = System.currentTimeMillis() / 1000L
                val signData = "$ts$cleanId$quality$codecsJoined$transport"
                val sign = URLEncoder.encode(hmacSha256TruncB64(signData), "UTF-8")
                val path = "/get-file-info?ts=$ts&trackId=${URLEncoder.encode(cleanId, "UTF-8")}" +
                    "&quality=$quality&codecs=$codecsCsv&transports=$transport&sign=$sign"
                val r = request(path, "GET")
                if (r.code == 401 || r.code == 403) {
                    throw AuthException(r.code, "Ошибка авторизации потока (HTTP ${r.code})")
                }
                if (r.code !in 200..299) {
                    Log.w(TAG, "get-file-info ($transport) HTTP ${r.code}: ${r.body.take(200)}")
                    return null
                }
                val root = unwrapObject(r.body) ?: return null
                val di = root.optJSONObject("downloadInfo") ?: root
                if (di.has("error")) {
                    Log.w(TAG, "downloadInfo error ($transport): ${di.optString("error")}")
                    return null
                }
                val retId = di.optString("trackId", "").ifBlank {
                    di.optLong("trackId", 0L).let { if (it > 0L) it.toString() else "" }
                }
                if (retId.isNotEmpty() && retId != cleanId) {
                    Log.w(TAG, "get-file-info ad-track mismatch ($retId != $cleanId), attempt=${attempt + 1}")
                    Thread.sleep(140)
                    continue
                }
                var url = di.optString("url", "")
                if (url.isBlank()) {
                    val urls = di.optJSONArray("urls")
                    if (urls != null && urls.length() > 0) url = urls.optString(0, "")
                }
                if (url.isBlank()) return null
                val retTransport = di.optString("transport", transport).lowercase()
                val keyHex = di.optString("key", "")
                val codec = di.optString("codec", "mp3")
                val bitrate = di.optInt("bitrate", 0)
                return YmStreamInfo(
                    url = url,
                    transport = retTransport,
                    codec = codec,
                    bitrate = bitrate,
                    keyHex = keyHex,
                    isPreview = false,
                    resolvedTrackId = retId
                )
            } catch (ae: AuthException) {
                throw ae
            } catch (t: Throwable) {
                Log.w(TAG, "get-file-info ($transport) failed: ${t.message}")
                return null
            }
        }
        return null
    }

    private fun resolveClassicDownloadInfo(trackId: String): YmStreamInfo {
        val cleanId = trackId.substringBefore(":")
        val r = request("/tracks/$cleanId/download-info", "GET")
        if (r.code == 401 || r.code == 403) {
            throw AuthException(r.code, "Требуется авторизация для прослушивания (HTTP ${r.code})")
        }
        if (r.code !in 200..299) {
            throw Exception("Не удалось получить поток трека (HTTP ${r.code})")
        }
        val arr = unwrapArray(r.body) ?: throw Exception("Пустой список download-info")
        var bestObj: JSONObject? = null
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val codec = o.optString("codec", "").lowercase()
            val bitrate = o.optInt("bitrateInKbps", 0)
            val preview = o.optBoolean("preview", false)
            var score = bitrate
            if (!preview) score += 10000
            if (codec == "mp3") score += 500
            if (score > bestScore) {
                bestScore = score
                bestObj = o
            }
        }
        val sel = bestObj ?: throw Exception("Нет доступных аудиопотоков")
        val diUrl = sel.optString("downloadInfoUrl", "")
        if (diUrl.isBlank()) throw Exception("Отсутствует downloadInfoUrl")
        val codec = sel.optString("codec", "mp3")
        val bitrate = sel.optInt("bitrateInKbps", 0)
        val isPreview = sel.optBoolean("preview", false)

        val xmlConn = (URL(diUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val xml = try {
            xmlConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            xmlConn.disconnect()
        }

        fun tag(name: String): String {
            val m = Regex("<$name>([^<]+)</$name>").find(xml)
            return m?.groupValues?.get(1)?.trim() ?: ""
        }
        val host = tag("host")
        val path = tag("path")
        val ts = tag("ts")
        val s = tag("s")
        if (host.isBlank() || path.isBlank() || ts.isBlank() || s.isBlank()) {
            throw Exception("Некорректный XML download-info")
        }
        val cleanPath = if (path.startsWith("/")) path.substring(1) else path
        val md5In = DOWNLOAD_INFO_SALT + cleanPath + s
        val md5Bytes = MessageDigest.getInstance("MD5").digest(md5In.toByteArray(Charsets.UTF_8))
        val sign = md5Bytes.joinToString("") { "%02x".format(it) }
        val finalUrl = "https://$host/get-mp3/$sign/$ts$path"
        return YmStreamInfo(
            url = finalUrl,
            transport = "raw",
            codec = codec,
            bitrate = bitrate,
            keyHex = "",
            isPreview = isPreview,
            resolvedTrackId = cleanId
        )
    }

    fun loadCoverBitmap(coverUri: String, sizePx: Int = 300): Bitmap? {
        val full = if (coverUri.startsWith("http")) coverUri else {
            val norm = if (coverUri.startsWith("//")) "https:$coverUri" else "https://$coverUri"
            norm.replace("%%", "${sizePx}x${sizePx}")
        }
        if (full.isBlank() || !full.contains("/")) return null
        coverCache.get(full)?.let { return it }
        return try {
            val conn = (URL(full).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val bmp = try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
            if (bmp != null) coverCache.put(full, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
