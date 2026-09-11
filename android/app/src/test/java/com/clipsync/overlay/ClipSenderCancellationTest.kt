package com.clipsync.overlay

import com.clipsync.crypto.Fingerprint
import com.clipsync.model.ClipPayloadBuilder
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import com.clipsync.sync.ClipboardEvents
import com.clipsync.sync.MemoryReplayStore
import java.util.Base64
import java.util.concurrent.TimeUnit

class ClipSenderCancellationTest {
    @Before fun setupReplayStore() { ClipboardEvents.installStore(MemoryReplayStore()) }
    @Test fun cancellingActiveCallReleasesSendLockAndNeverRetries() = runBlocking {
        val certificate = HeldCertificate.Builder().build()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val pin = Fingerprint.spkiSha256Base64Url(certificate.certificate)
            val credential = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
            val first = launch(Dispatchers.Default) {
                ClipSender().sendCancellable("localhost", server.port, credential, credential, pin, ClipPayloadBuilder.text("first"))
                fail("Cancelled send must not return success")
            }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            first.cancelAndJoin()
            val second = withTimeout(3000) {
                ClipSender().sendCancellable("localhost", server.port, credential, credential, pin, ClipPayloadBuilder.text("second"))
            }
            assertEquals(ClipSender.Result.Ok, second)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellationBeforeSendProducesNoRequest() {
        val cancellation = ClipSender.SendCancellation()
        cancellation.cancel()
        val result = ClipSender().send("localhost", 443, "invalid", "invalid", "invalid", ClipPayloadBuilder.text("never sent"), cancellation = cancellation)
        assertTrue(result is ClipSender.Result.Failed)
        assertEquals("Superseded or cancelled", (result as ClipSender.Result.Failed).reason)
    }
    @Test fun supersededDuringReplayJournalCommitNeverStartsHttpRequest() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        ClipboardEvents.installStore(object : com.clipsync.sync.ReplayStore {
            override fun accept(key: String): Boolean {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return true
            }
        })
        val certificate = HeldCertificate.Builder().build()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val pin = Fingerprint.spkiSha256Base64Url(certificate.certificate)
            val credential = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
            val pending = async(Dispatchers.Default) {
                ClipSender().sendCancellable("localhost", server.port, credential, credential, pin,
                    ClipPayloadBuilder.text("superseded during journal"), isCurrent = { current.get() })
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                current.set(false)
                release.countDown()
                val result = withTimeout(3000) { pending.await() }
                assertTrue(result is ClipSender.Result.Failed)
                assertEquals("Superseded or cancelled", (result as ClipSender.Result.Failed).reason)
                assertEquals(0, server.requestCount)
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }
}
