@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.domain.RetroMode
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelChip
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.PixelSlider
import com.neopal.pet.ui.components.PixelTextWell
import com.neopal.pet.ui.components.PixelToggle
import com.neopal.pet.ui.components.pixelUnits
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Target buffer heights. The renderer derives the real size from the screen so the upscale is
 * always a whole number; these are the sizes it rounds toward. Below about 130 the creature's
 * face stops surviving the downsample, which is why the coarse end starts where it does.
 */
private val PixelPresets = listOf(
    130 to "Chunky",
    200 to "Classic",
    280 to "Fine",
    380 to "Crisp",
)

/** Matches the kit's dimming so a label greys out with the control it names. */
private const val DisabledAlpha = 0.38f

/** Look, sound, reminders, pace, tips, save import/export and reset — grouped by what they change. */
@Composable
fun SettingsScreen(viewModel: PetViewModel, onBack: () -> Unit, onResetToNewGame: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val config = ui.config
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }
    var tipsReplayed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pixelUnits(3)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(pixelUnits(2)))

        SettingsPanel("Look", "How the pet and the room are drawn on screen.") {
            PixelToggle(
                checked = config.pixelMode,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(pixelMode = on) } },
                label = "Pixel-art mode",
                accent = NeoAccents.cyan,
            )
            if (config.pixelMode) {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    "Detail — lower is chunkier and more retro.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                // Flow, not a fixed row: four chips do not fit on one line on narrow phones.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
                    verticalArrangement = Arrangement.spacedBy(pixelUnits(1)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PixelPresets.forEach { (height, label) ->
                        PixelChip(
                            label = label,
                            selected = config.pixelHeight == height,
                            onClick = { viewModel.updateConfig { it.copy(pixelHeight = height) } },
                            accent = NeoAccents.cyan,
                        )
                    }
                }
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    "${config.pixelHeight}px tall buffer, upscaled with hard edges.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                Text(
                    "Screen — which machine you are pretending to hold.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
                    verticalArrangement = Arrangement.spacedBy(pixelUnits(1)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RetroMode.entries.forEach { mode ->
                        PixelChip(
                            label = mode.displayName,
                            selected = config.retroMode == mode,
                            onClick = { viewModel.updateConfig { it.copy(retroMode = mode) } },
                            accent = NeoAccents.gold,
                        )
                    }
                }
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    config.retroMode.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(pixelUnits(2)))
            PixelToggle(
                checked = config.reducedMotion,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(reducedMotion = on) } },
                label = "Reduced motion",
                accent = NeoAccents.cyan,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Sound and feel", "Audio and vibration feedback for taps, meals and games.") {
            PixelToggle(
                checked = config.soundEnabled,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(soundEnabled = on) } },
                label = "Sound effects",
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            // The slider owns the announcement — this line is the same value in print, so it is
            // shown and not spoken.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            ) {
                Text(
                    "Volume",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (config.soundEnabled) 1f else DisabledAlpha),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(config.sfxVolume * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (config.soundEnabled) NeoAccents.cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PixelSlider(
                value = config.sfxVolume.coerceIn(0f, 1f),
                onValueChange = { v -> viewModel.updateConfig { it.copy(sfxVolume = v.coerceIn(0f, 1f)) } },
                label = "Volume",
                valueLabel = "${(config.sfxVolume * 100f).roundToInt()} percent",
                valueRange = 0f..1f,
                notches = 20,
                enabled = config.soundEnabled,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            PixelToggle(
                checked = config.hapticsEnabled,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(hapticsEnabled = on) } },
                label = "Haptics",
                accent = NeoAccents.cyan,
            )
        }


        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(
            "The creature's brain",
            "Off by default. Everything else in NeoPal works with no network at all, and nothing " +
                "here is needed to play — this only lets the creature talk back and think out loud.",
            accent = NeoAccents.gold,
        ) {
            PixelToggle(
                checked = config.mind.enabled,
                onCheckedChange = { on -> viewModel.updateMind { it.copy(enabled = on) } },
                label = "Give it somewhere to think",
                accent = NeoAccents.gold,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                "Route: ${config.mind.routeLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (config.mind.enabled) {
                Spacer(Modifier.height(pixelUnits(3)))
                // The player's own key wins over the shared service when both are set, because
                // somebody who went to the trouble of pasting one meant to use it.
                PixelTextWell(
                    value = config.mind.apiKey,
                    onValueChange = { v -> viewModel.updateMind { it.copy(apiKey = v.trim()) } },
                    label = "Your own key (optional)",
                    placeholder = "Leave empty to use the shared service",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    "Stored on this device and sent only to the address below. It is never put " +
                        "into anything the creature says, and never written to the save's diary.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.baseUrl,
                    onValueChange = { v -> viewModel.updateMind { it.copy(baseUrl = v.trim()) } },
                    label = "Endpoint",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.model,
                    onValueChange = { v -> viewModel.updateMind { it.copy(model = v.trim()) } },
                    label = "Model",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    "The default is a free model, so trying this costs nothing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.quickModel,
                    onValueChange = { v -> viewModel.updateMind { it.copy(quickModel = v.trim()) } },
                    label = "Quick model (optional)",
                    placeholder = "A smaller model, for deciding only",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    if (config.mind.splitsModels) {
                        "Deciding goes to ${config.mind.quickModel}; talking, planning and " +
                            "remembering go to ${config.mind.model}."
                    } else {
                        "Deciding runs many times an hour and only has to pick from a list the " +
                            "game already checked. Sending it somewhere small leaves the quota " +
                            "for talking and planning, which is where a big model shows."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.proxyUrl,
                    onValueChange = { v -> viewModel.updateMind { it.copy(proxyUrl = v.trim()) } },
                    label = "Shared service (optional)",
                    placeholder = "Used when no key of your own is set",
                    accent = NeoAccents.gold,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelToggle(
                    checked = config.mind.conversation,
                    onCheckedChange = { on -> viewModel.updateMind { it.copy(conversation = on) } },
                    label = "Let it talk",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                PixelToggle(
                    checked = config.mind.decidesActions,
                    onCheckedChange = { on -> viewModel.updateMind { it.copy(decidesActions = on) } },
                    label = "Let it choose what to do",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                PixelToggle(
                    checked = config.mind.lineageLessons,
                    onCheckedChange = { on -> viewModel.updateMind { it.copy(lineageLessons = on) } },
                    label = "Let it draw lessons for its children",
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    "Children inherit lessons from their parent's life whether this is on or " +
                        "off. This only changes who words them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Reminders", "Whether NeoPal nudges you when a need runs low.") {
            PixelToggle(
                checked = config.notificationsEnabled,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(notificationsEnabled = on) } },
                label = "Care reminders",
                accent = NeoAccents.cyan,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Finish", "How soft the picture is, and how much atmosphere sits over it.") {
            Text(
                "Pixel softness ${(config.softFinish * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelSlider(
                value = config.softFinish,
                onValueChange = { value -> viewModel.updateConfig { it.copy(softFinish = value) } },
                label = "Pixel softness",
                valueLabel = "${(config.softFinish * 100).roundToInt()} percent",
                valueRange = 0f..1f,
                notches = 20,
                // The handheld reprints the frame in four flat tones, so a bloom would only add a
                // fifth and a sixth. The slider is left visible and disabled rather than hidden,
                // so the reason is discoverable instead of the control merely vanishing.
                enabled = config.pixelMode && !config.retroMode.replacesColour,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                "At zero the blocks are razor-hard. Higher lets light bleed a pixel past an edge, " +
                    "which takes the glare off without blurring the art.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(3)))
            Text(
                "Atmosphere ${(config.atmosphere * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelSlider(
                value = config.atmosphere,
                onValueChange = { value -> viewModel.updateConfig { it.copy(atmosphere = value) } },
                label = "Atmosphere",
                valueLabel = "${(config.atmosphere * 100).roundToInt()} percent",
                valueRange = 0f..1f,
                notches = 20,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                "Warm light by day, cool by night, and a soft vignette around the room.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Pace", "How long a whole life takes, and how fast the clock runs.") {
            val lifetimeHours = Simulation.expectedLifetimeSeconds(config) / 3600f
            Text(
                "A full life takes about ${"%.1f".format(lifetimeHours)} hours of real time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            // Presets rather than a raw multiplier: nobody knows what "1.8x life speed" means.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
                verticalArrangement = Arrangement.spacedBy(pixelUnits(1)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(
                    "Slow" to 0.5f,
                    "Normal" to 1f,
                    "Fast" to 3f,
                    "Demo" to 12f,
                ).forEach { (label, speed) ->
                    PixelChip(
                        label = label,
                        selected = kotlin.math.abs(config.lifeSpeed - speed) < 0.01f,
                        onClick = { viewModel.updateConfig { it.copy(lifeSpeed = speed) } },
                        accent = NeoAccents.cyan,
                    )
                }
            }
            Spacer(Modifier.height(pixelUnits(3)))
            Text(
                "Clock: one pet day lasts ${config.secondsPerPetDay / 60} minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelSlider(
                value = (config.secondsPerPetDay / 60f),
                onValueChange = { minutes ->
                    viewModel.updateConfig { it.copy(secondsPerPetDay = (minutes.toLong().coerceAtLeast(5L)) * 60L) }
                },
                label = "Minutes per pet day",
                valueLabel = "${config.secondsPerPetDay / 60} minutes",
                valueRange = 5f..240f,
                // 47 notches of five minutes — the same stops the Material slider's 46 steps had.
                notches = 47,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                "The clock only sets day, night and the day counter. Time away is simulated at " +
                    "${(config.offlineDecayMultiplier * 100).toInt()}% speed and capped at " +
                    "${config.maxOfflineSeconds / 3600} hours, and absence alone will never kill a " +
                    "healthy pet — only illness you left untreated can.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Help", "The first-run coach marks that explain the room.") {
            PixelButton(
                onClick = {
                    viewModel.updateConfig { it.copy(tutorialSeen = false) }
                    tipsReplayed = true
                },
                accent = NeoAccents.cyan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Replay the tips", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            if (tipsReplayed) {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    "The tips will show again next time you open the room.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoAccents.cyan,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Save data", "Move this pet between devices, or restore an older copy.") {
            Row(horizontalArrangement = Arrangement.spacedBy(pixelUnits(2))) {
                PixelButton(
                    onClick = {
                        scope.launch {
                            clipboard.setText(AnnotatedString(viewModel.exportSave()))
                            importResult = "Save copied to the clipboard."
                        }
                    },
                    accent = NeoAccents.cyan,
                ) {
                    Text("Export", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
                PixelButton(
                    onClick = {
                        viewModel.importSave(importText) { ok ->
                            importResult = if (ok) "Save imported." else "That save could not be read."
                        }
                    },
                    accent = NeoAccents.cyan,
                    enabled = importText.isNotBlank(),
                ) {
                    Text("Import", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
            Spacer(Modifier.height(pixelUnits(2)))
            PixelTextWell(
                value = importText,
                onValueChange = { importText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Paste a save here",
                minLines = 2,
                maxLines = 4,
                accent = NeoAccents.cyan,
            )
            importResult?.let {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(it, style = MaterialTheme.typography.labelSmall, color = NeoAccents.cyan)
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Danger zone", "Ends this pet's life and starts a fresh save.", accent = MaterialTheme.colorScheme.error) {
            PixelButton(
                onClick = { confirmReset = true },
                accent = MaterialTheme.colorScheme.error,
                fill = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.error, 0.18f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Delete save and start over",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(5)))
        Text(
            "NeoPal is an original virtual pet. All art is drawn procedurally in code; the console " +
                "styling is a generic handheld design and carries no third-party branding.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(pixelUnits(6)))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Delete this pet?") },
            text = { Text("The save, the album and every unlock are erased. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetToNewGame()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/** A section of the screen: the kit's panel, with the section name on the title strip. */
@Composable
private fun SettingsPanel(
    title: String,
    blurb: String? = null,
    accent: Color = NeoAccents.cyan,
    content: @Composable ColumnScope.() -> Unit,
) {
    PixelPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        title = title,
        contentPadding = PaddingValues(pixelUnits(3)),
    ) {
        if (blurb != null) {
            Text(blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(pixelUnits(2)))
        }
        content()
    }
}
