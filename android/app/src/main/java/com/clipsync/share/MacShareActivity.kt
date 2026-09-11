package com.clipsync.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.clipsync.images.SharedImageReader
import com.clipsync.model.ClipPayloadBuilder
import com.clipsync.overlay.ClipSender
import com.clipsync.storage.Prefs
import kotlinx.coroutines.*

/** Explicit Android share target; only a single text or PNG/JPEG item is eligible. */
class MacShareActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finishWith("Share interrupted; send again if needed"); return }
        val prefs = Prefs(this)
        if (!prefs.hasPairing() || prefs.host.isNullOrBlank()) { finishWith("Pair ClipSync first"); return }
        if (!prefs.syncEnabled) { finishWith("Sync is paused"); return }
        if (intent.action != Intent.ACTION_SEND) { finishWith("Share one item at a time"); return }
        if (intent.clipData?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) {
            finishWith("Sensitive content is excluded"); return
        }
        val host = prefs.host!!
        val port = prefs.port
        val token = prefs.token!!
        val pin = prefs.fp!!
        val secret = prefs.pairingSecret!!
        @Suppress("DEPRECATION")
        val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        scope.launch {
            val result = try {
                val payload = withContext(Dispatchers.IO) {
                    if (uri != null) {
                        val image = SharedImageReader.read(contentResolver, uri)
                        ClipPayloadBuilder.image(image.mime, image.bytes)
                    } else ClipPayloadBuilder.text(requireNotNull(text) { "Nothing to send" })
                }
                ClipSender().sendCancellable(host, port, token, secret, pin, payload,
                    isCurrent = { prefs.syncEnabled && prefs.token == token && prefs.fp == pin })
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { ClipSender.Result.Failed("Unsupported, inaccessible or oversized content") }
            finishWith(if (result is ClipSender.Result.Ok) "Sent to Mac" else (result as ClipSender.Result.Failed).reason)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun finishWith(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); finish() }
}
