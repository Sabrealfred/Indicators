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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.domain.RetroMode
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.domain.PetClock
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
// Buffer height paired with the resource that names it, not with the English. The list is a
// top-level `val` and a top-level `val` cannot call `stringResource`, so what it carries has to
// be the id; the chip resolves it. Same shape as the domain question in §6.1, decided the same
// way — the table holds identity, the composable holds words.
private val PixelPresets = listOf(
    130 to R.string.settings_pixel_preset_chunky,
    200 to R.string.settings_pixel_preset_classic,
    280 to R.string.settings_pixel_preset_fine,
    380 to R.string.settings_pixel_preset_crisp,
)

/** Matches the kit's dimming so a label greys out with the control it names. */
private const val DisabledAlpha = 0.38f

/** Look, sound, reminders, pace, tips, save import/export and reset — grouped by what they change. */
@Composable
fun SettingsScreen(
    viewModel: PetViewModel,
    onBack: () -> Unit,
    onResetToNewGame: () -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val config = ui.config
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }
    var tipsReplayed by remember { mutableStateOf(false) }

    // Resolved here rather than where they are assigned: both assignments happen inside a
    // callback or a coroutine, and `stringResource` is only legal in composition. Reading the
    // words at the composable edge and handing the *value* to the code that runs later is the
    // same move §6.1 asks for at the domain boundary, in the small.
    val savedToClipboard = stringResource(R.string.settings_save_copied)
    val saveImported = stringResource(R.string.settings_save_imported)
    val saveUnreadable = stringResource(R.string.settings_save_unreadable)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Edge to edge: keep the content out of the status and gesture bars. The
            // background is applied first on purpose, so it still bleeds under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pixelUnits(3)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back), tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(pixelUnits(2)))

        SettingsPanel(stringResource(R.string.settings_look), stringResource(R.string.settings_look_blurb)) {
            PixelToggle(
                checked = config.pixelMode,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(pixelMode = on) } },
                label = stringResource(R.string.settings_pixel_mode),
                accent = NeoAccents.cyan,
            )
            if (config.pixelMode) {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    stringResource(R.string.settings_detail_hint),
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
                    PixelPresets.forEach { (height, labelRes) ->
                        PixelChip(
                            label = stringResource(labelRes),
                            selected = config.pixelHeight == height,
                            onClick = { viewModel.updateConfig { it.copy(pixelHeight = height) } },
                            accent = NeoAccents.cyan,
                        )
                    }
                }
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    stringResource(R.string.settings_buffer_hint, config.pixelHeight),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                Text(
                    stringResource(R.string.settings_screen_hint),
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
                label = stringResource(R.string.settings_reduced_motion),
                accent = NeoAccents.cyan,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_sound), stringResource(R.string.settings_sound_blurb)) {
            PixelToggle(
                checked = config.soundEnabled,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(soundEnabled = on) } },
                label = stringResource(R.string.settings_sound_effects),
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
                    stringResource(R.string.settings_volume),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (config.soundEnabled) 1f else DisabledAlpha),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.percent_value, (config.sfxVolume * 100f).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (config.soundEnabled) NeoAccents.cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PixelSlider(
                value = config.sfxVolume.coerceIn(0f, 1f),
                onValueChange = { v -> viewModel.updateConfig { it.copy(sfxVolume = v.coerceIn(0f, 1f)) } },
                label = stringResource(R.string.settings_volume),
                valueLabel = stringResource(R.string.cd_percent_value, (config.sfxVolume * 100f).roundToInt()),
                valueRange = 0f..1f,
                notches = 20,
                enabled = config.soundEnabled,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            PixelToggle(
                checked = config.hapticsEnabled,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(hapticsEnabled = on) } },
                label = stringResource(R.string.settings_haptics),
                accent = NeoAccents.cyan,
            )
        }


        // Its own file: the only section of this screen that needs a speech engine in the
        // composition, and the only one whose copy has to stay honest about hardware.
        Spacer(Modifier.height(pixelUnits(3)))
        VoiceSettingsPanel(
            pet = ui.pet,
            config = config.voice,
            onChange = { voice -> viewModel.updateConfig { it.copy(voice = voice) } },
        )

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(
            stringResource(R.string.settings_brain),
            stringResource(R.string.settings_brain_blurb),
            accent = NeoAccents.gold,
        ) {
            PixelToggle(
                checked = config.mind.enabled,
                onCheckedChange = { on -> viewModel.updateMind { it.copy(enabled = on) } },
                label = stringResource(R.string.settings_brain_enable),
                accent = NeoAccents.gold,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                stringResource(R.string.settings_route, config.mind.routeLabel),
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
                    label = stringResource(R.string.settings_own_key),
                    placeholder = stringResource(R.string.settings_own_key_placeholder),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    stringResource(R.string.settings_own_key_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.baseUrl,
                    onValueChange = { v -> viewModel.updateMind { it.copy(baseUrl = v.trim()) } },
                    label = stringResource(R.string.settings_endpoint),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.model,
                    onValueChange = { v -> viewModel.updateMind { it.copy(model = v.trim()) } },
                    label = stringResource(R.string.settings_model),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    stringResource(R.string.settings_model_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.quickModel,
                    onValueChange = { v -> viewModel.updateMind { it.copy(quickModel = v.trim()) } },
                    label = stringResource(R.string.settings_quick_model),
                    placeholder = stringResource(R.string.settings_quick_model_placeholder),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    if (config.mind.splitsModels) {
                        stringResource(R.string.settings_split_models, config.mind.quickModel, config.mind.model)
                    } else {
                        stringResource(R.string.settings_split_models_hint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelTextWell(
                    value = config.mind.proxyUrl,
                    onValueChange = { v -> viewModel.updateMind { it.copy(proxyUrl = v.trim()) } },
                    label = stringResource(R.string.settings_shared_service),
                    placeholder = stringResource(R.string.settings_shared_service_placeholder),
                    accent = NeoAccents.gold,
                )

                Spacer(Modifier.height(pixelUnits(3)))
                PixelToggle(
                    checked = config.mind.conversation,
                    onCheckedChange = { on -> viewModel.updateMind { it.copy(conversation = on) } },
                    label = stringResource(R.string.settings_let_it_talk),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                PixelToggle(
                    checked = config.mind.decidesActions,
                    onCheckedChange = { on -> viewModel.updateMind { it.copy(decidesActions = on) } },
                    label = stringResource(R.string.settings_let_it_choose),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                PixelToggle(
                    checked = config.mind.lineageLessons,
                    onCheckedChange = { on -> viewModel.updateMind { it.copy(lineageLessons = on) } },
                    label = stringResource(R.string.settings_let_it_teach),
                    accent = NeoAccents.gold,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    stringResource(R.string.settings_let_it_teach_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_blurb)) {
            PixelToggle(
                checked = config.notificationsEnabled,
                onCheckedChange = { on -> viewModel.updateConfig { it.copy(notificationsEnabled = on) } },
                label = stringResource(R.string.channel_care_name),
                accent = NeoAccents.cyan,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_finish), stringResource(R.string.settings_finish_blurb)) {
            Text(
                stringResource(R.string.settings_softness_value, (config.softFinish * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelSlider(
                value = config.softFinish,
                onValueChange = { value -> viewModel.updateConfig { it.copy(softFinish = value) } },
                label = stringResource(R.string.settings_softness),
                valueLabel = stringResource(R.string.cd_percent_value, (config.softFinish * 100).roundToInt()),
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
                stringResource(R.string.settings_softness_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(3)))
            Text(
                stringResource(R.string.settings_atmosphere_value, (config.atmosphere * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelSlider(
                value = config.atmosphere,
                onValueChange = { value -> viewModel.updateConfig { it.copy(atmosphere = value) } },
                label = stringResource(R.string.settings_atmosphere),
                valueLabel = stringResource(R.string.cd_percent_value, (config.atmosphere * 100).roundToInt()),
                valueRange = 0f..1f,
                notches = 20,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                stringResource(R.string.settings_atmosphere_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_pace), stringResource(R.string.settings_pace_blurb)) {
            val lifetimeHours = Simulation.expectedLifetimeSeconds(config) / 3600f
            Text(
                stringResource(R.string.settings_lifetime, lifetimeHours),
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
                    R.string.settings_pace_slow to 0.5f,
                    R.string.settings_pace_normal to 1f,
                    R.string.settings_pace_fast to 3f,
                    R.string.settings_pace_demo to 12f,
                ).forEach { (labelRes, speed) ->
                    PixelChip(
                        label = stringResource(labelRes),
                        selected = kotlin.math.abs(config.lifeSpeed - speed) < 0.01f,
                        onClick = { viewModel.updateConfig { it.copy(lifeSpeed = speed) } },
                        accent = NeoAccents.cyan,
                    )
                }
            }
            Spacer(Modifier.height(pixelUnits(3)))
            Text(
                stringResource(R.string.settings_clock, PetClock.minutesOf(config)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelSlider(
                value = PetClock.minutesOf(config).toFloat(),
                onValueChange = { minutes ->
                    viewModel.updateConfig { PetClock.withMinutes(it, minutes) }
                },
                label = stringResource(R.string.settings_minutes_per_day),
                valueLabel = stringResource(R.string.cd_minutes_value, PetClock.minutesOf(config)),
                // Range and stops come from the domain, which has a test tying the top of the
                // range to GameConfig's own default. They were literals here, and they did not
                // include it: the control opened pinned to its maximum, two hours short of the
                // truth, and the first touch anywhere on it cut the day by a third.
                valueRange = PetClock.MIN_MINUTES_PER_DAY.toFloat()..PetClock.MAX_MINUTES_PER_DAY.toFloat(),
                notches = PetClock.NOTCHES,
                accent = NeoAccents.cyan,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                stringResource(
                    R.string.settings_clock_hint,
                    (config.offlineDecayMultiplier * 100).toInt(),
                    config.maxOfflineSeconds / 3600,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_help), stringResource(R.string.settings_help_blurb)) {
            PixelButton(
                onClick = {
                    viewModel.updateConfig { it.copy(tutorialSeen = false) }
                    tipsReplayed = true
                },
                accent = NeoAccents.cyan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_replay_tips), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            if (tipsReplayed) {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    stringResource(R.string.settings_replay_tips_done),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoAccents.cyan,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_save_data), stringResource(R.string.settings_save_data_blurb)) {
            Row(horizontalArrangement = Arrangement.spacedBy(pixelUnits(2))) {
                PixelButton(
                    onClick = {
                        scope.launch {
                            clipboard.setText(AnnotatedString(viewModel.exportSave()))
                            importResult = savedToClipboard
                        }
                    },
                    accent = NeoAccents.cyan,
                ) {
                    Text(stringResource(R.string.settings_export), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
                PixelButton(
                    onClick = {
                        viewModel.importSave(importText) { ok ->
                            importResult = if (ok) saveImported else saveUnreadable
                        }
                    },
                    accent = NeoAccents.cyan,
                    enabled = importText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.settings_import), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
            Spacer(Modifier.height(pixelUnits(2)))
            PixelTextWell(
                value = importText,
                onValueChange = { importText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.settings_paste_save),
                minLines = 2,
                maxLines = 4,
                accent = NeoAccents.cyan,
            )
            importResult?.let {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(it, style = MaterialTheme.typography.labelSmall, color = NeoAccents.cyan)
            }
        }

        // Its own file: the only section of this screen that reads a download running somewhere
        // else, and the only one whose numbers are gigabytes of somebody's phone.
        Spacer(Modifier.height(pixelUnits(3)))
        ModelStoragePanel()

        Spacer(Modifier.height(pixelUnits(3)))
        // Directly above the danger zone on purpose: it is the other thing on this screen that
        // can end with the save gone, if a build turns out to be signed with a different key.
        SettingsPanel(stringResource(R.string.settings_updates), stringResource(R.string.settings_updates_blurb)) {
            PixelButton(
                onClick = onOpenUpdates,
                accent = NeoAccents.cyan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.settings_check_updates),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                stringResource(R.string.settings_updates_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(pixelUnits(3)))
        SettingsPanel(stringResource(R.string.settings_danger), stringResource(R.string.settings_danger_blurb), accent = MaterialTheme.colorScheme.error) {
            PixelButton(
                onClick = { confirmReset = true },
                accent = MaterialTheme.colorScheme.error,
                fill = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.error, 0.18f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.settings_delete_save),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(5)))
        Text(
            stringResource(R.string.settings_colophon),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(pixelUnits(6)))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetToNewGame()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
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
