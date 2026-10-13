package com.rickle8.mvswitch.core

enum class Heard { GAME, AD, UNSURE }

/** One second of audio, scored: how game-like and how ad-like it is. */
class Frame(val t: Double, val game: Double, val ad: Double, val loudDb: Double)

val GAME_CLASSES = setOf(
    "Crowd", "Cheering", "Applause", "Hubbub, speech noise, speech babble",
    "Chatter", "Whistle", "Shout", "Yell",
)
/** A crowd reacting: what follows a big play by a second or two. */
val CROWD_CLASSES = setOf("Crowd", "Cheering", "Applause", "Yell", "Shout")
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

/**
 * How eager to be about calling a commercial right now.
 * windowSecs: how much recent audio to average; minAdSecs: how long it must
 * sound like an ad; shift: added to both thresholds (negative = easier).
 */
data class Bias(
    val windowSecs: Double,
    val minAdSecs: Double,
    val shift: Double = 0.0,
    val why: String? = null,
) {
    companion object {
        val NORMAL = Bias(10.0, 8.0)
        /** Sound dipped and then changed character: a break may have just started. */
        val TRANSITION = Bias(4.0, 3.0, 0.0, "sound changed suddenly")
        /** A play that usually leads to a break just happened. */
        val LIKELY = Bias(6.0, 4.0, -0.05, "break likely after that play")
        /** The game just came back from a break with nothing since that causes another. */
        val UNLIKELY = Bias(10.0, 14.0, 0.08, "just back from a break")
        val TRANSITION_BUT_UNLIKELY = Bias(5.0, 5.0, 0.0, "sound changed suddenly")
        /** The game clock is running: commercials only air when it's stopped. */
        val CLOCK = Bias(10.0, 16.0, 0.12, "game clock is running")
        /** Just switched games: if this one is also in a break, notice fast and move on. */
        val LANDED = Bias(5.0, 4.0, 0.0, "checking the game it just switched to")
    }
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

    /** Share of ad-like audio in the last [over] seconds, 0..1, or null if not enough signal. */
    fun adFraction(over: Double = windowSecs): Double? {
        val last = frames.lastOrNull() ?: return null
        val recent = frames.filter { last.t - it.t < over }
        val useful = recent.filter { it.loudDb > silenceDb }
        if (useful.size < maxOf(3, recent.size / 2)) return null
        val game = useful.sumOf { it.game }
        val ad = useful.sumOf { it.ad }
        if (game + ad < 0.05 * useful.size) return null
        return ad / (game + ad)
    }

    /** The game is back (the network's coming-back music): no need to wait. */
    fun forceGame(t: Double) {
        state = Heard.GAME
        adSince = null
    }

    /** A replayed commercial was recognized: no need to wait. */
    fun forceAd(t: Double) {
        state = Heard.AD
        adSince = t
    }

    fun add(frame: Frame, bias: Bias? = null): Heard {
        val window = bias?.windowSecs ?: windowSecs
        val minAd = bias?.minAdSecs ?: minAdSecs
        val shift = bias?.shift ?: 0.0
        frames.addLast(frame)
        val keep = maxOf(windowSecs, window)
        while (frames.isNotEmpty() && frame.t - frames.first().t > keep) frames.removeFirst()
        val span = frames.last().t - frames.first().t
        if (span < window * 0.6) return state
        val frac = adFraction(window) ?: return state
        when {
            frac >= adThreshold + shift -> {
                val since = adSince ?: frame.t.also { adSince = it }
                if (frame.t - since >= minAd) state = Heard.AD
            }
            frac <= gameThreshold + shift -> {
                adSince = null
                state = Heard.GAME
            }
            else -> adSince = null
        }
        return state
    }
}
