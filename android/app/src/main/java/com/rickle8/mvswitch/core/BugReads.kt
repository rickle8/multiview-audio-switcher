package com.rickle8.mvswitch.core

/**
 * What the text reader has seen of each box's score bug over time: when it was last read, how
 * dependably, and how long it's been gone when that means something (the cut to a commercial).
 */
class BugReads {
    private class Read(val t: Double, val game: Boolean, val notLive: Boolean)

    private val reads = HashMap<Int, ArrayDeque<Read>>()
    private val gameAt = HashMap<Int, Double>()
    private val dependable = HashMap<Int, Boolean>()

    /** A read of [box] at [t]: its score bug read ([game]), its own graphics saying halftime or final ([notLive]). */
    @Synchronized
    fun add(box: Int, t: Double, game: Boolean, notLive: Boolean) {
        val q = reads.getOrPut(box) { ArrayDeque() }
        q.addLast(Read(t, game, notLive))
        while (q.isNotEmpty() && t - q.first().t > 120) q.removeFirst()
        if (game) {
            gameAt[box] = t
            // Dependable: read 4+ times in the minute up to now. Kept as of its last read: the reads only
            // go back two minutes, and a commercial break runs longer with the bug gone the whole time.
            dependable[box] = q.count { it.game && t - it.t <= 60 } >= 4
        }
    }

    /** When [box]'s score bug was last read. */
    @Synchronized
    fun gameAt(box: Int): Double? = gameAt[box]

    /** [box]'s score bug read in the last [secs]. */
    @Synchronized
    fun readWithin(box: Int, now: Double, secs: Double): Boolean = gameAt[box]?.let { now - it < secs } == true

    /**
     * How long [box]'s score bug has been missing, when that means something, else null:
     * - 8 to 45 s on a box where it's read nearly every time (60%+ of reads in the 40 s before it
     *   went), missing enough reads in a row that it isn't a misread;
     * - 45 s to 20 minutes on a box where it was read dependably (4+ times in the minute before).
     * Only while the camera still reads another box's score bug (so it can see the TV) and is still
     * reading this box, and never when this box's own graphics said halftime or final in the last minute.
     */
    @Synchronized
    fun goneSecs(box: Int, now: Double): Double? {
        val at = gameAt[box] ?: return null
        val gone = now - at
        if (gone < 8 || gone > 1200) return null
        val q = reads[box] ?: return null
        val last = q.lastOrNull() ?: return null
        if (q.any { it.notLive && now - it.t < 60 }) return null
        if (gone < 45) {
            if (now - last.t > 6) return null
            val before = q.filter { it.t in (at - 40)..at }
            val since = q.count { it.t > at }
            if (before.size < 8 || since < 3) return null
            val hit = before.count { it.game }.toDouble() / before.size
            if (hit < 0.6 || Math.pow(1 - hit, since.toDouble()) > 0.01) return null
            return gone.takeIf { othersRead(box, now, 20.0) }
        }
        if (now - last.t > 10 || dependable[box] != true) return null
        return gone.takeIf { othersRead(box, now, 45.0) }
    }

    private fun othersRead(box: Int, now: Double, secs: Double) = gameAt.any { (b, t) -> b != box && now - t < secs }

    @Synchronized
    fun clear() { reads.clear(); gameAt.clear(); dependable.clear() }
}
