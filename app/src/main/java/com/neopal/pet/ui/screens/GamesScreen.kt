package com.neopal.pet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.CareActions
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.Routes
import com.neopal.pet.ui.components.MenuTile
import com.neopal.pet.ui.theme.NeoColors

/** Game-select grid, styled like a console home menu row of cartridges. */
@Composable
fun GamesScreen(viewModel: PetViewModel, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val blocker = CareActions.canPlay(pet)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("PLAY", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            text = blocker ?: "Winning raises mood and bond, and burns energy. Play often for an Athletic evolution.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (blocker != null) NeoColors.NeonRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MenuTile(
                title = "Rhythm Tap",
                subtitle = "4 lanes · 30 s",
                accent = NeoColors.NeonCyan,
                onClick = { if (blocker == null) onPlay(Routes.GAME_RHYTHM) },
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
            MenuTile(
                title = "Memory Match",
                subtitle = "6 rounds",
                accent = NeoColors.NeonPurple,
                onClick = { if (blocker == null) onPlay(Routes.GAME_MEMORY) },
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MenuTile(
                title = "Snack Catch",
                subtitle = "40 s · 3 lives",
                accent = NeoColors.NeonYellow,
                onClick = { if (blocker == null) onPlay(Routes.GAME_CATCH) },
                modifier = Modifier.weight(1f),
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
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Record: ${pet.gamesWon} wins in ${pet.gamesPlayed} games",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
