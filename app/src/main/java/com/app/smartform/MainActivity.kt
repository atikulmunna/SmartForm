package com.app.smartform

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.smartform.calibration.CalibrationState
import com.app.smartform.calibration.CalibrationStep
import com.app.smartform.calibration.CalibrationStore
import com.app.smartform.camera.CameraPreview
import com.app.smartform.gesture.Gesture
import com.app.smartform.gesture.GestureDetector
import com.app.smartform.hand.HandFrame
import com.app.smartform.hand.HandOverlay
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PostureEvaluator
import com.app.smartform.pose.SkeletonOverlay
import com.app.smartform.reps.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import kotlin.math.abs

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

    MaterialTheme {
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
private fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var poseFrame by remember { mutableStateOf<PoseFrame?>(null) }
    var handFrame by remember { mutableStateOf<HandFrame?>(null) }

    // session controls
    var isRunning by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableIntStateOf(0) } // 0=Curl, 1=Squat, 2=Push-up

    // toast-like gesture feedback
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureShownAt by remember { mutableLongStateOf(0L) }

    // ---- Pinch hold (Start/Stop OR Capture) ----
    var pinchStartTime by remember { mutableLongStateOf(0L) }
    var pinchActive by remember { mutableStateOf(false) }

    // ---- OpenPalm hold (Switch mode) ----
    var palmStartTime by remember { mutableLongStateOf(0L) }
    var palmActive by remember { mutableStateOf(false) }

    // Cooldown for ANY trigger
    var lastToggleTime by remember { mutableLongStateOf(0L) }

    // Tunables
    val pinchHoldMs = 1000L
    val palmHoldMs = 750L
    val toggleCooldownMs = 1100L
    val handFreshMs = 250L

    // frame freshness
    val now = SystemClock.uptimeMillis()
    val freshHandFrame = handFrame?.takeIf { now - it.timestampMs < handFreshMs }

    // mode mapping
    val mode = when (modeIndex) {
        0 -> ExerciseMode.CURL
        1 -> ExerciseMode.SQUAT
        else -> ExerciseMode.PUSHUP
    }
    val modeName = when (modeIndex) {
        0 -> "Curl"
        1 -> "Squat"
        else -> "Push-up"
    }

    // calibration datastore
    val store = remember { CalibrationStore(context.applicationContext) }
    val profile by store.profileFlow.collectAsState(initial = CalibrationProfile())

    // rep counter
    val repCounter = remember { RepCounter() }
    var repResult by remember { mutableStateOf(RepResult(0, "IDLE", angle = null)) }

    // posture / form gate
    val feedback = PostureEvaluator.evaluate(poseFrame, mode)
    val formOk = feedback.status == "Good form"

    // Effective running: user wants RUNNING, but reps only count when form is OK
    val effectiveRunning = isRunning && formOk

    // reset reps when switching mode
    LaunchedEffect(modeIndex) {
        repCounter.reset()
        repResult = RepResult(0, "IDLE", angle = null)
    }

    // update reps (NOTE: use effectiveRunning here)
    LaunchedEffect(modeIndex, poseFrame, effectiveRunning, profile) {
        repResult = repCounter.update(mode, poseFrame, effectiveRunning, profile)
    }

    // ----- QUALITY TRACKING -----
    var prevReps by remember { mutableIntStateOf(0) }
    var repAngleMin by remember { mutableStateOf<Double?>(null) }
    var lastRepAt by remember { mutableLongStateOf(0L) }
    var lastQuality by remember { mutableStateOf<RepQuality?>(null) }

    var sessionGood by remember { mutableIntStateOf(0) }
    var sessionShallow by remember { mutableIntStateOf(0) }
    var sessionFast by remember { mutableIntStateOf(0) }
    var sessionAvgScoreSum by remember { mutableIntStateOf(0) }

    val thresholds = when (mode) {
        ExerciseMode.CURL -> profile.curl
        ExerciseMode.SQUAT -> profile.squat
        ExerciseMode.PUSHUP -> profile.pushup
    }

    // track min angle during a rep (only when effectiveRunning)
    LaunchedEffect(effectiveRunning, repResult.angle) {
        if (!effectiveRunning) return@LaunchedEffect
        val a = repResult.angle ?: return@LaunchedEffect
        repAngleMin = repAngleMin?.let { minOf(it, a) } ?: a
    }

    // on rep increment => compute quality
    LaunchedEffect(modeIndex, repResult.reps) {
        if (repResult.reps > prevReps) {
            val nowMs = SystemClock.uptimeMillis()
            val tempo = if (lastRepAt == 0L) 0L else (nowMs - lastRepAt)
            lastRepAt = nowMs

            val q = RepQualityEvaluator.evaluate(mode, repAngleMin, thresholds, tempo)
            lastQuality = q

            sessionAvgScoreSum += q.score
            when (q.verdict) {
                "GOOD", "EXCELLENT" -> sessionGood += 1
                "SHALLOW" -> sessionShallow += 1
                "TOO FAST" -> sessionFast += 1
                "TOO FAST + SHALLOW" -> {
                    sessionFast += 1
                    sessionShallow += 1
                }
            }
            repAngleMin = null
        }
        prevReps = repResult.reps
    }

    LaunchedEffect(modeIndex) {
        prevReps = 0
        repAngleMin = null
        lastRepAt = 0L
        lastQuality = null
        sessionGood = 0
        sessionShallow = 0
        sessionFast = 0
        sessionAvgScoreSum = 0
    }

    // calibration state
    var calib by remember { mutableStateOf(CalibrationState()) }

    fun currentAngle(): Double? = repCounter.currentPrimaryAngle(mode, poseFrame)

    fun buildCalibratedProfile(
        existing: CalibrationProfile,
        mode: ExerciseMode,
        up: Double,
        down: Double
    ): CalibrationProfile {
        val hi = maxOf(up, down)
        val lo = minOf(up, down)
        val range = maxOf(15.0, abs(hi - lo))
        val margin = range * 0.15

        return when (mode) {
            ExerciseMode.CURL -> {
                val upThresh = (minOf(up, down) + margin).coerceIn(20.0, 140.0)
                val downThresh = (maxOf(up, down) - margin).coerceIn(80.0, 180.0)
                existing.copy(curl = RepThresholds(downThresh, upThresh))
            }

            ExerciseMode.SQUAT -> {
                val downThresh = (minOf(up, down) + margin).coerceIn(40.0, 160.0)
                val upThresh = (maxOf(up, down) - margin).coerceIn(80.0, 180.0)
                existing.copy(squat = RepThresholds(downThresh, upThresh))
            }

            ExerciseMode.PUSHUP -> {
                val downThresh = (minOf(up, down) + margin).coerceIn(40.0, 160.0)
                val upThresh = (maxOf(up, down) - margin).coerceIn(80.0, 180.0)
                existing.copy(pushup = RepThresholds(downThresh, upThresh))
            }
        }
    }

    // ✅ Gesture loop (current working logic kept)
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
                        val a = currentAngle()
                        if (a != null) {
                            calib = when (calib.step) {
                                CalibrationStep.BASELINE_UP -> calib.copy(
                                    step = CalibrationStep.BASELINE_DOWN,
                                    capturedUpAngle = a,
                                    message = "Captured UP (${a.toInt()}°). Now do DOWN pose and pinch-hold."
                                )

                                CalibrationStep.BASELINE_DOWN -> {
                                    val up = calib.capturedUpAngle
                                    if (up != null) {
                                        val newProfile = buildCalibratedProfile(profile, calib.mode, up, a)
                                        scope.launch { store.saveProfile(newProfile) }
                                        calib.copy(
                                            isActive = false,
                                            capturedDownAngle = a,
                                            message = "Saved: UP=${up.toInt()}°, DOWN=${a.toInt()}°"
                                        )
                                    } else calib.copy(message = "Missing UP capture, restart calibration.")
                                }
                            }
                        } else {
                            calib = calib.copy(message = "No angle detected (ensure joints visible).")
                        }

                        lastToggleTime = nowMs
                        pinchActive = false
                        gestureLabel = "Pinch → Capture"
                        gestureShownAt = nowMs
                        return@LaunchedEffect
                    }

                    isRunning = !isRunning
                    lastToggleTime = nowMs
                    pinchActive = false
                    gestureLabel = "Pinch → ${if (isRunning) "Start" else "Stop"}"
                    gestureShownAt = nowMs
                }
            }

            is Gesture.OpenPalm -> {
                pinchActive = false
                pinchStartTime = 0L

                if (isRunning || calib.isActive) {
                    palmActive = false
                    palmStartTime = 0L
                    return@LaunchedEffect
                }
                if (!cooldownOk()) return@LaunchedEffect

                if (!palmActive) {
                    palmActive = true
                    palmStartTime = nowMs
                } else if (nowMs - palmStartTime >= palmHoldMs) {
                    modeIndex = (modeIndex + 1) % 3
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
    val avgScore = if (repResult.reps == 0) 0 else (sessionAvgScoreSum / repResult.reps)

    // Warning banner: too fast (or shallow+fast)
    val tooFastNow = lastQuality?.verdict?.contains("TOO FAST") == true

    // Debug bottom sheet toggle
    var showDebug by remember { mutableStateOf(false) }

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
                        label = { Text(if (isRunning) "RUNNING" else "PAUSED") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            )
        }
        // ✅ REMOVED floatingActionButton (was overlapping bottom HUD)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera + overlays
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onPoseFrame = { poseFrame = it },
                onHandFrame = { if (it != null) handFrame = it }
            )
            SkeletonOverlay(modifier = Modifier.fillMaxSize(), frame = poseFrame)
            HandOverlay(modifier = Modifier.fillMaxSize(), frame = freshHandFrame)

            // ✅ NEW: Debug chip/button near the top (below app bar), no overlap with bottom UI
            AssistChip(
                onClick = { showDebug = true },
                label = { Text("Debug") },
                leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
            )

            // Bottom HUD (keeps face area clear)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Form banner (Q1)
                FormBanner(
                    status = feedback.status,
                    details = feedback.details,
                    score = feedback.score,
                    isOk = formOk
                )

                // Speed warning banner
                AnimatedVisibility(visible = tooFastNow) {
                    WarningBanner(text = "You're going too fast — slow down for controlled reps.")
                }

                // Big reps card + reset button near reps (Q2)
                RepDashboardCard(
                    modeName = modeName,
                    reps = repResult.reps,
                    phase = repResult.phase,
                    avgScore = avgScore,
                    isRunning = isRunning,
                    effectiveRunning = effectiveRunning,
                    onReset = {
                        repCounter.reset()
                        repResult = RepResult(0, "IDLE", angle = null)
                        prevReps = 0
                        repAngleMin = null
                        lastRepAt = 0L
                        lastQuality = null
                        sessionGood = 0
                        sessionShallow = 0
                        sessionFast = 0
                        sessionAvgScoreSum = 0
                    }
                )

                // Optional: last rep quick card
                lastQuality?.let { q -> CompactQualityCard(q) }

                // Calibration info
                AnimatedVisibility(visible = calib.isActive || calib.message.isNotBlank()) {
                    CalibrationBanner(calib = calib)
                }
            }

            // Small gesture toast
            if (showGestureToast) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center),
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
                        modeName = modeName,
                        isRunning = isRunning,
                        formOk = formOk,
                        effectiveRunning = effectiveRunning,
                        reps = repResult.reps,
                        phase = repResult.phase,
                        angle = repResult.angle,
                        thresholds = thresholds,
                        avgScore = avgScore,
                        sessionGood = sessionGood,
                        sessionShallow = sessionShallow,
                        sessionFast = sessionFast,
                        feedbackStatus = feedback.status,
                        feedbackScore = feedback.score,
                        feedbackDetails = feedback.details,
                        calib = calib,
                        repDebug = repResult.debug,
                        lastQuality = lastQuality,
                        onStartCalibration = {
                            if (!isRunning) {
                                calib = CalibrationState(
                                    isActive = true,
                                    mode = mode,
                                    step = CalibrationStep.BASELINE_UP,
                                    message = "Do UP pose for $modeName and pinch-hold to capture."
                                )
                            }
                        },
                        onResetCalibration = {
                            scope.launch { store.resetToDefaults() }
                            calib = calib.copy(isActive = false, message = "Reset calibration to defaults.")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormBanner(status: String, details: String, score: Int, isOk: Boolean) {
    val container = if (isOk) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (isOk) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container.copy(alpha = 0.92f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (isOk) "Form OK ✅" else "Form NOT OK ⚠️",
                style = MaterialTheme.typography.titleMedium,
                color = content
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$status • $score\n$details",
                color = content
            )
            if (!isOk) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Reps will pause until your form is OK.",
                    color = content,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)),
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
private fun RepDashboardCard(
    modeName: String,
    reps: Int,
    phase: String,
    avgScore: Int,
    isRunning: Boolean,
    effectiveRunning: Boolean,
    onReset: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(74.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { (avgScore.coerceIn(0, 100) / 100f) },
                    strokeWidth = 6.dp
                )
                Text(
                    text = reps.toString(),
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text("Reps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "$modeName • Phase $phase",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                val runLabel = when {
                    !isRunning -> "PAUSED"
                    effectiveRunning -> "RUNNING"
                    else -> "RUNNING (paused by form)"
                }
                Text(
                    text = "Avg $avgScore • $runLabel",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalIconButton(
                onClick = onReset,
                shape = RoundedCornerShape(16.dp),
                enabled = reps > 0
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset count")
            }
        }
    }
}

@Composable
private fun CompactQualityCard(q: RepQuality) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(onClick = { }, label = { Text(q.verdict) })
            Spacer(Modifier.width(10.dp))
            Text("Depth ${q.depthPct}%  •  Tempo ${q.tempoMs}ms", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalibrationBanner(calib: CalibrationState) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)),
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Debug", style = MaterialTheme.typography.titleLarge)
        Text(
            "Mode: $modeName | ${if (isRunning) "RUNNING" else "PAUSED"} | formOk=$formOk | effectiveRunning=$effectiveRunning",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Reps: $reps   Phase: $phase")
                Spacer(Modifier.height(6.dp))
                Text("Angle: ${angle?.toInt()?.toString() ?: "—"}°")
                Text("Thresholds: DOWN=${thresholds.downThresh.toInt()}°  UP=${thresholds.upThresh.toInt()}°")

                lastQuality?.let {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text("Last rep: ${it.verdict} | score=${it.score} | depth=${it.depthPct}% | tempo=${it.tempoMs}ms")
                    if (it.tips.isNotBlank()) Text(it.tips, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text("Session: avg=$avgScore  good=$sessionGood  shallow=$sessionShallow  fast=$sessionFast")

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text("Posture: $feedbackStatus ($feedbackScore)")
                Text(feedbackDetails, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text("Rep debug:", style = MaterialTheme.typography.labelLarge)
                Text(repDebug.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Calibration", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (calib.isActive) "ACTIVE: ${calib.step}\n${calib.message}" else calib.message.ifBlank { "Not running." },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStartCalibration, enabled = !isRunning) { Text("Calibrate") }
                    OutlinedButton(onClick = onResetCalibration) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SmartForm needs camera access for pose + hand tracking.")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onGrant) { Text("Grant Camera Permission") }
    }
}