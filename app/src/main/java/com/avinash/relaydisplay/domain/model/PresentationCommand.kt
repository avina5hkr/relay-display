package com.avinash.relaydisplay.domain.model

/**
 * Commands that change how the display presents, without sending new content.
 *
 * Every one of these is idempotent: replaying a command after a reconnect produces the same
 * end state, which is what lets the transport retry safely.
 */
enum class PresentCommandType(val wireCode: Int) {
    /** Black screen, content retained in memory. */
    BLANK(1),

    /** Drop current content and return to the waiting screen. */
    SHOW_WAITING(2),

    /** Argument: [FitMode.wireCode]. */
    SET_FIT_MODE(3),

    /** Argument: 0..100 in-app window brightness, or -1 to follow the system. */
    SET_BRIGHTNESS(4),

    /** Argument: 1 = enter immersive, 0 = leave immersive. */
    SET_IMMERSIVE(5),

    /** Argument: [PresentationRotation.wireCode]. */
    SET_ROTATION(6),

    /** Argument: 1 = keep the screen awake while presenting, 0 = allow normal timeout. */
    SET_KEEP_AWAKE(7),
    ;

    companion object {
        fun fromWire(code: Int): PresentCommandType? = entries.firstOrNull { it.wireCode == code }
    }
}

/** Brightness sentinel meaning "do not override the system brightness". */
const val BRIGHTNESS_SYSTEM_DEFAULT: Int = -1
