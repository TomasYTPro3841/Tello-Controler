package com.miaouss90.tellocontroler.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTrackerTest {
    private val w = GrayFrame.VISION_WIDTH
    private val h = GrayFrame.VISION_HEIGHT
    private val red = 0xFFE02020.toInt()
    private val green = 0xFF20C030.toInt()

    private fun scene(cx: Int, cy: Int, side: Int, color: Int = red): GrayFrame {
        val argb = IntArray(w * h) { 0xFF808080.toInt() }
        for (y in cy - side / 2 until cy + side / 2) for (x in cx - side / 2 until cx + side / 2) argb[y * w + x] = color
        return GrayFrame.fromArgb(w, h, argb)
    }

    @Test
    fun `follows a colored square and reports its scale`() {
        val t = ColorTracker()
        assertTrue(t.select(scene(100, 90, 30), 100f / w, 90f / h))
        val moved = t.track(scene(130, 70, 30))
        assertFalse(moved.lost)
        assertEquals(130f / w, moved.centerX, 2f / w)
        assertEquals(70f / h, moved.centerY, 2f / h)
        assertEquals(1f, moved.scale, 0.05f)
        assertEquals(1.5f, t.track(scene(130, 70, 45)).scale, 0.1f)
        assertEquals(0.5f, t.track(scene(130, 70, 15)).scale, 0.1f)
    }

    @Test
    fun `a gray area cannot be selected`() {
        assertFalse(ColorTracker().select(scene(100, 90, 30), 0.05f, 0.05f))
    }

    @Test
    fun `other hues are ignored and the target is reported lost when it disappears`() {
        val t = ColorTracker()
        t.select(scene(100, 90, 30), 100f / w, 90f / h)
        assertTrue(t.track(scene(100, 90, 30, color = green)).lost)
        assertFalse(t.track(scene(100, 90, 30)).lost) // found again with the whole-frame search
    }

    @Test
    fun `hue wraps around red`() {
        val a = ColorTracker.hsv(0xFFE02010.toInt())[0]
        val b = ColorTracker.hsv(0xFFE01030.toInt())[0]
        assertTrue(a < 20f && b > 340f)
    }
}
