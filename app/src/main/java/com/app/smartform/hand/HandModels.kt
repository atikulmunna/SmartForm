package com.app.smartform.hand

import android.graphics.Rect

data class HandPoint(val x: Float, val y: Float, val z: Float = 0f)

data class HandFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val cropRect: Rect,
    val rotationDegrees: Int,
    val isFrontCamera: Boolean,
    val timestampMs: Long,
    val hands: List<OneHand>
)

data class OneHand(
    val handedness: String,
    val score: Float,
    val landmarks: List<HandPoint>
)
