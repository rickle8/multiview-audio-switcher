package com.rickle8.mvswitch.remote

import android.content.Context
import android.net.wifi.WifiManager
import com.rickle8.mvswitch.data.TvType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FoundTv(val type: TvType, val host: String, val name: String)

/** Finding TVs on the Wi-Fi, and turning a Samsung on remotely. */
object TvNetwork {
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).build()

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    /** Samsung's info endpoint: name, model, power state and MAC. Null if not reachable. */
    fun samsungInfo(host: String): JSONObject? = if (host.isBlank()) null else
        get("http://${host.trim()}:8001/api/v2/")?.let { runCatching { JSONObject(it).optJSONObject("device") }.getOrNull() }

    /**
     * On and showing a picture. Newer Samsungs keep answering on the network in
     * standby, but say so in PowerState.
     */
    fun samsungAwake(host: String): Boolean {
        val d = samsungInfo(host) ?: return false
        val power = d.optString("PowerState")
        return power.isBlank() || power.equals("on", ignoreCase = true)
    }

    private fun rokuName(host: String): String? {
        val xml = get("http://$host:8060/query/device-info") ?: return null
        fun tag(t: String) = Regex("<$t>([^<]*)</$t>").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return tag("user-device-name") ?: tag("friendly-model-name") ?: tag("model-name") ?: "Roku"
    }

    private fun samsungName(host: String): String? = samsungInfo(host)?.let { d ->
        d.optString("name").ifBlank { d.optString("modelName") }.ifBlank { "Samsung TV" }
    }

    private fun verify(host: String, saidRoku: Boolean): FoundTv? =
        if (saidRoku) rokuName(host)?.let { FoundTv(TvType.ROKU, host, it) }
        else samsungName(host)?.let { FoundTv(TvType.SAMSUNG, host, it) }
            ?: rokuName(host)?.let { FoundTv(TvType.ROKU, host, it) }

    /** The phone's IPv4 Wi-Fi/Ethernet addresses with their subnet prefix length. */
    private fun localNets(): List<Pair<Inet4Address, Int>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
            .flatMap { ni -> ni.interfaceAddresses.mapNotNull { a ->
                (a.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.let { it to a.networkPrefixLength.toInt() }
            } }
    }.getOrDefault(emptyList())

    private fun Inet4Address.asInt(): Int = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
    private fun intToAddr(v: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    private fun portOpen(host: String, port: Int, timeoutMs: Int) = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    /**
     * Ask every device on the Wi-Fi "are you a TV?" (SSDP), then confirm each
     * answer by talking to it. If nobody answers, knock on every address on the
     * home network instead. Takes about 3-6 seconds.
     */
    fun scan(context: Context, waitMs: Int = 3000): List<FoundTv> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("mvswitch:scan").apply { setReferenceCounted(false); acquire() }
        val hosts = LinkedHashMap<String, Boolean>() // host -> said it's a Roku
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("roku:ecp", "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:dial-multiscreen-org:service:dial:1")) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = msg.toByteArray()
                    repeat(2) { sock.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
                }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length)
                    val host = p.address.hostAddress ?: continue
                    val roku = text.contains("roku", ignoreCase = true)
                    hosts[host] = (hosts[host] ?: false) || roku
                }
            }
        } catch (e: Exception) {
            // Network not available: go with what we have.
        } finally {
            lock.release()
        }

        val pool = Executors.newFixedThreadPool(24)
        try {
            val found = hosts.map { (h, roku) -> pool.submit(Callable { verify(h, roku) }) }
                .mapNotNull { runCatching { it.get(6, TimeUnit.SECONDS) }.getOrNull() }
            if (found.isNotEmpty()) return found.distinctBy { it.host }

            // Nobody answered SSDP (some TVs and routers block it): try every address nearby.
            val (me, prefix) = localNets().firstOrNull { it.second in 22..30 } ?: return emptyList()
            val mask = -1 shl (32 - prefix)
            val base = me.asInt() and mask
            val count = (1 shl (32 - prefix)) - 2
            val candidates = (1..count).map { intToAddr(base + it).hostAddress!! }.filter { it != me.hostAddress }
            return candidates.map { h ->
                pool.submit(Callable {
                    when {
                        portOpen(h, 8001, 350) -> samsungName(h)?.let { FoundTv(TvType.SAMSUNG, h, it) }
                        portOpen(h, 8060, 350) -> rokuName(h)?.let { FoundTv(TvType.ROKU, h, it) }
                        else -> null
                    }
                })
            }.mapNotNull { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Wake-on-LAN magic packet. Needs "Power On with Mobile" (or similar) on the TV.
     * Sent to every broadcast address the phone knows plus the TV itself, since
     * some routers drop one kind or the other.
     */
    fun wake(mac: String, host: String? = null) {
        val hex = mac.replace(":", "").replace("-", "").trim()
        if (hex.length != 12 || hex.any { it.digitToIntOrNull(16) == null }) return
        val macBytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (r in 0 until 16) macBytes.copyInto(packet, 6 + r * 6)
        val targets = LinkedHashSet<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach { targets += it }
        }
        host?.takeIf { it.isNotBlank() }?.let { h -> runCatching { targets += InetAddress.getByName(h.trim()) } }
        DatagramSocket().use { s ->
            s.broadcast = true
            for (addr in targets) for (port in listOf(9, 7)) repeat(3) {
                runCatching { s.send(DatagramPacket(packet, packet.size, addr, port)) }
            }
        }
    }

    /** "aa:bb:cc:dd:ee:ff" if [text] looks like a MAC address, else null. */
    fun normalizeMac(text: String): String? {
        val hex = text.filter { it.isLetterOrDigit() }.lowercase()
        if (hex.length != 12 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return hex.chunked(2).joinToString(":")
    }
}
