package com.rickle8.mvswitch.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Yard lines: thin, straight, bright lines running across a green field. A wide shot of a football
 * field shows several (yard lines, sidelines, a row of hash marks); commercials almost never do. It
 * looks in one box's picture (the one the text reader straightened out), in the middle of the box:
 * not its edges, and not the bottom fifth where the score bar and a ticker run.
 */
object FieldLines {
    /** One look: how many long lines, and the longest as a share of the box's height. */
    data class Found(val lines: Int, val longest: Double)

    /** The size it looks at (each box is shrunk to this first). */
    const val W = 480
    const val H = 270
    /** The field-green grid that comes with each box picture (see [greenGrid]). */
    const val GW = 48
    const val GH = 27

    private const val THETAS = 120 // 1.5° apart
    private const val RHO_STEP = 2
    private val COS = DoubleArray(THETAS) { cos(PI * it / THETAS) }
    private val SIN = DoubleArray(THETAS) { sin(PI * it / THETAS) }
    private val DIAG = sqrt((W * W + H * H).toDouble()).toInt() + 2
    private val RHOS = (W + DIAG) / RHO_STEP + 2
    /** Brighter than the field a few pixels to both sides by at least this much (0..255). */
    private const val RIDGE = 16
    /** Ridge widths it looks for: a yard line in a wide shot up to a closer one. */
    private val SCALES = intArrayOf(2, 4)

    /** The box picture ([w] x [h] gray) shrunk to [W] x [H] by averaging. */
    fun shrink(gray: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(W * H)
        for (y in 0 until H) {
            val ya = y * h / H; val yb = maxOf(ya + 1, (y + 1) * h / H)
            for (x in 0 until W) {
                val xa = x * w / W; val xb = maxOf(xa + 1, (x + 1) * w / W)
                var s = 0; var n = 0
                for (yy in ya until yb) {
                    val row = yy * w
                    for (xx in xa until xb) { s += gray[row + xx].toInt() and 0xFF; n++ }
                }
                out[y * W + x] = s / n
            }
        }
        return out
    }

    /**
     * Lines in a box. [gray]: the box upright, [w] x [h]. [green]: per cell of a [gw] x [gh] grid over
     * the box, whether it's football-field green.
     */
    fun find(gray: ByteArray, w: Int, h: Int, green: BooleanArray, gw: Int = GW, gh: Int = GH): Found {
        if (w < 64 || h < 36 || gw < 4 || gh < 4 || green.size < gw * gh) return Found(0, 0.0)
        val img = shrink(gray, w, h)
        // Field around each cell: the cell itself, or 3 of its 4 neighbors (a cell can sample the line itself).
        val field = BooleanArray(gw * gh) { idx ->
            val i = idx % gw; val j = idx / gw
            if (green[idx]) true else {
                var k = 0
                if (i > 0 && green[idx - 1]) k++
                if (i < gw - 1 && green[idx + 1]) k++
                if (j > 0 && green[idx - gw]) k++
                if (j < gh - 1 && green[idx + gw]) k++
                k >= 3
            }
        }
        val x0 = (W * 0.05).toInt(); val x1 = (W * 0.95).toInt()
        val y0 = (H * 0.07).toInt(); val y1 = (H * 0.80).toInt()
        var fieldPx = 0
        val cap = (x1 - x0) * (y1 - y0) / 5
        val px = IntArray(cap); val py = IntArray(cap); val kind = ByteArray(cap)
        var n = 0
        for (y in y0 until y1) {
            val cy = y * gh / H
            for (x in x0 until x1) {
                if (!field[cy * gw + x * gw / W]) continue
                fieldPx++
                val c = img[y * W + x]
                if (c < 60) continue
                var k = 0
                for (d in SCALES) {
                    if (c - maxOf(img[y * W + x - d], img[y * W + x + d]) >= RIDGE) k = k or 1 // crosses the row: a steep line
                    if (c - maxOf(img[(y - d) * W + x], img[(y + d) * W + x]) >= RIDGE) k = k or 2 // crosses the column: a flat one
                }
                if (k == 0) continue
                // Busy texture all over (a crowd, foliage) isn't a field with lines on it.
                if (n == cap) return Found(0, 0.0)
                px[n] = x; py[n] = y; kind[n] = k.toByte(); n++
            }
        }
        // Too little field to say anything, or ridges everywhere.
        if (fieldPx < (x1 - x0) * (y1 - y0) / 10 || n > fieldPx / 6) return Found(0, 0.0)
        val density = n.toDouble() / fieldPx

        val acc = IntArray(THETAS * RHOS)
        val steep = BooleanArray(THETAS) { it <= THETAS / 3 || it >= THETAS * 2 / 3 } // normal within 60° of level
        val flat = BooleanArray(THETAS) { it >= THETAS / 6 && it <= THETAS * 5 / 6 }   // normal within 60° of upright
        fun vote(i: Int, add: Int) {
            val x = px[i]; val y = py[i]; val k = kind[i].toInt()
            for (t in 0 until THETAS) {
                if (!((k and 1 != 0 && steep[t]) || (k and 2 != 0 && flat[t]))) continue
                val r = ((x * COS[t] + y * SIN[t] + W) / RHO_STEP).toInt()
                acc[t * RHOS + r] += add
            }
        }
        for (i in 0 until n) vote(i, 1)

        val used = BooleanArray(n)
        val minVotes = (H * 0.22).toInt()
        val minSpan = H * 0.28
        val bins = IntArray((W + H) * 2 / 3 + 4)
        var lines = 0
        var longest = 0.0
        for (round in 0 until 10) {
            var best = -1; var bestV = 0
            for (t in 0 until THETAS) {
                val base = t * RHOS
                for (r in 1 until RHOS - 1) {
                    val v = acc[base + r - 1] + acc[base + r] + acc[base + r + 1]
                    if (v > bestV) { bestV = v; best = base + r }
                }
            }
            if (best < 0 || bestV < minVotes) break
            val t = best / RHOS
            val rho = (best % RHOS) * RHO_STEP - W + RHO_STEP / 2.0
            val cs = COS[t]; val sn = SIN[t]
            // The ridge pixels on this line, by where along it they sit (3 px apart).
            java.util.Arrays.fill(bins, 0)
            val offset = W + 2
            for (i in 0 until n) {
                if (used[i]) continue
                val x = px[i]; val y = py[i]
                if (abs(x * cs + y * sn - rho) > 2.6) continue
                used[i] = true
                vote(i, -1)
                val s = ((-x * sn + y * cs) + offset) / 3
                val b = s.toInt().coerceIn(0, bins.size - 1)
                bins[b]++
            }
            // The longest stretch with gaps of no more than 24 px, mostly covered, and far denser in
            // ridges than the field around it (scattered ridges line up by chance on any line).
            var bestRun = 0; var start = -1; var last = -1; var covered = 0; var count = 0
            fun close() {
                val len = last - start + 1
                val length = len * 3.0
                if (covered * 2 >= len && count >= maxOf(0.8 * length, 4 * density * length * 5.2)) bestRun = maxOf(bestRun, len)
            }
            for (b in bins.indices) {
                if (bins[b] == 0) continue
                if (start < 0 || b - last > 8) {
                    if (start >= 0) close()
                    start = b; covered = 0; count = 0
                }
                covered++; count += bins[b]; last = b
            }
            if (start >= 0) close()
            val span = bestRun * 3.0
            if (span >= minSpan) { lines++; longest = maxOf(longest, span / H) }
        }
        return Found(lines, longest)
    }

    /** Field-green grid for a box from its color: [isGreen] per cell, [GW] x [GH]. */
    inline fun greenGrid(isGreen: (i: Int, j: Int) -> Boolean): BooleanArray =
        BooleanArray(GW * GH) { idx -> isGreen(idx % GW, idx / GW) }
}
