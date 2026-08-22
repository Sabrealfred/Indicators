package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.MiniGame
import com.neopal.pet.domain.Autonomy
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Skill
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The silhouettes a board can ask for.
 *
 * A board never repeats a shape, so every loose piece has exactly one hole it belongs in. That
 * matters more for the creature than for the player: an unambiguous puzzle means a wrong
 * placement is unambiguously a misjudgement, and a misjudgement is the thing worth watching.
 */
private enum class PuzzleShape(val label: String) {
    CIRCLE("circle"),
    SQUARE("square"),
    TRIANGLE("triangle"),
    DIAMOND("diamond"),
    STAR("star"),
    CROSS("cross"),
    HEXAGON("hexagon"),
}

/**
 * Pairs of shapes that are easy to mistake for one another.
 *
 * Wrong guesses are drawn from this table first. A creature that confuses the square with the
 * diamond looks like it is nearly there; a creature that posts a star into a circle looks broken.
 */
private fun confusable(a: PuzzleShape, b: PuzzleShape): Boolean = when (a) {
    PuzzleShape.CIRCLE -> b == PuzzleShape.HEXAGON
    PuzzleShape.HEXAGON -> b == PuzzleShape.CIRCLE || b == PuzzleShape.DIAMOND
    PuzzleShape.SQUARE -> b == PuzzleShape.DIAMOND || b == PuzzleShape.CROSS
    PuzzleShape.DIAMOND -> b == PuzzleShape.SQUARE || b == PuzzleShape.TRIANGLE || b == PuzzleShape.HEXAGON
    PuzzleShape.TRIANGLE -> b == PuzzleShape.STAR || b == PuzzleShape.DIAMOND
    PuzzleShape.STAR -> b == PuzzleShape.TRIANGLE || b == PuzzleShape.CROSS
    PuzzleShape.CROSS -> b == PuzzleShape.STAR || b == PuzzleShape.SQUARE
}

/** A rectangle in playfield space, 0..1 on both axes, so the layout and the Canvas agree. */
private data class Slot(val cx: Float, val cy: Float, val w: Float, val h: Float)

/** One board: the holes across the top, the loose pieces in the tray, and where both sit. */
private class PuzzleBoard(
    val sockets: List<PuzzleShape>,
    val pieces: List<PuzzleShape>,
    val socketSlots: List<Slot>,
    val pieceSlots: List<Slot>,
) {
    val size: Int get() = sockets.size

    /** The one hole this piece belongs in. Unique, because a board never repeats a shape. */
    fun homeOf(piece: Int): Int = sockets.indexOf(pieces[piece])
}

/** What the creature is doing with its attention this instant. */
private enum class Ponder { APPROACH, WEIGH, CARRY, LAND, RECOIL, REST }

/**
 * The creature's half of the game, kept out of composition because it changes every frame.
 *
 * None of this is a plan. The creature holds one piece in mind, one hole it currently believes
 * that piece goes in, and a confidence that has to climb past 1 before it will act. Everything
 * the player reads as thinking — the pause over the tray, the hole it stares at and then
 * abandons, the piece it carries halfway and takes back — falls out of those three numbers.
 */
private class Solver {
    var phase = Ponder.REST
    var piece = -1
    var guess = -1
    var confidence = 0f
    var timer = 0f
    var thought = 0f
    var hesitation = 0f
    /** 0..1, decaying. Drives the wobble that shows a mind changing itself. */
    var doubt = 0f
    /** 0..1, decaying. A small delight when a piece drops in — its own or the player's. */
    var cheer = 0f
    var x = 0.5f
    var y = 0.82f
    var tx = 0.5f
    var ty = 0.82f
    var facing = 1f

    fun forNewBoard() {
        phase = Ponder.REST
        piece = -1
        guess = -1
        confidence = 0f
        timer = 0.6f
        doubt = 0f
        hesitation = 0f
    }
}

private val GAME_ID = MiniGame.PUZZLE.id
private const val TARGET_BOARDS = 4
private const val DURATION = 95f
private const val BOARD_PAUSE = 0.9f

/** Intellect below this and the shapes simply do not resolve; it can watch, and that is all. */
private const val HELP_AT = 30f

private const val EMPTY = 0
private const val BY_PLAYER = 1
private const val BY_PET = 2

private const val PLAYER_POINTS = 120
private const val PET_POINTS = 70
private const val BOARD_BONUS = 150
private const val TAUGHT_BONUS = 40
private const val PLAYER_MISTAKE_COST = 25

private val MinTarget = 48.dp

/**
 * Shape Sorter: four boards of holes and a tray of loose pieces, and a creature that may or may
 * not be able to help you empty it.
 *
 * The other minigames are things you do at the pet. This one is a thing you do beside it. Under
 * [HELP_AT] intellect the shapes do not resolve for the creature at all — it picks a piece up,
 * turns it over, holds it against the wrong hole and puts it back, and the board is entirely
 * yours to clear. Once it is bright enough, and once [Autonomy] lets it act, it starts fitting
 * pieces itself, and a taught creature will beat a player who is not paying attention.
 *
 * It is never made infallible. Accuracy tops out below certain, wrong guesses are drawn from
 * shapes that are genuinely alike, and the deliberation is shown rather than summarised: a
 * tether to the piece it is weighing up, a bubble holding the hole it currently believes in, and
 * a row of pips that fills as it convinces itself and drops when it changes its mind.
 */
@Composable
fun PuzzleGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    // Frozen at entry: finishGame writes the new record before the result card renders, so
    // reading it live would make "NEW RECORD" impossible to ever show.
    val best = remember { pet.highScores[GAME_ID] ?: 0 }
    val motion = if (ui.config.reducedMotion) 0.3f else 1f
    val petName = pet.name.ifBlank { "Your pal" }

    // Seeded from the pet rather than the clock: the same creature at the same moment of its life
    // deals itself the same boards, which is what makes a run reproducible at all.
    val random = remember { Random(puzzleSeed(pet)) }
    val path = remember { Path() }
    val solver = remember { Solver() }
    // Both faces built once. The frame still has to be made per draw — the renderer takes it by
    // value — but the spec never changes mid-run, so it has no business being rebuilt at 60Hz.
    val restingSpec = remember { creatureSpec(pet, Mood.NEUTRAL) }
    val pleasedSpec = remember { creatureSpec(pet, Mood.HAPPY) }

    val mind = (pet.intellect / 100f).coerceIn(0f, 1f)
    val wit = pet.genome.wit.coerceIn(0f, 1f)
    val reads = pet.skills.contains(Skill.READ)
    val bright = pet.intellect >= HELP_AT
    val willing = pet.autonomy != Autonomy.OFF

    // Every number the creature plays by. Accuracy stops short of certainty on purpose.
    val accuracy = (0.34f + mind * 0.60f + if (reads) 0.06f else 0f).coerceAtMost(0.94f)
    val thinkRate = 0.24f + mind * 1.05f + wit * 0.22f
    val thoughtGap = (0.66f - wit * 0.18f).coerceAtLeast(0.34f)
    val moveSpeed = 0.32f + mind * 0.62f
    val restTime = 1.20f - mind * 0.85f
    val ponderLimit = 2.6f + (1f - mind) * 3.4f

    var board by remember { mutableIntStateOf(0) }
    val layout = remember(board) { buildBoard(random, 3 + board) }
    val filled = remember(board) { mutableStateListOf<Int>().also { list -> repeat(layout.size) { list.add(EMPTY) } } }
    val placed = remember(board) { mutableStateListOf<Boolean>().also { list -> repeat(layout.size) { list.add(false) } } }
    var heldPiece by remember(board) { mutableIntStateOf(-1) }
    var playerOnThisBoard by remember(board) { mutableIntStateOf(0) }

    var clock by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var wrongSocket by remember { mutableIntStateOf(-1) }
    var wrongTimer by remember { mutableFloatStateOf(0f) }
    var points by remember { mutableIntStateOf(0) }
    var boardsCleared by remember { mutableIntStateOf(0) }
    var playerSolved by remember { mutableIntStateOf(0) }
    var petSolved by remember { mutableIntStateOf(0) }
    var petMisjudged by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var won by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf("") }
    var flashText by remember { mutableStateOf<String?>(null) }
    var flashColor by remember { mutableStateOf(NeoColors.NeonCyan) }
    var flashTick by remember { mutableIntStateOf(0) }

    val taught = petSolved * TAUGHT_BONUS
    val total = points + taught

    // The whole game in one loop: the clock, the creature's deliberation, and the board pause
    // between levels. Keyed on the board as well as the start so it picks up the new layout.
    LaunchedEffect(started, board) {
        if (!started || finished) return@LaunchedEffect
        solver.forNewBoard()
        var previous = 0L
        var clearing = -1f
        while (true) {
            var stop = false
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                clock += dt
                elapsed += dt
                if (wrongTimer > 0f) {
                    wrongTimer -= dt
                    if (wrongTimer <= 0f) wrongSocket = -1
                }
                solver.doubt = (solver.doubt - dt * 1.6f).coerceAtLeast(0f)
                solver.cheer = (solver.cheer - dt * 1.4f).coerceAtLeast(0f)

                if (elapsed >= DURATION) {
                    won = false
                    finished = true
                    stop = true
                    return@withFrameNanos
                }

                // Assisted creatures take their cue from the keeper: they wait for the first
                // piece to go in before they will touch the board. Autonomous ones just start.
                val mayPlace = bright && willing &&
                    (pet.autonomy == Autonomy.FULL || playerOnThisBoard > 0)

                when (solver.phase) {
                    Ponder.REST -> {
                        solver.timer -= dt
                        if (solver.timer <= 0f) {
                            val next = pickPiece(layout, placed, heldPiece, random)
                            if (next < 0) {
                                solver.timer = 0.4f
                            } else {
                                solver.piece = next
                                solver.guess = -1
                                solver.confidence = 0f
                                solver.thought = thoughtGap * 0.5f
                                solver.timer = 0f
                                solver.phase = Ponder.APPROACH
                                val slot = layout.pieceSlots[next]
                                solver.tx = slot.cx
                                solver.ty = slot.cy - slot.h * 0.30f
                            }
                        }
                    }

                    Ponder.APPROACH -> {
                        if (solver.piece < 0 || placed[solver.piece] || solver.piece == heldPiece) {
                            // The keeper took it out from under it. Look up, start again.
                            solver.piece = -1
                            solver.doubt = 1f
                            solver.timer = restTime * 0.6f
                            solver.phase = Ponder.REST
                        } else if (advance(solver, moveSpeed, dt)) {
                            solver.phase = Ponder.WEIGH
                            solver.timer = 0f
                        }
                    }

                    Ponder.WEIGH -> {
                        if (solver.piece < 0 || placed[solver.piece] || solver.piece == heldPiece) {
                            solver.piece = -1
                            solver.doubt = 1f
                            solver.timer = restTime * 0.6f
                            solver.phase = Ponder.REST
                        } else {
                            solver.timer += dt
                            solver.confidence += thinkRate * dt
                            solver.thought -= dt
                            if (solver.thought <= 0f) {
                                solver.thought = thoughtGap
                                val was = solver.guess
                                solver.guess = guessSocket(layout, filled, solver.piece, accuracy, random)
                                when {
                                    was < 0 -> Unit
                                    // Changing its mind costs it, and shows: the pips drop back.
                                    solver.guess != was -> {
                                        solver.doubt = 1f
                                        solver.confidence = (solver.confidence - 0.38f).coerceAtLeast(0f)
                                    }
                                    // Landing on the same answer twice is what conviction is.
                                    else -> solver.confidence += 0.20f
                                }
                            }
                            if (solver.timer >= ponderLimit) {
                                // It could not talk itself into anything. Put it down again.
                                solver.doubt = 1f
                                solver.guess = -1
                                solver.piece = -1
                                solver.timer = restTime
                                solver.phase = Ponder.REST
                            } else if (solver.confidence >= 1f && solver.guess >= 0) {
                                val slot = layout.socketSlots[solver.guess]
                                solver.tx = slot.cx
                                solver.ty = slot.cy - slot.h * 0.34f
                                solver.hesitation = 0f
                                solver.phase = Ponder.CARRY
                            }
                        }
                    }

                    Ponder.CARRY -> {
                        if (solver.piece < 0 || placed[solver.piece] || solver.piece == heldPiece) {
                            solver.piece = -1
                            solver.doubt = 1f
                            solver.timer = restTime * 0.6f
                            solver.phase = Ponder.REST
                        } else if (solver.hesitation > 0f) {
                            // A second thought, halfway there. Stop, look back at the tray.
                            solver.hesitation -= dt
                        } else {
                            // The less sure it is, the more likely it stalls on the way over.
                            if (solver.doubt <= 0f && random.nextFloat() < dt * (1.4f - mind)) {
                                solver.hesitation = 0.45f
                                solver.doubt = 0.8f
                            }
                            if (advance(solver, moveSpeed * 0.9f, dt)) {
                                solver.phase = Ponder.LAND
                                solver.timer = 0.24f
                            }
                        }
                    }

                    Ponder.LAND -> {
                        solver.timer -= dt
                        if (solver.timer <= 0f) {
                            val hole = solver.guess
                            val piece = solver.piece
                            val shape = if (piece >= 0) layout.pieces[piece] else null
                            when {
                                piece < 0 || hole < 0 -> {
                                    solver.phase = Ponder.REST
                                    solver.timer = restTime
                                }
                                // It cannot act yet: it holds the piece up to the hole, thinks
                                // better of it, and puts it back. This is the whole low-intellect
                                // performance, and it is meant to be endearing rather than sad.
                                !mayPlace -> {
                                    solver.doubt = 1f
                                    solver.piece = -1
                                    solver.guess = -1
                                    solver.phase = Ponder.RECOIL
                                    solver.timer = 0.55f
                                }
                                filled[hole] != EMPTY -> {
                                    flashText = "YOU GOT THERE FIRST"
                                    flashColor = NeoColors.NeonYellow
                                    flashTick += 1
                                    announcement = "$petName found that hole already filled. " +
                                        progressLine(filled, layout.size)
                                    solver.doubt = 1f
                                    solver.piece = -1
                                    solver.guess = -1
                                    solver.phase = Ponder.RECOIL
                                    solver.timer = 0.5f
                                }
                                layout.homeOf(piece) == hole -> {
                                    filled[hole] = BY_PET
                                    placed[piece] = true
                                    petSolved += 1
                                    points += PET_POINTS
                                    solver.cheer = 1f
                                    solver.confidence = 0f
                                    solver.piece = -1
                                    solver.guess = -1
                                    solver.phase = Ponder.REST
                                    solver.timer = restTime
                                    // A different sound from the player's, deliberately: who
                                    // fitted the piece has to be audible without looking.
                                    if (ui.config.soundEnabled) {
                                        ChiptuneEngine.play(Sfx.CONFIRM, pitch = 1.15f)
                                    }
                                    announcement = "$petName fitted the ${shape?.label ?: "piece"}. " +
                                        progressLine(filled, layout.size)
                                }
                                else -> {
                                    petMisjudged += 1
                                    wrongSocket = hole
                                    wrongTimer = 0.5f
                                    solver.doubt = 1f
                                    solver.confidence = 0f
                                    solver.piece = -1
                                    solver.guess = -1
                                    solver.phase = Ponder.RECOIL
                                    solver.timer = 0.6f
                                    if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.DENY, pitch = 1.25f)
                                    announcement = "$petName tried the ${shape?.label ?: "piece"} " +
                                        "in the ${layout.sockets[hole].label} hole."
                                }
                            }
                        }
                    }

                    Ponder.RECOIL -> {
                        solver.timer -= dt
                        solver.y = (solver.y + dt * 0.10f).coerceAtMost(0.94f)
                        if (solver.timer <= 0f) {
                            solver.timer = restTime * 0.8f
                            solver.phase = Ponder.REST
                        }
                    }
                }

                solver.facing = if (solver.tx > solver.x + 0.002f) {
                    1f
                } else if (solver.tx < solver.x - 0.002f) {
                    -1f
                } else {
                    solver.facing
                }

                if (clearing < 0f) {
                    var open = 0
                    for (i in 0 until layout.size) if (filled[i] == EMPTY) open += 1
                    if (open == 0) {
                        clearing = BOARD_PAUSE
                        solver.cheer = 1f
                        points += BOARD_BONUS
                        flashText = "BOARD CLEAR"
                        flashColor = NeoColors.NeonGreen
                        flashTick += 1
                    }
                } else {
                    clearing -= dt
                    if (clearing <= 0f) {
                        boardsCleared += 1
                        if (boardsCleared >= TARGET_BOARDS) {
                            won = true
                            finished = true
                        } else {
                            announcement = "Board ${boardsCleared + 1} of $TARGET_BOARDS."
                            board += 1
                        }
                        stop = true
                    }
                }
            }
            if (stop || finished) break
        }
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        viewModel.finishGame(
            won = won,
            score = (boardsCleared.toFloat() / TARGET_BOARDS).coerceIn(0f, 1f),
            gameName = "Shape Sorter",
            gameId = GAME_ID,
            points = total,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Shape Sorter",
            left = "Score $points",
            right = if (best > 0) "Best $best" else "Board ${boardsCleared + 1}/$TARGET_BOARDS",
            progress = (elapsed / DURATION).coerceIn(0f, 1f),
            onExit = onExit,
        )
        Spacer(Modifier.height(8.dp))

        Text(
            text = "Board ${boardsCleared + 1} of $TARGET_BOARDS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF12141B)),
            ) {
                val boxW = maxWidth
                val boxH = maxHeight

                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Read the frame clock so the whole scene invalidates each frame.
                    val now = clock
                    val unit = size.minDimension
                    val socketR = unit * (if (layout.size > 4) 0.072f else 0.086f)
                    val pieceR = socketR * 0.86f

                    drawRoundRect(
                        color = Color(0xFF1B1F29),
                        topLeft = Offset(size.width * 0.03f, size.height * 0.03f),
                        size = Size(size.width * 0.94f, size.height * 0.59f),
                        cornerRadius = CornerRadius(unit * 0.05f, unit * 0.05f),
                    )

                    for (i in 0 until layout.size) {
                        val slot = layout.socketSlots[i]
                        val shake = if (wrongSocket == i) {
                            sin(now * 46f) * unit * 0.012f * wrongTimer * motion
                        } else {
                            0f
                        }
                        val cx = slot.cx * size.width + shake
                        val cy = slot.cy * size.height
                        // Rim then hole: two fills make a bevel without a single stroke object.
                        drawPuzzleShape(layout.sockets[i], cx, cy, socketR * 1.14f, Color(0xFF2A303D), path)
                        drawPuzzleShape(layout.sockets[i], cx, cy, socketR, Color(0xFF0B0D13), path)
                        when (filled[i]) {
                            BY_PLAYER -> {
                                drawPuzzleShape(layout.sockets[i], cx, cy, socketR * 0.94f, NeoColors.NeonCyan, path)
                                drawPuzzleShape(layout.sockets[i], cx, cy, socketR * 0.34f, Color(0xFF0B0D13), path)
                            }
                            BY_PET -> {
                                // Filled by the creature: a different colour AND a different
                                // marking, so the two are told apart without relying on hue.
                                drawPuzzleShape(layout.sockets[i], cx, cy, socketR * 0.94f, NeoColors.NeonPurple, path)
                                drawCircle(
                                    color = Color(0xFF0B0D13),
                                    radius = socketR * 0.15f,
                                    center = Offset(cx, cy - socketR * 0.30f),
                                )
                                drawCircle(
                                    color = Color(0xFF0B0D13),
                                    radius = socketR * 0.15f,
                                    center = Offset(cx, cy + socketR * 0.30f),
                                )
                            }
                        }
                        if (wrongSocket == i) {
                            val arm = socketR * 0.9f
                            val fade = (wrongTimer * 2f).coerceIn(0f, 1f)
                            drawLine(
                                color = NeoColors.NeonRed.copy(alpha = fade),
                                start = Offset(cx - arm, cy - arm),
                                end = Offset(cx + arm, cy + arm),
                                strokeWidth = unit * 0.012f,
                            )
                            drawLine(
                                color = NeoColors.NeonRed.copy(alpha = fade),
                                start = Offset(cx + arm, cy - arm),
                                end = Offset(cx - arm, cy + arm),
                                strokeWidth = unit * 0.012f,
                            )
                        }
                    }

                    // The tether: what the creature is looking at, as a line of dots so it reads
                    // as attention rather than as a leash.
                    val fromX = solver.x * size.width
                    val fromY = solver.y * size.height
                    val onPiece = solver.piece >= 0 &&
                        (solver.phase == Ponder.APPROACH || solver.phase == Ponder.WEIGH)
                    if (onPiece) {
                        val slot = layout.pieceSlots[solver.piece]
                        drawTether(fromX, fromY, slot.cx * size.width, slot.cy * size.height, unit, 0.30f)
                    }
                    if (solver.guess >= 0 && solver.phase != Ponder.REST) {
                        val slot = layout.socketSlots[solver.guess]
                        // Fainter while it is still weighing up, solid once it has committed.
                        val weight = if (solver.phase == Ponder.CARRY) 0.42f else 0.20f
                        drawTether(fromX, fromY, slot.cx * size.width, slot.cy * size.height, unit, weight)
                    }

                    for (i in 0 until layout.size) {
                        if (placed[i]) continue
                        if (solver.piece == i && solver.phase == Ponder.CARRY) continue
                        val slot = layout.pieceSlots[i]
                        val held = heldPiece == i
                        val bob = sin(now * 2.1f + i * 1.7f) * pieceR * 0.10f * motion
                        val cx = slot.cx * size.width
                        val lift = if (held) pieceR * 0.45f else 0f
                        val cy = slot.cy * size.height + bob - lift
                        if (held) {
                            drawCircle(
                                color = NeoColors.NeonCyan.copy(alpha = 0.22f),
                                radius = pieceR * 1.55f,
                                center = Offset(cx, cy),
                            )
                        }
                        drawPuzzleShape(layout.pieces[i], cx, cy + pieceR * 0.12f, pieceR, Color(0xFF11131A), path)
                        drawPuzzleShape(
                            layout.pieces[i],
                            cx,
                            cy,
                            pieceR,
                            if (held) NeoColors.NeonCyan else Color(0xFF8FA0BC),
                            path,
                        )
                    }

                    val cx = solver.x * size.width
                    val cy = solver.y * size.height
                    val bob = sin(now * 3.4f) * unit * 0.010f * motion
                    val jitter = if (solver.doubt > 0f) sin(now * 34f) * unit * 0.008f * solver.doubt * motion else 0f

                    if (solver.piece >= 0 && solver.phase == Ponder.CARRY) {
                        drawPuzzleShape(
                            layout.pieces[solver.piece],
                            cx + jitter,
                            cy - unit * 0.075f + bob,
                            pieceR,
                            NeoColors.NeonPurple,
                            path,
                        )
                    }

                    drawCreature(
                        center = Offset(cx + jitter, cy + bob + unit * 0.055f),
                        unit = unit * 0.30f,
                        spec = if (solver.cheer > 0.1f) pleasedSpec else restingSpec,
                        frame = CreatureFrame(
                            bobY = -solver.cheer * 0.10f * motion,
                            eyeOpen = if (solver.phase == Ponder.WEIGH) 0.55f else 1f,
                            mouthOpen = solver.cheer * 0.7f,
                            lean = solver.doubt * sin(now * 9f) * 7f * motion,
                            gaze = solver.facing * 0.8f,
                            gazeY = if (solver.phase == Ponder.WEIGH) -0.4f else 0f,
                        ),
                    )

                    // The bubble is the hypothesis made visible: the hole it currently believes
                    // in, or an ellipsis while it is still looking. It flickers when it wavers.
                    if (solver.phase == Ponder.WEIGH || solver.phase == Ponder.CARRY) {
                        drawThoughtBubble(
                            cx = cx + unit * 0.10f + jitter,
                            cy = cy - unit * 0.14f + bob,
                            radius = unit * 0.062f,
                            shape = if (solver.guess >= 0) layout.sockets[solver.guess] else null,
                            doubt = solver.doubt,
                            confidence = solver.confidence,
                            unit = unit,
                            path = path,
                        )
                    }
                }

                // Transparent hit targets laid over the Canvas, from the same slot table the
                // drawing uses, so touch and paint cannot drift apart.
                for (i in 0 until layout.size) {
                    val slot = layout.socketSlots[i]
                    val shape = layout.sockets[i]
                    val state = filled[i]
                    val label = when (state) {
                        BY_PLAYER -> "${shape.label} hole, fitted by you"
                        BY_PET -> "${shape.label} hole, fitted by $petName"
                        else -> "${shape.label} hole, empty"
                    }
                    Box(
                        modifier = Modifier
                            .slotTarget(slot, boxW, boxH)
                            .clickable(
                                enabled = started && !finished && state == EMPTY,
                                onClickLabel = "Place the held piece",
                                role = Role.Button,
                            ) {
                                val piece = heldPiece
                                if (piece < 0) {
                                    announcement = "Pick up a piece first."
                                    return@clickable
                                }
                                if (placed[piece]) return@clickable
                                if (layout.homeOf(piece) == i) {
                                    filled[i] = BY_PLAYER
                                    placed[piece] = true
                                    heldPiece = -1
                                    playerSolved += 1
                                    playerOnThisBoard += 1
                                    points += PLAYER_POINTS
                                    solver.cheer = 1f
                                    if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_HIT)
                                    announcement = "You fitted the ${layout.pieces[piece].label}. " +
                                        progressLine(filled, layout.size)
                                } else {
                                    heldPiece = -1
                                    wrongSocket = i
                                    wrongTimer = 0.5f
                                    points = (points - PLAYER_MISTAKE_COST).coerceAtLeast(0)
                                    if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.GAME_MISS)
                                    flashText = "NOT THAT ONE"
                                    flashColor = NeoColors.NeonRed
                                    flashTick += 1
                                    announcement = "The ${layout.pieces[piece].label} does not go " +
                                        "in the ${shape.label} hole."
                                }
                            }
                            .semantics(mergeDescendants = true) { contentDescription = label },
                    )
                }

                for (i in 0 until layout.size) {
                    if (placed[i]) continue
                    val slot = layout.pieceSlots[i]
                    val shape = layout.pieces[i]
                    val held = heldPiece == i
                    Box(
                        modifier = Modifier
                            .slotTarget(slot, boxW, boxH)
                            .clickable(
                                enabled = started && !finished,
                                onClickLabel = if (held) "Put down" else "Pick up",
                                role = Role.Button,
                            ) {
                                if (placed[i]) return@clickable
                                heldPiece = if (held) -1 else i
                                if (solver.piece == i && solver.phase != Ponder.REST) {
                                    // Taken out from under it. It looks up, and starts over.
                                    solver.piece = -1
                                    solver.guess = -1
                                    solver.doubt = 1f
                                    solver.timer = 0.5f
                                    solver.phase = Ponder.REST
                                }
                                if (ui.config.soundEnabled) ChiptuneEngine.play(Sfx.SELECT)
                            }
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (held) {
                                    "${shape.label} piece, in hand"
                                } else {
                                    "${shape.label} piece, in the tray"
                                }
                            },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                JudgementFlash(text = flashText, color = flashColor, tick = flashTick)
            }

            BestChip(best = best, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))

            if (!started && !finished) {
                CountdownGate(onReady = { started = true })
            }
        }

        Spacer(Modifier.height(8.dp))
        // The one line a screen reader hears from: it always names who fitted the piece.
        Text(
            text = announcement.ifBlank { "Tap a piece, then tap the hole it belongs in." },
            style = MaterialTheme.typography.bodyMedium,
            color = NeoColors.NeonCyan,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = when {
                !bright -> "$petName is watching. The shapes start to click at ${HELP_AT.toInt()} intellect."
                !willing -> "$petName can see the answers, but autonomy is off."
                pet.autonomy == Autonomy.ASSIST -> "$petName will join in once you have started the board."
                else -> "$petName is solving alongside you. Sit back, or race it."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (finished) {
            GameResult(
                title = if (won) "TRAY EMPTY!" else "TIME UP",
                lines = listOf(
                    "Score $total" + if (total > best) "  ★ NEW RECORD" else "",
                    "You fitted $playerSolved  ·  $petName fitted $petSolved",
                    "Taught bonus $taught  ·  $petName misjudged $petMisjudged",
                    "Boards cleared $boardsCleared of $TARGET_BOARDS",
                ),
                onExit = onExit,
            )
        }
    }
}

/** The creature as this game draws it: hatched, whatever the save says, and one of two faces. */
private fun creatureSpec(pet: PetState, mood: Mood): CreatureSpec = CreatureSpec(
    species = pet.species,
    stage = if (pet.stage == LifeStage.EGG) LifeStage.BABY else pet.stage,
    branch = pet.branch,
    mood = mood,
    hatId = pet.equippedHat,
    weightGrams = pet.weightGrams,
)

/**
 * Places a transparent touch target over a slot, never smaller than the 48dp minimum and never
 * hanging off the edge of the playfield.
 */
private fun Modifier.slotTarget(slot: Slot, boxW: Dp, boxH: Dp): Modifier {
    val w = (boxW * slot.w).coerceAtLeast(MinTarget).coerceAtMost(boxW)
    val h = (boxH * slot.h).coerceAtLeast(MinTarget).coerceAtMost(boxH)
    val x = (boxW * slot.cx - w / 2f).coerceIn(0.dp, (boxW - w).coerceAtLeast(0.dp))
    val y = (boxH * slot.cy - h / 2f).coerceIn(0.dp, (boxH - h).coerceAtLeast(0.dp))
    return this.offset(x = x, y = y).size(width = w, height = h)
}

/** "3 of 5 pieces in." Appended to every announcement so progress is heard, not just seen. */
private fun progressLine(filled: List<Int>, size: Int): String {
    var count = 0
    for (i in 0 until size) if (filled[i] != EMPTY) count += 1
    return "$count of $size pieces in."
}

/**
 * Deals a board: distinct shapes, shuffled once for the holes and again for the tray, plus the
 * geometry both halves are drawn and tapped through.
 */
private fun buildBoard(random: Random, count: Int): PuzzleBoard {
    val chosen = PuzzleShape.entries.shuffled(random).take(count)
    val sockets = chosen.shuffled(random)
    val pieces = chosen.shuffled(random)
    return PuzzleBoard(
        sockets = sockets,
        pieces = pieces,
        socketSlots = gridSlots(count, columnsFor(count), 0.08f, 0.56f),
        pieceSlots = gridSlots(count, columnsFor(count), 0.66f, 0.98f),
    )
}

/** Never more than three across, so a hole stays a comfortable thumb target on a narrow phone. */
private fun columnsFor(count: Int): Int = if (count <= 3) count else (count + 1) / 2

/** Lays [count] cells into a centred grid between [top] and [bottom], in 0..1 playfield space. */
private fun gridSlots(count: Int, columns: Int, top: Float, bottom: Float): List<Slot> {
    val rows = (count + columns - 1) / columns
    val cellH = (bottom - top) / rows
    val cellW = 1f / columns
    return List(count) { i ->
        val row = i / columns
        val column = i % columns
        // The final row is centred on its own width, so a five-piece board is not lopsided.
        val inRow = if (row == rows - 1) count - row * columns else columns
        val left = (1f - inRow * cellW) / 2f + column * cellW
        Slot(left + cellW / 2f, top + row * cellH + cellH / 2f, cellW, cellH)
    }
}

/** The next loose piece worth thinking about, skipping anything the keeper is holding. */
private fun pickPiece(board: PuzzleBoard, placed: List<Boolean>, held: Int, random: Random): Int {
    var free = 0
    for (i in 0 until board.size) if (!placed[i] && i != held) free += 1
    if (free == 0) return -1
    var target = random.nextInt(free)
    for (i in 0 until board.size) {
        if (placed[i] || i == held) continue
        if (target == 0) return i
        target -= 1
    }
    return -1
}

/**
 * One guess at where a piece goes.
 *
 * Right with probability [accuracy] and no higher — a creature that is always right is a
 * cutscene. A wrong guess prefers a hole of a genuinely similar shape, because "it mixed up the
 * square and the diamond" is a mistake you can forgive and learn the creature by.
 */
private fun guessSocket(
    board: PuzzleBoard,
    filled: List<Int>,
    piece: Int,
    accuracy: Float,
    random: Random,
): Int {
    val home = board.homeOf(piece)
    if (home >= 0 && filled[home] == EMPTY && random.nextFloat() < accuracy) return home
    val shape = board.pieces[piece]
    var near = 0
    for (i in 0 until board.size) {
        if (i == home || filled[i] != EMPTY) continue
        if (confusable(shape, board.sockets[i])) near += 1
    }
    if (near > 0) {
        var target = random.nextInt(near)
        for (i in 0 until board.size) {
            if (i == home || filled[i] != EMPTY) continue
            if (!confusable(shape, board.sockets[i])) continue
            if (target == 0) return i
            target -= 1
        }
    }
    var open = 0
    for (i in 0 until board.size) if (i != home && filled[i] == EMPTY) open += 1
    if (open > 0) {
        var target = random.nextInt(open)
        for (i in 0 until board.size) {
            if (i == home || filled[i] != EMPTY) continue
            if (target == 0) return i
            target -= 1
        }
    }
    return if (home >= 0 && filled[home] == EMPTY) home else -1
}

/** Steps the creature toward its target. Returns true on the frame it arrives. */
private fun advance(solver: Solver, speed: Float, dt: Float): Boolean {
    val dx = solver.tx - solver.x
    val dy = solver.ty - solver.y
    val distance = sqrt(dx * dx + dy * dy)
    val step = speed * dt
    if (distance <= step || distance <= 0.0005f) {
        solver.x = solver.tx
        solver.y = solver.ty
        return true
    }
    solver.x += dx / distance * step
    solver.y += dy / distance * step
    return false
}

/**
 * A line of fading dots from the creature to whatever currently has its attention. Dots rather
 * than a line, because a leash looks like a rule and attention is not one.
 */
private fun DrawScope.drawTether(fromX: Float, fromY: Float, toX: Float, toY: Float, unit: Float, weight: Float) {
    for (d in 1 until 9) {
        val t = d / 9f
        drawCircle(
            color = NeoColors.NeonPurple.copy(alpha = weight * (1f - t * 0.7f)),
            radius = unit * 0.007f,
            center = Offset(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t),
        )
    }
}

/**
 * The creature's current hypothesis, drawn where the player can read it: the hole it means to
 * try, or an ellipsis while it is still deciding, over a row of pips that fills as it convinces
 * itself and drops back when it changes its mind.
 */
private fun DrawScope.drawThoughtBubble(
    cx: Float,
    cy: Float,
    radius: Float,
    shape: PuzzleShape?,
    doubt: Float,
    confidence: Float,
    unit: Float,
    path: Path,
) {
    val wobble = if (doubt > 0f) doubt * radius * 0.10f else 0f
    drawCircle(color = Color(0xFF1E2431), radius = radius * 0.22f, center = Offset(cx - radius * 0.9f, cy + radius * 1.0f))
    drawCircle(color = Color(0xFF1E2431), radius = radius * 0.32f, center = Offset(cx - radius * 0.6f, cy + radius * 0.7f))
    drawCircle(color = Color(0xFF1E2431), radius = radius + wobble, center = Offset(cx, cy))
    if (shape == null) {
        for (d in 0 until 3) {
            drawCircle(
                color = NeoColors.OnDarkMuted,
                radius = radius * 0.11f,
                center = Offset(cx + (d - 1) * radius * 0.42f, cy),
            )
        }
    } else {
        drawPuzzleShape(
            shape,
            cx,
            cy,
            radius * 0.58f,
            NeoColors.NeonPurple.copy(alpha = 1f - doubt * 0.55f),
            path,
        )
    }
    // Five pips of conviction. Nothing is placed until all five are lit.
    val pipW = unit * 0.014f
    val filledPips = (confidence * 5f).toInt().coerceIn(0, 5)
    for (d in 0 until 5) {
        drawRect(
            color = if (d < filledPips) NeoColors.NeonYellow else Color(0xFF39404F),
            topLeft = Offset(cx - pipW * 4.5f + d * pipW * 2.2f, cy + radius * 1.35f),
            size = Size(pipW * 1.6f, pipW * 1.6f),
        )
    }
}

/** One silhouette, filled. Polygons reuse a single [path] so a frame allocates nothing. */
private fun DrawScope.drawPuzzleShape(
    shape: PuzzleShape,
    cx: Float,
    cy: Float,
    radius: Float,
    color: Color,
    path: Path,
) {
    when (shape) {
        PuzzleShape.CIRCLE -> drawCircle(color = color, radius = radius, center = Offset(cx, cy))
        PuzzleShape.SQUARE -> drawRect(
            color = color,
            topLeft = Offset(cx - radius * 0.84f, cy - radius * 0.84f),
            size = Size(radius * 1.68f, radius * 1.68f),
        )
        PuzzleShape.CROSS -> {
            val arm = radius * 0.32f
            drawRect(color = color, topLeft = Offset(cx - arm, cy - radius), size = Size(arm * 2f, radius * 2f))
            drawRect(color = color, topLeft = Offset(cx - radius, cy - arm), size = Size(radius * 2f, arm * 2f))
        }
        PuzzleShape.TRIANGLE -> {
            polygon(path, cx, cy + radius * 0.12f, radius * 1.1f, 3)
            drawPath(path, color)
        }
        PuzzleShape.DIAMOND -> {
            polygon(path, cx, cy, radius * 1.08f, 4)
            drawPath(path, color)
        }
        PuzzleShape.HEXAGON -> {
            polygon(path, cx, cy, radius, 6)
            drawPath(path, color)
        }
        PuzzleShape.STAR -> {
            star(path, cx, cy, radius * 1.12f)
            drawPath(path, color)
        }
    }
}

/** Regular polygon with a point at the top, rebuilt in place. */
private fun polygon(path: Path, cx: Float, cy: Float, radius: Float, sides: Int) {
    path.reset()
    val turn = (2.0 * PI).toFloat() / sides
    for (i in 0 until sides) {
        val angle = -(PI / 2.0).toFloat() + i * turn
        val px = cx + cos(angle) * radius
        val py = cy + sin(angle) * radius
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

/** Five-pointed star, rebuilt in place. */
private fun star(path: Path, cx: Float, cy: Float, radius: Float) {
    path.reset()
    val turn = (PI / 5.0).toFloat()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else radius * 0.45f
        val angle = -(PI / 2.0).toFloat() + i * turn
        val px = cx + cos(angle) * r
        val py = cy + sin(angle) * r
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

/**
 * A seed that depends only on the pet, never on the wall clock.
 *
 * The same creature at the same second of its life deals itself the same four boards, which is
 * what makes a run something you can argue about afterwards.
 */
private fun puzzleSeed(pet: PetState): Long =
    pet.name.hashCode().toLong() * 1_000_003L xor
        (pet.ageSeconds * 31L) xor
        (pet.gamesPlayed.toLong() shl 17)
