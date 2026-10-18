package com.rickle8.mvswitch.core

import kotlin.math.abs

/**
 * Watching the TV through a camera: which multiview box is highlighted (has the
 * sound), and which boxes are showing the game versus a commercial.
 */

/** Maps the unit square onto the four corners of the multiview grid in the camera picture. */
class Homography(corners: List<Pair<Double, Double>>) {
    // Corners in order: top-left, top-right, bottom-right, bottom-left.
    private val h: DoubleArray

    init {
        require(corners.size == 4)
        val src = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = src[i]
            val (u, v) = corners[i]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination for the 8 unknowns (h22 = 1).
        for (c in 0 until 8) {
            var p = c
            for (r in c + 1 until 8) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c].takeIf { abs(it) > 1e-12 } ?: 1e-12
            for (k in c until 9) a[c][k] /= d
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c]
                if (f != 0.0) for (k in c until 9) a[r][k] -= f * a[c][k]
            }
        }
        h = DoubleArray(9) { if (it < 8) a[it][8] else 1.0 }
    }

    /** Unit-square point (0..1, 0..1) to camera-picture point. */
    fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = h[6] * x + h[7] * y + h[8]
        return ((h[0] * x + h[1] * y + h[2]) / w) to ((h[3] * x + h[4] * y + h[5]) / w)
    }
}

/** A camera frame's brightness (0..255), row by row. */
class GrayFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/** Straightens the multiview grid out of a camera frame into a small W x H picture. */
fun warpGrid(frame: GrayFrame, corners: List<Pair<Double, Double>>, w: Int = 192, h: Int = 108): FloatArray {
    // Corners are given as fractions of the frame.
    val hom = Homography(corners.map { (x, y) -> x * (frame.width - 1) to y * (frame.height - 1) })
    val out = FloatArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
        val (u, v) = hom.map((i + 0.5) / w, (j + 0.5) / h)
        val x = u.toInt().coerceIn(0, frame.width - 1)
        val y = v.toInt().coerceIn(0, frame.height - 1)
        out[j * w + i] = frame.at(x, y).toFloat()
    }
    return out
}

/** Where each multiview box sits in the straightened grid, as fractions (left, top, right, bottom). */
fun boxRects(layout: Int): Map<Int, DoubleArray> = when (layout) {
    2 -> mapOf(1 to doubleArrayOf(0.0, 0.0, 0.5, 1.0), 2 to doubleArrayOf(0.5, 0.0, 1.0, 1.0))
    3 -> mapOf(1 to doubleArrayOf(0.0, 0.0, 0.66, 1.0), 2 to doubleArrayOf(0.66, 0.0, 1.0, 0.5),
        3 to doubleArrayOf(0.66, 0.5, 1.0, 1.0))
    else -> mapOf(1 to doubleArrayOf(0.0, 0.0, 0.5, 0.5), 2 to doubleArrayOf(0.5, 0.0, 1.0, 0.5),
        3 to doubleArrayOf(0.0, 0.5, 0.5, 1.0), 4 to doubleArrayOf(0.5, 0.5, 1.0, 1.0))
}

data class CameraView(
    /** The box with the highlight outline (the one with sound), once clear. */
    val highlighted: Int?,
    /** Per box: true = showing the game (its score bar is up), false = looks like a commercial, null = not sure yet. */
    val showingGame: Map<Int, Boolean?>,
    /** Per box, 0..100: how much of the learned score bar is on screen (for the setup screen). */
    val scoreBar: Map<Int, Int?>,
)

/**
 * Reads the straightened multiview grid, frame after frame (about 2 a second).
 *
 * Highlight: the box with sound has a bright outline; its edge is much brighter
 * than just inside it, unlike the other boxes.
 *
 * Game vs commercial: during a game the network's score bar sits still on screen
 * while the picture moves. Each box learns where its score bar is (pixels that
 * stay the same while the rest changes, and have sharp edges) and checks whether
 * it's still there. A commercial doesn't have it.
 */
class ScreenWatcher(private val layout: Int, private val w: Int = 192, private val h: Int = 108) {
    private val rects = boxRects(layout)
    private var lastBest: Int? = null
    private var bestRuns = 0
    var highlighted: Int? = null
        private set

    private class BoxState(val bw: Int, val bh: Int) {
        val n = bw * bh
        var prev: FloatArray? = null
        val slowMean = FloatArray(n)
        val motion = FloatArray(n) { 30f }
        var frames = 0
        /** The learned score bar: which pixels, and what they look like. */
        var mask: BooleanArray? = null
        var template: FloatArray? = null
        var sinceMask = 0
        val recentScores = ArrayDeque<Double>()
        var showing: Boolean? = null
    }

    private val boxes = HashMap<Int, BoxState>()

    private fun crop(img: FloatArray, r: DoubleArray, inset: Int): Pair<FloatArray, IntArray> {
        val x0 = (r[0] * w).toInt() + inset; val x1 = (r[2] * w).toInt() - inset
        val y0 = (r[1] * h).toInt() + inset; val y1 = (r[3] * h).toInt() - inset
        val bw = (x1 - x0).coerceAtLeast(1); val bh = (y1 - y0).coerceAtLeast(1)
        val out = FloatArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) out[y * bw + x] = img[(y0 + y) * w + (x0 + x)]
        return out to intArrayOf(bw, bh)
    }

    /** Edge brightness minus inside brightness, for one box. */
    private fun outlineContrast(img: FloatArray, r: DoubleArray): Double {
        val x0 = (r[0] * w).toInt(); val x1 = (r[2] * w).toInt() - 1
        val y0 = (r[1] * h).toInt(); val y1 = (r[3] * h).toInt() - 1
        fun ring(a: Int, b: Int): Double {
            var s = 0.0; var c = 0
            for (d in a..b) {
                for (x in x0 + d..x1 - d) { s += img[(y0 + d) * w + x] + img[(y1 - d) * w + x]; c += 2 }
                for (y in y0 + d..y1 - d) { s += img[y * w + x0 + d] + img[y * w + x1 - d]; c += 2 }
            }
            return if (c == 0) 0.0 else s / c
        }
        return ring(0, 3) - ring(5, 8)
    }

    /**
     * [gameLikely]: boxes known to be showing the game right now (from the sound or
     * ESPN); the score bar is only learned from those, never from a commercial.
     */
    fun frame(img: FloatArray, gameLikely: Set<Int> = rects.keys): CameraView {
        // ---- highlight
        val contrast = rects.mapValues { (_, r) -> outlineContrast(img, r) }
        val ranked = contrast.entries.sortedByDescending { it.value }
        val top = ranked.first()
        val rest = ranked.drop(1).map { it.value }.sorted()
        val restMid = if (rest.isEmpty()) 0.0 else rest[rest.size / 2]
        val clear = top.value - restMid > 18 && top.value > 8
        if (clear && top.key == lastBest) bestRuns++ else { bestRuns = if (clear) 1 else 0 }
        lastBest = if (clear) top.key else null
        if (bestRuns >= 3) highlighted = top.key

        // ---- score bar per box
        val showing = HashMap<Int, Boolean?>()
        val bars = HashMap<Int, Int?>()
        for ((box, r) in rects) {
            val (px, size) = crop(img, r, 10) // stay clear of the outline
            val s = boxes.getOrPut(box) { BoxState(size[0], size[1]) }
            if (s.n != px.size) continue
            val prev = s.prev
            for (i in 0 until s.n) {
                if (s.frames == 0) s.slowMean[i] = px[i]
                s.slowMean[i] += 0.03f * (px[i] - s.slowMean[i])
                if (prev != null) s.motion[i] += 0.05f * (abs(px[i] - prev[i]) - s.motion[i])
            }
            s.prev = px
            s.frames++
            s.sinceMask++
            // Re-learn the score bar every ~minute: still pixels with sharp edges, while most of the picture moves.
            if (s.frames >= 60 && box in gameLikely && (s.mask == null || s.sinceMask >= 120)) {
                val moving = s.motion.count { it > 6f }.toDouble() / s.n
                if (moving > 0.25) learnBar(s)
            }
            val mask = s.mask
            val tmpl = s.template
            if (mask != null && tmpl != null) {
                var hit = 0; var tot = 0
                for (i in 0 until s.n) if (mask[i]) { tot++; if (abs(px[i] - tmpl[i]) < 22f) hit++ }
                val score = if (tot == 0) 0.0 else hit.toDouble() / tot
                s.recentScores.addLast(score)
                while (s.recentScores.size > 12) s.recentScores.removeFirst()
                val avg = s.recentScores.average()
                // Hysteresis over ~6 s so a replay wipe or a flash doesn't flip it.
                s.showing = when {
                    s.recentScores.size >= 6 && avg >= 0.6 -> true
                    s.recentScores.size >= 6 && avg <= 0.3 -> false
                    else -> s.showing
                }
                bars[box] = (avg * 100).toInt()
            } else bars[box] = null
            showing[box] = s.showing
        }
        return CameraView(highlighted, showing, bars)
    }

    private fun learnBar(s: BoxState) {
        val m = BooleanArray(s.n)
        var count = 0
        for (y in 1 until s.bh - 1) for (x in 1 until s.bw - 1) {
            val i = y * s.bw + x
            if (s.motion[i] > 2.5f) continue
            val edge = maxOf(abs(s.slowMean[i] - s.slowMean[i - 1]), abs(s.slowMean[i] - s.slowMean[i + 1]),
                abs(s.slowMean[i] - s.slowMean[i - s.bw]), abs(s.slowMean[i] - s.slowMean[i + s.bw]))
            if (edge > 18f) { m[i] = true; count++ }
        }
        // A score bar is a modest strip of the picture: too little is nothing, too much is a frozen picture.
        val frac = count.toDouble() / s.n
        if (frac in 0.01..0.25) {
            s.mask = m
            s.template = s.slowMean.copyOf()
            s.sinceMask = 0
        }
    }

    /** You said the sound is on this box: trust that until the picture says otherwise. */
    fun setHighlighted(box: Int) { highlighted = box; bestRuns = 3; lastBest = box }
}

/**
 * Finds the TV in the camera picture on its own: the screen is the one big area
 * where the picture keeps changing (four live games), while the room stays still.
 * Feed it frames for several seconds, then ask for the grid's four corners.
 */
class GridFinder(private val cols: Int = 96) {
    private var rows = 0
    private var cellW = 1.0
    private var cellH = 1.0
    private var fw = 0
    private var fh = 0
    private var prev: FloatArray? = null
    private var motion: FloatArray? = null
    private var bright: FloatArray? = null
    var frames = 0
        private set

    private fun cells(f: GrayFrame): FloatArray {
        if (fw != f.width || fh != f.height) {
            fw = f.width; fh = f.height
            rows = maxOf(8, (cols * f.height.toDouble() / f.width).toInt())
            cellW = f.width.toDouble() / cols; cellH = f.height.toDouble() / rows
            prev = null; motion = null; bright = null; frames = 0
        }
        val out = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = (c * cellW).toInt(); val x1 = maxOf(x0 + 1, ((c + 1) * cellW).toInt())
            val y0 = (r * cellH).toInt(); val y1 = maxOf(y0 + 1, ((r + 1) * cellH).toInt())
            var s = 0; var n = 0
            var y = y0
            while (y < y1) { var x = x0; while (x < x1) { s += f.at(x, y); n++; x += 2 }; y += 2 }
            out[r * cols + c] = s.toFloat() / maxOf(1, n)
        }
        return out
    }

    fun add(f: GrayFrame) {
        val cur = cells(f)
        val p = prev
        val m = motion ?: FloatArray(cur.size).also { motion = it }
        val b = bright ?: FloatArray(cur.size).also { bright = it }
        if (p != null) for (i in cur.indices) m[i] += abs(cur[i] - p[i])
        for (i in cur.indices) b[i] += cur[i]
        prev = cur
        frames++
    }

    /** Enough frames to tell the moving screen from the still room. */
    val ready: Boolean get() = frames >= 12

    /** The grid's corners as fractions of the frame (TL, TR, BR, BL), or null if there's no clear TV. */
    fun find(): List<Pair<Double, Double>>? {
        val m = motion ?: return null
        if (frames < 4) return null
        val n = m.size
        val sorted = m.sorted()
        val hi = sorted[(n * 0.97).toInt().coerceAtMost(n - 1)]
        if (hi < frames * 2f) return null // nothing on screen is moving
        val thr = maxOf(hi * 0.18f, sorted[n / 2] * 3f)
        val on = BooleanArray(n) { m[it] >= thr }
        // Close small gaps (score bars, the black lines between games) so the screen is one piece.
        val closed = BooleanArray(n)
        for (r in 0 until rows) for (c in 0 until cols) {
            var any = false
            for (dr in -2..2) for (dc in -2..2) {
                val rr = r + dr; val cc = c + dc
                if (rr in 0 until rows && cc in 0 until cols && on[rr * cols + cc]) { any = true }
            }
            closed[r * cols + c] = any
        }
        // Largest connected piece.
        val label = IntArray(n) { -1 }
        var best = -1; var bestSize = 0; var next = 0
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (!closed[start] || label[start] >= 0) continue
            var sp = 0; stack[sp++] = start; label[start] = next; var size = 0
            while (sp > 0) {
                val i = stack[--sp]; size++
                val r = i / cols; val c = i % cols
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val rr = r + dr; val cc = c + dc
                    if (rr !in 0 until rows || cc !in 0 until cols) continue
                    val j = rr * cols + cc
                    if (closed[j] && label[j] < 0) { label[j] = next; stack[sp++] = j }
                }
            }
            if (size > bestSize) { bestSize = size; best = next }
            next++
        }
        if (best < 0 || bestSize < n * 0.04) return null // too small to be the TV
        // Corners: the piece's extreme points along the diagonals, using only cells that really moved
        // (the closing step padded the edges by two cells).
        var tl = Double.MAX_VALUE; var br = -Double.MAX_VALUE; var tr = -Double.MAX_VALUE; var bl = Double.MAX_VALUE
        var ptl = 0.0 to 0.0; var pbr = 0.0 to 0.0; var ptr = 0.0 to 0.0; var pbl = 0.0 to 0.0
        for (i in 0 until n) {
            if (label[i] != best || !on[i]) continue
            val x = (i % cols + 0.5) / cols; val y = (i / cols + 0.5) / rows
            // Weight by the frame's shape so "diagonal" means corner of the picture, not of a square.
            val ax = x * cols; val ay = y * rows
            if (ax + ay < tl) { tl = ax + ay; ptl = x to y }
            if (ax + ay > br) { br = ax + ay; pbr = x to y }
            if (ax - ay > tr) { tr = ax - ay; ptr = x to y }
            if (ax - ay < bl) { bl = ax - ay; pbl = x to y }
        }
        // Push each corner out by half a cell to the true edge.
        val hx = 0.5 / cols; val hy = 0.5 / rows
        val corners = listOf(
            (ptl.first - hx) to (ptl.second - hy), (ptr.first + hx) to (ptr.second - hy),
            (pbr.first + hx) to (pbr.second + hy), (pbl.first - hx) to (pbl.second + hy),
        ).map { (x, y) -> x.coerceIn(0.0, 1.0) to y.coerceIn(0.0, 1.0) }
        // Sanity: a real screen is a decent size and roughly TV-shaped (wider than tall in the picture's terms).
        val wTop = corners[1].first - corners[0].first
        val hLeft = corners[3].second - corners[0].second
        if (wTop < 0.15 || hLeft < 0.1) return null
        return corners
    }

    fun reset() { prev = null; motion = null; bright = null; frames = 0 }
}
