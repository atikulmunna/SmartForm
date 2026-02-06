package com.app.smartform.reps

import android.os.SystemClock
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PosePoint
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.acos
import kotlin.math.sqrt

data class RepResult(
    val reps: Int,
    val phase: String,        // "UP" / "DOWN" / "IDLE"
    val debug: String = "",
    val angle: Double? = null
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
        if (frame == null) return RepResult(s.reps, s.phase, debug = "no-frame", angle = null)

        val thresholds = when (mode) {
            ExerciseMode.CURL -> profile.curl
            ExerciseMode.SQUAT -> profile.squat
            ExerciseMode.PUSHUP -> profile.pushup
        }

        val rawAngle = currentPrimaryAngle(mode, frame)
            ?: return RepResult(s.reps, s.phase, debug = "missing-joints", angle = null)

        val angle = smoothEma(s, rawAngle, alpha = 0.35)

        val now = SystemClock.uptimeMillis()
        val minGapMs = 400L
        val confirmFrames = 2

        val canMove = now - s.lastTransitionMs > minGapMs

        // Note:
        // - CURL: DOWN=extended(high angle), UP=flexed(low angle)
        // - SQUAT/PUSHUP: DOWN=bent(low angle), UP=extended(high angle)
        val wantDown = when (mode) {
            ExerciseMode.CURL -> angle > thresholds.downThresh
            else -> angle < thresholds.downThresh
        }

        val wantUp = when (mode) {
            ExerciseMode.CURL -> angle < thresholds.upThresh
            else -> angle > thresholds.upThresh
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

        return RepResult(
            reps = s.reps,
            phase = s.phase,
            debug = "raw=${rawAngle.toInt()} ema=${angle.toInt()} down=${thresholds.downThresh.toInt()} up=${thresholds.upThresh.toInt()}",
            angle = angle
        )
    }

    fun currentPrimaryAngle(mode: ExerciseMode, frame: PoseFrame?): Double? {
        if (frame == null) return null
        return when (mode) {
            ExerciseMode.CURL ->
                best(elbow(frame, true), elbow(frame, false))

            ExerciseMode.SQUAT ->
                avg(knee(frame, true), knee(frame, false))

            ExerciseMode.PUSHUP ->
                avg(elbow(frame, true), elbow(frame, false))
        }
    }

    /* ---------- Geometry ---------- */

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

        if (pa.inFrameLikelihood < 0.4f || pb.inFrameLikelihood < 0.4f || pc.inFrameLikelihood < 0.4f)
            return null

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

    private fun best(a: Double?, b: Double?): Double? =
        when {
            a == null -> b
            b == null -> a
            else -> a
        }

    private fun avg(a: Double?, b: Double?): Double? =
        when {
            a == null -> b
            b == null -> a
            else -> (a + b) / 2.0
        }
}