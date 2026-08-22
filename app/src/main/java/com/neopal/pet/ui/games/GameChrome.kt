package com.neopal.pet.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.neopal.pet.ui.theme.NeoColors
import kotlinx.coroutines.delay

/** Shared top bar for the minigames: back arrow, title, two counters and a timer line. */
@Composable
fun GameHeader(
    title: String,
    left: String,
    right: String,
    progress: Float,
    onExit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    // The header sits on colorScheme.background, so the arrow has to be
                    // onBackground. The fixed NeoColors.OnDark it used to carry is a
                    // dark-theme near-white: 16.71:1 on the dark chassis, 1.01:1 on the
                    // light surface — the only way out of a game, invisible.
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Text(left, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(right, style = MaterialTheme.typography.labelSmall, color = NeoColors.NeonCyan)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(NeoColors.NeonCyan),
            )
        }
    }
}

/** Full-screen result card shown when a minigame ends. */
@Composable
fun GameResult(title: String, lines: List<String>, onExit: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0A0C12)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = NeoColors.SurfaceCard),
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = NeoColors.NeonCyan)
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = NeoColors.OnDark)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onExit) { Text("Back to the room") }
            }
        }
    }
}

/**
 * The 3-2-1-GO gate every game opens with. Nothing is more unfair than a game that starts
 * before the player's thumb is on the screen.
 */
@Composable
fun CountdownGate(onReady: () -> Unit) {
    var count by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (count > 0) {
            delay(700)
            count -= 1
        }
        delay(350)
        onReady()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA0A0C12)),
        contentAlignment = Alignment.Center,
    ) {
        val scale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(260),
            label = "countdown-$count",
        )
        Text(
            text = if (count > 0) "$count" else "GO!",
            style = MaterialTheme.typography.displayLarge,
            color = if (count > 0) NeoColors.OnDark else NeoColors.NeonGreen,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

/** Big centred verdict that pops and fades — the whole feedback loop of a timing game. */
@Composable
fun JudgementFlash(text: String?, color: Color, tick: Int) {
    if (text == null) return
    var visible by remember(tick) { mutableStateOf(true) }
    LaunchedEffect(tick) {
        visible = true
        delay(420)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(60)) + scaleIn(initialScale = 0.6f, animationSpec = tween(140)),
        exit = fadeOut(tween(220)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
        )
    }
}

/** Small "BEST 1200" chip for the game tiles and headers. */
@Composable
fun BestChip(best: Int, modifier: Modifier = Modifier) {
    if (best <= 0) return
    Text(
        text = "BEST $best",
        style = MaterialTheme.typography.labelSmall,
        color = NeoColors.NeonYellow,
        modifier = modifier,
    )
}
