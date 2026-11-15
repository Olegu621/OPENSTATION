package com.openstation.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object SetupAddress {
    fun isUsableIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return bytes[0] in 1..223 && bytes[0] != 127 && !(bytes[0] == 169 && bytes[1] == 254)
    }

    fun webUrl(ip: String): String? = if (isUsableIpv4(ip)) "http://$ip:8080" else null

    fun wizardUrl(ip: String): String? = webUrl(ip)?.plus("/#wizard")
}

internal data class DnsPresetUiItem(
    val host: String,
    val name: String,
    val category: String,
    val active: Boolean,
    val tested: Boolean,
    val ok: Boolean,
    val latencyMs: Int
)

internal object DaemonApi {
    fun requestJson(
        path: String,
        method: String = "GET",
        jsonBody: String? = null,
        timeoutMs: Int = 3500
    ): JSONObject? {
        return try {
            val conn = (URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = minOf(timeoutMs, 2000)
                readTimeout = timeoutMs
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (txt.isNotBlank()) JSONObject(txt) else JSONObject().put("ok", code in 200..299)
        } catch (_: Throwable) {
            null
        }
    }

    fun parseDnsPresets(arr: JSONArray?): List<DnsPresetUiItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<DnsPresetUiItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host", "").trim()
            if (host.isEmpty()) continue
            out.add(
                DnsPresetUiItem(
                    host = host,
                    name = o.optString("name", host).ifBlank { host },
                    category = o.optString("category", "smart"),
                    active = o.optBoolean("active", false),
                    tested = o.has("ok"),
                    ok = o.optBoolean("ok", false),
                    latencyMs = o.optInt("latencyMs", -1)
                )
            )
        }
        return out
    }
}
