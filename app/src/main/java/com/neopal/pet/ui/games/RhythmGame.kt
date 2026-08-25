package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.MiniGame
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

private data class Note(val lane: Int, var y: Float, var hit: Boolean = false, var missed: Boolean = false)

/** How close to the line a tap landed. Tight windows are what make a rhythm game feel fair. */
private enum class Judgement(val label: String, val points: Int, val color: Color) {
    PERFECT("PERFECT", 150, NeoColors.NeonYellow),
    GOOD("GOOD", 80, NeoColors.NeonCyan),
    MISS("MISS", 0, NeoColors.NeonRed),
}

/**
 * Four-lane rhythm game. Notes fall toward a hit line; how close the tap lands decides the
 * verdict, the combo raises both the score multiplier and the pitch of the hit sound, and
 * accuracy at the end maps onto the reward.
 */
@Composable
fun RhythmGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    // Frozen at entry: finishGame writes the new record before the result card renders, so
    // reading it live would make "NEW RECORD" impossible to ever show.
    val best = remember { ui.pet?.highScores?.get(GAME_ID) ?: 0 }
    val title = stringResource(R.string.game_rhythm)
    val lanes = 4
    val laneColors = listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)

    val notes = remember { mutableListOf<Note>() }
    var time by remember { mutableFloatStateOf(0f) }
    var nextSpawn by remember { mutableFloatStateOf(0.6f) }
    var score by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var bestCombo by remember { mutableIntStateOf(0) }
    var perfects by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var judgement by remember { mutableStateOf<Judgement?>(null) }
    var judgeTick by remember { mutableIntStateOf(0) }
    var flashLane by remember { mutableIntStateOf(-1) }
    var flashLaneAt by remember { mutableFloatStateOf(-1f) }

    val duration = 32f
    val fallSpeed = 0.55f          // screen heights per second
    val hitLine = 0.82f            // normalised y of the hit line
    val window = 0.06f             // how far from the line still counts
    val perfectWindow = window * 0.4f

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
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
                // A hit note stops moving, so a removal test based on its position could never
                // fire for anything hit early — which is every PERFECT. Drop them on the spot.
                notes.removeAll { it.y > 1.1f || it.hit }
                // The lane highlight is a flash, not a latch.
                if (flashLane >= 0 && time - flashLaneAt > 0.12f) flashLane = -1

                if (time >= duration) finished = true
            }
        }
    }

    // Settle up once the song ends.
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val accuracy = if (total == 0) 0f else hits.toFloat() / total
        viewModel.finishGame(
            won = accuracy >= 0.6f,
            score = accuracy,
            gameName = title,
            gameId = GAME_ID,
            points = score,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Edge to edge: keep the content out of the status and gesture bars. The
            // background is applied first on purpose, so it still bleeds under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        GameHeader(
            title = title,
            left = stringResource(R.string.game_score, score),
            right = if (combo > 1) {
                stringResource(R.string.rhythm_combo, combo)
            } else {
                stringResource(R.string.game_best, best)
            },
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
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val laneWidth = size.width / lanes
                repeat(lanes) { i ->
                    drawRect(
                        color = laneColors[i].copy(alpha = if (flashLane == i) 0.22f else 0.07f),
                        topLeft = Offset(i * laneWidth, 0f),
                        size = Size(laneWidth - 3f, size.height),
                    )
                }
                // Hit line, with a glow that pulses with the combo.
                drawRect(
                    color = Color.White.copy(alpha = 0.35f + min(combo, 20) * 0.02f),
                    topLeft = Offset(0f, hitLine * size.height),
                    size = Size(size.width, 4f),
                )
                notes.forEach { note ->
                    if (note.hit) return@forEach
                    val x = note.lane * laneWidth
                    // Notes brighten as they approach the line, so timing is readable at a glance.
                    val closeness = (1f - (abs(note.y - hitLine) / 0.5f)).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = if (note.missed) Color(0xFF555A66)
                        else laneColors[note.lane].copy(alpha = 0.55f + closeness * 0.45f),
                        topLeft = Offset(x + laneWidth * 0.12f, note.y * size.height),
                        size = Size(laneWidth * 0.76f, size.height * 0.035f),
                        cornerRadius = CornerRadius(12f, 12f),
                    )
                }
            }

            JudgementFlash(
                text = judgement?.label,
                color = judgement?.color ?: NeoColors.OnDark,
                tick = judgeTick,
            )

            if (!started && !finished) {
                CountdownGate(onReady = { started = true })
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
                        .background(laneColors[lane].copy(alpha = if (flashLane == lane) 0.55f else 0.32f))
                        .clickable(enabled = started && !finished) {
                            flashLane = lane
                            flashLaneAt = time
                            val target = notes
                                .filter { it.lane == lane && !it.hit && !it.missed }
                                .minByOrNull { abs(it.y - hitLine) }
                            val distance = target?.let { abs(it.y - hitLine) } ?: Float.MAX_VALUE
                            val verdict = when {
                                distance <= perfectWindow -> Judgement.PERFECT
                                distance <= window -> Judgement.GOOD
                                else -> Judgement.MISS
                            }
                            judgement = verdict
                            judgeTick += 1
                            if (verdict == Judgement.MISS) {
                                combo = 0
                                if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                            } else {
                                target?.hit = true
                                hits += 1
                                combo += 1
                                if (verdict == Judgement.PERFECT) perfects += 1
                                bestCombo = maxOf(bestCombo, combo)
                                score += verdict.points + combo * 10
                                if (ui.config.soundEnabled) {
                                    // Rising pitch turns a streak into something you can hear.
                                    ChiptuneEngine.play(Sfx.GAME_HIT, pitch = 1f + min(combo, 12) * 0.04f)
                                }
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
            val accuracy = if (total == 0) 0 else hits * 100 / total
            GameResult(
                title = stringResource(if (accuracy >= 60) R.string.rhythm_result_win else R.string.rhythm_result_lose),
                lines = listOf(
                    stringResource(if (score > best) R.string.game_score_record else R.string.game_score, score),
                    stringResource(R.string.rhythm_result_accuracy, accuracy, perfects),
                    stringResource(R.string.rhythm_result_combo, bestCombo),
                ),
                onExit = onExit,
            )
        }
    }
}

private val GAME_ID = MiniGame.RHYTHM.id
