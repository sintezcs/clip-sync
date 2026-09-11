package com.clipsync.service

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clipsync.app.MainActivity
import com.clipsync.crypto.Fingerprint
import com.clipsync.model.ClipPayloadBuilder
import com.clipsync.storage.Prefs
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real service and clipboard with a local pinned TLS peer; no macOS process or real personal data. */
@RunWith(AndroidJUnit4::class)
class ClipboardServiceIntegrationTest {
    @Test fun receivesTextAndImageWithoutHelperAndRejectsReplayWhilePaused() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ClipForegroundService.stop(context)
        val prefs = Prefs(context)
        prefs.clearPairing()
        val cert = HeldCertificate.Builder().commonName("synthetic-peer").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val socketReady = CountDownLatch(1)
        var peer: WebSocket? = null
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket; socketReady.countDown() }
        }))
        server.start()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            prefs.savePairing("localhost", server.port, Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                Fingerprint.spkiSha256Base64Url(cert.certificate), Base64.getEncoder().encodeToString(ByteArray(32) { 2 }), Prefs.MODE_MANUAL)
            prefs.autoSendEnabled = false
            scenario.onActivity { ClipForegroundService.start(it) }
            assertTrue("TLS WebSocket opened", socketReady.await(10, TimeUnit.SECONDS))
            val text = ClipPayloadBuilder.text("Klippa synthetic inbound")
            assertTrue(peer!!.send(text.toJson()))
            eventually {
                var value: String? = null
                scenario.onActivity { value = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
                value == "Klippa synthetic inbound"
            }
            val bytes = ByteArrayOutputStream().also { stream ->
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
            }.toByteArray()
            val image = ClipPayloadBuilder.image("image/png", bytes)
            val beforeImage = ClipForegroundService.readiness.value.lastReceivedAt
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue(peer!!.send(image.toJson()))
            eventually { ClipForegroundService.readiness.value.lastReceivedAt != beforeImage }
            // Receipt was applied while Activity was backgrounded, before resuming to inspect it.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            eventually {
                var valid = false
                scenario.onActivity {
                    val clip = it.getSystemService(ClipboardManager::class.java).primaryClip
                    val uri = clip?.getItemAt(0)?.uri
                    valid = uri != null && it.contentResolver.openInputStream(uri)!!.use { stream -> stream.readBytes().contentEquals(bytes) }
                }
                valid
            }
            // A replay must not turn the current image back into the previously received text.
            assertTrue(peer!!.send(text.toJson()))
            Thread.sleep(300)
            scenario.onActivity { assertNotNull(it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri) }
            prefs.syncEnabled = false
            assertTrue(peer!!.send(ClipPayloadBuilder.text("Must stay paused").toJson()))
            Thread.sleep(300)
            scenario.onActivity { assertNotNull(it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.uri) }
        } finally {
            ClipForegroundService.stop(context)
            prefs.clearPairing()
            scenario.close()
            peer?.close(1000, null)
            server.close()
        }
    }

    private fun eventually(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < end) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Clipboard state did not converge")
    }
}
