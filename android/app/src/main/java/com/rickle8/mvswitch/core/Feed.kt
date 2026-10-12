package com.rickle8.mvswitch.core

import org.json.JSONObject

/** Live game status from ESPN's public scoreboard data. Runs 10-60 s behind TV. */

enum class GameStatus { LIVE, BREAK, FINAL, PREGAME, UNKNOWN }

data class GameSlot(
    val league: String = "football/nfl",
    val team: String = "",
    val name: String = "",
    val importance: Double = 1.0,
    val pick: String = "",
)

data class GameInfo(
    var status: GameStatus = GameStatus.UNKNOWN,
    val statusName: String = "",
    val league: String = "",
    val period: Int = 0,
    val clock: Double? = null,
    val scores: LinkedHashMap<String, Int> = linkedMapOf(),
    val redZone: Boolean = false,
    val lastPlay: String? = null,
    val detail: String = "",
)

data class ScheduledGame(
    val league: String,
    val shortName: String,
    val away: String,
    val home: String,
    val detail: String,
    val status: GameStatus,
)

val BREAK_STATUSES = setOf(
    "STATUS_HALFTIME", "STATUS_END_PERIOD", "STATUS_DELAYED",
    "STATUS_RAIN_DELAY", "STATUS_END_OF_REGULATION",
)

val BREAK_PLAY = Regex(
    "timeout|injur|challenge|review|two.minute warning|touchdown|field goal|" +
        "end of (the )?(quarter|period|half|inning)|pitching change|kickoff|safety",
    RegexOption.IGNORE_CASE,
)

private fun JSONObject.firstCompetition(): JSONObject =
    optJSONArray("competitions")?.optJSONObject(0) ?: JSONObject()

fun parseEvent(event: JSONObject, league: String = ""): GameInfo {
    val comp = event.firstCompetition()
    val stObj = comp.optJSONObject("status") ?: event.optJSONObject("status") ?: JSONObject()
    val st = stObj.optJSONObject("type") ?: JSONObject()
    val state = st.optString("state")
    val name = st.optString("name")
    val sit = comp.optJSONObject("situation") ?: JSONObject()
    val scores = linkedMapOf<String, Int>()
    val competitors = comp.optJSONArray("competitors")
    if (competitors != null) {
        for (i in 0 until competitors.length()) {
            val c = competitors.optJSONObject(i) ?: continue
            val abbr = c.optJSONObject("team")?.optString("abbreviation").orEmpty().uppercase()
            scores[abbr] = c.optString("score").toDoubleOrNull()?.toInt() ?: 0
        }
    }
    val status = when {
        state == "post" || st.optBoolean("completed") -> GameStatus.FINAL
        state == "pre" -> GameStatus.PREGAME
        state != "in" -> GameStatus.UNKNOWN
        name in BREAK_STATUSES -> GameStatus.BREAK
        else -> GameStatus.LIVE
    }
    return GameInfo(
        status = status,
        statusName = name,
        league = league,
        period = stObj.optInt("period", 0),
        clock = if (stObj.has("clock")) stObj.optDouble("clock") else null,
        scores = scores,
        redZone = sit.optBoolean("isRedZone", false),
        lastPlay = sit.optJSONObject("lastPlay")?.optString("text")?.takeIf { it.isNotBlank() },
        detail = st.optString("shortDetail"),
    )
}

fun findEvent(board: JSONObject, team: String): JSONObject? {
    val want = team.uppercase()
    val events = board.optJSONArray("events") ?: return null
    for (i in 0 until events.length()) {
        val e = events.optJSONObject(i) ?: continue
        val competitors = e.firstCompetition().optJSONArray("competitors") ?: continue
        for (j in 0 until competitors.length()) {
            val t = competitors.optJSONObject(j)?.optJSONObject("team") ?: continue
            if (want == t.optString("abbreviation").uppercase() ||
                want == t.optString("displayName").uppercase()
            ) return e
        }
    }
    return null
}

/** Today's games for a league, for the game picker. */
fun listGames(board: JSONObject, league: String): List<ScheduledGame> {
    val out = mutableListOf<ScheduledGame>()
    val events = board.optJSONArray("events") ?: return out
    for (i in 0 until events.length()) {
        val e = events.optJSONObject(i) ?: continue
        val comps = e.firstCompetition().optJSONArray("competitors") ?: continue
        var home = ""
        var away = ""
        for (j in 0 until comps.length()) {
            val c = comps.optJSONObject(j) ?: continue
            val abbr = c.optJSONObject("team")?.optString("abbreviation").orEmpty().uppercase()
            if (c.optString("homeAway") == "home") home = abbr else away = abbr
        }
        val info = parseEvent(e, league)
        out += ScheduledGame(
            league = league,
            shortName = e.optString("shortName").ifBlank { "$away @ $home" },
            away = away, home = home, detail = info.detail, status = info.status,
        )
    }
    // Live games first, then upcoming, then finals.
    return out.sortedBy {
        when (it.status) {
            GameStatus.LIVE, GameStatus.BREAK -> 0
            GameStatus.PREGAME -> 1
            GameStatus.UNKNOWN -> 2
            GameStatus.FINAL -> 3
        }
    }
}

fun scoreboardUrl(league: String) =
    "https://site.api.espn.com/apis/site/v2/sports/$league/scoreboard"

class GameFeed(
    slots: Map<Int, GameSlot>,
    private val fetch: (String) -> JSONObject,
    private val clock: () -> Double,
    private val pollSecs: Double = 15.0,
    private val breakPlaySecs: Double = 150.0,
) {
    private val slots = slots.filterValues { it.team.isNotBlank() }
    private var lastPoll = -1e9
    val status = mutableMapOf<Int, GameStatus>().apply { slots.keys.forEach { put(it, GameStatus.UNKNOWN) } }
    val info = mutableMapOf<Int, GameInfo>()
    private val plays = mutableMapOf<Int, Pair<String, Double>>() // box -> (text, first seen)
    var lastError: String? = null
        private set

    @Synchronized
    fun poll(force: Boolean = false): Map<Int, GameStatus> {
        val now = clock()
        if (!force && now - lastPoll < pollSecs) return status
        lastPoll = now
        val boards = mutableMapOf<String, JSONObject>()
        lastError = null
        for ((box, slot) in slots) {
            val event = try {
                val board = boards.getOrPut(slot.league) { fetch(slot.league) }
                findEvent(board, slot.team)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                continue // keep last known status
            }
            if (event == null) {
                status[box] = GameStatus.UNKNOWN
                continue
            }
            val gi = parseEvent(event, slot.league)
            val play = gi.lastPlay
            if (play != null) {
                val prev = plays[box]
                if (prev == null) {
                    plays[box] = play to (now - breakPlaySecs) // already on the board: old news
                } else if (prev.first != play) {
                    plays[box] = play to now
                }
                val (text, seen) = plays.getValue(box)
                if (gi.status == GameStatus.LIVE && BREAK_PLAY.containsMatchIn(text) &&
                    now - seen < breakPlaySecs
                ) gi.status = GameStatus.BREAK
            }
            info[box] = gi
            status[box] = gi.status
        }
        return status
    }

    @Synchronized
    fun latestPlay(box: Int): Pair<String, Double>? = plays[box]

    @Synchronized
    fun playChangedSince(box: Int, t: Double): Boolean {
        val p = plays[box] ?: return false
        return p.second > t && !BREAK_PLAY.containsMatchIn(p.first)
    }
}
