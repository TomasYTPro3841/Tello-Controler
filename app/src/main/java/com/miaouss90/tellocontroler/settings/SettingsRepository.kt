package com.miaouss90.tellocontroler.settings

import android.content.Context
import com.miaouss90.tellocontroler.vision.TrackerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists [FlightSettings] in SharedPreferences. */
class SettingsRepository(context: Context) {
    private companion object {
        const val FILE = "flight_settings"
        const val KEY_RATE = "rate"
        const val KEY_DEAD_ZONE = "dead_zone"
        const val KEY_MIN_BATTERY = "min_takeoff_battery"
        const val KEY_TOUCH_STICKS = "touch_sticks"
        const val KEY_HUD_HORIZON = "hud_horizon"
        const val KEY_HUD_HEADING = "hud_heading"
        const val KEY_HUD_RETICLE = "hud_reticle"
        const val KEY_RUMBLE_ALERTS = "rumble_alerts"
        const val KEY_FLIGHT_LOGS = "flight_logs"
        const val KEY_MISSION_PADS = "mission_pads"
        const val KEY_MODE = "flight_mode"
        const val KEY_EXPO = "expo"
        const val KEY_MAX_HEIGHT = "max_height_cm"
        const val KEY_TRACKER = "tracker"
        const val KEY_ORBIT_SPEED = "orbit_speed"
        const val KEY_ORBIT_CLOCKWISE = "orbit_clockwise"
        const val KEY_QR_ACTIONS = "qr_actions"
    }

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FlightSettings> = _settings.asStateFlow()

    fun update(transform: (FlightSettings) -> FlightSettings) {
        val next = transform(_settings.value).sanitized()
        _settings.value = next
        prefs.edit()
            .putString(KEY_RATE, next.rate.name)
            .putFloat(KEY_DEAD_ZONE, next.deadZone)
            .putInt(KEY_MIN_BATTERY, next.minTakeoffBatteryPercent)
            .putBoolean(KEY_TOUCH_STICKS, next.touchSticks)
            .putBoolean(KEY_HUD_HORIZON, next.hudHorizon)
            .putBoolean(KEY_HUD_HEADING, next.hudHeading)
            .putBoolean(KEY_HUD_RETICLE, next.hudReticle)
            .putBoolean(KEY_RUMBLE_ALERTS, next.rumbleAlerts)
            .putBoolean(KEY_FLIGHT_LOGS, next.flightLogs)
            .putBoolean(KEY_MISSION_PADS, next.missionPads)
            .putString(KEY_MODE, next.mode.name)
            .putFloat(KEY_EXPO, next.expo)
            .putInt(KEY_MAX_HEIGHT, next.maxHeightCm)
            .putString(KEY_TRACKER, next.tracker.name)
            .putInt(KEY_ORBIT_SPEED, next.orbitSpeed)
            .putBoolean(KEY_ORBIT_CLOCKWISE, next.orbitClockwise)
            .putBoolean(KEY_QR_ACTIONS, next.qrActions)
            .apply()
    }

    private fun load(): FlightSettings {
        val defaults = FlightSettings()
        return FlightSettings(
            rate = RateProfile.entries.firstOrNull { it.name == prefs.getString(KEY_RATE, null) } ?: defaults.rate,
            deadZone = prefs.getFloat(KEY_DEAD_ZONE, defaults.deadZone),
            minTakeoffBatteryPercent = prefs.getInt(KEY_MIN_BATTERY, defaults.minTakeoffBatteryPercent),
            touchSticks = prefs.getBoolean(KEY_TOUCH_STICKS, defaults.touchSticks),
            hudHorizon = prefs.getBoolean(KEY_HUD_HORIZON, defaults.hudHorizon),
            hudHeading = prefs.getBoolean(KEY_HUD_HEADING, defaults.hudHeading),
            hudReticle = prefs.getBoolean(KEY_HUD_RETICLE, defaults.hudReticle),
            rumbleAlerts = prefs.getBoolean(KEY_RUMBLE_ALERTS, defaults.rumbleAlerts),
            flightLogs = prefs.getBoolean(KEY_FLIGHT_LOGS, defaults.flightLogs),
            missionPads = prefs.getBoolean(KEY_MISSION_PADS, defaults.missionPads),
            mode = FlightMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: defaults.mode,
            expo = prefs.getFloat(KEY_EXPO, defaults.expo),
            maxHeightCm = prefs.getInt(KEY_MAX_HEIGHT, defaults.maxHeightCm),
            tracker = TrackerKind.entries.firstOrNull { it.name == prefs.getString(KEY_TRACKER, null) } ?: defaults.tracker,
            orbitSpeed = prefs.getInt(KEY_ORBIT_SPEED, defaults.orbitSpeed),
            orbitClockwise = prefs.getBoolean(KEY_ORBIT_CLOCKWISE, defaults.orbitClockwise),
            qrActions = prefs.getBoolean(KEY_QR_ACTIONS, defaults.qrActions),
        ).sanitized()
    }
}
