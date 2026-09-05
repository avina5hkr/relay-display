package com.avinash.relaydisplay.platform

/**
 * What the app wants to do. Permissions are decided per purpose and asked for at the moment
 * the purpose is triggered, never up front.
 */
enum class RelayPurpose {
    /** Opening sockets, listening, and running mDNS discovery on the local network. */
    LocalNetwork,

    /** Running the connected-device foreground service with a visible ongoing notification. */
    ForegroundAvailability,

    /** Scanning a pairing QR with the in-app camera. */
    QrScanning,

    /** Capturing this device's screen and sending it to the display. */
    ScreenMirroring,
}

/** One permission the app may need, and how badly. */
data class PermissionNeed(
    val permission: String,
    /**
     * Blocking needs stop the purpose entirely when denied. Non-blocking ones degrade it: a
     * denied POST_NOTIFICATIONS still leaves a working foreground service, just a silent one.
     */
    val blocking: Boolean,
)

/**
 * The single place that maps an Android API level to the runtime permissions a purpose needs.
 *
 * Every SDK check for permissions lives here. Screens ask this object; they never test
 * `Build.VERSION.SDK_INT` themselves, which is what keeps API-37-only behaviour from leaking
 * onto an API 23 device.
 */
object PermissionPolicy {

    const val INTERNET = "android.permission.INTERNET"
    const val ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
    const val ACCESS_WIFI_STATE = "android.permission.ACCESS_WIFI_STATE"
    const val CHANGE_WIFI_MULTICAST_STATE = "android.permission.CHANGE_WIFI_MULTICAST_STATE"
    const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
    const val FOREGROUND_SERVICE_CONNECTED_DEVICE = "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
    const val FOREGROUND_SERVICE_MEDIA_PROJECTION = "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val CAMERA = "android.permission.CAMERA"

    /**
     * Android 17 gates local network access behind a runtime permission. It does not exist on
     * earlier releases, so requesting it there would fail permanently.
     */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    const val SDK_NOTIFICATIONS_RUNTIME = 33
    const val SDK_TYPED_FOREGROUND_SERVICE = 29
    const val SDK_FGS_TYPE_PERMISSION = 34
    const val SDK_LOCAL_NETWORK_RUNTIME = 37

    /** Runtime permissions to request for [purpose] on a device running [sdkInt]. */
    fun runtimeNeeds(purpose: RelayPurpose, sdkInt: Int): List<PermissionNeed> = when (purpose) {
        RelayPurpose.LocalNetwork ->
            if (sdkInt >= SDK_LOCAL_NETWORK_RUNTIME) {
                listOf(PermissionNeed(ACCESS_LOCAL_NETWORK, blocking = true))
            } else {
                emptyList()
            }

        RelayPurpose.ForegroundAvailability ->
            if (sdkInt >= SDK_NOTIFICATIONS_RUNTIME) {
                // A denied notification permission is not fatal: the service still runs, and the
                // UI has to say so rather than pretending availability failed.
                listOf(PermissionNeed(POST_NOTIFICATIONS, blocking = false))
            } else {
                emptyList()
            }

        RelayPurpose.QrScanning -> listOf(PermissionNeed(CAMERA, blocking = true))

        RelayPurpose.ScreenMirroring ->
            if (sdkInt >= SDK_NOTIFICATIONS_RUNTIME) {
                listOf(PermissionNeed(POST_NOTIFICATIONS, blocking = false))
            } else {
                emptyList()
            }
    }

    /** Only the permissions that stop the purpose when denied. */
    fun blockingPermissions(purpose: RelayPurpose, sdkInt: Int): List<String> =
        runtimeNeeds(purpose, sdkInt).filter { it.blocking }.map { it.permission }

    /** True when the platform requires a foreground service to declare a type. */
    fun requiresForegroundServiceType(sdkInt: Int): Boolean = sdkInt >= SDK_TYPED_FOREGROUND_SERVICE

    /** True when the platform requires the matching FOREGROUND_SERVICE_* permission. */
    fun requiresForegroundServiceTypePermission(sdkInt: Int): Boolean = sdkInt >= SDK_FGS_TYPE_PERMISSION

    /** True when a notification channel must exist before posting. */
    fun requiresNotificationChannel(sdkInt: Int): Boolean = sdkInt >= 26

    /**
     * Whether a purpose can proceed given what has actually been granted.
     *
     * [granted] is the set of permission strings the OS reports as granted.
     */
    fun evaluate(purpose: RelayPurpose, sdkInt: Int, granted: Set<String>): CapabilityVerdict {
        val needs = runtimeNeeds(purpose, sdkInt)
        val missingBlocking = needs.filter { it.blocking && it.permission !in granted }.map { it.permission }
        val missingOptional = needs.filter { !it.blocking && it.permission !in granted }.map { it.permission }
        return when {
            missingBlocking.isNotEmpty() -> CapabilityVerdict.Blocked(missingBlocking)
            missingOptional.isNotEmpty() -> CapabilityVerdict.Degraded(missingOptional)
            else -> CapabilityVerdict.Allowed
        }
    }
}

/** The outcome of a capability check. */
sealed interface CapabilityVerdict {
    data object Allowed : CapabilityVerdict

    /** Works, but something is missing that the user should know about. */
    data class Degraded(val missing: List<String>) : CapabilityVerdict

    /** Cannot proceed. The UI shows an actionable blocked state and retries only on user action. */
    data class Blocked(val missing: List<String>) : CapabilityVerdict

    val allowsProceeding: Boolean get() = this !is Blocked
}
