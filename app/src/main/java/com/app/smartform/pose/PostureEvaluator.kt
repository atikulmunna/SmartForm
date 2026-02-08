package com.app.smartform.pose

import com.app.smartform.reps.ExerciseMode
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

data class PostureFeedback(
    val status: String,
    val details: String,
    val score: Int
)

object PostureEvaluator {

    // Backward-compatible default (if anything still calls the old signature)
    fun evaluate(frame: PoseFrame?): PostureFeedback = evaluate(frame, ExerciseMode.SQUAT)

    fun evaluate(frame: PoseFrame?, mode: ExerciseMode): PostureFeedback {
        if (frame == null) return PostureFeedback("Detecting...", "Hold still for a moment", 0)

        // Conservative visibility gate (prevents “OK” with partial body)
        val minLik = 0.55f

        fun p(i: Int) = frame.points[i]?.takeIf { it.inFrameLikelihood >= minLik }

        val ls = p(PoseLandmark.LEFT_SHOULDER) ?: return PostureFeedback("Detecting...", "Need shoulders", 0)
        val rs = p(PoseLandmark.RIGHT_SHOULDER) ?: return PostureFeedback("Detecting...", "Need shoulders", 0)
        val lh = p(PoseLandmark.LEFT_HIP) ?: return PostureFeedback("Detecting...", "Need hips", 0)
        val rh = p(PoseLandmark.RIGHT_HIP) ?: return PostureFeedback("Detecting...", "Need hips", 0)

        val midShoulderX = (ls.x + rs.x) / 2f
        val midShoulderY = (ls.y + rs.y) / 2f
        val midHipX = (lh.x + rh.x) / 2f
        val midHipY = (lh.y + rh.y) / 2f

        // Generic stability checks (your existing idea, slightly stricter)
        val shoulderTiltPx = (ls.y - rs.y)
        val hipTiltPx = (lh.y - rh.y)

        val shoulderWidth = abs(ls.x - rs.x).coerceAtLeast(1f)
        val hipWidth = abs(lh.x - rh.x).coerceAtLeast(1f)

        val shoulderTiltNorm = shoulderTiltPx / shoulderWidth
        val hipTiltNorm = hipTiltPx / hipWidth

        val shoulderBad = abs(shoulderTiltNorm) > 0.07f
        val hipBad = abs(hipTiltNorm) > 0.09f

        val issues = mutableListOf<String>()
        if (shoulderBad) issues += if (shoulderTiltNorm > 0) "Right shoulder higher" else "Left shoulder higher"
        if (hipBad) issues += if (hipTiltNorm > 0) "Right hip higher" else "Left hip higher"

        // ---- Mode-specific “READY stance” gates ----
        fun angleDeg(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
            val abx = ax - bx
            val aby = ay - by
            val cbx = cx - bx
            val cby = cy - by
            val ab = sqrt((abx * abx + aby * aby).toDouble())
            val cb = sqrt((cbx * cbx + cby * cby).toDouble())
            if (ab < 1e-6 || cb < 1e-6) return 180.0
            val dot = (abx * cbx + aby * cby).toDouble()
            val cos = (dot / (ab * cb)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cos))
        }

        fun kneeAngle(right: Boolean): Double? {
            val hip = p(if (right) PoseLandmark.RIGHT_HIP else PoseLandmark.LEFT_HIP) ?: return null
            val knee = p(if (right) PoseLandmark.RIGHT_KNEE else PoseLandmark.LEFT_KNEE) ?: return null
            val ankle = p(if (right) PoseLandmark.RIGHT_ANKLE else PoseLandmark.LEFT_ANKLE) ?: return null
            return angleDeg(hip.x, hip.y, knee.x, knee.y, ankle.x, ankle.y)
        }

        fun elbowAngle(right: Boolean): Double? {
            val sh = p(if (right) PoseLandmark.RIGHT_SHOULDER else PoseLandmark.LEFT_SHOULDER) ?: return null
            val el = p(if (right) PoseLandmark.RIGHT_ELBOW else PoseLandmark.LEFT_ELBOW) ?: return null
            val wr = p(if (right) PoseLandmark.RIGHT_WRIST else PoseLandmark.LEFT_WRIST) ?: return null
            return angleDeg(sh.x, sh.y, el.x, el.y, wr.x, wr.y)
        }

        fun torsoUpright(): Boolean {
            // Upright torso => shoulders above hips and roughly vertical alignment
            val dy = (midHipY - midShoulderY)
            val dx = abs(midHipX - midShoulderX)
            if (dy <= 0f) return false
            val leanRatio = dx / dy.coerceAtLeast(1f)
            return leanRatio < 0.35f
        }

        when (mode) {
            ExerciseMode.SQUAT -> {
                val rk = kneeAngle(true)
                val lk = kneeAngle(false)
                if (rk == null && lk == null) return PostureFeedback("Detecting...", "Need knees + ankles", 0)

                val knee = listOfNotNull(rk, lk).average()

                // Conservative “ready” = standing-ish (not already squatting)
                val uprightOk = torsoUpright()
                val kneesExtended = knee >= 155.0

                if (!uprightOk) issues += "Torso leaning"
                if (!kneesExtended) issues += "Stand tall (start position)"

                // Score
                val score = when {
                    issues.isEmpty() -> 95
                    issues.size == 1 -> 80
                    issues.size == 2 -> 65
                    else -> 50
                }

                return if (issues.isEmpty()) {
                    PostureFeedback("Good form", "Squat ready ✅", score)
                } else {
                    PostureFeedback("Adjust form", issues.joinToString(" • "), score)
                }
            }

            ExerciseMode.CURL -> {
                val re = elbowAngle(true)
                val le = elbowAngle(false)
                if (re == null && le == null) return PostureFeedback("Detecting...", "Need elbows + wrists", 0)

                val elbow = listOfNotNull(re, le).average()

                // Conservative “ready” = arms mostly extended (not mid-curl)
                val uprightOk = torsoUpright()
                val armsDown = elbow >= 150.0

                if (!uprightOk) issues += "Torso leaning"
                if (!armsDown) issues += "Lower arms (start position)"

                val score = when {
                    issues.isEmpty() -> 95
                    issues.size == 1 -> 80
                    issues.size == 2 -> 65
                    else -> 50
                }

                return if (issues.isEmpty()) {
                    PostureFeedback("Good form", "Curl ready ✅", score)
                } else {
                    PostureFeedback("Adjust form", issues.joinToString(" • "), score)
                }
            }

            ExerciseMode.PUSHUP -> {
                // Pushup is hard from a front cam. We’ll gate conservatively:
                // require hips + shoulders (already have), plus at least one ankle
                val ra = p(PoseLandmark.RIGHT_ANKLE)
                val la = p(PoseLandmark.LEFT_ANKLE)
                if (ra == null && la == null) return PostureFeedback("Detecting...", "Need ankles", 0)

                val ankleX = listOfNotNull(ra?.x, la?.x).average().toFloat()
                val ankleY = listOfNotNull(ra?.y, la?.y).average().toFloat()

                // Body should look more horizontal: shoulder-hip line should not be vertical.
                val dx = abs(midHipX - midShoulderX)
                val dy = abs(midHipY - midShoulderY)
                val notVertical = (dx / dy.coerceAtLeast(1f)) > 0.35f

                // Also ensure hips are not far above shoulders (standing)
                val notStanding = abs(midHipY - midShoulderY) < abs(ankleY - midHipY) * 0.9f

                if (!notVertical) issues += "Get into push-up position"
                if (!notStanding) issues += "Lower body (not standing)"

                val score = when {
                    issues.isEmpty() -> 95
                    issues.size == 1 -> 80
                    issues.size == 2 -> 65
                    else -> 50
                }

                return if (issues.isEmpty()) {
                    PostureFeedback("Good form", "Push-up ready ✅", score)
                } else {
                    PostureFeedback("Adjust form", issues.joinToString(" • "), score)
                }
            }
        }
    }
}
