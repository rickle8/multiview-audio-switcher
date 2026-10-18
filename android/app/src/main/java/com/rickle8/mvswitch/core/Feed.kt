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
    /** ESPN's id for the exact event picked (works for golf, racing, fights... not just teams). */
    val eventId: String = "",
    /** "game" (live scores from ESPN) or "other" (anything else: no scores, the mic decides). */
    val kind: String = GAME,
) {
    val isOther: Boolean get() = kind == OTHER
    /** Has something ESPN can follow. */
    val tracked: Boolean get() = !isOther && (team.isNotBlank() || eventId.isNotBlank())

    companion object {
        const val GAME = "game"
        const val OTHER = "other"
    }
}

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
    /** Football: down (1-4) and yards to go; baseball: outs. Null when not known. */
    val down: Int? = null,
    val distance: Int? = null,
    val outs: Int? = null,
)

data class ScheduledGame(
    val league: String,
    val id: String = "",
    val shortName: String,
    /** Full event name ("Detroit Lions at Chicago Bears", "The Masters"). */
    val name: String = "",
    val away: String,
    val home: String,
    val detail: String,
    val status: GameStatus,
    /** TV networks carrying it ("CBS", "ESPN/ESPN+"), to match what's in multiview. */
    val network: String = "",
    /** How worth watching it is right now (same scale as switching uses). */
    val excite: Double = 0.0,
    /** Start time (epoch ms), for sorting upcoming games. */
    val startMs: Long = 0,
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
        down = sit.optInt("down", 0).takeIf { it in 1..4 },
        distance = sit.optInt("distance", -1).takeIf { it >= 0 },
        outs = if (sit.has("outs")) sit.optInt("outs", -1).takeIf { it in 0..3 } else null,
    )
}

/** The event picked for this slot: the exact one if it's on today's board, otherwise its team's game today. */
fun findEvent(board: JSONObject, slot: GameSlot): JSONObject? {
    if (slot.eventId.isNotBlank()) {
        val events = board.optJSONArray("events")
        if (events != null) for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (e.optString("id") == slot.eventId) return e
        }
    }
    return if (slot.team.isNotBlank()) findEvent(board, slot.team) else null
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
        val comps = e.firstCompetition().optJSONArray("competitors") ?: org.json.JSONArray()
        var home = ""
        var away = ""
        for (j in 0 until comps.length()) {
            val c = comps.optJSONObject(j) ?: continue
            val abbr = c.optJSONObject("team")?.optString("abbreviation").orEmpty().uppercase()
            if (abbr.isBlank()) continue // individual sports: competitors are players, not teams
            if (c.optString("homeAway") == "home") home = abbr else away = abbr
        }
        val info = parseEvent(e, league)
        val comp = e.firstCompetition()
        val nets = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.let { b ->
            for (k in 0 until b.length()) b.optJSONObject(k)?.optJSONArray("names")?.let { n ->
                for (m in 0 until n.length()) n.optString(m).takeIf { it.isNotBlank() }?.let { nets += it }
            }
        }
        if (nets.isEmpty()) comp.optJSONArray("geoBroadcasts")?.let { b ->
            for (k in 0 until b.length()) b.optJSONObject(k)?.optJSONObject("media")?.optString("shortName")
                ?.takeIf { it.isNotBlank() }?.let { nets += it }
        }
        val start = runCatching { java.time.Instant.parse(e.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }
            .getOrDefault(0L)
        out += ScheduledGame(
            league = league,
            id = e.optString("id"),
            name = e.optString("name"),
            shortName = e.optString("shortName").ifBlank { e.optString("name") }
                .ifBlank { if (home.isNotBlank() || away.isNotBlank()) "$away @ $home" else "Event" },
            away = away, home = home, detail = info.detail, status = info.status,
            network = nets.take(3).joinToString("/"), excite = excitement(info, null), startMs = start,
        )
    }
    return sortForPicking(out)
}

/** Live games first (most exciting on top), then upcoming by start time, then finals. */
fun sortForPicking(games: List<ScheduledGame>): List<ScheduledGame> = games.sortedWith(
    compareBy<ScheduledGame>({
        when (it.status) {
            GameStatus.LIVE, GameStatus.BREAK -> 0
            GameStatus.PREGAME -> 1
            GameStatus.UNKNOWN -> 2
            GameStatus.FINAL -> 3
        }
    }, { if (it.status == GameStatus.LIVE || it.status == GameStatus.BREAK) -it.excite else it.startMs.toDouble() }),
)

/** Best live games to fill empty boxes: most exciting first, skipping ones already picked. */
fun suggestFill(all: List<ScheduledGame>, taken: Set<String>, count: Int): List<ScheduledGame> =
    all.filter { (it.status == GameStatus.LIVE || it.status == GameStatus.BREAK) && it.id !in taken }
        .distinctBy { it.league + it.id }.sortedByDescending { it.excite }.take(count)


/**
 * Today's scoreboard. Without extra settings ESPN only lists featured/top-25
 * college games, so ask for every game.
 */
fun scoreboardUrl(league: String): String {
    val extra = when (league) {
        "football/college-football" -> "?groups=80&limit=300"
        "basketball/mens-college-basketball", "basketball/womens-college-basketball" -> "?groups=50&limit=400"
        "hockey/mens-college-hockey", "baseball/college-baseball", "softball/college-softball" -> "?limit=300"
        else -> "?limit=300"
    }
    return "https://site.api.espn.com/apis/site/v2/sports/$league/scoreboard$extra"
}

fun summaryUrl(league: String, eventId: String) =
    "https://site.api.espn.com/apis/site/v2/sports/$league/summary?event=$eventId"

/** Plays that usually lead to a TV break soon (used to bias the mic). */
val LIKELY_BREAK = Regex(
    "touchdown|field goal|extra point|two-point|safety|timeout|two.minute|" +
        "end of (the )?(quarter|half|period|game|regulation)|end period|end of half|injur|" +
        "challenge|review|kicks \\d+ yards|kickoff|intercept|fumble|turnover on downs|punts? ",
    RegexOption.IGNORE_CASE,
)

/**
 * One play. [t] = when it actually happened (from ESPN's wall clock when
 * available), [seen] = when this app first got it. Both on the app's clock.
 */
data class Play(val text: String, val t: Double, val seen: Double, val seq: Long = -1)

/** Plays from an ESPN game summary, oldest first: (sequence, text, wall clock ms). */
fun parseSummaryPlays(summary: JSONObject): List<Triple<Long, String, Long?>> {
    val raw = mutableListOf<JSONObject>()
    val drives = summary.optJSONObject("drives")
    if (drives != null) {
        drives.optJSONArray("previous")?.let { prev ->
            for (i in 0 until prev.length()) {
                val ps = prev.optJSONObject(i)?.optJSONArray("plays") ?: continue
                for (j in 0 until ps.length()) ps.optJSONObject(j)?.let { raw += it }
            }
        }
        drives.optJSONObject("current")?.optJSONArray("plays")?.let { ps ->
            for (j in 0 until ps.length()) ps.optJSONObject(j)?.let { raw += it }
        }
    }
    if (raw.isEmpty()) summary.optJSONArray("plays")?.let { ps -> // basketball, hockey
        for (j in 0 until ps.length()) ps.optJSONObject(j)?.let { raw += it }
    }
    val out = raw.mapNotNull { p ->
        val seq = p.optString("sequenceNumber").toLongOrNull() ?: p.optString("id").toLongOrNull() ?: return@mapNotNull null
        val type = p.optJSONObject("type")?.optString("text").orEmpty()
        val text = p.optString("text")
        val combined = when {
            type.isBlank() -> text
            text.isBlank() -> type
            text.contains(type, ignoreCase = true) -> text
            else -> "$type: $text"
        }
        val wall = p.optString("wallclock").takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        Triple(seq, combined, wall)
    }
    return out.distinctBy { it.first }.sortedBy { it.first }
}

class GameFeed(
    slots: Map<Int, GameSlot>,
    private val fetch: (String) -> JSONObject,
    private val clock: () -> Double,
    private val pollSecs: Double = 15.0,
    private val breakPlaySecs: Double = 150.0,
    private val fetchSummary: ((String, String) -> JSONObject)? = null,
    private val wallNow: () -> Long = { System.currentTimeMillis() },
    private val summarySecs: Double = 5.0,
    /** Other games' play-by-play is checked less often. */
    private val otherSummarySecs: Double = 20.0,
) {
    private val slots = slots.filterValues { it.tracked }
    /** One poll at a time. Network calls happen under this lock only, never under the data lock (this). */
    private val pollLock = Any()
    private var lastPoll = -1e9
    val status: MutableMap<Int, GameStatus> =
        java.util.concurrent.ConcurrentHashMap<Int, GameStatus>().apply { slots.keys.forEach { put(it, GameStatus.UNKNOWN) } }
    val info: MutableMap<Int, GameInfo> = java.util.concurrent.ConcurrentHashMap()
    private val rawStatus = HashMap<Int, GameStatus>()
    private val eventIds = HashMap<Int, String>()
    private val log = HashMap<Int, ArrayDeque<Play>>()
    private val lastSeq = HashMap<Int, Long>()
    private val lastSummary = HashMap<Int, Double>()
    private val summaryOk = HashMap<Int, Double>()
    /** Last game clock seen, per box and source ("board"/"pbp"): period to seconds left. */
    private val lastClock = HashMap<String, Pair<Int, Double>>()
    private val clockMoved = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    /** How long after a play happened this app first saw it (ESPN's own delay), seconds. */
    private val lagSamples = ArrayDeque<Double>()
    private val names = java.util.concurrent.ConcurrentHashMap<Int, Set<String>>()
    private val teams = java.util.concurrent.ConcurrentHashMap<Int, Set<String>>()

    private val namesAt = HashMap<Int, Double>()

    /** The game whose play-by-play is polled every few seconds (the one you're hearing). */
    @Volatile var focusBox: Int? = null
    @Volatile var lastError: String? = null
        private set
    /** True once the scoreboard has been read successfully at least once. */
    @Volatile var loaded = false
        private set

    /** Record a play. Public for tests. */
    @Synchronized
    fun recordPlay(box: Int, text: String, t: Double, seen: Double = t, seq: Long = -1) {
        val q = log.getOrPut(box) { ArrayDeque() }
        q.addLast(Play(text, t, seen, seq))
        while (q.size > 40) q.removeFirst()
    }

    @Synchronized
    private fun applyPlayStatus(box: Int, now: Double) {
        val raw = rawStatus[box] ?: return
        val latest = log[box]?.lastOrNull()
        // Plays with a real wall-clock time are aged from when they happened; others from when seen.
        val age = latest?.let { if (it.seq >= 0) now - it.t else now - it.seen } ?: Double.MAX_VALUE
        val s = if (raw == GameStatus.LIVE && latest != null && isBreakPlay(latest.text) &&
            age < breakPlaySecs
        ) GameStatus.BREAK else raw
        status[box] = s
        info[box]?.status = s
    }

    /**
     * Fetches whatever is due. Readers (the switching loop, the screen) are never
     * blocked by a slow network: fetching happens outside the data lock.
     */
    fun poll(force: Boolean = false): Map<Int, GameStatus> = synchronized(pollLock) {
        val now = clock()
        if (force || now - lastPoll >= pollSecs) {
            lastPoll = now
            pollScoreboard(now)
        }
        val focus = focusBox
        focus?.let { pollSummary(it, now, summarySecs) }
        for (box in slots.keys) if (box != focus) pollSummary(box, now, otherSummarySecs)
        status
    }

    private fun pollScoreboard(now: Double) {
        val boards = mutableMapOf<String, JSONObject>()
        var error: String? = null
        for ((box, slot) in slots) {
            val event = try {
                val board = boards.getOrPut(slot.league) { fetch(slot.league) }
                findEvent(board, slot)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                continue // keep last known status
            }
            if (event == null) {
                loaded = true
                status[box] = GameStatus.UNKNOWN
                continue
            }
            loaded = true
            event.optString("id").takeIf { it.isNotBlank() }?.let { eventIds[box] = it }
            val gi = parseEvent(event, slot.league)
            rawStatus[box] = gi.status
            info[box] = gi
            if (gi.status == GameStatus.LIVE || gi.status == GameStatus.BREAK) noteClock(box, "board", gi.period, gi.clock, now)
            teamNames(event).takeIf { it.isNotEmpty() }?.let { t ->
                val cur = names[box].orEmpty()
                if (!cur.containsAll(t)) { teams[box] = t; names[box] = t + cur }
            }
            // The scoreboard only has the latest play. Use it when play-by-play isn't flowing.
            val play = gi.lastPlay
            val pbpFresh = (summaryOk[box] ?: -1e9) > now - 30
            if (play != null && !pbpFresh) synchronized(this) {
                val prev = log[box]?.lastOrNull()
                if (prev == null) recordPlay(box, play, now - breakPlaySecs) // already on the board: old news
                else if (prev.text != play && !prev.text.endsWith(play)) recordPlay(box, play, now)
            }
            applyPlayStatus(box, now)
        }
        lastError = error
    }

    private fun pollSummary(box: Int, now: Double, every: Double) {
        val fs = fetchSummary ?: return
        val id = eventIds[box] ?: return
        val raw = rawStatus[box]
        if (raw != GameStatus.LIVE && raw != GameStatus.BREAK) return
        if (now - (lastSummary[box] ?: -1e9) < every) return
        lastSummary[box] = now
        val json = try {
            fs(slots.getValue(box).league, id)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            return
        }
        val plays = parseSummaryPlays(json)
        json.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONObject("status")?.let { st ->
            // The summary gives "5:21" (displayClock), the scoreboard a number of seconds.
            val secs = if (st.has("clock")) st.optDouble("clock") else clockSeconds(st.optString("displayClock"))
            if (secs != null) noteClock(box, "pbp", st.optInt("period", 0), secs, now)
        }
        if (now - (namesAt[box] ?: -1e9) > 600) {
            namesAt[box] = now
            val found = HashSet<String>()
            athleteNames(json, found, 0)
            if (found.isNotEmpty()) names[box] = (teams[box].orEmpty() + names[box].orEmpty() + found).take(150).toSet()
        }
        if (plays.isEmpty()) return
        summaryOk[box] = now
        val wall = wallNow()
        synchronized(this) {
            val first = !lastSeq.containsKey(box)
            if (first) log[box]?.clear()
            val after = lastSeq[box] ?: Long.MIN_VALUE
            val fresh = plays.filter { it.first > after }
            if (!first) for ((_, _, wallMs) in fresh) if (wallMs != null) {
                val lag = (wall - wallMs) / 1000.0
                if (lag in 0.0..180.0) { lagSamples.addLast(lag); while (lagSamples.size > 30) lagSamples.removeFirst() }
            }
            for ((seq, text, wallMs) in (if (first) fresh.takeLast(40) else fresh)) {
                val t = if (wallMs != null) now - maxOf(0.0, (wall - wallMs) / 1000.0)
                else if (first) now - breakPlaySecs else now
                // seq >= 0 marks plays with a real wall-clock time (safe for learning).
                recordPlay(box, text, t, if (first) minOf(t, now - breakPlaySecs) else now, if (wallMs != null) seq else -1)
            }
            lastSeq[box] = plays.last().first
        }
        applyPlayStatus(box, now)
    }

    /** A countdown clock sport where breaks only happen with the clock stopped. */
    private fun clockSport(box: Int): Boolean = slots[box]?.league?.let {
        it.startsWith("football") || it.startsWith("basketball") || it.startsWith("hockey")
    } == true

    private fun noteClock(box: Int, source: String, period: Int, clock: Double?, now: Double) {
        if (clock == null || clock.isNaN() || !clockSport(box)) return
        val key = "$box:$source"
        val prev = lastClock[key]
        lastClock[key] = period to clock
        if (prev != null && prev.first == period && clock < prev.second - 0.5) clockMoved[box] = now
    }

    /** When this app last saw the game clock running for [box] (app clock), or null. */
    fun clockMovedAt(box: Int): Double? = clockMoved[box]

    /** ESPN's own delay: how long after a play happens it shows up here. Null until known. */
    @Synchronized
    fun espnLag(): Double? = if (lagSamples.size < 3) null else lagSamples.sorted()[lagSamples.size / 2]

    /** Play-by-play for this box arrived recently, so its plays can be trusted to tell when it's back. */
    fun pbpFresh(box: Int, now: Double): Boolean = (summaryOk[box] ?: -1e9) > now - 60

    fun league(box: Int): String = slots[box]?.league.orEmpty()

    /** Each game's team words: names, places, abbreviations (for reading score bugs off the screen). */
    fun teamWords(): Map<Int, Set<String>> = slots.keys.associateWith { box ->
        teams[box].orEmpty() + info[box]?.scores?.keys.orEmpty().map { it.lowercase() }.filter { it.length >= 2 }
    }

    /** Player and team names for every game, lowercase (for the announcers' words). */
    fun names(): Set<String> = names.values.flatten().toSet()


    @Synchronized
    fun latestPlay(box: Int): Pair<String, Double>? = log[box]?.lastOrNull()?.let { it.text to it.seen }

    @Synchronized
    fun plays(box: Int): List<Play> = log[box]?.toList().orEmpty()

    @Synchronized
    fun playChangedSince(box: Int, t: Double): Boolean {
        val p = log[box]?.lastOrNull() ?: return false
        return p.seen > t && !isBreakPlay(p.text)
    }
}

private val NAME_SUFFIX = setOf("jr", "sr", "ii", "iii", "iv", "v")

private fun cleanName(full: String): String? {
    val words = full.lowercase().replace('-', ' ').replace(Regex("[^a-z' ]"), " ").split(' ')
        .filter { it.isNotBlank() && it !in NAME_SUFFIX }
    val last = words.lastOrNull() ?: return null
    return last.takeIf { it.length >= 3 }
}

/** Last names of every athlete mentioned in an ESPN game summary. */
fun athleteNames(j: Any?, out: MutableSet<String>, depth: Int) {
    if (depth > 14 || out.size >= 150) return
    when (j) {
        is JSONObject -> {
            j.optJSONObject("athlete")?.let { a ->
                (a.optString("lastName").ifBlank { null }?.let { cleanName(it) }
                    ?: cleanName(a.optString("displayName")))?.let { out += it }
            }
            for (k in j.keys()) athleteNames(j.opt(k), out, depth + 1)
        }
        is org.json.JSONArray -> for (i in 0 until j.length()) athleteNames(j.opt(i), out, depth + 1)
    }
}

/** Team nicknames from a scoreboard event ("bears", "packers"). */
fun teamNames(event: JSONObject): Set<String> {
    val out = HashSet<String>()
    val comps = event.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: return out
    for (i in 0 until comps.length()) {
        val t = comps.optJSONObject(i)?.optJSONObject("team") ?: continue
        // Nickname and place: announcers say "the Dodgers", "Milwaukee", "Kennesaw State".
        for (k in listOf("name", "location", "shortDisplayName")) {
            t.optString(k).lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
                .takeIf { it.length >= 3 }?.let { out += it }
        }
    }
    return out
}

/** "5:21" -> 321.0, "0:45.3" -> 45.3; null for anything else ("2'", ""). */
fun clockSeconds(display: String): Double? {
    val m = Regex("^(\\d{1,2}):(\\d{2}(?:\\.\\d+)?)$").find(display.trim()) ?: return null
    return m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble()
}

/** A play wiped out by a penalty ("TOUCHDOWN NULLIFIED by Penalty", "No Play"): it didn't count. */
val NULLIFIED = Regex("nullified|no play", RegexOption.IGNORE_CASE)

/** A play that means a TV break is coming or on, unless a penalty wiped it out. */
fun isBreakPlay(text: String): Boolean = BREAK_PLAY.containsMatchIn(text) && !NULLIFIED.containsMatchIn(text)
