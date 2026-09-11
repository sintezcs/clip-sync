package com.clipsync.ui.layout

/** Physical left/top coordinates in dp, intentionally independent of text/layout direction. */
data class PaneBounds(val left: Float, val top: Float, val width: Float, val height: Float)
enum class FoldAxis { VERTICAL, HORIZONTAL }
data class FoldSpan(val axis: FoldAxis, val start: Float, val end: Float)
data class SettingsGeometry(val primary: PaneBounds, val detail: PaneBounds? = null, val status: PaneBounds? = null)

object AdaptiveGeometry {
    fun resolve(width: Float, height: Float, fontScale: Float, fold: FoldSpan?, hasCompactStatus: Boolean): SettingsGeometry {
        require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
        require(fontScale.isFinite() && fontScale > 0)
        val minPane = 320f * fontScale.coerceAtLeast(1f)
        if (fold == null) {
            if (width >= 840f && width >= minPane * 2 + 24f) {
                val paneWidth = (width - 8f) / 2
                return SettingsGeometry(PaneBounds(0f, 0f, paneWidth, height), PaneBounds(paneWidth + 8, 0f, paneWidth, height))
            }
            return SettingsGeometry(PaneBounds(0f, 0f, width, height))
        }
        require(fold.start.isFinite() && fold.end.isFinite())
        val extent = if (fold.axis == FoldAxis.VERTICAL) width else height
        val start = fold.start.coerceIn(0f, extent)
        val end = fold.end.coerceIn(start, extent)
        val trailing = extent - end
        return if (fold.axis == FoldAxis.VERTICAL) {
            val left = PaneBounds(0f, 0f, start, height)
            val right = PaneBounds(end, 0f, trailing, height)
            if (start >= minPane && trailing >= minPane) SettingsGeometry(left, right)
            else SettingsGeometry(if (start >= trailing) left else right)
        } else {
            val top = PaneBounds(0f, 0f, width, start)
            val bottom = PaneBounds(0f, end, width, trailing)
            if (hasCompactStatus && start >= 96f && trailing >= 160f) SettingsGeometry(bottom, status = top)
            else SettingsGeometry(if (start >= trailing) top else bottom)
        }
    }
}
