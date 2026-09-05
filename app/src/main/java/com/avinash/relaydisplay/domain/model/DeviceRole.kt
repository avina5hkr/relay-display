package com.avinash.relaydisplay.domain.model

/** The persistent role this installation plays. Chosen once; changed only from Settings. */
enum class DeviceRole(val storageValue: String, val wireCode: Int) {
    CONTROLLER("CONTROLLER", 1),
    DISPLAY("DISPLAY", 2),
    ;

    val other: DeviceRole get() = if (this == CONTROLLER) DISPLAY else CONTROLLER

    companion object {
        /** Unknown/corrupt stored values fall back to "not chosen yet" rather than crashing. */
        fun fromStorage(value: String?): DeviceRole? = entries.firstOrNull { it.storageValue == value }

        fun fromWire(code: Int): DeviceRole? = entries.firstOrNull { it.wireCode == code }
    }
}

/**
 * How hard the app works to stay reachable. Independent of [DeviceRole] and equally persistent.
 */
enum class OperatingMode(val storageValue: String) {
    /** Default. Nothing runs until the user taps Connect / Make available. */
    ON_DEMAND("ON_DEMAND"),

    /** Best effort persistent availability behind a visible foreground service. */
    ALWAYS_READY("ALWAYS_READY"),

    /** Everything stopped. No sockets, no discovery, no reconnect, no capture. */
    PAUSED("PAUSED"),
    ;

    companion object {
        val DEFAULT = ON_DEMAND

        fun fromStorage(value: String?): OperatingMode =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/** How received media is scaled on the display. */
enum class FitMode(val storageValue: String, val wireCode: Int) {
    FIT("FIT", 1),
    FILL("FILL", 2),
    ;

    companion object {
        val DEFAULT = FIT
        fun fromStorage(value: String?): FitMode = entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        fun fromWire(code: Int): FitMode? = entries.firstOrNull { it.wireCode == code }
    }
}

/** Presentation rotation applied by the display, in degrees. */
enum class PresentationRotation(val degrees: Int, val wireCode: Int) {
    DEG_0(0, 0),
    DEG_90(90, 1),
    DEG_180(180, 2),
    DEG_270(270, 3),
    ;

    companion object {
        fun fromWire(code: Int): PresentationRotation? = entries.firstOrNull { it.wireCode == code }
        fun fromDegrees(deg: Int): PresentationRotation = entries.firstOrNull { it.degrees == deg } ?: DEG_0
    }
}

/** What kind of thing a transfer carries. Used to pick a renderer and to validate MIME. */
enum class ContentKind(val wireCode: Int) {
    IMAGE(1),
    PDF(2),
    ;

    companion object {
        fun fromWire(code: Int): ContentKind? = entries.firstOrNull { it.wireCode == code }
    }
}
