package com.miaouss90.tellocontroler.vision

import com.miaouss90.tellocontroler.controller.RcInput

/**
 * Pure ORBIT: FOLLOW's yaw/throttle/distance corrections keep the target centered and at the selection distance
 * (the radius) while a constant sideways roll circles it. No side sensors: fly it in free space only.
 * Capped and routed through RcSafetyLoop like FOLLOW. HARDWARE-UNVERIFIED (roll sign = which way it circles).
 */
object OrbitController {
    const val MAX_SPEED = 25
    const val DEFAULT_SPEED = 15

    /** [clockwise]: roll right (the drone moves to its right around the target). */
    fun command(target: TrackResult, clockwise: Boolean, speed: Int): RcInput {
        if (target.lost) return RcInput.NEUTRAL
        val roll = speed.coerceIn(0, MAX_SPEED) * if (clockwise) 1 else -1
        return FollowController.command(target).copy(roll = roll)
    }
}
