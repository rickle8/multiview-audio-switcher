package com.rickle8.mvswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExposureTest {
    @Test fun turnsDownBlownOutWhitesAndUpAMurkyPicture() {
        val t = ExposureTuner()
        assertEquals(-4.0 / 3, t.update(clipped = 0.05, mean = 120.0, now = 0.0)!!, 1e-9)
        assertNull("not again right away", t.update(0.05, 120.0, 3.0))
        assertEquals(-5.0 / 3, t.update(0.05, 120.0, 9.0)!!, 1e-9)
        assertNull("in between: leave it", t.update(0.01, 90.0, 20.0))
        assertEquals(-4.0 / 3, t.update(0.0, 50.0, 30.0)!!, 1e-9)
    }

    @Test fun staysInRange() {
        val t = ExposureTuner(start = -2.4)
        assertEquals(-2.5, t.update(0.2, 200.0, 0.0)!!, 1e-9)
        assertNull(t.update(0.2, 200.0, 10.0))
    }
}
