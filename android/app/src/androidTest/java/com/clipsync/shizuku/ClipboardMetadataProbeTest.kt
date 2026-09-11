package com.clipsync.shizuku

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clipsync.storage.Prefs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit diagnostic: reports only clipboard eligibility metadata, never clipboard content. */
class ClipboardMetadataProbeTest {
    @Test fun reportsCurrentClipboardMetadataWithoutChangingIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit clipboard metadata probe required", args.getString("clipboardMetadata") == "true")
        require(args.getString("physicalFold") == "true" && Build.MODEL == "SM-F971B")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ShizukuClipboardManager(context)
        val ready = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                helper.onStateChanged = { if (it == ShizukuClipboardManager.State.READY) ready.countDown() }
                helper.initialize()
            }
            assertTrue("Clipboard helper did not become available", ready.await(15, TimeUnit.SECONDS))
            val snapshot = try { helper.getClipboardSnapshot() } catch (_: Exception) { null }
            val mime = try { helper.getClipboardMime() } catch (_: Exception) { null }
            // Never echo arbitrary provider strings; report only a syntactically bounded MIME.
            val safeMime = mime?.takeIf { it == "image/*" || it.matches(Regex("[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}")) } ?: "unavailable"
            val report = Bundle().apply {
                putString("mime", safeMime)
                putString("sensitive", snapshot?.sensitive?.toString() ?: "unavailable")
                putBoolean("textPresent", snapshot?.text != null)
                putInt("textLength", snapshot?.text?.length ?: 0)
                putBoolean("uriPresent", snapshot?.uri != null)
                putBoolean("sourceEventPresent", snapshot?.sourceEvent != null)
                putBoolean("autoSendEnabled", Prefs(context).autoSendEnabled)
                putString("helperState", helper.state.name)
            }
            instrumentation.sendStatus(0, report)
        } finally {
            instrumentation.runOnMainSync { helper.destroy(stopUserService = false) }
        }
    }
}
