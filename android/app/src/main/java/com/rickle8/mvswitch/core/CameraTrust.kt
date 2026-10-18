package com.rickle8.mvswitch.core

/*
 * The camera earns its say. Each thing it sees only gets to act once it has matched
 * something surer on your own TV, so it works on its own without you having to watch it.
 */

/**
 * Whether the camera's reading of YouTube TV's outline flash can be trusted to say where the
 * sound is. Every time the app moves the sound itself, it checks where the outline flashed: on
 * the box it moved to (right), somewhere else (wrong) or nowhere it could see (missed). Once the
 * last 3 checks were right, with nothing wrong in the last 5, a flash the app didn't cause (you,
 * with the TV remote) moves the app's sound box on its own. More than 3 of those in 2 minutes
 * means it's misreading something: it goes back to asking.
 */
class OutlineTrust {
    enum class Action { NOTHING, ASK, FOLLOW }

    private class Check(val target: Int, val via: Set<Int>, val start: Double, val end: Double) {
        val flashes = mutableListOf<Int>()
    }

    private val results = ArrayDeque<Boolean>()
    private val follows = ArrayDeque<Double>()
    private var check: Check? = null

    /** For the diagnostics. */
    var right = 0
        private set
    var wrong = 0
        private set
    var missed = 0
        private set

    @get:Synchronized
    val trusted: Boolean
        get() = results.size >= 3 && results.takeLast(3).all { it } && results.takeLast(5).none { !it }

    /** The app pressed keys between [start] and [end] to move the sound to [target], passing through [via]. */
    @Synchronized
    fun appMoved(target: Int, via: Set<Int>, start: Double, end: Double) {
        check?.let { settle(it) }
        check = Check(target, via, start, end)
    }

    /** An outline flash appeared on [box] at [t]. Says what to do about it. */
    @Synchronized
    fun flash(box: Int, t: Double, current: Int?): Action {
        val c = check
        if (c != null && t >= c.start - 0.3 && t <= c.end + 3) {
            c.flashes += box // the app's own move
            return Action.NOTHING
        }
        if (box == current) return Action.NOTHING
        if (!trusted) return Action.ASK
        while (follows.isNotEmpty() && t - follows.first() > 120) follows.removeFirst()
        if (follows.size >= 3) {
            // Moving that often, nobody's doing it: something on screen looks like the outline.
            results.clear()
            follows.clear()
            return Action.ASK
        }
        follows.addLast(t)
        return Action.FOLLOW
    }

    /** Every frame: a move's check is settled 3 s after its last key. */
    @Synchronized
    fun tick(now: Double) {
        val c = check ?: return
        if (now > c.end + 3) { settle(c); check = null }
    }

    /** You answered "Is it there?": that counts like a check. */
    @Synchronized
    fun answered(yes: Boolean) = record(yes)

    private fun settle(c: Check) {
        val last = c.flashes.lastOrNull()
        when {
            last == null -> missed++
            last == c.target -> record(true)
            last in c.via -> missed++ // only saw it pass through: can't tell
            else -> record(false)
        }
    }

    private fun record(ok: Boolean) {
        if (ok) right++ else wrong++
        results.addLast(ok)
        while (results.size > 5) results.removeFirst()
    }
}

/**
 * Football's green field. In a box showing the game, wide shots are mostly field; commercials
 * almost never are. "Field in the last 8 s" says the game is on (never the other way round:
 * close-ups and replays go long stretches without it). It only counts on a box once it has
 * matched surer signs there: field while the score text reads or the sound is the game (3
 * times), and none in a commercial (once). Field showing in a sure commercial resets it.
 */
class FieldWatch {
    private val seen = HashMap<Int, ArrayDeque<Pair<Double, Double>>>()
    private val up = HashMap<Int, Int>()
    private val gone = HashMap<Int, Int>()
    private val checkedAt = HashMap<Int, Double>()

    /** Share of the box that's field green right now (0..1). */
    @Synchronized
    fun add(box: Int, green: Double, t: Double) {
        val q = seen.getOrPut(box) { ArrayDeque() }
        q.addLast(t to green)
        while (q.isNotEmpty() && t - q.first().first > 10) q.removeFirst()
    }

    /** A wide shot of the field in the last 8 s. */
    @Synchronized
    fun fieldNow(box: Int, now: Double): Boolean =
        seen[box]?.any { (t, g) -> now - t <= 8 && g >= FIELD_SHARE } == true

    @Synchronized
    fun trusted(box: Int): Boolean = (up[box] ?: 0) >= 3 && (gone[box] ?: 0) >= 1

    @Synchronized
    fun counts(box: Int): Pair<Int, Int> = (up[box] ?: 0) to (gone[box] ?: 0)

    /**
     * Checks a box against surer signs. [game]: its score text read just now, or its sound is the
     * game. [commercial]: the sound or the text says commercial. [sureAd]: a commercial it's sure of.
     */
    @Synchronized
    fun check(box: Int, now: Double, game: Boolean, commercial: Boolean, sureAd: Boolean) {
        val field = fieldNow(box, now)
        if (field && sureAd) { up.remove(box); gone.remove(box); return }
        if (now - (checkedAt[box] ?: -1e9) < 5) return
        when {
            field && game -> { checkedAt[box] = now; up[box] = (up[box] ?: 0) + 1 }
            !field && commercial -> { checkedAt[box] = now; gone[box] = (gone[box] ?: 0) + 1 }
        }
    }

    @Synchronized
    fun clear() { seen.clear(); up.clear(); gone.clear(); checkedAt.clear() }

    companion object {
        /** Share of a box that has to be field for a wide shot. */
        const val FIELD_SHARE = 0.2

        /** Whether one camera pixel (YUV, as the camera gives it) is the green of a football field. */
        fun isFieldGreen(y: Int, u: Int, v: Int): Boolean {
            val cu = u - 128; val cv = v - 128
            val r = (y + 1.402 * cv).coerceIn(0.0, 255.0)
            val g = (y - 0.344 * cu - 0.714 * cv).coerceIn(0.0, 255.0)
            val b = (y + 1.772 * cu).coerceIn(0.0, 255.0)
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            if (max < 35 || g < max) return false // too dark, or not green-led
            val sat = (max - min) / max
            if (sat < 0.15) return false
            // Hue between yellow-green and blue-green.
            val hue = 60 * ((b - r) / (max - min)) + 120
            return hue in 75.0..165.0
        }
    }
}
