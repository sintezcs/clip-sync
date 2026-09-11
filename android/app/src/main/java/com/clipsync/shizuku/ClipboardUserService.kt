package com.clipsync.shizuku

import android.content.ClipData
import android.os.IBinder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import com.clipsync.util.L
import java.lang.reflect.Method
import rikka.shizuku.SystemServiceHelper

/**
 * Shizuku UserService that runs in a separate process with UID 2000 (shell).
 *
 * Accesses the system clipboard via reflection on the hidden [IClipboard]
 * interface. Hidden API restrictions do not apply to UID 2000 processes,
 * so no HiddenApiBypass is needed.
 *
 * If the IClipboard method signatures change in a future Android version,
 * all methods gracefully return null/0 and the app falls back to the
 * AccessibilityService approach.
 */
class ClipboardUserService : IClipUserService.Stub() {

    private val clipboard: Any by lazy {
        val binder = SystemServiceHelper.getSystemService("clipboard")
        val stubClass = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder)!!
    }

    private val imagePermit = Semaphore(1)
    private val imageWorker = Executors.newSingleThreadExecutor()
    private val timeoutWorker = Executors.newSingleThreadScheduledExecutor()

    override fun getClipboardSnapshot(): Bundle = ClipboardSnapshot.fromClip(getPrimaryClipOrThrow()).toBundle()

    /** Opens only the current clipboard image, never a caller-supplied URI/path. */
    override fun openClipboardImage(expectedIdentity: String): ParcelFileDescriptor {
        val snapshot = ClipboardSnapshot.fromClip(getPrimaryClipOrThrow())
        require(snapshot.identity == expectedIdentity && !snapshot.sensitive) { "Clipboard changed" }
        require(snapshot.mime in setOf("image/png", "image/jpeg")) { "Unsupported clipboard image" }
        val uri = requireNotNull(snapshot.uri)
        require(android.net.Uri.parse(uri).scheme == "content") { "Only content images are supported" }
        check(imagePermit.tryAcquire()) { "Image read already in progress" }
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (e: Exception) {
            imagePermit.release(); throw e
        }
        imageWorker.execute {
            var process: Process? = null
            var timeout: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                // The shell content tool acquires the provider under the same shell UID
                // that received the clipboard grant. No shell interpolation is used.
                process = ProcessBuilder("/system/bin/content", "read", "--uri", uri)
                    .redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null"))).start()
                val running = process
                timeout = timeoutWorker.schedule({
                    runCatching { pipe[1].closeWithError("Image read timed out") }
                    running.destroyForcibly()
                }, 10, TimeUnit.SECONDS)
                val bytes = running.inputStream.use {
                    com.clipsync.model.ClipPayloadBuilder.readBounded(it, 8 * 1024 * 1024)
                }
                check(running.waitFor() == 0) { "Clipboard provider denied image access" }
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (e: Exception) {
                runCatching { pipe[1].closeWithError("Clipboard image unavailable") }
            } finally {
                timeout?.cancel(false)
                process?.destroyForcibly()
                runCatching { pipe[1].close() }
                imagePermit.release()
            }
        }
        return pipe[0]
    }

    override fun getClipboardText(): String? {
        val clip = getPrimaryClip() ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    override fun setClipboardText(text: String) {
        val clip = ClipData.newPlainText("clipsync", text)
        setPrimaryClipInternal(clip)
    }

    override fun getClipboardHash(): Int {
        val clip = getPrimaryClip() ?: return 0
        if (clip.itemCount == 0) return 0
        val item = clip.getItemAt(0)
        val content = item.text?.toString() ?: item.uri?.toString() ?: ""
        return content.hashCode()
    }

    override fun getClipboardMime(): String? {
        val clip = getPrimaryClip() ?: return null
        val desc = clip.description ?: return null
        return if (desc.mimeTypeCount > 0) desc.getMimeType(0) else null
    }

    override fun getClipboardUri(): String? {
        val clip = getPrimaryClip() ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).uri?.toString()
    }

    override fun setClipboardUri(uri: String, mime: String) {
        val clipUri = android.net.Uri.parse(uri)
        val description = android.content.ClipDescription("clipsync", arrayOf(mime))
        val item = ClipData.Item(clipUri)
        val clip = ClipData(description, item)
        setPrimaryClipInternal(clip)
    }

    override fun destroy() {
        imageWorker.shutdownNow()
        timeoutWorker.shutdownNow()
        System.exit(0)
    }

    // --- Reflection-based access to IClipboard hidden API ---
    // We discover the method signature at runtime instead of hard-coding parameter
    // types, because Android adds extra parameters across versions (e.g. deviceId
    // in Android 14+). We pick the method by name and fill in sensible defaults
    // (null for String?, 0 for int) for any extra parameters we don't recognise.

    private val getMethod: Method? by lazy { findMethod("getPrimaryClip") }
    private val setMethod: Method? by lazy { findMethod("setPrimaryClip") }

    private fun findMethod(name: String): Method? {
        val m = clipboard.javaClass.methods
            .filter { it.name == name }
            .maxByOrNull { it.parameterCount }
        if (m == null) L.error(M, "Method $name not found on IClipboard")
        if (m != null) {
            val types = m.parameterTypes
            check(types.all { it == String::class.java || it == Int::class.javaPrimitiveType || it == ClipData::class.java }) {
                "Unsupported clipboard API signature"
            }
            check(types.count { it == String::class.java } in 1..2 && types.count { it == Int::class.javaPrimitiveType } <= 2) {
                "Unsupported clipboard API signature"
            }
        }
        return m
    }

    private fun getPrimaryClipOrThrow(): ClipData? {
        val method = getMethod ?: error("Unsupported clipboard API")
        return try {
            method.invoke(clipboard, *buildArgs(method, firstArg = PACKAGE)) as? ClipData
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("Clipboard access unavailable", e.cause)
        }
    }

    private fun getPrimaryClip(): ClipData? {
        val method = getMethod ?: return null
        return try {
            val args = buildArgs(method, firstArg = PACKAGE)
            method.invoke(clipboard, *args) as? ClipData
        } catch (e: java.lang.reflect.InvocationTargetException) {
            L.warn(M, "getPrimaryClip failed (cause): ${e.cause}")
            null
        } catch (e: Exception) {
            L.warn(M, "getPrimaryClip failed: $e")
            null
        }
    }

    private fun setPrimaryClipInternal(clip: ClipData) {
        val method = setMethod ?: return
        try {
            val args = buildArgs(method, firstArg = clip)
            method.invoke(clipboard, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            L.warn(M, "setPrimaryClip failed (cause): ${e.cause}")
        } catch (e: Exception) {
            L.warn(M, "setPrimaryClip failed: $e")
        }
    }

    /**
     * Builds the argument array for an IClipboard method by inspecting
     * parameter types at runtime:
     *  - ClipData  → [firstArg] (only used in setPrimaryClip)
     *  - 1st String → package name (or [firstArg] if firstArg is String)
     *  - other Strings → null (attributionTag etc.)
     *  - int / Integer → 0  (userId, deviceId etc.)
     */
    private fun buildArgs(method: Method, firstArg: Any): Array<Any?> {
        var stringCount = 0
        return Array(method.parameterCount) { i ->
            val type = method.parameterTypes[i]
            when {
                type == ClipData::class.java -> firstArg
                type == String::class.java -> {
                    val v: Any? = if (stringCount == 0 && firstArg is String) firstArg
                                  else if (stringCount == 0) PACKAGE
                                  else null  // attributionTag etc.
                    stringCount++
                    v
                }
                type == Int::class.javaPrimitiveType || type == Integer::class.java -> 0
                else -> null
            }
        }
    }

    companion object {
        private const val M = "UserSvc"
        // UserService runs as UID 2000 (shell). We pass our own app package so the
        // system clipboard access notification shows "ClipSync" instead of "Shell".
        // Shell UID typically bypasses package/UID validation on most ROMs.
        // If the security check fails, getPrimaryClip() catches the SecurityException
        // and returns null, falling back gracefully.
        // UID 2000 (shell) must identify as "com.android.shell" so the
        // clipboard service accepts the call. Using our own package name
        // triggers SecurityException on Pixel / Android 13+.
        private const val PACKAGE = "com.android.shell"
    }
}
