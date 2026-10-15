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
        c.micOn = false                      // live data only: leave once the TV has shown it
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
        assertFalse("the TV hasn't shown the score yet", c.step(Heard.AD))
        now = 160.0
        assertTrue(c.step(Heard.AD)) // the break after the score
        assertEquals(2, nav.current)
        now = 250.0
        plays += "T.Bass kicks 65 yards from BUF 35 to end zone, Touchback." to 245.0
        f.poll()
        assertTrue("kickoff between the two breaks", c.inBreak(1, now, f.status))
        now = 400.0
        plays += "T.Tagovailoa pass short right to T.Hill for 8 yards" to 398.0
        f.poll()
        assertTrue("ESPN has it, the TV doesn't yet", c.inBreak(1, now, f.status))
        now = 440.0
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

    // ------------------------------------------------------------ waits for the commercial itself
    @Test fun espnCueWaitsForTheCommercialToStart() {
        var now = 106.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Pass short left to J.Cook for 12 yards, TOUCHDOWN.", 100.0, 105.0, seq = 7)
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, log = { logs += it })
        // The TV is still showing the game (replays, the extra point): stay, even long after ESPN had it.
        for (t in 0..120 step 5) { now = 106.0 + t; assertFalse(c.step(Heard.GAME)) }
        assertEquals(1, nav.current)
        assertEquals(Bias.LIKELY, c.bias(1, now))
        assertNotNull(c.waitingFor(1))
        now = 240.0
        assertTrue(c.step(Heard.AD))
        assertEquals(2, nav.current)
        assertTrue(logs.last().contains("Commercial started"))
    }

    @Test fun espnCueWithNoCommercialExpires() {
        var now = 10.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Timeout #1 by BUF at 03:12.", 5.0, 8.0, seq = 3)
        f.recordPlay(1, "Official Timeout at 03:12.", 5.0, 9.0, seq = 4)
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, log = { logs += it })
        assertFalse(c.step(Heard.GAME))
        now = 400.0
        assertFalse(c.step(Heard.GAME))
        assertNull(c.waitingFor(1))
        assertTrue(logs.any { it.contains("No commercial came") })
    }

    @Test fun learnsWhatThisGameSoundsLike() {
        val d = Detector()
        val game = room(tones(11, 200), 12)
        // Something that doesn't sound like this broadcast: a steady low chord.
        val other = FloatArray(Dsp.SR * 20) { i ->
            val t = i.toDouble() / Dsp.SR
            (0.2 * (sin(2 * PI * 196 * t) + sin(2 * PI * 247 * t) + sin(2 * PI * 294 * t))).toFloat()
        }.let { room(it, 13) }
        var t = 0.0
        for (s in 0 until 180) d.process(sec(game, s, 0.8f, 0f), t++, 1, Bias.CLOCK, true)
        assertTrue(d.profiles.ready(1))
    }

    // ------------------------------------------------------------ which game is this?
    private val tonight = mapOf(
        1 to BoxFacts("football/college-football", names = setOf("smith", "jackson"), teams = setOf("hilltoppers", "western kentucky"),
            scores = listOf(listOf(0, 7)), period = 2, down = 3, distance = 4),
        2 to BoxFacts("football/college-football", names = setOf("smith", "brown"), teams = setOf("owls", "kennesaw state"),
            scores = listOf(listOf(6, 0)), period = 2, down = 1, distance = 10),
        3 to BoxFacts("baseball/mlb", names = setOf("ohtani", "yelich"), teams = setOf("dodgers", "brewers", "milwaukee"),
            scores = listOf(listOf(0, 0), listOf(0, 1)), period = 3, half = "top", outs = 2),
        4 to BoxFacts("basketball/nba", names = setOf("doncic", "irving"), teams = setOf("mavericks", "pelicans", "new orleans"),
            scores = listOf(listOf(25, 30), listOf(25, 32)), period = 2),
    )

    @Test fun numbersReadTheSameEitherWay() {
        assertEquals("trailing 32 to 25", numbersToDigits("trailing thirty-two to twenty-five"))
        assertEquals("third and 4", numbersToDigits("third and four"))
    }

    @Test fun locatesTheGameFromWhatsSaid() {
        val loc = Locator()
        // The score, said either way, and even the one before the latest.
        assertEquals(setOf(4), loc.clues("New Orleans up thirty two to twenty five", tonight).map { it.box }.toSet())
        assertEquals(listOf(4), loc.clues("it was 30-25 a minute ago", tonight).map { it.box })
        // Down and distance picks one of two football games.
        assertEquals(listOf(1), loc.clues("third and four from the 30", tonight).filter { it.why.contains("and") }.map { it.box })
        // Inning and sport words: only the baseball game.
        assertTrue(loc.clues("top of the third, the pitch, strike two", tonight).all { it.box == 3 })
        // A name two games share isn't enough on its own.
        assertTrue(loc.clues("smith on the tackle", tonight).none { it.box == 3 || it.box == 4 })

        loc.reset(1, 0.0)
        assertNull(loc.where(0.0))
        loc.hear("rebound Doncic", 5.0, tonight)
        assertNull("not sure yet", loc.where(6.0))
        loc.hear("Pelicans lead thirty two to twenty five", 12.0, tonight)
        assertEquals(4, loc.where(13.0)!!.first)
        assertNotNull(loc.heardRecently(4, 20.0, 15.0))
        assertNull("fades out", loc.where(400.0))
    }

    @Test fun mixedSignalsDecideNothing() {
        val loc = Locator()
        loc.hear("the Dodgers and the Mavericks", 0.0, tonight)
        loc.hear("Ohtani, Doncic", 1.0, tonight)
        assertNull(loc.where(2.0))
    }

    @Test fun teamNamesIncludeThePlace() {
        val ev = JSONObject().put("competitions", JSONArray().put(JSONObject().put("competitors", JSONArray()
            .put(JSONObject().put("team", JSONObject().put("name", "Owls").put("location", "Kennesaw State")))
            .put(JSONObject().put("team", JSONObject().put("name", "Dodgers").put("location", "Los Angeles"))))))
        assertEquals(setOf("owls", "kennesaw state", "dodgers", "los angeles"), teamNames(ev))
    }

    @Test fun stopsBouncingWhenEverythingIsInCommercials() {
        var now = 100.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val c = Controller(nav, listOf(1, 2, 3, 4), null, clock = { now }, log = { logs += it })
        assertTrue(c.step(Heard.AD)); now += 10
        assertTrue(c.step(Heard.AD)); now += 10
        assertFalse("third commercial in a row: stay put", c.step(Heard.AD))
        assertTrue(logs.last().contains("Commercials everywhere"))
        now += 20
        assertFalse(c.step(Heard.AD))
        now += 30
        assertTrue("after a while, it tries again", c.step(Heard.AD))
    }
}
