package com.rickle8.mvswitch.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Diagonal Gaussian mixture over MFCC frames (a classic voice model). */
class Gmm(val means: Array<DoubleArray>, val vars: Array<DoubleArray>, val weights: DoubleArray) {
    private val logNorm = DoubleArray(weights.size) { k ->
        ln(weights[k]) - 0.5 * vars[k].sumOf { ln(2 * PI * it) }
    }

    fun frameLogLik(x: FloatArray): Double {
        var best = Double.NEGATIVE_INFINITY
        val parts = DoubleArray(weights.size)
        for (k in weights.indices) {
            var s = logNorm[k]
            val m = means[k]
            val v = vars[k]
            for (d in x.indices) { val z = x[d] - m[d]; s -= 0.5 * z * z / v[d] }
            parts[k] = s
            if (s > best) best = s
        }
        var sum = 0.0
        for (p in parts) sum += exp(p - best)
        return best + ln(sum)
    }

    fun meanLogLik(frames: List<FloatArray>): Double =
        if (frames.isEmpty()) 0.0 else frames.sumOf { frameLogLik(it) } / frames.size

    companion object {
        fun fit(data: List<FloatArray>, k: Int = 8, iters: Int = 15, seed: Long = 7): Gmm {
            val dims = data.first().size
            val rnd = java.util.Random(seed)
            val gMean = DoubleArray(dims) { d -> data.sumOf { it[d].toDouble() } / data.size }
            val gVar = DoubleArray(dims) { d -> max(1e-4, data.sumOf { (it[d] - gMean[d]).let { z -> z * z } } / data.size) }
            val floor = DoubleArray(dims) { gVar[it] * 0.01 }
            var means = Array(k) { data[rnd.nextInt(data.size)].map { it.toDouble() }.toDoubleArray() }
            var vars = Array(k) { gVar.copyOf() }
            var weights = DoubleArray(k) { 1.0 / k }
            val resp = DoubleArray(k)
            repeat(iters) {
                val g = Gmm(means, vars, weights)
                val nk = DoubleArray(k)
                val sx = Array(k) { DoubleArray(dims) }
                val sxx = Array(k) { DoubleArray(dims) }
                for (x in data) {
                    var best = Double.NEGATIVE_INFINITY
                    for (c in 0 until k) {
                        var s = g.logNorm[c]
                        for (d in 0 until dims) { val z = x[d] - means[c][d]; s -= 0.5 * z * z / vars[c][d] }
                        resp[c] = s
                        if (s > best) best = s
                    }
                    var tot = 0.0
                    for (c in 0 until k) { resp[c] = exp(resp[c] - best); tot += resp[c] }
                    for (c in 0 until k) {
                        val r = resp[c] / tot
                        nk[c] += r
                        for (d in 0 until dims) { sx[c][d] += r * x[d]; sxx[c][d] += r * x[d] * x[d] }
                    }
                }
                for (c in 0 until k) {
                    if (nk[c] < 1e-3) { // dead component: restart on a random frame
                        means[c] = data[rnd.nextInt(data.size)].map { it.toDouble() }.toDoubleArray()
                        vars[c] = gVar.copyOf(); nk[c] = 1.0
                        continue
                    }
                    for (d in 0 until dims) {
                        val m = sx[c][d] / nk[c]
                        means[c][d] = m
                        vars[c][d] = max(floor[d], sxx[c][d] / nk[c] - m * m)
                    }
                }
                val tot = nk.sum()
                weights = DoubleArray(k) { max(1e-4, nk[it] / tot) }
            }
            return Gmm(means, vars, weights)
        }
    }
}

/**
 * Learns the announcers' voices from game audio. A different voice talking
 * (a commercial's voiceover or actors) is evidence of an ad; the announcers
 * talking is strong evidence of the game.
 */
class AnnouncerModel(private val maxGame: Int = 8000, private val maxOther: Int = 4000) {
    companion object {
        const val MIN_GAME_FRAMES = 900
        const val MIN_OTHER_FRAMES = 450
        const val SPEECH_MIN = 0.35
    }

    private val gameFrames = ArrayList<FloatArray>()
    private val otherFrames = ArrayList<FloatArray>()
    private val rnd = java.util.Random(11)
    private var game: Gmm? = null
    private var other: Gmm? = null
    private var mu = 0.0
    private var sd = 1.0
    private var addedSinceFit = 0

    val ready get() = game != null
    val gameFrameCount get() = gameFrames.size

    /** Loud frames only: quiet frames are mostly room noise, not voice. */
    private fun voiced(a: SecondAudio): List<FloatArray> {
        if (a.mfccs.isEmpty()) return emptyList()
        val sorted = a.energiesDb.sortedArray()
        val med = sorted[sorted.size / 2]
        return a.mfccs.filterIndexed { i, _ -> i < a.energiesDb.size && a.energiesDb[i] >= med }
    }

    private fun reservoir(list: ArrayList<FloatArray>, frames: List<FloatArray>, cap: Int) {
        for (f in frames) {
            if (list.size < cap) list += f else list[rnd.nextInt(cap)] = f
        }
    }

    @Synchronized
    fun learn(a: SecondAudio, speech: Double, isGame: Boolean) {
        if (speech < SPEECH_MIN) return
        val v = voiced(a)
        if (isGame) reservoir(gameFrames, v, maxGame) else reservoir(otherFrames, v, maxOther)
        addedSinceFit += v.size
        val needFirst = game == null && gameFrames.size >= MIN_GAME_FRAMES
        val needOther = other == null && otherFrames.size >= MIN_OTHER_FRAMES
        if (needFirst || needOther || (game != null && addedSinceFit >= 600)) refit()
    }

    private fun refit() {
        addedSinceFit = 0
        if (gameFrames.size < MIN_GAME_FRAMES) return
        val g = Gmm.fit(gameFrames)
        // Typical score of announcer speech, in 15-frame chunks like a real second.
        val scores = gameFrames.shuffled(rnd).chunked(15).filter { it.size == 15 }.map { g.meanLogLik(it) }
        mu = scores.average()
        sd = max(0.05, sqrt(scores.sumOf { (it - mu) * (it - mu) } / max(1, scores.size - 1)))
        game = g
        other = if (otherFrames.size >= MIN_OTHER_FRAMES) Gmm.fit(otherFrames) else null
    }

    /**
     * Positive = sounds like the announcers, negative = a different voice.
     * Returned in log-odds units; null if no speech or not trained yet.
     */
    @Synchronized
    fun evidence(a: SecondAudio, speech: Double): Double? {
        val g = game ?: return null
        if (speech < SPEECH_MIN) return null
        val v = voiced(a)
        if (v.size < 5) return null
        val lg = g.meanLogLik(v)
        val o = other
        return if (o != null) {
            ((lg - o.meanLogLik(v)) * 0.5).coerceIn(-3.0, 3.0)
        } else {
            val z = (lg - mu) / sd
            (z + 1.5).coerceIn(-3.0, 1.5)
        }
    }

    @Synchronized
    fun clear() {
        gameFrames.clear(); otherFrames.clear(); game = null; other = null
    }

    @Synchronized
    fun save(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(0x4D565631); d.writeInt(Mel.CEPS)
        for (list in listOf(gameFrames, otherFrames)) {
            d.writeInt(list.size)
            for (f in list) for (v in f) d.writeFloat(v)
        }
        d.flush()
    }

    @Synchronized
    fun load(input: InputStream) {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != 0x4D565631 || d.readInt() != Mel.CEPS) return
        clear()
        for (list in listOf(gameFrames, otherFrames)) {
            repeat(d.readInt()) { list += FloatArray(Mel.CEPS) { d.readFloat() } }
        }
        refit()
    }
}
