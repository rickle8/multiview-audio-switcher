package com.rickle8.mvswitch.remote

import com.rickle8.mvswitch.core.Key
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A pretend Samsung TV: answers like the real one, and can misbehave on cue. */
class FakeTv : WsOpener, TvHttp {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-tv").apply { isDaemon = true } }
    private fun now() = System.nanoTime() / 1_000_000

    @Volatile var validToken = "11111111"
    /** First pairing: someone presses Allow after this long (null = nobody does). */
    @Volatile var allowAfterMs: Long? = 50
    @Volatile var deny = false
    @Volatile var pingEveryMs: Long? = null
    @Volatile var answersApps = true
    /** (appId, action) -> launch reply data, or null to say nothing. */
    @Volatile var launchReply: (String, String) -> Any? = { _, _ -> 200 }
    @Volatile var restGet: (String) -> HttpReply? = { HttpReply(404, "") }
    @Volatile var restPost: (String) -> HttpReply? = { HttpReply(200, "") }
    @Volatile var visible = true
    @Volatile var failConnect: String? = null
    @Volatile var tokenAsNumber = false
    val posts = CopyOnWriteArrayList<String>()
    val sockets = CopyOnWriteArrayList<Sock>()
    val apps = JSONArray()
        .put(JSONObject().put("appId", "111299001912").put("app_type", 2).put("name", "YouTube").put("icon", "/icons/yt.png"))
        .put(JSONObject().put("appId", "PvWgqxV3Xa.YouTubeTV").put("app_type", 2).put("name", "YouTube TV").put("icon", "/icons/yttv.png"))
        .put(JSONObject().put("appId", "org.tizen.native").put("app_type", 4).put("name", "Native App").put("icon", "/icons/n.png"))

    fun later(ms: Long = 5, block: () -> Unit) { exec.schedule({ runCatching(block) }, ms, TimeUnit.MILLISECONDS) }

    inner class Sock(val url: String, val events: WsEvents) : Ws {
        val received = CopyOnWriteArrayList<String>()
        val opened = now()
        @Volatile var alive = true
        @Volatile var cancelled = false
        @Volatile var frozenPings = 0
        val control = url.contains("/api/v2?")

        /** Keys/messages this socket actually delivered to the TV. */
        fun keys() = received.mapNotNull { runCatching { JSONObject(it).getJSONObject("params").optString("DataOfCmd").ifBlank { null } }.getOrNull() }

        override fun send(text: String): Boolean {
            if (cancelled) return false
            if (!alive) return true // a dead socket happily swallows everything
            received += text
            respond(this, JSONObject(text))
            return true
        }
        override fun close() { cancelled = true }
        override fun cancel() { cancelled = true }
        override fun pingsReceived(): Int {
            val every = pingEveryMs ?: return frozenPings
            return if (alive && !cancelled) ((now() - opened) / every).toInt().also { frozenPings = it } else frozenPings
        }
        fun say(json: JSONObject) { later { if (alive && !cancelled) events.onText(json.toString()) } }

        /** The TV sends a close frame (and then ignores everything). */
        fun tvCloses() { pingsReceived(); alive = false; later { events.onClosed(1000, "") } }
        /** Wi-Fi drop / TV asleep: no close, no pings, nothing. */
        fun goesSilent() { pingsReceived(); alive = false }
    }

    override fun open(url: String, events: WsEvents): Ws {
        val s = Sock(url, events)
        sockets += s
        failConnect?.let { msg -> later { events.onFailure(msg) }; return s }
        val tok = Regex("[?&]token=([^&]+)").find(url)?.groupValues?.get(1)
        when {
            deny && !s.control -> s.say(JSONObject().put("event", "ms.channel.unauthorized"))
            s.control || tok == validToken -> s.say(hello(null))
            else -> allowAfterMs?.let { d -> later(d) { if (!s.cancelled) events.onText(hello(validToken).toString()) } }
        }
        return s
    }

    private fun hello(token: String?) = JSONObject().put("event", "ms.channel.connect").put("data",
        JSONObject().put("id", "abc").apply {
            if (token != null) put("token", if (tokenAsNumber) token.toLong() else token)
        })

    private fun respond(s: Sock, msg: JSONObject) {
        val params = msg.optJSONObject("params") ?: JSONObject()
        when (msg.optString("method")) {
            "ms.channel.emit" -> when (params.optString("event")) {
                "ed.installedApp.get" -> if (answersApps) s.say(JSONObject().put("event", "ed.installedApp.get")
                    .put("data", JSONObject().put("data", apps)))
                "ed.apps.launch" -> {
                    val d = params.getJSONObject("data")
                    launchReply(d.getString("appId"), d.getString("action_type"))?.let { r ->
                        s.say(JSONObject().put("event", "ed.apps.launch").put("data", r).put("from", "host"))
                    }
                }
                "ed.apps.icon" -> s.say(JSONObject().put("event", "ed.apps.icon").put("data", JSONObject()
                    .put("iconPath", params.getJSONObject("data").getString("iconPath"))
                    .put("imageBase64", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))))
            }
            "ms.application.get" -> s.say(JSONObject().put("id", params.getString("id"))
                .put("result", JSONObject().put("visible", visible).put("running", true)))
        }
    }

    override fun get(url: String) = restGet(url)
    override fun post(url: String): HttpReply? { posts += url; return restPost(url) }

    val keySocket get() = sockets.last { !it.control }
    fun allKeys() = sockets.filter { !it.control }.flatMap { it.keys() }
}

class SamsungRemoteTest {
    private val fast = SamsungTiming(
        firstPairMs = 1500, connectMs = 800, staleMs = 400, probeMs = 300, replyMs = 400,
        keyGapMs = 20, failFastMs = 300, pendingMs = 5000, tickMs = 40,
    )

    private fun remote(tv: FakeTv, token: String? = tv.validToken, saved: MutableList<String> = mutableListOf()) =
        SamsungRemote("10.0.0.5", token, { saved += it }, tv, tv, t = fast)

    private fun fails(block: () -> Unit): String? = try { block(); null } catch (e: RemoteException) { e.message ?: "" }

    @Test fun pairsOnceAndSavesToken() {
        val tv = FakeTv()
        val saved = mutableListOf<String>()
        val r = remote(tv, token = null, saved = saved)
        r.press(Key.HOME)
        assertEquals(listOf(tv.validToken), saved)
        assertEquals(listOf("KEY_HOME"), tv.allKeys())
        r.press(Key.SELECT)
        assertEquals(1, tv.sockets.size)
        assertTrue(tv.sockets[0].url.contains("name=TXVsdGl2aWV3IEF1ZGlv"))
        r.close()
    }

    @Test fun numericTokenIsSaved() {
        val tv = FakeTv().apply { tokenAsNumber = true }
        val saved = mutableListOf<String>()
        val r = remote(tv, token = null, saved = saved)
        r.press(Key.HOME)
        assertEquals(listOf(tv.validToken), saved)
        r.close()
    }

    @Test fun keysUseOneConnectionAndArriveInOrder() {
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = remote(tv)
        val keys = listOf(Key.UP, Key.LEFT, Key.DOWN, Key.RIGHT, Key.SELECT, Key.BACK)
        keys.forEach { r.press(it) }
        assertEquals(listOf("KEY_UP", "KEY_LEFT", "KEY_DOWN", "KEY_RIGHT", "KEY_ENTER", "KEY_RETURN"), tv.allKeys())
        assertEquals(1, tv.sockets.size)
        r.close()
    }

    @Test fun reconnectsAfterTheTvClosesTheSocket() {
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = remote(tv)
        r.press(Key.HOME)
        tv.keySocket.tvCloses()
        Thread.sleep(60)
        r.press(Key.HOME)
        assertEquals(2, tv.sockets.size)
        assertEquals(listOf("KEY_HOME"), tv.sockets[1].keys())
        r.close()
    }

    @Test fun silentlyDeadSocketIsReplacedBeforeSending() {
        // The bug behind "Home worked, then stopped": the socket died without a word.
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = remote(tv)
        r.press(Key.HOME)
        tv.keySocket.goesSilent()
        Thread.sleep(600) // longer than staleMs with no pings
        r.press(Key.HOME)
        assertEquals(2, tv.sockets.size)
        assertEquals(listOf("KEY_HOME"), tv.sockets[1].keys())
        r.close()
    }

    @Test fun quietButHealthyTvIsCheckedNotReconnected() {
        val tv = FakeTv().apply { pingEveryMs = null } // never pings, but answers
        val r = remote(tv)
        r.press(Key.HOME)
        Thread.sleep(600)
        r.press(Key.BACK)
        assertEquals(1, tv.sockets.size)
        assertEquals(listOf("KEY_HOME", "KEY_RETURN"), tv.allKeys())
        r.close()
    }

    @Test fun pingingTvIsNotProbed() {
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = remote(tv)
        r.press(Key.HOME)
        Thread.sleep(600)
        r.press(Key.BACK)
        val probes = tv.keySocket.received.count { it.contains("ed.installedApp.get") }
        assertEquals(0, probes)
        r.close()
    }

    @Test fun blockedAppGetsAClearMessage() {
        val tv = FakeTv().apply { deny = true }
        val r = remote(tv, token = null)
        val msg = fails { r.press(Key.HOME) }
        assertNotNull(msg)
        assertTrue(msg!!.contains("Device Connection Manager"))
        r.close()
    }

    @Test fun allowPromptKeepsTheSameConnectionWaiting() {
        val tv = FakeTv().apply { allowAfterMs = 700 }
        val r = SamsungRemote("10.0.0.5", null, {}, tv, tv, t = fast.copy(firstPairMs = 200))
        val msg = fails { r.press(Key.HOME) }
        assertEquals(SamsungRemote.NO_ANSWER_FIRST, msg)
        Thread.sleep(800) // they press Allow
        r.press(Key.HOME)
        assertEquals(1, tv.sockets.size)
        assertEquals(listOf("KEY_HOME"), tv.allKeys())
        r.close()
    }

    @Test fun unreachableTvFailsFastInsteadOfStackingWaits() {
        val tv = FakeTv().apply { failConnect = "No route to host" }
        val r = remote(tv)
        assertNotNull(fails { r.press(Key.HOME) })
        val t0 = System.nanoTime()
        assertNotNull(fails { r.press(Key.HOME) })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 250)
        assertEquals(1, tv.sockets.size)
        Thread.sleep(350)
        tv.failConnect = null
        r.press(Key.HOME)
        assertEquals(listOf("KEY_HOME"), tv.allKeys())
        r.close()
    }

    @Test fun keysAreSpacedOut() {
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = SamsungRemote("10.0.0.5", tv.validToken, {}, tv, tv, t = fast.copy(keyGapMs = 80))
        r.press(Key.UP)
        val t0 = System.nanoTime()
        r.press(Key.DOWN)
        r.press(Key.UP)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 150)
        r.close()
    }

    @Test fun launchUsesTheAppsType() {
        val tv = FakeTv()
        val actions = mutableListOf<String>()
        tv.launchReply = { _, a -> actions += a; 200 }
        val r = remote(tv)
        r.launch("org.tizen.native")
        r.launch("PvWgqxV3Xa.YouTubeTV")
        assertEquals(listOf("NATIVE_LAUNCH", "DEEP_LINK"), actions)
        assertTrue(tv.posts.isEmpty())
        r.close()
    }

    @Test fun launchTriesTheOtherTypeWhenRefused() {
        val tv = FakeTv()
        tv.launchReply = { _, a -> if (a == "DEEP_LINK") 404 else 200 }
        val r = remote(tv)
        r.launch("111299001912")
        assertTrue(tv.posts.isEmpty())
        r.close()
    }

    @Test fun launchOnTvThatNeverConfirmsDoesNotHang() {
        val tv = FakeTv()
        tv.launchReply = { _, _ -> null }
        val r = remote(tv)
        r.launch("111299001912")
        assertEquals(1, tv.posts.size) // nudged over REST
        val t0 = System.nanoTime()
        r.launch("111299001912")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 350) // second time it doesn't wait for a reply
        r.close()
    }

    @Test fun launchRefusedEverywhereThrows() {
        val tv = FakeTv()
        tv.launchReply = { _, _ -> 404 }
        tv.restPost = { HttpReply(404, "") }
        val r = remote(tv)
        assertNotNull(fails { r.launch("111299001912") })
        r.close()
    }

    @Test fun listsAppsAndFindsYouTubeTv() {
        val tv = FakeTv()
        val r = remote(tv)
        val apps = r.listApps()
        assertEquals(3, apps.size)
        assertEquals("PvWgqxV3Xa.YouTubeTV", r.findApp("youtube tv"))
        assertEquals("111299001912", r.findApp("YouTube"))
        r.close()
    }

    @Test fun typingSendsTheWholeSequence() {
        val tv = FakeTv()
        val r = remote(tv)
        r.sendText("bears")
        val msgs = tv.keySocket.received.map { JSONObject(it) }
        assertEquals("custom.remote.textReceived", msgs[0].getJSONObject("params").getString("event"))
        val input = msgs[1].getJSONObject("params")
        assertEquals("SendInputString", input.getString("TypeOfRemote"))
        assertEquals("bears", String(Base64.getDecoder().decode(input.getString("Cmd"))))
        assertEquals("SendInputEnd", msgs[2].getJSONObject("params").getString("TypeOfRemote"))
        r.close()
    }

    @Test fun onScreenUsesRestThenControlChannel() {
        val tv = FakeTv()
        val r = remote(tv)
        tv.restGet = { HttpReply(200, JSONObject().put("visible", false).toString()) }
        assertEquals(false, r.isOnScreen("PvWgqxV3Xa.YouTubeTV"))
        tv.restGet = { null }
        assertEquals(false, r.isOnScreen("PvWgqxV3Xa.YouTubeTV"))
        tv.restGet = { HttpReply(404, "") }
        tv.visible = true
        assertEquals(true, r.isOnScreen("PvWgqxV3Xa.YouTubeTV"))
        tv.visible = false
        assertEquals(false, r.isOnScreen("PvWgqxV3Xa.YouTubeTV"))
        assertEquals(1, tv.sockets.count { it.control })
        r.close()
    }

    @Test fun keyboardOpeningIsReported() {
        val tv = FakeTv()
        val r = remote(tv)
        val seen = CopyOnWriteArrayList<Boolean>()
        r.onKeyboard = { seen += it }
        r.press(Key.SELECT)
        tv.keySocket.say(JSONObject().put("event", "ms.remote.imeStart"))
        tv.keySocket.say(JSONObject().put("event", "ms.remote.imeEnd"))
        Thread.sleep(100)
        assertEquals(listOf(true, false), seen.toList())
        r.close()
    }

    @Test fun appIconsComeFromTheTv() {
        val tv = FakeTv()
        val r = remote(tv)
        r.listApps()
        val icon = r.icon("111299001912")
        assertNotNull(icon)
        assertEquals(3, icon!!.size)
        assertNull(r.icon("not-installed"))
        r.close()
    }

    @Test fun linkedReflectsTheConnection() {
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = remote(tv)
        assertFalse(r.linked)
        r.check()
        assertTrue(r.linked)
        Thread.sleep(150) // a few pings
        assertTrue(r.linked)
        tv.keySocket.goesSilent()
        Thread.sleep(600)
        assertFalse(r.linked)
        r.close()
    }

    @Test fun manyPressesAtOnceShareOneNewConnection() {
        val tv = FakeTv().apply { pingEveryMs = 50 }
        val r = remote(tv)
        val threads = (1..6).map { Thread { r.press(Key.VOL_UP) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(3000) }
        assertEquals(1, tv.sockets.size)
        assertEquals(List(6) { "KEY_VOLUP" }, tv.allKeys())
        r.close()
    }

    @Test fun quietTvThatNeverPingsStillShowsConnected() {
        val tv = FakeTv().apply { pingEveryMs = null }
        val r = remote(tv)
        r.check()
        Thread.sleep(600)
        assertTrue(r.linked)
        r.close()
        assertFalse(r.linked)
    }

    @Test fun closedRemoteRefusesWork() {
        val tv = FakeTv()
        val r = remote(tv)
        r.press(Key.HOME)
        r.close()
        assertNotNull(fails { r.press(Key.HOME) })
    }
}
