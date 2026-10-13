package com.rickle8.mvswitch.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spectrum math shared by fingerprinting, voice matching and features. 16 kHz mono. */
object Dsp {
    const val SR = 16000
    const val N = 1024          // 64 ms frames
    const val HOP = 512         // 32 ms hop, ~31 frames per second
    const val BINS = N / 2 + 1
    private const val BITS = 10

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
    private val cosT = DoubleArray(N / 2) { cos(-2 * PI * it / N) }
    private val sinT = DoubleArray(N / 2) { sin(-2 * PI * it / N) }
    private val rev = IntArray(N) { i ->
        var x = i
        var y = 0
        repeat(BITS) { y = (y shl 1) or (x and 1); x = x shr 1 }
        y
    }

    /** Magnitude spectrum (scaled by 1/N) of samples[off until off+N]. */
    fun magnitude(samples: FloatArray, off: Int): FloatArray {
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        for (i in 0 until N) re[rev[i]] = (samples[off + i] * window[i]).toDouble()
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var start = 0
            while (start < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        return FloatArray(BINS) { k -> (sqrt(re[k] * re[k] + im[k] * im[k]) / N).toFloat() }
    }

    fun energyDb(mag: FloatArray): Float {
        var s = 0.0
        for (m in mag) s += m * m
        return (10 * log10(s + 1e-12)).toFloat()
    }
}

/** Streaming short-time spectrum: push audio in any chunk size, get frames out. */
class Stft {
    private var tail = FloatArray(0)
    /** Absolute index of the next frame that will be produced. */
    var nextFrame = 0L
        private set

    fun push(samples: FloatArray): List<FloatArray> {
        val all = FloatArray(tail.size + samples.size)
        tail.copyInto(all)
        samples.copyInto(all, tail.size)
        val out = ArrayList<FloatArray>(40)
        var off = 0
        while (off + Dsp.N <= all.size) {
            out += Dsp.magnitude(all, off)
            off += Dsp.HOP
        }
        tail = all.copyOfRange(off, all.size)
        nextFrame += out.size
        return out
    }

    fun reset() {
        tail = FloatArray(0)
    }
}

/** 32-band log-mel spectrum and 12 MFCCs (c1..c12, loudness-free). */
object Mel {
    const val BANDS = 32
    const val CEPS = 12
    private const val FMIN = 60.0
    private const val FMAX = 7600.0

    private fun hzToMel(f: Double) = 2595 * log10(1 + f / 700)
    private fun melToHz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)

    private val filters: Array<FloatArray> = run {
        val lo = hzToMel(FMIN)
        val hi = hzToMel(FMAX)
        val pts = DoubleArray(BANDS + 2) { melToHz(lo + (hi - lo) * it / (BANDS + 1)) }
        val binHz = Dsp.SR.toDouble() / Dsp.N
        Array(BANDS) { b ->
            FloatArray(Dsp.BINS) { k ->
                val f = k * binHz
                val (l, c, r) = Triple(pts[b], pts[b + 1], pts[b + 2])
                when {
                    f <= l || f >= r -> 0f
                    f <= c -> ((f - l) / (c - l)).toFloat()
                    else -> ((r - f) / (r - c)).toFloat()
                }
            }
        }
    }
    private val dct = Array(CEPS) { k -> DoubleArray(BANDS) { n -> cos(PI * (k + 1) * (n + 0.5) / BANDS) } }

    fun logMel(mag: FloatArray): FloatArray = FloatArray(BANDS) { b ->
        val f = filters[b]
        var s = 0.0
        for (k in f.indices) if (f[k] != 0f) s += f[k] * mag[k] * mag[k]
        ln(s + 1e-10).toFloat()
    }

    fun mfcc(logMel: FloatArray): FloatArray = FloatArray(CEPS) { k ->
        var s = 0.0
        for (n in 0 until BANDS) s += dct[k][n] * logMel[n]
        (s * sqrt(2.0 / BANDS)).toFloat()
    }
}

/** Everything computed from one second of audio. */
class SecondAudio(
    val logMels: List<FloatArray>,
    val mfccs: List<FloatArray>,
    val energiesDb: FloatArray,
    val loudDb: Double,
)

object Features {
    const val N_YAMNET = 521
    const val SIZE = N_YAMNET + Mel.BANDS * 2 + Mel.CEPS * 2 + 3

    /**
     * About 600 numbers per second: every YAMNet sound score plus the spectral
     * shape, its movement, voice timbre (MFCCs) and loudness.
     */
    fun build(yamnet: FloatArray, a: SecondAudio): DoubleArray {
        val x = DoubleArray(SIZE)
        var i = 0
        for (k in 0 until N_YAMNET) x[i++] = ln((yamnet.getOrElse(k) { 0f }) + 1e-3)
        val frames = a.logMels
        val n = max(1, frames.size)
        var level = 0.0
        for (f in frames) for (v in f) level += v
        level /= (n * Mel.BANDS)
        for (b in 0 until Mel.BANDS) {
            var m = 0.0
            for (f in frames) m += f[b]
            m /= n
            var v = 0.0
            for (f in frames) v += (f[b] - m) * (f[b] - m)
            x[i + b] = m - level
            x[i + Mel.BANDS + b] = sqrt(v / n)
        }
        i += Mel.BANDS * 2
        for (c in 0 until Mel.CEPS) {
            var m = 0.0
            for (f in a.mfccs) m += f[c]
            m /= max(1, a.mfccs.size)
            var v = 0.0
            for (f in a.mfccs) v += (f[c] - m) * (f[c] - m)
            x[i + c] = m
            x[i + Mel.CEPS + c] = sqrt(v / max(1, a.mfccs.size))
        }
        i += Mel.CEPS * 2
        var flux = 0.0
        for (t in 1 until frames.size) {
            var s = 0.0
            for (b in 0 until Mel.BANDS) s += max(0f, frames[t][b] - frames[t - 1][b])
            flux += s / Mel.BANDS
        }
        x[i++] = flux / max(1, frames.size - 1)
        val meanE = if (a.energiesDb.isEmpty()) 0.0 else a.energiesDb.average()
        x[i++] = if (a.energiesDb.isEmpty()) 0.0 else a.energiesDb.count { it < meanE - 10 }.toDouble() / a.energiesDb.size
        x[i] = min(0.0, a.loudDb) / 20.0
        return x
    }

    /** Spectral shape only (for spotting sudden changes in sound). */
    fun shape(a: SecondAudio): DoubleArray {
        val frames = a.logMels
        val n = max(1, frames.size)
        var level = 0.0
        for (f in frames) for (v in f) level += v
        level /= (n * Mel.BANDS)
        return DoubleArray(Mel.BANDS) { b -> frames.sumOf { it[b].toDouble() } / n - level }
    }
}
