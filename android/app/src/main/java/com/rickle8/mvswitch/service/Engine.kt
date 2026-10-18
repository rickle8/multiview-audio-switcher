package com.rickle8.mvswitch.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.rickle8.mvswitch.audio.MicClassifier
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
    val why: String? = null,
    /** YouTube TV was just opened: waiting for you to put multiview up. */
    val waitingForMultiview: Boolean = false,
    /** Paused on its own because YouTube TV isn't on screen. */
    val autoPaused: Boolean = false,
    val remoteMsg: String? = null,
    val feedError: String? = null,
    /** Holding off on switching for a bit because you're using the remote. */
    val held: Boolean = false,
    /** The camera is watching the TV (frames are arriving). */
    val cameraLive: Boolean = false,
    /** The box YouTube TV last flashed its outline on (where the sound went), in the last half minute. */
    val cameraBox: Int? = null,
    /** Per box: where the camera found the score bug (fractions of the box). */
    val cameraBugs: Map<Int, DoubleArray?> = emptyMap(),
    /** Per box: game (true) / commercial (false) / not sure (null), as the camera sees it. */
    val cameraGame: Map<Int, Boolean?> = emptyMap(),
    /** Per box: what the camera sees, in a few words ("score bug, yard lines"). */
    val cameraSees: Map<Int, String> = emptyMap(),
    /** The box you're hearing: how the sound and the camera lined up on the last call. */
    val paired: String? = null,
    /** Per box: what the phone read in it (score bug text), for the setup screen. */
    val cameraText: Map<Int, String> = emptyMap(),
    /** Per box: how much of the learned score bar is on screen, 0..100. */
    val cameraBars: Map<Int, Int?> = emptyMap(),
    /** The camera saw the outline flash on this box (not where the app thinks the sound is): ask. */
    val outlineAsk: Int? = null,
    /** Auto-finding the TV: progress or result. */
    val cameraFind: String? = null,
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
)

object Engine {
    private lateinit var app: Context
    private lateinit var store: SettingsStore
    /** Anything that slips through is logged and saved for the diagnostics report, never a crash. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            saveCrash(e, "background task")
            runCatching { log("Something went wrong (${e.javaClass.simpleName}: ${e.message}).") }
        })

    /** The last error that would have stopped the app, for the diagnostics report. */
    fun saveCrash(e: Throwable, where: String) {
        if (!this::app.isInitialized) return
        runCatching {
            java.io.File(app.filesDir, "last_error.txt").writeText(
                "${java.text.SimpleDateFormat("MMM d h:mm:ss a", java.util.Locale.US).format(java.util.Date())} in $where " +
                    "(version ${appVersion()})\n" + e.stackTraceToString().take(6000))
        }
    }

    private fun appVersion(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"
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
        // A crash anywhere: keep what happened for the diagnostics report.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            saveCrash(e, "app (thread ${t.name})")
            previous?.uncaughtException(t, e)
        }
        // An error saved by an older version was reported already: start clean after an update.
        runCatching {
            val f = java.io.File(app.filesDir, "last_error.txt")
            if (f.exists() && !f.readText().contains("(version ${appVersion()})")) f.delete()
        }
        store = SettingsStore(app)
        _settings.value = store.load()
        // Controls on your own phone (the camera phone sits on its stand).
        runCatching { PhoneRemote.start(app) }
        detector = Detector(app.filesDir)
        // Learned sounds load in the background (a few MB); anything that needs them waits on the lock.
        scope.launch(Dispatchers.IO) { synchronized(detector) { runCatching { detector.load() } }; publish() }
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
            new.homing != old.homing || new.homePresses != old.homePresses
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
                returnAfterBreak = new.returnToGame
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
        // Every game's play-by-play every few seconds: it's how the app knows which games are in commercial without hearing them.
        GameFeed(s.slots, ::fetchBoard, ::now, pollSecs = 10.0, fetchSummary = if (preview) null else ::fetchSummary,
            summarySecs = 5.0, otherSummarySecs = 8.0)

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

    private val CONNECTING_MSGS = setOf(SamsungRemote.NO_ANSWER_FIRST, SamsungRemote.NO_ANSWER, SamsungRemote.PROMPT_TIMED_OUT)

    private fun publish(starting: Boolean = false) = synchronized(publishLock) {
        val s = _settings.value
        val running = job?.isActive == true
        val linked = (tv as? SamsungRemote)?.linked
        if (linked == true && remoteMsg in CONNECTING_MSGS) remoteMsg = null
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
            why = if (running && micLive) {
                nav?.current?.let { ctrl?.waitingFor(it) }?.let { "ESPN says a break is coming. Waiting for the commercial to start" }
                    ?: r?.why
            } else null,
            waitingForMultiview = running && waitingForMultiview,
            autoPaused = running && autoPaused,
            remoteMsg = remoteMsg,
            feedError = f?.lastError,
            held = running && n < holdUntil,
            cameraLive = n - cameraAt < 5,
            cameraBox = lastOutline?.takeIf { n - it.second < 30 }?.first,
            cameraBugs = cameraView?.bugArea?.takeIf { n - cameraAt < 5 }.orEmpty(),
            cameraGame = cameraMerged.takeIf { n - cameraAt < 5 }.orEmpty(),
            cameraSees = cameraSights.mapValues { it.value.describe() }.takeIf { n - cameraAt < 5 }.orEmpty(),
            paired = ctrl?.lastCall?.summary?.takeIf { running && n - cameraAt < 5 },
            cameraText = tileRead.mapValues { (_, r) -> r.first.summary.ifBlank { "(no text)" } }.takeIf { n - cameraAt < 5 }.orEmpty(),
            cameraBars = cameraView?.scoreBar?.takeIf { n - cameraAt < 5 }.orEmpty(),
            outlineAsk = outlineAsk?.takeIf { running && n - it.second < 25 && it.first != nav?.current }?.first,
            cameraFind = findStatus,
            tvLinked = linked,
            tvWaiting = tvWaiting,
            tvKeyboard = tvKeyboard,
            askInput = askInput,
            nowOnTv = nowOnTv,
            tvVolume = tvVolume,
            streamDelay = detector.timing.delay.seconds?.toInt(),
            breaksTimed = detector.timing.breaks.count(),
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

    @Volatile private var startedAt = -1e9


    fun start() {
        val old = job
        if (old?.isActive == true && !stopping) {
            // Already running, unless it has been stuck getting started: then start over.
            if (nav != null || now() - startedAt < 45) return
            log("Start was stuck. Starting over.")
            old.cancel()
        }
        startedAt = now()
        error = null
        paused = false
        lockedBox = null
        waitingForMultiview = false
        autoPaused = false
        needResync = false
        holdUntil = 0.0
        job = scope.launch(Dispatchers.IO) {
            // A restart waits for the previous run to let go of the mic (but never forever).
            if (old != null && kotlinx.coroutines.withTimeoutOrNull(8000) { old.join() } == null) {
                log("The last run was stuck. Starting fresh.")
                synchronized(lock) { runCatching { mic?.close() }; mic = null }
            }
            stopping = false
            try {
                runLoop()
            } finally {
                synchronized(detector) { detector.save() }
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
        // The mic starts first: it doesn't need the TV, so a slow TV never leaves it off.
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
        publish(starting = true)
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
                ).also { it.returnAfterBreak = s.returnToGame }
            }
        } catch (e: Exception) {
            error = e.message
            log("Couldn't start: ${e.message}")
            return
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
                // Stopped and started again: the TV's sound is still where it was, not back on box 1.
                val kept = if (!s.homing) keptBox(s) else null
                if (kept != null) synchronized(lock) { nav?.current = kept }
                log("Running. Audio on box ${nav?.current}" + (if (kept != null) " (where it was when you stopped)." else "."))
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
        var loopErrors = 0
        try {
            while (currentCoroutineContext().isActive) {
              try {
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
                val n = now()
                val cur = nav?.current
                val c = ctrl ?: break
                c.micOn = m != null
                val held = n < holdUntil
                val result = if (sec != null) synchronized(detector) {
                    cur?.let { box -> feed?.let { detector.observePlays(box, it.plays(box), n) } }
                    detector.process(sec, n, cur, c.bias(cur, n),
                        learning = !paused && !autoPaused && !waitingForMultiview && !held && lockedBox == null)
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
                rememberBox(s)
                synchronized(lock) {
                    if (lockedBox == null) {
                        try {
                            val from = nav?.current
                            val started = now()
                            if (c.step(heard, strongAd = result?.strongAd == true,
                                    strongWhy = if (result?.strongAd == true) result.why else null,
                                    adPct = result?.adPct ?: lastResult?.adPct)) {
                                m?.drain()
                                noteAppMove(from, nav?.current, started)
                                recordSwitch(from, nav?.current, started)
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
              } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
              } catch (e: Throwable) {
                // One bad second must never stop the listening.
                loopErrors++
                saveCrash(e, "listening loop")
                if (loopErrors <= 3 || loopErrors % 100 == 0) log("Hiccup while listening (${e.javaClass.simpleName}: ${e.message}). Still going.")
                delay(500)
              }
            }
        } finally {
            feedJob.cancel()
            watchJob?.cancel()
            watching = false
        }
    }

    // ---------------------------------------------------------------- where the sound was
    /** Which games are up (a different set means a different multiview, where the sound starts on box 1). */
    private fun gamesKey(s: Settings) = s.slots.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value.eventId}" }
    private var rememberedBox: Int? = null
    private var rememberedAt = -1e9

    /** Notes where the sound is (when it changes, and once a minute), so a stop and start picks up from there. */
    private fun rememberBox(s: Settings) {
        val box = nav?.current ?: return
        val n = now()
        if (box == rememberedBox && n - rememberedAt < 60) return
        rememberedBox = box; rememberedAt = n
        runCatching { java.io.File(app.filesDir, "last_box.txt").writeText("$box|${System.currentTimeMillis()}|${gamesKey(s)}") }
    }

    /** Where the sound was when switching last ran, if that was in the last 20 minutes with the same games. */
    private fun keptBox(s: Settings): Int? = runCatching {
        val (box, at, games) = java.io.File(app.filesDir, "last_box.txt").readText().split("|", limit = 3)
        val b = box.toInt()
        b.takeIf { System.currentTimeMillis() - at.toLong() < 20 * 60_000 && games == gamesKey(s) && b in 1..boxCount(s) }
    }.getOrNull()

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
        val cur = nav?.current
        synchronized(detector) { detector.label(isAd, now(), cur) }
        if (isAd) {
            if (cur != null) { adTapAt[cur] = now(); gameSaidAt.remove(cur); visualGameAt.remove(cur) }
            log("Got it, commercial. Learning this sound.")
            scope.launch(Dispatchers.IO) {
                synchronized(lock) {
                    try {
                        val from = nav?.current
                        val started = now()
                        if (lockedBox == null && ctrl?.commercialNow() == true) {
                            noteAppMove(from, nav?.current, started)
                            recordSwitch(from, nav?.current, started)
                            mic?.drain()
                            synchronized(detector) { detector.onSwitch() }
                        }
                    } catch (e: Exception) {
                        log(e.message ?: "TV error")
                    }
                }
            }
        } else {
            // You're looking at it: this game is on, whatever the camera or ESPN's timing guessed.
            if (cur != null) {
                ctrl?.userSaysGame(cur)
                gameSaidAt[cur] = now(); visualGameAt[cur] = now(); adTapAt.remove(cur)
            }
            log("Got it, game audio. Learning this sound.")
        }
        publish()
    }

    /** Your answer to "Camera saw the sound move to box N. Is it there?" */
    fun answerOutline(yes: Boolean) {
        val ask = outlineAsk ?: return
        outlineAsk = null
        outline.answered(yes)
        if (yes) {
            log("Camera was right: the sound moved to box ${ask.first}.")
            audioIsOn(ask.first)
        } else {
            log("Camera was wrong: the sound didn't move to box ${ask.first}.")
            publish()
        }
    }

    // ---------------------------------------------------------------- camera
    /** What the screen can ask of the camera on the phone (zoom, where to expose). */
    interface CameraControls {
        fun zoom(linear: Float)
        /** Focus and expose on the TV: its center and size (fractions of the upright picture). */
        fun meterOn(cx: Double, cy: Double, size: Double)
        /** Exposure compensation, in stops. */
        fun exposure(ev: Double)
    }
    @Volatile var cameraControls: CameraControls? = null

    /** The latest camera frame, for lining up the grid on the setup screen. */
    @Volatile var cameraFrame: com.rickle8.mvswitch.core.GrayFrame? = null
        private set
    /** A color picture of what the camera sees, while the setup screen is open. */
    @Volatile var cameraColor: android.graphics.Bitmap? = null
        private set
    /** The setup screen is open and wants the color picture. */
    @Volatile var wantCameraColor = false
    @Volatile private var cameraView: com.rickle8.mvswitch.core.CameraView? = null
    @Volatile private var cameraMerged: Map<Int, Boolean?> = emptyMap()
    @Volatile private var cameraAt = -1e9
    @Volatile private var cameraRotation = -1
    /** When the phone last turned (a re-find waits for it to stay put). */
    private var rotationAt = -1e9
    /** When the last look for the TV ended (it doesn't keep re-looking back to back). */
    @Volatile private var findEndedAt = -1e9
    private var watcher: com.rickle8.mvswitch.core.ScreenWatcher? = null
    private var watcherKey: String? = null
    /** When the app last moved the sound itself (its own outline flash isn't news). */
    @Volatile private var appMovedAt = -1e9
    /** The last box YouTube TV flashed its outline on, and when. */
    @Volatile private var lastOutline: Pair<Int, Double>? = null
    /**
     * The camera saw the outline flash on a box other than where the app thinks the sound is, and
     * the app didn't move it: the box and when. Only asked about on the Live screen, never acted on:
     * a misread here used to send the app's idea of the sound box somewhere else.
     */
    @Volatile private var outlineAsk: Pair<Int, Double>? = null
    private val finder = com.rickle8.mvswitch.core.GridFinder()
    /** Keeps the TV lined up: it looks again in the background every minute, and right after a bump. */
    private val tracker = com.rickle8.mvswitch.core.GridTracker()
    /** Whether the outline flash can be trusted to say where the sound is (proven on the app's own moves). */
    private val outline = com.rickle8.mvswitch.core.OutlineTrust()
    /** Football's green field in each box (proven against the score text and the sound before it counts). */
    private val field = com.rickle8.mvswitch.core.FieldWatch()
    /** Each box's share of field green, as last measured. */
    @Volatile private var greenShares: Map<Int, Double> = emptyMap()
    /** What the game and commercials look like on your TV, learned (see VisualLook). */
    private var visual: com.rickle8.mvswitch.core.VisualLook? = null
    private var visualSavedAt = 0.0
    /** When you tapped "It's an ad", and on which box: that box is a commercial for the next 30 s. */
    private val adTapAt = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    /** When you said a box is the game (for the picture model: 30 s of lessons). */
    private val visualGameAt = java.util.concurrent.ConcurrentHashMap<Int, Double>()

    private fun visualFor(layout: Int): com.rickle8.mvswitch.core.VisualLook {
        visual?.takeIf { it.layoutIs(layout) }?.let { return it }
        val v = com.rickle8.mvswitch.core.VisualLook(layout)
        runCatching {
            java.io.File(app.filesDir, "visual_look.bin").takeIf { it.exists() }?.inputStream()?.use { v.load(it) }
            java.io.File(app.filesDir, "visual_checks.txt").takeIf { it.exists() }?.readText()?.let { v.loadChecks(it) }
        }
        visual = v
        return v
    }

    private fun saveVisual(n: Double, force: Boolean = false) {
        val v = visual ?: return
        if (!force && n - visualSavedAt < 60) return
        visualSavedAt = n
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = java.io.File(app.filesDir, "visual_look.bin.tmp")
                tmp.outputStream().use { v.save(it) }
                tmp.renameTo(java.io.File(app.filesDir, "visual_look.bin"))
                java.io.File(app.filesDir, "visual_checks.txt").writeText(v.checksText())
            }
        }
    }

    /**
     * Teaches the camera's picture model from what's surely the game or surely a commercial on each
     * box right now. Never from the picture model itself, ESPN's timing guesses, or halftime shows.
     */
    private fun teachVisual(v: com.rickle8.mvswitch.core.VisualLook, cur: Int?, n: Double, boxes: Int) {
        val r = lastResult
        val f = feed ?: previewFeed
        for (b in 1..boxes) {
            if (f?.info?.get(b)?.statusName.let { it == "STATUS_HALFTIME" || it == "STATUS_END_PERIOD" }) continue
            val bugNow = bugReads.readWithin(b, n, 3.0)
            val game = bugNow || gameSaidAt[b]?.let { n - it < 30 } == true || visualGameAt[b]?.let { n - it < 30 } == true
            val soundAd = b == cur && r != null && r.heard == Heard.AD && (r.strongAd || (r.adPct ?: 0) >= 78) &&
                !bugReads.readWithin(b, n, 6.0)
            val ad = adTextNow(b, n) || adTapAt[b]?.let { n - it < 30 } == true || soundAd
            if (game == ad) continue
            v.label(b, ad, n)
        }
    }
    /** Yard lines in each box (proven against the score text and the sound before they count). */
    private val lineWatch = com.rickle8.mvswitch.core.LineWatch()
    /** Keeps the TV's whites (the score bug's lettering, the yard lines) from blowing out. */
    private val exposure = com.rickle8.mvswitch.core.ExposureTuner()
    /** What the camera sees on each box right now. */
    @Volatile private var cameraSights: Map<Int, com.rickle8.mvswitch.core.Sight> = emptyMap()
    /** When it last focused and metered on the TV. */
    @Volatile private var meteredAt = -1e9
    @Volatile private var finding = false
    @Volatile private var findSkip = 0
    private var cameraPublished = -1e9
    /** "Looking for the TV…" / "Found the TV" / "Couldn't find…", for the setup screen. */
    @Volatile var findStatus: String? = null
        private set

    fun onCameraColor(b: android.graphics.Bitmap) { cameraColor = b }

    /** The camera phone got bumped (its motion sensor): look for the TV again once it settles. */
    fun cameraBumped() { tracker.bumped(now()) }

    /** Per box, how much of the picture is football-field green right now. */
    fun onCameraField(shares: Map<Int, Double>) {
        val n = now()
        shares.forEach { (b, g) -> field.add(b, g, n) }
        greenShares = shares
    }

    /** Yard lines found in one box (each box gets a look about every 2 s). */
    fun onFieldLines(box: Int, found: com.rickle8.mvswitch.core.FieldLines.Found) {
        lineWatch.add(box, found, now())
    }

    /**
     * The app just moved the sound from [from] to [to] (its keys went out from [start] until now):
     * the outline should flash on [to], which is how the camera proves it reads the outline right.
     */
    private fun noteAppMove(from: Int?, to: Int?, start: Double) {
        appMovedAt = now(); outlineAsk = null
        if (from == null || to == null || from == to) return
        val via = runCatching {
            val nb = nav?.neighbors ?: return@runCatching emptySet<Int>()
            var b: Int = from
            val passed = HashSet<Int>()
            for (k in com.rickle8.mvswitch.core.keyPath(nb, from, to).dropLast(1)) { b = nb[b]?.get(k) ?: b; passed += b }
            passed
        }.getOrDefault(emptySet())
        outline.appMoved(to, via, start, now())
    }

    // What the phone's text recognition reads in each box.
    private val tileRead = java.util.concurrent.ConcurrentHashMap<Int, Pair<com.rickle8.mvswitch.core.TileRead, Double>>()
    /** Each box's score bug read over time: when, how dependably, how long it's been gone. */
    private val bugReads = com.rickle8.mvswitch.core.BugReads()
    /** Per box: when it last read commercial text there (a web address, fine print) with no score bug. */
    private val tileAdAt = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    /** Per box: when it last read a scores ticker there (other games: says nothing about this one). */
    private val tileTickerAt = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    /** Where each box's ticker scrolls, so even one of your games going by on it isn't read as this box's game. */
    private val tickers = com.rickle8.mvswitch.core.TickerBands()

    private val tileMismatchLogged = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Where the grid is and the layout, for the text reader (null when the camera isn't set up). */
    fun cameraGrid(): Pair<List<Pair<Double, Double>>, Int>? {
        val s = _settings.value
        // Not while the phone is turned from where the grid was lined up (it re-finds the TV after 3 s).
        if (!s.useCamera || finding || s.gridRotation < 0 || (cameraRotation >= 0 && cameraRotation != s.gridRotation)) return null
        return s.gridCorners.chunked(2).map { it[0] to it[1] } to s.layout
    }

    /** The text read off one box: a score bug means that box is showing its game. */
    fun onTileText(box: Int, lines: List<com.rickle8.mvswitch.core.TileLine>) = runCatching { tileText(box, lines) }.let { }

    private fun tileText(box: Int, lines: List<com.rickle8.mvswitch.core.TileLine>) {
        val n = now()
        val f = feed ?: previewFeed
        val teams = f?.teamWords().orEmpty()
        val scores = f?.info?.mapValues { it.value.scores.values.toList() }.orEmpty()
        val sides = f?.teamSides().orEmpty()
        tickers.update(box, lines, n, ownWords = sides[box].orEmpty().flatten().flatMap { it.split(' ') }.toSet())
        val r = com.rickle8.mvswitch.core.TileText.read(lines, teams, scores, own = box, sides = sides,
            tickerBand = tickers.band(box, n), steady = { tickers.steady(box, it) })
        tileRead[box] = r to n
        bugReads.add(box, n, r.game, r.notLive)
        if (r.ad) tileAdAt[box] = n
        if (r.ticker) tileTickerAt[box] = n
        // The games in the Games tab in a different order than on the TV? Say so once.
        val other = r.showsGameOf
        if (r.game && other != null && other != box) {
            val key = "$box>$other"
            if (tileMismatchLogged.add(key)) {
                val name = _settings.value.slots[other]?.let { it.name.ifBlank { it.team } } ?: "box $other's"
                log("Camera: box $box on the TV is showing the $name game (it's set as box $other in Games).")
            }
        }
    }

    /**
     * The score bug's look (its shape, learned per box) only decides anything on a box once it
     * has matched what's read off the screen both ways: up while the score text reads, and gone
     * in a commercial. Until then it's only shown on the setup screen. When something surer
     * contradicts it, what it learned for that box is thrown out and learned again. (A look that
     * latched onto something that never goes away, like the edge of the box, would otherwise call
     * every commercial "the game".)
     */
    private val bugUp = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val bugGone = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val bugCheckedAt = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    private val bugDoubtLogged = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    /** Boxes whose learned score bug turned out wrong: forgotten on the camera's next frame. */
    private val bugForget = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    /** You said "It's the game" on this box: if the camera still says commercial a bit later, it's wrong. */
    private val gameSaidAt = java.util.concurrent.ConcurrentHashMap<Int, Double>()

    private fun bugTrusted(box: Int) = (bugUp[box] ?: 0) >= 2 && (bugGone[box] ?: 0) >= 1

    private fun forgetBugChecks() { bugUp.clear(); bugGone.clear(); bugCheckedAt.clear() }

    private fun doubtBug(box: Int, why: String, n: Double) {
        bugUp.remove(box); bugGone.remove(box); bugCheckedAt.remove(box)
        bugForget.add(box)
        if (n - (bugDoubtLogged[box] ?: -1e9) > 120) {
            bugDoubtLogged[box] = n
            log("Camera: box $box's score bug was learned wrong ($why). Learning it again.")
        }
    }

    /**
     * What the text on a box says: the game (its score bug read in the last 10 s), a commercial
     * (it reads this box's score bug normally but nothing for 45 s, longer than replays and
     * close-ups, while it still reads other boxes, so the camera can see), or not sure.
     */
    private fun textSays(box: Int, n: Double): Boolean? {
        // Commercial text read lately (and no score bug since): a commercial, no waiting.
        if (adTextNow(box, n)) return false
        if (bugReads.readWithin(box, n, 10.0)) return true
        return if ((bugGoneSecs(box, n) ?: 0.0) >= 45) false else null
    }

    private fun adTextNow(box: Int, n: Double): Boolean {
        val adAt = tileAdAt[box] ?: return false
        return n - adAt < 10 && (bugReads.gameAt(box) ?: -1e9) < adAt
    }

    /** How long this box's score bug has been missing, when that means something (see [BugReads.goneSecs]). */
    private fun bugGoneSecs(box: Int, n: Double): Double? {
        // Halftime: the score bug is off for much of the studio show, so its absence says nothing.
        if ((feed ?: previewFeed)?.info?.get(box)?.statusName == "STATUS_HALFTIME") return null
        return bugReads.goneSecs(box, n)
    }

    /** What the camera sees on a box right now, for pairing with the sound. */
    private fun sightFor(box: Int, v: com.rickle8.mvswitch.core.CameraView, n: Double) =
        com.rickle8.mvswitch.core.Sight(
            bug = bugReads.readWithin(box, n, 6.0),
            bugGoneSecs = bugGoneSecs(box, n),
            lines = lineWatch.trusted(box) && lineWatch.linesNow(box, n),
            grass = field.trusted(box) && field.fieldNow(box, n),
            adText = adTextNow(box, n),
            bugShape = if (bugTrusted(box)) v.showingGame[box] else null,
            look = runCatching { visual?.adChance(box, n) }.getOrNull(),
        )

    /** When each contradiction started (it has to last a few seconds: the look lags the text by a moment). */
    private val doubtSince = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** Checks each box's score bug look against what's read on screen (and the sound, and you), every frame. */
    private fun checkBugs(v: com.rickle8.mvswitch.core.CameraView, cur: Int?, onSound: Boolean, n: Double, boxes: Int) {
        val r = lastResult
        for (b in 1..boxes) {
            val bug = v.showingGame[b] ?: continue
            val text = textSays(b, n)
            val textNow = bugReads.readWithin(b, n, 3.0)
            val here = onSound && b == cur
            val knownAd = here && r != null && r.heard == Heard.AD && r.strongAd
            val saidGame = here && gameSaidAt[b]?.let { n - it in 8.0..20.0 } == true
            fun lasted(kind: String, cond: Boolean, secs: Double): Boolean {
                val key = "$b:$kind"
                if (!cond) { doubtSince.remove(key); return false }
                val since = doubtSince.getOrPut(key) { n }
                if (n - since < secs) return false
                doubtSince.remove(key)
                return true
            }
            when {
                // It reads the score on screen, but the look says the bug's gone (it comes back within ~3 s).
                lasted("text", !bug && textNow, 8.0) -> doubtBug(b, "it reads the game's score on screen", n)
                lasted("commercial", bug && text == false, 4.0) -> doubtBug(b, "it's still there in a commercial", n)
                lasted("ad", bug && knownAd && (v.scoreBar[b] ?: 0) >= 55, 4.0) ->
                    doubtBug(b, "it's still there in a commercial it knows", n)
                !bug && saidGame -> { gameSaidAt.remove(b); doubtBug(b, "you said it's the game", n) }
                n - (bugCheckedAt[b] ?: -1e9) < 5 -> Unit
                bug && textNow -> { bugCheckedAt[b] = n; bugUp.merge(b, 1, Int::plus) }
                !bug && text == false -> { bugCheckedAt[b] = n; bugGone.merge(b, 1, Int::plus) }
            }
        }
    }

    /** Checks each box's field green against what's read on screen and heard, every frame. */
    private fun checkField(cur: Int?, onSound: Boolean, n: Double, boxes: Int) {
        val r = lastResult
        for (b in 1..boxes) {
            val here = onSound && b == cur
            val textNow = bugReads.readWithin(b, n, 3.0)
            val heard = if (here) r?.heard else null
            val sureAd = (here && r != null && r.heard == Heard.AD && r.strongAd) ||
                (tileAdAt[b]?.let { n - it < 5 } == true)
            val game = textNow || heard == Heard.GAME
            val commercial = textSays(b, n) == false || heard == Heard.AD
            field.check(b, n, game = game, commercial = commercial, sureAd = sureAd)
            lineWatch.check(b, n, game = game, commercial = commercial, sureAd = sureAd)
        }
    }

    /** Look for the TV in the camera picture (a few seconds, while the games play). */
    fun findTv() {
        synchronized(finder) { finder.reset() }
        findSkip = 4 // let the picture settle (after a zoom or a turn) before comparing frames
        finding = true
        findStatus = "Looking for the TV… keep the games playing."
        publish()
    }

    /** A frame from the camera watching the TV (about 3 a second), and which way the phone is turned. */
    fun onCameraFrame(frame: com.rickle8.mvswitch.core.GrayFrame, rotation: Int) {
        cameraFrame = frame
        val s = _settings.value
        if (!s.useCamera) return
        val n = now()
        // Keep the screens current even when switching isn't running (setting up the camera).
        if (job?.isActive != true && n - cameraPublished > 0.9) { cameraPublished = n; publish() }
        // Turned the phone (and left it that way, not a wobble), or never lined up: find the TV again on its own.
        if (rotation != cameraRotation) { cameraRotation = rotation; rotationAt = n }
        if (s.gridRotation != rotation && !finding && n - rotationAt > 3 && n - findEndedAt > 30) findTv()
        if (finding) {
            if (findSkip > 0) { findSkip--; return }
            val corners = synchronized(finder) {
                finder.add(frame)
                if (finder.ready) finder.find() else null
            }
            when {
                corners != null -> {
                    finding = false
                    findEndedAt = n
                    val flat = corners.flatMap { listOf(it.first, it.second) }
                    update { it.copy(gridCorners = flat, foundCorners = flat, gridRotation = rotation) }
                    tracker.reset(n)
                    findStatus = "Found the TV. Drag a dot if a corner's a little off."
                    log("Camera found the TV.")
                    meterOnGrid(flat)
                    publish()
                }
                finder.frames > 75 -> {
                    finding = false
                    findEndedAt = n
                    findStatus = "Couldn't find the TV. Make sure the whole TV is in view and the games are playing, then try again."
                    publish()
                }
            }
            return
        }
        // Every minute, and right after a bump, it looks for the TV again in the background (the
        // camera keeps reading meanwhile). If the TV moved in the picture, it lines it up again.
        if (s.gridRotation == rotation) {
            val base = (s.foundCorners.takeIf { it.size == 8 } ?: s.gridCorners).chunked(2).map { it[0] to it[1] }
            val moved = tracker.frame(frame, n, base)
            if (moved != null) {
                val flat = moved.flatMap { listOf(it.first, it.second) }
                update { it.copy(gridCorners = flat, foundCorners = flat, gridRotation = rotation) }
                log("Camera: the TV moved in the picture. Lined it up again.")
                meterOnGrid(flat)
                return
            }
        }
        // A tiny nudge of a corner doesn't throw away what it learned.
        val key = "${s.layout}:${s.gridCorners.map { kotlin.math.round(it * 100).toInt() }}"
        if (watcher == null || watcherKey != key) {
            watcher = com.rickle8.mvswitch.core.ScreenWatcher(s.layout); watcherKey = key
            visual?.clearLooks()
            forgetBugChecks()
            field.clear()
            lineWatch.clear()
            meterOnGrid(s.gridCorners)
        }
        while (true) { val b = bugForget.poll() ?: break; watcher?.forgetBug(b) }
        val corners = s.gridCorners.chunked(2).map { it[0] to it[1] }
        val img = runCatching { com.rickle8.mvswitch.core.warpGrid(frame, corners) }.getOrNull() ?: return
        // Keep the TV's whites from blowing out (and its picture from going murky), and refocus now and then.
        val (clipped, mean) = com.rickle8.mvswitch.core.ExposureTuner.measure(img)
        exposure.update(clipped, mean, n)?.let { ev -> cameraControls?.exposure(ev) }
        if (n - meteredAt > 600) meterOnGrid(s.gridCorners)
        val c = ctrl
        val cur = nav?.current
        val running = job?.isActive == true && !paused && !autoPaused && !waitingForMultiview && lockedBox == null
        // Score bars are only learned from boxes known to be showing the game.
        val gameLikely = (1..boxCount(s)).filter { b ->
            (b == cur && lastResult?.heard == Heard.GAME) ||
                bugReads.readWithin(b, n, 10.0) ||
                (c != null && c.outlook(b, n, ignoreCamera = true).first == com.rickle8.mvswitch.core.Controller.Outlook.LIVE)
        }.toSet()
        // ...and never from one known to be in commercial.
        val adLikely = (1..boxCount(s)).filter { b ->
            (b == cur && lastResult?.heard == Heard.AD) || textSays(b, n) == false ||
                (c != null && c.outlook(b, n, ignoreCamera = true).first == com.rickle8.mvswitch.core.Controller.Outlook.BREAK)
        }.toSet() - gameLikely
        val v = watcher!!.frame(img, gameLikely, adLikely)
        cameraView = v
        // The picture model: measure every box, learn from what's sure, save now and then.
        runCatching {
            val look = visualFor(s.layout)
            look.frame(img, n, greenShares)
            teachVisual(look, cur, n, boxCount(s))
            saveVisual(n)
        }
        cameraAt = n
        checkBugs(v, cur, job?.isActive == true, n, boxCount(s))
        checkField(cur, job?.isActive == true, n, boxCount(s))
        // What the camera sees on each box: its score bug, yard lines, the field, commercial text. The
        // switching logic pairs it with the sound on the box you're hearing, and uses its word on the others.
        val sights = (1..boxCount(s)).associateWith { b -> sightFor(b, v, n) }
        cameraSights = sights
        cameraMerged = sights.mapValues { it.value.word }
        c?.sights = n to sights
        // YouTube TV flashes an outline on the box the sound moves to. Every move the app makes
        // checks it; once it has proven it reads the outline right, a flash the app didn't cause
        // (you, with the TV remote) moves the app's sound box too. Until then it only asks.
        outline.tick(n)
        val flash = v.outlineAppeared
        if (flash != null) {
            lastOutline = flash to n
            when (outline.flash(flash, n, cur)) {
                com.rickle8.mvswitch.core.OutlineTrust.Action.FOLLOW -> if (job?.isActive == true && cur != null) {
                    synchronized(lock) {
                        nav?.current = flash
                        c?.lastSwitch = n
                        c?.returnTo = null
                    }
                    synchronized(detector) { detector.onSwitch() }
                    outlineAsk = null
                    log("Camera: you moved the sound to box $flash. Going with it.")
                }
                com.rickle8.mvswitch.core.OutlineTrust.Action.ASK -> if (running && cur != null) outlineAsk = flash to n
                com.rickle8.mvswitch.core.OutlineTrust.Action.NOTHING -> if (flash == cur) outlineAsk = null
            }
        }
    }

    /** Expose and focus on the TV, not the room. */
    private fun meterOnGrid(flat: List<Double>) {
        if (flat.size != 8) return
        meteredAt = now()
        val xs = listOf(flat[0], flat[2], flat[4], flat[6]); val ys = listOf(flat[1], flat[3], flat[5], flat[7])
        val size = (maxOf(xs.max() - xs.min(), ys.max() - ys.min()) * 0.9).coerceIn(0.15, 0.9)
        cameraControls?.meterOn(xs.average(), ys.average(), size)
    }

    fun setCameraZoom(z: Float) {
        cameraControls?.zoom(z)
        update { it.copy(cameraZoom = z) }
        findTv() // the TV moved in the picture
    }

    private var cameraErrorLogged: String? = null
    fun cameraError(msg: String) {
        if (cameraErrorLogged != msg) { cameraErrorLogged = msg; log("Camera problem: $msg") }
    }

    fun setGridCorners(corners: List<Double>) {
        update { it.copy(gridCorners = corners, gridRotation = cameraRotation) }
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
        // Multiview just put up: the sound starts on box 1 (with homing on, the resync presses its way there).
        synchronized(lock) { if (!_settings.value.homing) nav?.current = 1 }
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
                val from = n.current
                val started = now()
                r.press(key)
                // In multiview the arrows move the audio: keep track of where it went.
                n.current = simulate(n.neighbors, n.current ?: 1, listOf(key))
                noteAppMove(from, n.current, started)
                ctrl?.lastSwitch = now()
                ctrl?.returnTo = null
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

    fun inputName(n: Int) = _settings.value.inputNames[n]?.takeIf { it.isNotBlank() }
        ?: "HDMI $n"

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
                    val from = nav?.current
                    val started = now()
                    nav?.goTo(box)
                    noteAppMove(from, box, started)
                    ctrl?.let { it.lastSwitch = now(); it.breakUntil.remove(box); it.returnTo = null }
                    synchronized(detector) { detector.onSwitch() }
                }
                log(if (lock) "Locked on box $box" else "You switched to box $box")
            } catch (e: Exception) {
                log(e.message ?: "TV error")
            }
        }
    }

    /** You told it the audio is already on this box (no keys pressed): it carries on from there. */
    fun audioIsOn(box: Int) {
        if (job?.isActive != true) return
        outlineAsk = null
        synchronized(lock) {
            nav?.current = box
            ctrl?.lastSwitch = now()
            ctrl?.returnTo = null
        }
        synchronized(detector) { detector.onSwitch() }
        log("Got it: the audio is on box $box.")
        publish()
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

    // ---------------------------------------------------------------- your phone as the remote
    /** The camera picture in color for a few seconds after the phone page asks for it. */
    @Volatile private var remoteColorUntil = -1e9

    /** The camera should make the color picture (the setup screen or the phone page is showing it). */
    fun wantsColor(): Boolean = wantCameraColor || now() < remoteColorUntil

    /** What the camera sees right now, as a JPEG (in color when it has one). */
    fun cameraJpeg(): ByteArray? {
        remoteColorUntil = now() + 5
        val f = cameraFrame ?: return null
        // The color picture is sharper than the gray one; it only has to be turned the same way.
        val color = cameraColor?.takeIf { (it.width > it.height) == (f.width > f.height) && !it.isRecycled }
        val bmp = color ?: android.graphics.Bitmap.createBitmap(
            IntArray(f.width * f.height) { i -> val g = f.pixels[i].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g },
            f.width, f.height, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }

    private fun chip(v: BoxView): String {
        val info = v.info
        return when {
            v.slot == null -> "EMPTY"
            v.slot.isOther -> "OTHER"
            v.noGame -> "NO GAME"
            info == null -> ""
            info.status == GameStatus.FINAL -> "FINAL"
            info.status == GameStatus.PREGAME -> "LATER"
            info.statusName == "STATUS_HALFTIME" -> "HALFTIME"
            v.onBreak || info.status == GameStatus.BREAK -> "BREAK"
            info.status == GameStatus.LIVE -> "LIVE"
            else -> ""
        }
    }

    private fun statusText(st: EngineState): String = when {
        st.tvWaiting != null && (st.running || st.starting) -> st.tvWaiting
        st.starting -> "Connecting to the TV…"
        !st.running -> "Not running"
        st.waitingForMultiview -> "Waiting for multiview"
        st.autoPaused -> "Paused · YouTube TV isn't on screen"
        st.paused -> "Paused"
        st.held -> "Waiting while you use the remote"
        st.lockedBox != null -> "Locked on box ${st.lockedBox}"
        else -> "Auto switching · sound on box ${st.currentBox ?: "?"}"
    }

    /** Everything the phone page shows, as JSON. */
    fun remoteState(): String {
        val st = _state.value
        val s = _settings.value
        val j = JSONObject()
            .put("version", appVersion())
            .put("running", st.running).put("starting", st.starting)
            .put("paused", st.paused || st.autoPaused)
            .put("locked", st.lockedBox ?: 0)
            .put("box", st.currentBox ?: 0)
            .put("status", statusText(st))
            .put("heard", when (st.heard) { Heard.GAME -> "game"; Heard.AD -> "commercial"; Heard.UNSURE -> "unsure" })
            .put("adPct", st.adPct ?: -1)
            .put("why", st.why ?: "")
            .put("paired", st.paired ?: "")
            .put("mic", st.micOn)
            .put("layout", s.layout)
            .put("ask", st.outlineAsk ?: 0)
            .put("cameraLive", st.cameraLive)
            .put("corners", org.json.JSONArray(s.gridCorners))
            .put("error", st.error ?: "")
            .put("remoteMsg", st.remoteMsg ?: "")
            .put("log", org.json.JSONArray(st.log.take(15).map { "${it.time}  ${it.text}" }))
            .put("lastSwitch", lastSwitchMade()?.let { sw ->
                JSONObject().put("ago", (now() - sw.t).toInt()).put("from", sw.from).put("to", sw.to)
                    .put("why", sw.why).put("verdict", sw.verdict ?: "")
            } ?: JSONObject())
        val boxes = org.json.JSONArray()
        val list = st.boxes.ifEmpty { (1..boxCount(s)).map { BoxView(it, s.slots[it], null, 0.0, false) } }
        for (b in list) boxes.put(JSONObject()
            .put("box", b.box)
            .put("name", b.slot?.name?.ifBlank { null } ?: b.slot?.team ?: "")
            .put("chip", chip(b))
            .put("detail", b.info?.detail ?: "")
            .put("camera", when (st.cameraGame[b.box]) { true -> "game"; false -> "commercial"; null -> "" })
            .put("sees", st.cameraSees[b.box] ?: "")
            .put("reads", st.cameraText[b.box] ?: ""))
        j.put("boxes", boxes)
        return j.toString()
    }

    /** A button pressed on the phone page. Returns what went wrong, or null. */
    fun remoteCommand(a: String, n: Int?, v: String? = null): String? {
        val running = job?.isActive == true
        val box = n?.takeIf { it in 1..boxCount(_settings.value) }
        fun needsRun(block: () -> Unit): String? = if (!running) "Switching isn't running. Tap Start." else { block(); null }
        fun needsBox(block: (Int) -> Unit): String? = needsRun { } ?: if (box == null) "Which box?" else { block(box); null }
        return when (a) {
            "ad" -> needsRun { label(true) }
            "game" -> needsRun { label(false) }
            "on" -> needsBox { audioIsOn(it) }
            "play" -> needsBox { switchTo(it) }
            "lock" -> needsBox { switchTo(it, lock = true) }
            "unlock" -> { unlock(); null }
            "pause" -> needsRun { setPaused(true) }
            "resume" -> needsRun { setPaused(false) }
            "yes" -> { answerOutline(true); null }
            "no" -> { answerOutline(false); null }
            "find" -> { findTv(); null }
            "start" -> if (running) null else runCatching { SwitcherService.start(app); null }
                .getOrElse { "Couldn't start from here. Open the app on the camera phone." }
            "stop" -> { SwitcherService.stop(app); null }
            "key" -> runCatching { Key.valueOf(v.orEmpty().uppercase()) }.getOrNull()?.let { k -> press(k); null } ?: "That button isn't known"
            "forgetSound" -> { resetLearning(); null }
            "forgetPicture" -> { visual?.forget(); saveVisual(now(), force = true); log("Camera: forgot what the game and commercials look like."); null }
            "fill" -> { scope.launch { val k = fillEmptyBoxes(); log(if (k > 0) "Filled $k empty boxes with games on now." else "No games to fill the empty boxes with.") }; null }
            "boxGame" -> if (box == null) "Which box?" else { teachBox(box, false); null }
            "boxAd" -> if (box == null) "Which box?" else { teachBox(box, true); null }
            "good" -> switchFeedback("good", null)
            "slow" -> switchFeedback("slow", v?.toIntOrNull())
            "wrong" -> switchFeedback("wrong", null)
            "note" -> note(v.orEmpty())
            "clearBox" -> if (box == null) "Which box?" else { update { st -> st.copy(slots = st.slots - box) }; null }
            else -> "That button isn't known"
        }
    }

    // ------------------------------------------------- your feedback on switches, and notes
    /** A switch the app made: when, from where to where, why, and how ad-like the sound was. */
    private data class SwitchMade(val t: Double, val from: Int, val to: Int, val why: String, val adPct: Int?, var verdict: String? = null)
    private val switches = ArrayDeque<SwitchMade>()
    /** Your feedback and notes, newest first, kept across restarts (for the diagnostics). */
    private val feedback = ArrayDeque<String>()
    private var feedbackLoaded = false

    private fun recordSwitch(from: Int?, to: Int?, started: Double) {
        if (from == null || to == null || from == to) return
        val why = synchronized(logLines) { logLines.firstOrNull()?.text }.orEmpty()
        synchronized(switches) {
            switches.addFirst(SwitchMade(started, from, to, why, lastResult?.adPct))
            while (switches.size > 20) switches.removeLast()
        }
    }

    private fun lastSwitchMade(): SwitchMade? = synchronized(switches) { switches.firstOrNull() }

    private fun keepFeedback(line: String) {
        val f = java.io.File(app.filesDir, "feedback.txt")
        synchronized(feedback) {
            if (!feedbackLoaded) {
                feedbackLoaded = true
                runCatching { f.readLines().takeLast(60).reversed().forEach { feedback.addLast(it) } }
            }
            feedback.addFirst("${stamp()}  $line")
            while (feedback.size > 60) feedback.removeLast()
            val all = feedback.toList().reversed()
            scope.launch(Dispatchers.IO) { runCatching { f.writeText(all.joinToString("\n")) } }
        }
    }

    private fun nudgeSensitivity(by: Float): String {
        var to = 0f
        update { st -> st.copy(micSensitivity = (st.micSensitivity + by).coerceIn(0.2f, 0.85f)).also { to = it.micSensitivity } }
        return "${(to * 100).toInt()}%"
    }

    /**
     * Your word on the last switch the app made:
     * - good: right call (the seconds before it are learned as the commercial);
     * - slow: right, but [secs] late (those seconds are learned as the commercial, and it gets a bit quicker);
     * - wrong: it wasn't a commercial (back to that game, the sound before it learned as the game, a bit less quick).
     */
    fun switchFeedback(kind: String, secs: Int?): String? {
        val sw = lastSwitchMade() ?: return "It hasn't made a switch yet."
        val n = now()
        val ago = (n - sw.t).toInt()
        if (ago > 600) return "Its last switch was over 10 minutes ago."
        val what = "box ${sw.from} → ${sw.to} ${ago}s ago (${sw.why.take(60)}${sw.adPct?.let { ", $it% ad" } ?: ""})"
        when (kind) {
            "good" -> {
                synchronized(detector) { detector.labelSpan(true, sw.t - 6, sw.t, sw.from) }
                sw.verdict = "good"
                keepFeedback("Good switch: $what")
                log("Thanks: good switch.")
            }
            "slow" -> {
                val late = (secs ?: 10).coerceIn(3, 60)
                synchronized(detector) { detector.labelSpan(true, sw.t - late, sw.t, sw.from) }
                val sens = nudgeSensitivity(if (late >= 15) 0.05f else 0.03f)
                sw.verdict = "slow ${late}s"
                keepFeedback("Too slow by about $late s: $what. Quickness now $sens.")
                log("Got it: about $late s late. Learning that commercial and calling them a bit quicker ($sens).")
            }
            "wrong" -> {
                synchronized(detector) { detector.labelSpan(false, sw.t - 15, sw.t, sw.from) }
                val sens = nudgeSensitivity(-0.03f)
                sw.verdict = "wrong"
                keepFeedback("Wrong switch (not a commercial): $what. Quickness now $sens.")
                log("Got it: box ${sw.from} wasn't in commercial. Going back, and calling them a bit less quickly ($sens).")
                gameSaidAt[sw.from] = n; visualGameAt[sw.from] = n
                ctrl?.userSaysGame(sw.from)
                if (nav?.current != sw.from) switchTo(sw.from)
            }
            else -> return "That isn't known"
        }
        return null
    }

    /** Something you typed or said on the phone page: kept with what was going on, for the diagnostics. */
    fun note(text: String): String? {
        val t = text.trim().take(500)
        if (t.isEmpty()) return "Nothing to save"
        val sw = lastSwitchMade()
        val r = lastResult
        val ctx = "sound on box ${nav?.current ?: "?"}, " +
            (r?.let { "heard ${it.heard.name.lowercase()}${it.adPct?.let { p -> " $p% ad" } ?: ""}, " } ?: "") +
            (sw?.let { "last switch box ${it.from} → ${it.to} ${(now() - it.t).toInt()}s ago" } ?: "no switch yet")
        keepFeedback("Note: \"$t\" [$ctx]")
        log("Saved your note.")
        return null
    }

    /** The camera's lesson on a box you aren't hearing (or are): it's the game or a commercial, without moving the sound. */
    fun teachBox(box: Int, isAd: Boolean) {
        val n = now()
        if (isAd) {
            adTapAt[box] = n; visualGameAt.remove(box); gameSaidAt.remove(box)
            if (box != nav?.current) ctrl?.userSaysAd(box)
            log("Camera: learning box $box as a commercial.")
        } else {
            visualGameAt[box] = n; adTapAt.remove(box)
            ctrl?.userSaysGame(box)
            log("Camera: learning box $box as the game.")
        }
        publish()
    }

    // ------------------------------------------------- settings and games from the phone page
    /** The settings the phone page can change, the games set for each box, and what's been learned. */
    fun remoteConfig(): String {
        val s = _settings.value
        val d = synchronized(detector) { detector.model.gameSeconds to detector.model.adSeconds }
        val j = JSONObject()
            .put("useMic", s.useMic).put("micSensitivity", s.micSensitivity.toDouble())
            .put("proactive", s.proactive).put("returnToGame", s.returnToGame)
            .put("useCamera", s.useCamera).put("cameraZoom", s.cameraZoom.toDouble())
            .put("layout", s.layout).put("breakSecs", s.breakSecs)
            .put("autoPause", s.autoPause).put("openYouTubeTv", s.openYouTubeTv)
            .put("hopping", s.hopping.name)
            .put("corners", org.json.JSONArray(s.gridCorners))
            .put("soundLearned", "${d.first} s game, ${d.second} s commercials")
            .put("pictureLearned", visual?.describe() ?: "not started")
        val slots = org.json.JSONArray()
        for (b in 1..boxCount(s)) {
            val g = s.slots[b]
            slots.put(JSONObject().put("box", b).put("name", g?.let { it.name.ifBlank { it.team } } ?: "")
                .put("other", g?.isOther == true))
        }
        return j.put("slots", slots).toString()
    }

    /** One setting changed on the phone page. Returns what went wrong, or null. */
    fun remoteSet(name: String, value: String): String? {
        fun bool() = value == "true" || value == "1"
        fun num() = value.toDoubleOrNull()
        when (name) {
            "useMic" -> update { it.copy(useMic = bool()) }
            "micSensitivity" -> num()?.let { v -> update { it.copy(micSensitivity = v.toFloat().coerceIn(0f, 1f)) } } ?: return "Not a number"
            "proactive" -> update { it.copy(proactive = bool()) }
            "returnToGame" -> update { it.copy(returnToGame = bool()) }
            "useCamera" -> update { it.copy(useCamera = bool()) }
            "autoPause" -> update { it.copy(autoPause = bool()) }
            "openYouTubeTv" -> update { it.copy(openYouTubeTv = bool()) }
            "breakSecs" -> num()?.let { v -> update { it.copy(breakSecs = v.toInt().coerceIn(60, 300)) } } ?: return "Not a number"
            "layout" -> value.toIntOrNull()?.takeIf { it in LAYOUTS }?.let { v -> update { it.copy(layout = v) } } ?: return "Not a layout"
            "hopping" -> runCatching { com.rickle8.mvswitch.data.Hopping.valueOf(value) }.getOrNull()
                ?.let { h -> update { it.copy(hopping = h) } } ?: return "Not known"
            "cameraZoom" -> num()?.let { v -> setCameraZoom(v.toFloat().coerceIn(0f, 1f)) } ?: return "Not a number"
            "corners" -> {
                val c = value.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (c.size != 8 || c.any { it !in -0.05..1.05 }) return "Those corners don't look right"
                setGridCorners(c.map { it.coerceIn(0.0, 1.0) })
                log("Camera: corners set from your phone.")
            }
            else -> return "That setting isn't known"
        }
        return null
    }

    /** Games found for the phone page's search, by id, so a tap can set one. */
    private val remoteFound = java.util.concurrent.ConcurrentHashMap<String, ScheduledGame>()

    /** Games to pick from: on now and coming up (blank), or matching a search. */
    fun remoteGames(query: String): String = kotlinx.coroutines.runBlocking {
        val list = runCatching { searchGames(query) }.getOrDefault(emptyList()).take(40)
        val a = org.json.JSONArray()
        for (g in list) {
            remoteFound[g.id] = g
            a.put(JSONObject().put("id", g.id).put("name", g.shortName).put("detail", g.detail)
                .put("network", g.network).put("status", g.status.name))
        }
        a.toString()
    }

    /** Sets box [box] to a game from the phone page's search, or to "something else" by name. */
    fun remoteAssign(box: Int?, id: String?, other: String?): String? {
        val b = box?.takeIf { it in 1..boxCount(_settings.value) } ?: return "Which box?"
        other?.trim()?.takeIf { it.isNotEmpty() }?.let { setOther(b, it); return null }
        val g = id?.let { remoteFound[it] } ?: return "Search again and pick a game."
        update { s ->
            val keep = s.slots[b]
            s.copy(league = g.league, slots = s.slots + (b to GameSlot(
                league = g.league, team = g.home.ifBlank { g.away }, name = g.shortName,
                importance = keep?.importance ?: 1.0, pick = "", eventId = g.id,
            )))
        }
        log("Box $b set to ${g.shortName} from your phone.")
        return null
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
            "paused ${st.paused}, auto-paused ${st.autoPaused}, held ${st.held}")
        sb.appendLine("Games: " + s.slots.entries.sortedBy { it.key }.joinToString { "${it.key}=" + if (it.value.isOther) "other:${it.value.name}" else "${it.value.league}/${it.value.team}#${it.value.eventId}" })
        val n = now()
        sb.appendLine("Sound box (app): ${st.currentBox ?: "-"}, homing ${s.homing}, locked ${st.lockedBox ?: "-"}")
        sb.appendLine("Mic learned: ${detector.model.gameSeconds} s game, ${detector.model.adSeconds} s ads, " +
            "${detector.prints.size} ads remembered, using learned ${detector.model.ready}")
        val c = ctrl
        if (c != null) {
            for (b in 1..boxCount(s)) {
                val (look, why) = runCatching { c.outlook(b, n) }.getOrDefault(Controller.Outlook.UNKNOWN to "?")
                sb.appendLine("Box $b decides: $look${why.ifBlank { null }?.let { " ($it)" } ?: ""}" +
                    (if (c.userSaidGame(b, n)) ", you said game" else "") +
                    (c.breakUntil[b]?.takeIf { it > n }?.let { ", break for ${(it - n).toInt()} s more" } ?: ""))
            }
        }
        if (s.useCamera) {
            sb.appendLine("Camera: " + (if (n - cameraAt < 5) "watching" else "no frames lately") +
                ", phone turned $cameraRotation (lined up at ${s.gridRotation}), corners " +
                s.gridCorners.joinToString(",") { String.format(Locale.US, "%.2f", it) })
            val v = cameraView
            ctrl?.lastCall?.let { sb.appendLine("  Last call: ${it.summary}") }
            sb.appendLine("  Exposure ${String.format(Locale.US, "%+.1f", exposure.ev)} stops, focused ${((n - meteredAt) / 60).toInt()} min ago")
            for (b in 1..boxCount(s)) {
                val text = tileRead[b]
                val look = lineWatch.lastLook(b)
                sb.appendLine("  Box $b sees: ${cameraSights[b]?.describe() ?: "-"} (${String.format(Locale.US, "%+.1f", cameraSights[b]?.points ?: 0.0)})" +
                    ", yard lines " + (look?.let { (f, t) -> "${f.lines} ${(n - t).toInt()} s ago" } ?: "not looked") +
                    lineWatch.counts(b).let { (u, g) -> if (lineWatch.trusted(b)) " (proven)" else " (proving: $u/2 with the game, $g/1 none in a commercial)" })
                sb.appendLine("  Box $b: says " + when (cameraMerged[b]) { true -> "game"; false -> "commercial"; null -> "not sure" } +
                    ", score bug look " + when (v?.showingGame?.get(b)) { true -> "up"; false -> "gone"; null -> "learning" } +
                    (v?.scoreBar?.get(b)?.let { " $it%" } ?: "") +
                    " (matched the text up ${bugUp[b] ?: 0}/2, gone ${bugGone[b] ?: 0}/1)" +
                    ", text " + (text?.let { (r, t) -> "\"${r.summary.take(40)}\" ${(n - t).toInt()} s ago" } ?: "none read") +
                    (bugReads.gameAt(b)?.let { ", score bug text ${(n - it).toInt()} s ago" } ?: "") +
                    (tileTickerAt[b]?.let { ", ticker ${(n - it).toInt()} s ago" } ?: "") +
                    (tileAdAt[b]?.let { ", commercial text ${(n - it).toInt()} s ago" } ?: ""))
            }
            sb.appendLine("  Looked for the TV again ${tracker.looks} times, moved the grid ${tracker.moves}" +
                (if (tracker.lastLookAt > 0) ", last look ${(n - tracker.lastLookAt).toInt()} s ago" else ""))
            sb.appendLine("  Outline checks on the app's own moves: right ${outline.right}, wrong ${outline.wrong}, " +
                "not seen ${outline.missed}; " + (if (outline.trusted) "follows TV-remote moves" else "asks"))
            sb.appendLine("  Picture model: " + (visual?.describe() ?: "not started") + "; per box " +
                (1..boxCount(s)).joinToString { b -> "$b " + (cameraSights[b]?.look?.let { "${(it * 100).toInt()}% ad" } ?: "-") })
            sb.appendLine("  Field green proven per box: " + (1..boxCount(s)).joinToString { b ->
                val (u, g) = field.counts(b); "$b ${if (field.trusted(b)) "yes" else "$u/3 up, $g/1 gone"}" })
            sb.appendLine("  Outline last flashed on " + (lastOutline?.let { (b, t) -> "box $b, ${(n - t).toInt()} s ago" } ?: "no box yet"))
        }
        synchronized(feedback) {
            if (!feedbackLoaded) {
                feedbackLoaded = true
                runCatching { java.io.File(app.filesDir, "feedback.txt").readLines().takeLast(60).reversed().forEach { feedback.addLast(it) } }
            }
            if (feedback.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Your feedback and notes (newest first):")
                feedback.take(25).forEach { sb.appendLine(it) }
            }
        }
        java.io.File(app.filesDir, "last_error.txt").takeIf { it.exists() }?.let { f ->
            sb.appendLine()
            sb.appendLine("Last error:")
            sb.appendLine(runCatching { f.readText() }.getOrDefault("?"))
        }
        sb.appendLine()
        sb.appendLine("TV connection log (newest first):")
        synchronized(tvLines) { tvLines.forEach { sb.appendLine("${it.time}  ${it.text}") } }
        sb.appendLine()
        sb.appendLine("Activity (newest first):")
        synchronized(logLines) { logLines.forEach { sb.appendLine("${it.time}  ${it.text}") } }
        sb.toString()
    }
}
