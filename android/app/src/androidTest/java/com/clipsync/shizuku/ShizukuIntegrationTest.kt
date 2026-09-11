package com.clipsync.shizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.clipsync.app.MainActivity
import com.clipsync.crypto.Fingerprint
import com.clipsync.images.ImageCache
import com.clipsync.model.ClipPayload
import com.clipsync.service.ClipForegroundService
import com.clipsync.storage.Prefs
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in on a dedicated synthetic-data AVD: am instrument -e shizuku true ... */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class ShizukuIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun requireOptInAndAuthorization() {
        assumeTrue("Opt in with instrumentation argument shizuku=true",
            InstrumentationRegistry.getArguments().getString("shizuku") == "true")
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!Shizuku.pingBinder() && System.nanoTime() < until) Thread.sleep(100)
        assumeTrue("Start Shizuku on the audit emulator first", Shizuku.pingBinder())
        assumeTrue("Grant ClipSync Shizuku access first",
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }

    @Test fun bindsReadsAtomicSnapshotAndCurrentImageWhileBackgrounded() {
        val prefs = Prefs(context)
        ClipForegroundService.stop(context)
        prefs.clearPairing()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val helper = ShizukuClipboardManager(context)
        try {
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync {
                helper.onStateChanged = { if (it == ShizukuClipboardManager.State.READY) ready.countDown() }
                helper.initialize()
            }
            assertTrue("Shizuku user service must bind", ready.await(12, TimeUnit.SECONDS))
            assertTrue(helper.isAvailable())
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Synthetic helper test", "Klippa atomic clipboard Ω\nsecond line"))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val text = requireNotNull(helper.getClipboardSnapshot())
            assertEquals("text/plain", text.mime)
            assertEquals("Klippa atomic clipboard Ω\nsecond line", text.text)
            assertNull(text.uri)
            assertFalse(text.sensitive)
            assertNull("Seed is an ordinary local copy, not a received event", text.sourceEvent)
            assertEquals("Unchanged snapshot identity must be stable", text.identity,
                helper.getClipboardSnapshot()?.identity)

            val bytes = imageBytes()
            val uri = ImageCache(context).writeImage(bytes, "png")
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newUri(it.contentResolver, "Synthetic helper image", uri))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val image = requireNotNull(helper.getClipboardSnapshot())
            assertEquals("image/png", image.mime)
            assertEquals(uri.toString(), image.uri)
            assertNotEquals(text.identity, image.identity)
            // No manual grant to shell: prove the real clipboard-provider grant path.
            assertArrayEquals(bytes, helper.getClipboardImage(image))
            assertEquals(Lifecycle.State.CREATED, scenario.state)

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Synthetic newer item", "Newer synthetic text"))
            }
            assertThrows("Stale image identity must not reopen the old URI", Exception::class.java) {
                helper.getClipboardImage(image)
            }
        } finally {
            try {
                instrumentation.runOnMainSync { helper.destroy() }
                assertFalse("Destroy must drop the helper connection", helper.isAvailable())
            } finally { clearSyntheticState(scenario, prefs) }
        }
    }

    @Test fun automaticallySendsTextAndImageToPinnedPeerWithoutSendActivity() {
        val prefs = Prefs(context)
        ClipForegroundService.stop(context)
        prefs.clearPairing()
        val certificate = HeldCertificate.Builder().commonName("synthetic-outbound-peer").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        val requests = LinkedBlockingQueue<RecordedRequest>()
        val connected = CountDownLatch(1)
        var socket: WebSocket? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        connected.countDown()
                    }
                })
                "/inject" -> { requests.add(request); MockResponse().setResponseCode(200) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val monitor = instrumentation.addMonitor("com.clipsync.overlay.SendClipActivity", null, false)
        try {
            val token = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
            prefs.savePairing("localhost", server.port, token, Fingerprint.spkiSha256Base64Url(certificate.certificate),
                Base64.getEncoder().encodeToString(ByteArray(32) { 4 }), Prefs.MODE_MANUAL)
            prefs.autoSendEnabled = true
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Synthetic baseline", "Do not upload initial clipboard"))
                ClipForegroundService.start(it)
            }
            assertTrue("Pinned WebSocket must connect", connected.await(12, TimeUnit.SECONDS))
            eventually { ClipForegroundService.readiness.value.clipboardReadable }
            assertNull("Initial clipboard is only a baseline", requests.poll(100, TimeUnit.MILLISECONDS))
            scenario.onActivity {
                // Ordinary local copy, not ClipboardWriter's inbound-event path.
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Synthetic text", "Klippa automatic outbound Ω"))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val textRequest = requests.poll(12, TimeUnit.SECONDS)
            assertNotNull("Automatic text POST must arrive", textRequest)
            assertEquals("Bearer $token", textRequest!!.getHeader("Authorization"))
            assertNotNull(textRequest.getHeader("X-ClipSync-Signature"))
            val text = ClipPayload.fromJson(textRequest.body.readUtf8())
            assertEquals("text", text.type)
            assertEquals("Klippa automatic outbound Ω", String(Base64.getDecoder().decode(text.data), Charsets.UTF_8))
            assertEquals(Lifecycle.State.CREATED, scenario.state)

            // The server echoes the actual outbound nonce. It must not be re-applied as an inbound copy.
            assertTrue(socket!!.send(text.toJson()))
            Thread.sleep(900)
            val bytes = imageBytes()
            val uri = ImageCache(context).writeImage(bytes, "png")
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                val clipboard = it.getSystemService(ClipboardManager::class.java)
                assertNull("Known outbound nonce must not be applied back to the clipboard",
                    clipboard.primaryClip?.description?.extras?.getString(com.clipsync.clipboard.ClipboardWriter.EVENT_NONCE))
                clipboard.setPrimaryClip(ClipData.newUri(it.contentResolver, "Synthetic outbound image", uri))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val imageRequest = requests.poll(12, TimeUnit.SECONDS)
            assertNotNull("Automatic image POST must arrive through the helper grant", imageRequest)
            val image = ClipPayload.fromJson(imageRequest!!.body.readUtf8())
            assertEquals("image", image.type)
            assertArrayEquals(bytes, Base64.getDecoder().decode(image.data))
            assertNotEquals("Each deliberate copy has a new event nonce", text.nonce, image.nonce)
            assertEquals(Lifecycle.State.CREATED, scenario.state)
            assertEquals("Automatic sending must not launch SendClipActivity", 0, monitor.hits)
            assertNull("No duplicate POST without another copy", requests.poll(1200, TimeUnit.MILLISECONDS))
        } finally {
            instrumentation.removeMonitor(monitor)
            clearSyntheticState(scenario, prefs)
            socket?.close(1000, null)
            server.close()
        }
    }

    private fun clearSyntheticState(scenario: ActivityScenario<MainActivity>, prefs: Prefs) {
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

    private fun imageBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.CYAN)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun eventually(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < until) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Helper readiness did not converge")
    }
}
