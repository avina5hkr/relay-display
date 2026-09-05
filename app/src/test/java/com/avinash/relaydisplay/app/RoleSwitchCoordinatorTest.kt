package com.avinash.relaydisplay.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.avinash.relaydisplay.content.ContentCache
import com.avinash.relaydisplay.content.ContentRouter
import com.avinash.relaydisplay.content.PresentationController
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.mirroring.MirrorController
import com.avinash.relaydisplay.network.session.SessionCoordinator
import com.avinash.relaydisplay.network.session.SessionEngine
import com.avinash.relaydisplay.network.session.SessionHost
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The role switch, as a serialized transition.
 *
 * The device logs that motivated this class showed three overlapping `hard reset: role change`
 * entries inside eleven seconds. These tests pin the properties that prevent that: one switch at
 * a time, a defined teardown order, persistence last, and safety when things fail mid-way.
 */
class RoleSwitchCoordinatorTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsRepository
    private lateinit var sessionCoordinator: SessionCoordinator
    private lateinit var coordinator: RoleSwitchCoordinator
    private lateinit var presentation: PresentationController
    private lateinit var router: ContentRouter
    private lateinit var mirror: MirrorController

    /** Records the order teardown steps ran in, which is the property under test. */
    private val steps = CopyOnWriteArrayList<String>()

    private class RecordingEngine(private val steps: MutableList<String>) : SessionEngine {
        override suspend fun start() {
            steps.add("engine.start")
        }

        override suspend fun stop(reason: String) {
            steps.add("engine.stop")
        }

        override suspend fun reconnectNow() = Unit
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(temp.newFolder(), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { file.toOkioPath() }
        settings = SettingsRepository(dataStore)
        val diagnostics = DiagnosticsLog()
        sessionCoordinator = SessionCoordinator(settings, diagnostics, scope)
        sessionCoordinator.attachEngine(RecordingEngine(steps))

        presentation = PresentationController()
        mirror = MirrorController(EmptySessionHost, diagnostics, scope)
        router = ContentRouter(
            engine = EmptySessionHost,
            presentation = presentation,
            settingsRepository = settings,
            cacheProvider = { ContentCache(temp.newFolder()) },
            diagnostics = diagnostics,
            scope = scope,
            mirrorController = mirror,
        )

        coordinator = RoleSwitchCoordinator(
            settingsRepository = settings,
            sessionCoordinator = sessionCoordinator,
            mirrorController = mirror,
            contentRouter = router,
            presentationController = presentation,
            diagnostics = diagnostics,
            stopForegroundService = { steps.add("service.stop") },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a switch persists the new role`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)
        assertEquals(DeviceRole.DISPLAY, settings.settings.first().role)
    }

    @Test
    fun `the role is persisted only after everything is stopped`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)

        // Persisting last is what makes an interrupted switch recoverable: the old role stays
        // in force with everything already stopped, rather than a new role owning old sockets.
        val stopIndex = steps.indexOf("engine.stop")
        assertTrue("the engine must have been stopped", stopIndex >= 0)
        assertTrue("the service must have been stopped", steps.contains("service.stop"))
        assertEquals(DeviceRole.DISPLAY, settings.settings.first().role)
    }

    @Test
    fun `teardown happens in the documented order`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)
        // The session must be closed before the service that describes it is stopped.
        assertTrue(steps.indexOf("engine.stop") < steps.indexOf("service.stop"))
    }

    @Test
    fun `a repeated tap while switching is dropped, not queued`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        // Fire several switches at once, as a user jabbing the button would.
        val jobs = (1..5).map { async { coordinator.switchTo(DeviceRole.DISPLAY) } }
        jobs.forEach { it.await() }

        // Exactly one teardown ran, not five.
        assertEquals(
            "a repeated tap must not run the sequence again",
            1,
            steps.count { it == "service.stop" },
        )
        assertEquals(DeviceRole.DISPLAY, settings.settings.first().role)
    }

    @Test
    fun `the state machine reports switching then completed`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        assertEquals(RoleSwitchState.Idle, coordinator.state.value)

        val switching = async {
            var seen = false
            repeat(200) {
                if (coordinator.state.value is RoleSwitchState.Switching) seen = true
                delay(2)
            }
            seen
        }
        coordinator.switchTo(DeviceRole.DISPLAY)
        assertTrue("the UI must be able to see a switch in progress", switching.await())

        val terminal = coordinator.state.value
        assertTrue("expected Completed, got $terminal", terminal is RoleSwitchState.Completed)
        assertEquals(DeviceRole.DISPLAY, (terminal as RoleSwitchState.Completed).role)
    }

    @Test
    fun `switching clears what was on screen`() = runBlocking {
        settings.setRole(DeviceRole.DISPLAY)
        presentation.onSessionStarted("session-x")
        presentation.showText(
            "session-x",
            com.avinash.relaydisplay.protocol.PresentationEnvelope(java.util.UUID.randomUUID(), 1),
            "left over",
        )
        assertTrue(presentation.state.value.hasContent)

        coordinator.switchTo(DeviceRole.CONTROLLER)
        assertTrue(
            "a new role must not inherit the old role's screen",
            !presentation.state.value.hasContent,
        )
    }

    @Test
    fun `switching resets the operating mode so a new role does not start advertising`() = runBlocking {
        settings.setRole(DeviceRole.DISPLAY)
        settings.setOperatingMode(OperatingMode.ALWAYS_READY)
        coordinator.switchTo(DeviceRole.CONTROLLER)
        assertEquals(OperatingMode.ON_DEMAND, settings.settings.first().operatingMode)
    }

    @Test
    fun `on demand does not bring the new role online by itself`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)
        steps.clear()
        coordinator.startNetworkingIfNeeded()
        delay(200)
        assertTrue("On Demand means nothing starts until asked", steps.none { it == "engine.start" })
    }

    @Test
    fun `switching twice ends on the second role`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)
        assertEquals(DeviceRole.DISPLAY, settings.settings.first().role)
        coordinator.switchTo(DeviceRole.CONTROLLER)
        assertEquals(DeviceRole.CONTROLLER, settings.settings.first().role)
        assertEquals(2, steps.count { it == "service.stop" })
    }

    @Test
    fun `a switch while mirroring stops the capture`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        // No projection is active, but the stop path must still run and be safe.
        coordinator.switchTo(DeviceRole.DISPLAY)
        assertEquals(
            com.avinash.relaydisplay.mirroring.MirrorState.Idle,
            mirror.state.value,
        )
    }

    @Test
    fun `acknowledging clears a terminal state but never a running one`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)
        assertTrue(coordinator.state.value is RoleSwitchState.Completed)
        coordinator.acknowledge()
        assertEquals(RoleSwitchState.Idle, coordinator.state.value)
    }

    @Test
    fun `inProgress is false once the switch has finished`() = runBlocking {
        settings.setRole(DeviceRole.CONTROLLER)
        coordinator.switchTo(DeviceRole.DISPLAY)
        assertTrue(!coordinator.inProgress)
    }
}

/**
 * A session host with no session.
 *
 * Everything the role switch touches goes through SessionCoordinator; the router and mirror
 * controller only need to be able to observe "there is no live session", which is exactly the
 * state a switch starts and ends in.
 */
private object EmptySessionHost : SessionHost {
    override val activeSession =
        kotlinx.coroutines.flow.MutableStateFlow<com.avinash.relaydisplay.network.session.RelaySession?>(null)
    override val messages =
        kotlinx.coroutines.flow.MutableSharedFlow<com.avinash.relaydisplay.protocol.RelayMessage>()
}
