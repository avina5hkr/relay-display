package com.avinash.relaydisplay.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.network.session.ConnectionEvent
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.Endpoint
import com.avinash.relaydisplay.network.session.PeerSummary
import com.avinash.relaydisplay.network.session.SessionCoordinator
import com.avinash.relaydisplay.network.session.SessionEngine
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The coordinator is the only writer of connection state, so its handling of user intent and of
 * Pause is what makes "Paused means nothing is running" true rather than aspirational.
 */
class SessionCoordinatorTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository
    private lateinit var coordinator: SessionCoordinator
    private lateinit var engine: RecordingEngine

    /** Records what the coordinator asked the networking layer to do, and when. */
    private class RecordingEngine : SessionEngine {
        val calls = CopyOnWriteArrayList<String>()
        var running = false
            private set

        override suspend fun start() {
            running = true
            calls.add("start")
        }

        override suspend fun stop(reason: String) {
            running = false
            calls.add("stop:$reason")
        }

        override suspend fun reconnectNow() {
            calls.add("reconnect")
        }
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(temp.newFolder(), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { file.toOkioPath() }
        repository = SettingsRepository(dataStore)
        coordinator = SessionCoordinator(repository, DiagnosticsLog(), scope)
        engine = RecordingEngine()
        coordinator.attachEngine(engine)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitState(predicate: (ConnectionState) -> Boolean) {
        withTimeout(5_000) {
            while (!predicate(coordinator.state.value)) delay(10)
        }
    }

    private suspend fun awaitCall(name: String) {
        withTimeout(5_000) {
            while (engine.calls.none { it.startsWith(name) }) delay(10)
        }
    }

    @Test
    fun `requesting a session starts the engine and records the intent`() = runBlocking {
        coordinator.requestSession()
        awaitCall("start")
        assertTrue(coordinator.sessionIntended.value)
        awaitState { it == ConnectionState.Preparing }
    }

    @Test
    fun `releasing a session stops the engine and clears the intent`() = runBlocking {
        coordinator.requestSession()
        awaitCall("start")
        coordinator.releaseSession()
        awaitCall("stop")
        assertFalse(coordinator.sessionIntended.value)
        awaitState { it == ConnectionState.Idle }
    }

    @Test
    fun `pausing stops the engine and blocks every later request`() = runBlocking {
        coordinator.requestSession()
        awaitCall("start")

        coordinator.onOperatingModeChanged(OperatingMode.PAUSED)
        awaitState { it == ConnectionState.Paused }
        assertFalse("Pause must clear the intent", coordinator.sessionIntended.value)
        assertFalse("Pause must stop the engine", engine.running)

        // The whole point of Pause: a later Connect does nothing at all.
        engine.calls.clear()
        coordinator.requestSession()
        delay(200)
        assertTrue("a paused coordinator must not start anything", engine.calls.isEmpty())
        assertEquals(ConnectionState.Paused, coordinator.state.value)
        assertFalse(coordinator.sessionIntended.value)
    }

    @Test
    fun `always ready starts a session by itself`() = runBlocking {
        coordinator.onOperatingModeChanged(OperatingMode.ALWAYS_READY)
        awaitCall("start")
        assertTrue(coordinator.sessionIntended.value)
    }

    @Test
    fun `leaving always ready for on demand stops the session`() = runBlocking {
        coordinator.onOperatingModeChanged(OperatingMode.ALWAYS_READY)
        awaitCall("start")
        engine.calls.clear()

        coordinator.onOperatingModeChanged(OperatingMode.ON_DEMAND)
        awaitCall("stop")
        assertFalse(
            "\"only when I say so\" must not leave a session running",
            coordinator.sessionIntended.value,
        )
    }

    @Test
    fun `a hard reset stops everything and gives up the intent`() = runBlocking {
        coordinator.requestSession()
        awaitCall("start")
        engine.calls.clear()

        coordinator.hardReset("role change")
        awaitCall("stop")
        assertFalse(coordinator.sessionIntended.value)
        awaitState { it == ConnectionState.Idle }
    }

    @Test
    fun `dispatch is the only path that moves state`() = runBlocking {
        coordinator.requestSession()
        awaitState { it == ConnectionState.Preparing }

        coordinator.dispatch(ConnectionEvent.PreparationComplete)
        assertEquals(ConnectionState.Discovering, coordinator.state.value)

        val endpoint = Endpoint("192.168.1.5", 41234)
        coordinator.dispatch(ConnectionEvent.EndpointAvailable(endpoint))
        assertEquals(ConnectionState.Connecting(endpoint), coordinator.state.value)

        coordinator.dispatch(ConnectionEvent.TransportConnected)
        assertEquals(ConnectionState.Authenticating, coordinator.state.value)

        val peer = PeerSummary("display-1", "K6 Power", "AAAA", setOf("qr"), 0)
        coordinator.dispatch(ConnectionEvent.Authenticated(peer, endpoint))
        assertTrue(coordinator.state.value.isConnected)
    }

    @Test
    fun `a drop after the user disconnected does not reconnect`() = runBlocking {
        coordinator.requestSession()
        awaitCall("start")
        coordinator.dispatch(ConnectionEvent.PreparationComplete)
        coordinator.releaseSession()
        awaitState { it == ConnectionState.Idle }

        coordinator.dispatch(ConnectionEvent.LinkLost(null))
        assertEquals(
            "a drop the user already asked for must not turn into a retry",
            ConnectionState.Idle,
            coordinator.state.value,
        )
    }

    @Test
    fun `stopping twice is harmless`() = runBlocking {
        coordinator.requestSession()
        awaitCall("start")
        coordinator.releaseSession()
        coordinator.releaseSession()
        awaitState { it == ConnectionState.Idle }
        assertFalse(engine.running)
    }
}
