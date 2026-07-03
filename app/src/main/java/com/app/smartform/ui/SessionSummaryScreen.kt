package com.app.smartform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.smartform.session.SessionStats
import com.app.smartform.ui.charts.QualityTimeline
import com.app.smartform.ui.charts.ScoreTrendChart
import com.app.smartform.ui.charts.StatRing
import com.app.smartform.ui.charts.TrendSparkline
import com.app.smartform.ui.charts.VerdictDonut
import com.app.smartform.ui.charts.VerdictLegend

@Composable
fun SessionSummaryScreen(
    stats: SessionStats,
    onDone: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Session Complete",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Hero: avg-score ring + total reps
                HeroCard(reps = stats.reps, avgScore = stats.avgScore)

                if (stats.reps == 0) {
                    Text(
                        "No reps were recorded this session. Start a session and complete a few reps to see your breakdown.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val scores = stats.repTimeline.map { it.score }
                    val depth = stats.repTimeline.map { it.depthPct.toFloat() }
                    val tempo = stats.repTimeline.filter { it.tempoMs > 0 }.map { it.tempoMs.toFloat() }

                    if (scores.isNotEmpty()) {
                        SectionCard(title = "Score per rep") {
                            ScoreTrendChart(
                                scores = scores,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            )
                        }
                    }

                    SectionCard(title = "Rep quality") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VerdictDonut(
                                good = stats.good,
                                shallow = stats.shallow,
                                fast = stats.fast,
                                modifier = Modifier.size(104.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stats.reps.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "reps",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(20.dp))
                            VerdictLegend(
                                good = stats.good,
                                shallow = stats.shallow,
                                fast = stats.fast,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    SectionCard(title = "Consistency") {
                        Text(
                            "Depth %",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        TrendSparkline(
                            values = depth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            color = MaterialTheme.colorScheme.primary,
                            minValue = 0f,
                            maxValue = 100f
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Tempo (ms between reps)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        TrendSparkline(
                            values = tempo,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    SectionCard(title = "Timeline") {
                        QualityTimeline(
                            reps = stats.repTimeline,
                            modifier = Modifier.fillMaxWidth(),
                            maxBars = 40
                        )
                    }
                }
            }

            // Pinned action
            Column(Modifier.padding(20.dp)) {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun HeroCard(reps: Int, avgScore: Int) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatRing(
                progress = avgScore / 100f,
                modifier = Modifier.size(120.dp),
                strokeWidth = 10.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        avgScore.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "avg score",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text(
                    reps.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "total reps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
