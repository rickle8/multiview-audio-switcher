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
            .setMaxResults(-1) // every sound type, not just the top few
            .build(),
    )
    private var record: AudioRecord? = null
    private val buffer = FloatArray(SAMPLE_RATE)
    private val names = Array(Features.N_YAMNET) { "" }

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

    /** Throw away buffered audio (right after switching games). */
    fun drain() {
        val rec = record ?: return
        val scratch = FloatArray(SAMPLE_RATE)
        while (rec.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING) == scratch.size) Unit
    }

    /** Blocks ~1 second; returns the raw audio and YAMNet's score for every sound type. */
    fun nextSecond(): MicSecond? {
        val rec = record ?: return null
        var filled = 0
        while (filled < buffer.size) {
            val n = rec.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
            if (n <= 0) return null
            filled += n
        }
        val audio = AudioData.create(
            AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(SAMPLE_RATE.toFloat()).build(),
            buffer.size,
        )
        audio.load(buffer)
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
        return MicSecond(buffer.copyOf(), totals, names.copyOf())
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
