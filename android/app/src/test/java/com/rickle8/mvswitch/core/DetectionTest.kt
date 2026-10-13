package com.rickle8.mvswitch.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class DetectionTest {
    // ------------------------------------------------------------ synthetic audio
    /** Commercial-like audio: overlapping tones of random pitch and length, some percussive hits. */
    private fun jingle(seed: Long, secs: Int): FloatArray {
        val r = java.util.Random(seed)
        val n = Dsp.SR * secs
        val x = FloatArray(n)
        var start = 0
        while (start < n) {
            val len = (Dsp.SR * (0.15 + r.nextDouble() * 0.45)).toInt()
            val f0 = 120 + r.nextDouble() * 1380
            val decay = 1 + r.nextDouble() * 5
            val phase = r.nextDouble() * 6
            val hit = r.nextDouble() < 0.3
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val t = i.toDouble() / Dsp.SR
                val env = exp(-t * decay)
                var v = 0.0
                for (h in 1..3) v += env * sin(2 * PI * f0 * h * t + phase) / h
                if (hit) v += r.nextGaussian() * 0.3 * exp(-t * 30)
                x[idx] += (v * 0.3).toFloat()
            }
            start += (len * (0.5 + r.nextDouble() * 0.5)).toInt()
        }
        return x
    }

    /** Same audio as heard across a room: quieter or louder, plus background noise. */
    private fun throughRoom(x: FloatArray, seed: Long, snrDb: Double = 10.0, gain: Float = 0.6f): FloatArray {
        val r = java.util.Random(seed)
        val p = x.sumOf { (it * it).toDouble() } / x.size
        val sd = sqrt(p / Math.pow(10.0, snrDb / 10))
        return FloatArray(x.size) { ((x[it] + r.nextGaussian() * sd) * gain).toFloat() }
    }

    private fun prints(audio: FloatArray): Pair<Fingerprinter, Long> {
        val stft = Stft()
        val fp = Fingerprinter()
        var i = 0
        while (i < audio.size) {
            val chunk = audio.copyOfRange(i, minOf(audio.size, i + Dsp.SR))
            val mags = stft.push(chunk)
            fp.push(mags, stft.nextFrame - mags.size)
            i += Dsp.SR
        }
        return fp to fp.latestFrame
    }

    // ------------------------------------------------------------ fingerprints
    @Test fun recognizesAReplayedAdThroughARoom() {
        val db = AdPrints()
        val ads = (0 until 6).associateWith { jingle(100L + it, 25) }
        for ((id, x) in ads) {
            val (fp, last) = prints(x.copyOfRange(0, Dsp.SR * 20))
            assertNotNull(db.add(fp.range(0, last), auto = false, now = id.toLong()))
        }
        // Same ads, different noise and volume, starting mid-ad, 4 s query.
        for ((id, x) in ads) {
            val heard = throughRoom(x, 1000L + id).copyOfRange(Dsp.SR * (6 + id), Dsp.SR * (12 + id))
            val (fp, last) = prints(heard)
            val m = db.match(fp.range(last - (4 * FpParams.FPS).toLong(), last), 0)!!
            assertTrue("ad $id matched only ${m.count}", m.count >= 14)
        }
        // Ads it never heard should not line up.
        for (s in 0 until 6) {
            val heard = throughRoom(jingle(500L + s, 10), 2000L + s)
            val (fp, last) = prints(heard)
            val m = db.match(fp.range(last - (4 * FpParams.FPS).toLong(), last), 0)
            assertTrue("unknown ad $s matched ${m?.count}", (m?.count ?: 0) < 10 || (m?.spread ?: 0) < 3)
        }
        val bytes = java.io.ByteArrayOutputStream().also { db.save(it) }.toByteArray()
        assertEquals(6, AdPrints().apply { load(bytes.inputStream()) }.size)
    }

    // ------------------------------------------------------------ announcer voices
    private fun voiceSecond(center: Double, seed: Long): SecondAudio {
        val r = java.util.Random(seed)
        val frames = List(31) { FloatArray(Mel.CEPS) { d -> (center + 0.3 * d % 1.0 + r.nextGaussian() * 0.4).toFloat() } }
        return SecondAudio(emptyList(), frames, FloatArray(31), -20.0)
    }

    @Test fun learnsAnnouncerVoices() {
        val v = AnnouncerModel()
        repeat(80) { v.learn(voiceSecond(0.0, it.toLong()), speech = 0.9, isGame = true) }
        assertTrue(v.ready)
        val same = v.evidence(voiceSecond(0.0, 999), 0.9)!!
        val other = v.evidence(voiceSecond(2.5, 998), 0.9)!!
        assertTrue("announcer $same", same > 0)
        assertTrue("other voice $other", other < -1)
        // No speech: no opinion.
        assertEquals(null, v.evidence(voiceSecond(0.0, 997), 0.1))
    }

    // ------------------------------------------------------------ transitions
    private fun shapeSecond(pattern: (Int) -> Double, seed: Long, dipFrames: Int = 0): SecondAudio {
        val r = java.util.Random(seed)
        val mels = List(31) { FloatArray(Mel.BANDS) { b -> (pattern(b) + r.nextGaussian() * 0.05).toFloat() } }
        val energies = FloatArray(31) { if (it < dipFrames) -60f else (-20 + r.nextGaussian()).toFloat() }
        return SecondAudio(mels, emptyList(), energies, -20.0)
    }

    @Test fun spotsDipThenNewSound() {
        val a = { b: Int -> sin(b * 0.4) }
        val b = { k: Int -> 1.5 * kotlin.math.cos(k * 0.3) }
        fun run(dip: Boolean, change: Boolean): Boolean {
            val t = TransitionDetector()
            var fired = false
            for (s in 0 until 12) fired = t.update(shapeSecond(a, s.toLong()), s.toDouble()) || fired
            fired = t.update(shapeSecond(if (change) b else a, 50, if (dip) 12 else 0), 12.0) || fired
            for (s in 13 until 18) fired = t.update(shapeSecond(if (change) b else a, s.toLong()), s.toDouble()) || fired
            return fired
        }
        assertTrue(run(dip = true, change = true))
        assertFalse(run(dip = true, change = false))
        assertFalse(run(dip = false, change = true))
    }

    // ------------------------------------------------------------ verdict bias
    private fun secondsUntilAd(bias: Bias): Int {
        val v = Verdict()
        for (i in 0 until 12) v.add(Frame(i.toDouble(), 0.9, 0.05, -20.0), bias)
        for (i in 12 until 60) {
            if (v.add(Frame(i.toDouble(), 0.05, 0.9, -20.0), bias) == Heard.AD) return i - 12
        }
        return 99
    }

    @Test fun biasChangesHowFastItCallsAnAd() {
        val normal = secondsUntilAd(Bias.NORMAL)
        val eager = secondsUntilAd(Bias.TRANSITION)
        val strict = secondsUntilAd(Bias.UNLIKELY)
        assertTrue("eager $eager normal $normal strict $strict", eager < normal && normal < strict)
        assertTrue(eager <= 6)
    }

    // ------------------------------------------------------------ live data
    private val summaryJson = JSONObject().put("drives", JSONObject()
        .put("previous", JSONArray().put(JSONObject().put("plays", JSONArray()
            .put(JSONObject().put("sequenceNumber", "4000").put("text", "E.Evans kicks 61 yards from LA 35 to BUF 4.")
                .put("type", JSONObject().put("text", "Kickoff")).put("wallclock", "2026-10-13T00:15:44Z"))
            .put(JSONObject().put("sequenceNumber", "13300").put("text", "T.Bass 49 yard field goal is GOOD.")
                .put("type", JSONObject().put("text", "Field Goal Good")).put("wallclock", "2026-10-13T00:18:47Z")))))
        .put("current", JSONObject().put("plays", JSONArray()
            .put(JSONObject().put("sequenceNumber", "14300").put("text", "Official Timeout at 13:23.")
                .put("type", JSONObject().put("text", "Official Timeout")).put("wallclock", "2026-10-13T00:19:12Z")))))

    @Test fun parsesPlayByPlay() {
        val plays = parseSummaryPlays(summaryJson)
        assertEquals(listOf(4000L, 13300L, 14300L), plays.map { it.first })
        assertEquals("Kickoff: E.Evans kicks 61 yards from LA 35 to BUF 4.", plays[0].second)
        assertEquals("Official Timeout at 13:23.", plays[2].second)
        assertEquals(java.time.Instant.parse("2026-10-13T00:18:47Z").toEpochMilli(), plays[1].third)
    }

    @Test fun feedUsesRealPlayTimes() {
        var now = 1000.0
        val wall = java.time.Instant.parse("2026-10-13T00:19:42Z").toEpochMilli() // 30 s after the timeout
        val board = JSONObject().put("events", JSONArray().put(JSONObject().put("id", "401872994")
            .put("competitions", JSONArray().put(JSONObject()
                .put("status", JSONObject().put("period", 1).put("clock", 803.0)
                    .put("type", JSONObject().put("state", "in").put("name", "STATUS_IN_PROGRESS")))
                .put("competitors", JSONArray()
                    .put(JSONObject().put("score", "0").put("team", JSONObject().put("abbreviation", "LAR")))
                    .put(JSONObject().put("score", "3").put("team", JSONObject().put("abbreviation", "BUF"))))))))
        val f = GameFeed(mapOf(1 to GameSlot(team = "BUF")), { board }, { now },
            fetchSummary = { _, id -> assertEquals("401872994", id); summaryJson }, wallNow = { wall })
        f.focusBox = 1
        f.poll()
        val plays = f.plays(1)
        assertEquals(3, plays.size)
        assertEquals(970.0, plays.last().t, 0.01)          // happened 30 s ago
        assertEquals(GameStatus.BREAK, f.status[1])        // official timeout = TV break
    }

    @Test fun breakOutlookFromPlays() {
        var now = 0.0
        val nav = Navigator(object : Remote { override fun press(key: Key) {} }, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        val c = Controller(nav, listOf(1, 2), f, clock = { now })
        assertEquals(Bias.NORMAL, c.bias(1, now))
        now = 100.0
        f.recordPlay(1, "Field Goal Good: T.Bass 49 yard field goal is GOOD.", 95.0, 95.0)
        assertEquals(Bias.LIKELY, c.bias(1, now))
        // Break happens and ends: right after, another break is unlikely.
        now = 120.0; c.step(Heard.AD)
        nav.current = 1
        now = 300.0; c.step(Heard.GAME)
        assertEquals(Bias.UNLIKELY, c.bias(1, now))
        now = 600.0
        assertEquals(Bias.NORMAL, c.bias(1, now))
    }

    // ------------------------------------------------------------ detector end to end
    private val names = Array(Features.N_YAMNET) { "class$it" }

    private val namesWithSpeech = Array(Features.N_YAMNET) { if (it == 0) "Speech" else "class$it" }

    private fun second(audio: FloatArray, s: Int, speech: Float = 0f) =
        MicSecond(audio.copyOfRange(s * Dsp.SR, (s + 1) * Dsp.SR),
            FloatArray(Features.N_YAMNET) { if (it == 0) speech else 0.01f },
            if (speech > 0) namesWithSpeech else names)

    @Test fun learnsGameAudioBetweenPlays() {
        val d = Detector()
        val audio = throughRoom(jingle(7, 130), 3)
        for (s in 0 until 125) d.process(second(audio, s), s.toDouble(), 1, Bias.NORMAL, learning = true)
        val plays = listOf(10.0, 40.0, 70.0, 100.0).mapIndexed { i, t -> Play("Rush: up the middle", t, t + 20, i.toLong()) }
        d.observePlays(1, plays, 124.0)
        // Stretch 10..100 had plays < 50 s apart; allowing for TV delay the usable part is 100..110.
        assertTrue("learned ${d.model.gameSeconds}", d.model.gameSeconds in 9..13)
    }

    @Test fun tappedAdIsRecognizedWhenItAirsAgain() {
        val d = Detector()
        val ad = jingle(42, 20)
        val game = throughRoom(jingle(43, 30), 9, snrDb = 0.0)
        var t = 0.0
        for (s in 0 until 10) d.process(second(game, s), t++, 1, Bias.NORMAL, true)
        val first = throughRoom(ad, 1)
        for (s in 0 until 8) d.process(second(first, s), t++, 1, Bias.NORMAL, true)
        d.label(isAd = true, now = t)
        for (s in 8 until 20) d.process(second(first, s), t++, 1, Bias.NORMAL, true)
        for (s in 10 until 14) d.process(second(game, s), t++, 1, Bias.NORMAL, true)
        assertEquals(1, d.prints.size)
        d.onSwitch()
        // Later the same ad airs again.
        val again = throughRoom(ad, 77, gain = 1.4f)
        var recognized = false
        for (s in 3 until 10) {
            if (d.process(second(again, s), t++, 1, Bias.NORMAL, true).strongAd) recognized = true
        }
        assertTrue(recognized)
    }

    @Test fun announcerBreakPhraseSwitchesOnceThenCoolsDown() {
        val d = Detector()
        val audio = throughRoom(jingle(5, 90), 4)
        var t = 0.0
        for (s in 0 until 10) d.process(second(audio, s), t++, 1, Bias.NORMAL, true)
        // Phrase "heard" but nobody is talking (music/noise): ignored.
        assertFalse(d.process(second(audio, 10), t, 1, Bias.NORMAL, true, cue = "stay with us").strongAd)
        val hit = d.process(second(audio, 10, speech = 0.7f), t++, 1, Bias.NORMAL, true, cue = "we'll be right back")
        assertTrue(hit.strongAd)
        assertEquals(Heard.AD, hit.heard)
        assertEquals("we'll be right back", hit.cue)
        assertTrue(hit.why!!.contains("right back"))
        d.onSwitch()
        // Same phrase again 20 s later (e.g. a replay of the call): ignored.
        for (s in 11 until 30) d.process(second(audio, s), t++, 1, Bias.NORMAL, true)
        val again = d.process(second(audio, 30, speech = 0.7f), t++, 1, Bias.NORMAL, true, cue = "stay with us")
        assertFalse(again.strongAd)
        // Phrases don't teach the classifier that announcer audio is an ad.
        assertEquals(0, d.model.adSeconds)
    }

    @Test fun controllerLogsTheCue() {
        var now = 100.0
        val logs = mutableListOf<String>()
        val nav = Navigator(object : Remote { override fun press(key: Key) {} }, LAYOUTS.getValue(2), home = false, sleep = {})
        val f = GameFeed(emptyMap(), { JSONObject() }, { now }, pollSecs = 1e9)
        f.recordPlay(1, "Rush: up the middle for 4 yards", 95.0, 95.0) // a play just happened...
        val c = Controller(nav, listOf(1, 2), f, clock = { now }, log = { logs += it })
        assertTrue(c.step(Heard.AD, strongAd = true, strongWhy = "Announcer said \u201cstay with us\u201d"))
        assertEquals(2, nav.current) // ...but the announcer's call still wins
        assertTrue(logs.last().startsWith("Announcer said"))
    }

    @Test fun autoSavedPrintNeedsConfirmation() {
        val db = AdPrints()
        val x = jingle(900, 20)
        val (fp, last) = prints(x)
        val id = db.add(fp.range(0, last), auto = true, now = 0)!!
        assertFalse(db.trusted(id))
        db.confirm(id)
        assertTrue(db.trusted(id))
        val bytes = java.io.ByteArrayOutputStream().also { db.save(it) }.toByteArray()
        assertTrue(AdPrints().apply { load(bytes.inputStream()) }.trusted(id))
    }
}
