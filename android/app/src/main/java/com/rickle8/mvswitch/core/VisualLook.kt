package com.rickle8.mvswitch.core

import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Learns what the game and commercials LOOK like on your TV, from the whole picture of each box
 * rather than a few hand-picked signs. Several times a second it measures each box: how often the
 * picture cuts (commercials cut every couple of seconds, a broadcast holds its shots), how much it
 * moves, whether a strip along the bottom stays put while the rest moves (the score bar, whatever
 * its design or logos), black bars top and bottom (movie trailers, many ads), brightness and contrast,
 * edges, and the field's green. Those, over the last 8 seconds, are one sample.
 *
 * It learns only from labels it's sure of, never from its own guesses: the score bug read off the
 * screen, your "It's the game" (game); commercial text, a commercial it recognized by sound, your
 * "It's an ad", clearly ad-sounding audio (commercial). Every labeled sample is first used to check
 * it: it only speaks once it has been right on most of the recent ones of both kinds.
 */
class VisualLook(private val layout: Int = 4, private val w: Int = 384, private val h: Int = 216) {
    companion object {
        /** Numbers in one sample. */
        const val SIZE = 22
        /** Seconds of picture in one sample. */
        const val WINDOW = 8.0
        /** Labeled samples of each kind before it starts checking itself. */
        const val MIN_EACH = 40
        /** Recent checked samples of each kind needed, and how many of them it must have got right. */
        const val CHECK_EACH = 20
        const val ACCURACY = 0.85
        /** A box's picture changing this much from one look to the next (mean gray levels) is a cut. */
        const val CUT = 22.0
    }

    private class Look(val t: Double, val f: DoubleArray, val cut: Boolean)

    private val rects = boxRects(layout)
    private val prev = HashMap<Int, FloatArray>()
    private val looks = HashMap<Int, ArrayDeque<Look>>()
    private val labeledAt = HashMap<Int, Double>()
    val model = SoundModel(SIZE, maxPerLabel = 800)
    /** Recent self-checks: was it right on a sample before learning from it (per kind). */
    private val checksGame = ArrayDeque<Boolean>()
    private val checksAd = ArrayDeque<Boolean>()

    // Where in a box: clear of the outline YouTube TV draws, the main picture, and the bottom strip
    // the score bar sits in.
    private val inset = 0.04
    private val mainTop = 0.06; private val mainBottom = 0.76
    private val stripTop = 0.78; private val stripBottom = 0.96
    private val barH = 0.12

    /** One straightened grid picture ([w] x [h] gray), and each box's share of field green, at [t]. */
    @Synchronized
    fun frame(img: FloatArray, t: Double, green: Map<Int, Double> = emptyMap()) {
        if (img.size != w * h) return
        for ((box, r) in rects) {
            val bx0 = (r[0] * w).toInt(); val bx1 = (r[2] * w).toInt()
            val by0 = (r[1] * h).toInt(); val by1 = (r[3] * h).toInt()
            val bw = bx1 - bx0; val bh = by1 - by0
            if (bw < 20 || bh < 20) continue
            // A small copy of the box (every other pixel), clear of its edges.
            val x0 = bx0 + (bw * inset).toInt(); val x1 = bx1 - (bw * inset).toInt()
            val y0 = by0 + (bh * inset).toInt(); val y1 = by1 - (bh * inset).toInt()
            val cw = (x1 - x0) / 2; val ch = (y1 - y0) / 2
            if (cw < 8 || ch < 8) continue
            val cur = FloatArray(cw * ch)
            for (j in 0 until ch) for (i in 0 until cw) cur[j * cw + i] = img[(y0 + j * 2) * w + x0 + i * 2]
            val p = prev.put(box, cur)?.takeIf { it.size == cur.size }
            val f = measure(cur, cw, ch, p, green[box] ?: 0.0)
            val q = looks.getOrPut(box) { ArrayDeque() }
            q.addLast(Look(t, f, p != null && f[F_MOTION] * 32 > CUT))
            while (q.isNotEmpty() && t - q.first().t > WINDOW) q.removeFirst()
        }
    }

    private val F_MOTION = 9

    /** One look at one box: the numbers [sample] averages over the window. */
    private fun measure(px: FloatArray, cw: Int, ch: Int, prev: FloatArray?, green: Double): DoubleArray {
        fun rows(a: Double, b: Double) = (a * ch).toInt().coerceIn(0, ch - 1) until (b * ch).toInt().coerceIn(1, ch)
        val main = rows(mainTop, mainBottom); val strip = rows(stripTop, stripBottom)
        val top = rows(0.0, barH); val bottom = rows(1 - barH, 1.0)
        var sum = 0.0; var sq = 0.0; var n = 0; var dark = 0; var bright = 0
        val hist = IntArray(8)
        var gx = 0.0; var gy = 0.0
        for (j in main) for (i in 1 until cw) {
            val v = px[j * cw + i].toDouble()
            sum += v; sq += v * v; n++
            if (v < 35) dark++; if (v > 215) bright++
            hist[(v / 32).toInt().coerceIn(0, 7)]++
            gx += abs(v - px[j * cw + i - 1])
            if (j > main.first) gy += abs(v - px[(j - 1) * cw + i])
        }
        val mean = sum / maxOf(1, n)
        val sd = sqrt(maxOf(0.0, sq / maxOf(1, n) - mean * mean))
        var entropy = 0.0
        for (c in hist) if (c > 0) { val pr = c.toDouble() / n; entropy -= pr * ln(pr) }
        fun meanOf(rs: IntRange): Pair<Double, Double> {
            var s = 0.0; var s2 = 0.0; var k = 0
            for (j in rs) for (i in 0 until cw) { val v = px[j * cw + i].toDouble(); s += v; s2 += v * v; k++ }
            val m = s / maxOf(1, k); return m to sqrt(maxOf(0.0, s2 / maxOf(1, k) - m * m))
        }
        val (topM, topSd) = meanOf(top); val (botM, botSd) = meanOf(bottom)
        // Black bars: the very top and bottom dark and flat while the picture between isn't.
        val bars = if (topM < 28 && botM < 28 && topSd < 10 && botSd < 10 && mean > 45) 1.0 else 0.0
        var sgx = 0.0; var sn = 0
        for (j in strip) for (i in 1 until cw) { sgx += abs(px[j * cw + i] - px[j * cw + i - 1]); sn++ }
        // Movement since the last look: in the picture, and in the bottom strip.
        var mMain = 0.0; var mStrip = 0.0
        if (prev != null) {
            var a = 0; var b = 0
            for (j in main) for (i in 0 until cw) { mMain += abs(px[j * cw + i] - prev[j * cw + i]); a++ }
            for (j in strip) for (i in 0 until cw) { mStrip += abs(px[j * cw + i] - prev[j * cw + i]); b++ }
            mMain /= maxOf(1, a); mStrip /= maxOf(1, b)
        }
        // How still the bottom strip is next to the picture (a score bar stays put while play moves).
        val stillStrip = if (mMain > 2) (1 - mStrip / mMain).coerceIn(-1.0, 1.0) else 0.0
        return doubleArrayOf(
            mean / 255, sd / 128, dark.toDouble() / maxOf(1, n), bright.toDouble() / maxOf(1, n), entropy / ln(8.0),
            gx / maxOf(1, n) / 32, gy / maxOf(1, n) / 32, (sgx / maxOf(1, sn)) / 32, bars,
            mMain / 32, mStrip / 32, stillStrip, green,
        )
    }

    /** The last [WINDOW] s of [box] as one sample, or null without enough looks. */
    @Synchronized
    fun sample(box: Int, now: Double): DoubleArray? {
        val q = looks[box]?.filter { now - it.t <= WINDOW } ?: return null
        if (q.size < 12 || now - q.last().t > 2) return null
        val k = q.first().f.size
        val avg = DoubleArray(k) { d -> q.sumOf { it.f[d] } / q.size }
        fun sdOf(d: Int) = sqrt(maxOf(0.0, q.sumOf { (it.f[d] - avg[d]) * (it.f[d] - avg[d]) } / q.size))
        val span = maxOf(1.0, q.last().t - q.first().t)
        val cuts = q.count { it.cut } / span * 8 // cuts per 8 s
        val motion = q.map { it.f[F_MOTION] }.sorted()
        return DoubleArray(SIZE).also { x ->
            for (d in 0 until k) x[d] = avg[d]
            x[13] = cuts / 4
            x[14] = sdOf(0) * 4            // brightness changing shot to shot
            x[15] = sdOf(F_MOTION) * 2     // steady pan vs bursts
            x[16] = motion[motion.size / 2]
            x[17] = q.count { it.f[11] > 0.6 }.toDouble() / q.size // share of looks with a still bottom strip
            x[18] = sdOf(12) * 2           // the field coming and going
            x[19] = minOf(1.0, avg[12] * 3) * (1 - minOf(1.0, cuts / 4)) // field held on screen
            x[20] = avg[7] * x[17]         // an edgy bottom strip that stays put: a score bar
            x[21] = q.count { it.f[8] > 0.5 }.toDouble() / q.size
        }
    }

    /**
     * [box] is surely showing the game ([isAd] false) or a commercial (true) right now. Learns from
     * its last 8 s, at most every 2 s per box, after first checking how it would have called it.
     */
    @Synchronized
    fun label(box: Int, isAd: Boolean, now: Double) {
        if (now - (labeledAt[box] ?: -1e9) < 2) return
        val x = sample(box, now) ?: return
        labeledAt[box] = now
        if (model.ready) model.adProbability(x)?.let { p ->
            val q = if (isAd) checksAd else checksGame
            q.addLast((p >= 0.5) == isAd)
            while (q.size > 50) q.removeFirst()
        }
        model.add(x, isAd)
    }

    /** How often it was right lately, per kind (null until it has checked enough of that kind). */
    @Synchronized
    fun accuracy(): Pair<Double?, Double?> {
        fun acc(q: ArrayDeque<Boolean>) = q.takeLast(CHECK_EACH).takeIf { it.size >= CHECK_EACH }?.let { l -> l.count { it }.toDouble() / l.size }
        return acc(checksGame) to acc(checksAd)
    }

    /** It has learned enough of both, and has been right on most of the recent ones of each. */
    val trusted: Boolean
        get() {
            if (model.gameSeconds < MIN_EACH || model.adSeconds < MIN_EACH) return false
            val (g, a) = accuracy()
            return g != null && a != null && g >= ACCURACY && a >= ACCURACY
        }

    /** How commercial-like [box] looks, 0..1, once trusted; else null. */
    @Synchronized
    fun adChance(box: Int, now: Double): Double? {
        if (!trusted) return null
        val x = sample(box, now) ?: return null
        return model.adProbability(x)
    }

    /** For the diagnostics: what it has learned and how it's doing. */
    @Synchronized
    fun describe(): String {
        val (g, a) = accuracy()
        fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "checking"
        return "learned ${model.gameSeconds} game, ${model.adSeconds} ad looks; right on game ${pct(g)}, ads ${pct(a)}; " +
            if (trusted) "in use" else "not in use yet"
    }

    @Synchronized
    fun clearLooks() { prev.clear(); looks.clear() }

    @Synchronized
    fun forget() { model.clear(); checksGame.clear(); checksAd.clear() }

    fun layoutIs(l: Int) = l == layout

    /** The recent self-checks, to keep across restarts (so it doesn't have to prove itself again). */
    @Synchronized
    fun checksText(): String = listOf(checksGame, checksAd).joinToString("\n") { q -> q.joinToString("") { if (it) "1" else "0" } }

    @Synchronized
    fun loadChecks(text: String) {
        val parts = text.split('\n')
        for ((i, q) in listOf(checksGame, checksAd).withIndex()) {
            q.clear(); parts.getOrNull(i)?.forEach { c -> if (c == '1' || c == '0') q.addLast(c == '1') }
        }
    }

    fun save(out: OutputStream) = model.save(out)
    fun load(input: InputStream) = model.load(input)
}
