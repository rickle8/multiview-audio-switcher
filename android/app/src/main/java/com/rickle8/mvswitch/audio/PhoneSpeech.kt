package com.rickle8.mvswitch.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.rickle8.mvswitch.core.AD_TERMS
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Something that turns mic audio into the words the app cares about. */
interface SpeechSource : AutoCloseable {
    /** One second of 16 kHz mono audio in; what was said since last time out. */
    fun accept(samples: FloatArray): SpeechHeard?
    fun setGameWords(words: Collection<String>)
    /** What it heard most recently, word for word (for the screen). */
    val lastText: String?
    /** It stopped working and the app should use something else. */
    val failed: Boolean get() = false
}

/** Finds break phrases, game words and ad words in transcribed text. */
class WordMatcher(phrases: List<String>) {
    val phrases = phrases.map { normalize(it) }.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    private val adTerms = AD_TERMS.map { normalize(it) }.distinct()
    @Volatile var gameTerms: List<String> = emptyList()

    companion object {
        fun normalize(s: String) = s.lowercase().replace("’", "'")
            .replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun find(text: String): Triple<String?, List<String>, List<String>> {
        val t = " " + normalize(text) + " "
        val cue = phrases.firstOrNull { t.contains(" $it ") }
        val game = gameTerms.filter { t.contains(" $it ") }
        val ad = adTerms.filter { t.contains(" $it ") }
        return Triple(cue, game, ad)
    }
}

/**
 * The phone's own speech recognizer (Google's, on the device when available):
 * far more accurate than the small built-in model. The app feeds it the same
 * mic audio it already records, so nothing else grabs the mic, and hints it
 * with the players and teams in tonight's games.
 */
@RequiresApi(33)
class PhoneSpeech private constructor(
    private val context: Context,
    phrases: List<String>,
    private val log: (String) -> Unit,
) : SpeechSource {
    companion object {
        /** Null if this phone can't do it (older than Android 13, or no recognizer). */
        fun create(context: Context, phrases: List<String>, log: (String) -> Unit): PhoneSpeech? {
            if (Build.VERSION.SDK_INT < 33) return null
            if (!SpeechRecognizer.isRecognitionAvailable(context) &&
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
            return runCatching { PhoneSpeech(context, phrases, log).also { it.begin() } }
                .onFailure { log("Phone speech recognition unavailable (${it.message}).") }.getOrNull()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val matcher = WordMatcher(phrases)
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    @Volatile private var closed = false
    @Volatile override var failed = false
        private set
    @Volatile override var lastText: String? = null
        private set
    @Volatile private var lastTextAt = 0L

    // Audio goes to the recognizer through a pipe, written on its own thread so a slow
    // recognizer never holds up the app (old audio is dropped instead).
    private val queue = ArrayBlockingQueue<ByteArray>(6)
    @Volatile private var out: OutputStream? = null
    private var writer: Thread? = null

    // What's been found, collected until the engine asks.
    private val lock = Any()
    private var cue: String? = null
    private val game = LinkedHashSet<String>()
    private val ad = LinkedHashSet<String>()
    /** Terms already reported in the current utterance (partials repeat them). */
    private val reportedNow = HashSet<String>()
    private var cueReportedNow = false
    private var bias: List<String> = emptyList()
    private var errorsInRow = 0
    private var sessions = 0
    private var startedAt = 0L
    private val createdAt = System.currentTimeMillis()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun begin() {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        onMain {
            try {
                onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                recognizer = (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)).apply { setRecognitionListener(listener) }
            } catch (e: Throwable) { error = e }
            ready.countDown()
        }
        if (!ready.await(5, TimeUnit.SECONDS)) throw IllegalStateException("speech recognizer didn't start")
        error?.let { throw it }
        writer = Thread({ writeLoop() }, "speech-audio").apply { isDaemon = true; start() }
        onMain { startSession() }
        log("Listening to the announcers with the phone's speech recognition" + if (onDevice) " (on the phone)." else ".")
    }

    private fun writeLoop() {
        while (!closed) {
            val chunk = runCatching { queue.poll(500, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val o = out ?: continue
            try { o.write(chunk); o.flush() } catch (_: Exception) { /* session ended; a new pipe is coming */ }
        }
    }

    private fun startSession() {
        if (closed) return
        val r = recognizer ?: return
        runCatching { out?.close() }
        val pipe = ParcelFileDescriptor.createPipe()
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // Keep going utterance after utterance until the audio stops (TV audio rarely pauses).
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            if (bias.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias.take(400)))
        }
        sessions++
        startedAt = System.currentTimeMillis()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            log("Speech recognition didn't start (${e.message}).")
            retryLater()
        }
        runCatching { pipe[0].close() } // the recognizer has its own copy
    }

    private fun retryLater() {
        errorsInRow++
        if (errorsInRow >= 8) {
            failed = true
            log("The phone's speech recognition keeps failing. Using the built-in one instead.")
            return
        }
        main.postDelayed({ startSession() }, (1000L * errorsInRow).coerceAtMost(8000))
    }

    private fun handle(text: String, final: Boolean) {
        if (text.isBlank()) return
        lastText = text.trim().takeLast(120)
        lastTextAt = System.currentTimeMillis()
        val (c, g, a) = matcher.find(text)
        synchronized(lock) {
            if (c != null && !cueReportedNow) { cue = c; cueReportedNow = true }
            g.filter { reportedNow.add(it) }.forEach { game += it }
            a.filter { reportedNow.add("ad:$it") }.forEach { ad += it }
            if (final) { reportedNow.clear(); cueReportedNow = false }
        }
    }

    private fun texts(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) = handle(texts(partialResults), final = false)
        override fun onSegmentResults(segmentResults: Bundle) {
            errorsInRow = 0
            handle(texts(segmentResults), final = true)
        }
        override fun onEndOfSegmentedSession() { if (!closed) main.postDelayed({ startSession() }, 200) }
        override fun onResults(results: Bundle?) {
            errorsInRow = 0
            handle(texts(results), final = true)
            if (!closed) main.postDelayed({ startSession() }, 200)
        }
        override fun onError(error: Int) {
            if (closed) return
            // No speech / no match just mean a quiet stretch: start again right away.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                main.postDelayed({ startSession() }, 200)
                return
            }
            if (errorsInRow == 0) log("Speech recognition hiccup (error $error). Restarting it.")
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) runCatching { recognizer?.cancel() }
            retryLater()
        }
    }

    override fun accept(samples: FloatArray): SpeechHeard? {
        if (!closed && !failed) {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                bytes[2 * i] = (v and 0xFF).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }
            // Running a while with no words at all while audio flows: the recognizer isn't working this way.
            if (lastTextAt == 0L && System.currentTimeMillis() - createdAt > 150_000) {
                failed = true
                log("The phone's speech recognition isn't hearing anything. Using the built-in one instead.")
            }
        }
        synchronized(lock) {
            if (cue == null && game.isEmpty() && ad.isEmpty()) return null
            val heard = SpeechHeard(cue, game.toList(), ad.toList())
            cue = null; game.clear(); ad.clear()
            return heard
        }
    }

    override fun setGameWords(words: Collection<String>) {
        val next = words.map { WordMatcher.normalize(it) }.filter { it.length >= 3 }.distinct()
        if (next.toSet() == matcher.gameTerms.toSet()) return
        matcher.gameTerms = next
        bias = next
    }

    override fun close() {
        closed = true
        runCatching { out?.close() }
        onMain {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }
}
