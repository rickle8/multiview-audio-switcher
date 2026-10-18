package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private fun call(heard: Heard, pct: Int, sight: Sight?, strongAd: Boolean = false, said: Boolean = false) =
        Pairing.decide(heard, pct, strongAd, if (strongAd) "Recognized a commercial it's heard before" else null, sight, said)

    @Test fun withoutTheCameraTheSoundDecides() {
        val c = call(Heard.AD, 70, null)
        assertEquals(Heard.AD, c.heard); assertFalse(c.strong)
    }

    @Test fun theScoreBugAndYardLinesMeanTheGame() {
        for (s in listOf(Sight(bug = true), Sight(lines = true))) {
            val held = call(Heard.AD, 85, s)
            assertEquals(Heard.UNSURE, held.heard); assertTrue(held.held)
            assertEquals(Heard.GAME, call(Heard.UNSURE, 50, s).heard)
        }
        // A recognized commercial gets past yard lines, never past the score bug read off the screen.
        assertEquals(Heard.AD, call(Heard.AD, 90, Sight(lines = true), strongAd = true).heard)
        assertEquals(Heard.UNSURE, call(Heard.AD, 90, Sight(bug = true), strongAd = true).heard)
    }

    @Test fun theFieldsGreenLeansGame() {
        assertEquals(Heard.UNSURE, call(Heard.AD, 65, Sight(grass = true)).heard)
        assertEquals(Heard.AD, call(Heard.AD, 90, Sight(grass = true)).heard)
    }

    @Test fun commercialSignsBackUpTheSound() {
        val text = call(Heard.AD, 70, Sight(adText = true))
        assertTrue(text.strong); assertTrue(text.why!!.contains("camera agrees"))
        assertEquals(Heard.AD, call(Heard.UNSURE, 40, Sight(adText = true)).heard)
        // The score bug just gone: ad-leaning sound is a commercial, game-leaning isn't.
        assertEquals(Heard.AD, call(Heard.UNSURE, 50, Sight(bugGoneSecs = 10.0)).heard)
        assertEquals(Heard.UNSURE, call(Heard.UNSURE, 30, Sight(bugGoneSecs = 10.0)).heard)
        assertTrue(call(Heard.AD, 70, Sight(bugGoneSecs = 10.0)).strong)
    }

    @Test fun youSayingItsTheGameKeepsTheCameraFromCallingACommercial() {
        assertEquals(Heard.UNSURE, call(Heard.UNSURE, 50, Sight(adText = true, bugGoneSecs = 60.0), said = true).heard)
        assertFalse(call(Heard.AD, 70, Sight(adText = true), said = true).strong)
    }

    @Test fun saysWhatItSees() {
        assertEquals("score bug, yard lines", Sight(bug = true, lines = true).describe())
        assertEquals("ad text, score bug gone 12 s", Sight(adText = true, bugGoneSecs = 12.4).describe())
        val c = call(Heard.AD, 72, Sight(bug = true))
        assertEquals("Sound: commercial (72% ad) · Camera: score bug → the game (camera sees it)", c.summary)
        assertEquals(true, Sight(grass = true).word)
        assertEquals(null, Sight(bugGoneSecs = 10.0).word)
        assertEquals(false, Sight(bugGoneSecs = 50.0).word)
    }
}
