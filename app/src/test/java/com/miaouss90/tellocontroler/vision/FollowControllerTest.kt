package com.miaouss90.tellocontroler.vision

import com.miaouss90.tellocontroler.controller.RcInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowControllerTest {
    private fun at(x: Float, y: Float, lost: Boolean = false, scale: Float = 1f) =
        TrackResult(x, y, 0.2f, 0.2f, 0.9f, lost, scale)

    @Test
    fun `centered target needs no correction`() {
        assertEquals(RcInput.NEUTRAL, FollowController.command(at(0.52f, 0.48f)))
    }

    @Test
    fun `target right and below turns right and descends`() {
        val c = FollowController.command(at(0.7f, 0.6f))
        assertEquals(16, c.yaw)
        assertEquals(-6, c.throttle)
        assertEquals(0, c.pitch)
        assertEquals(0, c.roll)
    }

    @Test
    fun `corrections are capped`() {
        val c = FollowController.command(at(0f, 0f))
        assertEquals(-FollowController.MAX_YAW, c.yaw)
        assertEquals(FollowController.MAX_THROTTLE, c.throttle)
    }

    @Test
    fun `target too far advances, too close backs off`() {
        assertEquals(12, FollowController.command(at(0.5f, 0.5f, scale = 0.8f)).pitch)
        assertEquals(-12, FollowController.command(at(0.5f, 0.5f, scale = 1.2f)).pitch)
    }

    @Test
    fun `size within the dead band holds position`() {
        assertEquals(0, FollowController.command(at(0.5f, 0.5f, scale = 1.05f)).pitch)
        assertEquals(0, FollowController.command(at(0.5f, 0.5f, scale = 0.95f)).pitch)
    }

    @Test
    fun `pitch is capped and never drives forward while the target is far off-center`() {
        assertEquals(FollowController.MAX_PITCH, FollowController.command(at(0.5f, 0.5f, scale = 0.3f)).pitch)
        assertEquals(0, FollowController.command(at(0.9f, 0.5f, scale = 0.5f)).pitch)
    }

    @Test
    fun `lost target means neutral`() {
        assertEquals(RcInput.NEUTRAL, FollowController.command(at(0.9f, 0.9f, lost = true)))
    }

    @Test
    fun `pilot input always overrides follow`() {
        val pilot = RcInput(roll = 20)
        val follow = RcInput(yaw = 10)
        assertEquals(pilot, InputArbiter.choose(pilot, follow))
        assertEquals(follow, InputArbiter.choose(RcInput.NEUTRAL, follow))
        assertEquals(follow, InputArbiter.choose(null, follow))
        assertEquals(RcInput.NEUTRAL, InputArbiter.choose(RcInput.NEUTRAL, null))
        assertNull(InputArbiter.choose(null, null))
    }
}
