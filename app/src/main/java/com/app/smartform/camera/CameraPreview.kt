package com.app.smartform.camera

import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.app.smartform.hand.HandFrame
import com.app.smartform.hand.HandProcessor
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PoseProcessor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onPoseFrame: (PoseFrame) -> Unit,
    onHandFrame: (HandFrame?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val poseProcessor = remember { PoseProcessor(context) }
    val handProcessor = remember { HandProcessor(context) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            val isFrontCamera = true

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val ts = SystemClock.uptimeMillis()

                runCatching {
                    handProcessor.process(
                        imageProxy = imageProxy,
                        isFrontCamera = isFrontCamera,
                        timestampMs = ts,
                        onHandFrame = onHandFrame
                    )
                }.onFailure {
                    onHandFrame(null)
                }

                poseProcessor.process(
                    imageProxy = imageProxy,
                    isFrontCamera = isFrontCamera,
                    onPoseFrame = onPoseFrame
                )
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            runCatching { poseProcessor.close() }
            runCatching { handProcessor.close() }
            runCatching { analysisExecutor.shutdown() }
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}
