package com.clipsync.images

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.OutputStream
import java.nio.ByteBuffer

/** Automatic clipboard input only. HEIF is decoded locally; the wire remains JPEG/PNG. */
object ClipboardImageConverter {
    data class WireImage(val mime: String, val bytes: ByteArray)
    val INPUT_MIMES = setOf("image/png", "image/jpeg", "image/heic", "image/heif")
    private val HEIF_MIMES = setOf("image/heic", "image/heif")
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_DIMENSION = 8192
    const val MAX_PIXELS = 24_000_000L

    /** Run off the main thread. Never opens a URI; callers supply bounded bytes from the current clip. */
    fun prepare(bytes: ByteArray, mime: String): WireImage {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Clipboard image input exceeds limit" }
        require(mime in INPUT_MIMES) { "Unsupported clipboard image" }
        if (mime !in HEIF_MIMES) {
            ImageSafety.validate(bytes, mime)
            return WireImage(mime, bytes)
        }
        if (Build.VERSION.SDK_INT >= 28) return decodeHeif(bytes)
        throw IllegalArgumentException("HEIF clipboard conversion requires Android 9 or newer")
    }

    internal fun validateDimensions(width: Int, height: Int) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS) { "Clipboard image dimensions exceed limit" }
    }

    @RequiresApi(28)
    private fun decodeHeif(bytes: ByteArray): WireImage {
        // ImageDecoder applies embedded orientation. Header checks precede bitmap allocation.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.mimeType in HEIF_MIMES && !info.isAnimated) { "Invalid HEIF clipboard image" }
            validateDimensions(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setOnPartialImageListener { false }
        }
        try {
            validateDimensions(bitmap.width, bitmap.height)
            require(bitmap.allocationByteCount.toLong() <= MAX_PIXELS * 4) { "Decoded clipboard image exceeds memory limit" }
            val output = CappedImageOutput(MAX_BYTES)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Clipboard JPEG encoding failed" }
            val jpeg = output.toByteArray()
            ImageSafety.validate(jpeg, "image/jpeg")
            return WireImage("image/jpeg", jpeg)
        } finally { bitmap.recycle() }
    }
}

/** Fixed capacity: native JPEG compression can never grow an unbounded output buffer. */
internal class CappedImageOutput(private val limit: Int) : OutputStream() {
    private val bytes = ByteArray(limit.also { require(it in 1..ClipboardImageConverter.MAX_BYTES) })
    private var count = 0
    private var overflow = false
    override fun write(value: Int) {
        reserve(1)
        bytes[count++] = value.toByte()
    }
    override fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length)
        reserve(length)
        source.copyInto(bytes, count, offset, offset + length)
        count += length
    }
    private fun reserve(length: Int) {
        if (overflow || length > limit - count) {
            overflow = true
            throw IllegalArgumentException("Converted image exceeds output limit")
        }
    }
    fun toByteArray(): ByteArray {
        check(!overflow) { "Converted image output overflowed" }
        return bytes.copyOf(count)
    }
}
