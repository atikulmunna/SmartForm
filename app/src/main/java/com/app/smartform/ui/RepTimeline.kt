// RepTimeline.kt  (FIXED)
package com.app.smartform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.app.smartform.reps.RepQuality

@Composable
fun RepTimeline(
    reps: List<RepQuality>,
    modifier: Modifier = Modifier
) {
    if (reps.isEmpty()) return

    val outline = MaterialTheme.colorScheme.outline

    fun barColor(rep: RepQuality): Color {
        return when (rep.verdict) {
            "EXCELLENT" -> Color(0xFF4CAF50)
            "GOOD" -> Color(0xFF81C784)
            "SHALLOW" -> Color(0xFFFFB74D)
            "TOO FAST", "TOO FAST + SHALLOW" -> Color(0xFFE57373)
            else -> outline
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reps.takeLast(20).forEach { rep ->
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                drawRoundRect(
                    color = barColor(rep),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
    }
}
