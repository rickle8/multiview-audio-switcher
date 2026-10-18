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
fun warpGrid(frame: GrayFrame, corners: List<Pair<Double, Double>>, w: Int = 384, h: Int = 216): FloatArray {
    // Corners are given as fractions of the frame.
    val hom = Homography(corners.map { (x, y) -> x * (frame.width - 1) to y * (frame.height - 1) })
    val out = FloatArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
        val (u, v) = hom.map((i + 0.5) / w, (j + 0.5) / h)
        // Blend the four nearest camera pixels: smoother edges than picking one.
        val x = u.coerceIn(0.0, frame.width - 1.001); val y = v.coerceIn(0.0, frame.height - 1.001)
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val x1 = minOf(x0 + 1, frame.width - 1); val y1 = minOf(y0 + 1, frame.height - 1)
        val top = frame.at(x0, y0) * (1 - fx) + frame.at(x1, y0) * fx
        val bot = frame.at(x0, y1) * (1 - fx) + frame.at(x1, y1) * fx
        out[j * w + i] = top * (1 - fy) + bot * fy
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
    /**
     * A box whose white outline just appeared this moment: YouTube TV flashes it for a few
     * seconds whenever the sound moves to a box, so this says where the sound just went.
     */
    val outlineAppeared: Int?,
    /** Per box: true = its score bug is up (the game), false = gone (commercial), null = still learning it. */
    val showingGame: Map<Int, Boolean?>,
    /** Per box, 0..100: how much of the learned score bug is on screen right now. */
    val scoreBar: Map<Int, Int?>,
    /** Per box: where it found the score bug, as fractions of the box (left, top, right, bottom), once learned. */
    val bugArea: Map<Int, DoubleArray?> = emptyMap(),
    /** The box whose outline is showing right now (only for the few seconds it flashes). */
    val outlineNow: Int? = null,
)

/**
 * Reads the straightened multiview grid, several times a second.
 *
 * The outline: YouTube TV flashes a white outline around a box for a few seconds when
 * the sound moves there, then it fades. So this looks for an outline *appearing*: a
 * box's edge suddenly much brighter than it has been, while nothing like that happens
 * on the other boxes. That's a moment, not a state: it says where the sound just went.
 *
 * The score bug: every game broadcast keeps a score bug in the same spot while the
 * picture moves; commercials don't have it. Each box learns its own bug from what
 * stays put: the edges (lettering, borders, logos) that line up the same way frame
 * after frame for minutes, while everything else changes. It compares edges, not
 * brightness, so the room light or the camera's exposure changing doesn't matter. The
 * game being on (ESPN or the sound) speeds the learning up; a known commercial is
 * never learned from.
 */
class ScreenWatcher(private val layout: Int, private val w: Int = 384, private val h: Int = 216) {
    private val rects = boxRects(layout)

    // ------------------------------------------------------------ outline
    private val ringHistory = HashMap<Int, ArrayDeque<Double>>()
    private var candidate: Int? = null
    private var candidateRuns = 0
    private var lastOnsetFrame = -100
    private var lastOnsetBox: Int? = null
    private var frameNo = 0
    private val outlineUntil = HashMap<Int, Int>()

    /** Brightest thin ring around the box edge (a few insets tried, since the outline's exact spot varies), minus inside. */
    private fun ringContrast(img: FloatArray, r: DoubleArray): Double {
        val x0 = (r[0] * w).toInt(); val x1 = (r[2] * w).toInt() - 1
        val y0 = (r[1] * h).toInt(); val y1 = (r[3] * h).toInt() - 1
        fun ring(d: Int, t: Int): Double {
            var s = 0.0; var c = 0
            for (k in d until d + t) {
                if (x0 + k >= x1 - k || y0 + k >= y1 - k) break
                var x = x0 + k
                while (x <= x1 - k) { s += img[(y0 + k) * w + x] + img[(y1 - k) * w + x]; c += 2; x += 2 }
                var y = y0 + k
                while (y <= y1 - k) { s += img[y * w + x0 + k] + img[y * w + x1 - k]; c += 2; y += 2 }
            }
            return if (c == 0) 0.0 else s / c
        }
        val inside = ring(12, 6)
        return (0..6 step 2).maxOf { ring(it, 3) } - inside
    }

    private fun detectOutline(img: FloatArray): Pair<Int?, Int?> {
        val jumps = HashMap<Int, Double>()
        for ((box, r) in rects) {
            val c = ringContrast(img, r)
            val hist = ringHistory.getOrPut(box) { ArrayDeque() }
            if (hist.size >= 12) {
                // Baseline: how this box's edge usually looks (leaving out the last second, in case it's rising).
                val base = hist.toList().dropLast(4).sorted().let { it[it.size / 2] }
                jumps[box] = c - base
            }
            hist.addLast(c)
            while (hist.size > 50) hist.removeFirst()
        }
        frameNo++
        if (jumps.size < rects.size) return null to null
        val ranked = jumps.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val second = ranked.getOrNull(1)?.value ?: 0.0
        // A clear jump on one box only (a scene change brightens a whole picture, not just its rim).
        val clear = top.value > 22 && top.value > 2.2 * maxOf(second, 4.0)
        if (clear && candidate == top.key) candidateRuns++ else { candidate = if (clear) top.key else null; candidateRuns = if (clear) 1 else 0 }
        if (clear) outlineUntil[top.key] = frameNo + 3
        val now = outlineUntil.entries.firstOrNull { it.value >= frameNo }?.key
        // (A different box can flash right after another: pressing two arrows passes through a box.)
        if (candidateRuns >= 2 && (top.key != lastOnsetBox || frameNo - lastOnsetFrame > 10)) {
            lastOnsetFrame = frameNo
            lastOnsetBox = top.key
            candidateRuns = 0
            return top.key to now
        }
        return null to now
    }

    // ------------------------------------------------------------ score bug
    private class Bug(val bw: Int, val bh: Int) {
        val n = bw * bh
        // Learned edges: average gradient where it's been steady, and how often it matched.
        val tx = FloatArray(n); val ty = FloatArray(n)
        val steady = FloatArray(n)
        var frames = 0
        var mask: BooleanArray? = null
        var maskSize = 0
        var area: DoubleArray? = null
        val recent = ArrayDeque<Double>()
        var showing: Boolean? = null
        var lastMaskFrame = 0
        var goneFrames = 0
    }

    private val bugs = HashMap<Int, Bug>()
    /** Frames (about 4 a second) the bug has to stay gone before it counts as a commercial. */
    var GONE_FRAMES = 100
    private val EDGE = 10f

    /** The box's picture at half resolution (averaged, which also smooths camera noise), clear of the outline. */
    private fun boxPixels(img: FloatArray, r: DoubleArray): Triple<FloatArray, Int, Int> {
        val pad = 8
        val x0 = (r[0] * w).toInt() + pad; val x1 = (r[2] * w).toInt() - pad
        val y0 = (r[1] * h).toInt() + pad; val y1 = (r[3] * h).toInt() - pad
        val bw = maxOf(4, (x1 - x0) / 2); val bh = maxOf(4, (y1 - y0) / 2)
        val out = FloatArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val sx = x0 + 2 * x; val sy = y0 + 2 * y
            out[y * bw + x] = (img[sy * w + sx] + img[sy * w + sx + 1] + img[(sy + 1) * w + sx] + img[(sy + 1) * w + sx + 1]) / 4f
        }
        return Triple(out, bw, bh)
    }

    private fun readBug(box: Int, img: FloatArray, r: DoubleArray, learnFast: Boolean, noLearn: Boolean): Pair<Boolean?, Int?> {
        val (px, bw, bh) = boxPixels(img, r)
        val b = bugs.getOrPut(box) { Bug(bw, bh) }
        if (b.n != px.size) { bugs.remove(box); return null to null }
        // Current edges.
        val gx = FloatArray(b.n); val gy = FloatArray(b.n)
        for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
            val i = y * bw + x
            gx[i] = px[i + 1] - px[i - 1]
            gy[i] = px[i + bw] - px[i - bw]
        }
        // How much of the learned bug is on screen: its edges, pointing the same way.
        var score: Double? = null
        val mask = b.mask
        if (mask != null && b.maskSize > 0) {
            var hit = 0
            for (i in 0 until b.n) if (mask[i]) {
                val dot = gx[i] * b.tx[i] + gy[i] * b.ty[i]
                val m1 = kotlin.math.sqrt(gx[i] * gx[i] + gy[i] * gy[i])
                val m2 = kotlin.math.sqrt(b.tx[i] * b.tx[i] + b.ty[i] * b.ty[i])
                if (m1 > EDGE * 0.6f && dot > 0.7f * m1 * m2) hit++
            }
            score = hit.toDouble() / b.maskSize
        }
        // Learn: never from a known commercial, and not while the bug is clearly missing.
        val bugAbsent = score != null && score < 0.4
        if (!noLearn && !bugAbsent) {
            b.frames++
            val a = when {
                b.frames < 40 -> 1f / b.frames
                learnFast -> 1f / 120
                else -> 1f / 400
            }
            for (i in 0 until b.n) {
                val m1 = kotlin.math.sqrt(gx[i] * gx[i] + gy[i] * gy[i])
                val m2 = kotlin.math.sqrt(b.tx[i] * b.tx[i] + b.ty[i] * b.ty[i])
                val match = m1 > EDGE && m2 > EDGE * 0.5f && (gx[i] * b.tx[i] + gy[i] * b.ty[i]) > 0.8f * m1 * m2
                b.steady[i] += a * ((if (match) 1f else 0f) - b.steady[i])
                b.tx[i] += a * (gx[i] - b.tx[i])
                b.ty[i] += a * (gy[i] - b.ty[i])
            }
            if (b.frames >= 60 && b.frames - b.lastMaskFrame >= 30) relearnMask(b)
        }
        val s = score ?: return null to null
        b.recent.addLast(s)
        while (b.recent.size > 10) b.recent.removeFirst()
        val avg = b.recent.average()
        // Random edges line up with the bug about a fifth of the time; the real bug, nearly always.
        // The bug back means the game, right away. The bug gone isn't enough on its own: broadcasts drop
        // it for replays, scores and close-ups. Only a long absence (~25 s) reads as a commercial.
        if (b.recent.size >= 6 && avg <= 0.35) b.goneFrames++ else if (avg >= 0.45) b.goneFrames = 0
        b.showing = when {
            b.recent.size >= 6 && avg >= 0.55 -> true
            b.goneFrames >= GONE_FRAMES -> false
            else -> b.showing
        }
        return b.showing to (avg * 100).toInt()
    }

    private fun relearnMask(b: Bug) {
        b.lastMaskFrame = b.frames
        val m = BooleanArray(b.n)
        var count = 0
        var minX = b.bw; var maxX = 0; var minY = b.bh; var maxY = 0
        for (y in 1 until b.bh - 1) for (x in 1 until b.bw - 1) {
            val i = y * b.bw + x
            val mag = kotlin.math.sqrt(b.tx[i] * b.tx[i] + b.ty[i] * b.ty[i])
            if (b.steady[i] > 0.5f && mag > EDGE) {
                m[i] = true; count++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        val frac = count.toDouble() / b.n
        // A score bug is a modest patch: too little is noise, too much is a frozen or empty picture.
        if (frac in 0.004..0.2 && count >= 12) {
            b.mask = m; b.maskSize = count
            b.area = doubleArrayOf(minX.toDouble() / b.bw, minY.toDouble() / b.bh, (maxX + 1.0) / b.bw, (maxY + 1.0) / b.bh)
        }
    }

    /**
     * [gameLikely]: boxes known to be showing the game (ESPN or the sound): learn their bugs faster.
     * [adLikely]: boxes known to be in commercial: don't learn from them at all.
     */
    fun frame(img: FloatArray, gameLikely: Set<Int> = emptySet(), adLikely: Set<Int> = emptySet()): CameraView {
        val (onset, outlineNow) = detectOutline(img)
        val showing = HashMap<Int, Boolean?>()
        val bars = HashMap<Int, Int?>()
        val areas = HashMap<Int, DoubleArray?>()
        for ((box, r) in rects) {
            val (sh, bar) = readBug(box, img, r, learnFast = box in gameLikely, noLearn = box in adLikely)
            showing[box] = sh; bars[box] = bar; areas[box] = bugs[box]?.area
        }
        return CameraView(onset, showing, bars, areas, outlineNow)
    }
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
