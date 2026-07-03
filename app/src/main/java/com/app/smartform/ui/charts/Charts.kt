package com.app.smartform.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.smartform.reps.RepQuality
import com.app.smartform.ui.theme.QualityColors
import com.app.smartform.ui.theme.SmartFormTheme
import kotlin.math.min

/**
 * Hand-drawn Compose-Canvas charts for SmartForm. No external charting dependency:
 * every mark is drawn here so it stays fully theme-aware and works offline/on-device.
 *
 * Design rules (from the dataviz method):
 *  - Rep-quality categories are a reserved *status* palette ([QualityColors]) and are
 *    always shown with a text label, never color alone.
 *  - Depth and tempo are different measures, so they are separate small-multiple
 *    sparklines — never a dual-axis chart.
 *  - Thin marks, rounded data-ends, recessive baselines, text in ink tokens.
 */

// ---------------------------------------------------------------------------
// StatRing — a single 0..1 progress ring with a free-form center (hero number).
// ---------------------------------------------------------------------------

@Composable
fun StatRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
    strokeWidth: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val d = min(size.width, size.height) - stroke
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = Size(d, d)

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = progress.coerceIn(0f, 1f) * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------
// ScoreTrendChart — line + soft area of per-rep score (0..100). One series, no legend.
// ---------------------------------------------------------------------------

@Composable
fun ScoreTrendChart(
    scores: List<Int>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (scores.isEmpty()) {
        Box(modifier)
        return
    }

    Canvas(modifier) {
        val pad = 6f
        val w = size.width
        val h = size.height
        val n = scores.size

        fun px(i: Int): Float = if (n == 1) w / 2f else pad + (w - 2 * pad) * i / (n - 1)
        fun py(v: Int): Float = pad + (h - 2 * pad) * (1f - v.coerceIn(0, 100) / 100f)

        val line = Path()
        scores.forEachIndexed { i, v ->
            val x = px(i); val y = py(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        // Soft area under the line.
        val area = Path().apply {
            addPath(line)
            lineTo(px(n - 1), h)
            lineTo(px(0), h)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.30f), Color.Transparent),
                startY = pad,
                endY = h
            )
        )

        drawPath(line, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // Emphasise the latest rep.
        drawCircle(color = lineColor, radius = 5f, center = Offset(px(n - 1), py(scores.last())))
    }
}

// ---------------------------------------------------------------------------
// TrendSparkline — tiny normalized line for one measure (depth OR tempo).
// ---------------------------------------------------------------------------

@Composable
fun TrendSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
    minValue: Float? = null,
    maxValue: Float? = null,
) {
    if (values.isEmpty()) {
        Box(modifier)
        return
    }

    Canvas(modifier) {
        val pad = 4f
        val w = size.width
        val h = size.height
        val n = values.size

        val lo = minValue ?: values.min()
        val hi = maxValue ?: values.max()
        val range = (hi - lo).coerceAtLeast(1e-3f)

        fun px(i: Int): Float = if (n == 1) w / 2f else pad + (w - 2 * pad) * i / (n - 1)
        fun py(v: Float): Float = pad + (h - 2 * pad) * (1f - ((v - lo) / range).coerceIn(0f, 1f))

        val line = Path()
        values.forEachIndexed { i, v ->
            val x = px(i); val y = py(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, color = color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        drawCircle(color = color, radius = 4f, center = Offset(px(n - 1), py(values.last())))
    }
}

// ---------------------------------------------------------------------------
// VerdictDonut — good / shallow / too-fast proportions, with a center slot.
// ---------------------------------------------------------------------------

@Composable
fun VerdictDonut(
    good: Int,
    shallow: Int,
    fast: Int,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val total = good + shallow + fast
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val d = min(size.width, size.height) - stroke
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = Size(d, d)

            if (total == 0) {
                drawArc(
                    color = QualityColors.Neutral,
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
                return@Canvas
            }

            val segments = listOf(
                good to QualityColors.Good,
                shallow to QualityColors.Shallow,
                fast to QualityColors.TooFast,
            )
            val gap = 5f
            var start = -90f
            segments.forEach { (count, color) ->
                if (count > 0) {
                    val sweep = 360f * count / total
                    drawArc(
                        color = color,
                        startAngle = start + gap / 2f,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                    )
                    start += sweep
                }
            }
        }
        content()
    }
}

/** Labeled legend for the verdict palette (status color + name + count). */
@Composable
fun VerdictLegend(
    good: Int,
    shallow: Int,
    fast: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendRow(QualityColors.Good, "Good", good)
        LegendRow(QualityColors.Shallow, "Shallow", shallow)
        LegendRow(QualityColors.TooFast, "Too fast", fast)
    }
}

@Composable
private fun LegendRow(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// QualityTimeline — per-rep verdict bars (polished successor to RepTimeline).
// ---------------------------------------------------------------------------

@Composable
fun QualityTimeline(
    reps: List<RepQuality>,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    maxBars: Int = 24,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val shown = reps.takeLast(maxBars)
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(QualityColors.Neutral.copy(alpha = 0.25f))
            )
            return@Row
        }
        shown.forEach { rep ->
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                drawRoundRect(
                    color = QualityColors.forVerdict(rep.verdict),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14, widthDp = 340)
@Composable
private fun ChartsPreview() {
    val sample = listOf(72, 65, 80, 88, 60, 92, 84, 95, 78, 90)
    SmartFormTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatRing(progress = 0.86f, modifier = Modifier.size(88.dp)) {
                    Text("86", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(16.dp))
                ScoreTrendChart(scores = sample, modifier = Modifier.height(88.dp).weight(1f))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                VerdictDonut(good = 7, shallow = 2, fast = 1, modifier = Modifier.size(96.dp)) {
                    Text("10", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(16.dp))
                VerdictLegend(good = 7, shallow = 2, fast = 1, modifier = Modifier.weight(1f))
            }

            TrendSparkline(
                values = sample.map { it.toFloat() },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                color = MaterialTheme.colorScheme.secondary,
                minValue = 0f, maxValue = 100f
            )

            QualityTimeline(
                reps = listOf(
                    q("EXCELLENT"), q("GOOD"), q("SHALLOW"), q("GOOD"),
                    q("TOO FAST"), q("GOOD"), q("EXCELLENT"), q("SHALLOW")
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun q(verdict: String) = RepQuality(depthPct = 80, tempoMs = 1800, score = 85, verdict = verdict, tips = "")
