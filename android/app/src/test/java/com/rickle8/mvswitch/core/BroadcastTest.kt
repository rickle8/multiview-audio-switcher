package com.rickle8.mvswitch.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        var now = 70.0
        val timing = BroadcastTiming().apply { load("""{"delay":{"s":40,"w":5,"hist":[]}}""") }
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Rush up the middle for 3 yards", 0.0, 10.0, seq = 3) // on TV at 40
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, timing = timing)
        assertTrue("seen 60 s ago, but on TV only 30 s ago", c.recentPlay(1, now) != null)
        now = 80.0
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
        assertEquals(Bias.EXPECTED, c.bias(1, now))
        assertNotNull(c.waitingFor(1))
        now = 240.0
        assertTrue(c.step(Heard.AD))
        assertEquals(2, nav.current)
        assertTrue(logs.last().contains("Commercial started"))
    }

    @Test fun anEspnWarnedBreakGoesTheMomentItSoundsLikeOne() {
        var now = 106.0
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Official Timeout at 03:12.", 100.0, 105.0, seq = 7)
        val c = Controller(nav, listOf(1, 2), f, clock = { now })
        assertFalse(c.step(Heard.GAME))
        // The TV runs closer to live than the 40 s it assumes: the commercial starts 20 s in. Go now.
        now = 126.0
        assertTrue(c.step(Heard.AD))
        assertEquals(2, nav.current)
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

    // ------------------------------------------------------------ which game is this?
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

    // ------------------------------------------------------------ games you're not hearing
    @Test fun knowsOtherGamesAreInCommercialFromEspn() {
        var now = 300.0
        val timing = BroadcastTiming().apply { load("""{"delay":{"s":30,"w":5,"hist":[]}}""") }
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(2, "Official Timeout at 05:32.", 230.0, 240.0, seq = 5)              // on TV at 260: in commercial
        f.recordPlay(3, "J.Smith rush up the middle for 4 yards", 250.0, 262.0, seq = 9) // on TV at 280: game on
        f.recordPlay(4, "Field Goal Good: 45 yard field goal is GOOD", 280.0, 290.0, seq = 3) // TV gets there at 310
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, timing = timing)
        assertEquals(Controller.Outlook.BREAK, c.outlook(2, now).first)
        assertEquals(Controller.Outlook.LIVE, c.outlook(3, now).first)
        assertEquals(Controller.Outlook.SOON, c.outlook(4, now).first)
        // Box 1 goes to commercial: skip the game in commercial and the one about to go, land on the live one.
        assertTrue(c.step(Heard.AD))
        assertEquals(3, nav.current)
    }

    @Test fun everythingInCommercialGoesToTheOneBackSoonest() {
        var now = 500.0
        val timing = BroadcastTiming().apply { load("""{"delay":{"s":30,"w":5,"hist":[]}}""") }
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(2, "Official Timeout at 05:32.", 400.0, 405.0, seq = 5)   // break since 430
        f.recordPlay(3, "End Period: END QUARTER 1", 340.0, 345.0, seq = 9)     // break since 370: back sooner
        f.recordPlay(4, "Official Timeout at 02:00.", 450.0, 455.0, seq = 3)   // break since 480: too new
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, timing = timing)
        assertTrue(c.step(Heard.AD))
        assertEquals(3, nav.current)
    }

    // ------------------------------------------------------------ syncing the TV with ESPN
    @Test fun commercialAfterATimeoutSyncsTheTvWithEspn() {
        var now = 0.0
        val timing = BroadcastTiming()
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, timing = timing, log = { logs += it })
        // Real time 100: TV timeout. ESPN shows it at 115. The TV (40 s behind) cuts to commercial at 145.
        f.recordPlay(1, "Official Timeout at 05:32.", 100.0, 115.0, seq = 5)
        now = 150.0; c.step(Heard.GAME)
        now = 153.0; c.step(Heard.AD) // heard as commercial ~8 s after the cut
        assertEquals(1, timing.delay.syncSamples)
        // Second break, same thing: now it's decided.
        nav.current = 1; now = 900.0; c.lastSwitch = 0.0
        f.recordPlay(1, "End Period: END QUARTER 1", 800.0, 815.0, seq = 9)
        c.step(Heard.GAME)
        now = 853.0; c.step(Heard.AD)
        assertEquals(40.0, timing.delay.seconds!!, 2.0)
        assertTrue(logs.any { it.startsWith("Synced with ESPN") })
    }

    @Test fun goesBackWhenTheCommercialEnds() {
        var now = 100.0
        val timing = BroadcastTiming().apply { load("""{"delay":{"s":30,"w":5,"hist":[]}}""") }
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, timing = timing, log = { logs += it })
        assertTrue(c.step(Heard.AD))                 // box 1 to commercial
        val cover = nav.current!!
        assertTrue(cover != 1)
        now = 160.0; assertFalse(c.step(Heard.GAME)) // nothing from box 1 yet
        // Box 1's first play after the break: real time 200, ESPN has it at 215, the TV shows it at 230.
        f.recordPlay(1, "J.Allen pass short right to K.Coleman for 9 yards", 200.0, 215.0, seq = 40)
        now = 220.0; assertFalse("the TV hasn't shown it yet", c.step(Heard.GAME))
        now = 232.0; assertTrue(c.step(Heard.GAME))
        assertEquals(1, nav.current)
        assertTrue(logs.last().contains("back from commercial"))
    }

    @Test fun runningClockMeansLiveEvenWhenPlaysAreLate() {
        var now = 0.0
        var clock = 600.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val slots = mapOf(4 to GameSlot(league = "football/college-football", team = "BUF"))
        val f = GameFeed(slots, { board(clock) }, { now }, pollSecs = 0.0)
        // Box 4's play-by-play is stale: its last play was a timeout minutes ago (as college feeds can be)...
        f.recordPlay(4, "Timeout #1 by BUF at 10:12.", -200.0, -150.0, seq = 3)
        f.recordPlay(2, "Official Timeout at 05:32.", -20.0, -5.0, seq = 5)
        f.recordPlay(3, "End Period: END QUARTER 1", -30.0, -15.0, seq = 9)
        f.poll()
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // ...but its clock is ticking down: the game is on.
        now = 10.0; clock = 590.0; f.poll()
        assertEquals(Controller.Outlook.LIVE, c.outlook(4, now).first)
        assertTrue(c.step(Heard.AD))
        assertEquals(4, nav.current)
    }

    // ------------------------------------------------------------ camera
    @Test fun cameraDecidesWhereToGoAndOverrulesTheSound() {
        var now = 100.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, log = { logs += it })
        // The camera reads box 1's score bug right now: a commercial-sounding moment isn't one.
        // Now box 1's score bug has been gone a long while: go, and skip the boxes it sees in commercial.
        now = 120.0
        c.see(now, 1 to Sight(bugGoneSecs = 50.0), 2 to Sight(adText = true), 3 to Sight(adText = true), 4 to Sight(bug = true))
        assertTrue(c.step(Heard.UNSURE, adPct = 55))
        assertEquals(4, nav.current)
    }

    @Test fun theScoreBugsLookAloneNeverHoldsYouOnACommercial() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // The look says "game" on box 1 but nothing's been read there: the commercial sound wins.
        c.see(now, 1 to Sight(bugShape = true), 2 to Sight(bug = true), 3 to Sight(adText = true), 4 to Sight(adText = true))
        assertTrue(c.step(Heard.AD))
        assertEquals(2, nav.current)
    }

    @Test fun cameraNeverTurnsGameAudioIntoACommercial() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // The camera thinks box 1 is in commercial, but it sounds like the game: stay.
        val live = Sight(bug = true)
        c.see(now, 1 to Sight(bugGoneSecs = 50.0), 2 to live, 3 to live, 4 to live)
        assertFalse(c.step(Heard.GAME, adPct = 35))
        now = 110.0; c.see(now, 1 to Sight(adText = true), 2 to live, 3 to live, 4 to live)
        assertFalse(c.step(Heard.GAME, adPct = 35))
        assertEquals(1, nav.current)
    }

    @Test fun twoCameraSignsOutweighShakyGameSound() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        val live = Sight(bug = true)
        // Commercial text and the score bug long gone, while the sound only barely says game (a
        // commercial with a crowd in it): a commercial.
        c.see(now, 1 to Sight(adText = true, bugGoneSecs = 50.0), 2 to live, 3 to live, 4 to live)
        assertTrue(c.step(Heard.GAME, adPct = 38))
        assertNotEquals(1, nav.current)
        // Sound that's clearly the game is never overruled.
        now = 200.0; val at = nav.current!!
        c.see(now, at to Sight(adText = true, bugGoneSecs = 50.0))
        assertFalse(c.step(Heard.GAME, adPct = 12))
    }

    @Test fun sureCommercialsEverywhereStillSettle() {
        var now = 100.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, log = { logs += it })
        // Each box it lands on is in commercial too, and it's sure of each one.
        assertTrue(c.step(Heard.AD, strongAd = true))
        now = 110.0; assertTrue(c.step(Heard.AD, strongAd = true))
        now = 120.0; assertFalse(c.step(Heard.AD, strongAd = true))
        assertTrue(logs.last().startsWith("Commercials everywhere"))
    }

    @Test fun yardLinesNeverHoldAnAdSoundingMoment() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // Yard lines (or a close-up's stripes) without the score bug: the sound decides.
        c.see(now, 1 to Sight(lines = true), 2 to Sight(bug = true))
        assertTrue(c.step(Heard.AD, adPct = 70))
        assertEquals(2, nav.current)
    }

    @Test fun theScoreBugNotReadDoesntRuleOutAGame() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // The camera can't read the bugs on boxes 2-4 (small type, logos): commercial on box 1 still moves you.
        val lost = Sight(bugGoneSecs = 120.0)
        c.see(now, 1 to Sight(), 2 to lost, 3 to lost, 4 to lost)
        assertTrue(c.step(Heard.AD, adPct = 75))
        assertNotEquals(1, nav.current)
    }

    @Test fun aBoxWithCommercialTextGoesBehindOneTheCameraCantRead() {
        val now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        c.see(now, 1 to Sight(), 2 to Sight(adText = true), 3 to Sight(bugGoneSecs = 60.0), 4 to Sight(adText = true))
        assertTrue(c.step(Heard.AD, adPct = 75))
        assertEquals(3, nav.current)
    }

    @Test fun yourAdTapAlwaysMovesYou() {
        val now = 100.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, log = { logs += it })
        // Every other box shows commercial text: nowhere better to go.
        c.see(now, 1 to Sight(), 2 to Sight(adText = true), 3 to Sight(adText = true), 4 to Sight(adText = true))
        assertFalse(c.commercialNow())
        // Only "not read lately" elsewhere: your tap moves you.
        c.see(now, 1 to Sight(), 2 to Sight(bugGoneSecs = 60.0), 3 to Sight(bugGoneSecs = 60.0), 4 to Sight(adText = true))
        assertTrue(c.commercialNow())
        assertTrue(nav.current == 2 || nav.current == 3)
    }

    @Test fun aFieldWithTheScoreBugGoneIsAPromo() {
        val now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // Yard lines, but the score bug it reads dependably has been gone 12 s: a promo shot on a field.
        c.see(now, 1 to Sight(lines = true, bugGoneSecs = 12.0), 2 to Sight(bug = true))
        assertTrue(c.step(Heard.AD, adPct = 80))
        assertEquals(2, nav.current)
    }

    @Test fun cameraBacksUpAnAdSoundOverAStalePlay() {
        var now = 100.0
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "J.Smith pass short right to K.Jones for 12 yards", now - 5, now - 5, seq = 1)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now })
        // ESPN just had a play on box 1, so an ad-sounding moment alone stays...
        assertFalse(c.step(Heard.AD))
        // ...but with the camera seeing its score bug gone, it's a commercial.
        now = 108.0
        c.see(now, 1 to Sight(bugGoneSecs = 10.0), 2 to Sight(adText = true), 3 to Sight(bug = true), 4 to Sight())
        assertTrue(c.step(Heard.AD))
        assertEquals(3, nav.current)
    }

    @Test fun youSayingItsTheGameBeatsTheCamera() {
        var now = 100.0
        val logs = mutableListOf<String>()
        val nav = Navigator(quiet, LAYOUTS.getValue(4), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2, 3, 4), f, clock = { now }, log = { logs += it })
        nav.current = 2
        val ad = Sight(adText = true)
        c.see(now, 1 to Sight(bug = true), 2 to ad, 3 to ad, 4 to ad)
        assertEquals(Controller.Outlook.BREAK, c.outlook(2, now).first)
        // You're watching box 2 and say it's the game: the camera's "commercial" doesn't count there.
        c.userSaysGame(2)
        assertEquals(Controller.Outlook.LIVE, c.outlook(2, now).first)
        assertFalse(c.looksOnBreak(2, now, emptyMap()))
        now = 110.0; c.see(now, 1 to Sight(bug = true), 2 to ad, 3 to ad, 4 to ad)
        assertFalse(c.step(Heard.UNSURE))
        assertEquals(2, nav.current)
        // A real commercial heard there still moves it, and then box 2 counts as in commercial again.
        now = 130.0; c.see(now, 1 to Sight(bug = true), 2 to ad, 3 to ad, 4 to ad)
        assertTrue(c.step(Heard.AD))
        assertEquals(1, nav.current)
        assertEquals(Controller.Outlook.BREAK, c.outlook(2, now).first)
    }
}
