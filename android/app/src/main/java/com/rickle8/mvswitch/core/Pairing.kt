package com.rickle8.mvswitch.core

/** What the camera sees on one box right now. */
data class Sight(
    /** The game's own score bug (its teams and score) read in the last few seconds. */
    val bug: Boolean = false,
    /** Its score bug, read dependably on this box, missing this long (8 s or more); null otherwise. */
    val bugGoneSecs: Double? = null,
    /** Yard lines on the field in view lately (proven on this box). */
    val lines: Boolean = false,
    /** A wide shot of the field's green lately (proven on this box). */
    val grass: Boolean = false,
    /** Commercial text (fine print, a web address) read lately, with no score bug since. */
    val adText: Boolean = false,
    /** The score bug's learned look (proven on this box): up, gone, or not known. */
    val bugShape: Boolean? = null,
) {
    /**
     * The camera's evidence in points: + for a commercial, - for the game. Its own score bug read
     * and yard lines are the strongest signs of the game; the field's green alone counts a bit less
     * (lawns and golf courses are green too). A field with the dependable score bug missing is
     * likely a commercial's (a promo, an ad shot on a field), so it counts for little.
     */
    val points: Double get() {
        val gone = bugGoneSecs != null
        var p = 0.0
        if (bug) p -= 3.0
        if (lines) p -= if (gone) 1.0 else 3.0
        if (grass && !lines) p -= if (gone) 0.5 else 2.0
        when (bugShape) { true -> p -= 1.5; false -> p += 2.0; null -> Unit }
        if (adText) p += 3.0
        bugGoneSecs?.let { p += if (it >= 45) 2.5 else 1.5 }
        return p.coerceIn(-5.0, 5.0)
    }

    /** The camera's word on the box: the game (true), a commercial (false), or not sure (null). */
    val word: Boolean? get() = points.let { if (it <= -1.5) true else if (it >= 2.0) false else null }

    /** In a few words, for the screens: "score bug, yard lines" or "score bug gone 12 s, ad text". */
    fun describe(): String {
        val parts = ArrayList<String>()
        if (bug) parts += "score bug"
        if (lines) parts += "yard lines"
        if (grass && !lines) parts += "field"
        if (adText) parts += "ad text"
        bugGoneSecs?.let { parts += "score bug gone ${it.toInt()} s" }
        if (bugShape != null && !bug && bugGoneSecs == null) parts += if (bugShape) "score bar" else "no score bar"
        return parts.joinToString(", ").ifEmpty { "not sure" }
    }
}

/**
 * The sound and the camera, paired into one call on the box you're hearing: commercial or the game.
 * The sound leads (it hears the cut to commercial first) and the camera confirms or holds it:
 *
 * - The camera sure it's the game (score bug, yard lines): an ad-sounding moment doesn't switch, and
 *   unsure sound counts as the game. Only a recognized commercial gets past, and not past the score
 *   bug read right off the screen.
 * - Leaning game (the field's green): holds an ad-sounding moment unless the sound is very sure.
 * - Sure it's a commercial (commercial text, the score bug long gone): an ad-sounding moment is a
 *   sure commercial, unsure sound is one too, and even game-like sound is when two camera signs agree.
 * - Leaning commercial (the score bug just went away): ad-leaning sound is a commercial, an
 *   ad-sounding one a sure one.
 *
 * Your "It's the game" keeps the camera from calling that game a commercial.
 */
object Pairing {
    const val SURE = 2.5
    const val LEAN_AD = 1.0
    const val LEAN_GAME = 1.8

    data class Call(
        val heard: Heard,
        val strong: Boolean,
        val why: String?,
        /** The camera kept an ad-sounding moment from switching. */
        val held: Boolean = false,
        /** How the two lined up, for the screens. */
        val summary: String = "",
    )

    fun decide(heard: Heard, adPct: Int?, strongAd: Boolean, why: String?, sight: Sight?, userSaidGame: Boolean): Call {
        val sound = soundWords(heard, adPct)
        if (sight == null) return Call(heard, strongAd, why, summary = "Sound: $sound")
        var pts = sight.points
        if (userSaidGame && pts > 0) pts = 0.0
        val seen = sight.describe()
        val pct = adPct ?: 0
        fun call(h: Heard, strong: Boolean, w: String?, held: Boolean, verdict: String) =
            Call(h, strong, w, held, "Sound: $sound · Camera: $seen → $verdict")
        return when {
            pts <= -SURE -> when {
                heard == Heard.AD && (!strongAd || sight.bug) ->
                    call(Heard.UNSURE, false, null, true, "the game (camera sees it)")
                heard == Heard.AD -> call(heard, true, why, false, "commercial (recognized it)")
                else -> call(Heard.GAME, false, null, false, "the game")
            }
            pts <= -LEAN_GAME && heard == Heard.AD && !strongAd && pct < 75 ->
                call(Heard.UNSURE, false, null, true, "probably the game (holding)")
            pts >= SURE -> when (heard) {
                Heard.AD -> call(Heard.AD, true, why.takeIf { strongAd } ?: "Sounds like a commercial and the camera agrees ($seen)", false, "commercial (both agree)")
                Heard.UNSURE -> call(Heard.AD, false, why, false, "commercial (camera sure)")
                Heard.GAME -> if (sight.adText && sight.bugGoneSecs != null && pct >= 30)
                    call(Heard.AD, false, "The camera sees a commercial ($seen)", false, "commercial (camera sure)")
                else call(heard, strongAd, why, false, "the game (sound)")
            }
            pts >= LEAN_AD -> when {
                heard == Heard.AD -> call(Heard.AD, true, why.takeIf { strongAd } ?: "Sounds like a commercial and the camera agrees ($seen)", false, "commercial (both agree)")
                heard == Heard.UNSURE && pct >= 45 -> call(Heard.AD, false, why, false, "commercial (both lean that way)")
                else -> call(heard, strongAd, why, false, soundVerdict(heard))
            }
            else -> call(heard, strongAd, why, false, soundVerdict(heard))
        }
    }

    private fun soundWords(heard: Heard, adPct: Int?): String =
        (when (heard) { Heard.AD -> "commercial"; Heard.GAME -> "game"; Heard.UNSURE -> "not sure" }) +
            (adPct?.let { " ($it% ad)" } ?: "")

    private fun soundVerdict(heard: Heard) = when (heard) {
        Heard.AD -> "commercial (sound)"; Heard.GAME -> "the game (sound)"; Heard.UNSURE -> "not sure yet"
    }
}
