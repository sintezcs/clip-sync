package com.clipsync.ui.layout

import org.junit.Assert.*
import org.junit.Test

class AdaptiveGeometryTest {
    @Test fun `asymmetric vertical fold uses exact physical sides`() {
        val geometry = AdaptiveGeometry.resolve(1000f, 700f, 1f, FoldSpan(FoldAxis.VERTICAL, 360f, 400f), true)
        assertEquals(PaneBounds(0f, 0f, 360f, 700f), geometry.primary)
        assertEquals(PaneBounds(400f, 0f, 600f, 700f), geometry.detail)
    }
    @Test fun `narrow left pane selects right physical region regardless of text direction`() {
        val geometry = AdaptiveGeometry.resolve(700f, 700f, 1f, FoldSpan(FoldAxis.VERTICAL, 240f, 270f), true)
        assertEquals(PaneBounds(270f, 0f, 430f, 700f), geometry.primary)
        assertNull(geometry.detail)
    }
    @Test fun `large fonts collapse panes without crossing hinge`() {
        val geometry = AdaptiveGeometry.resolve(1000f, 700f, 2f, FoldSpan(FoldAxis.VERTICAL, 500f, 520f), true)
        assertNull(geometry.detail)
        assertEquals(PaneBounds(0f, 0f, 500f, 700f), geometry.primary)
    }
    @Test fun `tabletop separates status from scrollable content`() {
        val geometry = AdaptiveGeometry.resolve(700f, 800f, 1f, FoldSpan(FoldAxis.HORIZONTAL, 360f, 400f), true)
        assertEquals(PaneBounds(0f, 0f, 700f, 360f), geometry.status)
        assertEquals(PaneBounds(0f, 400f, 700f, 400f), geometry.primary)
    }
    @Test fun `short tabletop uses larger usable side instead of tiny content strip`() {
        val geometry = AdaptiveGeometry.resolve(700f, 300f, 1f, FoldSpan(FoldAxis.HORIZONTAL, 180f, 200f), true)
        assertNull(geometry.status)
        assertEquals(PaneBounds(0f, 0f, 700f, 180f), geometry.primary)
    }
    @Test fun `fold clipped outside inset content leaves full usable rectangle`() {
        val geometry = AdaptiveGeometry.resolve(700f, 300f, 1f, FoldSpan(FoldAxis.HORIZONTAL, -50f, -20f), true)
        assertEquals(PaneBounds(0f, 0f, 700f, 300f), geometry.primary)
    }
    @Test fun `expanded breakpoint respects font scale and retains short window height`() {
        assertNull(AdaptiveGeometry.resolve(839f, 180f, 1f, null, false).detail)
        val wide = AdaptiveGeometry.resolve(840f, 180f, 1f, null, false)
        assertNotNull(wide.detail)
        assertEquals(180f, wide.primary.height, 0f)
        assertNull(AdaptiveGeometry.resolve(1000f, 180f, 2f, null, false).detail)
    }
    @Test fun `all produced panes stay within window and outside occluding fold`() {
        for (width in listOf(320f, 700f, 1000f)) for (scale in listOf(1f, 2f)) {
            val start = width * .4f
            val end = start + 20f
            val geometry = AdaptiveGeometry.resolve(width, 400f, scale, FoldSpan(FoldAxis.VERTICAL, start, end), true)
            for (pane in listOfNotNull(geometry.primary, geometry.detail, geometry.status)) {
                assertTrue(pane.left >= 0f && pane.left + pane.width <= width)
                assertTrue(pane.top >= 0f && pane.top + pane.height <= 400f)
                assertTrue(pane.left + pane.width <= start || pane.left >= end)
            }
        }
    }
}
