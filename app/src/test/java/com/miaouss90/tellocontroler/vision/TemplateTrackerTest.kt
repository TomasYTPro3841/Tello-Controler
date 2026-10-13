package com.miaouss90.tellocontroler.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTrackerTest {
    private val w = GrayFrame.VISION_WIDTH
    private val h = GrayFrame.VISION_HEIGHT

    /** Flat gray background with a textured 20x20 "object" (checker + gradient) at (ox, oy). */
    private fun scene(ox: Int, oy: Int, brightness: Int = 0): GrayFrame {
        val px = IntArray(w * h) { 90 + brightness }
        for (y in 0 until 20) for (x in 0 until 20) {
            val checker = if ((x / 5 + y / 5) % 2 == 0) 200 else 40
            px[(oy + y) * w + ox + x] = (checker + x * 2 + brightness).coerceIn(0, 255)
        }
        return GrayFrame(w, h, px)
    }

    private fun centerOf(ox: Int, oy: Int) = (ox + 10f) / w to (oy + 10f) / h

    @Test
    fun `follows a moving object`() {
        val tracker = TemplateTracker()
        val (sx, sy) = centerOf(100, 80)
        assertTrue(tracker.select(scene(100, 80), sx, sy))
        var ox = 100
        var oy = 80
        repeat(6) {
            ox += 7
            oy -= 4
            val r = tracker.track(scene(ox, oy))
            assertFalse(r.lost)
            val (ex, ey) = centerOf(ox, oy)
            assertEquals(ex, r.centerX, 1.5f / w)
            assertEquals(ey, r.centerY, 1.5f / h)
        }
    }

    @Test
    fun `robust to a brightness change`() {
        val tracker = TemplateTracker()
        val (sx, sy) = centerOf(60, 60)
        tracker.select(scene(60, 60), sx, sy)
        val r = tracker.track(scene(64, 62, brightness = 20))
        assertFalse(r.lost)
        assertTrue(r.confidence > 0.9f)
    }

    @Test
    fun `flat area cannot be selected`() {
        assertFalse(TemplateTracker().select(scene(10, 10), 0.8f, 0.8f))
    }

    @Test
    fun `object gone is reported lost and the box stays put`() {
        val tracker = TemplateTracker()
        val (sx, sy) = centerOf(100, 80)
        tracker.select(scene(100, 80), sx, sy)
        val before = tracker.track(scene(100, 80))
        val flat = GrayFrame(w, h, IntArray(w * h) { 90 })
        val r = tracker.track(flat)
        assertTrue(r.lost)
        assertEquals(before.centerX, r.centerX, 1e-6f)
    }

    /** Same texture as [scene] but stretched to [side] x [side] pixels, centered on (cx, cy). */
    private fun scaledScene(cx: Int, cy: Int, side: Int): GrayFrame {
        val px = IntArray(w * h) { 90 }
        for (y in 0 until side) for (x in 0 until side) {
            val u = x * 20 / side
            val v = y * 20 / side
            val checker = if ((u / 5 + v / 5) % 2 == 0) 200 else 40
            px[(cy - side / 2 + y) * w + cx - side / 2 + x] = (checker + u * 2).coerceIn(0, 255)
        }
        return GrayFrame(w, h, px)
    }

    @Test
    fun `reports the scale when the object gets closer and then further`() {
        val tracker = TemplateTracker()
        // The template (36 px) is bigger than the object (20 px), so select on a scene where the object fills it.
        val start = scaledScene(120, 90, 36)
        assertTrue(tracker.select(start, 0.5f, 0.5f))
        var scale = 1f
        for (side in listOf(40, 44, 48, 52)) scale = tracker.track(scaledScene(120, 90, side)).scale
        assertTrue("scale $scale", scale > 1.2f)
        for (side in listOf(48, 44, 40, 36, 32, 29)) scale = tracker.track(scaledScene(120, 90, side)).scale
        assertTrue("scale $scale", scale < 0.9f)
    }

    @Test
    fun `scale stays 1 for a still object`() {
        val tracker = TemplateTracker()
        tracker.select(scaledScene(120, 90, 36), 0.5f, 0.5f)
        assertEquals(1f, tracker.track(scaledScene(120, 90, 36)).scale, 0.01f)
    }

    @Test
    fun `downscale halves the frame averaging luminance`() {
        val argb = IntArray(4 * 4) { 0xFF000000.toInt() or (if (it % 4 < 2) 0xFFFFFF else 0) }
        val small = GrayFrame.fromArgb(4, 4, argb).downscale2()
        assertEquals(2, small.width)
        assertEquals(255, small.pixels[0])
        assertEquals(0, small.pixels[1])
        assertEquals(0xFFFFFFFF.toInt(), small.argb!![0])
    }
}
