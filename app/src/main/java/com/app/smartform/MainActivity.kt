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

    var isRunning by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableIntStateOf(0) } // 0=Curl, 1=Squat, 2=Push-up

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

    val now = SystemClock.uptimeMillis()
    val freshHandFrame = handFrame?.takeIf { now - it.timestampMs < handFreshMs }

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

    val store = remember { CalibrationStore(context.applicationContext) }
    val profile by store.profileFlow.collectAsState(initial = CalibrationProfile())

    val repCounter = remember { RepCounter() }
    var repResult by remember { mutableStateOf(RepResult(0, "IDLE", angle = null)) }

    LaunchedEffect(modeIndex) {
        repCounter.reset()
        repResult = RepResult(0, "IDLE", angle = null)
    }

    LaunchedEffect(modeIndex, poseFrame, isRunning, profile) {
        repResult = repCounter.update(mode, poseFrame, isRunning, profile)
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

    LaunchedEffect(isRunning, repResult.angle) {
        if (!isRunning) return@LaunchedEffect
        val a = repResult.angle ?: return@LaunchedEffect
        repAngleMin = repAngleMin?.let { minOf(it, a) } ?: a
    }

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

    var calib by remember { mutableStateOf(CalibrationState()) }

    val feedback = PostureEvaluator.evaluate(poseFrame)

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

    // ✅ Gesture loop (patched)
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
                        onClick = { },
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDebug = true },
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.BugReport, contentDescription = "Debug")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onPoseFrame = { poseFrame = it },
                onHandFrame = { if (it != null) handFrame = it }
            )

            SkeletonOverlay(modifier = Modifier.fillMaxSize(), frame = poseFrame)
            HandOverlay(modifier = Modifier.fillMaxSize(), frame = freshHandFrame)

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatsRow(
                    modeName = modeName,
                    reps = repResult.reps,
                    phase = repResult.phase,
                    avgScore = avgScore
                )

                lastQuality?.let { q -> QualityCard(q = q) }

                AnimatedVisibility(visible = calib.isActive || calib.message.isNotBlank()) {
                    CalibrationBanner(calib = calib)
                }
            }

            if (showGestureToast) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                    tonalElevation = 8.dp,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        text = gestureLabel ?: ""
                    )
                }
            }

            if (showDebug) {
                ModalBottomSheet(
                    onDismissRequest = { showDebug = false },
                    dragHandle = { BottomSheetDefaults.DragHandle() }
                ) {
                    DebugPanel(
                        modeName = modeName,
                        isRunning = isRunning,
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
                        repDebug = repResult.debug, // ✅ NEW
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
private fun StatsRow(modeName: String, reps: Int, phase: String, avgScore: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard("Mode", modeName, Modifier.weight(1f))
        StatCard("Reps", reps.toString(), Modifier.weight(1f))
        StatCard("Phase", phase, Modifier.weight(1f))
        StatCard("Avg", avgScore.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun QualityCard(q: RepQuality) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last Rep", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                AssistChip(onClick = { }, label = { Text(q.verdict) })
                Spacer(Modifier.weight(1f))
                Text("Score ${q.score}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text("Depth ${q.depthPct}%  •  Tempo ${q.tempoMs}ms", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (q.tips.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(q.tips, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    repDebug: String, // ✅ NEW
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
        Text("Mode: $modeName | ${if (isRunning) "RUNNING" else "PAUSED"}", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Reps: $reps   Phase: $phase")
                Spacer(Modifier.height(6.dp))
                Text("Angle: ${angle?.toInt()?.toString() ?: "—"}°")
                Text("Thresholds: DOWN=${thresholds.downThresh.toInt()}°  UP=${thresholds.upThresh.toInt()}°")

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text("Session: avg=$avgScore  good=$sessionGood  shallow=$sessionShallow  fast=$sessionFast")

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                // ✅ Step-1 verification: full rep state machine trace
                Text("Rep debug:", style = MaterialTheme.typography.labelLarge)
                Text(
                    repDebug.ifBlank { "—" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Posture: $feedbackStatus ($feedbackScore)")
                Spacer(Modifier.height(6.dp))
                Text(feedbackDetails, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SmartForm needs camera access for pose + hand tracking.")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onGrant) { Text("Grant Camera Permission") }
    }
}