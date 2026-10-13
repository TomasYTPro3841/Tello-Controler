package com.miaouss90.tellocontroler.vision

/**
 * Pure 8-bit luminance image, row-major, values 0..255. [argb] is the same frame in color when it came from the
 * video; the grabber reuses that array, so it is only valid during the frame callback.
 */
class GrayFrame(val width: Int, val height: Int, val pixels: IntArray, val argb: IntArray? = null) {
    init {
        require(pixels.size == width * height) { "pixels size ${pixels.size} != $width x $height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Half-size copy (2x2 average for luminance, subsampled color) for the trackers when QR needs a bigger grab. */
    fun downscale2(): GrayFrame {
        val w = width / 2
        val h = height / 2
        val gray = IntArray(w * h)
        val color = argb?.let { IntArray(w * h) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = (2 * y) * width + 2 * x
            gray[y * w + x] = (pixels[i] + pixels[i + 1] + pixels[i + width] + pixels[i + width + 1]) / 4
            if (color != null) color[y * w + x] = argb!![i]
        }
        return GrayFrame(w, h, gray, color)
    }

    companion object {
        /** Width/height of the frames grabbed from the video for vision (1/4 of 960x720). */
        const val VISION_WIDTH = 240
        const val VISION_HEIGHT = 180

        /** ITU-R BT.601 luma from packed ARGB. */
        fun luma(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 77 + g * 150 + b * 29) shr 8
        }

        fun fromArgb(width: Int, height: Int, argb: IntArray) =
            GrayFrame(width, height, IntArray(argb.size) { luma(argb[it]) }, argb)
    }
}
