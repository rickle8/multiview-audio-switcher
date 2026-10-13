package com.rickle8.mvswitch.remote

import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.core.Remote
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Roku via External Control Protocol (Settings > System > Advanced > Control by mobile apps). */
class RokuRemote(private val host: String) : Remote, TvApps, TvExtras {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).build()
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

    private fun post(path: String): Int? = try {
        val req = Request.Builder().url("http://$host:8060$path")
            .post(ByteArray(0).toRequestBody("text/plain".toMediaType())).build()
        client.newCall(req).execute().use { it.code }
    } catch (e: java.io.IOException) {
        null
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    override fun listApps(): List<TvApp> {
        val xml = get("/query/apps") ?: return emptyList()
        return Regex("<app id=\"([^\"]+)\"[^>]*>([^<]*)</app>").findAll(xml)
            .map { TvApp(it.groupValues[1], unescape(it.groupValues[2])) }
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
        when (val code = post("/launch/$appId")) {
            null -> throw RemoteException("Can't reach the Roku at $host. Is it on and on the same Wi-Fi?")
            in 200..299 -> Unit
            else -> throw RemoteException("The Roku wouldn't open that app ($code)")
        }
    }

    /** Roku TVs (not sticks) can be turned on over the network. */
    fun powerOn() { post("/keypress/PowerOn") }

    fun awake(): Boolean = get("/query/device-info")?.contains("<power-mode>PowerOn</power-mode>") ?: false

    override fun isOnScreen(appId: String): Boolean? {
        val xml = get("/query/active-app") ?: return false
        return Regex("<app id=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) == appId
    }

    override fun press(key: Key) = pressRaw(keyNames.getValue(key))

    private fun pressRaw(name: String) {
        when (val code = post("/keypress/$name")) {
            null -> throw RemoteException("Can't reach the Roku at $host. Is it on and on the same Wi-Fi?")
            in 200..299 -> Unit
            else -> throw RemoteException("The Roku said $code. Turn on Settings > System > Advanced system " +
                "settings > Control by mobile apps.")
        }
    }
}

/** Presses nothing. For trying detection without touching the TV. */
class TestRemote : Remote {
    override fun press(key: Key) {}
}
