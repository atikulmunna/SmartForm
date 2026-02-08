package com.app.smartform.reps

import android.os.SystemClock
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PosePoint
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.acos
import kotlin.math.sqrt

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

class RepCounter {

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

        val thresholds = when (mode) {
            ExerciseMode.CURL -> profile.curl
            ExerciseMode.SQUAT -> profile.squat
            ExerciseMode.PUSHUP -> profile.pushup
        }

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

        val ema = smoothEma(s, raw, alpha = 0.35)

        val now = SystemClock.uptimeMillis()
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

        // Determine “wantDown / wantUp” using hysteresis + current state
        val wantDown = if (!s.inDown) {
            when (mode) {
                ExerciseMode.CURL -> ema > downEnter
                else -> ema < downEnter
            }
        } else {
            // already in down; don’t care about re-entering
            false
        }

        val wantUp = if (s.inDown) {
            when (mode) {
                ExerciseMode.CURL -> ema < upExit
                else -> ema > upExit
            }
        } else {
            // already up; don’t care about re-exiting
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
            rawAngle = raw,
            wantDown = wantDown,
            wantUp = wantUp,
            downThresh = thresholds.downThresh,
            upThresh = thresholds.upThresh,
            inDown = s.inDown,
            streak = s.streak,
            canMove = canMove,
            debug = "raw=${raw.toInt()} ema=${ema.toInt()} gap=${gap}ms " +
                    "downEnter=${downEnter.toInt()} upExit=${upExit.toInt()}"
        )
    }

    fun currentPrimaryAngle(mode: ExerciseMode, frame: PoseFrame?): Double? {
        if (frame == null) return null
        return when (mode) {
            ExerciseMode.CURL -> best(elbow(frame, true), elbow(frame, false))
            ExerciseMode.SQUAT -> avg(knee(frame, true), knee(frame, false))
            ExerciseMode.PUSHUP -> avg(elbow(frame, true), elbow(frame, false))
        }
    }

    private fun elbow(f: PoseFrame, right: Boolean): Double? =
        angle(
            f,
            if (right) PoseLandmark.RIGHT_SHOULDER else PoseLandmark.LEFT_SHOULDER,
            if (right) PoseLandmark.RIGHT_ELBOW else PoseLandmark.LEFT_ELBOW,
            if (right) PoseLandmark.RIGHT_WRIST else PoseLandmark.LEFT_WRIST
        )

    private fun knee(f: PoseFrame, right: Boolean): Double? =
        angle(
            f,
            if (right) PoseLandmark.RIGHT_HIP else PoseLandmark.LEFT_HIP,
            if (right) PoseLandmark.RIGHT_KNEE else PoseLandmark.LEFT_KNEE,
            if (right) PoseLandmark.RIGHT_ANKLE else PoseLandmark.LEFT_ANKLE
        )

    private fun angle(f: PoseFrame, a: Int, b: Int, c: Int): Double? {
        val pa = f.points[a] ?: return null
        val pb = f.points[b] ?: return null
        val pc = f.points[c] ?: return null

        if (pa.inFrameLikelihood < 0.45f || pb.inFrameLikelihood < 0.45f || pc.inFrameLikelihood < 0.45f) {
            return null
        }

        return angleDeg(pa, pb, pc)
    }

    private fun angleDeg(a: PosePoint, b: PosePoint, c: PosePoint): Double {
        val abx = a.x - b.x
        val aby = a.y - b.y
        val cbx = c.x - b.x
        val cby = c.y - b.y

        val ab = sqrt((abx * abx + aby * aby).toDouble())
        val cb = sqrt((cbx * cbx + cby * cby).toDouble())
        if (ab < 1e-6 || cb < 1e-6) return 180.0

        val dot = (abx * cbx + aby * cby).toDouble()
        val cos = (dot / (ab * cb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
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

    private fun best(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> a
    }

    private fun avg(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> (a + b) / 2.0
    }
}