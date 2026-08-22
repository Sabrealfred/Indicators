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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Pal
import com.neopal.pet.domain.Species
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.PixelTextWell
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import kotlin.math.sin

/**
 * Species pick + naming. The preview animates so the choice feels alive before you commit.
 *
 * [heirs] is the whole of selective breeding reaching the screen. `Simulation.nextGeneration` has
 * always taken an heir — its genome carries over, its species, its parents' names, whatever it was
 * taught — and the only call site never passed one, so a child a player had bred simply vanished
 * when its parent died and every generation was an unrelated founder in a furnished room. The
 * genetics worked perfectly right up to the moment they were supposed to pay off.
 */
@Composable
fun NewGameScreen(
    isNextGeneration: Boolean,
    generation: Int,
    heirs: List<Pal> = emptyList(),
    onStart: (String, Species, String?) -> Unit,
) {
    var name by remember { mutableStateOf("Pip") }
    var species by remember { mutableStateOf(Species.AQUA) }
    // Null is the nursery, which stays the default: continuing the line has to be chosen, the
    // same way courting and raising the child had to be.
    var heirId by remember { mutableStateOf<String?>(null) }
    val heir = heirs.firstOrNull { it.id == heirId }
    // An heir keeps its own species, so the picker below is showing a decision that no longer
    // exists. Showing the heir's family instead is the honest preview.
    val shownSpecies = heir?.species ?: species
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
            // Edge to edge: keep the content out of the status and gesture bars. The
            // background is applied first on purpose, so it still bleeds under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(pixelUnits(5)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Everything above START scrolls, and START itself never leaves the bottom of the window:
        // in landscape the preview alone is taller than the screen, and a weighted spacer cannot
        // live inside a scrolling column.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
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
                    .height(pixelUnits(55))
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
                            species = shownSpecies,
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

            // The children the last one actually raised. Offered before the egg picker because
            // choosing one settles the species, and an egg picker that silently stops mattering
            // is worse than one that is not there.
            if (heirs.isNotEmpty()) {
                PixelPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = accent,
                    background = background,
                    title = "Who continues",
                    contentPadding = PaddingValues(pixelUnits(2)),
                ) {
                    Text(
                        text = "An heir keeps its own body and family, and whatever it was taught " +
                            "in time. The nursery sends something unrelated.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(pixelUnits(2)))
                    HeirRow(
                        label = "From the nursery",
                        detail = "A new family, nothing behind it",
                        isSelected = heirId == null,
                        accent = accent,
                        surface = surface,
                        background = background,
                        onClick = { heirId = null },
                    )
                    heirs.forEach { candidate ->
                        Spacer(Modifier.height(pixelUnits(1)))
                        HeirRow(
                            label = candidate.name,
                            detail = candidate.parentNames.joinToString(" and ")
                                .ifBlank { candidate.species.displayName },
                            isSelected = heirId == candidate.id,
                            accent = accent,
                            surface = surface,
                            background = background,
                            onClick = { heirId = candidate.id },
                        )
                    }
                }
                Spacer(Modifier.height(pixelUnits(4)))
            }

            // Hidden rather than disabled once an heir is chosen: nextGeneration ignores the
            // species outright for an heir, so leaving the row on screen would be offering a
            // choice the simulation is about to throw away.
            if (heir == null) {
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
                            Canvas(Modifier.size(pixelUnits(10)).align(Alignment.CenterHorizontally)) {
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
            }

            Spacer(Modifier.height(pixelUnits(4)))
            PixelPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = accent,
                background = background,
                title = "Name",
                contentPadding = PaddingValues(pixelUnits(2)),
            ) {
                PixelTextWell(
                    value = name,
                    // The twelve-character cap is what the save format and the top bar can show.
                    onValueChange = { if (it.length <= 12) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Name",
                    placeholder = "Pip",
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    accent = accent,
                    fill = surface,
                    background = background,
                )
            }
            Spacer(Modifier.height(pixelUnits(4)))
        }
        PixelButton(
            // heir?.id rather than heirId: a selection that no longer matches anybody in the
            // list must not be handed on as if it did.
            onClick = { onStart(name.ifBlank { "Pip" }, shownSpecies, heir?.id) },
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

/**
 * One candidate for the next life: the nursery, or one of the last creature's own children.
 *
 * A full-width row rather than a chip because the detail line is the reason to pick one — a name
 * on its own says nothing about whose child it is, and whose child it is was the whole point of
 * having bred it.
 */
@Composable
private fun HeirRow(
    label: String,
    detail: String,
    isSelected: Boolean,
    accent: Color,
    surface: Color,
    background: Color,
    onClick: () -> Unit,
) {
    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isSelected },
        fill = if (isSelected) lerp(surface, accent, 0.20f) else surface,
        accent = if (isSelected) accent else MaterialTheme.colorScheme.outline,
        background = background,
        bevel = if (isSelected) PixelBevel.PRESSED else PixelBevel.RAISED,
        contentPadding = PaddingValues(vertical = pixelUnits(2), horizontal = pixelUnits(2)),
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
