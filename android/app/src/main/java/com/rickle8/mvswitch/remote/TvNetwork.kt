package com.rickle8.mvswitch.remote

import android.content.Context
import android.net.wifi.WifiManager
import com.rickle8.mvswitch.data.TvType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
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

    /** Samsung's info endpoint: name, model and Wi-Fi MAC. Null if not reachable. */
    fun samsungInfo(host: String): JSONObject? =
        get("http://$host:8001/api/v2/")?.let { runCatching { JSONObject(it).optJSONObject("device") }.getOrNull() }

    fun samsungAwake(host: String): Boolean = samsungInfo(host) != null

    private fun rokuName(host: String): String? {
        val xml = get("http://$host:8060/query/device-info") ?: return null
        fun tag(t: String) = Regex("<$t>([^<]*)</$t>").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return tag("user-device-name") ?: tag("friendly-model-name") ?: tag("model-name") ?: "Roku"
    }

    /**
     * Ask every device on the Wi-Fi "are you a TV?" (SSDP), then confirm each
     * answer by talking to it. Takes about 3 seconds.
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
            // Network not available: return what we have.
        } finally {
            lock.release()
        }
        return hosts.mapNotNull { (host, roku) ->
            if (roku) rokuName(host)?.let { FoundTv(TvType.ROKU, host, it) }
            else samsungInfo(host)?.let { d ->
                val name = d.optString("name").ifBlank { d.optString("modelName") }.ifBlank { "Samsung TV" }
                FoundTv(TvType.SAMSUNG, host, name)
            }
        }
    }

    /** Wake-on-LAN magic packet. Needs "Power On with Mobile" (or similar) enabled on the TV. */
    fun wake(mac: String) {
        val hex = mac.replace(":", "").replace("-", "")
        if (hex.length != 12) return
        val macBytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (r in 0 until 16) macBytes.copyInto(packet, 6 + r * 6)
        DatagramSocket().use { s ->
            s.broadcast = true
            val addr = InetAddress.getByName("255.255.255.255")
            for (port in listOf(9, 7)) repeat(3) { s.send(DatagramPacket(packet, packet.size, addr, port)) }
        }
    }
}
