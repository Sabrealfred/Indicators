package com.neopal.pet.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.neopal.pet.data.OnDeviceMindClient
import com.neopal.pet.data.OnDeviceState
import com.neopal.pet.domain.LocalMindConfig

/**
 * The Compose end of the on-device brain: who owns the engine, and for exactly how long.
 *
 * There is no decision in this file. It is the same twelve lines as [rememberCreatureSpeaker] and
 * [rememberCreatureEars], on purpose — the design says the engine should live the way those two
 * already do, and a third shape for the same problem would be a third thing to get wrong. What it
 * buys is the entire lifecycle argument in one place: an engine that is built when a screen
 * appears and given back when it goes, and never on any other schedule.
 *
 * **Where this belongs, and where it does not.** On the talking screen, which is the one place a
 * player is waiting on an answer. Not on the home screen, not in a `ViewModel` that outlives the
 * composition, and not anywhere the autonomous half can reach — see the file comment on
 * [OnDeviceMindClient] for why that is structural rather than a matter of remembering.
 *
 * Nothing here has been compiled. There is no Android SDK in this project's local harness.
 */

/**
 * An on-device brain that belongs to this screen and dies with it.
 *
 * Built with the *application* context, as [OnDeviceMindClient] requires: it outlives any one
 * composition, and an engine holding an Activity is an Activity that cannot be collected.
 *
 * [config] is read through [rememberUpdatedState] rather than captured, so that a player switching
 * the feature on in settings while this screen is up is seen the next time the lifecycle asks,
 * instead of being remembered as whatever it was when the screen opened.
 *
 * The engine is *not* built by this call. Registering the observer is what does it, and only
 * because a started lifecycle replays `ON_START` immediately — so a screen that is composed but
 * never started never loads a thing.
 */
@Composable
fun rememberOnDeviceMind(config: () -> LocalMindConfig): OnDeviceMindClient {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val latest = rememberUpdatedState(config)
    val client = remember(context) { OnDeviceMindClient(context) { latest.value() } }
    DisposableEffect(client, owner) {
        owner.lifecycle.addObserver(client)
        onDispose {
            owner.lifecycle.removeObserver(client)
            // Permanent, not merely stopped. The screen is gone for good, and an engine of this
            // size is not something to leave behind on the chance the player comes back.
            client.close()
        }
    }
    return client
}

/**
 * The sentence to show about the brain on the handset, or null when there is nothing to say.
 *
 * The same rule [speakerNote] follows: only the states a player can act on produce words.
 * [OnDeviceState.Idle] is where every player who never asked for this stays forever, and
 * announcing it would be the app explaining a feature nobody wanted; [OnDeviceState.Ready] is not
 * news either. Loading is worth a line because it takes seconds and the creature is visibly
 * answering with its smaller brain in the meantime, and a refusal already arrived as a sentence.
 */
fun onDeviceNote(state: OnDeviceState): String? = when (state) {
    OnDeviceState.Idle, OnDeviceState.Ready -> null
    OnDeviceState.Loading -> "Waking the brain on this phone. It answers for itself in a moment."
    is OnDeviceState.Unavailable -> state.reason
}
