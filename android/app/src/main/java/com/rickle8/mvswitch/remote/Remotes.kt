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

/**
 * Samsung Tizen TV over the local network (YouTube TV app running on the TV).
 * First connection shows an "Allow" prompt on the TV; the token it returns is saved
 * so it never asks again.
 */
class SamsungRemote(
    private val host: String,
    private var token: String?,
    private val saveToken: (String) -> Unit,
) : Remote {
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
        Key.DOWN to "KEY_DOWN", Key.SELECT to "KEY_ENTER",
    )

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

    override fun press(key: Key) {
        connect()
        val cmd = JSONObject()
            .put("method", "ms.remote.control")
            .put("params", JSONObject()
                .put("Cmd", "Click")
                .put("DataOfCmd", keyNames.getValue(key))
                .put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey"))
        if (ws?.send(cmd.toString()) != true) {
            ready = false
            connect()
            ws?.send(cmd.toString())
        }
    }

    fun close() {
        ws?.close(1000, null)
        ws = null
    }
}

/** Roku via External Control Protocol (Settings > System > Advanced > Control by mobile apps). */
class RokuRemote(private val host: String) : Remote {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    private val keyNames = mapOf(
        Key.LEFT to "Left", Key.RIGHT to "Right", Key.UP to "Up", Key.DOWN to "Down", Key.SELECT to "Select",
    )

    override fun press(key: Key) {
        val req = Request.Builder()
            .url("http://$host:8060/keypress/${keyNames.getValue(key)}")
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
