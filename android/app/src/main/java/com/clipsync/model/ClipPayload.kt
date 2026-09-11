package com.clipsync.model

import org.json.JSONObject

/**
 * Wire protocol payload shared between mac server and android client.
 * See docs/protocol.md and docs/phase-4-summary.md.
 *
 * Example: {"type":"text","mime":"text/plain","data":"<base64>","ts":172..., "nonce":"..."}
 */
data class ClipPayload(
    val type: String,      // "text" | "image" | "file"
    val mime: String,
    val data: String,      // base64 encoded payload
    val ts: Long,          // unix milliseconds
    val nonce: String,
    val name: String? = null
) {
    fun validate(nowMs: Long = System.currentTimeMillis()) {
        require(type == "text" || type == "image") { "Unsupported clipboard type" }
        require(if (type == "text") mime == "text/plain" else mime in IMAGE_MIMES) { "Unsupported MIME" }
        require(nonce.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid nonce" }
        name?.let {
            require(it.isNotEmpty() && it.length <= 255 && it != "." && it != ".." &&
                it.none { c -> c == '/' || c == '\\' || c.code < 32 || c.code == 127 }) { "Invalid name" }
        }
        require(ts >= 0 && nowMs >= 0 &&
            (if (ts > nowMs) ts - nowMs else nowMs - ts) < 300_000L) { "Timestamp out of range" }
        val limit = if (type == "text") MAX_TEXT_BYTES else MAX_IMAGE_BYTES
        require(data.length <= ((limit + 2) / 3) * 4 && data.length % 4 == 0) { "Payload too large or invalid base64" }
        val bytes = java.util.Base64.getDecoder().decode(data)
        require(bytes.size <= limit) { "Invalid payload encoding" }
        // Java's decoder accepts nonzero padding bits. Check those directly instead of
        // allocating a second ~67 MiB encoded String to round-trip a maximum-size image.
        if (data.endsWith("==")) {
            require(BASE64_ALPHABET.indexOf(data[data.length - 3]) and 15 == 0) { "Invalid payload encoding" }
        } else if (data.endsWith("=")) {
            require(BASE64_ALPHABET.indexOf(data[data.length - 2]) and 3 == 0) { "Invalid payload encoding" }
        }
        if (type == "text") {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes))
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("type", type)
        o.put("mime", mime)
        o.put("data", data)
        o.put("ts", ts)
        o.put("nonce", nonce)
        if (name != null) o.put("name", name)
        return o.toString()
    }

    companion object {
        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        const val MAX_TEXT_BYTES = 1024 * 1024
        const val MAX_IMAGE_BYTES = 50 * 1024 * 1024
        const val MAX_JSON_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4 + 4096
        val IMAGE_MIMES = setOf("image/png", "image/jpeg")
        internal fun validateEnvelopeLength(length: Int) {
            require(length in 0..MAX_JSON_CHARS) { "Frame too large" }
        }
        internal fun validateImageByteCount(length: Int) {
            require(length in 0..MAX_IMAGE_BYTES) { "Image too large" }
        }
        fun fromJson(raw: String): ClipPayload {
            validateEnvelopeLength(raw.length)
            val o = JSONObject(raw)
            fun string(key: String) = (o.get(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
            val timestamp = o.get("ts")
            require(timestamp is Long || timestamp is Int) { "Invalid timestamp" }
            return ClipPayload(
                type = string("type"),
                mime = string("mime"),
                data = string("data"),
                ts = (timestamp as Number).toLong(),
                nonce = string("nonce"),
                name = if (o.has("name")) string("name") else null
            ).also { it.validate() }
        }
    }
}
