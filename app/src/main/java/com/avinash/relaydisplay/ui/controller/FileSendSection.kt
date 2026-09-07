package com.avinash.relaydisplay.ui.controller

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avinash.relaydisplay.content.FileBatchState
import com.avinash.relaydisplay.content.FilePhase
import com.avinash.relaydisplay.content.FileProgress
import com.avinash.relaydisplay.content.FileTransferPolicy
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.VerticalGap

/**
 * Picking, reviewing and sending a batch of files.
 *
 * Shared by the dashboard and the send screen rather than duplicated, because both are entry
 * points into the same operation and the state behind it lives in one [SendViewModel] keyed
 * "send". A batch started from the dashboard therefore shows its progress on the send screen too,
 * and vice versa: there is only ever one batch, so showing it in two places is not a conflict.
 *
 * [showPickAction] is false on the dashboard, where the tile is already the way in and a second
 * "Send files" button underneath it would be one control too many.
 */
@Composable
internal fun FileSendSection(
    ui: SendUiState,
    viewModel: SendViewModel,
    onPick: () -> Unit,
    showPickAction: Boolean = true,
) {
    ui.batchError?.let { Notice(it, error = true) }

    val batch = ui.fileBatch
    when {
        // A send in flight or just finished: progress, then what to do about it.
        batch != null -> FileBatchProgress(
            batch = batch,
            onCancel = viewModel::cancelFileBatch,
            onRetry = viewModel::retryFileBatch,
            onDone = viewModel::clearFileBatch,
        )

        // Files chosen but not yet offered: the review list.
        ui.picked.isNotEmpty() -> {
            Text(
                "${ui.picked.size} file(s), ${formatTotal(ui.pickedTotalBytes)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("picked_summary"),
            )
            VerticalGap(RelayDimens.SmallGap)
            ui.picked.forEachIndexed { index, file ->
                PickedFileRow(file = file, onRemove = { viewModel.removePickedFile(index) })
            }
            VerticalGap(RelayDimens.SmallGap)
            PrimaryAction(
                "Send ${ui.picked.size} file(s)",
                onClick = viewModel::sendPickedFiles,
                enabled = ui.canSendPicked,
                modifier = Modifier.testTag("send_files_confirm"),
            )
            VerticalGap(RelayDimens.SmallGap)
            SecondaryAction(
                "Cancel",
                onClick = viewModel::clearPickedFiles,
                modifier = Modifier.testTag("send_files_cancel"),
            )
        }

        showPickAction -> {
            PrimaryAction(
                "Send files",
                // MIME "*/*": a generic file is stored and handed to a chooser, never decoded
                // here, so narrowing the picker would only hide valid files.
                onClick = onPick,
                enabled = ui.connected && ui.peerSupportsFiles,
                modifier = Modifier.testTag("send_files"),
            )
            if (ui.connected && !ui.peerSupportsFiles) {
                // Said before they pick, not after: this is a property of the other phone's
                // version and no amount of retrying will change it.
                Notice(
                    "The other phone is running an older version of Relay Display that cannot " +
                        "receive files. Update it on both phones.",
                    warning = true,
                )
            }
        }
    }
}

/**
 * One row of the review list: what it is, how big, and a way to drop it.
 *
 * The MIME type is shown because a name alone can be misleading, and the size because a
 * multi-select picker makes it easy to queue far more than intended over Wi-Fi.
 */
@Composable
private fun PickedFileRow(file: PickedFile, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${file.mimeType.ifBlank { "unknown type" }} · ${formatBytes(file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove, modifier = Modifier.testTag("remove_picked")) {
            Text("Remove")
        }
    }
}

/**
 * Progress for a multi-file send.
 *
 * Reports per-file phases rather than one bar, because "3 of 7" plus a named current file is what
 * a person actually wants to know, and because a single bar cannot express that file 2 was
 * rejected while file 3 is still going. Nothing here is inferred: every line comes from the
 * batch state, so a rejected or failed file can never render as complete.
 */
@Composable
private fun FileBatchProgress(
    batch: FileBatchState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val position = batch.completedCount + batch.failedCount + batch.rejectedCount
    Text(
        if (batch.settled) {
            "${batch.completedCount} of ${batch.total} sent"
        } else {
            "${(position + 1).coerceAtMost(batch.total)} of ${batch.total}"
        },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("batch_position"),
    )

    // Null when any size is unknown, which is exactly when an indeterminate bar is the honest
    // choice. A percentage invented from a guess is worse than no percentage.
    if (!batch.settled) {
        val percent = batch.percent
        if (percent == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("batch_progress"),
            )
        }
    }

    VerticalGap(RelayDimens.SmallGap)
    batch.files.forEach { file ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                phaseLabel(file),
                style = MaterialTheme.typography.bodySmall,
                color = when (file.phase) {
                    is FilePhase.Failed, FilePhase.Rejected -> MaterialTheme.colorScheme.error
                    FilePhase.Complete -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    VerticalGap(RelayDimens.SmallGap)
    if (batch.settled) {
        if (batch.canRetry) {
            // Offered only for retryable failures. A file the peer refused on policy grounds, or
            // one that cannot be read, gets no button: it would fail again identically.
            PrimaryAction(
                "Retry ${batch.retryableIndices.size} file(s)",
                onClick = onRetry,
                modifier = Modifier.testTag("batch_retry"),
            )
            VerticalGap(RelayDimens.SmallGap)
        }
        SecondaryAction("Done", onClick = onDone, modifier = Modifier.testTag("batch_done"))
    } else {
        SecondaryAction("Cancel", onClick = onCancel, modifier = Modifier.testTag("batch_cancel"))
    }
}

private fun phaseLabel(file: FileProgress): String = when (val phase = file.phase) {
    FilePhase.Waiting -> "waiting"
    FilePhase.Accepted -> "queued"
    FilePhase.Sending -> file.percent?.let { "$it%" } ?: "sending"
    FilePhase.Verifying -> "verifying"
    FilePhase.Complete -> "sent"
    FilePhase.Rejected -> "declined"
    FilePhase.Cancelled -> "cancelled"
    is FilePhase.Failed -> phase.reason
}

/** A size, or an honest "Unknown size" when the provider would not report one. */
internal fun formatBytes(bytes: Long): String = when {
    bytes == FileTransferPolicy.SIZE_UNKNOWN -> "Unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** A batch total, where null means at least one size is unknown. */
internal fun formatTotal(bytes: Long?): String = bytes?.let { formatBytes(it) } ?: "total size unknown"
