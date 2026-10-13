package com.miaouss90.tellocontroler.vision

import com.miaouss90.tellocontroler.controller.RcInput
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure proportional follow: turns (yaw) and climbs/descends (throttle) to bring the tracked target back to the
 * image center, and moves forward/back (pitch) to keep the target's apparent size equal to what it was when it was
 * selected. The Tello has no range sensor ahead, so distance is only that: select the target from 1 m and follow
 * holds ~1 m (apparent size ∝ 1/distance). Gentle, capped commands; they still go through RcSafetyLoop (height
 * limit, smoothing, stale watchdog). HARDWARE-UNVERIFIED gains and signs.
 */
object FollowController {
    const val MAX_YAW = 30
    const val MAX_THROTTLE = 25
    const val MAX_PITCH = 20
    const val YAW_GAIN = 80f
    const val THROTTLE_GAIN = 60f
    const val PITCH_GAIN = 60f
    /** No forward/back motion within this relative size change (±8 %), avoids hunting. */
    const val SCALE_DEAD_BAND = 0.08f
    /** No forward/back motion while the target is further than this from the center horizontally (turn first). */
    const val PITCH_MAX_OFFSET = 0.25f
    /** No correction within this distance from the center (fraction of the frame), avoids hunting. */
    const val DEAD_BAND = 0.04f

    fun command(target: TrackResult): RcInput {
        if (target.lost) return RcInput.NEUTRAL
        val errorX = target.centerX - 0.5f // > 0: target right of center → turn right (positive yaw)
        val errorY = target.centerY - 0.5f // > 0: target below center → descend (negative throttle)
        // scale > 1: target looks bigger = too close → back off (negative pitch); < 1: too far → advance.
        val pitch = if (abs(errorX) > PITCH_MAX_OFFSET) 0 else -correction(target.scale - 1f, PITCH_GAIN, MAX_PITCH, SCALE_DEAD_BAND)
        return RcInput(
            pitch = pitch,
            yaw = correction(errorX, YAW_GAIN, MAX_YAW),
            throttle = -correction(errorY, THROTTLE_GAIN, MAX_THROTTLE),
        )
    }

    private fun correction(error: Float, gain: Float, max: Int, deadBand: Float = DEAD_BAND): Int =
        if (abs(error) < deadBand) 0 else (error * gain).roundToInt().coerceIn(-max, max)
}

/** Pure input arbitration: any pilot stick input wins; otherwise follow (if active); otherwise pilot neutral. */
object InputArbiter {
    fun choose(pilot: RcInput?, follow: RcInput?): RcInput? = when {
        pilot != null && !pilot.isNeutral -> pilot
        follow != null -> follow
        else -> pilot
    }
}
