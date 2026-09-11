package com.clipsync.images

import android.graphics.BitmapFactory

/** Validate compressed format and dimensions without allocating the full bitmap. */
object ImageSafety {
    fun validate(bytes: ByteArray, mime: String) {
        require(detectMime(bytes) == mime) { "Invalid image format" }
    }

    fun detectMime(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) { "Image too large or empty" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outMimeType in setOf("image/png", "image/jpeg")) { "Invalid image format" }
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
            options.outWidth.toLong() * options.outHeight <= 24_000_000L) { "Image dimensions exceed limit" }
        return requireNotNull(options.outMimeType)
    }
}
