package com.avinash.relaydisplay.content

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Copies a received file out to wherever the user chose.
 *
 * Extracted from the Compose callback it used to live in. That callback ran `copyOut()` inline,
 * which meant up to 50 MiB was copied **on the main thread**: on the API 24 phone that is seconds
 * of a frozen UI and a plausible ANR, and there was no way for the screen to show that anything
 * was happening because the thread that would draw it was the thread doing the copying.
 *
 * The destination is written through a caller-supplied opener rather than a `ContentResolver`, so
 * the copy logic is testable on the JVM with injected streams. The Android wiring lives in
 * `DisplayViewModel`.
 */
class FileExporter(
    private val io: CoroutineDispatcher,
    private val bufferBytes: Int = 64 * 1024,
) {

    /**
     * Streams [source] into the stream [openDestination] returns.
     *
     * Never reads the file into memory: the buffer is one fixed allocation whatever the size. Both
     * streams are closed on every path, including cancellation, by `use`.
     *
     * A short write is treated as a failure rather than a success, because a truncated file that
     * claims to have saved is worse than an error the user can act on: they would discover it only
     * when something later failed to open it.
     */
    suspend fun export(
        source: InputStream,
        expectedBytes: Long,
        openDestination: () -> OutputStream?,
        onCleanupDestination: () -> Unit = {},
    ): ExportResult = withContext(io) {
        var written = 0L
        try {
            val destination = openDestination()
                ?: return@withContext ExportResult.Failed(ExportError.DESTINATION_UNAVAILABLE)
            source.use { input ->
                destination.use { output ->
                    val buffer = ByteArray(bufferBytes)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        written += read
                    }
                    output.flush()
                }
            }
        } catch (e: IOException) {
            // The half-written destination is the user's file now, and leaving a truncated copy
            // where they asked for a whole one is misleading. Removing it is delegated, because
            // only the caller knows how to delete through the resolver.
            onCleanupDestination()
            return@withContext ExportResult.Failed(ExportError.WRITE_FAILED)
        } catch (e: SecurityException) {
            onCleanupDestination()
            return@withContext ExportResult.Failed(ExportError.DESTINATION_UNAVAILABLE)
        } catch (e: Throwable) {
            // Cancellation included: clean up, then let it propagate to the caller's scope.
            onCleanupDestination()
            throw e
        }

        if (expectedBytes >= 0 && written != expectedBytes) {
            onCleanupDestination()
            return@withContext ExportResult.Failed(ExportError.INCOMPLETE)
        }
        ExportResult.Completed(written)
    }

    sealed interface ExportResult {
        data class Completed(val bytesWritten: Long) : ExportResult
        data class Failed(val error: ExportError) : ExportResult
    }

    enum class ExportError {
        /** The picked location could not be opened, or the provider handed back nothing. */
        DESTINATION_UNAVAILABLE,
        WRITE_FAILED,
        /** Fewer bytes arrived than the file holds. Never reported as success. */
        INCOMPLETE,
        SOURCE_UNAVAILABLE,
    }
}

/**
 * What the Received Files UI shows about a save in progress.
 *
 * Carries the id so the row that started the save is the row that shows the state, and so a second
 * tap on an already-saving row can be ignored rather than starting a duplicate copy.
 */
sealed interface SaveState {
    data object Idle : SaveState
    data class Saving(val transferId: UUID, val displayName: String) : SaveState
    data class Completed(val transferId: UUID, val displayName: String) : SaveState
    data class Failed(val transferId: UUID, val displayName: String, val message: String) : SaveState

    /** The row currently being saved, if any. */
    val busyWith: UUID? get() = (this as? Saving)?.transferId
}
