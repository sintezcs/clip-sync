package com.clipsync.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class PayloadSecurityTest {
    private fun valid() = ClipPayload("text", "text/plain", "aGk=", System.currentTimeMillis(), "event-1")
    @Test fun rejectsMalformedPayloads() {
        listOf(
            valid().copy(ts = Long.MIN_VALUE), valid().copy(ts = Long.MAX_VALUE),
            valid().copy(type = "file"), valid().copy(mime = "text/html"),
            valid().copy(data = "aGk=\n"), valid().copy(data = "%%%="),
            valid().copy(nonce = "../bad"), valid().copy(name = "../secret"),
            valid().copy(name = "a\\b"), valid().copy(name = "."),
            valid().copy(data = "A".repeat(1_400_000))
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it.validate() } }
    }
    @Test fun rejectsCoercedTimestamp() {
        val raw = valid().toJson().replace(Regex("\"ts\":\\d+"), "\"ts\":1.5")
        assertThrows(IllegalArgumentException::class.java) { ClipPayload.fromJson(raw) }
    }
    @Test fun boundsReadBeforeReturning() {
        assertArrayEquals(byteArrayOf(1,2), ClipPayloadBuilder.readBounded(ByteArrayInputStream(byteArrayOf(1,2)),2))
        assertThrows(IllegalArgumentException::class.java) {
            ClipPayloadBuilder.readBounded(ByteArrayInputStream(ByteArray(3)),2)
        }
    }
}
