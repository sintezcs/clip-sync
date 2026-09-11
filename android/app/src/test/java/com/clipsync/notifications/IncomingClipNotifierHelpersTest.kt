package com.clipsync.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingClipNotifierHelpersTest {
    @Test fun only_generic_status_is_exposed() {
        assertEquals("Text received", IncomingClipNotifier.statusText("text"))
        assertEquals("Image received", IncomingClipNotifier.statusText("image"))
    }
    @Test fun only_supported_image_extensions_are_used() {
        assertEquals("png", IncomingClipNotifier.extensionForMime("image/png"))
        assertEquals("jpg", IncomingClipNotifier.extensionForMime("image/jpeg"))
        assertEquals("bin", IncomingClipNotifier.extensionForMime("../../private"))
    }
}
