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

    val feedback = PostureEvaluator.evaluate(poseFrame)

    val now = SystemClock.uptimeMillis()
    val freshHandFrame = handFrame?.takeIf { now - it.timestampMs < 200 }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onPoseFrame = { pf: PoseFrame -> poseFrame = pf },
            onHandFrame = { hf: HandFrame? -> handFrame = hf }
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
                text = "${feedback.status}  (${feedback.score})\n${feedback.details}"
            )
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
