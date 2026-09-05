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
        /**
         * Reads the metadata a provider is willing to give, and validates it.
         *
         * A provider that reports no size is refused rather than streamed blindly: without a
         * length there is nothing to check the transfer against.
         */
        fun from(resolver: ContentResolver, uri: Uri): ContentSourceResult {
            val declaredMime = resolver.getType(uri)
            val kind = MimeSupport.kindForMime(declaredMime)
                ?: return ContentSourceResult.Failed(ContentSourceError.UNSUPPORTED_TYPE)

            var size = -1L
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
                return ContentSourceResult.Failed(ContentSourceError.UNREADABLE)
            } catch (e: IllegalArgumentException) {
                return ContentSourceResult.Failed(ContentSourceError.UNREADABLE)
            }

            if (size <= 0) return ContentSourceResult.Failed(ContentSourceError.EMPTY)
            if (size > ContentLimits.MAX_FILE_BYTES) {
                return ContentSourceResult.Failed(ContentSourceError.TOO_LARGE)
            }

            return ContentSourceResult.Ready(
                UriContentSource(
                    resolver = resolver,
                    uri = uri,
                    sizeBytes = size,
                    mimeType = declaredMime.orEmpty(),
                    displayName = FilenameSanitizer.sanitize(name ?: "shared"),
                    kind = kind,
                ),
            )
        }
    }
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
