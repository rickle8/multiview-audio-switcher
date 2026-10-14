package com.rickle8.mvswitch.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What each game's broadcast sounds like (its crowd, its stadium, its announcers'
 * mics), learned while the game is definitely on. A commercial sounds unlike the
 * last several minutes of the game; the game keeps sounding like itself.
 */
class GameProfiles(private val minSecs: Int = 90) {
    private class P(val mean: DoubleArray, val varr: DoubleArray) {
        var n = 0
        val dists = ArrayDeque<Double>()     // distances of known game seconds
        val recent = ArrayDeque<Pair<Double, Double>>()  // (t, distance) for any second
        var lastLearn = 0.0
    }

    private val boxes = HashMap<Int, P>()

    fun vector(a: SecondAudio): DoubleArray {
        val shape = Features.shape(a)
        val v = DoubleArray(Mel.BANDS + Mel.CEPS)
        for (b in 0 until Mel.BANDS) v[b] = shape[b]
        // (The first MFCC is overall loudness, which changes with the TV volume: left out.)
        for (c in 1 until Mel.CEPS) {
            var m = 0.0
            for (f in a.mfccs) m += f[c]
            v[Mel.BANDS + c] = if (a.mfccs.isEmpty()) 0.0 else m / a.mfccs.size
        }
        return v
    }

    private fun dist(p: P, v: DoubleArray): Double {
        var s = 0.0
        for (i in v.indices) {
            val d = v[i] - p.mean[i]
            s += d * d / (p.varr[i] + 0.05)
        }
        return sqrt(s / v.size)
    }

    /** This second is the game (ESPN plays were flowing, or the game clock is running). */
    @Synchronized
    fun learn(box: Int, v: DoubleArray, t: Double) {
        var p = boxes[box]
        // A new game on this box (or a new day): start over.
        if (p != null && t - p.lastLearn > 3 * 3600) { boxes.remove(box); p = null }
        if (p == null) {
            p = P(v.copyOf(), DoubleArray(v.size) { 1.0 })
            boxes[box] = p
        }
        if (p.n >= 20) {
            p.dists.addLast(dist(p, v))
            while (p.dists.size > 400) p.dists.removeFirst()
        }
        // Adapts over ~10 minutes, so it follows the broadcast as the game goes on.
        val a = maxOf(1.0 / (p.n + 1), 1.0 / 600)
        for (i in v.indices) {
            val d = v[i] - p.mean[i]
            p.mean[i] += a * d
            p.varr[i] = (1 - a) * (p.varr[i] + a * d * d)
        }
        p.n++
        p.lastLearn = t
    }

    /**
     * Evidence for "commercial" from the last few seconds on [box]: positive when
     * it sounds unlike this game, negative when it sounds just like it. Null until learned.
     */
    @Synchronized
    fun evidence(box: Int, v: DoubleArray, t: Double): Double? {
        val p = boxes[box] ?: return null
        if (p.n < minSecs || p.dists.size < 40) return null
        p.recent.addLast(t to dist(p, v))
        while (p.recent.isNotEmpty() && t - p.recent.first().first > 5) p.recent.removeFirst()
        if (p.recent.size < 3) return null
        val now = p.recent.map { it.second }.average()
        val sorted = p.dists.sorted()
        val med = sorted[sorted.size / 2]
        val mad = maxOf(0.05, sorted.map { abs(it - med) }.sorted()[sorted.size / 2])
        val z = (now - med) / (1.4826 * mad)
        return (0.35 * (z - 1.0)).coerceIn(-0.7, 1.2)
    }

    @Synchronized
    fun ready(box: Int): Boolean = boxes[box]?.let { it.n >= minSecs && it.dists.size >= 40 } == true

    @Synchronized
    fun clear() = boxes.clear()
}
