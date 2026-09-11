package com.clipsync.images

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Instrumentation-only provider: controlled open/read stalls and a synthetic PNG. */
class StalledImageProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = error("Reader must sniff bytes, not call getType")
    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        entered.countDown()
        if (uri.lastPathSegment == "open-stall") {
            val cancelled = CountDownLatch(1)
            signal?.setOnCancelListener { cancelled.countDown(); openCancelled.countDown() }
            if (!cancelled.await(5, TimeUnit.SECONDS)) error("Cancellation signal never arrived")
            throw OperationCanceledException()
        }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        if (uri.lastPathSegment == "read-stall") {
            writers.add(pipe[1])
        } else {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        }
        return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object {
        @Volatile var entered = CountDownLatch(1)
        @Volatile var openCancelled = CountDownLatch(1)
        private val writers = CopyOnWriteArrayList<ParcelFileDescriptor>()
        fun reset() { writers.forEach { runCatching { it.close() } }; writers.clear(); entered = CountDownLatch(1); openCancelled = CountDownLatch(1) }
    }
}
