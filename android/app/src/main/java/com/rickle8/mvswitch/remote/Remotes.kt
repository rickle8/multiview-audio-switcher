package com.rickle8.mvswitch.remote

import android.util.Base64
import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.core.Remote
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RemoteException(msg: String) : Exception(msg)

data class TvApp(val id: String, val name: String)

/** Opening apps on the TV and checking what's on screen. */
interface TvApps {
    /** Every app installed on the TV. */
    fun listApps(): List<TvApp>

    /** App id for the first installed app whose name contains [name], or null. */
    fun findApp(name: String): String? =
        listApps().firstOrNull { it.name.lowercase().contains(name.lowercase()) }?.id

    fun launch(appId: String)
    /** True/false if the TV can tell us; null if this TV doesn't say. */
    fun isOnScreen(appId: String): Boolean?
}

/** Extras beyond buttons: HDMI inputs, typing text, app icons. */
interface TvExtras {
    /** Switch to HDMI input [n] (1-4). */
    fun input(n: Int)
    /** Type text into whatever search box is open on the TV. */
    fun sendText(text: String)
    /** App icon image bytes, if the TV provides them. */
    fun icon(appId: String): ByteArray? = null
}

/**
 * Samsung Tizen TV over the local network (YouTube TV app running on the TV).
 * First connection shows an "Allow" prompt on the TV; the token it returns is saved
 * so it never asks again.
 */
class SamsungRemote(
    private val host: String,
    private var token: String?,
    private val saveToken: (String) -> Unit,
) : Remote, TvApps, TvExtras {
    private val client: OkHttpClient by lazy {
        // The TV uses a self-signed certificate. Only ever used for this LAN address.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { h, _ -> h == host }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var failure: String? = null

    private val keyNames = mapOf(
        Key.LEFT to "KEY_LEFT", Key.RIGHT to "KEY_RIGHT", Key.UP to "KEY_UP",
        Key.DOWN to "KEY_DOWN", Key.SELECT to "KEY_ENTER", Key.BACK to "KEY_RETURN",
        Key.HOME to "KEY_HOME", Key.PLAY to "KEY_PLAY", Key.PAUSE to "KEY_PAUSE",
        Key.VOL_UP to "KEY_VOLUP", Key.VOL_DOWN to "KEY_VOLDOWN", Key.MUTE to "KEY_MUTE",
        Key.POWER to "KEY_POWER", Key.CH_UP to "KEY_CHUP", Key.CH_DOWN to "KEY_CHDOWN",
        Key.REWIND to "KEY_REWIND", Key.FAST_FORWARD to "KEY_FF", Key.MENU to "KEY_MENU",
        Key.SOURCE to "KEY_SOURCE", Key.INFO to "KEY_INFO",
    )
    @Volatile private var appsReply: java.util.concurrent.CompletableFuture<JSONObject>? = null
    private val http by lazy { OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build() }

    @Synchronized
    private fun connect() {
        if (ws != null && ready) return
        ws?.cancel()
        ready = false
        failure = null
        val name = Base64.encodeToString("Multiview Audio".toByteArray(), Base64.NO_WRAP)
        val tokenPart = token?.let { "&token=$it" } ?: ""
        val url = "wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name$tokenPart"
        val latch = CountDownLatch(1)
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("event")) {
                    "ms.channel.connect" -> {
                        msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                            if (it != token) { token = it; saveToken(it) }
                        }
                        ready = true
                        latch.countDown()
                    }
                    "ed.installedApp.get" -> appsReply?.complete(msg)
                    "ms.channel.unauthorized" -> {
                        failure = "TV said no. Accept the prompt on the TV, or allow the app under " +
                            "TV Settings > General > External Device Manager > Device Connection Manager."
                        latch.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                ws = null
                failure = "Can't reach the TV at $host (${t.message}). Is it on and on the same Wi-Fi?"
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                ws = null
            }
        })
        // First time, the TV waits for you to press Allow.
        if (!latch.await(30, TimeUnit.SECONDS)) failure = "TV didn't answer. Check for an Allow prompt on the TV."
        failure?.let { ws?.cancel(); ws = null; throw RemoteException(it) }
    }

    override fun press(key: Key) = pressRaw(keyNames.getValue(key))

    private fun pressRaw(name: String) = sendControl(JSONObject()
        .put("Cmd", "Click").put("DataOfCmd", name).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))

    private fun sendControl(params: JSONObject) {
        connect()
        val cmd = JSONObject().put("method", "ms.remote.control").put("params", params).toString()
        if (ws?.send(cmd) != true) {
            ready = false
            connect()
            ws?.send(cmd)
        }
    }

    override fun input(n: Int) = pressRaw("KEY_HDMI${n.coerceIn(1, 4)}")

    override fun sendText(text: String) = sendControl(JSONObject()
        .put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
        .put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString"))

    fun close() {
        ws?.close(1000, null)
        ws = null
    }

    private fun emit(event: String, data: JSONObject? = null) {
        connect()
        val params = JSONObject().put("event", event).put("to", "host")
        if (data != null) params.put("data", data)
        ws?.send(JSONObject().put("method", "ms.channel.emit").put("params", params).toString())
    }

    override fun listApps(): List<TvApp> {
        val reply = java.util.concurrent.CompletableFuture<JSONObject>()
        appsReply = reply
        emit("ed.installedApp.get")
        val msg = runCatching { reply.get(6, TimeUnit.SECONDS) }.getOrNull() ?: return emptyList()
        val list = msg.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val a = list.optJSONObject(i) ?: return@mapNotNull null
            val id = a.optString("appId").ifBlank { null } ?: return@mapNotNull null
            TvApp(id, a.optString("name").ifBlank { id })
        }.sortedBy { it.name.lowercase() }
    }

    override fun launch(appId: String) {
        emit("ed.apps.launch", JSONObject().put("appId", appId).put("action_type", "DEEP_LINK"))
    }

    override fun isOnScreen(appId: String): Boolean? = try {
        http.newCall(Request.Builder().url("http://$host:8001/api/v2/applications/$appId").build()).execute().use {
            if (!it.isSuccessful) null
            else JSONObject(it.body!!.string()).let { j -> if (j.has("visible")) j.optBoolean("visible") else null }
        }
    } catch (e: java.io.IOException) {
        false // TV off or unreachable: YouTube TV certainly isn't on screen
    }
}

/** Roku via External Control Protocol (Settings > System > Advanced > Control by mobile apps). */
class RokuRemote(private val host: String) : Remote, TvApps, TvExtras {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    private val keyNames = mapOf(
        Key.LEFT to "Left", Key.RIGHT to "Right", Key.UP to "Up", Key.DOWN to "Down", Key.SELECT to "Select",
        Key.BACK to "Back", Key.HOME to "Home", Key.PLAY to "Play", Key.PAUSE to "Play",
        Key.VOL_UP to "VolumeUp", Key.VOL_DOWN to "VolumeDown", Key.MUTE to "VolumeMute",
        Key.POWER to "Power", Key.CH_UP to "ChannelUp", Key.CH_DOWN to "ChannelDown",
        Key.REWIND to "Rev", Key.FAST_FORWARD to "Fwd", Key.MENU to "Info", Key.INFO to "Info",
        Key.SOURCE to "Home",
    )

    private fun get(path: String): String? = try {
        client.newCall(Request.Builder().url("http://$host:8060$path").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (e: java.io.IOException) {
        null
    }

    override fun listApps(): List<TvApp> {
        val xml = get("/query/apps") ?: return emptyList()
        return Regex("<app id=\"([^\"]+)\"[^>]*>([^<]*)</app>").findAll(xml)
            .map { TvApp(it.groupValues[1], it.groupValues[2].replace("&amp;", "&").trim()) }
            .toList().sortedBy { it.name.lowercase() }
    }

    override fun input(n: Int) = pressRaw("InputHDMI${n.coerceIn(1, 4)}")

    override fun sendText(text: String) {
        for (ch in text) pressRaw("Lit_" + java.net.URLEncoder.encode(ch.toString(), "UTF-8"))
    }

    override fun icon(appId: String): ByteArray? = try {
        client.newCall(Request.Builder().url("http://$host:8060/query/icon/$appId").build()).execute().use {
            if (it.isSuccessful) it.body?.bytes() else null
        }
    } catch (e: java.io.IOException) {
        null
    }

    override fun launch(appId: String) {
        val req = Request.Builder().url("http://$host:8060/launch/$appId")
            .post(ByteArray(0).toRequestBody("text/plain".toMediaType())).build()
        runCatching { client.newCall(req).execute().close() }
    }

    /** Roku TVs (not sticks) can be turned on over the network. */
    fun powerOn() {
        val req = Request.Builder().url("http://$host:8060/keypress/PowerOn")
            .post(ByteArray(0).toRequestBody("text/plain".toMediaType())).build()
        runCatching { client.newCall(req).execute().close() }
    }

    fun awake(): Boolean = get("/query/device-info")?.contains("<power-mode>PowerOn</power-mode>") ?: false

    override fun isOnScreen(appId: String): Boolean? {
        val xml = get("/query/active-app") ?: return false
        return Regex("<app id=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) == appId
    }

    override fun press(key: Key) = pressRaw(keyNames.getValue(key))

    private fun pressRaw(name: String) {
        val req = Request.Builder()
            .url("http://$host:8060/keypress/$name")
            .post(ByteArray(0).toRequestBody("text/plain".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use {
                if (!it.isSuccessful) throw RemoteException("Roku said ${it.code}. Turn on 'Control by mobile apps'.")
            }
        } catch (e: java.io.IOException) {
            throw RemoteException("Can't reach the Roku at $host (${e.message})")
        }
    }
}

/** Presses nothing. For trying detection without touching the TV. */
class TestRemote : Remote {
    override fun press(key: Key) {}
}
