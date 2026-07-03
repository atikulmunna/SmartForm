package com.app.smartform.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseMathTest {

    private val eps = 1e-6

    @Test
    fun rightAngle_is90() {
        // a above b, c to the right of b -> 90 degrees at b
        assertEquals(90.0, PoseMath.angleDeg(0f, 1f, 0f, 0f, 1f, 0f), eps)
    }

    @Test
    fun straightLine_is180() {
        // a-b-c colinear and opposite -> 180 degrees
        assertEquals(180.0, PoseMath.angleDeg(0f, 1f, 0f, 0f, 0f, -1f), eps)
    }

    @Test
    fun fortyFiveDegrees() {
        // ab = (1,1), cb = (1,0) -> 45 degrees
        assertEquals(45.0, PoseMath.angleDeg(1f, 1f, 0f, 0f, 1f, 0f), eps)
    }

    @Test
    fun degenerate_whenAEqualsB_returns180() {
        assertEquals(180.0, PoseMath.angleDeg(0f, 0f, 0f, 0f, 1f, 0f), eps)
    }

    @Test
    fun degenerate_whenCEqualsB_returns180() {
        assertEquals(180.0, PoseMath.angleDeg(1f, 0f, 0f, 0f, 0f, 0f), eps)
    }

    @Test
    fun posePointOverload_matchesFloatOverload() {
        val a = PosePoint(0f, 1f, 1f)
        val b = PosePoint(0f, 0f, 1f)
        val c = PosePoint(1f, 0f, 1f)
        assertEquals(90.0, PoseMath.angleDeg(a, b, c), eps)
    }
}
