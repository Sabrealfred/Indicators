package com.neopal.pet.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.neopal.pet.data.CreatureEars
import com.neopal.pet.data.CreatureSpeaker
import com.neopal.pet.data.Heard
import com.neopal.pet.data.SpeakerState
import com.neopal.pet.domain.MicOffer
import com.neopal.pet.domain.MicPermission
import com.neopal.pet.domain.MicSurface

/**
 * The Compose end of the voice layer: who owns the engine, who owns the microphone, and how the
 * two of them are drawn in the kit's language.
 *
 * Everything here is plumbing. Not one decision about *what* the player sees is taken in this
 * file — that is [MicSurface] and [com.neopal.pet.domain.TalkVoice], which are pure Kotlin and
 * have a test suite. What is left over is the part that genuinely needs Android: building an
 * engine, tying it to a lifecycle, and asking for a permission at the moment a finger lands on a
 * button.
 *
 * None of this has been compiled against a real SDK, and none of it has been heard. The two
 * things that can be got wrong here without a device are ownership and lifetime — an engine that
 * outlives its screen, a recogniser still open behind a backgrounded app — so both are handled in
 * one place, in a [DisposableEffect] next to the object it owns.
 */

/**
 * A speech engine that belongs to this screen and dies with it.
 *
 * Built with the *application* context, as [CreatureSpeaker] requires: an engine holding an
 * Activity is an Activity that cannot be collected. It is also registered against the screen's
 * lifecycle, so speech stops when the app goes away, and [CreatureSpeaker.close] on dispose is
 * what stops a backgrounded game from holding a bound speech service.
 *
 * Nothing is built until the creature first opens its mouth — the constructor is deliberately
 * lazy — so a player who never turns the voice on pays nothing for this call sitting in the
 * composition.
 */
@Composable
fun rememberCreatureSpeaker(): CreatureSpeaker {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val speaker = remember(context) { CreatureSpeaker(context) }
    DisposableEffect(speaker, owner) {
        owner.lifecycle.addObserver(speaker)
        onDispose {
            owner.lifecycle.removeObserver(speaker)
            speaker.close()
        }
    }
    return speaker
}

/**
 * A recogniser that belongs to this screen and dies with it.
 *
 * The disposal matters more here than for the speaker: a recogniser left alive is a microphone
 * left open, and the one thing a player must be able to trust about a listening feature is that
 * it stops when they leave.
 */
@Composable
fun rememberCreatureEars(): CreatureEars {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val ears = remember(context) { CreatureEars(context) }
    DisposableEffect(ears, owner) {
        owner.lifecycle.addObserver(ears)
        onDispose {
            owner.lifecycle.removeObserver(ears)
            ears.close()
        }
    }
    return ears
}

/**
 * What this screen knows about the microphone permission, and the one way to ask for it.
 *
 * Held as an object rather than a pair of values so that the asking and the answer cannot drift
 * apart: there is exactly one place that changes [status], and it is the system's own callback.
 */
@Stable
class MicAsking internal constructor(
    private val state: MutableState<MicPermission>,
    private val requester: () -> Unit,
) {
    /** Granted, refused, or never asked. Read during composition, so the screen follows it. */
    val status: MicPermission get() = state.value

    /**
     * Shows the system prompt.
     *
     * Only ever called from a press. That is the whole permission policy: the manifest declares
     * `RECORD_AUDIO`, and nothing in this app asks for it until a finger lands on a microphone.
     */
    fun ask() {
        requester()
    }
}

/**
 * The permission, watched.
 *
 * Re-checked on every `ON_START` rather than only at first composition, because the interesting
 * case is the player who refuses, walks to the system settings, grants it there and comes back:
 * without the re-check, this screen would go on believing it had been told no.
 *
 * A refusal is remembered only for as long as the screen lives. It does not need to survive
 * longer — [MicSurface] uses it to stop re-offering a button in the same sitting, and a player
 * who comes back tomorrow having changed their mind should find the offer waiting.
 */
@Composable
fun rememberMicAsking(ears: CreatureEars): MicAsking {
    val status = remember(ears) {
        mutableStateOf(if (ears.hasPermission()) MicPermission.GRANTED else MicPermission.UNASKED)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status.value = if (granted) MicPermission.GRANTED else MicPermission.REFUSED
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, ears) {
        // The same shape [CreatureSpeaker] and [CreatureEars] already use, rather than an event
        // observer: one fewer platform interface in a file that cannot be compiled here.
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (ears.hasPermission()) status.value = MicPermission.GRANTED
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(status, launcher) {
        MicAsking(status) { launcher.launch(CreatureEars.PERMISSION) }
    }
}

/**
 * True when this handset could recognise speech at all.
 *
 * Asked once per screen and remembered: it cannot change while the screen is up, and the check
 * goes out to the package manager. Asked *before* the permission ever is, because a prompt for a
 * microphone that leads nowhere is a prompt that should never have been shown.
 */
@Composable
fun rememberRecogniserPresent(): Boolean {
    val context = LocalContext.current.applicationContext
    return remember(context) { CreatureEars.availableOn(context) }
}

/**
 * The sentence to show about the creature's own mouth, or null when there is nothing to say.
 *
 * Only [SpeakerState.Unavailable] produces one. Starting and Ready are not news, and Idle is the
 * state a player who never switched the voice on stays in forever — announcing it would be the
 * app explaining a feature nobody asked for.
 */
fun speakerNote(state: SpeakerState, speaks: Boolean): String? = when {
    !speaks -> null
    state is SpeakerState.Unavailable -> state.reason
    else -> null
}

/**
 * The sentence to show about something that was heard, or not heard.
 *
 * Words are not a note — they go into the composer where the player can see and edit them, which
 * is the entire reason dictation is trustworthy here. Everything else is an honest sentence that
 * [Heard] already wrote.
 */
fun heardNote(heard: Heard): String? = if (heard is Heard.Words) null else heard.message

/**
 * The microphone button.
 *
 * Drawn only when [MicSurface] says there is something to press. There is no disabled state on
 * purpose: a greyed-out microphone is a question the player cannot get an answer to, and every
 * reason for not offering one already has a sentence of its own beside it.
 */
@Composable
fun MicButton(
    surface: MicSurface,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!surface.actionable) return
    val open = surface.offer == MicOffer.STOP
    val accent = if (open) NeoAccents.green else NeoAccents.cyan
    val panel = MaterialTheme.colorScheme.surfaceVariant

    PixelButton(
        onClick = onPress,
        accent = accent,
        fill = if (open) lerp(panel, NeoAccents.green, 0.22f) else panel,
        contentPadding = PaddingValues(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = surface.readOut
        },
    ) {
        Icon(
            // The icon changes with the state, not only the colour: an open microphone and a
            // closed one told apart by hue alone are the same picture to a colour-blind player.
            imageVector = if (open) Icons.Filled.GraphicEq else Icons.Filled.Mic,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(pixelUnits(6)),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Text(
            text = surface.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A plain sentence about the voice layer, with the input level beside it while listening.
 *
 * The meter is the only part of this feature that proves the microphone is actually open. Without
 * it, a recogniser that hears nothing and a recogniser that is broken look exactly alike, and the
 * player has no way to tell that speaking louder is the answer.
 */
@Composable
fun VoiceNote(
    text: String,
    modifier: Modifier = Modifier,
    level: Float? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        if (level != null) {
            Spacer(Modifier.height(pixelUnits(1)))
            PixelBar(
                fraction = level.coerceIn(0f, 1f),
                color = NeoAccents.green,
                segments = 12,
                height = pixelUnits(3),
            )
        }
    }
}
