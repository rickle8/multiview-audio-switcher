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
import com.rickle8.mvswitch.core.Frame
import com.rickle8.mvswitch.core.features
import com.rickle8.mvswitch.core.heuristicScores
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens through the phone's microphone and scores each second of audio with
 * Google's YAMNet sound classifier (bundled in the app, runs fully offline).
 */
class MicClassifier(context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
    }

    private val classifier: AudioClassifier = AudioClassifier.createFromOptions(
        context,
        AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .build(),
    )
    private var record: AudioRecord? = null
    private val buffer = FloatArray(SAMPLE_RATE)
    var lastTop: List<Pair<String, Float>> = emptyList()
        private set

    @SuppressLint("MissingPermission") // checked by the UI before the service starts
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf, SAMPLE_RATE * 4 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't open the microphone")
        }
        rec.startRecording()
        record = rec
    }

    /** Throw away buffered audio (right after switching games). */
    fun drain() {
        val rec = record ?: return
        val scratch = FloatArray(SAMPLE_RATE)
        rec.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING)
    }

    /** Blocks ~1 second, returns one scored frame. */
    fun nextFrame(now: Double): Frame? {
        val rec = record ?: return null
        var filled = 0
        while (filled < buffer.size) {
            val n = rec.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
            if (n <= 0) return null
            filled += n
        }
        var sum = 0.0
        for (s in buffer) sum += s * s
        val rms = sqrt(sum / buffer.size) + 1e-9
        val audio = AudioData.create(
            AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(SAMPLE_RATE.toFloat()).build(),
            buffer.size,
        )
        audio.load(buffer)
        val result = classifier.classify(audio)
        val totals = HashMap<String, Float>()
        var count = 0
        for (cr in result.classificationResults()) {
            for (cls in cr.classifications()) {
                count++
                for (cat in cls.categories()) {
                    totals[cat.categoryName()] = (totals[cat.categoryName()] ?: 0f) + cat.score()
                }
            }
        }
        if (count == 0) return null
        val mean = totals.mapValues { it.value / count }
        lastTop = mean.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
        val loud = 20 * log10(rms)
        val (game, ad) = heuristicScores(mean)
        return Frame(now, game, ad, loud, features(mean, loud))
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
