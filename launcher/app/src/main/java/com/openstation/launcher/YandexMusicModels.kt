package com.openstation.launcher

import org.json.JSONArray
import org.json.JSONObject

data class YmTrack(
    val id: String,
    val realId: String,
    val title: String,
    val artists: String,
    val album: String,
    val albumId: String,
    val coverUri: String,
    val durationMs: Long,
    val batchId: String = "",
    val available: Boolean = true,
    val liked: Boolean = false
) {
    val stableId: String
        get() = realId.ifBlank { id }

    val rotorTrackKey: String
        get() = if (albumId.isNotBlank()) "$stableId:$albumId" else stableId

    fun coverUrl(sizePx: Int = 400): String {
        val raw = coverUri.trim()
        if (raw.isEmpty()) return ""
        val withScheme = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
        return withScheme.replace("%%", "${sizePx}x${sizePx}")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("realId", realId)
        .put("title", title)
        .put("artists", artists)
        .put("album", album)
        .put("albumId", albumId)
        .put("coverUri", coverUri)
        .put("durationMs", durationMs)
        .put("batchId", batchId)
        .put("available", available)
        .put("liked", liked)

    companion object {
        fun fromApiJson(obj: JSONObject, batchId: String = "", likedOverride: Boolean? = null): YmTrack? {
            val id = obj.optString("id", "").ifBlank {
                obj.optLong("id", 0L).let { if (it > 0L) it.toString() else "" }
            }
            if (id.isBlank()) return null
            val realId = obj.optString("realId", "").ifBlank { id }
            val title = obj.optString("title", "Без названия")
            val version = obj.optString("version", "").trim()
            val fullTitle = if (version.isNotEmpty() && !title.contains(version)) "$title ($version)" else title

            val artistsArr = obj.optJSONArray("artists")
            val artistNames = ArrayList<String>()
            if (artistsArr != null) {
                for (i in 0 until artistsArr.length()) {
                    val a = artistsArr.optJSONObject(i) ?: continue
                    val nm = a.optString("name", "").trim()
                    if (nm.isNotEmpty()) artistNames.add(nm)
                }
            }
            val artistsStr = if (artistNames.isNotEmpty()) artistNames.joinToString(", ") else "Неизвестный исполнитель"

            var albumName = ""
            var albumId = ""
            var cover = obj.optString("coverUri", "").ifBlank { obj.optString("ogImage", "") }
            val albumsArr = obj.optJSONArray("albums")
            if (albumsArr != null && albumsArr.length() > 0) {
                val al = albumsArr.optJSONObject(0)
                if (al != null) {
                    albumName = al.optString("title", "")
                    albumId = al.optString("id", "").ifBlank {
                        al.optLong("id", 0L).let { if (it > 0L) it.toString() else "" }
                    }
                    if (cover.isBlank()) cover = al.optString("coverUri", "")
                }
            }

            val durMs = obj.optLong("durationMs", 0L)
            val available = if (obj.has("available")) obj.optBoolean("available", true) else true
            val liked = likedOverride ?: obj.optBoolean("liked", false)

            return YmTrack(
                id = id,
                realId = realId,
                title = fullTitle,
                artists = artistsStr,
                album = albumName,
                albumId = albumId,
                coverUri = cover,
                durationMs = durMs,
                batchId = batchId,
                available = available,
                liked = liked
            )
        }

        fun fromSnapshotJson(obj: JSONObject?): YmTrack? {
            if (obj == null) return null
            val id = obj.optString("id", "")
            if (id.isBlank()) return null
            return YmTrack(
                id = id,
                realId = obj.optString("realId", id),
                title = obj.optString("title", ""),
                artists = obj.optString("artists", ""),
                album = obj.optString("album", ""),
                albumId = obj.optString("albumId", ""),
                coverUri = obj.optString("coverUri", ""),
                durationMs = obj.optLong("durationMs", 0L),
                batchId = obj.optString("batchId", ""),
                available = obj.optBoolean("available", true),
                liked = obj.optBoolean("liked", false)
            )
        }
    }
}

data class YmAccount(
    val loggedIn: Boolean = false,
    val uid: String = "",
    val login: String = "",
    val fullName: String = "",
    val hasPlus: Boolean = false
)

data class YmStreamInfo(
    val url: String,
    val transport: String,
    val codec: String,
    val bitrate: Int,
    val keyHex: String = "",
    val isPreview: Boolean = false,
    val resolvedTrackId: String = ""
) {
    val label: String
        get() = buildString {
            append(codec.uppercase())
            if (bitrate > 0) append(" $bitrate")
            if (isPreview) append(" (30с)")
        }
}

data class YmRotorBatch(
    val sessionId: String,
    val batchId: String,
    val tracks: List<YmTrack>,
    val promoBan: Boolean = false
)

enum class YmWaveMood(val key: String, val label: String, val seed: String?) {
    ANY("any", "Моя волна", null),
    ENERGETIC("energetic", "Бодрая ⚡", "moodEnergy:active"),
    FUN("fun", "Весёлая 🎉", "moodEnergy:fun"),
    CALM("calm", "Спокойная 🌙", "moodEnergy:calm"),
    SAD("sad", "Грустная 🌧", "moodEnergy:sad");

    companion object {
        fun fromKey(k: String?): YmWaveMood {
            val low = (k ?: "").trim().lowercase()
            return values().firstOrNull { it.key == low } ?: ANY
        }
    }
}

data class YmPlayerSnapshot(
    val playing: Boolean = false,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val ducked: Boolean = false,
    val sourceType: String = "idle",
    val sourceLabel: String = "Яндекс Музыка",
    val waveMood: String = "any",
    val quality: String = "normal",
    val codecLabel: String = "",
    val currentTrack: YmTrack? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSize: Int = 0,
    val currentIndex: Int = -1,
    val queuePreview: List<YmTrack> = emptyList(),
    val account: YmAccount = YmAccount(),
    val lastError: String = "",
    val seq: Long = 0L
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("playing", playing)
            .put("paused", paused)
            .put("buffering", buffering)
            .put("ducked", ducked)
            .put("sourceType", sourceType)
            .put("sourceLabel", sourceLabel)
            .put("waveMood", waveMood)
            .put("quality", quality)
            .put("codecLabel", codecLabel)
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("queueSize", queueSize)
            .put("currentIndex", currentIndex)
            .put("hasAuth", account.loggedIn)
            .put("userUid", account.uid)
            .put("userLogin", account.login)
            .put("userName", account.fullName)
            .put("hasPlus", account.hasPlus)
            .put("error", lastError)
            .put("seq", seq)
        if (currentTrack != null) {
            o.put("trackId", currentTrack.id)
            o.put("title", currentTrack.title)
            o.put("artist", currentTrack.artists)
            o.put("album", currentTrack.album)
            o.put("coverUrl", currentTrack.coverUrl(400))
            o.put("liked", currentTrack.liked)
            o.put("currentTrack", currentTrack.toJson())
        } else {
            o.put("trackId", "")
            o.put("title", "")
            o.put("artist", "")
            o.put("album", "")
            o.put("coverUrl", "")
            o.put("liked", false)
        }
        val qArr = JSONArray()
        for (t in queuePreview) qArr.put(t.toJson())
        o.put("queue", qArr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): YmPlayerSnapshot {
            val qList = ArrayList<YmTrack>()
            val qArr = o.optJSONArray("queue")
            if (qArr != null) {
                for (i in 0 until qArr.length()) {
                    YmTrack.fromSnapshotJson(qArr.optJSONObject(i))?.let { qList.add(it) }
                }
            }
            val acct = YmAccount(
                loggedIn = o.optBoolean("hasAuth", false),
                uid = o.optString("userUid", ""),
                login = o.optString("userLogin", ""),
                fullName = o.optString("userName", ""),
                hasPlus = o.optBoolean("hasPlus", false)
            )
            return YmPlayerSnapshot(
                playing = o.optBoolean("playing", false),
                paused = o.optBoolean("paused", false),
                buffering = o.optBoolean("buffering", false),
                ducked = o.optBoolean("ducked", false),
                sourceType = o.optString("sourceType", "idle"),
                sourceLabel = o.optString("sourceLabel", "Яндекс Музыка"),
                waveMood = o.optString("waveMood", "any"),
                quality = o.optString("quality", "normal"),
                codecLabel = o.optString("codecLabel", ""),
                currentTrack = YmTrack.fromSnapshotJson(o.optJSONObject("currentTrack")),
                positionMs = o.optLong("positionMs", 0L),
                durationMs = o.optLong("durationMs", 0L),
                queueSize = o.optInt("queueSize", 0),
                currentIndex = o.optInt("currentIndex", -1),
                queuePreview = qList,
                account = acct,
                lastError = o.optString("error", ""),
                seq = o.optLong("seq", 0L)
            )
        }
    }
}
