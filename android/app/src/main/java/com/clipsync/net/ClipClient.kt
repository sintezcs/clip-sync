package com.clipsync.net

import com.clipsync.crypto.Fingerprint
import com.clipsync.model.ClipPayload

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** HTTPS transport authenticated by an independently verified SPKI pin. */
class ClipClient {

    fun pinnedClient(host: String, fpBase64Url: String): OkHttpClient {
        val expected = Fingerprint.decodePin(fpBase64Url)
        val expectedHost = endpoint(host, 443, "/").host
        // OkHttp's CertificatePinner cannot work with a custom TrustManager
        // (it needs the system chain cleaner to build the peer cert list).
        // Instead we verify the SPKI-SHA256 fingerprint manually inside the
        // TrustManager — equally secure, compatible with self-signed certs.
        val trustPinned = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw java.security.cert.CertificateException("Client certificate validation is unsupported")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("Empty certificate chain")
                val actual = Fingerprint.spkiSha256Base64Url(leaf)
                if (!java.security.MessageDigest.isEqual(Fingerprint.decodePin(actual), expected)) {
                    throw java.security.cert.CertificateException(
                        "SPKI pin mismatch"
                    )
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustPinned), java.security.SecureRandom())
        return baseBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustPinned)
            .hostnameVerifier { requestedHost, _ -> requestedHost == expectedHost }
            .build()
    }

    fun connectWebSocket(
        client: OkHttpClient,
        host: String,
        port: Int,
        token: String,
        onFrame: (ClipPayload) -> Unit,
        onStatus: (WsStatus) -> Unit
    ): WebSocket {
        validateToken(token)
        val req = Request.Builder()
            .url(endpoint(host, port, "/ws"))
            .header("Authorization", "Bearer $token")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus(WsStatus.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val payload = try {
                    ClipPayload.fromJson(text)
                } catch (t: Exception) {
                    onStatus(WsStatus.Error("Invalid clipboard frame"))
                    return
                }
                onFrame(payload)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onStatus(WsStatus.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus(WsStatus.Error(t.message ?: "ws failure"))
            }
        }
        return client.newWebSocket(req, listener)
    }

    companion object {
        fun endpoint(host: String, port: Int, path: String): okhttp3.HttpUrl {
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\?#@%" }) { "Invalid host" }
            require(port in 1..65535) { "Invalid port" }
            return okhttp3.HttpUrl.Builder().scheme("https").host(host).port(port).encodedPath(path).build()
        }
        fun validateToken(token: String) {
            require(token.length in 32..512 && token.all { it.isLetterOrDigit() && it.code < 128 || it in "+/=_-" }) { "Invalid token" }
        }
    }

    sealed class WsStatus {
        data object Open : WsStatus()
        data class Closed(val code: Int, val reason: String) : WsStatus()
        data class Error(val message: String) : WsStatus()
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
}
