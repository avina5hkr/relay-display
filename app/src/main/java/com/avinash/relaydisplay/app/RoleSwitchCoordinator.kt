package com.avinash.relaydisplay.app

import com.avinash.relaydisplay.content.ContentRouter
import com.avinash.relaydisplay.content.PresentationController
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.mirroring.MirrorController
import com.avinash.relaydisplay.network.session.SessionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where a role switch has got to. Rendered directly, so the UI never guesses. */
sealed interface RoleSwitchState {
    data object Idle : RoleSwitchState

    /** A switch is under way. The UI disables every role control while this is showing. */
    data class Switching(val target: DeviceRole, val step: String) : RoleSwitchState

    /** Finished. [disconnected] is true when a live session was torn down to get here. */
    data class Completed(val role: DeviceRole, val disconnected: Boolean) : RoleSwitchState

    data class Failed(val target: DeviceRole, val reason: String) : RoleSwitchState
}

/**
 * Turns a role change into one coordinated, serialized transition.
 *
 * Before this existed, changing role was a `hardReset()` followed by a DataStore write. Real
 * device logs showed three of those inside eleven seconds, overlapping each other, which left the
 * two phones disagreeing about who was the controller. The fix is not a bigger reset -- it is
 * making the transition a single critical section with a defined order and a bounded wait.
 *
 * The order matters and is deliberately outside-in: stop producing (capture, transfers), then
 * stop the session, then stop the things that would let a peer reconnect (listener, discovery),
 * then release OS resources, and only then persist the new role. Persisting last means a process
 * death mid-switch leaves the old role intact and everything stopped, which is recoverable;
 * persisting first would leave the new role running the old role's sockets.
 *
 * Every step is idempotent and every failure is swallowed into the next step, because a switch
 * that cannot complete cleanly must still end with everything stopped.
 */
class RoleSwitchCoordinator(
    private val settingsRepository: SettingsRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val mirrorController: MirrorController,
    private val contentRouter: ContentRouter,
    private val presentationController: PresentationController,
    private val diagnostics: DiagnosticsLog,
    private val stopForegroundService: () -> Unit,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<RoleSwitchState>(RoleSwitchState.Idle)
    val state: StateFlow<RoleSwitchState> = _state.asStateFlow()

    /** True while a switch is running. The UI uses this to refuse a second tap. */
    val inProgress: Boolean get() = _state.value is RoleSwitchState.Switching

    /**
     * Performs the switch, or returns immediately if one is already running.
     *
     * `tryLock` rather than `withLock` is the double-tap guard: a second tap while a switch is
     * under way is dropped rather than queued, because queueing would run the whole sequence
     * again for no reason.
     */
    suspend fun switchTo(target: DeviceRole) {
        if (!mutex.tryLock()) {
            diagnostics.info("RD/RoleSwitch", "ignored a repeat tap while switching")
            return
        }
        try {
            runSwitch(target)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runSwitch(target: DeviceRole) {
        val hadSession = sessionCoordinator.state.value.isConnected
        diagnostics.info("RD/RoleSwitch", "begin -> ${target.storageValue} (connected=$hadSession)")

        // NonCancellable: a half-executed teardown is worse than a slow one. If the caller's
        // scope dies mid-switch we still finish stopping things.
        withContext(NonCancellable) {
            try {
                step(target, "stopping screen sharing")
                // 1-3: stop producing. Capture first: it is the only thing holding a system
                // resource the user can see (the recording indicator).
                runStep("mirror") { mirrorController.stop("role change") }
                runStep("transfer") { contentRouter.cancelOutboundTransfer() }

                step(target, "closing the session")
                // 4-8: hardReset sends a courtesy close, stops reconnects and heartbeats, closes
                // sockets, stops the listener and unregisters NSD. Bounded so a wedged socket
                // cannot hang the switch forever.
                val stopped = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    sessionCoordinator.hardReset("role change")
                    true
                }
                if (stopped == null) {
                    diagnostics.warn("RD/RoleSwitch", "shutdown timed out; continuing")
                }

                step(target, "clearing what was on screen")
                // 10: drop presentation state. A new role must not inherit the old one's screen.
                runStep("presentation") { contentRouter.dismissLocally() }
                runStep("presentation-reset") { presentationController.onSessionStarted(NO_SESSION) }

                step(target, "stopping the background service")
                // 11: the service is role-agnostic but its notification describes a session that
                // no longer exists.
                runStep("service") { stopForegroundService() }

                step(target, "saving the new role")
                // 13: persist last, so an interrupted switch leaves a stopped old role rather
                // than a running mismatch.
                settingsRepository.changeRole(target)

                // 14-16: wait for the new role to actually be readable before declaring success,
                // so the UI does not navigate against a stale value.
                withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                    settingsRepository.settings.first { it.loaded && it.role == target }
                }

                diagnostics.info("RD/RoleSwitch", "complete -> ${target.storageValue}")
                _state.value = RoleSwitchState.Completed(target, disconnected = hadSession)
            } catch (e: CancellationException) {
                // NonCancellable means this should not happen; record it if it somehow does.
                diagnostics.warn("RD/RoleSwitch", "cancelled mid-switch")
                _state.value = RoleSwitchState.Failed(target, "interrupted")
                throw e
            } catch (e: java.io.IOException) {
                diagnostics.error("RD/RoleSwitch", "failed: ${e.javaClass.simpleName}")
                _state.value = RoleSwitchState.Failed(target, "storage error")
            }
        }
    }

    /**
     * Runs one teardown step, absorbing failures.
     *
     * A step that throws must not abort the rest: leaving a socket open because the encoder
     * failed to stop is exactly how two phones end up disagreeing about who is connected.
     */
    private inline fun runStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            diagnostics.warn("RD/RoleSwitch", "step $name: ${e.javaClass.simpleName}")
        } catch (e: java.io.IOException) {
            diagnostics.warn("RD/RoleSwitch", "step $name: ${e.javaClass.simpleName}")
        }
    }

    private fun step(target: DeviceRole, description: String) {
        _state.value = RoleSwitchState.Switching(target, description)
    }

    /**
     * Brings the new role's runtime up, but only if the saved mode says so.
     *
     * Called after navigation has settled. On Demand deliberately starts nothing: the whole point
     * of that mode is that the user asks first.
     */
    suspend fun startNetworkingIfNeeded() {
        val settings = settingsRepository.settings.first { it.loaded }
        if (settings.operatingMode == OperatingMode.ALWAYS_READY) {
            diagnostics.info("RD/RoleSwitch", "always ready: bringing the new role online")
            sessionCoordinator.onOperatingModeChanged(OperatingMode.ALWAYS_READY)
        }
    }

    /** Clears a terminal state once the UI has shown it. */
    fun acknowledge() {
        if (_state.value !is RoleSwitchState.Switching) _state.value = RoleSwitchState.Idle
    }

    private companion object {
        /** Long enough for a courtesy close and socket teardown, short enough not to feel stuck. */
        const val SHUTDOWN_TIMEOUT_MS = 4_000L
        const val SETTINGS_TIMEOUT_MS = 2_000L
        const val NO_SESSION = ""
    }
}
