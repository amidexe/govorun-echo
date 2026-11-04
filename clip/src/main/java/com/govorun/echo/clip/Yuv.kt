package com.govorun.echo.clip

import android.graphics.Bitmap
import android.media.Image

/**
 * A picture into the encoder's own YUV 4:2:0 layout, whatever its plane order and strides (BT.709, limited range, as the encoder
 * is told). Chroma takes the top-left pixel of each 2×2 block: on flat colours and text that is indistinguishable from averaging.
 */
internal class Yuv(
    private val width: Int,
    private val height: Int,
) {
    private val pixels = IntArray(width * height)

    fun write(
        picture: Bitmap,
        image: Image,
    ) {
        picture.getPixels(pixels, 0, width, 0, 0, width, height)
        val (yPlane, uPlane, vPlane) = image.planes
        val y = yPlane.buffer
        for (row in 0 until height) {
            val base = row * yPlane.rowStride
            for (col in 0 until width) {
                val c = pixels[row * width + col]
                y.put(base + col * yPlane.pixelStride, luma(c).toByte())
            }
        }
        val u = uPlane.buffer
        val v = vPlane.buffer
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val c = pixels[row * 2 * width + col * 2]
                u.put(row * uPlane.rowStride + col * uPlane.pixelStride, cb(c).toByte())
                v.put(row * vPlane.rowStride + col * vPlane.pixelStride, cr(c).toByte())
            }
        }
    }

    private fun luma(c: Int): Int = ((47 * r(c) + 157 * g(c) + 16 * b(c) + 128) shr 8) + 16

    private fun cb(c: Int): Int = ((-26 * r(c) - 87 * g(c) + 112 * b(c) + 128) shr 8) + 128

    private fun cr(c: Int): Int = ((112 * r(c) - 102 * g(c) - 10 * b(c) + 128) shr 8) + 128

    private fun r(c: Int) = (c shr 16) and 0xFF

    private fun g(c: Int) = (c shr 8) and 0xFF

    private fun b(c: Int) = c and 0xFF
}
