package com.avinash.relaydisplay.content

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.ContentLimits
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Something that can be sent.
 *
 * An interface rather than a `Uri` so the transfer logic is testable without a ContentResolver,
 * and so the rule "a content URI is a stream, never a path" is enforced by the shape of the API.
 */
interface ContentSource {
    val sizeBytes: Long
    val mimeType: String
    val displayName: String
    val kind: ContentKind

    /** A fresh stream each time. The sender opens it twice: once to digest, once to send. */
    @Throws(IOException::class)
    fun openStream(): InputStream
}

/** Why a picked file cannot be sent. */
enum class ContentSourceError { UNREADABLE, UNSUPPORTED_TYPE, TOO_LARGE, EMPTY }

sealed interface ContentSourceResult {
    data class Ready(val source: ContentSource) : ContentSourceResult
    data class Failed(val error: ContentSourceError) : ContentSourceResult
}

/**
 * A [ContentSource] over a Storage Access Framework or Sharesheet URI.
 *
 * Everything is read through the resolver: no filesystem path is derived, which is what keeps
 * this working for cloud-backed providers and scoped storage alike.
 */
class UriContentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val sizeBytes: Long,
    override val mimeType: String,
    override val displayName: String,
    override val kind: ContentKind,
) : ContentSource {

    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("provider returned no stream")

    companion object {

        /** What a provider was willing to tell us. */
        private data class Metadata(val size: Long, val name: String?)

        /**
         * Reads the metadata a provider is willing to give.
         *
         * Returns [FileTransferPolicy.SIZE_UNKNOWN] for the size when the provider does not
         * report one, which is normal and not an error: a cloud-backed or generated document may
         * genuinely not know its length until it is read.
         */
        private fun readMetadata(resolver: ContentResolver, uri: Uri): Metadata? {
            var size = FileTransferPolicy.SIZE_UNKNOWN
            var name: String? = null
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    }
                }
            } catch (e: SecurityException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
            // A provider reporting a genuinely negative length is broken, not unknown.
            if (size < 0) size = FileTransferPolicy.SIZE_UNKNOWN
            return Metadata(size, name)
        }

        /**
         * Reads and validates an image or PDF for the presentation path.
         *
         * Narrow on purpose: these bytes end up in a decoder, so an unrecognised MIME type is a
         * refusal rather than something to stream and hope about. Generic files go through
         * [forGenericFile] instead.
         */
        fun from(resolver: ContentResolver, uri: Uri): ContentSourceResult {
            val declaredMime = resolver.getType(uri)
            val kind = MimeSupport.kindForMime(declaredMime)
                ?: return ContentSourceResult.Failed(ContentSourceError.UNSUPPORTED_TYPE)

            val metadata = readMetadata(resolver, uri)
                ?: return ContentSourceResult.Failed(ContentSourceError.UNREADABLE)

            // An image or PDF of zero length has nothing for a decoder to open, so this stays a
            // refusal here even though a zero-length generic file is perfectly ordinary.
            if (metadata.size == 0L) return ContentSourceResult.Failed(ContentSourceError.EMPTY)
            if (metadata.size > ContentLimits.MAX_FILE_BYTES) {
                return ContentSourceResult.Failed(ContentSourceError.TOO_LARGE)
            }

            return ContentSourceResult.Ready(
                UriContentSource(
                    resolver = resolver,
                    uri = uri,
                    sizeBytes = metadata.size,
                    mimeType = declaredMime.orEmpty(),
                    displayName = FilenameSanitizer.sanitize(metadata.name ?: "shared"),
                    kind = kind,
                ),
            )
        }

        /**
         * Reads any file the user picked, for generic file transfer.
         *
         * Differs from [from] in three ways, all deliberate:
         *  - any MIME type is accepted, including none at all, because nothing decodes these bytes
         *  - a zero-length file is accepted; an empty file is a real file
         *  - an unreported size is accepted and left as [FileTransferPolicy.SIZE_UNKNOWN]
         *
         * The size is only a *hint* at this stage. [prepare] measures the stream before anything
         * is offered, so a wrong or missing declaration cannot turn into a failed transfer.
         */
        fun forGenericFile(resolver: ContentResolver, uri: Uri): ContentSourceResult {
            val metadata = readMetadata(resolver, uri)
                ?: return ContentSourceResult.Failed(ContentSourceError.UNREADABLE)

            // Checked against the hint too, so an obviously oversized pick is refused before the
            // user waits through a preparation pass that can only end the same way.
            if (metadata.size != FileTransferPolicy.SIZE_UNKNOWN &&
                metadata.size > FileTransferPolicy.MAX_FILE_BYTES
            ) {
                return ContentSourceResult.Failed(ContentSourceError.TOO_LARGE)
            }

            val verdict = FileTransferPolicy.validateFileMetadata(
                rawName = metadata.name ?: "shared",
                mimeType = resolver.getType(uri),
                declaredSize = metadata.size,
            )
            return when (verdict) {
                is FileTransferPolicy.MetadataVerdict.Invalid ->
                    ContentSourceResult.Failed(ContentSourceError.UNREADABLE)
                is FileTransferPolicy.MetadataVerdict.Valid -> ContentSourceResult.Ready(
                    UriContentSource(
                        resolver = resolver,
                        uri = uri,
                        sizeBytes = verdict.sizeBytes,
                        mimeType = verdict.mimeType,
                        displayName = verdict.safeName,
                        kind = ContentKind.FILE,
                    ),
                )
            }
        }
    }
}

/**
 * A source over a file this app already wrote: the spool copy of a picked document.
 *
 * Exists so the transmit path takes a [ContentSource] whatever the bytes came from, while the
 * bytes it actually reads are the prepared, measured, digested ones rather than a second read of
 * a `ContentResolver` stream that may not repeat.
 */
class SpooledContentSource(
    private val file: java.io.File,
    override val mimeType: String,
    override val displayName: String,
    override val kind: ContentKind = ContentKind.FILE,
) : ContentSource {
    override val sizeBytes: Long get() = file.length()
    override fun openStream(): InputStream = file.inputStream()
}

/** An in-memory source, used by tests and by anything that already holds the bytes. */
class ByteArrayContentSource(
    private val bytes: ByteArray,
    override val mimeType: String,
    override val displayName: String,
    override val kind: ContentKind,
) : ContentSource {
    override val sizeBytes: Long get() = bytes.size.toLong()
    override fun openStream(): InputStream = bytes.inputStream()
}

/**
 * SHA-256 over a source, computed by streaming.
 *
 * Never calls `readBytes()`: a 50 MiB file must not become a 50 MiB array on the phone doing the
 * sending either.
 */
fun ContentSource.computeSha256(bufferBytes: Int = 64 * 1024): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    openStream().use { stream ->
        val buffer = ByteArray(bufferBytes)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}

/** What a source turned out to actually contain. */
sealed interface PrepareResult {
    /**
     * [sizeBytes] is the **measured** length, not the declared one, and [sha256] covers exactly
     * those bytes.
     */
    data class Ready(val sizeBytes: Long, val sha256: ByteArray) : PrepareResult {
        // Data class over a ByteArray: generated equals/hashCode compare by identity, which is
        // never what a caller means for a digest.
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Ready && sizeBytes == other.sizeBytes && sha256.contentEquals(other.sha256))

        override fun hashCode(): Int = 31 * sizeBytes.hashCode() + sha256.contentHashCode()
    }

    data class Failed(val error: ContentSourceError) : PrepareResult
}

/**
 * Measures and digests a source in one bounded pass, before anything is offered.
 *
 * **Still reads the source twice overall**, because the transmit path reopens it. Generic file
 * transfer no longer does this -- it spools to app-private storage first, see [spoolTo] -- but the
 * image and PDF presentation path does, and was deliberately left alone rather than migrated in
 * the same change. The consequence is bounded: a one-shot or changing provider stream makes an
 * image or PDF fail the receiver's digest check, which is a clean visible failure rather than
 * wrong content, because there is no manifest for those to disagree with. Worth migrating to
 * [spoolTo] when the presentation path is next touched.
 *
 * This exists because a `ContentResolver` is not obliged to report a size, and the two places
 * that used to care disagreed about it: the picker refused anything without a length, while the
 * protocol and the receiver were documented as supporting an unknown one. Neither behaviour was
 * right. Measuring resolves it without a policy argument, and needs no extra work: the sender
 * already had to read the whole stream once to compute the SHA-256 the offer carries, so the
 * byte count is free.
 *
 * Consequences worth knowing:
 *  - the offered size is always the real size, so "the file changed while sending" can no longer
 *    be caused by a merely inaccurate provider
 *  - a source that yields different bytes on the second read fails the receiver's hash check,
 *    which is the correct outcome and is what that check is for
 *  - nothing is buffered. Memory is one [bufferBytes] array whatever the file's size
 *
 * [maxBytes] is enforced *during* the pass, so a provider that under-reports its length cannot
 * make this read an unbounded amount: the read stops the moment the limit is passed.
 */
fun ContentSource.prepare(
    maxBytes: Long = FileTransferPolicy.MAX_FILE_BYTES,
    bufferBytes: Int = 64 * 1024,
): PrepareResult {
    val digest = MessageDigest.getInstance("SHA-256")
    var measured = 0L
    try {
        openStream().use { stream ->
            val buffer = ByteArray(bufferBytes)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                // Compared before accumulating, so `measured` itself cannot run away.
                if (measured > maxBytes - read) {
                    return PrepareResult.Failed(ContentSourceError.TOO_LARGE)
                }
                measured += read
                digest.update(buffer, 0, read)
            }
        }
    } catch (e: IOException) {
        return PrepareResult.Failed(ContentSourceError.UNREADABLE)
    } catch (e: SecurityException) {
        // The grant expired between picking and sending: a real case when the picker result is
        // acted on later.
        return PrepareResult.Failed(ContentSourceError.UNREADABLE)
    }
    return PrepareResult.Ready(measured, digest.digest())
}
