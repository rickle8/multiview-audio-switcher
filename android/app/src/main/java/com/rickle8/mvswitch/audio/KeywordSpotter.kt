package com.rickle8.mvswitch.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
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

/**
 * Offline speech recognition (Vosk) restricted to a short list of phrases
 * announcers use right before a break. Everything else is ignored.
 */
class KeywordSpotter(modelDir: File, phrases: List<String>) : AutoCloseable {
    private val phrases = phrases.map { normalize(it) }.filter { it.isNotBlank() }.distinct()
        .sortedByDescending { it.length }
    private val model: Model
    private val recognizer: Recognizer
    private val shorts = ShortArray(16000)

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(modelDir.absolutePath)
        val grammar = JSONArray(this.phrases + "[unk]").toString()
        recognizer = Recognizer(model, 16000f, grammar).apply { setWords(true) }
    }

    private fun normalize(s: String) = s.lowercase().replace("’", "'")
        .replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private var lastPartial: String? = null
    private var reported = false

    /**
     * Feed one second of audio; returns a phrase if one was just said clearly.
     * TV audio rarely pauses, so sentences can run long before the recognizer
     * finishes them. In-progress text counts once a phrase holds for 2 seconds.
     */
    fun accept(samples: FloatArray): String? {
        val n = minOf(samples.size, shorts.size)
        for (i in 0 until n) shorts[i] = (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        if (recognizer.acceptWaveForm(shorts, n)) {
            val already = reported
            reported = false
            lastPartial = null
            val hit = match(recognizer.result)
            return if (already) null else hit
        }
        if (reported) return null
        val partial = runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrNull()
        val text = " " + normalize(partial.orEmpty()) + " "
        val found = phrases.firstOrNull { text.contains(" $it ") }
        val stable = found != null && found == lastPartial
        lastPartial = found
        if (stable) {
            reported = true
            return found
        }
        return null
    }

    private fun match(json: String): String? {
        val j = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val text = " " + normalize(j.optString("text")) + " "
        val words = j.optJSONArray("result")
        for (p in phrases) {
            if (!text.contains(" $p ")) continue
            // Average confidence of the words that make up the phrase.
            val wanted = p.split(" ").toMutableList()
            var sum = 0.0
            var count = 0
            if (words != null) for (i in 0 until words.length()) {
                val w = words.optJSONObject(i) ?: continue
                if (wanted.remove(normalize(w.optString("word")))) {
                    sum += w.optDouble("conf", 0.0); count++
                }
            }
            val conf = if (count > 0) sum / count else 0.0
            if (conf >= 0.7) return p
        }
        return null
    }

    override fun close() {
        runCatching { recognizer.close() }
        runCatching { model.close() }
    }
}
