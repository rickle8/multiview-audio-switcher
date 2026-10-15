package com.rickle8.mvswitch.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import android.media.AudioManager
import com.rickle8.mvswitch.core.Features
import com.rickle8.mvswitch.core.MicSecond

/**
 * Listens through the phone's microphone and scores each second of audio with
 * Google's YAMNet sound classifier (bundled in the app, runs fully offline).
 */
class MicClassifier(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
    }

    private val classifier: AudioClassifier = AudioClassifier.createFromOptions(
        context,
        AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            // No max results: unset means every sound type. (Setting -1 made MediaPipe refuse to start.)
            .build(),
    )
    private var record: AudioRecord? = null
    private val buffer = FloatArray(SAMPLE_RATE)
    private val names = Array(Features.N_YAMNET) { "" }
    private var level = 0.02 // slow-moving average loudness (RMS)

    @SuppressLint("MissingPermission") // checked by the UI before the service starts
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        // Raw mic audio if the phone offers it: no noise suppression eating the
        // crowd noise, no auto-gain pumping the volume. Otherwise the voice
        // recognition source, which also skips most processing.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = AudioRecord(
            source,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf, SAMPLE_RATE * 4 * 4), // 4 s of slack
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't open the microphone")
        }
        rec.startRecording()
        record = rec
    }

    @Volatile private var drainWanted = false

    /**
     * Throw away buffered audio (right after switching games). Safe from any
     * thread: the reading thread does the actual draining, since AudioRecord
     * can't be read from two threads at once.
     */
    fun drain() { drainWanted = true }

    private fun drainNow(rec: AudioRecord) {
        val scratch = FloatArray(SAMPLE_RATE)
        while (rec.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING) == scratch.size) Unit
    }

    /** Blocks ~1 second; returns the raw audio and YAMNet's score for every sound type. Null if the mic failed. */
    fun nextSecond(): MicSecond? {
        val rec = record ?: return null
        if (drainWanted) { drainWanted = false; drainNow(rec) }
        var filled = 0
        while (filled < buffer.size) {
            val n = rec.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
            if (n <= 0) return null
            filled += n
        }
        // Raw mic audio can be quiet. Boost a copy toward a steady level for the sound
        // classifier and speech recognition (slowly, so real loud/quiet changes still show).
        var sum = 0.0
        for (v in buffer) sum += v * v
        val rms = kotlin.math.sqrt(sum / buffer.size)
        if (rms > 1e-4) level = level * 0.9 + rms * 0.1
        val gain = (0.08 / level).coerceIn(1.0, 20.0).toFloat()
        val boosted = FloatArray(buffer.size) { (buffer[it] * gain).coerceIn(-1f, 1f) }
        val audio = AudioData.create(
            AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(SAMPLE_RATE.toFloat()).build(),
            buffer.size,
        )
        audio.load(boosted)
        val result = classifier.classify(audio)
        val totals = FloatArray(Features.N_YAMNET)
        var count = 0
        for (cr in result.classificationResults()) {
            for (cls in cr.classifications()) {
                count++
                for (cat in cls.categories()) {
                    val i = cat.index()
                    if (i in totals.indices) {
                        totals[i] += cat.score()
                        if (names[i].isEmpty()) names[i] = cat.categoryName()
                    }
                }
            }
        }
        if (count == 0) return null
        for (i in totals.indices) totals[i] /= count
        return MicSecond(buffer.copyOf(), totals, names.copyOf(), boosted)
    }

    fun stop() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    fun close() {
        stop()
        classifier.close()
    }
}
