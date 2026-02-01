package com.app.smartform.pose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.max

@Composable
fun SkeletonOverlay(
    modifier: Modifier = Modifier,
    frame: PoseFrame?
) {
    Canvas(modifier = modifier) {
        val f = frame ?: return@Canvas
        val crop = f.cropRect

        val srcW = crop.width().toFloat().coerceAtLeast(1f)
        val srcH = crop.height().toFloat().coerceAtLeast(1f)

        val viewW = size.width
        val viewH = size.height

        // This matches PreviewView.ScaleType.FILL_CENTER
        val scale = max(viewW / srcW, viewH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        fun toView(type: Int): Offset? {
            val pt = f.points[type] ?: return null

            // Landmark coords are in image space -> normalize within cropRect
            var nx = (pt.x - crop.left) / srcW
            val ny = (pt.y - crop.top) / srcH

            // Mirror for front camera (because PreviewView mirrors)
            if (f.isFrontCamera) nx = 1f - nx

            val x = nx * srcW * scale + offsetX
            val y = ny * srcH * scale + offsetY
            return Offset(x, y)
        }

        fun line(a: Int, b: Int) {
            val pa = toView(a) ?: return
            val pb = toView(b) ?: return
            drawLine(color = Color.Green, start = pa, end = pb, strokeWidth = 6f)
        }

        fun dot(t: Int) {
            val p = toView(t) ?: return
            drawCircle(color = Color.Green, radius = 8f, center = p)
        }

        // Torso
        line(11, 12)
        line(23, 24)
        line(11, 23)
        line(12, 24)

        // Arms
        line(11, 13); line(13, 15)
        line(12, 14); line(14, 16)

        // Legs
        line(23, 25); line(25, 27)
        line(24, 26); line(26, 28)

        listOf(0, 11, 12, 23, 24, 13, 14, 15, 16, 25, 26, 27, 28).forEach { dot(it) }
    }
}
