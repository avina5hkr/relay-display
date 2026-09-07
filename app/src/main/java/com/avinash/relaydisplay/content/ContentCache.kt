package com.avinash.relaydisplay.content

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Bounded on-disk cache for received content.
 *
 * Three properties matter:
 *  - filenames are generated here from a transfer id, never derived from anything the peer sent,
 *    so a hostile display name cannot influence where a byte lands, and
 *  - the cache is bounded by both bytes and entries, and evicts oldest first, so a peer cannot
 *    fill the phone by sending file after file, and
 *  - those bounds come from [FileTransferPolicy], which is also what the sender validates a
 *    batch against. They used to be independent numbers, and the entry budget was smaller than
 *    one legal batch, so accepting a 20-file batch evicted the first 8 files of it.
 *
 * Everything lives under the app's cache directory, which the system may reclaim and which is
 * excluded from backup.
 */
class ContentCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /**
     * How much space is really available.
     *
     * Injected so this class stays pure JVM and unit testable. [openIn] supplies a version-aware
     * implementation that, from API 26, asks StorageManager for allocatable bytes -- which counts
     * space the system could reclaim from other apps' caches, and is therefore a far better
     * answer than raw free space when deciding whether a 50 MB transfer will fit.
     */
    private val availableSpaceProvider: (File) -> Long = { it.usableSpace },
    /** Injected so expiry can be tested without waiting a day. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // No directory is created at construction time. The container is built in
    // Application.onCreate, and touching the filesystem there is main-thread disk I/O -- which
    // StrictMode flags and which costs real milliseconds on the older phone. Directories are
    // created on first use instead, always from a background dispatcher.
    private val incoming = File(root, "incoming")
    private val ready = File(root, "ready")

    private fun ensureDirectories() {
        if (!incoming.isDirectory) incoming.mkdirs()
        if (!ready.isDirectory) ready.mkdirs()
    }

    /**
     * A private, uniquely named file to stream into.
     *
     * The `.part` suffix makes an interrupted transfer obvious both to [sweepPartials] and to
     * anyone looking at the directory.
     */
    fun createPartial(transferId: UUID): File {
        ensureDirectories()
        val file = File(incoming, "$transferId.part")
        if (file.exists()) file.delete()
        file.createNewFile()
        return file
    }

    /**
     * Moves a completed download into the session cache.
     *
     * A rename within the same filesystem is atomic, so a reader never sees a half-written file
     * under the final name. If the rename fails, the partial is deleted rather than left behind.
     */
    fun promote(
        partial: File,
        transferId: UUID,
        extension: String?,
        /**
         * Display name, MIME type and size to remember alongside the bytes.
         *
         * Optional because image and PDF transfers do not need it: those are shown immediately
         * and their metadata lives in the presentation state. A generic file has to survive in a
         * list the user comes back to later, and its real name is not recoverable from a file
         * named after a transfer id.
         */
        metadata: PromotedMetadata? = null,
    ): File? {
        ensureDirectories()
        val suffix = extension?.let { safeExtension(it) }.orEmpty()
        val target = File(ready, "$transferId$suffix")
        return try {
            if (target.exists()) target.delete()
            if (partial.renameTo(target)) {
                if (metadata != null) {
                    // After the rename, so a sidecar never describes a file that does not exist.
                    // A failure here costs the name, not the file, so it is not fatal.
                    try {
                        ReceivedFileMetadata.write(
                            target = File(ready, "$transferId${ReceivedFileMetadata.SUFFIX}"),
                            displayName = metadata.displayName,
                            mimeType = metadata.mimeType,
                            sizeBytes = target.length(),
                        )
                    } catch (e: IOException) {
                        // Ignored on purpose: the file is still usable, just less well labelled.
                    }
                }
                evict()
                target
            } else {
                partial.delete()
                null
            }
        } catch (e: SecurityException) {
            partial.delete()
            null
        }
    }

    /** What [promote] should remember about a received file. */
    data class PromotedMetadata(val displayName: String, val mimeType: String)

    /**
     * Every complete received file, newest first, with whatever metadata was recorded.
     *
     * A payload with no readable sidecar still appears: losing a name is not a reason to hide a
     * file the user was told they received. It gets its on-disk name and a generic type instead.
     */
    fun receivedFiles(): List<ReceivedFile> = payloadFiles()
        .mapNotNull { file ->
            val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()
                ?: return@mapNotNull null
            val sidecar = File(ready, "$id${ReceivedFileMetadata.SUFFIX}")
            val parsed = ReceivedFileMetadata.read(sidecar, file)
            ReceivedFile(
                transferId = id,
                displayName = parsed?.displayName ?: file.name,
                mimeType = parsed?.mimeType ?: FileTransferPolicy.FALLBACK_MIME,
                sizeBytes = file.length(),
                file = file,
                receivedAtMs = file.lastModified(),
            )
        }
        .sortedByDescending { it.receivedAtMs }

    /** Deletes one received file and its sidecar. */
    fun deleteReceived(transferId: UUID): Boolean {
        var deleted = false
        ready.listFiles()?.forEach { file ->
            if (file.isFile && file.nameWithoutExtension == transferId.toString()) {
                if (file.delete()) deleted = true
            }
        }
        return deleted
    }

    /** Free space on the volume holding the cache, for deciding whether an offer can be accepted. */
    fun usableSpaceBytes(): Long = try {
        ensureDirectories()
        availableSpaceProvider(root)
    } catch (e: SecurityException) {
        0
    }

    /** Deletes any `.part` files left by a crash or a killed process. Call at startup. */
    fun sweepPartials() {
        incoming.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part")) file.delete()
        }
    }

    /**
     * Deletes received files older than [FileTransferPolicy.PENDING_EXPIRY_MS].
     *
     * Enforces the retention window that policy advertises. Runs on the same startup pass as
     * [sweepPartials]: a received file the user never opened is not worth keeping indefinitely,
     * and the cache directory is reclaimable by the system regardless.
     *
     * Returns the number of files deleted so a caller can log a count without logging names.
     */
    fun sweepExpired(): Int {
        val cutoff = clock() - FileTransferPolicy.PENDING_EXPIRY_MS
        var deleted = 0
        payloadFiles().forEach { file ->
            // lastModified() returns 0 when it cannot be read; treat that as "do not touch"
            // rather than as 1970 and therefore expired.
            val stamp = file.lastModified()
            if (stamp in 1 until cutoff && deleteWithSidecar(file)) deleted++
        }
        return deleted
    }

    fun clear() {
        incoming.listFiles()?.forEach { it.delete() }
        ready.listFiles()?.forEach { it.delete() }
    }

    fun readyFiles(): List<File> = ready.listFiles()?.filter { it.isFile }.orEmpty()

    /**
     * The received files themselves, excluding metadata sidecars.
     *
     * The distinction matters: a sidecar must not count as an entry against the retention budget
     * or the batch limits, or half the budget would be spent on three-line text files and the
     * effective file count would be half what policy says.
     */
    private fun payloadFiles(): List<File> =
        readyFiles().filterNot { it.name.endsWith(ReceivedFileMetadata.SUFFIX) }

    /**
     * Oldest-first eviction until both the byte and entry budgets are met.
     *
     * Budgets count payloads, and evicting one takes its sidecar with it so no orphan metadata
     * accumulates.
     */
    private fun evict() {
        val files = payloadFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var remaining = files.size
        for (victim in files) {
            if (total <= maxBytes && remaining <= maxEntries) break
            total -= victim.length()
            remaining--
            deleteWithSidecar(victim)
        }
    }

    /** Deletes a payload and the sidecar that describes it. */
    private fun deleteWithSidecar(payload: File): Boolean {
        val id = payload.nameWithoutExtension
        File(ready, "$id${ReceivedFileMetadata.SUFFIX}").delete()
        return payload.delete()
    }

    /** Only a short alphanumeric extension survives; anything else is dropped. */
    private fun safeExtension(raw: String): String {
        val cleaned = raw.removePrefix(".").filter { it.isLetterOrDigit() }.take(8).lowercase()
        return if (cleaned.isEmpty()) "" else ".$cleaned"
    }

    companion object {
        /**
         * Retention budget, taken from [FileTransferPolicy] rather than restated.
         *
         * These are the same numbers the documentation quotes and the sender validates against,
         * which is the point: a local copy is how the entry budget ended up below
         * [FileTransferPolicy.MAX_FILES_PER_BATCH] without anyone noticing.
         */
        const val DEFAULT_MAX_BYTES = FileTransferPolicy.MAX_PENDING_BYTES
        const val DEFAULT_MAX_ENTRIES = FileTransferPolicy.MAX_PENDING_FILES
        const val DIRECTORY_NAME = "relay_cache"

        /** Headroom kept free so accepting a transfer never fills the volume completely. */
        const val STORAGE_HEADROOM_BYTES = 32L * 1024 * 1024

        fun openIn(cacheDir: File): ContentCache = ContentCache(File(cacheDir, DIRECTORY_NAME))

        /** The Android-aware variant, which can also count reclaimable cache space. */
        fun openIn(context: android.content.Context): ContentCache = ContentCache(
            root = File(context.cacheDir, DIRECTORY_NAME),
            availableSpaceProvider = { file -> allocatableBytes(context, file) },
        )

        private fun allocatableBytes(context: android.content.Context, file: File): Long {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val manager = androidx.core.content.ContextCompat
                    .getSystemService(context, android.os.storage.StorageManager::class.java)
                if (manager != null) {
                    try {
                        return manager.getAllocatableBytes(manager.getUuidForPath(file))
                    } catch (e: java.io.IOException) {
                        // Fall through to the plain free-space answer.
                    }
                }
            }
            return file.usableSpace
        }
    }
}

/** Thrown by cache operations that cannot proceed. Callers turn this into a protocol error. */
class ContentCacheException(message: String, cause: Throwable? = null) : IOException(message, cause)
