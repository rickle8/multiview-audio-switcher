package com.rickle8.mvswitch.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/*
 * What the app learns about how games are broadcast: how far behind real time
 * your TV stream runs, and how long breaks really last.
 */

/** Plays that get a crowd reaction worth timing (scores, turnovers, big gains). */
val BIG_PLAY = Regex(
    "touchdown|intercept|fumble|field goal is good|safety|sacked|for (1[5-9]|[2-9]\\d) yards|" +
        "home run|three point|3-pointer|dunk|goal scored|scores",
    RegexOption.IGNORE_CASE,
)

/** Plays that don't mean the game is back from a break (the kickoff between two breaks, an extra point...). */
val NOT_RESUMED = Regex(
    "extra point|two.point|kicks \\d+ yards from|onside|kickoff|touchback|timeout|end of|two.minute|" +
        "injur|review|challenge|halftime",
    RegexOption.IGNORE_CASE,
)

/** Breaks that follow a score are often two in a row (before and after the kickoff). */
val SCORE_PLAY = Regex("touchdown|field goal is good|safety", RegexOption.IGNORE_CASE)

/**
 * How far behind live the TV stream runs, learned by lining up the crowd's
 * reaction heard through the mic with the real time of big plays.
 */
class StreamDelay(private val minD: Int = 4, private val maxD: Int = 100) {
    private val hist = DoubleArray(maxD + 1)
    private var weight = 0.0
    private val used = LinkedHashSet<String>()
    /** Seconds the TV runs behind the real game, once it's clear. */
    @Volatile var seconds: Double? = null
        private set

    /**
     * Direct measurements from commercial breaks: when the TV went to (or came back
     * from) a commercial, against ESPN's real time for the timeout or play around it.
     * These line up the audio with ESPN far better than crowd noise, so once there
     * are two, they decide.
     */
    private val sync = ArrayDeque<Double>()
    val syncSamples: Int get() = synchronized(sync) { sync.size }

    fun addSync(d: Double) {
        if (d < minD || d > maxD + 20) return
        synchronized(sync) {
            sync.addLast(d)
            while (sync.size > 9) sync.removeFirst()
            if (sync.size >= 2) seconds = sync.sorted()[sync.size / 2]
        }
    }

    /** Plays timed so far (for the screen). */
    val samples: Int get() = weight.toInt()

    /**
     * [reaction] gives how much the crowd/announcers reacted at an app-clock
     * second (null if the mic wasn't on this game then). Call regularly; each
     * play is used once, about [maxD] seconds after it happened.
     */
    fun observe(key: String, playT: Double, now: Double, reaction: (Double) -> Double?): Boolean {
        if (key in used || now < playT + maxD + 2 || now > playT + maxD + 40) return false
        used += key
        while (used.size > 300) used.remove(used.first())
        val series = DoubleArray(maxD + 1) { Double.NaN }
        var heard = 0
        for (d in minD..maxD) reaction(playT + d)?.let { series[d] = it; heard++ }
        if (heard < (maxD - minD) * 0.8) return false // wasn't listening to this game long enough
        val vals = series.filter { !it.isNaN() }.sorted()
        val base = vals[vals.size / 2]
        val top = vals.last() - base
        if (top < 0.15) return false // no clear reaction
        for (d in hist.indices) hist[d] *= 0.85 // newer plays count more
        weight = weight * 0.85 + 1
        for (d in minD..maxD) {
            val v = series[d]
            if (!v.isNaN()) hist[d] += max(0.0, v - base) / top
        }
        estimate()
        return true
    }

    private fun estimate() {
        if (syncSamples >= 2) return // break timing already pins it down
        if (weight < 2.5) return
        val smooth = DoubleArray(hist.size) { d ->
            var s = 0.0; var n = 0
            for (k in d - 2..d + 2) if (k in minD..maxD) { s += hist[k]; n++ }
            if (n == 0) 0.0 else s / n
        }
        val best = (minD..maxD).maxByOrNull { smooth[it] } ?: return
        val mean = (minD..maxD).sumOf { smooth[it] } / (maxD - minD + 1)
        if (smooth[best] >= 2.2 * mean) seconds = best.toDouble()
    }

    fun toJson(): JSONObject = JSONObject().put("w", weight).put("hist", JSONArray(hist.toList()))
        .put("s", seconds ?: -1.0).put("sync", JSONArray(synchronized(sync) { sync.toList() }))

    fun load(j: JSONObject) {
        j.optJSONArray("hist")?.let { a -> for (i in 0 until minOf(a.length(), hist.size)) hist[i] = a.optDouble(i, 0.0) }
        weight = j.optDouble("w", 0.0)
        seconds = j.optDouble("s", -1.0).takeIf { it > 0 }
        synchronized(sync) {
            sync.clear()
            j.optJSONArray("sync")?.let { a -> for (i in 0 until a.length()) sync.addLast(a.optDouble(i)) }
        }
    }

    fun clear() { hist.fill(0.0); weight = 0.0; seconds = null; used.clear(); synchronized(sync) { sync.clear() } }
}

/** Real break lengths from your games, per league, split into ordinary breaks and breaks after a score. */
class BreakLengths(private val keep: Int = 12) {
    private val samples = HashMap<String, ArrayDeque<Int>>()

    private fun key(league: String, afterScore: Boolean) = league + if (afterScore) "#score" else ""

    @Synchronized
    fun add(league: String, afterScore: Boolean, secs: Double) {
        if (secs < 45 || secs > 480) return
        val q = samples.getOrPut(key(league, afterScore)) { ArrayDeque() }
        q.addLast(secs.toInt())
        while (q.size > keep) q.removeFirst()
    }

    /** Typical length, once there are a few to go on. */
    @Synchronized
    fun typical(league: String, afterScore: Boolean): Double? {
        val q = samples[key(league, afterScore)] ?: return null
        if (q.size < 3) return null
        return q.sorted()[q.size / 2].toDouble()
    }

    @Synchronized
    fun count(): Int = samples.values.sumOf { it.size }

    @Synchronized
    fun toJson(): JSONObject = JSONObject().apply { samples.forEach { (k, v) -> put(k, JSONArray(v.toList())) } }

    @Synchronized
    fun load(j: JSONObject) {
        samples.clear()
        for (k in j.keys()) {
            val a = j.optJSONArray(k) ?: continue
            samples[k] = ArrayDeque((0 until a.length()).map { a.optInt(it) }.filter { it > 0 })
        }
    }

    @Synchronized
    fun clear() = samples.clear()
}

/** Everything learned about the broadcast, saved between games. */
class BroadcastTiming {
    val delay = StreamDelay()
    val breaks = BreakLengths()

    fun toJson(): JSONObject = JSONObject().put("delay", delay.toJson()).put("breaks", breaks.toJson())

    fun load(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        j.optJSONObject("delay")?.let { delay.load(it) }
        j.optJSONObject("breaks")?.let { breaks.load(it) }
    }

    fun clear() { delay.clear(); breaks.clear() }
}
