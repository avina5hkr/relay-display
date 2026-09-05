package com.avinash.relaydisplay.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the app needs to know about the network underneath it. */
data class NetworkStatus(
    val available: Boolean,
    /** Changes when the device moves to a different network, so a stale socket can be dropped. */
    val networkId: String?,
)

/**
 * Event-driven connectivity, never polling.
 *
 * A `NetworkCallback` costs nothing while nothing changes, which is the whole point: the
 * companion phone may sit on the waiting screen for hours, and a polling loop there would show
 * up directly in battery use.
 */
class NetworkMonitor(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {
    private val connectivityManager: ConnectivityManager?
        get() = ContextCompat.getSystemService(context, ConnectivityManager::class.java)

    /**
     * Emits whenever a local-network-capable transport appears, disappears or changes identity.
     *
     * The callback is unregistered in `awaitClose`, so collecting from a cancelled scope leaves
     * nothing behind.
     */
    fun status(): Flow<NetworkStatus> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(NetworkStatus(available = false, networkId = null))
            awaitClose { }
            return@callbackFlow
        }

        // Only transports that can actually carry LAN traffic. Cellular is deliberately absent:
        // the two phones can never reach each other over it.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                diagnostics.debug("network", "transport available")
                trySend(NetworkStatus(available = true, networkId = network.toString()))
            }

            override fun onLost(network: Network) {
                diagnostics.debug("network", "transport lost")
                trySend(NetworkStatus(available = false, networkId = null))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(NetworkStatus(available = true, networkId = network.toString()))
            }
        }

        val registered = try {
            manager.registerNetworkCallback(request, callback)
            true
        } catch (e: SecurityException) {
            diagnostics.warn("network", "cannot watch connectivity")
            trySend(NetworkStatus(available = false, networkId = null))
            false
        } catch (e: RuntimeException) {
            // Some OEM builds throw "too many NetworkRequests" here; degrade rather than crash.
            diagnostics.warn("network", "connectivity callback refused")
            trySend(NetworkStatus(available = false, networkId = null))
            false
        }

        awaitClose {
            if (registered) {
                try {
                    manager.unregisterNetworkCallback(callback)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered.
                }
            }
        }
    }.distinctUntilChanged()
}
