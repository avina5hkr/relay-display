package com.avinash.relaydisplay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.OperatingMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs against a real Preferences DataStore backed by a temp file, so migrations, corrupt values
 * and defaults behave exactly as they will on device.
 */
class SettingsRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        // A real dispatcher: DataStore does its own IO, and a test dispatcher that only
        // advances on demand would deadlock the very first edit().
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(temp.newFolder(), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { file.toOkioPath() }
        repo = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults are safe before anything is written`() = runTest {
        val s = repo.settings.first()
        assertTrue("the store must report that it has loaded", s.loaded)
        assertNull("no role until the user picks one", s.role)
        assertFalse(s.roleChosen)
        assertEquals(OperatingMode.ON_DEMAND, s.operatingMode)
        assertEquals(FitMode.FIT, s.defaultFitMode)
        assertEquals(BRIGHTNESS_SYSTEM_DEFAULT, s.windowBrightness)
        assertTrue(s.reconnectAutomatically)
        assertFalse("opening peer links must be opt in", s.allowTrustedLinkOpen)
        assertNull(s.readError)
    }

    @Test
    fun `role round trips`() = runTest {
        repo.setRole(DeviceRole.DISPLAY)
        assertEquals(DeviceRole.DISPLAY, repo.settings.first().role)
        repo.setRole(DeviceRole.CONTROLLER)
        assertEquals(DeviceRole.CONTROLLER, repo.settings.first().role)
    }

    @Test
    fun `operating mode round trips`() = runTest {
        for (mode in OperatingMode.entries) {
            repo.setOperatingMode(mode)
            assertEquals(mode, repo.settings.first().operatingMode)
        }
    }

    @Test
    fun `a corrupt role reads back as not chosen instead of crashing`() = runTest {
        dataStore.edit { it[stringPreferencesKey("device_role")] = "PROJECTOR" }
        val s = repo.settings.first()
        assertNull(s.role)
        assertTrue(s.loaded)
    }

    @Test
    fun `a corrupt operating mode falls back to the default`() = runTest {
        dataStore.edit { it[stringPreferencesKey("operating_mode")] = "TURBO" }
        assertEquals(OperatingMode.ON_DEMAND, repo.settings.first().operatingMode)
    }

    @Test
    fun `a corrupt fit mode falls back to fit`() = runTest {
        dataStore.edit { it[stringPreferencesKey("default_fit_mode")] = "STRETCH" }
        assertEquals(FitMode.FIT, repo.settings.first().defaultFitMode)
    }

    @Test
    fun `changing role resets the operating mode but keeps presentation settings`() = runTest {
        repo.setRole(DeviceRole.DISPLAY)
        repo.setOperatingMode(OperatingMode.ALWAYS_READY)
        repo.setKeepScreenAwake(false)
        repo.setDefaultFitMode(FitMode.FILL)
        repo.setLocalDeviceName("Living room")

        repo.changeRole(DeviceRole.CONTROLLER)

        val s = repo.settings.first()
        assertEquals(DeviceRole.CONTROLLER, s.role)
        assertEquals("a fresh role must not start out advertising", OperatingMode.ON_DEMAND, s.operatingMode)
        assertFalse(s.keepScreenAwake)
        assertEquals(FitMode.FILL, s.defaultFitMode)
        assertEquals("Living room", s.localDeviceName)
    }

    @Test
    fun `the device id is generated once and then reused`() = runTest {
        var calls = 0
        val first = repo.ensureLocalDeviceId { calls++; "generated-$calls" }
        val second = repo.ensureLocalDeviceId { calls++; "generated-$calls" }
        assertEquals(first, second)
        assertEquals(1, calls)
        assertEquals(first, repo.settings.first().localDeviceId)
    }

    @Test
    fun `device names are trimmed and bounded`() = runTest {
        repo.setLocalDeviceName("   " + "x".repeat(100) + "   ")
        assertEquals(SettingsRepository.MAX_DEVICE_NAME_CHARS, repo.settings.first().localDeviceName.length)
    }

    @Test
    fun `brightness is clamped and keeps its system sentinel`() = runTest {
        repo.setWindowBrightness(250)
        assertEquals(100, repo.settings.first().windowBrightness)
        repo.setWindowBrightness(-50)
        assertEquals(0, repo.settings.first().windowBrightness)
        repo.setWindowBrightness(BRIGHTNESS_SYSTEM_DEFAULT)
        assertEquals(BRIGHTNESS_SYSTEM_DEFAULT, repo.settings.first().windowBrightness)
    }

    @Test
    fun `retain seconds is clamped to a day`() = runTest {
        repo.setRetainContentSeconds(999_999)
        assertEquals(24 * 3600, repo.settings.first().retainContentSeconds)
        repo.setRetainContentSeconds(-5)
        assertEquals(0, repo.settings.first().retainContentSeconds)
    }

    @Test
    fun `settings survive a new repository over the same file`() = runTest {
        repo.setRole(DeviceRole.DISPLAY)
        repo.setOperatingMode(OperatingMode.ALWAYS_READY)
        // Same backing file, fresh store: this is what a process restart looks like.
        val reopened = SettingsRepository(dataStore)
        val s = reopened.settings.first()
        assertEquals(DeviceRole.DISPLAY, s.role)
        assertEquals(OperatingMode.ALWAYS_READY, s.operatingMode)
    }

    @Test
    fun `onboarding flags round trip`() = runTest {
        repo.setOnboardingSeen(true)
        repo.setNotificationRationaleSeen(true)
        repo.setLocalNetworkRationaleSeen(true)
        val s = repo.settings.first()
        assertTrue(s.onboardingSeen)
        assertTrue(s.notificationRationaleSeen)
        assertTrue(s.localNetworkRationaleSeen)
    }
}
