package com.clipsync.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Receives only an opaque SHA-256 event key; no clipboard bytes or plaintext credentials. */
interface ReplayStore {
    /** Returns true only after this event is durably recorded, or false for duplicate/full storage. */
    fun accept(key: String): Boolean
}

object ReplayKey {
    fun of(fingerprint: String, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in listOf("clipsync-replay-v1", fingerprint, nonce)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Explicit test injection. Production must install DurableReplayStore from Application.onCreate. */
class MemoryReplayStore : ReplayStore {
    private val ledger = EventLedger()
    override fun accept(key: String): Boolean = ledger.accept(key)
}

/**
 * Bounded, atomic app-private journal. Load is lazy, so the caller MUST run accept on an IO thread.
 * A corrupt/unwritable store latches failure for this instance; it is never silently reset.
 * Records precede clipboard/network effects: a crash can lose an event, but cannot replay it.
 */
class DurableReplayStore(
    private val file: File,
    private val capacity: Int = 4096,
    private val retentionMs: Long = 600_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
) : ReplayStore {
    private var entries: LinkedHashMap<String, Long>? = null
    private var failed = false
    private var lastWrittenAt = 0L

    init {
        require(capacity in 1..4096)
        require(retentionMs in 1..600_000)
    }

    @Synchronized override fun accept(key: String): Boolean {
        require(KEY.matches(key)) { "Invalid replay key" }
        if (failed) throw IOException("Replay protection unavailable")
        try {
            val now = clockMs()
            require(now >= 0 && now <= Long.MAX_VALUE - retentionMs) { "Invalid replay clock" }
            val current = entries ?: load().also { entries = it }
            require(now >= lastWrittenAt) { "Replay clock moved backwards" }
            val next = LinkedHashMap(current.filterValues { it > now })
            if (key in next || next.size >= capacity) return false
            next[key] = now + retentionMs
            persist(next, now)
            entries = next
            lastWrittenAt = now
            return true
        } catch (error: Exception) {
            failed = true
            throw IOException("Replay protection unavailable", error)
        }
    }

    private fun load(): LinkedHashMap<String, Long> {
        if (!file.exists()) return LinkedHashMap()
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "Invalid replay storage" }
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Replay storage too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.all { it.toInt() in 10..126 }) { "Invalid replay storage" }
        val lines = String(bytes, Charsets.US_ASCII).split('\n')
        require(lines.size >= 4 && lines.first() == HEADER && lines.last() == "") { "Invalid replay storage" }
        require(lines[1].startsWith("TIME ")) { "Missing replay clock" }
        lastWrittenAt = lines[1].removePrefix("TIME ").toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid replay clock")
        val signedBody = lines.dropLast(2).joinToString("\n", postfix = "\n")
        require(lines[lines.lastIndex - 1] == "SHA256 ${checksum(signedBody)}") { "Replay storage integrity failure" }
        val records = lines.subList(2, lines.lastIndex - 1)
        require(records.size <= capacity) { "Replay storage capacity exceeded" }
        val result = LinkedHashMap<String, Long>()
        for (line in records) {
            val parts = line.split(' ')
            require(parts.size == 2 && KEY.matches(parts[0])) { "Invalid replay entry" }
            val expiry = parts[1].toLongOrNull()
            require(expiry != null && expiry > 0) { "Invalid replay expiry" }
            require(result.put(parts[0], expiry) == null) { "Duplicate replay entry" }
        }
        return result
    }

    private fun persist(next: LinkedHashMap<String, Long>, now: Long) {
        val parent = file.absoluteFile.parentFile ?: throw IOException("Missing replay directory")
        ensureDirectory(parent)
        val temporary = File(parent, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                val body = buildString {
                    append(HEADER).append('\n')
                    append("TIME ").append(now).append('\n')
                    next.forEach { (key, expiry) -> append(key).append(' ').append(expiry).append('\n') }
                }
                output.write((body + "SHA256 " + checksum(body) + "\n").toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            // Same-directory atomic replacement keeps the previous valid journal on failure.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Rename durability is directory metadata, distinct from syncing the temporary file bytes.
            syncDirectory(parent)
        } finally {
            temporary.delete()
        }
    }

    private fun ensureDirectory(parent: File) {
        val missing = ArrayList<File>()
        var ancestor = parent
        while (!ancestor.exists()) {
            missing.add(ancestor)
            ancestor = ancestor.parentFile ?: throw IOException("Missing replay directory ancestor")
        }
        require(ancestor.isDirectory) { "Cannot create replay directory" }
        // An earlier failed/crashed mkdir can still be visible without its parent metadata being synced.
        syncDirectory(ancestor)
        ancestor.parentFile?.let(syncDirectory)
        for (directory in missing.asReversed()) {
            require(directory.mkdir() || directory.isDirectory) { "Cannot create replay directory" }
            syncDirectory(directory)
            // Persist the newly created directory's name in its containing directory too.
            syncDirectory(requireNotNull(directory.parentFile))
        }
    }

    private fun checksum(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HEADER = "CLIPSYNC_REPLAY_V1"
        private const val MAX_FILE_BYTES = 400_000
        private val KEY = Regex("[0-9a-f]{64}")
    }
}
