package com.clipsync.ui

/** Permission comes from the live Shizuku check, even before a sync service exists. */
internal data class ClipboardAccessDiagnostics(
    val authorization: String,
    val process: String,
    val clipboard: String
) {
    companion object {
        fun from(
            shizukuState: String,
            paired: Boolean,
            syncEnabled: Boolean,
            helperRunning: Boolean,
            clipboardReadable: Boolean
        ): ClipboardAccessDiagnostics {
            val authorization = when (shizukuState) {
                "ready" -> "Allowed"
                "no_permission" -> "Needed"
                "not_installed" -> "Install Shizuku first"
                "not_running" -> "Start Shizuku to check"
                else -> "Not checked"
            }
            val running = shizukuState == "ready" && paired && syncEnabled && helperRunning
            val process = when {
                running -> "Running"
                !paired -> "Not running — pair a Mac first"
                !syncEnabled -> "Not running — sync is paused"
                shizukuState == "no_permission" -> "Not running — authorization needed"
                shizukuState != "ready" -> "Not running — start Shizuku first"
                else -> "Not running — waiting for helper"
            }
            return ClipboardAccessDiagnostics(authorization, process,
                if (running && clipboardReadable) "Readable" else "Not confirmed")
        }
    }
}
