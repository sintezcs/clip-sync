package com.clipsync.shizuku

import android.content.ClipData
import android.os.Bundle
import java.security.MessageDigest

/** A single clipboard observation; content never goes into logs. */
data class ClipboardSnapshot(
    val identity: String,
    val mime: String?,
    val text: String?,
    val uri: String?,
    val sensitive: Boolean,
    val sourceEvent: String? = null,
) {
    fun toBundle() = Bundle().apply {
        putString("identity", identity)
        putString("mime", mime)
        putString("text", text)
        putString("uri", uri)
        putBoolean("sensitive", sensitive)
        putString("sourceEvent", sourceEvent)
    }

    companion object {
        // Leave ample Binder transaction headroom for UTF-16 and metadata.
        const val MAX_TEXT_CHARS = 100_000
        fun fromBundle(bundle: Bundle) = ClipboardSnapshot(
            requireNotNull(bundle.getString("identity")), bundle.getString("mime"),
            bundle.getString("text"), bundle.getString("uri"), bundle.getBoolean("sensitive"), bundle.getString("sourceEvent"),
        )

        fun fromClip(clip: ClipData?): ClipboardSnapshot {
            if (clip == null || clip.itemCount == 0) return ClipboardSnapshot("empty", null, null, null, false)
            val description = clip.description
            val sensitive = description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
            val item = clip.getItemAt(0)
            val uri = item.uri?.toString()
            val rawText = item.text
            val advertised = (0 until description.mimeTypeCount).map(description::getMimeType)
            // Rich-text clips carry their plain representation in Item.text. Never parse HTML,
            // read providers, or turn an intent/URI into text during automatic observation.
            val plainRepresentation = rawText != null && uri == null && item.intent == null &&
                advertised.any { it == "text/plain" || it == "text/html" }
            val mime = advertised.firstOrNull { it.startsWith("image/") && uri != null }
                ?: if (plainRepresentation) "text/plain" else advertised.firstOrNull()
            require(rawText == null || rawText.length <= MAX_TEXT_CHARS) { "Clipboard text exceeds automatic capture limit" }
            require(uri == null || uri.length <= 8192) { "Clipboard URI too long" }
            val text = rawText?.toString()
            val hash = MessageDigest.getInstance("SHA-256")
            for (part in listOf(description.timestamp.toString(), mime, text, uri, sensitive.toString())) {
                val bytes = (part ?: "").toByteArray(Charsets.UTF_8)
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                hash.update(bytes)
            }
            val id = hash.digest().joinToString("") { "%02x".format(it) }
            return ClipboardSnapshot(id, mime, if (sensitive) null else text, if (sensitive) null else uri, sensitive, description.extras?.getString("com.clipsync.EVENT_NONCE"))
        }
    }
}
