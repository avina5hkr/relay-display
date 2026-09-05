package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.data.settings.RelaySettings
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.OperatingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the connection lifecycle for the whole process.
 *
 * This is the only object allowed to move [ConnectionState]. Everything else -- view models,
 * the foreground service, the notification actions -- asks it to do something and then observes
 * [state]. That single-writer rule is what stops a background/foreground transition from ending
 * up with two discovery listeners or two socket readers.
 *
 * The networking itself lives behind [SessionEngine] so the lifecycle rules here can be reasoned
 * about, and tested, without a socket in sight.
 */
class SessionCoordinator(
    private val settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _sessionIntended = MutableStateFlow(false)

    /** True while the user (or Always Ready) wants a session. Nothing reconnects when false. */
    val sessionIntended: StateFlow<Boolean> = _sessionIntended.asStateFlow()

    @Volatile
    private var engine: SessionEngine? = null

    @Volatile
    private var settings: RelaySettings = RelaySettings()

    init {
        scope.launch {
            settingsRepository.settings.collect { latest ->
                val previous = settings
                settings = latest
                if (!previous.loaded && latest.loaded) {
                    // First load: adopt the persisted mode without pretending anything is running.
                    applyMode(latest.operatingMode, initial = true)
                }
            }
        }
    }

    /** Installs the object that actually talks to the network. Called once, from the container. */
    fun attachEngine(newEngine: SessionEngine) {
        engine = newEngine
    }

    private fun context(): ConnectionContext = ConnectionContext(
        mode = settings.operatingMode,
        autoReconnect = settings.reconnectAutomatically,
        sessionIntended = _sessionIntended.value,
    )

    /** Feeds an event through the state machine. The single write path for [state]. */
    fun dispatch(event: ConnectionEvent) {
        val previous = _state.value
        val next = ConnectionStateMachine.reduce(previous, event, context())
        if (next != previous) {
            _state.value = next
            diagnostics.debug("session", "${previous.label()} -> ${next.label()}")
        }
    }

    /** User tapped Connect, or Always Ready came up. */
    fun requestSession() {
        scope.launch {
            mutex.withLock {
                if (settings.operatingMode == OperatingMode.PAUSED) {
                    diagnostics.info("session", "connect ignored: paused")
                    return@withLock
                }
                _sessionIntended.value = true
                dispatch(ConnectionEvent.SessionRequested)
                engine?.start()
            }
        }
    }

    /** User tapped Disconnect. Suppresses reconnection until they ask again. */
    fun releaseSession() {
        scope.launch {
            mutex.withLock {
                _sessionIntended.value = false
                dispatch(ConnectionEvent.SessionReleased)
                engine?.stop("user disconnected")
                dispatch(ConnectionEvent.StopComplete)
            }
        }
    }

    fun reconnectNow() {
        scope.launch {
            mutex.withLock {
                if (settings.operatingMode == OperatingMode.PAUSED) return@withLock
                _sessionIntended.value = true
                dispatch(ConnectionEvent.RetryNow)
                engine?.reconnectNow()
            }
        }
    }

    /**
     * Stop everything and forget any intent to reconnect.
     *
     * Used for a role change, a forgotten peer and a revoked permission: cases where continuing
     * would be either meaningless or wrong.
     */
    suspend fun hardReset(reason: String) {
        mutex.withLock {
            diagnostics.warn("session", "hard reset: $reason")
            _sessionIntended.value = false
            engine?.stop(reason)
            dispatch(ConnectionEvent.HardReset)
        }
    }

    /** Brings runtime state in line with a mode the user just chose. */
    suspend fun onOperatingModeChanged(mode: OperatingMode) {
        mutex.withLock { applyMode(mode, initial = false) }
    }

    private suspend fun applyMode(mode: OperatingMode, initial: Boolean) {
        // Adopt the mode into the cached snapshot straight away rather than waiting for the
        // DataStore flow to catch up. Otherwise a Connect arriving in the window between
        // "user chose Paused" and "the store reported Paused" would sail past the guard in
        // requestSession and start the very thing Pause is meant to stop.
        settings = settings.copy(operatingMode = mode)

        when (mode) {
            OperatingMode.PAUSED -> {
                _sessionIntended.value = false
                engine?.stop("paused")
                dispatch(ConnectionEvent.UserPaused)
            }
            OperatingMode.ON_DEMAND -> {
                if (initial) {
                    // First load only adopts the mode. It must not dispatch StopComplete: the
                    // user can tap Connect while the store is still being read, and resetting to
                    // Idle here would silently cancel the session they just asked for.
                    dispatch(ConnectionEvent.UserResumed)
                } else {
                    // Leaving Always Ready must not silently keep a session alive; the user
                    // asked for "only when I say so".
                    _sessionIntended.value = false
                    engine?.stop("switched to on demand")
                    dispatch(ConnectionEvent.UserResumed)
                    dispatch(ConnectionEvent.StopComplete)
                }
            }
            OperatingMode.ALWAYS_READY -> {
                dispatch(ConnectionEvent.UserResumed)
                _sessionIntended.value = true
                dispatch(ConnectionEvent.SessionRequested)
                engine?.start()
            }
        }
    }

    /** Called when the app process is going away for good. Makes teardown deterministic. */
    suspend fun shutdown() {
        mutex.withLock {
            engine?.stop("process shutdown")
            engine = null
        }
    }

    /** Waits until settings have loaded at least once. Used by the service before it acts. */
    suspend fun awaitLoadedSettings(): RelaySettings = settingsRepository.settings.first { it.loaded }
}

/** A short, stable label for logs and the diagnostics screen. */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.Paused -> "Paused"
    ConnectionState.Idle -> "Idle"
    ConnectionState.Preparing -> "Preparing"
    ConnectionState.Discovering -> "Discovering"
    is ConnectionState.Pairing -> "Pairing(${stage.name})"
    is ConnectionState.Connecting -> "Connecting"
    ConnectionState.Authenticating -> "Authenticating"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting(#$attempt)"
    is ConnectionState.Failed -> "Failed(${error.javaClass.simpleName})"
    ConnectionState.Stopping -> "Stopping"
}

/**
 * Read-only access to the live session.
 *
 * A narrow seam so the content router and the mirror controller depend on *what a session is*
 * rather than on the whole engine. That keeps both of them testable without a socket, and stops
 * either from reaching into engine internals it has no business touching.
 */
interface SessionHost {
    val activeSession: kotlinx.coroutines.flow.StateFlow<RelaySession?>
    val messages: kotlinx.coroutines.flow.SharedFlow<com.avinash.relaydisplay.protocol.RelayMessage>
}

/**
 * The networking half of a session.
 *
 * Implementations own sockets, discovery listeners and cipher state; every method must be safe
 * to call twice, because stop paths run from more than one place (user action, network loss,
 * process teardown).
 */
interface SessionEngine {
    suspend fun start()
    suspend fun stop(reason: String)
    suspend fun reconnectNow()
}
