package com.rickle8.mvswitch.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/** Plain HTTPS with headers (SmartThings' cloud API). Null means it couldn't be reached. */
fun interface WebHttp {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): HttpReply?
}

data class StTokens(val access: String, val refresh: String, val expiresAtMs: Long)

data class StTv(val id: String, val name: String, val capability: String)

/** One input as SmartThings names it: id "HDMI1", name "PlayStation 5" (if the TV knows). */
data class StInput(val id: String, val name: String)

/**
 * Switches inputs through Samsung SmartThings. Samsung TVs often ignore the
 * input keys sent over Wi-Fi; SmartThings' input command works on every recent one.
 *
 * Sign-in is OAuth with your own SmartThings developer client (free): the
 * personal access tokens SmartThings hands out now expire after a day.
 */
class SmartThings(
    private val clientId: String,
    private val clientSecret: String,
    tokens: StTokens,
    private val saveTokens: (StTokens) -> Unit,
    private val http: WebHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var tokens: StTokens = tokens
        private set

    companion object {
        const val API = "https://api.smartthings.com"
        /** Never actually loaded: the app catches the sign-in redirect before it opens. */
        const val REDIRECT = "https://oauth.pstmn.io/v1/callback"
        const val SCOPES = "r:devices:* w:devices:* x:devices:*"
        val CAPS = listOf("samsungvd.mediaInputSource", "mediaInputSource")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun authorizeUrl(clientId: String): String =
            "$API/oauth/authorize?client_id=${enc(clientId.trim())}&response_type=code" +
                "&redirect_uri=${enc(REDIRECT)}&scope=${enc(SCOPES)}"

        /** The sign-in code from the address the sign-in page sent you to (or the code itself). */
        fun codeFrom(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (!t.contains("=") && !t.contains("/")) return t
            return Regex("[?&]code=([^&#]+)").find(t)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        }

        private fun basic(id: String, secret: String) =
            "Basic " + Base64.getEncoder().encodeToString("${id.trim()}:${secret.trim()}".toByteArray())

        private fun parseTokens(r: HttpReply?, now: Long, oldRefresh: String? = null): StTokens {
            if (r == null) throw RemoteException("Couldn't reach SmartThings. Check the phone's internet.")
            if (r.code !in 200..299) {
                val msg = runCatching { JSONObject(r.body).optString("error_description").ifBlank { JSONObject(r.body).optString("error") } }.getOrNull()
                throw RemoteException("SmartThings sign-in failed (${r.code}${msg?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})")
            }
            val j = JSONObject(r.body)
            val access = j.optString("access_token").ifBlank { throw RemoteException("SmartThings sent no access token") }
            val refresh = j.optString("refresh_token").ifBlank { oldRefresh ?: "" }
            return StTokens(access, refresh, now + j.optLong("expires_in", 86_400) * 1000)
        }

        /** Finish signing in: trade the one-minute code for tokens. */
        fun exchange(clientId: String, clientSecret: String, code: String, http: WebHttp, now: Long = System.currentTimeMillis()): StTokens =
            parseTokens(http.request("POST", "$API/oauth/token", mapOf("Authorization" to basic(clientId, clientSecret)),
                "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&client_id=${enc(clientId.trim())}",
                "application/x-www-form-urlencoded"), now)
    }

    @Synchronized
    private fun accessToken(): String {
        val t = tokens
        if (clock() < t.expiresAtMs - 3_600_000) return t.access
        if (t.refresh.isBlank()) throw RemoteException("SmartThings sign-in expired. Sign in again in Settings.")
        val fresh = parseTokens(http.request("POST", "$API/oauth/token", mapOf("Authorization" to basic(clientId, clientSecret)),
            "grant_type=refresh_token&refresh_token=${enc(t.refresh)}&client_id=${enc(clientId.trim())}",
            "application/x-www-form-urlencoded"), clock(), t.refresh)
        tokens = fresh
        saveTokens(fresh)
        return fresh.access
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val r = http.request(method, API + path, mapOf("Authorization" to "Bearer ${accessToken()}"),
            body?.toString(), if (body != null) "application/json" else null)
            ?: throw RemoteException("Couldn't reach SmartThings. Check the phone's internet.")
        if (r.code == 401) throw RemoteException("SmartThings sign-in expired. Sign in again in Settings.")
        if (r.code == 403) throw RemoteException("SmartThings refused (403). Check the client has the device scopes.")
        if (r.code == 409 || r.code == 422) throw RemoteException("The TV didn't accept that. Is it on?")
        if (r.code !in 200..299) throw RemoteException("SmartThings error ${r.code}")
        return runCatching { JSONObject(r.body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    }

    /** Samsung TVs on your SmartThings account that can switch inputs. */
    fun tvs(): List<StTv> {
        val items = call("GET", "/v1/devices").optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val d = items.optJSONObject(i) ?: return@mapNotNull null
            val caps = mutableSetOf<String>()
            val comps = d.optJSONArray("components") ?: JSONArray()
            for (c in 0 until comps.length()) {
                val cc = comps.optJSONObject(c)?.optJSONArray("capabilities") ?: continue
                for (k in 0 until cc.length()) cc.optJSONObject(k)?.optString("id")?.let { caps += it }
            }
            val cap = CAPS.firstOrNull { it in caps } ?: return@mapNotNull null
            StTv(d.optString("deviceId"), d.optString("label").ifBlank { d.optString("name") }.ifBlank { "Samsung TV" }, cap)
        }
    }

    /** The TV's inputs and which one it's on now. */
    fun inputs(tv: StTv): Pair<List<StInput>, String?> {
        val main = call("GET", "/v1/devices/${tv.id}/status")
            .optJSONObject("components")?.optJSONObject("main") ?: return emptyList<StInput>() to null
        val cap = main.optJSONObject(tv.capability) ?: return emptyList<StInput>() to null
        val current = cap.optJSONObject("inputSource")?.optString("value")?.ifBlank { null }
        val map = cap.optJSONObject("supportedInputSourcesMap")?.optJSONArray("value")
        val list = if (map != null) (0 until map.length()).mapNotNull { i ->
            map.optJSONObject(i)?.let { StInput(it.optString("id"), it.optString("name").ifBlank { it.optString("id") }) }
        } else {
            val a = cap.optJSONObject("supportedInputSources")?.optJSONArray("value") ?: JSONArray()
            (0 until a.length()).map { StInput(a.optString(it), a.optString(it)) }
        }
        return list.filter { it.id.isNotBlank() } to current
    }

    /** Switch to an input by SmartThings id ("HDMI2"). */
    fun setInput(tv: StTv, source: String) {
        call("POST", "/v1/devices/${tv.id}/commands", JSONObject().put("commands", JSONArray().put(JSONObject()
            .put("component", "main").put("capability", tv.capability)
            .put("command", "setInputSource").put("arguments", JSONArray().put(source)))))
    }
}
