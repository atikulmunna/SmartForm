package com.app.smartform.hand

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.max

private val EDGES = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    0 to 9, 9 to 10, 10 to 11, 11 to 12,
    0 to 13, 13 to 14, 14 to 15, 15 to 16,
    0 to 17, 17 to 18, 18 to 19, 19 to 20
)

@Composable
fun HandOverlay(
    modifier: Modifier = Modifier,
    frame: HandFrame?
) {
    Canvas(modifier = modifier) {
        val f = frame ?: return@Canvas
        val crop = f.cropRect

        val srcW = crop.width().toFloat().coerceAtLeast(1f)
        val srcH = crop.height().toFloat().coerceAtLeast(1f)

        val viewW = size.width
        val viewH = size.height

        // Match PreviewView.ScaleType.FILL_CENTER
        val scale = max(viewW / srcW, viewH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        fun toView(p: HandPoint): Offset {
            // p is normalized in [0..1] of the *upright* bitmap we fed to MediaPipe
            val px = p.x * f.imageWidth.toFloat()
            val py = p.y * f.imageHeight.toFloat()

            var nx = ((px - crop.left) / srcW).coerceIn(0f, 1f)
            val ny = ((py - crop.top) / srcH).coerceIn(0f, 1f)

            if (f.isFrontCamera) nx = 1f - nx


            val x = nx * srcW * scale + offsetX
            val y = ny * srcH * scale + offsetY
            return Offset(x, y)
        }

        f.hands.forEach { hand ->
            val pts = hand.landmarks
            EDGES.forEach { (a, b) ->
                val pa = pts.getOrNull(a) ?: return@forEach
                val pb = pts.getOrNull(b) ?: return@forEach
                drawLine(Color.Cyan, toView(pa), toView(pb), strokeWidth = 5f)
            }
            pts.forEach { p ->
                drawCircle(Color.Cyan, radius = 6f, center = toView(p))
            }
        }
    }
}
