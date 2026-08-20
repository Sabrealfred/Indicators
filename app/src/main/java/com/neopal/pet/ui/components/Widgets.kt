@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neopal.pet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

/** Below this fraction the simulation treats a need as critical (see PetState.mood). */
const val CriticalStatFraction = 0.25f

/** The disc dims hard so a disabled button still reads as off at a glance… */
private const val DisabledGraphicAlpha = 0.38f

/** …but its label keeps enough ink to clear 4.5:1 against the console screen. */
private const val DisabledLabelAlpha = 0.80f

/** The action dock's face buttons, in whole grid units (13 × 4dp). */
private val FaceButtonSize: Dp = pixelUnits(13)

/**
 * The neon accents are drawn for the near-black console shell. On a light surface the same
 * cyan/gold/green land near 2:1, so each one has a dimmed twin that clears AA for body text.
 */
object NeoAccents {
    private val CyanOnLight = Color(0xFF0A6C82)
    private val GoldOnLight = Color(0xFF8A6A00)
    private val GreenOnLight = Color(0xFF1F6B3B)

    private val onLightSurface: Boolean
        @Composable get() = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    val cyan: Color @Composable get() = if (onLightSurface) CyanOnLight else NeoColors.NeonCyan
    val gold: Color @Composable get() = if (onLightSurface) GoldOnLight else NeoColors.NeonYellow
    val green: Color @Composable get() = if (onLightSurface) GreenOnLight else NeoColors.NeonGreen
}

/** One labelled stat meter with a segmented fill and a value read out for screen readers. */
@Composable
fun StatBar(
    label: String,
    value: Float,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val animated by animateFloatAsState(
        targetValue = (value / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "stat-$label",
    )
    val rounded = value.roundToInt()
    val warn = value < CriticalStatFraction * 100f
    // One sentence for the whole meter; four loose fragments is what TalkBack reads otherwise.
    val readOut = if (warn) {
        stringResource(R.string.cd_stat_value_low, label, rounded)
    } else {
        stringResource(R.string.cd_stat_value, label, rounded)
    }
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        if (!compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(pixelUnits(4)))
                    Spacer(Modifier.width(pixelUnits(1)))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    // NeonRed on the card only reaches 4.2:1; the scheme error colour clears AA in both themes.
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                // The value is a readout, so it is built from the same blocks as the meter under
                // it; the name beside it is a word and stays in the real typeface. The glyphs are
                // five units tall, which is what sets the height of this row now.
                PixelDigits(
                    text = "$rounded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(pixelUnits(1)))
        }
        // Ten cells in the five-across strip, twenty on a full-width bar: a cell narrower than a
        // couple of grid units stops reading as a cell and turns back into a gradient.
        PixelBar(
            fraction = animated,
            color = if (warn) NeoColors.NeonRed else color,
            segments = if (compact) 10 else 20,
            height = if (compact) pixelUnits(4) else pixelUnits(5),
            markerFraction = CriticalStatFraction,
        )
    }
}

/** A dock action button: a bevelled face that presses in under the thumb. */
@Composable
fun ActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: Int? = null,
) {
    val faceAlpha = if (enabled) 1f else DisabledGraphicAlpha
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val down = pressed && enabled
    // Buttons that shrink under the thumb feel physical; the spring gives them a bounce back.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "press-$label",
    )
    // An urgent badge breathes, so the eye lands on it without a colour change.
    val urgent = (badge ?: 0) > 0
    val pulse by rememberInfiniteTransition(label = "pulse-$label").animateFloat(
        initialValue = 0.85f,
        targetValue = if (urgent) 1.12f else 0.85f,
        animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
        label = "pulse-value-$label",
    )
    // The icon, the badge and the caption are one control, so they get one announcement.
    val readOut = if (urgent) stringResource(R.string.cd_action_with_badge, label, badge ?: 0) else label
    // The dock always sits on the console screen, so the bevel is derived against that, not
    // against the theme background — otherwise the light theme washes the edges out.
    val shell = NeoColors.SurfaceDark
    val face = lerp(accent, shell, 0.20f)
    val shift = if (down) pixelUnits(1) else 0.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = readOut }
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            )
            // Inside the clickable: the padding is touchable area, not a dead gap around it.
            .padding(vertical = pixelUnits(2), horizontal = pixelUnits(1)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (urgent) {
                Box(
                    modifier = Modifier
                        .size(FaceButtonSize + pixelUnits(1))
                        .graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .pixelSurface(
                            fill = accent.copy(alpha = 0.22f),
                            accent = accent,
                            bevel = PixelBevel.FLAT,
                            borderUnits = 0,
                            background = shell,
                        ),
                )
            }
            Box(
                modifier = Modifier
                    .size(FaceButtonSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = faceAlpha
                    }
                    // Pressing flips the bevel; the icon moves with it so the face reads as sunk in.
                    .pixelSurface(
                        fill = face,
                        accent = accent,
                        bevel = if (down) PixelBevel.PRESSED else PixelBevel.RAISED,
                        background = shell,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .offset(x = shift, y = shift)
                        .size(24.dp),
                )
            }
            if (badge != null && badge > 0) {
                PixelBadge(
                    text = if (badge > 9) stringResource(R.string.badge_overflow) else "$badge",
                    color = NeoColors.NeonRed,
                    background = shell,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(1)))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = if (enabled) 1f else DisabledLabelAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Home-menu style tile with a recessed square icon slot, like a console's game grid. */
@Composable
fun MenuTile(
    title: String,
    subtitle: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    PixelPanel(
        modifier = modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
        fill = fill,
        accent = accent,
        contentPadding = PaddingValues(pixelUnits(2)),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pixelUnits(18))
                // The art slot is pressed *into* the tile, so the cartridge reads as inset glass.
                .pixelSurface(
                    fill = lerp(fill, accent, 0.24f),
                    accent = accent,
                    bevel = PixelBevel.PRESSED,
                )
                .padding(pixelUnits(2)),
            contentAlignment = Alignment.Center,
        ) { content() }
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Transient message strip that slides in under the top bar. */
@Composable
fun ToastBanner(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        PixelPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pixelUnits(3))
                // The banner is the only feedback for most actions, so it has to announce itself.
                .semantics { liveRegion = LiveRegionMode.Polite },
            fill = NeoColors.SurfaceCard,
            accent = NeoColors.NeonCyan,
            background = NeoColors.SurfaceDark,
            contentPadding = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeoColors.OnDark,
                )
            }
        }
    }
}

/** Coin pill used in the top bar and in the shop. */
@Composable
fun CoinPill(coins: Int, modifier: Modifier = Modifier) {
    val readOut = stringResource(R.string.cd_coins, coins)
    PixelPanel(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = readOut },
        fill = NeoColors.SurfaceCard,
        accent = NeoColors.NeonYellow,
        background = NeoColors.SurfaceDark,
        borderUnits = 1,
        // The count is now five units tall rather than a line of 12sp type, so the pill takes its
        // breathing room from the grid on both axes instead of padding the sides harder.
        contentPadding = PaddingValues(pixelUnits(1)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A five-by-five pixel coin rather than a vector circle: at five units every pixel of
            // it is exactly one grid unit, and it stands the same height as the numerals it labels.
            Canvas(Modifier.size(pixelUnits(5))) {
                val u = size.minDimension / 5f
                drawRect(NeoColors.NeonYellow, Offset(u, 0f), Size(u * 3f, u * 5f))
                drawRect(NeoColors.NeonYellow, Offset(0f, u), Size(u * 5f, u * 3f))
                drawRect(Color(0xFF8A6A00), Offset(u * 2f, u), Size(u, u * 3f))
            }
            Spacer(Modifier.width(pixelUnits(2)))
            PixelDigits(text = "$coins", color = NeoColors.OnDark)
        }
    }
}

/** Level + XP readout with a segmented progress line. */
@Composable
fun LevelPill(level: Int, xp: Int, xpNeeded: Int, modifier: Modifier = Modifier) {
    val readOut = stringResource(R.string.cd_level, level, xp, xpNeeded)
    PixelPanel(
        modifier = modifier
            .width(pixelUnits(24))
            .semantics(mergeDescendants = true) { contentDescription = readOut },
        fill = NeoColors.SurfaceCard,
        accent = NeoColors.NeonCyan,
        background = NeoColors.SurfaceDark,
        borderUnits = 1,
        contentPadding = PaddingValues(horizontal = pixelUnits(2), vertical = pixelUnits(1)),
    ) {
        Text(
            stringResource(R.string.level_short, level),
            style = MaterialTheme.typography.labelMedium,
            // The pill now guarantees its own dark fill, so the bright cyan is the readable
            // choice here — NeoAccents' dimmed twin is for cyan sitting on a light surface.
            color = NeoColors.NeonCyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(pixelUnits(1)))
        PixelBar(
            fraction = xp.toFloat() / xpNeeded.coerceAtLeast(1),
            color = NeoColors.NeonCyan,
            segments = 10,
            height = pixelUnits(3),
            trackColor = NeoColors.SurfaceDark,
            background = NeoColors.SurfaceDark,
        )
    }
}

/** Decorative scanline overlay that sells the "screen inside a console" look. */
@Composable
fun ScanlineOverlay(modifier: Modifier = Modifier, alpha: Float = 0.05f, spacing: Dp = 2.dp) {
    // Pure decoration: kept out of the accessibility tree entirely.
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        // Spacing has to be in dp, not raw pixels. At three raw pixels it was one dark line per
        // density-independent pixel on a modern phone — not a CRT, just a dirty tint that beat
        // against the pixel-art grid and produced moiré.
        val step = spacing.toPx().coerceAtLeast(2f)
        val thickness = (step / 4f).coerceIn(1f, 2f)
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(0f, y),
                size = Size(size.width, thickness),
            )
            y += step
        }
    }
}
