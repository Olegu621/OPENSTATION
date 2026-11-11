package com.openstation.launcher

import android.util.Log
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

object YandexMusicServer {
    private const val TAG = "YandexMusicSrv"
    const val PORT = 18080

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var rootDir: File? = null
    private val pool = Executors.newCachedThreadPool()

    private val HOP_BY_HOP_REQ = setOf(
        "host", "connection", "content-length", "transfer-encoding",
        "origin", "referer", "accept-encoding", "upgrade",
        "x-yandex-music-device", "x-request-id"
    )
    private val STRIP_RESP_HEADERS = setOf(
        "connection", "transfer-encoding", "content-length", "content-encoding",
        "x-frame-options", "content-security-policy", "content-security-policy-report-only",
        "access-control-allow-origin", "access-control-allow-methods",
        "access-control-allow-headers", "access-control-allow-credentials",
        "access-control-expose-headers", "keep-alive", "public-key-pins"
    )

    @Synchronized
    fun ensureStarted(appRootDir: File): Boolean {
        rootDir = appRootDir
        if (running && serverSocket != null && !(serverSocket?.isClosed ?: true)) {
            return true
        }
        return try {
            val ss = ServerSocket(PORT, 64, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).apply {
                reuseAddress = true
            }
            serverSocket = ss
            running = true
            val t = Thread({
                while (running && !ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        sock.soTimeout = 30000
                        pool.execute { handleClient(sock) }
                    } catch (e: Throwable) {
                        if (running) Log.w(TAG, "accept error: ${e.message}")
                    }
                }
            }, "YmHttpServer")
            t.isDaemon = true
            t.start()
            Log.i(TAG, "Loopback HTTP server listening on 127.0.0.1:$PORT -> ${appRootDir.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind 127.0.0.1:$PORT: ${t.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    private fun readAsciiLine(inp: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = inp.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("ISO_8859_1")
            }
            if (b == '\n'.code) {
                var s = baos.toString("ISO_8859_1")
                if (s.endsWith("\r")) s = s.dropLast(1)
                return s
            }
            if (baos.size() < 32768) baos.write(b)
        }
    }

    private fun readExact(inp: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = inp.read(out, off, len - off)
            if (r < 0) throw EOFException("Unexpected EOF reading body")
            off += r
        }
        return out
    }

    private fun readChunkedBody(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val szLine = readAsciiLine(inp)?.trim() ?: break
            val semi = szLine.indexOf(';')
            val hex = (if (semi >= 0) szLine.substring(0, semi) else szLine).trim()
            if (hex.isEmpty()) continue
            val chunkLen = hex.toIntOrNull(16) ?: break
            if (chunkLen == 0) {
                while (true) {
                    val tr = readAsciiLine(inp)
                    if (tr.isNullOrEmpty()) break
                }
                break
            }
            if (chunkLen > 32 * 1024 * 1024) break
            out.write(readExact(inp, chunkLen))
            readAsciiLine(inp) // CRLF after chunk
        }
        return out.toByteArray()
    }

    private fun handleClient(sock: Socket) {
        sock.use { s ->
            val inp = s.getInputStream()
            val out = s.getOutputStream()

            val reqLine = readAsciiLine(inp) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) {
                sendSimple(out, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]

            val reqHeaders = LinkedHashMap<String, String>()
            while (true) {
                val hl = readAsciiLine(inp) ?: break
                if (hl.isEmpty()) break
                val colon = hl.indexOf(':')
                if (colon > 0) {
                    val k = hl.substring(0, colon).trim()
                    val v = hl.substring(colon + 1).trim()
                    reqHeaders[k] = v
                }
            }

            if (reqHeaders.entries.any { it.key.equals("Expect", true) && it.value.contains("100-continue", true) }) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            }

            var reqBody: ByteArray? = null
            val te = reqHeaders.entries.firstOrNull { it.key.equals("Transfer-Encoding", true) }?.value ?: ""
            val clStr = reqHeaders.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value
            if (te.contains("chunked", true)) {
                reqBody = readChunkedBody(inp)
            } else if (clStr != null) {
                val cl = clStr.toIntOrNull() ?: 0
                if (cl in 1..(32 * 1024 * 1024)) {
                    reqBody = readExact(inp, cl)
                } else if (cl == 0) {
                    reqBody = ByteArray(0)
                }
            }

            val qIdx = rawTarget.indexOf('?')
            val rawPath = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val rawQuery = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

            if (rawPath == "/_ym_proxy") {
                if (method == "OPTIONS") {
                    sendCorsPreflight(out)
                    return
                }
                handleProxy(method, rawQuery, reqHeaders, reqBody, out)
                return
            }

            if (rawPath == "/_ym_blocked") {
                val hdr = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: 2\r\n")
                    append("Access-Control-Allow-Origin: http://127.0.0.1:$PORT\r\n")
                    append("Access-Control-Allow-Credentials: true\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write("{}".toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }

            if (method == "OPTIONS") {
                sendCorsPreflight(out)
                return
            }
            if (method != "GET" && method != "HEAD") {
                sendSimple(out, 405, "Method Not Allowed")
                return
            }
            handleStatic(method == "HEAD", rawPath, reqHeaders, out)
        }
    }

    private fun sendCorsPreflight(out: OutputStream) {
        val hdr = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun handleStatic(headOnly: Boolean, rawPath: String, reqHeaders: Map<String, String>, out: OutputStream) {
        val root = rootDir
        if (root == null || !root.isDirectory) {
            sendSimple(out, 503, "Yandex Music static directory missing")
            return
        }
        val decodedPath = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (_: Throwable) {
            rawPath
        }
        var rel = decodedPath.trimStart('/')
        val rootCanon = root.canonicalPath
        val candidates = ArrayList<File>(4)
        if (rel.isEmpty()) {
            candidates.add(File(root, "index.html"))
        } else {
            val clean = rel.trimEnd('/')
            candidates.add(File(root, clean))
            candidates.add(File(root, "$clean.html"))
            candidates.add(File(root, "$clean/index.html"))
        }

        var chosen: File? = null
        var status = 200
        for (c in candidates) {
            val cp = c.canonicalPath
            if (cp != rootCanon && !cp.startsWith(rootCanon + File.separator)) continue
            if (c.isFile) {
                chosen = c
                break
            }
        }
        if (chosen == null) {
            val f404 = File(root, "404.html")
            if (f404.isFile) {
                chosen = f404
                status = 404
            } else {
                sendSimple(out, 404, "Not Found: $decodedPath")
                return
            }
        }

        val totalLen = chosen.length()
        val mime = guessMime(chosen.name)
        val cacheHdr = if (chosen.name.endsWith(".html") || chosen.name == "_ym_bridge.js" || chosen.name == "_ym_tv.css") {
            "no-cache"
        } else {
            "public, max-age=86400"
        }

        val rangeHdr = reqHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (status == 200 && rangeHdr != null && rangeHdr.startsWith("bytes=") && totalLen > 0) {
            val spec = rangeHdr.removePrefix("bytes=").split(',')[0].trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val sStr = spec.substring(0, dash).trim()
                val eStr = spec.substring(dash + 1).trim()
                val start: Long
                val end: Long
                if (sStr.isEmpty()) {
                    val suffix = eStr.toLongOrNull() ?: 0L
                    start = maxOf(0L, totalLen - suffix)
                    end = totalLen - 1
                } else {
                    start = sStr.toLongOrNull() ?: 0L
                    end = minOf(totalLen - 1, eStr.toLongOrNull() ?: (totalLen - 1))
                }
                if (start in 0 until totalLen && end >= start) {
                    val len = end - start + 1
                    val hdr = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Type: $mime\r\n")
                        append("Content-Length: $len\r\n")
                        append("Content-Range: bytes $start-$end/$totalLen\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Cache-Control: $cacheHdr\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    if (!headOnly) {
                        RandomAccessFile(chosen, "r").use { raf ->
                            raf.seek(start)
                            val buf = ByteArray(16384)
                            var rem = len
                            while (rem > 0) {
                                val r = raf.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                                if (r <= 0) break
                                out.write(buf, 0, r)
                                rem -= r
                            }
                        }
                    }
                    out.flush()
                    return
                }
            }
        }

        val statusTxt = if (status == 404) "404 Not Found" else "200 OK"
        val hdr = buildString {
            append("HTTP/1.1 $statusTxt\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $totalLen\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: $cacheHdr\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (!headOnly) {
            chosen.inputStream().use { fi ->
                val buf = ByteArray(16384)
                while (true) {
                    val r = fi.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                }
            }
        }
        out.flush()
    }

    private fun extractQueryParam(query: String, key: String): String {
        if (query.isEmpty()) return ""
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            if (k == key) {
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                return try { URLDecoder.decode(rawVal, "UTF-8") } catch (_: Throwable) { rawVal }
            }
        }
        return ""
    }

    private fun isTrustedYandexHost(host: String): Boolean {
        val h = host.lowercase(Locale.US)
        return h.endsWith(".yandex.ru") || h == "yandex.ru" ||
            h.endsWith(".yandex.net") || h == "yandex.net" ||
            h.endsWith(".ya.ru") || h == "ya.ru"
    }

    private fun handleProxy(
        method: String,
        rawQuery: String,
        reqHeaders: Map<String, String>,
        reqBody: ByteArray?,
        out: OutputStream
    ) {
        val target = extractQueryParam(rawQuery, "url").trim()
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            sendSimple(out, 400, "Invalid target url")
            return
        }
        var curUrl = target
        var curMethod = method
        var curBody = reqBody

        try {
            var conn: HttpURLConnection? = null
            var code = 502
            var statusMsg = "Bad Gateway"

            for (hop in 0 until 6) {
                val parsed = URL(curUrl)
                val hostLow = parsed.host.lowercase(Locale.US)
                if (hostLow == "127.0.0.1" || hostLow == "localhost" || hostLow == "::1") {
                    sendSimple(out, 403, "Forbidden loopback proxy")
                    return
                }
                val c = (parsed.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = curMethod
                    connectTimeout = 15000
                    readTimeout = 30000
                    useCaches = false
                }
                var hasCookie = false
                for ((k, v) in reqHeaders) {
                    val lk = k.lowercase(Locale.US)
                    if (lk in HOP_BY_HOP_REQ || lk == "user-agent") continue
                    if ((lk == "authorization" || lk == "cookie") && hop > 0 && !isTrustedYandexHost(hostLow)) {
                        continue
                    }
                    if (lk == "cookie") hasCookie = true
                    c.setRequestProperty(k, v)
                }
                c.setRequestProperty("User-Agent", "YandexMusicDesktopAppWindows/5.86.0")
                if (!hasCookie && isTrustedYandexHost(hostLow)) {
                    val cmCookie = try { CookieManager.getInstance().getCookie(curUrl) } catch (_: Throwable) { null }
                    if (!cmCookie.isNullOrBlank()) {
                        c.setRequestProperty("Cookie", cmCookie)
                    }
                }
                if (curBody != null && curMethod != "GET" && curMethod != "HEAD" && curMethod != "DELETE") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(curBody.size)
                    c.outputStream.use { os -> os.write(curBody) }
                }

                code = c.responseCode
                statusMsg = c.responseMessage ?: "OK"

                if (isTrustedYandexHost(hostLow)) {
                    val setCookies = c.headerFields["Set-Cookie"] ?: c.headerFields["set-cookie"]
                    if (setCookies != null) {
                        val cm = try { CookieManager.getInstance() } catch (_: Throwable) { null }
                        for (sc in setCookies) {
                            try { cm?.setCookie(curUrl, sc) } catch (_: Throwable) {}
                        }
                    }
                }

                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (!loc.isNullOrBlank()) {
                        curUrl = URL(parsed, loc).toString()
                        if (code == 303 || ((code == 301 || code == 302) && curMethod == "POST")) {
                            curMethod = "GET"
                            curBody = null
                        }
                        continue
                    }
                }
                conn = c
                break
            }

            if (conn == null) {
                sendSimple(out, 502, "Too many redirects")
                return
            }

            try {
                val respHdrs = ArrayList<Pair<String, String>>()
                var idx = 0
                while (true) {
                    val key = conn.getHeaderFieldKey(idx)
                    val value = conn.getHeaderField(idx)
                    idx++
                    if (key == null && value == null) break
                    if (key == null || value == null) continue
                    val lk = key.lowercase(Locale.US)
                    if (lk in STRIP_RESP_HEADERS || lk == "set-cookie") continue
                    respHdrs.add(key to value)
                }

                val hasBody = curMethod != "HEAD" && code != 204 && code != 304
                val inpStream = if (!hasBody) null else {
                    if (code < 400) conn.inputStream else conn.errorStream
                }

                val declaredLen = conn.contentLengthLong
                // Buffer normal responses <= 8 MB so Content-Length matches decompressed bytes;
                // stream larger media payloads directly with Connection: close.
                if (inpStream == null) {
                    val hdr = buildString {
                        append("HTTP/1.1 $code $statusMsg\r\n")
                        for ((k, v) in respHdrs) append("$k: $v\r\n")
                        append("Content-Length: 0\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Access-Control-Expose-Headers: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    out.flush()
                } else if (declaredLen in 0..8_388_608 || declaredLen < 0) {
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    var streamedDirect = false
                    while (true) {
                        val r = inpStream.read(buf)
                        if (r <= 0) break
                        if (!streamedDirect && baos.size() + r > 8_388_608) {
                            streamedDirect = true
                            val hdr = buildString {
                                append("HTTP/1.1 $code $statusMsg\r\n")
                                for ((k, v) in respHdrs) append("$k: $v\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("Access-Control-Expose-Headers: *\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            baos.writeTo(out)
                        }
                        if (streamedDirect) {
                            out.write(buf, 0, r)
                        } else {
                            baos.write(buf, 0, r)
                        }
                    }
                    if (!streamedDirect) {
                        val bytes = baos.toByteArray()
                        val hdr = buildString {
                            append("HTTP/1.1 $code $statusMsg\r\n")
                            for ((k, v) in respHdrs) append("$k: $v\r\n")
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Access-Control-Expose-Headers: *\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(hdr.toByteArray(Charsets.US_ASCII))
                        out.write(bytes)
                    }
                    out.flush()
                } else {
                    val hdr = buildString {
                        append("HTTP/1.1 $code $statusMsg\r\n")
                        for ((k, v) in respHdrs) append("$k: $v\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Access-Control-Expose-Headers: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    val buf = ByteArray(16384)
                    while (true) {
                        val r = inpStream.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                    }
                    out.flush()
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Proxy error for $curUrl: ${t.message}")
            sendSimple(out, 502, "Proxy error: ${t.message}")
        }
    }

    private fun sendSimple(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val hdr = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        try {
            out.write(hdr.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun guessMime(name: String): String {
        val low = name.lowercase(Locale.US)
        return when {
            low.endsWith(".html") || low.endsWith(".htm") -> "text/html; charset=utf-8"
            low.endsWith(".js") || low.endsWith(".mjs") -> "application/javascript; charset=utf-8"
            low.endsWith(".css") -> "text/css; charset=utf-8"
            low.endsWith(".json") -> "application/json; charset=utf-8"
            low.endsWith(".txt") -> "text/plain; charset=utf-8"
            low.endsWith(".svg") -> "image/svg+xml"
            low.endsWith(".png") -> "image/png"
            low.endsWith(".jpg") || low.endsWith(".jpeg") -> "image/jpeg"
            low.endsWith(".webp") -> "image/webp"
            low.endsWith(".gif") -> "image/gif"
            low.endsWith(".wasm") -> "application/wasm"
            low.endsWith(".woff2") -> "font/woff2"
            low.endsWith(".woff") -> "font/woff"
            low.endsWith(".ttf") -> "font/ttf"
            low.endsWith(".otf") -> "font/otf"
            low.endsWith(".mp4") -> "video/mp4"
            low.endsWith(".mp3") -> "audio/mpeg"
            low.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }
}
