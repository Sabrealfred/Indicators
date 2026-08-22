package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.MiniGame
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawItem
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private enum class ItemKind { GOOD, BAD, GOLDEN }

private data class FallingItem(
    val iconKey: String,
    val tint: Color,
    val kind: ItemKind,
    val x: Float,
    var y: Float,
    val speed: Float,
    /** Degrees per second; signed so items tumble both ways. */
    val spin: Float,
    /** Desynchronises the squash wobble between items. */
    val phase: Float,
    var rot: Float = 0f,
)

/** Expanding ring plus a few sparks, drawn straight into the Canvas at the catch point. */
private data class Burst(val x: Float, val y: Float, val color: Color, val seed: Float, var age: Float = 0f)

private val GAME_ID = MiniGame.CATCH.id
private const val BURST_LIFE = 0.45f
private const val PET_Y = 0.80f
private const val GOLDEN_AFTER = 15f

/**
 * Catch the falling snacks and dodge the junk. The pet itself is the paddle — drag anywhere
 * on the playfield to move it — which makes the reward feel like it belongs to the creature.
 */
@Composable
fun CatchGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    // Frozen at entry: finishGame writes the new record before the result card renders.
    val best = remember { pet.highScores[GAME_ID] ?: 0 }
    val motion = if (ui.config.reducedMotion) 0.3f else 1f

    val items = remember { mutableListOf<FallingItem>() }
    val bursts = remember { mutableListOf<Burst>() }
    var petX by remember { mutableFloatStateOf(0.5f) }
    var petLean by remember { mutableFloatStateOf(0f) }
    var lastPetX by remember { mutableFloatStateOf(0.5f) }
    var catchPulse by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    var nextSpawn by remember { mutableFloatStateOf(0.5f) }
    var caught by remember { mutableIntStateOf(0) }
    var dropped by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var bestMultiplier by remember { mutableIntStateOf(1) }
    var lives by remember { mutableIntStateOf(3) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashColor by remember { mutableStateOf(NeoColors.NeonCyan) }
    var flashTick by remember { mutableIntStateOf(0) }

    val duration = 40f
    val target = 15
    val multiplier = min(1 + streak / 3, 5)

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val random = Random(System.currentTimeMillis())
        var previous = 0L
        while (!finished) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                time += dt
                val ramp = time / duration

                if (time >= nextSpawn && time < duration - 1.5f) {
                    nextSpawn = time + (0.85f - ramp * 0.45f).coerceAtLeast(0.30f)
                    // Everything falls faster the longer you survive.
                    val boost = 1f + ramp * 0.85f
                    val x = random.nextFloat() * 0.86f + 0.07f
                    val spin = (random.nextFloat() * 90f + 40f) * (if (random.nextBoolean()) 1f else -1f)
                    val phase = random.nextFloat() * 6.28f
                    val roll = random.nextFloat()
                    items += when {
                        time >= GOLDEN_AFTER && roll > 0.90f ->
                            FallingItem("cake", NeoColors.NeonYellow, ItemKind.GOLDEN, x, -0.05f, (0.42f + random.nextFloat() * 0.16f) * boost, spin * 1.6f, phase)
                        roll < 0.28f ->
                            FallingItem("pill", Color(0xFF8A8F99), ItemKind.BAD, x, -0.05f, (0.34f + random.nextFloat() * 0.20f) * boost, spin, phase)
                        else -> {
                            val pick = listOf(
                                "berry" to Color(0xFFE0555F),
                                "cake" to Color(0xFFF6C6D9),
                                "sushi" to Color(0xFFF2F0E6),
                                "icecream" to Color(0xFF8FD8E8),
                            ).random(random)
                            FallingItem(pick.first, pick.second, ItemKind.GOOD, x, -0.05f, (0.30f + random.nextFloat() * 0.22f) * boost, spin, phase)
                        }
                    }
                }

                items.forEach {
                    it.y += it.speed * dt
                    it.rot += it.spin * dt * motion
                }
                val landed = items.filter { it.y >= PET_Y }
                landed.forEach { item ->
                    val hit = abs(item.x - petX) < 0.12f
                    when {
                        hit && item.kind != ItemKind.BAD -> {
                            val golden = item.kind == ItemKind.GOLDEN
                            caught += 1
                            streak += 1
                            // Recomputed here: the composition-level multiplier lags a frame behind.
                            val mult = min(1 + streak / 3, 5)
                            score += 100 * mult * (if (golden) 3 else 1)
                            bestMultiplier = maxOf(bestMultiplier, mult)
                            catchPulse = 1f
                            bursts += Burst(item.x, PET_Y, if (golden) NeoColors.NeonYellow else item.tint, item.phase)
                            if (golden) {
                                flash = "GOLDEN x3"
                                flashColor = NeoColors.NeonYellow
                                flashTick += 1
                            }
                            // Rising pitch turns a streak into something you can hear.
                            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_HIT, pitch = 1f + min(streak, 12) * 0.05f)
                        }
                        hit -> {
                            lives -= 1
                            streak = 0
                            flash = "OUCH"
                            flashColor = NeoColors.NeonRed
                            flashTick += 1
                            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                        }
                        item.kind != ItemKind.BAD -> {
                            dropped += 1
                            if (streak >= 3) {
                                flash = "STREAK LOST"
                                flashColor = NeoColors.OnDarkMuted
                                flashTick += 1
                            }
                            streak = 0
                        }
                        else -> Unit
                    }
                }
                items.removeAll(landed)

                bursts.forEach { it.age += dt }
                bursts.removeAll { it.age >= BURST_LIFE }

                // Lean into the direction of travel; smoothed so a flick does not snap the body.
                if (dt > 0f) {
                    val velocity = (petX - lastPetX) / dt
                    lastPetX = petX
                    val targetLean = (velocity * 14f).coerceIn(-16f, 16f) * motion
                    petLean += (targetLean - petLean) * (dt * 12f).coerceAtMost(1f)
                }
                catchPulse = (catchPulse - dt * 4f).coerceAtLeast(0f)

                if (time >= duration || lives <= 0) finished = true
            }
        }
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val normalised = (caught.toFloat() / target).coerceIn(0f, 1f)
        viewModel.finishGame(
            won = caught >= target && lives > 0,
            score = normalised,
            gameName = "Snack Catch",
            gameId = GAME_ID,
            points = score,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Snack Catch",
            left = "Score $score  ·  $caught/$target",
            right = if (multiplier > 1) "COMBO x$multiplier" else "Lives $lives",
            progress = (time / duration).coerceIn(0f, 1f),
            onExit = onExit,
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF12141B))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        petX = (change.position.x / size.width).coerceIn(0.08f, 0.92f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Ground line the pet runs along.
                drawRect(
                    color = NeoColors.NeonCyan.copy(alpha = 0.25f),
                    topLeft = Offset(0f, size.height * 0.88f),
                    size = Size(size.width, 3f),
                )

                val heartR = size.minDimension * 0.030f
                repeat(3) { i ->
                    val filled = i < lives
                    drawHeart(
                        center = Offset(heartR * 2.2f + i * heartR * 2.8f, heartR * 2.4f),
                        radius = heartR,
                        color = if (filled) NeoColors.NeonRed else Color.White.copy(alpha = 0.12f),
                    )
                }

                items.forEach { item ->
                    val cx = item.x * size.width
                    val cy = item.y * size.height
                    val iconSize = size.minDimension * 0.14f
                    // Air resistance read as a wobble: wide when compressed, tall when stretched.
                    val wobble = sin(time * 7f + item.phase) * 0.09f * motion
                    if (item.kind == ItemKind.GOLDEN) {
                        drawCircle(
                            color = NeoColors.NeonYellow.copy(alpha = 0.18f + 0.12f * (sin(time * 9f) * 0.5f + 0.5f)),
                            radius = iconSize * 0.85f,
                            center = Offset(cx, cy),
                        )
                    }
                    rotate(degrees = item.rot, pivot = Offset(cx, cy)) {
                        scale(scaleX = 1f + wobble, scaleY = 1f - wobble, pivot = Offset(cx, cy)) {
                            // Shrink the drawing area to an icon-sized box centred on the item, so
                            // `drawItem` can keep laying itself out against the full canvas it is given.
                            inset(
                                left = cx - iconSize / 2f,
                                top = cy - iconSize / 2f,
                                right = size.width - (cx + iconSize / 2f),
                                bottom = size.height - (cy + iconSize / 2f),
                            ) {
                                drawItem(item.iconKey, item.tint)
                            }
                        }
                    }
                }

                bursts.forEach { burst ->
                    val t = (burst.age / BURST_LIFE).coerceIn(0f, 1f)
                    val center = Offset(burst.x * size.width, burst.y * size.height)
                    val fade = 1f - t
                    drawCircle(
                        color = burst.color.copy(alpha = fade * 0.75f),
                        radius = size.minDimension * (0.04f + 0.17f * t),
                        center = center,
                        style = Stroke(width = size.minDimension * 0.014f * fade + 1f),
                    )
                    if (motion > 0.5f) {
                        repeat(7) { i ->
                            val angle = burst.seed + i * 0.897f
                            val distance = size.minDimension * (0.03f + 0.17f * t)
                            drawCircle(
                                color = burst.color.copy(alpha = fade),
                                radius = size.minDimension * 0.014f * fade,
                                center = Offset(
                                    center.x + cos(angle) * distance,
                                    center.y + sin(angle) * distance * 0.8f - size.minDimension * 0.05f * t,
                                ),
                            )
                        }
                    }
                }

                drawCreature(
                    center = Offset(petX * size.width, size.height * PET_Y),
                    unit = size.minDimension * 0.62f,
                    spec = CreatureSpec(
                        species = pet.species,
                        stage = if (pet.stage == LifeStage.EGG) LifeStage.BABY else pet.stage,
                        branch = pet.branch,
                        mood = Mood.HAPPY,
                        hatId = pet.equippedHat,
                        weightGrams = pet.weightGrams,
                    ),
                    frame = CreatureFrame(
                        squash = 1f - catchPulse * 0.12f * motion,
                        mouthOpen = 0.5f + catchPulse * 0.4f,
                        lean = petLean,
                        armSwing = sin(time * 8f) * motion,
                        gaze = (petLean / 16f).coerceIn(-1f, 1f),
                    ),
                )
            }

            JudgementFlash(text = flash, color = flashColor, tick = flashTick)

            BestChip(best = best, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))

            if (!started && !finished) {
                CountdownGate(onReady = { started = true })
            }
        }

        Text(
            text = "Drag to move. Catch the food, dodge the pills — gold is worth triple.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (finished) {
            GameResult(
                title = if (caught >= target && lives > 0) "FEAST!" else "TIME UP",
                lines = listOf(
                    "Score $score" + if (score > best) "  ★ NEW RECORD" else "",
                    "Caught $caught  ·  Dropped $dropped",
                    "Best combo x$bestMultiplier  ·  Lives left $lives",
                ),
                onExit = onExit,
            )
        }
    }
}

/** Two lobes and a point — a heart small enough to read at 12dp on the playfield. */
private fun DrawScope.drawHeart(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + radius * 0.95f)
        cubicTo(
            center.x - radius * 1.55f, center.y - radius * 0.25f,
            center.x - radius * 0.62f, center.y - radius * 1.35f,
            center.x, center.y - radius * 0.32f,
        )
        cubicTo(
            center.x + radius * 0.62f, center.y - radius * 1.35f,
            center.x + radius * 1.55f, center.y - radius * 0.25f,
            center.x, center.y + radius * 0.95f,
        )
        close()
    }
    drawPath(path, color)
}
