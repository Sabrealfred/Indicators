package com.neopal.pet.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Species
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.sin

/** Species pick + naming. The preview animates so the choice feels alive before you commit. */
@Composable
fun NewGameScreen(
    isNextGeneration: Boolean,
    generation: Int,
    onStart: (String, Species) -> Unit,
) {
    var name by remember { mutableStateOf("Pip") }
    var species by remember { mutableStateOf(Species.AQUA) }
    val transition = rememberInfiniteTransition(label = "preview")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "preview-clock",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isNextGeneration) "GENERATION ${generation + 1}" else "NEW PET",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (isNextGeneration) {
                "Coins, cosmetics and the album carry over."
            } else {
                "Pick an egg. Every family grows up differently."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // Big animated preview of the currently selected family.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCreature(
                    center = Offset(size.width / 2f, size.height * 0.58f),
                    unit = size.minDimension,
                    spec = CreatureSpec(
                        species = species,
                        stage = LifeStage.CHILD,
                        branch = EvolutionBranch.BALANCED,
                        mood = Mood.HAPPY,
                    ),
                    frame = CreatureFrame(
                        bobY = sin(time) * 0.03f,
                        squash = 1f + sin(time) * 0.03f,
                        mouthOpen = 0.35f,
                        armSwing = sin(time * 2f) * 0.6f,
                        gaze = sin(time * 0.5f),
                    ),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Species.entries.forEach { option ->
                val palette = Palettes.creature(option, EvolutionBranch.BALANCED)
                val selected = option == species
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            BorderStroke(if (selected) 3.dp else 1.dp, if (selected) NeoColors.NeonCyan else palette.body.copy(alpha = 0.4f)),
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { species = option }
                        .padding(vertical = 10.dp),
                ) {
                    Canvas(Modifier.size(38.dp)) {
                        drawCircle(palette.body, size.minDimension / 2.4f)
                        drawCircle(palette.accent, size.minDimension / 5f, Offset(size.width * 0.62f, size.height * 0.38f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(option.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 12) name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onStart(name.ifBlank { "Pip" }, species) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("START", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
    }
}
