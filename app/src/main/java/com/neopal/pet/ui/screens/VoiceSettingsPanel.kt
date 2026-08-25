package com.neopal.pet.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.neopal.pet.R
import com.neopal.pet.data.SpeakerState
import com.neopal.pet.domain.CreatureVoice
import com.neopal.pet.domain.MicPermission
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.TalkVoice
import com.neopal.pet.domain.Utterance
import com.neopal.pet.domain.VoiceChannel
import com.neopal.pet.domain.VoiceConfig
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.PixelSlider
import com.neopal.pet.ui.components.PixelToggle
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.rememberCreatureEars
import com.neopal.pet.ui.components.rememberCreatureSpeaker
import com.neopal.pet.ui.components.rememberMicAsking
import com.neopal.pet.ui.components.rememberRecogniserPresent
import com.neopal.pet.ui.components.speakerNote
import kotlin.math.roundToInt

/**
 * The voice section of Settings, kept in its own file.
 *
 * Its own file because the settings screen is the busiest shared surface in the project and this
 * panel is the only part of it that needs a speech engine in the composition. Everything the
 * panel knows how to do is here; the screen that hosts it gains four lines.
 *
 * Two things are deliberately *not* done here. It never asks for the microphone — switching
 * listening on is a statement of taste, not a grant, and the permission is asked for at the
 * moment a finger lands on the microphone and at no other time. And it never claims a device can
 * do something it cannot: a handset with no engine and one with no recogniser each say so in a
 * sentence, in the place where the switch for it lives.
 */
@Composable
fun VoiceSettingsPanel(
    pet: PetState?,
    config: VoiceConfig,
    onChange: (VoiceConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val speaker = rememberCreatureSpeaker()
    val ears = rememberCreatureEars()
    val asking = rememberMicAsking(ears)
    val recogniserPresent = rememberRecogniserPresent()
    val speakerState: SpeakerState by speaker.state.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Every press is a new line as far as the engine is concerned. Without this the second press
    // of the preview button is silent — the speaker drops a repeat of the line it last spoke,
    // which is right for a diary entry arriving twice and wrong for a button labelled "hear it".
    var previews: Int by remember { mutableStateOf(0) }

    val ownVoice: CreatureVoice? = remember(pet?.stage, pet?.personality, pet?.genome, pet?.isSick) {
        pet?.let { TalkVoice.voiceOf(it) }
    }

    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = NeoAccents.gold,
        title = stringResource(R.string.voice_title),
        contentPadding = PaddingValues(pixelUnits(3)),
    ) {
        Text(
            stringResource(R.string.voice_blurb),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Spacer(Modifier.height(pixelUnits(2)))

        // ---- the creature speaking ---------------------------------------------------------

        PixelToggle(
            checked = config.speaks,
            onCheckedChange = { on -> onChange(config.copy(speaks = on)) },
            label = stringResource(R.string.voice_let_it_speak),
            accent = NeoAccents.gold,
        )

        speakerNote(speakerState, config.speaks)?.let { trouble ->
            Spacer(Modifier.height(pixelUnits(2)))
            Text(trouble, style = MaterialTheme.typography.labelSmall, color = muted)
        }

        if (config.speaks) {
            Spacer(Modifier.height(pixelUnits(2)))
            PixelToggle(
                checked = config.readsChat,
                onCheckedChange = { on -> onChange(config.copy(readsChat = on)) },
                label = stringResource(R.string.voice_read_replies),
                accent = NeoAccents.gold,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                stringResource(R.string.voice_read_replies_hint),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )

            Spacer(Modifier.height(pixelUnits(3)))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            ) {
                Text(
                    stringResource(R.string.voice_volume),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.percent_value, (config.volumeScale * 100f).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = NeoAccents.gold,
                )
            }
            PixelSlider(
                value = config.volumeScale,
                onValueChange = { v -> onChange(config.copy(volume = v.coerceIn(0f, 1f))) },
                label = stringResource(R.string.voice_volume),
                valueLabel = stringResource(R.string.cd_percent_value, (config.volumeScale * 100f).roundToInt()),
                valueRange = 0f..1f,
                notches = 20,
                accent = NeoAccents.gold,
            )

            if (pet != null && ownVoice != null) {
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    // The claim this panel is making, in one sentence: the voice belongs to the
                    // creature. A bred line inherits build and muzzle, and those are two of the
                    // four numbers behind this description.
                    stringResource(R.string.voice_descriptor, pet.name, ownVoice.descriptor),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                // `semantics {}` is not a composable scope, so the sentence is read out here and
                // handed in as a value.
                val hearIt = stringResource(R.string.cd_voice_preview, pet.name)
                PixelButton(
                    onClick = {
                        previews += 1
                        val line = Utterance.of(
                            text = CreatureVoice.previewLine(pet.name, pet.stage),
                            channel = VoiceChannel.CHAT,
                            key = "preview:$previews",
                        )
                        if (line != null) speaker.say(line, ownVoice, config.volumeScale)
                    },
                    accent = NeoAccents.gold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = hearIt
                        },
                ) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = null,
                        tint = NeoAccents.gold,
                        modifier = Modifier.size(pixelUnits(6)),
                    )
                    Spacer(Modifier.width(pixelUnits(2)))
                    Text(
                        stringResource(R.string.voice_hear_it),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---- the player speaking -----------------------------------------------------------

        Spacer(Modifier.height(pixelUnits(3)))
        PixelToggle(
            checked = config.listens,
            onCheckedChange = { on -> onChange(config.copy(listens = on)) },
            // Not "microphone on". Nothing here opens one, and a switch that sounds like it does
            // is a switch a player would be right to feel misled by.
            label = stringResource(R.string.voice_let_it_listen),
            accent = NeoAccents.gold,
            enabled = recogniserPresent,
        )
        Spacer(Modifier.height(pixelUnits(1)))
        Text(
            text = stringResource(
                when {
                    !recogniserPresent -> R.string.voice_listen_no_recogniser
                    !config.listens -> R.string.voice_listen_off
                    asking.status == MicPermission.GRANTED -> R.string.voice_listen_granted
                    else -> R.string.voice_listen_unasked
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )

        if (config.listens && recogniserPresent) {
            Spacer(Modifier.height(pixelUnits(2)))
            PixelToggle(
                checked = config.preferOnDevice,
                onCheckedChange = { on -> onChange(config.copy(preferOnDevice = on)) },
                label = stringResource(R.string.voice_on_device),
                accent = NeoAccents.gold,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                stringResource(R.string.voice_on_device_hint),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}
