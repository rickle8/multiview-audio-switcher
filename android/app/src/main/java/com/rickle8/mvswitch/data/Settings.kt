package com.rickle8.mvswitch.data

import android.content.Context
import com.rickle8.mvswitch.core.GameSlot
import org.json.JSONObject

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
) {
    val adThreshold: Double get() = 0.75 - 0.25 * micSensitivity
    val gameThreshold: Double get() = adThreshold - 0.2

    fun toJson(): JSONObject = JSONObject()
        .put("tvType", tvType.name).put("tvHost", tvHost).put("samsungToken", samsungToken ?: "")
        .put("layout", layout).put("homing", homing).put("homePresses", homePresses)
        .put("useMic", useMic).put("micSensitivity", micSensitivity.toDouble())
        .put("proactive", proactive).put("hopping", hopping.name).put("breakSecs", breakSecs)
        .put("league", league)
        .put("slots", JSONObject().apply {
            slots.forEach { (box, s) ->
                put(box.toString(), JSONObject().put("league", s.league).put("team", s.team)
                    .put("name", s.name).put("importance", s.importance).put("pick", s.pick))
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
                    s.optString("name"), s.optDouble("importance", 1.0), s.optString("pick"))
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
    "hockey/nhl" to "NHL",
    "baseball/mlb" to "MLB",
    "basketball/wnba" to "WNBA",
)
