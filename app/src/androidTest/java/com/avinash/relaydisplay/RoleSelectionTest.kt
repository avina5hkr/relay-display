package com.avinash.relaydisplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avinash.relaydisplay.domain.model.DeviceRole
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleSelectionTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun reset() {
        RelayTestSupport.resetPersistedState()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @Test
    fun firstLaunchShowsTheRoleChooser() {
        compose.onNodeWithText("Choose this phone's role").assertIsDisplayed()
        compose.onNodeWithTag("role_confirm").assertIsNotEnabled()
    }

    @Test
    fun choosingControllerOpensTheControllerDashboard() {
        compose.onNodeWithText("Controller", substring = false).performClick()
        compose.onNodeWithTag("role_confirm").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.hasNodeWithTag("primary_action")
        }
        compose.onNodeWithText("Controller").assertIsDisplayed()
    }

    @Test
    fun choosingDisplayOpensTheDisplayDashboard() {
        compose.onNodeWithText("Companion display").performClick()
        compose.onNodeWithTag("role_confirm").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.hasNodeWithTag("display_pair")
        }
        compose.onNodeWithText("Companion display").assertIsDisplayed()
    }

    @Test
    fun theRoleSurvivesActivityRecreation() {
        runBlocking { RelayTestSupport.container.settingsRepository.setRole(DeviceRole.DISPLAY) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("display_pair") }
        // The chooser must not appear at all, not even briefly.
        compose.onNodeWithTag("role_confirm").assertDoesNotExist()
    }

    @Test
    fun aStoredRoleSkipsTheChooserOnLaunch() {
        runBlocking { RelayTestSupport.container.settingsRepository.setRole(DeviceRole.CONTROLLER) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("controller_pair") }
        compose.onNodeWithTag("role_confirm").assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** waitUntil needs a plain predicate; fetching semantics nodes is how you spell "is it there yet". */
internal fun androidx.compose.ui.test.junit4.ComposeTestRule.hasNodeWithTag(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
