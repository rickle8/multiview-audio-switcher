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
}
