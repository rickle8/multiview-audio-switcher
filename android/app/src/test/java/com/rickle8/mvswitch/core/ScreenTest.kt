package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ScreenTest {
    private val w = 192
    private val h = 108

    /** A fake multiview grid: moving picture in every box, a score bar on games, an outline on the sound box. */
    private fun grid(t: Int, highlight: Int, inAds: Set<Int>, rnd: Random): FloatArray {
        val img = FloatArray(w * h)
        val rects = boxRects(4)
        for ((box, r) in rects) {
            val x0 = (r[0] * w).toInt(); val x1 = (r[2] * w).toInt()
            val y0 = (r[1] * h).toInt(); val y1 = (r[3] * h).toInt()
            for (y in y0 until y1) for (x in x0 until x1) {
                // Moving picture: drifting stripes plus noise.
                img[y * w + x] = (90 + 40 * kotlin.math.sin((x + t * 3 + box * 7) / 5.0) + rnd.nextGaussian() * 12).toFloat()
            }
            if (box !in inAds) {
                // Score bar: a fixed strip along the bottom with text-like blocks.
                for (y in y1 - 16 until y1 - 11) for (x in x0 + 14 until x1 - 14) {
                    img[y * w + x] = if ((x / 4) % 2 == 0) 230f else 20f
                }
            }
            if (box == highlight) {
                for (d in 0..2) {
                    for (x in x0 until x1) { img[(y0 + d) * w + x] = 250f; img[(y1 - 1 - d) * w + x] = 250f }
                    for (y in y0 until y1) { img[y * w + x0 + d] = 250f; img[y * w + x1 - 1 - d] = 250f }
                }
            }
        }
        return img
    }

    @Test fun seesTheHighlightedBox() {
        val sw = ScreenWatcher(4)
        val rnd = Random(1)
        var v: CameraView? = null
        for (t in 0 until 5) v = sw.frame(grid(t, 2, emptySet(), rnd))
        assertEquals(2, v!!.highlighted)
        for (t in 5 until 10) v = sw.frame(grid(t, 4, emptySet(), rnd))
        assertEquals(4, v!!.highlighted)
    }

    @Test fun tellsGamesFromCommercialsByTheScoreBar() {
        val sw = ScreenWatcher(4)
        val rnd = Random(2)
        var v: CameraView? = null
        for (t in 0 until 80) v = sw.frame(grid(t, 1, emptySet(), rnd))
        assertTrue("all games: ${v!!.scoreBar}", v.showingGame.values.all { it == true })
        // Box 3 cuts to a commercial: its score bar is gone.
        for (t in 80 until 95) v = sw.frame(grid(t, 1, setOf(3), rnd))
        assertEquals(false, v!!.showingGame[3])
        assertEquals(true, v.showingGame[2])
        // And back.
        for (t in 95 until 110) v = sw.frame(grid(t, 1, emptySet(), rnd))
        assertEquals(true, v!!.showingGame[3])
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
