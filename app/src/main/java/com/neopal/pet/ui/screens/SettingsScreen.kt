@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.MinTouchTarget
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelSurface
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

/** Matches the kit's dimming so a disabled slider sits next to a disabled Material switch. */
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
            ToggleRow("Pixel-art mode", config.pixelMode) { on ->
                viewModel.updateConfig { it.copy(pixelMode = on) }
            }
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
                        PixelChoice(
                            label = label,
                            isSelected = config.pixelHeight == height,
                            onClick = { viewModel.updateConfig { it.copy(pixelHeight = height) } },
                        )
                    }
                }
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    "${config.pixelHeight}px tall buffer, upscaled with hard edges.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(pixelUnits(1)))
            ToggleRow("Reduced motion", config.reducedMotion) { on ->
                viewModel.updateConfig { it.copy(reducedMotion = on) }
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Sound and feel", "Audio and vibration feedback for taps, meals and games.") {
            ToggleRow("Sound effects", config.soundEnabled) { on ->
                viewModel.updateConfig { it.copy(soundEnabled = on) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
            PixelStepSlider(
                label = "Volume",
                valueLabel = "${(config.sfxVolume * 100f).roundToInt()} percent",
                value = config.sfxVolume.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                notches = 20,
                enabled = config.soundEnabled,
                onValueChange = { v -> viewModel.updateConfig { it.copy(sfxVolume = v.coerceIn(0f, 1f)) } },
            )
            ToggleRow("Haptics", config.hapticsEnabled) { on ->
                viewModel.updateConfig { it.copy(hapticsEnabled = on) }
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Reminders", "Whether NeoPal nudges you when a need runs low.") {
            ToggleRow("Care reminders", config.notificationsEnabled) { on ->
                viewModel.updateConfig { it.copy(notificationsEnabled = on) }
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel("Finish", "How soft the picture is, and how much atmosphere sits over it.") {
            Text(
                "Pixel softness ${(config.softFinish * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PixelStepSlider(
                label = "Pixel softness",
                valueLabel = "${(config.softFinish * 100).roundToInt()} percent",
                value = config.softFinish,
                valueRange = 0f..1f,
                notches = 20,
                enabled = config.pixelMode,
                onValueChange = { value -> viewModel.updateConfig { it.copy(softFinish = value) } },
            )
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
            )
            PixelStepSlider(
                label = "Atmosphere",
                valueLabel = "${(config.atmosphere * 100).roundToInt()} percent",
                value = config.atmosphere,
                valueRange = 0f..1f,
                notches = 20,
                onValueChange = { value -> viewModel.updateConfig { it.copy(atmosphere = value) } },
            )
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
                    PixelChoice(
                        label = label,
                        isSelected = kotlin.math.abs(config.lifeSpeed - speed) < 0.01f,
                        onClick = { viewModel.updateConfig { it.copy(lifeSpeed = speed) } },
                    )
                }
            }
            Spacer(Modifier.height(pixelUnits(3)))
            Text(
                "Clock: one pet day lasts ${config.secondsPerPetDay / 60} minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PixelStepSlider(
                label = "Minutes per pet day",
                valueLabel = "${config.secondsPerPetDay / 60} minutes",
                value = (config.secondsPerPetDay / 60f),
                valueRange = 5f..240f,
                // 47 notches of five minutes — the same stops the Material slider's 46 steps had.
                notches = 47,
                onValueChange = { minutes ->
                    viewModel.updateConfig { it.copy(secondsPerPetDay = (minutes.toLong().coerceAtLeast(5L)) * 60L) }
                },
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
                placeholder = "Paste a save here",
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
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

/**
 * A one-of-many choice. The kit has no chip, and a Material [androidx.compose.material3.FilterChip]
 * inside a bevelled panel is exactly the second visual language the kit exists to remove — so the
 * choice is a small panel that sits pressed *in* while it is the live one.
 */
@Composable
private fun PixelChoice(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (isSelected) NeoAccents.cyan else MaterialTheme.colorScheme.onSurfaceVariant
    PixelPanel(
        modifier = modifier.semantics { selected = isSelected },
        fill = if (isSelected) lerp(fill, NeoAccents.cyan, 0.22f) else fill,
        accent = accent,
        bevel = if (isSelected) PixelBevel.PRESSED else PixelBevel.RAISED,
        borderUnits = 1,
        contentPadding = PaddingValues(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
        onClick = onClick,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * A slider that can only land on a notch.
 *
 * Material's slider is a hairline track with a round thumb — the two shapes the art and the kit
 * never use — so it reads as a foreign control the moment it sits inside a bevelled panel. This
 * one borrows the kit's segmented meter for the track and moves a bevelled block along it, and
 * snaps to [notches] equal stops so the value always lands on the grid too.
 */
@Composable
private fun PixelStepSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    notches: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = NeoAccents.cyan,
) {
    val steps = notches.coerceAtLeast(1)
    val min = valueRange.start
    val span = (valueRange.endInclusive - min).takeIf { it > 0f } ?: 1f
    val current = value.coerceIn(min, valueRange.endInclusive)
    val fraction = ((current - min) / span).coerceIn(0f, 1f)

    val surface = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background
    val knobWidth = pixelUnits(5)
    val knobPx = with(LocalDensity.current) { knobWidth.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    // Held live: the gesture handlers outlive the composition that installed them.
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    // Touch x is measured against the knob's travel, not the whole width, so the block ends up
    // under the finger at both ends instead of running out of room.
    val report: (Float) -> Unit = { x ->
        val travel = (widthPx - knobPx).coerceAtLeast(1f)
        val f = ((x - knobPx / 2f) / travel).coerceIn(0f, 1f)
        val index = (f * steps).roundToInt().coerceIn(0, steps)
        latestOnValueChange(min + span * index / steps)
    }
    val dragX = remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .onSizeChanged { widthPx = it.width }
            // Tap sits outside the drag: the drag node sees the pointer first and only claims it
            // once it has crossed the slop, so a tap on the track still lands on a notch and a
            // drag never also fires a tap when the finger lifts.
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { offset -> report(offset.x) }
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    dragX.value += delta
                    report(dragX.value)
                },
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { start ->
                    dragX.value = start.x
                    report(start.x)
                },
            )
            .graphicsLayer { alpha = if (enabled) 1f else DisabledAlpha }
            .semantics {
                contentDescription = label
                stateDescription = valueLabel
                progressBarRangeInfo = ProgressBarRangeInfo(current, valueRange, steps - 1)
                if (!enabled) disabled()
                setProgress { target ->
                    if (!enabled) {
                        false
                    } else {
                        val f = ((target - min) / span).coerceIn(0f, 1f)
                        latestOnValueChange(min + span * (f * steps).roundToInt() / steps)
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        PixelBar(
            fraction = fraction,
            color = accent,
            segments = steps,
            height = pixelUnits(6),
            trackColor = surface,
            background = background,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((((widthPx - knobPx).coerceAtLeast(0f)) * fraction).roundToInt(), 0) }
                .size(width = knobWidth, height = pixelUnits(9))
                .pixelSurface(
                    fill = lerp(surface, accent, 0.35f),
                    accent = accent,
                    borderUnits = 1,
                    background = background,
                ),
        )
    }
}

/**
 * A text field as a recessed well. Material's outlined field brings its own radius and floating
 * label; the well is the same shape the kit uses for anything the player can put something into.
 */
@Composable
private fun PixelTextWell(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = false,
    accent: Color = NeoAccents.cyan,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
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
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(accent),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
