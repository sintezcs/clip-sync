package com.clipsync.app

import android.app.Application
import com.clipsync.sync.ClipboardEvents
import com.clipsync.sync.DurableReplayStore
import java.io.File

/** Installs replay protection for service and explicit sends; file access stays lazy on IO callers. */
class ClipSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ClipboardEvents.installStore(DurableReplayStore(File(filesDir, "clipboard-replay/events-v1"),
            syncDirectory = { directory ->
                // Android's public Os API opens directories directly; no FileInputStream directory assumptions.
                val descriptor = android.system.Os.open(directory.absolutePath,
                    android.system.OsConstants.O_RDONLY, 0)
                try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
            }))
    }
}
