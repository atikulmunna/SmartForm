package com.app.smartform

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.smartform.calibration.CalibrationState
import com.app.smartform.camera.CameraPreview
import com.app.smartform.gesture.Gesture
import com.app.smartform.gesture.GestureDetector
import com.app.smartform.hand.HandOverlay
import com.app.smartform.pose.SkeletonOverlay
import com.app.smartform.reps.ExerciseMode
import com.app.smartform.reps.RepQuality
import com.app.smartform.reps.RepThresholds
import com.app.smartform.session.SessionStats
import com.app.smartform.session.SessionViewModel
import com.app.smartform.ui.SessionSummaryScreen
import com.app.smartform.ui.charts.QualityTimeline
import com.app.smartform.ui.charts.ScoreTrendChart
import com.app.smartform.ui.charts.StatRing
import com.app.smartform.ui.theme.Charcoal900
import com.app.smartform.ui.theme.SmartFormTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun AppRoot() {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    SmartFormTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (cameraPermission.status) {
                is PermissionStatus.Granted -> CameraScreen()
                else -> PermissionScreen(onGrant = { cameraPermission.launchPermissionRequest() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(vm: SessionViewModel = viewModel()) {
    val ui by vm.uiState
    val calib by vm.calib
    val handFrame by vm.handFrame.collectAsState()

    // ---- Gesture hold-state (transient UI only) ----
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureShownAt by remember { mutableLongStateOf(0L) }
    var pinchStartTime by remember { mutableLongStateOf(0L) }
    var pinchActive by remember { mutableStateOf(false) }
    var palmStartTime by remember { mutableLongStateOf(0L) }
    var palmActive by remember { mutableStateOf(false) }
    var lastToggleTime by remember { mutableLongStateOf(0L) }

    // Tunables
    val pinchHoldMs = 1000L
    val palmHoldMs = 750L
    val toggleCooldownMs = 1100L
    val handFreshMs = 250L

    val now = SystemClock.uptimeMillis()
    val freshHandFrame = handFrame?.takeIf { now - it.timestampMs < handFreshMs }

    var showDebug by remember { mutableStateOf(false) }
    var summaryStats by remember { mutableStateOf<SessionStats?>(null) }
    var gesturesAvailable by remember { mutableStateOf(true) }

    // Gesture loop: detect -> intent -> ViewModel
    LaunchedEffect(freshHandFrame?.timestampMs) {
        val nowMs = SystemClock.uptimeMillis()
        val g = GestureDetector.detect(
            freshHandFrame,
            minHandScore = 0.55f,
            minPalmAreaForOpenPalm = 0.016f
        )

        fun cooldownOk(): Boolean = (nowMs - lastToggleTime) >= toggleCooldownMs

        when (g) {
            is Gesture.Pinch -> {
                palmActive = false
                palmStartTime = 0L
                if (!cooldownOk()) return@LaunchedEffect

                if (!pinchActive) {
                    pinchActive = true
                    pinchStartTime = nowMs
                } else if (nowMs - pinchStartTime >= pinchHoldMs) {
                    if (calib.isActive) {
                        vm.captureCalibration()
                        gestureLabel = "Pinch → Capture"
                    } else {
                        vm.toggleRunning()
                        gestureLabel = "Pinch → ${if (vm.uiState.value.isRunning) "Start" else "Stop"}"
                    }
                    lastToggleTime = nowMs
                    pinchActive = false
                    gestureShownAt = nowMs
                }
            }

            is Gesture.OpenPalm -> {
                pinchActive = false
                pinchStartTime = 0L
                if (ui.isRunning || calib.isActive) {
                    palmActive = false
                    palmStartTime = 0L
                    return@LaunchedEffect
                }
                if (!cooldownOk()) return@LaunchedEffect

                if (!palmActive) {
                    palmActive = true
                    palmStartTime = nowMs
                } else if (nowMs - palmStartTime >= palmHoldMs) {
                    vm.cycleMode()
                    lastToggleTime = nowMs
                    palmActive = false
                    gestureLabel = "Palm → Switch mode"
                    gestureShownAt = nowMs
                }
            }

            is Gesture.None -> {
                pinchActive = false
                palmActive = false
                pinchStartTime = 0L
                palmStartTime = 0L
            }
        }
    }

    val showGestureToast = gestureLabel != null && (SystemClock.uptimeMillis() - gestureShownAt) < 900
    val tooFastNow = ui.lastQuality?.verdict?.contains("TOO FAST") == true

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SmartForm", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                },
                actions = {
                    AssistChip(
                        onClick = { /* no-op */ },
                        label = { Text(if (ui.isRunning) "RUNNING" else "PAUSED") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (ui.isRunning) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera + overlays
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onPoseFrame = { vm.submitPoseFrame(it) },
                onHandFrame = { vm.submitHandFrame(it) },
                onGesturesAvailable = { gesturesAvailable = it }
            )
            SkeletonOverlay(modifier = Modifier.fillMaxSize(), frame = ui.poseFrame)
            HandOverlay(modifier = Modifier.fillMaxSize(), frame = freshHandFrame)

            // Debug chip near the top (below app bar)
            AssistChip(
                onClick = { showDebug = true },
                label = { Text("Debug") },
                leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
            )

            // Bottom HUD — scrim keeps text/cards legible over the camera feed.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Charcoal900.copy(alpha = 0.90f))
                        )
                    )
                    .padding(horizontal = 14.dp)
                    .padding(top = 28.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(visible = !gesturesAvailable) {
                    WarningBanner(text = "Gesture control unavailable — use the on-screen buttons below.")
                }

                // Push-ups track poorly from the front; guide side placement before starting.
                AnimatedVisibility(visible = ui.mode == ExerciseMode.PUSHUP && !ui.isRunning) {
                    CoachHintBanner(
                        text = "Push-ups track best from the side. Prop your phone to your left or right so your whole body is in frame, then pinch to start."
                    )
                }

                AnimatedVisibility(visible = tooFastNow) {
                    WarningBanner(text = "You're going too fast — slow down for controlled reps.")
                }

                FormBanner(
                    status = ui.posture.status,
                    details = ui.posture.details,
                    isOk = ui.formOk
                )

                HudStatsCard(
                    modeName = ui.modeName,
                    phase = ui.phase,
                    reps = ui.reps,
                    avgScore = ui.avgScore,
                    isRunning = ui.isRunning,
                    effectiveRunning = ui.effectiveRunning,
                    lastQuality = ui.lastQuality,
                    scores = ui.repTimeline.map { it.score },
                    onReset = { vm.resetSession() }
                )

                QualityTimeline(
                    reps = ui.repTimeline,
                    modifier = Modifier.fillMaxWidth()
                )

                ModeSelector(
                    current = ui.mode,
                    enabled = !ui.isRunning && !calib.isActive,
                    onSelect = { vm.selectMode(it) }
                )

                ControlsRow(
                    isRunning = ui.isRunning,
                    onToggle = { vm.toggleRunning() },
                    onEnd = { summaryStats = vm.endSession() }
                )

                AnimatedVisibility(visible = calib.isActive || calib.message.isNotBlank()) {
                    CalibrationBanner(calib = calib)
                }
            }

            // Small gesture toast
            if (showGestureToast) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    tonalElevation = 8.dp,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        text = gestureLabel ?: ""
                    )
                }
            }

            // Debug bottom sheet
            if (showDebug) {
                ModalBottomSheet(
                    onDismissRequest = { showDebug = false },
                    dragHandle = { BottomSheetDefaults.DragHandle() }
                ) {
                    DebugPanel(
                        modeName = ui.modeName,
                        isRunning = ui.isRunning,
                        formOk = ui.formOk,
                        effectiveRunning = ui.effectiveRunning,
                        reps = ui.reps,
                        phase = ui.phase,
                        angle = ui.angle,
                        thresholds = ui.thresholds,
                        avgScore = ui.avgScore,
                        sessionGood = ui.sessionGood,
                        sessionShallow = ui.sessionShallow,
                        sessionFast = ui.sessionFast,
                        feedbackStatus = ui.posture.status,
                        feedbackScore = ui.posture.score,
                        feedbackDetails = ui.posture.details,
                        calib = calib,
                        repDebug = ui.repDebug,
                        lastQuality = ui.lastQuality,
                        onStartCalibration = { vm.startCalibration() },
                        onResetCalibration = { vm.resetCalibration() }
                    )
                }
            }

            // Full-screen Summary (no nav)
            summaryStats?.let { stats ->
                SessionSummaryScreen(
                    stats = stats,
                    onDone = {
                        vm.resetSession()
                        summaryStats = null
                    }
                )
            }
        }
    }
}

@Composable
private fun FormBanner(status: String, details: String, isOk: Boolean) {
    val container =
        if (isOk) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content =
        if (isOk) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container.copy(alpha = 0.92f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = content
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleSmall,
                    color = content
                )
                Text(
                    text = if (isOk) details else "$details — reps pause until fixed",
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            }
        }
    }
}

@Composable
private fun CoachHintBanner(text: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            modifier = Modifier.padding(14.dp),
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun HudStatsCard(
    modeName: String,
    phase: String,
    reps: Int,
    avgScore: Int,
    isRunning: Boolean,
    effectiveRunning: Boolean,
    lastQuality: RepQuality?,
    scores: List<Int>,
    onReset: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatRing(
                    progress = avgScore / 100f,
                    modifier = Modifier.size(88.dp),
                    strokeWidth = 7.dp
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = reps.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "reps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = modeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val (runLabel, runColor) = when {
                        !isRunning -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
                        effectiveRunning -> "● Live" to MaterialTheme.colorScheme.primary
                        else -> "Paused by form" to MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = runLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = runColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Avg score $avgScore · Phase $phase",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = onReset,
                    shape = RoundedCornerShape(14.dp),
                    enabled = reps > 0
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset count")
                }
            }

            if (scores.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Score trend",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                ScoreTrendChart(
                    scores = scores,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
            }

            lastQuality?.let { q ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { }, label = { Text(q.verdict) })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Depth ${q.depthPct}% · Tempo ${q.tempoMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    current: ExerciseMode,
    enabled: Boolean,
    onSelect: (ExerciseMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val modes = listOf(
            ExerciseMode.CURL to "Curl",
            ExerciseMode.SQUAT to "Squat",
            ExerciseMode.PUSHUP to "Push-up"
        )
        modes.forEach { (m, label) ->
            FilterChip(
                selected = current == m,
                onClick = { onSelect(m) },
                enabled = enabled,
                label = { Text(label, maxLines = 1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ControlsRow(
    isRunning: Boolean,
    onToggle: () -> Unit,
    onEnd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onToggle,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                contentColor = if (isRunning) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (isRunning) "Pause" else "Start")
        }

        OutlinedButton(
            onClick = onEnd,
            modifier = Modifier.height(52.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("End")
        }
    }
}

@Composable
private fun CalibrationBanner(calib: CalibrationState) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            val title = if (calib.isActive) "Calibration Active" else "Calibration"
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (calib.isActive) "Step: ${calib.step}\n${calib.message}" else calib.message,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun DebugPanel(
    modeName: String,
    isRunning: Boolean,
    formOk: Boolean,
    effectiveRunning: Boolean,
    reps: Int,
    phase: String,
    angle: Double?,
    thresholds: RepThresholds,
    avgScore: Int,
    sessionGood: Int,
    sessionShallow: Int,
    sessionFast: Int,
    feedbackStatus: String,
    feedbackScore: Int,
    feedbackDetails: String,
    calib: CalibrationState,
    repDebug: String,
    lastQuality: RepQuality?,
    onStartCalibration: () -> Unit,
    onResetCalibration: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Developer Debug", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Live rep-engine & calibration internals",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugPill(
                if (isRunning) "RUNNING" else "PAUSED",
                if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            DebugPill(
                if (formOk) "FORM OK" else "FORM ADJUST",
                if (formOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            DebugPill(
                if (effectiveRunning) "COUNTING" else "IDLE",
                if (effectiveRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DebugCard("Rep engine") {
            DebugRow("Mode", modeName)
            DebugRow("Reps", reps.toString())
            DebugRow("Phase", phase)
            DebugRow("Angle", angle?.let { "${it.toInt()}°" } ?: "—")
            DebugRow("Thresholds", "DOWN ${thresholds.downThresh.toInt()}° · UP ${thresholds.upThresh.toInt()}°")
        }

        lastQuality?.let {
            DebugCard("Last rep") {
                DebugRow("Verdict", it.verdict)
                DebugRow("Score", it.score.toString())
                DebugRow("Depth", "${it.depthPct}%")
                DebugRow("Tempo", "${it.tempoMs} ms")
                if (it.tips.isNotBlank()) DebugRow("Tip", it.tips)
            }
        }

        DebugCard("Session") {
            DebugRow("Avg score", avgScore.toString())
            DebugRow("Good", sessionGood.toString())
            DebugRow("Shallow", sessionShallow.toString())
            DebugRow("Too fast", sessionFast.toString())
        }

        DebugCard("Posture") {
            DebugRow("Status", "$feedbackStatus ($feedbackScore)")
            DebugRow("Details", feedbackDetails)
        }

        DebugCard("Raw") {
            Text(
                repDebug.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DebugCard("Calibration") {
            Text(
                if (calib.isActive) "ACTIVE · ${calib.step}\n${calib.message}" else calib.message.ifBlank { "Not running." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStartCalibration, enabled = !isRunning) { Text("Calibrate") }
                OutlinedButton(onClick = onResetCalibration) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun DebugPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(104.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(com.app.smartform.R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(28.dp))
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "SmartForm",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "On-device form coaching — real-time reps, posture checks, and rep-quality scoring from your camera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "How it works",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HowRow(Icons.Default.TouchApp, "Pinch & hold", "Start or stop a session")
                HowRow(Icons.Default.PanTool, "Open palm & hold", "Switch exercise while paused")
                HowRow(Icons.Default.CropFree, "Stay in frame", "Keep your whole body visible")
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrant,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Grant Camera Access")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Everything runs on-device. No video leaves your phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HowRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
