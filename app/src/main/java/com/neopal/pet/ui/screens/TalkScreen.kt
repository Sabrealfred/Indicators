package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.data.EarsState
import com.neopal.pet.data.Heard
import com.neopal.pet.data.OnDeviceState
import com.neopal.pet.data.SpeakerState
import com.neopal.pet.domain.ChatTurn
import com.neopal.pet.domain.CreatureVoice
import com.neopal.pet.domain.MicOffer
import com.neopal.pet.domain.MicPermission
import com.neopal.pet.domain.MicSurface
import com.neopal.pet.domain.MindConfig
import com.neopal.pet.domain.MindRoute
import com.neopal.pet.domain.MindWiring
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.TalkBlock
import com.neopal.pet.domain.TalkVoice
import com.neopal.pet.domain.VoiceComposer
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.MicButton
import com.neopal.pet.ui.components.MinTouchTarget
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.PixelTextWell
import com.neopal.pet.ui.components.VoiceNote
import com.neopal.pet.ui.components.bevelSafePadding
import com.neopal.pet.ui.components.heardNote
import com.neopal.pet.ui.components.onDeviceNote
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.rememberCreatureEars
import com.neopal.pet.ui.components.rememberCreatureSpeaker
import com.neopal.pet.ui.components.rememberMicAsking
import com.neopal.pet.ui.components.rememberOnDeviceMind
import com.neopal.pet.ui.components.rememberRecogniserPresent
import com.neopal.pet.ui.components.speakerNote

/**
 * Longest thing the player can say in one go.
 *
 * The view model trims to its own cap on the way in, so this is not the guard — it is the reason
 * the player never runs into the guard. A field that quietly swallows the end of a sentence is
 * worse than one that stops taking letters while you can still see why.
 */
private const val MaxMessageChars = 400

/** How close to [MaxMessageChars] the counter has to be before it is worth showing. */
private const val CounterShowsWithin = 80

/** Why the composer is not on screen — or [TalkGate.READY], which is the only state that has one. */
private enum class TalkGate {
    READY,
    GONE,
    EGG,
    NO_BRAIN,
    TALK_OFF,
    ASLEEP,
}

/**
 * What the screen can honestly offer right now.
 *
 * The order is the order the sentences would be said in. A creature that has not hatched cannot
 * answer whatever else is true, so that comes before anything about brains; the missing brain
 * comes before the nap, because telling a player their pet is asleep when nothing would have
 * answered anyway sends them off to switch the lights on for nothing.
 *
 * The brain half is [MindWiring.talkBlock]'s and is read off the [route] rather than off the
 * settings, which is the change a second brain forced. "Is there a key pasted in" stopped being
 * the same question as "can this creature answer me" the moment a model could be sitting loaded
 * on the phone with no account anywhere — and answering the second question with the first told
 * a player holding a working brain to go and find a key.
 */
private fun gateFor(pet: PetState, mind: MindConfig, route: MindRoute): TalkGate = when {
    pet.isDead -> TalkGate.GONE
    pet.isEgg -> TalkGate.EGG
    else -> when (MindWiring.talkBlock(mind, route)) {
        TalkBlock.NO_BRAIN -> TalkGate.NO_BRAIN
        TalkBlock.TALK_OFF -> TalkGate.TALK_OFF
        null -> if (pet.isSleeping) TalkGate.ASLEEP else TalkGate.READY
    }
}

/**
 * Talking to the creature.
 *
 * Everything else in the game is the player doing something *to* the pet — feeding it, cleaning
 * it, teaching it. This is the one screen where it answers back, so the whole layout is built
 * around the log rather than around the controls: the conversation owns the height, the composer
 * is a strip at the bottom, and the chrome above is two lines.
 *
 * Most players will arrive here with nothing connected, and that state is not an error — it is
 * the default the game ships in and is perfectly playable. So it gets a plain explanation and a
 * way to Settings, and no input box: a field that takes a sentence and drops it teaches the
 * player that the screen is broken.
 *
 * [onOpenSettings] is optional so the screen can be dropped into navigation with the same
 * one-lambda shape as its neighbours; without it the panel says where to go instead of offering
 * to take you there.
 */
@Composable
fun TalkScreen(
    viewModel: PetViewModel,
    onOpenSettings: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val mind = ui.config.mind

    // ---- the brain on this phone ----------------------------------------------------------
    //
    // Built here and nowhere else, and given back when this screen goes. That is the whole
    // lifetime argument: loading costs seconds so it cannot happen per message, and a resident
    // multi-gigabyte engine is heat and battery so it cannot stay. The view model borrows it for
    // as long as this composition lives and holds nothing afterwards — an engine owned by
    // something that outlives the screen is an engine warm behind a backgrounded game.
    //
    // Nothing is loaded for a player who has not downloaded a model: the client reads the config
    // when the lifecycle starts it, finds nothing on the disk, and stays idle.
    val localMind = rememberOnDeviceMind { ui.config.localMind }
    val localState: OnDeviceState by localMind.state.collectAsState()
    DisposableEffect(viewModel, localMind) {
        viewModel.attachOnDeviceMind(localMind)
        onDispose { viewModel.attachOnDeviceMind(null) }
    }

    // Recomputed on every recomposition on purpose, and `localState` above is what makes those
    // happen: the route changes the moment the engine finishes loading, and a composer that
    // decided once at open would still be saying there was nowhere to think.
    val route = viewModel.talkRoute()
    val gate = gateFor(pet, mind, route)

    // Read once per composition. The flag lives in the view model rather than in a state object,
    // so this recomposes when the conversation itself changes — which is exactly when it flips,
    // since a reply landing and a request finishing are the same moment.
    val thinking = viewModel.isThinking() && gate == TalkGate.READY

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // ---- the voice layer -----------------------------------------------------------------
    //
    // All four of these are inert until used. The speaker builds no engine until a line is
    // actually spoken, the ears open no microphone until pressed, the permission is not asked
    // for by any of them, and every decision about what appears on screen is taken by
    // [MicSurface] and [TalkVoice], which are pure Kotlin with a test suite behind them.
    val voice = ui.config.voice
    val speaker = rememberCreatureSpeaker()
    val ears = rememberCreatureEars()
    val asking = rememberMicAsking(ears)
    val recogniserPresent = rememberRecogniserPresent()
    val earsState: EarsState by ears.state.collectAsState()
    val speakerState: SpeakerState by speaker.state.collectAsState()

    /** The last thing the microphone came back with that was not words. Cleared by the next try. */
    var heard: String? by remember { mutableStateOf<String?>(null) }

    /** True between pressing a microphone that has never been granted and the player's answer. */
    var waitingOnPermission: Boolean by remember { mutableStateOf(false) }

    // What this creature sounds like. Recomputed only when the creature changes, not on every
    // recomposition of a screen that recomposes once a second with the clock.
    val ownVoice: CreatureVoice = remember(pet.stage, pet.personality, pet.genome, pet.isSick) {
        TalkVoice.voiceOf(pet)
    }

    val newest = pet.chat.lastOrNull()
    // The thinking line is the last row while it is up, so it counts towards where the log ends.
    val tail = pet.chat.size + if (thinking) 1 else 0
    LaunchedEffect(tail) {
        if (tail > 0) listState.animateScrollToItem(tail - 1)
    }

    val canSend = gate == TalkGate.READY && !thinking && draft.isNotBlank()

    val micSurface = MicSurface.of(
        listens = voice.listens,
        recogniserPresent = recogniserPresent,
        permission = asking.status,
        listening = earsState.listening,
        ready = gate == TalkGate.READY,
    )

    /**
     * Opens the microphone and puts whatever comes back into the composer.
     *
     * Never sends. A recogniser mishears constantly, and the difference between a feature that
     * mishears and one that is broken is entirely whether the player saw the sentence first.
     */
    fun listenNow() {
        heard = null
        ears.listen(voice.preferOnDevice) { outcome ->
            heard = heardNote(outcome)
            if (outcome is Heard.Words) {
                draft = VoiceComposer.blend(draft, outcome.text, MaxMessageChars)
            }
        }
    }

    // The creature's newest reply, read out once — and only when the player asked for that. The
    // key is seeded from whatever was already on screen when this screen opened, so walking back
    // into the room does not make it repeat its last answer.
    val newestReplyKey = TalkVoice.newestReplyKey(pet.chat)
    var alreadySaid: String? by remember { mutableStateOf(newestReplyKey) }
    LaunchedEffect(newestReplyKey, voice) {
        val line = TalkVoice.replyToSpeak(pet.chat, voice, alreadySaid)
        // Marked as said whether or not it was spoken: a line the player read on screen with the
        // voice off is not one they want read to them the moment they switch the voice on.
        if (newestReplyKey != null) alreadySaid = newestReplyKey
        if (line != null) speaker.say(line, ownVoice, voice.volumeScale)
    }

    // The permission answer arrives long after the press that caused it. Carrying on into the
    // microphone is the whole reason the player pressed it; a grant that leaves them looking at
    // the same button, having to press it again, reads as the press not having worked.
    LaunchedEffect(asking.status) {
        if (!waitingOnPermission) return@LaunchedEffect
        when (asking.status) {
            MicPermission.GRANTED -> {
                waitingOnPermission = false
                listenNow()
            }
            MicPermission.REFUSED -> waitingOnPermission = false
            MicPermission.UNASKED -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The app draws edge to edge; without this the back button sits under the status bar.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // safeDrawing already carries the keyboard and consumes it for everything after it in
            // the chain, so this adds nothing while that holds — and keeps the composer reachable
            // if the screen is ever hung under insets that do not include the IME.
            .imePadding()
            .padding(horizontal = pixelUnits(3)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "TALK",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${pet.name} · ${mind.routeLabel.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))

        ConversationHeader(
            remembered = pet.chat.size,
            onClear = viewModel::clearChat,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(pixelUnits(2)))

        // The log takes whatever height is left, so the composer stays pinned to the bottom edge
        // and the newest line sits directly above the field the player is typing in.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (pet.chat.isEmpty() && !thinking) {
                item {
                    OpeningPanel(
                        name = pet.name,
                        gate = gate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(pet.chat) { turn: ChatTurn ->
                ChatBubble(
                    turn = turn,
                    petName = pet.name,
                    elapsed = sinceLabel(pet.ageSeconds - turn.atSeconds),
                    // Only the creature's newest line is announced. Announcing the player's own
                    // line reads their message back at them the instant they send it.
                    announce = turn === newest && turn.fromPet,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(pixelUnits(2)))
            }
            if (thinking) {
                item {
                    ThinkingLine(name = pet.name, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(pixelUnits(2)))
                }
            }
        }

        Spacer(Modifier.height(pixelUnits(2)))
        if (gate == TalkGate.READY) {
            VoiceStrip(
                surface = micSurface,
                level = if (earsState.listening) earsState.level else null,
                mouthNote = speakerNote(speakerState, voice.speaks),
                brainNote = onDeviceNote(localState),
                lastHeard = heard,
                onMic = {
                    when (micSurface.offer) {
                        MicOffer.ASK -> {
                            waitingOnPermission = true
                            heard = null
                            asking.ask()
                        }
                        MicOffer.LISTEN -> listenNow()
                        // Giving up is silent. The player asked for the microphone to close and
                        // does not need to be told that it did.
                        MicOffer.STOP -> ears.cancel()
                        MicOffer.HIDDEN -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Composer(
                draft = draft,
                onDraftChange = { draft = it.take(MaxMessageChars) },
                name = pet.name,
                thinking = thinking,
                canSend = canSend,
                onSend = {
                    viewModel.say(draft)
                    draft = ""
                    // Whatever the microphone last failed to catch is history the moment a
                    // message goes; leaving it up puts "I did not hear anything" over a
                    // sentence that was plainly heard.
                    heard = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ClosedPanel(
                gate = gate,
                name = pet.name,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(pixelUnits(3)))
    }
}

/**
 * The strip over the log: what the creature is holding on to, and the one way to let it go.
 *
 * The count is the point of putting it here. "Forget" on its own is a button nobody presses
 * because nobody knows what it costs; with the number beside it, it is a decision.
 */
@Composable
private fun ConversationHeader(
    remembered: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cap = Simulation.MAX_CHAT_TURNS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.heightIn(min = MinTouchTarget),
    ) {
        Text(
            text = "Conversation",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.width(pixelUnits(2)))
        PixelBadge(
            text = "$remembered",
            color = MaterialTheme.colorScheme.surface,
            contentDescription = "$remembered of $cap lines remembered",
        )
        Spacer(Modifier.weight(1f))
        PixelButton(
            onClick = onClear,
            enabled = remembered > 0,
            accent = MaterialTheme.colorScheme.onSurfaceVariant,
            contentPadding = PaddingValues(horizontal = pixelUnits(2), vertical = pixelUnits(1)),
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = if (remembered > 0) {
                    "Forget the conversation. $remembered of $cap lines remembered."
                } else {
                    "Nothing to forget. The conversation is empty."
                }
            },
        ) {
            Text(
                text = "FORGET",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * One line of the conversation.
 *
 * The two speakers differ in three ways at once — which side of the screen, which accent, and
 * which name sits above the words — because a chat log distinguished by colour alone is a chat
 * log a colour-blind player reads as one long monologue.
 */
@Composable
private fun ChatBubble(
    turn: ChatTurn,
    petName: String,
    elapsed: String,
    announce: Boolean,
    modifier: Modifier = Modifier,
) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background
    val accent = if (turn.fromPet) NeoAccents.cyan else NeoAccents.gold
    val speaker = if (turn.fromPet) petName else "You"
    val readOut = "$speaker said: ${turn.text}. $elapsed."

    Box(
        modifier = modifier,
        contentAlignment = if (turn.fromPet) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                // Never the full width: the gap on the far side is what makes the side it sits on
                // legible at a glance, and it leaves the timestamp somewhere to go.
                .fillMaxWidth(0.88f)
                .pixelSurface(
                    fill = lerp(panel, accent, if (turn.fromPet) 0.16f else 0.10f),
                    accent = accent,
                    bevel = PixelBevel.RAISED,
                    borderUnits = 1,
                    background = background,
                )
                .padding(bevelSafePadding(1))
                .semantics(mergeDescendants = true) {
                    if (announce) liveRegion = LiveRegionMode.Polite
                    contentDescription = readOut
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = speaker.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(pixelUnits(1)))
            // No line cap and no ellipsis: a reply cut short is a reply the player has to guess
            // at, and the log is the only place these sentences ever appear.
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The pause between the question and the answer, in words.
 *
 * A bare spinner says the app is busy. This has to say the *creature* is busy — and it is the one
 * thing on the screen that appears without the player touching anything, so it is announced.
 */
@Composable
private fun ThinkingLine(name: String, modifier: Modifier = Modifier) {
    val accent = NeoAccents.cyan
    val readOut = "$name is thinking."

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = readOut
            },
    ) {
        Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(pixelUnits(6)),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Text(
            text = "$name is turning it over…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What the log says before there is a log.
 *
 * Only shown when the conversation is empty, and it changes with the gate: with a brain connected
 * it is an invitation, and without one it stays quiet, because the panel under the log is already
 * saying the useful thing and saying it twice reads as nagging.
 */
@Composable
private fun OpeningPanel(name: String, gate: TalkGate, modifier: Modifier = Modifier) {
    val body = if (gate == TalkGate.READY) {
        "Ask $name anything. It answers as itself — it knows how it feels, what it has been " +
            "doing and who is about, and it will tell you in its own words."
    } else {
        "Nothing has been said yet. Whatever you two end up saying will be kept here."
    }

    PixelPanel(
        modifier = modifier,
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
        title = "Nothing said yet",
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The row above the composer: the microphone, and the truth about it.
 *
 * It disappears completely when there is nothing to press and nothing to admit, which is the
 * shipped default — the voice is off in both directions until a player switches it on, and a
 * talking screen that permanently reserves a strip for a feature nobody enabled is a talking
 * screen with less room for the conversation.
 *
 * Four separate sentences can land here and they are not interchangeable. [mouthNote] is about
 * the creature's voice failing, [brainNote] is about the brain on this phone waking up or
 * refusing to, [surface]'s own note is about the microphone not being on offer, and [lastHeard]
 * is about the last attempt at listening. Collapsing them into one line would mean a device with
 * no text-to-speech quietly stops explaining itself the moment something else goes wrong.
 *
 * [brainNote] sits last of the three that are about the app rather than about the player's last
 * press, and it is usually absent: a loaded brain says nothing, and so does a player who never
 * downloaded one. It speaks up for the ten seconds a load takes — where silence would read as a
 * feature that does not work — and when it will not run at all, where the sentence is the only
 * thing standing between a player and a download they have no use for.
 */
@Composable
private fun VoiceStrip(
    surface: MicSurface,
    level: Float?,
    mouthNote: String?,
    brainNote: String?,
    lastHeard: String?,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes = listOfNotNull(surface.note, mouthNote, brainNote, lastHeard)
    if (!surface.actionable && notes.isEmpty()) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.heightIn(min = MinTouchTarget),
    ) {
        if (surface.actionable) {
            MicButton(surface = surface, onPress = onMic)
            Spacer(Modifier.width(pixelUnits(2)))
        }
        Column(modifier = Modifier.weight(1f)) {
            notes.forEachIndexed { index, note ->
                if (index > 0) Spacer(Modifier.height(pixelUnits(1)))
                VoiceNote(
                    text = note,
                    // The level rides on the first note, which while listening is the one that
                    // says to go ahead and speak. A meter under a sentence about a missing
                    // recogniser would be a meter for a microphone that is not open.
                    level = if (index == 0) level else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    Spacer(Modifier.height(pixelUnits(2)))
}

/**
 * The field and the button, and nothing else.
 *
 * Send is out of reach for a blank message and for one sent while an answer is still coming: the
 * view model drops both, and a button that looks armed and does nothing is how a player learns to
 * stop trusting the screen. The counter only appears near the ceiling, where it is information
 * rather than pressure.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    name: String,
    thinking: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (canSend) NeoAccents.green else MaterialTheme.colorScheme.onSurfaceVariant
    val left = MaxMessageChars - draft.length
    val sendReadOut = when {
        thinking -> "Send. Waiting — $name is still thinking."
        draft.isBlank() -> "Send. Nothing written yet."
        else -> "Send to $name."
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            PixelTextWell(
                value = draft,
                onValueChange = onDraftChange,
                label = "Message to $name",
                placeholder = "Say something",
                // Left usable while a reply is coming: typing the next thing you want to say is
                // not the same as sending it, and only the button is barred.
                minLines = 1,
                // A few lines rather than one: most of what a player types to a pet is a sentence,
                // and a sentence scrolling sideways inside a one-line well is unreadable.
                maxLines = 4,
                accent = NeoAccents.cyan,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            PixelButton(
                onClick = onSend,
                enabled = canSend,
                accent = accent,
                fill = if (canSend) lerp(panel, NeoAccents.green, 0.22f) else panel,
                contentPadding = PaddingValues(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = sendReadOut
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(pixelUnits(6)),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                Text(
                    text = "SEND",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        if (left <= CounterShowsWithin) {
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                text = if (left > 0) "$left ${letterWord(left)} left" else "That is as much as it can hold",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The panel that stands in for the composer when nobody can answer.
 *
 * Each case gets its own sentence rather than one shared "unavailable", because they are fixed in
 * completely different places — one in Settings, one by the light switch, and two only by waiting.
 */
@Composable
private fun ClosedPanel(
    gate: TalkGate,
    name: String,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val settings = gate == TalkGate.NO_BRAIN || gate == TalkGate.TALK_OFF

    val headline = when (gate) {
        TalkGate.GONE -> "Nobody left to answer"
        TalkGate.EGG -> "Still an egg"
        TalkGate.NO_BRAIN -> "No brain connected"
        TalkGate.TALK_OFF -> "Talking is switched off"
        TalkGate.ASLEEP -> "$name is asleep"
        TalkGate.READY -> ""
    }
    val body = when (gate) {
        TalkGate.GONE ->
            "$name is no longer with us. Whatever was said is still here to read, and it stays " +
                "here — the next generation starts its own conversation."
        TalkGate.EGG ->
            "$name has not hatched. There is nobody in there to talk to yet; it will come out on " +
                "its own, and this is where you will hear from it first."
        TalkGate.NO_BRAIN ->
            "$name can only hold a conversation with a brain connected, and there is not one yet. " +
                "You can set that up in Settings. Nothing else about the creature depends on it — " +
                "it eats, sleeps and makes its own decisions either way."
        TalkGate.TALK_OFF ->
            "A brain is connected, but conversation is turned off for it. Switch it back on in " +
                "Settings and $name will start answering again."
        TalkGate.ASLEEP ->
            "$name is asleep and will not answer while it is. Rest is doing it more good than " +
                "conversation would; the lights are on the home screen if it truly cannot wait."
        TalkGate.READY -> ""
    }

    PixelPanel(
        modifier = modifier,
        accent = muted,
        title = "Not talking",
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = "$headline. $body" },
        ) {
            Icon(
                imageVector = if (settings) Icons.Filled.Settings else Icons.Filled.Psychology,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(pixelUnits(8)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Only offered where it would actually help, and only when navigation handed us a way
        // there. Everything else on this list is fixed by waiting, and a button that walks the
        // player to a screen with no answer on it is worse than no button.
        if (settings && onOpenSettings != null) {
            Spacer(Modifier.height(pixelUnits(2)))
            PixelButton(
                onClick = onOpenSettings,
                accent = NeoAccents.cyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Open Settings to connect a brain."
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = NeoAccents.cyan,
                    modifier = Modifier.size(pixelUnits(6)),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                Text(
                    text = "OPEN SETTINGS",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun letterWord(n: Int): String = if (n == 1) "character" else "characters"

/**
 * How long ago a line was said, in pet time.
 *
 * Coarse on purpose, and measured against the creature's own age rather than the wall clock:
 * a log that reads "13 seconds ago" rewrites every one of its rows on every tick, and none of
 * those rewrites tell the player anything they did not already know.
 */
private fun sinceLabel(seconds: Long): String {
    val past = seconds.coerceAtLeast(0L)
    val hours = past / 3600L
    val minutes = (past % 3600L) / 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ago"
        minutes > 0L -> "${minutes}m ago"
        else -> "just now"
    }
}
