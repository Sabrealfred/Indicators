package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.MiniGame
import com.neopal.pet.domain.Personality
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// ------------------------------------------------------------------ the scale both voices share

/**
 * The ladder the duet is sung on. Pentatonic, because it contains no wrong note: a player
 * hammering pads at random still produces a line that sounds intended, which is the only honest
 * footing for a game that never tells anybody they were incorrect.
 */
private val PentatonicSteps = intArrayOf(0, 2, 4, 7, 9)

/**
 * Roughly C4. Degree [TopDegree] lands exactly two octaves above it, and that is not a
 * coincidence: [ChiptuneEngine.play] clamps its pitch multiplier to 0.5..2.0, and [Sfx.BACK] is a
 * single 520 Hz blip, so 260 Hz to 1040 Hz is the whole range the engine can be asked for. The
 * scale was cut to fit the synthesiser rather than the other way round.
 */
private const val RootHz = 260f
private const val VoiceBaseHz = 520f
private const val TopDegree = 10

/** The five degrees the pads offer — G4 A4 C5 D5 E5, a comfortable middle of the ladder. */
private const val PadLowDegree = 3
private const val Pads = 5

/** Where a phrase comes to rest. The middle pad, so home is somewhere the player can go too. */
private const val HomeDegree = 5

private const val Verses = 6
private const val MaxPhrase = 8
private const val MaxReply = 10

/** A breath before the creature starts, and another after it finishes. Nobody answers instantly. */
private const val HandoverPause = 0.45f
private const val SettlePause = 0.7f

/** Note letters for the pads. Spoken in the label rather than drawn as decoration. */
private val PadNames = arrayOf("G", "A", "C", "D", "E")

/** Hoisted because a [Stroke] is a real object and this is used inside a draw lambda. */
private val CreatureOutline = Stroke(width = 2f)

private enum class Singer { YOU, THEM }

private enum class Phase { YOURS, THEIRS, DONE }

/** One note of the song so far. [slots] is how many beats it occupies; only a held note is >1. */
private data class SungNote(val degree: Int, val singer: Singer, val slots: Int)

/**
 * Everything about how one personality sings. None of it is a difficulty knob: a shy creature is
 * not an easier creature, it is a quieter one.
 */
private data class Voice(
    val accent: Color,
    /** Seconds per note. A calm creature is not slowed down to be fair to you; it is unhurried. */
    val slot: Float,
    /** Extra beats on the final note — the visible, countable version of holding it. */
    val holdLast: Int,
    /** How large it is drawn while answering. The nearest thing to volume that we can show. */
    val presence: Float,
    /** Its own timbre, spent once at the end of an answer as a signature. */
    val sign: Sfx,
    val signPitch: Float,
    /** The half-sentence describing its habit, for the spoken read-out of the answer. */
    val habit: String,
)

/** The five ways of answering. Chosen by [Personality] and by nothing else. */
private fun voiceOf(personality: Personality): Voice = when (personality) {
    Personality.PLAYFUL -> Voice(
        accent = NeoColors.NeonYellow,
        slot = 0.20f,
        holdLast = 0,
        presence = 1.05f,
        sign = Sfx.HAPPY,
        signPitch = 1.15f,
        habit = "It cannot resist decorating what you gave it.",
    )
    Personality.SHY -> Voice(
        accent = NeoColors.NeonPurple,
        slot = 0.30f,
        holdLast = 0,
        presence = 0.72f,
        sign = Sfx.SLEEP,
        signPitch = 0.9f,
        habit = "It answers from underneath your line, and stops before you did.",
    )
    Personality.GREEDY -> Voice(
        accent = NeoColors.StatSatiety,
        slot = 0.24f,
        holdLast = 1,
        presence = 1.0f,
        sign = Sfx.COIN,
        signPitch = 1f,
        habit = "It has kept the note it liked best and will not hand it back.",
    )
    Personality.BRAVE -> Voice(
        accent = NeoColors.NeonRed,
        slot = 0.21f,
        holdLast = 2,
        presence = 1.25f,
        sign = Sfx.LEVEL_UP,
        signPitch = 1f,
        habit = "It carries your line up to the top of its range and holds the last note.",
    )
    Personality.CALM -> Voice(
        accent = NeoColors.NeonGreen,
        slot = 0.36f,
        holdLast = 2,
        presence = 0.9f,
        sign = Sfx.CLEAN,
        signPitch = 0.85f,
        habit = "It pares your line back to its bones and settles on home.",
    )
}

// ------------------------------------------------------------------------------- the music itself

/** Degree to frequency, up the pentatonic ladder. */
private fun degreeHz(degree: Int): Float {
    val d = degree.coerceIn(0, TopDegree)
    return RootHz * 2f.pow((12 * (d / 5) + PentatonicSteps[d % 5]) / 12f)
}

/** Sounds one note. Silent when sound is off, which is why every note is also drawn. */
private fun sing(degree: Int, soundOn: Boolean) {
    if (!soundOn) return
    ChiptuneEngine.play(Sfx.BACK, pitch = degreeHz(degree) / VoiceBaseHz)
}

/**
 * Trims an over-long answer from the middle. The opening quote and the final note are the two
 * parts carrying meaning, so a windy answer should lose its waffle rather than its shape.
 */
private fun trimTo(out: MutableList<Int>, cap: Int) {
    while (out.size > cap && out.size >= 2) out.removeAt(out.size - 2)
}

/**
 * The creature's reply, and the heart of the game — so it is worth saying plainly what it is not.
 * It is not a target. There is no phrase the player was supposed to sing and no distance from one
 * being measured. Whatever arrives is transformed, and which transformation runs is decided by
 * [personality] alone.
 *
 * [motif] is the hook the two of you have built so far: one note you opened with and one note it
 * finished on. Quoting that at the top of every answer is what stops six verses feeling like six
 * unrelated exercises.
 */
private fun answerOf(personality: Personality, call: IntArray, motif: IntArray, rng: Random): IntArray {
    if (call.isEmpty()) return intArrayOf(HomeDegree)
    val out = ArrayList<Int>(MaxReply + 8)
    for (d in motif) out += d
    val top = call.maxOrNull() ?: HomeDegree
    val bottom = call.minOrNull() ?: HomeDegree

    when (personality) {
        Personality.PLAYFUL -> {
            // Shows off: your line back, with grace notes wedged in, then a run off the end of it.
            for (d in call) {
                out += d
                if (rng.nextInt(3) == 0) out += d + 1
            }
            out += top + 2
            out += top + 3
            trimTo(out, MaxReply)
        }
        Personality.SHY -> {
            // Only the tail of what you sang, and as far under it as the ladder goes. Shifting by
            // the whole phrase rather than by a fixed interval is what keeps the shape intact:
            // transposing note by note would flatten anything that ran off the bottom.
            val keep = max(1, call.size / 2)
            for (i in call.size - keep until call.size) out += call[i] - bottom
            // Then, whatever else it had in mind, it stops short of the length you offered.
            trimTo(out, max(1, call.size - 1))
        }
        Personality.GREEDY -> {
            // Takes the whole line, then keeps the best note in it for itself.
            for (d in call) out += d
            repeat(3) { out += top }
            trimTo(out, MaxReply)
        }
        Personality.BRAVE -> {
            // Your line, then the end of it again as high as the ladder goes, then the ceiling.
            // Same trick as the shy one, in the other direction and for the same reason.
            for (d in call) out += d
            val lift = TopDegree - top
            val tail = min(3, call.size)
            for (i in call.size - tail until call.size) out += call[i] + lift
            out += TopDegree
            trimTo(out, MaxReply)
        }
        Personality.CALM -> {
            // Keeps the contour and drops everything else: no repeats, every other step, home.
            var previous = -1
            for (i in call.indices) {
                val d = call[i]
                if (d != previous && (call.size <= 4 || i % 2 == 0)) {
                    out += d
                    previous = d
                }
            }
            out += HomeDegree
            // Pared back relative to what arrived, not to a fixed number — otherwise a two-note
            // call comes back as five, which is the opposite of simplifying.
            trimTo(out, max(2, min(5, call.size)))
        }
    }

    if (out.isEmpty()) out += HomeDegree
    return IntArray(out.size) { out[it].coerceIn(0, TopDegree) }
}

/**
 * Did the player sing back something the creature had just handed them? Compared by scale step
 * and not by exact degree, because the creature answers in registers the pads cannot reach, and
 * asking for the literal note would make the echo impossible to perform.
 */
private fun quotes(call: IntArray, previous: IntArray): Boolean {
    if (call.size < 2 || previous.size < 2) return false
    for (i in 0 until call.size - 1) {
        for (j in 0 until previous.size - 1) {
            if (call[i] % 5 == previous[j] % 5 && call[i + 1] % 5 == previous[j + 1] % 5) return true
        }
    }
    return false
}

private fun meanDegree(phrase: IntArray): Float {
    if (phrase.isEmpty()) return HomeDegree.toFloat()
    var sum = 0
    for (d in phrase) sum += d
    return sum.toFloat() / phrase.size
}

/**
 * The number the game has to hand back. Kept as one function so the result card and the reward
 * cannot ever disagree about it.
 */
private fun tally(yourNotes: Int, theirNotes: Int, echoes: Int, sungVerses: Int): Int =
    yourNotes * 12 + theirNotes * 6 + echoes * 90 + sungVerses * 50

/**
 * The answer in words. This is the channel carrying the whole game when the sound is off or the
 * screen is not being looked at, so it reports length, register and habit — never a verdict.
 */
private fun describeAnswer(name: String, call: IntArray, reply: IntArray, voice: Voice, quoted: Boolean): String {
    val length = when {
        reply.size < call.size -> "shorter than yours"
        reply.size > call.size -> "longer than yours"
        else -> "the same length as yours"
    }
    val theirs = meanDegree(reply)
    val ours = meanDegree(call)
    val register = when {
        theirs < ours - 0.8f -> "lower down"
        theirs > ours + 0.8f -> "higher up"
        else -> "in your own register"
    }
    val hook = if (quoted) " It opens on the hook the two of you have been building." else ""
    return "$name answers: ${reply.size} notes, $length, $register. ${voice.habit}$hook"
}

/** What the player just sang, in words, for the same reason. */
private fun describeCall(call: IntArray, sang: Boolean): String {
    if (!sang) return "You left it an opening, so it took one."
    val shape = when {
        call.last() > call.first() -> "rising"
        call.last() < call.first() -> "falling"
        else -> "level"
    }
    return "You sang ${call.size} notes, $shape."
}

// ------------------------------------------------------------------------------------ the screen

/**
 * A duet. You sing a short phrase on five pads, the creature answers with a phrase built out of
 * yours and bent by its personality, and the hook the two of you make comes back at the top of
 * every answer after the first.
 *
 * Nothing here is timed and nothing is marked. The pads wait as long as you like, the answer is a
 * transformation rather than a target, and the score is not shown until the song is over — a
 * player who never looks at it loses nothing.
 */
@Composable
fun DuetGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    // Frozen at entry: finishGame writes the new record before the result card renders, so
    // reading it live would make "NEW RECORD" impossible to ever show.
    val best: Int = remember { pet.highScores[GAME_ID] ?: 0 }
    val personality: Personality = remember { pet.personality }
    val voice: Voice = remember { voiceOf(personality) }
    val petName: String = remember { pet.name }
    // Deterministic. Born-at and games-played are stored state, so the same pet on the same play
    // count always gets the same ornaments; no clock is read during composition.
    val rng: Random = remember { Random(pet.bornAtMillis * 31 + pet.gamesPlayed) }

    // The song is only ever drawn, and the canvas redraws off the frame clock, so a plain list is
    // enough and saves a snapshot write per note. The phrase in hand drives composition — pad
    // enablement, button wording — so its length is held as observable state instead.
    val song: MutableList<SungNote> = remember { mutableListOf<SungNote>() }
    val phrase: IntArray = remember { IntArray(MaxPhrase) }
    var phraseCount by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf(IntArray(0)) }
    var lastReply by remember { mutableStateOf(IntArray(0)) }
    var motif by remember { mutableStateOf(IntArray(0)) }

    var phase by remember { mutableStateOf(Phase.YOURS) }
    var verse by remember { mutableIntStateOf(0) }
    var clock by remember { mutableFloatStateOf(0f) }
    var turnTime by remember { mutableFloatStateOf(0f) }
    var played by remember { mutableIntStateOf(0) }
    var signed by remember { mutableStateOf(false) }
    /** A replay adds nothing and advances nothing. It is a second listen, not a turn. */
    var replaying by remember { mutableStateOf(false) }
    var liveDegree by remember { mutableIntStateOf(-1) }
    var announcement by remember {
        mutableStateOf("Sing anything on the five pads, then hand over. $petName will answer.")
    }

    var yourNotes by remember { mutableIntStateOf(0) }
    var theirNotes by remember { mutableIntStateOf(0) }
    var echoes by remember { mutableIntStateOf(0) }
    var sungVerses by remember { mutableIntStateOf(0) }

    // Read inside the frame loop, which is launched once and would otherwise keep the values it
    // captured on the first composition for the whole song.
    val soundOn by rememberUpdatedState(ui.config.soundEnabled)
    val currentVoice by rememberUpdatedState(voice)

    // Phrases lengthen as the session goes on, so the last verse is a bigger idea than the first.
    val phraseCap = min(MaxPhrase, 3 + verse)

    LaunchedEffect(Unit) {
        var previous = 0L
        while (phase != Phase.DONE) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                clock += dt
                if (phase != Phase.THEIRS) return@withFrameNanos

                turnTime += dt
                val v: Voice = currentVoice
                val notes: IntArray = reply
                // Notes land on their own beat whether or not anything is audible: the drawing and
                // the sound are two readings of one clock, not one following the other.
                while (played < notes.size && turnTime >= HandoverPause + played * v.slot) {
                    val degree = notes[played]
                    liveDegree = degree
                    val held = if (played == notes.size - 1) 1 + v.holdLast else 1
                    if (!replaying) song += SungNote(degree, Singer.THEM, held)
                    sing(degree, soundOn)
                    played += 1
                }

                val endsAt = HandoverPause + (notes.size + v.holdLast) * v.slot
                if (!signed && turnTime >= endsAt) {
                    signed = true
                    liveDegree = -1
                    // The one place a personality gets its own timbre. The engine has no per-note
                    // waveform, but it does have ready-made flourishes with distinct characters.
                    if (soundOn) ChiptuneEngine.play(v.sign, pitch = v.signPitch)
                }
                if (turnTime >= endsAt + SettlePause) {
                    if (replaying) replaying = false else verse += 1
                    phase = if (verse >= Verses) Phase.DONE else Phase.YOURS
                    turnTime = 0f
                }
            }
        }
    }

    // Settle up once the last answer has died away.
    LaunchedEffect(phase) {
        if (phase != Phase.DONE) return@LaunchedEffect
        // Togetherness, not accuracy: did the two of you actually take turns, and did you ever
        // pick anything up from what came back?
        val turnTaking = sungVerses.toFloat() / Verses
        val listening = if (Verses > 1) echoes.toFloat() / (Verses - 1) else 0f
        viewModel.finishGame(
            won = sungVerses >= (Verses + 1) / 2,
            score = (turnTaking * 0.6f + listening * 0.4f).coerceIn(0f, 1f),
            gameName = "Duet",
            gameId = GAME_ID,
            points = tally(yourNotes, theirNotes, echoes, sungVerses),
        )
    }

    /** Hands the phrase over. An empty hand-over is allowed: the creature simply starts one. */
    fun handOver() {
        if (phase != Phase.YOURS) return
        val count: Int = phraseCount
        val sang = count > 0
        val call = if (sang) {
            IntArray(count) { phrase[it] }
        } else {
            // It has been left an opening, so it takes one — from the hook, if there is one yet.
            if (motif.isNotEmpty()) motif.copyOf() else intArrayOf(HomeDegree, HomeDegree + 1, HomeDegree - 2)
        }
        if (sang) {
            sungVerses += 1
            yourNotes += count
            for (i in 0 until count) song += SungNote(phrase[i], Singer.YOU, 1)
            if (quotes(call, lastReply)) echoes += 1
        }
        val answer = answerOf(personality, call, motif, rng)
        announcement = describeCall(call, sang) + " " +
            describeAnswer(petName, call, answer, voice, motif.isNotEmpty())
        reply = answer
        lastReply = answer
        theirNotes += answer.size
        motif = intArrayOf(call.first().coerceIn(0, TopDegree), answer.last())
        phraseCount = 0
        played = 0
        signed = false
        turnTime = 0f
        replaying = false
        phase = Phase.THEIRS
    }

    /** Plays the last answer again. Nothing is added and nothing is judged. */
    fun hearAgain() {
        if (phase != Phase.YOURS || lastReply.isEmpty()) return
        reply = lastReply
        replaying = true
        played = 0
        signed = false
        turnTime = 0f
        phase = Phase.THEIRS
    }

    val answerLength = (reply.size + voice.holdLast) * voice.slot + HandoverPause
    val progress = when (phase) {
        Phase.DONE -> 1f
        Phase.THEIRS -> (verse + (turnTime / max(0.2f, answerLength)).coerceIn(0f, 1f)) / Verses
        else -> verse.toFloat() / Verses
    }

    // The result card overlays rather than joining the column, so the song stays on screen behind
    // it instead of being squeezed out by a full-size sibling.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Edge to edge: keep the content out of the status and gesture bars. The
                // background is applied first on purpose, so it still bleeds under them.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(10.dp),
        ) {
            GameHeader(
                title = "Duet",
                left = "Verse ${min(verse + 1, Verses)} of $Verses",
                // Deliberately not the score. There is a number and it is nobody's business
                // until the song is over.
                right = if (phase == Phase.THEIRS) "$petName sings" else "Your turn",
                progress = progress,
                onExit = onExit,
            )
            Spacer(Modifier.height(8.dp))

            SingerBar(
                voice = voice,
                singing = phase == Phase.THEIRS,
                clock = clock,
                announcement = announcement,
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "The song so far",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF12141B))
                    .semantics {
                        contentDescription = "The song so far: $yourNotes notes from you and " +
                            "$theirNotes from $petName, drawn as blocks with pitch as height."
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLedger()
                    drawRibbon(song, NeoColors.NeonCyan, voice.accent, clock)
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF171A22))
                    .semantics {
                        contentDescription = if (phase == Phase.THEIRS) {
                            "$petName is answering, note ${min(played, reply.size)} of ${reply.size}"
                        } else {
                            "Your phrase: $phraseCount of $phraseCap notes"
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLedger()
                    if (phase == Phase.THEIRS) {
                        drawExchange(reply, played, voice.accent, reply.size, clock, live = true)
                    } else {
                        drawExchange(phrase, phraseCount, NeoColors.NeonCyan, phraseCap, clock, live = false)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(Pads) { index ->
                    val degree = PadLowDegree + index
                    val lit = liveDegree == degree
                    val enabled = phase == Phase.YOURS && phraseCount < phraseCap
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                NeoColors.NeonCyan.copy(
                                    alpha = when {
                                        lit -> 0.85f
                                        enabled -> 0.34f
                                        else -> 0.12f
                                    },
                                ),
                            )
                            .clickable(
                                enabled = enabled,
                                role = Role.Button,
                                onClickLabel = "Sing ${PadNames[index]}",
                            ) {
                                phrase[phraseCount] = degree
                                phraseCount += 1
                                sing(degree, soundOn)
                            }
                            .semantics {
                                contentDescription = "Sing ${PadNames[index]}, pad ${index + 1} of $Pads"
                            },
                    ) {
                        // The pads are themselves a ladder: taller block, higher note.
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val bar = size.height * (0.22f + index * 0.13f)
                            drawRect(
                                color = Color.White.copy(alpha = if (lit) 0.95f else 0.5f),
                                topLeft = Offset(size.width * 0.3f, size.height - bar - 8f),
                                size = Size(size.width * 0.4f, bar),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DuetButton(
                    label = if (phraseCount > 0) "Take it back" else "Hear it again",
                    enabled = phase == Phase.YOURS && (phraseCount > 0 || lastReply.isNotEmpty()),
                    accent = NeoColors.OnDarkMuted,
                    description = if (phraseCount > 0) {
                        "Take back the last note you sang"
                    } else {
                        "Hear the last answer from $petName again"
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    if (phraseCount > 0) phraseCount -= 1 else hearAgain()
                }
                DuetButton(
                    label = if (phraseCount > 0) "Over to you" else "You start",
                    enabled = phase == Phase.YOURS,
                    accent = voice.accent,
                    description = if (phraseCount > 0) {
                        "Hand your phrase of $phraseCount notes to $petName"
                    } else {
                        "Let $petName start this verse"
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    handOver()
                }
            }
        }

        if (phase == Phase.DONE) {
            val points = tally(yourNotes, theirNotes, echoes, sungVerses)
            GameResult(
                title = "THE SONG ENDS",
                lines = listOf(
                    "You sang $yourNotes notes, $petName answered with $theirNotes",
                    if (echoes > 0) {
                        "You picked its line back up $echoes times"
                    } else {
                        "You kept a line of your own throughout"
                    },
                    "Score $points" + if (points > best) "  ★ NEW RECORD" else "",
                ),
                onExit = onExit,
            )
        }
    }
}

// --------------------------------------------------------------------------------- the pieces

/**
 * The other voice, and the answer in words. The text is the live region: with the sound off — or
 * with the screen not being looked at — this line is the answer.
 */
@Composable
private fun SingerBar(voice: Voice, singing: Boolean, clock: Float, announcement: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(52.dp)) {
            drawCreature(voice, singing, clock)
        }
        Text(
            text = announcement,
            style = MaterialTheme.typography.labelSmall,
            color = if (singing) voice.accent else NeoColors.OnDarkMuted,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A flat, wide button that keeps its 48dp target on the narrowest phone we support. */
@Composable
private fun DuetButton(
    label: String,
    enabled: Boolean,
    accent: Color,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (enabled) 0.28f else 0.08f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) NeoColors.OnDark else NeoColors.OnDarkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------------- the drawing

/** Where a degree sits in the panel, with room left below it for the block itself. */
private fun DrawScope.pitchY(degree: Int, blockHeight: Float): Float {
    val t = degree.coerceIn(0, TopDegree).toFloat() / TopDegree
    return (1f - t) * (size.height - blockHeight - 6f) + 3f
}

/** Faint rails at every degree, so height on a panel reads as pitch rather than as decoration. */
private fun DrawScope.drawLedger() {
    val blockHeight = size.height * 0.09f
    for (d in 0..TopDegree) {
        drawRect(
            color = Color.White.copy(alpha = if (d % 5 == 0) 0.16f else 0.06f),
            topLeft = Offset(0f, pitchY(d, blockHeight) + blockHeight * 0.5f),
            size = Size(size.width, 1f),
        )
    }
}

/**
 * The whole song, compressed to fit whatever width it is given. Colour says who sang it, height
 * says how high, width says how long it was held — the three things the ear would have told you.
 */
private fun DrawScope.drawRibbon(song: List<SungNote>, yours: Color, theirs: Color, clock: Float) {
    var slots = 0
    // Indexed rather than forEach: this runs every frame, and an iterator per frame is an
    // allocation per frame.
    for (i in 0 until song.size) slots += song[i].slots
    val columns = max(slots, 28)
    val step = size.width / columns
    val blockHeight = size.height * 0.09f
    var x = 0f
    for (i in 0 until song.size) {
        val note = song[i]
        val width = step * note.slots
        // The newest few notes glow, so an answer arriving is visible with no sound at all.
        val age = song.size - i
        val glow = if (age <= 3) 0.35f * (4 - age) / 3f else 0f
        drawRoundRect(
            color = (if (note.singer == Singer.YOU) yours else theirs)
                .copy(alpha = (0.62f + glow).coerceIn(0f, 1f)),
            topLeft = Offset(x + 1f, pitchY(note.degree, blockHeight)),
            size = Size(max(2f, width - 2f), blockHeight),
            cornerRadius = CornerRadius(3f, 3f),
        )
        x += width
    }
    // A cursor at the end of the song, breathing, so the panel is never quite still.
    drawRect(
        color = Color.White.copy(alpha = 0.35f + 0.25f * (0.5f + 0.5f * sin(clock * 3f))),
        topLeft = Offset(min(x, size.width - 2f), 0f),
        size = Size(2f, size.height),
    )
}

/**
 * The phrase currently in play, drawn large: yours as you build it, theirs as it arrives. The
 * empty slots ahead are drawn too, so how much room is left is something you can see.
 */
private fun DrawScope.drawExchange(
    degrees: IntArray,
    count: Int,
    colour: Color,
    capacity: Int,
    clock: Float,
    live: Boolean,
) {
    val slots = max(capacity, 1)
    val step = size.width / slots
    val blockHeight = size.height * 0.16f
    for (i in 0 until slots) {
        val left = i * step + 3f
        val width = max(2f, step - 6f)
        if (i < count && i < degrees.size) {
            val newest = i == count - 1
            val pulse = if (newest && live) 0.25f * (0.5f + 0.5f * sin(clock * 9f)) else 0f
            drawRoundRect(
                color = colour.copy(alpha = (0.75f + pulse).coerceIn(0f, 1f)),
                topLeft = Offset(left, pitchY(degrees[i], blockHeight)),
                size = Size(width, blockHeight),
                cornerRadius = CornerRadius(4f, 4f),
            )
        } else {
            // An empty slot is a dash on the floor, not a note.
            drawRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(left, size.height - 5f),
                size = Size(width, 2f),
            )
        }
    }
}

/**
 * The other half of the duet, drawn from scratch. Its size is the only volume the synthesiser
 * lets us express, so a brave creature is literally bigger on screen while it is answering.
 */
private fun DrawScope.drawCreature(voice: Voice, singing: Boolean, clock: Float) {
    val bob = sin(clock * (if (singing) 6f else 2f)) * (if (singing) 0.045f else 0.02f)
    val radius = size.minDimension * 0.34f * (if (singing) voice.presence else 0.9f)
    val centre = Offset(size.width * 0.5f, size.height * (0.52f + bob))

    if (singing) {
        // A ring of held breath: how loudly it is singing, since we cannot make it louder.
        drawCircle(
            color = voice.accent.copy(alpha = 0.18f),
            radius = radius * (1.35f + 0.18f * (0.5f + 0.5f * sin(clock * 5f))),
            center = centre,
        )
    }
    drawCircle(color = voice.accent.copy(alpha = 0.9f), radius = radius, center = centre)
    drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius, center = centre, style = CreatureOutline)
    val eyeY = centre.y - radius * 0.22f
    val eyeGap = radius * 0.38f
    drawCircle(color = Color(0xFF12141B), radius = radius * 0.12f, center = Offset(centre.x - eyeGap, eyeY))
    drawCircle(color = Color(0xFF12141B), radius = radius * 0.12f, center = Offset(centre.x + eyeGap, eyeY))
    // The mouth opens on the beat while it sings, and rests nearly shut otherwise.
    val open = if (singing) 0.18f + 0.30f * (0.5f + 0.5f * sin(clock * 11f)) else 0.06f
    drawOval(
        color = Color(0xFF12141B),
        topLeft = Offset(centre.x - radius * 0.26f, centre.y + radius * 0.14f),
        size = Size(radius * 0.52f, radius * open * 2.2f),
    )
}

private val GAME_ID = MiniGame.DUET.id
