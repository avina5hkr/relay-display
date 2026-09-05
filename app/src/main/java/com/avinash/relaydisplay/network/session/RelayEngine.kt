package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.data.peers.TrustedPeerRepository
import com.avinash.relaydisplay.data.settings.RelaySettings
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.TrustedPeer
import com.avinash.relaydisplay.network.discovery.DiscoveredDisplay
import com.avinash.relaydisplay.network.discovery.NsdAdvertiser
import com.avinash.relaydisplay.network.discovery.BrowseLease
import com.avinash.relaydisplay.network.discovery.NsdBrowser
import com.avinash.relaydisplay.network.transport.RelayDialer
import com.avinash.relaydisplay.network.transport.RelayLink
import com.avinash.relaydisplay.network.transport.RelayListener
import com.avinash.relaydisplay.network.transport.SecureConnection
import com.avinash.relaydisplay.platform.AndroidPlatformCapabilities
import com.avinash.relaydisplay.platform.CapabilityVerdict
import com.avinash.relaydisplay.platform.RelayPurpose
import com.avinash.relaydisplay.protocol.Capabilities
import com.avinash.relaydisplay.protocol.ProtocolConstants
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import com.avinash.relaydisplay.protocol.ProtocolTimings
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.protocol.SasConfirm
import com.avinash.relaydisplay.security.DeviceIdentity
import com.avinash.relaydisplay.security.HandshakeConfig
import com.avinash.relaydisplay.security.HandshakeOutcome
import com.avinash.relaydisplay.security.PairingPayload
import com.avinash.relaydisplay.security.PairingUri
import com.avinash.relaydisplay.security.constantTimeEquals
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How the controller should find the display this time round. */
sealed interface ConnectTarget {
    /** Try the endpoint stored from the last successful session, if the network still matches. */
    data object LastKnown : ConnectTarget

    /** A freshly scanned pairing code: dial its endpoint and prove its token. */
    data class Paired(val payload: PairingPayload) : ConnectTarget

    /** Manual host and port from the Advanced section. */
    data class Manual(val endpoint: Endpoint) : ConnectTarget
}

/**
 * The networking half of a session, for both roles.
 *
 * One run loop. It prepares, tries to establish a session, waits for that session to end, and
 * then either stops or backs off and tries again. Everything it opens -- a listener, an
 * advertisement, a socket, a session -- is closed in the `finally` of the same function that
 * opened it, so there is exactly one place to look for each resource.
 *
 * State is published through [SessionCoordinator], never held here for the UI to read.
 */
class RelayEngine(
    private val settingsRepository: SettingsRepository,
    private val trustedPeerRepository: TrustedPeerRepository,
    private val coordinator: SessionCoordinator,
    private val identityProvider: () -> DeviceIdentity?,
    private val advertiser: NsdAdvertiser,
    private val browser: NsdBrowser,
    private val dialer: RelayDialer,
    private val listenerFactory: () -> RelayListener,
    private val platform: AndroidPlatformCapabilities,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SessionEngine, SessionHost {

    private val lifecycleLock = Mutex()
    private var runJob: Job? = null

    private val _activeSession = MutableStateFlow<RelaySession?>(null)
    override val activeSession: StateFlow<RelaySession?> = _activeSession.asStateFlow()

    /**
     * Inbound application messages.
     *
     * A shared flow with a small replay buffer, so a screen that subscribes a moment after a
     * message arrives still sees it, without the engine having to know who is listening.
     */
    private val _messages = MutableSharedFlow<RelayMessage>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    /** The pairing code this display is currently offering, if any. */
    private val _pairingCode = MutableStateFlow<PairingOffer?>(null)
    val pairingCode: StateFlow<PairingOffer?> = _pairingCode.asStateFlow()

    /** The port this display is currently listening on, or null when not listening. */
    private val _listeningPort = MutableStateFlow<Int?>(null)
    val listeningPort: StateFlow<Int?> = _listeningPort.asStateFlow()

    /** Displays seen on this network, for the controller's pairing screen. */
    private val _discovered = MutableStateFlow<List<DiscoveredDisplay>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDisplay>> = _discovered.asStateFlow()

    private val _browseError = MutableStateFlow<String?>(null)

    /** Why the network search could not run, if it could not. Null while it is fine. */
    val browseError: StateFlow<String?> = _browseError.asStateFlow()

    private val browseLease = BrowseLease(onStart = ::beginBrowse, onStop = ::endBrowse)

    /**
     * The bound listener, reachable from outside the coroutine that owns it.
     *
     * `ServerSocket.accept()` is a blocking call that coroutine cancellation cannot interrupt --
     * only closing the socket makes it return. Without this reference, [stop] cancelled the run
     * loop, closed the session, and then waited on `join()` for a coroutine parked in `accept()`
     * that could never resume. On a role change that showed up as "shutdown timed out", the old
     * role's listener left bound, and the engine never restarting under the new role.
     */
    private val activeListener = AtomicReference<RelayListener?>(null)

    @Volatile
    private var target: ConnectTarget = ConnectTarget.LastKnown

    /** Latest loaded settings, so minting a pairing code does not have to suspend. */
    @Volatile
    private var settingsSnapshot: RelaySettings? = null

    @Volatile
    private var sasDecision: CompletableDeferred<Boolean>? = null

    /**
     * Failed authentication attempts since the last success, per remote address.
     *
     * Used to slow down a device on the LAN that keeps trying: after a few failures its next
     * attempt is simply dropped for a while. Bounded so the map itself cannot be a memory
     * exhaustion vector.
     */
    private val failedAttempts = object : LinkedHashMap<String, FailureRecord>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FailureRecord>?): Boolean =
            size > MAX_TRACKED_CLIENTS
    }

    private data class FailureRecord(val count: Int, val lastAtMs: Long)

    override suspend fun start() {
        lifecycleLock.withLock {
            if (runJob?.isActive == true) return
            // LAZY then start(): the run loop checks `runJob` to decide whether it should keep
            // going, and an eagerly dispatched coroutine can reach that check on another thread
            // before the assignment below lands, which would make it exit immediately.
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { runLoop() }
            runJob = job
            job.start()
        }
    }

    override suspend fun stop(reason: String) {
        val job = lifecycleLock.withLock {
            val current = runJob
            runJob = null
            current
        }
        diagnostics.info("engine", "stopping: $reason")
        // Cancel first so the run loop stops retrying, then close what it may be blocked on.
        job?.cancel(CancellationException(reason))
        closeEverything()
        job?.join()
        coordinator.dispatch(ConnectionEvent.StopComplete)
    }

    /**
     * Skips the rest of the backoff wait and tries again straight away.
     *
     * Implemented as a full stop and restart rather than by nudging the running loop: the loop
     * may be parked in `delay`, in a blocking accept, or mid-handshake, and cancelling it is the
     * only way to reach all three. "Reconnect now" has to mean now, not "after the current 30
     * second wait".
     */
    override suspend fun reconnectNow() {
        val job = lifecycleLock.withLock {
            val current = runJob
            runJob = null
            current
        }
        job?.cancel(CancellationException("reconnect requested"))
        _activeSession.value?.close()
        _activeSession.value = null
        job?.join()
        start()
    }

    /** Controller: use a scanned pairing code for the next attempt. */
    fun useTarget(next: ConnectTarget) {
        target = next
    }

    /** The user's answer to the six-digit comparison. */
    fun confirmShortAuthString(accepted: Boolean) {
        sasDecision?.complete(accepted)
    }

    /**
     * Display: mint a fresh single-use pairing code.
     *
     * Returns null when there is nothing to advertise yet -- no listener, no local address or no
     * device identity -- rather than showing a code that cannot work.
     */
    fun offerPairingCode(): PairingOffer? {
        val identity = identityProvider() ?: return null
        val port = _listeningPort.value ?: return null
        val host = AndroidPlatformCapabilities.localIpv4Address() ?: return null
        val settings = settingsSnapshot ?: return null

        val token = PairingUri.newToken()
        val expires = nowMs() + ProtocolTimings.PAIRING_TOKEN_TTL_MS
        val offer = PairingOffer(
            uri = PairingUri.build(
                peerId = settings.localDeviceId,
                fingerprint = identity.fingerprint,
                host = host,
                port = port,
                token = token,
                expiresAtEpochMs = expires,
                displayName = settings.localDeviceName,
            ),
            token = token,
            expiresAtEpochMs = expires,
        )
        _pairingCode.value = offer
        return offer
    }

    fun withdrawPairingCode() {
        _pairingCode.value = null
    }

    // -- run loop -------------------------------------------------------------------------

    private suspend fun runLoop() {
        val backoff = ReconnectBackoff()
        while (scope.isActive && currentJobActive()) {
            val settings = settingsRepository.settings.first { it.loaded }
            settingsSnapshot = settings
            val role = settings.role
            if (role == null) {
                coordinator.dispatch(ConnectionEvent.Failure(RelayError.Internal("no role selected")))
                return
            }

            coordinator.dispatch(ConnectionEvent.SessionRequested)
            val verdict = platform.evaluate(RelayPurpose.LocalNetwork)
            if (verdict is CapabilityVerdict.Blocked) {
                // Do not retry on a loop: the only thing that changes this is the user granting
                // the permission, and the UI already offers that.
                coordinator.dispatch(ConnectionEvent.Failure(RelayError.LocalNetworkBlocked))
                return
            }
            coordinator.dispatch(ConnectionEvent.PreparationComplete)

            val connectedAtMs = nowMs()
            val error = try {
                when (role) {
                    DeviceRole.DISPLAY -> runDisplayCycle(settings)
                    DeviceRole.CONTROLLER -> runControllerCycle(settings)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                RelayError.ConnectFailed(e.javaClass.simpleName)
            } catch (e: ProtocolException) {
                protocolErrorToRelayError(e)
            }

            if (!coordinator.sessionIntended.value || !currentJobActive()) {
                coordinator.dispatch(ConnectionEvent.StopComplete)
                return
            }

            if (error != null && !error.recoverable) {
                coordinator.dispatch(ConnectionEvent.Failure(error))
                return
            }

            // A session that lasted a while means the network is fine; start the ladder again so
            // an occasional drop does not push us straight to a thirty second wait.
            if (nowMs() - connectedAtMs >= ReconnectBackoff.STABLE_CONNECTION_MS) backoff.reset()

            if (!settingsRepository.settings.first().reconnectAutomatically) {
                coordinator.dispatch(ConnectionEvent.LinkLost(error))
                return
            }

            val delayMs = backoff.nextDelayMs()
            coordinator.dispatch(ConnectionEvent.RetryScheduled(backoff.attemptCount(), delayMs, error))
            delay(delayMs)
            coordinator.dispatch(ConnectionEvent.RetryNow)
        }
    }

    private fun currentJobActive(): Boolean = runJob?.isActive ?: false

    // -- display side ---------------------------------------------------------------------

    private suspend fun runDisplayCycle(settings: RelaySettings): RelayError? {
        var listener: RelayListener? = null
        try {
            listener = withContext(Dispatchers.IO) { listenerFactory() }
            activeListener.set(listener)
            val port = listener.port
            _listeningPort.value = port
            diagnostics.info("engine", "listening on port $port")

            var advertiseError: String? = null
            advertiser.start(
                serviceName = settings.localDeviceName.ifEmpty { "RelayDisplay" },
                port = port,
                protocolMajor = ProtocolConstants.VERSION_MAJOR,
            ) { message -> advertiseError = message }

            // Discovery failing is survivable: the QR code carries the same endpoint, so the
            // waiting screen still has a working path.
            if (advertiseError != null) diagnostics.warn("engine", "advertise: $advertiseError")

            coordinator.dispatch(ConnectionEvent.PairingProgress(PairingStage.AwaitingPeer))

            val boundListener = listener
            while (currentJobActive()) {
                val link = withContext(Dispatchers.IO) {
                    try {
                        boundListener.accept()
                    } catch (e: IOException) {
                        null
                    }
                } ?: return RelayError.ConnectFailed("listener closed")

                if (isRateLimited(link.remote.host)) {
                    diagnostics.warn("engine", "dropping repeat attempt from ${DiagnosticsLog.redactHost(link.remote.host)}")
                    link.close()
                    continue
                }

                // One authoritative session per role. A second caller is refused rather than
                // silently replacing the one the user is looking at.
                if (_activeSession.value != null) {
                    diagnostics.warn("engine", "refusing a second connection")
                    link.close()
                    continue
                }

                val result = serveOne(link, settings)
                if (result != null) return result
                if (!coordinator.sessionIntended.value) return null

                // The loop is about to block in accept() again. Nothing else tells the
                // coordinator that the peer has gone, so without this the waiting screen goes on
                // saying "Connected to <peer>" until the next caller arrives -- which is exactly
                // the kind of contradiction between the two phones this whole design exists to
                // prevent, and it reproduced on hardware.
                coordinator.dispatch(ConnectionEvent.PeerDisconnected)
            }
            return null
        } finally {
            activeListener.compareAndSet(listener, null)
            _listeningPort.value = null
            advertiser.stop()
            listener?.close()
            withdrawPairingCode()
        }
    }

    private suspend fun serveOne(link: RelayLink, settings: RelaySettings): RelayError? {
        val connection = SecureConnection(link)
        val identity = identityProvider() ?: return RelayError.Internal("device identity unavailable")
        val trusted = trustedPeerRepository.current()
        val offer = _pairingCode.value?.takeIf { !it.isExpired(nowMs()) }

        coordinator.dispatch(ConnectionEvent.TransportConnected)
        coordinator.dispatch(ConnectionEvent.PairingProgress(PairingStage.ExchangingKeys))

        val outcome = try {
            withTimeoutOrNull(ProtocolTimings.HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    HandshakeRunner.runResponder(
                        connection,
                        HandshakeConfig(
                            role = DeviceRole.DISPLAY,
                            deviceId = settings.localDeviceId,
                            deviceName = settings.localDeviceName,
                            identity = identity,
                            capabilities = displayCapabilities(),
                            pinnedPeerFingerprint = trusted?.fingerprint,
                            pairingToken = offer?.token,
                        ),
                    )
                }
            }
        } catch (e: ProtocolException) {
            recordFailure(link.remote.host)
            connection.close()
            return protocolErrorToRelayError(e)
        }

        if (outcome == null) {
            recordFailure(link.remote.host)
            connection.close()
            return RelayError.HandshakeFailed("timed out")
        }

        // A pairing token is single use: burn it the moment it has done its job, successfully
        // or not, so a captured code cannot be replayed.
        if (offer != null) withdrawPairingCode()

        return finishSession(connection, outcome, settings, isInitiator = false)
    }

    // -- controller side ------------------------------------------------------------------

    private suspend fun runControllerCycle(settings: RelaySettings): RelayError? {
        val identity = identityProvider() ?: return RelayError.Internal("device identity unavailable")
        val trusted = trustedPeerRepository.current()
        val currentTarget = target

        val endpoint = when (currentTarget) {
            is ConnectTarget.Manual -> currentTarget.endpoint
            is ConnectTarget.Paired -> Endpoint(currentTarget.payload.host, currentTarget.payload.port)
            ConnectTarget.LastKnown -> resolveEndpoint(trusted) ?: return RelayError.PeerNotFound
        }

        coordinator.dispatch(ConnectionEvent.EndpointAvailable(endpoint))

        val link = try {
            withContext(Dispatchers.IO) { dialer.connect(endpoint, ProtocolTimings.CONNECT_TIMEOUT_MS) }
        } catch (e: IOException) {
            return RelayError.ConnectFailed(e.javaClass.simpleName)
        }

        val connection = SecureConnection(link)
        coordinator.dispatch(ConnectionEvent.TransportConnected)
        coordinator.dispatch(ConnectionEvent.PairingProgress(PairingStage.ExchangingKeys))

        val pairing = (currentTarget as? ConnectTarget.Paired)?.payload
        val pinned = pairing?.fingerprint ?: trusted?.fingerprint

        val outcome = try {
            withTimeoutOrNull(ProtocolTimings.HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    HandshakeRunner.runInitiator(
                        connection,
                        HandshakeConfig(
                            role = DeviceRole.CONTROLLER,
                            deviceId = settings.localDeviceId,
                            deviceName = settings.localDeviceName,
                            identity = identity,
                            capabilities = controllerCapabilities(),
                            pinnedPeerFingerprint = pinned,
                            pairingToken = pairing?.token,
                        ),
                    )
                }
            }
        } catch (e: ProtocolException) {
            connection.close()
            return protocolErrorToRelayError(e)
        }

        if (outcome == null) {
            connection.close()
            return RelayError.HandshakeFailed("timed out")
        }

        // A scanned code is good for exactly one attempt; fall back to the stored endpoint next.
        if (currentTarget is ConnectTarget.Paired) target = ConnectTarget.LastKnown

        return finishSession(connection, outcome, settings, isInitiator = true)
    }

    /**
     * The last known endpoint, then discovery.
     *
     * The fast path is only tried when the current subnet matches the one the endpoint worked on
     * before; otherwise it is a guaranteed timeout that would just delay real discovery.
     */
    private suspend fun resolveEndpoint(trusted: TrustedPeer?): Endpoint? {
        val subnet = AndroidPlatformCapabilities.subnetOf(AndroidPlatformCapabilities.localIpv4Address())
        if (trusted != null && trusted.hasEndpoint && trusted.lastSubnet != null && trusted.lastSubnet == subnet) {
            val fast = Endpoint(trusted.lastHost!!, trusted.lastPort)
            diagnostics.debug("engine", "trying last known endpoint on ${DiagnosticsLog.redactHost(fast.host)}")
            val reachable = withContext(Dispatchers.IO) {
                runCatching {
                    dialer.connect(fast, ProtocolTimings.FAST_PATH_CONNECT_TIMEOUT_MS).use { true }
                }.getOrDefault(false)
            }
            if (reachable) return fast
        }
        return discoverOne()
    }

    private suspend fun discoverOne(): Endpoint? {
        coordinator.dispatch(ConnectionEvent.PreparationComplete)
        retainBrowse()
        return try {
            withTimeoutOrNull(ProtocolTimings.NSD_RESOLVE_TIMEOUT_MS) {
                combine(_discovered, _browseError) { list, error ->
                    val usable = list.firstOrNull(::isCompatible)
                    when {
                        usable != null -> BrowseOutcome.Found(usable.endpoint)
                        error != null -> BrowseOutcome.Failed
                        else -> null
                    }
                }.filterNotNull().first()
            }.let { (it as? BrowseOutcome.Found)?.endpoint }
        } finally {
            releaseBrowse()
        }
    }

    private fun isCompatible(display: DiscoveredDisplay): Boolean =
        display.protocolMajor == 0 || display.protocolMajor == ProtocolConstants.VERSION_MAJOR

    private sealed interface BrowseOutcome {
        data class Found(val endpoint: Endpoint) : BrowseOutcome
        data object Failed : BrowseOutcome
    }

    // -- browsing -------------------------------------------------------------------------

    /**
     * Browsing has two consumers with different lifetimes: a connection attempt wants the first
     * compatible endpoint and then stops, while the pairing screen wants a live list for as long
     * as it is open.
     *
     * They used to be one: the only `browser.start` in the codebase was inside `discoverOne`, and
     * its `finally` stopped discovery as soon as an endpoint was found. So "Displays on this
     * network" was populated only during a connection attempt on a phone that was already
     * connecting -- which is exactly when nobody is reading that list. On a Controller sitting on
     * the pairing screen with nothing paired, no attempt is running, so the list could never fill
     * and the section was a promise the app could not keep.
     *
     * `NsdBrowser` holds one listener, so two independent browses are not an option. Both
     * consumers retain this one instead, and the last to leave stops it.
     */
    fun retainBrowse() = browseLease.retain()

    /** Balances one [retainBrowse]. Safe to call more times than it was retained. */
    fun releaseBrowse() = browseLease.release()

    private fun beginBrowse() {
        _browseError.value = null
        _discovered.value = emptyList()
        browser.start(
            excludeServiceName = null,
            onFound = { display ->
                _discovered.update { current ->
                    if (current.any { it.serviceName == display.serviceName }) {
                        current
                    } else {
                        current + display
                    }
                }
            },
            onLost = { name ->
                _discovered.update { current -> current.filterNot { it.serviceName == name } }
            },
            onError = { reason -> _browseError.value = reason },
        )
    }

    private fun endBrowse() {
        browser.stop()
        _discovered.value = emptyList()
    }

    // -- shared tail ----------------------------------------------------------------------

    /**
     * Everything after a successful key exchange: user confirmation when required, persisting
     * trust, then running the session until it ends.
     */
    private suspend fun finishSession(
        connection: SecureConnection,
        outcome: HandshakeOutcome,
        settings: RelaySettings,
        isInitiator: Boolean,
    ): RelayError? {
        if (outcome.requiresSasConfirmation) {
            val agreed = confirmShortAuthString(connection, outcome, isInitiator)
            if (!agreed) {
                connection.close()
                recordFailure(connection.remote.host)
                return RelayError.Untrusted("the codes did not match")
            }
        }

        coordinator.dispatch(ConnectionEvent.PairingProgress(PairingStage.Finalising))
        persistTrust(outcome, connection.remote)
        clearFailures(connection.remote.host)

        val ended = CompletableDeferred<RelayError?>()
        val session = RelaySession(
            connection = connection,
            outcome = outcome,
            listener = object : SessionListener {
                override fun onMessage(message: RelayMessage) {
                    _messages.tryEmit(message)
                }

                override fun onClosed(error: RelayError?) {
                    _activeSession.value = null
                    ended.complete(error)
                }
            },
            parentScope = scope,
            nowMs = nowMs,
        )
        _activeSession.value = session
        session.start()

        coordinator.dispatch(
            ConnectionEvent.Authenticated(
                peer = PeerSummary(
                    peerId = outcome.peerDeviceId,
                    displayName = outcome.peerDeviceName,
                    fingerprintShort = com.avinash.relaydisplay.security.Fingerprints.format(outcome.peerFingerprint),
                    capabilities = outcome.peerCapabilities,
                    protocolMinor = outcome.negotiatedMinorVersion,
                ),
                endpoint = connection.remote,
            ),
        )
        diagnostics.info("engine", "session established with ${outcome.peerDeviceName}")

        return try {
            ended.await()
        } finally {
            session.close()
            _activeSession.value = null
        }
    }

    /**
     * Shows the six digits, waits for this user, then exchanges confirmations with the peer.
     *
     * Both sides must say yes. A peer that says no, or that says nothing before the timeout,
     * means no trust is written: a partial agreement is treated as a failure.
     */
    private suspend fun confirmShortAuthString(
        connection: SecureConnection,
        outcome: HandshakeOutcome,
        isInitiator: Boolean,
    ): Boolean {
        val decision = CompletableDeferred<Boolean>()
        sasDecision = decision
        coordinator.dispatch(
            ConnectionEvent.PairingProgress(
                PairingStage.AwaitingUserConfirmation,
                outcome.keys.shortAuthString,
            ),
        )
        val localAccepted = withTimeoutOrNull(SAS_TIMEOUT_MS) { decision.await() } ?: false
        sasDecision = null

        return withContext(Dispatchers.IO) {
            runCatching {
                connection.write(SasConfirm(UUID.randomUUID(), localAccepted))
                if (!localAccepted) return@runCatching false
                val reply = withTimeoutOrNull(SAS_TIMEOUT_MS) { connection.read() }
                (reply as? SasConfirm)?.confirmed == true
            }.getOrDefault(false)
        }
    }

    private suspend fun persistTrust(outcome: HandshakeOutcome, endpoint: Endpoint) {
        val existing = trustedPeerRepository.current()
        val subnet = AndroidPlatformCapabilities.subnetOf(AndroidPlatformCapabilities.localIpv4Address())
        val peer = TrustedPeer(
            peerId = outcome.peerDeviceId,
            displayName = outcome.peerDeviceName,
            fingerprint = outcome.peerFingerprint,
            identityPublicKey = outcome.peerIdentityPublicKey,
            role = outcome.peerRole,
            protocolMajor = ProtocolConstants.VERSION_MAJOR,
            protocolMinor = outcome.negotiatedMinorVersion,
            capabilities = outcome.peerCapabilities,
            lastHost = endpoint.host,
            lastPort = endpoint.port,
            lastSubnet = subnet,
            pairedAtEpochMs = existing?.pairedAtEpochMs?.takeIf {
                it != 0L && constantTimeEquals(existing.fingerprint, outcome.peerFingerprint)
            } ?: nowMs(),
            lastConnectedEpochMs = nowMs(),
        )
        trustedPeerRepository.save(peer)
    }

    private fun controllerCapabilities(): Set<String> = setOf(
        Capabilities.TEXT,
        Capabilities.QR,
        Capabilities.LINK,
        Capabilities.IMAGE,
        Capabilities.PDF,
        Capabilities.MIRROR_SEND,
    )

    private fun displayCapabilities(): Set<String> = setOf(
        Capabilities.TEXT,
        Capabilities.QR,
        Capabilities.LINK,
        Capabilities.IMAGE,
        Capabilities.PDF,
        Capabilities.MIRROR_RECEIVE,
    )

    // -- abuse control --------------------------------------------------------------------

    @Synchronized
    private fun isRateLimited(host: String): Boolean {
        val record = failedAttempts[host] ?: return false
        if (record.count < FAILURES_BEFORE_DELAY) return false
        val waitMs = minOf(MAX_PENALTY_MS, BASE_PENALTY_MS shl minOf(record.count - FAILURES_BEFORE_DELAY, 5))
        return nowMs() - record.lastAtMs < waitMs
    }

    @Synchronized
    private fun recordFailure(host: String) {
        val previous = failedAttempts[host]
        failedAttempts[host] = FailureRecord((previous?.count ?: 0) + 1, nowMs())
    }

    @Synchronized
    private fun clearFailures(host: String) {
        failedAttempts.remove(host)
    }

    private suspend fun closeEverything() {
        _listeningPort.value = null
        advertiser.stop()
        // Before anything else: this is the one thing the run loop can be blocked on in a way
        // cancellation cannot reach, so closing it is what lets stop()'s join() ever return.
        activeListener.getAndSet(null)?.close()
        browseLease.reset()
        _activeSession.value?.close()
        _activeSession.value = null
        _discovered.value = emptyList()
        withdrawPairingCode()
    }

    private fun protocolErrorToRelayError(e: ProtocolException): RelayError = when (e.errorCode) {
        ProtocolErrorCode.AUTH_FAILED, ProtocolErrorCode.NOT_AUTHENTICATED ->
            RelayError.Untrusted(e.errorCode.name)
        ProtocolErrorCode.UNSUPPORTED_VERSION -> RelayError.HandshakeFailed("protocol version mismatch")
        ProtocolErrorCode.RATE_LIMITED -> RelayError.HandshakeFailed("too many attempts")
        else -> RelayError.ProtocolFailure(e.errorCode.name)
    }

    private companion object {
        const val SAS_TIMEOUT_MS = 120_000L
        const val FAILURES_BEFORE_DELAY = 3
        const val BASE_PENALTY_MS = 2_000L
        const val MAX_PENALTY_MS = 60_000L
        const val MAX_TRACKED_CLIENTS = 32
    }
}

/** A live pairing code and the secret behind it. The token never leaves the process. */
data class PairingOffer(
    val uri: String,
    val token: ByteArray,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtEpochMs

    override fun equals(other: Any?): Boolean =
        this === other || (other is PairingOffer && uri == other.uri && expiresAtEpochMs == other.expiresAtEpochMs)

    override fun hashCode(): Int = uri.hashCode()

    /** Never prints the token. */
    override fun toString(): String = "PairingOffer(expiresAt=$expiresAtEpochMs)"
}

