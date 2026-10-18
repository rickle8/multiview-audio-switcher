package com.rickle8.mvswitch.camera

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rickle8.mvswitch.core.GrayFrame
import com.rickle8.mvswitch.core.boxRects
import com.rickle8.mvswitch.core.warpBoxFromRaw
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks closely at each multiview box, one at a time in turn so the phone keeps up: straightens it
 * out of the full-resolution camera picture, looks for yard lines on the field ([FieldLines]), and
 * reads its text (score bugs: teams, score, quarter, clock) with the phone's built-in text
 * recognition. Works offline, free.
 *
 * It must never take the app down. A missing library or running out of memory turns it
 * off for this run; a read that fails now and then is just skipped (it stops after many
 * in a row). And since a crash inside the text recognition library itself can't be
 * caught, a marker file is left while it hasn't read a box yet: if the app died with the
 * marker still there, text reading stays off for this version of the app (the rest of
 * camera mode keeps working). A new version tries again.
 */
class TileOcr(
    filesDir: File,
    private val version: String,
    private val onText: (box: Int, lines: List<com.rickle8.mvswitch.core.TileLine>) -> Unit,
    private val onError: (String) -> Unit,
    /** Yard lines found in a box (looked for even when text reading is off). */
    private val onLines: (box: Int, found: com.rickle8.mvswitch.core.FieldLines.Found) -> Unit = { _, _ -> },
) {
    private val trying = File(filesDir, "ocr_trying")
    private val disabled = File(filesDir, "ocr_off")
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var dead = false
    @Volatile private var closed = false
    private var recognizer: TextRecognizer? = null
    private var proven = false
    private var next = 0
    private var failsInARow = 0

    init {
        runCatching {
            if (trying.exists()) {
                // Last time it started reading and the app died before it got a single box read.
                trying.delete(); disabled.writeText(version)
            }
            if (disabled.exists()) {
                if (disabled.readText().trim() == version) {
                    dead = true
                    onError("text reading is off on this phone (it crashed the app before)")
                } else disabled.delete() // an older version's problem: try again
            }
        }
    }

    /** True if it's free to take another box right now (its last buffer is free to reuse). */
    fun ready(): Boolean = !closed && !busy.get()

    /**
     * Look at the next box in this full-resolution camera buffer ([raw], turned [deg]), given the
     * grid's corners (fractions of the upright picture), the layout, and each box's field-green grid.
     */
    fun submit(raw: GrayFrame, deg: Int, corners: List<Pair<Double, Double>>, layout: Int,
               greens: Map<Int, BooleanArray>? = null) {
        if (closed || !busy.compareAndSet(false, true)) return
        try {
            executor.execute { readOne(raw, deg, corners, layout, greens) }
        } catch (t: Throwable) {
            busy.set(false)
        }
    }

    private fun readOne(raw: GrayFrame, deg: Int, corners: List<Pair<Double, Double>>, layout: Int,
                        greens: Map<Int, BooleanArray>?) {
        var read: Pair<Int, List<com.rickle8.mvswitch.core.TileLine>>? = null
        var lines: Pair<Int, com.rickle8.mvswitch.core.FieldLines.Found>? = null
        try {
            if (closed) return
            if (corners.size < 4) return
            val rects = boxRects(layout).toSortedMap()
            if (rects.isEmpty()) return
            val boxes = rects.keys.toList()
            val box = boxes[next % boxes.size]
            next++
            // 960 x 540 per box, 1280 x 720 from a 4K camera: score bug lettering big enough to read.
            val big = maxOf(raw.width, raw.height) >= 3000
            val w = if (big) 1280 else 960; val h = if (big) 720 else 540
            val px = warpBoxFromRaw(raw, deg, corners, rects.getValue(box), w, h)
            greens?.get(box)?.let { g ->
                runCatching { lines = box to com.rickle8.mvswitch.core.FieldLines.find(px, w, h, g) }
            }
            if (!dead) readText(box, px, w, h)?.let { read = it }
        } catch (t: Throwable) {
            runCatching { onError("box look: ${t.message ?: t.javaClass.simpleName}") }
        } finally {
            busy.set(false)
        }
        // What it means is decided elsewhere; a problem there isn't a reading problem.
        lines?.let { (box, f) -> runCatching { onLines(box, f) } }
        read?.let { (box, l) -> runCatching { onText(box, l) } }
    }

    /** The box's text, or null when this read failed (now and then is fine; many in a row turns it off). */
    private fun readText(box: Int, px: ByteArray, w: Int, h: Int): Pair<Int, List<com.rickle8.mvswitch.core.TileLine>>? {
        try {
            // As NV21: the gray picture plus neutral color.
            val nv21 = ByteArray(w * h * 3 / 2)
            System.arraycopy(px, 0, nv21, 0, w * h)
            java.util.Arrays.fill(nv21, w * h, nv21.size, 128.toByte())
            val image = InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21)
            // The marker is only there for the moment the library is working on it.
            if (!proven) runCatching { trying.writeText("1") }
            val rec = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            val result = Tasks.await(rec.process(image), 5, TimeUnit.SECONDS)
            if (!proven) { proven = true; runCatching { trying.delete() } }
            failsInARow = 0
            // Each line with where it sits in the box (a ticker runs along the very bottom).
            return box to result.textBlocks.flatMap { it.lines }.map { l ->
                val r = l.boundingBox
                com.rickle8.mvswitch.core.TileLine(l.text,
                    (r?.top ?: 0) / h.toDouble(), (r?.bottom ?: h / 2) / h.toDouble(),
                    (r?.left ?: 0) / w.toDouble(), (r?.right ?: w) / w.toDouble())
            }
        } catch (t: Throwable) {
            if (!proven) runCatching { trying.delete() }
            failsInARow++
            // Missing library or out of memory: stop. A read that failed now and then: skip it.
            if (t is LinkageError || t is OutOfMemoryError || failsInARow >= 20) {
                dead = true
                runCatching { onError("text reading stopped: ${t.message ?: t.javaClass.simpleName}") }
            }
            return null
        }
    }

    fun close() {
        closed = true
        runCatching {
            executor.execute {
                runCatching { recognizer?.close() }
                recognizer = null
                if (!proven) runCatching { trying.delete() }
            }
        }
        executor.shutdown()
    }
}
