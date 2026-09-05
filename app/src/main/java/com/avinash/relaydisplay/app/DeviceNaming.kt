package com.avinash.relaydisplay.app

import android.os.Build

/** Friendly defaults for the local device name, with no personal data in them. */
object DeviceNaming {

    /** "Galaxy S22 Ultra", "K33a42" -- the model, tidied. Never the user's account or phone name. */
    fun defaultName(): String {
        val model = Build.MODEL.orEmpty().trim()
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val combined = when {
            model.isEmpty() && manufacturer.isEmpty() -> "Android device"
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return sanitize(combined)
    }

    /**
     * Strips anything that would confuse an mDNS instance name or a UI label, and bounds length.
     * Also the reason a name can never smuggle control characters into a notification.
     */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(Regex(" +"), " ")
            .trim()
        return if (cleaned.isEmpty()) "Android device" else cleaned.take(MAX_LENGTH)
    }

    const val MAX_LENGTH = 32
}
