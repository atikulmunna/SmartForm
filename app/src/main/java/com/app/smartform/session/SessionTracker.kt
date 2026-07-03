package com.app.smartform.session

import com.app.smartform.reps.ExerciseMode
import com.app.smartform.reps.RepQuality
import com.app.smartform.reps.RepQualityEvaluator
import com.app.smartform.reps.RepThresholds

/**
 * Pure, Android-free accumulator for per-session rep quality. Owns everything the
 * summary needs (verdict tallies, running score, recent timeline) so the logic can
 * be unit-tested on the host JVM without a ViewModel, clock, or DataStore.
 *
 * The caller feeds it the measured min-angle per frame ([trackAngle]) and the rep
 * count after each [com.app.smartform.reps.RepCounter] update ([onRep]); tempo is
 * supplied by the caller so this class stays clock-independent.
 */
class SessionTracker {

    private var prevReps = 0
    private var repAngleMin: Double? = null
    private var scoreSum = 0

    var good = 0; private set
    var shallow = 0; private set
    var fast = 0; private set
    var lastQuality: RepQuality? = null; private set

    private val _timeline = ArrayList<RepQuality>()
    val timeline: List<RepQuality> get() = _timeline

    fun reset() {
        prevReps = 0
        repAngleMin = null
        scoreSum = 0
        good = 0
        shallow = 0
        fast = 0
        lastQuality = null
        _timeline.clear()
    }

    /** Track the deepest point of the in-progress rep. No-op unless counting is live. */
    fun trackAngle(effectiveRunning: Boolean, angle: Double?) {
        if (!effectiveRunning) return
        val a = angle ?: return
        repAngleMin = repAngleMin?.let { minOf(it, a) } ?: a
    }

    /**
     * Reconcile with the latest [reps] count. When it has advanced, scores the
     * completed rep (using the accumulated depth + supplied [tempoMs]) and folds it
     * into the session tallies. Returns true iff a new rep was recorded.
     */
    fun onRep(
        mode: ExerciseMode,
        reps: Int,
        thresholds: RepThresholds,
        tempoMs: Long
    ): Boolean {
        if (reps <= prevReps) {
            prevReps = reps
            return false
        }

        val q = RepQualityEvaluator.evaluate(mode, repAngleMin, thresholds, tempoMs)
        lastQuality = q
        scoreSum += q.score
        when (q.verdict) {
            "GOOD", "EXCELLENT" -> good += 1
            "SHALLOW" -> shallow += 1
            "TOO FAST" -> fast += 1
            "TOO FAST + SHALLOW" -> {
                fast += 1
                shallow += 1
            }
        }

        _timeline.add(q)
        if (_timeline.size > 60) _timeline.removeAt(0)

        repAngleMin = null
        prevReps = reps
        return true
    }

    fun avgScore(reps: Int): Int = if (reps == 0) 0 else scoreSum / reps

    fun snapshot(reps: Int): SessionStats = SessionStats(
        reps = reps,
        avgScore = avgScore(reps),
        good = good,
        shallow = shallow,
        fast = fast,
        repTimeline = _timeline.toList()
    )
}
