package com.clipsync.shizuku

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.PersistableBundle
import org.junit.Assert.*
import org.junit.Test

class ClipboardSnapshotTest {
    @Test fun platformHtmlClipUsesItsExistingPlainRepresentation() {
        val clip = ClipData.newHtmlText("Rich text", "Plain Ω", "<b>Different markup Ω</b>")
        assertTrue(clip.description.hasMimeType("text/html"))
        val snapshot = ClipboardSnapshot.fromClip(clip)
        assertEquals("text/plain", snapshot.mime)
        assertEquals("Plain Ω", snapshot.text)
        assertNull(snapshot.uri)
        assertFalse(snapshot.sensitive)
        assertEquals(snapshot, ClipboardSnapshot.fromBundle(snapshot.toBundle()))
    }

    @Test fun htmlFirstDescriptionDoesNotHideAdvertisedPlainText() {
        val clip = ClipData(ClipDescription("Rich text", arrayOf("text/html", "text/plain")),
            ClipData.Item("Existing plain", "<i>HTML</i>"))
        val snapshot = ClipboardSnapshot.fromClip(clip)
        assertEquals("text/plain", snapshot.mime)
        assertEquals("Existing plain", snapshot.text)
    }

    @Test fun sensitiveHtmlStillExcludesClipboardContent() {
        val clip = ClipData.newHtmlText("Private", "Private plain", "<b>Private</b>")
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        val snapshot = ClipboardSnapshot.fromClip(clip)
        assertTrue(snapshot.sensitive)
        assertNull(snapshot.text)
        assertNull(snapshot.uri)
    }

    @Test fun uriWithHtmlDescriptionIsNotCoercedOrPromotedToPlainText() {
        val clip = ClipData(ClipDescription("URI", arrayOf("text/html")),
            ClipData.Item(Uri.parse("content://unresolved.invalid/item")))
        val snapshot = ClipboardSnapshot.fromClip(clip)
        assertEquals("text/html", snapshot.mime)
        assertNull(snapshot.text)
        assertEquals("content://unresolved.invalid/item", snapshot.uri)
    }

    @Test fun unrelatedMimeDoesNotBecomeTextJustBecauseItHasATextField() {
        val clip = ClipData(ClipDescription("Custom", arrayOf("application/x-custom")), ClipData.Item("Not advertised as text"))
        assertEquals("application/x-custom", ClipboardSnapshot.fromClip(clip).mime)
    }
}
