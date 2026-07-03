package com.app.smartform.reps

import org.junit.Assert.assertEquals
import org.junit.Test

class RepCounterTest {

    // Squat-style thresholds: DOWN = bent (low), UP = extended (high).
    private val squat = RepThresholds(downThresh = 115.0, upThresh = 165.0)

    private class Clock { var t = 0L }

    private fun feed(
        rc: RepCounter,
        clock: Clock,
        angle: Double,
        running: Boolean,
        frames: Int = 8,
        stepMs: Long = 100L
    ): RepResult {
        var last = RepResult(0, "IDLE")
        repeat(frames) {
            last = rc.updateWithAngle(ExerciseMode.SQUAT, angle, running, squat)
            clock.t += stepMs
        }
        return last
    }

    /** One full rep = go to bottom (bent) then back to top (extended). */
    private fun oneRep(rc: RepCounter, clock: Clock, running: Boolean): RepResult {
        feed(rc, clock, angle = 90.0, running = running)   // DOWN
        return feed(rc, clock, angle = 180.0, running = running) // UP
    }

    @Test
    fun countsOneRep_whenRunning() {
        val clock = Clock()
        val rc = RepCounter { clock.t }

        feed(rc, clock, angle = 180.0, running = true) // establish UP baseline
        val r = oneRep(rc, clock, running = true)

        assertEquals(1, r.reps)
        assertEquals("UP", r.phase)
    }

    @Test
    fun countsMultipleReps() {
        val clock = Clock()
        val rc = RepCounter { clock.t }

        feed(rc, clock, angle = 180.0, running = true)
        oneRep(rc, clock, running = true)
        val r = oneRep(rc, clock, running = true)

        assertEquals(2, r.reps)
    }

    @Test
    fun formGating_doesNotCountWhenNotRunning() {
        val clock = Clock()
        val rc = RepCounter { clock.t }

        feed(rc, clock, angle = 180.0, running = false)
        val r = oneRep(rc, clock, running = false)

        // The state machine still tracks phase, but no rep is credited.
        assertEquals(0, r.reps)
        assertEquals("UP", r.phase)
    }

    @Test
    fun reset_clearsRepCount() {
        val clock = Clock()
        val rc = RepCounter { clock.t }

        feed(rc, clock, angle = 180.0, running = true)
        oneRep(rc, clock, running = true)

        rc.reset()

        feed(rc, clock, angle = 180.0, running = true)
        val r = oneRep(rc, clock, running = true)
        assertEquals(1, r.reps)
    }

    @Test
    fun moreFlexed_picksSmallerAngle() {
        assertEquals(90.0, moreFlexed(170.0, 90.0))
        assertEquals(90.0, moreFlexed(90.0, 170.0))
    }

    @Test
    fun moreFlexed_toleratesMissingSide() {
        assertEquals(90.0, moreFlexed(null, 90.0))
        assertEquals(90.0, moreFlexed(90.0, null))
        assertEquals(null, moreFlexed(null, null))
    }

    @Test
    fun avg_averagesBothSides() {
        assertEquals(130.0, avg(100.0, 160.0))
        assertEquals(50.0, avg(null, 50.0))
    }
}
