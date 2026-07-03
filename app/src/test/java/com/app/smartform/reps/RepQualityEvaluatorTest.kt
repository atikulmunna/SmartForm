package com.app.smartform.reps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepQualityEvaluatorTest {

    // A "normal" depth model where downThresh < upThresh (squat/pushup-style).
    private val t = RepThresholds(downThresh = 100.0, upThresh = 160.0) // denom = 60

    private fun eval(min: Double?, tempoMs: Long, mode: ExerciseMode = ExerciseMode.SQUAT) =
        RepQualityEvaluator.evaluate(mode, min, t, tempoMs)

    @Test
    fun nullAngle_isNoData_withZeroDepth() {
        val q = eval(min = null, tempoMs = 0L)
        assertEquals("NO DATA", q.verdict)
        assertEquals(0, q.depthPct)
    }

    @Test
    fun fullDepth_goodTempo_isExcellent() {
        val q = eval(min = 100.0, tempoMs = 3000L) // depth = 100%
        assertEquals(100, q.depthPct)
        assertEquals("EXCELLENT", q.verdict)
        assertEquals(100, q.score)
    }

    @Test
    fun mediumDepth_goodTempo_isGood() {
        val q = eval(min = 115.0, tempoMs = 3000L) // (160-115)/60 = 75%
        assertEquals(75, q.depthPct)
        assertEquals("GOOD", q.verdict)
    }

    @Test
    fun lowDepth_goodTempo_isShallow() {
        val q = eval(min = 145.0, tempoMs = 3000L) // (160-145)/60 = 25%
        assertEquals(25, q.depthPct)
        assertEquals("SHALLOW", q.verdict)
    }

    @Test
    fun fullDepth_fastTempo_isTooFast() {
        val q = eval(min = 100.0, tempoMs = 500L) // deep but faster than 1700ms
        assertEquals("TOO FAST", q.verdict)
        assertTrue("fast reps must be penalised", q.score < 60)
    }

    @Test
    fun lowDepth_fastTempo_isTooFastAndShallow() {
        val q = eval(min = 145.0, tempoMs = 500L)
        assertEquals("TOO FAST + SHALLOW", q.verdict)
    }

    @Test
    fun tempoExactlyAtMinimum_isNotTooFast() {
        // SQUAT minimum tempo is 1700ms; exactly 1700 is allowed.
        val q = eval(min = 100.0, tempoMs = 1700L)
        assertEquals("EXCELLENT", q.verdict)
    }

    @Test
    fun perModeTempoThreshold_curlIsMoreLenient() {
        // 1200ms is TOO FAST for a squat but fine for a curl.
        val squat = RepQualityEvaluator.evaluate(ExerciseMode.SQUAT, 100.0, t, 1200L)
        val curl = RepQualityEvaluator.evaluate(ExerciseMode.CURL, 100.0, t, 1200L)
        assertEquals("TOO FAST", squat.verdict)
        assertEquals("EXCELLENT", curl.verdict)
    }

    @Test
    fun curlDepth_isGradedWithInvertedThresholds() {
        // Curls invert the thresholds: UP (flexed) = 70 is LOWER than DOWN (extended) = 150.
        val curl = RepThresholds(downThresh = 150.0, upThresh = 70.0)
        // Full flex to the up target -> 100%
        assertEquals(100, RepQualityEvaluator.evaluate(ExerciseMode.CURL, 70.0, curl, 3000L).depthPct)
        // Halfway (110°) -> (150-110)/80 = 50%
        assertEquals(50, RepQualityEvaluator.evaluate(ExerciseMode.CURL, 110.0, curl, 3000L).depthPct)
        // Barely moved (still extended) -> 0%
        assertEquals(0, RepQualityEvaluator.evaluate(ExerciseMode.CURL, 150.0, curl, 3000L).depthPct)
    }

    @Test
    fun scoreIsAlwaysClamped() {
        val worst = eval(min = 160.0, tempoMs = 100L) // 0% depth + fast
        assertTrue(worst.score in 0..100)
    }
}
