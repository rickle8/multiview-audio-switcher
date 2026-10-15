package com.rickle8.mvswitch.core

import kotlin.math.pow

/** What one box's announcers could be talking about right now. */
data class BoxFacts(
    val league: String,
    val names: Set<String> = emptySet(),
    val teams: Set<String> = emptySet(),
    /** Recent scores, oldest first (the TV runs behind, so an older one may still be on air). */
    val scores: List<List<Int>> = emptyList(),
    val period: Int = 0,
    /** Baseball: "top" or "bottom" of the inning. */
    val half: String? = null,
    val down: Int? = null,
    val distance: Int? = null,
    val outs: Int? = null,
)

/** One reason to think a box is the one being heard. */
data class Clue(val box: Int, val points: Double, val why: String)

private val ORDINALS = mapOf(
    "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4,
    "fifth" to 5, "5th" to 5, "sixth" to 6, "6th" to 6, "seventh" to 7, "7th" to 7, "eighth" to 8, "8th" to 8,
    "ninth" to 9, "9th" to 9, "tenth" to 10, "10th" to 10, "eleventh" to 11, "11th" to 11, "twelfth" to 12, "12th" to 12,
)

private val SMALL = mapOf(
    "zero" to 0, "nothing" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
    "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
)
private val TENS = mapOf(
    "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
    "eighty" to 80, "ninety" to 90,
)

/** "thirty two to twenty five" -> "32 to 25", so scores read the same however the recognizer writes them. */
fun numbersToDigits(text: String): String {
    val words = text.lowercase().replace(Regex("[^a-z0-9&' -]"), " ")
        .replace(Regex("(?<=[a-z])-(?=[a-z])"), " ").replace("-", " - ").split(Regex("\\s+")).filter { it.isNotBlank() }
    val out = ArrayList<String>()
    var i = 0
    while (i < words.size) {
        val w = words[i]
        val tens = TENS[w]
        if (tens != null) {
            val unit = words.getOrNull(i + 1)?.let { SMALL[it] }?.takeIf { it in 1..9 }
            out += (tens + (unit ?: 0)).toString(); i += if (unit != null) 2 else 1; continue
        }
        val small = SMALL[w]
        out += small?.toString() ?: w
        i++
    }
    return out.joinToString(" ")
}

/** Words that only come up in one sport's broadcast. */
fun sportWords(league: String): List<String> = when {
    league.startsWith("football") -> listOf("yards", "yard line", "first down", "second down", "third down", "fourth down",
        "touchdown", "field goal", "quarterback", "end zone", "red zone", "snap", "sacked", "punt", "kickoff",
        "line of scrimmage", "interception", "fumble", "incomplete", "pass interference", "holding", "receiver",
        "running back", "linebacker", "huddle", "two minute warning", "extra point")
    league.startsWith("basketball") -> listOf("rebound", "three pointer", "three point", "free throw", "layup", "dunk",
        "shot clock", "assist", "fast break", "paint", "jumper", "and one", "bucket", "from downtown", "point guard",
        "fouled", "triple double", "double double", "on the glass", "backcourt")
    league.startsWith("baseball") -> listOf("pitch", "inning", "strike", "ball four", "full count", "home run", "pitcher",
        "bullpen", "fastball", "curveball", "slider", "changeup", "batter", "at bat", "double play", "ground ball",
        "fly ball", "line drive", "base hit", "on deck", "two outs", "one out", "the count", "dugout", "outfield", "infield")
    league.startsWith("hockey") -> listOf("puck", "power play", "goalie", "goaltender", "faceoff", "slap shot", "wrist shot",
        "icing", "blue line", "penalty kill", "empty net", "one timer", "breakaway", "the crease")
    league.startsWith("soccer") -> listOf("penalty area", "corner kick", "free kick", "offside", "keeper", "the box",
        "yellow card", "red card", "header", "stoppage time", "midfield", "the pitch")
    else -> emptyList()
}

/**
 * Works out which multiview box is being heard from what the announcers say:
 * player and team names, the score, down and distance, the inning, words only
 * one sport uses. Evidence builds up and fades over about a minute, so the app
 * can tell when the highlight isn't where it thinks and correct itself.
 */
class Locator(private val halfLifeSecs: Double = 60.0) {
    private val points = HashMap<Int, Double>()
    private val reasons = HashMap<Int, ArrayDeque<Pair<Double, String>>>()
    private val lastClue = HashMap<Int, Double>()
    private var at = 0.0
    var expected: Int? = null
        private set

    private fun decay(now: Double) {
        if (now <= at) return
        val k = 0.5.pow((now - at) / halfLifeSecs)
        for (b in points.keys.toList()) points[b] = points.getValue(b) * k
        at = now
    }

    /** Everything in [text] that points at a box. */
    fun clues(text: String, games: Map<Int, BoxFacts>): List<Clue> {
        val t = " " + numbersToDigits(text) + " "
        val out = ArrayList<Clue>()
        fun has(p: String) = t.contains(" $p ")

        // Names: a player or team only one of the games has.
        val allNames = games.mapValues { (_, g) -> g.names + g.teams }
        val said = allNames.values.flatten().toSet().filter { it.length >= 3 && has(it) }
        for (n in said) {
            val boxes = allNames.filterValues { n in it }.keys
            val isTeam = games.values.any { n in it.teams }
            val p = (if (isTeam) 3.0 else 2.5) / boxes.size
            boxes.forEach { out += Clue(it, if (boxes.size == 1) p else p * 0.4, n) }
        }

        // Sport: "rebound" can't be the baseball game.
        val bySport = games.entries.groupBy { sportWords(it.value.league) }
        for ((words, entries) in bySport) {
            if (words.isEmpty()) continue
            val hits = words.filter { has(it) }.take(3)
            if (hits.isEmpty()) continue
            val share = if (entries.size == games.size) 0.0 else 0.9 * hits.size // no help if every game is this sport
            if (share > 0) entries.forEach { out += Clue(it.key, share, hits.first()) }
        }

        // Score: "32 to 25", "32-25", "up 32 25".
        for (m in Regex(" (\\d{1,3}) (?:to|-|and|,)? ?(\\d{1,3}) ").findAll(t)) {
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if (a == b && a < 2) continue // "one and one", "nothing nothing": too common
            for ((box, g) in games) {
                val pair = setOf(a, b)
                val match = g.scores.any { it.size == 2 && it.toSet() == pair && (a != b || it[0] == it[1]) }
                if (match && maxOf(a, b) >= 2) out += Clue(box, 3.0, "$a-$b")
            }
        }

        // Football: "third and 4", "2nd & goal".
        Regex(" (first|second|third|fourth|1st|2nd|3rd|4th) (?:and|&) (\\d{1,2}|goal|inches) ").find(t)?.let { m ->
            val down = ORDINALS[m.groupValues[1]] ?: return@let
            val dist = m.groupValues[2].toIntOrNull()
            for ((box, g) in games) if (g.league.startsWith("football") && g.down == down &&
                (dist == null || g.distance == null || g.distance == dist)) out += Clue(box, 2.5, "${m.groupValues[1]} and ${m.groupValues[2]}")
        }

        // Baseball: "top of the third", "bottom 7th", "two outs".
        Regex(" (top|bottom) (?:of )?(?:the )?(\\w+) ").find(t)?.let { m ->
            val inning = ORDINALS[m.groupValues[2]] ?: m.groupValues[2].toIntOrNull() ?: return@let
            for ((box, g) in games) if (g.league.startsWith("baseball") && g.period == inning && (g.half == null || g.half == m.groupValues[1]))
                out += Clue(box, 2.0, "${m.groupValues[1]} of the ${m.groupValues[2]}")
        }
        Regex(" (0|1|2) outs? ").find(t)?.let { m ->
            val o = m.groupValues[1].toInt()
            for ((box, g) in games) if (g.league.startsWith("baseball") && g.outs == o) out += Clue(box, 0.8, "${m.groupValues[1]} out")
        }

        // Quarter/period: "early in the fourth quarter".
        Regex(" (first|second|third|fourth|1st|2nd|3rd|4th) (quarter|period) ").find(t)?.let { m ->
            val q = ORDINALS[m.groupValues[1]] ?: return@let
            for ((box, g) in games) if (!g.league.startsWith("baseball") && g.period == q) out += Clue(box, 0.8, "${m.groupValues[1]} ${m.groupValues[2]}")
        }
        return out
    }

    /** The announcers said [text] on whichever box is playing. Returns what it pointed to. */
    @Synchronized
    fun hear(text: String, now: Double, games: Map<Int, BoxFacts>): List<Clue> {
        decay(now)
        val cl = clues(text, games)
        for (c in cl) {
            points[c.box] = (points[c.box] ?: 0.0) + c.points
            lastClue[c.box] = now
            val r = reasons.getOrPut(c.box) { ArrayDeque() }
            r.addLast(now to c.why)
            while (r.size > 8) r.removeFirst()
        }
        return cl
    }

    /** The audio moved to [box] (the app switched, or you did): start fresh, expecting that one. */
    @Synchronized
    fun reset(box: Int?, now: Double) {
        points.clear(); reasons.clear(); lastClue.clear()
        expected = box
        at = now
    }

    /** Something from this box was said in the last [secs]: its game is on, not a commercial. */
    @Synchronized
    fun heardRecently(box: Int, now: Double, secs: Double): String? =
        reasons[box]?.lastOrNull()?.takeIf { now - it.first <= secs && (lastClue[box] ?: -1e9) >= now - secs }?.second

    /**
     * The box being heard, if what's been said makes it clear: well ahead of
     * every other box, and enough evidence to act on.
     */
    @Synchronized
    fun where(now: Double): Pair<Int, List<String>>? {
        decay(now)
        val ranked = points.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0.0
        if (top.value < 7.0 || top.value - second < 5.0) return null
        val why = reasons[top.key].orEmpty().filter { now - it.first < 120 }.map { it.second }.distinct().takeLast(3)
        return top.key to why
    }

    @Synchronized
    fun score(box: Int, now: Double): Double { decay(now); return points[box] ?: 0.0 }
}
