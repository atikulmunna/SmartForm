package com.app.smartform.hand

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class YuvToRgbConverter {

    fun rgbaProxyToBitmap(imageProxy: ImageProxy, reuse: Bitmap? = null): Bitmap {
        val plane = imageProxy.planes.firstOrNull()
            ?: throw IllegalStateException("No planes in ImageProxy")

        val width = imageProxy.width
        val height = imageProxy.height

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val out = reuse?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val buffer = plane.buffer
        buffer.rewind()

        // Fast path: perfect layout
        if (rowPadding == 0 && pixelStride == 4) {
            out.copyPixelsFromBuffer(buffer)
            return out
        }

        // Handle row padding: copy into a temp padded bitmap then crop
        val temp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        temp.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(temp, 0, 0, width, height)
        val outBuf = ByteBuffer.allocate(width * height * 4)
        cropped.copyPixelsToBuffer(outBuf)
        outBuf.rewind()
        out.copyPixelsFromBuffer(outBuf)

        return out
    }
}