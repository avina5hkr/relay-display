package com.avinash.relaydisplay.content

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Bounded on-disk cache for received content.
 *
 * Two properties matter:
 *  - filenames are generated here from a transfer id, never derived from anything the peer sent,
 *    so a hostile display name cannot influence where a byte lands, and
 *  - the cache is bounded by both bytes and entries, and evicts oldest first, so a peer cannot
 *    fill the phone by sending file after file.
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
    fun promote(partial: File, transferId: UUID, extension: String?): File? {
        ensureDirectories()
        val suffix = extension?.let { safeExtension(it) }.orEmpty()
        val target = File(ready, "$transferId$suffix")
        return try {
            if (target.exists()) target.delete()
            if (partial.renameTo(target)) {
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

    fun clear() {
        incoming.listFiles()?.forEach { it.delete() }
        ready.listFiles()?.forEach { it.delete() }
    }

    fun readyFiles(): List<File> = ready.listFiles()?.filter { it.isFile }.orEmpty()

    /** Oldest-first eviction until both the byte and entry budgets are met. */
    private fun evict() {
        val files = readyFiles().sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        var index = 0
        while (index < files.size && (total > maxBytes || files.size - index > maxEntries)) {
            val victim = files[index]
            total -= victim.length()
            victim.delete()
            index++
        }
    }

    /** Only a short alphanumeric extension survives; anything else is dropped. */
    private fun safeExtension(raw: String): String {
        val cleaned = raw.removePrefix(".").filter { it.isLetterOrDigit() }.take(8).lowercase()
        return if (cleaned.isEmpty()) "" else ".$cleaned"
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 12
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
