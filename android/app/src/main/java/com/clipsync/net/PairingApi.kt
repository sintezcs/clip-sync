package com.clipsync.net

import com.clipsync.crypto.Fingerprint
import com.clipsync.crypto.HmacSigner
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Legacy /pair exchange. The caller MUST compare the pin on the Mac independently.
 * mDNS is only an endpoint hint. A high-entropy single-use QR exchange requires a Mac update.
 */
class PairingApi(private val clientFactory: ClipClient = ClipClient()) {
    data class PairingResponse(val token: String, val sig: String, val secret: String)

    fun pairWithKnownFp(host: String, port: Int, code: String, fpBase64Url: String): PairingResponse {
        require(code.matches(Regex("[0-9]{6}"))) { "Pairing code must be six digits" }
        val url = ClipClient.endpoint(host, port, "/pair").newBuilder().addQueryParameter("code", code).build()
        val client = clientFactory.pinnedClient(host, fpBase64Url).newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw PairingException("Pairing failed (HTTP ${response.code})")
            val body = response.body ?: throw PairingException("Missing pairing response")
            val source = body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw PairingException("Pairing response too large")
            return parseResponse(source.readUtf8())
        }
    }

    fun ping(host: String, port: Int, fp: String): Result<Boolean> = runCatching {
        val client = clientFactory.pinnedClient(host, fp).newBuilder()
            .callTimeout(3, TimeUnit.SECONDS).connectTimeout(3, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(ClipClient.endpoint(host, port, "/health")).get().build())
            .execute().use { require(it.isSuccessful) { "Health check failed" }; true }
    }

    class PairingException(message: String) : Exception(message)
    companion object {
        private const val MAX_RESPONSE_BYTES = 4096L
        fun pinFor(fpBase64Url: String): String = Fingerprint.okHttpPin(fpBase64Url)
        fun decodeSecret(secret: String): ByteArray = decode32(secret)
        private fun decode32(value: String): ByteArray {
            require(value.length == 44) { "Invalid credential length" }
            val bytes = Base64.getDecoder().decode(value)
            require(bytes.size == 32 && Base64.getEncoder().encodeToString(bytes) == value) { "Invalid credential encoding" }
            return bytes
        }
        internal fun parseResponse(body: String): PairingResponse {
            try {
                require(body.length <= MAX_RESPONSE_BYTES)
                val json = JSONObject(body)
                fun field(key: String): String = (json.get(key) as? String) ?: error("Invalid field")
                val token = field("token")
                val sig = field("sig")
                val secret = field("secret")
                val tokenBytes = decode32(token)
                val secretBytes = decode32(secret)
                // Consistency check only: identity comes from the independently verified TLS pin.
                require(MessageDigest.isEqual(decode32(sig), HmacSigner.hmacSha256(secretBytes, tokenBytes)))
                return PairingResponse(token, sig, secret)
            } catch (_: Exception) {
                throw PairingException("Malformed pairing response")
            }
        }
    }
}
