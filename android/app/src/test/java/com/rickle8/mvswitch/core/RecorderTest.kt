package com.rickle8.mvswitch.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

class RecorderTest {
    @Test fun recordsASessionAndZipsIt() {
        val root = File(System.getProperty("java.io.tmpdir"), "rec-test-${System.nanoTime()}").apply { mkdirs() }
        val r = Recorder(root)
        val t0 = 1_760_000_000_000L
        r.start(JSONObject().put("version", "test").put("layout", 4), t0)
        assertTrue(r.recording)
        // 2 minutes: the game, a commercial on box 1 from 40 s, a switch at 50 s.
        for (i in 0 until 120) {
            val ms = t0 + i * 1000L
            val box = if (i < 50) 1 else 2
            val ad = if (i in 40..49) 80 else 15
            r.second(JSONObject().put("t", i.toDouble()).put("box", box).put("heard", if (ad > 60 && i >= 46) "AD" else "GAME")
                .put("adPct", ad).put("why", "").put("call", "").put("bias", "").put("boxes", org.json.JSONArray()), ms)
            if (i == 50) r.event("switch", JSONObject().put("from", 1).put("to", 2).put("why", "Commercial on box 1"), ms, mark = true)
            if (i == 55) r.event("feedback", JSONObject().put("text", "Too slow by about 10 s: box 1 → 2"), ms, mark = true)
            if (r.wantFrame(wallMs = ms)) r.frame(byteArrayOf(1, 2, 3), ms)
        }
        // Pictures every 5 s, every second near the switch.
        assertTrue("${r.frames} pictures", r.frames in 40..60)
        val s = r.sessions().single()
        assertEquals(120, s.third)
        val out = java.io.ByteArrayOutputStream()
        assertTrue(r.zip(s.first, out))
        val names = ArrayList<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertTrue(names.any { it.endsWith("seconds.jsonl") } && names.any { it.endsWith("events.jsonl") } && names.any { it.contains("frames/") })
        r.stop()
        File(System.getProperty("java.io.tmpdir"), "sample-recording.zip").writeBytes(out.toByteArray())
        assertFalse(r.recording)
        assertTrue(r.delete(s.first))
        root.deleteRecursively()
    }

    @Test fun keepsOnlyTheNewestSessions() {
        val root = File(System.getProperty("java.io.tmpdir"), "rec-prune-${System.nanoTime()}").apply { mkdirs() }
        val r = Recorder(root, maxSessions = 3)
        for (d in 0 until 5) r.start(JSONObject(), 1_760_000_000_000L + d * 86_400_000L)
        r.stop()
        assertEquals(3, r.sessions().size)
        root.deleteRecursively()
    }
}
