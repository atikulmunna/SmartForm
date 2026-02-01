package com.app.smartform.hand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type

@Suppress("DEPRECATION")
class YuvToRgbConverter(context: Context) {
    private val rs: RenderScript = RenderScript.create(context)
    private val yuvToRgbIntrinsic: ScriptIntrinsicYuvToRGB =
        ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    private var yuvBits: ByteArray? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null

    fun yuvToRgb(image: Image, output: Bitmap) {
        require(image.format == ImageFormat.YUV_420_888)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val total = ySize + uSize + vSize
        val bytes = yuvBits?.takeIf { it.size == total } ?: ByteArray(total).also { yuvBits = it }

        yPlane.buffer.get(bytes, 0, ySize)
        vPlane.buffer.get(bytes, ySize, vSize)
        uPlane.buffer.get(bytes, ySize + vSize, uSize)

        if (inputAllocation == null || inputAllocation?.type?.x != bytes.size) {
            val inType = Type.Builder(rs, Element.U8(rs)).setX(bytes.size).create()
            inputAllocation = Allocation.createTyped(rs, inType, Allocation.USAGE_SCRIPT)

            val outType = Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(output.width)
                .setY(output.height)
                .create()
            outputAllocation = Allocation.createTyped(rs, outType, Allocation.USAGE_SCRIPT)
        }

        inputAllocation!!.copyFrom(bytes)
        yuvToRgbIntrinsic.setInput(inputAllocation)
        yuvToRgbIntrinsic.forEach(outputAllocation)
        outputAllocation!!.copyTo(output)
    }
}
