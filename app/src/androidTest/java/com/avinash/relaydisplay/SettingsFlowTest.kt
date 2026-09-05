package com.avinash.relaydisplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.network.session.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings behaviour that only makes sense on a device: the confirmation dialog, mode
 * persistence, and what Pause actually stops.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val settings get() = RelayTestSupport.container.settingsRepository
    private val coordinator get() = RelayTestSupport.container.sessionCoordinator

    @Before
    fun reset() {
        RelayTestSupport.resetPersistedState()
        runBlocking { settings.setRole(DeviceRole.CONTROLLER) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @Test
    fun changingRoleRequiresConfirmation() {
        openSettings()
        compose.onNodeWithTag("settings_change_role").performScrollTo().performClick()
        compose.waitForIdle()

        // The dialog is up and the role has not moved yet.
        compose.onNodeWithTag("confirm_role_change").assertIsDisplayed()
        assertEquals(DeviceRole.CONTROLLER, runBlocking { settings.settings.first().role })

        compose.onNodeWithTag("confirm_role_change").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { settings.settings.first().role } == DeviceRole.DISPLAY
        }
    }

    @Test
    fun changingRoleResetsTheModeButKeepsOtherSettings() {
        runBlocking {
            settings.setOperatingMode(OperatingMode.ALWAYS_READY)
            settings.setLocalDeviceName("Test Phone")
        }
        openSettings()
        compose.onNodeWithTag("settings_change_role").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_role_change").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { settings.settings.first().role } == DeviceRole.DISPLAY
        }

        val after = runBlocking { settings.settings.first() }
        assertEquals("a fresh role must not start out advertising", OperatingMode.ON_DEMAND, after.operatingMode)
        assertEquals("unrelated settings must survive", "Test Phone", after.localDeviceName)
    }

    @Test
    fun theOperatingModeSurvivesRecreation() {
        openSettings()
        compose.onNodeWithTag("mode_ALWAYS_READY").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { settings.settings.first().operatingMode } == OperatingMode.ALWAYS_READY
        }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(
            OperatingMode.ALWAYS_READY,
            runBlocking { settings.settings.first().operatingMode },
        )
    }

    @Test
    fun pauseStopsEverythingAndSurvivesRecreation() {
        coordinator.requestSession()
        compose.waitForIdle()

        openSettings()
        compose.onNodeWithTag("mode_PAUSED").performScrollTo().performClick()

        compose.waitUntil(TIMEOUT_MS) { coordinator.state.value == ConnectionState.Paused }
        assertFalse("Pause must clear the intent to hold a session", coordinator.sessionIntended.value)
        assertNull(
            "Pause must leave no listening socket",
            RelayTestSupport.container.engine.listeningPort.value,
        )

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(OperatingMode.PAUSED, runBlocking { settings.settings.first().operatingMode })
        assertEquals(ConnectionState.Paused, coordinator.state.value)
    }

    @Test
    fun forgettingThePeerRequiresConfirmationAndEndsTheSession() {
        runBlocking { RelayTestSupport.saveFakePeer() }
        openSettings()
        compose.onNodeWithTag("settings_forget_peer").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_forget").assertIsDisplayed()
        compose.onNodeWithTag("confirm_forget").performClick()

        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { RelayTestSupport.container.trustedPeerRepository.current() } == null
        }
        assertFalse(coordinator.sessionIntended.value)
    }

    /**
     * The exact sequence the user reported: Settings, change role, come back.
     *
     * Before the fix the role in storage changed correctly but the back stack still had the old
     * role's dashboard at its root, so pressing Back showed the previous role and it looked as
     * though nothing had happened.
     */
    @Test
    fun goingBackAfterARoleChangeShowsTheNewRole() {
        openSettings()
        compose.onNodeWithTag("settings_change_role").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_role_change").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { settings.settings.first().role } == DeviceRole.DISPLAY
        }
        compose.waitForIdle()

        // Come back the way the user did.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()

        // The companion display dashboard, not the controller one it replaced.
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("display_pair") }
        compose.onNodeWithTag("controller_pair").assertDoesNotExist()
    }

    /**
     * Recreation after a role change restores where the user was, and Back still leads to the
     * new role's dashboard.
     *
     * The user stays on Settings, which is the correct restore behaviour -- the point being
     * tested is that the *root beneath it* survived the rebase as the new role's home, not the
     * old one.
     */
    @Test
    fun theRebasedStackSurvivesRecreation() {
        openSettings()
        compose.onNodeWithTag("settings_change_role").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_role_change").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { settings.settings.first().role } == DeviceRole.DISPLAY
        }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        // Still in Settings, as a restore should be.
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("settings_change_role") }

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()

        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("display_pair") }
        compose.onNodeWithTag("controller_pair").assertDoesNotExist()
        assertEquals(DeviceRole.DISPLAY, runBlocking { settings.settings.first().role })
    }

    private fun openSettings() {
        // No performScrollTo: settings lives in a pinned top bar now, not at the bottom of the
        // scrolling body, and performScrollTo throws for a node with no scrollable ancestor.
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
