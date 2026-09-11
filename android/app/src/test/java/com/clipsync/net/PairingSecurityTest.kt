package com.clipsync.net

import com.clipsync.crypto.Fingerprint
import com.clipsync.crypto.HmacSigner
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class PairingSecurityTest {
    private fun body(): String {
        val token = ByteArray(32) { 3 }
        val secret = ByteArray(32) { 7 }
        fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        return JSONObject().put("token", b64(token)).put("secret", b64(secret))
            .put("sig", b64(HmacSigner.hmacSha256(secret, token))).toString()
    }
    @Test fun rejectsBadCredentialFieldsWithoutEchoingThem() {
        for (field in listOf("token", "secret", "sig")) {
            for (value in listOf("", "sensitive-bad-value", "A".repeat(6000), 12)) {
                val raw = JSONObject(body()).put(field, value).toString()
                val error = assertThrows(PairingApi.PairingException::class.java) { PairingApi.parseResponse(raw) }
                assertEquals("Malformed pairing response", error.message)
            }
        }
    }
    @Test fun rejectsInvalidInputBeforeConnection() {
        val pin = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        for (host in listOf("", "good@evil", "good/path", "good?x", "good#x")) {
            assertThrows(IllegalArgumentException::class.java) { PairingApi().pairWithKnownFp(host,443,"123456",pin) }
        }
        assertThrows(IllegalArgumentException::class.java) { PairingApi().pairWithKnownFp("localhost",443,"12345x",pin) }
        assertThrows(IllegalArgumentException::class.java) { PairingApi().pairWithKnownFp("localhost",0,"123456",pin) }
        assertThrows(IllegalArgumentException::class.java) { PairingApi().pairWithKnownFp("localhost",443,"123456","bad") }
    }
    @Test fun pinnedTlsAcceptsVerifiedPeerAndRejectsImpostorBeforeHttp() {
        val certificate = HeldCertificate.Builder().commonName("self-signed Mac").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody(body()))
            server.start()
            val pin = Fingerprint.spkiSha256Base64Url(certificate.certificate)
            val result = PairingApi().pairWithKnownFp("localhost", server.port, "123456", pin)
            assertEquals(JSONObject(body()).getString("token"), result.token)
            assertEquals("/pair?code=123456", server.takeRequest().path)
            val wrongPin = Fingerprint.spkiSha256Base64Url(HeldCertificate.Builder().build().certificate)
            assertThrows(Exception::class.java) { PairingApi().pairWithKnownFp("localhost",server.port,"123456",wrongPin) }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun rejectsOversizedPinnedResponseAndDoesNotFollowRedirect() {
        val certificate = HeldCertificate.Builder().build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("A".repeat(4097)))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other"))
            server.start()
            val pin = Fingerprint.spkiSha256Base64Url(certificate.certificate)
            repeat(2) { assertThrows(PairingApi.PairingException::class.java) {
                PairingApi().pairWithKnownFp("localhost", server.port, "123456", pin)
            } }
            assertEquals(2, server.requestCount)
        }
    }
}
