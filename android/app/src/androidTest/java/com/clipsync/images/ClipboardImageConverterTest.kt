package com.clipsync.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Bundle
import java.nio.ByteBuffer
import kotlin.math.abs
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ClipboardImageConverterTest {
    @Test fun orientedHeicBecomesBoundedJpegWithDisplayOrientation() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("oriented-red.heic").use { it.readBytes() }
        val reference = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            assertEquals(32, reference.width)
            assertEquals(64, reference.height)
            for (mime in listOf("image/heic", "image/heif")) {
                val image = ClipboardImageConverter.prepare(source, mime)
                assertEquals("image/jpeg", image.mime)
                assertTrue(image.bytes.size <= ClipboardImageConverter.MAX_BYTES)
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size))
                try {
                    // Source is 64x32 with orientation6; JPEG pixels must be physically rotated.
                    assertEquals(32, bitmap.width)
                    assertEquals(64, bitmap.height)
                    val diagnostics = "source=${rgbRanges(reference)} jpeg=${rgbRanges(bitmap)}"
                    // These pixels belong solely to the checked-in synthetic fixture, never a user image.
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("syntheticHeicColorRanges", diagnostics)
                    })
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val original = reference.getPixel(x, y)
                        val converted = bitmap.getPixel(x, y)
                        assertTrue("Synthetic HEIC must remain predominantly red: $diagnostics",
                            Color.red(original) > 220 && Color.green(original) < 80 && Color.blue(original) < 40)
                        assertTrue("JPEG must preserve decoded HEIC pixels at ($x,$y): $diagnostics",
                            abs(Color.red(original) - Color.red(converted)) <= 8 &&
                                abs(Color.green(original) - Color.green(converted)) <= 8 &&
                                abs(Color.blue(original) - Color.blue(converted)) <= 8)
                    }
                } finally { bitmap.recycle() }
            }
        } finally { reference.recycle() }
    }

    private fun rgbRanges(bitmap: Bitmap): String {
        val low = intArrayOf(255, 255, 255)
        val high = intArrayOf(0, 0, 0)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val values = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            for (channel in 0..2) {
                low[channel] = minOf(low[channel], values[channel])
                high[channel] = maxOf(high[channel], values[channel])
            }
        }
        return (0..2).joinToString(",") { "${low[it]}..${high[it]}" }
    }

    @Test fun pngRemainsUnchangedAndCannotMasqueradeAsHeic() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        val image = ClipboardImageConverter.prepare(png, "image/png")
        assertEquals("image/png", image.mime)
        assertSame(png, image.bytes)
        rejects { ClipboardImageConverter.prepare(png, "image/heic") }
        rejects { ClipboardImageConverter.prepare(png, "image/webp") }
    }

    @Test fun sourceDimensionAndOutputLimitsFailClosed() {
        rejects { ClipboardImageConverter.prepare(ByteArray(ClipboardImageConverter.MAX_BYTES + 1), "image/heic") }
        rejects { ClipboardImageConverter.prepare(byteArrayOf(1, 2, 3), "image/heic") }
        rejects { ClipboardImageConverter.validateDimensions(8193, 1) }
        rejects { ClipboardImageConverter.validateDimensions(8192, 8192) }
        rejects { ClipboardImageConverter.validateDimensions(Int.MAX_VALUE, Int.MAX_VALUE) }
        rejects { ClipboardImageConverter.validateDimensions(0, 1) }
        ClipboardImageConverter.validateDimensions(6000, 4000)
        val output = CappedImageOutput(4)
        output.write(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        rejects { output.write(5) }
        rejects { output.toByteArray() } // Native encoders cannot turn overflow into a partial success.
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe image accepted") } catch (_: Exception) { }
    }
}
