package com.rickle8.mvswitch.remote

class RemoteException(msg: String) : Exception(msg)

/** An app installed on the TV. [iconPath] is where a Samsung keeps its icon. */
data class TvApp(val id: String, val name: String, val iconPath: String? = null)

/** Opening apps on the TV and checking what's on screen. */
interface TvApps {
    /** Every app installed on the TV. */
    fun listApps(): List<TvApp>

    /** App id for the installed app called [name] (exact match first, then partial), or null. */
    fun findApp(name: String): String? {
        val apps = listApps()
        return (apps.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.name.lowercase().contains(name.lowercase()) })?.id
    }

    fun launch(appId: String)
    /** True/false if the TV can tell us; null if this TV doesn't say. */
    fun isOnScreen(appId: String): Boolean?
}

/** Extras beyond buttons: HDMI inputs, typing text, app icons. */
interface TvExtras {
    /** Switch straight to HDMI input [n] (1-4), if the TV supports it. */
    fun input(n: Int)
    /** Type text into whatever search box is open on the TV. */
    fun sendText(text: String)
    /** App icon image bytes, if the TV provides them. */
    fun icon(appId: String): ByteArray? = null
}

/*
 * The small slice of networking the TV code needs. The app uses OkHttp (Net.kt);
 * the tests use a fake TV, so the protocol logic can be checked without a TV.
 */

/** One open websocket. */
interface Ws {
    /** False if the socket is already closing or broken. */
    fun send(text: String): Boolean
    /** Polite close. */
    fun close()
    /** Drop it now (it's probably dead anyway). */
    fun cancel()
    /** Pings the other side has sent us so far. Samsung TVs ping every few seconds but never answer ours. */
    fun pingsReceived(): Int
}

/** What a websocket reports back. Called on a background thread. */
interface WsEvents {
    fun onText(text: String)
    /** Any other sign of life (a binary frame). */
    fun onActivity()
    /** The other side closed it, or it finished closing. */
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: String)
}

fun interface WsOpener {
    fun open(url: String, events: WsEvents): Ws
}

data class HttpReply(val code: Int, val body: String)

/** Plain HTTP to the TV. Null means it couldn't be reached at all. */
interface TvHttp {
    fun get(url: String): HttpReply?
    fun post(url: String): HttpReply?
}
