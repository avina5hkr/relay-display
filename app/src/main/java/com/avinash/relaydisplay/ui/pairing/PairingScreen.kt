package com.avinash.relaydisplay.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.PairingStage
import com.avinash.relaydisplay.ui.common.ConnectionHero
import com.avinash.relaydisplay.ui.common.PairingCodeView
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.RelayDetailBar
import com.avinash.relaydisplay.ui.common.RelayGlyph
import com.avinash.relaydisplay.ui.common.RelayIcon
import com.avinash.relaydisplay.ui.common.RelaySection
import com.avinash.relaydisplay.ui.common.StatusRow
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.relayViewModel

@Composable
fun PairingScreen(
    onOpenScanner: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = relayViewModel { PairingViewModel.create(it) },
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val role = ui.settings.role

    // Once a session is live and trusted, pairing is finished and the screen steps aside.
    LaunchedEffect(ui.connection) {
        if (ui.connection is ConnectionState.Connected) onDone()
    }

    // Only the controller half of this screen shows a list of displays, and the search stops the
    // moment the screen goes away.
    if (role == DeviceRole.CONTROLLER) {
        DisposableEffect(Unit) {
            viewModel.startBrowsing()
            onDispose { viewModel.stopBrowsing() }
        }
    }

    // Leaving this screen abandons the attempt, whichever way you leave it. Before, the Cancel
    // button tore the attempt down and the system Back gesture quietly left it running, so a
    // half-finished handshake could outlive the screen that started it.
    val abandon = { viewModel.cancel(); onDone() }
    BackHandler { abandon() }

    Column(modifier.fillMaxSize()) {
        RelayDetailBar(title = "Pair devices", onBack = abandon)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RelayDimens.ScreenPadding),
        ) {
            ConnectionHero(state = ui.connection)
            VerticalGap()

            if (!ui.identityAvailable) {
                RelayCard(container = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "Pairing is unavailable",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    VerticalGap(RelayDimens.SmallGap)
                    Text(
                        "This phone's secure keystore could not create a device identity, so there " +
                            "is no safe way to verify the other phone. Pairing is disabled rather " +
                            "than done insecurely. Restarting the phone sometimes clears this.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                return@Column
            }

            val pairing = ui.connection as? ConnectionState.Pairing
            if (pairing?.stage == PairingStage.AwaitingUserConfirmation && pairing.shortAuthString != null) {
                ShortAuthStringCard(
                    code = pairing.shortAuthString,
                    onMatches = { viewModel.confirmShortAuthString(true) },
                    onDiffers = { viewModel.confirmShortAuthString(false) },
                )
                VerticalGap()
            }

            when (role) {
                DeviceRole.DISPLAY -> DisplayPairingSection(ui, viewModel)
                DeviceRole.CONTROLLER -> ControllerPairingSection(ui, viewModel, onOpenScanner)
                null -> Unit
            }

            VerticalGap(RelayDimens.ScreenPadding)
        }
    }
}

@Composable
private fun ShortAuthStringCard(code: String, onMatches: () -> Unit, onDiffers: () -> Unit) {
    // This is the only security decision the user is ever asked to make, and it is the one screen
    // where getting it wrong matters. It gets its own colour and the largest type in the app so it
    // cannot be mistaken for one more grey card to scroll past.
    val scheme = MaterialTheme.colorScheme
    RelayCard(modifier = Modifier.testTag("sas_card"), container = scheme.tertiaryContainer) {
        Text(
            "Do both phones show this?",
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onTertiaryContainer,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            // Spaced out so it is easy to read aloud across a room, and letter-spaced so a
            // cracked panel cannot merge two digits into one.
            text = code.chunked(3).joinToString("  "),
            style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 2.sp),
            textAlign = TextAlign.Center,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth().testTag("sas_code"),
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "If the two codes are different, do not continue: something is sitting between the " +
                "phones.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onTertiaryContainer.copy(alpha = 0.8f),
        )
        VerticalGap()
        PrimaryAction("They match", onClick = onMatches, modifier = Modifier.testTag("sas_match"))
        VerticalGap(RelayDimens.SmallGap)
        SecondaryAction("They are different", onClick = onDiffers)
    }
}

@Composable
private fun DisplayPairingSection(ui: PairingUiState, viewModel: PairingViewModel) {
    // Minting needs a bound listener, so ask once the port appears.
    LaunchedEffect(ui.listeningPort) {
        if (ui.listeningPort != null && ui.pairingOffer == null) viewModel.refreshPairingCode()
    }

    RelaySection("Show a code to the controller") {
        val offer = ui.pairingOffer
        when {
            offer != null -> {
                PairingCodeView(offer.uri, modifier = Modifier.testTag("pairing_qr"))
                VerticalGap(RelayDimens.SmallGap)
                Text(
                    "Scan this from the controller phone. The code is single use and expires in " +
                        "two minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VerticalGap(RelayDimens.SmallGap)
                SecondaryAction("Show a fresh code", onClick = viewModel::refreshPairingCode)
            }
            ui.listeningPort == null -> {
                Text(
                    "Make this phone available first, so it has an address to put in the code.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                VerticalGap(RelayDimens.SmallGap)
                PrimaryAction(
                    "Make available and show a code",
                    onClick = viewModel::showPairingCode,
                    modifier = Modifier.testTag("show_pairing_code"),
                )
            }
            else -> {
                Text("Preparing a code...", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    VerticalGap()
    RelaySection("Manual details") {
        StatusRow("Address", ui.localAddress ?: "no local address")
        StatusRow("Port", ui.listeningPort?.toString() ?: "not listening")
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "Use these on the controller only if scanning does not work. You will still be asked " +
                "to compare a six digit code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ControllerPairingSection(
    ui: PairingUiState,
    viewModel: PairingViewModel,
    onOpenScanner: () -> Unit,
) {
    RelaySection("Scan the display's code") {
        Text(
            "The fastest way to pair. Open Pair on the other phone, then scan what it shows.",
            style = MaterialTheme.typography.bodyLarge,
        )
        ui.scanError?.let {
            VerticalGap(RelayDimens.SmallGap)
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        VerticalGap(RelayDimens.SmallGap)
        PrimaryAction("Scan a pairing code", onClick = onOpenScanner, modifier = Modifier.testTag("scan_code"))
    }

    VerticalGap()
    RelaySection("Displays on this network") {
        val browseError = ui.browseError
        if (browseError != null) {
            Text(
                browseError,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("browse_error"),
            )
        } else if (ui.discovered.isEmpty()) {
            Text(
                "Searching. Both phones must be on the same Wi-Fi, or one must be using the " +
                    "other's hotspot, and the display must be made available on its own screen.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("no_displays"),
            )
        } else {
            // The whole row is the target. It used to be an inert label above a button that
            // repeated the same name, which doubled the height of the list and made the name
            // look like a heading rather than a thing you can tap.
            for (display in ui.discovered) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { viewModel.connectToDiscovered(display) }
                        .heightIn(min = RelayDimens.MinTouchTarget)
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Pair with ${display.serviceName}"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RelayGlyph(RelayIcon.CONNECT, tint = MaterialTheme.colorScheme.primary, size = 26.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(display.serviceName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "port ${display.endpoint.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    VerticalGap()
    ManualEntrySection(ui, viewModel)
}

@Composable
private fun ManualEntrySection(ui: PairingUiState, viewModel: PairingViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }

    VerticalGap()
    RelaySection("Advanced") {
        if (!expanded) {
            SecondaryAction("Enter an address manually", onClick = { expanded = true })
        } else {
            Text(
                "A fallback for networks where discovery is blocked. The display shows both " +
                    "values under Manual details.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalGap(RelayDimens.SmallGap)
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.take(15) },
                label = { Text("Display address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("manual_host"),
            )
            VerticalGap(RelayDimens.SmallGap)
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("manual_port"),
            )
            ui.manualError?.let {
                VerticalGap(RelayDimens.SmallGap)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            VerticalGap(RelayDimens.SmallGap)
            PrimaryAction(
                "Connect",
                onClick = { viewModel.connectManually(host, port) },
                modifier = Modifier.testTag("manual_connect"),
            )
        }
    }
}
