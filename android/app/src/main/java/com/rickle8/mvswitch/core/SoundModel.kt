package com.rickle8.mvswitch.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/**
 * Learns what YOUR TV sounds like through YOUR phone, game vs commercial.
 *
 * Each second of audio becomes a small feature vector (YAMNet scores for a
 * few dozen sound types + loudness). Samples come from:
 *  - "It's the game" / "It's a commercial" taps in the app
 *  - automatically: seconds right after ESPN shows a fresh, normal play are game audio
 * A Gaussian naive Bayes model then scores new audio. Until it has enough of
 * both kinds, the fixed rules are used instead.
 */

val FEATURE_CLASSES = listOf(
    "Speech", "Male speech, man speaking", "Female speech, woman speaking", "Narration, monologue",
    "Conversation", "Crowd", "Cheering", "Applause", "Hubbub, speech noise, speech babble",
    "Chatter", "Whistle", "Shout", "Yell", "Clapping",
    "Music", "Pop music", "Hip hop music", "Rock music", "Electronic music", "Jingle (music)",
    "Theme music", "Background music", "Soundtrack music", "Singing", "Musical instrument",
    "Television", "Inside, large room or hall", "Inside, small room", "Silence", "Drum",
)

fun features(scores: Map<String, Float>, loudDb: Double): DoubleArray =
    DoubleArray(FEATURE_CLASSES.size + 1) { i ->
        if (i < FEATURE_CLASSES.size) log10((scores[FEATURE_CLASSES[i]] ?: 0f) + 0.001)
        else loudDb / 20.0
    }

class SoundModel(private val maxPerLabel: Int = 900) {
    private val game = ArrayDeque<DoubleArray>()
    private val ad = ArrayDeque<DoubleArray>()
    private var fit: Fit? = null

    val gameSeconds get() = game.size
    val adSeconds get() = ad.size
    val ready get() = game.size >= MIN_SAMPLES && ad.size >= MIN_SAMPLES

    companion object {
        const val MIN_SAMPLES = 20
        private const val VAR_FLOOR = 0.01
    }

    private class Fit(val gm: DoubleArray, val gv: DoubleArray, val am: DoubleArray, val av: DoubleArray)

    @Synchronized
    fun add(x: DoubleArray, isAd: Boolean) {
        val q = if (isAd) ad else game
        q.addLast(x)
        while (q.size > maxPerLabel) q.removeFirst()
        fit = null
    }

    @Synchronized
    fun clear() {
        game.clear(); ad.clear(); fit = null
    }

    private fun stats(q: Collection<DoubleArray>): Pair<DoubleArray, DoubleArray> {
        val n = q.first().size
        val mean = DoubleArray(n)
        for (x in q) for (i in 0 until n) mean[i] += x[i] / q.size
        val v = DoubleArray(n)
        for (x in q) for (i in 0 until n) v[i] += (x[i] - mean[i]).let { it * it } / q.size
        for (i in 0 until n) v[i] = max(v[i], VAR_FLOOR)
        return mean to v
    }

    /** Probability this second is a commercial, or null if not trained yet. */
    @Synchronized
    fun adProbability(x: DoubleArray): Double? {
        if (!ready) return null
        val f = fit ?: run {
            val (gm, gv) = stats(game)
            val (am, av) = stats(ad)
            Fit(gm, gv, am, av).also { fit = it }
        }
        var llr = 0.0
        for (i in x.indices) {
            llr += logGauss(x[i], f.am[i], f.av[i]) - logGauss(x[i], f.gm[i], f.gv[i])
        }
        // Naive Bayes is overconfident with correlated features; soften it.
        llr /= 3.0
        return 1.0 / (1.0 + exp(-llr.coerceIn(-30.0, 30.0)))
    }

    private fun logGauss(x: Double, m: Double, v: Double) = -0.5 * (ln(v) + (x - m) * (x - m) / v)

    @Synchronized
    fun toJson(): String = JSONObject()
        .put("dims", FEATURE_CLASSES.size + 1)
        .put("game", JSONArray(game.map { JSONArray(it.toList()) }))
        .put("ad", JSONArray(ad.map { JSONArray(it.toList()) }))
        .toString()

    @Synchronized
    fun load(json: String) {
        val j = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (j.optInt("dims") != FEATURE_CLASSES.size + 1) return // feature set changed: start over
        clear()
        fun read(key: String, isAd: Boolean) {
            val arr = j.optJSONArray(key) ?: return
            for (k in 0 until arr.length()) {
                val row = arr.optJSONArray(k) ?: continue
                add(DoubleArray(row.length()) { row.optDouble(it) }, isAd)
            }
        }
        read("game", false)
        read("ad", true)
    }
}
