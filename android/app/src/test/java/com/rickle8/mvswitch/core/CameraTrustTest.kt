package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTrustTest {
    @Test fun outlineHasToProveItselfOnTheAppsOwnMoves() {
        val o = OutlineTrust()
        // Not proven yet: a flash nobody in the app caused is only asked about.
        assertEquals(OutlineTrust.Action.ASK, o.flash(3, 10.0, current = 1))
        // Three app moves, each flashing where it went.
        var t = 20.0
        for (target in listOf(2, 4, 3)) {
            o.appMoved(target, emptySet(), t, t + 0.4)
            assertEquals(OutlineTrust.Action.NOTHING, o.flash(target, t + 0.8, current = target))
            t += 30; o.tick(t)
        }
        assertTrue(o.trusted)
        // Now you move it with the TV remote: it follows.
        assertEquals(OutlineTrust.Action.FOLLOW, o.flash(1, t + 5, current = 3))
        // A flash on the box it already thinks has the sound changes nothing.
        assertEquals(OutlineTrust.Action.NOTHING, o.flash(1, t + 9, current = 1))
    }

    @Test fun oneWrongReadingTakesTheTrustAway() {
        val o = OutlineTrust()
        var t = 0.0
        repeat(3) { o.appMoved(2, emptySet(), t, t + 0.4); o.flash(2, t + 1, 2); t += 30; o.tick(t) }
        assertTrue(o.trusted)
        // The app moved to box 4, but the outline showed up on box 1: it's misreading.
        o.appMoved(4, setOf(2), t, t + 0.8); o.flash(1, t + 1.2, 4); t += 30; o.tick(t)
        assertFalse(o.trusted)
        assertEquals(OutlineTrust.Action.ASK, o.flash(3, t + 5, current = 4))
    }

    @Test fun passingThroughOrMissingIsntProof() {
        val o = OutlineTrust()
        var t = 0.0
        // Two-key moves where only the box in between was seen, and moves with no flash at all.
        repeat(3) { o.appMoved(4, setOf(2), t, t + 0.8); o.flash(2, t + 0.3, 4); t += 30; o.tick(t) }
        repeat(3) { o.appMoved(3, emptySet(), t, t + 0.4); t += 30; o.tick(t) }
        assertFalse(o.trusted)
        assertEquals(6, o.missed)
    }

    @Test fun tooManyMovesMeansItsMisreading() {
        val o = OutlineTrust()
        var t = 0.0
        repeat(3) { o.appMoved(2, emptySet(), t, t + 0.4); o.flash(2, t + 1, 2); t += 30; o.tick(t) }
        assertEquals(OutlineTrust.Action.FOLLOW, o.flash(1, t + 10, 2))
        assertEquals(OutlineTrust.Action.FOLLOW, o.flash(3, t + 30, 1))
        assertEquals(OutlineTrust.Action.FOLLOW, o.flash(4, t + 50, 3))
        assertEquals(OutlineTrust.Action.ASK, o.flash(2, t + 70, 4))
        assertFalse(o.trusted)
    }

    @Test fun yourAnswersCount() {
        val o = OutlineTrust()
        repeat(3) { o.answered(true) }
        assertTrue(o.trusted)
        o.answered(false)
        assertFalse(o.trusted)
    }

    private fun yuv(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        return Triple(y.toInt(), (128 + 0.564 * (b - y)).toInt(), (128 + 0.713 * (r - y)).toInt())
    }

    private fun green(r: Int, g: Int, b: Int) = yuv(r, g, b).let { (y, u, v) -> FieldWatch.isFieldGreen(y, u, v) }

    @Test fun knowsTheGreenOfAField() {
        assertTrue(green(60, 140, 60))     // turf under lights
        assertTrue(green(45, 95, 40))      // darker grass
        assertTrue(green(90, 150, 70))     // yellowish grass
        assertFalse(green(100, 160, 230))  // sky
        assertFalse(green(220, 170, 140))  // skin
        assertFalse(green(240, 240, 240))  // white
        assertFalse(green(120, 120, 120))  // gray
        assertFalse(green(20, 30, 20))     // too dark
        assertFalse(green(200, 40, 40))    // red jersey
    }

    @Test fun fieldOnlyCountsOnceProvenBothWays() {
        val f = FieldWatch()
        var t = 0.0
        // Wide shots while the score text reads: field and game agree.
        repeat(4) { f.add(1, 0.5, t); f.check(1, t, game = true, commercial = false, sureAd = false); t += 6 }
        assertFalse("not proven in a commercial yet", f.trusted(1))
        // A commercial: no field.
        t += 10
        repeat(3) { f.add(1, 0.02, t); t += 1 }
        f.check(1, t, game = false, commercial = true, sureAd = false)
        assertTrue(f.trusted(1))
        assertFalse(f.fieldNow(1, t))
        // A commercial it's sure of showing lots of green (a golf ad): it starts over.
        f.add(1, 0.6, t)
        f.check(1, t, game = false, commercial = true, sureAd = true)
        assertFalse(f.trusted(1))
    }
}
