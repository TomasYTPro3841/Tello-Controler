package com.miaouss90.tellocontroler.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure color blob tracker: tap a saturated object, then follow the centroid of the pixels with the same hue.
 * Apparent size = sqrt(pixel count), so [TrackResult.scale] works like the template tracker's (distance keeping).
 * Looks only near the last position while the blob is found, in the whole frame once it is lost.
 * Needs a color frame ([GrayFrame.argb]). Not robust to similar colors elsewhere. HARDWARE-UNVERIFIED.
 */
class ColorTracker : TargetTracker {
    companion object {
        const val HUE_TOLERANCE = 18f
        const val MIN_SATURATION = 0.35f
        const val MIN_VALUE = 0.2f
        /** The blob must cover at least this fraction of the frame, at selection and while tracking. */
        const val MIN_PIXEL_FRACTION = 0.003f
        private const val SAMPLE_RADIUS = 2

        /** Hue in degrees 0..360, saturation and value 0..1. */
        fun hsv(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val delta = maxC - min(r, min(g, b))
            val hue = when {
                delta == 0f -> 0f
                maxC == r -> 60f * (((g - b) / delta) % 6f)
                maxC == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return floatArrayOf((hue + 360f) % 360f, if (maxC == 0f) 0f else delta / maxC, maxC)
        }
    }

    private var hue = 0f
    private var basePixels = 0
    private var cx = 0f
    private var cy = 0f
    private var halfWindow = 0 // 0 = whole frame
    private var selected = false

    override val hasTarget get() = selected

    override fun select(frame: GrayFrame, nx: Float, ny: Float): Boolean {
        val argb = frame.argb ?: return false
        val px = (nx * frame.width).roundToInt().coerceIn(0, frame.width - 1)
        val py = (ny * frame.height).roundToInt().coerceIn(0, frame.height - 1)
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (y in max(0, py - SAMPLE_RADIUS)..min(frame.height - 1, py + SAMPLE_RADIUS)) {
            for (x in max(0, px - SAMPLE_RADIUS)..min(frame.width - 1, px + SAMPLE_RADIUS)) {
                val c = argb[y * frame.width + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        val hsv = hsv((0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n))
        if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) return false
        hue = hsv[0]
        val blob = measure(frame, 0, 0, frame.width, frame.height)
        if (blob.count < frame.width * frame.height * MIN_PIXEL_FRACTION) return false
        basePixels = blob.count
        cx = blob.x
        cy = blob.y
        halfWindow = 0
        selected = true
        return true
    }

    override fun clear() {
        selected = false
    }

    override fun track(frame: GrayFrame): TrackResult {
        check(selected) { "no target selected" }
        val w = frame.width
        val h = frame.height
        val window = if (halfWindow == 0) null else halfWindow
        val left = if (window == null) 0 else max(0, (cx - window).roundToInt())
        val top = if (window == null) 0 else max(0, (cy - window).roundToInt())
        val right = if (window == null) w else min(w, (cx + window).roundToInt())
        val bottom = if (window == null) h else min(h, (cy + window).roundToInt())
        val blob = measure(frame, left, top, right, bottom)
        val lost = blob.count < w * h * MIN_PIXEL_FRACTION
        if (lost) {
            halfWindow = 0
        } else {
            cx = blob.x
            cy = blob.y
            halfWindow = max(24, (sqrt(blob.count.toDouble()) * 1.5).roundToInt())
        }
        val side = sqrt(blob.count.toDouble()).toFloat()
        return TrackResult(
            centerX = cx / w,
            centerY = cy / h,
            boxWidth = side / w,
            boxHeight = side / h,
            confidence = min(1f, blob.count.toFloat() / basePixels),
            lost = lost,
            scale = sqrt(blob.count.toFloat() / basePixels),
        )
    }

    private class Blob(val count: Int, val x: Float, val y: Float)

    private fun measure(frame: GrayFrame, left: Int, top: Int, right: Int, bottom: Int): Blob {
        val argb = frame.argb ?: return Blob(0, 0f, 0f)
        var count = 0
        var sx = 0L
        var sy = 0L
        for (y in top until bottom) for (x in left until right) {
            val hsv = hsv(argb[y * frame.width + x])
            if (hsv[1] >= MIN_SATURATION && hsv[2] >= MIN_VALUE && hueDistance(hsv[0], hue) <= HUE_TOLERANCE) {
                count++
                sx += x
                sy += y
            }
        }
        return if (count == 0) Blob(0, 0f, 0f) else Blob(count, sx.toFloat() / count, sy.toFloat() / count)
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b)
        return min(d, 360f - d)
    }
}
