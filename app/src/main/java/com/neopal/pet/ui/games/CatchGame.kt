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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawItem
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs
import kotlin.random.Random

private data class FallingItem(
    val iconKey: String,
    val tint: Color,
    val good: Boolean,
    var x: Float,
    var y: Float,
    val speed: Float,
)

/**
 * Catch the falling snacks and dodge the junk. The pet itself is the paddle — drag anywhere
 * on the playfield to move it — which makes the reward feel like it belongs to the creature.
 */
@Composable
fun CatchGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return

    val items = remember { mutableListOf<FallingItem>() }
    var petX by remember { mutableFloatStateOf(0.5f) }
    var time by remember { mutableFloatStateOf(0f) }
    var nextSpawn by remember { mutableFloatStateOf(0.5f) }
    var caught by remember { mutableIntStateOf(0) }
    var dropped by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var finished by remember { mutableStateOf(false) }

    val duration = 40f
    val target = 15

    LaunchedEffect(Unit) {
        val random = Random(System.currentTimeMillis())
        var previous = 0L
        while (!finished) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                time += dt

                if (time >= nextSpawn && time < duration - 1.5f) {
                    nextSpawn = time + (0.85f - (time / duration) * 0.45f).coerceAtLeast(0.30f)
                    val good = random.nextFloat() > 0.28f
                    items += if (good) {
                        val pick = listOf(
                            "berry" to Color(0xFFE0555F),
                            "cake" to Color(0xFFF6C6D9),
                            "sushi" to Color(0xFFF2F0E6),
                            "icecream" to Color(0xFF8FD8E8),
                        ).random(random)
                        FallingItem(pick.first, pick.second, true, random.nextFloat() * 0.86f + 0.07f, -0.05f, 0.30f + random.nextFloat() * 0.22f)
                    } else {
                        FallingItem("pill", Color(0xFF8A8F99), false, random.nextFloat() * 0.86f + 0.07f, -0.05f, 0.34f + random.nextFloat() * 0.20f)
                    }
                }

                val petY = 0.80f
                items.forEach { it.y += it.speed * dt }
                val landed = items.filter { it.y >= petY }
                landed.forEach { item ->
                    val hit = abs(item.x - petX) < 0.12f
                    when {
                        hit && item.good -> {
                            caught += 1
                            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_HIT)
                        }
                        hit && !item.good -> {
                            lives -= 1
                            if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                        }
                        item.good -> dropped += 1
                        else -> Unit
                    }
                }
                items.removeAll(landed)

                if (time >= duration || lives <= 0) finished = true
            }
        }
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val score = (caught.toFloat() / target).coerceIn(0f, 1f)
        viewModel.finishGame(won = caught >= target && lives > 0, score = score, gameName = "Snack Catch")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Snack Catch",
            left = "Caught $caught/$target",
            right = "Lives $lives",
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
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Ground line the pet runs along.
                drawRect(
                    color = NeoColors.NeonCyan.copy(alpha = 0.25f),
                    topLeft = Offset(0f, size.height * 0.88f),
                    size = androidx.compose.ui.geometry.Size(size.width, 3f),
                )
                items.forEach { item ->
                    val cx = item.x * size.width
                    val cy = item.y * size.height
                    val iconSize = size.minDimension * 0.14f
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
                drawCreature(
                    center = Offset(petX * size.width, size.height * 0.80f),
                    unit = size.minDimension * 0.62f,
                    spec = CreatureSpec(
                        species = pet.species,
                        stage = if (pet.stage == LifeStage.EGG) LifeStage.BABY else pet.stage,
                        branch = pet.branch,
                        mood = Mood.HAPPY,
                        hatId = pet.equippedHat,
                        weightGrams = pet.weightGrams,
                    ),
                    frame = CreatureFrame(mouthOpen = 0.5f, armSwing = kotlin.math.sin(time * 8f)),
                )
            }
        }

        Text(
            text = "Drag to move. Catch the food, dodge the pills.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (finished) {
            GameResult(
                title = if (caught >= target && lives > 0) "FEAST!" else "TIME UP",
                lines = listOf("Caught $caught", "Dropped $dropped", "Lives left $lives"),
                onExit = onExit,
            )
        }
    }
}
