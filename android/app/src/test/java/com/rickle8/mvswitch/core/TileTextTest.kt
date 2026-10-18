package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun recognizesCommercialText() {
        assertTrue(TileText.read("0.9% APR FOR 60 MONTHS. SEE DEALER FOR DETAILS", teams).ad)
        assertTrue(TileText.read("Professional driver on closed course. Do not attempt.", teams).ad)
        assertTrue(TileText.read("VISIT GEICO.COM", teams).ad)
        assertTrue(TileText.read("CALL 1-800-555-0199", teams).ad)
        assertTrue(TileText.read("THE DENIM EVENT 50% OFF EVERYTHING SHOP NOW", teams).ad)
        // One hint alone isn't enough.
        assertFalse(TileText.read("SHOP NOW", teams).ad)
        // A sponsor's name on the game's own graphics isn't a commercial.
        assertFalse(TileText.read("BYU 14 UTAH 10 2ND 4:32 3RD & 6 VISIT STATEFARM.COM", teams).ad)
        assertFalse(TileText.read("ESPN FLORIDA ST 14 1ST & 10 3RD 5:45 MIAMI 14", teams).ad)
    }

    private fun line(text: String, center: Double) = TileLine(text, center - 0.03, center + 0.03, 0.02, 0.98)

    @Test fun tickerLinesSayNothingAboutTheBox() {
        // ESPN, live: the game's score bug, and the BottomLine under it with another game's score.
        val live = TileText.read(listOf(
            line("BYU 14 UTAH 10 2ND 4:32 3RD & 6", 0.84),
            line("NCAAF ALABAMA 24 LSU 17 3RD 4:12", 0.96)), teams, own = 2)
        assertTrue(live.game); assertTrue(live.ticker); assertEquals(2, live.showsGameOf)
        // Only the ticker (a halftime show, or a commercial it runs over): other games' clocks and quarters.
        val half = TileText.read(listOf(line("NCAAF GEORGIA 21 TENN 14 3RD 8:01", 0.96)), teams, own = 2)
        assertFalse(half.game); assertTrue(half.ticker); assertFalse(half.ad)
        // No league name, but along the very bottom with teams that aren't one of your games.
        val other = TileText.read(listOf(line("OHIO ST 28 PENN ST 21 3RD 4:15", 0.95)), teams, own = 3)
        assertFalse(other.game); assertTrue(other.ticker)
        // One of your other games scrolling by on this box's ticker: not this box's game.
        val yours = TileText.read(listOf(line("BYU 14 UTAH 10 2ND 4:32", 0.95)), teams, own = 1)
        assertFalse(yours.game); assertTrue(yours.ticker); assertEquals(null, yours.showsGameOf)
        // ...but the same at the bottom of BYU's own box is its score bug.
        val own = TileText.read(listOf(line("BYU 14 UTAH 10 2ND 4:32", 0.93)), teams, own = 2)
        assertTrue(own.game); assertFalse(own.ticker)
    }

    private val sides = mapOf(
        2 to listOf(setOf("byu", "cougars", "brigham young"), setOf("utah", "utes")),
        3 to listOf(setOf("ucla", "bruins"), setOf("wisconsin", "badgers", "wis")),
    )

    @Test fun theGamesOwnTeamsAndScoreAreTheKey() {
        val now = mapOf(2 to listOf(14, 10), 3 to listOf(21, 17))
        // Another game's clock and quarter in the middle of the picture (a graphic, a promo): not this game.
        assertFalse(TileText.read("ALABAMA 24 LSU 17 3RD 4:12", teams, now, own = 2, sides = sides).game)
        // Its score exactly, with a team: the game.
        assertTrue(TileText.read("BYU 14 10", teams, now, own = 2, sides = sides).game)
        // ESPN is ahead of the TV (a touchdown the TV hasn't shown): close enough, with a team and the quarter.
        assertTrue(TileText.read("BYU 7 UTAH 10 2ND", teams, now, own = 2, sides = sides).game)
        // Both teams and the clock.
        assertTrue(TileText.read("UCLA WIS 12:05", teams, now, own = 3, sides = sides).game)
        // A team alone (a promo, a graphic) isn't enough.
        assertFalse(TileText.read("UCLA BRUINS FOOTBALL", teams, now, own = 3, sides = sides).game)
        // A score nowhere near ESPN's isn't this game.
        assertFalse(TileText.read("BYU 42 3RD", teams, now, own = 2, sides = sides).game)
    }

    @Test fun findsTheTickerByItsScrolling() {
        val tb = TickerBands()
        val own = setOf("miami", "florida")
        fun read(bottom: String, bug: String) = listOf(line(bug, 0.84), line(bottom, 0.96))
        tb.update(1, read("NCAAF ALABAMA 24 LSU 17 3RD 4:12", "MIAMI 14 FLORIDA ST 14 3RD 5:45"), 0.0, own)
        tb.update(1, read("GEORGIA 21 TENNESSEE 14 HALFTIME", "MIAMI 14 FLORIDA ST 14 3RD 5:43"), 2.0, own)
        assertNull("one change could be a graphic", tb.band(1, 2.0))
        tb.update(1, read("OREGON 31 USC 28 FINAL", "MIAMI 14 FLORIDA ST 14 3RD 5:41"), 4.0, own)
        val band = tb.band(1, 4.0)!!
        assertTrue(0.96 in band); assertFalse(0.84 in band)
        // Anything going by in that band is set aside, even one of your own games.
        val r = TileText.read(listOf(line("BYU 14 UTAH 10 2ND 4:32 3RD & 6", 0.96)), teams, mapOf(2 to listOf(14, 10)),
            own = 2, sides = sides, tickerBand = band)
        assertFalse(r.game); assertTrue(r.ticker)
        // The score bug, read the same way again and again, is never a ticker.
        assertNull(TickerBands().apply {
            update(2, listOf(line("BYU 14 UTAH 10 2ND 4:32", 0.93)), 0.0)
            update(2, listOf(line("BYU 14 UTAH 10 2ND 4:30", 0.93)), 2.0)
            update(2, listOf(line("BYU 14 UTAH 10 2ND 4:28", 0.93)), 4.0)
        }.band(2, 4.0))
        assertNull("forgotten after a minute", tb.band(1, 70.0))
    }

    @Test fun theScoreBugNeverMakesATickerBand() {
        // A score bar along the bottom: its clock, down and distance and banners change; its teams don't.
        val tb = TickerBands()
        val own = setOf("byu", "utah", "cougars", "utes")
        listOf(
            listOf(line("BYU 14", 0.92), line("UTAH 10", 0.92), line("2ND 4:32", 0.92), line("1ST & 10", 0.92)),
            listOf(line("BYU 14", 0.92), line("UTAH 10", 0.92), line("2ND 4:05", 0.92), line("2ND & 4", 0.92)),
            listOf(line("BYU 14", 0.92), line("UTAH 10", 0.92), line("OFFICIAL REVIEW", 0.92)),
            listOf(line("BYU 14", 0.92), line("UTAH 10", 0.92), line("2ND 3:51", 0.92), line("3RD & 2", 0.92)),
        ).forEachIndexed { i, r -> tb.update(2, r, i * 2.0, own) }
        assertNull(tb.band(2, 6.0))
        // It stayed put, so even inside a ticker band it's still the game.
        assertTrue(tb.steady(2, line("BYU 14", 0.92)))
        assertFalse(tb.steady(2, line("GEORGIA 21 TENNESSEE 14", 0.92)))
        val kept = TileText.read(listOf(line("BYU 14 UTAH 10 2ND 4:32", 0.92)), teams, mapOf(2 to listOf(14, 10)),
            own = 2, sides = sides, tickerBand = 0.88..0.99, steady = { true })
        assertTrue(kept.game)
    }

    @Test fun finePrintCountsEvenWhereATickerRuns() {
        val r = TileText.read(listOf(line("0.9% APR FOR 60 MONTHS SEE DEALER FOR DETAILS", 0.96)), teams, own = 1)
        assertTrue(r.ad)
        // The ticker plugging its own website isn't a commercial.
        assertFalse(TileText.read(listOf(line("NCAAF MORE AT ESPN.COM", 0.96)), teams, own = 1).ad)
    }

    @Test fun halftimeAndFinalArentPlay() {
        val half = TileText.read("BYU 14 UTAH 10 HALFTIME", teams)
        assertFalse(half.game); assertTrue(half.notLive); assertEquals(2, half.showsGameOf)
        assertFalse(TileText.read("FINAL MIAMI 31 FLORIDA ST 24", teams).game)
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
