package com.avinash.relaydisplay.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.app.RoleSwitchState
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDetailBar
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.SectionHeader
import com.avinash.relaydisplay.ui.common.StatusRow
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.relayViewModel

@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = relayViewModel { SettingsViewModel.create(it) },
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.settings

    var pendingRole by remember { mutableStateOf<DeviceRole?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(settings.localDeviceName) }
    LaunchedEffect(settings.localDeviceName) {
        if (nameDraft.isEmpty()) nameDraft = settings.localDeviceName
    }

    Column(modifier.fillMaxSize()) {
        RelayDetailBar(title = "Settings", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RelayDimens.ScreenPadding),
        ) {

        SectionHeader("This phone")
        RelayCard {
            StatusRow("Role", settings.role?.let { roleLabel(it) } ?: "Not chosen")
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(32) },
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RelayDimens.SmallGap)
                    .testTag("settings_device_name"),
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction("Save name", onClick = { viewModel.setDeviceName(nameDraft) })
            VerticalGap()
            // The switch state is rendered, not guessed: while it runs the control is disabled,
            // which is what stops the repeated taps the device logs showed.
            when (val switch = ui.roleSwitch) {
                is RoleSwitchState.Switching -> {
                    Text(
                        "Switching role: ${switch.step}...",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("role_switching"),
                    )
                }
                is RoleSwitchState.Completed -> {
                    Text(
                        if (switch.disconnected) {
                            "Role changed. The connection was closed, which is expected: the two " +
                                "phones must have opposite roles. Reconnect from the dashboard."
                        } else {
                            "Role changed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("role_switch_done"),
                    )
                    VerticalGap(RelayDimens.SmallGap)
                }
                is RoleSwitchState.Failed -> {
                    Text(
                        "The role change did not finish cleanly (${switch.reason}). Everything " +
                            "has been stopped; try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    VerticalGap(RelayDimens.SmallGap)
                }
                RoleSwitchState.Idle -> Unit
            }
            SecondaryAction(
                text = "Change device role",
                enabled = ui.roleControlsEnabled,
                onClick = { settings.role?.let { pendingRole = it.other } },
                modifier = Modifier.testTag("settings_change_role"),
            )
        }

        SectionHeader("Availability")
        RelayCard {
            Text(
                "How hard this phone works to stay reachable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalGap(RelayDimens.SmallGap)
            for (mode in OperatingMode.entries) {
                ModeOption(
                    mode = mode,
                    selected = settings.operatingMode == mode,
                    onSelect = { viewModel.setOperatingMode(mode) },
                )
            }
        }

        SectionHeader("Connection")
        RelayCard {
            SwitchRow(
                label = "Reconnect automatically",
                description = "Retry with a growing delay after an unexpected drop.",
                checked = settings.reconnectAutomatically,
                onCheckedChange = viewModel::setReconnect,
            )
        }

        SectionHeader("Presentation")
        RelayCard {
            SwitchRow(
                label = "Keep screen awake while presenting",
                description = "Released as soon as the session ends.",
                checked = settings.keepScreenAwake,
                onCheckedChange = viewModel::setKeepScreenAwake,
            )
            HorizontalDivider(Modifier.padding(vertical = RelayDimens.SmallGap))
            SwitchRow(
                label = "Full screen by default",
                description = "Hide the status and navigation bars while showing content.",
                checked = settings.immersiveByDefault,
                onCheckedChange = viewModel::setImmersive,
            )
            HorizontalDivider(Modifier.padding(vertical = RelayDimens.SmallGap))
            Text("Default scaling", style = MaterialTheme.typography.titleMedium)
            for (fit in FitMode.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RelayDimens.MinTouchTarget)
                        .selectable(
                            selected = settings.defaultFitMode == fit,
                            onClick = { viewModel.setFitMode(fit) },
                            role = SemanticsRole.RadioButton,
                        )
                        .testTag("fit_${fit.storageValue}"),
                ) {
                    RadioButton(selected = settings.defaultFitMode == fit, onClick = null)
                    Text(if (fit == FitMode.FIT) "Fit (show everything)" else "Fill (crop to edges)")
                }
            }
        }

        SectionHeader("Privacy")
        RelayCard {
            SwitchRow(
                label = "Blank immediately on disconnect",
                description = "Otherwise the last content stays for a few minutes.",
                checked = settings.blankOnDisconnect,
                onCheckedChange = viewModel::setBlankOnDisconnect,
            )
            HorizontalDivider(Modifier.padding(vertical = RelayDimens.SmallGap))
            SwitchRow(
                label = "Let the paired phone open links",
                description = "Off by default. A received link always needs a tap here first.",
                checked = settings.allowTrustedLinkOpen,
                onCheckedChange = viewModel::setAllowTrustedLinkOpen,
            )
        }

        SectionHeader("Paired device")
        RelayCard {
            val peer = ui.trustedPeer
            if (peer == null) {
                Text("No paired device yet.", style = MaterialTheme.typography.bodyLarge)
            } else {
                StatusRow("Name", peer.displayName)
                StatusRow("Fingerprint", peer.shortFingerprint)
                StatusRow("Last seen at", if (peer.hasEndpoint) "${peer.lastHost}:${peer.lastPort}" else "unknown")
                VerticalGap(RelayDimens.SmallGap)
                SecondaryAction(
                    text = "Forget this device",
                    onClick = { confirmForget = true },
                    modifier = Modifier.testTag("settings_forget_peer"),
                )
            }
        }

        SectionHeader("About")
        RelayCard {
            SecondaryAction("Diagnostics", onClick = onOpenDiagnostics)
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction("About and licences", onClick = onOpenAbout)
        }
            VerticalGap(RelayDimens.ScreenPadding)
        }
    }

    pendingRole?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRole = null },
            title = { Text("Change this phone to ${roleLabel(target)}?") },
            text = {
                Text(
                    "This stops discovery, any connection, transfers and screen sharing on this " +
                        "phone before switching. Your paired device is kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.changeRole(target)
                        pendingRole = null
                    },
                    modifier = Modifier.testTag("confirm_role_change"),
                ) { Text("Change role") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRole = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget the paired device?") },
            text = { Text("The connection ends now and you will need to pair again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetPeer()
                        confirmForget = false
                    },
                    modifier = Modifier.testTag("confirm_forget"),
                ) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModeOption(mode: OperatingMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RelayDimens.MinTouchTarget)
            // The whole row is the target, not just the radio dot. On a cracked screen a 20dp
            // circle is genuinely hard to hit, and a row that looks tappable but is not is worse
            // than one that is not styled at all.
            .selectable(selected = selected, onClick = onSelect, role = SemanticsRole.RadioButton)
            .padding(vertical = 4.dp)
            .testTag("mode_${mode.storageValue}"),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(modeTitle(mode), style = MaterialTheme.typography.titleMedium)
            Text(
                modeDescription(mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RelayDimens.MinTouchTarget)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = SemanticsRole.Switch),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

internal fun roleLabel(role: DeviceRole): String =
    if (role == DeviceRole.CONTROLLER) "Controller" else "Companion display"

private fun modeTitle(mode: OperatingMode): String = when (mode) {
    OperatingMode.ON_DEMAND -> "On demand (recommended)"
    OperatingMode.ALWAYS_READY -> "Always ready"
    OperatingMode.PAUSED -> "Paused"
}

private fun modeDescription(mode: OperatingMode): String = when (mode) {
    OperatingMode.ON_DEMAND -> "Nothing runs until you tap Connect. Lowest battery use."
    OperatingMode.ALWAYS_READY ->
        "Stays reachable behind an ongoing notification. Uses noticeably more battery, " +
            "especially on the older phone."
    OperatingMode.PAUSED -> "Stops discovery, connections, transfers and screen sharing entirely."
}
