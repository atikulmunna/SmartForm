package com.app.smartform.pose

import android.graphics.Rect

data class PosePoint(
    val x: Float,
    val y: Float,
    val inFrameLikelihood: Float
)

data class PoseFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val cropRect: Rect,
    val rotationDegrees: Int,
    val isFrontCamera: Boolean,
    val points: Map<Int, PosePoint>
)
