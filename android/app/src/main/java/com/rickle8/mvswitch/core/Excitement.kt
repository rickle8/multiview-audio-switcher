package com.rickle8.mvswitch.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How worth-watching a game is right now.
 * score = importance x (base + closeness x lateness boost + red zone + overtime)
 */

private data class Sport(val periods: Int, val periodSecs: Int?, val blowout: Int)

private val SPORTS = linkedMapOf(
    "basketball/mens-college-basketball" to Sport(2, 1200, 15),
    "basketball/womens-college-basketball" to Sport(4, 600, 15),
    "basketball/wnba" to Sport(4, 600, 15),
    "basketball" to Sport(4, 720, 15),
    "football" to Sport(4, 900, 17),
    "hockey" to Sport(3, 1200, 3),
    "baseball" to Sport(9, null, 4),
    "soccer" to Sport(2, 2700, 2),
)

private fun sport(league: String): Sport =
    SPORTS.entries.sortedByDescending { it.key.length }
        .firstOrNull { league.startsWith(it.key) }?.value ?: SPORTS.getValue("football")

private val PICK = Regex("""^\s*([A-Za-z]+)\s*([+-]?\d+(?:\.\d+)?)?\s*$""")

/** 0 at start, 1 at end of regulation, >1 in overtime. */
fun progress(info: GameInfo): Double {
    val s = sport(info.league)
    if (info.period <= 0) return 0.0
    val secs = s.periodSecs
    val clock = info.clock
    if (secs == null || clock == null) return info.period.toDouble() / s.periods
    if (info.period > s.periods) return 1 + (info.period - s.periods) * 0.1
    val done = (info.period - 1) + (1 - min(max(clock, 0.0), secs.toDouble()) / secs)
    return done / s.periods
}

fun closeness(info: GameInfo, pick: String? = null): Double {
    if (info.scores.size != 2) return 0.5
    val blowout = sport(info.league).blowout
    val (a, b) = info.scores.entries.toList()
    var close = max(0.0, 1 - abs(a.value - b.value).toDouble() / blowout)
    val m = PICK.matchEntire(pick.orEmpty())
    val line = m?.groupValues?.getOrNull(2)?.toDoubleOrNull()
    if (m != null && line != null) {
        val team = m.groupValues[1].uppercase()
        val mine = info.scores[team]
        if (mine != null) {
            val other = if (team == a.key) b.value else a.value
            val ats = abs(mine + line - other)
            close = max(close, max(0.0, 1 - ats / blowout))
        }
    }
    return close
}

/** Dead time in football: a kickoff, an extra point, a touchback (and usually a commercial next). */
private val DEAD_PLAY = Regex("kickoff|kicks \\d+ yards|extra point|touchback|two.point|\\bpat\\b", RegexOption.IGNORE_CASE)

private fun margin(info: GameInfo): Int? =
    info.scores.values.toList().takeIf { it.size == 2 }?.let { abs(it[0] - it[1]) }

/**
 * How worth watching a game is right now, the way RedZone picks: a close game late, and in football
 * a touchdown one snap away (first and goal), the red zone, a 4th down, a one-score game in the last
 * minutes; a kickoff or extra point is dead time.
 */
fun excitement(info: GameInfo?, slot: GameSlot?): Double {
    val importance = slot?.importance ?: 1.0
    if (info == null) return 0.5 * importance
    if (info.status != GameStatus.LIVE && info.status != GameStatus.BREAK) return 0.0
    val p = progress(info)
    val c = closeness(info, slot?.pick)
    val m = margin(info)
    var score = 0.3 + c * (0.4 + 1.3 * min(p, 1.0).pow(2))
    if (info.league.startsWith("football")) {
        if (info.redZone) score += 0.4
        if (info.goalToGo) score += 0.2
        // 4th down in a game still in reach: go for it, a long field goal, a stop.
        if (info.down == 4 && c > 0.3) score += 0.25
        // One score (8 points) with a few minutes left, more in the last two.
        if (m != null && m <= 8 && p in 0.9..1.0) score += if (p >= 0.97) 0.6 else 0.35
        info.lastPlay?.let { if (DEAD_PLAY.containsMatchIn(it)) score -= 0.25 }
    } else if (info.league.startsWith("basketball")) {
        // A one- or two-possession game in its last minutes.
        if (m != null && m <= 6 && p in 0.93..1.0) score += if (p >= 0.98) 0.6 else 0.35
    } else if (info.redZone) score += 0.4
    if (p > 1 && c > 0.5) score += 0.8
    return ((maxOf(0.0, score) * importance) * 1000).roundToInt() / 1000.0
}
