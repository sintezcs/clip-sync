package com.clipsync.images

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import com.clipsync.model.ClipPayloadBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit shares only. A stalled provider cannot retain an Activity coroutine or spawn unbounded reads. */
object SharedImageReader {
    data class Image(val bytes: ByteArray, val mime: String)
    private fun workers(name: String) = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        SynchronousQueue(), { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val readers = workers("clipsync-shared-image")
    // CancellationSignal can itself call an uncooperative remote provider; keep it off callers/deadline timer.
    private val cancellations = workers("clipsync-image-cancel")
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "clipsync-image-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    suspend fun read(resolver: ContentResolver, uri: Uri, timeoutMs: Long = 10_000): Image {
        require(uri.scheme == "content") { "Only content images are supported" }
        require(timeoutMs in 1..10_000) { "Invalid image deadline" }
        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val resources = ReadResources()
            val timeout = deadlines.schedule({
                if (finished.compareAndSet(false, true)) {
                    resources.close(cancel = true)
                    continuation.resumeWith(Result.failure(IOException("Image read timed out")))
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation {
                finished.set(true)
                timeout.cancel(false)
                resources.close(cancel = true)
            }
            try {
                readers.execute {
                    val result = runCatching {
                        check(!finished.get()) { "Image read cancelled" }
                        val descriptor = resolver.openAssetFileDescriptor(uri, "r", resources.signal)
                            ?: throw IOException("Cannot open shared image")
                        resources.attach(descriptor)
                        require(descriptor.declaredLength < 0 || descriptor.declaredLength <= ClipPayloadBuilder.MAX_IMAGE_BYTES) { "Image too large" }
                        val stream = descriptor.createInputStream()
                        resources.attach(stream)
                        val bytes = ClipPayloadBuilder.readBounded(stream)
                        val mime = ImageSafety.detectMime(bytes)
                        Image(bytes, mime)
                    }
                    resources.close(cancel = false)
                    if (finished.compareAndSet(false, true)) {
                        timeout.cancel(false)
                        continuation.resumeWith(result)
                    }
                }
            } catch (failure: java.util.concurrent.RejectedExecutionException) {
                if (finished.compareAndSet(false, true)) {
                    timeout.cancel(false)
                    resources.close(cancel = true)
                    continuation.resumeWith(Result.failure(IOException("Image reader busy", failure)))
                }
            }
        }
    }

    private class ReadResources {
        val signal = CancellationSignal()
        private val opened = ArrayList<Closeable>()
        private var closed = false
        fun attach(resource: Closeable) {
            val rejected = synchronized(this) {
                if (closed) true else { opened.add(resource); false }
            }
            if (rejected) {
                runCatching { resource.close() }
                throw IOException("Image read cancelled")
            }
        }
        fun close(cancel: Boolean) {
            val resources = synchronized(this) {
                closed = true
                opened.asReversed().toList().also { opened.clear() }
            }
            resources.forEach { runCatching { it.close() } }
            if (cancel) runCatching { cancellations.execute { signal.cancel() } }
        }
    }
}
