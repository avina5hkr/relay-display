package com.avinash.relaydisplay

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avinash.relaydisplay.domain.model.DeviceRole
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard header.
 *
 * Settings used to be a full-width button at the bottom of a long scroll. These pin that it is
 * now reachable without scrolling at all, on both roles.
 */
@RunWith(AndroidJUnit4::class)
class DashboardChromeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun reset() {
        RelayTestSupport.resetPersistedState()
    }

    private fun launchAs(role: DeviceRole) {
        runBlocking { RelayTestSupport.container.settingsRepository.setRole(role) }
        compose.activityRule.scenario.recreate()
        // recreate() returns once the Activity is RESUMED, but on a busy device the Compose host
        // may not have attached its semantics tree yet -- which surfaces as an intermittent
        // "No compose hierarchies found". onActivity blocks until the new instance is really
        // there, and moveToState pins it, giving a sync point instead of a hopeful waitForIdle.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.onActivity { }
        compose.waitForIdle()
    }

    @Test
    fun theControllerDashboardHasASettingsIconWithoutScrolling() {
        launchAs(DeviceRole.CONTROLLER)
        // No performScrollTo anywhere: if this needed scrolling the assertion would fail.
        compose.onNodeWithTag("open_settings").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun theDisplayDashboardHasASettingsIconWithoutScrolling() {
        launchAs(DeviceRole.DISPLAY)
        compose.onNodeWithTag("open_settings").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun theSettingsIconIsLabelledAndBigEnoughToHit() {
        launchAs(DeviceRole.CONTROLLER)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithTag("open_settings")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tappingTheIconOpensSettings() {
        launchAs(DeviceRole.CONTROLLER)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("settings_change_role") }
        compose.onNodeWithTag("settings_change_role").assertIsDisplayed()
    }

    @Test
    fun theBackControlOnSettingsIsAtTheTop() {
        launchAs(DeviceRole.CONTROLLER)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("nav_back") }
        // Reachable without scrolling: it used to be a button at the very bottom of the page.
        compose.onNodeWithTag("nav_back").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun backFromSettingsReturnsToTheDashboard() {
        launchAs(DeviceRole.CONTROLLER)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("nav_back") }
        compose.onNodeWithTag("nav_back").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.hasNodeWithTag("open_settings") }
        compose.onNodeWithTag("open_settings").assertIsDisplayed()
    }

    @Test
    fun theSendTilesAreVisibleAndDisabledWhileDisconnected() {
        launchAs(DeviceRole.CONTROLLER)
        // Six choices, all on screen, all labelled. Disabled because nothing is connected.
        for (tag in listOf("tile_qr", "tile_text", "tile_link", "tile_image", "tile_pdf", "tile_mirror")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithContentDescription("QR code").assertExists()
        compose.onNodeWithContentDescription("Share screen").assertExists()
    }

    @Test
    fun theHeaderStaysPutWhileTheBodyScrolls() {
        launchAs(DeviceRole.CONTROLLER)
        // Scroll the body to something near the bottom, then check the header is still there.
        compose.onNodeWithTag("controller_pause").performScrollTo()
        compose.onNodeWithTag("open_settings").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
