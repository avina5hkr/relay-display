package com.avinash.relaydisplay.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avinash.relaydisplay.BuildConfig
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.network.session.label
import com.avinash.relaydisplay.platform.AndroidPlatformCapabilities
import com.avinash.relaydisplay.platform.PermissionPolicy
import com.avinash.relaydisplay.platform.RelayPurpose
import com.avinash.relaydisplay.ui.appContainer
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDetailBar
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.SectionHeader
import com.avinash.relaydisplay.ui.common.StatusRow
import com.avinash.relaydisplay.ui.common.VerticalGap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Non-sensitive troubleshooting information, safe to copy or share.
 *
 * Nothing here contains a key, a token, a payload, a filename or a full address. The endpoint is
 * redacted by default and the log is the same bounded, already-redacted list the app keeps in
 * memory.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = com.avinash.relaydisplay.data.settings.RelaySettings())
    val connection by container.sessionCoordinator.state.collectAsStateWithLifecycle()
    val events by container.diagnostics.events.collectAsStateWithLifecycle()
    val peer by container.trustedPeerRepository.trustedPeer.collectAsStateWithLifecycle(initialValue = null)

    var revealEndpoint by remember { mutableStateOf(false) }
    var resetNotice by remember { mutableStateOf<String?>(null) }

    val report = remember(settings, connection, events, peer, revealEndpoint) {
        buildReport(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            platform = container.platformCapabilities,
            role = settings.role?.let { roleLabel(it) } ?: "not chosen",
            mode = settings.operatingMode.storageValue,
            connectionLabel = connection.label(),
            peerName = peer?.displayName,
            peerFingerprint = peer?.shortFingerprint,
            endpoint = peer?.let { p ->
                if (!p.hasEndpoint) {
                    "none"
                } else if (revealEndpoint) {
                    "${p.lastHost}:${p.lastPort}"
                } else {
                    "${DiagnosticsLog.redactHost(p.lastHost)}:${p.lastPort}"
                }
            } ?: "none",
            identityAvailable = container.identity != null,
            events = events,
        )
    }

    Column(modifier.fillMaxSize()) {
        RelayDetailBar(title = "Diagnostics", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RelayDimens.ScreenPadding),
        ) {

        SectionHeader("This phone")
        RelayCard {
            StatusRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            StatusRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            StatusRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            StatusRow("Role", settings.role?.let { roleLabel(it) } ?: "not chosen")
            StatusRow("Mode", settings.operatingMode.storageValue)
            StatusRow("Device identity", if (container.identity != null) "available" else "UNAVAILABLE")
        }

        SectionHeader("Permissions")
        RelayCard {
            for (purpose in RelayPurpose.entries) {
                StatusRow(purposeLabel(purpose), container.platformCapabilities.evaluate(purpose).describe())
            }
            StatusRow(
                "Local network permission",
                if (Build.VERSION.SDK_INT >= PermissionPolicy.SDK_LOCAL_NETWORK_RUNTIME) {
                    "required on this Android version"
                } else {
                    "not applicable below Android 17"
                },
            )
        }

        SectionHeader("Connection")
        RelayCard {
            StatusRow("State", connection.label())
            StatusRow("Transport", container.platformCapabilities.activeTransportLabel())
            StatusRow("This phone", AndroidPlatformCapabilities.localIpv4Address() ?: "no local address")
            StatusRow("Paired device", peer?.displayName ?: "none")
            StatusRow("Fingerprint", peer?.shortFingerprint ?: "none")
            StatusRow(
                "Last endpoint",
                peer?.let { p ->
                    when {
                        !p.hasEndpoint -> "none"
                        revealEndpoint -> "${p.lastHost}:${p.lastPort}"
                        else -> "${DiagnosticsLog.redactHost(p.lastHost)}:${p.lastPort}"
                    }
                } ?: "none",
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction(
                if (revealEndpoint) "Redact the address" else "Show the full address",
                onClick = { revealEndpoint = !revealEndpoint },
            )
        }

        SectionHeader("Recent events")
        RelayCard {
            if (events.isEmpty()) {
                Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                // Newest first: the thing that just went wrong is what the user is looking for.
                for (entry in events.asReversed().take(MAX_SHOWN_EVENTS)) {
                    Text(
                        text = "${formatTime(entry.timestampMs)}  ${entry.level.name.first()}  " +
                            "${entry.tag}: ${entry.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.level == DiagnosticsLog.Level.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        SectionHeader("Actions")
        RelayCard {
            SecondaryAction(
                "Copy diagnostics",
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(
                                android.content.ClipData.newPlainText("RelayDisplay diagnostics", report),
                            ),
                        )
                    }
                },
                modifier = Modifier.testTag("copy_diagnostics"),
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction(
                "Export diagnostics",
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "RelayDisplay diagnostics")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(share, "Share diagnostics"))
                },
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction(
                "Reset networking",
                onClick = {
                    scope.launch {
                        // Stops everything, then comes back only as far as the saved mode says.
                        container.sessionCoordinator.hardReset("diagnostics reset")
                        container.sessionCoordinator.onOperatingModeChanged(settings.operatingMode)
                        resetNotice = "Networking stopped and restarted according to your saved mode."
                    }
                },
                modifier = Modifier.testTag("reset_networking"),
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction("Clear this log", onClick = { container.diagnostics.clear() })
            resetNotice?.let {
                VerticalGap(RelayDimens.SmallGap)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

            VerticalGap(RelayDimens.ScreenPadding)
        }
    }
}

private const val MAX_SHOWN_EVENTS = 80

private fun purposeLabel(purpose: RelayPurpose): String = when (purpose) {
    RelayPurpose.LocalNetwork -> "Local network"
    RelayPurpose.ForegroundAvailability -> "Ongoing notification"
    RelayPurpose.QrScanning -> "Camera (pairing)"
    RelayPurpose.ScreenMirroring -> "Screen sharing"
}

private fun com.avinash.relaydisplay.platform.CapabilityVerdict.describe(): String = when (this) {
    com.avinash.relaydisplay.platform.CapabilityVerdict.Allowed -> "granted"
    is com.avinash.relaydisplay.platform.CapabilityVerdict.Degraded -> "limited"
    is com.avinash.relaydisplay.platform.CapabilityVerdict.Blocked -> "blocked"
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))

/** The exact text that Copy and Export produce. Kept in one place so both stay identical. */
private fun buildReport(
    appVersion: String,
    platform: AndroidPlatformCapabilities,
    role: String,
    mode: String,
    connectionLabel: String,
    peerName: String?,
    peerFingerprint: String?,
    endpoint: String,
    identityAvailable: Boolean,
    events: List<DiagnosticsLog.Entry>,
): String = buildString {
    appendLine("RelayDisplay diagnostics")
    appendLine("app: $appVersion")
    appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("role: $role")
    appendLine("mode: $mode")
    appendLine("identity: ${if (identityAvailable) "available" else "UNAVAILABLE"}")
    appendLine("state: $connectionLabel")
    appendLine("transport: ${platform.activeTransportLabel()}")
    appendLine("peer: ${peerName ?: "none"}")
    appendLine("fingerprint: ${peerFingerprint ?: "none"}")
    appendLine("endpoint: $endpoint")
    for (purpose in RelayPurpose.entries) {
        appendLine("permission ${purposeLabel(purpose)}: ${platform.evaluate(purpose).describe()}")
    }
    appendLine("--- events (newest last) ---")
    for (entry in events) {
        appendLine("${formatTime(entry.timestampMs)} ${entry.level.name} ${entry.tag}: ${entry.message}")
    }
}
