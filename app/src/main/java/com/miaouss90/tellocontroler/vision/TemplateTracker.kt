package com.miaouss90.tellocontroler.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Tracked target, normalized to the frame: center (0..1) and box size (fraction of width/height). */
data class TrackResult(
    val centerX: Float,
    val centerY: Float,
    val boxWidth: Float,
    val boxHeight: Float,
    /** Zero-mean normalized cross-correlation of the best match, -1..1. */
    val confidence: Float,
    val lost: Boolean,
    /** Box side now / box side at selection: >1 the target got closer (or bigger), <1 it moved away. */
    val scale: Float = 1f,
)

/**
 * Pure single-target tracker: zero-mean normalized cross-correlation template matching in a window around the
 * last position (coarse step 2, then refined), with slow template adaptation while the match is strong.
 * Robust to brightness changes; not to large scale/rotation changes (HARDWARE-UNVERIFIED in flight).
 * Not thread-safe: use from the vision thread only.
 */
class TemplateTracker(
    private val searchRadius: Int = 24,
    private val lostThreshold: Float = 0.45f,
    private val adaptThreshold: Float = 0.85f,
    private val adaptRate: Float = 0.1f,
) : TargetTracker {
    companion object {
        /** Template side as a fraction of the frame height. */
        const val TEMPLATE_FRACTION = 0.2f
        private const val MIN_TEMPLATE_STD = 6.0
        /** Scale step tried per frame, and the tracked range relative to the selection (shortcut: bounded zoom). */
        private const val SCALE_STEP = 0.1
        private const val MIN_SCALE = 0.5
        private const val MAX_SCALE = 2.0
    }

    private var baseSize = 0
    private var size = 0
    private var template = FloatArray(0) // zero-mean
    private var templateNorm = 0.0 // sqrt(sum(t^2))
    private var x = 0 // top-left of the box
    private var y = 0
    private var lostFrames = 0

    override val hasTarget: Boolean get() = size > 0

    /** Selects the patch centered on (nx, ny) normalized; false if the area is too uniform to track. */
    override fun select(frame: GrayFrame, nx: Float, ny: Float): Boolean {
        val side = max(8, (frame.height * TEMPLATE_FRACTION).roundToInt())
        val left = ((nx * frame.width).roundToInt() - side / 2).coerceIn(0, frame.width - side)
        val top = ((ny * frame.height).roundToInt() - side / 2).coerceIn(0, frame.height - side)
        val patch = patch(frame, left, top, side)
        val mean = patch.average()
        val centered = FloatArray(patch.size) { (patch[it] - mean).toFloat() }
        val norm = sqrt(centered.sumOf { (it * it).toDouble() })
        if (norm / sqrt(patch.size.toDouble()) < MIN_TEMPLATE_STD) return false
        baseSize = side
        size = side
        template = centered
        templateNorm = norm
        x = left
        y = top
        lostFrames = 0
        return true
    }

    override fun clear() {
        size = 0
        template = FloatArray(0)
    }

    override fun track(frame: GrayFrame): TrackResult {
        check(hasTarget) { "no target selected" }
        // Widen the search while lost, to re-acquire after a quick motion.
        val radius = searchRadius * (1 + min(lostFrames, 3))
        var bestScore = -2.0
        var bestX = x
        var bestY = y
        fun consider(cx: Int, cy: Int) {
            if (cx < 0 || cy < 0 || cx > frame.width - size || cy > frame.height - size) return
            val s = score(frame, cx, cy, size, template, templateNorm)
            if (s > bestScore) {
                bestScore = s
                bestX = cx
                bestY = cy
            }
        }
        for (dy in -radius..radius step 2) for (dx in -radius..radius step 2) consider(x + dx, y + dy)
        val coarseX = bestX
        val coarseY = bestY
        for (dy in -1..1) for (dx in -1..1) consider(coarseX + dx, coarseY + dy)

        val lost = bestScore < lostThreshold
        if (lost) {
            lostFrames++
        } else {
            lostFrames = 0
            x = bestX
            y = bestY
            bestScore = refineScale(frame, bestScore)
            if (bestScore > adaptThreshold) adapt(frame)
        }
        return TrackResult(
            centerX = (x + size / 2f) / frame.width,
            centerY = (y + size / 2f) / frame.height,
            boxWidth = size.toFloat() / frame.width,
            boxHeight = size.toFloat() / frame.height,
            confidence = bestScore.toFloat(),
            lost = lost,
            scale = size.toFloat() / baseSize,
        )
    }

    /** Tries the template one step bigger/smaller around the same center; keeps a clearly better fit. */
    private fun refineScale(frame: GrayFrame, current: Double): Double {
        var best = current
        for (factor in doubleArrayOf(1 - SCALE_STEP, 1 + SCALE_STEP)) {
            val side = (size * factor).roundToInt()
            if (side < 8 || side > frame.height || side == size) continue
            if (side < baseSize * MIN_SCALE || side > baseSize * MAX_SCALE) continue
            val resized = resample(template, size, side)
            val norm = sqrt(resized.sumOf { (it * it).toDouble() })
            if (norm <= 1e-6) continue
            val left = (x + (size - side) / 2).coerceIn(0, frame.width - side)
            val top = (y + (size - side) / 2).coerceIn(0, frame.height - side)
            val s = score(frame, left, top, side, resized, norm)
            // Margin: prefer staying at the same scale unless the other one matches clearly better.
            if (s > best + 0.03) {
                best = s
                size = side
                template = resized
                templateNorm = norm
                x = left
                y = top
            }
        }
        return best
    }

    /** Nearest-neighbor resize of a square template, re-centered to zero mean. */
    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        val out = FloatArray(to * to) { src[(it / to * from / to) * from + (it % to * from / to)] }
        val mean = out.average().toFloat()
        return FloatArray(out.size) { out[it] - mean }
    }

    private fun score(frame: GrayFrame, left: Int, top: Int, size: Int, template: FloatArray, templateNorm: Double): Double {
        var sum = 0.0
        var sumSq = 0.0
        var cross = 0.0
        var i = 0
        for (row in top until top + size) {
            var index = row * frame.width + left
            repeat(size) {
                val v = frame.pixels[index++].toDouble()
                sum += v
                sumSq += v * v
                cross += v * template[i++]
            }
        }
        val n = (size * size).toDouble()
        val variance = sumSq - sum * sum / n
        if (variance <= 1e-6) return -1.0
        // sum((I - meanI) * T) == sum(I * T) because T is zero-mean.
        return cross / (sqrt(variance) * templateNorm)
    }

    private fun adapt(frame: GrayFrame) {
        val patch = patch(frame, x, y, size)
        val mean = patch.average()
        val blended = FloatArray(template.size) {
            template[it] * (1 - adaptRate) + (patch[it] - mean).toFloat() * adaptRate
        }
        val blendedMean = blended.average().toFloat()
        template = FloatArray(blended.size) { blended[it] - blendedMean }
        templateNorm = sqrt(template.sumOf { (it * it).toDouble() })
    }

    private fun patch(frame: GrayFrame, left: Int, top: Int, side: Int) =
        IntArray(side * side) { frame[left + it % side, top + it / side] }
}
