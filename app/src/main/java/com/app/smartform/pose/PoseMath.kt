package com.app.smartform.pose

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Pure geometry helpers shared by rep counting ([com.app.smartform.reps.RepCounter])
 * and posture evaluation ([PostureEvaluator]).
 *
 * No Android dependencies, so this is directly unit-testable on the host JVM.
 */
object PoseMath {

    /**
     * Interior angle (degrees) at vertex `b` formed by the points `a-b-c`.
     * Returns 180.0 for degenerate input (a coincides with b, or c with b).
     */
    fun angleDeg(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Double {
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

    /** Convenience overload for [PosePoint] triples. */
    fun angleDeg(a: PosePoint, b: PosePoint, c: PosePoint): Double =
        angleDeg(a.x, a.y, b.x, b.y, c.x, c.y)
}
