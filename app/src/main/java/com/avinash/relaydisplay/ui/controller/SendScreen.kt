package com.avinash.relaydisplay.ui.controller

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avinash.relaydisplay.content.SendState
import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.ui.common.ConnectionHero
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.RelayDetailBar
import com.avinash.relaydisplay.ui.common.RelaySection
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.navigation.SendFocus
import com.avinash.relaydisplay.ui.relayViewModel
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.avinash.relaydisplay.content.FileBatchState
import com.avinash.relaydisplay.content.FilePhase
import com.avinash.relaydisplay.content.FileProgress
import com.avinash.relaydisplay.content.FileTransferPolicy

/**
 * Everything the controller can put on the other screen.
 *
 * The screen leads with whichever kind the dashboard tile asked for. All three text-shaped kinds
 * share one draft, so changing your mind between "as text" and "as a QR code" costs one tap and
 * loses nothing you already typed -- which is why the other two stay on screen rather than
 * sending you back to the dashboard to pick again.
 *
 * Actions that need a live session are disabled without one rather than hidden, so the layout
 * does not jump around as the connection comes and goes, and a disabled button under the status
 * card above it explains itself.
 */
@Composable
fun SendScreen(
    focus: SendFocus,
    initialText: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: SendViewModel = relayViewModel(key = "send") {
        SendViewModel.create(it, context.contentResolver)
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // A share intent arrives as text; seed the draft once without clobbering later edits.
    var seeded by rememberSaveable { mutableStateOf(false) }
    if (!seeded && !initialText.isNullOrBlank()) {
        viewModel.setDraft(initialText)
        seeded = true
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Read access is granted for this URI to this task; nothing persistable is claimed
            // because the file is streamed once and never re-opened later.
            viewModel.sendPickedFile(uri, ui.defaultFitMode)
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.sendPickedFile(uri, ui.defaultFitMode)
    }

    // Any file, several at a time, through the Storage Access Framework.
    //
    // OpenMultipleDocuments and "*/*" on purpose: the picker itself is the permission. The user
    // chooses exactly which files this app may read and the system grants access to those URIs
    // alone, which is why the app holds no storage permission at all -- no READ_MEDIA_*, no
    // READ_EXTERNAL_STORAGE, and certainly no MANAGE_EXTERNAL_STORAGE. Every byte is then read
    // through the ContentResolver; no filesystem path is ever derived from a URI.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.onFilesPicked(uris)
    }

    Column(modifier.fillMaxSize()) {
        // Back sits in the corner it occupies on every other detail screen. The full-width "Back"
        // button that used to close this page is gone: it was below three sections of controls,
        // so reaching it meant scrolling past everything the screen does.
        RelayDetailBar(title = focus.screenTitle, onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RelayDimens.ScreenPadding),
        ) {
            ConnectionHero(state = ui.connection)
            VerticalGap()

            RelaySection(focus.sectionTitle) {
                OutlinedTextField(
                    value = ui.draft,
                    onValueChange = viewModel::setDraft,
                    label = { Text(focus.draftLabel) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("send_draft"),
                )
                ui.qrWarning?.let { Notice(it, warning = true) }
                ui.qrError?.let { Notice(it, error = true) }
                ui.linkError?.let { Notice(it, error = true) }
                VerticalGap(RelayDimens.SmallGap)

                PrimaryAction(
                    focus.actionLabel,
                    onClick = { viewModel.send(focus) },
                    enabled = ui.canSend,
                    modifier = Modifier.testTag(focus.testTag),
                )
                for (other in SendFocus.entries) {
                    if (other == focus) continue
                    VerticalGap(RelayDimens.SmallGap)
                    SecondaryAction(
                        other.actionLabel,
                        onClick = { viewModel.send(other) },
                        enabled = ui.canSend,
                        modifier = Modifier.testTag(other.testTag),
                    )
                }
            }
            VerticalGap()

            RelaySection("Image or document") {
                ui.fileError?.let { Notice(it, error = true) }
                when (val state = ui.sendState) {
                    is SendState.Preparing -> {
                        Text("Preparing ${state.displayName}", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    is SendState.Sending -> {
                        Text(
                            "Sending ${state.displayName} (${(state.fraction * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("send_progress"),
                        )
                        VerticalGap(RelayDimens.SmallGap)
                        SecondaryAction("Cancel", onClick = viewModel::cancelTransfer)
                    }
                    is SendState.Complete -> {
                        Notice("${state.displayName} is on the display.")
                        SecondaryAction("Send something else", onClick = viewModel::clearTransferState)
                    }
                    is SendState.Failed -> {
                        Notice("${state.displayName} failed: ${state.reason}", error = true)
                        SecondaryAction("Try again", onClick = viewModel::clearTransferState)
                    }
                    SendState.Idle -> {
                        PrimaryAction(
                            "Send an image",
                            onClick = { pickImage.launch(arrayOf("image/*")) },
                            enabled = ui.connected,
                            modifier = Modifier.testTag("send_image"),
                        )
                        VerticalGap(RelayDimens.SmallGap)
                        SecondaryAction(
                            "Send a PDF",
                            onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                            enabled = ui.connected,
                            modifier = Modifier.testTag("send_pdf"),
                        )
                    }
                }
            }
            VerticalGap()

            RelaySection("Files") {
                FileSendSection(
                    ui = ui,
                    viewModel = viewModel,
                    onPick = { pickFiles.launch(arrayOf("*/*")) },
                )
            }
            VerticalGap()

            RelaySection("Control the display") {
                // The single source of truth about the other screen, straight from its own report.
                ui.remoteStatusText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().testTag("remote_status"),
                    )
                    VerticalGap(RelayDimens.SmallGap)
                }
                PrimaryAction(
                    "Blank the display",
                    onClick = viewModel::blankDisplay,
                    enabled = ui.connected,
                    modifier = Modifier.testTag("blank_display"),
                )
                VerticalGap(RelayDimens.SmallGap)
                SecondaryAction(
                    // One phrase for this action everywhere in the app.
                    CLOSE_ON_DISPLAY,
                    onClick = viewModel::closeOnDisplay,
                    // Only offered when the Display reports it actually has content up.
                    enabled = ui.canCloseOnDisplay,
                    modifier = Modifier.testTag("close_on_display"),
                )
                VerticalGap(RelayDimens.SmallGap)
                Row(Modifier.fillMaxWidth()) {
                    SecondaryAction(
                        "Fit",
                        onClick = { viewModel.setFitMode(FitMode.FIT) },
                        enabled = ui.connected,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryAction(
                        "Fill",
                        onClick = { viewModel.setFitMode(FitMode.FILL) },
                        enabled = ui.connected,
                        modifier = Modifier.weight(1f).padding(start = RelayDimens.SmallGap),
                    )
                }
                VerticalGap(RelayDimens.SmallGap)
                BrightnessControl(enabled = ui.connected, onSet = viewModel::setBrightness)
                VerticalGap(RelayDimens.SmallGap)
                Row(Modifier.fillMaxWidth()) {
                    SecondaryAction(
                        "Full screen",
                        onClick = { viewModel.setImmersive(true) },
                        enabled = ui.connected,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryAction(
                        "Show bars",
                        onClick = { viewModel.setImmersive(false) },
                        enabled = ui.connected,
                        modifier = Modifier.weight(1f).padding(start = RelayDimens.SmallGap),
                    )
                }
            }
            VerticalGap()

            MirrorSection(connected = ui.connected)

            ui.notice?.let {
                VerticalGap()
                Notice(it)
            }

            VerticalGap(RelayDimens.ScreenPadding)
        }
    }
}

/**
 * How each send kind presents itself.
 *
 * Kept here rather than on the navigation enum: the route only needs to survive process death,
 * and wording is a UI concern that should not leak into the back stack.
 */
private val SendFocus.screenTitle: String
    get() = when (this) {
        SendFocus.TEXT -> "Send text"
        SendFocus.QR -> "Send a QR code"
        SendFocus.LINK -> "Send a link"
    }

private val SendFocus.sectionTitle: String
    get() = when (this) {
        SendFocus.TEXT -> "Text to show"
        SendFocus.QR -> "What to encode"
        SendFocus.LINK -> "Link to open"
    }

private val SendFocus.draftLabel: String
    get() = when (this) {
        SendFocus.TEXT -> "Type or paste"
        SendFocus.QR -> "Text or link to encode"
        SendFocus.LINK -> "Paste a link"
    }

private val SendFocus.actionLabel: String
    get() = when (this) {
        SendFocus.TEXT -> "Show as text"
        SendFocus.QR -> "Show as QR code"
        SendFocus.LINK -> "Send as a link"
    }

/** Test tags predate the focus enum and are kept verbatim so the existing suites still bind. */
private val SendFocus.testTag: String
    get() = when (this) {
        SendFocus.TEXT -> "send_text"
        SendFocus.QR -> "send_qr"
        SendFocus.LINK -> "send_link"
    }

private fun SendViewModel.send(focus: SendFocus) = when (focus) {
    SendFocus.TEXT -> sendAsText()
    SendFocus.QR -> sendAsQr()
    SendFocus.LINK -> sendAsLink()
}

/**
 * Screen sharing.
 *
 * Consent is requested through the system dialog every time, and the result is handed straight
 * to the service that will use it. Nothing about the grant is stored: Android treats a cached
 * projection token as a bug, and so does this.
 */
@Composable
private fun MirrorSection(connected: Boolean) {
    val context = LocalContext.current
    val container = com.avinash.relaydisplay.ui.appContainer()
    val mirrorState by container.mirrorController.state.collectAsStateWithLifecycle()
    val encoderAvailable = remember { com.avinash.relaydisplay.mirroring.MirrorProfile.hasAvcEncoder() }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            com.avinash.relaydisplay.service.MirrorProjectionService.start(context, result.resultCode, data)
        }
    }

    RelaySection("Share this screen") {
        when {
            !encoderAvailable -> Text(
                "This phone has no hardware H.264 encoder, so it cannot share its screen. " +
                    "Sending images, PDFs and QR codes still works.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            mirrorState is com.avinash.relaydisplay.mirroring.MirrorState.Active -> {
                val active = mirrorState as com.avinash.relaydisplay.mirroring.MirrorState.Active
                Text(
                    "Sharing at ${active.profile.width}x${active.profile.height}, " +
                        "${active.profile.frameRate} fps.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (active.droppedFrames > 0) {
                    Text(
                        "${active.droppedFrames} frames dropped to keep up with the link.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VerticalGap(RelayDimens.SmallGap)
                PrimaryAction(
                    "Stop sharing",
                    onClick = { com.avinash.relaydisplay.service.MirrorProjectionService.stop(context) },
                    modifier = Modifier.testTag("stop_mirror"),
                )
            }

            mirrorState is com.avinash.relaydisplay.mirroring.MirrorState.Unavailable -> {
                Text(
                    (mirrorState as com.avinash.relaydisplay.mirroring.MirrorState.Unavailable).reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                VerticalGap(RelayDimens.SmallGap)
                SecondaryAction(
                    "Try again",
                    onClick = { consent.launch(screenCaptureIntent(context)) },
                    enabled = connected,
                )
            }

            else -> {
                Text(
                    "Sends a live video of this screen. Android will ask for permission each " +
                        "time. Apps that block screenshots will appear black, which is the " +
                        "system's decision, not this app's.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VerticalGap(RelayDimens.SmallGap)
                PrimaryAction(
                    "Share this screen",
                    onClick = { consent.launch(screenCaptureIntent(context)) },
                    enabled = connected,
                    modifier = Modifier.testTag("start_mirror"),
                )
            }
        }
    }
}

internal fun screenCaptureIntent(context: android.content.Context) =
    (context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
        as android.media.projection.MediaProjectionManager).createScreenCaptureIntent()

/** The one phrase used for closing remote content, everywhere in the app. */
const val CLOSE_ON_DISPLAY = "Close on display"

@Composable
private fun BrightnessControl(enabled: Boolean, onSet: (Int) -> Unit) {
    var value by remember { mutableFloatStateOf(70f) }
    Text("Display brightness", style = MaterialTheme.typography.titleMedium)
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onSet(value.toInt()) },
        valueRange = 0f..100f,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("brightness_slider"),
    )
    SecondaryAction(
        "Use the display's own brightness",
        onClick = { onSet(BRIGHTNESS_SYSTEM_DEFAULT) },
        enabled = enabled,
    )
}

/** Shared with FileSendSection, which renders the same kinds of message. */
@Composable
internal fun Notice(text: String, error: Boolean = false, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            error -> MaterialTheme.colorScheme.error
            warning -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
