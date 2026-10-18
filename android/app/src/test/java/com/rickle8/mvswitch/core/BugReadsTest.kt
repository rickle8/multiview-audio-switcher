package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReadsTest {
    /** Reads each of 4 boxes in turn, one every 0.45 s (each box about every 1.8 s), as the app does. */
    private fun run(b: BugReads, from: Double, to: Double, game: (box: Int, t: Double) -> Boolean, notLive: (Int, Double) -> Boolean = { _, _ -> false }) {
        var t = from; var box = 1
        while (t < to) { b.add(box, t, game(box, t), notLive(box, t)); box = box % 4 + 1; t += 0.45 }
    }

    @Test fun aCommercialCountsTheWholeBreak() {
        val b = BugReads()
        // Box 1 goes to commercial at 100 s and stays there 4 minutes; the others keep playing.
        val live = { box: Int, t: Double -> box != 1 || t < 100 }
        run(b, 0.0, 104.0, live)
        assertNull("not right away", b.goneSecs(1, 104.0))
        run(b, 104.0, 340.0, live)
        val g = b.goneSecs(1, 339.9)
        assertTrue("still gone after 4 minutes: $g", g != null && g > 230)
        // Past 5 minutes the camera has more likely lost the bug than the break is still on.
        run(b, 340.0, 420.0, live)
        assertNull(b.goneSecs(1, 419.9))
    }

    @Test fun justGoneNeedsADependableBox() {
        val b = BugReads()
        run(b, 0.0, 112.0, { box, t -> box != 1 || t < 100 })
        val g = b.goneSecs(1, 111.5)
        assertTrue("gone about 11 s: $g", g != null && g in 9.0..13.0)
        // A box it only reads now and then: a gap isn't news.
        val flaky = BugReads()
        var k = 0
        run(flaky, 0.0, 112.0, { box, t -> box != 1 || (t < 100 && (k++ % 3 == 0)) })
        assertNull(flaky.goneSecs(1, 111.5))
    }

    @Test fun halftimeGraphicsArentGone() {
        val b = BugReads()
        run(b, 0.0, 200.0, { box, t -> box != 1 || t < 100 }, { box, t -> box == 1 && t >= 100 })
        assertNull(b.goneSecs(1, 199.0))
    }

    @Test fun needsToSeeTheTv() {
        val b = BugReads()
        // Every box goes dark at once (the camera lost the TV): nobody's score bug is "gone".
        run(b, 0.0, 200.0, { _, t -> t < 100 })
        assertNull(b.goneSecs(1, 199.0))
        assertEquals(true, b.readWithin(2, 99.9, 6.0))
    }
}
