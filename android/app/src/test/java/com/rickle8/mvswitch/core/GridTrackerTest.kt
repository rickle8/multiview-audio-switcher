package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GridTrackerTest {
    private val fw = 320
    private val fh = 180

    /** A camera frame: a still room, the TV (four moving games) inside [quad]; [stillBox] shows a frozen picture. */
    private fun room(quad: List<Pair<Double, Double>>, t: Int, rnd: Random, stillBox: Int? = null): GrayFrame {
        val px = ByteArray(fw * fh)
        val hom = Homography(quad.map { (x, y) -> x * fw to y * fh })
        for (y in 0 until fh) for (x in 0 until fw) {
            val v = 60 + 30 * kotlin.math.sin(x / 13.0) * kotlin.math.cos(y / 17.0) + rnd.nextGaussian() * 3
            px[y * fw + x] = v.toInt().coerceIn(0, 255).toByte()
        }
        val steps = 400
        for (j in 0 until steps) for (i in 0 until steps) {
            val u = (i + 0.5) / steps; val v = (j + 0.5) / steps
            val box = (if (v < 0.5) 1 else 3) + (if (u < 0.5) 0 else 1)
            val gap = kotlin.math.abs(u - 0.5) < 0.005 || kotlin.math.abs(v - 0.5) < 0.008
            val value = when {
                gap -> 10.0
                box == stillBox -> 120 + 60 * kotlin.math.sin(u * 40 + v * 7)
                else -> 120 + 80 * kotlin.math.sin(u * 40 + t * 1.3 + v * 7) + rnd.nextGaussian() * 10
            }
            val (x, y) = hom.map(u, v)
            val xi = x.toInt(); val yi = y.toInt()
            if (xi in 0 until fw && yi in 0 until fh) px[yi * fw + xi] = value.toInt().coerceIn(0, 255).toByte()
        }
        return GrayFrame(fw, fh, px)
    }

    private val quad = listOf(0.18 to 0.22, 0.86 to 0.18, 0.88 to 0.80, 0.16 to 0.76)

    /** Where the first find puts the TV (what the app saves). */
    private fun firstFind(q: List<Pair<Double, Double>>, rnd: Random): List<Pair<Double, Double>> {
        val gf = GridFinder()
        for (t in 0 until 14) gf.add(room(q, t, rnd))
        return gf.find()!!
    }

    private fun near(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>, tol: Double) =
        a.zip(b).all { (p, q) -> kotlin.math.abs(p.first - q.first) < tol && kotlin.math.abs(p.second - q.second) < tol }

    @Test fun leavesTheGridAloneWhenNothingMoved() {
        val rnd = Random(21)
        val base = firstFind(quad, rnd)
        val tr = GridTracker(everySecs = 20.0)
        var t = 0
        while (t < 4 * 120) { // two minutes, about 4 frames a second
            val moved = tr.frame(room(quad, t, rnd), t / 4.0, base)
            assertEquals("moved at ${t / 4.0} s", null, moved)
            t++
        }
        assertTrue("looked ${tr.looks} times", tr.looks >= 4)
        assertEquals(0, tr.moves)
    }

    @Test fun followsTheTvWhenThePhoneShifts() {
        val rnd = Random(22)
        val base = firstFind(quad, rnd)
        val tr = GridTracker(everySecs = 20.0)
        // The phone got nudged: the TV sits a bit lower and to the right in the picture.
        val shifted = quad.map { (x, y) -> x + 0.04 to y + 0.05 }
        var found: List<Pair<Double, Double>>? = null
        var t = 0
        while (found == null && t < 4 * 60) { found = tr.frame(room(shifted, t, rnd), t / 4.0, base); t++ }
        assertNotNull("never followed it", found)
        assertTrue("found $found", near(found!!, shifted, 0.04))
        // Without a bump it takes a second look agreeing (10 s after the first) before it moves the grid.
        assertTrue("took ${t / 4.0} s", t / 4.0 in 25.0..45.0)
    }

    @Test fun aBumpLooksRightAway() {
        val rnd = Random(23)
        val base = firstFind(quad, rnd)
        val tr = GridTracker(everySecs = 60.0)
        val shifted = quad.map { (x, y) -> x - 0.05 to y + 0.03 }
        var found: List<Pair<Double, Double>>? = null
        var t = 0
        while (found == null && t < 4 * 30) {
            if (t == 4 * 5) tr.bumped(t / 4.0)
            val q = if (t < 4 * 5) quad else shifted
            found = tr.frame(room(q, t, rnd), t / 4.0, base); t++
        }
        assertNotNull("never followed it", found)
        assertTrue("found $found", near(found!!, shifted, 0.04))
        assertTrue("took ${t / 4.0} s after the bump at 5 s", t / 4.0 < 15)
    }

    @Test fun aPhoneThatKeepsShakingStillGetsChecked() {
        val rnd = Random(25)
        val base = firstFind(quad, rnd)
        val tr = GridTracker(everySecs = 20.0)
        val shifted = quad.map { (x, y) -> x + 0.04 to y - 0.03 }
        var found: List<Pair<Double, Double>>? = null
        var t = 0
        // A bump every second (bass through the stand): it can't wait for stillness, so it looks on schedule.
        while (found == null && t < 4 * 60) {
            if (t % 4 == 0) tr.bumped(t / 4.0)
            found = tr.frame(room(shifted, t, rnd), t / 4.0, base); t++
        }
        assertNotNull("never looked", found)
    }

    @Test fun oneOddLookIsntBelieved() {
        val rnd = Random(24)
        val base = firstFind(quad, rnd)
        val tr = GridTracker(everySecs = 20.0)
        // During the first look one game is frozen, so less of the TV moves. Then everything's normal again.
        var t = 0
        while (t < 4 * 100) {
            val still = if (t / 4.0 < 30) 4 else null
            val moved = tr.frame(room(quad, t, rnd, stillBox = still), t / 4.0, base)
            assertEquals("moved at ${t / 4.0} s", null, moved)
            t++
        }
        assertEquals(0, tr.moves)
    }
}
