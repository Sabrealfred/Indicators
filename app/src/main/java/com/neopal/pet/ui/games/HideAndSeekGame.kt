package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.Genome
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Morphology
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val GAME_ID = "game_hide"

/** Four rounds, roles swapping every round, so both halves of the game are always played. */
private const val ROUNDS = 4

/** Longest a single round may run before it is called. Four of these is a ~50 s game. */
private const val ROUND_CAP = 13f

/** How long the player has to choose a hiding place before one is chosen for them. */
private const val CHOOSE_CAP = 4.5f

/** The creature's own counting-to-ten, shortened to something a thumb will sit through. */
private const val COUNT_IN = 1.6f

/** Breather between rounds, long enough to read the verdict. */
private const val ROUND_BREAK = 1.7f

/** Six props reads as a room; more turns the search into a chore on a phone screen. */
private const val SPOT_COUNT = 6

/** Where the creature stands when it is not at a prop. */
private const val FLOOR_Y = 0.86f

/** One hiding place. Colours live on the enum so nothing has to be built inside a draw pass. */
private enum class HideProp(val label: String, val body: Color, val trim: Color) {
    CRATE("the crate", Color(0xFF9A6B3F), Color(0xFFC79055)),
    POT("the plant pot", Color(0xFFB05C4A), Color(0xFF4BD97B)),
    CURTAIN("the curtain", Color(0xFF5C4E8A), Color(0xFF8474C4)),
    BARREL("the barrel", Color(0xFF7A5230), Color(0xFFB9B4A6)),
    BASKET("the basket", Color(0xFFC9A45C), Color(0xFF8E6F33)),
    RUG("the rug", Color(0xFF3F5D7A), Color(0xFF6D93B8)),
}

/**
 * A prop on the floor and everything the round needs to know about it.
 *
 * Mutable on purpose: the animation loop writes [open] and [tell] every frame and the Canvas
 * reads them, exactly as the falling items in Snack Catch do. Redraws are driven by the frame
 * clock the loop also writes, not by these fields.
 */
private class HideSpot(
    val prop: HideProp,
    /** Normalised centre within the playfield. */
    val x: Float,
    val y: Float,
    /**
     * 0..1, how obvious a hiding place this looks. Shuffled per run and never shown to the
     * player — it exists so the creature's *taste* in hiding places can be a trait rather than
     * a coin flip.
     */
    val obviousness: Float,
    /** 0 shut, 1 flung open. Eased, so a lid opening reads as a hand lifting it. */
    var open: Float = 0f,
    var checked: Boolean = false,
    /** 0..1 rustle amplitude, decaying. This is the hider giving itself away. */
    var tell: Float = 0f,
)

/** Which half of the game is running. */
private enum class HidePhase { CHOOSE, SEEKER_HUNT, COUNT_IN, PLAYER_HUNT, BREAK }

/**
 * The genome, read once and turned into the handful of numbers the round actually uses.
 *
 * Every field here is a gene and nothing else — there is no difficulty setting to blend with,
 * on purpose. Two creatures from different lineages play this game differently, and the only
 * way to change how it plays is to breed for it.
 */
private class HideInstincts(genome: Genome) {
    /** Normalised widths per second. Vigour is the gene you can see from across the room. */
    val walkSpeed = 0.34f + genome.vigor * 0.62f

    /** Seconds spent peering into each prop. A curious creature is quick about it. */
    val dwell = 1.20f - genome.curiosity * 0.62f

    /** How far the search order drifts away from "look in the obvious places first". */
    val oddness = genome.curiosity

    /** Chance of wandering back to a prop it has already emptied. Wit is what stops this. */
    val repeatChance = (1f - genome.wit) * 0.42f

    /** Chance, per failed check, of re-sorting the rest of the search toward the player. */
    val hunch = genome.sociability

    /** A shy creature prizes a poor-looking hiding place; a sociable one half wants finding. */
    val shyness = 1f - genome.sociability

    /** Seconds between rustles while hiding. A restless creature cannot hold still. */
    val tellPeriod = 5.6f - genome.vigor * 4.2f

    /** How much it avoids the place it hid in last time. */
    val memory = genome.wit
}

/**
 * Hide and seek, played with the creature rather than against it.
 *
 * Rounds alternate: in one the player picks a prop to hide behind and the creature searches the
 * room, in the next the creature hides and the player taps props to find it. Both halves are
 * driven by the same four temperament genes, and nothing else — see [HideInstincts]. A vigorous
 * creature crosses the room quickly but cannot keep still once it is the one hiding; a shy one
 * picks the least promising corner; a witty one never checks the same prop twice.
 */
@Composable
fun HideAndSeekGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    // Frozen at entry: finishGame writes the new record before the result card renders.
    val best = remember { pet.highScores[GAME_ID] ?: 0 }
    val motion = if (ui.config.reducedMotion) 0.3f else 1f

    // Deterministic, but not the same room twice: the save's own seed pinned to how much life
    // the pet has had. No wall clock is read during composition.
    val seed: Long = remember(pet.rngSeed, pet.gamesPlayed, pet.ageSeconds) {
        pet.rngSeed * 31L + pet.gamesPlayed * 7919L + pet.ageSeconds
    }
    val board: List<HideSpot> = remember(seed) { buildHideBoard(Random(seed xor 0x5DEECE66DL)) }
    val random: Random = remember(seed) { Random(seed) }
    val instincts: HideInstincts = remember(pet.genome) { HideInstincts(pet.genome) }
    val searchOrder: MutableList<Int> = remember(seed) { mutableListOf() }

    // Hoisted out of the draw pass: none of this changes while a game is running.
    val creatureSpec: CreatureSpec = remember(pet.species, pet.stage, pet.branch, pet.equippedHat, pet.weightGrams, pet.genome) {
        CreatureSpec(
            species = pet.species,
            stage = if (pet.stage == LifeStage.EGG) LifeStage.BABY else pet.stage,
            branch = pet.branch,
            mood = Mood.HAPPY,
            hatId = pet.equippedHat,
            weightGrams = pet.weightGrams,
            // The bred silhouette plays the game, not a stand-in.
            morphology = Morphology.of(
                pet.genome,
                if (pet.stage == LifeStage.EGG) LifeStage.BABY else pet.stage,
                pet.branch,
                pet.weightGrams,
            ),
        )
    }

    var playerHidesFirst by remember { mutableStateOf<Boolean?>(null) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    var time by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf(HidePhase.CHOOSE) }
    var phaseTime by remember { mutableFloatStateOf(0f) }
    var round by remember { mutableIntStateOf(0) }

    var petX by remember { mutableFloatStateOf(0.5f) }
    var petY by remember { mutableFloatStateOf(FLOOR_Y) }
    var petLean by remember { mutableFloatStateOf(0f) }

    var playerSpot by remember { mutableIntStateOf(-1) }
    var hiddenSpot by remember { mutableIntStateOf(-1) }
    var lastHidden by remember { mutableIntStateOf(-1) }
    var seekerAt by remember { mutableIntStateOf(-1) }
    var forcedNext by remember { mutableIntStateOf(-1) }
    var orderCursor by remember { mutableIntStateOf(0) }
    var dwellLeft by remember { mutableFloatStateOf(0f) }
    var pendingTap by remember { mutableIntStateOf(-1) }
    var dashUsed by remember { mutableStateOf(false) }

    var nextTell by remember { mutableFloatStateOf(0f) }
    var tellCount by remember { mutableIntStateOf(0) }

    var score by remember { mutableIntStateOf(0) }
    var roundsWon by remember { mutableIntStateOf(0) }
    var hiddenSeconds by remember { mutableFloatStateOf(0f) }
    var checksMade by remember { mutableIntStateOf(0) }
    var repeats by remember { mutableIntStateOf(0) }
    var roundRepeats by remember { mutableIntStateOf(0) }
    var lifted by remember { mutableIntStateOf(0) }
    var wrongChecks by remember { mutableIntStateOf(0) }
    var bestFind by remember { mutableFloatStateOf(0f) }

    var caption by remember { mutableStateOf("Choose a side to start.") }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashColor by remember { mutableStateOf(NeoColors.NeonCyan) }
    var flashTick by remember { mutableIntStateOf(0) }

    // The timer bar is the one thing the header needs from the clock. Published in steps rather
    // than read live, so the whole screen does not recompose sixty times a second for a 4dp bar.
    var barProgress by remember { mutableFloatStateOf(0f) }

    // Which props have been opened, as one bit each. The props themselves are plain objects the
    // frame loop writes to, which the Canvas can follow but a screen reader cannot; this is the
    // same fact in a form that recomposes the labels.
    var checkedMask: Int by remember { mutableIntStateOf(0) }

    val playerHides = (round % 2 == 0) == (playerHidesFirst == true)

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val firstHides = playerHidesFirst == true
        val sound = ui.config.soundEnabled

        /** Wipes the room and deals whichever role this round belongs to. */
        fun startRound(index: Int) {
            board.forEach {
                it.checked = false
                it.open = 0f
                it.tell = 0f
            }
            playerSpot = -1
            hiddenSpot = -1
            seekerAt = -1
            forcedNext = -1
            orderCursor = 0
            dwellLeft = 0f
            wrongChecks = 0
            dashUsed = false
            tellCount = 0
            roundRepeats = 0
            phaseTime = 0f
            petX = 0.5f
            petY = FLOOR_Y
            if ((index % 2 == 0) == firstHides) {
                // The order is fixed before the player has hidden, so it can never be a reaction
                // to where they went — only to what kind of creature this is.
                searchOrder.clear()
                val keys = FloatArray(SPOT_COUNT) { i ->
                    board[i].obviousness * (1f - instincts.oddness) + random.nextFloat() * instincts.oddness
                }
                board.indices.sortedByDescending { keys[it] }.forEach { searchOrder += it }
                phase = HidePhase.CHOOSE
                caption = "Pick a hiding place. ${pet.name} is counting."
            } else {
                phase = HidePhase.COUNT_IN
                caption = "Eyes shut — ${pet.name} is finding a spot."
            }
        }

        /** Settles a round the player spent hiding. */
        fun endHideRound(found: Boolean, elapsed: Float) {
            hiddenSeconds += elapsed
            score += (elapsed * 14f).toInt()
            if (found) {
                flash = "FOUND YOU"
                flashColor = NeoColors.NeonRed
                caption = "${pet.name} found you in ${hideOneDecimal(elapsed)} s."
                if (sound) ChiptuneEngine.play(Sfx.GAME_MISS)
            } else {
                score += 400
                roundsWon += 1
                flash = "STILL HIDDEN"
                flashColor = NeoColors.NeonGreen
                caption = "${pet.name} gave up. You held out ${hideOneDecimal(elapsed)} s."
                if (sound) ChiptuneEngine.play(Sfx.HAPPY)
            }
            flashTick += 1
            phase = HidePhase.BREAK
            phaseTime = 0f
        }

        startRound(0)
        var previous = 0L
        while (!finished) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                time += dt
                phaseTime += dt

                // Lids ease toward their state and rustles fade, whatever the round is doing.
                board.forEach { spot ->
                    val target = if (spot.checked) 1f else 0f
                    spot.open += (target - spot.open) * (dt * 9f).coerceAtMost(1f)
                    spot.tell = (spot.tell - dt * 2.4f).coerceAtLeast(0f)
                }
                // A plain loop, not forEachIndexed: capturing the accumulator in a lambda would
                // allocate a holder for it on every frame.
                var mask = 0
                for (i in 0 until SPOT_COUNT) if (board[i].checked) mask = mask or (1 shl i)
                if (mask != checkedMask) checkedMask = mask

                // One tap per frame is deliberate: a second thumb landing in the same frame
                // would otherwise be able to check two props off a single reveal.
                val tap = pendingTap
                if (tap >= 0) {
                    pendingTap = -1
                    when (phase) {
                        HidePhase.CHOOSE -> {
                            playerSpot = tap
                            phase = HidePhase.SEEKER_HUNT
                            phaseTime = 0f
                            caption = "Hidden in ${board[tap].prop.label}. Tap elsewhere to bolt — once only."
                            if (sound) ChiptuneEngine.play(Sfx.SELECT)
                        }
                        HidePhase.SEEKER_HUNT ->
                            // Bolting into a prop the creature has already emptied is the whole
                            // trick against a forgetful seeker, so it is allowed.
                            if (!dashUsed && tap != playerSpot && tap != seekerAt) {
                                dashUsed = true
                                playerSpot = tap
                                score = (score - 60).coerceAtLeast(0)
                                caption = "You bolt for ${board[tap].prop.label}."
                                if (sound) ChiptuneEngine.play(Sfx.SELECT, pitch = 1.3f)
                            }
                        HidePhase.PLAYER_HUNT ->
                            if (!board[tap].checked) {
                                board[tap].checked = true
                                lifted += 1
                                if (tap == hiddenSpot) {
                                    val elapsed = phaseTime
                                    score += (520 - wrongChecks * 80 - (elapsed * 18f).toInt()).coerceAtLeast(60)
                                    roundsWon += 1
                                    bestFind = if (bestFind <= 0f) elapsed else min(bestFind, elapsed)
                                    flash = "FOUND!"
                                    flashColor = NeoColors.NeonGreen
                                    flashTick += 1
                                    caption = "Found ${pet.name} in ${hideOneDecimal(elapsed)} s."
                                    if (sound) ChiptuneEngine.play(Sfx.GAME_HIT, pitch = 1.2f)
                                    phase = HidePhase.BREAK
                                    phaseTime = 0f
                                } else {
                                    wrongChecks += 1
                                    caption = hideHintFor(board[tap], board[hiddenSpot])
                                    if (sound) ChiptuneEngine.play(Sfx.GAME_MISS)
                                }
                            }
                        else -> Unit
                    }
                }

                when (phase) {
                    HidePhase.CHOOSE -> {
                        petX += (0.5f - petX) * (dt * 5f).coerceAtMost(1f)
                        petY += (FLOOR_Y - petY) * (dt * 5f).coerceAtMost(1f)
                        if (phaseTime >= CHOOSE_CAP) {
                            // Dithering costs you the choice, not the round.
                            playerSpot = random.nextInt(SPOT_COUNT)
                            phase = HidePhase.SEEKER_HUNT
                            phaseTime = 0f
                            caption = "Time up — you dive into ${board[playerSpot].prop.label}."
                        }
                    }

                    HidePhase.SEEKER_HUNT -> {
                        if (dwellLeft > 0f) {
                            dwellLeft -= dt
                            if (dwellLeft <= 0f) {
                                val looked = seekerAt
                                seekerAt = -1
                                if (looked == playerSpot) {
                                    endHideRound(found = true, elapsed = phaseTime)
                                } else {
                                    if (roundRepeats < 2 && orderCursor > 0 && random.nextFloat() < instincts.repeatChance) {
                                        // Low wit: it has genuinely forgotten it looked there.
                                        roundRepeats += 1
                                        repeats += 1
                                        forcedNext = searchOrder[orderCursor - 1]
                                        caption = "${pet.name} doubles back to ${board[forcedNext].prop.label}."
                                    } else {
                                        if (random.nextFloat() < instincts.hunch * 0.55f && orderCursor + 1 < searchOrder.size) {
                                            // Sociability as a hunch: it wants to be near you, so
                                            // the rest of the search bends toward wherever you are.
                                            val rest = searchOrder.subList(orderCursor + 1, searchOrder.size)
                                            val target = board[playerSpot]
                                            val warm = rest.sortedBy { hideDistanceBetween(board[it], target) }
                                            rest.indices.forEach { i -> rest[i] = warm[i] }
                                            caption = "${pet.name} drifts your way."
                                        }
                                        orderCursor += 1
                                    }
                                }
                            }
                        } else if (seekerAt >= 0) {
                            val spot = board[seekerAt]
                            val tx = spot.x
                            val ty = spot.y + 0.15f
                            val dx = tx - petX
                            val dy = ty - petY
                            val distance = sqrt(dx * dx + dy * dy)
                            if (distance < 0.02f) {
                                petX = tx
                                petY = ty
                                dwellLeft = instincts.dwell
                                spot.checked = true
                                checksMade += 1
                                if (sound) ChiptuneEngine.play(Sfx.SELECT, pitch = 0.8f)
                            } else {
                                val step = (instincts.walkSpeed * dt / distance).coerceAtMost(1f)
                                petX += dx * step
                                petY += dy * step
                                petLean += ((dx * 90f).coerceIn(-14f, 14f) * motion - petLean) *
                                    (dt * 9f).coerceAtMost(1f)
                            }
                        } else if (forcedNext >= 0) {
                            seekerAt = forcedNext
                            forcedNext = -1
                        } else if (orderCursor >= searchOrder.size) {
                            endHideRound(found = false, elapsed = phaseTime)
                        } else {
                            seekerAt = searchOrder[orderCursor]
                        }
                        if (phase == HidePhase.SEEKER_HUNT && phaseTime >= ROUND_CAP) {
                            endHideRound(found = false, elapsed = ROUND_CAP)
                        }
                    }

                    HidePhase.COUNT_IN -> {
                        if (phaseTime >= COUNT_IN) {
                            var pick = 0
                            var bestKey = -1f
                            for (i in 0 until SPOT_COUNT) {
                                val spot = board[i]
                                // Shy creatures want the unpromising corner, sociable ones the
                                // prop you are bound to try first.
                                val taste = spot.obviousness * (1f - instincts.shyness) +
                                    (1f - spot.obviousness) * instincts.shyness
                                val stale = if (i == lastHidden) instincts.memory * 0.6f else 0f
                                val key = taste * 0.62f + random.nextFloat() * instincts.oddness * 0.38f - stale
                                if (key > bestKey) {
                                    bestKey = key
                                    pick = i
                                }
                            }
                            hiddenSpot = pick
                            lastHidden = pick
                            nextTell = time + instincts.tellPeriod * 0.6f
                            phase = HidePhase.PLAYER_HUNT
                            phaseTime = 0f
                            caption = "Your turn. Tap a hiding place to look inside."
                        }
                    }

                    HidePhase.PLAYER_HUNT -> {
                        if (time >= nextTell && hiddenSpot >= 0) {
                            board[hiddenSpot].tell = 1f
                            nextTell = time + instincts.tellPeriod
                            tellCount += 1
                            // Only the first couple are narrated: a restless creature rustles
                            // every second or so and would otherwise talk over everything else.
                            if (tellCount <= 2) caption = "Something shifted somewhere in the room."
                            if (sound) ChiptuneEngine.play(Sfx.SELECT, pitch = 0.55f)
                        }
                        if (phaseTime >= ROUND_CAP) {
                            board[hiddenSpot].checked = true
                            flash = "GOT AWAY"
                            flashColor = NeoColors.NeonRed
                            flashTick += 1
                            caption = "${pet.name} was in ${board[hiddenSpot].prop.label} all along."
                            phase = HidePhase.BREAK
                            phaseTime = 0f
                            if (sound) ChiptuneEngine.play(Sfx.GAME_MISS)
                        }
                    }

                    HidePhase.BREAK -> {
                        if (phaseTime >= ROUND_BREAK) {
                            val next = round + 1
                            round = next
                            if (next >= ROUNDS) finished = true else startRound(next)
                        }
                    }
                }

                val bar = ((round + (phaseTime / ROUND_CAP).coerceIn(0f, 1f)) / ROUNDS).coerceIn(0f, 1f)
                if (abs(bar - barProgress) > 0.005f) barProgress = bar
            }
        }
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        viewModel.finishGame(
            won = roundsWon >= 3,
            score = (roundsWon.toFloat() / ROUNDS).coerceIn(0f, 1f),
            gameName = "Hide and Seek",
            gameId = GAME_ID,
            points = score,
        )
    }

    val status = when {
        finished -> "Final score $score"
        else -> "$caption  ·  Score $score"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Hide and Seek",
            left = "Score $score  ·  Won $roundsWon/$ROUNDS",
            right = when {
                playerHidesFirst == null -> "PICK A SIDE"
                playerHides -> "YOU HIDE"
                else -> "YOU SEEK"
            },
            progress = barProgress,
            onExit = onExit,
        )
        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF12141B)),
            contentAlignment = Alignment.Center,
        ) {
            val fieldWidth = maxWidth
            val fieldHeight = maxHeight

            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = min(size.width * 0.21f, size.height * 0.20f)
                // Skirting board, so the props read as standing in a room rather than floating.
                drawRect(
                    color = NeoColors.NeonCyan.copy(alpha = 0.20f),
                    topLeft = Offset(0f, size.height * 0.90f),
                    size = Size(size.width, 3f),
                )

                board.forEach { spot ->
                    val cx = spot.x * size.width
                    val cy = spot.y * size.height
                    val shake = sin(time * 27f) * spot.tell * unit * 0.07f * motion
                    drawHideProp(spot.prop, cx + shake, cy, unit, spot.open)
                    if (spot.tell > 0.02f) {
                        // Dust knocked loose by a fidget. The only tell the seeking player gets.
                        repeat(3) { i ->
                            val angle = i * 2.1f + time * 2f
                            drawCircle(
                                color = Color.White.copy(alpha = spot.tell * 0.5f),
                                radius = unit * 0.035f * spot.tell,
                                center = Offset(
                                    cx + cos(angle) * unit * (0.38f + 0.10f * i),
                                    cy - unit * (0.55f + 0.16f * i) + sin(angle) * unit * 0.05f,
                                ),
                            )
                        }
                    }
                }

                // Where the player is hiding, ringed so it survives a glance.
                if (playerSpot >= 0 && (phase == HidePhase.SEEKER_HUNT || phase == HidePhase.CHOOSE)) {
                    val spot = board[playerSpot]
                    val pulse = 0.5f + 0.5f * sin(time * 5f)
                    drawCircle(
                        color = NeoColors.NeonCyan.copy(alpha = 0.35f + 0.35f * pulse),
                        radius = unit * (0.62f + 0.05f * pulse),
                        center = Offset(spot.x * size.width, spot.y * size.height),
                        style = Stroke(width = unit * 0.045f),
                    )
                }

                // The creature. Hiding, it is only drawn once its prop has been opened.
                val hidingNow = phase == HidePhase.PLAYER_HUNT || (phase == HidePhase.BREAK && hiddenSpot >= 0)
                if (hidingNow) {
                    if (hiddenSpot >= 0 && board[hiddenSpot].open > 0.25f) {
                        val spot = board[hiddenSpot]
                        drawCreature(
                            center = Offset(spot.x * size.width, spot.y * size.height - unit * 0.42f),
                            unit = size.minDimension * 0.30f,
                            spec = creatureSpec,
                            frame = CreatureFrame(
                                squash = 1f + 0.06f * sin(time * 6f) * motion,
                                mouthOpen = 0.55f,
                                armsUp = 0.7f,
                            ),
                        )
                    }
                } else {
                    val counting = phase == HidePhase.CHOOSE
                    val stride = if (counting) 0f else 1f
                    drawCreature(
                        center = Offset(petX * size.width, petY * size.height),
                        unit = size.minDimension * 0.34f,
                        spec = creatureSpec,
                        frame = CreatureFrame(
                            squash = 1f + 0.04f * sin(time * 9f) * motion,
                            eyeOpen = if (counting) 0f else 1f,
                            mouthOpen = if (dwellLeft > 0f) 0.45f else 0.15f,
                            lean = if (counting) 0f else petLean,
                            armSwing = sin(time * 10f) * motion * stride,
                            armsUp = if (counting) 0.85f else 0f,
                            gaze = (petLean / 14f).coerceIn(-1f, 1f),
                            gazeY = if (dwellLeft > 0f) 0.8f else 0f,
                        ),
                    )
                }
            }

            // Real composables over the painted props, because a Canvas cannot be tapped by a
            // screen reader and 48dp is the floor for a target.
            val tappable = started && !finished &&
                (phase == HidePhase.CHOOSE || phase == HidePhase.SEEKER_HUNT || phase == HidePhase.PLAYER_HUNT)
            board.forEachIndexed { index, spot ->
                val label = spot.prop.label
                val action = when {
                    phase == HidePhase.CHOOSE -> "Hide in $label"
                    phase == HidePhase.SEEKER_HUNT -> "Bolt for $label"
                    else -> "Look in $label"
                }
                val emptied = (checkedMask shr index) and 1 == 1
                val state = when {
                    index == playerSpot && phase != HidePhase.PLAYER_HUNT -> "$label, you are hiding here"
                    emptied && phase == HidePhase.PLAYER_HUNT -> "$label, already searched"
                    emptied -> "$label, already emptied"
                    else -> label
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = (fieldWidth * spot.x - 32.dp).coerceIn(0.dp, (fieldWidth - 64.dp).coerceAtLeast(0.dp)),
                            y = (fieldHeight * spot.y - 32.dp).coerceIn(0.dp, (fieldHeight - 64.dp).coerceAtLeast(0.dp)),
                        )
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable(enabled = tappable, onClickLabel = action) { pendingTap = index }
                        .semantics { contentDescription = state },
                )
            }

            JudgementFlash(text = flash, color = flashColor, tick = flashTick)

            BestChip(best = best, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))

            if (playerHidesFirst == null) {
                HideSidePicker(
                    name = pet.name,
                    onPick = { hidesFirst ->
                        playerHidesFirst = hidesFirst
                        phase = if (hidesFirst) HidePhase.CHOOSE else HidePhase.COUNT_IN
                        caption = if (hidesFirst) "You hide first." else "${pet.name} hides first."
                    },
                )
            } else if (!started && !finished) {
                CountdownGate(onReady = { started = true })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            // The only running commentary the game has, so it has to announce itself.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        HideInstinctStrip(name = pet.name, genome = pet.genome)

        if (finished) {
            GameResult(
                title = if (roundsWon >= 3) "WELL PLAYED!" else if (roundsWon >= 2) "HONOURS EVEN" else "OUT-FOXED",
                lines = listOf(
                    "Score $score" + if (score > best) "  ★ NEW RECORD" else "",
                    "Rounds won $roundsWon of $ROUNDS  ·  hidden ${hideOneDecimal(hiddenSeconds)} s",
                    "${pet.name} searched $checksMade places  ·  doubled back $repeats",
                    if (bestFind > 0f) {
                        "You lifted $lifted lids  ·  quickest find ${hideOneDecimal(bestFind)} s"
                    } else {
                        "You lifted $lifted lids  ·  never found ${pet.name}"
                    },
                ),
                onExit = onExit,
            )
        }
    }
}

/**
 * Who takes the first turn. Offered rather than fixed, because the two halves of this game are
 * not the same game, and a player who has come for one of them should not have to sit out the
 * other one first.
 */
@Composable
private fun HideSidePicker(name: String, onPick: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0A0C12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = "Who hides first?",
                style = MaterialTheme.typography.titleMedium,
                color = NeoColors.OnDark,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Rounds swap over, so you play both sides either way.",
                style = MaterialTheme.typography.labelSmall,
                color = NeoColors.OnDarkMuted,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPick(true) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("I hide") }
                Button(
                    onClick = { onPick(false) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("$name hides") }
            }
        }
    }
}

/**
 * The four temperament genes, on the screen while they are being played against.
 *
 * Without this the creature's behaviour is only felt, and a player with one lineage has nothing
 * to compare it to. With it, the bars and the sentence say in advance what the room is about to
 * do, and the round either bears that out or does not.
 */
@Composable
private fun HideInstinctStrip(name: String, genome: Genome) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = "Instincts",
            style = MaterialTheme.typography.labelSmall,
            color = NeoColors.NeonCyan,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HideGeneBar("Curiosity", genome.curiosity, NeoColors.NeonYellow, Modifier.weight(1f))
            HideGeneBar("Company", genome.sociability, NeoColors.NeonPurple, Modifier.weight(1f))
            HideGeneBar("Vigour", genome.vigor, NeoColors.NeonRed, Modifier.weight(1f))
            HideGeneBar("Wit", genome.wit, NeoColors.NeonGreen, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = hideInstinctSentence(name, genome),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/** One labelled gene, read out as a value rather than as a picture of a bar. */
@Composable
private fun HideGeneBar(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    val tenths = (value * 10f).roundToInt().coerceIn(0, 10)
    Column(modifier = modifier.semantics { contentDescription = "$label $tenths out of 10" }) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0.05f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Plain-English version of [HideInstincts], so the behaviour is predicted rather than guessed at. */
private fun hideInstinctSentence(name: String, genome: Genome): String {
    val pace = when {
        genome.vigor > 0.66f -> "tears around the room"
        genome.vigor > 0.33f -> "keeps a steady pace"
        else -> "takes its time"
    }
    val taste = if (genome.curiosity > 0.55f) "and tries the odd corners first" else "and starts with the obvious places"
    val hider = when {
        genome.vigor > 0.6f -> "It fidgets badly when it hides"
        genome.vigor < 0.35f -> "It hides very still"
        else -> "It hides well enough"
    }
    val tail = when {
        genome.wit > 0.6f -> "and never looks twice"
        genome.wit < 0.4f -> "but loses track and doubles back"
        else -> "and mostly remembers where it has been"
    }
    val company = when {
        genome.sociability > 0.6f -> " It drifts towards you, and half wants finding."
        genome.sociability < 0.35f -> " It hides where you would never think to look."
        else -> ""
    }
    return "$name $pace $taste. $hider $tail.$company"
}

/** Six props on two shelves of three, with their promise as hiding places shuffled per run. */
private fun buildHideBoard(random: Random): List<HideSpot> {
    val props = HideProp.entries.shuffled(random)
    // A fixed ladder, shuffled: every run has one obvious prop and one hopeless one, but which
    // is which changes, so a player cannot learn the room instead of learning the creature.
    val promise = listOf(0.95f, 0.78f, 0.60f, 0.42f, 0.24f, 0.06f).shuffled(random)
    val xs = listOf(0.17f, 0.50f, 0.83f)
    return List(SPOT_COUNT) { i ->
        HideSpot(
            prop = props[i],
            x = xs[i % 3],
            y = if (i < 3) 0.32f else 0.66f,
            obviousness = promise[i],
        )
    }
}

/** Straight-line distance between two props in playfield units. */
private fun hideDistanceBetween(a: HideSpot, b: HideSpot): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * What the player learns from an empty prop.
 *
 * Directional rather than warm-or-cold: six props on a phone screen are few enough that pure
 * distance would give the game away on the second tap.
 */
private fun hideHintFor(from: HideSpot, to: HideSpot): String {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val across = if (abs(dx) < 0.08f) "" else if (dx > 0f) "to the right" else "to the left"
    val along = if (abs(dy) < 0.08f) "" else if (dy > 0f) "nearer the front" else "further back"
    return when {
        across.isEmpty() && along.isEmpty() -> "Empty. Close, though."
        across.isEmpty() -> "Empty. Something breathed $along."
        along.isEmpty() -> "Empty. Something breathed $across."
        else -> "Empty. Something breathed $across, $along."
    }
}

/** One decimal place without dragging a formatter in. */
private fun hideOneDecimal(value: Float): String = "${(value * 10f).roundToInt() / 10f}"

/**
 * One hiding place, drawn from primitives at [cx], [cy] in a box [u] across.
 *
 * [open] runs 0..1 and is the same gesture on every prop — a lid, a leaf or a fold moving aside
 * to show the dark inside — so an opened prop reads as searched wherever it is in the room.
 */
private fun DrawScope.drawHideProp(prop: HideProp, cx: Float, cy: Float, u: Float, open: Float) {
    val left = cx - u / 2f
    val top = cy - u / 2f
    val body = prop.body
    val trim = prop.trim
    val inside = Color(0xFF090A0F)

    drawOval(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(left, top + u * 0.84f),
        size = Size(u, u * 0.20f),
    )

    when (prop) {
        HideProp.CRATE -> {
            drawRect(inside, Offset(left + u * 0.08f, top + u * 0.14f), Size(u * 0.84f, u * 0.34f))
            drawRect(body, Offset(left, top + u * 0.30f), Size(u, u * 0.62f))
            drawLine(trim, Offset(left, top + u * 0.34f), Offset(left + u, top + u * 0.88f), strokeWidth = u * 0.05f)
            drawLine(trim, Offset(left + u, top + u * 0.34f), Offset(left, top + u * 0.88f), strokeWidth = u * 0.05f)
            drawRect(
                color = trim,
                topLeft = Offset(left + open * u * 0.26f, top + u * 0.24f - open * u * 0.30f),
                size = Size(u, u * 0.14f),
            )
        }

        HideProp.POT -> {
            drawRect(inside, Offset(left + u * 0.22f, top + u * 0.20f), Size(u * 0.56f, u * 0.30f))
            // Leaves part as the pot is searched.
            repeat(3) { i ->
                val lean = (i - 1) * (0.22f + open * 0.30f)
                drawOval(
                    color = trim.copy(alpha = 0.9f),
                    topLeft = Offset(cx + lean * u - u * 0.15f, top + u * 0.02f - open * u * 0.06f),
                    size = Size(u * 0.30f, u * 0.44f),
                )
            }
            drawRect(body, Offset(left + u * 0.16f, top + u * 0.44f), Size(u * 0.68f, u * 0.46f))
            drawRect(trim.copy(alpha = 0.35f), Offset(left + u * 0.10f, top + u * 0.40f), Size(u * 0.80f, u * 0.10f))
        }

        HideProp.CURTAIN -> {
            drawRect(inside, Offset(left + u * 0.20f, top), Size(u * 0.60f, u * 0.92f))
            // Two halves sliding apart.
            drawRect(body, Offset(left - open * u * 0.22f, top), Size(u * 0.44f, u * 0.92f))
            drawRect(body, Offset(left + u * 0.56f + open * u * 0.22f, top), Size(u * 0.44f, u * 0.92f))
            drawRect(trim, Offset(left - u * 0.06f, top), Size(u * 1.12f, u * 0.10f))
        }

        HideProp.BARREL -> {
            drawRect(inside, Offset(left + u * 0.14f, top + u * 0.16f), Size(u * 0.72f, u * 0.30f))
            drawRoundRect(
                color = body,
                topLeft = Offset(left + u * 0.10f, top + u * 0.28f),
                size = Size(u * 0.80f, u * 0.62f),
                cornerRadius = CornerRadius(u * 0.16f, u * 0.16f),
            )
            drawRect(trim, Offset(left + u * 0.08f, top + u * 0.44f), Size(u * 0.84f, u * 0.07f))
            drawRect(trim, Offset(left + u * 0.08f, top + u * 0.70f), Size(u * 0.84f, u * 0.07f))
            drawOval(
                color = trim,
                topLeft = Offset(left + u * 0.10f + open * u * 0.24f, top + u * 0.20f - open * u * 0.26f),
                size = Size(u * 0.80f, u * 0.18f),
            )
        }

        HideProp.BASKET -> {
            drawRect(inside, Offset(left + u * 0.16f, top + u * 0.22f), Size(u * 0.68f, u * 0.28f))
            drawArc(
                color = body,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(left + u * 0.06f, top + u * 0.24f),
                size = Size(u * 0.88f, u * 0.66f),
            )
            repeat(3) { i ->
                val y = top + u * (0.42f + i * 0.14f)
                drawLine(trim, Offset(left + u * 0.14f, y), Offset(left + u * 0.86f, y), strokeWidth = u * 0.035f)
            }
            drawArc(
                color = trim,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(left + u * 0.10f + open * u * 0.22f, top + u * 0.16f - open * u * 0.24f),
                size = Size(u * 0.80f, u * 0.24f),
                style = Stroke(width = u * 0.06f),
            )
        }

        HideProp.RUG -> {
            drawOval(color = body, topLeft = Offset(left - u * 0.04f, top + u * 0.56f), size = Size(u * 1.08f, u * 0.36f))
            drawOval(
                color = trim.copy(alpha = 0.5f),
                topLeft = Offset(left + u * 0.18f, top + u * 0.64f),
                size = Size(u * 0.64f, u * 0.20f),
            )
            // The lump under it flattens out once it has been lifted and found empty.
            val lump = 1f - open
            drawOval(
                color = trim,
                topLeft = Offset(left + u * 0.16f, top + u * (0.62f - 0.34f * lump)),
                size = Size(u * 0.68f, u * (0.14f + 0.30f * lump)),
            )
        }
    }
}
