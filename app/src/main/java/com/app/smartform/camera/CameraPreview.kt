package com.app.smartform.camera

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    val poseExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val handExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val poseProcessor = remember { PoseProcessor(context) }
    val handProcessor = remember { HandProcessor(context) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            val isFrontCamera = true

            // Pose: YUV (ML Kit-safe)
            val poseAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            poseAnalysis.setAnalyzer(poseExecutor) { imageProxy ->
                poseProcessor.process(
                    imageProxy = imageProxy,
                    isFrontCamera = isFrontCamera,
                    onPoseFrame = onPoseFrame
                )
                // PoseProcessor closes imageProxy asynchronously (in addOnCompleteListener)
            }

            // Hands: RGBA (MediaPipe-friendly)
            val handAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            handAnalysis.setAnalyzer(handExecutor) { imageProxy ->
                // Use camera timestamp (ns) -> ms. More stable than uptimeMillis().
                val tsMs = imageProxy.imageInfo.timestamp / 1_000_000L

                try {
                    handProcessor.process(
                        imageProxy = imageProxy,
                        isFrontCamera = isFrontCamera,
                        timestampMs = tsMs,
                        onHandFrame = onHandFrame
                    )
                } catch (_: Throwable) {
                    onHandFrame(null)
                } finally {
                    // HandProcessor does NOT close, so we close here.
                    runCatching { imageProxy.close() }
                }
            }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    poseAnalysis,
                    handAnalysis
                )
            }.onFailure {
                // If binding fails, avoid silent crash; also stop overlays.
                onHandFrame(null)
            }
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            runCatching { poseProcessor.close() }
            runCatching { handProcessor.close() }
            runCatching { poseExecutor.shutdownNow() }
            runCatching { handExecutor.shutdownNow() }
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}
