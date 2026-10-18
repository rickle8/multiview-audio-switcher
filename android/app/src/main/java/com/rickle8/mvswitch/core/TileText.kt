package com.rickle8.mvswitch.core

/**
 * Reading each multiview box the way a person does: the text on screen. A game's
 * score bug says the teams, the score, the quarter and the clock ("FLORIDA ST 14
 * 1ST & 10 3RD 5:45 14 MIAMI"). A commercial doesn't. The phone reads the text with
 * its own built-in text recognition (no internet); this decides what the text means.
 */
data class TileRead(
    /** It reads like a game broadcast (score bug, clock, quarter, teams). */
    val game: Boolean,
    /** How sure (0 = nothing game-like, 5+ = unmistakable). */
    val strength: Int,
    /** Which of your games it is, if the team names say so (box number of that game). */
    val showsGameOf: Int?,
    /** A short version of what it read, for the setup screen. */
    val summary: String,
    /** It reads like a commercial: fine print, a web address, a price offer (and no score bug in it). */
    val ad: Boolean = false,
)

object TileText {
    // Note for every pattern here: Android's regex engine (ICU) reads "[:" as the start of a
    // "[:Letter:]"-style class and refuses the pattern, crashing the app. Never begin a set with ':'.
    private val CLOCK = Regex("""(?<![\d:])([0-5]?\d)[.:]([0-5]\d)(?![\d:])""")
    private val PERIOD = Regex("""\b(1ST|2ND|3RD|4TH|OT|HALF|HALFTIME|FINAL|Q[1-4])\b""")
    private val DOWN = Regex("""\b(1ST|2ND|3RD|4TH)\s*(&|AND|8)\s*(\d{1,2}|GOAL|GL|INCHES)\b""")
    private val SCORE = Regex("""\b(\d{1,2})\b""")

    /** Commercial text that's enough on its own: web addresses, financing fine print, disclaimers, phone numbers. */
    private val AD_SURE = Regex(
        """\.(COM|NET|ORG)\b|\bWWW\.|\bAPR\b|\bMSRP\b|\bSEE DEALER\b|\bCLOSED COURSE\b|\bDO NOT ATTEMPT\b|""" +
            """\bASK YOUR DOCTOR\b|\bSIDE EFFECTS\b|\bRESTRICTIONS APPLY\b|\bTERMS APPLY\b|\bOFFER ENDS\b|""" +
            """\bPAID FOR BY\b|\bAPPROVED? THIS MESSAGE\b|\b1 ?8(00|88|77|66|55|44|33)\b""")
    /** Commercial words that take two together (one alone shows up in game graphics too). */
    private val AD_HINTS = listOf(
        "SHOP NOW", "BUY NOW", "ORDER NOW", "CALL NOW", "LEARN MORE", "LIMITED TIME", "PER MONTH", "LEASE",
        "FINANCING", "DEALER", "DOWNLOAD", "APP STORE", "GOOGLE PLAY", "IN THEATERS", "NOW STREAMING",
        "STREAMING NOW", "AVAILABLE NOW", "SUBSCRIBE", "INSURANCE", "VISIT", "SALE", "FREE TRIAL",
    ).map { Regex("""\b$it\b""") } + Regex("""\b\d+ OFF\b""") + Regex("""\bRATED (PG|R)\b""")

    private val OTHER_CHARS = Regex("[^A-Z0-9.:&' ]")
    private val SPACES = Regex("\\s+")

    private fun norm(s: String) = s.uppercase().replace('’', '\'').replace(OTHER_CHARS, " ")
        .replace(SPACES, " ").trim()

    /**
     * [teams]: each box's team words (names, places, abbreviations), [scores]: each box's
     * current score from ESPN. Returns what this box's text says.
     */
    fun read(text: String, teams: Map<Int, Set<String>>, scores: Map<Int, List<Int>> = emptyMap()): TileRead {
        val t = " " + norm(text) + " "
        var strength = 0
        val clock = CLOCK.containsMatchIn(t)
        val period = PERIOD.containsMatchIn(t)
        val down = DOWN.containsMatchIn(t)
        if (clock) strength++
        if (period) strength++
        if (down) strength += 2
        if (clock && period) strength++
        // Which game: the most team words from one game's teams (whole words only, so "OSU" isn't in "BOSUN").
        val hits = teams.mapValues { (_, words) ->
            words.map { norm(it) }.filter { it.length >= 3 }.distinct().count { w -> t.contains(" $w ") }
        }.filterValues { it > 0 }
        val best = hits.maxByOrNull { it.value }
        val second = hits.values.sortedDescending().getOrNull(1) ?: 0
        val showsGameOf = best?.takeIf { it.value > second }?.key
        if (showsGameOf != null) strength += minOf(2, best.value)
        // Both teams' scores on screen, matching ESPN.
        val numbers = SCORE.findAll(t).map { it.groupValues[1].toInt() }.toSet()
        if (showsGameOf != null) scores[showsGameOf]?.takeIf { it.size == 2 }?.let { s ->
            if (numbers.containsAll(s)) strength++
        }
        val summary = norm(text).take(60)
        // Game-like: a clock with a quarter, or down and distance, or a team of one of your games plus a clock or quarter.
        val game = strength >= 3 || (showsGameOf != null && (clock || period))
        // Commercial-like, only without a score bug in the same picture (sponsor graphics show up mid-game).
        val ad = !game && (AD_SURE.containsMatchIn(t) || AD_HINTS.count { it.containsMatchIn(t) } >= 2)
        return TileRead(game, strength, showsGameOf, summary, ad)
    }
}

/**
 * From the upright picture (what people see) back to the camera's own buffer, which is
 * stored turned [deg] degrees the other way. Fractions in, fractions out.
 */
fun uprightToRaw(u: Double, v: Double, deg: Int): Pair<Double, Double> = when (deg) {
    90 -> v to (1 - u)
    180 -> (1 - u) to (1 - v)
    270 -> (1 - v) to u
    else -> u to v
}

/**
 * One box, straightened out of the full-resolution camera buffer (still turned [deg]
 * degrees), at [outW] x [outH], sharp enough to read the score bug.
 */
fun warpBoxFromRaw(raw: GrayFrame, deg: Int, corners: List<Pair<Double, Double>>, rect: DoubleArray, outW: Int, outH: Int): ByteArray {
    val hom = Homography(corners)
    val out = ByteArray(outW * outH)
    val maxX = raw.width - 1.001; val maxY = raw.height - 1.001
    for (j in 0 until outH) for (i in 0 until outW) {
        val gx = rect[0] + (rect[2] - rect[0]) * (i + 0.5) / outW
        val gy = rect[1] + (rect[3] - rect[1]) * (j + 0.5) / outH
        val (u, v) = hom.map(gx, gy)               // upright fractions
        val (rx, ry) = uprightToRaw(u, v, deg)      // raw fractions
        val x = (rx * raw.width).coerceIn(0.0, maxX); val y = (ry * raw.height).coerceIn(0.0, maxY)
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = x - x0; val fy = y - y0
        val x1 = minOf(x0 + 1, raw.width - 1); val y1 = minOf(y0 + 1, raw.height - 1)
        val top = raw.at(x0, y0) * (1 - fx) + raw.at(x1, y0) * fx
        val bot = raw.at(x0, y1) * (1 - fx) + raw.at(x1, y1) * fx
        out[j * outW + i] = (top * (1 - fy) + bot * fy).toInt().coerceIn(0, 255).toByte()
    }
    return out
}
