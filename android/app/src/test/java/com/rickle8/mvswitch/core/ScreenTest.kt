package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ScreenTest {
    private val w = 384
    private val h = 216

    /**
     * A fake multiview grid: moving picture in every box, a score bug (lettering-like blocks)
     * on boxes showing a game, and the white outline on [outline] if given.
     */
    private fun grid(t: Int, outline: Int?, inAds: Set<Int>, rnd: Random, gain: Float = 1f): FloatArray {
        val img = FloatArray(w * h)
        for ((box, r) in boxRects(4)) {
            val x0 = (r[0] * w).toInt(); val x1 = (r[2] * w).toInt()
            val y0 = (r[1] * h).toInt(); val y1 = (r[3] * h).toInt()
            for (y in y0 until y1) for (x in x0 until x1) {
                img[y * w + x] = (90 + 40 * kotlin.math.sin((x + t * 5 + box * 7) / 6.0) * kotlin.math.cos((y - t * 3) / 9.0) +
                    rnd.nextGaussian() * 6).toFloat()
            }
            if (box in inAds) {
                // A commercial: different moving content, no bug (and a big moving bright logo, for spite).
                for (y in y0 + 30 until y0 + 60) for (x in x0 + 20 + (t % 30) until x0 + 70 + (t % 30)) img[y * w + x] = 210f
            } else {
                // Score bug along the bottom: blocks like letters and numbers.
                for (y in y1 - 26 until y1 - 14) for (x in x0 + 30 until x1 - 30) {
                    img[y * w + x] = if (((x - x0) / 5) % 3 == 0 || (y - y1 + 26) < 2) 230f else 30f
                }
            }
            if (box == outline) {
                for (d in 0..2) {
                    for (x in x0 until x1) { img[(y0 + d) * w + x] = 250f; img[(y1 - 1 - d) * w + x] = 250f }
                    for (y in y0 until y1) { img[y * w + x0 + d] = 250f; img[y * w + x1 - 1 - d] = 250f }
                }
            }
        }
        for (i in img.indices) img[i] *= gain
        return img
    }

    @Test fun seesTheOutlineAppearOnce() {
        val sw = ScreenWatcher(4)
        val rnd = Random(1)
        var onsets = mutableListOf<Int>()
        for (t in 0 until 20) sw.frame(grid(t, null, emptySet(), rnd)).outlineAppeared?.let { onsets += it }
        assertTrue("nothing yet: $onsets", onsets.isEmpty())
        // The sound moves to box 3: its outline flashes for a few seconds, then fades.
        for (t in 20 until 30) sw.frame(grid(t, 3, emptySet(), rnd)).outlineAppeared?.let { onsets += it }
        for (t in 30 until 50) sw.frame(grid(t, null, emptySet(), rnd)).outlineAppeared?.let { onsets += it }
        assertEquals(listOf(3), onsets)
        // Then to box 2.
        for (t in 50 until 58) sw.frame(grid(t, 2, emptySet(), rnd)).outlineAppeared?.let { onsets += it }
        assertEquals(listOf(3, 2), onsets)
    }

    @Test fun learnsEachScoreBugAndSpotsCommercials() {
        val sw = ScreenWatcher(4)
        val rnd = Random(2)
        var v: CameraView? = null
        for (t in 0 until 120) v = sw.frame(grid(t, null, emptySet(), rnd))
        assertTrue("all games: ${v!!.scoreBar}", v.showingGame.values.all { it == true })
        // It found the bug near the bottom of each box.
        assertTrue(v.bugArea[1]!![1] > 0.6)
        // Box 3 cuts to a commercial.
        for (t in 120 until 140) v = sw.frame(grid(t, null, setOf(3), rnd))
        assertEquals("bars ${v!!.scoreBar} area ${v.bugArea[3]?.toList()}", false, v.showingGame[3])
        assertEquals(true, v.showingGame[2])
        // The room gets darker (or the camera changes exposure): still reads the games right.
        for (t in 140 until 160) v = sw.frame(grid(t, null, setOf(3), rnd, gain = 0.7f))
        assertEquals("dark bars ${v!!.scoreBar}", true, v.showingGame[1])
        assertEquals("dark bars ${v.scoreBar}", false, v.showingGame[3])
        // Box 3 back from commercial.
        for (t in 160 until 180) v = sw.frame(grid(t, null, emptySet(), rnd))
        assertEquals("back bars ${v!!.scoreBar}", true, v.showingGame[3])
    }

    @Test fun neverLearnsACommercialAsTheBug() {
        val sw = ScreenWatcher(4)
        val rnd = Random(3)
        var v: CameraView? = null
        // Box 4 starts in a commercial the app knows about: it learns nothing there.
        for (t in 0 until 100) v = sw.frame(grid(t, null, setOf(4), rnd), adLikely = setOf(4))
        assertEquals(null, v!!.showingGame[4])
        for (t in 100 until 220) v = sw.frame(grid(t, null, emptySet(), rnd))
        assertEquals(true, v!!.showingGame[4])
    }

    @Test fun straightensATiltedGrid() {
        // A frame where the grid occupies a skewed quad: the warp should recover the corners' brightness.
        val fw = 320; val fh = 240
        val px = ByteArray(fw * fh) { 0 }
        // Bright marker near where the grid's top-left corner is.
        for (y in 40..44) for (x in 60..64) px[y * fw + x] = 200.toByte()
        val g = GrayFrame(fw, fh, px)
        val corners = listOf(62.0 / fw to 42.0 / fh, 280.0 / fw to 30.0 / fh, 300.0 / fw to 220.0 / fh, 40.0 / fw to 200.0 / fh)
        val out = warpGrid(g, corners)
        assertTrue(out[0] > 150f)
        assertTrue(out[w * h - 1] < 50f)
    }

    /** A camera frame: a still room, with the TV (moving picture) inside the given quad. */
    private fun room(fw: Int, fh: Int, quad: List<Pair<Double, Double>>, t: Int, rnd: Random): GrayFrame {
        val px = ByteArray(fw * fh)
        val hom = Homography(quad.map { (x, y) -> x * fw to y * fh })
        // Room: fixed pattern plus sensor noise.
        for (y in 0 until fh) for (x in 0 until fw) {
            val v = 60 + 30 * kotlin.math.sin(x / 13.0) * kotlin.math.cos(y / 17.0) + rnd.nextGaussian() * 3
            px[y * fw + x] = v.toInt().coerceIn(0, 255).toByte()
        }
        // Screen: sample the unit square densely and paint moving content.
        val steps = 400
        for (j in 0 until steps) for (i in 0 until steps) {
            val u = (i + 0.5) / steps; val v = (j + 0.5) / steps
            val gap = kotlin.math.abs(u - 0.5) < 0.005 || kotlin.math.abs(v - 0.5) < 0.008
            val bar = (v % 0.5) > 0.42 && (v % 0.5) < 0.46
            val value = when {
                gap -> 10.0
                bar -> 220.0
                else -> 120 + 80 * kotlin.math.sin(u * 40 + t * 1.3 + v * 7) + rnd.nextGaussian() * 10
            }
            val (x, y) = hom.map(u, v)
            val xi = x.toInt(); val yi = y.toInt()
            if (xi in 0 until fw && yi in 0 until fh) px[yi * fw + xi] = value.toInt().coerceIn(0, 255).toByte()
        }
        return GrayFrame(fw, fh, px)
    }

    private fun near(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>, tol: Double) =
        a.zip(b).all { (p, q) -> kotlin.math.abs(p.first - q.first) < tol && kotlin.math.abs(p.second - q.second) < tol }

    @Test fun findsTheTvOnItsOwnSideways() {
        val quad = listOf(0.18 to 0.22, 0.86 to 0.18, 0.88 to 0.80, 0.16 to 0.76)
        val gf = GridFinder()
        val rnd = Random(5)
        for (t in 0 until 14) gf.add(room(320, 180, quad, t, rnd))
        assertTrue(gf.ready)
        val found = gf.find()!!
        assertTrue("found $found", near(found, quad, 0.04))
    }

    @Test fun findsTheTvWithThePhoneUpright() {
        // Phone standing vertical: the TV is a band across the middle of a tall picture.
        val quad = listOf(0.05 to 0.36, 0.95 to 0.37, 0.94 to 0.62, 0.06 to 0.61)
        val gf = GridFinder()
        val rnd = Random(6)
        for (t in 0 until 14) gf.add(room(180, 320, quad, t, rnd))
        val found = gf.find()!!
        assertTrue("found $found", near(found, quad, 0.04))
    }

    @Test fun noTvMeansNoGuess() {
        val gf = GridFinder()
        val rnd = Random(7)
        val px = ByteArray(320 * 180) { 50 }
        for (t in 0 until 14) gf.add(GrayFrame(320, 180, ByteArray(320 * 180) { (50 + rnd.nextGaussian() * 2).toInt().toByte() }))
        assertEquals(null, gf.find())
    }
}
