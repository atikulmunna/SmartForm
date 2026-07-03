package com.app.smartform.session

import com.app.smartform.reps.ExerciseMode
import com.app.smartform.reps.RepThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {

    // Squat-style depth model (down < up).
    private val t = RepThresholds(downThresh = 100.0, upThresh = 160.0)

    @Test
    fun freshTracker_isEmpty() {
        val s = SessionTracker()
        assertEquals(0, s.good)
        assertEquals(0, s.shallow)
        assertEquals(0, s.fast)
        assertEquals(0, s.timeline.size)
        assertEquals(0, s.avgScore(0))
    }

    @Test
    fun deepControlledRep_countsAsGood() {
        val s = SessionTracker()
        s.trackAngle(effectiveRunning = true, angle = 100.0) // full depth
        val counted = s.onRep(ExerciseMode.SQUAT, reps = 1, thresholds = t, tempoMs = 3000L)

        assertTrue(counted)
        assertEquals(1, s.good)
        assertEquals("EXCELLENT", s.lastQuality?.verdict)
        assertEquals(1, s.timeline.size)
    }

    @Test
    fun noIncrement_isNotCounted() {
        val s = SessionTracker()
        s.trackAngle(true, 100.0)
        assertFalse(s.onRep(ExerciseMode.SQUAT, reps = 0, thresholds = t, tempoMs = 0L))
        assertEquals(0, s.timeline.size)
    }

    @Test
    fun shallowRep_countsAsShallow() {
        val s = SessionTracker()
        s.trackAngle(true, 150.0) // (160-150)/60 = 16%
        s.onRep(ExerciseMode.SQUAT, 1, t, 3000L)
        assertEquals(1, s.shallow)
        assertEquals("SHALLOW", s.lastQuality?.verdict)
    }

    @Test
    fun fastRep_countsAsFast() {
        val s = SessionTracker()
        s.trackAngle(true, 100.0)
        s.onRep(ExerciseMode.SQUAT, 1, t, 500L) // below squat minimum tempo
        assertEquals(1, s.fast)
        assertEquals("TOO FAST", s.lastQuality?.verdict)
    }

    @Test
    fun angleIgnored_whenNotRunning() {
        val s = SessionTracker()
        s.trackAngle(effectiveRunning = false, angle = 100.0) // ignored -> no depth
        s.onRep(ExerciseMode.SQUAT, 1, t, 3000L)
        assertEquals("NO DATA", s.lastQuality?.verdict)
        assertEquals(0, s.good)
        assertEquals(0, s.shallow)
        assertEquals(0, s.fast)
    }

    @Test
    fun avgScore_averagesAcrossReps() {
        val s = SessionTracker()
        s.trackAngle(true, 100.0)
        s.onRep(ExerciseMode.SQUAT, 1, t, 3000L) // excellent -> 100
        s.trackAngle(true, 145.0)
        s.onRep(ExerciseMode.SQUAT, 2, t, 3000L) // shallow 25% -> 25
        assertEquals((100 + 25) / 2, s.avgScore(2))
    }

    @Test
    fun timeline_isCappedAt60() {
        val s = SessionTracker()
        for (i in 1..65) {
            s.trackAngle(true, 100.0)
            s.onRep(ExerciseMode.SQUAT, i, t, 3000L)
        }
        assertEquals(60, s.timeline.size)
    }

    @Test
    fun snapshot_reflectsTallies() {
        val s = SessionTracker()
        s.trackAngle(true, 100.0)
        s.onRep(ExerciseMode.SQUAT, 1, t, 3000L) // good
        s.trackAngle(true, 150.0)
        s.onRep(ExerciseMode.SQUAT, 2, t, 3000L) // shallow

        val snap = s.snapshot(2)
        assertEquals(2, snap.reps)
        assertEquals(1, snap.good)
        assertEquals(1, snap.shallow)
        assertEquals(0, snap.fast)
        assertEquals(2, snap.repTimeline.size)
    }

    @Test
    fun reset_clearsEverything() {
        val s = SessionTracker()
        s.trackAngle(true, 100.0)
        s.onRep(ExerciseMode.SQUAT, 1, t, 3000L)
        s.reset()
        assertEquals(0, s.good)
        assertEquals(0, s.timeline.size)
        assertEquals(0, s.avgScore(5))
        assertEquals(null, s.lastQuality)
    }
}
