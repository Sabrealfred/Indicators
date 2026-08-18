package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlinx.coroutines.launch

/** Sound, haptics, notifications, accessibility, game speed, save import/export and reset. */
@Composable
fun SettingsScreen(viewModel: PetViewModel, onBack: () -> Unit, onResetToNewGame: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val config = ui.config
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }

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

        SettingsCard("Game") {
            ToggleRow("Sound effects", config.soundEnabled) { on ->
                viewModel.updateConfig { it.copy(soundEnabled = on) }
            }
            ToggleRow("Haptics", config.hapticsEnabled) { on ->
                viewModel.updateConfig { it.copy(hapticsEnabled = on) }
            }
            ToggleRow("Care reminders", config.notificationsEnabled) { on ->
                viewModel.updateConfig { it.copy(notificationsEnabled = on) }
            }
            ToggleRow("Reduced motion", config.reducedMotion) { on ->
                viewModel.updateConfig { it.copy(reducedMotion = on) }
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Pace") {
            Text(
                "One pet day lasts ${config.secondsPerPetDay / 60} minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = (config.secondsPerPetDay / 60f),
                onValueChange = { minutes ->
                    viewModel.updateConfig { it.copy(secondsPerPetDay = (minutes.toLong().coerceAtLeast(2L)) * 60L) }
                },
                valueRange = 2f..60f,
                steps = 28,
            )
            Text(
                "Shorter days mean faster growth and faster needs. Offline progress is capped at " +
                    "${config.maxOfflineSeconds / 3600} hours so a long break never wipes a healthy pet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingsCard("Save data") {
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
        SettingsCard("Danger zone") {
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
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = NeoColors.NeonCyan)
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
