package com.rickle8.mvswitch.camera

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rickle8.mvswitch.core.GrayFrame
import com.rickle8.mvswitch.core.boxRects
import com.rickle8.mvswitch.core.warpBoxFromRaw
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the text in each multiview box (score bugs: teams, score, quarter, clock) with
 * the phone's built-in text recognition. Works offline, free. One box at a time, in
 * turn, so the phone keeps up.
 */
class TileOcr(private val onText: (box: Int, text: String) -> Unit, private val onError: (String) -> Unit) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var next = 0

    /** True if it's free to take another box right now. */
    fun ready(): Boolean = !busy.get()

    /**
     * Read the next box from this full-resolution camera buffer ([raw], turned [deg]),
     * given the grid's corners (fractions of the upright picture) and the layout.
     */
    fun submit(raw: GrayFrame, deg: Int, corners: List<Pair<Double, Double>>, layout: Int) {
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                val rects = boxRects(layout).toSortedMap()
                val boxes = rects.keys.toList()
                val box = boxes[next % boxes.size]
                next++
                // 960 x 540 per box: score bug lettering ends up big enough to read.
                val w = 960; val h = 540
                val px = warpBoxFromRaw(raw, deg, corners, rects.getValue(box), w, h)
                // As NV21: the gray picture plus neutral color.
                val nv21 = ByteArray(w * h * 3 / 2)
                System.arraycopy(px, 0, nv21, 0, w * h)
                java.util.Arrays.fill(nv21, w * h, nv21.size, 128.toByte())
                val image = InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21)
                val result = Tasks.await(recognizer.process(image), 5, TimeUnit.SECONDS)
                onText(box, result.text)
            } catch (e: Exception) {
                onError("text reading: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy.set(false)
            }
        }
    }

    fun close() {
        runCatching { recognizer.close() }
        executor.shutdown()
    }
}
