package com.clipsync.overlay

import com.clipsync.crypto.HmacSigner
import com.clipsync.model.ClipPayload
import com.clipsync.net.ClipClient
import com.clipsync.net.PairingApi
import com.clipsync.sync.ClipboardEvents
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Call
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Serialized, single-attempt sends. Legacy Mac does not guarantee idempotent retry. */
class ClipSender(
    private val clientFactory: ClipClient = ClipClient(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Cancellation closes an active socket; it cannot undo content already accepted by the Mac. */
    suspend fun sendCancellable(
        host: String,
        port: Int,
        token: String,
        pairingSecretB64: String,
        fpBase64Url: String,
        payload: ClipPayload,
        isCurrent: () -> Boolean = { true }
    ): Result = suspendCancellableCoroutine { continuation ->
        val cancellation = SendCancellation()
        continuation.invokeOnCancellation { cancellation.cancel() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            val result = runCatching {
                send(host, port, token, pairingSecretB64, fpBase64Url, payload,
                    isCurrent = { !cancellation.cancelled && isCurrent() }, cancellation = cancellation)
            }
            if (continuation.isActive) continuation.resumeWith(result)
        })
    }

    /** Active call registration and cancellation are atomic, including cancellation before registration. */
    class SendCancellation internal constructor() {
        @Volatile var cancelled = false
            private set
        private var call: Call? = null
        @Synchronized internal fun attach(next: Call) {
            call = next
            if (cancelled) next.cancel()
        }
        @Synchronized fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }

    fun send(
        host: String,
        port: Int,
        token: String,
        pairingSecretB64: String,
        fpBase64Url: String,
        payload: ClipPayload,
        isCurrent: () -> Boolean = { true },
        cancellation: SendCancellation? = null
    ): Result = synchronized(sendLock) {
        try {
            if (cancellation?.cancelled == true || !isCurrent() || Thread.currentThread().isInterrupted) return@synchronized Result.Failed("Superseded or cancelled")
            payload.validate(clockMs())
            ClipClient.validateToken(token)
            val secret = PairingApi.decodeSecret(pairingSecretB64)
            val client = clientFactory.pinnedClient(host, fpBase64Url).newBuilder()
                .callTimeout(15, TimeUnit.SECONDS).build()
            val body = payload.toJson()
            val request = Request.Builder().url(ClipClient.endpoint(host, port, "/inject"))
                .header("Authorization", "Bearer $token")
                .header("X-ClipSync-Signature", HmacSigner.signatureHeader(secret, clockMs() / 1000L, body))
                .header("X-ClipSync-Source", "android-fab")
                .post(body.toRequestBody(JSON)).build()
            if (cancellation?.cancelled == true || !isCurrent() || Thread.currentThread().isInterrupted) return@synchronized Result.Failed("Superseded or cancelled")
            ClipboardEvents.recordOutbound(payload, fpBase64Url)
            // The journal may block on durable I/O; recheck supersession/cancellation after it commits.
            if (cancellation?.cancelled == true || !isCurrent() || Thread.currentThread().isInterrupted) return@synchronized Result.Failed("Superseded or cancelled")
            val call = client.newCall(request)
            cancellation?.attach(call)
            call.execute().use { response ->
                if (response.isSuccessful) {
                    lastSentHash = payload.data.hashCode()
                    lastSentMs = clockMs()
                    Result.Ok
                } else Result.Failed("Send failed (HTTP ${response.code})")
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not include peer response bodies or credentials in errors or logs.
            Result.Failed("Send failed; delivery is unconfirmed")
        }
    }

    companion object {
        private val sendLock = Any()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        @Volatile var lastSentHash: Int = 0
        @Volatile var lastSentMs: Long = 0L
    }
}
