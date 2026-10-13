package com.miaouss90.tellocontroler.vision

/** Single-target tracker used by FOLLOW/ORBIT. Not thread-safe: vision thread only. */
interface TargetTracker {
    val hasTarget: Boolean

    /** Selects what is at (nx, ny) normalized; false when nothing trackable is there. */
    fun select(frame: GrayFrame, nx: Float, ny: Float): Boolean

    fun track(frame: GrayFrame): TrackResult

    fun clear()
}

/** Which tracker FOLLOW/ORBIT use. */
enum class TrackerKind { TEMPLATE, COLOR, FACE }
