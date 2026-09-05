package com.avinash.relaydisplay.data.settings

import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.OperatingMode

/**
 * Every persisted preference, as one immutable snapshot.
 *
 * [loaded] is the flag that stops the role chooser from flashing on launch: until the first
 * read of the store completes, the UI shows nothing rather than showing defaults.
 */
data class RelaySettings(
    val loaded: Boolean = false,
    val role: DeviceRole? = null,
    val operatingMode: OperatingMode = OperatingMode.DEFAULT,
    val localDeviceName: String = "",
    val localDeviceId: String = "",

    // presentation
    val keepScreenAwake: Boolean = true,
    val immersiveByDefault: Boolean = true,
    val defaultFitMode: FitMode = FitMode.DEFAULT,
    /** 0..100, or [BRIGHTNESS_SYSTEM_DEFAULT] to leave the system brightness alone. */
    val windowBrightness: Int = BRIGHTNESS_SYSTEM_DEFAULT,

    // behaviour
    val reconnectAutomatically: Boolean = true,
    /** Blank the display the moment the controller disconnects, instead of retaining content. */
    val blankOnDisconnect: Boolean = false,
    /** How long received content stays on screen after a disconnect, when not blanking. */
    val retainContentSeconds: Int = 300,
    /** Opening a received link in another app always needs a local tap unless the user opts in. */
    val allowTrustedLinkOpen: Boolean = false,

    // onboarding
    val onboardingSeen: Boolean = false,
    val notificationRationaleSeen: Boolean = false,
    val localNetworkRationaleSeen: Boolean = false,

    /** Set when the store could not be read. Defaults above stay in force meanwhile. */
    val readError: String? = null,
) {
    val roleChosen: Boolean get() = role != null
}
