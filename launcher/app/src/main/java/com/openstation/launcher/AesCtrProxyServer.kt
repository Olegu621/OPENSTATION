package com.openstation.launcher

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesCtrProxyServer {
    companion object {
        private const val TAG = "YmAesProxy"
    }

    data class StreamTarget(
        val id: String,
        val upstreamUrl: String,
        val keyHex: String,
        val codec: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    @Volatile
    var port: Int = 0
        private set

    private val targets = ConcurrentHashMap<String, StreamTarget>()
    private val acceptExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExec: ExecutorService = Executors.newCachedThreadPool()

    @Synchronized
    fun start(): Int {
        if (running && port > 0) return port
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        running = true
        acceptExec.submit {
            while (running) {
                try {
                    val sock = ss.accept()
                    clientExec.submit { handleClient(sock) }
                } catch (t: Throwable) {
                    if (running) Log.w(TAG, "Accept error: ${t.message}")
                }
            }
        }
        Log.i(TAG, "AES-CTR loopback stream proxy started on 127.0.0.1:$port")
        return port
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        port = 0
        targets.clear()
        acceptExec.shutdownNow()
        clientExec.shutdownNow()
    }

    fun registerStream(upstreamUrl: String, keyHex: String, codec: String): String {
        val p = start()
        if (targets.size > 10) {
            val oldest = targets.entries.sortedBy { it.value.createdAt }.take(targets.size - 6)
            for (e in oldest) targets.remove(e.key)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        targets[id] = StreamTarget(id, upstreamUrl, keyHex.trim(), codec.lowercase())
        val ext = when {
            codec.contains("flac-mp4") || codec.contains("-mp4") -> "mp4"
            codec.contains("flac") -> "flac"
            codec.contains("aac") -> "aac"
            else -> "mp3"
        }
        return "http://127.0.0.1:$p/stream/$id.$ext"
    }

    private fun mimeForCodec(codec: String): String = when {
        codec == "flac" -> "audio/flac"
        codec.contains("-mp4") -> "audio/mp4"
        codec.contains("aac") -> "audio/aac"
        else -> "audio/mpeg"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun buildCtrIv(blockIndex: Long): ByteArray {
        val iv = ByteArray(16)
        var v = blockIndex
        for (i in 15 downTo 0) {
            iv[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        return iv
    }

    private fun handleClient(sock: Socket) {
        sock.soTimeout = 15_000
        var conn: HttpURLConnection? = null
        try {
            val ins = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream())
            val reqLine = readAsciiLine(ins) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1].substringBefore("?")

            var rangeHeader: String? = null
            while (true) {
                val line = readAsciiLine(ins) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    if (k == "range") rangeHeader = v
                }
            }

            if (method != "GET" && method != "HEAD") {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val token = rawPath.substringAfterLast("/").substringBefore(".")
            val target = targets[token]
            if (target == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            var reqStart = 0L
            var reqEnd: Long? = null
            var hasRange = false
            if (!rangeHeader.isNullOrBlank() && rangeHeader.lowercase().startsWith("bytes=")) {
                val spec = rangeHeader.substringAfter("=").substringBefore(",").trim()
                val dash = spec.indexOf('-')
                if (dash > 0) {
                    reqStart = spec.substring(0, dash).toLongOrNull() ?: 0L
                    reqEnd = spec.substring(dash + 1).toLongOrNull()
                    hasRange = true
                }
            }

            val skipWithinBlock = (reqStart % 16L).toInt()
            val alignedStart = reqStart - skipWithinBlock

            conn = (URL(target.upstreamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                if (hasRange || alignedStart > 0L) {
                    val rangeStr = if (reqEnd != null) "bytes=$alignedStart-$reqEnd" else "bytes=$alignedStart-"
                    setRequestProperty("Range", rangeStr)
                }
            }

            val code = conn.responseCode
            if (code >= 400) {
                out.write("HTTP/1.1 $code Upstream Error\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val upLen = conn.contentLengthLong
            val upRange = conn.getHeaderField("Content-Range")
            var totalSize: Long = -1L
            if (!upRange.isNullOrBlank() && upRange.contains("/")) {
                totalSize = upRange.substringAfter("/").trim().toLongOrNull() ?: -1L
            } else if (code == 200 && upLen > 0L) {
                totalSize = upLen
            }

            val responseLen: Long = when {
                upLen > 0L -> (upLen - skipWithinBlock).coerceAtLeast(0L)
                totalSize > 0L && hasRange -> {
                    val endActual = reqEnd?.coerceAtMost(totalSize - 1L) ?: (totalSize - 1L)
                    (endActual - reqStart + 1L).coerceAtLeast(0L)
                }
                else -> -1L
            }

            val mime = mimeForCodec(target.codec)
            val hdr = StringBuilder()
            if (hasRange && (code == 206 || totalSize > 0L)) {
                hdr.append("HTTP/1.1 206 Partial Content\r\n")
                val endByte = when {
                    reqEnd != null -> reqEnd
                    totalSize > 0L -> totalSize - 1L
                    upLen > 0L -> alignedStart + upLen - 1L
                    else -> -1L
                }
                if (endByte >= reqStart) {
                    val totalPart = if (totalSize > 0L) totalSize.toString() else "*"
                    hdr.append("Content-Range: bytes $reqStart-$endByte/$totalPart\r\n")
                }
            } else {
                hdr.append("HTTP/1.1 200 OK\r\n")
            }
            hdr.append("Content-Type: $mime\r\n")
            hdr.append("Accept-Ranges: bytes\r\n")
            if (responseLen >= 0L) {
                hdr.append("Content-Length: $responseLen\r\n")
            }
            hdr.append("Connection: close\r\n\r\n")
            out.write(hdr.toString().toByteArray(Charsets.US_ASCII))
            out.flush()

            if (method == "HEAD") return

            val cipher: Cipher? = if (target.keyHex.length == 32) {
                val keyBytes = hexToBytes(target.keyHex)
                val iv = buildCtrIv(alignedStart / 16L)
                Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                }
            } else {
                null
            }

            val upStream: InputStream = conn.inputStream
            val buf = ByteArray(16384)
            var toDiscard = skipWithinBlock
            while (running) {
                val n = upStream.read(buf)
                if (n <= 0) break
                val dec = if (cipher != null) cipher.update(buf, 0, n) else buf.copyOf(n)
                if (dec != null && dec.isNotEmpty()) {
                    if (toDiscard >= dec.size) {
                        toDiscard -= dec.size
                    } else if (toDiscard > 0) {
                        out.write(dec, toDiscard, dec.size - toDiscard)
                        toDiscard = 0
                    } else {
                        out.write(dec, 0, dec.size)
                    }
                }
            }
            if (cipher != null) {
                val fin = cipher.doFinal()
                if (fin != null && fin.isNotEmpty()) {
                    if (toDiscard < fin.size) {
                        out.write(fin, toDiscard, fin.size - toDiscard)
                    }
                }
            }
            out.flush()
        } catch (_: Throwable) {
            // Client aborted or closed early (normal on seek/skip)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun readAsciiLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var b = ins.read()
        if (b < 0) return null
        while (b >= 0) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            b = ins.read()
        }
        return sb.toString()
    }
}
