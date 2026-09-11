package com.clipsync.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clipsync.app.MainActivity
import com.clipsync.images.ImageCache
import com.clipsync.storage.Prefs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Start native-large-lan-smoke.swift --confirm-system-clipboard before this test.
 * Explicit args: largeLanSmoke=true, expectedSerial=R5GL72NJTFM,
 * expectedModel=SM-F971B, expectedFingerprint=<current user-verified pin>.
 * Instrumentation completion can stop the app process/service; relaunch normal ClipSync afterwards.
 * No pairing/preference reset. Only known synthetic clipboard items are cleaned up.
 */
class LargeLanSmokeTest {
    @Test fun existingPairingTransfersPngAboveEightMiBBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit large LAN smoke opt-in required", args.getString("largeLanSmoke") == "true")
        require(args.getString("expectedSerial") == "R5GL72NJTFM" && args.getString("expectedModel") == "SM-F971B")
        require(Build.MODEL == "SM-F971B") { "Wrong physical phone model" }
        val serial = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.serialno"))
            .use { String(com.clipsync.model.ClipPayloadBuilder.readBounded(it, 128), Charsets.US_ASCII).trim() }
        require(serial == args.getString("expectedSerial")) { "Wrong physical phone serial" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val pin = requireNotNull(args.getString("expectedFingerprint"))
        require(pin.matches(Regex("[A-Za-z0-9_-]{43}")) && prefs.hasPairing() && prefs.fp == pin)
        require(!prefs.host.isNullOrBlank() && prefs.host !in setOf("127.0.0.1", "localhost", "::1", "10.0.2.2"))
        require(prefs.syncEnabled && prefs.autoSendEnabled) { "Enable normal automatic sync before this smoke test" }
        val outgoing = png(ANDROID_SEED)
        val expectedIncoming = pixelHash(pixels(MAC_SEED))
        val cache = ImageCache(context)
        val outgoingUri = cache.writeImage(outgoing, "png")
        val syntheticUris = mutableSetOf(outgoingUri.toString())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            eventually { Shizuku.pingBinder() }
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
            scenario.onActivity { ClipForegroundService.start(it) }
            eventually { ClipForegroundService.readiness.value.clipboardReadable &&
                ClipForegroundService.serviceState.value is ClipForegroundService.ServiceState.Connected }
            val beforeImage = ClipForegroundService.readiness.value.lastReceivedAt
            scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Large LAN smoke", READY)) }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Mac image arrived before background transition", beforeImage, ClipForegroundService.readiness.value.lastReceivedAt)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeImage }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                val uri = requireNotNull(it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri)
                val bytes = requireNotNull(it.contentResolver.openInputStream(uri)).use { input -> com.clipsync.model.ClipPayloadBuilder.readBounded(input, MAX_BYTES) }
                assertTrue("Incoming PNG must exercise the raised limit", bytes.size > OLD_LIMIT && bytes.size <= MAX_BYTES)
                val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    assertEquals(WIDTH, image.width); assertEquals(HEIGHT, image.height)
                    val actual = IntArray(WIDTH * HEIGHT)
                    image.getPixels(actual, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    assertArrayEquals("Incoming Mac RGB pixels", expectedIncoming, pixelHash(actual))
                    syntheticUris += uri.toString()
                } finally { image.recycle() }
            }
            Thread.sleep(2_000)
            val beforeComplete = ClipForegroundService.readiness.value.lastReceivedAt
            scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(it.contentResolver, "Large LAN smoke", outgoingUri)) }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Completion arrived before background transition", beforeComplete, ClipForegroundService.readiness.value.lastReceivedAt)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeComplete }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertEquals(COMPLETE, it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }
            assertTrue(prefs.hasPairing() && prefs.fp == pin && prefs.syncEnabled && prefs.autoSendEnabled)
        } finally {
            try {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity {
                    val clipboard = it.getSystemService(ClipboardManager::class.java)
                    val item = clipboard.primaryClip?.takeIf { clip -> clip.itemCount == 1 }?.getItemAt(0)
                    if (item?.text?.toString() in setOf(READY, COMPLETE) || item?.uri?.toString() in syntheticUris) clipboard.clearPrimaryClip()
                }
            } finally { scenario.close() }
        }
    }

    private fun png(seed: Int): ByteArray {
        val expected = pixels(seed)
        val bitmap = Bitmap.createBitmap(expected, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val bytes = try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { bitmap.recycle() }
        assertTrue("Synthetic PNG must exceed the former 8 MiB cap", bytes.size > OLD_LIMIT && bytes.size <= MAX_BYTES)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val actual = IntArray(WIDTH * HEIGHT)
            decoded.getPixels(actual, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            assertArrayEquals(pixelHash(expected), pixelHash(actual))
        } finally { decoded.recycle() }
        return bytes
    }

    // Rows mirror vertically so CoreGraphics/Android row-coordinate conventions do not affect the canonical RGB hash.
    private fun pixels(seed: Int): IntArray = IntArray(WIDTH * HEIGHT).also { result ->
        for (y in 0 until HEIGHT) {
            var state = seed xor ((minOf(y, HEIGHT - 1 - y) + 1) * 0x9E3779B9.toInt())
            for (x in 0 until WIDTH) {
                state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
                result[y * WIDTH + x] = 0xff000000.toInt() or (state and 0x00ffffff)
            }
        }
    }
    private fun pixelHash(pixels: IntArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = ByteArray(WIDTH * 3)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val p = pixels[y * WIDTH + x]
                require(p ushr 24 == 255) { "Synthetic pixel lost opacity" }
                row[x * 3] = (p ushr 16).toByte(); row[x * 3 + 1] = (p ushr 8).toByte(); row[x * 3 + 2] = p.toByte()
            }
            digest.update(row)
        }
        return digest.digest()
    }
    private fun eventually(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < until) { if (predicate()) return; Thread.sleep(100) }
        fail("Large LAN smoke stage did not converge within 90 seconds")
    }
    private companion object {
        const val WIDTH = 2048; const val HEIGHT = 1536
        const val OLD_LIMIT = 8 * 1024 * 1024; const val MAX_BYTES = 50 * 1024 * 1024
        const val MAC_SEED = 0x13579BDF; const val ANDROID_SEED = 0x2468ACE1
        const val READY = "LARGE_LAN_ANDROID_READY"; const val COMPLETE = "LARGE_LAN_MAC_COMPLETE"
    }
}
