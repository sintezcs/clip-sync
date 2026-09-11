package com.clipsync.images

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.clipsync.util.L
import java.io.File
import java.util.UUID

/**
 * Writes incoming image bytes under `cacheDir/clipsync/<uuid>.<ext>` and
 * exposes them to other apps via a `FileProvider` with authority
 * [AUTHORITY]. Stale files older than [DEFAULT_MAX_AGE_MS] are pruned on
 * demand.
 */
class ImageCache private constructor(
    private val context: Context?,
    private val rootProvider: () -> File,
    private val markerProvider: () -> File?
) {

    constructor(context: Context) : this(context, { File(context.cacheDir, DIR_NAME) }, { File(context.cacheDir, "clipsync-current-image") })

    /** Test-only: inject a custom root folder without needing a Context. */
    internal constructor(rootProvider: () -> File) : this(null, rootProvider, { null })
    internal constructor(rootProvider: () -> File, marker: File) : this(null, rootProvider, { marker })

    /** Absolute directory under which incoming images live. */
    val dir: File
        get() {
            val f = rootProvider()
            if (!f.exists()) f.mkdirs()
            return f
        }

    /**
     * Persist [bytes] as `<uuid>.<ext>` and return a content `Uri` that
     * downstream apps can read via [FileProvider].
     */
    fun writeImage(bytes: ByteArray, ext: String): Uri {
        val ctx = requireNotNull(context) { "Context required for FileProvider" }
        val mime = if (ext.lowercase() in setOf("jpg", "jpeg")) "image/jpeg" else "image/png"
        ImageSafety.validate(bytes, mime)
        val file = writeToFile(bytes, ext)
        return FileProvider.getUriForFile(ctx, AUTHORITY, file)
    }

    /** Pin only an actually applied image, never a discarded incoming candidate. */
    fun markApplied(uri: Uri) {
        val ctx = requireNotNull(context)
        require(uri.authority == AUTHORITY)
        val name = requireNotNull(uri.lastPathSegment)
        require(name.matches(Regex("[a-fA-F0-9-]+\\.(png|jpg|jpeg)")))
        require(File(dir, name).isFile)
        File(ctx.cacheDir, "clipsync-current-image").writeText(name)
        enforceMaxSize()
    }

    /** Package-private file writer — exposed for tests. */
    internal fun writeToFile(bytes: ByteArray, ext: String): File {
        com.clipsync.model.ClipPayload.validateImageByteCount(bytes.size)
        val safeExt = ext.lowercase().ifBlank { "bin" }
        require(safeExt in setOf("png", "jpg", "jpeg", "bin")) { "Unsupported cache extension" }
        cleanupOlderThan()
        val file = File(dir, "${UUID.randomUUID()}.$safeExt")
        file.outputStream().use { it.write(bytes) }
        enforceMaxSize(preserveCandidate = file)
        return file
    }

    /**
     * Evict oldest files until the cache directory is under [MAX_CACHE_SIZE_BYTES].
     */
    fun enforceMaxSize(preserveCandidate: File? = null) {
        val root = rootProvider()
        if (!root.exists() || !root.isDirectory) return
        val files = root.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        var evicted = 0
        for (file in files) {
            if (totalSize <= MAX_CACHE_SIZE_BYTES) break
            if (isProtected(file) || file == preserveCandidate) continue
            val size = file.length()
            if (file.delete()) { totalSize -= size; evicted++ }
        }
        if (evicted > 0 && context != null) L.event("ImageCache", "evicted $evicted files to stay under ${MAX_CACHE_SIZE_BYTES / 1024 / 1024}MB")
    }

    /**
     * Delete files older than [maxAgeMs] from the cache directory.
     * Returns the number of files deleted.
     */
    fun cleanupOlderThan(maxAgeMs: Long = DEFAULT_MAX_AGE_MS, now: Long = System.currentTimeMillis()): Int {
        val root = rootProvider()
        if (!root.exists() || !root.isDirectory) return 0
        var deleted = 0
        val cutoff = now - maxAgeMs
        root.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff && !isProtected(f)) {
                if (f.delete()) deleted++
            }
        }
        enforceMaxSize()
        return deleted
    }

    private fun isProtected(file: File): Boolean {
        val marker = markerProvider() ?: return false
        // Protect the last issued URI for its documented 24-hour lifetime.
        return System.currentTimeMillis() - file.lastModified() < DEFAULT_MAX_AGE_MS &&
            marker.exists() && marker.readText().take(80) == file.name
    }

    companion object {
        const val AUTHORITY = "com.clipsync.fileprovider"
        const val DIR_NAME = "clipsync"
        const val DEFAULT_MAX_AGE_MS = 24L * 60L * 60L * 1000L // 24h
        private const val MAX_CACHE_SIZE_BYTES = 64L * 1024 * 1024 // bounded transient image storage
    }
}
