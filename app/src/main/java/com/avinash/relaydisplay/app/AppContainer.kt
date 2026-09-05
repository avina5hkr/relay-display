package com.avinash.relaydisplay.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.avinash.relaydisplay.data.peers.TrustedPeerRepository
import com.avinash.relaydisplay.content.ContentCache
import com.avinash.relaydisplay.content.ContentRouter
import com.avinash.relaydisplay.content.PdfPageSource
import com.avinash.relaydisplay.content.PresentationController
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.mirroring.MirrorController
import com.avinash.relaydisplay.network.discovery.NsdAdvertiser
import com.avinash.relaydisplay.network.discovery.NsdBrowser
import com.avinash.relaydisplay.network.session.RelayEngine
import com.avinash.relaydisplay.network.session.SessionCoordinator
import com.avinash.relaydisplay.network.transport.TcpDialer
import com.avinash.relaydisplay.network.transport.TcpListener
import com.avinash.relaydisplay.platform.AndroidPlatformCapabilities
import com.avinash.relaydisplay.platform.NetworkMonitor
import com.avinash.relaydisplay.service.RelayConnectionService
import com.avinash.relaydisplay.security.DeviceIdentity
import com.avinash.relaydisplay.security.KeystoreDeviceIdentity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.network.session.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.relayDataStore: DataStore<Preferences> by preferencesDataStore(name = "relay_settings")

/**
 * The application-scoped dependency container.
 *
 * A plain object graph rather than a DI framework: the graph is a dozen objects with obvious
 * lifetimes, and a framework would add build complexity without removing any real problem.
 *
 * Nothing here holds an Activity. The only Context stored is the application context.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Lives as long as the process. Used for work that must outlive any one screen. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagnostics: DiagnosticsLog = DiagnosticsLog()

    val settingsRepository: SettingsRepository = SettingsRepository(appContext.relayDataStore)

    val trustedPeerRepository: TrustedPeerRepository = TrustedPeerRepository(appContext.relayDataStore)

    val platformCapabilities: AndroidPlatformCapabilities = AndroidPlatformCapabilities(appContext)

    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext, diagnostics)

    /** The single owner of connection lifecycle for the process. */
    val sessionCoordinator: SessionCoordinator = SessionCoordinator(
        settingsRepository = settingsRepository,
        diagnostics = diagnostics,
        scope = applicationScope,
    )

    /** Null when the keystore is unusable; the UI then blocks pairing and explains why. */
    val identity: DeviceIdentity?
        get() = when (val result = KeystoreDeviceIdentity.Provider.load()) {
            is KeystoreDeviceIdentity.Result.Available -> result.identity
            is KeystoreDeviceIdentity.Result.Unavailable -> {
                diagnostics.record(
                    DiagnosticsLog.Level.ERROR,
                    "identity",
                    "device identity unavailable: ${result.reason}",
                )
                null
            }
        }

    /** The networking half of a session. Attached to the coordinator once, below. */
    val engine: RelayEngine = RelayEngine(
        settingsRepository = settingsRepository,
        trustedPeerRepository = trustedPeerRepository,
        coordinator = sessionCoordinator,
        identityProvider = { identity },
        advertiser = NsdAdvertiser(appContext, diagnostics),
        browser = NsdBrowser(appContext, diagnostics),
        dialer = TcpDialer(),
        // Port 0: the OS picks a free port and we advertise the one it gave us, so two installs
        // on one network never fight over a fixed number.
        listenerFactory = { TcpListener(requestedPort = 0) },
        platform = platformCapabilities,
        diagnostics = diagnostics,
        scope = applicationScope,
    )

    /**
     * Bounded cache for received images and documents.
     *
     * Lazy because `Context.getCacheDir()` itself touches the filesystem, and this container is
     * built on the main thread in Application.onCreate.
     */
    val contentCache: ContentCache by lazy { ContentCache.openIn(appContext) }

    /** What the companion display is showing. Application-scoped so a rotation does not clear it. */
    val presentationController: PresentationController = PresentationController()

    /** Screen capture on the controller, and video decode on the display. */
    val mirrorController: MirrorController = MirrorController(
        engine = engine,
        diagnostics = diagnostics,
        scope = applicationScope,
    )

    val contentRouter: ContentRouter = ContentRouter(
        engine = engine,
        presentation = presentationController,
        settingsRepository = settingsRepository,
        cacheProvider = { contentCache },
        diagnostics = diagnostics,
        scope = applicationScope,
        pdfPageCounter = { file -> PdfPageSource.pageCount(file) },
        mirrorController = mirrorController,
    )

    /** Turns a role change into one serialized, bounded transition. */
    val roleSwitchCoordinator: RoleSwitchCoordinator = RoleSwitchCoordinator(
        settingsRepository = settingsRepository,
        sessionCoordinator = sessionCoordinator,
        mirrorController = mirrorController,
        contentRouter = contentRouter,
        presentationController = presentationController,
        diagnostics = diagnostics,
        stopForegroundService = { RelayConnectionService.stop(appContext) },
    )

    init {
        sessionCoordinator.attachEngine(engine)
        keepForegroundServiceInStep()
        reactToNetworkChanges()
        applyDisconnectPolicy()

        // Give this installation a stable id and a friendly name the first time it runs.
        applicationScope.launch {
            settingsRepository.ensureLocalDeviceId { UUID.randomUUID().toString() }
            val loaded = settingsRepository.settings.first { it.loaded }
            if (loaded.localDeviceName.isEmpty()) {
                settingsRepository.setLocalDeviceName(DeviceNaming.defaultName())
            }
        }
    }

    /**
     * Starts the foreground service exactly while a session is wanted, and stops it otherwise.
     *
     * The service is what lets a session survive the app leaving the foreground. Driving it from
     * the coordinator's own intent flag, rather than from a screen, is what keeps the ongoing
     * notification from outliving the thing it describes.
     */
    private fun keepForegroundServiceInStep() {
        applicationScope.launch {
            combine(sessionCoordinator.sessionIntended, settingsRepository.settings) { intended, settings ->
                intended && settings.loaded && settings.operatingMode != OperatingMode.PAUSED
            }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    try {
                        if (shouldRun) {
                            RelayConnectionService.start(appContext)
                        } else {
                            RelayConnectionService.stop(appContext)
                        }
                    } catch (e: IllegalStateException) {
                        // Android refuses a foreground start from the background. The session
                        // still runs while the app is visible; say so rather than pretending.
                        diagnostics.warn("service", "could not start in the background")
                    }
                }
        }
    }

    /**
     * Drops a session whose network went away.
     *
     * Without this a socket on a vanished Wi-Fi sits there until a read finally times out, which
     * on some devices is minutes. Closing it immediately hands control to the reconnect ladder.
     */
    private fun reactToNetworkChanges() {
        applicationScope.launch {
            var previousId: String? = null
            networkMonitor.status().collect { status ->
                val changed = status.networkId != previousId
                previousId = status.networkId
                if (!status.available || changed) {
                    val session = engine.activeSession.value
                    if (session != null) {
                        diagnostics.info("network", "closing the session after a network change")
                        session.close()
                    }
                }
            }
        }
    }

    /**
     * Decides what the display does when the controller goes away.
     *
     * Two honest options, both the user's choice: blank straight away for privacy, or keep the
     * content for a bounded time with a quiet "disconnected" hint so a QR code on a wall does
     * not vanish the instant a phone is pocketed. Either way it is bounded -- content never
     * stays up indefinitely after a session ends.
     */
    private fun applyDisconnectPolicy() {
        applicationScope.launch {
            var retentionJob: Job? = null
            sessionCoordinator.state.collect { state ->
                val settings = settingsRepository.settings.first { it.loaded }
                if (settings.role != DeviceRole.DISPLAY) return@collect

                if (state.isConnected) {
                    retentionJob?.cancel()
                    retentionJob = null
                    presentationController.setDisconnectedOverlay(false)
                    return@collect
                }

                // Only react to an actual end of session, not to the states before one starts.
                if (state is ConnectionState.Connecting || state is ConnectionState.Authenticating) return@collect

                if (settings.blankOnDisconnect) {
                    retentionJob?.cancel()
                    retentionJob = null
                    contentRouter.dismissLocally()
                } else if (retentionJob == null) {
                    presentationController.setDisconnectedOverlay(true)
                    retentionJob = applicationScope.launch {
                        delay(settings.retainContentSeconds * 1000L)
                        contentRouter.dismissLocally()
                    }
                }
            }
        }
    }
}
