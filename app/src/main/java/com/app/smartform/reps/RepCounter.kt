package com.app.smartform.reps

import android.os.SystemClock
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PoseMath
import com.google.mlkit.vision.pose.PoseLandmark.*

data class RepResult(
    val reps: Int,
    val phase: String, // "UP" / "DOWN" / "IDLE"
    val angle: Double? = null,          // EMA-smoothed primary angle
    val rawAngle: Double? = null,       // raw angle before EMA
    val wantDown: Boolean? = null,
    val wantUp: Boolean? = null,
    val downThresh: Double? = null,
    val upThresh: Double? = null,
    val inDown: Boolean? = null,
    val streak: Int = 0,
    val canMove: Boolean? = null,
    val debug: String = ""
)

/**
 * @param now injectable time source (defaults to [SystemClock.uptimeMillis]).
 *            Injecting it lets the rep state machine be unit-tested deterministically.
 */
class RepCounter(private val now: () -> Long = { SystemClock.uptimeMillis() }) {

    private data class State(
        var reps: Int = 0,
        var phase: String = "IDLE",
        var inDown: Boolean = false,
        var ema: Double = 0.0,
        var emaInit: Boolean = false,
        var lastTransitionMs: Long = 0L,
        var streak: Int = 0
    )

    private val states = mutableMapOf<ExerciseMode, State>()

    fun reset() {
        states.clear()
    }

    /**
     * Frame-driven entry point. Extracts the primary joint angle from [frame] and
     * delegates to the pure [updateWithAngle] state machine.
     */
    fun update(
        mode: ExerciseMode,
        frame: PoseFrame?,
        running: Boolean,
        profile: CalibrationProfile
    ): RepResult {
        val s = states.getOrPut(mode) { State() }

        if (frame == null) {
            return RepResult(
                reps = s.reps,
                phase = s.phase,
                debug = "no-frame",
                inDown = s.inDown,
                streak = s.streak
            )
        }

        val thresholds = thresholdsFor(mode, profile)

        val raw = currentPrimaryAngle(mode, frame)
            ?: return RepResult(
                reps = s.reps,
                phase = s.phase,
                debug = "missing-joints",
                inDown = s.inDown,
                streak = s.streak,
                downThresh = thresholds.downThresh,
                upThresh = thresholds.upThresh
            )

        return updateWithAngle(mode, raw, running, thresholds)
    }

    /**
     * Pure rep state machine: given a freshly measured primary [rawAngle], advances
     * the hysteresis/confirm-frame state for [mode] and returns the current result.
     *
     * Kept free of frame/Android types so it can be exercised directly in unit tests.
     */
    fun updateWithAngle(
        mode: ExerciseMode,
        rawAngle: Double,
        running: Boolean,
        thresholds: RepThresholds
    ): RepResult {
        val s = states.getOrPut(mode) { State() }

        val ema = smoothEma(s, rawAngle, alpha = 0.35)

        val now = now()
        val minGapMs = 450L
        val confirmFrames = 3
        val canMove = (now - s.lastTransitionMs) > minGapMs

        // --- Hysteresis pads (degrees) ---
        // Enter DOWN must be a bit "more down"
        // Exit DOWN (go UP) can be a bit easier
        val enterPad = 6.0
        val exitPad = 6.0

        // Semantics:
        // - CURL: DOWN = extended (high angle), UP = flexed (low angle)
        // - SQUAT/PUSHUP: DOWN = bent (low angle), UP = extended (high angle)
        val (downEnter, upExit) = when (mode) {
            ExerciseMode.CURL -> {
                val downEnter = thresholds.downThresh + enterPad     // need a bit MORE extension to count as DOWN
                val upExit = thresholds.upThresh - exitPad           // allow a bit LESS flex to return UP
                downEnter to upExit
            }
            else -> {
                val downEnter = thresholds.downThresh - enterPad     // need a bit MORE bend (lower) to count as DOWN
                val upExit = thresholds.upThresh - exitPad           // allow a bit LESS extension to return UP
                downEnter to upExit
            }
        }

        // Determine "wantDown / wantUp" using hysteresis + current state
        val wantDown = if (!s.inDown) {
            when (mode) {
                ExerciseMode.CURL -> ema > downEnter
                else -> ema < downEnter
            }
        } else {
            // already in down; don't care about re-entering
            false
        }

        val wantUp = if (s.inDown) {
            when (mode) {
                ExerciseMode.CURL -> ema < upExit
                else -> ema > upExit
            }
        } else {
            // already up; don't care about re-exiting
            false
        }

        if (!s.inDown) {
            if (wantDown && canMove) {
                s.streak++
                if (s.streak >= confirmFrames) {
                    s.inDown = true
                    s.phase = "DOWN"
                    s.lastTransitionMs = now
                    s.streak = 0
                }
            } else {
                s.streak = 0
                if (s.phase == "IDLE") s.phase = "UP"
            }
        } else {
            if (wantUp && canMove) {
                s.streak++
                if (s.streak >= confirmFrames) {
                    s.inDown = false
                    s.phase = "UP"
                    s.lastTransitionMs = now
                    s.streak = 0
                    if (running) s.reps++
                }
            } else {
                s.streak = 0
            }
        }

        val gap = now - s.lastTransitionMs

        return RepResult(
            reps = s.reps,
            phase = s.phase,
            angle = ema,
            rawAngle = rawAngle,
            wantDown = wantDown,
            wantUp = wantUp,
            downThresh = thresholds.downThresh,
            upThresh = thresholds.upThresh,
            inDown = s.inDown,
            streak = s.streak,
            canMove = canMove,
            debug = "raw=${rawAngle.toInt()} ema=${ema.toInt()} gap=${gap}ms " +
                    "downEnter=${downEnter.toInt()} upExit=${upExit.toInt()}"
        )
    }

    fun currentPrimaryAngle(mode: ExerciseMode, frame: PoseFrame?): Double? {
        if (frame == null) return null
        return when (mode) {
            // Whichever arm is actually curling drives the count (the more-flexed one),
            // so single-arm and either-hand curls are counted — not just the right arm.
            ExerciseMode.CURL -> moreFlexed(elbow(frame, true), elbow(frame, false))
            ExerciseMode.SQUAT -> avg(knee(frame, true), knee(frame, false))
            ExerciseMode.PUSHUP -> avg(elbow(frame, true), elbow(frame, false))
        }
    }

    private fun elbow(f: PoseFrame, right: Boolean): Double? =
        angle(
            f,
            if (right) RIGHT_SHOULDER else LEFT_SHOULDER,
            if (right) RIGHT_ELBOW else LEFT_ELBOW,
            if (right) RIGHT_WRIST else LEFT_WRIST
        )

    private fun knee(f: PoseFrame, right: Boolean): Double? =
        angle(
            f,
            if (right) RIGHT_HIP else LEFT_HIP,
            if (right) RIGHT_KNEE else LEFT_KNEE,
            if (right) RIGHT_ANKLE else LEFT_ANKLE
        )

    private fun angle(f: PoseFrame, a: Int, b: Int, c: Int): Double? {
        val pa = f.points[a] ?: return null
        val pb = f.points[b] ?: return null
        val pc = f.points[c] ?: return null

        if (pa.inFrameLikelihood < 0.45f || pb.inFrameLikelihood < 0.45f || pc.inFrameLikelihood < 0.45f) {
            return null
        }

        return PoseMath.angleDeg(pa, pb, pc)
    }

    private fun smoothEma(s: State, v: Double, alpha: Double): Double {
        if (!s.emaInit) {
            s.ema = v
            s.emaInit = true
        } else {
            s.ema = alpha * v + (1 - alpha) * s.ema
        }
        return s.ema
    }

    private fun thresholdsFor(mode: ExerciseMode, profile: CalibrationProfile): RepThresholds =
        when (mode) {
            ExerciseMode.CURL -> profile.curl
            ExerciseMode.SQUAT -> profile.squat
            ExerciseMode.PUSHUP -> profile.pushup
        }
}

/** Pick the more-flexed (smaller) of two elbow angles; tolerates a missing side. */
internal fun moreFlexed(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}

/** Average two joint angles; tolerates a missing side. */
internal fun avg(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> (a + b) / 2.0
}
