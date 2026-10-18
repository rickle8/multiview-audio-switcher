package com.rickle8.mvswitch.core

/**
 * Reading each multiview box the way a person does: the text on screen. A game's
 * score bug says the teams, the score, the quarter and the clock ("FLORIDA ST 14
 * 1ST & 10 3RD 5:45 14 MIAMI"). A commercial doesn't. Some networks also run a ticker
 * along the bottom (ESPN's BottomLine): other games' scores and headlines. That means the
 * network's own programming is on (tickers don't run in commercials), but its scores are
 * other games', not the one in the box. The phone reads the text with its own built-in
 * text recognition (no internet); this decides what the text means.
 */
data class TileRead(
    /** The game in the box is being played: its score bug with a live quarter, clock, down and distance. */
    val game: Boolean,
    /** How sure (0 = nothing game-like, 5+ = unmistakable). */
    val strength: Int,
    /** Which of your games it is, if the team names say so (box number of that game). */
    val showsGameOf: Int?,
    /** A short version of what it read, for the setup screen. */
    val summary: String,
    /** It reads like a commercial: fine print, a web address, a price offer (and no score bug in it). */
    val ad: Boolean = false,
    /** A ticker of other games' scores or headlines: the network's programming is on, not a commercial. */
    val ticker: Boolean = false,
    /** The box's own text says its game isn't being played right now (halftime, final, end of a quarter). */
    val notLive: Boolean = false,
)

/** One line of text read in a box, and where it sits as fractions of the box (0 = top/left). */
data class TileLine(
    val text: String,
    val top: Double = 0.0,
    val bottom: Double = 0.5,
    val left: Double = 0.0,
    val right: Double = 1.0,
)

object TileText {
    // Note for every pattern here: Android's regex engine (ICU) reads "[:" as the start of a
    // "[:Letter:]"-style class and refuses the pattern, crashing the app. Never begin a set with ':'.
    private val CLOCK = Regex("""(?<![\d:])([0-5]?\d)[.:]([0-5]\d)(?![\d:])""")
    private val PERIOD = Regex("""\b(1ST|2ND|3RD|4TH|OT|Q[1-4])\b""")
    private val DOWN = Regex("""\b(1ST|2ND|3RD|4TH)\s*(&|AND|8)\s*(\d{1,2}|GOAL|GL|INCHES)\b""")
    private val SCORE = Regex("""\b(\d{1,2})\b""")
    /** The game's own graphics saying it isn't being played right now. */
    private val NOT_LIVE = Regex("""\b(HALF|HALFTIME|FINAL|END OF (THE )?(1ST|2ND|3RD|4TH|QUARTER|QTR)|END (1ST|2ND|3RD|4TH|QTR))\b""")
    /** League names a scores ticker shows ("NCAAF", "NFL") between games. */
    private val LEAGUE = Regex("""\b(NCAAF|NCAAB|NCAAM|NCAAW|NCAA|CFB|NFL|NBA|WNBA|NHL|MLB|MLS|PGA|LPGA|NASCAR|BOTTOMLINE|BOTTOM LINE|TOP 25)\b""")

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

    /** Plain text, one line per line (as the phone's text recognition gives it). */
    fun read(text: String, teams: Map<Int, Set<String>>, scores: Map<Int, List<Int>> = emptyMap(), own: Int? = null): TileRead =
        read(text.split('\n').map { TileLine(it) }, teams, scores, own)

    /**
     * [teams]: each box's team words (names, places, abbreviations), [scores]: each box's
     * current score from ESPN, [own]: the box being read (its game is the one set for it in Games).
     * Returns what this box's text says.
     */
    fun read(lines: List<TileLine>, teams: Map<Int, Set<String>>, scores: Map<Int, List<Int>> = emptyMap(), own: Int? = null): TileRead {
        val words = teams.mapValues { (_, w) -> w.map { norm(it) }.filter { it.length >= 3 }.distinct() }
        // Words that belong to one game only ("COUGARS" is both BYU and Houston: no help telling them apart).
        val owner = HashMap<String, Int?>()
        for ((box, ws) in words) for (w in ws) owner[w] = if (owner.containsKey(w) && owner[w] != box) null else box
        val picture = StringBuilder(" "); val ticker = StringBuilder()
        for (l in lines) {
            val t = " " + norm(l.text) + " "
            if (t.isBlank()) continue
            if (isTicker(t, l, owner, own)) ticker.append(t) else picture.append(t)
        }
        val t = " " + norm(picture.toString()) + " "
        val notLive = NOT_LIVE.containsMatchIn(t)
        var strength = 0
        val clock = CLOCK.containsMatchIn(t)
        val period = PERIOD.containsMatchIn(t)
        val down = DOWN.containsMatchIn(t)
        if (clock) strength++
        if (period) strength++
        if (down) strength += 2
        if (clock && period) strength++
        // Which game: the most team words from one game's teams (whole words only, so "OSU" isn't in "BOSUN").
        val hits = words.mapValues { (_, ws) -> ws.count { w -> t.contains(" $w ") } }.filterValues { it > 0 }
        val best = hits.maxByOrNull { it.value }
        val second = hits.values.sortedDescending().getOrNull(1) ?: 0
        val showsGameOf = best?.takeIf { it.value > second }?.key
        if (showsGameOf != null) strength += minOf(2, best.value)
        // Both teams' scores on screen, matching ESPN.
        val numbers = SCORE.findAll(t).map { it.groupValues[1].toInt() }.toSet()
        if (showsGameOf != null) scores[showsGameOf]?.takeIf { it.size == 2 }?.let { s ->
            if (numbers.containsAll(s)) strength++
        }
        val tickerText = norm(ticker.toString())
        val summary = (norm(picture.toString()).ifBlank { null }?.take(60) ?: "") +
            (if (tickerText.isNotBlank()) " [ticker: ${tickerText.take(30)}]" else "")
        // Being played: a clock with a quarter, or down and distance, or a team of one of your games plus a clock or
        // quarter. Not when its own graphics say halftime or final.
        val game = !notLive && (strength >= 3 || (showsGameOf != null && (clock || period)))
        // Commercial-like, only without a score bug or a ticker (sponsor graphics show up mid-game).
        val ad = !game && tickerText.isBlank() && (AD_SURE.containsMatchIn(t) || AD_HINTS.count { it.containsMatchIn(t) } >= 2)
        return TileRead(game, strength, showsGameOf, summary.trim(), ad, ticker = tickerText.isNotBlank(), notLive = notLive)
    }

    /**
     * A line from a scores ticker rather than the game's own graphics: a league name, two of your
     * games in one line, or along the very bottom another game's score, a score of a game that
     * isn't yours, "final" or a headline (never with down and distance: only the game's own
     * score bug shows that).
     */
    private fun isTicker(t: String, l: TileLine, owner: Map<String, Int?>, own: Int?): Boolean {
        if (LEAGUE.containsMatchIn(t)) return true
        val games = owner.filterValues { it != null }.filterKeys { w -> t.contains(" $w ") }.values.toSet()
        if (games.size >= 2) return true
        val bottom = (l.top + l.bottom) / 2 >= 0.86
        if (!bottom || DOWN.containsMatchIn(t)) return false
        if (games.isNotEmpty()) return own != null && own !in games // one of your other games, on this box's ticker
        val nums = SCORE.findAll(t.replace(CLOCK, " ")).count()
        return nums >= 2 || NOT_LIVE.containsMatchIn(t) || (l.right - l.left >= 0.55 && t.trim().length >= 12)
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
