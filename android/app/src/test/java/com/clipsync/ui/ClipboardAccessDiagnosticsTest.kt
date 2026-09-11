package com.clipsync.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardAccessDiagnosticsTest {
    @Test fun authorizedUnpairedPhoneDoesNotRequestPermissionAgain() {
        val result = ClipboardAccessDiagnostics.from("ready", false, true, false, false)
        assertEquals("Allowed", result.authorization)
        assertEquals("Not running — pair a Mac first", result.process)
        assertEquals("Not confirmed", result.clipboard)
    }

    @Test fun authorizedPausedPhoneDoesNotRequestPermissionAgain() {
        val result = ClipboardAccessDiagnostics.from("ready", true, false, false, false)
        assertEquals("Allowed", result.authorization)
        assertEquals("Not running — sync is paused", result.process)
    }

    @Test fun unavailableBinderDoesNotPretendPermissionWasDenied() {
        val result = ClipboardAccessDiagnostics.from("not_running", true, true, true, true)
        assertEquals("Start Shizuku to check", result.authorization)
        assertEquals("Not running — start Shizuku first", result.process)
        assertEquals("Not confirmed", result.clipboard)
    }

    @Test fun deniedPermissionAndRunningHelperHaveDifferentStates() {
        val denied = ClipboardAccessDiagnostics.from("no_permission", true, true, true, true)
        assertEquals("Needed", denied.authorization)
        assertEquals("Not confirmed", denied.clipboard)
        val running = ClipboardAccessDiagnostics.from("ready", true, true, true, true)
        assertEquals("Allowed", running.authorization)
        assertEquals("Running", running.process)
        assertEquals("Readable", running.clipboard)
    }
}
