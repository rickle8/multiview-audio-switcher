package com.rickle8.mvswitch.service

import android.content.Context
import android.os.SystemClock
import com.rickle8.mvswitch.audio.KeywordSpotter
import com.rickle8.mvswitch.audio.MicClassifier
import com.rickle8.mvswitch.audio.SpeechModel
import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.remote.FoundTv
import com.rickle8.mvswitch.remote.TvApps
import com.rickle8.mvswitch.remote.TvNetwork
import com.rickle8.mvswitch.core.Controller
import com.rickle8.mvswitch.core.GameFeed
import com.rickle8.mvswitch.core.GameInfo
import com.rickle8.mvswitch.core.GameSlot
import com.rickle8.mvswitch.core.Heard
import com.rickle8.mvswitch.core.LAYOUTS
import com.rickle8.mvswitch.core.Navigator
import com.rickle8.mvswitch.core.Remote
import com.rickle8.mvswitch.core.DetectResult
import com.rickle8.mvswitch.core.Detector
import com.rickle8.mvswitch.core.ScheduledGame
import com.rickle8.mvswitch.core.summaryUrl
import com.rickle8.mvswitch.core.excitement
import com.rickle8.mvswitch.core.listGames
import com.rickle8.mvswitch.core.scoreboardUrl
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.data.SettingsStore
import com.rickle8.mvswitch.data.TvType
import com.rickle8.mvswitch.remote.RokuRemote
import com.rickle8.mvswitch.remote.SamsungRemote
import com.rickle8.mvswitch.remote.TestRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BoxView(
    val box: Int,
    val slot: GameSlot?,
    val info: GameInfo?,
    val rating: Double,
    val onBreak: Boolean,
    /** A team is set for this box but it has no game today. */
    val noGame: Boolean = false,
)

data class LogLine(val time: String, val text: String)

data class EngineState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val paused: Boolean = false,
    val lockedBox: Int? = null,
    val currentBox: Int? = null,
    val heard: Heard = Heard.UNSURE,
    val adPct: Int? = null,
    val topSounds: List<String> = emptyList(),
    val micOn: Boolean = false,
    val error: String? = null,
    val boxes: List<BoxView> = emptyList(),
    val log: List<LogLine> = emptyList(),
    val testStatus: String? = null,
    val learnedGameSecs: Int = 0,
    val learnedAdSecs: Int = 0,
    val usingLearned: Boolean = false,
    val labeling: String? = null,
    val adsRemembered: Int = 0,
    val voiceLearned: Boolean = false,
    val why: String? = null,
    /** YouTube TV was just opened: waiting for you to put multiview up. */
    val waitingForMultiview: Boolean = false,
    /** Paused on its own because YouTube TV isn't on screen. */
    val autoPaused: Boolean = false,
    val cuesOn: Boolean = false,
    val remoteMsg: String? = null,
    val feedError: String? = null,
)

object Engine {
    private lateinit var app: Context
    private lateinit var store: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).build()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private val lock = Any()
    private var job: Job? = null
    private var nav: Navigator? = null
    private var ctrl: Controller? = null
    private var feed: GameFeed? = null
    private var mic: MicClassifier? = null
    private var remote: Remote? = null
    private var previewFeed: GameFeed? = null
    private val logLines = ArrayDeque<LogLine>()
    private val timeFmt = SimpleDateFormat("h:mm:ss", Locale.US)

    private lateinit var detector: Detector
    @Volatile private var lastResult: DetectResult? = null

    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var speechDir: java.io.File? = null
    @Volatile private var waitingForMultiview = false
    @Volatile private var autoPaused = false
    @Volatile private var needResync = false
    @Volatile private var remoteMsg: String? = null
    private var manualRemote: Remote? = null

    @Volatile var restarting = false
    @Volatile private var paused = false
    @Volatile private var lockedBox: Int? = null
    @Volatile private var error: String? = null
    @Volatile private var testStatus: String? = null

    fun micAllowed(): Boolean = _settings.value.useMic &&
        androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun now(): Double = SystemClock.elapsedRealtime() / 1000.0

    fun init(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        store = SettingsStore(app)
        _settings.value = store.load()
        detector = Detector(app.filesDir).also { runCatching { it.load() } }
        scope.launch(Dispatchers.IO) { speechDir = runCatching { SpeechModel.install(app) }.getOrNull() }
        scope.launch(Dispatchers.IO) { previewLoop() }
    }

    // ---------------------------------------------------------------- settings
    fun update(change: (Settings) -> Settings) {
        val old = _settings.value
        val new = change(old).let {
            // A different TV has a different YouTube TV app id (and MAC address).
            if (it.tvType != old.tvType || it.tvHost != old.tvHost) it.copy(youTubeTvAppId = "", tvMac = "") else it
        }
        if (new == old) return
        _settings.value = new
        store.save(new)
        if (new.tvType != old.tvType || new.tvHost != old.tvHost) {
            (manualRemote as? SamsungRemote)?.close(); manualRemote = null
        }
        if (new.slots.mapValues { it.value.team } != old.slots.mapValues { it.value.team } ||
            new.layout != old.layout) previewFeed = null
        val running = job?.isActive == true
        if (!running) { publish(); return }
        fun games(x: Settings) = x.slots.mapValues { it.value.league to it.value.team }
        val structural = new.tvType != old.tvType || new.tvHost != old.tvHost ||
            new.layout != old.layout || games(new) != games(old) || new.useMic != old.useMic ||
            new.homing != old.homing || new.homePresses != old.homePresses ||
            new.useCues != old.useCues || new.cuePhrases != old.cuePhrases
        if (structural) {
            log("Settings changed, restarting")
            SwitcherService.restart(app)
        } else {
            // Plain field updates: no lock, so the screen never waits on a switch in progress.
            ctrl?.apply {
                games = new.slots
                upgradeMargin = new.hopping.margin
                minDwellSecs = new.hopping.dwellSecs
                assumedBreakSecs = new.breakSecs.toDouble()
                proactive = new.proactive
            }
            detector.verdict.adThreshold = new.adThreshold
            detector.verdict.gameThreshold = new.gameThreshold
        }
    }

    // ---------------------------------------------------------------- data
    private fun fetchBoard(league: String): JSONObject {
        http.newCall(Request.Builder().url(scoreboardUrl(league)).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("ESPN returned ${it.code}")
            return JSONObject(it.body!!.string())
        }
    }

    private fun fetchSummary(league: String, eventId: String): JSONObject {
        http.newCall(Request.Builder().url(summaryUrl(league, eventId)).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("ESPN returned ${it.code}")
            return JSONObject(it.body!!.string())
        }
    }

    suspend fun schedule(league: String): List<ScheduledGame> = withContext(Dispatchers.IO) {
        listGames(fetchBoard(league), league)
    }

    private fun boxCount(s: Settings) = LAYOUTS[s.layout]?.size ?: 4

    private fun makeFeed(s: Settings, preview: Boolean = false) =
        GameFeed(s.slots, ::fetchBoard, ::now, pollSecs = 15.0, fetchSummary = if (preview) null else ::fetchSummary)

    private fun makeRemote(s: Settings): Remote = when (s.tvType) {
        TvType.SAMSUNG -> {
            require(s.tvHost.isNotBlank()) { "Enter your TV's IP address in Settings" }
            SamsungRemote(s.tvHost, s.samsungToken) { token -> update { it.copy(samsungToken = token) } }
        }
        TvType.ROKU -> {
            require(s.tvHost.isNotBlank()) { "Enter your Roku's IP address in Settings" }
            RokuRemote(s.tvHost)
        }
        TvType.TEST -> TestRemote()
    }

    private fun makeNav(s: Settings, r: Remote) = Navigator(
        r, LAYOUTS.getValue(s.layout), home = s.homing, homePresses = s.homePresses,
        log = ::log,
    )

    // ---------------------------------------------------------------- log/state
    fun log(text: String) {
        synchronized(logLines) {
            logLines.addFirst(LogLine(timeFmt.format(Date()), text))
            while (logLines.size > 40) logLines.removeLast()
        }
        publish()
    }

    private fun publish(starting: Boolean = false) {
        val s = _settings.value
        val running = job?.isActive == true
        val f = if (running) feed else previewFeed
        val n = now()
        val boxes = (1..boxCount(s)).map { box ->
            val slot = s.slots[box]
            val info = f?.info?.get(box)
            val onBreak = if (running) {
                ctrl?.inBreak(box, n, f?.status ?: emptyMap()) ?: false
            } else info?.status == com.rickle8.mvswitch.core.GameStatus.BREAK
            val noGame = slot != null && f?.loaded == true && f.status[box] == com.rickle8.mvswitch.core.GameStatus.UNKNOWN
            BoxView(box, slot, info, excitement(info, slot), onBreak, noGame)
        }
        val r = lastResult
        val micLive = mic != null
        _state.value = EngineState(
            running = running,
            starting = starting || (running && nav == null),
            paused = paused,
            lockedBox = lockedBox,
            currentBox = if (running) nav?.current else null,
            heard = if (running && !micLive) Heard.GAME else if (running) r?.heard ?: Heard.UNSURE else Heard.UNSURE,
            adPct = if (running && micLive) r?.adPct else null,
            topSounds = if (running && micLive) r?.topSounds.orEmpty() else emptyList(),
            micOn = micLive,
            error = error,
            boxes = boxes,
            log = synchronized(logLines) { logLines.toList() },
            testStatus = testStatus,
            learnedGameSecs = detector.model.gameSeconds,
            learnedAdSecs = detector.model.adSeconds,
            usingLearned = detector.model.ready,
            labeling = detector.labelingNow(n),
            adsRemembered = detector.prints.size,
            voiceLearned = detector.voices.ready,
            why = if (running && micLive) r?.why else null,
            waitingForMultiview = running && waitingForMultiview,
            autoPaused = running && autoPaused,
            cuesOn = running && spotter != null,
            remoteMsg = remoteMsg,
            feedError = f?.lastError,
        )
    }

    private suspend fun previewLoop() {
        while (true) {
            if (job?.isActive != true && _settings.value.slots.isNotEmpty()) {
                val pf = previewFeed ?: makeFeed(_settings.value, preview = true).also { previewFeed = it }
                runCatching { pf.poll() }
                publish()
            }
            delay(5_000)
        }
    }

    // ---------------------------------------------------------------- run loop
    @Volatile private var stopping = false

    fun start() {
        val old = job
        if (old?.isActive == true && !stopping) return
        error = null
        paused = false
        lockedBox = null
        waitingForMultiview = false
        autoPaused = false
        needResync = false
        job = scope.launch(Dispatchers.IO) {
            old?.join() // a restart waits for the previous run to fully let go of the mic and TV
            stopping = false
            try {
                runLoop()
            } finally {
                synchronized(detector) { detector.save() }
                spotter?.close(); spotter = null
                synchronized(lock) {
                    mic?.close(); mic = null
                    (remote as? SamsungRemote)?.close()
                    remote = null; nav = null; ctrl = null; feed = null
                }
                lastResult = null
                publish()
            }
        }
        publish(starting = true)
    }

    fun stop() {
        stopping = true
        job?.cancel()
        log("Stopped")
    }

    /** Turn the TV on if it's off (Samsung: Wake-on-LAN, Roku TV: power key). */
    private suspend fun wakeTv(s: Settings) {
        when (s.tvType) {
            TvType.SAMSUNG -> {
                if (s.tvHost.isBlank() || TvNetwork.samsungAwake(s.tvHost)) return
                if (s.tvMac.isBlank()) return
                log("Turning on the TV…")
                runCatching { TvNetwork.wake(s.tvMac) }
                repeat(12) {
                    delay(2500)
                    if (TvNetwork.samsungAwake(s.tvHost)) { delay(4000); return } // give it a moment to finish booting
                    if (it % 4 == 3) runCatching { TvNetwork.wake(s.tvMac) }
                }
                log("The TV didn't wake up. On the TV, turn on Settings > General > Network > Expert Settings > Power On with Mobile.")
            }
            TvType.ROKU -> {
                val roku = RokuRemote(s.tvHost)
                if (s.tvHost.isBlank() || roku.awake()) return
                roku.powerOn()
                delay(6000)
            }
            TvType.TEST -> Unit
        }
    }

    private suspend fun runLoop() {
        val s = _settings.value
        wakeTv(s)
        try {
            val r = makeRemote(s)
            val n = makeNav(s, r)
            val f = makeFeed(s)
            synchronized(lock) {
                remote = r; nav = n; feed = f
                synchronized(detector) {
                    detector.verdict.adThreshold = s.adThreshold
                    detector.verdict.gameThreshold = s.gameThreshold
                    detector.onSwitch()
                }
                ctrl = Controller(
                    n, (1..boxCount(s)).toList(), f, games = s.slots,
                    assumedBreakSecs = s.breakSecs.toDouble(), upgradeMargin = s.hopping.margin,
                    minDwellSecs = s.hopping.dwellSecs, proactive = s.proactive,
                    clock = ::now, log = ::log,
                )
            }
        } catch (e: Exception) {
            error = e.message
            log("Couldn't start: ${e.message}")
            return
        }
        if (s.useMic && !micAllowed()) {
            log("Microphone permission is off. Using live game data only.")
        } else if (s.useMic) {
            try {
                mic = MicClassifier(app).also { it.start() }
            } catch (e: Exception) {
                log("Microphone unavailable (${e.message}). Using live game data only.")
            }
        } else {
            log("Microphone off. Using live game data only.")
        }
        if (mic != null && s.useCues) {
            val dir = speechDir ?: runCatching { SpeechModel.install(app) }.getOrNull()
            spotter = when {
                dir == null -> { log("Speech model not available; skipping announcer phrases."); null }
                else -> runCatching { KeywordSpotter(dir, s.cuePhrases) }
                    .onFailure { log("Couldn't start speech recognition (${it.message}).") }.getOrNull()
            }
        }

        // Remember the Samsung's MAC address so a later Start can turn the TV on.
        if (s.tvType == TvType.SAMSUNG && s.tvMac.isBlank()) {
            TvNetwork.samsungInfo(s.tvHost)?.optString("wifiMac")?.takeIf { it.length >= 12 }?.let { mac ->
                update { it.copy(tvMac = mac) }
            }
        }

        // YouTube TV: find it, open it if needed, and watch whether it's on screen.
        val apps = remote as? TvApps
        var ytId = s.youTubeTvAppId.ifBlank { null }
        if (apps != null && ytId == null && (s.openYouTubeTv || s.autoPause)) {
            ytId = runCatching { apps.findApp("youtube tv") }.getOrNull()
            ytId?.let { id -> update { it.copy(youTubeTvAppId = id) } }
        }
        var opened = false
        if (apps != null && ytId != null && s.openYouTubeTv) {
            val onScreen = runCatching { apps.isOnScreen(ytId) }.getOrNull()
            if (onScreen != true) {
                runCatching { apps.launch(ytId) }
                    .onSuccess {
                        opened = true
                        waitingForMultiview = true
                        log("Opening YouTube TV. Put multiview up, then tap Go.")
                    }
                    .onFailure { log("Couldn't open YouTube TV (${it.message}).") }
            }
        }
        if (!opened) {
            try {
                synchronized(lock) { nav?.resync() }
                log("Running. Audio on box ${nav?.current}." + if (spotter != null) " Listening for announcer phrases." else "")
            } catch (e: Exception) {
                error = e.message
                log(e.message ?: "TV error")
            }
        }
        publish()

        val watchJob = if (apps != null && ytId != null && s.autoPause) scope.launch(Dispatchers.IO) {
            var misses = 0
            var offSince = 0.0
            while (true) {
                delay(15_000)
                if (waitingForMultiview) continue
                when (runCatching { apps.isOnScreen(ytId) }.getOrNull()) {
                    null -> { log("This TV doesn't report what's on screen; auto-pause is off."); break }
                    true -> {
                        misses = 0
                        if (autoPaused) {
                            autoPaused = false
                            needResync = true
                            log("YouTube TV is back on screen. Resuming.")
                        }
                    }
                    false -> {
                        misses++
                        if (misses == 2 && !autoPaused) {
                            autoPaused = true
                            offSince = now()
                            log("YouTube TV isn't on screen. Paused until it's back.")
                        }
                        if (autoPaused && now() - offSince > 20 * 60) {
                            log("YouTube TV has been off screen for 20 minutes. Stopping.")
                            SwitcherService.stop(app)
                            break
                        }
                    }
                }
                publish()
            }
        } else null

        // Live data on its own loop so a slow network never stalls the mic.
        val feedJob = scope.launch(Dispatchers.IO) {
            while (true) {
                feed?.let { fd ->
                    fd.focusBox = nav?.current
                    runCatching { fd.poll() }
                }
                delay(1000)
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                val m = mic
                val sec = if (m != null) m.nextSecond() else { delay(1000); null }
                val cue = sec?.let { x -> spotter?.let { sp -> runCatching { sp.accept(x.boosted) }.getOrNull() } }
                val n = now()
                val cur = nav?.current
                val c = ctrl ?: break
                val result = if (sec != null) synchronized(detector) {
                    cur?.let { box -> feed?.let { detector.observePlays(box, it.plays(box), n) } }
                    detector.process(sec, n, cur, c.bias(cur, n),
                        learning = !paused && !autoPaused && !waitingForMultiview && lockedBox == null, cue = cue)
                } else null
                if (result != null) lastResult = result
                if (paused || autoPaused || waitingForMultiview) {
                    publish()
                    continue
                }
                if (needResync) {
                    needResync = false
                    runCatching { synchronized(lock) { nav?.resync() } }
                    synchronized(detector) { detector.onSwitch() }
                }
                val heard = when {
                    m == null -> Heard.GAME
                    result != null -> result.heard
                    else -> lastResult?.heard ?: Heard.UNSURE
                }
                synchronized(lock) {
                    if (lockedBox == null) {
                        try {
                            if (c.step(heard, strongAd = result?.strongAd == true,
                                    strongWhy = if (result?.cue != null) result.why else null)) {
                                m?.drain()
                                synchronized(detector) { detector.onSwitch() }
                                lastResult = null
                            }
                            error = null
                        } catch (e: Exception) {
                            c.lastSwitch = now() // back off a few seconds before retrying
                            if (error != e.message) log(e.message ?: "Error")
                            error = e.message
                        }
                    }
                }
                publish()
            }
        } finally {
            feedJob.cancel()
            watchJob?.cancel()
        }
    }

    // ---------------------------------------------------------------- learning
    /** "It's the game" / "It's an ad": label the last 8 s and the next 12 s. */
    fun label(isAd: Boolean) {
        synchronized(detector) { detector.label(isAd, now(), nav?.current) }
        if (isAd) {
            log("Got it, commercial. Learning this sound.")
            scope.launch(Dispatchers.IO) {
                synchronized(lock) {
                    try {
                        if (lockedBox == null && ctrl?.commercialNow() == true) {
                            mic?.drain()
                            synchronized(detector) { detector.onSwitch() }
                        }
                    } catch (e: Exception) {
                        log(e.message ?: "TV error")
                    }
                }
            }
        } else {
            log("Got it, game audio. Learning this sound.")
        }
        publish()
    }

    fun resetLearning() {
        synchronized(detector) { detector.forgetAll() }
        log("Forgot what it learned. Back to the built-in rules.")
    }

    // ---------------------------------------------------------------- manual controls
    fun setPaused(p: Boolean) {
        paused = p
        log(if (p) "Paused" else "Resumed")
    }

    /** Multiview is up after opening YouTube TV: start switching. */
    fun go() {
        waitingForMultiview = false
        autoPaused = false
        needResync = true
        log("Running. Audio on box 1.")
    }

    private fun remoteForManual(): Remote? {
        if (job?.isActive == true) remote?.let { return it }
        manualRemote?.let { return it }
        return runCatching { makeRemote(_settings.value) }.getOrNull()?.also { manualRemote = it }
    }

    /** Buttons on the Remote tab. */
    fun press(key: Key) {
        scope.launch(Dispatchers.IO) {
            try {
                val r = remoteForManual() ?: throw IllegalStateException("Set up your TV in Settings first")
                synchronized(lock) {
                    r.press(key)
                    // Arrows in multiview move the audio: keep track of where it went.
                    val n = nav
                    if (n != null && job?.isActive == true && key in setOf(Key.LEFT, Key.RIGHT, Key.UP, Key.DOWN)) {
                        n.current = com.rickle8.mvswitch.core.simulate(n.neighbors, n.current ?: 1, listOf(key))
                        ctrl?.lastSwitch = now()
                        synchronized(detector) { detector.onSwitch() }
                    }
                }
                if (remoteMsg != null) { remoteMsg = null; publish() }
            } catch (e: Exception) {
                remoteMsg = e.message ?: "TV error"
                publish()
            }
        }
    }

    /** Look for TVs on the Wi-Fi (Settings > Find my TV). */
    suspend fun findTvs(): List<FoundTv> = withContext(Dispatchers.IO) { TvNetwork.scan(app) }

    fun openYouTubeTv() {
        scope.launch(Dispatchers.IO) {
            try {
                val apps = remoteForManual() as? TvApps
                    ?: throw IllegalStateException("Opening apps works with Samsung and Roku")
                val id = _settings.value.youTubeTvAppId.ifBlank { null } ?: apps.findApp("youtube tv")
                    ?.also { id -> update { it.copy(youTubeTvAppId = id) } }
                    ?: throw IllegalStateException("Couldn't find YouTube TV on the TV")
                apps.launch(id)
                remoteMsg = "Opening YouTube TV…"
            } catch (e: Exception) {
                remoteMsg = e.message ?: "TV error"
            }
            publish()
        }
    }

    fun switchTo(box: Int, lock: Boolean = false) {
        if (job?.isActive != true) return
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(this@Engine.lock) {
                    lockedBox = if (lock) box else null
                    nav?.goTo(box)
                    ctrl?.let { it.lastSwitch = now(); it.breakUntil.remove(box) }
                    synchronized(detector) { detector.onSwitch() }
                }
                log(if (lock) "Locked on box $box" else "You switched to box $box")
            } catch (e: Exception) {
                log(e.message ?: "TV error")
            }
        }
    }

    fun unlock() {
        lockedBox = null
        log("Unlocked. Auto switching again.")
    }

    /** Resets the highlight and walks the audio through every box. */
    fun testTv() {
        scope.launch(Dispatchers.IO) {
            val s = _settings.value
            fun status(t: String?) { testStatus = t; publish() }
            try {
                val running = job?.isActive == true
                val n: Navigator
                var tmpRemote: Remote? = null
                if (running) {
                    n = nav ?: return@launch
                } else {
                    tmpRemote = makeRemote(s)
                    n = makeNav(s, tmpRemote)
                }
                status("Connecting… (accept the prompt on the TV if one appears)")
                synchronized(lock) { n.resync() }
                for (box in (1..boxCount(s)) + 1) {
                    status("Audio should be on box $box")
                    synchronized(lock) { n.goTo(box) }
                    delay(2500)
                }
                (tmpRemote as? SamsungRemote)?.close()
                status("Done. If the audio didn't land where it said, see Help below.")
            } catch (e: Exception) {
                status("Failed: ${e.message}")
            }
        }
    }
}
