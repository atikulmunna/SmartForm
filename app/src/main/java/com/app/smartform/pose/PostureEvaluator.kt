package com.app.smartform.pose

import kotlin.math.abs
import kotlin.math.atan2

data class PostureFeedback(
    val status: String,
    val details: String,
    val score: Int
)

object PostureEvaluator {

    fun evaluate(frame: PoseFrame?): PostureFeedback {
        if (frame == null) return PostureFeedback("Detecting...", "Hold still for a moment", 0)

        val ls = frame.points[11] ?: return PostureFeedback("Detecting...", "Need shoulders", 0)
        val rs = frame.points[12] ?: return PostureFeedback("Detecting...", "Need shoulders", 0)
        val lh = frame.points[23] ?: return PostureFeedback("Detecting...", "Need hips", 0)
        val rh = frame.points[24] ?: return PostureFeedback("Detecting...", "Need hips", 0)

        val shoulderTiltPx = (ls.y - rs.y)
        val hipTiltPx = (lh.y - rh.y)

        val shoulderWidth = abs(ls.x - rs.x).coerceAtLeast(1f)
        val hipWidth = abs(lh.x - rh.x).coerceAtLeast(1f)

        val shoulderTiltNorm = shoulderTiltPx / shoulderWidth
        val hipTiltNorm = hipTiltPx / hipWidth

        val midShoulderX = (ls.x + rs.x) / 2f
        val midShoulderY = (ls.y + rs.y) / 2f
        val midHipX = (lh.x + rh.x) / 2f
        val midHipY = (lh.y + rh.y) / 2f

        val torsoAngleRad = atan2(midHipY - midShoulderY, midHipX - midShoulderX)
        val torsoLean = abs(torsoAngleRad - (Math.PI / 2.0)).toFloat()

        val shoulderBad = abs(shoulderTiltNorm) > 0.08f
        val hipBad = abs(hipTiltNorm) > 0.10f
        val leanBad = torsoLean > 0.20f

        val issues = mutableListOf<String>()
        if (shoulderBad) issues += if (shoulderTiltNorm > 0) "Right shoulder higher" else "Left shoulder higher"
        if (hipBad) issues += if (hipTiltNorm > 0) "Right hip higher" else "Left hip higher"
        if (leanBad) issues += "Torso leaning"

        val score = when {
            issues.isEmpty() -> 95
            issues.size == 1 -> 80
            issues.size == 2 -> 65
            else -> 50
        }

        return if (issues.isEmpty()) {
            PostureFeedback("Good form", "Keep it steady", score)
        } else {
            PostureFeedback("Adjust form", issues.joinToString(" • "), score)
        }
    }
}
