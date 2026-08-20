@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
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
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(8.dp))

        SettingsCard("Look", "How the pet and the room are drawn on screen.") {
            ToggleRow("Pixel-art mode", config.pixelMode) { on ->
                viewModel.updateConfig { it.copy(pixelMode = on) }
            }
            if (config.pixelMode) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Detail — lower is chunkier and more retro.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // Flow, not a fixed row: four chips do not fit on one line on narrow phones.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PixelPresets.forEach { (height, label) ->
                        FilterChip(
                            selected = config.pixelHeight == height,
                            onClick = { viewModel.updateConfig { it.copy(pixelHeight = height) } },
                            label = {
                                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${config.pixelHeight}px tall buffer, upscaled with hard edges.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            ToggleRow("Reduced motion", config.reducedMotion) { on ->
                viewModel.updateConfig { it.copy(reducedMotion = on) }
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Sound and feel", "Audio and vibration feedback for taps, meals and games.") {
            ToggleRow("Sound effects", config.soundEnabled) { on ->
                viewModel.updateConfig { it.copy(soundEnabled = on) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Volume",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (config.soundEnabled) 1f else 0.38f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(config.sfxVolume * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (config.soundEnabled) NeoColors.NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = config.sfxVolume.coerceIn(0f, 1f),
                onValueChange = { v -> viewModel.updateConfig { it.copy(sfxVolume = v.coerceIn(0f, 1f)) } },
                valueRange = 0f..1f,
                enabled = config.soundEnabled,
            )
            ToggleRow("Haptics", config.hapticsEnabled) { on ->
                viewModel.updateConfig { it.copy(hapticsEnabled = on) }
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Reminders", "Whether NeoPal nudges you when a need runs low.") {
            ToggleRow("Care reminders", config.notificationsEnabled) { on ->
                viewModel.updateConfig { it.copy(notificationsEnabled = on) }
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Finish", "How soft the picture is, and how much atmosphere sits over it.") {
            Text(
                "Pixel softness ${(config.softFinish * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = config.softFinish,
                onValueChange = { value -> viewModel.updateConfig { it.copy(softFinish = value) } },
                valueRange = 0f..1f,
                enabled = config.pixelMode,
            )
            Text(
                "At zero the blocks are razor-hard. Higher lets light bleed a pixel past an edge, " +
                    "which takes the glare off without blurring the art.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Atmosphere ${(config.atmosphere * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = config.atmosphere,
                onValueChange = { value -> viewModel.updateConfig { it.copy(atmosphere = value) } },
                valueRange = 0f..1f,
            )
            Text(
                "Warm light by day, cool by night, and a soft vignette around the room.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Pace", "How long a whole life takes, and how fast the clock runs.") {
            val lifetimeHours = Simulation.expectedLifetimeSeconds(config) / 3600f
            Text(
                "A full life takes about ${"%.1f".format(lifetimeHours)} hours of real time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            // Presets rather than a raw multiplier: nobody knows what "1.8x life speed" means.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Slow" to 0.5f,
                    "Normal" to 1f,
                    "Fast" to 3f,
                    "Demo" to 12f,
                ).forEach { (label, speed) ->
                    FilterChip(
                        selected = kotlin.math.abs(config.lifeSpeed - speed) < 0.01f,
                        onClick = { viewModel.updateConfig { it.copy(lifeSpeed = speed) } },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Clock: one pet day lasts ${config.secondsPerPetDay / 60} minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = (config.secondsPerPetDay / 60f),
                onValueChange = { minutes ->
                    viewModel.updateConfig { it.copy(secondsPerPetDay = (minutes.toLong().coerceAtLeast(5L)) * 60L) }
                },
                valueRange = 5f..240f,
                steps = 46,
            )
            Text(
                "The clock only sets day, night and the day counter. Time away is simulated at " +
                    "${(config.offlineDecayMultiplier * 100).toInt()}% speed and capped at " +
                    "${config.maxOfflineSeconds / 3600} hours, and absence alone will never kill a " +
                    "healthy pet — only illness you left untreated can.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Help", "The first-run coach marks that explain the room.") {
            OutlinedButton(
                onClick = {
                    viewModel.updateConfig { it.copy(tutorialSeen = false) }
                    tipsReplayed = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Replay the tips") }
            if (tipsReplayed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The tips will show again next time you open the room.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoColors.NeonCyan,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Save data", "Move this pet between devices, or restore an older copy.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setText(AnnotatedString(viewModel.exportSave()))
                            importResult = "Save copied to the clipboard."
                        }
                    },
                ) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        viewModel.importSave(importText) { ok ->
                            importResult = if (ok) "Save imported." else "That save could not be read."
                        }
                    },
                    enabled = importText.isNotBlank(),
                ) { Text("Import") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("Paste a save here") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            importResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = NeoColors.NeonCyan)
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Danger zone", "Ends this pet's life and starts a fresh save.") {
            Button(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete save and start over") }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "NeoPal is an original virtual pet. All art is drawn procedurally in code; the console " +
                "styling is a generic handheld design and carries no third-party branding.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
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

@Composable
private fun SettingsCard(title: String, blurb: String? = null, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = NeoColors.NeonCyan)
            if (blurb != null) {
                Spacer(Modifier.height(2.dp))
                Text(blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
