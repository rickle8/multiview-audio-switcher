package com.rickle8.mvswitch.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Learns what YOUR TV sounds like through YOUR phone: game vs commercial.
 *
 * Each second is about 600 numbers (all YAMNet sound scores + spectral shape,
 * movement and voice timbre). A regularized logistic regression is trained on
 * labeled seconds: your taps, audio between live plays (game), and replays of
 * fingerprinted ads (ad). Until it has enough of both, the fixed rules are used.
 */
class SoundModel(val dims: Int = Features.SIZE, private val maxPerLabel: Int = 600) {
    companion object {
        const val MIN_SAMPLES = 25
        private const val LAMBDA = 0.05
        private const val ITERS = 150
        private const val LR = 0.2
        private const val MAGIC = 0x4D564C32 // "MVL2"
    }

    private val game = ArrayDeque<FloatArray>()
    private val ad = ArrayDeque<FloatArray>()
    private var w: DoubleArray? = null
    private var b = 0.0
    private var mean = DoubleArray(dims)
    private var std = DoubleArray(dims) { 1.0 }
    private var newSinceTrain = 0

    val gameSeconds get() = game.size
    val adSeconds get() = ad.size
    val ready get() = game.size >= MIN_SAMPLES && ad.size >= MIN_SAMPLES

    @Synchronized
    fun add(x: DoubleArray, isAd: Boolean) {
        if (x.size != dims) return
        val q = if (isAd) ad else game
        q.addLast(FloatArray(dims) { x[it].toFloat() })
        while (q.size > maxPerLabel) q.removeFirst()
        newSinceTrain++
    }

    @Synchronized
    fun clear() {
        game.clear(); ad.clear(); w = null; newSinceTrain = 0
    }

    private fun train() {
        newSinceTrain = 0
        val rows = game.map { it to 0.0 } + ad.map { it to 1.0 }
        val n = rows.size
        val wg = 0.5 / game.size
        val wa = 0.5 / ad.size
        val m = DoubleArray(dims)
        for ((x, _) in rows) for (d in 0 until dims) m[d] += x[d] / n.toDouble()
        val s = DoubleArray(dims)
        for ((x, _) in rows) for (d in 0 until dims) { val z = x[d] - m[d]; s[d] += z * z / n }
        for (d in 0 until dims) s[d] = max(1e-3, sqrt(s[d]))
        val z = Array(n) { i -> DoubleArray(dims) { d -> (rows[i].first[d] - m[d]) / s[d] } }
        val y = DoubleArray(n) { rows[it].second }
        val wt = DoubleArray(n) { if (y[it] > 0.5) wa else wg }
        val weights = DoubleArray(dims)
        var bias = 0.0
        val grad = DoubleArray(dims)
        repeat(ITERS) {
            grad.fill(0.0)
            var gb = 0.0
            for (i in 0 until n) {
                var a = bias
                val zi = z[i]
                for (d in 0 until dims) a += weights[d] * zi[d]
                val p = 1.0 / (1.0 + exp(-a.coerceIn(-30.0, 30.0)))
                val g = (p - y[i]) * wt[i]
                gb += g
                for (d in 0 until dims) grad[d] += g * zi[d]
            }
            for (d in 0 until dims) weights[d] -= LR * (grad[d] + LAMBDA * weights[d])
            bias -= LR * gb
        }
        mean = m; std = s; w = weights; b = bias
    }

    /** Probability this second is a commercial, or null if not trained yet. */
    @Synchronized
    fun adProbability(x: DoubleArray): Double? {
        if (!ready || x.size != dims) return null
        if (w == null || newSinceTrain >= 10) train()
        val weights = w ?: return null
        var a = b
        for (d in 0 until dims) a += weights[d] * (x[d] - mean[d]) / std[d]
        return 1.0 / (1.0 + exp(-a.coerceIn(-30.0, 30.0)))
    }

    @Synchronized
    fun save(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC); d.writeInt(dims)
        for (q in listOf(game, ad)) {
            d.writeInt(q.size)
            for (x in q) for (v in x) d.writeFloat(v)
        }
        d.flush()
    }

    @Synchronized
    fun load(input: InputStream) {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC || d.readInt() != dims) return // older format: start over
        clear()
        for (q in listOf(game, ad)) repeat(d.readInt()) { q.addLast(FloatArray(dims) { d.readFloat() }) }
        newSinceTrain = 1
    }
}
