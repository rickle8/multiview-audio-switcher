package com.rickle8.mvswitch.remote

import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.core.Remote
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How long to wait for things. Tests shrink these. */
data class SamsungTiming(
    /** First connection: the TV waits for someone to press Allow. */
    val firstPairMs: Long = 30_000,
    /** Already paired: the TV normally answers in well under a second. */
    val connectMs: Long = 8_000,
    /** Heard nothing from the TV for this long: check it's still there before trusting the socket. */
    val staleMs: Long = 9_000,
    val probeMs: Long = 2_500,
    val replyMs: Long = 4_000,
    /** Tizen drops keys that arrive too close together. */
    val keyGapMs: Long = 120,
    /** After a failed connect, fail later presses right away for this long instead of stacking up waits. */
    val failFastMs: Long = 2_500,
    /** How long an unanswered connection (an Allow prompt on the TV) is kept open. */
    val pendingMs: Long = 60_000,
    val tickMs: Long = 1_000,
)

/**
 * Samsung Tizen TV over the local network (2016 and newer).
 *
 * Keys, apps and text go over the TV's "remote control" websocket. The first
 * connection shows an Allow prompt on the TV; the token it hands back is saved
 * so it never asks again.
 *
 * The socket is never trusted blindly: the TV pings every few seconds, so a
 * socket that has gone quiet is checked (and replaced if it's dead) before a
 * key is sent. A TV that closes the connection, sleeps, or drops off Wi-Fi
 * can't make keys silently disappear.
 */
class SamsungRemote(
    private val host: String,
    token: String?,
    private val saveToken: (String) -> Unit,
    private val opener: WsOpener,
    private val http: TvHttp,
    private val log: (String) -> Unit = {},
    private val t: SamsungTiming = SamsungTiming(),
    private val port: Int = 8002,
    private val scheme: String = "wss",
) : Remote, TvApps, TvExtras {

    companion object {
        const val NAME = "Multiview Audio"
        private val KEYS = mapOf(
            Key.LEFT to "KEY_LEFT", Key.RIGHT to "KEY_RIGHT", Key.UP to "KEY_UP",
            Key.DOWN to "KEY_DOWN", Key.SELECT to "KEY_ENTER", Key.BACK to "KEY_RETURN",
            Key.HOME to "KEY_HOME", Key.PLAY to "KEY_PLAY", Key.PAUSE to "KEY_PAUSE",
            Key.VOL_UP to "KEY_VOLUP", Key.VOL_DOWN to "KEY_VOLDOWN", Key.MUTE to "KEY_MUTE",
            Key.POWER to "KEY_POWER", Key.CH_UP to "KEY_CHUP", Key.CH_DOWN to "KEY_CHDOWN",
            Key.REWIND to "KEY_REWIND", Key.FAST_FORWARD to "KEY_FF", Key.MENU to "KEY_MENU",
            Key.SOURCE to "KEY_SOURCE", Key.INFO to "KEY_INFO",
        )
        const val DENIED = "The TV blocked this app. On the TV go to Settings > General > External Device Manager > " +
            "Device Connection Manager > Device List, set Multiview Audio to Allow (or delete it and try again)."
        const val PROMPT_TIMED_OUT = "The Allow prompt on the TV timed out. Try again and press Allow on the TV."
        const val NO_ANSWER_FIRST = "Waiting for the TV. Press Allow on the TV, then try again."
        const val NO_ANSWER = "The TV didn't answer. If it's showing an Allow prompt, press Allow, then try again."
        private val TICKER = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tv-ticker").apply { isDaemon = true }
        }

        private fun now() = System.nanoTime() / 1_000_000
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

        fun keyJson(code: String): String = JSONObject().put("method", "ms.remote.control").put(
            "params", JSONObject().put("Cmd", "Click").put("DataOfCmd", code).put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey"),
        ).toString()

        fun emitJson(event: String, data: Any? = null, to: String = "host"): String {
            val params = JSONObject().put("event", event).put("to", to)
            if (data != null) params.put("data", data)
            return JSONObject().put("method", "ms.channel.emit").put("params", params).toString()
        }

        private val APPS_REQUEST = emitJson("ed.installedApp.get")
        private fun isEvent(msg: JSONObject, name: String) = msg.optString("event") == name
    }

    @Volatile private var token: String? = token?.takeIf { it.isNotBlank() }
    /** The TV's on-screen keyboard opened (true) or closed (false). */
    @Volatile var onKeyboard: ((Boolean) -> Unit)? = null
    /** What it's waiting on while connecting ("Press Allow on the TV"), then null. */
    @Volatile var onWaiting: ((String?) -> Unit)? = null

    private val sendLock = ReentrantLock(true) // fair: presses go out in the order they came in
    private val controlLock = ReentrantLock()
    private val iconLock = ReentrantLock()
    @Volatile private var lastSend = 0L
    @Volatile private var failedAt = Long.MIN_VALUE / 2
    @Volatile private var failedWhy: String? = null
    @Volatile private var closed = false
    @Volatile private var iconMisses = 0
    /** Whether this TV answers app launches (most do; null = not known yet). */
    @Volatile private var confirmsLaunch: Boolean? = null
    @Volatile private var apps: List<TvApp>? = null
    private val appTypes = ConcurrentHashMap<String, Int>()

    private val remote = Channel("/api/v2/channels/samsung.remote.control", useToken = true)
    private val control = Channel("/api/v2", useToken = false)
    private val ticking: ScheduledFuture<*> = TICKER.scheduleWithFixedDelay(
        { runCatching { remote.tick(); control.tick() } }, t.tickMs, t.tickMs, TimeUnit.MILLISECONDS,
    )

    /**
     * True while the key connection is up. For a TV that pings, also that it was
     * heard from recently (a TV that never pings is only checked when used).
     */
    val linked: Boolean
        get() {
            remote.tick()
            val c = remote.conn ?: return false
            if (!c.ready || c.dead) return false
            return c.pings == 0 || now() - c.lastHeard < t.staleMs
        }

    // ------------------------------------------------------------------ connections
    private class Waiter(val match: (JSONObject) -> Boolean) {
        val reply = CompletableFuture<JSONObject>()
    }

    /** One connection attempt. Events from an old socket only ever touch their own Conn. */
    private inner class Conn(val ch: Channel) : WsEvents {
        val started = now()
        @Volatile var ws: Ws? = null
        @Volatile var ready = false
        @Volatile var dead = false
        @Volatile var failure: String? = null
        @Volatile var lastHeard = now()
        @Volatile var pings = 0
        val hello = CountDownLatch(1)
        val waiters = CopyOnWriteArrayList<Waiter>()

        override fun onText(text: String) {
            lastHeard = now()
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            ch.handle(this, msg)
            for (w in waiters) if (runCatching { w.match(msg) }.getOrDefault(false)) w.reply.complete(msg)
        }

        override fun onActivity() { lastHeard = now() }

        override fun onClosed(code: Int, reason: String) =
            die("The TV closed the connection" + if (reason.isNotBlank()) " ($reason)" else "")

        override fun onFailure(error: String) = die("Can't reach the TV at $host ($error). Is it on and on the same Wi-Fi?")

        fun die(why: String) {
            if (dead) return
            dead = true
            if (failure == null) failure = why
            if (ready && ch.conn === this) log("${ch.label} connection lost: $why")
            hello.countDown()
            for (w in waiters) w.reply.completeExceptionally(RemoteException(why))
        }
    }

    private inner class Channel(private val path: String, private val useToken: Boolean) {
        @Volatile var conn: Conn? = null
        val label = if (useToken) "Remote" else "App-control"

        fun url(): String {
            val tok = if (useToken) token?.let { "&token=" + enc(it) }.orEmpty() else ""
            return "$scheme://$host:$port$path?name=${enc(b64(NAME))}$tok"
        }

        val live: Boolean get() = conn?.let { it.ready && !it.dead } == true

        fun heardWithin(ms: Long): Boolean {
            tick()
            val c = conn ?: return false
            return now() - c.lastHeard < ms
        }

        /** Notice the TV's pings as they arrive (they don't come through as messages). */
        fun tick() {
            val c = conn ?: return
            if (c.dead) return
            val p = runCatching { c.ws?.pingsReceived() }.getOrNull() ?: return
            if (p != c.pings) { c.pings = p; c.lastHeard = now() }
        }

        fun handle(c: Conn, msg: JSONObject) {
            when (msg.optString("event")) {
                "ms.channel.connect" -> {
                    if (useToken) msg.optJSONObject("data")?.opt("token")?.toString()
                        ?.takeIf { it.isNotBlank() && it != "null" }?.let { tok ->
                            if (tok != token) { token = tok; runCatching { saveToken(tok) } }
                        }
                    c.ready = true
                    c.hello.countDown()
                }
                "ms.channel.unauthorized" -> { c.failure = DENIED; c.die(DENIED) }
                "ms.channel.timeOut" -> { c.failure = PROMPT_TIMED_OUT; c.die(PROMPT_TIMED_OUT) }
                "ms.remote.imeStart" -> runCatching { onKeyboard?.invoke(true) }
                "ms.remote.imeEnd" -> runCatching { onKeyboard?.invoke(false) }
                "ed.installedApp.get" -> parseApps(msg)
                "ms.error" -> log("TV said: " + (msg.optJSONObject("data")?.optString("message") ?: msg.toString()))
            }
        }

        /** Usable now, connecting (and waiting for the TV's hello) if needed. */
        fun ensure(waitMs: Long) {
            var c = conn
            if (c != null && c.ready && !c.dead) return
            if (c == null || c.dead || now() - c.started > t.pendingMs) {
                c?.let { drop(it) }
                c = Conn(this)
                conn = c
                c.ws = try {
                    opener.open(url(), c)
                } catch (e: Exception) {
                    drop(c)
                    throw RemoteException("That TV address doesn't look right ($host). Check it in Settings.")
                }
            }
            if (useToken) runCatching {
                onWaiting?.invoke(if (token == null) "Look at the TV and press Allow" else "Connecting to the TV…")
            }
            try {
                c.hello.await(waitMs, TimeUnit.MILLISECONDS)
            } finally {
                if (useToken) runCatching { onWaiting?.invoke(null) }
            }
            if (c.ready && !c.dead) {
                log("$label connected")
                return
            }
            if (c.dead) {
                drop(c)
                throw RemoteException(c.failure ?: "Lost the connection to the TV")
            }
            // No answer yet. Usually that's an Allow prompt on the TV: keep this connection
            // waiting so pressing Allow still counts, and use it as soon as the TV says hello.
            throw RemoteException(if (useToken && token == null) NO_ANSWER_FIRST else NO_ANSWER)
        }

        fun drop(c: Conn) {
            if (conn === c) conn = null
            c.die("Replaced")
            runCatching { c.ws?.cancel() }
        }

        fun reset() { conn?.let { drop(it) } }

        fun close() {
            val c = conn ?: return
            conn = null
            c.die("Closed")
            runCatching { c.ws?.close() }
        }

        fun send(text: String): Boolean {
            val c = conn ?: return false
            if (!c.ready || c.dead) return false
            return c.ws?.send(text) == true
        }

        /** Sends [text] and waits for the first message that [match]es. Null on timeout or a lost connection. */
        fun request(text: String, timeoutMs: Long, match: (JSONObject) -> Boolean): JSONObject? {
            val w = register(match) ?: return null
            if (!send(text)) { unregister(w); return null }
            return await(w, timeoutMs)
        }

        fun register(match: (JSONObject) -> Boolean): Waiter? {
            val c = conn?.takeIf { it.ready && !it.dead } ?: return null
            return Waiter(match).also { c.waiters += it }
        }

        fun unregister(w: Waiter) { conn?.waiters?.remove(w) }

        fun await(w: Waiter, timeoutMs: Long): JSONObject? = try {
            w.reply.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); null
        } catch (e: Exception) {
            null
        } finally {
            unregister(w)
        }
    }

    /** The key connection, verified alive. Must hold [sendLock]. */
    private fun ensureRemote() {
        if (closed) throw RemoteException("Remote closed")
        if (!remote.live && now() - failedAt < t.failFastMs) throw RemoteException(failedWhy ?: "Can't reach the TV")
        try {
            if (remote.live) {
                if (remote.heardWithin(t.staleMs)) return
                // Quiet for a while. Ask the TV something to be sure it's still listening.
                if (remote.request(APPS_REQUEST, t.probeMs) { isEvent(it, "ed.installedApp.get") } != null) return
                log("The TV stopped answering. Reconnecting.")
                remote.reset()
            }
            remote.ensure(if (token == null) t.firstPairMs else t.connectMs)
            failedWhy = null
        } catch (e: RemoteException) {
            failedAt = now()
            failedWhy = e.message
            throw e
        }
    }

    /** Makes sure the TV is connected (connecting if needed). Throws with a readable reason if not. */
    fun check() = sendLock.withLock { ensureRemote() }

    private fun sendRemote(text: String) = sendLock.withLock {
        ensureRemote()
        val wait = t.keyGapMs - (now() - lastSend)
        if (wait > 0) Thread.sleep(wait)
        if (!remote.send(text)) {
            // It died between the check and the send: one fresh connection, one more try.
            remote.reset()
            ensureRemote()
            if (!remote.send(text)) throw RemoteException("Couldn't send to the TV")
        }
        lastSend = now()
    }

    /** Sends and waits for a reply, without holding up other presses while waiting. */
    private fun requestRemote(text: String, timeoutMs: Long, match: (JSONObject) -> Boolean): JSONObject? {
        val w = sendLock.withLock {
            ensureRemote()
            val w = remote.register(match) ?: return null
            if (!remote.send(text)) { remote.unregister(w); return null }
            lastSend = now()
            w
        }
        return remote.await(w, timeoutMs)
    }

    fun close() {
        closed = true
        ticking.cancel(false)
        remote.close()
        control.close()
    }

    // ------------------------------------------------------------------ keys and text
    override fun press(key: Key) = pressRaw(KEYS.getValue(key))

    fun pressRaw(code: String) = sendRemote(keyJson(code))

    /** Direct input keys. Many Tizen TVs ignore these; see the taught steps in the app. */
    override fun input(n: Int) = pressRaw("KEY_HDMI${n.coerceIn(1, 4)}")

    /** Next HDMI input (works on every Samsung). */
    fun nextInput() = pressRaw("KEY_HDMI")

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        // Some TVs only accept typed text after this broadcast.
        sendRemote(emitJson("custom.remote.textReceived", to = "broadcast"))
        sendRemote(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", b64(text)).put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")).toString())
        sendRemote(JSONObject().put("method", "ms.remote.control")
            .put("params", JSONObject().put("TypeOfRemote", "SendInputEnd")).toString())
    }

    // ------------------------------------------------------------------ apps
    private fun parseApps(msg: JSONObject): List<TvApp>? {
        val arr = msg.optJSONObject("data")?.optJSONArray("data") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = a.optString("appId").ifBlank { null } ?: return@mapNotNull null
            appTypes[id] = a.optInt("app_type", 2)
            TvApp(id, a.optString("name").ifBlank { id }, a.optString("icon").ifBlank { null })
        }.sortedBy { it.name.lowercase() }
        apps = list
        return list
    }

    override fun listApps(): List<TvApp> {
        val reply = requestRemote(APPS_REQUEST, t.replyMs * 2) { isEvent(it, "ed.installedApp.get") }
        return reply?.let { parseApps(it) } ?: apps.orEmpty()
    }

    /** True = opened, false = the TV said no, null = the TV didn't say. */
    private fun launchReply(reply: JSONObject?): Boolean? {
        reply ?: return null
        if (isEvent(reply, "ms.error")) return false
        val code = when (val d = reply.opt("data")) {
            is Number -> d.toInt()
            is String -> d.toIntOrNull()
            else -> null
        }
        return code == null || code == 200
    }

    /** Old-style REST launch. True/false, or null if the TV couldn't be reached. */
    private fun restLaunch(appId: String): Boolean? =
        http.post("http://$host:8001/api/v2/applications/$appId")?.let { it.code in 200..299 }

    override fun launch(appId: String) {
        if (!appTypes.containsKey(appId)) runCatching { listApps() }
        // Web apps (type 2) open with DEEP_LINK, native ones with NATIVE_LAUNCH. Try the right one first.
        val first = if ((appTypes[appId] ?: 2) == 2) "DEEP_LINK" else "NATIVE_LAUNCH"
        val second = if (first == "DEEP_LINK") "NATIVE_LAUNCH" else "DEEP_LINK"
        for (action in listOf(first, second)) {
            val data = JSONObject().put("appId", appId).put("action_type", action).put("metaTag", "")
            val msg = emitJson("ed.apps.launch", data)
            if (confirmsLaunch == false) {
                // This TV never says whether it worked: send it, nudge over REST too (harmless if open).
                sendRemote(msg)
                restLaunch(appId)
                return
            }
            val reply = requestRemote(msg, t.replyMs) { isEvent(it, "ed.apps.launch") || isEvent(it, "ms.error") }
            when (launchReply(reply)) {
                true -> { confirmsLaunch = true; return }
                false -> { confirmsLaunch = true; log("The TV wouldn't open $appId as $action") }
                null -> {
                    if (confirmsLaunch == null) confirmsLaunch = false
                    restLaunch(appId)
                    return
                }
            }
        }
        if (restLaunch(appId) == true) return
        throw RemoteException("The TV wouldn't open that app")
    }

    override fun isOnScreen(appId: String): Boolean? {
        val rest = http.get("http://$host:8001/api/v2/applications/$appId")
            ?: return false // TV off or unreachable: the app certainly isn't on screen
        if (rest.code in 200..299) {
            val j = runCatching { JSONObject(rest.body) }.getOrNull()
            if (j != null && j.has("visible")) return j.optBoolean("visible")
        }
        // Newer TVs only answer this on the app-control channel.
        return runCatching { controlVisible(appId) }.getOrNull()
    }

    private fun controlVisible(appId: String): Boolean? {
        controlLock.withLock {
            if (closed) return null
            if (appTypes[appId] == 4) return null // the TV never reports these
            if (!control.live) control.ensure(t.connectMs)
            val req = JSONObject().put("id", appId).put("method", "ms.application.get")
                .put("params", JSONObject().put("id", appId)).toString()
            val reply = control.request(req, t.replyMs) {
                it.optString("id") == appId && (it.has("result") || it.has("error"))
            } ?: return null
            val r = reply.optJSONObject("result") ?: return null
            return if (r.has("visible")) r.optBoolean("visible") else null
        }
    }

    override fun icon(appId: String): ByteArray? {
        if (iconMisses >= 2) return null // this TV doesn't hand out icons
        val path = apps?.firstOrNull { it.id == appId }?.iconPath ?: return null
        val reply = iconLock.withLock {
            requestRemote(emitJson("ed.apps.icon", JSONObject().put("iconPath", path)), t.replyMs) {
                isEvent(it, "ed.apps.icon")
            }
        }
        if (reply == null) { iconMisses++; return null }
        val data = reply.optJSONObject("data")?.optString("imageBase64")?.substringAfter("base64,")
            ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
    }
}
