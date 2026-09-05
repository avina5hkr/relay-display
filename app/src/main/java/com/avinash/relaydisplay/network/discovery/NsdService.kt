package com.avinash.relaydisplay.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.network.session.Endpoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** DNS-SD constants. The trailing dot matters: NsdManager wants a fully qualified type. */
object NsdConstants {
    const val SERVICE_TYPE = "_relaydisplay._tcp."

    /** Advertised protocol major version, so a future incompatible build is visible before dialling. */
    const val TXT_VERSION = "v"

    const val MAX_SERVICE_NAME = 40
}

/** A resolved peer we could try to connect to. */
data class DiscoveredDisplay(
    val serviceName: String,
    val endpoint: Endpoint,
    val protocolMajor: Int,
)

/**
 * Holds a multicast lock only while mDNS is actually running.
 *
 * Some OEM builds drop multicast packets to the app unless this is held, and others do not need
 * it at all. It is cheap while held and released on every exit path, including failures.
 */
internal class MulticastLease(context: Context, private val diagnostics: DiagnosticsLog) {
    private val wifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    fun acquire() {
        if (lock != null) return
        lock = try {
            wifiManager?.createMulticastLock("relaydisplay-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            diagnostics.warn("nsd", "multicast lock refused")
            null
        }
    }

    @Synchronized
    fun release() {
        val held = lock ?: return
        lock = null
        try {
            if (held.isHeld) held.release()
        } catch (e: RuntimeException) {
            // Releasing an already-released lock throws; there is nothing to fix.
            diagnostics.debug("nsd", "multicast lock already released")
        }
    }
}

/**
 * Advertises this device's listener over DNS-SD.
 *
 * Lifecycle rules, learned the hard way from NsdManager:
 *  - `unregisterService` must be called exactly once per successful registration, and calling it
 *    for a registration that failed throws.
 *  - callbacks arrive after a stop, so every one checks a generation counter and ignores work
 *    that belongs to a previous run.
 *  - `onServiceRegistered` may hand back a *different* name than requested when the name was
 *    taken; that renamed instance is the one that must later be unregistered.
 */
class NsdAdvertiser(
    context: Context,
    private val diagnostics: DiagnosticsLog,
) {
    private val nsdManager = ContextCompat.getSystemService(context, NsdManager::class.java)
    private val multicast = MulticastLease(context, diagnostics)
    private val generation = AtomicInteger(0)

    private var listener: NsdManager.RegistrationListener? = null
    private val registered = AtomicBoolean(false)

    /** Name the system actually gave us, which may differ from the requested one. */
    @Volatile
    var registeredName: String? = null
        private set

    @Synchronized
    fun start(serviceName: String, port: Int, protocolMajor: Int, onError: (String) -> Unit) {
        val manager = nsdManager ?: run {
            onError("This device has no network service discovery")
            return
        }
        stop()
        val myGeneration = generation.incrementAndGet()
        multicast.acquire()

        val info = NsdServiceInfo().apply {
            this.serviceName = sanitizeServiceName(serviceName)
            this.serviceType = NsdConstants.SERVICE_TYPE
            this.port = port
            // Nothing identifying goes in the TXT record: see docs/SECURITY.md. Only the
            // protocol major version, so an incompatible build is visible before dialling.
            setAttribute(NsdConstants.TXT_VERSION, protocolMajor.toString())
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (generation.get() != myGeneration) return
                registered.set(true)
                registeredName = info.serviceName
                diagnostics.info("nsd", "advertising on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (generation.get() != myGeneration) return
                registered.set(false)
                diagnostics.warn("nsd", "registration failed ($errorCode)")
                multicast.release()
                onError(describeNsdError(errorCode))
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registered.set(false)
                diagnostics.debug("nsd", "advertisement withdrawn")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
                diagnostics.warn("nsd", "unregistration failed ($errorCode)")
            }
        }
        listener = registrationListener

        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: IllegalArgumentException) {
            listener = null
            multicast.release()
            onError("Could not advertise on this network")
        }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        val current = listener
        listener = null
        registeredName = null
        if (current != null) {
            try {
                nsdManager?.unregisterService(current)
            } catch (e: IllegalArgumentException) {
                // Registration never completed. Nothing is advertised, so nothing to undo.
            }
        }
        registered.set(false)
        multicast.release()
    }
}

/**
 * Browses for companion displays.
 *
 * Resolution is serialised through a single-slot queue because older platform versions fail a
 * concurrent `resolveService` with FAILURE_ALREADY_ACTIVE, and because resolving every service
 * on a busy network at once is wasteful on the phone doing the browsing.
 */
class NsdBrowser(
    context: Context,
    private val diagnostics: DiagnosticsLog,
) {
    private val nsdManager = ContextCompat.getSystemService(context, NsdManager::class.java)
    private val multicast = MulticastLease(context, diagnostics)
    private val generation = AtomicInteger(0)

    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start(
        excludeServiceName: String?,
        onFound: (DiscoveredDisplay) -> Unit,
        onLost: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val manager = nsdManager ?: run {
            onError("This device has no network service discovery")
            return
        }
        stop()
        val myGeneration = generation.incrementAndGet()
        multicast.acquire()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                diagnostics.debug("nsd", "discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (generation.get() != myGeneration) return
                // Our own advertisement comes back to us; skip it rather than dialling ourselves.
                if (excludeServiceName != null && info.serviceName == excludeServiceName) return
                enqueueResolve(manager, info, myGeneration, onFound)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (generation.get() != myGeneration) return
                onLost(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                diagnostics.debug("nsd", "discovery stopped")
                multicast.release()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (generation.get() != myGeneration) return
                diagnostics.warn("nsd", "discovery start failed ($errorCode)")
                multicast.release()
                onError(describeNsdError(errorCode))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                diagnostics.warn("nsd", "discovery stop failed ($errorCode)")
                multicast.release()
            }
        }
        listener = discoveryListener

        try {
            manager.discoverServices(
                NsdConstants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        } catch (e: IllegalArgumentException) {
            listener = null
            multicast.release()
            onError("Could not search this network")
        }
    }

    @Synchronized
    private fun enqueueResolve(
        manager: NsdManager,
        info: NsdServiceInfo,
        myGeneration: Int,
        onFound: (DiscoveredDisplay) -> Unit,
    ) {
        if (pending.size >= MAX_PENDING_RESOLVES) {
            // A network with hundreds of advertisements must not turn into hundreds of resolves.
            diagnostics.warn("nsd", "resolve queue full; ignoring further services")
            return
        }
        pending.addLast(info)
        pumpResolves(manager, myGeneration, onFound)
    }

    @Synchronized
    private fun pumpResolves(
        manager: NsdManager,
        myGeneration: Int,
        onFound: (DiscoveredDisplay) -> Unit,
    ) {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                diagnostics.debug("nsd", "resolve failed ($errorCode)")
                finishResolve(manager, myGeneration, onFound)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (generation.get() == myGeneration) {
                    hostAddressOf(info)?.let { host ->
                        val version = info.attributes[NsdConstants.TXT_VERSION]
                            ?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0
                        onFound(DiscoveredDisplay(info.serviceName, Endpoint(host, info.port), version))
                    }
                }
                finishResolve(manager, myGeneration, onFound)
            }
        }

        try {
            @Suppress("DEPRECATION")
            manager.resolveService(next, resolveListener)
        } catch (e: IllegalArgumentException) {
            finishResolve(manager, myGeneration, onFound)
        }
    }

    @Synchronized
    private fun finishResolve(manager: NsdManager, myGeneration: Int, onFound: (DiscoveredDisplay) -> Unit) {
        resolving = false
        if (generation.get() != myGeneration) {
            pending.clear()
            return
        }
        pumpResolves(manager, myGeneration, onFound)
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        pending.clear()
        resolving = false
        val current = listener
        listener = null
        if (current != null) {
            try {
                nsdManager?.stopServiceDiscovery(current)
            } catch (e: IllegalArgumentException) {
                // Discovery never started.
            }
        }
        multicast.release()
    }

    private companion object {
        const val MAX_PENDING_RESOLVES = 16
    }
}

/**
 * The resolved IPv4 address, or null.
 *
 * `getHost` is deprecated from API 34 in favour of `getHostAddresses`; both are read behind a
 * version guard so the API 23 phone never touches the newer symbol.
 */
private fun hostAddressOf(info: NsdServiceInfo): String? {
    val addresses = if (Build.VERSION.SDK_INT >= 34) {
        info.hostAddresses
    } else {
        @Suppress("DEPRECATION")
        listOfNotNull(info.host)
    }
    return addresses.filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
        ?: addresses.firstOrNull()?.hostAddress
}

/** A sanitised, length-bounded DNS-SD instance name. */
internal fun sanitizeServiceName(raw: String): String {
    val cleaned = raw.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .replace(Regex(" +"), " ")
        .trim()
    return (cleaned.ifEmpty { "RelayDisplay" }).take(NsdConstants.MAX_SERVICE_NAME)
}

internal fun describeNsdError(errorCode: Int): String = when (errorCode) {
    NsdManager.FAILURE_ALREADY_ACTIVE -> "Discovery is already running"
    NsdManager.FAILURE_MAX_LIMIT -> "Too many discovery requests on this device"
    NsdManager.FAILURE_BAD_PARAMETERS -> "This network rejected the request"
    else -> "Network discovery is unavailable on this network"
}
