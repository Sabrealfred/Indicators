package com.neopal.pet.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width buckets, same thresholds Material uses — phone, large phone/small tablet, tablet. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** Height buckets. SHORT is "a phone on its side": there is no room to stack chrome vertically. */
enum class HeightClass { SHORT, TALL }

/** The minimum size a control may be hit at; every touch target laid out here honours it. */
val MinTouchTarget: Dp = 48.dp

/**
 * A window classification derived by hand instead of via material3-window-size-class, because the
 * build's dependency list is fixed. The raw dp are kept so layouts can also work in fractions.
 */
@Immutable
data class WindowSize(
    val widthDp: Int,
    val heightDp: Int,
    val width: WidthClass,
    val height: HeightClass,
) {
    val isLandscape: Boolean get() = widthDp > heightDp
    val isCompactWidth: Boolean get() = width == WidthClass.COMPACT
    val isExpandedWidth: Boolean get() = width == WidthClass.EXPANDED
    val isShort: Boolean get() = height == HeightClass.SHORT
    /** True when there is room beside the play area for a permanently visible panel. */
    val isTwoPane: Boolean get() = isExpandedWidth || isLandscape
}

fun windowSizeOf(widthDp: Int, heightDp: Int): WindowSize = WindowSize(
    widthDp = widthDp,
    heightDp = heightDp,
    width = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    },
    height = if (heightDp < 480) HeightClass.SHORT else HeightClass.TALL,
)

/** For use inside BoxWithConstraints, where the box — not the window — is what layout must fit. */
fun windowSizeOf(width: Dp, height: Dp): WindowSize = windowSizeOf(width.value.toInt(), height.value.toInt())

@Composable
fun rememberWindowSize(): WindowSize {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        windowSizeOf(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}
