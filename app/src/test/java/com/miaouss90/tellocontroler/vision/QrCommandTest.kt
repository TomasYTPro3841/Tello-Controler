package com.miaouss90.tellocontroler.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCommandTest {
    @Test
    fun `parses only prefixed safe actions`() {
        assertEquals(QrAction.LAND, QrCommand.parse("TELLO:LAND"))
        assertEquals(QrAction.PHOTO, QrCommand.parse("  tello:photo "))
        assertNull(QrCommand.parse("TAKEOFF"))
        assertNull(QrCommand.parse("TELLO:TAKEOFF"))
        assertNull(QrCommand.parse("https://example.com"))
        assertNull(QrCommand.parse(null))
    }

    @Test
    fun `same action does not retrigger inside the cooldown`() {
        var now = 0L
        val d = QrDebouncer { now }
        assertTrue(d.accept(QrAction.PHOTO))
        now = 1000
        assertFalse(d.accept(QrAction.PHOTO))
        assertTrue(d.accept(QrAction.LAND))
        now = QrCommand.COOLDOWN_MS + 1
        assertTrue(d.accept(QrAction.PHOTO))
    }
}
