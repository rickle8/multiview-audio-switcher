package com.rickle8.mvswitch.data

import android.content.Context
import com.rickle8.mvswitch.audio.DEFAULT_CUE_PHRASES
import com.rickle8.mvswitch.core.GameSlot
import org.json.JSONObject

/** A one-tap button on the Remote tab: open an app or switch to an input. */
data class Shortcut(val kind: String, val label: String, val value: String) {
    companion object {
        const val APP = "app"
        const val INPUT = "input"
    }
}

enum class TvType(val label: String) { SAMSUNG("Samsung TV"), ROKU("Roku"), TEST("Test mode") }

enum class Hopping(val label: String, val margin: Double, val dwellSecs: Double) {
    CALM("Calm", 0.6, 90.0),
    NORMAL("Normal", 0.35, 45.0),
    EAGER("Eager", 0.2, 25.0),
}

data class Settings(
    val tvType: TvType = TvType.SAMSUNG,
    val tvHost: String = "",
    val samsungToken: String? = null,
    val layout: Int = 4,
    val homing: Boolean = true,
    val homePresses: Int = 2,
    val slots: Map<Int, GameSlot> = emptyMap(),
    val useMic: Boolean = true,
    /** 0 = only obvious ads, 1 = jumpy. Maps to the ad threshold. */
    val micSensitivity: Float = 0.5f,
    val proactive: Boolean = true,
    val hopping: Hopping = Hopping.NORMAL,
    val breakSecs: Int = 150,
    val league: String = "football/nfl",
    val githubToken: String = "",
    /** Listen for announcer break phrases ("we'll be right back"). */
    val useCues: Boolean = true,
    val cuePhrases: List<String> = DEFAULT_CUE_PHRASES,
    /** Open YouTube TV on the TV when switching starts (if it isn't already on screen). */
    val openYouTubeTv: Boolean = true,
    /** Pause switching while YouTube TV isn't on screen, so arrows never go to another app. */
    val autoPause: Boolean = true,
    val youTubeTvAppId: String = "",
    /** Samsung TV's network MAC, learned automatically; lets Start turn the TV on. */
    val tvMac: String = "",
    val askedBattery: Boolean = false,
    val shortcuts: List<Shortcut> = emptyList(),
    /** Your names for HDMI inputs, e.g. 2 -> "PS5". */
    val inputNames: Map<Int, String> = emptyMap(),
    /** Phone volume buttons change the TV volume while the Remote tab is open. */
    val volumeButtons: Boolean = true,
    /** Buttons that reach each input from the TV's source menu, taught once (e.g. SOURCE, LEFT*8, RIGHT, SELECT). */
    val inputSteps: Map<Int, List<String>> = emptyMap(),
    /** Whether this TV obeys direct input keys: 1 yes, -1 no, 0 not known yet. */
    val directInputs: Int = 0,
) {
    val adThreshold: Double get() = 0.75 - 0.25 * micSensitivity
    val gameThreshold: Double get() = adThreshold - 0.2

    fun toJson(): JSONObject = JSONObject()
        .put("tvType", tvType.name).put("tvHost", tvHost).put("samsungToken", samsungToken ?: "")
        .put("layout", layout).put("homing", homing).put("homePresses", homePresses)
        .put("useMic", useMic).put("micSensitivity", micSensitivity.toDouble())
        .put("proactive", proactive).put("hopping", hopping.name).put("breakSecs", breakSecs)
        .put("league", league)
        .put("githubToken", githubToken)
        .put("useCues", useCues)
        .put("cuePhrases", org.json.JSONArray(cuePhrases))
        .put("openYouTubeTv", openYouTubeTv)
        .put("autoPause", autoPause)
        .put("youTubeTvAppId", youTubeTvAppId)
        .put("tvMac", tvMac)
        .put("askedBattery", askedBattery)
        .put("shortcuts", org.json.JSONArray(shortcuts.map {
            JSONObject().put("kind", it.kind).put("label", it.label).put("value", it.value)
        }))
        .put("inputNames", JSONObject().apply { inputNames.forEach { (k, v) -> put(k.toString(), v) } })
        .put("volumeButtons", volumeButtons)
        .put("inputSteps", JSONObject().apply {
            inputSteps.forEach { (k, v) -> put(k.toString(), org.json.JSONArray(v)) }
        })
        .put("directInputs", directInputs)
        .put("slots", JSONObject().apply {
            slots.forEach { (box, s) ->
                put(box.toString(), JSONObject().put("league", s.league).put("team", s.team)
                    .put("name", s.name).put("importance", s.importance).put("pick", s.pick)
                    .put("eventId", s.eventId).put("kind", s.kind))
            }
        })

    companion object {
        fun fromJson(j: JSONObject): Settings {
            val d = Settings()
            val slotsJ = j.optJSONObject("slots") ?: JSONObject()
            val slots = mutableMapOf<Int, GameSlot>()
            for (k in slotsJ.keys()) {
                val s = slotsJ.optJSONObject(k) ?: continue
                val box = k.toIntOrNull() ?: continue
                slots[box] = GameSlot(s.optString("league", d.league), s.optString("team"),
                    s.optString("name"), s.optDouble("importance", 1.0), s.optString("pick"),
                    s.optString("eventId"), s.optString("kind", GameSlot.GAME).ifBlank { GameSlot.GAME })
            }
            return Settings(
                tvType = runCatching { TvType.valueOf(j.optString("tvType")) }.getOrDefault(d.tvType),
                tvHost = j.optString("tvHost", d.tvHost),
                samsungToken = j.optString("samsungToken").ifBlank { null },
                layout = j.optInt("layout", d.layout),
                homing = j.optBoolean("homing", d.homing),
                homePresses = j.optInt("homePresses", d.homePresses),
                slots = slots,
                useMic = j.optBoolean("useMic", d.useMic),
                micSensitivity = j.optDouble("micSensitivity", d.micSensitivity.toDouble()).toFloat(),
                proactive = j.optBoolean("proactive", d.proactive),
                hopping = runCatching { Hopping.valueOf(j.optString("hopping")) }.getOrDefault(d.hopping),
                breakSecs = j.optInt("breakSecs", d.breakSecs),
                league = j.optString("league", d.league),
                githubToken = j.optString("githubToken", ""),
                useCues = j.optBoolean("useCues", d.useCues),
                cuePhrases = j.optJSONArray("cuePhrases")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                }?.takeIf { it.isNotEmpty() } ?: d.cuePhrases,
                openYouTubeTv = j.optBoolean("openYouTubeTv", d.openYouTubeTv),
                autoPause = j.optBoolean("autoPause", d.autoPause),
                youTubeTvAppId = j.optString("youTubeTvAppId", ""),
                tvMac = j.optString("tvMac", ""),
                askedBattery = j.optBoolean("askedBattery", false),
                shortcuts = j.optJSONArray("shortcuts")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { Shortcut(it.optString("kind"), it.optString("label"), it.optString("value")) }
                    }
                }.orEmpty(),
                inputNames = j.optJSONObject("inputNames")?.let { o ->
                    o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optString(k) } }.toMap()
                }.orEmpty(),
                volumeButtons = j.optBoolean("volumeButtons", true),
                inputSteps = j.optJSONObject("inputSteps")?.let { o ->
                    o.keys().asSequence().mapNotNull { k ->
                        val n = k.toIntOrNull() ?: return@mapNotNull null
                        val a = o.optJSONArray(k) ?: return@mapNotNull null
                        n to (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                    }.filter { it.second.isNotEmpty() }.toMap()
                }.orEmpty(),
                directInputs = j.optInt("directInputs", 0),
            )
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mvswitch", Context.MODE_PRIVATE)

    fun load(): Settings = prefs.getString("settings", null)
        ?.let { runCatching { Settings.fromJson(JSONObject(it)) }.getOrNull() }
        ?: Settings()

    fun save(s: Settings) {
        prefs.edit().putString("settings", s.toJson().toString()).apply()
    }
}

val LEAGUES = listOf(
    "football/nfl" to "NFL",
    "football/college-football" to "College FB",
    "basketball/nba" to "NBA",
    "basketball/mens-college-basketball" to "NCAA MBB",
    "basketball/womens-college-basketball" to "NCAA WBB",
    "basketball/wnba" to "WNBA",
    "hockey/nhl" to "NHL",
    "hockey/mens-college-hockey" to "College hockey",
    "baseball/mlb" to "MLB",
    "baseball/college-baseball" to "College baseball",
    "soccer/usa.1" to "MLS",
    "soccer/eng.1" to "Premier League",
    "soccer/uefa.champions" to "Champions League",
    "soccer/esp.1" to "La Liga",
    "soccer/mex.1" to "Liga MX",
    "soccer/usa.nwsl" to "NWSL",
    "football/ufl" to "UFL",
    "golf/pga" to "PGA Tour",
    "racing/f1" to "F1",
    "racing/nascar-premier" to "NASCAR",
    "mma/ufc" to "UFC",
    "tennis/atp" to "Tennis ATP",
    "tennis/wta" to "Tennis WTA",
)
