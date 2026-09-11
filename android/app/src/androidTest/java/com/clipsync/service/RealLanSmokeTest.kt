package com.clipsync.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.core.content.FileProvider
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit physical-phone LAN smoke test. Uses only the pairing established in the normal UI. */
class RealLanSmokeTest {
    @Test fun existingPairingSynchronizesTextAndImagesOverLan() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit LAN smoke opt-in required", args.getString("lanSmoke") == "true")
        require(args.getString("physicalFold") == "true" && Build.MODEL == "SM-F971B") {
            "LAN smoke restricted to the authorized physical Fold"
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val expectedFingerprint = requireNotNull(args.getString("expectedFingerprint"))
        require(expectedFingerprint.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(prefs.hasPairing() && prefs.fp == expectedFingerprint) { "Existing trusted pairing does not match the requested peer" }
        require(!prefs.host.isNullOrBlank() && prefs.host !in setOf("127.0.0.1", "localhost", "::1", "10.0.2.2")) {
            "LAN smoke requires the existing LAN endpoint"
        }
        val htmlText = args.getString("htmlText") == "true"
        fun syntheticText(value: String): ClipData = if (htmlText) {
            ClipData.newHtmlText("LAN smoke", value, "<b>$value</b>")
        } else ClipData.newPlainText("LAN smoke", value)
        val syntheticTexts = setOf("LAN_ANDROID_READY", "LAN_MAC_TEXT_Ω", "LAN_ANDROID_TEXT_Ω", "LAN_MAC_COMPLETE")
        val syntheticUris = mutableSetOf<String>()
        var heicFixture: File? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            eventually { Shizuku.pingBinder() }
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
            prefs.syncEnabled = true
            prefs.autoSendEnabled = true
            scenario.onActivity { ClipForegroundService.start(it) }
            eventually {
                ClipForegroundService.readiness.value.clipboardReadable &&
                    ClipForegroundService.serviceState.value is ClipForegroundService.ServiceState.Connected
            }
            val beforeText = ClipForegroundService.readiness.value.lastReceivedAt
            // Mac waits for READY, then allows the Activity to enter CREATED before publishing text.
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(syntheticText("LAN_ANDROID_READY"))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Response arrived before the Activity reached background", beforeText,
                ClipForegroundService.readiness.value.lastReceivedAt)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeText }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals("LAN_MAC_TEXT_Ω", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
            val beforeImage = ClipForegroundService.readiness.value.lastReceivedAt
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(syntheticText("LAN_ANDROID_TEXT_Ω"))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Response arrived before the Activity reached background", beforeImage,
                ClipForegroundService.readiness.value.lastReceivedAt)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeImage }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                val uri = requireNotNull(it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri)
                val bitmap = requireNotNull(it.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream))
                try {
                    assertEquals(8, bitmap.width)
                    assertEquals(8, bitmap.height)
                    for (y in 0 until 8) for (x in 0 until 8) assertEquals(Color.RED, bitmap.getPixel(x, y))
                    syntheticUris += uri.toString()
                } finally { bitmap.recycle() }
            }
            val beforeComplete = ClipForegroundService.readiness.value.lastReceivedAt
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val bytes = try {
                bitmap.eraseColor(Color.BLUE)
                ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            } finally { bitmap.recycle() }
            val heicImage = args.getString("heicImage") == "true"
            val uri = if (heicImage) {
                val fixture = File(ImageCache(context).dir, "${UUID.randomUUID()}.heic")
                heicFixture = fixture
                InstrumentationRegistry.getInstrumentation().context.assets.open("white.heic").use { source ->
                    fixture.outputStream().use { output -> source.copyTo(output) }
                }
                FileProvider.getUriForFile(context, ImageCache.AUTHORITY, fixture)
            } else ImageCache(context).writeImage(bytes, "png")
            syntheticUris += uri.toString()
            scenario.onActivity {
                val clip = if (heicImage) ClipData(ClipDescription("LAN smoke image", arrayOf("image/heic")), ClipData.Item(uri))
                    else ClipData.newUri(it.contentResolver, "LAN smoke image", uri)
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Response arrived before the Activity reached background", beforeComplete,
                ClipForegroundService.readiness.value.lastReceivedAt)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeComplete }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals("LAN_MAC_COMPLETE", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
            assertTrue("Pairing must remain intact", prefs.hasPairing() && prefs.fp == expectedFingerprint)
            assertTrue(prefs.syncEnabled)
        } finally {
            try {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity {
                    val clipboard = it.getSystemService(ClipboardManager::class.java)
                    val item = clipboard.primaryClip?.takeIf { clip -> clip.itemCount == 1 }?.getItemAt(0)
                    if (item?.text?.toString() in syntheticTexts || item?.uri?.toString() in syntheticUris) {
                        clipboard.clearPrimaryClip()
                    }
                }
            } finally {
                heicFixture?.delete()
                scenario.close()
            }
        }
    }

    private fun eventually(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < until) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("LAN smoke state did not converge")
    }
}
