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

private val KICK = Regex("kick|extra point|touchback|two.point", RegexOption.IGNORE_CASE)
private val STOPPAGE = Regex("end of|end period|halftime|official timeout|tv timeout|media timeout", RegexOption.IGNORE_CASE)

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
    var liveGuardSecs: Double = 35.0,
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
        if (box != nav.current && feed != null && outlook(box, now, status).first == Outlook.BREAK) return true
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

    enum class Outlook { LIVE, BREAK, SOON, UNKNOWN }

    /** What the camera sees: per box, showing the game (true) or a commercial (false), and when. */
    @Volatile var camera: Pair<Double, Map<Int, Boolean?>>? = null

    /** The camera's word on a box, if it's watching and sure. */
    fun cameraSays(box: Int, now: Double): Boolean? = camera?.takeIf { now - it.first < 4 }?.second?.get(box)

    /**
     * What a game you're not hearing is showing on the TV right now, from ESPN alone:
     * the game clock running or a fresh ordinary play means it's on; a score, timeout,
     * end of quarter (and nothing since) means it's probably in commercial; a break
     * play ESPN has but the TV hasn't reached yet means a commercial is coming.
     */
    fun outlook(box: Int, now: Double, status: Map<Int, GameStatus> = feedStatus()): Pair<Outlook, String> {
        if (status[box] == GameStatus.BREAK) return Outlook.BREAK to (info(box)?.detail?.ifBlank { null } ?: "break")
        // The camera sees the box itself: nothing beats that.
        when (cameraSays(box, now)) {
            true -> return Outlook.LIVE to "camera sees the game"
            false -> return Outlook.BREAK to "camera sees a commercial"
            null -> Unit
        }
        val f = feed ?: return Outlook.UNKNOWN to ""
        val plays = f.plays(box)
        val p = plays.lastOrNull()
        val ago = p?.let { now - tvTime(it) }
        val breaky = p != null && !NULLIFIED.containsMatchIn(p.text) && run {
            val afterScore = plays.takeLast(4).dropLast(1).any { SCORE_PLAY.containsMatchIn(it.text) && tvTime(p) - tvTime(it) < 240 }
            // Kickoffs and extra points only lead to commercial right after a score.
            val kick = KICK.containsMatchIn(p.text)
            proactiveRe.containsMatchIn(p.text) || (isBreakPlay(p.text) && !kick) || (kick && afterScore) ||
                STOPPAGE.containsMatchIn(p.text)
        }
        // A break play ESPN has that the TV hasn't reached yet: a commercial is on its way.
        if (breaky && ago != null && ago < 0 && ago > -90) return Outlook.SOON to p!!.text
        // The game clock running on the TV is the surest sign the game is on (TV breaks only come with it stopped).
        val moved = f.clockMovedAt(box)
        if (moved != null && now - (moved + dataLead()) < 40) return Outlook.LIVE to "clock running"
        if (p != null && ago != null) {
            if (breaky) {
                val afterScore = plays.takeLast(4).any { SCORE_PLAY.containsMatchIn(it.text) && now - tvTime(it) < 400 }
                if (ago in 0.0..(breakLength(box, afterScore) + 40)) return Outlook.BREAK to p.text
            } else if (ago in -90.0..30.0) {
                return Outlook.LIVE to p.text
            }
        }
        // Nothing happening for a while. College play-by-play can arrive in bursts minutes late, so only
        // call it a break when the clock agrees (stopped a good while) or, with no clock, after a long quiet.
        val clockStoppedLong = moved != null && now - (moved + dataLead()) > 75
        val quiet = p != null && ago != null && ago in 85.0..1500.0 && now - p.seen > 60
        if (quiet && (clockStoppedLong || (moved == null && f.pbpFresh(box, now)))) return Outlook.BREAK to "no plays lately"
        return Outlook.UNKNOWN to ""
    }

    /** Where to go: games that are on first, then ones ESPN can't vouch for; never one in (or about to go to) commercial. */
    private fun ranked(now: Double, status: Map<Int, GameStatus>, exclude: Int?): List<Int> {
        val open = boxes.filter { box ->
            box != exclude &&
                status[box] != GameStatus.FINAL && status[box] != GameStatus.PREGAME &&
                (cameraSays(box, now) == true || !inBreak(box, now, status))
        }
        if (feed == null && camera == null) return open
        val look = open.associateWith { outlook(it, now, status).first }
        val usable = open.filter { look[it] == Outlook.LIVE || look[it] == Outlook.UNKNOWN }
        return usable.sortedWith(compareByDescending<Int> { look[it] == Outlook.LIVE }.thenByDescending { score(it) })
    }

    /** Every other game is in commercial too: the one whose break started first should be back first. */
    private fun backSoonest(now: Double, status: Map<Int, GameStatus>, exclude: Int): Int? =
        boxes.filter { it != exclude && status[it] != GameStatus.FINAL && status[it] != GameStatus.PREGAME }
            .mapNotNull { b ->
                val started = breakStarted[b]?.takeIf { inBreak(b, now, status) }
                    ?: feed?.plays(b)?.lastOrNull()?.let { tvTime(it) }?.takeIf { outlook(b, now, status).first == Outlook.BREAK }
                started?.let { b to it }
            }
            .filter { now - it.second > 60 } // only if its break has had a real head start
            .minByOrNull { it.second }?.first

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

    /** Go back to the game you were watching once its commercial ends. */
    var returnAfterBreak = true
    /** The game to go back to after its commercial. */
    @Volatile var returnTo: Int? = null

    /** The game is back on the TV: a play since its break that the TV has shown, or ESPN says it's live. */
    private fun isBack(box: Int, now: Double, status: Map<Int, GameStatus>): Boolean {
        cameraSays(box, now)?.let { return it }
        if (status[box] == GameStatus.BREAK) return false
        val started = breakStarted[box]
        if (feed != null && started != null && feed.pbpFresh(box, now)) {
            if (resumedPlay(box, started, now) != null) return true
            return now > started + breakCap(box) // never heard back: give up waiting eventually
        }
        return !inBreak(box, now, status)
    }

    /** When it left a game for a commercial, recently. */
    private val adSwitches = ArrayDeque<Double>()
    /** Every game seems to be in commercials: stop bouncing between them until this time. */
    private var settleUntil = -1e9

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
        // Remember the game you were watching (not a stop along the way) to come back to.
        if (returnAfterBreak && returnTo == null) returnTo = cur
        if (targets.isNotEmpty()) { adSwitches.addLast(now); return switchTo(targets.first(), now, why) }
        backSoonest(now, status, cur)?.let { b ->
            adSwitches.addLast(now)
            return switchTo(b, now, "$why; every game is in commercial, box $b should be back first")
        }
        log("$why, but every other game is in commercial too. Staying.")
        return false
    }

    /** A normal (non-break) play showed up for this box recently: the game is on. */
    fun recentPlay(box: Int, now: Double, within: Double = liveGuardSecs): String? {
        val p = feed?.plays(box)?.lastOrNull() ?: return null
        // Punts, kickoffs, turnovers, scores often go straight to commercial: they don't prove the game is on.
        if (isBreakPlay(p.text) || LIKELY_BREAK.containsMatchIn(p.text)) return null
        // A play the TV shows soon, or showed a moment ago, means the game is on.
        if (now - tvTime(p) > within) return null
        return p.text
    }

    /** You said it's a commercial: treat it as a break and move now. */
    fun commercialNow(): Boolean {
        val now = clock()
        val cur = nav.current ?: return false
        syncAdStart(cur, now - 2) // you tapped a moment after it started
        return goToBreak(cur, now, feedStatus(), "You marked a commercial on box $cur")
    }

    /** Returns true if it switched (caller should reset the audio window). */
    // ------------------------------------------------------------ syncing the TV with ESPN
    private val SYNC_PLAY = Regex("official timeout|tv timeout|media timeout|end period|end of (the )?(1st|first|3rd|third) quarter|two.minute warning", RegexOption.IGNORE_CASE)
    /** When the TV came back from a commercial on a box, waiting for ESPN's first play after it. */
    private val returnedAt = HashMap<Int, Double>()
    private val syncedPlay = HashMap<Int, Long>()

    /**
     * The TV went to commercial on [box] at [at]: if ESPN's last play there was a TV
     * timeout or quarter end (which cut to commercial within seconds), the gap is how
     * far behind ESPN's real time the TV runs.
     */
    private fun syncAdStart(box: Int, at: Double) {
        val p = feed?.plays(box)?.lastOrNull { it.seq >= 0 } ?: return
        if (!SYNC_PLAY.containsMatchIn(p.text) || syncedPlay[box] == p.seq) return
        syncedPlay[box] = p.seq
        val d = at - p.t - 5 // the cut usually follows the timeout call by about 5 s
        if (d in 4.0..120.0) {
            timing?.delay?.addSync(d)
            log("Synced with ESPN: the TV runs about ${d.toInt()} s behind live.")
        }
    }

    /** Back from commercial while still on this box: ESPN's first play after it times the TV. */
    private fun syncReturn(box: Int, now: Double) {
        val back = returnedAt[box] ?: return
        if (now - back > 240) { returnedAt.remove(box); return }
        val p = feed?.plays(box)?.firstOrNull { it.seq >= 0 && it.seen > back } ?: return
        returnedAt.remove(box)
        val d = back - p.t + 15 // the first snap or kickoff comes about 15 s after the broadcast is back
        if (d in 4.0..120.0) timing?.delay?.addSync(d)
    }

    private var cameraLogged = -1e9

    fun step(heard: Heard, strongAd: Boolean = false, strongWhy: String? = null): Boolean =
        stepWith(heard, strongAd, strongWhy)

    private fun stepWith(heard0: Heard, strongAd0: Boolean, strongWhy0: String?): Boolean {
        val now = clock()
        val cur = nav.current ?: return false
        var heard = heard0; var strongAd = strongAd0; var strongWhy = strongWhy0
        // The camera on the box you're hearing: the score bar gone means commercial (unless the
        // sound clearly says game); the score bar up means it's the game, whatever the sound thinks.
        when (cameraSays(cur, now)) {
            false -> if (heard != Heard.GAME) { heard = Heard.AD; strongAd = true; strongWhy = "Camera sees a commercial" }
            true -> if (heard == Heard.AD && !strongAd) {
                if (now - cameraLogged > 60) { cameraLogged = now; log("Sounded like an ad, but the camera sees the game on box $cur. Staying.") }
                heard = Heard.UNSURE
            }
            null -> Unit
        }
        if (heard == Heard.GAME && lastHeard[cur] == Heard.AD && now - lastSwitch > 15) returnedAt[cur] = now - 4
        // The sound settles on "commercial" about 8 s after it starts.
        if (heard == Heard.AD && lastHeard[cur] != Heard.AD && !strongAd && now - lastSwitch > 20) syncAdStart(cur, now - 8)
        syncReturn(cur, now)
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
                // Bouncing through games that are all in commercials helps no one: sit on this one a bit,
                // but jump the moment ESPN shows one of them is back on (its clock running, a fresh play).
                if (!strongAd && now < settleUntil) {
                    val back = boxes.firstOrNull { it != cur && !inBreak(it, now, status) && outlook(it, now, status).first == Outlook.LIVE }
                    if (back != null && now - lastSwitch > 10) return switchTo(back, now, "Box $back is back on")
                    return false
                }
                while (adSwitches.isNotEmpty() && now - adSwitches.first() > 75) adSwitches.removeFirst()
                if (!strongAd && adSwitches.size >= 2) {
                    adSwitches.clear()
                    settleUntil = now + 45
                    log("Commercials everywhere. Staying on box $cur for a bit.")
                    return false
                }
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
        // Back to the game you were watching once its commercial is over.
        returnTo?.let { home ->
            val status0 = status[home]
            when {
                home == cur || status0 == GameStatus.FINAL -> returnTo = null
                now - lastSwitch >= 15 && isBack(home, now, status) -> {
                    returnTo = null
                    return switchTo(home, now, "Box $home is back from commercial")
                }
            }
        }
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
