package com.avinash.relaydisplay.ui.controller

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.ui.common.ConnectionStatusCard
import com.avinash.relaydisplay.ui.appContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import com.avinash.relaydisplay.ui.common.ActionTile
import com.avinash.relaydisplay.ui.common.ConnectionHero
import androidx.compose.ui.Alignment
import com.avinash.relaydisplay.mirroring.MirrorState
import com.avinash.relaydisplay.ui.common.RelayGlyph
import com.avinash.relaydisplay.ui.common.RelayIcon
import com.avinash.relaydisplay.ui.common.RelaySection
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.avinash.relaydisplay.service.MirrorProjectionService
import com.avinash.relaydisplay.ui.navigation.SendFocus
import com.avinash.relaydisplay.ui.common.PermissionGate
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayTopBar
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.SectionHeader
import com.avinash.relaydisplay.ui.common.StatusRow
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.platform.RelayPurpose
import com.avinash.relaydisplay.ui.relayViewModel

/**
 * The controller dashboard.
 *
 * Laid out for a cracked screen: one full-width primary action, generous vertical spacing, no
 * control that only exists at an edge, and nothing that claims a connection it does not have.
 */
@Composable
fun ControllerHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenSend: (SendFocus) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControllerViewModel = relayViewModel { ControllerViewModel.create(it) },
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mirrorState by appContainer().mirrorController.state.collectAsStateWithLifecycle()

    // The same instance the send screen uses, keyed identically, so a file picked here and a
    // file picked there run through one code path and report progress in one place.
    val sendViewModel: SendViewModel = relayViewModel(key = "send") {
        SendViewModel.create(it, context.contentResolver)
    }

    // Image, PDF and screen sharing act immediately. Routing them through a screen first would
    // be an extra tap that buys the user nothing: the very next thing they see is a system
    // picker or a consent dialog either way.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendViewModel.sendPickedFile(uri, ui.settings.defaultFitMode)
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendViewModel.sendPickedFile(uri, ui.settings.defaultFitMode)
    }
    val projectionConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            MirrorProjectionService.start(context, result.resultCode, data)
        }
    }

    Column(modifier.fillMaxSize()) {
        // Pinned: settings is reachable without scrolling the page.
        RelayTopBar(
            title = "Controller",
            subtitle = ui.settings.localDeviceName.ifEmpty { "This phone" },
            onOpenSettings = onOpenSettings,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RelayDimens.ScreenPadding),
        ) {

            VerticalGap(RelayDimens.SmallGap)
            // The visual anchor: colour carries the state before any text is read.
            ConnectionHero(state = ui.connection) {
                PrimaryActionForState(ui = ui, viewModel = viewModel, onOpenPairing = onOpenPairing)
            }

            // Stopping a screen share was only reachable from the send screen, several taps and a
            // scroll away. Anything that is recording the user's screen needs a stop control they
            // can find while it is happening, so it sits directly under the status card and only
            // while capture is actually running.
            if (mirrorState is MirrorState.Active || mirrorState is MirrorState.Starting) {
                VerticalGap(RelayDimens.SmallGap)
                StopSharingCard(
                    state = mirrorState,
                    onStop = { MirrorProjectionService.stop(context) },
                )
            }

            // Only renders on Android versions that actually gate local network access.
            PermissionGate(
                purpose = RelayPurpose.LocalNetwork,
                platform = appContainer().platformCapabilities,
                modifier = Modifier.padding(top = RelayDimens.Gap),
            )

            VerticalGap(RelayDimens.Gap)
            // Deliberately not wrapped in a card: tiles are already surfaces, and nesting them
            // inside another one produced a muddy card-within-card with no clear edges.
            Text(
                "Send to the display",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = RelayDimens.SmallGap),
            )
            Column {
                if (!ui.canSendContent) {
                    Text(
                        "Connect first. Sending needs a verified live session, so these stay off " +
                            "until there is one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = RelayDimens.SmallGap),
                    )
                }
                // Two columns of tiles: six choices in the space two buttons used to take, and
                // scannable by shape rather than by reading four similar labels.
                //
                // Each tile now does what its label says: the three text-shaped kinds open the
                // composer on that kind, and the three file-shaped ones go straight to the
                // system picker or consent dialog.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(
                        icon = RelayIcon.QR,
                        label = "QR code",
                        onClick = { onOpenSend(SendFocus.QR) },
                        enabled = ui.canSendContent,
                        testTag = "tile_qr",
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = RelayIcon.TEXT,
                        label = "Text",
                        onClick = { onOpenSend(SendFocus.TEXT) },
                        enabled = ui.canSendContent,
                        testTag = "tile_text",
                        modifier = Modifier.weight(1f),
                    )
                }
                VerticalGap(10.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(
                        icon = RelayIcon.LINK,
                        label = "Link",
                        onClick = { onOpenSend(SendFocus.LINK) },
                        enabled = ui.canSendContent,
                        testTag = "tile_link",
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = RelayIcon.IMAGE,
                        label = "Image",
                        onClick = { pickImage.launch(arrayOf("image/*")) },
                        enabled = ui.canSendContent,
                        testTag = "tile_image",
                        modifier = Modifier.weight(1f),
                    )
                }
                VerticalGap(10.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(
                        icon = RelayIcon.DOCUMENT,
                        label = "PDF",
                        onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                        enabled = ui.canSendContent,
                        testTag = "tile_pdf",
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = RelayIcon.SCREEN_SHARE,
                        label = "Share screen",
                        onClick = { projectionConsent.launch(screenCaptureIntent(context)) },
                        enabled = ui.canSendContent,
                        testTag = "tile_mirror",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            VerticalGap(RelayDimens.Gap)
        SectionHeader("Paired display")
        RelayCard {
            val peer = ui.trustedPeer
            if (peer == null) {
                Text(
                    "No display paired yet. Pair once and this phone will reconnect on its own " +
                        "after that.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                VerticalGap(RelayDimens.SmallGap)
                SecondaryAction(
                    "Pair a display",
                    onClick = onOpenPairing,
                    modifier = Modifier.testTag("controller_pair"),
                )
            } else {
                StatusRow("Name", peer.displayName)
                StatusRow("Fingerprint", peer.shortFingerprint)
            }
        }

        SectionHeader("Network")
        RelayCard {
            StatusRow("Connection type", ui.networkLabel.ifEmpty { "unknown" })
            StatusRow("This phone", ui.localAddress ?: "no local address")
        }

        SectionHeader("Availability")
        RelayCard {
            StatusRow("Mode", modeLabel(ui.settings.operatingMode))
            VerticalGap(RelayDimens.SmallGap)
            if (ui.paused) {
                SecondaryAction(
                    "Resume",
                    onClick = { viewModel.setOperatingMode(OperatingMode.ON_DEMAND) },
                    modifier = Modifier.testTag("controller_resume"),
                )
            } else {
                SecondaryAction(
                    "Pause everything",
                    onClick = { viewModel.setOperatingMode(OperatingMode.PAUSED) },
                    modifier = Modifier.testTag("controller_pause"),
                )
            }
        }

            VerticalGap(RelayDimens.ScreenPadding)
        }
    }
}

@Composable
private fun PrimaryActionForState(
    ui: ControllerUiState,
    viewModel: ControllerViewModel,
    onOpenPairing: () -> Unit,
) {
    when {
        ui.paused -> PrimaryAction(
            text = "Resume RelayDisplay",
            onClick = { viewModel.setOperatingMode(OperatingMode.ON_DEMAND) },
            modifier = Modifier.testTag("primary_action"),
        )

        !ui.paired -> PrimaryAction(
            text = "Pair a display",
            onClick = onOpenPairing,
            modifier = Modifier.testTag("primary_action"),
        )

        ui.connection is ConnectionState.Connected -> PrimaryAction(
            text = "Disconnect",
            onClick = viewModel::disconnect,
            modifier = Modifier.testTag("primary_action"),
        )

        ui.connection is ConnectionState.Reconnecting -> PrimaryAction(
            text = "Reconnect now",
            onClick = viewModel::reconnectNow,
            modifier = Modifier.testTag("primary_action"),
        )

        ui.connection.isActive -> SecondaryAction(
            text = "Cancel",
            onClick = viewModel::disconnect,
            modifier = Modifier.testTag("primary_action"),
        )

        else -> PrimaryAction(
            text = "Connect to ${ui.trustedPeer?.displayName ?: "display"}",
            onClick = viewModel::connect,
            modifier = Modifier.testTag("primary_action"),
        )
    }
}

internal fun modeLabel(mode: OperatingMode): String = when (mode) {
    OperatingMode.ON_DEMAND -> "On demand"
    OperatingMode.ALWAYS_READY -> "Always ready"
    OperatingMode.PAUSED -> "Paused"
}

/**
 * The screen-sharing stop control.
 *
 * Deliberately loud: it uses the error container, because a capture the user has lost track of is
 * a privacy problem, not a neutral status. It states what is being shared so the row is not just
 * a button with no context, and it is the only control on this screen that is destructive.
 */
@Composable
private fun StopSharingCard(state: MirrorState, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    RelayCard(container = scheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RelayGlyph(RelayIcon.SCREEN_SHARE, tint = scheme.onErrorContainer, size = 26.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "Sharing this screen",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onErrorContainer,
                )
                Text(
                    when (state) {
                        is MirrorState.Active ->
                            "${state.profile.width}x${state.profile.height}, ${state.profile.frameRate} fps"
                        else -> "Starting"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
        VerticalGap(RelayDimens.SmallGap)
        PrimaryAction(
            "Stop sharing",
            onClick = onStop,
            modifier = Modifier.testTag("stop_sharing_home"),
        )
    }
}
