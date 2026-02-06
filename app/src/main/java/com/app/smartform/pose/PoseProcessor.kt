package com.app.smartform.pose

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class PoseProcessor(context: Context) {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val latestFrameId = AtomicLong(0L)

    @OptIn(ExperimentalGetImage::class)
    fun process(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
        onPoseFrame: (PoseFrame) -> Unit
    ) {
        val frameId = latestFrameId.incrementAndGet()

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // ✅ Guard: ML Kit only supports YUV_420_888 or JPEG
        val fmt = mediaImage.format
        if (fmt != ImageFormat.YUV_420_888 && fmt != ImageFormat.JPEG) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees

        val rotatedCrop = rotateRectToUpright(
            rect = imageProxy.cropRect,
            rotationDegrees = rotation,
            srcWidth = imageProxy.width,
            srcHeight = imageProxy.height
        )

        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                if (frameId != latestFrameId.get()) return@addOnSuccessListener

                val map = pose.allPoseLandmarks.associate { lm ->
                    lm.landmarkType to PosePoint(
                        x = lm.position.x,
                        y = lm.position.y,
                        inFrameLikelihood = lm.inFrameLikelihood
                    )
                }

                onPoseFrame(
                    PoseFrame(
                        imageWidth = uprightW,
                        imageHeight = uprightH,
                        cropRect = rotatedCrop,
                        rotationDegrees = rotation,
                        isFrontCamera = isFrontCamera,
                        points = map
                    )
                )
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun rotateRectToUpright(
        rect: Rect,
        rotationDegrees: Int,
        srcWidth: Int,
        srcHeight: Int
    ): Rect {
        val l = rect.left.toFloat()
        val t = rect.top.toFloat()
        val r = rect.right.toFloat()
        val b = rect.bottom.toFloat()

        fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
            return when (((rotationDegrees % 360) + 360) % 360) {
                0 -> Pair(x, y)
                90 -> Pair(y, srcWidth - x)
                180 -> Pair(srcWidth - x, srcHeight - y)
                270 -> Pair(srcHeight - y, x)
                else -> Pair(x, y)
            }
        }

        val p1 = mapPoint(l, t)
        val p2 = mapPoint(r, t)
        val p3 = mapPoint(r, b)
        val p4 = mapPoint(l, b)

        val xs = floatArrayOf(p1.first, p2.first, p3.first, p4.first)
        val ys = floatArrayOf(p1.second, p2.second, p3.second, p4.second)

        val left = xs.minOrNull() ?: 0f
        val right = xs.maxOrNull() ?: 0f
        val top = ys.minOrNull() ?: 0f
        val bottom = ys.maxOrNull() ?: 0f

        val outW = if (rotationDegrees == 90 || rotationDegrees == 270) srcHeight else srcWidth
        val outH = if (rotationDegrees == 90 || rotationDegrees == 270) srcWidth else srcHeight

        val cl = left.coerceIn(0f, outW.toFloat()).toInt()
        val cr = right.coerceIn(0f, outW.toFloat()).toInt()
        val ct = top.coerceIn(0f, outH.toFloat()).toInt()
        val cb = bottom.coerceIn(0f, outH.toFloat()).toInt()

        return Rect(min(cl, cr), min(ct, cb), max(cl, cr), max(ct, cb))
    }
}