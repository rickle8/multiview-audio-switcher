package com.rickle8.mvswitch.core

enum class Heard { GAME, AD, UNSURE }

/** One second of audio, already scored. `features` feeds the learned model. */
class Frame(
    val t: Double,
    val game: Double,
    val ad: Double,
    val loudDb: Double,
    val features: DoubleArray = DoubleArray(0),
) {
    fun withScores(game: Double, ad: Double) = Frame(t, game, ad, loudDb, features)
}

val GAME_CLASSES = setOf(
    "Crowd", "Cheering", "Applause", "Hubbub, speech noise, speech babble",
    "Chatter", "Whistle", "Shout", "Yell",
)
val SPEECH_CLASSES = setOf("Speech", "Male speech, man speaking", "Narration, monologue")
val AD_CLASSES = setOf(
    "Music", "Pop music", "Hip hop music", "Rock music", "Electronic music",
    "Jingle (music)", "Theme music", "Background music", "Soundtrack music", "Singing",
)

/**
 * Fixed rules used until the app has learned your TV.
 * Through a phone mic a game is mostly announcers with a little crowd, so
 * talking counts a bit toward "game"; commercials are mostly music. ("Television"
 * is deliberately not used: it fires on all TV audio.)
 */
fun heuristicScores(scores: Map<String, Float>): Pair<Double, Double> {
    val crowd = GAME_CLASSES.sumOf { (scores[it] ?: 0f).toDouble() }
    val speech = SPEECH_CLASSES.maxOf { (scores[it] ?: 0f).toDouble() }
    val music = AD_CLASSES.sumOf { (scores[it] ?: 0f).toDouble() }
    return (crowd + 0.35 * speech) to music
}

/** Rolling window + hysteresis: game audio or commercial? */
class Verdict(
    var windowSecs: Double = 10.0,
    var adThreshold: Double = 0.6,
    var gameThreshold: Double = 0.4,
    var minAdSecs: Double = 8.0,
    var silenceDb: Double = -55.0,
) {
    private val frames = ArrayDeque<Frame>()
    var state = Heard.UNSURE
        private set
    private var adSince: Double? = null

    fun reset() {
        frames.clear()
        state = Heard.UNSURE
        adSince = null
    }

    /** Share of ad-like audio in the window, 0..1, or null if not enough signal. */
    fun adFraction(): Double? {
        val useful = frames.filter { it.loudDb > silenceDb }
        if (useful.size < maxOf(3, frames.size / 2)) return null
        val game = useful.sumOf { it.game }
        val ad = useful.sumOf { it.ad }
        if (game + ad < 0.05 * useful.size) return null
        return ad / (game + ad)
    }

    fun add(frame: Frame): Heard {
        frames.addLast(frame)
        while (frames.isNotEmpty() && frame.t - frames.first().t > windowSecs) frames.removeFirst()
        if (frames.last().t - frames.first().t < windowSecs * 0.6) return state
        val frac = adFraction() ?: return state
        when {
            frac >= adThreshold -> {
                val since = adSince ?: frame.t.also { adSince = it }
                if (frame.t - since >= minAdSecs) state = Heard.AD
            }
            frac <= gameThreshold -> {
                adSince = null
                state = Heard.GAME
            }
            else -> adSince = null
        }
        return state
    }
}
