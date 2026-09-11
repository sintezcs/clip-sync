package com.clipsync.model

import java.util.Base64
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Constructs [ClipPayload] instances for outbound dispatch (Pixel → Mac).
 *
 * Extracted from the removed `ShareSender` so that any send path (overlay,
 * notification action, etc.) can build payloads without duplicating logic.
 */
object ClipPayloadBuilder {

    fun text(text: String, clockMs: Long = System.currentTimeMillis()): ClipPayload {
        require(text.length <= ClipPayload.MAX_TEXT_BYTES) { "Text too large" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ClipPayload.MAX_TEXT_BYTES) { "Text too large" }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return ClipPayload(
            type = "text",
            mime = "text/plain",
            data = b64,
            ts = clockMs,
            nonce = UUID.randomUUID().toString()
        )
    }

    fun image(mime: String, bytes: ByteArray, clockMs: Long = System.currentTimeMillis()): ClipPayload {
        require(mime in ClipPayload.IMAGE_MIMES && bytes.size <= MAX_IMAGE_BYTES) { "Unsupported or oversized image" }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return ClipPayload(
            type = "image",
            mime = mime,
            data = b64,
            ts = clockMs,
            nonce = UUID.randomUUID().toString()
        )
    }

    fun file(mime: String, name: String, bytes: ByteArray, clockMs: Long = System.currentTimeMillis()): ClipPayload {
        throw IllegalArgumentException("Generic file transfer is disabled")
    }

    fun readBounded(stream: InputStream, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray {
        require(maxBytes in 0..MAX_IMAGE_BYTES)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Read cancelled")
            val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
            if (count < 0) break
            require(count <= maxBytes - output.size()) { "Content too large" }
            if (count == 0) {
                val byte = stream.read()
                if (byte < 0) break
                require(output.size() < maxBytes) { "Content too large" }
                output.write(byte)
            } else output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    const val MAX_IMAGE_BYTES: Int = ClipPayload.MAX_IMAGE_BYTES
    const val MAX_FILE_BYTES: Int = ClipPayload.MAX_IMAGE_BYTES
}
