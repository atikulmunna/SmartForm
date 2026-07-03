package com.app.smartform.hand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandProcessor(context: Context) {

    private val converter = YuvToRgbConverter()
    private var reuseBitmap: Bitmap? = null

    // Null if the model asset is missing/corrupt. We degrade to "no gestures"
    // instead of crashing the whole app on first composition.
    private val landmarker: HandLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .build()

        HandLandmarker.createFromOptions(context.applicationContext, options)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to initialize HandLandmarker — gesture control disabled", t)
        null
    }

    /** True when hand tracking (and therefore gesture control) is available. */
    val available: Boolean get() = landmarker != null

    private var frameCount = 0
    private var lastFrame: HandFrame? = null

    fun process(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
        timestampMs: Long,
        onHandFrame: (HandFrame?) -> Unit
    ) {
        val lm = landmarker ?: run {
            // No model -> no hands. Overlay/gestures simply stay inactive.
            onHandFrame(null)
            return
        }

        frameCount++

        // Optional throttling: run every 2 frames.
        // To reduce blinking: re-emit the last frame with updated timestamp.
        if (frameCount % 2 != 0) {
            val lf = lastFrame
            if (lf != null) {
                // Requires HandFrame to have timestampMs; if your model uses it, this compiles.
                onHandFrame(lf.copy(timestampMs = timestampMs))
            } else {
                onHandFrame(null)
            }
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees

        // RGBA -> Bitmap (no RenderScript)
        val rawBmp = converter.rgbaProxyToBitmap(imageProxy, reuseBitmap)
        reuseBitmap = rawBmp

        val uprightBmp = rotateBitmap(rawBmp, rotation)

        // In RGBA mode + rotated bitmap path, simplest is to treat crop as full frame.
        val crop = Rect(0, 0, uprightBmp.width, uprightBmp.height)

        val mpImage = BitmapImageBuilder(uprightBmp).build()

        val result: HandLandmarkerResult = lm.detectForVideo(mpImage, timestampMs)

        val hands = result.landmarks().mapIndexed { i, landmarks ->
            val handed = result.handedness()[i].firstOrNull()
            OneHand(
                handedness = handed?.categoryName() ?: "Unknown",
                score = handed?.score() ?: 0f,
                landmarks = landmarks.map { lm -> HandPoint(lm.x(), lm.y(), lm.z()) } // normalized
            )
        }

        val frame = HandFrame(
            timestampMs = timestampMs,
            imageWidth = uprightBmp.width,
            imageHeight = uprightBmp.height,
            cropRect = crop,
            rotationDegrees = rotation,
            isFrontCamera = isFrontCamera,
            hands = hands
        )

        lastFrame = frame
        onHandFrame(frame)
    }

    fun close() {
        landmarker?.close()
    }

    private fun rotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val r = ((rotationDegrees % 360) + 360) % 360
        if (r == 0) return src
        val m = Matrix().apply { postRotate(r.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private companion object {
        const val TAG = "HandProcessor"
    }
}