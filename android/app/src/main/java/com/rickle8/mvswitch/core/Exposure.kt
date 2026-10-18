package com.rickle8.mvswitch.core

import kotlin.math.abs

/**
 * Keeps the TV exposed so its whites (the score bug's lettering, the yard lines) aren't blown out
 * and its picture isn't murky. The camera already meters on the TV; this nudges it a third of a
 * stop at a time, at most every 8 s, from how the TV actually looks in the picture.
 */
class ExposureTuner(start: Double = -1.0, private val minEv: Double = -2.5, private val maxEv: Double = 0.5) {
    var ev = start
        private set
    private var changedAt = -1e9

    /**
     * [clipped]: share of the TV's pixels at the very top of the range; [mean]: its average
     * brightness (0..255). Returns the new setting when it should change.
     */
    fun update(clipped: Double, mean: Double, now: Double): Double? {
        if (now - changedAt < 8) return null
        val next = when {
            clipped > 0.02 -> ev - 1.0 / 3
            clipped < 0.003 && mean < 70 -> ev + 1.0 / 3
            else -> return null
        }.coerceIn(minEv, maxEv)
        if (abs(next - ev) < 1e-6) return null
        ev = next; changedAt = now
        return ev
    }

    companion object {
        /** Share of [px] (0..255) that's blown out, and the average. */
        fun measure(px: FloatArray): Pair<Double, Double> {
            if (px.isEmpty()) return 0.0 to 0.0
            var clipped = 0; var sum = 0.0
            for (v in px) { if (v >= 250f) clipped++; sum += v }
            return clipped.toDouble() / px.size to sum / px.size
        }
    }
}
