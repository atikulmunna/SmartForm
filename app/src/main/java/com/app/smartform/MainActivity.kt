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
import androidx.compose.ui.unit.dp
import com.app.smartform.camera.CameraPreview
import com.app.smartform.gesture.Gesture
import com.app.smartform.gesture.GestureDetector
import com.app.smartform.hand.HandFrame
import com.app.smartform.hand.HandOverlay
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PostureEvaluator
import com.app.smartform.pose.SkeletonOverlay
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
    var poseFrame by remember { mutableStateOf<PoseFrame?>(null) }
    var handFrame by remember { mutableStateOf<HandFrame?>(null) }

    var isRunning by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableStateOf(0) } // 0=Curl, 1=Squat, 2=Push-up

    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureShownAt by remember { mutableLongStateOf(0L) }

    // ---- New: arm/disarm + stability tracking ----
    var armed by remember { mutableStateOf(true) }
    var noneStreak by remember { mutableIntStateOf(0) }
    var pinchStreak by remember { mutableIntStateOf(0) }
    var palmStreak by remember { mutableIntStateOf(0) }

    val feedback = PostureEvaluator.evaluate(poseFrame)

    val now = SystemClock.uptimeMillis()
    val freshHandFrame = handFrame?.takeIf { now - it.timestampMs < 200 }

    val modeName = when (modeIndex) {
        0 -> "Curl"
        1 -> "Squat"
        else -> "Push-up"
    }

    val showGesture = gestureLabel != null && (SystemClock.uptimeMillis() - gestureShownAt) < 700

    val pinchNeed = 2
    val palmNeed = 3
    val rearmNeedNone = 2


    LaunchedEffect(freshHandFrame?.timestampMs) {
        val g = GestureDetector.detect(
            freshHandFrame,
            minHandScore = 0.45f,
            minPalmAreaForOpenPalm = 0.010f
        )


        when (g) {
            is Gesture.None -> {
                noneStreak += 1
                pinchStreak = 0
                palmStreak = 0
                if (noneStreak >= rearmNeedNone) armed = true
            }

            is Gesture.Pinch -> {
                noneStreak = 0
                pinchStreak += 1
                palmStreak = 0

                if (armed && pinchStreak >= pinchNeed) {
                    isRunning = !isRunning
                    armed = false
                    pinchStreak = 0
                    gestureLabel = "Pinch → ${if (isRunning) "Start" else "Stop"}"
                    gestureShownAt = SystemClock.uptimeMillis()
                }
            }

            is Gesture.OpenPalm -> {
                noneStreak = 0
                palmStreak += 1
                pinchStreak = 0

                if (armed && palmStreak >= palmNeed) {
                    modeIndex = (modeIndex + 1) % 3
                    armed = false
                    palmStreak = 0
                    gestureLabel = "Palm → Switch mode"
                    gestureShownAt = SystemClock.uptimeMillis()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onPoseFrame = { pf: PoseFrame -> poseFrame = pf },
            onHandFrame = { hf: HandFrame? ->
                if (hf != null) handFrame = hf
            }
        )

        SkeletonOverlay(
            modifier = Modifier.fillMaxSize(),
            frame = poseFrame
        )

        HandOverlay(
            modifier = Modifier.fillMaxSize(),
            frame = freshHandFrame
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
            tonalElevation = 2.dp
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                text = "Mode: $modeName | ${if (isRunning) "RUNNING" else "PAUSED"}\n" +
                        "${feedback.status}  (${feedback.score})\n${feedback.details}"
            )
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
