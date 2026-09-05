package com.avinash.relaydisplay.ui

import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.ui.navigation.RelayNavigator
import com.avinash.relaydisplay.ui.navigation.Screen
import com.avinash.relaydisplay.ui.navigation.SendFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Route mapping.
 *
 * `fromRoute` is the very first thing touched when `rememberSaveable` restores the back stack
 * after process death. It used to look up a `listOf(...)` held in the companion, which was a
 * class-initialisation-order trap: on a real device, Activity recreation produced
 * `NullPointerException ... Screen.getRoute() on a null object reference`. These tests call it
 * before anything else in the class is touched, which is the shape that broke.
 */
class ScreenRouteTest {

    @Test
    fun `every route resolves without touching a screen object first`() {
        // Deliberately string literals, not Screen.X.route: referencing an object here would
        // initialise it and mask the ordering bug this test exists to catch.
        for (route in listOf(
            "role", "controller", "display", "pairing", "scan",
            "send-text", "send-link", "presentation", "settings", "diagnostics", "about",
        )) {
            assertNotNull("route '$route' did not resolve", Screen.fromRoute(route))
        }
    }

    @Test
    fun `a resolved screen reports the route it was found by`() {
        assertEquals("controller", Screen.fromRoute("controller")?.route)
        assertEquals("display", Screen.fromRoute("display")?.route)
    }

    @Test
    fun `an unknown route is null rather than a crash`() {
        assertNull(Screen.fromRoute("nonsense"))
        assertNull(Screen.fromRoute(""))
    }

    @Test
    fun `routes are unique`() {
        val routes = listOf(
            Screen.RoleChooser, Screen.ControllerHome, Screen.DisplayHome, Screen.Pairing,
            Screen.ScanPairingCode, Screen.Send(SendFocus.TEXT), Screen.Send(SendFocus.QR), Screen.Send(SendFocus.LINK), Screen.Presentation,
            Screen.Settings, Screen.Diagnostics, Screen.About,
        ).map { it.route }
        assertEquals("two screens share a route", routes.size, routes.toSet().size)
    }
}

/**
 * Back-stack behaviour across a role change.
 *
 * Reported from the device: change role in Settings, press Back, and the *old* role's dashboard
 * is still there. The role in storage had changed correctly; the navigation stack had not.
 */
class RoleNavigationTest {

    private fun navigatorAt(vararg screens: Screen): RelayNavigator {
        val navigator = RelayNavigator(screens.first())
        screens.drop(1).forEach(navigator::navigateTo)
        return navigator
    }

    @Test
    fun `changing role from settings replaces the old role home beneath it`() {
        val navigator = navigatorAt(Screen.ControllerHome, Screen.Settings)
        navigator.rebaseForRole(DeviceRole.DISPLAY)

        // The user stays where they are: Settings is role-agnostic and shows the confirmation.
        assertEquals(Screen.Settings, navigator.current)
        // But Back must now lead to the *new* role's dashboard, not the old one.
        assertTrue(navigator.goBack())
        assertEquals(Screen.DisplayHome, navigator.current)
    }

    @Test
    fun `changing role the other way does the same`() {
        val navigator = navigatorAt(Screen.DisplayHome, Screen.Settings)
        navigator.rebaseForRole(DeviceRole.CONTROLLER)
        navigator.goBack()
        assertEquals(Screen.ControllerHome, navigator.current)
    }

    @Test
    fun `role specific destinations above settings are dropped`() {
        // Controller-only screens must not survive a switch to Display.
        val navigator = navigatorAt(Screen.ControllerHome, Screen.Send(SendFocus.TEXT), Screen.Settings)
        navigator.rebaseForRole(DeviceRole.DISPLAY)
        navigator.goBack()
        assertEquals("Send belongs to the controller and must be gone", Screen.DisplayHome, navigator.current)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `role agnostic destinations are preserved in order`() {
        val navigator = navigatorAt(Screen.ControllerHome, Screen.Settings, Screen.About)
        navigator.rebaseForRole(DeviceRole.DISPLAY)
        assertEquals(Screen.About, navigator.current)
        navigator.goBack()
        assertEquals(Screen.Settings, navigator.current)
        navigator.goBack()
        assertEquals(Screen.DisplayHome, navigator.current)
    }

    @Test
    fun `a same-role rebase preserves a restored stack`() {
        // This path also runs on the first composition after process death. Dropping every
        // role-specific destination here would silently discard the user's restored screen.
        val navigator = navigatorAt(Screen.ControllerHome, Screen.Send(SendFocus.TEXT))
        navigator.rebaseForRole(DeviceRole.CONTROLLER)
        assertEquals(Screen.Send(SendFocus.TEXT), navigator.current)
        navigator.goBack()
        assertEquals(Screen.ControllerHome, navigator.current)
    }

    @Test
    fun `rebasing to the same role is a no-op`() {
        val navigator = navigatorAt(Screen.ControllerHome, Screen.Settings)
        navigator.rebaseForRole(DeviceRole.CONTROLLER)
        assertEquals(Screen.Settings, navigator.current)
        navigator.goBack()
        assertEquals(Screen.ControllerHome, navigator.current)
    }

    @Test
    fun `choosing a first role leaves the chooser behind`() {
        val navigator = navigatorAt(Screen.RoleChooser)
        navigator.rebaseForRole(DeviceRole.CONTROLLER)
        assertEquals(Screen.ControllerHome, navigator.current)
        assertFalse("the chooser must not be reachable by Back", navigator.canGoBack)
    }

    @Test
    fun `clearing the role returns to the chooser`() {
        val navigator = navigatorAt(Screen.DisplayHome, Screen.Settings)
        navigator.rebaseForRole(null)
        assertEquals(Screen.RoleChooser, navigator.current)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `a presentation destination never survives a switch to controller`() {
        val navigator = navigatorAt(Screen.DisplayHome, Screen.Presentation)
        navigator.rebaseForRole(DeviceRole.CONTROLLER)
        assertEquals(Screen.ControllerHome, navigator.current)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `the rebased stack survives a save and restore`() {
        val navigator = navigatorAt(Screen.ControllerHome, Screen.Settings)
        navigator.rebaseForRole(DeviceRole.DISPLAY)
        val restored = RelayNavigator(Screen.RoleChooser).apply { restore(navigator.routes()) }
        assertEquals(Screen.Settings, restored.current)
        restored.goBack()
        assertEquals(Screen.DisplayHome, restored.current)
    }
}
