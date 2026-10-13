package com.rickle8.mvswitch.core

/**
 * When to switch audio and where to.
 *  1. Break coming (ESPN shows a TD, FG, 2-min warning...) or commercial heard
 *     -> switch to the most exciting game that isn't on break/final/pregame.
 *  2. Game audio -> upgrade to a clearly more exciting game (after a dwell time).
 */

const val DEFAULT_PROACTIVE =
    "touchdown|field goal|two.minute warning|official timeout|" +
        "end of (the )?(1st|first|3rd|third) quarter|end of (the )?quarter|" +
        "end of (the )?half|injur"
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
) {
    private val proactiveRe = Regex(proactivePlays, RegexOption.IGNORE_CASE)
    private val handledPlay = mutableMapOf<Int, String>()
    private var guardLogged: String? = null
    var lastSwitch = -1e9
    val breakUntil = mutableMapOf<Int, Double>()
    private val breakStarted = mutableMapOf<Int, Double>()
    private val lastBreakEnd = mutableMapOf<Int, Double>()
    private val awayForBreak = mutableSetOf<Int>()
    private val lastHeard = mutableMapOf<Int, Heard>()

    private fun hasData() = feed != null && feed.info.isNotEmpty()
    /** Cached live data; the engine polls the feed on its own loop. */
    private fun feedStatus(): Map<Int, GameStatus> = feed?.status?.toMap() ?: emptyMap()
    private fun info(box: Int): GameInfo? = feed?.info?.get(box)

    fun score(box: Int): Double = excitement(info(box), games[box])

    fun inBreak(box: Int, now: Double, status: Map<Int, GameStatus>): Boolean {
        if (status[box] == GameStatus.BREAK) return true
        if ((breakUntil[box] ?: 0.0) <= now) return false
        val started = breakStarted[box]
        if (feed != null && started != null && feed.playChangedSince(box, started)) {
            breakUntil.remove(box)
            return false
        }
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

    private fun breakComing(box: Int, now: Double): String? {
        if (!proactive || feed == null) return null
        val gi = info(box)
        if (gi != null && gi.statusName in PROACTIVE_STATUSES) {
            val key = "status:${gi.statusName}:${gi.period}"
            if (handledPlay[box] != key) {
                handledPlay[box] = key
                return gi.statusName.removePrefix("STATUS_").replace('_', ' ').lowercase()
            }
        }
        val p = feed.plays(box).lastOrNull() ?: return null
        if (handledPlay[box] == p.text) return null
        handledPlay[box] = p.text
        if (now - p.seen > proactiveMaxAge || now - p.t > 240) return null
        return if (proactiveRe.containsMatchIn(p.text)) p.text else null
    }

    /**
     * How likely a break is on this game right now, from play-by-play timing:
     * right after a score/timeout/turnover/quarter end it's likely; right after
     * a break ended with nothing since, it's unlikely.
     */
    fun bias(box: Int?, now: Double): Bias {
        if (box == null || feed == null) return Bias.NORMAL
        val end = lastBreakEnd[box] ?: -1e9
        val likely = feed.plays(box).lastOrNull {
            LIKELY_BREAK.containsMatchIn(it.text) && it.seen > end && now - it.seen < 180 && now - it.t < 300
        }
        if (likely != null) return Bias.LIKELY
        if (now - end < 240) return Bias.UNLIKELY
        return Bias.NORMAL
    }

    private fun goToBreak(cur: Int, now: Double, status: Map<Int, GameStatus>, why: String): Boolean {
        awayForBreak += cur
        if ((breakUntil[cur] ?: 0.0) <= now) breakStarted[cur] = now
        breakUntil[cur] = now + assumedBreakSecs
        val targets = ranked(now, status, exclude = cur)
        if (targets.isNotEmpty()) return switchTo(targets.first(), now, why)
        log("$why, but no other game is live. Staying.")
        return false
    }

    /** A normal (non-break) play showed up for this box recently: the game is on. */
    fun recentPlay(box: Int, now: Double, within: Double = liveGuardSecs): String? {
        val (text, seen) = feed?.latestPlay(box) ?: return null
        if (now - seen > within || BREAK_PLAY.containsMatchIn(text)) return null
        return text
    }

    /** You said it's a commercial: treat it as a break and move now. */
    fun commercialNow(): Boolean {
        val now = clock()
        val cur = nav.current ?: return false
        return goToBreak(cur, now, feedStatus(), "You marked a commercial on box $cur")
    }

    /** Returns true if it switched (caller should reset the audio window). */
    fun step(heard: Heard, strongAd: Boolean = false): Boolean {
        val now = clock()
        val cur = nav.current ?: return false
        if (heard == Heard.GAME) {
            if (lastHeard[cur] == Heard.AD || cur in awayForBreak) lastBreakEnd[cur] = now
            awayForBreak.remove(cur)
        }
        if (heard != Heard.UNSURE) lastHeard[cur] = heard
        if (now - lastSwitch < settleSecs) return false
        val status = feedStatus()

        breakComing(cur, now)?.let {
            return goToBreak(cur, now, status, "Break coming on box $cur (${it.take(50)})")
        }
        when (heard) {
            Heard.AD -> {
                if (strongAd) return goToBreak(cur, now, status, "Recognized a commercial on box $cur")
                val play = recentPlay(cur, now)
                if (play != null) {
                    if (guardLogged != play) {
                        guardLogged = play
                        log("Sounded like an ad, but box $cur just had a play. Staying.")
                    }
                    return false
                }
                return goToBreak(cur, now, status, "Commercial on box $cur")
            }
            Heard.UNSURE -> return false
            Heard.GAME -> Unit
        }
        breakUntil.remove(cur)
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
