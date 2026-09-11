package com.clipsync.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.clipsync.app.MainActivity
import com.clipsync.app.R
import com.clipsync.images.ImageCache
import com.clipsync.model.ClipPayload

/** Status only: no clipboard text, image, URI or stale reapply intent in notifications. */
class IncomingClipNotifier(private val context: Context, imageCache: ImageCache = ImageCache(context)) {
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("clipsync_incoming")
        manager.deleteNotificationChannel("clipsync_incoming_v2")
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Clipboard updates", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun notify(payload: ClipPayload) {
        if (payload.type !in setOf("text", "image")) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = PendingIntent.getActivity(context, 4244, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ClipSync")
            .setContentText(statusText(payload.type))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build()
        try { NotificationManagerCompat.from(context).notify(4244, notification) } catch (_: SecurityException) { }
    }

    companion object {
        const val CHANNEL_ID = "clipsync_incoming_v3"
        const val NOTIF_ID_IMAGE = 4245
        const val NOTIF_ID_FILE = 4246
        internal fun statusText(type: String) = if (type == "image") "Image received" else "Text received"
        internal fun extensionForMime(mime: String): String = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> "bin"
        }
    }
}
