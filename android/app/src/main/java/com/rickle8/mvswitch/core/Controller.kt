package com.rickle8.mvswitch.core

/**
 * When to switch audio and where to.
 *  1. Break coming (ESPN shows a TD, FG, 2-min warning...) or commercial heard
 *     -> switch to the most exciting game that isn't on break/final/pregame.
 *  2. Game audio -> upgrade to a clearly more exciting game (after a dwell time).
 */

// Real ESPN wording: "Official Timeout at 05:32.", "End Period: END QUARTER 1", "End of Half: END QUARTER 2",
// "Field Goal Good: ... is GOOD" (a missed field goal usually isn't followed by a break).
const val DEFAULT_PROACTIVE =
    "touchdown|field goal (is )?good|two.minute warning|official timeout|tv timeout|media timeout|" +
        "end of (the )?(1st|first|3rd|third) quarter|end of (the )?quarter|end (of )?period|end quarter|" +
        "end of (the )?half|halftime|injur"
private val HALF = Regex("end of (the )?(1st |first )?half|halftime|end quarter 2\\b|end of (the )?(2nd|second) quarter", RegexOption.IGNORE_CASE)
val PROACTIVE_STATUSES = setOf("STATUS_HALFTIME", "STATUS_END_PERIOD")

class Controller(
    private val nav: Navigator,
    private val boxes: List<Int>,
    private val feed: GameFeed?,
    var games: Map<Int, GameSlot> = emptyMap(),
    private val settleSecs: Double = 6.0,
    var assumedBreakSecs: Double = 150.0,
    var upgradeMargin: Double = 0.35,
    var minDwellSecs: Double = 45.0,
    var proactive: Boolean = true,
    proactivePlays: String = DEFAULT_PROACTIVE,
    private val proactiveMaxAge: Double = 45.0,
    /** Ignore the mic's "commercial" if ESPN showed a normal play this recently. */
    var liveGuardSecs: Double = 75.0,
    private val clock: () -> Double,
    private val log: (String) -> Unit = {},
    /** What's been learned about the broadcast: stream delay and real break lengths. */
    private val timing: BroadcastTiming? = null,
    /** How long after the game clock was last seen running that a break is ruled out. */
    var clockGuardSecs: Double = 25.0,
) {
    private val proactiveRe = Regex(proactivePlays, RegexOption.IGNORE_CASE)
    private val handledPlay = mutableMapOf<Int, String>()
    private var guardLogged: String? = null
    var lastSwitch = -1e9
    // Read from the screen's thread too, hence concurrent maps.
    val breakUntil: MutableMap<Int, Double> = java.util.concurrent.ConcurrentHashMap()
    private val breakStarted: MutableMap<Int, Double> = java.util.concurrent.ConcurrentHashMap()
    private val lastBreakEnd: MutableMap<Int, Double> = java.util.concurrent.ConcurrentHashMap()
    private val awayForBreak = mutableSetOf<Int>()
    private val lastHeard = mutableMapOf<Int, Heard>()
    private val breakAfterScore: MutableMap<Int, Boolean> = java.util.concurrent.ConcurrentHashMap()
    /** Halftime: much longer than a commercial break. */
    private val breakIsHalf: MutableMap<Int, Boolean> = java.util.concurrent.ConcurrentHashMap()
    /**
     * ESPN says a break is coming: wait for the commercial itself before leaving
     * (YouTube TV runs behind ESPN). Without a mic, leave once the TV should have caught up.
     */
    private class Arm(val text: String, val at: Double, val due: Double, val until: Double,
                      /** Halftime: the studio show isn't the game either, so go once the TV gets there. */
                      val anyway: Boolean = false)
    private val armed: MutableMap<Int, Arm> = java.util.concurrent.ConcurrentHashMap()
    /** False when running on live data alone (no mic to hear the commercial start). */
    @Volatile var micOn = true
    /** Seconds the TV is assumed to run behind live until the real delay is learned. */
    var assumedDelaySecs = 40.0
    private var clockLogged = -1e9

    /** Seconds the TV runs behind the real game, once learned. */
    private fun delay(): Double? = timing?.delay?.seconds

    /** When the TV shows (or showed) this play. */
    fun tvTime(p: Play): Double {
        val d = delay()
        if (p.seq < 0) return p.seen
        // Not learned yet: assume the TV is a little behind ESPN, never ahead of it.
        return if (d != null) p.t + d else maxOf(p.seen + 10, p.t + assumedDelaySecs)
    }

    /** For the screen: the break ESPN says is coming that the app is waiting out. */
    fun waitingFor(box: Int): String? = armed[box]?.text

    /** How much later the TV shows something than this app hears about it from ESPN. */
    private fun dataLead(): Double {
        val d = delay() ?: return 0.0
        val lag = feed?.espnLag() ?: return 0.0
        return maxOf(0.0, d - lag)
    }

    /** The game clock is running on what the TV shows now (or is about to): no break. */
    fun clockRunning(box: Int, now: Double): Boolean {
        val moved = feed?.clockMovedAt(box) ?: return false
        return now - (moved + dataLead()) < clockGuardSecs
    }

    private fun recentScore(box: Int, now: Double): Boolean =
        feed?.plays(box)?.takeLast(4)?.any { now - it.seen < 300 && (SCORE_PLAY.containsMatchIn(it.text) && !NULLIFIED.containsMatchIn(it.text)) } == true

    /** Typical break for this game, learned from your games when possible. */
    private fun breakLength(box: Int, afterScore: Boolean): Double {
        val league = feed?.league(box).orEmpty()
        return timing?.breaks?.typical(league, afterScore)
            ?: if (afterScore && league.startsWith("football")) assumedBreakSecs * 1.8 else assumedBreakSecs
    }

    /** Longest to wait for a play saying the game is back. */
    private fun breakCap(box: Int): Double =
        if (breakIsHalf[box] == true) 1200.0
        else (breakLength(box, breakAfterScore[box] == true) * 1.6).coerceIn(150.0, 480.0)

    /** The first play since the break started that the TV has shown and that means the game is back. */
    private fun resumedPlay(box: Int, started: Double, now: Double): Play? =
        feed?.plays(box)?.lastOrNull {
            it.seen > started && !isBreakPlay(it.text) && !NOT_RESUMED.containsMatchIn(it.text)
        }?.takeIf { tvTime(it) - 3 <= now }

    private fun endBreak(box: Int, started: Double, back: Play) {
        val tv = tvTime(back)
        if (breakIsHalf[box] != true) timing?.breaks?.add(feed?.league(box).orEmpty(), breakAfterScore[box] == true, tv - started)
        lastBreakEnd[box] = tv
        breakUntil.remove(box)
        breakStarted.remove(box)
    }

    private fun hasData() = feed != null && feed.info.isNotEmpty()
    /** Cached live data; the engine polls the feed on its own loop. */
    private fun feedStatus(): Map<Int, GameStatus> = feed?.status?.toMap() ?: emptyMap()
    private fun info(box: Int): GameInfo? = feed?.info?.get(box)

    fun score(box: Int): Double = excitement(info(box), games[box])

    /** For the screen: same answer as [inBreak] without changing anything. */
    fun looksOnBreak(box: Int, now: Double, status: Map<Int, GameStatus>): Boolean {
        if (status[box] == GameStatus.BREAK) return true
        val until = breakUntil[box] ?: return false
        val started = breakStarted[box]
        if (feed != null && started != null) {
            if (resumedPlay(box, started, now) != null) return false
            if (feed.pbpFresh(box, now)) {
                return now < started + breakCap(box)
            }
        }
        return until > now
    }

    fun inBreak(box: Int, now: Double, status: Map<Int, GameStatus>): Boolean {
        if (status[box] == GameStatus.BREAK) return true
        val until = breakUntil[box] ?: return false
        val started = breakStarted[box]
        if (feed != null && started != null) {
            resumedPlay(box, started, now)?.let { endBreak(box, started, it); return false }
            if (feed.pbpFresh(box, now)) {
                // Plays are flowing: wait for one that shows the game is back (this sits out
                // the second break after a kickoff), up to a generous limit.
                if (now < started + breakCap(box)) return true
                breakUntil.remove(box)
                return false
            }
        }
        if (until <= now) { breakUntil.remove(box); return false }
        return true
    }

    private fun ranked(now: Double, status: Map<Int, GameStatus>, exclude: Int?): List<Int> {
        val out = boxes.filter { box ->
            box != exclude &&
                status[box] != GameStatus.FINAL && status[box] != GameStatus.PREGAME &&
                !inBreak(box, now, status)
        }
        return if (hasData()) out.sortedByDescending { score(it) } else out
    }

    private fun switchTo(box: Int, now: Double, why: String): Boolean {
        log("$why → box $box")
        nav.goTo(box)
        lastSwitch = now
        return true
    }

    /** Notes breaks ESPN says are coming; returns one that's due to act on. */
    private fun breakComing(box: Int, now: Double): Arm? {
        if (!proactive || feed == null) return null
        val gi = info(box)
        if (gi != null && gi.statusName in PROACTIVE_STATUSES) {
            val key = "status:${gi.statusName}:${gi.period}"
            if (handledPlay[box] != key) {
                handledPlay[box] = key
                val text = gi.statusName.removePrefix("STATUS_").replace('_', ' ').lowercase()
                arm(box, text, now, now + (delay() ?: assumedDelaySecs), 300.0,
                    anyway = gi.statusName == "STATUS_HALFTIME")
            }
        }
        val p = feed.plays(box).lastOrNull()
        if (p != null && handledPlay[box] != p.text) {
            handledPlay[box] = p.text
            if (now - p.seen <= proactiveMaxAge && now - p.t <= 240 && proactiveRe.containsMatchIn(p.text) && !NULLIFIED.containsMatchIn(p.text)) {
                // When the TV shows it (the stream runs behind), plus a moment for the celebration.
                val lead = when {
                    p.text.contains("touchdown", ignoreCase = true) -> 15.0
                    p.text.contains("field goal", ignoreCase = true) -> 8.0
                    else -> 2.0
                }
                // A touchdown's break waits for the extra point (and often the kickoff).
                val window = if (SCORE_PLAY.containsMatchIn(p.text)) 240.0 else 150.0
                arm(box, p.text, now, maxOf(p.seen, tvTime(p)) + lead, window)
            } else if (!isBreakPlay(p.text) && !NOT_RESUMED.containsMatchIn(p.text) && armed[box]?.let { p.seen > it.at } == true) {
                log("Box $box played on without a break.")
                armed.remove(box)
            }
        }
        val a = armed[box] ?: return null
        if (now > a.until) {
            log("No commercial came on box $box after \u201c${a.text.take(40)}\u201d. Staying.")
            armed.remove(box)
            return null
        }
        return a.takeIf { now >= a.due }
    }

    private fun arm(box: Int, text: String, now: Double, due: Double, window: Double, anyway: Boolean = false) {
        if (armed[box] == null) log("ESPN: break coming on box $box (${text.take(50)}). Waiting for the commercial.")
        armed[box] = Arm(text, now, due, due + window, anyway)
    }

    /**
     * How likely a break is on this game right now, from play-by-play timing:
     * right after a score/timeout/turnover/quarter end it's likely; right after
     * a break ended with nothing since, it's unlikely.
     */
    fun bias(box: Int?, now: Double): Bias {
        if (box == null || feed == null) return Bias.NORMAL
        val end = lastBreakEnd[box] ?: -1e9
        if (armed[box] != null) return Bias.LIKELY
        if (clockRunning(box, now)) return Bias.CLOCK
        val likely = feed.plays(box).lastOrNull {
            LIKELY_BREAK.containsMatchIn(it.text) && !NULLIFIED.containsMatchIn(it.text) && it.seen > end && now - it.seen < 180 && now - it.t < 300
        }
        if (likely != null) return Bias.LIKELY
        if (now - end < 240) return Bias.UNLIKELY
        if (now - lastSwitch < 20) return Bias.LANDED
        return Bias.NORMAL
    }

    private fun goToBreak(cur: Int, now: Double, status: Map<Int, GameStatus>, why: String): Boolean {
        armed.remove(cur)
        awayForBreak += cur
        if ((breakUntil[cur] ?: 0.0) <= now) {
            breakStarted[cur] = now
            breakAfterScore[cur] = recentScore(cur, now)
            breakIsHalf[cur] = HALF.containsMatchIn(why) || info(cur)?.statusName == "STATUS_HALFTIME"
        }
        breakUntil[cur] = now + if (breakIsHalf[cur] == true) 900.0 else breakLength(cur, breakAfterScore[cur] == true)
        val targets = ranked(now, status, exclude = cur)
        if (targets.isNotEmpty()) return switchTo(targets.first(), now, why)
        log("$why, but no other game is live. Staying.")
        return false
    }

    /** A normal (non-break) play showed up for this box recently: the game is on. */
    fun recentPlay(box: Int, now: Double, within: Double = liveGuardSecs): String? {
        val p = feed?.plays(box)?.lastOrNull() ?: return null
        if (isBreakPlay(p.text)) return null
        // A play the TV shows soon, or showed a moment ago, means the game is on.
        if (now - tvTime(p) > within) return null
        return p.text
    }

    /** You said it's a commercial: treat it as a break and move now. */
    fun commercialNow(): Boolean {
        val now = clock()
        val cur = nav.current ?: return false
        return goToBreak(cur, now, feedStatus(), "You marked a commercial on box $cur")
    }

    /** Returns true if it switched (caller should reset the audio window). */
    fun step(heard: Heard, strongAd: Boolean = false, strongWhy: String? = null): Boolean {
        val now = clock()
        val cur = nav.current ?: return false
        if (heard == Heard.GAME) {
            if (lastHeard[cur] == Heard.AD || cur in awayForBreak) lastBreakEnd[cur] = now
            awayForBreak.remove(cur)
        }
        if (heard != Heard.UNSURE) lastHeard[cur] = heard
        if (now - lastSwitch < settleSecs) return false
        val status = feedStatus()

        val arm = breakComing(cur, now)
        if (arm != null && (!micOn || arm.anyway)) {
            return goToBreak(cur, now, status, "Break coming on box $cur (${arm.text.take(50)})")
        }
        when (heard) {
            Heard.AD -> {
                // ESPN said a break is coming but the TV hasn't got there yet (replays, celebration): wait.
                val waiting = armed[cur]
                if (!strongAd && waiting != null && now < waiting.due) return false
                // ESPN said a break was coming and now it sounds like one: go.
                if (arm != null) return goToBreak(cur, now, status,
                    "Commercial started on box $cur after \u201c${arm.text.take(40)}\u201d")
                if (strongAd) return goToBreak(cur, now, status,
                    (strongWhy ?: "Recognized a commercial") + " on box $cur")
                val play = recentPlay(cur, now)
                if (play != null) {
                    if (guardLogged != play) {
                        guardLogged = play
                        log("Sounded like an ad, but box $cur just had a play. Staying.")
                    }
                    return false
                }
                if (clockRunning(cur, now)) {
                    if (now - clockLogged > 60) {
                        clockLogged = now
                        log("Sounded like an ad, but box $cur's game clock is running. Staying.")
                    }
                    return false
                }
                return goToBreak(cur, now, status, "Commercial on box $cur")
            }
            Heard.UNSURE -> return false
            Heard.GAME -> Unit
        }
        breakUntil.remove(cur)
        // On a filler box: leave as soon as any real game is back on.
        if (games[cur]?.let { it.isOther && it.importance < 1.0 } == true) {
            val game = ranked(now, status, exclude = cur).firstOrNull { games[it]?.isOther != true }
            if (game != null) return switchTo(game, now, "A game is back on, leaving the filler")
        }
        if (now - lastSwitch < minDwellSecs) return false
        if (hasData()) {
            val best = ranked(now, status, exclude = cur).firstOrNull() ?: return false
            if (score(best) - score(cur) >= upgradeMargin) {
                return switchTo(best, now, "Better game than box $cur")
            }
            return false
        }
        // No live data: go back to box 1 once its break should be over.
        val primary = boxes.first()
        if (cur != primary && !inBreak(primary, now, status)) {
            return switchTo(primary, now, "Main game should be back")
        }
        return false
    }
}
