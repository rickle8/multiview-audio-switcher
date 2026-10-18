package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VisualLookTest {
    private val w = 384; private val h = 216

    /**
     * Pictures for the four boxes at time [t]: [kinds] per box is "game" (a field panning slowly with
     * a score bar that stays put along the bottom, a cut now and then), "ad" (a new scene every
     * couple of seconds, no score bar, sometimes black bars), or "fieldAd" (an ad shot on a field).
     */
    private fun picture(t: Double, kinds: Map<Int, String>, rnd: Random, seed: Int): FloatArray {
        val img = FloatArray(w * h)
        for ((box, r) in boxRects(4)) {
            val kind = kinds[box] ?: "game"
            val x0 = (r[0] * w).toInt(); val x1 = (r[2] * w).toInt()
            val y0 = (r[1] * h).toInt(); val y1 = (r[3] * h).toInt()
            val bw = x1 - x0; val bh = y1 - y0
            val shot = when (kind) { "game" -> (t / 12).toInt(); else -> (t / 2.2).toInt() } + box * 31 + seed
            val sr = Random(shot.toLong())
            val base = 40 + sr.nextInt(150)
            val stripes = 6 + sr.nextInt(10)
            val letterbox = kind == "ad" && sr.nextInt(3) == 0
            val pan = when (kind) { "game" -> t * 9; else -> t * (sr.nextInt(40) - 20) }
            for (j in 0 until bh) for (i in 0 until bw) {
                val fy = j.toDouble() / bh; val fx = i.toDouble() / bw
                var v = when (kind) {
                    "game", "fieldAd" -> 95 + 25 * kotlin.math.sin((fx * stripes + pan / 20) * 6.28) + (if ((i + pan.toInt()) % 23 == 0) 80 else 0)
                    else -> base + 60 * kotlin.math.sin(fx * stripes * 3 + fy * (sr.nextInt(5) + 1) * 4 + pan / 10) * kotlin.math.cos(fy * 7.0)
                }
                if (kind == "game" && fy in 0.80..0.92 && fx in 0.1..0.9) v = if ((i / 4 + j / 3) % 3 == 0) 235.0 else 25.0
                if (letterbox && (fy < 0.12 || fy > 0.88)) v = 8.0
                img[(y0 + j) * w + x0 + i] = (v + rnd.nextGaussian() * 4).toFloat().coerceIn(0f, 255f)
            }
        }
        return img
    }

    private fun green(kinds: Map<Int, String>) = kinds.mapValues { (_, k) -> if (k == "ad") 0.05 else 0.55 }

    /** Plays [secs] of TV at 4 looks a second, labeling with [teach] (box to commercial or not). */
    private fun play(v: VisualLook, from: Double, secs: Double, kinds: Map<Int, String>, rnd: Random, seed: Int,
                     teach: Boolean = true): Double {
        var t = from
        while (t < from + secs) {
            v.frame(picture(t, kinds, rnd, seed), t, green(kinds))
            if (teach) for ((b, k) in kinds) v.label(b, k != "game", t)
            t += 0.25
        }
        return t
    }

    @Test fun learnsWhatTheGameAndCommercialsLookLike() {
        val rnd = Random(4)
        val v = VisualLook()
        var t = 0.0
        // Boxes 1-2 the game, 3-4 commercials; then they swap, a few times over.
        repeat(4) { k ->
            val kinds = if (k % 2 == 0) mapOf(1 to "game", 2 to "game", 3 to "ad", 4 to "ad")
                else mapOf(1 to "ad", 2 to "ad", 3 to "game", 4 to "game")
            t = play(v, t, 90.0, kinds, rnd, k * 100)
        }
        assertTrue(v.describe(), v.trusted)
        // New footage it hasn't learned from: it calls both right.
        val kinds = mapOf(1 to "game", 2 to "ad", 3 to "game", 4 to "ad")
        t = play(v, t, 10.0, kinds, rnd, 999, teach = false)
        val g = v.adChance(1, t)!!; val a = v.adChance(2, t)!!
        assertTrue("game looked ${g}", g < 0.25)
        assertTrue("commercial looked ${a}", a > 0.75)
        // An ad shot on a field (green, but cutting, no score bar) it never mistakes for the game.
        t = play(v, t, 10.0, mapOf(1 to "game", 2 to "fieldAd", 3 to "game", 4 to "ad"), rnd, 555, teach = false)
        val fa = v.adChance(2, t)!!
        assertTrue("field ad looked ${fa}", fa > 0.25)
    }

    @Test fun saysNothingUntilItHasProvenItself() {
        val rnd = Random(5)
        val v = VisualLook()
        // Only the game so far: nothing to tell a commercial from.
        val t = play(v, 0.0, 60.0, mapOf(1 to "game", 2 to "game", 3 to "game", 4 to "game"), rnd, 1)
        assertFalse(v.trusted)
        assertNull(v.adChance(1, t))
    }

    @Test fun keepsWhatItLearnedAcrossRestarts() {
        val rnd = Random(6)
        val v = VisualLook()
        var t = 0.0
        repeat(4) { k ->
            val kinds = if (k % 2 == 0) mapOf(1 to "game", 2 to "ad", 3 to "game", 4 to "ad")
                else mapOf(1 to "ad", 2 to "game", 3 to "ad", 4 to "game")
            t = play(v, t, 90.0, kinds, rnd, k * 10)
        }
        assertTrue(v.trusted)
        val out = java.io.ByteArrayOutputStream(); v.save(out)
        val again = VisualLook()
        again.load(java.io.ByteArrayInputStream(out.toByteArray()))
        again.loadChecks(v.checksText())
        assertTrue(again.describe(), again.trusted)
        t = play(again, t, 10.0, mapOf(1 to "game", 2 to "ad", 3 to "game", 4 to "ad"), rnd, 77, teach = false)
        assertNotNull(again.adChance(1, t))
    }

    @Test fun itsWordCountsWithTheOtherSigns() {
        assertEquals(true, Sight(look = 0.05).word)
        assertEquals(false, Sight(look = 0.95).word)
        assertEquals(null, Sight(look = 0.5).word)
        // It never holds the sound, but sure it's a commercial, unsure sound is one.
        assertEquals(Heard.AD, Pairing.decide(Heard.UNSURE, 35, false, null, Sight(look = 0.95), false).heard)
        assertEquals(Heard.AD, Pairing.decide(Heard.AD, 65, false, null, Sight(look = 0.05), false).heard)
        assertEquals(Heard.GAME, Pairing.decide(Heard.UNSURE, 50, false, null, Sight(look = 0.05), false).heard)
    }
}
