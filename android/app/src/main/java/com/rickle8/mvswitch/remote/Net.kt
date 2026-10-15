package com.rickle8.mvswitch.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.internal.ws.RealWebSocket
import okio.ByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Websockets to a TV that uses a self-signed certificate (only ever for that one LAN address). */
class OkWsOpener(private val host: String) : WsOpener {
    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { h, _ -> h == host }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // the TV can be quiet for a long time
            // No pingInterval: the TV never answers pings, so OkHttp would kill a healthy socket.
            .build()
    }

    override fun open(url: String, events: WsEvents): Ws {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = events.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.onActivity()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Without this the socket lingers half-closed and every key sent to it vanishes.
                webSocket.close(1000, null)
                events.onClosed(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.onClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                events.onFailure(t.message ?: t.javaClass.simpleName)
        }
        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        return object : Ws {
            override fun send(text: String) = ws.send(text)
            override fun close() { if (!ws.close(1000, null)) ws.cancel() }
            override fun cancel() = ws.cancel()
            override fun pingsReceived(): Int = (ws as? RealWebSocket)?.receivedPingCount() ?: 0
        }
    }
}

/** HTTP to the TV's REST endpoints. */
object OkTvHttp : TvHttp {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).build()

    private fun call(req: Request): HttpReply? = try {
        client.newCall(req).execute().use { HttpReply(it.code, it.body?.string().orEmpty()) }
    } catch (e: Exception) {
        null
    }

    override fun get(url: String) = call(Request.Builder().url(url).build())
    override fun soap(url: String, action: String, body: String) = call(Request.Builder().url(url)
        .header("SOAPAction", action)
        .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build())
    override fun post(url: String) =
        call(Request.Builder().url(url).post(ByteArray(0).toRequestBody("text/plain".toMediaType())).build())
}
