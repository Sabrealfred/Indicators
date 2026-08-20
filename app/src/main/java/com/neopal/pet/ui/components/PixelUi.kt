package com.neopal.pet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * A chunky-but-soft UI kit that shares a grid with the pixel-art scene.
 *
 * The scene is drawn into a low-res buffer and blown up at a whole-number factor (see
 * [com.neopal.pet.ui.art.PixelRenderer]); stock Material chrome around it — 18dp radii, 1dp
 * hairlines, smooth gradient meters — reads as a second, unrelated visual language in the same
 * frame. Everything here is instead measured in whole [PixelUnit]s so the chrome sits on a grid
 * of its own, the way the art does.
 *
 * "Chunky" is about *shape*, not *colour*. Corners are squared off with a notch rather than a
 * radius, edges are two units thick, and buttons physically move when pressed — but no edge is
 * ever pure black. Border colours are blended from the caller's accent toward the surrounding
 * background and highlights are low-alpha white, which is what keeps the result soft instead of
 * harsh.
 */

/** The whole kit is measured in these. Nothing lands on a half-unit. */
val PixelUnit: Dp = 4.dp

/** [PixelUnit] times [n] — the only sanctioned way to spell a size in this kit. */
fun pixelUnits(n: Int): Dp = PixelUnit * n

/** Which way a bevel catches the light. */
enum class PixelBevel {
    /** Lit from the top-left: the default resting state of a panel or button face. */
    RAISED,

    /** Lit from the bottom-right: a pressed button, or a recessed well like an icon slot. */
    PRESSED,

    /** No bevel at all — for glows and fills that should not read as a physical surface. */
    FLAT,
}

/** Matches Material's disabled alpha so a dimmed pixel surface sits next to dimmed Material. */
private const val PixelDisabledAlpha = 0.38f

/** Highlights stay this faint; a bright bevel is what makes retro chrome look cheap. */
private const val HighlightAlpha = 0.16f

/**
 * A border colour derived from [accent] rather than picked. Blending toward [background] keeps
 * the edge in the same family as whatever it frames, and guarantees it is never pure black.
 */
/**
 * Black or white, whichever reads on [fill]. Leaving this to the caller is how a badge ends up
 * as white text on gold: every call site has to remember, and one of them never does.
 */
fun inkFor(fill: Color): Color =
    if (fill.luminance() > 0.55f) Color(0xFF15171A) else Color(0xFFF6F8FC)

/**
 * A surface that is present but not available — a locked award, a game you cannot start yet.
 * Kept here rather than lerped by hand at each call site so "unavailable" looks like one thing
 * across the whole app.
 */
@Composable
fun dimmedFor(color: Color, background: Color, amount: Float = 0.7f): Color =
    lerp(color, background, amount.coerceIn(0f, 1f))

fun pixelEdgeColor(accent: Color, background: Color, toward: Float = 0.42f): Color =
    lerp(accent.copy(alpha = 1f), background.copy(alpha = 1f), toward.coerceIn(0f, 1f))

/**
 * A pixel-art corner: two overlapping rects, so the corner loses exactly [notch] pixels on each
 * axis instead of being swept through a radius the upscaled art never uses.
 */
private fun DrawScope.notchedRect(
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    notch: Float,
) {
    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) return
    val n = notch.coerceAtMost(minOf(w, h) / 2f)
    drawRect(color, Offset(left + n, top), Size(w - 2f * n, h))
    drawRect(color, Offset(left, top + n), Size(w, h - 2f * n))
}

/** Device pixels per grid unit, floored so a unit is always a whole number of pixels. */
private fun DrawScope.gridUnit(): Float = floor(PixelUnit.toPx()).coerceAtLeast(1f)

private fun DrawScope.drawPixelSurface(
    fill: Color,
    edge: Color,
    light: Color,
    dark: Color,
    bevel: PixelBevel,
    borderUnits: Int,
) {
    val u = gridUnit()
    val border = u * borderUnits
    // The notch scales with the frame: a two-unit notch on a pill-sized chip eats the whole corner.
    val notch = if (borderUnits >= 2) u * 2f else u
    val w = size.width
    val h = size.height
    if (borderUnits > 0) notchedRect(edge, 0f, 0f, w, h, notch)
    notchedRect(fill, border, border, w - border, h - border, notch)
    if (bevel == PixelBevel.FLAT) return

    // Swapping which pair of edges is lit is the whole trick: the same geometry reads as raised
    // or as pressed in, with no extra layout and no shadow blur.
    val topLeft = if (bevel == PixelBevel.RAISED) light else dark
    val bottomRight = if (bevel == PixelBevel.RAISED) dark else light
    val inset = border
    val run = inset + notch
    val hSpan = (w - 2f * run).coerceAtLeast(0f)
    val vSpan = (h - 2f * run).coerceAtLeast(0f)
    if (hSpan <= 0f || vSpan <= 0f) return
    drawRect(topLeft, Offset(run, inset), Size(hSpan, u))
    drawRect(topLeft, Offset(inset, run), Size(u, vSpan))
    drawRect(bottomRight, Offset(run, h - inset - u), Size(hSpan, u))
    drawRect(bottomRight, Offset(w - inset - u, run), Size(u, vSpan))
}

/**
 * Gives any composable the kit's bevelled, notched surface: a solid outer edge, a lighter
 * highlight on the top-left and a darker one on the bottom-right.
 *
 * Order matters — put this *after* any `graphicsLayer` so the surface scales with the content,
 * and *before* `padding` so the padding lands inside the border.
 */
@Composable
/**
 * Padding that clears the bevel. `pixelSurface` pays out exactly the border width, so opaque
 * content laid straight on top paints over the highlight edge and the panel loses its shape.
 */
fun bevelSafePadding(borderUnits: Int = 2): Dp = pixelUnits(borderUnits + 1)

@Composable
fun Modifier.pixelSurface(
    fill: Color,
    accent: Color,
    bevel: PixelBevel = PixelBevel.RAISED,
    borderUnits: Int = 2,
    background: Color = MaterialTheme.colorScheme.background,
): Modifier {
    val edge = pixelEdgeColor(accent, background)
    val light = Color.White.copy(alpha = HighlightAlpha)
    // The shadow edge is mixed from the fill and the border, so it darkens without going black.
    val dark = lerp(fill.copy(alpha = 1f), edge, 0.55f).copy(alpha = fill.alpha)
    return this.drawBehind { drawPixelSurface(fill, edge, light, dark, bevel, borderUnits) }
}

/**
 * The kit's container: a bevelled box with an optional title strip.
 *
 * Pass [onClick] to make the whole panel a button — it then also claims a [MinTouchTarget].
 */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    accent: Color = MaterialTheme.colorScheme.primary,
    background: Color = MaterialTheme.colorScheme.background,
    title: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    bevel: PixelBevel = PixelBevel.RAISED,
    borderUnits: Int = 2,
    contentPadding: PaddingValues = PaddingValues(pixelUnits(3)),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shell = modifier
        .then(
            if (onClick != null) {
                Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            } else {
                Modifier
            },
        )
        .pixelSurface(fill = fill, accent = accent, bevel = bevel, borderUnits = borderUnits, background = background)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(pixelUnits(borderUnits))

    Column(modifier = shell) {
        if (title != null) {
            PixelTitleStrip(title = title, accent = accent, titleColor = titleColor, fill = fill)
        }
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
private fun PixelTitleStrip(title: String, accent: Color, titleColor: Color, fill: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(lerp(fill.copy(alpha = 1f), accent, 0.20f))
            .padding(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
    ) {
        // A block of accent instead of accent-coloured type: a 4dp bar only has to clear 3:1.
        Box(Modifier.size(width = pixelUnits(1), height = pixelUnits(3)).background(accent))
        Spacer(Modifier.width(pixelUnits(2)))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
    PixelDivider(color = lerp(fill.copy(alpha = 1f), accent, 0.45f))
}

/**
 * A button that physically depresses: on press the bevel flips and the content shifts one unit
 * down-right, so the face of the button moves rather than just changing colour.
 */
@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    background: Color = MaterialTheme.colorScheme.background,
    enabled: Boolean = true,
    borderUnits: Int = 2,
    contentPadding: PaddingValues = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed && enabled
    val shift = if (down) pixelUnits(1) else 0.dp

    Box(
        modifier = modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .pixelSurface(
                fill = fill,
                accent = accent,
                bevel = if (down) PixelBevel.PRESSED else PixelBevel.RAISED,
                borderUnits = borderUnits,
                background = background,
            )
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = source,
                indication = LocalIndication.current,
            )
            .padding(pixelUnits(borderUnits)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .offset(x = shift, y = shift)
                .graphicsLayer { alpha = if (enabled) 1f else PixelDisabledAlpha }
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A segmented meter. Discrete cells with a one-unit gap read as a pixel-art gauge where a smooth
 * gradient reads as a Material progress bar; the partially filled cell is drawn at reduced alpha
 * so the exact value is still legible between segment boundaries.
 */
@Composable
fun PixelBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    /**
     * An upper bound, not a promise. A cell thinner than two units stops reading as a cell and
     * the bar collapses back into a gradient, so a narrow bar drops cells rather than shrinking
     * them — the five-across strip asks for ten and gets four or five.
     */
    segments: Int = 20,
    height: Dp = pixelUnits(5),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    background: Color = MaterialTheme.colorScheme.background,
    // Null draws no marker; the stat meters pass the fraction the game calls critical.
    markerFraction: Float? = null,
    markerColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
) {
    val value = fraction.coerceIn(0f, 1f)
    val edge = pixelEdgeColor(color, background)
    val well = lerp(trackColor.copy(alpha = 1f), background.copy(alpha = 1f), 0.30f)

    // The bar carries no text of its own; whoever owns the value announces it.
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val u = gridUnit()
        notchedRect(edge, 0f, 0f, size.width, size.height, u)
        notchedRect(well, u, u, size.width - u, size.height - u, u)

        val left = u
        val right = size.width - u
        val top = u
        val bottom = size.height - u
        val span = right - left
        if (span <= 0f || bottom <= top) return@Canvas

        // One unit of gap plus at least two units of cell; below that, use fewer cells.
        val roomFor = floor(span / (u * 3f)).toInt().coerceAtLeast(1)
        val cells = segments.coerceAtLeast(1).coerceAtMost(roomFor)
        val step = span / cells
        val exact = value * cells
        val full = floor(exact).toInt()
        val partial = exact - full
        for (i in 0 until cells) {
            val cellAlpha = when {
                i < full -> 1f
                // A cell that is half full is drawn half lit, so 47 and 52 do not look identical.
                i == full && partial > 0.02f -> 0.25f + 0.5f * partial
                else -> 0f
            }
            if (cellAlpha <= 0f) continue
            // Round each edge to a whole device pixel, otherwise the gaps shimmer at some widths.
            val cellLeft = (left + i * step).roundToInt().toFloat()
            val cellRight = (left + (i + 1) * step - u).roundToInt().toFloat()
            if (cellRight <= cellLeft) continue
            drawRect(
                color = color.copy(alpha = color.alpha * cellAlpha),
                topLeft = Offset(cellLeft, top),
                size = Size(cellRight - cellLeft, bottom - top),
            )
        }

        markerFraction?.let { marker ->
            if (marker !in 0f..1f) return@let
            val x = (left + span * marker - u / 2f).roundToInt().toFloat()
            drawRect(markerColor, Offset(x.coerceIn(0f, size.width - u), 0f), Size(u, size.height))
        }
    }
}

/** A small bevelled chip for counts and rewards. */
@Composable
fun PixelBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    /** Defaults to whichever of black or white actually reads on [color]. */
    contentColor: Color = inkFor(color),
    background: Color = MaterialTheme.colorScheme.background,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = pixelUnits(5), minHeight = pixelUnits(5))
            .pixelSurface(fill = color, accent = color, borderUnits = 1, background = background)
            .padding(horizontal = pixelUnits(1), vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** A dashed rule built from whole blocks rather than a 1dp hairline. */
@Composable
/**
 * The vertical twin of [PixelDivider], for timelines and rails. The diary had to hand-roll one
 * because the kit only shipped the horizontal case.
 */
@Composable
fun PixelRail(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
    dashUnits: Int = 2,
    widthUnits: Int = 1,
) {
    Canvas(
        modifier
            .width(pixelUnits(widthUnits))
            .clearAndSetSemantics { },
    ) {
        val dash = gridUnit() * dashUnits.coerceAtLeast(1)
        var y = 0f
        while (y < size.height) {
            drawRect(color, Offset(0f, y), Size(size.width, dash.coerceAtMost(size.height - y)))
            y += dash * 2f
        }
    }
}

@Composable
fun PixelDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
    dashUnits: Int = 2,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(pixelUnits(1))
            .clearAndSetSemantics { },
    ) {
        val dash = gridUnit() * dashUnits.coerceAtLeast(1)
        var x = 0f
        while (x < size.width) {
            drawRect(color, Offset(x, 0f), Size(dash.coerceAtMost(size.width - x), size.height))
            x += dash * 2f
        }
    }
}
