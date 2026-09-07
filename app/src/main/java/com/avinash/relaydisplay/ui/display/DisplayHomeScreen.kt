package com.avinash.relaydisplay.ui.display

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
import com.avinash.relaydisplay.ui.common.ConnectionStatusCard
import com.avinash.relaydisplay.ui.appContainer
import com.avinash.relaydisplay.ui.common.ConnectionHero
import com.avinash.relaydisplay.ui.common.PermissionGate
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayTopBar
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.SectionHeader
import com.avinash.relaydisplay.ui.common.StatusRow
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.controller.modeLabel
import com.avinash.relaydisplay.platform.RelayPurpose
import com.avinash.relaydisplay.ui.relayViewModel

/**
 * The waiting screen on the companion phone.
 *
 * Deliberately static: no animations, no ticking clock, no gradients. Everything here is drawn
 * once and then costs nothing, because this phone is slow and is expected to sit on this screen
 * for long stretches.
 */
@Composable
fun DisplayHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DisplayViewModel = relayViewModel { DisplayViewModel.create(it) },
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // Modal, and outside the scrolling content: an offer waiting on an answer must not be
    // something the user can scroll away from without answering.
    ui.incomingBatch?.let { batch ->
        IncomingBatchDialog(
            batch = batch,
            onAccept = viewModel::acceptIncomingBatch,
            onReject = viewModel::rejectIncomingBatch,
        )
    }

    Column(modifier.fillMaxSize()) {
        // Pinned: settings is reachable without scrolling the page.
        RelayTopBar(
            title = "Companion display",
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
            ConnectionHero(state = ui.connection) {
                when {
                    ui.paused -> PrimaryAction(
                        text = "Resume RelayDisplay",
                        onClick = { viewModel.setOperatingMode(OperatingMode.ON_DEMAND) },
                        modifier = Modifier.testTag("primary_action"),
                    )
                    ui.available -> SecondaryAction(
                        text = "Stop being available",
                        onClick = viewModel::stopBeingAvailable,
                        modifier = Modifier.testTag("primary_action"),
                    )
                    else -> PrimaryAction(
                        text = "Make this phone available",
                        onClick = viewModel::makeAvailable,
                        modifier = Modifier.testTag("primary_action"),
                    )
                }
            }

            PermissionGate(
                purpose = RelayPurpose.LocalNetwork,
                platform = appContainer().platformCapabilities,
                modifier = Modifier.padding(top = RelayDimens.Gap),
            )

            VerticalGap(RelayDimens.Gap)
        SectionHeader("Pairing")
        RelayCard {
            val peer = ui.trustedPeer
            if (peer == null) {
                Text(
                    "Not paired yet. Show a pairing code here and scan it from the controller " +
                        "phone, or pair by comparing a six digit code.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                StatusRow("Paired with", peer.displayName)
                StatusRow("Fingerprint", peer.shortFingerprint)
            }
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction(
                text = if (peer == null) "Show pairing code" else "Pair another controller",
                onClick = onOpenPairing,
                modifier = Modifier.testTag("display_pair"),
            )
        }

        ReceivedFilesSection(
            files = ui.receivedFiles,
            onDelete = viewModel::deleteReceivedFile,
        )

        SectionHeader("Network")
        RelayCard {
            StatusRow("Connection type", ui.networkLabel.ifEmpty { "unknown" })
            StatusRow("This phone", ui.localAddress ?: "no local address")
            VerticalGap(RelayDimens.SmallGap)
            Text(
                "Both phones must be on the same Wi-Fi, or one must be using the other's hotspot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader("Availability")
        RelayCard {
            StatusRow("Mode", modeLabel(ui.settings.operatingMode))
            VerticalGap(RelayDimens.SmallGap)
            if (ui.paused) {
                SecondaryAction("Resume", onClick = { viewModel.setOperatingMode(OperatingMode.ON_DEMAND) })
            } else {
                SecondaryAction(
                    "Pause everything",
                    onClick = { viewModel.setOperatingMode(OperatingMode.PAUSED) },
                    modifier = Modifier.testTag("display_pause"),
                )
            }
        }

            VerticalGap(RelayDimens.ScreenPadding)
        }
    }
}
