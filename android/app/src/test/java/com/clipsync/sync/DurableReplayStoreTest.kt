package com.clipsync.sync

import com.clipsync.model.ClipPayload
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DurableReplayStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "private/replay-v1")
    private fun key(nonce: String, peer: String = "synthetic-peer") = ReplayKey.of(peer, nonce)

    @Test fun `accepted nonce remains rejected after store recreation`() {
        val file = file()
        assertTrue(DurableReplayStore(file, clockMs = { 100 }).accept(key("a")))
        assertFalse(DurableReplayStore(file, clockMs = { 101 }).accept(key("a")))
        assertTrue(DurableReplayStore(file, clockMs = { 102 }).accept(key("b")))
        assertFalse(DurableReplayStore(file, clockMs = { 103 }).accept(key("a")))
    }

    @Test fun `expiry survives recreation and expires at deadline`() {
        val file = file()
        assertTrue(DurableReplayStore(file, retentionMs = 100, clockMs = { 10 }).accept(key("a")))
        assertFalse(DurableReplayStore(file, retentionMs = 100, clockMs = { 109 }).accept(key("a")))
        assertTrue(DurableReplayStore(file, retentionMs = 100, clockMs = { 110 }).accept(key("a")))
    }

    @Test fun `capacity fails closed without evicting an unexpired event`() {
        val file = file()
        val store = DurableReplayStore(file, capacity = 2, clockMs = { 10 })
        assertTrue(store.accept(key("a")))
        assertTrue(store.accept(key("b")))
        assertFalse(store.accept(key("c")))
        val restored = DurableReplayStore(file, capacity = 2, clockMs = { 11 })
        assertFalse(restored.accept(key("a")))
        assertFalse(restored.accept(key("c")))
    }

    @Test fun `corruption and valid-prefix truncation are never silently reset`() {
        val file = file()
        DurableReplayStore(file, clockMs = { 10 }).accept(key("a"))
        val original = file.readText()
        file.writeText(original.substringBefore("SHA256 "))
        assertThrows(IOException::class.java) { DurableReplayStore(file, clockMs = { 11 }).accept(key("b")) }
        assertEquals(original.substringBefore("SHA256 "), file.readText())
        file.writeText(original.replace(key("a").first(), if (key("a").first() == 'a') 'b' else 'a'))
        assertThrows(IOException::class.java) { DurableReplayStore(file, clockMs = { 12 }).accept(key("b")) }
    }

    @Test fun `unwritable directory blocks acceptance before the caller effect and latches failure`() {
        val blocker = File(temporary.root, "blocked").apply { writeText("not a directory") }
        val file = File(blocker, "events")
        val store = DurableReplayStore(file, clockMs = { 10 })
        var applied = false
        assertThrows(IOException::class.java) { if (store.accept(key("a"))) applied = true }
        assertFalse(applied)
        assertTrue(blocker.delete())
        assertTrue(blocker.mkdir())
        assertThrows(IOException::class.java) { store.accept(key("b")) }
        assertFalse(file.exists())
    }

    @Test fun `file contains only hashed peer-event identifiers and expiry metadata`() {
        val file = file()
        val peer = "private-peer-fingerprint"
        val nonce = "private-event-nonce"
        val payload = ClipPayload("text", "text/plain", "private clipboard contents", 1, nonce)
        ClipboardEvents.installStore(DurableReplayStore(file, clockMs = { 10 }))
        ClipboardEvents.recordOutbound(payload, peer)
        val disk = file.readText()
        assertFalse(disk.contains(peer))
        assertFalse(disk.contains(nonce))
        assertFalse(disk.contains(payload.data))
        assertTrue(disk.contains(key(nonce, peer)))
        ClipboardEvents.installStore(DurableReplayStore(file, clockMs = { 11 }))
        assertFalse(ClipboardEvents.shouldAcceptInbound(payload, peer))
        assertTrue(ClipboardEvents.shouldAcceptInbound(payload, "another-peer"))
    }

    @Test fun `clock rollback does not reopen replay window`() {
        val file = file()
        DurableReplayStore(file, clockMs = { 1000 }).accept(key("a"))
        assertThrows(IOException::class.java) { DurableReplayStore(file, clockMs = { 999 }).accept(key("a")) }
    }

    @Test fun `incomplete temporary write preserves last committed journal`() {
        val file = file()
        DurableReplayStore(file, clockMs = { 10 }).accept(key("a"))
        File(file.parentFile, file.name + ".tmp").writeText("partial write")
        val restored = DurableReplayStore(file, clockMs = { 11 })
        assertFalse(restored.accept(key("a")))
        assertTrue(restored.accept(key("b")))
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }
    @Test fun `new directory ancestry and final rename are synced before acceptance`() {
        val file = File(temporary.root, "new-parent/replay/events")
        val synced = mutableListOf<File>()
        val store = DurableReplayStore(file, clockMs = { 10 }, syncDirectory = { synced.add(it) })
        assertTrue(store.accept(key("a")))
        assertTrue(synced.contains(temporary.root))
        assertTrue(synced.contains(file.parentFile.parentFile))
        assertEquals(file.parentFile, synced.last())
        assertEquals(2, synced.count { it == file.parentFile })
    }

    @Test fun `directory sync failure after rename refuses caller effect and latches closed`() {
        val file = File(temporary.newFolder("existing-parent"), "events")
        val store = DurableReplayStore(file, clockMs = { 10 }, syncDirectory = { if (file.isFile) throw IOException("Injected directory fsync failure") })
        var applied = false
        assertThrows(IOException::class.java) { if (store.accept(key("a"))) applied = true }
        assertFalse(applied)
        assertTrue("Rename happened but cannot yet be acknowledged durable", file.isFile)
        assertThrows(IOException::class.java) { store.accept(key("b")) }
        // A restart also conservatively suppresses the recorded event, even though no effect was allowed.
        assertFalse(DurableReplayStore(file, clockMs = { 11 }).accept(key("a")))
    }

    @Test fun `new directory sync failure prevents recording or applying an event`() {
        val file = file()
        val store = DurableReplayStore(file, clockMs = { 10 }, syncDirectory = { throw IOException("Injected mkdir sync failure") })
        assertThrows(IOException::class.java) { store.accept(key("a")) }
        assertFalse(file.exists())
    }
}
