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

class LogicTest {
    private class Clock { var t = 0.0 }

    private class Recorder : Remote {
        val pressed = mutableListOf<Key>()
        override fun press(key: Key) { pressed += key }
    }

    private fun event(
        a: String, b: String, state: String = "in", name: String = "STATUS_IN_PROGRESS",
        play: String? = null, period: Int = 1, clock: Double = 900.0, sa: Int = 0, sb: Int = 0,
    ): JSONObject {
        val comp = JSONObject()
            .put("status", JSONObject().put("period", period).put("clock", clock)
                .put("type", JSONObject().put("state", state).put("name", name)
                    .put("completed", state == "post")))
            .put("competitors", JSONArray()
                .put(JSONObject().put("homeAway", "home").put("score", "$sa")
                    .put("team", JSONObject().put("abbreviation", a)))
                .put(JSONObject().put("homeAway", "away").put("score", "$sb")
                    .put("team", JSONObject().put("abbreviation", b))))
        if (play != null) comp.put("situation", JSONObject().put("lastPlay", JSONObject().put("text", play)))
        return JSONObject().put("shortName", "$b @ $a").put("competitions", JSONArray().put(comp))
    }

    private fun board(vararg e: JSONObject) = JSONObject().put("events", JSONArray(e.toList()))

    private fun nfl(a: Int, b: Int, period: Int, clock: Double, status: GameStatus = GameStatus.LIVE,
                    redZone: Boolean = false, statusName: String = "") =
        GameInfo(status = status, statusName = statusName, league = "football/nfl", period = period,
            clock = clock, scores = linkedMapOf("AAA" to a, "BBB" to b), redZone = redZone)

    // ---- navigation ----
    @Test fun keyPaths() {
        val grid = LAYOUTS.getValue(4)
        assertEquals(emptyList<Key>(), keyPath(grid, 1, 1))
        assertEquals(listOf(Key.RIGHT), keyPath(grid, 1, 2))
        assertEquals(2, keyPath(grid, 1, 4).size)
        assertEquals(listOf(Key.LEFT), keyPath(LAYOUTS.getValue(3), 3, 1))
    }

    @Test fun homingReachesEveryBoxFromAnywhere() {
        for ((_, grid) in LAYOUTS) for (actual in grid.keys) for (target in grid.keys) {
            val r = Recorder()
            val nav = Navigator(r, grid, sleep = {})
            nav.current = null
            nav.goTo(target)
            assertEquals(target, simulate(grid, actual, r.pressed))
        }
    }

    // ---- verdict ----
    private fun feedFrames(v: Verdict, n: Int, game: Double, ad: Double, start: Int = 0): Heard {
        var s = Heard.UNSURE
        for (i in 0 until n) s = v.add(Frame((start + i).toDouble(), game, ad, -20.0))
        return s
    }

    @Test fun verdictGameThenAd() {
        val v = Verdict(minAdSecs = 3.0)
        assertEquals(Heard.GAME, feedFrames(v, 10, 0.8, 0.05))
        assertEquals(Heard.GAME, feedFrames(v, 2, 0.0, 0.9, 10))
        assertEquals(Heard.AD, feedFrames(v, 12, 0.0, 0.9, 12))
    }

    @Test fun verdictIgnoresSilence() {
        val v = Verdict()
        for (i in 0 until 10) v.add(Frame(i.toDouble(), 0.0, 0.9, -80.0))
        assertEquals(Heard.UNSURE, v.state)
    }

    // ---- feed ----
    @Test fun parseEventBasics() {
        assertEquals(GameStatus.LIVE, parseEvent(event("GB", "MIN")).status)
        assertEquals(GameStatus.BREAK, parseEvent(event("GB", "MIN", name = "STATUS_HALFTIME")).status)
        assertEquals(GameStatus.FINAL, parseEvent(event("GB", "MIN", state = "post")).status)
        val gi = parseEvent(event("GB", "MIN", period = 4, clock = 125.0, sa = 24, sb = 21), "football/nfl")
        assertEquals(linkedMapOf("GB" to 24, "MIN" to 21), gi.scores)
        assertEquals(4, gi.period)
    }

    @Test fun listGamesForPicker() {
        val games = listGames(board(event("GB", "MIN", state = "post"), event("DET", "CHI")), "football/nfl")
        assertEquals("CHI @ DET", games.first().shortName) // live game first
        assertEquals("DET", games.first().home)
    }

    @Test fun feedTimeoutIsBreakThenExpires() {
        val c = Clock()
        var b = board(event("GB", "MIN", play = "Pass complete for 8 yards."))
        val f = GameFeed(mapOf(1 to GameSlot(team = "GB")), { b }, { c.t }, pollSecs = 0.0)
        assertEquals(GameStatus.LIVE, f.poll()[1])
        b = board(event("GB", "MIN", play = "Timeout #1 by GB at 04:12."))
        assertEquals(GameStatus.BREAK, f.poll()[1])
        c.t = 200.0
        assertEquals(GameStatus.LIVE, f.poll()[1])
    }

    // ---- excitement ----
    @Test fun closeLateBeatsCloseEarlyBeatsBlowout() {
        val closeLate = excitement(nfl(24, 21, 4, 120.0), null)
        val closeEarly = excitement(nfl(7, 3, 1, 600.0), null)
        val blowout = excitement(nfl(38, 3, 4, 120.0), null)
        assertTrue(closeLate > closeEarly && closeEarly > blowout)
    }

    @Test fun bonusesAndImportance() {
        val base = excitement(nfl(17, 14, 3, 300.0), null)
        assertTrue(excitement(nfl(17, 14, 3, 300.0, redZone = true), null) > base)
        assertTrue(excitement(nfl(20, 20, 5, 400.0), null) > excitement(nfl(20, 17, 4, 30.0), null))
        assertEquals(2 * base, excitement(nfl(17, 14, 3, 300.0), GameSlot(importance = 2.0)), 0.002)
        assertEquals(0.0, excitement(nfl(1, 0, 4, 0.0, GameStatus.FINAL), null), 0.0)
    }

    @Test fun pickAgainstSpread() {
        val g = nfl(10, 24, 4, 300.0)
        assertTrue(closeness(g, "AAA +13.5") > 0.9)
        assertTrue(closeness(g, "AAA +13.5") > closeness(g))
    }

    // ---- controller ----
    private fun setup(info: Map<Int, GameInfo>, status: Map<Int, GameStatus> = info.mapValues { GameStatus.LIVE },
                      plays: MutableMap<Int, Pair<String, Double>> = mutableMapOf(),
                      dwell: Double = 45.0): Triple<Controller, Navigator, Clock> {
        val c = Clock()
        val nav = Navigator(Recorder(), LAYOUTS.getValue(4), home = false, sleep = {})
        // Build a feed with no slots and inject state.
        val feed = GameFeed(emptyMap(), { JSONObject() }, { c.t }, pollSecs = 1e9)
        feed.info.putAll(info)
        feed.status.putAll(status)
        val ctrl = Controller(nav, listOf(1, 2, 3, 4), feed, minDwellSecs = dwell, clock = { c.t })
        // inject plays via reflection-free hook
        plays.forEach { (box, p) -> injectPlay(feed, box, p) }
        return Triple(ctrl, nav, c)
    }

    private fun injectPlay(feed: GameFeed, box: Int, p: Pair<String, Double>) {
        feed.recordPlay(box, p.first, p.second, p.second)
    }

    @Test fun commercialGoesToMostExcitingGame() {
        val info = mapOf(1 to nfl(10, 7, 2, 300.0), 2 to nfl(35, 3, 4, 200.0),
            3 to nfl(21, 20, 4, 90.0), 4 to nfl(14, 10, 1, 500.0))
        val (ctrl, nav, c) = setup(info)
        c.t = 100.0
        assertTrue(ctrl.step(Heard.AD))
        assertEquals(3, nav.current)
    }

    @Test fun skipsFinalAndBreakGames() {
        val info = (1..4).associateWith { nfl(14, 10, 3, 400.0) }
        val status = mapOf(1 to GameStatus.LIVE, 2 to GameStatus.FINAL, 3 to GameStatus.BREAK, 4 to GameStatus.LIVE)
        val (ctrl, nav, c) = setup(info, status)
        c.t = 100.0
        ctrl.step(Heard.AD)
        assertEquals(4, nav.current)
    }

    @Test fun upgradeFromBlowoutAfterDwell() {
        val info = mutableMapOf(1 to nfl(38, 3, 4, 400.0), 2 to nfl(24, 24, 4, 100.0),
            3 to nfl(0, 0, 1, 900.0), 4 to nfl(0, 0, 1, 900.0))
        val (ctrl, nav, c) = setup(info)
        c.t = 100.0
        assertTrue(ctrl.step(Heard.GAME))
        assertEquals(2, nav.current)
    }

    @Test fun touchdownSwitchesRightAwayOnce() {
        val info = (1..4).associateWith { nfl(14, 10, 3, 400.0) }
        val (ctrl, nav, c) = setup(info, plays = mutableMapOf(1 to ("TOUCHDOWN, J.Love pass" to 95.0)))
        ctrl.micOn = false // live data only: goes once the TV should have shown it
        c.t = 100.0
        assertFalse(ctrl.step(Heard.GAME))
        c.t = 116.0
        assertTrue(ctrl.step(Heard.GAME))
        assertNotEquals(1, nav.current)
        nav.current = 1
        c.t = 200.0
        assertFalse(ctrl.step(Heard.GAME))
    }

    @Test fun stalePlayIgnored() {
        val info = (1..4).associateWith { nfl(14, 10, 3, 400.0) }
        val (ctrl, _, c) = setup(info, plays = mutableMapOf(1 to ("Field goal is GOOD" to 10.0)))
        c.t = 100.0
        assertFalse(ctrl.step(Heard.GAME))
    }

    @Test fun halftimeTriggersSwitch() {
        val info = (1..4).associateWith { nfl(14, 10, 2, 0.0) }.toMutableMap()
        info[1] = nfl(14, 10, 2, 0.0, GameStatus.BREAK, statusName = "STATUS_HALFTIME")
        val status = mapOf(1 to GameStatus.BREAK, 2 to GameStatus.LIVE, 3 to GameStatus.LIVE, 4 to GameStatus.LIVE)
        val (ctrl, nav, c) = setup(info, status)
        c.t = 100.0
        assertFalse("the TV is still behind", ctrl.step(Heard.GAME))
        c.t = 141.0
        assertTrue(ctrl.step(Heard.GAME))
        assertNotEquals(1, nav.current)
    }

    @Test fun returnsToImportantGameAfterBreak() {
        val info = mapOf(1 to nfl(14, 10, 3, 400.0), 2 to nfl(14, 10, 3, 400.0),
            3 to nfl(0, 30, 3, 400.0), 4 to nfl(0, 30, 3, 400.0))
        val (ctrl, nav, c) = setup(info)
        ctrl.games = mapOf(1 to GameSlot(importance = 2.0))
        c.t = 100.0
        ctrl.step(Heard.AD)
        assertEquals(2, nav.current)
        c.t = 160.0
        assertFalse(ctrl.step(Heard.GAME))
        c.t = 260.0
        assertTrue(ctrl.step(Heard.GAME))
        assertEquals(1, nav.current)
    }

    @Test fun startupPlaysAreOldNews() {
        val c = Clock()
        c.t = 1000.0
        val f = GameFeed(mapOf(1 to GameSlot(team = "GB")), { board(event("GB", "MIN", play = "TOUCHDOWN GB")) },
            { c.t }, pollSecs = 0.0)
        f.poll()
        assertEquals(GameStatus.LIVE, f.status[1])
        val nav = Navigator(Recorder(), LAYOUTS.getValue(2), home = false, sleep = {})
        val ctrl = Controller(nav, listOf(1, 2), f, clock = { c.t })
        assertFalse(ctrl.step(Heard.GAME))
        assertNull(f.latestPlay(2))
    }

    // ---- detection fixes ----
    @Test fun announcersWithLittleCrowdIsGameNotAd() {
        // What a game sounds like through a phone mic: mostly talk, a bit of crowd, TV-ish.
        val (game, ad) = heuristicScores(mapOf("Speech" to 0.8f, "Crowd" to 0.05f, "Television" to 0.4f,
            "Music" to 0.05f))
        assertTrue(game > ad)
        val (g2, a2) = heuristicScores(mapOf("Music" to 0.6f, "Pop music" to 0.2f, "Speech" to 0.3f))
        assertTrue(a2 > g2)
    }

    @Test fun learnedModelSeparatesYourTvsGameFromAds() {
        val m = SoundModel()
        val rnd = java.util.Random(1)
        fun sample(ad: Boolean) = DoubleArray(Features.SIZE) { i ->
            rnd.nextGaussian() + if (i < 40) (if (ad) 0.7 else -0.7) else 0.0
        }
        assertNull(m.adProbability(sample(false)))
        repeat(60) { m.add(sample(false), isAd = false) }
        repeat(60) { m.add(sample(true), isAd = true) }
        assertTrue(m.ready)
        var right = 0
        repeat(100) { if (m.adProbability(sample(false))!! < 0.5) right++ }
        repeat(100) { if (m.adProbability(sample(true))!! > 0.5) right++ }
        assertTrue("accuracy $right/200", right >= 170)
        val bytes = java.io.ByteArrayOutputStream().also { m.save(it) }.toByteArray()
        val copy = SoundModel().apply { load(bytes.inputStream()) }
        assertEquals(60, copy.adSeconds)
    }

    @Test fun micAdIgnoredRightAfterAPlay() {
        val info = (1..4).associateWith { nfl(14, 10, 3, 400.0) }
        val (ctrl, nav, c) = setup(info, plays = mutableMapOf(1 to ("Pass short right for 9 yards" to 80.0)))
        c.t = 100.0
        assertFalse(ctrl.step(Heard.AD))
        assertEquals(1, nav.current)
        c.t = 200.0 // play is old now: trust the mic
        assertTrue(ctrl.step(Heard.AD))
    }

    @Test fun markedCommercialSwitches() {
        val info = (1..4).associateWith { nfl(14, 10, 3, 400.0) }
        val (ctrl, nav, c) = setup(info)
        c.t = 100.0
        assertTrue(ctrl.commercialNow())
        assertNotEquals(1, nav.current)
    }

    @Test fun syncPathsAreShortAndAvoidBoxOne() {
        val grid = LAYOUTS.getValue(4)
        for (t in 1..4) assertEquals(2, syncPath(grid, t)!!.size)
        // From box 3 to box 4 the audio never passes through box 1.
        val path = syncPath(grid, 4)!!
        var b = 3
        for (k in path) { b = grid[b]?.get(k) ?: b; assertNotEquals(1, b) }
        for ((_, g) in LAYOUTS) for (t in g.keys) assertNotNull(syncPath(g, t))
    }
}
