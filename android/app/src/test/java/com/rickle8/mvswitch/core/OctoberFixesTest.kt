package com.rickle8.mvswitch.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Oct 25 game recordings showed, as tests. */
class OctoberFixesTest {
    private class Clock { var t = 0.0 }

    private fun nfl(a: Int, b: Int, period: Int, clock: Double, redZone: Boolean = false, down: Int? = null,
                    goal: Boolean = false, last: String? = null, league: String = "football/nfl") =
        GameInfo(status = GameStatus.LIVE, league = league, period = period, clock = clock,
            scores = linkedMapOf("AAA" to a, "BBB" to b), redZone = redZone, down = down, goalToGo = goal, lastPlay = last)

    private class Setup(val ctrl: Controller, val nav: Navigator, val c: Clock, val feed: GameFeed, val logs: MutableList<String>)

    private fun setup(): Setup {
        val c = Clock()
        val nav = Navigator(object : Remote { override fun press(key: Key) {} }, LAYOUTS.getValue(4), home = false, sleep = {})
        val feed = GameFeed(emptyMap(), { JSONObject() }, { c.t }, pollSecs = 1e9)
        (1..4).forEach { feed.info[it] = nfl(14, 10, 3, 400.0); feed.status[it] = GameStatus.LIVE }
        val logs = mutableListOf<String>()
        val ctrl = Controller(nav, listOf(1, 2, 3, 4), feed, clock = { c.t }, log = { logs += it })
        return Setup(ctrl, nav, c, feed, logs)
    }

    // ------------------------------------------------------------ the sound's own timing
    @Test fun aCommercialWobblingUnderTheLineKeepsItsClock() {
        // 3 s clearly commercial, 3 s of a voice over music (just under the line), and again.
        val pattern = listOf(0.9, 0.9, 0.9, 0.5, 0.5, 0.5)
        val bias = Bias(3.0, 6.0)
        val v = Verdict(adThreshold = 0.6, gameThreshold = 0.4)
        for (i in 0 until 10) v.add(Frame(i.toDouble(), 0.95, 0.05, -20.0), bias)
        var calledAt: Int? = null
        for (i in 0 until 30) {
            val p = pattern[i % pattern.size]
            if (v.add(Frame(10.0 + i, 1 - p, p, -20.0), bias) == Heard.AD) { calledAt = i; break }
        }
        // Before, every dip reset the clock and it never got there.
        assertTrue("called at $calledAt", calledAt != null && calledAt <= 9)
    }

    @Test fun espnsClockOnlySlowsTheSoundALittle() {
        fun until(bias: Bias): Int {
            val v = Verdict(adThreshold = 0.55, gameThreshold = 0.35)
            for (i in 0 until 12) v.add(Frame(i.toDouble(), 0.9, 0.05, -20.0), bias)
            for (i in 12 until 60) if (v.add(Frame(i.toDouble(), 0.05, 0.9, -20.0), bias) == Heard.AD) return i - 12
            return 99
        }
        // 14:27 on Oct 25: 88-98% commercial for 16 s while ESPN still had the clock running.
        val normal = until(Bias.NORMAL)
        assertTrue("clock ${until(Bias.CLOCK)} vs $normal", until(Bias.CLOCK) <= normal + 3)
        assertTrue("just back ${until(Bias.UNLIKELY)} vs $normal", until(Bias.UNLIKELY) <= normal + 3)
        assertTrue(until(Bias.CAMERA_AD) < until(Bias.NORMAL))
    }

    // ------------------------------------------------------------ ESPN never holds a clear commercial
    @Test fun clearCommercialGoesWhileEspnStillHasTheClockRunning() {
        val s = setup()
        s.c.t = 500.0
        s.feed.clockSeenRunning(1, 495.0)
        assertTrue(s.ctrl.clockRunning(1, s.c.t))
        assertFalse("a borderline call waits", s.ctrl.step(Heard.AD, adPct = 66))
        assertEquals(1, s.nav.current)
        s.c.t = 501.0
        assertTrue("a clear one goes", s.ctrl.step(Heard.AD, adPct = 92))
        assertNotEquals(1, s.nav.current)
    }

    @Test fun clearCommercialGoesRightAfterAnEspnPlay() {
        val s = setup()
        s.c.t = 500.0
        // The last play before the quarter ended, as ESPN posts it after the TV has gone to commercial.
        s.feed.recordPlay(1, "Pass Reception: J.McCarthy pass short right to D.Mooney for 4 yards", 495.0, 495.0)
        assertFalse(s.ctrl.step(Heard.AD, adPct = 65))
        s.c.t = 501.0
        assertTrue(s.ctrl.step(Heard.AD, adPct = 95))
    }

    // ------------------------------------------------------------ the camera
    @Test fun cameraSureOfACommercialMakesTheSoundQuick() {
        val s = setup()
        s.c.t = 500.0
        s.feed.clockSeenRunning(1, 498.0)
        assertEquals(Bias.CLOCK, s.ctrl.bias(1, s.c.t))
        s.ctrl.sights = s.c.t to mapOf(1 to Sight(adText = true))
        assertEquals(Bias.CAMERA_AD, s.ctrl.bias(1, s.c.t))
        s.ctrl.sights = s.c.t to mapOf(1 to Sight(bugGoneSecs = 60.0, look = 0.85))
        assertEquals(Bias.CAMERA_AD, s.ctrl.bias(1, s.c.t))
        // The score bug just not read isn't enough (box 1's went unread through a minute of live play).
        s.ctrl.sights = s.c.t to mapOf(1 to Sight(bugGoneSecs = 60.0))
        assertEquals(Bias.CLOCK, s.ctrl.bias(1, s.c.t))
        // Nor when it reads the score bug.
        s.ctrl.sights = s.c.t to mapOf(1 to Sight(bug = true, look = 0.95))
        assertEquals(Bias.CLOCK, s.ctrl.bias(1, s.c.t))
    }

    @Test fun itsTheGameOnlyCoversUntilThePictureChanges() {
        val s = setup()
        s.c.t = 100.0
        s.ctrl.userSaysGame(1)
        // The camera wrongly sees a commercial: your word holds it off.
        s.ctrl.sights = s.c.t to mapOf(1 to Sight(adText = true, look = 0.95))
        assertTrue(s.ctrl.userSaidGame(1, s.c.t))
        assertFalse(s.ctrl.cameraSeesAd(1, s.c.t))
        // Later the camera sees the game there, then a real commercial (the Geico one, 90 s after the tap).
        s.c.t = 150.0; s.ctrl.sights = s.c.t to mapOf(1 to Sight(bug = true, look = 0.05))
        assertTrue(s.ctrl.userSaidGame(1, s.c.t))
        s.c.t = 190.0; s.ctrl.sights = s.c.t to mapOf(1 to Sight(bugGoneSecs = 30.0, look = 0.85))
        assertFalse(s.ctrl.userSaidGame(1, s.c.t))
        assertTrue(s.ctrl.cameraSeesAd(1, s.c.t))
    }

    // ------------------------------------------------------------ your taps
    @Test fun aTapAboutABoxItAlreadyLeftDoesntMoveAgain() {
        val s = setup()
        s.c.t = 100.0
        s.nav.current = 2
        // You tapped "It's an ad" on box 3 while it was already moving you to box 2.
        assertFalse(s.ctrl.commercialNow(tapped = 3))
        assertEquals(2, s.nav.current)
        // It still counts: box 3 isn't somewhere to go back to right now.
        assertTrue(s.ctrl.looksOnBreak(3, s.c.t, s.feed.status))
    }

    @Test fun everyGameInCommercialStaysInsteadOfBouncing() {
        val s = setup()
        s.c.t = 100.0
        s.nav.current = 1
        for (b in 2..4) s.ctrl.userSaysAd(b)
        s.c.t = 105.0
        assertFalse(s.ctrl.commercialNow(tapped = 1))
        assertEquals(1, s.nav.current)
        assertTrue(s.logs.joinToString("\n"), s.logs.any { it.startsWith("Every game is in commercial") })
    }

    @Test fun markingTwoBoxesDoesntPingPong() {
        val s = setup()
        s.c.t = 100.0
        s.nav.current = 3
        // Boxes 1 and 4 went to commercial a while ago.
        s.ctrl.userSaysAd(1); s.ctrl.userSaysAd(4)
        s.c.t = 160.0
        assertTrue(s.ctrl.commercialNow(tapped = 3))
        assertEquals(2, s.nav.current)
        s.c.t = 163.0
        // Box 2 is in commercial too: not back to box 3, which you marked a moment ago.
        s.ctrl.commercialNow(tapped = 2)
        assertNotEquals(3, s.nav.current)
    }

    // ------------------------------------------------------------ RedZone-style priority
    @Test fun scoringChancesAndLateCloseGamesComeFirst() {
        val plain = excitement(nfl(14, 10, 3, 400.0), null)
        val red = excitement(nfl(14, 10, 3, 400.0, redZone = true), null)
        val goal = excitement(nfl(14, 10, 3, 400.0, redZone = true, goal = true), null)
        assertTrue(goal > red && red > plain)
        assertTrue(excitement(nfl(14, 10, 3, 400.0, down = 4), null) > plain)
        // A one-score game in the last two minutes beats a 10-point one.
        assertTrue(excitement(nfl(24, 20, 4, 90.0), null) > excitement(nfl(30, 20, 4, 90.0), null) + 0.5)
        // Kickoffs and extra points are dead time.
        assertTrue(excitement(nfl(14, 10, 3, 400.0, last = "A.Szmyt kicks 65 yards from CLV 35 to TEN 0."), null) < plain)
        // Basketball: a two-possession game in the last minute.
        val nba = { a: Int, b: Int -> nfl(a, b, 4, 40.0, league = "basketball/nba") }
        assertTrue(excitement(nba(101, 98), null) > excitement(nba(110, 98), null) + 0.5)
    }
}
