package com.rickle8.mvswitch.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartThingsTest {
    private class Fake : WebHttp {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var tokenReplies = 0
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): HttpReply? {
            calls += Triple(method, url, body)
            return when {
                url.endsWith("/oauth/token") -> {
                    tokenReplies++
                    HttpReply(200, """{"access_token":"A$tokenReplies","refresh_token":"R$tokenReplies","expires_in":86399}""")
                }
                url.endsWith("/v1/devices") -> HttpReply(200, """{"items":[
                    {"deviceId":"lamp","label":"Lamp","components":[{"id":"main","capabilities":[{"id":"switch"}]}]},
                    {"deviceId":"tv1","label":"Living Room TV","components":[{"id":"main","capabilities":[{"id":"switch"},{"id":"samsungvd.mediaInputSource"}]}]}]}""")
                url.endsWith("/status") -> HttpReply(200, """{"components":{"main":{"samsungvd.mediaInputSource":{
                    "inputSource":{"value":"HDMI2"},
                    "supportedInputSourcesMap":{"value":[{"id":"dtv","name":"TV"},{"id":"HDMI1","name":"HDMI1"},{"id":"HDMI2","name":"PlayStation 5"}]}}}}}""")
                url.endsWith("/commands") -> HttpReply(200, """{"results":[{"status":"ACCEPTED"}]}""")
                else -> HttpReply(404, "")
            }
        }
    }

    @Test fun signsInFindsTheTvAndSwitches() {
        val f = Fake()
        assertEquals("abc", SmartThings.codeFrom("${SmartThings.REDIRECT}?code=abc&state=x"))
        assertEquals("abc", SmartThings.codeFrom(" abc "))
        assertTrue(SmartThings.authorizeUrl("id1").contains("redirect_uri=https%3A%2F%2Foauth.pstmn.io"))
        val t = SmartThings.exchange("id1", "sec", "abc", f, now = 0)
        assertEquals("A1", t.access)
        var saved: StTokens? = null
        var now = 1000L
        val st = SmartThings("id1", "sec", t, { saved = it }, f) { now }
        val tvs = st.tvs()
        assertEquals(listOf("tv1"), tvs.map { it.id })
        val (inputs, current) = st.inputs(tvs[0])
        assertEquals("HDMI2", current)
        assertEquals("PlayStation 5", inputs.first { it.id == "HDMI2" }.name)
        st.setInput(tvs[0], "HDMI1")
        val cmd = JSONObject(f.calls.last().third!!).getJSONArray("commands").getJSONObject(0)
        assertEquals("setInputSource", cmd.getString("command"))
        assertEquals("HDMI1", cmd.getJSONArray("arguments").getString(0))
        assertEquals("samsungvd.mediaInputSource", cmd.getString("capability"))
        // A day later the token is refreshed, and the new one saved.
        now = 86_000_000L
        st.setInput(tvs[0], "HDMI2")
        assertEquals("R2", saved!!.refresh)
        assertTrue(f.calls.any { it.third?.contains("grant_type=refresh_token&refresh_token=R1") == true })
    }
}
