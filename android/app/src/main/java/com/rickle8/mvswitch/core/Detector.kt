package com.rickle8.mvswitch.core

import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One second from the mic: raw audio plus YAMNet's score for each of its 521 sound types. */
class MicSecond(val samples: FloatArray, val scores: FloatArray, val names: Array<String>)

data class DetectResult(
    val heard: Heard,
    /** Recognized a fingerprinted ad: trust it over live-data guards. */
    val strongAd: Boolean,
    val adPct: Int?,
    val why: String?,
    val topSounds: List<String>,
)

/**
 * Spots the moment a broadcast cuts to break: a brief dip in sound followed by
 * audio that sounds clearly different from the last several seconds.
 */
class TransitionDetector {
    private val energies = ArrayDeque<Float>()
    private val shapes = ArrayDeque<Pair<Double, DoubleArray>>()
    private var dipAt: Double? = null
    var lastTransition = -1e9
        private set

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += (a[i] - b[i]) * (a[i] - b[i])
        return sqrt(s)
    }

    private fun mean(list: List<DoubleArray>) =
        DoubleArray(list.first().size) { i -> list.sumOf { it[i] } / list.size }

    fun update(a: SecondAudio, now: Double): Boolean {
        if (energies.size >= 150) {
            val sorted = energies.sorted()
            val med = sorted[sorted.size / 2]
            var run = 0
            var best = 0
            for (e in a.energiesDb) {
                if (e < med - 18) { run++; best = max(best, run) } else run = 0
            }
            if (best >= 8) dipAt = now
        }
        for (e in a.energiesDb) energies.addLast(e)
        while (energies.size > 470) energies.removeFirst()
        shapes.addLast(now to Features.shape(a))
        while (shapes.isNotEmpty() && now - shapes.first().first > 20) shapes.removeFirst()

        val d = dipAt ?: return false
        if (now - d < 3.0) return false
        dipAt = null
        val after = shapes.filter { it.first > d }.map { it.second }
        val before = shapes.filter { it.first < d - 0.5 && it.first > d - 12 }.map { it.second }
        if (after.size < 2 || before.size < 5) return false
        val steps = before.zipWithNext { x, y -> dist(x, y) }.sorted()
        val typical = max(0.3, steps[steps.size / 2])
        if (dist(mean(after), mean(before)) > 2.5 * typical) {
            lastTransition = now
            return true
        }
        return false
    }
}

/**
 * Turns each second of mic audio into a game/commercial verdict using every
 * signal the app has: repeat-ad fingerprints, the learned classifier (or the
 * fixed rules), the announcers' voices, sudden sound changes, and how likely a
 * break is right now. Also handles all learning.
 */
class Detector(private val dir: File? = null) {
    val model = SoundModel()
    val prints = AdPrints()
    val voices = AnnouncerModel()
    val verdict = Verdict()
    private val stft = Stft()
    private val fp = Fingerprinter()
    private val transitions = TransitionDetector()

    private class Rec(
        val t: Double, val box: Int?, val x: DoubleArray, val audio: SecondAudio,
        val speech: Double, val frameTo: Long, val pAd: Double?, var labeled: Boolean = false,
    )

    private val history = ArrayDeque<Rec>()
    private var prevMatch: AdMatch? = null
    private var lastMatch: Pair<Int, Double>? = null    // segment, time
    @Volatile private var labelUntil = 0.0
    @Volatile private var labelAsAd = false
    private var pendingPrintFrom: Long? = null
    private var pendingPrintUntil = 0.0
    private var dirty = false
    private var lastSave = 0.0
    var lastWhy: String? = null
        private set

    fun labelingNow(now: Double): String? =
        if (now < labelUntil) (if (labelAsAd) "commercial" else "game") else null

    // ------------------------------------------------------------ persistence
    private fun file(name: String) = dir?.let { File(it, name) }

    fun load() {
        file("sound_model2.bin")?.takeIf { it.exists() }?.let { f -> runCatching { f.inputStream().use { model.load(it) } } }
        file("ad_prints.bin")?.takeIf { it.exists() }?.let { f -> runCatching { f.inputStream().use { prints.load(it) } } }
        file("voices.bin")?.takeIf { it.exists() }?.let { f -> runCatching { f.inputStream().use { voices.load(it) } } }
        file("sound_model.json")?.delete() // old format
    }

    fun save() {
        dirty = false
        val d = dir ?: return
        fun write(name: String, block: (java.io.OutputStream) -> Unit) = runCatching {
            val tmp = File(d, "$name.tmp")
            tmp.outputStream().use(block)
            tmp.renameTo(File(d, name))
        }
        write("sound_model2.bin") { model.save(it) }
        write("ad_prints.bin") { prints.save(it) }
        write("voices.bin") { voices.save(it) }
    }

    private fun maybeSave(now: Double) {
        if (dirty && now - lastSave > 60) { lastSave = now; save() }
    }

    // ------------------------------------------------------------ main step
    private fun logit(p: Double) = ln(p.coerceIn(1e-4, 1 - 1e-4) / (1 - p.coerceIn(1e-4, 1 - 1e-4)))
    private fun sigmoid(a: Double) = 1 / (1 + exp(-a))

    fun process(sec: MicSecond, now: Double, box: Int?, bias: Bias, learning: Boolean): DetectResult {
        val mags = stft.push(sec.samples)
        val firstAbs = stft.nextFrame - mags.size
        fp.push(mags, firstAbs)
        val logMels = mags.map { Mel.logMel(it) }
        val mfccs = logMels.map { Mel.mfcc(it) }
        val energies = FloatArray(mags.size) { Dsp.energyDb(mags[it]) }
        var sumSq = 0.0
        for (s in sec.samples) sumSq += s * s
        val loud = 10 * kotlin.math.log10(sumSq / max(1, sec.samples.size) + 1e-12)
        val audio = SecondAudio(logMels, mfccs, energies, loud)

        val byName = HashMap<String, Float>(sec.names.size * 2)
        sec.names.forEachIndexed { i, n -> if (i < sec.scores.size) byName[n] = sec.scores[i] }
        val top = sec.scores.indices.sortedByDescending { sec.scores[it] }.take(3).mapNotNull { sec.names.getOrNull(it) }
        val speech = SPEECH_CLASSES.maxOf { (byName[it] ?: 0f).toDouble() }
        val x = Features.build(sec.scores, audio)

        // 1. Repeat-ad fingerprint.
        val latest = fp.latestFrame
        val query = fp.range(latest - (4 * FpParams.FPS).toLong(), latest)
        val m = prints.match(query, System.currentTimeMillis())
        // A real replay lines up steadily across the whole window, not in one burst.
        val solid = m != null && m.ratio >= 0.04 && m.spread >= 3
        val strong = solid && (m!!.count >= 14 || (m.count >= 10 && prevMatch?.segment == m.segment))
        prevMatch = if (solid && m!!.count >= 10) m else null
        if (strong) lastMatch = m!!.segment to now

        // 2. Classifier (learned, or fixed rules until trained).
        val learnedP = model.adProbability(x)
        var (game, ad) = if (learnedP != null) (1 - learnedP) to learnedP else heuristicScores(byName)

        // 3. Announcer voice.
        val voice = voices.evidence(audio, speech)
        if (voice != null && game + ad > 0.05) {
            val p = sigmoid(logit(ad / (game + ad)) - voice)
            val total = game + ad
            game = total * (1 - p); ad = total * p
        }

        // 4. Sudden change in sound.
        transitions.update(audio, now)
        val recentTransition = now - transitions.lastTransition < 20
        val effBias = when {
            !recentTransition -> bias
            bias == Bias.UNLIKELY -> Bias.TRANSITION_BUT_UNLIKELY
            else -> Bias.TRANSITION
        }

        val before = verdict.state
        val frame = Frame(now, game, ad, loud)
        if (strong) { verdict.add(frame, effBias); verdict.forceAd(now) } else verdict.add(frame, effBias)
        val heard = verdict.state
        val pAd = if (game + ad > 0.05) ad / (game + ad) else null

        val rec = Rec(now, box, x, audio, speech, latest, pAd)
        history.addLast(rec)
        while (history.isNotEmpty() && now - history.first().t > 150) history.removeFirst()

        // ------------------------------------------------------- learning
        if (learning) {
            if (now < labelUntil) labelRec(rec, labelAsAd)
            if (strong) {
                // A replayed ad: its audio is perfect training data for "commercial".
                history.filter { !it.labeled && it.box == box && now - it.t <= 4 }.forEach { labelRec(it, true) }
            } else if (before != Heard.AD && heard == Heard.AD && learnedP != null) {
                // Learned model is very sure: save this ad's fingerprint for next time.
                val recent = history.filter { now - it.t < 6 }.mapNotNull { it.pAd }
                if (recent.size >= 4 && recent.average() >= 0.85) {
                    val from = latest - (8 * FpParams.FPS).toLong()
                    prints.add(fp.range(from, latest), auto = true, now = System.currentTimeMillis())
                    dirty = true
                }
            }
        }
        if (pendingPrintFrom != null && now >= pendingPrintUntil) finishPendingPrint()
        maybeSave(now)

        val why = when {
            strong -> "Recognized an ad it's heard before"
            recentTransition && heard == Heard.AD -> "Sound changed suddenly"
            voice != null && voice >= 1.0 -> "Announcers talking"
            voice != null && voice <= -1.5 -> "Different voice than the announcers"
            else -> effBias.why?.replaceFirstChar { it.uppercase() }
        }
        lastWhy = why
        val frac = verdict.adFraction(effBias.windowSecs)
        return DetectResult(heard, strong, frac?.let { (it * 100).toInt() }, why, top)
    }

    private fun labelRec(r: Rec, isAd: Boolean) {
        if (r.labeled) return
        r.labeled = true
        model.add(r.x, isAd)
        voices.learn(r.audio, r.speech, isGame = !isAd)
        dirty = true
    }

    private fun finishPendingPrint() {
        val from = pendingPrintFrom ?: return
        pendingPrintFrom = null
        prints.add(fp.range(from, fp.latestFrame), auto = false, now = System.currentTimeMillis())
        dirty = true
    }

    /** Call right after switching games: the next audio is a different game. */
    fun onSwitch() {
        if (pendingPrintFrom != null) finishPendingPrint()
        labelUntil = 0.0
        verdict.reset()
        prevMatch = null
    }

    /** "It's the game" / "It's an ad": label the last 8 s and the next 12 s. */
    fun label(isAd: Boolean, now: Double) {
        history.filter { now - it.t <= 8 }.forEach { labelRec(it, isAd) }
        labelAsAd = isAd
        labelUntil = now + 12
        if (isAd) {
            pendingPrintFrom = fp.latestFrame - (8 * FpParams.FPS).toLong()
            pendingPrintUntil = now + 12
        } else {
            pendingPrintFrom = null
            // If a fingerprint just fired, it was wrong: forget that one.
            lastMatch?.let { (seg, t) -> if (now - t < 30) prints.remove(seg) }
            lastMatch = null
            verdict.reset()
        }
        dirty = true
    }

    /**
     * Learn game audio from live plays: any stretch where plays came less than
     * 50 s apart had no break in it. TV streams run 10-60 s behind real time,
     * so only the part that is game audio for any delay in that range is used.
     */
    fun observePlays(box: Int, plays: List<Play>, now: Double) {
        val exact = plays.filter { it.seq >= 0 }.sortedBy { it.t }
        if (exact.size < 2) return
        var start = exact.last().t
        for (i in exact.size - 1 downTo 1) {
            if (exact[i].t - exact[i - 1].t > 50) break
            start = exact[i - 1].t
        }
        val end = exact.last().t
        val from = start + 60
        val to = minOf(now, end + 10)
        if (to <= from) return
        history.filter { !it.labeled && it.box == box && it.t in from..to }.forEach { labelRec(it, false) }
    }

    fun forgetAll() {
        model.clear(); prints.clear(); voices.clear()
        history.forEach { it.labeled = false }
        save()
    }
}
