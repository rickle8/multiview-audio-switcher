package com.rickle8.mvswitch.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ln

/**
 * Shazam-style audio fingerprints for recognizing commercials that already aired.
 *
 * Each frame's strongest spectral peaks are paired with peaks shortly after them;
 * each pair (freq1, freq2, time gap) becomes a hash. The same ad played again
 * produces the same hashes at the same relative times, even through a phone mic
 * in a noisy room. Different audio almost never lines up.
 */
object FpParams {
    const val FMIN = 8          // ~125 Hz
    const val FMAX = 256        // ~4 kHz
    const val PT = 3            // peak neighborhood in frames (each side)
    const val PF = 6            // peak neighborhood in bins (each side)
    const val PEAKS_PER_FRAME = 5
    const val FANOUT = 4
    const val DT_MAX = 20       // pair up to ~0.64 s ahead
    const val DF_MAX = 64
    const val FPS = Dsp.SR.toDouble() / Dsp.HOP  // 31.25
    const val KEEP_SECS = 30
}

fun packHash(f1: Int, f2: Int, dt: Int) = (f1 shl 13) or (f2 shl 5) or dt

/** Turns spectrum frames into (frame, hash) pairs as they become complete. */
class Fingerprinter {
    private val frames = ArrayDeque<Pair<Long, FloatArray>>()   // abs frame -> log magnitude
    private val peaks = HashMap<Long, IntArray>()
    private var nextPeakFrame = 0L
    private var nextAnchor = 0L
    /** Packed (frame shl 21 | hash), oldest first. */
    private val recent = ArrayDeque<Long>()
    var latestFrame = -1L
        private set

    fun push(mags: List<FloatArray>, firstAbs: Long) {
        mags.forEachIndexed { i, m ->
            val sub = FloatArray(FpParams.FMAX - FpParams.FMIN) { k -> ln(m[FpParams.FMIN + k] + 1e-9f) }
            frames.addLast((firstAbs + i) to sub)
            latestFrame = firstAbs + i
        }
        if (frames.isEmpty()) return
        if (nextPeakFrame < frames.first().first) nextPeakFrame = frames.first().first
        if (nextAnchor < frames.first().first) nextAnchor = frames.first().first
        // Peaks need PT frames of look-ahead.
        while (nextPeakFrame + FpParams.PT <= latestFrame) {
            peaks[nextPeakFrame] = findPeaks(nextPeakFrame)
            nextPeakFrame++
        }
        // Hashes need DT_MAX frames of peaks after the anchor.
        while (nextAnchor + FpParams.DT_MAX < nextPeakFrame) {
            emit(nextAnchor)
            nextAnchor++
        }
        // Trim.
        val keepFrom = nextAnchor - 2
        while (frames.size > 0 && frames.first().first < keepFrom - FpParams.PT) frames.removeFirst()
        peaks.keys.removeAll { it < keepFrom }
        val oldest = latestFrame - (FpParams.KEEP_SECS * FpParams.FPS).toLong()
        while (recent.isNotEmpty() && (recent.first() ushr 21) < oldest) recent.removeFirst()
    }

    private fun frame(abs: Long): FloatArray? {
        val first = frames.firstOrNull()?.first ?: return null
        val idx = (abs - first).toInt()
        return frames.getOrNull(idx)?.second
    }

    private fun findPeaks(t: Long): IntArray {
        val row = frame(t) ?: return IntArray(0)
        val sorted = row.sortedArray()
        val thr = sorted[sorted.size / 2] + 2.0f
        val neighbors = (-FpParams.PT..FpParams.PT).mapNotNull { frame(t + it) }
        val cand = ArrayList<Pair<Float, Int>>()
        for (f in row.indices) {
            val v = row[f]
            if (v < thr) continue
            var isMax = true
            loop@ for (nb in neighbors) {
                for (g in maxOf(0, f - FpParams.PF)..minOf(row.size - 1, f + FpParams.PF)) {
                    if (nb[g] > v) { isMax = false; break@loop }
                }
            }
            if (isMax) cand += v to f
        }
        cand.sortByDescending { it.first }
        return IntArray(minOf(FpParams.PEAKS_PER_FRAME, cand.size)) { cand[it].second }
    }

    private fun emit(t1: Long) {
        val p1 = peaks[t1] ?: return
        for (f1 in p1) {
            var n = 0
            for (dt in 1..FpParams.DT_MAX) {
                val p2 = peaks[t1 + dt] ?: continue
                for (f2 in p2) {
                    if (kotlin.math.abs(f2 - f1) <= FpParams.DF_MAX) {
                        recent.addLast((t1 shl 21) or packHash(f1, f2, dt).toLong())
                        if (++n >= FpParams.FANOUT) break
                    }
                }
                if (n >= FpParams.FANOUT) break
            }
        }
    }

    /** Hashes whose anchor frame is in [from, to], as (frame, hash) packed longs. */
    fun range(from: Long, to: Long): List<Long> =
        recent.filter { val f = it ushr 21; f in from..to }

    fun reset() {
        frames.clear(); peaks.clear(); recent.clear()
        nextPeakFrame = 0; nextAnchor = 0
    }
}

/** count: aligned hashes; spread: how many ~0.25 s slots of the query they cover. */
data class AdMatch(val segment: Int, val count: Int, val ratio: Double, val spread: Int)

/** Library of commercials heard before. */
class AdPrints(private val maxSegments: Int = 80) {
    private class Segment(val id: Int, val created: Long, var lastUsed: Long, val auto: Boolean,
                          val hashes: IntArray, val times: ShortArray)

    private val segments = LinkedHashMap<Int, Segment>()
    /** Sorted entries: hash shl 32 | segment shl 16 | time. */
    private var index = LongArray(0)
    private var nextId = 1

    val size get() = segments.size

    @Synchronized
    fun add(packed: List<Long>, auto: Boolean, now: Long): Int? {
        if (packed.size < 200) return null // too short to be useful
        val base = packed.minOf { it ushr 21 }
        val usable = packed.filter { (it ushr 21) - base < 65000 }
        val id = nextId
        nextId = if (nextId >= 65000) 1 else nextId + 1
        segments.remove(id)
        segments[id] = Segment(id, now, now, auto,
            IntArray(usable.size) { (usable[it] and 0x1FFFFF).toInt() },
            ShortArray(usable.size) { ((usable[it] ushr 21) - base).toInt().toShort() })
        while (segments.size > maxSegments) {
            val victim = segments.values.minByOrNull { it.lastUsed } ?: break
            segments.remove(victim.id)
        }
        rebuild()
        return id
    }

    @Synchronized
    fun remove(id: Int) {
        if (segments.remove(id) != null) rebuild()
    }

    @Synchronized
    fun clear() {
        segments.clear(); index = LongArray(0)
    }

    private fun rebuild() {
        var n = 0
        for (s in segments.values) n += s.hashes.size
        val arr = LongArray(n)
        var i = 0
        for (s in segments.values) for (k in s.hashes.indices) {
            arr[i++] = (s.hashes[k].toLong() shl 32) or (s.id.toLong() shl 16) or (s.times[k].toLong() and 0xFFFF)
        }
        arr.sort()
        index = arr
    }

    private fun lowerBound(key: Long): Int {
        var lo = 0
        var hi = index.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (index[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Best-aligned segment for a query (packed frame/hash list). */
    @Synchronized
    fun match(query: List<Long>, now: Long): AdMatch? {
        if (query.isEmpty() || index.isEmpty()) return null
        val q0 = query.minOf { it ushr 21 }
        val counts = HashMap<Int, Int>()
        val slots = HashMap<Int, Long>()
        for (q in query) {
            val h = q and 0x1FFFFF
            val qt = ((q ushr 21) - q0).toInt()
            var i = lowerBound(h shl 32)
            while (i < index.size && (index[i] ushr 32) == h) {
                val e = index[i]
                val seg = ((e ushr 16) and 0xFFFF).toInt()
                val t = (e and 0xFFFF).toInt()
                val key = seg * 131072 + (t - qt + 65536)
                counts[key] = (counts[key] ?: 0) + 1
                slots[key] = (slots[key] ?: 0L) or (1L shl minOf(63, qt / 8))
                i++
            }
        }
        val best = counts.maxByOrNull { it.value } ?: return null
        val seg = best.key / 131072
        segments[seg]?.lastUsed = now
        val spread = java.lang.Long.bitCount(slots[best.key] ?: 0L)
        return AdMatch(seg, best.value, best.value.toDouble() / query.size, spread)
    }

    @Synchronized
    fun save(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(0x4D564631) // "MVF1"
        d.writeInt(nextId)
        d.writeInt(segments.size)
        for (s in segments.values) {
            d.writeInt(s.id); d.writeLong(s.created); d.writeLong(s.lastUsed); d.writeBoolean(s.auto)
            d.writeInt(s.hashes.size)
            for (h in s.hashes) d.writeInt(h)
            for (t in s.times) d.writeShort(t.toInt())
        }
        d.flush()
    }

    @Synchronized
    fun load(input: InputStream) {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != 0x4D564631) return
        clear()
        nextId = d.readInt()
        repeat(d.readInt()) {
            val id = d.readInt()
            val created = d.readLong()
            val used = d.readLong()
            val auto = d.readBoolean()
            val n = d.readInt()
            val hashes = IntArray(n) { d.readInt() }
            val times = ShortArray(n) { d.readShort() }
            segments[id] = Segment(id, created, used, auto, hashes, times)
        }
        rebuild()
    }
}
