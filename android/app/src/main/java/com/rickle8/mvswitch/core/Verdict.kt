package com.rickle8.mvswitch.core

enum class Heard { GAME, AD, UNSURE }

/** One second of audio, already scored. */
data class Frame(val t: Double, val game: Double, val ad: Double, val loudDb: Double)

val GAME_CLASSES = setOf(
    "Crowd", "Cheering", "Applause", "Hubbub, speech noise, speech babble",
    "Chatter", "Whistle", "Roar",
)
val AD_CLASSES = setOf(
    "Music", "Pop music", "Hip hop music", "Rock music", "Electronic music",
    "Jingle (music)", "Theme music", "Background music", "Soundtrack music",
    "Television", "Singing",
)

/** Rolling window + hysteresis: game audio or commercial? */
class Verdict(
    var windowSecs: Double = 8.0,
    var adThreshold: Double = 0.6,
    var gameThreshold: Double = 0.4,
    var minAdSecs: Double = 5.0,
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
