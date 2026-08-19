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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

/** One labelled stat meter with a smooth animated fill and a value read out for screen readers. */
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
    val trackHeight = if (compact) 6.dp else 9.dp
    val tick = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        if (!compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
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
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$rounded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.75f), if (warn) NeoColors.NeonRed else color),
                        ),
                    ),
            )
            // A tick where the game starts calling the need critical, so "how bad is it" is one glance.
            Box(
                modifier = Modifier
                    .fillMaxWidth(CriticalStatFraction)
                    .height(trackHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(tick),
                )
            }
        }
    }
}

/** A round action button styled after a console face button. */
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
    val alpha = if (enabled) 1f else DisabledGraphicAlpha
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = readOut }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            )
            // Inside the clickable: the padding is touchable area, not a dead gap around it.
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (urgent) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.22f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.90f * alpha), accent.copy(alpha = 0.55f * alpha)),
                        ),
                    )
                    .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.16f * alpha)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = alpha),
                    modifier = Modifier.size(24.dp),
                )
            }
            if (badge != null && badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(NeoColors.NeonRed),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badge > 9) stringResource(R.string.badge_overflow) else "$badge",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
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

/** Home-menu style tile with a big square icon slot, like a console's game grid. */
@Composable
fun MenuTile(
    title: String,
    subtitle: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(2.dp, accent.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.08f))),
                    ),
                contentAlignment = Alignment.Center,
            ) { content() }
            Spacer(Modifier.height(8.dp))
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NeoColors.SurfaceCard.copy(alpha = 0.96f))
                .border(BorderStroke(1.dp, NeoColors.NeonCyan.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                // The banner is the only feedback for most actions, so it has to announce itself.
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = NeoColors.OnDark,
            )
        }
    }
}

/** Coin pill used in the top bar and in the shop. */
@Composable
fun CoinPill(coins: Int, modifier: Modifier = Modifier) {
    val readOut = stringResource(R.string.cd_coins, coins)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(NeoColors.SurfaceCard)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Canvas(Modifier.size(14.dp)) {
            drawCircle(NeoColors.NeonYellow, size.minDimension / 2f)
            drawCircle(Color(0xFF8A6A00), size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        Spacer(Modifier.width(6.dp))
        Text("$coins", style = MaterialTheme.typography.labelMedium, color = NeoColors.OnDark, maxLines = 1)
    }
}

/** Level + XP readout with a thin progress line. */
@Composable
fun LevelPill(level: Int, xp: Int, xpNeeded: Int, modifier: Modifier = Modifier) {
    val readOut = stringResource(R.string.cd_level, level, xp, xpNeeded)
    Column(
        modifier = modifier
            .width(96.dp)
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Text(
            stringResource(R.string.level_short, level),
            style = MaterialTheme.typography.labelMedium,
            color = NeoAccents.cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(NeoColors.SurfaceCard),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((xp.toFloat() / xpNeeded.coerceAtLeast(1)).coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(NeoColors.NeonCyan),
            )
        }
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
