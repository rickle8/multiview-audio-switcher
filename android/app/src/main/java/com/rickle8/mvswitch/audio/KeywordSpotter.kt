package com.rickle8.mvswitch.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import com.rickle8.mvswitch.core.AD_TERMS
import java.io.File

val DEFAULT_CUE_PHRASES = listOf(
    "we'll be right back", "we will be right back", "be right back",
    "stay with us", "stick around", "don't go anywhere",
    "when we come back", "when we return", "back after this", "after these messages",
    "after the break", "brought to you by", "presented by",
)

/** Copies the bundled speech model out of the APK once (the recognizer needs real files). */
object SpeechModel {
    private const val ASSET_DIR = "vosk-model"
    private const val VERSION = "small-en-us-0.15"

    fun dir(context: Context) = File(context.filesDir, "vosk-$VERSION")

    fun bundled(context: Context): Boolean =
        runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    @Synchronized
    fun install(context: Context): File? {
        val out = dir(context)
        val done = File(out, ".installed")
        if (done.exists()) return out
        if (!bundled(context)) return null
        out.deleteRecursively()
        copy(context, ASSET_DIR, out)
        done.writeText(VERSION)
        context.filesDir.listFiles()?.filter { it.name.startsWith("vosk-") && it != out }
            ?.forEach { it.deleteRecursively() } // older versions
        return out
    }

    private fun copy(context: Context, asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copy(context, "$asset/$c", File(dest, c))
        }
    }
}

/** What was said this second: a break phrase, game talk, ad talk. */
data class SpeechHeard(
    val cue: String?, val gameWords: List<String>, val adWords: List<String>,
    /** Everything said (finished sentences), word for word, for working out which game this is. */
    val text: String? = null,
)

/**
 * Offline speech recognition (Vosk) restricted to a short list: phrases
 * announcers use right before a break, game talk (player and team names,
 * "first down"), and things only commercials say ("terms apply"). Everything
 * else is ignored.
 */
class KeywordSpotter(modelDir: File, phrases: List<String>) : SpeechSource {
    private val phrases = phrases.map { normalize(it) }.filter { it.isNotBlank() }.distinct()
        .sortedByDescending { it.length }
    private val adTerms = AD_TERMS.map { normalize(it) }.distinct()
    private var gameTerms: List<String> = emptyList()
    private val model: Model
    private var recognizer: Recognizer
    private val shorts = ShortArray(16000)
    @Volatile override var lastText: String? = null
        private set

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(modelDir.absolutePath)
        recognizer = build()
    }

    private fun build(): Recognizer {
        val grammar = JSONArray((phrases + gameTerms + adTerms).distinct() + "[unk]").toString()
        return Recognizer(model, 16000f, grammar).apply { setWords(true) }
    }

    /** Game words to listen for (terms for the sports on, plus player and team names). */
    override fun setGameWords(words: Collection<String>) {
        // In the order given (most useful first), so a long list never drops a whole game.
        val next = words.map { normalize(it) }.filter { it.length >= 3 && it !in phrases }.distinct().take(500)
        if (next.toSet() == gameTerms.toSet()) return
        gameTerms = next
        val old = recognizer
        recognizer = build()
        runCatching { old.close() }
        lastPartial = null; reported = false; partialTerms = emptySet(); reportedTerms.clear()
    }

    private fun normalize(s: String) = s.lowercase().replace("’", "'")
        .replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private var lastPartial: String? = null
    private var reported = false
    private var partialTerms: Set<String> = emptySet()
    private val reportedTerms = HashSet<String>()

    private fun termsIn(text: String, terms: List<String>) = terms.filter { text.contains(" $it ") }

    /**
     * Feed one second of audio. TV audio rarely pauses, so sentences can run long
     * before the recognizer finishes them: in-progress text counts once it holds
     * for 2 seconds.
     */
    override fun accept(samples: FloatArray): SpeechHeard? {
        val n = minOf(samples.size, shorts.size)
        for (i in 0 until n) shorts[i] = (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        if (recognizer.acceptWaveForm(shorts, n)) {
            val already = reported
            val done = HashSet(reportedTerms)
            reported = false
            lastPartial = null
            partialTerms = emptySet()
            reportedTerms.clear()
            val json = runCatching { JSONObject(recognizer.result) }.getOrNull() ?: return null
            json.optString("text").takeIf { it.isNotBlank() && it != "[unk]" }?.let { lastText = it }
            val cue = if (already) null else confident(json, phrases, 0.7).firstOrNull()
            val game = (confident(json, gameTerms.filter { ' ' in it }, 0.75) +
                confident(json, gameTerms.filter { ' ' !in it }, 0.85)).filter { it !in done }
            val ad = confident(json, adTerms, 0.75).filter { it !in done }
            val said = json.optString("text").takeIf { it.isNotBlank() && it != "[unk]" }
            return if (cue == null && game.isEmpty() && ad.isEmpty() && said == null) null else SpeechHeard(cue, game, ad, said)
        }
        val partial = runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrNull()
        val text = " " + normalize(partial.orEmpty()) + " "
        var cue: String? = null
        if (!reported) {
            val found = phrases.firstOrNull { text.contains(" $it ") }
            val stable = found != null && found == lastPartial
            lastPartial = found
            if (stable) { reported = true; cue = found }
        }
        // In-progress text has no confidence, so only multi-word terms count from it (names wait for the final).
        val terms = (termsIn(text, gameTerms.filter { ' ' in it }) + termsIn(text, adTerms.filter { ' ' in it })).toSet()
        val steady = terms.intersect(partialTerms).filter { it !in reportedTerms }
        partialTerms = terms
        reportedTerms += steady
        val game = steady.filter { it in gameTerms }
        val ad = steady.filter { it in adTerms }
        return if (cue == null && game.isEmpty() && ad.isEmpty()) null else SpeechHeard(cue, game, ad)
    }

    /** Terms in a finished result whose words the recognizer was sure of. */
    private fun confident(j: JSONObject, terms: List<String>, min: Double): List<String> {
        val text = " " + normalize(j.optString("text")) + " "
        val words = j.optJSONArray("result")
        return terms.filter { p ->
            if (!text.contains(" $p ")) return@filter false
            val wanted = p.split(" ").toMutableList()
            var sum = 0.0
            var count = 0
            if (words != null) for (i in 0 until words.length()) {
                val w = words.optJSONObject(i) ?: continue
                if (wanted.remove(normalize(w.optString("word")))) {
                    sum += w.optDouble("conf", 0.0); count++
                }
            }
            count > 0 && sum / count >= min
        }
    }

    override fun close() {
        runCatching { recognizer.close() }
        runCatching { model.close() }
    }
}
