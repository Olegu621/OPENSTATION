package com.openstation.launcher

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AesCtrProxyServerTest {
    private val key = ByteArray(16) { it.toByte() }
    private val plain = ByteArray(4096) { (it % 251).toByte() }

    private fun testRange(upstreamSupportsRange: Boolean) {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encrypted = cipher.doFinal(plain)
        val upstream = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            val socket = upstream.accept()
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val output = socket.getOutputStream()
            if (range != null && upstreamSupportsRange) {
                val bits = range!!.removePrefix("bytes=").split("-")
                val start = bits[0].toInt()
                val end = bits.getOrNull(1)?.toIntOrNull()?.coerceAtMost(encrypted.lastIndex) ?: encrypted.lastIndex
                val bytes = encrypted.copyOfRange(start, end + 1)
                output.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${encrypted.size}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(bytes)
            } else {
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${encrypted.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(encrypted)
            }
            output.flush()
            socket.close()
        }
        val proxy = AesCtrProxyServer()
        try {
            val hex = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val target = proxy.registerStream("http://127.0.0.1:${upstream.localPort}/audio", hex, "flac")
            val client = URL(target).openConnection() as HttpURLConnection
            client.setRequestProperty("Range", "bytes=37-99")
            client.readTimeout = 3000
            assertEquals(206, client.responseCode)
            assertEquals("bytes 37-99/4096", client.getHeaderField("Content-Range"))
            assertArrayEquals(plain.copyOfRange(37, 100), client.inputStream.use { it.readBytes() })
            client.disconnect()
        } finally {
            proxy.stop()
            upstream.close()
            serverThread.join(3000)
        }
    }

    @Test fun decryptsRangeWhenUpstreamHonorsRange() = testRange(true)
    @Test fun decryptsRangeWhenUpstreamIgnoresRange() = testRange(false)

    @Test fun concurrentStartsUseOneListeningPort() {
        val proxy = AesCtrProxyServer()
        val ports = Collections.synchronizedList(mutableListOf<Int>())
        try {
            val workers = (1..20).map { thread { ports.add(proxy.start()) } }
            workers.forEach { it.join() }
            assertEquals(20, ports.size)
            assertEquals(1, ports.toSet().size)
        } finally {
            proxy.stop()
        }
    }
}
