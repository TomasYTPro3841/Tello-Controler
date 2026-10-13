package com.miaouss90.tellocontroler.vision

/** What a QR code may trigger. Only actions that cannot move the aircraft into danger: no takeoff, no motion. */
enum class QrAction { LAND, HOVER, PHOTO, RECORD }

object QrCommand {
    const val PREFIX = "TELLO:"
    /** The same code in front of the camera must not retrigger an action. */
    const val COOLDOWN_MS = 3000L

    /** "TELLO:LAND" → LAND. Anything else (other QR codes in the scene) is ignored. */
    fun parse(text: String?): QrAction? {
        val name = text?.trim()?.takeIf { it.startsWith(PREFIX, ignoreCase = true) }?.substring(PREFIX.length)?.trim()
        return QrAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Pure per-action cooldown so a code held in view fires once. */
class QrDebouncer(private val clock: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<QrAction, Long>()

    fun accept(action: QrAction): Boolean {
        val now = clock()
        val previous = last[action]
        if (previous != null && now - previous < QrCommand.COOLDOWN_MS) return false
        last[action] = now
        return true
    }
}
