package com.miaouss90.tellocontroler.settings

import com.miaouss90.tellocontroler.controller.StickMapper
import com.miaouss90.tellocontroler.vision.OrbitController
import com.miaouss90.tellocontroler.vision.TrackerKind

/** Output scale applied to every RC channel. */
enum class RateProfile(val scale: Float) {
    SLOW(0.35f),
    NORMAL(0.65f),
    SPORT(1f),
    ;

    fun next(): RateProfile = entries[(ordinal + 1) % entries.size]
}

data class FlightSettings(
    val rate: RateProfile = RateProfile.NORMAL,
    val deadZone: Float = StickMapper.DEFAULT_DEAD_ZONE,
    val minTakeoffBatteryPercent: Int = 20,
    val touchSticks: Boolean = false,
    val hudHorizon: Boolean = true,
    val hudHeading: Boolean = true,
    val hudReticle: Boolean = true,
    val rumbleAlerts: Boolean = true,
    val flightLogs: Boolean = true,
    val missionPads: Boolean = false,
    val mode: FlightMode = FlightMode.STANDARD,
    /** 0 = linear, higher = finer control around the stick center. */
    val expo: Float = 0f,
    /** Height limit in cm, 0 = off. */
    val maxHeightCm: Int = 0,
    /** What FOLLOW / ORBIT lock onto: a textured patch, a color blob or a face. */
    val tracker: TrackerKind = TrackerKind.TEMPLATE,
    val orbitSpeed: Int = OrbitController.DEFAULT_SPEED,
    val orbitClockwise: Boolean = true,
    /** QR codes "TELLO:LAND|HOVER|PHOTO|RECORD" in front of the camera trigger that action. */
    val qrActions: Boolean = false,
) {
    companion object {
        val DEAD_ZONE_RANGE = 0.02f..0.25f
        val MIN_BATTERY_RANGE = 10..50
        val EXPO_RANGE = 0f..0.8f
        val MAX_HEIGHT_RANGE = 0..800
        val ORBIT_SPEED_RANGE = 5..OrbitController.MAX_SPEED
    }

    fun sanitized() = copy(
        deadZone = deadZone.coerceIn(DEAD_ZONE_RANGE),
        minTakeoffBatteryPercent = minTakeoffBatteryPercent.coerceIn(MIN_BATTERY_RANGE),
        expo = expo.coerceIn(EXPO_RANGE),
        maxHeightCm = maxHeightCm.coerceIn(MAX_HEIGHT_RANGE),
        orbitSpeed = orbitSpeed.coerceIn(ORBIT_SPEED_RANGE),
    )
}
