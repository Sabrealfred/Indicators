package com.neopal.pet.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

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
fun dimmedFor(color: Color, background: Color, amount: Float = 0.7f): Color =
    lerp(color, background, amount.coerceIn(0f, 1f))

/**
 * A border colour derived from [accent] rather than picked. Blending toward [background] keeps
 * the edge in the same family as whatever it frames, and guarantees it is never pure black.
 */
fun pixelEdgeColor(accent: Color, background: Color, toward: Float = 0.42f): Color =
    lerp(accent.copy(alpha = 1f), background.copy(alpha = 1f), toward.coerceIn(0f, 1f))

/**
 * The fill of anything recessed — a text well, a meter track, an icon slot. Every well was being
 * lerped by hand at its call site, which is how two wells side by side end up different depths.
 *
 * [depth] is how far the surface sinks toward [background]; the default is the well proper, and
 * shallower values are for surfaces that only need to read as *behind* rather than as a hole.
 */
fun pixelWellFill(surface: Color, background: Color, depth: Float = 0.35f): Color =
    lerp(surface.copy(alpha = 1f), background.copy(alpha = 1f), depth.coerceIn(0f, 1f))

/**
 * A pixel-art corner: two overlapping rects, so the corner loses exactly [notch] pixels on each
 * axis instead of being swept through a radius the upscaled art never uses.
 */
fun DrawScope.notchedRect(
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
fun DrawScope.gridUnit(): Float = floor(PixelUnit.toPx()).coerceAtLeast(1f)

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
 * Padding that clears the bevel. `pixelSurface` pays out exactly the border width, so opaque
 * content laid straight on top paints over the highlight edge and the panel loses its shape.
 */
fun bevelSafePadding(borderUnits: Int = 2): Dp = pixelUnits(borderUnits + 1)

/**
 * Gives any composable the kit's bevelled, notched surface: a solid outer edge, a lighter
 * highlight on the top-left and a darker one on the bottom-right.
 *
 * Order matters — put this *after* any `graphicsLayer` so the surface scales with the content,
 * and *before* `padding` so the padding lands inside the border.
 */
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
 * Pass [titleTrailing] to hang a count or a small action off the right of the title strip; it is
 * laid out in the strip's own [Row], so a badge and a button can sit there side by side.
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
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
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
            PixelTitleStrip(
                title = title,
                accent = accent,
                titleColor = titleColor,
                fill = fill,
                trailing = titleTrailing,
            )
        }
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
private fun PixelTitleStrip(
    title: String,
    accent: Color,
    titleColor: Color,
    fill: Color,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
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
            // The title only takes the weight when something is competing for the row; without a
            // trailing slot it stays intrinsically sized, exactly as it was.
            modifier = (if (trailing != null) Modifier.weight(1f) else Modifier).semantics { heading() },
        )
        if (trailing != null) {
            Spacer(Modifier.width(pixelUnits(2)))
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
    PixelDivider(color = lerp(fill.copy(alpha = 1f), accent, 0.45f))
}

/**
 * A button that physically depresses: on press the bevel flips and the content shifts one unit
 * down-right, so the face of the button moves rather than just changing colour.
 *
 * [bevel] and [pressedBevel] are the two halves of that flip. A tab that is already the live one
 * pins both to [PixelBevel.PRESSED] so it stays sunk in while it is tapped; a glow-only button
 * pins both to [PixelBevel.FLAT].
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
    bevel: PixelBevel = PixelBevel.RAISED,
    pressedBevel: PixelBevel = PixelBevel.PRESSED,
    /** Ink for the whole content slot; an unset one inherits, which is what it did before. */
    contentColor: Color = LocalContentColor.current,
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
                bevel = if (down) pressedBevel else bevel,
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
        CompositionLocalProvider(LocalContentColor provides contentColor) {
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
    // Shallower than a text well: the track is behind the fill, not a hole the fill sits in.
    val well = pixelWellFill(trackColor, background, depth = 0.30f)

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
    /**
     * What TalkBack should say instead of the bare [text]. A badge reading "3" or "!" out of
     * context is noise, so give it the sentence — "3 unread letters" — or pass the empty string
     * to drop it from the tree when the row around it already says the same thing.
     */
    contentDescription: String? = null,
) {
    val readOut = contentDescription
    Box(
        modifier = modifier
            .sizeIn(minWidth = pixelUnits(5), minHeight = pixelUnits(5))
            .pixelSurface(fill = color, accent = color, borderUnits = 1, background = background)
            .padding(horizontal = pixelUnits(1), vertical = 0.dp)
            .then(
                when {
                    readOut == null -> Modifier
                    readOut.isEmpty() -> Modifier.clearAndSetSemantics { }
                    else -> Modifier.semantics(mergeDescendants = true) { this.contentDescription = readOut }
                },
            ),
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

/** A dashed rule built from whole blocks rather than a 1dp hairline. */
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

/**
 * A one-of-many choice. A Material `FilterChip` inside a bevelled panel is exactly the second
 * visual language this kit exists to remove, so the chip is a small panel that sits pressed *in*
 * while it is the live one — the same "held down" reading the buttons already use.
 */
@Composable
fun PixelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    background: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
) {
    // Unselected chips borrow the muted on-surface colour for their edge: a row of chips all
    // outlined in the accent reads as a row of selected ones.
    val edgeAccent = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .pixelSurface(
                fill = if (selected) lerp(fill.copy(alpha = 1f), accent, 0.22f) else fill,
                accent = edgeAccent,
                bevel = if (selected) PixelBevel.PRESSED else PixelBevel.RAISED,
                borderUnits = 1,
                background = background,
            )
            // Role.RadioButton, not Button: "selected" is the state TalkBack has to announce.
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(pixelUnits(1))
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = if (enabled) contentColor.alpha else PixelDisabledAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A labelled switch. Material's switch is a stadium track with a circular thumb — two shapes the
 * art never draws — so this is a well with a bevelled block that slides between two notches.
 *
 * The whole row is the target: a 48dp switch alone is a small thing to hit next to its own label.
 */
@Composable
fun PixelToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    background: Color = MaterialTheme.colorScheme.background,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    /** Only for a switch whose off state is not simply "off" — otherwise Role.Switch says it. */
    stateDescription: String? = null,
) {
    val travel = with(LocalDensity.current) { pixelUnits(6).toPx() }
    // Held as a State and read in the layout lambda: `by` here would recompose the row every
    // frame the knob is moving.
    val slide = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "toggle-$label",
    )
    val readOut = stateDescription

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(
                if (readOut != null) {
                    Modifier.semantics { this.stateDescription = readOut }
                } else {
                    Modifier
                },
            )
            .padding(vertical = pixelUnits(1)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor.copy(alpha = if (enabled) labelColor.alpha else PixelDisabledAlpha),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        // Off is a plain well; on tints the well itself, so the state survives a colour-blind eye.
        val trackFill = if (checked) lerp(fill.copy(alpha = 1f), accent, 0.35f) else pixelWellFill(fill, background)
        Box(
            modifier = Modifier
                .size(width = pixelUnits(12), height = pixelUnits(6))
                .graphicsLayer { alpha = if (enabled) 1f else PixelDisabledAlpha }
                .pixelSurface(
                    fill = trackFill,
                    accent = if (checked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    bevel = PixelBevel.PRESSED,
                    borderUnits = 1,
                    background = background,
                ),
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((travel * slide.value).roundToInt(), 0) }
                    .size(width = pixelUnits(6), height = pixelUnits(6))
                    .pixelSurface(
                        fill = if (checked) accent else fill,
                        accent = if (checked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        borderUnits = 1,
                        background = background,
                    ),
            )
        }
    }
}

/**
 * A slider that can only land on a notch.
 *
 * Material's slider is a hairline track with a round thumb, so it reads as a foreign control the
 * moment it sits inside a bevelled panel. This one borrows [PixelBar] for the track and moves a
 * bevelled block along it, snapping to [notches] equal stops so the value lands on the grid too.
 */
@Composable
fun PixelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    /** What the current value *is* — "70 percent", "Classic". Read out after [label]. */
    valueLabel: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    notches: Int = 10,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    background: Color = MaterialTheme.colorScheme.background,
) {
    val steps = notches.coerceAtLeast(1)
    val min = valueRange.start
    val span = (valueRange.endInclusive - min).takeIf { it > 0f } ?: 1f
    val current = value.coerceIn(min, valueRange.endInclusive)
    val fraction = ((current - min) / span).coerceIn(0f, 1f)

    val knobWidth = pixelUnits(5)
    val knobPx = with(LocalDensity.current) { knobWidth.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    // Held live: the gesture handlers outlive the composition that installed them.
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    // Touch x is measured against the knob's travel, not the whole width, so the block ends up
    // under the finger at both ends instead of running out of room.
    val report: (Float) -> Unit = { x ->
        val travel = (widthPx - knobPx).coerceAtLeast(1f)
        val f = ((x - knobPx / 2f) / travel).coerceIn(0f, 1f)
        val index = (f * steps).roundToInt().coerceIn(0, steps)
        latestOnValueChange(min + span * index / steps)
    }
    val dragX = remember { mutableStateOf(0f) }
    val readOut = valueLabel

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .onSizeChanged { widthPx = it.width }
            // Tap sits outside the drag: the drag node sees the pointer first and only claims it
            // once it has crossed the slop, so a tap on the track still lands on a notch and a
            // drag never also fires a tap when the finger lifts.
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { offset -> report(offset.x) }
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    dragX.value += delta
                    report(dragX.value)
                },
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { start ->
                    dragX.value = start.x
                    report(start.x)
                },
            )
            .graphicsLayer { alpha = if (enabled) 1f else PixelDisabledAlpha }
            .semantics {
                contentDescription = label
                if (readOut != null) stateDescription = readOut
                progressBarRangeInfo = ProgressBarRangeInfo(current, valueRange, (steps - 1).coerceAtLeast(0))
                if (!enabled) disabled()
                setProgress { target ->
                    if (!enabled) {
                        false
                    } else {
                        val f = ((target - min) / span).coerceIn(0f, 1f)
                        latestOnValueChange(min + span * (f * steps).roundToInt() / steps)
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        PixelBar(
            fraction = fraction,
            color = accent,
            segments = steps,
            height = pixelUnits(6),
            trackColor = fill,
            background = background,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((((widthPx - knobPx).coerceAtLeast(0f)) * fraction).roundToInt(), 0) }
                .size(width = knobWidth, height = pixelUnits(9))
                .pixelSurface(
                    fill = lerp(fill.copy(alpha = 1f), accent, 0.35f),
                    accent = accent,
                    borderUnits = 1,
                    background = background,
                ),
        )
    }
}

/**
 * A text field as a recessed well. Material's outlined field brings its own radius and floating
 * label; the well is the same shape the kit uses for anything the player can put something into.
 */
@Composable
fun PixelTextWell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The field's name for TalkBack. Falls back to [placeholder], which is usually the same word. */
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    accent: Color = MaterialTheme.colorScheme.primary,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    background: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val readOut = label ?: placeholder
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .pixelSurface(
                fill = pixelWellFill(fill, background),
                accent = accent,
                bevel = PixelBevel.PRESSED,
                borderUnits = 1,
                background = background,
            )
            .padding(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = textStyle.copy(color = contentColor),
            cursorBrush = SolidColor(accent),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (readOut != null) {
                        Modifier.semantics { contentDescription = readOut }
                    } else {
                        Modifier
                    },
                ),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/*
 * A bitmap font for the HUD.
 *
 * A coin count set in Material's typeface sits a hairline stroke and a smooth curve next to art
 * that has neither, and the eye reads the number as belonging to a different picture. These
 * glyphs are 3×5 blocks on the same grid as everything else here, so the numerals are made of
 * the same pixels as the creature standing beside them.
 *
 * It is deliberately not a text renderer: no kerning, no fallback, no shaping. Anything outside
 * the covered set draws as a blank cell, so a label goes through [Text] and a *number* comes
 * through here.
 */

/** Ink columns in one glyph cell. */
const val PixelGlyphWidthUnits: Int = 3

/** Ink rows in one glyph cell. */
const val PixelGlyphHeightUnits: Int = 5

/** Cell plus the one-unit gap that follows it. */
const val PixelGlyphAdvanceUnits: Int = 4

/** Rows top to bottom, each a 3-bit mask with the leftmost column in the high bit. */
private fun glyph(r0: Int, r1: Int, r2: Int, r3: Int, r4: Int): Int =
    r0 or (r1 shl 3) or (r2 shl 6) or (r3 shl 9) or (r4 shl 12)

private val PixelDigitGlyphs = intArrayOf(
    glyph(0b111, 0b101, 0b101, 0b101, 0b111),
    glyph(0b010, 0b110, 0b010, 0b010, 0b111),
    glyph(0b111, 0b001, 0b111, 0b100, 0b111),
    glyph(0b111, 0b001, 0b111, 0b001, 0b111),
    glyph(0b101, 0b101, 0b111, 0b001, 0b001),
    glyph(0b111, 0b100, 0b111, 0b001, 0b111),
    glyph(0b111, 0b100, 0b111, 0b101, 0b111),
    glyph(0b111, 0b001, 0b010, 0b010, 0b010),
    glyph(0b111, 0b101, 0b111, 0b101, 0b111),
    glyph(0b111, 0b101, 0b111, 0b001, 0b111),
)

/** The bits for [c], or an empty cell for anything the font does not carry. */
private fun glyphBits(c: Char): Int = when (c) {
    in '0'..'9' -> PixelDigitGlyphs[c - '0']
    '%' -> glyph(0b101, 0b001, 0b010, 0b100, 0b101)
    '\u00B7' -> glyph(0b000, 0b000, 0b010, 0b000, 0b000)
    '/' -> glyph(0b001, 0b001, 0b010, 0b100, 0b100)
    '+' -> glyph(0b000, 0b010, 0b111, 0b010, 0b000)
    '-' -> glyph(0b000, 0b000, 0b111, 0b000, 0b000)
    ':' -> glyph(0b000, 0b010, 0b000, 0b010, 0b000)
    '.' -> glyph(0b000, 0b000, 0b000, 0b000, 0b010)
    'x', 'X', '\u00D7' -> glyph(0b000, 0b101, 0b010, 0b101, 0b000)
    else -> 0
}

/** How wide [text] will be at [unit] device pixels per block, gap after the last glyph removed. */
fun pixelDigitsWidth(text: String, unit: Float): Float =
    if (text.isEmpty()) 0f else (PixelGlyphAdvanceUnits * text.length - 1) * unit

/**
 * Draws [text] as blocks with its top-left at [origin], and returns the width it covered so a
 * caller laying numerals out along a row can advance by it.
 *
 * Every run of set bits in a row becomes one rect, which keeps a four-digit readout at a couple
 * of dozen draw calls and allocates nothing — [Offset] and [Size] are value classes.
 */
fun DrawScope.drawPixelDigits(
    text: String,
    color: Color,
    origin: Offset = Offset.Zero,
    unit: Float = gridUnit(),
): Float {
    val u = unit.coerceAtLeast(1f)
    for (i in text.indices) {
        val bits = glyphBits(text[i])
        if (bits == 0) continue
        val glyphLeft = origin.x + i * PixelGlyphAdvanceUnits * u
        for (row in 0 until PixelGlyphHeightUnits) {
            val rowBits = (bits shr (row * PixelGlyphWidthUnits)) and 0b111
            if (rowBits == 0) continue
            var col = 0
            while (col < PixelGlyphWidthUnits) {
                if ((rowBits shr (PixelGlyphWidthUnits - 1 - col)) and 1 == 0) {
                    col++
                    continue
                }
                var run = 1
                while (
                    col + run < PixelGlyphWidthUnits &&
                    (rowBits shr (PixelGlyphWidthUnits - 1 - col - run)) and 1 == 1
                ) {
                    run++
                }
                drawRect(
                    color = color,
                    topLeft = Offset(glyphLeft + col * u, origin.y + row * u),
                    size = Size(run * u, u),
                )
                col += run
            }
        }
    }
    return pixelDigitsWidth(text, u)
}

/**
 * A HUD numeral drawn from [drawPixelDigits]. Covers `0`-`9`, `%`, `·`, `/`, `+`, `-`, `:`, `.`
 * and `x`; anything else comes out blank.
 *
 * [scale] multiplies the grid, so `scale = 2` is a 24×40dp digit. The canvas is decorative by
 * default — a bare "12" is not worth reading aloud — so either pass [contentDescription] or let
 * the row around it own the announcement.
 */
@Composable
fun PixelDigits(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    scale: Int = 1,
    contentDescription: String? = null,
) {
    val cell = PixelUnit * scale.coerceAtLeast(1)
    val widthUnits = if (text.isEmpty()) 0 else PixelGlyphAdvanceUnits * text.length - 1
    val readOut = contentDescription
    Canvas(
        modifier = modifier
            .size(width = cell * widthUnits, height = cell * PixelGlyphHeightUnits)
            .then(
                if (readOut != null) {
                    Modifier.semantics { this.contentDescription = readOut }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        drawPixelDigits(text, color, Offset.Zero, floor(cell.toPx()).coerceAtLeast(1f))
    }
}

/**
 * The phase of the kit's shine sweep, wrapping 0f..1f once every [periodMillis].
 *
 * Exposed on its own because the item art already takes a phase (`ItemIcon(shinePhase = …)`) and
 * had no way to be given one — every glint in the app should be on the same clock.
 *
 * Returned as a [State] rather than a value: read it inside a draw or layout lambda and the frame
 * costs a redraw instead of a recomposition.
 */
@Composable
fun rememberPixelShinePhase(enabled: Boolean = true, periodMillis: Int = 2400): State<Float> {
    val transition = rememberInfiniteTransition(label = "pixel-shine")
    return transition.animateFloat(
        initialValue = 0f,
        // Equal endpoints rather than a branch: the composable has to be called unconditionally.
        targetValue = if (enabled) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis.coerceAtLeast(1), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pixel-shine-phase",
    )
}

/**
 * A band of light that sweeps across whatever it decorates — for a newly unlocked item, or the
 * one tile the screen wants the eye to land on.
 *
 * The band is a staircase of whole blocks rather than a smooth gradient, and it fades in and out
 * at the ends of its travel so it reads as a glint rather than as a wipe. Draws over the content,
 * so put it last in the chain; it is pure decoration and adds no semantics.
 */
@Composable
fun Modifier.pixelShine(
    enabled: Boolean = true,
    color: Color = Color.White,
    alpha: Float = 0.22f,
    periodMillis: Int = 2400,
    bandUnits: Int = 3,
    slantUnits: Int = 2,
): Modifier {
    val phase = rememberPixelShinePhase(enabled = enabled, periodMillis = periodMillis)
    return this.drawWithContent {
        drawContent()
        if (!enabled) return@drawWithContent
        val u = gridUnit()
        val rows = ceil(size.height / u).toInt()
        if (rows <= 0 || size.width <= 0f) return@drawWithContent
        val band = u * bandUnits.coerceAtLeast(1)
        val slant = u * slantUnits.coerceAtLeast(0)
        val lean = slant * (rows - 1)
        // Travel starts fully off the leading edge and ends fully off the trailing one.
        val start = -band - lean + phase.value * (size.width + band + lean)
        val fade = sin(phase.value * PI).toFloat()
        val ink = color.copy(alpha = color.alpha * alpha * fade)
        if (ink.alpha <= 0.002f) return@drawWithContent
        for (row in 0 until rows) {
            val y = row * u
            // Lower rows lag, so the band leans like light coming from above.
            val x = start + (rows - 1 - row) * slant
            val left = x.coerceAtLeast(0f)
            val right = (x + band).coerceAtMost(size.width)
            if (right <= left) continue
            drawRect(ink, Offset(left, y), Size(right - left, (size.height - y).coerceAtMost(u)))
        }
    }
}
