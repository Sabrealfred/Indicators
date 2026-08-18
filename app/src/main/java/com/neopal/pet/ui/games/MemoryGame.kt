package com.neopal.pet.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Call-and-response memory game. The pads light up in a growing sequence; repeat it back.
 * Clearing round six wins. One wrong pad ends the run and the score is the round reached.
 */
@Composable
fun MemoryGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val colors = listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)
    val targetRounds = 6

    val sequence = remember { mutableStateListOf<Int>() }
    var round by remember { mutableIntStateOf(0) }
    var inputIndex by remember { mutableIntStateOf(0) }
    var playingBack by remember { mutableStateOf(true) }
    var litPad by remember { mutableIntStateOf(-1) }
    var finished by remember { mutableStateOf(false) }
    var won by remember { mutableStateOf(false) }

    // Grow the sequence, then play it back for the player to copy.
    LaunchedEffect(round) {
        if (finished) return@LaunchedEffect
        playingBack = true
        delay(600)
        sequence += Random(System.nanoTime()).nextInt(colors.size)
        sequence.forEach { pad ->
            litPad = pad
            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.SELECT)
            delay(420)
            litPad = -1
            delay(160)
        }
        inputIndex = 0
        playingBack = false
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val score = (sequence.size.toFloat() / targetRounds).coerceIn(0f, 1f)
        viewModel.finishGame(won = won, score = score, gameName = "Memory Match")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Memory Match",
            left = "Round ${sequence.size}",
            right = if (playingBack) "Watch" else "Your turn",
            progress = sequence.size.toFloat() / targetRounds,
            onExit = onExit,
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            repeat(2) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(2) { columnIndex ->
                        val pad = rowIndex * 2 + columnIndex
                        val lit = litPad == pad
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors[pad].copy(alpha = if (lit) 1f else 0.30f))
                                .clickable(enabled = !playingBack && !finished) {
                                    litPad = pad
                                    if (sequence[inputIndex] == pad) {
                                        if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_HIT)
                                        inputIndex += 1
                                        if (inputIndex >= sequence.size) {
                                            if (sequence.size >= targetRounds) {
                                                won = true
                                                finished = true
                                            } else {
                                                round += 1
                                            }
                                        }
                                    } else {
                                        if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                                        won = false
                                        finished = true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = listOf("A", "B", "X", "Y")[pad],
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White.copy(alpha = if (lit) 1f else 0.55f),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = if (playingBack) "Watch the pattern..." else "Repeat it back",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (finished) {
            GameResult(
                title = if (won) "PERFECT MEMORY!" else "MISSED IT",
                lines = listOf("Sequence reached ${sequence.size}", "Target $targetRounds"),
                onExit = onExit,
            )
        }
    }

    // Reset the lit pad shortly after a tap so it reads as a flash.
    LaunchedEffect(litPad, playingBack) {
        if (!playingBack && litPad >= 0) {
            delay(180)
            litPad = -1
        }
    }
}
