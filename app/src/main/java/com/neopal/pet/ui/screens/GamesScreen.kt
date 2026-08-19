package com.neopal.pet.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.CareActions
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.Routes
import com.neopal.pet.ui.components.MenuTile
import com.neopal.pet.ui.games.BestChip
import com.neopal.pet.ui.theme.NeoColors

/** Game-select grid, styled like a console home menu row of cartridges. */
@Composable
fun GamesScreen(viewModel: PetViewModel, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val blocker = CareActions.canPlay(pet)
    val locked = blocker != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("PLAY", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }

        if (blocker != null) {
            // The reason has to outrank the tiles, or players keep tapping a dead grid.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NeoColors.NeonRed.copy(alpha = 0.12f))
                    .border(BorderStroke(1.dp, NeoColors.NeonRed.copy(alpha = 0.6f)), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text("No games right now", style = MaterialTheme.typography.titleSmall, color = NeoColors.NeonRed)
                Text(blocker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Text(
                text = "Winning raises mood and bond, and burns energy. Play often for an Athletic evolution.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (locked) 0.35f else 1f),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GameTile(
                    title = "Rhythm Tap",
                    subtitle = "4 lanes · 30 s\nTiming drill — big mood, small bond, ~10 energy",
                    accent = NeoColors.NeonCyan,
                    best = pet.highScores["rhythm"] ?: 0,
                    enabled = !locked,
                    onClick = { onPlay(Routes.GAME_RHYTHM) },
                    modifier = Modifier.weight(1f),
                ) {
                    Canvas(Modifier.size(52.dp)) {
                        repeat(4) { i ->
                            drawRect(
                                color = listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)[i],
                                topLeft = Offset(i * size.width / 4f + 2f, size.height * (0.15f + i * 0.12f)),
                                size = androidx.compose.ui.geometry.Size(size.width / 4f - 4f, size.height * 0.18f),
                            )
                        }
                    }
                }
                GameTile(
                    title = "Memory Match",
                    subtitle = "6 rounds\nRecall drill — steady mood and bond, ~8 energy",
                    accent = NeoColors.NeonPurple,
                    best = pet.highScores["memory"] ?: 0,
                    enabled = !locked,
                    onClick = { onPlay(Routes.GAME_MEMORY) },
                    modifier = Modifier.weight(1f),
                ) {
                    Canvas(Modifier.size(52.dp)) {
                        val half = size.width / 2f
                        listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)
                            .forEachIndexed { i, color ->
                                drawRect(
                                    color = color.copy(alpha = if (i == 1) 1f else 0.45f),
                                    topLeft = Offset((i % 2) * half + 2f, (i / 2) * half + 2f),
                                    size = androidx.compose.ui.geometry.Size(half - 4f, half - 4f),
                                )
                            }
                    }
                }
            }
            // Odd tile out: full width beats a half tile next to dead space.
            GameTile(
                title = "Snack Catch",
                subtitle = "40 s · 3 lives\nReflex drill — best bond gain, ~12 energy",
                accent = NeoColors.NeonYellow,
                best = pet.highScores["catch"] ?: 0,
                enabled = !locked,
                onClick = { onPlay(Routes.GAME_CATCH) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Canvas(Modifier.size(52.dp)) {
                    drawCircle(Color(0xFFE0555F), size.minDimension * 0.14f, Offset(size.width * 0.3f, size.height * 0.25f))
                    drawCircle(Color(0xFF8FD8E8), size.minDimension * 0.12f, Offset(size.width * 0.7f, size.height * 0.42f))
                    drawArc(
                        color = NeoColors.NeonGreen,
                        startAngle = 180f, sweepAngle = 180f, useCenter = true,
                        topLeft = Offset(size.width * 0.25f, size.height * 0.62f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.5f, size.height * 0.34f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Record: ${pet.gamesWon} wins in ${pet.gamesPlayed} games",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A [MenuTile] with the personal best pinned to the corner of the cartridge art. */
@Composable
private fun GameTile(
    title: String,
    subtitle: String,
    accent: Color,
    best: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
) {
    MenuTile(
        title = title,
        subtitle = subtitle,
        accent = accent,
        onClick = { if (enabled) onClick() },
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            art()
            BestChip(
                best = best,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
    }
}
