package com.avinash.relaydisplay.ui.common

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.avinash.relaydisplay.platform.AndroidPlatformCapabilities
import com.avinash.relaydisplay.platform.CapabilityVerdict
import com.avinash.relaydisplay.platform.PermissionPolicy
import com.avinash.relaydisplay.platform.RelayPurpose

/**
 * Asks for a runtime permission at the moment it is needed, with the reason first.
 *
 * Shows nothing at all when the platform does not require the permission -- notably, the local
 * network permission simply does not exist below Android 17, and this composable renders empty
 * there rather than confusing the user with an impossible request.
 *
 * A second denial is treated as permanent: the card switches to pointing at system settings
 * rather than launching a dialog that will never appear again.
 */
@Composable
fun PermissionGate(
    purpose: RelayPurpose,
    platform: AndroidPlatformCapabilities,
    modifier: Modifier = Modifier,
    onGranted: () -> Unit = {},
) {
    val context = LocalContext.current
    var verdict by remember(purpose) { mutableStateOf(platform.evaluate(purpose)) }
    var askedOnce by remember(purpose) { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        askedOnce = true
        verdict = platform.evaluate(purpose)
        if (results.values.any { it }) onGranted()
    }

    when (val current = verdict) {
        CapabilityVerdict.Allowed -> Unit

        is CapabilityVerdict.Degraded -> RelayCard(modifier) {
            Text(degradedTitle(purpose), style = MaterialTheme.typography.titleMedium)
            VerticalGap(RelayDimens.SmallGap)
            Text(
                degradedBody(purpose),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction(
                "Allow notifications",
                onClick = { launcher.launch(current.missing.toTypedArray()) },
                modifier = Modifier.testTag("permission_degraded"),
            )
        }

        is CapabilityVerdict.Blocked -> RelayCard(modifier) {
            Text(blockedTitle(purpose), style = MaterialTheme.typography.titleLarge)
            VerticalGap(RelayDimens.SmallGap)
            Text(blockedBody(purpose), style = MaterialTheme.typography.bodyMedium)
            VerticalGap(RelayDimens.SmallGap)
            if (askedOnce) {
                // The system will not show the dialog again; the only route left is Settings.
                SecondaryAction(
                    "Open app settings",
                    onClick = { context.startActivity(appSettingsIntent(context.packageName)) },
                    modifier = Modifier.testTag("permission_settings"),
                )
            } else {
                PrimaryAction(
                    grantLabel(purpose),
                    onClick = { launcher.launch(current.missing.toTypedArray()) },
                    modifier = Modifier.testTag("permission_grant"),
                )
            }
        }
    }
}

private fun appSettingsIntent(packageName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun blockedTitle(purpose: RelayPurpose): String = when (purpose) {
    RelayPurpose.LocalNetwork -> "Local network access is needed"
    RelayPurpose.QrScanning -> "Camera access is needed"
    RelayPurpose.ForegroundAvailability -> "Notifications are needed"
    RelayPurpose.ScreenMirroring -> "Screen sharing needs permission"
}

private fun blockedBody(purpose: RelayPurpose): String = when (purpose) {
    RelayPurpose.LocalNetwork ->
        "Android ${PermissionPolicy.SDK_LOCAL_NETWORK_RUNTIME} and later require your permission " +
            "before an app can reach other devices on your Wi-Fi. RelayDisplay uses it only to " +
            "talk to your other phone; it never contacts the internet."
    RelayPurpose.QrScanning ->
        "The camera is used only to read the pairing code on the other phone. Nothing is " +
            "recorded or sent anywhere."
    RelayPurpose.ForegroundAvailability ->
        "The ongoing notification is how Android lets an app stay reachable in the background, " +
            "and how you stop it."
    RelayPurpose.ScreenMirroring ->
        "Android asks separately each time before any app can capture your screen."
}

private fun grantLabel(purpose: RelayPurpose): String = when (purpose) {
    RelayPurpose.LocalNetwork -> "Allow local network access"
    RelayPurpose.QrScanning -> "Allow camera"
    RelayPurpose.ForegroundAvailability -> "Allow notifications"
    RelayPurpose.ScreenMirroring -> "Continue"
}

private fun degradedTitle(purpose: RelayPurpose): String = when (purpose) {
    RelayPurpose.ForegroundAvailability -> "Running without a notification"
    else -> "Working with limits"
}

private fun degradedBody(purpose: RelayPurpose): String = when (purpose) {
    RelayPurpose.ForegroundAvailability ->
        "RelayDisplay still works, but Android will not show the ongoing row, so there is no " +
            "quick way to disconnect or pause from outside the app."
    else -> "Some parts of this feature are unavailable until you grant permission."
}
