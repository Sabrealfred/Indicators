package com.neopal.pet.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Species
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.components.MinTouchTarget
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
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
    val accent = NeoAccents.cyan
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(pixelUnits(5)),
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
        Spacer(Modifier.height(pixelUnits(4)))

        // Big animated preview of the currently selected family, sunk into the page like the
        // cartridge slot on the home menu.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pixelSurface(
                    fill = surface,
                    accent = accent,
                    bevel = PixelBevel.PRESSED,
                    background = background,
                )
                .padding(pixelUnits(2)),
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
        Spacer(Modifier.height(pixelUnits(4)))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
        ) {
            Species.entries.forEach { option ->
                val palette = Palettes.creature(option, EvolutionBranch.BALANCED)
                val isSelected = option == species
                PixelPanel(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { selected = isSelected },
                    fill = if (isSelected) lerp(surface, accent, 0.20f) else surface,
                    accent = if (isSelected) accent else palette.body,
                    background = background,
                    // The chosen egg is held down: same geometry, light from the other corner.
                    bevel = if (isSelected) PixelBevel.PRESSED else PixelBevel.RAISED,
                    contentPadding = PaddingValues(vertical = pixelUnits(2), horizontal = pixelUnits(1)),
                    onClick = { species = option },
                ) {
                    Canvas(Modifier.size(38.dp).align(Alignment.CenterHorizontally)) {
                        drawCircle(palette.body, size.minDimension / 2.4f)
                        drawCircle(palette.accent, size.minDimension / 5f, Offset(size.width * 0.62f, size.height * 0.38f))
                    }
                    Spacer(Modifier.height(pixelUnits(2)))
                    Text(
                        option.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        Spacer(Modifier.height(pixelUnits(4)))
        PixelPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = accent,
            background = background,
            title = "Name",
            contentPadding = PaddingValues(pixelUnits(2)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    // A well, not an outlined field: the name is something you drop into the panel.
                    .pixelSurface(
                        fill = lerp(surface, background, 0.35f),
                        accent = accent,
                        bevel = PixelBevel.PRESSED,
                        borderUnits = 1,
                        background = background,
                    )
                    .padding(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = name,
                    // The twelve-character cap is what the save format and the top bar can show.
                    onValueChange = { if (it.length <= 12) name = it },
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(accent),
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Name" },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (name.isEmpty()) {
                                Text(
                                    "Pip",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        PixelButton(
            onClick = { onStart(name.ifBlank { "Pip" }, species) },
            modifier = Modifier
                .fillMaxWidth()
                .height(pixelUnits(13)),
            accent = accent,
            background = background,
        ) {
            Text("START", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
        Spacer(Modifier.height(pixelUnits(3)))
    }
}
