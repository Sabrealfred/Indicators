package com.neopal.pet.ui.games

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Call-and-response memory game. The pads light up in a growing sequence; repeat it back.
 * Each pad owns a note, so a run is a melody you can hum back as well as a pattern you watch.
 * Clearing round six wins; one wrong pad ends the run.
 */
@Composable
fun MemoryGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val colors = listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)
    // One note per pad turns the sequence into something the ear can hold on to.
    val padPitches = listOf(0.75f, 0.9f, 1.1f, 1.3f)
    val labels = listOf("A", "B", "X", "Y")
    val targetRounds = 6

    val sequence = remember { mutableStateListOf<Int>() }
    val random = remember { Random(System.nanoTime()) }
    var round by remember { mutableIntStateOf(0) }
    var inputIndex by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var playingBack by remember { mutableStateOf(true) }
    var litPad by remember { mutableIntStateOf(-1) }
    var wrongPad by remember { mutableIntStateOf(-1) }
    var turnStart by remember { mutableLongStateOf(0L) }
    var cleared by remember { mutableIntStateOf(0) }
    var speedBonus by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var won by remember { mutableStateOf(false) }
    var flashText by remember { mutableStateOf<String?>(null) }
    var flashColor by remember { mutableStateOf(NeoColors.NeonCyan) }
    var flashTick by remember { mutableIntStateOf(0) }

    val points = cleared * 100 + speedBonus

    // Freeze the incoming best once the run ends, or finishGame's own update would quietly
    // erase the NEW RECORD line the moment it is earned.
    var best by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.pet?.highScores?.get(GAME_ID), finished) {
        if (!finished) best = ui.pet?.highScores?.get(GAME_ID) ?: 0
    }

    // Grow the sequence, then play it back for the player to copy.
    LaunchedEffect(round, started) {
        if (!started || finished) return@LaunchedEffect
        playingBack = true
        delay(600)
        sequence += random.nextInt(colors.size)
        // Playback tightens every round, so the last sequences test reflex as well as recall.
        val step = ((sequence.size - 1) * 44L).coerceAtMost(220L)
        val padMs = 420L - step
        sequence.forEach { pad ->
            litPad = pad
            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.SELECT, pitch = padPitches[pad] * keyLift(sequence.size))
            delay(padMs)
            litPad = -1
            delay(padMs / 3)
        }
        inputIndex = 0
        turnStart = System.currentTimeMillis()
        playingBack = false
        flashText = "YOUR TURN"
        flashColor = NeoColors.NeonCyan
        flashTick += 1
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val score = (cleared.toFloat() / targetRounds).coerceIn(0f, 1f)
        viewModel.finishGame(
            won = won,
            score = score,
            gameName = "Memory Match",
            gameId = GAME_ID,
            points = points,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Memory Match",
            left = "Score $points",
            right = if (best > 0) "Best $best" else "Round ${sequence.size}/$targetRounds",
            progress = sequence.size.toFloat() / targetRounds,
            onExit = onExit,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                repeat(2) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(2) { columnIndex ->
                            val pad = rowIndex * 2 + columnIndex
                            val lit = litPad == pad || wrongPad == pad
                            val padColor = if (wrongPad == pad) NeoColors.NeonRed else colors[pad]
                            val scale by animateFloatAsState(
                                targetValue = if (lit && !ui.config.reducedMotion) 1.08f else 1f,
                                animationSpec = tween(120),
                                label = "pad-$pad",
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(vertical = 6.dp)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(padColor.copy(alpha = if (lit) 1f else 0.30f))
                                    .clickable(enabled = started && !playingBack && !finished) {
                                        litPad = pad
                                        if (sequence[inputIndex] == pad) {
                                            if (ui.config.soundEnabled) {
                                                ChiptuneEngine.play(
                                                    Sfx.GAME_HIT,
                                                    pitch = padPitches[pad] * keyLift(sequence.size),
                                                )
                                            }
                                            inputIndex += 1
                                            if (inputIndex >= sequence.size) {
                                                // Answering before the allowance runs out is worth points.
                                                val elapsed = System.currentTimeMillis() - turnStart
                                                val allowance = sequence.size * 1200L
                                                speedBonus += ((allowance - elapsed) / 10L)
                                                    .coerceIn(0L, 300L)
                                                    .toInt()
                                                cleared = sequence.size
                                                if (cleared >= targetRounds) {
                                                    won = true
                                                    finished = true
                                                } else {
                                                    flashText = "NICE"
                                                    flashColor = NeoColors.NeonGreen
                                                    flashTick += 1
                                                    round += 1
                                                }
                                            }
                                        } else {
                                            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                                            wrongPad = pad
                                            flashText = "WRONG"
                                            flashColor = NeoColors.NeonRed
                                            flashTick += 1
                                            won = false
                                            finished = true
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = labels[pad],
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White.copy(alpha = if (lit) 1f else 0.55f),
                                )
                            }
                        }
                    }
                }
            }

            JudgementFlash(text = flashText, color = flashColor, tick = flashTick)

            if (!started && !finished) {
                CountdownGate(onReady = { started = true })
            }
        }

        Spacer(Modifier.height(12.dp))
        // Dots keep the position in the sequence readable without counting taps.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(sequence.size) { index ->
                val done = !playingBack && index < inputIndex
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (done) NeoColors.NeonCyan else Color.White.copy(alpha = 0.18f)),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                !started -> "Get ready..."
                playingBack -> "Watch the pattern..."
                else -> "Repeat it back"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (finished) {
            GameResult(
                title = if (won) "PERFECT MEMORY!" else "MISSED IT",
                lines = listOf(
                    "Score $points" + if (points > best) "  ★ NEW RECORD" else "",
                    "Sequence reached $cleared of $targetRounds",
                    "Speed bonus $speedBonus",
                ),
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

/** Later rounds sit a semitone or so higher, so the pressure is audible. */
private fun keyLift(length: Int): Float = 1f + (length - 1) * 0.02f

private const val GAME_ID = "memory"
