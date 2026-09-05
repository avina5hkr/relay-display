package com.avinash.relaydisplay.platform

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The one place that asks Android what this device can currently do.
 *
 * Screens and view models call this; they never touch `Build.VERSION.SDK_INT`, `checkSelfPermission`
 * or `ConnectivityManager` directly. Keeping the API-37 calls behind version guards in a single
 * class is also what stops class verification from tripping on the API 23 phone.
 */
class AndroidPlatformCapabilities(private val context: Context) {

    val sdkInt: Int get() = Build.VERSION.SDK_INT

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun grantedAmong(permissions: Collection<String>): Set<String> =
        permissions.filterTo(mutableSetOf()) { isGranted(it) }

    /** Evaluate a purpose against what the OS has actually granted right now. */
    fun evaluate(purpose: RelayPurpose): CapabilityVerdict {
        val needed = PermissionPolicy.runtimeNeeds(purpose, sdkInt).map { it.permission }
        return PermissionPolicy.evaluate(purpose, sdkInt, grantedAmong(needed))
    }

    /** Runtime permissions still worth asking for, in the order the UI should request them. */
    fun pendingRequests(purpose: RelayPurpose): List<String> =
        PermissionPolicy.runtimeNeeds(purpose, sdkInt)
            .map { it.permission }
            .filterNot { isGranted(it) }

    private val connectivityManager: ConnectivityManager?
        get() = ContextCompat.getSystemService(context, ConnectivityManager::class.java)

    /** A short, non-identifying description of the active transport, for the dashboard. */
    fun activeTransportLabel(): String {
        val cm = connectivityManager ?: return "unknown"
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Other"
        }
    }

    fun hasLocalNetworkCapableTransport(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // A hotspot's own device often reports no active network at all, so a running local
        // interface counts too; localIpv4Address() covers that case.
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun hasCamera(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    companion object {
        /**
         * This device's IPv4 address on a local interface, or null.
         *
         * Reads network interfaces directly rather than going through WifiManager, because it
         * also works when this phone *is* the hotspot, and it needs no permission on any level.
         */
        fun localIpv4Address(): String? = try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: java.net.SocketException) {
            null
        }

        /** "192.168.43.7" -> "192.168.43.". Used only to judge whether a saved endpoint is worth trying. */
        fun subnetOf(address: String?): String? {
            if (address.isNullOrEmpty()) return null
            val lastDot = address.lastIndexOf('.')
            return if (lastDot > 0) address.substring(0, lastDot + 1) else null
        }
    }
}
