package com.rickle8.mvswitch.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.rickle8.mvswitch.audio.KeywordSpotter
import com.rickle8.mvswitch.audio.MicClassifier
import com.rickle8.mvswitch.audio.SpeechModel
import com.rickle8.mvswitch.core.Controller
import com.rickle8.mvswitch.core.DetectResult
import com.rickle8.mvswitch.core.Detector
import com.rickle8.mvswitch.core.GameFeed
import com.rickle8.mvswitch.core.GameInfo
import com.rickle8.mvswitch.core.GameSlot
import com.rickle8.mvswitch.core.GameStatus
import com.rickle8.mvswitch.core.Heard
import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.core.LAYOUTS
import com.rickle8.mvswitch.core.Navigator
import com.rickle8.mvswitch.core.Remote
import com.rickle8.mvswitch.core.ScheduledGame
import com.rickle8.mvswitch.core.excitement
import com.rickle8.mvswitch.core.gameTerms
import com.rickle8.mvswitch.core.listGames
import com.rickle8.mvswitch.core.scoreboardUrl
import com.rickle8.mvswitch.core.simulate
import com.rickle8.mvswitch.core.sortForPicking
import com.rickle8.mvswitch.core.suggestFill
import com.rickle8.mvswitch.core.summaryUrl
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.data.SettingsStore
import com.rickle8.mvswitch.data.Shortcut
import com.rickle8.mvswitch.data.TvType
import com.rickle8.mvswitch.remote.FoundTv
import com.rickle8.mvswitch.remote.OkTvHttp
import com.rickle8.mvswitch.remote.OkWsOpener
import com.rickle8.mvswitch.remote.RokuRemote
import com.rickle8.mvswitch.remote.SamsungRemote
import com.rickle8.mvswitch.remote.TestRemote
import com.rickle8.mvswitch.remote.TvApp
import com.rickle8.mvswitch.remote.TvApps
import com.rickle8.mvswitch.remote.TvExtras
import com.rickle8.mvswitch.remote.TvNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
    /** Holding off on switching for a bit because you're using the remote. */
    val held: Boolean = false,
    /** Samsung: whether the TV connection is up right now (null for other TVs). */
    val tvLinked: Boolean? = null,
    /** While connecting: what it's waiting for ("Press Allow on the TV"). */
    val tvWaiting: String? = null,
    /** The TV's on-screen keyboard is open, so typed text goes there. */
    val tvKeyboard: Boolean = false,
    /** The first direct input key was just sent: ask whether the TV switched to this input. */
    val askInput: Int? = null,
    /** What's on the TV right now: an app's name, an input, or off. Null if not known. */
    val nowOnTv: String? = null,
    /** "Volume 23" or "Muted", when the TV says. */
    val tvVolume: String? = null,
    /** How far behind live the TV stream runs, once learned (seconds). */
    val streamDelay: Int? = null,
    val breaksTimed: Int = 0,
    /** Network break / coming-back music clips it recognizes. */
    val breakMusic: Int = 0,
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

    /** Held while switching (navigator + controller), so a press never lands in the middle of a switch. */
    private val lock = Any()
    private val settingsLock = Any()
    private val publishLock = Any()
    private var job: Job? = null
    @Volatile private var nav: Navigator? = null
    @Volatile private var ctrl: Controller? = null
    @Volatile private var feed: GameFeed? = null
    @Volatile private var mic: MicClassifier? = null
    @Volatile private var previewFeed: GameFeed? = null
    private val logLines = ArrayDeque<LogLine>()
    private val tvLines = ArrayDeque<LogLine>()
    private val timeFmt = SimpleDateFormat("h:mm:ss", Locale.US)

    private lateinit var detector: Detector
    @Volatile private var lastResult: DetectResult? = null

    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var speechDir: java.io.File? = null
    @Volatile private var waitingForMultiview = false
    @Volatile private var autoPaused = false
    @Volatile private var autoPausedAt = 0.0
    /** The YouTube TV on-screen watcher is running (so auto-pause can also resume on its own). */
    @Volatile private var watching = false
    @Volatile private var needResync = false
    @Volatile private var holdUntil = 0.0
    @Volatile private var remoteMsg: String? = null
    @Volatile private var askInput: Int? = null

    @Volatile var restarting = false
    @Volatile private var paused = false
    @Volatile private var lockedBox: Int? = null
    @Volatile private var error: String? = null
    @Volatile private var testStatus: String? = null

    // ---------------------------------------------------------------- the TV connection
    private val tvThread = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-remote").apply { isDaemon = true } }
    /** Remote-tab commands run here: one at a time, in the order they were tapped. */
    private val tvQueue = tvThread.asCoroutineDispatcher()
    private val tvLock = Any()
    @Volatile private var tv: Remote? = null
    @Volatile private var tvKey: String? = null
    @Volatile private var tvWaiting: String? = null
    @Volatile private var tvKeyboard = false
    @Volatile private var nowOnTv: String? = null
    @Volatile private var tvVolume: String? = null
    @Volatile private var lastInputPicked: String? = null
    @Volatile private var lastAppLaunched: String? = null
    @Volatile private var readingScreen = false

    private val ARROWS = setOf(Key.LEFT, Key.RIGHT, Key.UP, Key.DOWN)
    /** Keys that don't take you anywhere, so switching carries on. */
    private val PASSIVE = setOf(Key.VOL_UP, Key.VOL_DOWN, Key.MUTE)

    fun micAllowed(): Boolean = _settings.value.useMic &&
        androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun now(): Double = SystemClock.elapsedRealtime() / 1000.0

    /** The app is on screen (scores are only fetched for the screen while it's showing). */
    @Volatile var visible = false
        set(v) { field = v; if (v) publish() }

    fun init(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        store = SettingsStore(app)
        _settings.value = store.load()
        detector = Detector(app.filesDir)
        // Learned sounds load in the background (a few MB); anything that needs them waits on the lock.
        scope.launch(Dispatchers.IO) { synchronized(detector) { runCatching { detector.load() } }; publish() }
        scope.launch(Dispatchers.IO) { speechDir = runCatching { SpeechModel.install(app) }.getOrNull() }
        scope.launch(Dispatchers.IO) { previewLoop() }
    }

    // ---------------------------------------------------------------- settings
    fun update(change: (Settings) -> Settings) {
        val (old, new) = synchronized(settingsLock) {
            val o = _settings.value
            var n = change(o)
            if (n.tvType != o.tvType) {
                n = n.copy(youTubeTvAppId = "") // app ids differ between TV makers
                // Samsung <-> Roku: nothing learned about the old TV applies. (Trying Test mode keeps it all.)
                if (n.tvType != TvType.TEST && o.tvType != TvType.TEST) {
                    n = n.copy(tvMac = "", samsungToken = null, inputSteps = emptyMap(), directInputs = 0)
                }
            } else if (n.tvHost.trim() != o.tvHost.trim()) {
                n = n.copy(tvMac = "") // learned again the next time the TV is on
            }
            if (n == o) return
            _settings.value = n
            store.save(n)
            o to n
        }
        if (new.tvType != old.tvType || new.tvHost.trim() != old.tvHost.trim()) {
            closeTv()
            appsCache = null
            synchronized(iconCache) { iconCache.clear() }
        }
        fun picked(x: Settings) = x.slots.mapValues { Triple(it.value.team, it.value.eventId, it.value.kind) }
        if (picked(new) != picked(old) ||
            new.layout != old.layout) previewFeed = null
        val running = job?.isActive == true
        if (!running) { publish(); return }
        fun games(x: Settings) = x.slots.mapValues { listOf(it.value.league, it.value.team, it.value.eventId, it.value.kind) }
        val structural = new.tvType != old.tvType || new.tvHost.trim() != old.tvHost.trim() ||
            new.layout != old.layout || games(new) != games(old) || new.useMic != old.useMic ||
            new.homing != old.homing || new.homePresses != old.homePresses ||
            new.useCues != old.useCues || new.cuePhrases != old.cuePhrases
        if (structural) {
            log("Settings changed, restarting")
            SwitcherService.restart(app, micChanged = new.useMic != old.useMic)
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
            publish()
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

    private val boardCache = HashMap<String, Pair<Double, List<ScheduledGame>>>()

    suspend fun schedule(league: String): List<ScheduledGame> = withContext(Dispatchers.IO) {
        synchronized(boardCache) { boardCache[league]?.takeIf { now() - it.first < 60 }?.second }
            ?: listGames(fetchBoard(league), league).also { synchronized(boardCache) { boardCache[league] = now() to it } }
    }

    /** Today's games in every sport whose name or teams match [query] (live first). */
    suspend fun searchGames(query: String): List<ScheduledGame> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext sortForPicking(allGames().filter { it.status != GameStatus.FINAL })
        sortForPicking(allGames().filter { g ->
            listOf(g.shortName, g.away, g.home, g.name, g.network).any { it.lowercase().contains(q) }
        })
    }

    /** Today's games in every sport (cached a minute per league). */
    private suspend fun allGames(): List<ScheduledGame> = coroutineScope {
        com.rickle8.mvswitch.data.LEAGUES.map { (league, _) ->
            async { runCatching { schedule(league) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    /** Puts the most exciting live games (any sport) into the empty boxes. Returns how many it filled. */
    suspend fun fillEmptyBoxes(): Int = withContext(Dispatchers.IO) {
        val s = _settings.value
        val empty = (1..boxCount(s)).filter { it !in s.slots }
        if (empty.isEmpty()) return@withContext 0
        val picks = suggestFill(allGames(), s.slots.values.map { it.eventId }.toSet(), empty.size)
        if (picks.isEmpty()) return@withContext 0
        update { cur ->
            val slots = cur.slots.toMutableMap()
            empty.zip(picks).forEach { (box, g) ->
                if (box !in slots) slots[box] = GameSlot(league = g.league, team = g.home.ifBlank { g.away },
                    name = g.shortName, eventId = g.id)
            }
            cur.copy(slots = slots)
        }
        minOf(empty.size, picks.size)
    }

    /** A "Something else" box, remembered for next time. */
    fun setOther(box: Int, name: String) = update { s ->
        val keep = s.slots[box]
        s.copy(
            slots = s.slots + (box to GameSlot(league = "", name = name, importance = if (keep?.isOther == true) keep.importance else 0.5,
                kind = GameSlot.OTHER)),
            recentOthers = (listOf(name) + s.recentOthers.filter { !it.equals(name, true) }).take(8),
        )
    }

    private fun boxCount(s: Settings) = LAYOUTS[s.layout]?.size ?: 4

    private fun makeFeed(s: Settings, preview: Boolean = false) =
        GameFeed(s.slots, ::fetchBoard, ::now, pollSecs = 15.0, fetchSummary = if (preview) null else ::fetchSummary)

    private fun makeRemote(s: Settings): Remote = when (s.tvType) {
        TvType.SAMSUNG -> {
            val host = s.tvHost.trim()
            require(host.isNotBlank()) { "Enter your TV's IP address in Settings" }
            SamsungRemote(host, s.samsungToken, { token ->
                // Saved off this thread: the connection may be holding locks while it waits.
                scope.launch { update { if (it.tvHost.trim() == host) it.copy(samsungToken = token) else it } }
            }, OkWsOpener(host), OkTvHttp, log = ::tvLog).apply {
                onKeyboard = { open -> tvKeyboard = open; publish() }
                onWaiting = { what -> tvWaiting = what; publish() }
                onScreenChanged = { refreshNowOnTv(800) }
            }
        }
        TvType.ROKU -> {
            require(s.tvHost.isNotBlank()) { "Enter your Roku's IP address in Settings" }
            RokuRemote(s.tvHost.trim())
        }
        TvType.TEST -> TestRemote()
    }

    /** The one connection to the TV, shared by switching and the Remote tab. */
    private fun tvRemote(): Remote = synchronized(tvLock) {
        val s = _settings.value
        val key = "${s.tvType}|${s.tvHost.trim()}"
        val cur = tv
        if (cur != null && tvKey == key) return cur
        (cur as? SamsungRemote)?.close()
        tv = null
        tvKey = null
        val r = makeRemote(s)
        tv = r
        tvKey = key
        r
    }

    private fun closeTv() = synchronized(tvLock) {
        (tv as? SamsungRemote)?.close()
        tv = null
        tvKey = null
        tvKeyboard = false
        tvWaiting = null
        nowOnTv = null
        tvVolume = null
    }

    private fun makeNav(s: Settings, r: Remote) = Navigator(
        r, LAYOUTS.getValue(s.layout), home = s.homing, homePresses = s.homePresses,
        log = ::log,
    )

    // ---------------------------------------------------------------- log/state
    private fun stamp(): String = synchronized(timeFmt) { timeFmt.format(Date()) }

    fun log(text: String) {
        synchronized(logLines) {
            logLines.addFirst(LogLine(stamp(), text))
            while (logLines.size > 40) logLines.removeLast()
        }
        publish()
    }

    /** Connection details: kept out of the Activity list, but in the diagnostics report. */
    private fun tvLog(text: String) {
        synchronized(tvLines) {
            tvLines.addFirst(LogLine(stamp(), text))
            while (tvLines.size > 80) tvLines.removeLast()
        }
    }

    private fun publish(starting: Boolean = false) = synchronized(publishLock) {
        val s = _settings.value
        val running = job?.isActive == true
        val f = if (running) feed else previewFeed
        val n = now()
        val boxes = (1..boxCount(s)).map { box ->
            val slot = s.slots[box]
            val info = f?.info?.get(box)
            val onBreak = if (running) {
                ctrl?.looksOnBreak(box, n, f?.status ?: emptyMap()) ?: false
            } else info?.status == GameStatus.BREAK
            val noGame = slot != null && f?.loaded == true && f.status[box] == GameStatus.UNKNOWN
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
            held = running && n < holdUntil,
            tvLinked = (tv as? SamsungRemote)?.linked,
            tvWaiting = tvWaiting,
            tvKeyboard = tvKeyboard,
            askInput = askInput,
            nowOnTv = nowOnTv,
            tvVolume = tvVolume,
            streamDelay = detector.timing.delay.seconds?.toInt(),
            breaksTimed = detector.timing.breaks.count(),
            breakMusic = detector.bumpers.trustedCount + detector.comebacks.trustedCount,
        )
    }

    private suspend fun previewLoop() {
        while (true) {
            if (visible && job?.isActive != true && _settings.value.slots.isNotEmpty()) {
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
        holdUntil = 0.0
        job = scope.launch(Dispatchers.IO) {
            old?.join() // a restart waits for the previous run to fully let go of the mic
            stopping = false
            try {
                runLoop()
            } finally {
                synchronized(detector) { detector.save() }
                spotter?.close(); spotter = null
                synchronized(lock) {
                    mic?.close(); mic = null
                    nav = null; ctrl = null; feed = null
                }
                watching = false
                lastResult = null
                publish()
            }
        }
        publish(starting = true)
    }

    fun stop(quiet: Boolean = false) {
        stopping = true
        job?.cancel()
        if (!quiet) log("Stopped")
    }

    /** Remember the Samsung's MAC address so the app can turn the TV on later. */
    private fun learnMac(s: Settings) {
        if (s.tvType != TvType.SAMSUNG || s.tvMac.isNotBlank() || s.tvHost.isBlank()) return
        val info = TvNetwork.samsungInfo(s.tvHost) ?: return
        val mac = TvNetwork.normalizeMac(info.optString("wifiMac")) ?: return
        update { if (it.tvHost == s.tvHost && it.tvMac.isBlank()) it.copy(tvMac = mac) else it }
    }

    /** Turn the TV on if it's off. True if it's on. */
    private suspend fun wakeTv(s: Settings): Boolean = when (s.tvType) {
        TvType.SAMSUNG -> wakeSamsung(s)
        TvType.ROKU -> {
            val roku = RokuRemote(s.tvHost.trim())
            when {
                s.tvHost.isBlank() -> false
                roku.awake() -> true
                else -> { roku.powerOn(); delay(6000); roku.awake() }
            }
        }
        TvType.TEST -> true
    }

    private suspend fun waitUntilOn(host: String, secs: Double): Boolean {
        val until = now() + secs
        while (now() < until) {
            delay(700)
            if (TvNetwork.samsungAwake(host)) return true
        }
        return false
    }

    /**
     * Samsung: if it's in network standby and still listening, its own power
     * key wakes it. Otherwise Wake-on-LAN, repeated for a while, since a
     * sleeping TV on Wi-Fi often misses the first packets.
     */
    private suspend fun wakeSamsung(s: Settings): Boolean {
        val host = s.tvHost.trim()
        if (host.isBlank()) return false
        val info = TvNetwork.samsungInfo(host)
        if (info != null && TvNetwork.isOn(info)) return true
        log("Turning on the TV…")
        if (info != null) {
            tvLog("TV is in standby (${info.optString("PowerState")}); trying its power key")
            val sent = (runCatching { tvRemote() }.getOrNull() as? SamsungRemote)?.powerKeyFromStandby() == true
            if (sent && waitUntilOn(host, 8.0)) { delay(2500); return true }
        }
        val mac = _settings.value.tvMac
        if (mac.isBlank()) {
            tvLog("Can't wake: MAC address not known yet")
            return false
        }
        val until = now() + 35
        var tries = 0
        while (now() < until) {
            tries++
            runCatching { TvNetwork.wake(app, mac, host) }
            if (waitUntilOn(host, 3.0)) {
                tvLog("Woke up after $tries Wake-on-LAN tries")
                delay(3000) // let it finish starting up
                return true
            }
        }
        tvLog("No answer after $tries Wake-on-LAN tries to $mac")
        log("The TV didn't wake up. On the TV, turn on Power On with Mobile (see the Remote tab).")
        return false
    }

    /** Why it couldn't turn the TV on, and what to change. */
    private fun powerHelp(s: Settings): String =
        if (s.tvType == TvType.SAMSUNG && s.tvMac.isBlank())
            "Can't turn it on yet: the app learns how the first time it connects while the TV is on. " +
                "Or enter the TV's MAC address in Settings."
        else "The TV didn't turn on. On the TV, turn on Power On with Mobile: Settings > General > Network > " +
            "Expert Settings (2022 and newer: Settings > All Settings > Connection > Network > Expert Settings). " +
            "If the TV is plugged into the router, check the MAC in Settings is the wired one."

    private suspend fun runLoop() {
        val s = _settings.value
        wakeTv(s)
        try {
            val r = tvRemote()
            val n = makeNav(s, r)
            val f = makeFeed(s)
            synchronized(lock) {
                nav = n; feed = f
                synchronized(detector) {
                    detector.verdict.adThreshold = s.adThreshold
                    detector.verdict.gameThreshold = s.gameThreshold
                    detector.onSwitch()
                }
                ctrl = Controller(
                    n, (1..boxCount(s)).toList(), f, games = s.slots,
                    assumedBreakSecs = s.breakSecs.toDouble(), upgradeMargin = s.hopping.margin,
                    minDwellSecs = s.hopping.dwellSecs, proactive = s.proactive,
                    clock = ::now, log = ::log, timing = detector.timing,
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

        learnMac(s)

        // YouTube TV: find it, open it if needed, and watch whether it's on screen.
        val apps = tv as? TvApps
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
                log("Running. Audio on box ${nav?.current}." + if (spotter != null) " Listening to the announcers." else "")
            } catch (e: Exception) {
                error = e.message
                log(e.message ?: "TV error")
            }
        }
        publish()

        val yt = ytId
        val watchJob = if (apps != null && yt != null && s.autoPause) {
            watching = true
            scope.launch(Dispatchers.IO) { watchYouTubeTv(apps, yt) }
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
        var micFails = 0
        var vocabAt = -1e9
        try {
            while (currentCoroutineContext().isActive) {
                val m = mic
                val sec = if (m != null) m.nextSecond() else { delay(1000); null }
                if (m != null) {
                    if (sec == null) {
                        // The mic stopped delivering audio (another app grabbed it, or audio hiccuped).
                        micFails++
                        delay(1000)
                        if (micFails % 10 == 5) {
                            if (micFails == 5) log("The microphone stopped working. Restarting it.")
                            runCatching { m.stop(); m.start() }
                        }
                    } else micFails = 0
                }
                // Keep the speech recognizer listening for these games' players and terms.
                if (now() - vocabAt > 60) {
                    vocabAt = now()
                    spotter?.let { sp ->
                        val words = s.slots.values.map { it.league }.distinct().flatMap { gameTerms(it) } +
                            (feed?.names().orEmpty())
                        runCatching { sp.setGameWords(words) }
                    }
                }
                val said = sec?.let { x -> spotter?.let { sp -> runCatching { sp.accept(x.boosted) }.getOrNull() } }
                val cue = said?.cue
                val n = now()
                val cur = nav?.current
                val c = ctrl ?: break
                val held = n < holdUntil
                val result = if (sec != null) synchronized(detector) {
                    cur?.let { box -> feed?.let { detector.observePlays(box, it.plays(box), n) } }
                    detector.process(sec, n, cur, c.bias(cur, n),
                        learning = !paused && !autoPaused && !waitingForMultiview && !held && lockedBox == null,
                        cue = cue, gameWords = said?.gameWords.orEmpty(), adWords = said?.adWords.orEmpty())
                } else null
                if (result != null) lastResult = result
                if (paused || autoPaused || waitingForMultiview || held) {
                    publish()
                    continue
                }
                if (needResync) {
                    needResync = false
                    runCatching { synchronized(lock) { nav?.resync() } }
                        .onFailure { log(it.message ?: "TV error") }
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
                                    strongWhy = if (result?.strongAd == true) result.why else null)) {
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
            watching = false
        }
    }

    /** Pauses switching while YouTube TV isn't on screen, so arrows never go to another app. */
    private suspend fun watchYouTubeTv(apps: TvApps, ytId: String) {
        var misses = 0
        var hits = 0
        var unknown = 0
        while (true) {
            delay(10_000)
            if (waitingForMultiview) { misses = 0; hits = 0; continue }
            when (runCatching { apps.isOnScreen(ytId) }.getOrNull()) {
                null -> {
                    if (++unknown >= 3) {
                        log("This TV doesn't say what's on screen, so it can't pause on its own.")
                        watching = false
                        if (autoPaused) {
                            autoPaused = false
                            paused = true
                            log("Paused. Tap Resume when you're back in multiview.")
                        }
                        publish()
                        return
                    }
                }
                true -> {
                    unknown = 0; misses = 0; hits++
                    // Twice in a row, and not right after you left: the app may still be closing.
                    if (autoPaused && hits >= 2 && now() - autoPausedAt > 20) {
                        autoPaused = false
                        needResync = true
                        log("YouTube TV is back on screen. Switching again.")
                    }
                }
                false -> {
                    unknown = 0; hits = 0; misses++
                    if (misses >= 2 && !autoPaused) {
                        autoPaused = true
                        autoPausedAt = now()
                        log("YouTube TV isn't on screen. Paused until it's back.")
                    }
                    if (autoPaused && now() - autoPausedAt > 20 * 60) {
                        log("YouTube TV has been off screen for 20 minutes. Stopping.")
                        SwitcherService.stop(app)
                        return
                    }
                }
            }
            publish()
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
        if (!p) holdUntil = 0.0
        log(if (p) "Paused" else "Resumed")
    }

    /** Multiview is up (after opening YouTube TV, or coming back to it): start switching. */
    fun go() {
        waitingForMultiview = false
        autoPaused = false
        paused = false
        holdUntil = 0.0
        needResync = true
        log("Running. Audio on box 1.")
    }

    /** Hold off automatic switching for a bit while you use the remote. */
    private fun holdSwitching(secs: Double) {
        if (job?.isActive != true) return
        val until = now() + secs
        if (until > holdUntil) holdUntil = until
    }

    /** You took the TV somewhere else: no arrows until YouTube TV is back. */
    private fun leftYouTubeTv(where: String) {
        if (job?.isActive != true || waitingForMultiview) return
        if (watching) {
            if (!autoPaused) log("Switching paused while you're on $where. It picks back up when YouTube TV is on screen.")
            autoPaused = true
            autoPausedAt = now()
        } else if (!paused) {
            paused = true
            log("Paused while you're on $where. Tap Resume when you're back in multiview.")
        }
        publish()
    }

    /** Runs a Remote-tab command on the TV queue, reporting any problem on the Remote tab. */
    private fun onTv(what: String, block: (Remote) -> Unit) {
        scope.launch(tvQueue) {
            try {
                block(tvRemote())
                remoteMsg = null
            } catch (e: Exception) {
                remoteMsg = e.message ?: "Couldn't $what"
                tvLog("Couldn't $what: ${e.message}")
            }
            publish()
        }
    }

    /** Buttons on the Remote tab. */
    fun press(key: Key) = onTv("press ${key.name.lowercase()}") { r ->
        val n = nav
        val switching = job?.isActive == true && !paused && !autoPaused && !waitingForMultiview && now() >= holdUntil
        if (key in ARROWS && n != null && switching) {
            synchronized(lock) {
                r.press(key)
                // In multiview the arrows move the audio: keep track of where it went.
                n.current = simulate(n.neighbors, n.current ?: 1, listOf(key))
                ctrl?.lastSwitch = now()
                synchronized(detector) { detector.onSwitch() }
            }
        } else {
            r.press(key)
        }
        // Anything but volume (or arrows in multiview) means you're in a menu: don't fight you.
        if (key !in PASSIVE && (key !in ARROWS || now() < holdUntil)) holdSwitching(30.0)
        when (key) {
            in PASSIVE -> refreshVolume()
            Key.HOME, Key.BACK, Key.SOURCE, Key.SELECT, Key.POWER -> refreshNowOnTv(2500)
            else -> Unit
        }
    }

    /** Just the volume, right after a volume key. */
    private fun refreshVolume() {
        scope.launch(Dispatchers.IO) {
            delay(400)
            if (!visible) return@launch
            val r = tv as? SamsungRemote ?: return@launch
            runCatching { r.volume() }.getOrNull()?.let { (level, muted) ->
                tvVolume = if (muted) "Muted" else "Volume $level"
                publish()
            }
        }
    }

    private fun parseStep(step: String): Pair<Key, Int>? {
        val parts = step.split('*')
        val key = runCatching { Key.valueOf(parts[0].trim()) }.getOrNull() ?: return null
        val count = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 20) ?: 1
        return key to count
    }

    /** Replays taught steps with the pauses a TV menu needs to keep up. */
    private fun playSteps(r: Remote, steps: List<String>, live: Boolean = false) {
        for (step in steps) {
            val (key, count) = parseStep(step) ?: continue
            repeat(count) {
                r.press(key)
                if (!live || count > 1) Thread.sleep(when {
                    key == Key.SOURCE || key == Key.HOME -> 1500L // the menu takes a moment to open
                    count > 1 -> 220L
                    else -> 500L
                })
            }
        }
    }

    /** One step while teaching an input (the TV follows along live). */
    fun pressStep(step: String) = onTv("press") { r ->
        holdSwitching(120.0)
        playSteps(r, listOf(step), live = true)
    }

    fun inputName(n: Int) = _settings.value.inputNames[n]?.takeIf { it.isNotBlank() } ?: "HDMI $n"

    /** A Samsung that ignores input keys, and this input hasn't been taught yet. */
    fun needsTeaching(n: Int): Boolean {
        val s = _settings.value
        return s.tvType == TvType.SAMSUNG && s.directInputs < 0 && s.inputSteps[n].isNullOrEmpty()
    }

    fun switchInput(n: Int) = onTv("switch inputs") { r ->
        val s = _settings.value
        val steps = s.inputSteps[n]
        leftYouTubeTv(inputName(n))
        when {
            !steps.isNullOrEmpty() -> playSteps(r, steps)
            r is TvExtras -> {
                r.input(n)
                // Many Samsungs ignore direct input keys and nothing can tell us, so ask once.
                if (r is SamsungRemote && s.directInputs == 0) askInput = n
            }
            else -> throw IllegalStateException("Inputs work with Samsung and Roku TVs")
        }
        pickedInput(inputName(n))
    }

    private fun pickedInput(name: String?) {
        lastInputPicked = name
        lastAppLaunched = null
        refreshNowOnTv(2500)
    }

    /** Tries taught steps before saving them. */
    fun testSteps(n: Int, steps: List<String>) = onTv("switch inputs") { r ->
        leftYouTubeTv(inputName(n))
        playSteps(r, steps)
        pickedInput(inputName(n))
    }

    fun saveSteps(n: Int, steps: List<String>) = update {
        it.copy(inputSteps = if (steps.isEmpty()) it.inputSteps - n else it.inputSteps + (n to steps))
    }

    /** "Did the TV switch?" after the first direct input key. */
    fun answerInput(worked: Boolean) {
        askInput = null
        update { it.copy(directInputs = if (worked) 1 else -1) }
        publish()
    }

    fun dismissInputQuestion() {
        askInput = null
        publish()
    }

    /** Samsung's HDMI key: goes to the next HDMI input each press. */
    fun nextInput() = onTv("switch inputs") { r ->
        leftYouTubeTv("another input")
        (r as? SamsungRemote ?: throw IllegalStateException("This button is for Samsung TVs")).nextInput()
        pickedInput(null)
    }

    // ---------------------------------------------------------------- full remote
    @Volatile private var appsCache: List<TvApp>? = null
    private val iconCache = HashMap<String, ByteArray?>()
    private val iconPermits = Semaphore(3)

    /** Everything installed on the TV (cached; [refresh] to reload). */
    suspend fun apps(refresh: Boolean = false): List<TvApp> = withContext(Dispatchers.IO) {
        if (!refresh) appsCache?.let { return@withContext it }
        val r = runCatching { tvRemote() }.getOrNull() as? TvApps
        val apps = r?.let { a ->
            runCatching { a.listApps() }.onFailure { remoteMsg = it.message; publish() }.getOrNull()
        }.orEmpty()
        if (apps.isNotEmpty()) {
            appsCache = apps
            // First time: suggest shortcuts for the streaming apps you have.
            if (_settings.value.shortcuts.isEmpty()) {
                val favorites = listOf("YouTube TV", "Prime Video", "Netflix", "YouTube", "Disney+", "Max", "Hulu",
                    "Peacock", "Paramount+", "ESPN")
                val picks = favorites.mapNotNull { f -> apps.firstOrNull { it.name.equals(f, true) } }.take(6)
                if (picks.isNotEmpty()) update { s ->
                    if (s.shortcuts.isEmpty()) s.copy(shortcuts = picks.map { Shortcut(Shortcut.APP, it.name, it.id) }) else s
                }
            }
        }
        apps
    }

    suspend fun appIcon(appId: String): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(iconCache) { if (iconCache.containsKey(appId)) return@withContext iconCache[appId] }
        val bytes = iconPermits.withPermit {
            (runCatching { tvRemote() }.getOrNull() as? TvExtras)?.let { runCatching { it.icon(appId) }.getOrNull() }
        }
        synchronized(iconCache) { iconCache[appId] = bytes }
        bytes
    }

    private fun isYouTubeTv(appId: String): Boolean = appId == _settings.value.youTubeTvAppId ||
        appsCache?.firstOrNull { it.id == appId }?.name.equals("YouTube TV", ignoreCase = true)

    private fun appName(appId: String): String = appsCache?.firstOrNull { it.id == appId }?.name
        ?: _settings.value.shortcuts.firstOrNull { it.kind == Shortcut.APP && it.value == appId }?.label
        ?: "another app"

    fun launchApp(appId: String) = onTv("open the app") { r ->
        val apps = r as? TvApps ?: throw IllegalStateException("Opening apps works with Samsung and Roku")
        if (isYouTubeTv(appId)) {
            if (job?.isActive == true) {
                waitingForMultiview = true
                autoPaused = false
                paused = false
                log("Opening YouTube TV. Put multiview up, then tap Go.")
            }
        } else leftYouTubeTv(appName(appId))
        apps.launch(appId)
        lastAppLaunched = appId
        lastInputPicked = null
        refreshNowOnTv(2500)
    }

    fun sendText(text: String) = onTv("type") { r ->
        (r as? TvExtras ?: throw IllegalStateException("Typing works with Samsung and Roku")).sendText(text)
    }

    fun runShortcut(s: Shortcut) = when (s.kind) {
        Shortcut.APP -> launchApp(s.value)
        Shortcut.INPUT -> s.value.toIntOrNull()?.let { switchInput(it) }
        else -> Unit
    }

    /** Remote tab open: get the TV connection ready so the first tap is instant. */
    fun warmUp() {
        val s = _settings.value
        if (s.tvType != TvType.SAMSUNG || s.tvHost.isBlank()) return
        scope.launch(tvQueue) {
            val r = runCatching { tvRemote() }.getOrNull() as? SamsungRemote ?: return@launch
            runCatching { r.check() }
                .onSuccess { remoteMsg = null }
                .onFailure { remoteMsg = it.message }
            publish()
            if (_settings.value.tvMac.isBlank()) runCatching { learnMac(_settings.value) }
        }
    }

    /** Power: turns the TV on if it's off (power key in standby, Wake-on-LAN), otherwise off. */
    fun power() {
        scope.launch(Dispatchers.IO) {
            val s = _settings.value
            if (s.tvType == TvType.TEST) return@launch
            if (s.tvHost.isBlank()) { remoteMsg = "Set up your TV in Settings first"; publish(); return@launch }
            val on = when (s.tvType) {
                TvType.SAMSUNG -> TvNetwork.samsungAwake(s.tvHost)
                TvType.ROKU -> RokuRemote(s.tvHost.trim()).awake()
                TvType.TEST -> true
            }
            if (on) {
                learnMac(s) // so this button can turn it back on later
                press(Key.POWER)
                nowOnTv = "Turning off…"
                publish()
                refreshNowOnTv(4000)
                return@launch
            }
            remoteMsg = "Turning on the TV…"; nowOnTv = "Turning on…"; publish()
            val woke = wakeTv(_settings.value)
            remoteMsg = if (woke) null else powerHelp(_settings.value)
            publish()
            refreshNowOnTv(if (woke) 1500 else 0)
            if (woke) warmUp()
        }
    }

    /**
     * What's on the TV right now: which app is open, or an input / Live TV;
     * whether it's off; the volume. For the Remote tab (only while it's showing).
     */
    fun refreshNowOnTv(delayMs: Long = 0) {
        val s = _settings.value
        if (s.tvType == TvType.TEST || s.tvHost.isBlank()) return
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            if (!visible || readingScreen) return@launch
            readingScreen = true
            try {
                readNowOnTv(_settings.value)
            } catch (e: Exception) {
                tvLog("Couldn't read what's on the TV: ${e.message}")
            } finally {
                readingScreen = false
                publish()
            }
        }
    }

    private fun appLabel(appId: String): String? = appsCache?.firstOrNull { it.id == appId }?.name
        ?: _settings.value.shortcuts.firstOrNull { it.kind == Shortcut.APP && it.value == appId }?.label

    private fun readNowOnTv(s: Settings) {
        when (s.tvType) {
            TvType.SAMSUNG -> {
                val info = TvNetwork.samsungInfo(s.tvHost)
                if (info == null || !TvNetwork.isOn(info)) {
                    nowOnTv = if (info == null) "Off, or not on the Wi-Fi" else "Off (standby)"
                    tvVolume = null
                    return
                }
                learnMac(s)
                val r = runCatching { tvRemote() }.getOrNull() as? SamsungRemote ?: return
                val apps = appsCache ?: runCatching { r.listApps() }.getOrDefault(emptyList())
                    .also { if (it.isNotEmpty()) appsCache = it }
                val candidates = buildList {
                    lastAppLaunched?.let { add(it) }
                    s.youTubeTvAppId.takeIf { it.isNotBlank() }?.let { add(it) }
                    s.shortcuts.filter { it.kind == Shortcut.APP }.forEach { add(it.value) }
                    apps.forEach { add(it.id) }
                }
                val id = r.visibleApp(candidates)
                nowOnTv = when {
                    id != null -> appLabel(id) ?: id
                    lastInputPicked != null -> "$lastInputPicked (or another input, Live TV or the home screen)"
                    else -> "An input, Live TV or the home screen"
                }
                tvVolume = r.volume()?.let { (level, muted) -> if (muted) "Muted" else "Volume $level" }
            }
            TvType.ROKU -> {
                val roku = runCatching { tvRemote() }.getOrNull() as? RokuRemote ?: return
                nowOnTv = when {
                    !roku.awake() -> roku.activeApp()?.let { "Off (standby)" } ?: "Off, or not on the Wi-Fi"
                    else -> roku.activeApp() ?: "Off, or not on the Wi-Fi"
                }
                tvVolume = null
            }
            TvType.TEST -> Unit
        }
    }

    /** Look for TVs on the Wi-Fi (Settings > Find my TV). */
    suspend fun findTvs(): List<FoundTv> = withContext(Dispatchers.IO) { TvNetwork.scan(app) }

    fun openYouTubeTv() {
        scope.launch(Dispatchers.IO) {
            try {
                val apps = runCatching { tvRemote() }.getOrNull() as? TvApps
                    ?: throw IllegalStateException("Opening apps works with Samsung and Roku")
                val id = _settings.value.youTubeTvAppId.ifBlank { null } ?: apps.findApp("youtube tv")
                    ?.also { id -> update { it.copy(youTubeTvAppId = id) } }
                    ?: throw IllegalStateException("Couldn't find YouTube TV on the TV")
                launchApp(id)
            } catch (e: Exception) {
                remoteMsg = e.message ?: "TV error"
                publish()
            }
        }
    }

    fun switchTo(box: Int, lock: Boolean = false) {
        if (job?.isActive != true) return
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(this@Engine.lock) {
                    lockedBox = if (lock) box else null
                    holdUntil = 0.0
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
                val n: Navigator = if (running) nav ?: return@launch else makeNav(s, tvRemote())
                status("Connecting… (accept the prompt on the TV if one appears)")
                synchronized(lock) { n.resync() }
                for (box in (1..boxCount(s)) + 1) {
                    status("Audio should be on box $box")
                    synchronized(lock) { n.goTo(box) }
                    delay(2500)
                }
                status("Done. If the audio didn't land where it said, see Help below.")
            } catch (e: Exception) {
                status("Failed: ${e.message}")
            }
        }
    }

    /** Everything useful for figuring out a problem, as text to paste into a message. */
    suspend fun report(): String = withContext(Dispatchers.IO) {
        val s = _settings.value
        val st = _state.value
        val sb = StringBuilder()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        sb.appendLine("Multiview Audio $version")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("TV: ${s.tvType.label} at ${s.tvHost.ifBlank { "(not set)" }}, paired: ${!s.samsungToken.isNullOrBlank()}, " +
            "MAC known: ${s.tvMac.isNotBlank()}")
        if (s.tvType == TvType.SAMSUNG && s.tvHost.isNotBlank()) {
            val d = TvNetwork.samsungInfo(s.tvHost)
            if (d == null) sb.appendLine("TV info: no answer on port 8001")
            else sb.appendLine("TV info: ${d.optString("modelName")} (${d.optString("model")}), ${d.optString("OS")}, " +
                "power ${d.optString("PowerState").ifBlank { "?" }}, network ${d.optString("networkType")}, " +
                "token auth ${d.optString("TokenAuthSupport")}, firmware ${d.optString("firmwareVersion")}")
        }
        sb.appendLine("Connection: " + when (st.tvLinked) { true -> "connected"; false -> "not connected"; null -> "n/a" } +
            (st.remoteMsg?.let { " ($it)" } ?: ""))
        sb.appendLine("Inputs: direct keys " + when (s.directInputs) { 1 -> "work"; -1 -> "don't work"; else -> "untested" } +
            ", taught: ${s.inputSteps.toSortedMap()}")
        sb.appendLine("YouTube TV app: ${s.youTubeTvAppId.ifBlank { "(not found yet)" }}")
        sb.appendLine("On the TV: ${st.nowOnTv ?: "?"}${st.tvVolume?.let { ", $it" } ?: ""}")
        sb.appendLine("Switching: ${if (st.running) "running" else "stopped"}, layout ${s.layout}, mic ${s.useMic}, " +
            "cues ${s.useCues}, paused ${st.paused}, auto-paused ${st.autoPaused}, held ${st.held}")
        sb.appendLine("Games: " + s.slots.entries.sortedBy { it.key }.joinToString { "${it.key}=" + if (it.value.isOther) "other:${it.value.name}" else "${it.value.league}/${it.value.team}#${it.value.eventId}" })
        sb.appendLine()
        sb.appendLine("TV connection log (newest first):")
        synchronized(tvLines) { tvLines.forEach { sb.appendLine("${it.time}  ${it.text}") } }
        sb.appendLine()
        sb.appendLine("Activity (newest first):")
        synchronized(logLines) { logLines.forEach { sb.appendLine("${it.time}  ${it.text}") } }
        sb.toString()
    }
}
