package com.app.smartform.gesture

import com.app.smartform.hand.HandFrame
import com.app.smartform.hand.HandPoint
import kotlin.math.hypot
import kotlin.math.max

sealed class Gesture {
    data object None : Gesture()
    data object Pinch : Gesture()
    data object OpenPalm : Gesture()
}

object GestureDetector {

    fun detect(
        frame: HandFrame?,
        minHandScore: Float = 0.55f,
        minPalmAreaForOpenPalm: Float = 0.016f
    ): Gesture {
        val hand = frame?.hands
            ?.filter { it.landmarks.size >= 21 && it.score >= minHandScore }
            ?.maxByOrNull { it.score }
            ?: return Gesture.None

        val pts = hand.landmarks

        val wrist = pts[0]
        val thumbTip = pts[4]
        val indexTip = pts[8]
        val pinkyTip = pts[20]
        val middleMcp = pts[9]

        val palmSize = dist(wrist, middleMcp).coerceAtLeast(1e-6f)

        // --- Pinch: keep it permissive ---
        val pinchRatio = dist(thumbTip, indexTip) / palmSize
        if (pinchRatio < 0.32f) return Gesture.Pinch

        // --- OpenPalm: make it strict ---
        val area = bboxArea(pts)
        if (area < minPalmAreaForOpenPalm) return Gesture.None

        val tips = listOf(8, 12, 16, 20).map { pts[it] }
        val avgTipDist = tips.map { dist(it, wrist) }.average().toFloat()
        val openPalmRatio = avgTipDist / palmSize

        // extra: require hand "spread" (index ↔ pinky far enough)
        val spreadRatio = dist(indexTip, pinkyTip) / palmSize

        return if (openPalmRatio > 1.45f && spreadRatio > 1.10f) Gesture.OpenPalm else Gesture.None
    }

    private fun dist(a: HandPoint, b: HandPoint): Float {
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun bboxArea(pts: List<HandPoint>): Float {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val w = max(0f, maxX - minX)
        val h = max(0f, maxY - minY)
        return w * h
    }
}
