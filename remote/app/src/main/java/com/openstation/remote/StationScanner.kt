package com.openstation.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Station(val host: String, val port: Int, val name: String, val version: String) {
    val url get() = "http://$host:$port/"
    val key get() = "$host:$port"
}

/**
 * Finds OpenStation daemons on the phone's Wi-Fi/Ethernet subnet: TCP-probe every host on the
 * web port, then confirm with GET /api/status (an OpenStation answer has "hostname" and
 * "firmwareVersion").
 * ponytail: /24 sweep around our own address; mDNS (_http._tcp, TXT model=...) if networks wider
 * than /24 need covering.
 */
object StationScanner {
    const val PORT = 8080

    /** Our IPv4 on the active LAN network, or null (mobile data / no network). */
    fun localIpv4(ctx: Context): Inet4Address? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }
    }

    /** Blocking; call off the main thread. Results sorted by name. */
    fun scan(ctx: Context, extraHosts: Collection<String> = emptyList()): List<Station> {
        val hosts = LinkedHashSet<String>(extraHosts)
        localIpv4(ctx)?.address?.let { b ->
            val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
            for (i in 1..254) hosts.add(prefix + i)
        }
        val pool = Executors.newFixedThreadPool(64)
        val futures = hosts.map { h -> pool.submit<Station?> { probe(h, PORT, connectMs = 400) } }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
        return futures.mapNotNull { runCatching { it.get(0, TimeUnit.MILLISECONDS) }.getOrNull() }
            .distinctBy { it.key }
            .sortedBy { it.name.lowercase() }
    }

    fun probe(host: String, port: Int, connectMs: Int = 1500): Station? = try {
        Socket().use { it.connect(InetSocketAddress(host, port), connectMs) }
        val conn = URL("http://$host:$port/api/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 2500
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val j = JSONObject(body)
        if (!j.has("hostname") || !j.has("firmwareVersion")) null
        else Station(host, port, j.optString("hostname").ifBlank { host }, j.optString("firmwareVersion"))
    } catch (_: Throwable) {
        null
    }
}
