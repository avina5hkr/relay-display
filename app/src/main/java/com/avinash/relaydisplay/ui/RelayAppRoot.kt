package com.avinash.relaydisplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avinash.relaydisplay.content.PresentedContent
import com.avinash.relaydisplay.data.settings.RelaySettings
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.ui.controller.ControllerHomeScreen
import com.avinash.relaydisplay.ui.controller.SendScreen
import com.avinash.relaydisplay.ui.display.DisplayHomeScreen
import com.avinash.relaydisplay.ui.display.PresentationSurface
import com.avinash.relaydisplay.ui.navigation.Screen
import com.avinash.relaydisplay.ui.navigation.SendFocus
import com.avinash.relaydisplay.ui.navigation.rememberRelayNavigator
import com.avinash.relaydisplay.ui.onboarding.RoleChooserScreen
import com.avinash.relaydisplay.ui.pairing.PairingScreen
import com.avinash.relaydisplay.ui.pairing.QrScannerScreen
import com.avinash.relaydisplay.ui.settings.AboutScreen
import com.avinash.relaydisplay.ui.settings.DiagnosticsScreen
import com.avinash.relaydisplay.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/** What arrived through an ACTION_SEND intent, after validation. */
data class SharedPayload(val text: String?)

/**
 * The root of the UI.
 *
 * Two things matter here. First, the launch gate: nothing draws until the settings store has
 * reported once, so an already-configured phone never flashes the role chooser. Second, the
 * display's presentation takes over the whole screen the moment there is content to show, and
 * gives it back the moment there is not.
 */
@Composable
fun RelayAppRoot(
    sharedPayload: SharedPayload? = null,
    onSharedPayloadConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val container = appContainer()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = RelaySettings())
    val scope = rememberCoroutineScope()

    if (!settings.loaded) {
        // A frame or two at most. No spinner: a flash of motion reads worse than a blank surface.
        Box(modifier.fillMaxSize().testTag("loading_gate"))
        return
    }

    val role = settings.role
    val navigator = rememberRelayNavigator(initial = homeFor(role))
    // Rebuilds the whole stack, not just the top. Changing the role from Settings used to leave
    // the previous role's dashboard sitting underneath, so Back landed the user right back on it.
    LaunchedEffect(role) { navigator.rebaseForRole(role) }

    // A share arriving in Display role must not silently flip the role; it offers the change.
    var shareInWrongRole by remember { mutableStateOf(false) }
    LaunchedEffect(sharedPayload, role) {
        val payload = sharedPayload ?: return@LaunchedEffect
        when (role) {
            DeviceRole.CONTROLLER ->
                if (!payload.text.isNullOrBlank()) navigator.navigateTo(Screen.Send(SendFocus.TEXT))
            DeviceRole.DISPLAY -> shareInWrongRole = true
            null -> Unit
        }
    }

    val connection by container.sessionCoordinator.state.collectAsStateWithLifecycle()
    val presentation by container.presentationController.state.collectAsStateWithLifecycle()

    // The display goes full screen only for real content, and only during a live session or the
    // retention window after one.
    val shouldPresent = role == DeviceRole.DISPLAY &&
        presentation.content != PresentedContent.Waiting &&
        navigator.current == Screen.DisplayHome

    if (shouldPresent) {
        PresentationSurface(
            content = presentation.content,
            options = presentation.options,
            disconnected = presentation.disconnectedOverlay,
            // Local close: frees the screen immediately and reports to the Controller. If the
            // link is down the local close still happens and reconciles on reconnect.
            onExitRequested = { container.contentRouter.dismissLocally() },
            modifier = modifier,
            mirrorController = container.mirrorController,
        )
        return
    }

    BackHandler(enabled = navigator.canGoBack) { navigator.goBack() }

    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (navigator.current) {
                Screen.RoleChooser -> RoleChooserScreen(
                    onRoleChosen = { chosen ->
                        scope.launch {
                            container.settingsRepository.setRole(chosen)
                            container.settingsRepository.setOnboardingSeen(true)
                        }
                    },
                )

                Screen.ControllerHome -> ControllerHomeScreen(
                    onOpenSettings = { navigator.navigateTo(Screen.Settings) },
                    onOpenPairing = { navigator.navigateTo(Screen.Pairing) },
                    onOpenSend = { focus -> navigator.navigateTo(Screen.Send(focus)) },
                )

                Screen.DisplayHome -> DisplayHomeScreen(
                    onOpenSettings = { navigator.navigateTo(Screen.Settings) },
                    onOpenPairing = { navigator.navigateTo(Screen.Pairing) },
                )

                Screen.Pairing -> PairingScreen(
                    onOpenScanner = { navigator.navigateTo(Screen.ScanPairingCode) },
                    onDone = { navigator.resetTo(homeFor(role)) },
                )

                Screen.ScanPairingCode -> QrScannerScreen(
                    onCancel = { navigator.goBack() },
                    onAccepted = { navigator.resetTo(homeFor(role)) },
                )

                is Screen.Send -> SendScreen(
                    focus = (navigator.current as Screen.Send).focus,
                    initialText = sharedPayload?.text,
                    onBack = {
                        onSharedPayloadConsumed()
                        navigator.goBack()
                    },
                )

                Screen.Settings -> SettingsScreen(
                    onOpenDiagnostics = { navigator.navigateTo(Screen.Diagnostics) },
                    onOpenAbout = { navigator.navigateTo(Screen.About) },
                    onBack = { navigator.goBack() },
                )

                Screen.Diagnostics -> DiagnosticsScreen(onBack = { navigator.goBack() })

                Screen.About -> AboutScreen(onBack = { navigator.goBack() })

                Screen.Presentation -> PresentationSurface(
                    content = presentation.content,
                    options = presentation.options,
                    disconnected = presentation.disconnectedOverlay,
                    onExitRequested = {
                        container.contentRouter.dismissLocally()
                        navigator.goBack()
                    },
                    mirrorController = container.mirrorController,
                )
            }
        }
    }

    if (shareInWrongRole) {
        AlertDialog(
            onDismissRequest = {
                shareInWrongRole = false
                onSharedPayloadConsumed()
            },
            title = { Text("This phone is the display") },
            text = {
                Text(
                    "Sending happens from the controller phone. You can switch this phone to " +
                        "Controller in Settings, but then it will stop being the display.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    shareInWrongRole = false
                    onSharedPayloadConsumed()
                    navigator.navigateTo(Screen.Settings)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    shareInWrongRole = false
                    onSharedPayloadConsumed()
                }) { Text("Not now") }
            },
        )
    }

    // Keeps the connection state referenced so a state change recomposes the dashboards even
    // when the presentation branch above is not taken.
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Failed && navigator.current == Screen.ScanPairingCode) {
            navigator.goBack()
        }
    }
}

private fun homeFor(role: DeviceRole?): Screen = when (role) {
    null -> Screen.RoleChooser
    DeviceRole.CONTROLLER -> Screen.ControllerHome
    DeviceRole.DISPLAY -> Screen.DisplayHome
}
