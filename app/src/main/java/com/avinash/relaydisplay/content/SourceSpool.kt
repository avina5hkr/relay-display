package com.avinash.relaydisplay.content

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * App-private scratch space for outbound files, so a selected source is read exactly once.
 *
 * The previous design opened each source twice: once to measure it and compute the SHA-256 the
 * offer carries, then again to transmit it. That is fine for a local file and wrong for a
 * `ContentResolver` in general -- a provider may hand back a one-shot stream, a stream that
 * regenerates different bytes, or a remote stream that is simply gone the second time. When that
 * happened the transfer either failed the receiver's digest check or, worse, sent bytes that no
 * longer matched what the user was shown and the receiver had agreed to.
 *
 * Spooling makes the prepared bytes the authoritative copy: the manifest describes the spool file,
 * and the spool file is what is transmitted. The original URI is never reopened, so nothing about
 * the source's stability matters after preparation.
 *
 * Spool files are **never** exposed through the FileProvider: they hold unsent copies of the
 * user's own documents and nothing outside this process has any reason to read them.
 */
class SourceSpool(private val root: File) {

    private val dir = File(root, DIRECTORY_NAME)

    private fun ensureDirectory() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    fun fileFor(transferId: UUID): File {
        ensureDirectory()
        return File(dir, "$transferId$SUFFIX")
    }

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    /**
     * Deletes every spool file. Call at startup and whenever a batch reaches a terminal state.
     *
     * Startup matters: a spool file survives process death, and without this sweep an interrupted
     * batch would leak a copy of the user's document into the cache indefinitely.
     */
    fun deleteAll(): Int {
        var deleted = 0
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(SUFFIX) && it.delete()) deleted++ }
        return deleted
    }

    companion object {
        const val DIRECTORY_NAME = "spool"
        private const val SUFFIX = ".spool"
    }
}

/** What a spooling pass produced. */
sealed interface SpoolResult {
    /** [sizeBytes] and [sha256] describe the spooled bytes exactly, and those bytes get sent. */
    data class Ready(val sizeBytes: Long, val sha256: ByteArray) : SpoolResult {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Ready && sizeBytes == other.sizeBytes && sha256.contentEquals(other.sha256))

        override fun hashCode(): Int = 31 * sizeBytes.hashCode() + sha256.contentHashCode()
    }

    data class Failed(val error: ContentSourceError) : SpoolResult
}

/**
 * Copies a source into [target] once, measuring and digesting it on the way.
 *
 * One pass does all four jobs -- copy, count, digest, bound -- because they must agree: a size or
 * digest computed from a *different* read than the one that produced the bytes is not a
 * description of those bytes.
 *
 * Cancellation is cooperative and checked every chunk, so a user cancelling a 50 MB preparation
 * does not wait for it to finish. On any failure or cancellation the partial spool file is
 * deleted before returning, so a failed preparation leaves nothing behind.
 *
 * [maxBytes] is enforced *during* the read, so a provider that under-reports its own length cannot
 * make this write an unbounded amount to the cache.
 */
suspend fun ContentSource.spoolTo(
    target: File,
    maxBytes: Long = FileTransferPolicy.MAX_FILE_BYTES,
    bufferBytes: Int = 64 * 1024,
    onProgress: (Long) -> Unit = {},
): SpoolResult {
    val digest = MessageDigest.getInstance("SHA-256")
    var measured = 0L
    try {
        openStream().use { input ->
            BufferedOutputStream(FileOutputStream(target), bufferBytes).use { output ->
                val buffer = ByteArray(bufferBytes)
                while (true) {
                    // Throws CancellationException, which propagates past the catches below and
                    // is handled by the caller's structured cancellation.
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    // A provider is allowed to return 0 without being at EOF. Treating that as the
                    // end would silently truncate the file.
                    if (read == 0) continue
                    // Compared before accumulating, so the counter itself cannot run away.
                    if (measured > maxBytes - read) {
                        target.delete()
                        return SpoolResult.Failed(ContentSourceError.TOO_LARGE)
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    measured += read
                    onProgress(measured)
                }
                output.flush()
            }
        }
    } catch (e: IOException) {
        target.delete()
        return SpoolResult.Failed(ContentSourceError.UNREADABLE)
    } catch (e: SecurityException) {
        // The URI grant expired between picking and preparing: a real case when the picker result
        // is acted on later.
        target.delete()
        return SpoolResult.Failed(ContentSourceError.UNREADABLE)
    } catch (e: Throwable) {
        // Includes cancellation. Delete the partial, then let it propagate.
        target.delete()
        throw e
    }
    return SpoolResult.Ready(measured, digest.digest())
}
