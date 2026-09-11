package com.clipsync.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

/** Fold bounds use physical window coordinates; pane placement remains physical even in RTL locales. */
@Composable
fun AdaptiveSettingsLayout(
    showDetail: Boolean,
    fold: FoldingFeature? = null,
    windowOffsetX: Float = 0f,
    windowOffsetY: Float = 0f,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    compactStatus: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val separating = fold?.takeIf { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
        val span = separating?.let {
            if (it.orientation == FoldingFeature.Orientation.VERTICAL) FoldSpan(FoldAxis.VERTICAL,
                with(density) { (it.bounds.left - windowOffsetX).toDp().value },
                with(density) { (it.bounds.right - windowOffsetX).toDp().value })
            else FoldSpan(FoldAxis.HORIZONTAL,
                with(density) { (it.bounds.top - windowOffsetY).toDp().value },
                with(density) { (it.bounds.bottom - windowOffsetY).toDp().value })
        }
        val geometry = AdaptiveGeometry.resolve(maxWidth.value, maxHeight.value, density.fontScale, span, compactStatus != null)
        geometry.status?.let { bounds ->
            PhysicalPane(bounds) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    compactStatus?.invoke()
                }
            }
        }
        PhysicalPane(geometry.primary) {
            if (geometry.detail != null || !showDetail) list() else detail()
        }
        geometry.detail?.let { bounds -> PhysicalPane(bounds, detail) }
    }
}

@Composable
private fun PhysicalPane(bounds: PaneBounds, content: @Composable () -> Unit) {
    Box(Modifier.absoluteOffset(bounds.left.dp, bounds.top.dp).size(bounds.width.dp, bounds.height.dp),
        contentAlignment = Alignment.TopCenter) {
        // Contents inherit the user's text direction; only physical pane placement ignores RTL.
        Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) { content() }
    }
}
