package com.clipsync.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clipsync.app.MainActivity
import com.clipsync.images.ImageCache
import com.clipsync.net.PairingApi
import com.clipsync.storage.Prefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Explicitly paired with the real Mac XCTest fixture on its isolated loopback listener. */
class MacEndToEndTest {
    @Test fun pairsAndSynchronizesBothDirectionsWithRealMac() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Mac fixture required", args.getString("macE2E") == "true")
        val config = JSONObject(String(Base64.getDecoder().decode(requireNotNull(args.getString("pairConfig"))), Charsets.UTF_8))
        val physicalFold = args.getString("physicalFold") == "true"
        val expectedHost = if (physicalFold) {
            require(Build.MODEL == "SM-F971B") { "Physical fixture restricted to the authorized Fold model" }
            "127.0.0.1"
        } else "10.0.2.2"
        require(config.getString("host") == expectedHost && config.getInt("port") == 17010)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        ClipForegroundService.stop(context)
        prefs.clearPairing()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            eventually { Shizuku.pingBinder() }
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
            val response = PairingApi().pairWithQrSecret(config.getString("host"), config.getInt("port"),
                config.getString("secret"), config.getString("fp"))
            prefs.savePairing(config.getString("host"), config.getInt("port"), response.token,
                config.getString("fp"), response.secret, Prefs.MODE_MANUAL, "Mac audit fixture")
            prefs.syncEnabled = true
            prefs.autoSendEnabled = true
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Audit", "Initial synthetic baseline"))
                ClipForegroundService.start(it)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != null }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals("KLIPPA_MAC_TEXT_Ω", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
            eventually { ClipForegroundService.readiness.value.clipboardReadable }
            val beforeImage = ClipForegroundService.readiness.value.lastReceivedAt
            scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Audit", "KLIPPA_ANDROID_TEXT_Ω")) }
            scenario.moveToState(Lifecycle.State.CREATED)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeImage }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                val uri = requireNotNull(it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri)
                val bitmap = it.contentResolver.openInputStream(uri)!!.use(BitmapFactory::decodeStream)
                assertNotNull(bitmap)
                assertEquals(8, bitmap.width); assertEquals(8, bitmap.height)
                assertEquals(Color.RED, bitmap.getPixel(0, 0)); bitmap.recycle()
            }
            val beforeComplete = ClipForegroundService.readiness.value.lastReceivedAt
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val uri = ImageCache(context).writeImage(bytes, "png")
            scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(it.contentResolver, "Audit image", uri)) }
            scenario.moveToState(Lifecycle.State.CREATED)
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeComplete }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals("KLIPPA_MAC_COMPLETE", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
        } finally {
            prefs.syncEnabled = false
            ClipForegroundService.stop(context)
            try {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { it.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
            } finally {
                prefs.clearPairing()
                scenario.close()
            }
        }
    }

    private fun eventually(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < until) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Mac/Android state did not converge")
    }
}
