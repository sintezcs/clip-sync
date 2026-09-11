package com.clipsync.model

import com.clipsync.overlay.ClipSender
import org.junit.Assert.*
import org.junit.Test

class ImagePayloadLimitsTest {
    @Test fun binaryAndEnvelopeBoundariesCoverExactlyFiftyMiB() {
        assertEquals(50 * 1024 * 1024, ClipPayload.MAX_IMAGE_BYTES)
        assertEquals(69_909_164, ClipPayload.MAX_JSON_CHARS)
        ClipPayload.validateImageByteCount(ClipPayload.MAX_IMAGE_BYTES)
        assertThrows(IllegalArgumentException::class.java) { ClipPayload.validateImageByteCount(ClipPayload.MAX_IMAGE_BYTES + 1) }
        ClipPayload.validateEnvelopeLength(ClipPayload.MAX_JSON_CHARS)
        assertThrows(IllegalArgumentException::class.java) { ClipPayload.validateEnvelopeLength(ClipPayload.MAX_JSON_CHARS + 1) }
        assertEquals(1024 * 1024, ClipPayload.MAX_TEXT_BYTES)
    }

    @Test fun imageLargerThanPreviousCapRoundTripsThroughActualWireParser() {
        val payload = ClipPayloadBuilder.image("image/png", ByteArray(9 * 1024 * 1024))
        val parsed = ClipPayload.fromJson(payload.toJson())
        assertEquals(payload.data.length, parsed.data.length)
        assertEquals(payload, parsed)
    }

    @Test fun canonicalBase64RemainsStrictWithoutSecondEncodedCopy() {
        val now = System.currentTimeMillis()
        for (encoded in listOf("Zg==", "Zm8=", "Zm9v")) ClipPayload("text", "text/plain", encoded, now, "event").validate(now)
        for (encoded in listOf("Zh==", "Zm9=", "Zg", "Zg=", "Zg==\n")) {
            assertThrows(IllegalArgumentException::class.java) { ClipPayload("text", "text/plain", encoded, now, "event").validate(now) }
        }
    }

    @Test fun largerUploadGetsMoreTimeWithinHmacFreshnessWindow() {
        assertEquals(15L, ClipSender.callTimeoutSeconds(1024))
        assertEquals(45L, ClipSender.callTimeoutSeconds(ClipPayload.MAX_JSON_CHARS))
        assertTrue(ClipSender.callTimeoutSeconds(ClipPayload.MAX_JSON_CHARS) < 60L)
    }
}
