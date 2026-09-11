package com.clipsync.sync

import com.clipsync.model.ClipPayload
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class ClipboardEventsTest {
    @Before fun setupReplayStore() { ClipboardEvents.installStore(MemoryReplayStore()) }
    @Test fun `nonce replay suppressed but intentional repeated contents accepted`() {
        val ledger = EventLedger()
        assertTrue(ledger.accept("event-a"))
        assertFalse(ledger.accept("event-a"))
        assertTrue(ledger.accept("event-b"))
    }
    @Test fun `full ledger refuses new events without evicting live replay protection`() {
        var now = 0L
        val ledger = EventLedger(2, 100) { now }
        assertTrue(ledger.accept("a"))
        assertTrue(ledger.accept("b"))
        assertFalse(ledger.accept("c"))
        assertFalse(ledger.accept("a"))
        now = 100
        assertTrue(ledger.accept("c"))
    }
    @Test fun `outbound echo rejected before an inbound effect`() {
        val payload = ClipPayload("text", "text/plain", "YQ==", 1, "outbound-test")
        ClipboardEvents.recordOutbound(payload, "test-peer")
        assertFalse(ClipboardEvents.shouldAcceptInbound(payload, "test-peer"))
        assertTrue(ClipboardEvents.shouldAcceptInbound(payload.copy(nonce = "intentional-recopy-test"), "test-peer"))
    }
    @Test fun `full digests distinguish Java hash collisions`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(ClipboardEvents.digest("Aa"), ClipboardEvents.digest("BB"))
        assertEquals(64, ClipboardEvents.digest("Aa").length)
    }
    @Test fun `latest pending wins and supersedes in-flight eligibility`() {
        val box = LatestOutbox<String>()
        val a = box.offer("a", 0)
        box.offer("b", 1)
        assertFalse(box.current(a, 2))
        assertEquals("b", box.take(2)?.value)
        assertNull(box.take(2))
    }
    @Test fun `offline item expires and cannot be replayed`() {
        val box = LatestOutbox<String>(100)
        val a = box.offer("a", 10)
        assertTrue(box.current(a, 109))
        assertFalse(box.current(a, 110))
        assertNull(box.take(110))
    }
    @Test fun `pause remote apply or sensitive copy invalidates queued item`() {
        val box = LatestOutbox<String>()
        val a = box.offer("a", 0)
        box.clear()
        assertFalse(box.current(a, 1))
        assertNull(box.take(1))
    }
    @Test fun `startup baseline does not send and same content recopy changes identity`() {
        val order = ClipboardOrder()
        assertFalse(order.observe("digest:platform-time-1", 100))
        assertFalse(order.observe("digest:platform-time-1", 101))
        assertTrue(order.observe("digest:platform-time-2", 102))
        assertFalse(order.canApply(101))
        assertFalse(order.canApply(102))
        assertTrue(order.canApply(103))
    }
    @Test fun `remote apply is baseline not a local copy and next real copy is retained`() {
        val order = ClipboardOrder()
        order.observe("initial", 1)
        assertTrue(order.acknowledge("nonce-a", "nonce-a", "remote-a"))
        assertFalse(order.observe("remote-a", 2))
        assertTrue(order.observe("local-b", 3))
        assertFalse(order.canApply(2))
    }
    @Test fun `user copy racing remote write is not consumed as an acknowledgement`() {
        val order = ClipboardOrder()
        order.observe("initial", 1)
        assertFalse(order.acknowledge("remote-nonce", null, "local-b"))
        assertEquals("initial", order.identity)
        assertTrue(order.observe("local-b", 3))
        assertFalse(order.canApply(2))
    }
    @Test fun `older remote event cannot replace a newer accepted remote event`() {
        val order = ClipboardOrder()
        assertTrue(order.acceptRemote(200))
        assertFalse(order.acceptRemote(199))
        assertTrue(order.acceptRemote(201))
    }
    @Test fun `in-flight image operation is rejected when either peer credential changes`() {
        val operation = PeerOperation("trusted-pin", "token-a")
        assertTrue(operation.isCurrent("trusted-pin", "token-a", true))
        assertFalse(operation.isCurrent("trusted-pin", "token-b", true))
        assertFalse(operation.isCurrent("new-pin", "token-a", true))
        assertFalse(operation.isCurrent("new-pin", "token-b", true))
    }
    @Test fun `unpair or loss of verified trust invalidates pending operation`() {
        val operation = PeerOperation("trusted-pin", "token-a")
        assertFalse(operation.isCurrent(null, null, false))
        assertFalse(operation.isCurrent("trusted-pin", "token-a", false))
    }
    @Test fun `pending old-peer payload cannot pass gate even before service restart`() {
        val operation = PeerOperation("old-pin", "old-token")
        val outbox = LatestOutbox<PeerOperation>()
        outbox.offer(operation, 1)
        val pending = outbox.take(2)!!
        assertTrue(outbox.current(pending, 2))
        assertFalse(pending.value.isCurrent("new-pin", "new-token", true))
    }
}
