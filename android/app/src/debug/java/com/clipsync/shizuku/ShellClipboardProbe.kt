package com.clipsync.shizuku

/** Debug-only shell harness. Prints metadata, never clipboard contents. Dedicated emulator only. */
object ShellClipboardProbe {
    @JvmStatic fun main(args: Array<String>) {
        android.os.Looper.prepareMainLooper()
        val helper = ClipboardUserService()
        try {
            if (args.firstOrNull() == "seed-text") helper.setClipboardText("Klippa synthetic shell test")
            val snapshot = ClipboardSnapshot.fromBundle(helper.clipboardSnapshot)
            println("snapshot mime=${snapshot.mime} sensitive=${snapshot.sensitive} textChars=${snapshot.text?.length ?: 0}")
            if (args.firstOrNull() == "read-image") {
                val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(helper.openClipboardImage(snapshot.identity)).use {
                    com.clipsync.model.ClipPayloadBuilder.readBounded(it)
                }
                com.clipsync.images.ImageSafety.validate(bytes, requireNotNull(snapshot.mime))
                println("validated clipboard image bytes=${bytes.size}")
            }
        } catch (e: Exception) {
            System.err.println("Probe failed: ${e.javaClass.simpleName}")
            System.exit(1)
        }
        helper.destroy()
    }
}
