package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Box pictures drawn the way the camera sees them (soft, a little noisy), with and without a field. */
class FieldLinesTest {
    private class Pic(val w: Int = 960, val h: Int = 540) {
        val y = FloatArray(w * h)
        val green = BooleanArray(w * h)
    }

    private val rnd = java.util.Random(11)

    private fun fill(p: Pic, x0: Int, y0: Int, x1: Int, y1: Int, green: Boolean, v: (Int, Int) -> Float) {
        for (yy in max(0, y0) until min(p.h, y1)) for (xx in max(0, x0) until min(p.w, x1)) {
            p.y[yy * p.w + xx] = v(xx, yy); p.green[yy * p.w + xx] = green
        }
    }

    /** An anti-aliased line [width] px wide from (ax, ay) to (bx, by). */
    private fun line(p: Pic, ax: Double, ay: Double, bx: Double, by: Double, width: Double, v: Float) {
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        for (yy in max(0, (min(ay, by) - width - 2).toInt()) until min(p.h, (max(ay, by) + width + 2).toInt()))
            for (xx in max(0, (min(ax, bx) - width - 2).toInt()) until min(p.w, (max(ax, bx) + width + 2).toInt())) {
                val t = (((xx - ax) * dx + (yy - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(xx - (ax + t * dx), yy - (ay + t * dy))
                val cover = (width / 2 + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
                if (cover <= 0f) continue
                val i = yy * p.w + xx
                p.y[i] = p.y[i] * (1 - cover) + v * cover
                if (cover > 0.5f) p.green[i] = false
            }
    }

    private fun blob(p: Pic, cx: Int, cy: Int, rx: Int, ry: Int, v: Float) {
        for (yy in max(0, cy - ry) until min(p.h, cy + ry)) for (xx in max(0, cx - rx) until min(p.w, cx + rx)) {
            val a = (xx - cx).toDouble() / rx; val b = (yy - cy).toDouble() / ry
            if (a * a + b * b <= 1) { p.y[yy * p.w + xx] = v; p.green[yy * p.w + xx] = false }
        }
    }

    /** The camera's softness (box blur) and sensor noise. */
    private fun camera(p: Pic, blurs: Int = 2, noise: Float = 5f): ByteArray {
        var a = p.y.copyOf()
        repeat(blurs) {
            val b = FloatArray(a.size)
            for (yy in 0 until p.h) for (xx in 0 until p.w) {
                var s = 0f; var n = 0
                for (j in -1..1) for (i in -1..1) {
                    val x = xx + i; val y = yy + j
                    if (x in 0 until p.w && y in 0 until p.h) { s += a[y * p.w + x]; n++ }
                }
                b[yy * p.w + xx] = s / n
            }
            a = b
        }
        return ByteArray(a.size) { (a[it] + (rnd.nextGaussian() * noise).toFloat()).toInt().coerceIn(0, 255).toByte() }
    }

    private fun grid(p: Pic) = FieldLines.greenGrid { i, j ->
        p.green[((j + 0.5) * p.h / FieldLines.GH).toInt() * p.w + ((i + 0.5) * p.w / FieldLines.GW).toInt()]
    }

    private fun look(p: Pic, blurs: Int = 2, noise: Float = 5f) = FieldLines.find(camera(p, blurs, noise), p.w, p.h, grid(p))

    private fun turf(p: Pic, top: Int, base: Float = 100f) =
        fill(p, 0, top, p.w, p.h, true) { x, y -> base + 6f * kotlin.math.sin(x / 37.0 + y / 53.0).toFloat() }

    private fun stands(p: Pic, bottom: Int) {
        for (k in 0 until 400) {
            val x = rnd.nextInt(p.w); val y = rnd.nextInt(bottom); val v = 30f + rnd.nextInt(190)
            fill(p, x, y, x + 6 + rnd.nextInt(10), min(bottom, y + 6 + rnd.nextInt(10)), false) { _, _ -> v }
        }
    }

    private fun players(p: Pic, count: Int, top: Int, size: Int = 1) = repeat(count) {
        blob(p, rnd.nextInt(p.w), top + rnd.nextInt(p.h - top), 7 * size, 17 * size, 40f + rnd.nextInt(190))
    }

    private fun scoreBar(p: Pic) {
        fill(p, 40, (p.h * 0.86).toInt(), p.w - 40, (p.h * 0.95).toInt(), false) { _, _ -> 40f }
        for (k in 0 until 12) fill(p, 60 + k * 70, (p.h * 0.88).toInt(), 100 + k * 70, (p.h * 0.93).toInt(), false) { _, _ -> 220f }
    }

    /** The usual sideline shot: stands up top, yard lines leaning toward a point above the picture. */
    private fun sideline(lineV: Float = 215f, width: Double = 3.0): Pic {
        val p = Pic()
        val top = (p.h * 0.28).toInt()
        stands(p, top); turf(p, top)
        for (i in 0 until 7) {
            val xb = 40.0 + i * 145; val xt = p.w / 2 + (xb - p.w / 2) * 0.67
            line(p, xt, top.toDouble(), xb, p.h.toDouble(), width, lineV)
        }
        players(p, 10, top); scoreBar(p)
        return p
    }

    @Test fun seesTheYardLinesInAWideShot() {
        val f = look(sideline())
        assertTrue("found $f", f.lines >= 3)
        assertTrue(f.longest > 0.4)
    }

    @Test fun seesThemThroughASofterCamera() {
        // Thinner, dimmer lines, and blurrier: about what a 1080p camera across the room gets.
        val f = look(sideline(lineV = 165f, width = 2.0), blurs = 3, noise = 6f)
        assertTrue("found $f", f.lines >= 2)
    }

    @Test fun seesTheEndZoneView() {
        val p = Pic()
        turf(p, 0)
        var y = 70.0
        for (i in 0 until 6) { line(p, 30.0, y, p.w - 30.0, y + 8, 3.0, 215f); y += 40 + i * 12 }
        players(p, 6, 0); scoreBar(p)
        val f = look(p)
        assertTrue("found $f", f.lines >= 2)
    }

    @Test fun aCloseUpHasNoLines() {
        val p = Pic()
        turf(p, 0)
        line(p, 300.0, 300.0, 320.0, 400.0, 3.0, 215f)
        repeat(4) { blob(p, 150 + it * 220, 260, 110, 230, 40f + rnd.nextInt(180)) }
        val f = look(p)
        assertTrue("found $f", f.lines <= 1)
    }

    @Test fun aLawnIsntAField() {
        // Mower stripes: wide light and dark bands of green, a house up top.
        val p = Pic()
        fill(p, 0, 0, p.w, 150, false) { _, _ -> 180f }
        fill(p, 0, 150, p.w, p.h, true) { x, y -> if (((x + y) / 60) % 2 == 0) 88f else 116f }
        assertEquals(0, look(p).lines)
    }

    @Test fun aGolfFlagIsOneLineAtMost() {
        val p = Pic()
        turf(p, 0, 110f)
        line(p, 480.0, 160.0, 482.0, 350.0, 3.0, 230f)
        blob(p, 520, 360, 6, 6, 240f)
        assertTrue(look(p).lines <= 1)
    }

    @Test fun linesOffTheFieldDontCount() {
        // A road with lane markings: no field green anywhere.
        val p = Pic()
        fill(p, 0, 0, p.w, p.h, false) { _, _ -> 90f }
        for (i in 0 until 5) line(p, 100.0 + i * 180, 100.0, 140.0 + i * 180, 520.0, 3.0, 220f)
        assertEquals(0, look(p).lines)
    }

    @Test fun busyGreenIsntAField() {
        // Leaves and bushes: green, but rough texture all over.
        val p = Pic()
        fill(p, 0, 0, p.w, p.h, true) { _, _ -> 60f + rnd.nextInt(120) }
        assertTrue(look(p, blurs = 1).lines <= 1)
    }

    @Test fun theBoxEdgeIsntAYardLine() {
        // The multiview's light border along the box's edges, on a field with no lines in view.
        val p = Pic()
        turf(p, 0)
        line(p, 3.0, 0.0, 3.0, p.h.toDouble(), 4.0, 230f)
        line(p, 0.0, 3.0, p.w.toDouble(), 3.0, 4.0, 230f)
        line(p, p.w - 4.0, 0.0, p.w - 4.0, p.h.toDouble(), 4.0, 230f)
        assertEquals(0, look(p).lines)
    }

    @Test fun quickEnough() {
        val img = camera(sideline()); val g = grid(sideline())
        repeat(3) { FieldLines.find(img, 960, 540, g) }
        val t0 = System.nanoTime()
        repeat(10) { FieldLines.find(img, 960, 540, g) }
        val ms = (System.nanoTime() - t0) / 1e7
        println("field lines: %.1f ms a look".format(ms))
        assertTrue(ms < 80)
    }
}
