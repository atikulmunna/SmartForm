package com.app.smartform.hand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class HandProcessor(context: Context) {

    private val converter = YuvToRgbConverter(context.applicationContext)

    private val landmarker: HandLandmarker = run {
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
    }

    private var frameCount = 0
    private val latestReqId = AtomicLong(0L)

    @ExperimentalGetImage
    fun process(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
        timestampMs: Long,
        onHandFrame: (HandFrame?) -> Unit
    ) {
        val reqId = latestReqId.incrementAndGet()

        frameCount++
        if (frameCount % 2 != 0) {
            if (reqId == latestReqId.get()) onHandFrame(null)
            return
        }

        val image = imageProxy.image ?: run {
            if (reqId == latestReqId.get()) onHandFrame(null)
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees

        val rawBmp = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        converter.yuvToRgb(image, rawBmp)

        val uprightBmp = rotateBitmap(rawBmp, rotation)

        val rotatedCrop = rotateRectToUpright(
            rect = imageProxy.cropRect,
            rotationDegrees = rotation,
            srcWidth = imageProxy.width,
            srcHeight = imageProxy.height
        )

        val mpImage = BitmapImageBuilder(uprightBmp).build()

        val result: HandLandmarkerResult = landmarker.detectForVideo(mpImage, timestampMs)

        if (reqId != latestReqId.get()) return

        val hands = result.landmarks().mapIndexed { i, landmarks ->
            val handed = result.handedness()[i].firstOrNull()
            OneHand(
                handedness = handed?.categoryName() ?: "Unknown",
                score = handed?.score() ?: 0f,
                landmarks = landmarks.map { lm -> HandPoint(lm.x(), lm.y(), lm.z()) }
            )
        }

        onHandFrame(
            HandFrame(
                imageWidth = uprightBmp.width,
                imageHeight = uprightBmp.height,
                cropRect = rotatedCrop,
                rotationDegrees = rotation,
                isFrontCamera = isFrontCamera,
                timestampMs = timestampMs,
                hands = hands
            )
        )
    }

    fun close() {
        landmarker.close()
    }

    private fun rotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val r = ((rotationDegrees % 360) + 360) % 360
        if (r == 0) return src
        val m = Matrix().apply { postRotate(r.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
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
