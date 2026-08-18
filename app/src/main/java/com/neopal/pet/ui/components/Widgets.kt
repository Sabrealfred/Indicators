@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neopal.pet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

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
    val warn = value < 25f
    Column(
        modifier = modifier.semantics {
            contentDescription = "$label ${value.roundToInt()} of 100"
        },
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
                    color = if (warn) NeoColors.NeonRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${value.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 6.dp else 9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(if (compact) 6.dp else 9.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.75f), if (warn) NeoColors.NeonRed else color),
                        ),
                    ),
            )
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
    val alpha = if (enabled) 1f else 0.38f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(52.dp)
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
                    contentDescription = label,
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
                        text = if (badge > 9) "9+" else "$badge",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
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
        modifier = modifier,
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(NeoColors.SurfaceCard)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            drawCircle(NeoColors.NeonYellow, size.minDimension / 2f)
            drawCircle(Color(0xFF8A6A00), size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        Spacer(Modifier.width(6.dp))
        Text("$coins", style = MaterialTheme.typography.labelMedium, color = NeoColors.OnDark)
    }
}

/** Level + XP readout with a thin progress line. */
@Composable
fun LevelPill(level: Int, xp: Int, xpNeeded: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(96.dp)) {
        Text(
            "LV $level",
            style = MaterialTheme.typography.labelMedium,
            color = NeoColors.NeonCyan,
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
fun ScanlineOverlay(modifier: Modifier = Modifier, alpha: Float = 0.05f) {
    Canvas(modifier = modifier) {
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(0f, y),
                size = Size(size.width, 1f),
            )
            y += 3f
        }
    }
}
