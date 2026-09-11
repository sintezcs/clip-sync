package com.clipsync.overlay

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.clipsync.util.L
import android.view.View
import android.widget.Toast
import com.clipsync.images.SharedImageReader
import com.clipsync.model.ClipPayloadBuilder
import com.clipsync.storage.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent trampoline Activity launched by the clipboard overlay FAB.
 *
 * Android 10+ restricts ClipboardManager.getPrimaryClip() to apps that have
 * a focused window (or are the default IME). Two issues prevented this from
 * working on Pixel / Android 12-13:
 *
 *  1. No setContentView() — without a view hierarchy the window is not fully
 *     registered in WindowManager and may not receive input focus.
 *  2. Clipboard was read in onCreate() — the window has not received input
 *     focus yet at that point; focus arrives later via onWindowFocusChanged().
 *
 * Fix: attach a transparent content view so the window is properly set up,
 * then read clipboard in onWindowFocusChanged(hasFocus=true). A short
 * postDelayed fallback handles the edge case where a translucent window never
 * fires onWindowFocusChanged on some devices.
 */
class SendClipActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val sender = ClipSender()
    private val handler = Handler(Looper.getMainLooper())

    private var clipboardAttempted = false
    private var isAutoSend = false

    private val fallbackRunnable = Runnable {
        if (!clipboardAttempted) {
            clipboardAttempted = true
            sendClipboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) { toast("Send interrupted; try again if needed"); finish(); return }
        isAutoSend = intent.getBooleanExtra(EXTRA_AUTO_SEND, false)

        // A real (transparent) content view is required so the window is
        // properly registered in WindowManager and can receive input focus.
        // Without this, getPrimaryClip() returns null on Android 10+.
        setContentView(View(this))

        // Fallback: if onWindowFocusChanged never fires (can happen with
        // translucent activities on some Android 12-13 builds), read clipboard
        // after 200 ms — enough time for the window to settle.
        handler.postDelayed(fallbackRunnable, 200)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Primary trigger: the window now has input focus, so getPrimaryClip()
        // will return the actual content instead of null.
        if (hasFocus && !clipboardAttempted) {
            clipboardAttempted = true
            handler.removeCallbacks(fallbackRunnable)
            sendClipboard()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(fallbackRunnable)
        scope.cancel()
        super.onDestroy()
    }

    private fun sendClipboard() {
        val prefs = Prefs(applicationContext)
        if (!prefs.hasPairing() || prefs.host.isNullOrEmpty() || prefs.pairingSecret.isNullOrEmpty()) {
            toast("Pair ClipSync first")
            finish()
            return
        }

        if (!prefs.syncEnabled) { toast("Sync is paused"); finish(); return }
        val host = prefs.host!!
        val port = prefs.port
        val token = prefs.token!!
        val secret = prefs.pairingSecret!!
        val fp = prefs.fp!!

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("Nothing to send")
            finish()
            return
        }

        if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) {
            toast("Sensitive clipboard content is excluded")
            finish()
            return
        }
        val item = clip.getItemAt(0)
        val mimeType = (0 until clip.description.mimeTypeCount).map(clip.description::getMimeType)
            .firstOrNull { it.startsWith("image/") && item.uri != null }
            ?: if (clip.description.mimeTypeCount > 0) clip.description.getMimeType(0) else ""

        when {
            // Check image MIME first — some clips have both URI and text
            mimeType.startsWith("image/") -> {
                val uri = item.uri
                if (uri == null) {
                    toast("No image in clipboard")
                    finish()
                    return
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val image = SharedImageReader.read(contentResolver, uri)
                            val payload = ClipPayloadBuilder.image(image.mime, image.bytes)
                            sender.sendCancellable(host, port, token, secret, fp, payload, isCurrent = { prefs.syncEnabled && prefs.token == token && prefs.fp == fp })
                        } catch (t: kotlinx.coroutines.CancellationException) { throw t
                        } catch (t: Exception) {
                            ClipSender.Result.Failed("Unsupported, inaccessible or oversized image")
                        }
                    }
                    handleResult(result)
                }
            }
            mimeType.startsWith("text/") || item.text != null -> {
                val text = item.text?.toString()
                if (text.isNullOrEmpty()) {
                    toast("Empty clipboard")
                    finish()
                    return
                }
                val payload = try { ClipPayloadBuilder.text(text) } catch (_: IllegalArgumentException) {
                    toast("Text is too large"); finish(); return
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        sender.sendCancellable(host, port, token, secret, fp, payload, isCurrent = { prefs.syncEnabled && prefs.token == token && prefs.fp == fp })
                    }
                    handleResult(result)
                }
            }
            else -> {
                toast("Unsupported clipboard content")
                finish()
            }
        }
    }

    private fun handleResult(result: ClipSender.Result) {
        when (result) {
            is ClipSender.Result.Ok -> toast("Sent to Mac")
            is ClipSender.Result.Failed -> {
                toast("Failed: ${result.reason}")
                L.warn(M, "Send failed: ${result.reason}")
            }
        }
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val M = "Send"
        const val EXTRA_AUTO_SEND = "auto_send"

        fun intent(context: Context): Intent {
            return Intent(context, SendClipActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}
