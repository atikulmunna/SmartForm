package com.app.smartform

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

@Composable
private fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var poseFrame by remember { mutableStateOf<PoseFrame?>(null) }
    var handFrame by remember { mutableStateOf<HandFrame?>(null) }

    var isRunning by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableStateOf(0) } // 0=Curl, 1=Squat, 2=Push-up

    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureShownAt by remember { mutableLongStateOf(0L) }

    // Pinch-hold + cooldown (1s)
    var pinchStartTime by remember { mutableLongStateOf(0L) }
    var pinchActive by remember { mutableStateOf(false) }
    var lastToggleTime by remember { mutableLongStateOf(0L) }
    val pinchHoldMs = 1000L
    val toggleCooldownMs = 1000L

    val now = SystemClock.uptimeMillis()
    val freshHandFrame = handFrame?.takeIf { now - it.timestampMs < 200 }

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

    val showGesture = gestureLabel != null && (SystemClock.uptimeMillis() - gestureShownAt) < 700

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

    // Session quality tracking
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
                "TOO FAST + SHALLOW" -> { sessionFast += 1; sessionShallow += 1 }
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

    // Calibration state
    var calib by remember { mutableStateOf(CalibrationState()) }

    val feedback = PostureEvaluator.evaluate(poseFrame)

    fun currentAngle(): Double? = repCounter.currentPrimaryAngle(mode, poseFrame)

    fun buildCalibratedProfile(existing: CalibrationProfile, mode: ExerciseMode, up: Double, down: Double): CalibrationProfile {
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

    // Gesture detection loop
    LaunchedEffect(freshHandFrame?.timestampMs) {
        val nowMs = SystemClock.uptimeMillis()

        val g = GestureDetector.detect(
            freshHandFrame,
            minHandScore = 0.5f,
            minPalmAreaForOpenPalm = 0.012f
        )

        when (g) {
            is Gesture.Pinch -> {
                if (nowMs - lastToggleTime < toggleCooldownMs) return@LaunchedEffect

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
                                            message = "Saved calibration: UP=${up.toInt()}°, DOWN=${a.toInt()}°"
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
                if (!isRunning && !calib.isActive && nowMs - lastToggleTime >= toggleCooldownMs) {
                    modeIndex = (modeIndex + 1) % 3
                    lastToggleTime = nowMs
                    gestureLabel = "Palm → Switch mode"
                    gestureShownAt = nowMs
                }
            }

            is Gesture.None -> pinchActive = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ✅ ONLY a call — NO duplicate CameraPreview function in this file.
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onPoseFrame = { poseFrame = it },
            onHandFrame = { hf ->
                if (hf != null) handFrame = hf
            }
        )

        SkeletonOverlay(modifier = Modifier.fillMaxSize(), frame = poseFrame)
        HandOverlay(modifier = Modifier.fillMaxSize(), frame = freshHandFrame)

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val avgScore = if (repResult.reps == 0) 0 else (sessionAvgScoreSum / repResult.reps)

                Text(
                    text =
                        "Mode: $modeName | ${if (isRunning) "RUNNING" else "PAUSED"}\n" +
                                "Reps: ${repResult.reps} | Phase: ${repResult.phase}\n" +
                                "Session: avg=$avgScore good=$sessionGood shallow=$sessionShallow fast=$sessionFast\n" +
                                "${feedback.status} (${feedback.score})\n" +
                                feedback.details
                )

                lastQuality?.let { q ->
                    Spacer(Modifier.height(6.dp))
                    Text("Last rep: ${q.verdict} | score=${q.score} | depth=${q.depthPct}% | tempo=${q.tempoMs}ms")
                    Text(q.tips)
                }

                if (calib.isActive) {
                    Spacer(Modifier.height(8.dp))
                    Text("CALIBRATING: ${calib.mode} | Step: ${calib.step}\n${calib.message}")
                } else if (calib.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(calib.message)
                }

                if (!isRunning) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            calib = CalibrationState(
                                isActive = true,
                                mode = mode,
                                step = CalibrationStep.BASELINE_UP,
                                message = "Do UP pose for $modeName and pinch-hold to capture."
                            )
                        }) { Text("Calibrate") }

                        OutlinedButton(onClick = {
                            scope.launch { store.resetToDefaults() }
                            calib = calib.copy(isActive = false, message = "Reset calibration to defaults.")
                        }) { Text("Reset") }
                    }
                }
            }
        }

        if (showGesture) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                tonalElevation = 6.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    text = gestureLabel ?: ""
                )
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
