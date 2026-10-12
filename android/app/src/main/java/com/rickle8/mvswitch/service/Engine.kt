package com.rickle8.mvswitch.service

import android.content.Context
import android.os.SystemClock
import com.rickle8.mvswitch.audio.MicClassifier
import com.rickle8.mvswitch.core.Controller
import com.rickle8.mvswitch.core.GameFeed
import com.rickle8.mvswitch.core.GameInfo
import com.rickle8.mvswitch.core.GameSlot
import com.rickle8.mvswitch.core.Heard
import com.rickle8.mvswitch.core.LAYOUTS
import com.rickle8.mvswitch.core.Navigator
import com.rickle8.mvswitch.core.Remote
import com.rickle8.mvswitch.core.ScheduledGame
import com.rickle8.mvswitch.core.Verdict
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
    private var verdict: Verdict? = null
    private var mic: MicClassifier? = null
    private var remote: Remote? = null
    private var previewFeed: GameFeed? = null
    private val logLines = ArrayDeque<LogLine>()
    private val timeFmt = SimpleDateFormat("h:mm:ss", Locale.US)

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
        scope.launch(Dispatchers.IO) { previewLoop() }
    }

    // ---------------------------------------------------------------- settings
    fun update(change: (Settings) -> Settings) {
        val old = _settings.value
        val new = change(old)
        if (new == old) return
        _settings.value = new
        store.save(new)
        if (new.slots.mapValues { it.value.team } != old.slots.mapValues { it.value.team } ||
            new.layout != old.layout) previewFeed = null
        val running = job?.isActive == true
        if (!running) { publish(); return }
        fun games(x: Settings) = x.slots.mapValues { it.value.league to it.value.team }
        val structural = new.tvType != old.tvType || new.tvHost != old.tvHost ||
            new.layout != old.layout || games(new) != games(old) || new.useMic != old.useMic ||
            new.homing != old.homing || new.homePresses != old.homePresses
        if (structural) {
            log("Settings changed, restarting")
            SwitcherService.restart(app)
        } else synchronized(lock) {
            ctrl?.apply {
                games = new.slots
                upgradeMargin = new.hopping.margin
                minDwellSecs = new.hopping.dwellSecs
                assumedBreakSecs = new.breakSecs.toDouble()
                proactive = new.proactive
            }
            verdict?.apply { adThreshold = new.adThreshold; gameThreshold = new.gameThreshold }
        }
    }

    // ---------------------------------------------------------------- data
    private fun fetchBoard(league: String): JSONObject {
        http.newCall(Request.Builder().url(scoreboardUrl(league)).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("ESPN returned ${it.code}")
            return JSONObject(it.body!!.string())
        }
    }

    suspend fun schedule(league: String): List<ScheduledGame> = withContext(Dispatchers.IO) {
        listGames(fetchBoard(league), league)
    }

    private fun boxCount(s: Settings) = LAYOUTS[s.layout]?.size ?: 4

    private fun makeFeed(s: Settings) = GameFeed(s.slots, ::fetchBoard, ::now, pollSecs = 15.0)

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
            BoxView(box, slot, info, excitement(info, slot), onBreak)
        }
        val v = verdict
        _state.value = EngineState(
            running = running,
            starting = starting || (running && nav == null),
            paused = paused,
            lockedBox = lockedBox,
            currentBox = if (running) nav?.current else null,
            heard = if (running && mic == null) Heard.GAME else v?.state ?: Heard.UNSURE,
            adPct = v?.adFraction()?.let { (it * 100).toInt() },
            topSounds = mic?.lastTop?.map { it.first }.orEmpty(),
            micOn = mic != null,
            error = error,
            boxes = boxes,
            log = synchronized(logLines) { logLines.toList() },
            testStatus = testStatus,
        )
    }

    private suspend fun previewLoop() {
        while (true) {
            if (job?.isActive != true && _settings.value.slots.isNotEmpty()) {
                val pf = previewFeed ?: makeFeed(_settings.value).also { previewFeed = it }
                runCatching { pf.poll() }
                publish()
            }
            delay(5_000)
        }
    }

    // ---------------------------------------------------------------- run loop
    fun start() {
        if (job?.isActive == true) return
        error = null
        paused = false
        lockedBox = null
        job = scope.launch(Dispatchers.IO) {
            try {
                runLoop()
            } finally {
                synchronized(lock) {
                    mic?.close(); mic = null
                    (remote as? SamsungRemote)?.close()
                    remote = null; nav = null; ctrl = null; verdict = null; feed = null
                }
                publish()
            }
        }
        publish(starting = true)
    }

    fun stop() {
        job?.cancel()
        log("Stopped")
    }

    private suspend fun runLoop() {
        val s = _settings.value
        try {
            val r = makeRemote(s)
            val n = makeNav(s, r)
            val f = makeFeed(s)
            synchronized(lock) {
                remote = r; nav = n; feed = f
                verdict = Verdict(adThreshold = s.adThreshold, gameThreshold = s.gameThreshold)
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
        try {
            synchronized(lock) { nav?.resync() }
            log("Running. Audio on box ${nav?.current}.")
        } catch (e: Exception) {
            error = e.message
            log(e.message ?: "TV error")
        }
        publish()

        while (currentCoroutineContext().isActive) {
            val m = mic
            val frame = if (m != null) m.nextFrame(now()) else { delay(1000); null }
            if (paused) {
                verdict?.reset()
                publish()
                continue
            }
            val v = verdict ?: break
            val heard = when {
                m == null -> Heard.GAME
                frame != null -> v.add(frame)
                else -> v.state
            }
            synchronized(lock) {
                val c = ctrl ?: return
                if (lockedBox != null) {
                    runCatching { feed?.poll() }
                } else {
                    try {
                        if (c.step(heard)) {
                            m?.drain()
                            v.reset()
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
    }

    // ---------------------------------------------------------------- manual controls
    fun setPaused(p: Boolean) {
        paused = p
        log(if (p) "Paused" else "Resumed")
    }

    fun switchTo(box: Int, lock: Boolean = false) {
        if (job?.isActive != true) return
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(this@Engine.lock) {
                    lockedBox = if (lock) box else null
                    nav?.goTo(box)
                    ctrl?.let { it.lastSwitch = now(); it.breakUntil.remove(box) }
                    verdict?.reset()
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
