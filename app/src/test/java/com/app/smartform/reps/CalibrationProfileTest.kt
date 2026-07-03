package com.app.smartform.reps

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationProfileTest {

    @Test
    fun defaultThresholdsAreStable() {
        val p = CalibrationProfile()

        assertEquals(150.0, p.curl.downThresh, 0.0)
        assertEquals(70.0, p.curl.upThresh, 0.0)

        assertEquals(115.0, p.squat.downThresh, 0.0)
        assertEquals(165.0, p.squat.upThresh, 0.0)

        assertEquals(100.0, p.pushup.downThresh, 0.0)
        assertEquals(165.0, p.pushup.upThresh, 0.0)
    }

    @Test
    fun copyReplacesOnlyTargetedMode() {
        val p = CalibrationProfile()
        val updated = p.copy(curl = RepThresholds(120.0, 60.0))

        assertEquals(120.0, updated.curl.downThresh, 0.0)
        // squat + pushup untouched
        assertEquals(p.squat, updated.squat)
        assertEquals(p.pushup, updated.pushup)
    }
}
