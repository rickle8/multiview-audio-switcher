package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileTextTest {
    private val teams = mapOf(
        1 to setOf("florida st", "seminoles", "fsu", "miami", "hurricanes", "mia"),
        2 to setOf("byu", "cougars", "provo", "utah"),
        3 to setOf("ucla", "bruins", "wisconsin", "badgers", "wis"),
        4 to setOf("houston", "cougars", "hou", "baylor", "bears", "bay"),
    )

    @Test fun readsAScoreBugLikeAPerson() {
        // What the phone's text recognition reads off the Miami box in your photo.
        val r = TileText.read("ESPN FLORIDA ST 3-3 (1-2) 14 1ST & 10 3RD 5:45 25 14 3 MIAMI 5-0 (3-0)", teams,
            mapOf(1 to listOf(14, 14)))
        assertTrue(r.game)
        assertEquals(1, r.showsGameOf)
        assertTrue(r.strength >= 5)
    }

    @Test fun recognitionMistakesStillRead() {
        // "&" misread as "8", clock read with a period.
        val r = TileText.read("1ST 8 10   3RD 5.45", teams)
        assertTrue(r.game)
    }

    @Test fun aCommercialIsntAGame() {
        assertFalse(TileText.read("THE DENIM EVENT 50% OFF EVERYTHING SHOP NOW", teams).game)
        assertFalse(TileText.read("", teams).game)
        // A team name alone (a promo, a graphic) isn't enough.
        val promo = TileText.read("MIAMI HURRICANES FOOTBALL", teams)
        assertFalse(promo.game)
        assertEquals(1, promo.showsGameOf)
    }

    @Test fun sharedNicknamesDontPickAGame() {
        // "COUGARS" is both BYU and Houston: no call on which game it is.
        assertEquals(null, TileText.read("COUGARS 2ND 10:12", teams).showsGameOf)
        assertEquals(4, TileText.read("HOU 21 BAY 17 2ND 10:12", teams).showsGameOf)
    }

    @Test fun turnedBuffersMapBackUpright() {
        // The top-left of the upright picture, for each way the buffer can be turned.
        assertEquals(0.0 to 1.0, uprightToRaw(0.0, 0.0, 90))
        assertEquals(1.0 to 1.0, uprightToRaw(0.0, 0.0, 180))
        assertEquals(1.0 to 0.0, uprightToRaw(0.0, 0.0, 270))
    }
}
