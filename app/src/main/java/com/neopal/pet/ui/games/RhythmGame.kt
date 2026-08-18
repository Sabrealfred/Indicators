package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs
import kotlin.random.Random

private data class Note(val lane: Int, var y: Float, var hit: Boolean = false, var missed: Boolean = false)

/**
 * Four-lane rhythm game. Notes fall toward a hit line; tapping the matching lane within the
 * window scores. Accuracy maps straight onto the reward, so precision beats mashing.
 */
@Composable
fun RhythmGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val lanes = 4
    val laneColors = listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)

    val notes = remember { mutableListOf<Note>() }
    var time by remember { mutableFloatStateOf(0f) }
    var nextSpawn by remember { mutableFloatStateOf(0.6f) }
    var score by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var bestCombo by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(-1) }

    val duration = 32f
    val fallSpeed = 0.55f          // screen heights per second
    val hitLine = 0.82f            // normalised y of the hit line
    val window = 0.06f             // how far from the line still counts

    LaunchedEffect(Unit) {
        val random = Random(System.currentTimeMillis())
        var previous = 0L
        while (!finished) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                time += dt

                // Spawn on a tightening beat so the song ramps up.
                if (time >= nextSpawn && time < duration - 2f) {
                    val interval = (0.75f - (time / duration) * 0.35f).coerceAtLeast(0.32f)
                    nextSpawn = time + interval
                    notes += Note(lane = random.nextInt(lanes), y = -0.05f)
                    total += 1
                }

                notes.forEach { note ->
                    if (!note.hit) note.y += fallSpeed * dt
                    if (!note.hit && !note.missed && note.y > hitLine + window) {
                        note.missed = true
                        combo = 0
                    }
                }
                notes.removeAll { it.y > 1.1f || (it.hit && it.y > hitLine + 0.02f) }

                if (time >= duration) finished = true
            }
        }
    }

    // Settle up once the song ends.
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val accuracy = if (total == 0) 0f else hits.toFloat() / total
        viewModel.finishGame(won = accuracy >= 0.6f, score = accuracy, gameName = "Rhythm Tap")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Rhythm Tap",
            left = "Score $score",
            right = "Combo $combo",
            progress = (time / duration).coerceIn(0f, 1f),
            onExit = onExit,
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF12141B)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val laneWidth = size.width / lanes
                // Lane guides.
                repeat(lanes) { i ->
                    drawRect(
                        color = laneColors[i].copy(alpha = if (flash == i) 0.22f else 0.07f),
                        topLeft = Offset(i * laneWidth, 0f),
                        size = Size(laneWidth - 3f, size.height),
                    )
                }
                // Hit line.
                drawRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(0f, hitLine * size.height),
                    size = Size(size.width, 4f),
                )
                // Notes as rounded bars.
                notes.forEach { note ->
                    if (note.hit) return@forEach
                    val x = note.lane * laneWidth
                    drawRoundRect(
                        color = if (note.missed) Color(0xFF555A66) else laneColors[note.lane],
                        topLeft = Offset(x + laneWidth * 0.12f, note.y * size.height),
                        size = Size(laneWidth * 0.76f, size.height * 0.035f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(lanes) { lane ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(laneColors[lane].copy(alpha = 0.32f))
                        .clickable(enabled = !finished) {
                            flash = lane
                            val target = notes
                                .filter { it.lane == lane && !it.hit && !it.missed }
                                .minByOrNull { abs(it.y - hitLine) }
                            if (target != null && abs(target.y - hitLine) <= window) {
                                target.hit = true
                                hits += 1
                                combo += 1
                                bestCombo = maxOf(bestCombo, combo)
                                score += 100 + combo * 10
                                if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_HIT)
                            } else {
                                combo = 0
                                if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = listOf("A", "B", "X", "Y")[lane],
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
            }
        }

        if (finished) {
            GameResult(
                title = if (total > 0 && hits.toFloat() / total >= 0.6f) "CLEARED!" else "SONG OVER",
                lines = listOf(
                    "Score $score",
                    "Accuracy ${if (total == 0) 0 else (hits * 100 / total)}%",
                    "Best combo $bestCombo",
                ),
                onExit = onExit,
            )
        }
    }
}
