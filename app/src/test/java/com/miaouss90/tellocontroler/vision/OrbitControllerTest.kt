package com.miaouss90.tellocontroler.vision

import com.miaouss90.tellocontroler.controller.RcInput
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitControllerTest {
    private fun at(x: Float, scale: Float = 1f, lost: Boolean = false) = TrackResult(x, 0.5f, 0.2f, 0.2f, 0.9f, lost, scale)

    @Test
    fun `circles with constant roll and keeps the target centered and at distance`() {
        val right = OrbitController.command(at(0.3f, scale = 0.8f), clockwise = true, speed = 15)
        assertEquals(15, right.roll)
        assertEquals(-16, right.yaw) // target left of center → turn left
        assertEquals(12, right.pitch) // too far → advance
        assertEquals(-15, OrbitController.command(at(0.5f), clockwise = false, speed = 15).roll)
    }

    @Test
    fun `speed is capped and a lost target stops the orbit`() {
        assertEquals(OrbitController.MAX_SPEED, OrbitController.command(at(0.5f), true, 99).roll)
        assertEquals(RcInput.NEUTRAL, OrbitController.command(at(0.5f, lost = true), true, 15))
    }
}
