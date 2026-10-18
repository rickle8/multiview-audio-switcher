package com.rickle8.mvswitch.core

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records a game session for review afterwards: what it heard, saw and decided every second, every
 * event (switches, your taps and feedback, ESPN's plays, the activity log), and small pictures of the
 * TV, more often around switches and your taps. Never the sound itself, only the app's scores for it.
 *
 * One folder per session under [root]: meta.json, seconds.jsonl (a line a second), events.jsonl,
 * frames/<ms>.jpg. Old sessions are deleted past [maxSessions] or [maxBytes] in all.
 */
class Recorder(
    private val root: File,
    private val maxSessions: Int = 8,
    private val maxBytes: Long = 600L * 1024 * 1024,
    /** Seconds between pictures normally, and near a switch or a tap. */
    private val frameEvery: Double = 5.0,
    private val frameEveryNear: Double = 1.0,
    private val nearSecs: Double = 25.0,
) {
    private var dir: File? = null
    private var seconds: Writer? = null
    private var events: Writer? = null
    private var lastFrameAt = -1e9
    private var markAt = -1e9
    private var startedAt = 0L
    var frames = 0
        private set

    val recording: Boolean @Synchronized get() = dir != null
    val sessionName: String? @Synchronized get() = dir?.name

    /** Starts a new session (ends the one running). [meta]: the app version, settings and games. */
    @Synchronized
    fun start(meta: JSONObject, wallMs: Long = System.currentTimeMillis()) {
        stop()
        val name = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(wallMs))
        var d = File(root, name); var k = 2
        while (d.exists()) { d = File(root, "${name}_${k++}") }
        if (!File(d, "frames").mkdirs()) return
        File(d, "meta.json").writeText(meta.put("started", wallMs).toString(1))
        seconds = File(d, "seconds.jsonl").bufferedWriter()
        events = File(d, "events.jsonl").bufferedWriter()
        dir = d; startedAt = wallMs; frames = 0; lastFrameAt = -1e9
        prune()
    }

    @Synchronized
    fun stop() {
        runCatching { seconds?.close() }; runCatching { events?.close() }
        seconds = null; events = null; dir = null
    }

    /** One second's record (any JSON object; "ms" is added). */
    @Synchronized
    fun second(j: JSONObject, wallMs: Long = System.currentTimeMillis()) {
        val w = seconds ?: return
        runCatching { w.write(j.put("ms", wallMs).toString()); w.write("\n"); if (wallMs / 1000 % 10 == 0L) w.flush() }
    }

    /** Something that happened: [kind] ("switch", "tap", "feedback", "note", "play", "log"), [data]. */
    @Synchronized
    fun event(kind: String, data: JSONObject = JSONObject(), wallMs: Long = System.currentTimeMillis(), mark: Boolean = false) {
        val w = events ?: return
        runCatching { w.write(data.put("ms", wallMs).put("kind", kind).toString()); w.write("\n"); w.flush() }
        if (mark) markAt = wallMs / 1000.0
    }

    /** A picture's worth recording now ([t] in seconds)? */
    @Synchronized
    fun wantFrame(t: Double): Boolean {
        if (dir == null) return false
        val every = if (t - markAt < nearSecs) frameEveryNear else frameEvery
        return t - lastFrameAt >= every
    }

    /** A picture of the TV (JPEG bytes) taken at [wallMs]. */
    @Synchronized
    fun frame(jpeg: ByteArray, wallMs: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        lastFrameAt = wallMs / 1000.0
        runCatching { File(d, "frames/$wallMs.jpg").writeBytes(jpeg); frames++ }
    }

    /** Sessions kept, newest first: name, size in bytes, seconds recorded. */
    @Synchronized
    fun sessions(): List<Triple<String, Long, Int>> = (root.listFiles()?.filter { it.isDirectory }.orEmpty())
        .sortedByDescending { it.name }
        .map { d ->
            runCatching { seconds?.flush() }
            Triple(d.name, d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                File(d, "seconds.jsonl").takeIf { it.exists() }?.useLines { it.count() } ?: 0)
        }

    /** Writes session [name] as a zip to [out]; false if there's no such session. */
    fun zip(name: String, out: OutputStream): Boolean {
        val d = synchronized(this) {
            runCatching { seconds?.flush(); events?.flush() }
            File(root, name).takeIf { it.isDirectory && it.parentFile == root }
        } ?: return false
        ZipOutputStream(out.buffered()).use { z ->
            for (f in d.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                z.putNextEntry(ZipEntry(name + "/" + f.relativeTo(d).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return true
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val d = File(root, name).takeIf { it.isDirectory && it.parentFile == root } ?: return false
        if (d == dir) stop()
        return d.deleteRecursively()
    }

    private fun prune() {
        val all = root.listFiles()?.filter { it.isDirectory }.orEmpty().sortedByDescending { it.name }
        var total = 0L
        for ((i, d) in all.withIndex()) {
            if (d == dir) continue
            total += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (i >= maxSessions || total > maxBytes) d.deleteRecursively()
        }
    }
}
