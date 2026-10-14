package com.rickle8.mvswitch.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class BroadcastTest {
    private val quiet = object : Remote { override fun press(key: Key) {} }

    // ------------------------------------------------------------ stream delay
    @Test fun learnsHowFarBehindTheStreamRuns() {
        val sd = StreamDelay()
        val r = java.util.Random(1)
        for (k in 0 until 5) {
            val playT = 1000.0 * k
            val react = { t: Double ->
                val d = t - playT
                0.1 + r.nextGaussian() * 0.03 + 0.8 * exp(-(d - 42) * (d - 42) / 6.0)
            }
            assertFalse("too early", sd.observe("p$k", playT, playT + 50, react))
            assertTrue(sd.observe("p$k", playT, playT + 110, react))
            assertFalse("used once", sd.observe("p$k", playT, playT + 111, react))
            if (k == 0) assertNull(sd.seconds)
        }
        val d = sd.seconds
        assertNotNull(d)
        assertTrue("delay $d", d!! in 40.0..44.0)
        // Saved and loaded.
        val t = BroadcastTiming().apply { load(BroadcastTiming().also { it.delay.load(sd.toJson()) }.toJson().toString()) }
        assertEquals(d, t.delay.seconds)
    }

    @Test fun ignoresPlaysItWasntListeningTo() {
        val sd = StreamDelay()
        assertFalse(sd.observe("x", 0.0, 110.0) { null })
        assertFalse(sd.observe("flat", 200.0, 310.0) { 0.2 })
        assertNull(sd.seconds)
    }

    @Test fun breakLengthsAreLearnedPerKind() {
        val b = BreakLengths()
        assertNull(b.typical("football/nfl", false))
        listOf(140.0, 150.0, 170.0).forEach { b.add("football/nfl", false, it) }
        listOf(280.0, 300.0, 320.0).forEach { b.add("football/nfl", true, it) }
        b.add("football/nfl", false, 10.0) // nonsense: ignored
        assertEquals(150.0, b.typical("football/nfl", false))
        assertEquals(300.0, b.typical("football/nfl", true))
        val copy = BreakLengths().apply { load(b.toJson()) }
        assertEquals(300.0, copy.typical("football/nfl", true))
    }

    // ------------------------------------------------------------ live data helpers
    private val wallBase = 1_790_000_000_000L

    private fun iso(t: Double) = java.time.Instant.ofEpochMilli(wallBase + (t * 1000).toLong()).toString()

    private fun board(clock: Double, period: Int = 2) = JSONObject().put("events", JSONArray().put(JSONObject()
        .put("id", "401")
        .put("competitions", JSONArray().put(JSONObject()
            .put("status", JSONObject().put("period", period).put("clock", clock)
                .put("type", JSONObject().put("state", "in").put("name", "STATUS_IN_PROGRESS")))
            .put("competitors", JSONArray()
                .put(JSONObject().put("homeAway", "home").put("score", "7").put("team", JSONObject().put("abbreviation", "BUF").put("name", "Bills")))
                .put(JSONObject().put("homeAway", "away").put("score", "3").put("team", JSONObject().put("abbreviation", "MIA").put("name", "Dolphins"))))))))

    private fun summary(plays: List<Pair<String, Double>>) = JSONObject().put("plays", JSONArray().apply {
        plays.forEachIndexed { i, (text, t) ->
            put(JSONObject().put("sequenceNumber", "${i + 1}").put("text", text).put("wallclock", iso(t)))
        }
    })

    // ------------------------------------------------------------ game clock
    @Test fun runningGameClockKeepsTheAudio() {
        var now = 0.0
        var clock = 600.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(mapOf(1 to GameSlot(league = "football/nfl", team = "BUF")), { board(clock) }, { now }, pollSecs = 0.0)
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, log = { logs += it })
        f.poll()
        now = 15.0; clock = 585.0; f.poll()
        assertEquals(15.0, f.clockMovedAt(1))
        assertEquals(Bias.CLOCK, c.bias(1, now))
        now = 20.0
        assertFalse("clock running: not a break", c.step(Heard.AD))
        assertEquals(1, nav.current)
        assertTrue(logs.last().contains("game clock"))
        // Clock stops (a real stoppage), then the ad sound counts.
        now = 60.0; f.poll()
        now = 75.0; f.poll()
        assertTrue(c.step(Heard.AD))
        assertEquals(2, nav.current)
    }

    @Test fun clockIgnoredForSportsWhereItKeepsRunning() {
        var now = 0.0
        var clock = 600.0
        val f = GameFeed(mapOf(1 to GameSlot(league = "soccer/usa.1", team = "BUF")), { board(clock) }, { now }, pollSecs = 0.0)
        f.poll(); now = 15.0; clock = 585.0; f.poll()
        assertNull(f.clockMovedAt(1))
    }

    // ------------------------------------------------------------ stream delay in switching
    @Test fun waitsForTheTvToShowTheTouchdown() {
        var now = 106.0
        val timing = BroadcastTiming().apply { load("""{"delay":{"s":30,"w":5,"hist":[]}}""") }
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Pass short left to J.Cook for 12 yards, TOUCHDOWN.", 100.0, 105.0, seq = 7)
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, timing = timing)
        assertFalse(c.step(Heard.GAME))      // ESPN has it, the TV doesn't yet
        now = 140.0; assertFalse(c.step(Heard.GAME))
        now = 146.0; assertTrue(c.step(Heard.GAME)) // shown at 130, plus the celebration
        assertEquals(2, nav.current)
    }

    @Test fun playStillAheadOnTheTvMeansTheGameIsOn() {
        var now = 100.0
        val timing = BroadcastTiming().apply { load("""{"delay":{"s":40,"w":5,"hist":[]}}""") }
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Rush up the middle for 3 yards", 0.0, 10.0, seq = 3) // on TV at 40
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, timing = timing)
        assertTrue("seen 90 s ago, but on TV only 60 s ago", c.recentPlay(1, now) != null)
        now = 130.0
        assertNull(c.recentPlay(1, now))
    }

    // ------------------------------------------------------------ return timing
    @Test fun sitsOutTheSecondBreakAfterAScore() {
        var now = 0.0
        val plays = mutableListOf("Rush for 3 yards" to -60.0)
        val timing = BroadcastTiming()
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(mapOf(1 to GameSlot(league = "football/nfl", team = "BUF")), { board(900.0) }, { now },
            pollSecs = 0.0, fetchSummary = { _, _ -> summary(plays) }, wallNow = { wallBase + (now * 1000).toLong() },
            summarySecs = 0.0, otherSummarySecs = 0.0)
        f.focusBox = 1
        f.poll()
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, timing = timing)
        now = 100.0
        plays += "J.Allen pass to S.Diggs for 20 yards, TOUCHDOWN" to 100.0
        f.poll()
        assertTrue(c.step(Heard.GAME)) // break coming after the score
        assertEquals(2, nav.current)
        now = 250.0
        plays += "T.Bass kicks 65 yards from BUF 35 to end zone, Touchback." to 245.0
        f.poll()
        assertTrue("kickoff between the two breaks", c.inBreak(1, now, f.status))
        now = 400.0
        plays += "T.Tagovailoa pass short right to T.Hill for 8 yards" to 398.0
        f.poll()
        assertFalse(c.inBreak(1, now, f.status))
        assertEquals(1, timing.breaks.count())
    }

    @Test fun learnedBreakLengthSetsTheTimer() {
        var now = 100.0
        val timing = BroadcastTiming()
        repeat(3) { timing.breaks.add("", false, 200.0) }
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, timing = timing)
        assertTrue(c.step(Heard.AD))
        assertEquals(300.0, c.breakUntil[1]!!, 0.01)
    }

    // ------------------------------------------------------------ detector: words and break music
    private val names = Array(Features.N_YAMNET) { when (it) { 0 -> "Speech"; 1 -> "Music"; else -> "class$it" } }

    private fun sec(audio: FloatArray, s: Int, speech: Float, music: Float) =
        MicSecond(audio.copyOfRange(s * Dsp.SR, (s + 1) * Dsp.SR),
            FloatArray(Features.N_YAMNET) { when (it) { 0 -> speech; 1 -> music; else -> 0.005f } }, names)

    private fun tones(seed: Long, secs: Int): FloatArray {
        val r = java.util.Random(seed)
        val n = Dsp.SR * secs
        val x = FloatArray(n)
        var start = 0
        while (start < n) {
            val len = (Dsp.SR * (0.15 + r.nextDouble() * 0.45)).toInt()
            val f0 = 120 + r.nextDouble() * 1380
            val decay = 1 + r.nextDouble() * 5
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val t = i.toDouble() / Dsp.SR
                var v = 0.0
                for (h in 1..3) v += exp(-t * decay) * sin(2 * PI * f0 * h * t) / h
                x[idx] += (v * 0.3).toFloat()
            }
            start += (len * (0.5 + r.nextDouble() * 0.5)).toInt()
        }
        return x
    }

    private fun room(x: FloatArray, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        val p = x.sumOf { (it * it).toDouble() } / x.size
        val sd = sqrt(p / 10.0)
        return FloatArray(x.size) { ((x[it] + r.nextGaussian() * sd) * 0.7).toFloat() }
    }

    @Test fun gameTalkOutweighsMusicySound() {
        val audio = room(tones(3, 30), 4)
        fun run(words: Boolean): Heard {
            val d = Detector()
            var last = Heard.UNSURE
            for (s in 0 until 25) {
                last = d.process(sec(audio, s, speech = 0.3f, music = 0.8f), s.toDouble(), 1, Bias.NORMAL, false,
                    gameWords = if (words && s % 3 == 0) listOf("third down") else emptyList()).heard
            }
            return last
        }
        assertEquals(Heard.AD, run(words = false))
        assertTrue(run(words = true) != Heard.AD)
    }

    @Test fun adTalkPushesTowardCommercial() {
        val audio = room(tones(5, 30), 6)
        val d = Detector()
        var r: DetectResult? = null
        for (s in 0 until 25) {
            r = d.process(sec(audio, s, speech = 0.5f, music = 0.12f), s.toDouble(), 1, Bias.NORMAL, false,
                adWords = if (s % 2 == 0) listOf("terms apply") else emptyList())
        }
        assertEquals(Heard.AD, r!!.heard)
    }

    @Test fun learnsTheNetworksBreakMusic() {
        val d = Detector()
        val bumper = tones(77, 4)
        var t = 0.0
        var recognizedAt: Double? = null
        var why: String? = null
        for (cycle in 0 until 3) {
            val game = room(tones(100L + cycle, 25), 10L + cycle)
            val bump = room(bumper, 20L + cycle)
            val ad = room(tones(200L + cycle, 20), 30L + cycle)
            for (s in 0 until 25) d.process(sec(game, s, 0.8f, 0f), t++, 1, Bias.NORMAL, true)
            for (s in 0 until 4) {
                val r = d.process(sec(bump, s, 0f, 0.8f), t++, 1, Bias.NORMAL, true)
                if (r.strongAd && recognizedAt == null) { recognizedAt = t; why = r.why }
            }
            for (s in 0 until 20) d.process(sec(ad, s, 0f, 0.8f), t++, 1, Bias.NORMAL, true)
            if (cycle == 0) assertEquals(0, d.bumpers.trustedCount)
            if (cycle == 1) assertEquals(1, d.bumpers.trustedCount)
            if (cycle < 2) assertNull("not before it's learned", recognizedAt)
        }
        assertNotNull("third break: recognized from the music alone", recognizedAt)
        assertEquals("Heard the network's break music", why)
    }

    @Test fun noBreakMusicMeansNothingLearned() {
        val d = Detector()
        var t = 0.0
        var strong = false
        for (cycle in 0 until 4) {
            val game = room(tones(300L + cycle, 25), 40L + cycle)
            val ad = room(tones(400L + cycle, 24), 50L + cycle)
            for (s in 0 until 25) d.process(sec(game, s, 0.8f, 0f), t++, 1, Bias.NORMAL, true)
            for (s in 0 until 24) if (d.process(sec(ad, s, 0f, 0.8f), t++, 1, Bias.NORMAL, true).strongAd) strong = true
        }
        assertEquals(0, d.bumpers.trustedCount)
        assertEquals(0, d.comebacks.trustedCount)
        assertFalse(strong)
    }

    @Test fun fillerBoxOnlyCoversBreaks() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val slots = mapOf(1 to GameSlot(league = "football/nfl", team = "BUF"),
            2 to GameSlot(name = "RedZone", importance = 0.5, kind = GameSlot.OTHER))
        val f = GameFeed(slots, { board(900.0) }, { now }, pollSecs = 1e9)
        f.poll(force = true)
        val c = Controller(nav, listOf(1, 2), f, games = slots, clock = { now })
        assertTrue(c.step(Heard.AD))            // game to commercial: the filler covers it
        assertEquals(2, nav.current)
        now = 400.0                              // break over
        assertTrue(c.step(Heard.GAME))
        assertEquals(1, nav.current)
    }

    @Test fun findsTheExactEventById() {
        val board = JSONObject().put("events", JSONArray()
            .put(JSONObject().put("id", "1").put("name", "Round 1").put("competitions", JSONArray().put(JSONObject())))
            .put(JSONObject().put("id", "2").put("name", "The Masters").put("competitions", JSONArray().put(JSONObject()))))
        assertEquals("The Masters", findEvent(board, GameSlot(league = "golf/pga", eventId = "2"))!!.optString("name"))
        assertNull(findEvent(board, GameSlot(league = "golf/pga", eventId = "9")))
        val games = listGames(board, "golf/pga")
        assertEquals("The Masters", games.first { it.id == "2" }.shortName)
        assertTrue(scoreboardUrl("football/college-football").contains("groups=80"))
    }

    @Test fun realEspnWording() {
        assertEquals(321.0, clockSeconds("5:21")!!, 0.01)
        assertEquals(7.5, clockSeconds("0:07.5")!!, 0.01)
        assertNull(clockSeconds("Halftime"))
        assertFalse(isBreakPlay("J.Allen pass for TOUCHDOWN NULLIFIED by Penalty"))
        assertTrue(isBreakPlay("Official Timeout at 05:32."))
        val p = Regex(DEFAULT_PROACTIVE, RegexOption.IGNORE_CASE)
        assertTrue(p.containsMatchIn("End Period: END QUARTER 1"))
        assertTrue(p.containsMatchIn("End of Half: END QUARTER 2"))
        assertTrue(p.containsMatchIn("Field Goal Good: B.Aubrey 45 yard field goal is GOOD"))
        assertFalse(p.containsMatchIn("Field Goal Missed: 52 yard field goal is No Good, Wide Left"))
    }
}
