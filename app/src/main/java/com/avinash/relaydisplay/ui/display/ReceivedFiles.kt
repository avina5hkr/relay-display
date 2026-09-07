package com.avinash.relaydisplay.ui.display

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.avinash.relaydisplay.content.FileTransferPolicy
import com.avinash.relaydisplay.content.IncomingBatch
import com.avinash.relaydisplay.content.ReceivedFile
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.SectionHeader
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.common.RelayDimens
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Asks whether to accept a batch of files.
 *
 * Deliberately modal and deliberately explicit about who is asking, how many files there are and
 * how much space they need. Being paired is not consent to receive: pairing established which
 * phone is on the other end, and this establishes that its user's request is wanted right now.
 *
 * Dismissal is a rejection, not a deferral. `onReject` runs on back, on an outside tap and on the
 * Reject button, because leaving the sender waiting on an answer that never comes is worse than
 * answering "no" -- and a dialog that ignores back is worse than either.
 */
@Composable
fun IncomingBatchDialog(
    batch: IncomingBatch,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        // Covers back and the scrim. There is no path out of this dialog that leaves the offer
        // unanswered.
        onDismissRequest = onReject,
        title = { Text("Receive ${batch.count} file(s)?") },
        text = {
            Column {
                Text(
                    "${batch.senderName} wants to send ${batch.count} file(s), " +
                        "${formatSize(batch.totalBytes)} in total.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                VerticalGap(RelayDimens.SmallGap)
                // Every name and size, not a count: "accept 12 files" tells the user nothing
                // about what they are accepting.
                batch.files.forEach { file ->
                    Text(
                        "· ${file.displayName} (${formatSize(file.sizeBytes)})",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (batch.containsExecutable) {
                    VerticalGap(RelayDimens.SmallGap)
                    Text(
                        "Some of these look like apps or programs. Relay Display never installs " +
                            "or runs anything it receives, but only accept these if you were " +
                            "expecting them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag("batch_accept")) {
                Text("Accept all")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject, modifier = Modifier.testTag("batch_reject")) {
                Text("Reject")
            }
        },
        modifier = Modifier.testTag("incoming_batch_dialog"),
    )
}

/**
 * The received-files list, with what you can do with one.
 *
 * Every action goes through a `content://` URI from the app's own FileProvider plus a temporary
 * read grant on a single intent. No `file://` URI is ever produced: since API 24 that throws
 * FileUriExposedException, and the reason behind that rule is the point -- a file URI hands out a
 * path with no expiry and no scoping, while a provider URI is one grant, for one file, for the
 * lifetime of one intent.
 */
@Composable
fun ReceivedFilesSection(
    files: List<ReceivedFile>,
    onDelete: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The file the user is saving out, remembered across the CreateDocument round trip.
    var pendingSave by remember { mutableStateOf<ReceivedFile?>(null) }
    var warnBeforeOpen by remember { mutableStateOf<ReceivedFile?>(null) }
    var confirmDelete by remember { mutableStateOf<ReceivedFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val saveAs = rememberLauncherForActivityResult(
        // CreateDocument, so the user picks the destination and the app writes through the
        // resolver. Nothing is written to shared storage on the app's own authority, which is why
        // no storage permission is needed on any API level.
        ActivityResultContracts.CreateDocument("*/*"),
    ) { destination ->
        val source = pendingSave
        pendingSave = null
        if (destination != null && source != null) {
            error = copyOut(context, source, destination)
        }
    }

    SectionHeader("Received files")
    RelayCard(modifier) {
        if (files.isEmpty()) {
            Text(
                "Files sent from the controller phone appear here. They are kept in this app's " +
                    "cache, so save anything you want to keep.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@RelayCard
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        files.forEachIndexed { index, file ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.fillMaxWidth().testTag("received_file")) {
                Text(
                    file.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.mimeType} · ${formatSize(file.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            // An executable never opens on one tap, whatever its declared type.
                            if (file.needsOpenWarning) {
                                warnBeforeOpen = file
                            } else {
                                error = openWith(context, file)
                            }
                        },
                        modifier = Modifier.testTag("received_open"),
                    ) { Text("Open") }
                    TextButton(
                        onClick = {
                            pendingSave = file
                            saveAs.launch(file.displayName)
                        },
                        modifier = Modifier.testTag("received_save"),
                    ) { Text("Save as") }
                    TextButton(
                        onClick = { error = shareFile(context, file) },
                        modifier = Modifier.testTag("received_share"),
                    ) { Text("Share") }
                    TextButton(
                        onClick = { confirmDelete = file },
                        modifier = Modifier.testTag("received_delete"),
                    ) { Text("Delete") }
                }
            }
        }
    }

    warnBeforeOpen?.let { file ->
        AlertDialog(
            onDismissRequest = { warnBeforeOpen = null },
            title = { Text("Open ${file.displayName}?") },
            text = {
                Text(
                    // No double hyphen in user-facing copy: it renders literally and reads like a
                    // typo on a phone screen.
                    "This looks like an app or a program. Relay Display will not install or run " +
                        "it. Android will ask which app should handle it, and you can still back " +
                        "out there. Only continue if you know where this file came from.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        warnBeforeOpen = null
                        error = openWith(context, file)
                    },
                    modifier = Modifier.testTag("open_warning_continue"),
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { warnBeforeOpen = null }) { Text("Cancel") }
            },
            modifier = Modifier.testTag("open_warning_dialog"),
        )
    }

    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${file.displayName}?") },
            text = { Text("This removes the file from this phone. It cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDelete(file.transferId)
                    },
                    modifier = Modifier.testTag("confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
            },
        )
    }
}

/**
 * A `content://` URI for one received file.
 *
 * The provider is not exported and its paths cover only the cache's `ready/` directory, so this
 * cannot name anything but a complete, verified file.
 */
private fun providerUri(context: Context, file: ReceivedFile): Uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    file.file,
)

/**
 * Runs [block] and turns a provider misconfiguration into a message instead of a crash.
 *
 * `getUriForFile` throws [IllegalArgumentException] when the file is not under a root declared in
 * `file_provider_paths.xml`. That is a bug in this app rather than anything the user did, but it
 * must not be fatal: the paths and the cache layout are declared in two different files, and if
 * they ever drift again the file simply cannot be shared. Taking the whole app down instead loses
 * the user's other received files from view too.
 */
private inline fun guardedIntent(block: () -> Unit): String? = try {
    block()
    null
} catch (e: ActivityNotFoundException) {
    "No app on this phone can handle that file."
} catch (e: IllegalArgumentException) {
    "That file could not be shared from this app's storage."
} catch (e: SecurityException) {
    "That file could not be opened."
}

/**
 * Hands the file to whichever app the user chooses.
 *
 * ACTION_VIEW inside a chooser, with a read grant that lasts only as long as the intent. Never
 * ACTION_INSTALL_PACKAGE and never a direct component: the app does not decide what opens a
 * received file, and it cannot install one.
 */
private fun openWith(context: Context, file: ReceivedFile): String? = guardedIntent {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            providerUri(context, file),
            file.mimeType.ifBlank { FileTransferPolicy.FALLBACK_MIME },
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // Always a chooser, so the target is the user's decision every time rather than a default
    // that got set once.
    context.startActivity(Intent.createChooser(view, "Open with"))
}

private fun shareFile(context: Context, file: ReceivedFile): String? = guardedIntent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType.ifBlank { FileTransferPolicy.FALLBACK_MIME }
        putExtra(Intent.EXTRA_STREAM, providerUri(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share"))
}

/**
 * Copies a received file to wherever the user chose.
 *
 * Streamed, not read into memory: this runs for files up to the 50 MB transfer limit.
 */
private fun copyOut(context: Context, file: ReceivedFile, destination: Uri): String? {
    return try {
        val stream = context.contentResolver.openOutputStream(destination)
            ?: return "That location could not be written to."
        stream.use { out -> file.file.inputStream().use { input -> input.copyTo(out) } }
        null
    } catch (e: IOException) {
        "That file could not be saved."
    } catch (e: SecurityException) {
        "That location could not be written to."
    }
}

/** Sizes for people, not for machines. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
