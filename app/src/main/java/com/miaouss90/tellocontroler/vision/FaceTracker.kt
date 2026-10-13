package com.miaouss90.tellocontroler.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import kotlin.math.hypot

/**
 * Person tracking by face: the platform [FaceDetector] (no extra dependency) finds faces, the tracker keeps the
 * one nearest to the selected face and uses the eye distance as apparent size (so distance keeping works too).
 * Limits: frontal faces only, weak in poor light. A person facing away is reported lost. HARDWARE-UNVERIFIED.
 */
class FaceTracker : TargetTracker {
    private companion object {
        const val MAX_FACES = 5
        const val MIN_CONFIDENCE = 0.4f
        const val EYES_TO_BOX = 2.6f
        /** A face is "the same" if it moved less than this fraction of the frame width between grabs. */
        const val MATCH_DISTANCE = 0.3f
    }

    private class Face(val x: Float, val y: Float, val eyes: Float)

    private var selected = false
    private var cx = 0f
    private var cy = 0f
    private var eyes = 1f
    private var baseEyes = 1f

    override val hasTarget get() = selected

    override fun select(frame: GrayFrame, nx: Float, ny: Float): Boolean {
        val face = detect(frame).minByOrNull { hypot(it.x / frame.width - nx, it.y / frame.width - ny * frame.height / frame.width) }
            ?: return false
        cx = face.x
        cy = face.y
        eyes = face.eyes
        baseEyes = face.eyes
        selected = true
        return true
    }

    override fun clear() {
        selected = false
    }

    override fun track(frame: GrayFrame): TrackResult {
        check(selected) { "no target selected" }
        val face = detect(frame)
            .filter { hypot(it.x - cx, it.y - cy) < MATCH_DISTANCE * frame.width }
            .minByOrNull { hypot(it.x - cx, it.y - cy) }
        if (face != null) {
            cx = face.x
            cy = face.y
            eyes = face.eyes
        }
        val side = eyes * EYES_TO_BOX
        return TrackResult(
            centerX = cx / frame.width,
            centerY = cy / frame.height,
            boxWidth = side / frame.width,
            boxHeight = side / frame.height,
            confidence = if (face != null) 1f else 0f,
            lost = face == null,
            scale = eyes / baseEyes,
        )
    }

    private fun detect(frame: GrayFrame): List<Face> {
        val argb = frame.argb ?: return emptyList()
        val width = frame.width and 1.inv() // FaceDetector needs an even width
        val bitmap = Bitmap.createBitmap(width, frame.height, Bitmap.Config.RGB_565)
        bitmap.setPixels(argb, 0, frame.width, 0, 0, width, frame.height)
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = FaceDetector(width, frame.height, MAX_FACES).findFaces(bitmap, found)
        bitmap.recycle()
        return (0 until count).mapNotNull { i ->
            val f = found[i] ?: return@mapNotNull null
            if (f.confidence() < MIN_CONFIDENCE) return@mapNotNull null
            val mid = PointF().also { f.getMidPoint(it) }
            Face(mid.x, mid.y, f.eyesDistance())
        }
    }
}
