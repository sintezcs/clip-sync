package com.clipsync.images

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Unit tests for the non-Android parts of [ImageCache]. The Android
 * framework dependency ([FileProvider] resolution) is avoided by exercising
 * [ImageCache.writeToFile] and [ImageCache.cleanupOlderThan] directly with
 * a temp-folder backed provider.
 */
class ImageCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newCache(root: File): ImageCache = ImageCache { root }

    @Test
    fun writeToFile_persists_bytes_with_extension() {
        val root = tmp.newFolder("clipsync")
        val cache = newCache(root)
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val file = cache.writeToFile(bytes, "png")
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".png"))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun writeToFile_uses_bin_for_blank_ext() {
        val root = tmp.newFolder("clipsync")
        val cache = newCache(root)
        val file = cache.writeToFile(byteArrayOf(1, 2, 3), "")
        assertTrue(file.name.endsWith(".bin"))
    }

    @Test
    fun cleanupOlderThan_removes_stale_files_only() {
        val root = tmp.newFolder("clipsync")
        val cache = newCache(root)
        val oldFile = cache.writeToFile(byteArrayOf(1), "png")
        val now = System.currentTimeMillis()
        // 48h in the past
        val fresh = cache.writeToFile(byteArrayOf(2), "png")
        assertTrue(oldFile.setLastModified(now - 48L * 3600_000L))

        val deleted = cache.cleanupOlderThan(maxAgeMs = 24L * 3600_000L, now = now)
        assertEquals(1, deleted)
        assertFalse(oldFile.exists())
        assertTrue(fresh.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun remote_extension_cannot_escape_cache() {
        newCache(tmp.newFolder("escape")).writeToFile(byteArrayOf(1), "png/../../outside")
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversized_image_is_rejected_before_writing() {
        val root = tmp.newFolder("oversize")
        try {
            newCache(root).writeToFile(ByteArray(com.clipsync.model.ClipPayload.MAX_IMAGE_BYTES + 1), "png")
        } finally { assertTrue(root.listFiles()!!.isEmpty()) }
    }

    @Test
    fun largeReplacementSurvivesInsertionUntilAppliedAndCleanupPreservesCurrent() {
        val root = tmp.newFolder("large-replacement")
        val marker = File(tmp.root, "current-image")
        val cache = ImageCache({ root }, marker)
        val previous = File(root, "old.png")
        val candidate = File(root, "new.png")
        for (file in listOf(previous, candidate)) {
            RandomAccessFile(file, "rw").use { it.setLength(50L * 1024 * 1024) }
        }
        marker.writeText(previous.name)
        cache.enforceMaxSize(preserveCandidate = candidate)
        assertTrue(previous.exists())
        assertTrue(candidate.exists())
        assertEquals(100L * 1024 * 1024, root.listFiles()!!.sumOf { it.length() })
        // The same pin switch + enforcement is used by markApplied after a clipboard write.
        marker.writeText(candidate.name)
        cache.enforceMaxSize()
        assertFalse(previous.exists())
        assertTrue(candidate.exists())
        val failedCandidate = File(root, "failed.png")
        RandomAccessFile(failedCandidate, "rw").use { it.setLength(50L * 1024 * 1024) }
        cache.enforceMaxSize(preserveCandidate = failedCandidate)
        cache.cleanupOlderThan() // A discarded candidate must not displace the current URI.
        assertTrue(candidate.exists())
        assertFalse(failedCandidate.exists())
    }

    @Test
    fun cleanupOlderThan_on_missing_dir_returns_zero() {
        val root = File(tmp.root, "does-not-exist")
        val cache = newCache(root)
        assertEquals(0, cache.cleanupOlderThan())
    }
}
