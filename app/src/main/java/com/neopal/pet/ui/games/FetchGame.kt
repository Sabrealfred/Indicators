package com.neopal.pet.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawItem
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val GAME_ID = "fetch"

// ---- session shape -------------------------------------------------------------------------
private const val THROWS = 6
private const val DURATION = 60f
/** Points that count as a full-marks run when the result is normalised for [PetViewModel.finishGame]. */
private const val TARGET_POINTS = 4600f

// ---- physics -------------------------------------------------------------------------------
//
// One unit of x is the width of the field; one unit of h is a fraction of its height. The two
// axes are deliberately not the same length in pixels — this is an arcade arc, not ballistics —
// but every constant below is in those units and nothing reads a pixel size, so the flight is
// identical on a tablet and on a small phone.
/** Downward acceleration, field-heights per second squared. */
private const val GRAVITY = 2.2f
private const val LAUNCH_X = 0.12f
/** Hand height at the moment of release. */
private const val LAUNCH_H = 0.17f
private const val MIN_POWER = 0.45f
private const val MAX_POWER = 1.15f
private const val MIN_ANGLE = 12f
private const val MAX_ANGLE = 80f
/** Vertical restitution. Anything springier than this and a long throw never settles. */
private const val BOUNCE = 0.42f
private const val ROLL_FRICTION = 0.80f
/** Below this vertical speed the ball stops bouncing and rolls. */
private const val REST_SPEED = 0.18f
private const val LEFT_EDGE = 0.05f
private const val RIGHT_EDGE = 0.955f
/** How close the creature's snout has to be to take the ball. */
private const val CATCH_RADIUS = 0.045f
/**
 * How high the creature can reach.
 *
 * Without this a ball merely passing overhead counts as caught, which turns every throw into an
 * instant snatch two paces from the hand and quietly deletes the run — and with it the whole
 * point of the athletic genes.
 */
private const val REACH_H = 0.15f

/**
 * The fixed physics timestep, and the largest frame the loop will ever swallow.
 *
 * A raw frame delta is not integrated anywhere in this file. A dropped frame during a launch
 * animation would otherwise hand the integrator a tenth of a second in one go, which turns a
 * bounce into a teleport and a chase into an overshoot.
 */
private const val STEP = 1f / 120f
private const val MAX_FRAME = 0.05f
private const val MAX_SUBSTEPS = 6

// ---- the read ------------------------------------------------------------------------------
/** A habit-model miss this large means the creature has learned nothing useful yet. */
private const val ERR_REF = 0.26f
private const val ERR_ALPHA = 0.40f
private const val MEAN_ALPHA = 0.45f
/** The error the model is credited with after its very first throw, when it had no habit to go on. */
private const val PRIMED_ERROR = 0.14f
/** Damps the read over the first couple of throws, so one lucky guess is not "it has you sussed". */
private const val READ_SETTLE = 0.8f
/** Predicting the resting place this closely counts as a read, and pays. */
private const val READ_HIT = 0.09f

// ---- layout --------------------------------------------------------------------------------
/** Ground line as a fraction of the playfield height. */
private const val GROUND_Y = 0.86f
/** Converts a height in physics units to a fraction of the playfield height. */
private const val HEIGHT_SCALE = 0.72f
private const val TWO_PI = 6.2831855f
private const val TRAIL = 10
private const val DUST = 8

/** What the pair of them is doing right now. One fetch walks the whole cycle and comes back. */
private enum class FetchPhase { AIM, FLIGHT, CARRY }

/**
 * The whole simulation, in one long-lived object.
 *
 * It is a plain class rather than a pile of Compose state on purpose: the frame loop mutates it
 * sixty to a hundred and twenty times a second and must not allocate or invalidate anything
 * while it does. The composition subscribes to a single clock value instead, and reads these
 * fields when it draws.
 */
private class FetchSim(seed: Int) {
    /** Seeded, so the same pet scuffing the same ground kicks up the same dust twice. */
    private val random = Random(seed)

    var phase = FetchPhase.AIM
    var ballX = LAUNCH_X
    var ballH = LAUNCH_H
    var ballVx = 0f
    var ballVh = 0f
    var ballSpin = 0f
    var ballResting = true

    var petX = LAUNCH_X
    var petV = 0f
    /** Radians of gait cycle; one full turn is one stride. */
    var gait = 0f
    private var lastStep = 0

    // ---- what it has worked out about you ----
    /** Exponential mean of where throws come to rest: the habit the creature is learning. */
    var meanLanding = 0.42f
    /** Exponential mean of how far that habit alone misses by. */
    var meanError = ERR_REF
    var samples = 0

    /** Where this throw will actually stop, known to the physics the instant the ball leaves. */
    var predicted = 0.42f
    /** Where the creature *believes* it will stop. Drawn on the ground as the hunch marker. */
    var anticipated = 0.42f
    /** Ground covered before the ball was even released. The visible half of anticipation. */
    var headStart = 0f
    /**
     * How much of the hunch the creature is still acting on.
     *
     * It commits to the guess for as long as the ball is still moving, and lets go of it once
     * the ball has stopped and it can simply see where the thing is. Without that surrender a
     * wrong hunch is fatal: the creature stands on the spot it predicted while the ball lies
     * somewhere behind it, and the fetch never ends.
     */
    var belief = 0f
    var fetchClock = 0f
    var caughtInAir = false

    // ---- one-shot events, drained by the frame loop ----
    var eventThrown = false
    var eventBounce = false
    var eventCaught = false
    var eventDelivered = false

    /** Set by either input path; consumed on the next physics step so the throw lands on a tick. */
    var pendingThrow = false
    var pendingAngle = 42f
    var pendingPower = 0.80f

    /** Ring buffer of x, h pairs for the ball's trail. Allocated once, overwritten forever. */
    val trail = FloatArray(TRAIL * 2)
    private var trailHead = 0
    private var trailClock = 0f
    /** Ring buffer of x, height, age triples for foot dust. */
    val dust = FloatArray(DUST * 3)
    private var dustHead = 0

    var accumulator = 0f

    /** 0..1. How far the creature trusts its own model of your arm. */
    val confidence: Float
        get() {
            if (samples == 0) return 0f
            val accuracy = (1f - meanError / ERR_REF).coerceIn(0f, 1f)
            return accuracy * (samples / (samples + READ_SETTLE))
        }

    /** Home is a step to the right of the launch spot, so the creature never covers the hand. */
    private val home: Float get() = LAUNCH_X + 0.02f

    /**
     * Where a throw at [angle] degrees and [power] would come to rest.
     *
     * Run once, at release, with the same integrator and the same timestep the live ball uses,
     * so the answer is the answer — not an estimate the visible ball can then contradict.
     */
    fun restingPlaceOf(angle: Float, power: Float): Float {
        val radians = angle * PI.toFloat() / 180f
        var x = LAUNCH_X
        var h = LAUNCH_H
        var vx = cos(radians) * power
        var vh = sin(radians) * power
        var guard = 0
        while (guard < 2400) {
            guard += 1
            vh -= GRAVITY * STEP
            x += vx * STEP
            h += vh * STEP
            if (x > RIGHT_EDGE) {
                x = RIGHT_EDGE
                vx = -abs(vx) * 0.45f
            }
            if (x < LEFT_EDGE) {
                x = LEFT_EDGE
                vx = abs(vx) * 0.45f
            }
            if (h <= 0f) {
                h = 0f
                if (abs(vh) < REST_SPEED) {
                    vh = 0f
                    vx *= 0.55f
                    if (abs(vx) < 0.03f) return x
                } else {
                    vh = -vh * BOUNCE
                    vx *= ROLL_FRICTION
                }
            }
        }
        return x
    }

    /** True while it is waiting for a throw. */
    val isAiming: Boolean get() = phase == FetchPhase.AIM

    /** True while the ball is loose and it is going after it. */
    val isChasing: Boolean get() = phase == FetchPhase.FLIGHT

    /** True while it has the ball in its mouth and is on its way back. */
    val isCarrying: Boolean get() = phase == FetchPhase.CARRY

    /**
     * What it is doing, as a clause a screen reader can read out.
     *
     * The phase never leaves this class: composition is handed this sentence and the three flags
     * above instead, which keeps the enum in the one place where its exhaustiveness is checked.
     */
    fun phaseSentence(): String = when (phase) {
        FetchPhase.AIM -> "is waiting for a throw."
        FetchPhase.FLIGHT -> "is running after the ball."
        FetchPhase.CARRY -> "is bringing the ball back."
    }

    /** Advances everything by exactly [dt]. Called only with [STEP]. */
    fun step(dt: Float, runSpeed: Float, carrySpeed: Float, stride: Float, winding: Boolean) {
        if (pendingThrow) release()
        when (phase) {
            FetchPhase.AIM -> stepAim(dt, runSpeed, winding)
            FetchPhase.FLIGHT -> stepFlight(dt, runSpeed)
            FetchPhase.CARRY -> stepCarry(dt, carrySpeed)
        }
        stepGait(dt, stride)
        ageDust(dt)
    }

    /** Consumes a queued throw. The ball leaves the hand on a physics tick, never on a gesture. */
    private fun release() {
        pendingThrow = false
        val radians = pendingAngle * PI.toFloat() / 180f
        ballX = LAUNCH_X
        ballH = LAUNCH_H
        ballVx = cos(radians) * pendingPower
        ballVh = sin(radians) * pendingPower
        ballResting = false
        ballSpin = 0f
        predicted = restingPlaceOf(pendingAngle, pendingPower)
        // The belief: its own habit, dragged toward the truth by however much it trusts itself.
        anticipated = meanLanding + (predicted - meanLanding) * confidence
        headStart = petX - home
        belief = confidence
        fetchClock = 0f
        caughtInAir = false
        phase = FetchPhase.FLIGHT
        eventThrown = true
        var i = 0
        while (i < TRAIL) {
            trail[i * 2] = ballX
            trail[i * 2 + 1] = ballH
            i += 1
        }
    }

    /**
     * Waiting for a throw — and, once it has your measure, easing out towards where it expects
     * the next one to land while you are still winding up.
     */
    private fun stepAim(dt: Float, runSpeed: Float, winding: Boolean) {
        val read = confidence
        val target = if (winding && read > 0.15f) {
            // Hedged twice over: by the read, and by a ceiling that stops it ever standing on
            // the spot before the ball is thrown. However well it has you worked out, it still
            // has to run the last third — which is where the legs and the lungs show.
            home + (meanLanding - home) * read * 0.65f
        } else {
            home
        }
        // A creeping trot rather than a sprint: this is a guess, and it reads as one.
        drive(target, runSpeed * (0.35f + read * 0.45f), dt)
    }

    /** The ball is in the air or rolling; the creature is going after it. */
    private fun stepFlight(dt: Float, runSpeed: Float) {
        fetchClock += dt
        if (!ballResting) {
            ballVh -= GRAVITY * dt
            ballX += ballVx * dt
            ballH += ballVh * dt
            ballSpin += ballVx * 900f * dt
            if (ballX > RIGHT_EDGE) {
                ballX = RIGHT_EDGE
                ballVx = -abs(ballVx) * 0.45f
            }
            if (ballX < LEFT_EDGE) {
                ballX = LEFT_EDGE
                ballVx = abs(ballVx) * 0.45f
            }
            if (ballH <= 0f) {
                ballH = 0f
                if (abs(ballVh) < REST_SPEED) {
                    ballVh = 0f
                    ballVx *= 0.55f
                    if (abs(ballVx) < 0.03f) {
                        ballVx = 0f
                        ballResting = true
                    }
                } else {
                    ballVh = -ballVh * BOUNCE
                    ballVx *= ROLL_FRICTION
                    eventBounce = true
                }
            }
        }

        // At no read at all it chases the ball it can see, which is always a step behind. At a
        // full read it goes straight to the spot and waits there — until the ball stops, at
        // which point believing beats looking no longer and it goes to the ball itself.
        if (ballResting) belief = (belief - dt * 1.6f).coerceAtLeast(0f)
        val target = ballX + (anticipated - ballX) * belief
        drive(target, runSpeed, dt)

        val reach = abs(petX - ballX)
        if (reach < CATCH_RADIUS && ballH > 0.03f && ballH < REACH_H && ballVh < 0f) {
            caughtInAir = true
            take()
        } else if (reach < CATCH_RADIUS + 0.012f && ballH <= 0.02f) {
            take()
        }
    }

    /** Ball in mouth, heading home. Carrying costs a little pace, as carrying does. */
    private fun stepCarry(dt: Float, carrySpeed: Float) {
        fetchClock += dt
        drive(home, carrySpeed, dt)
        ballX = petX
        ballH = 0.10f
        if (petX <= home + 0.02f) {
            phase = FetchPhase.AIM
            ballX = LAUNCH_X
            ballH = LAUNCH_H
            eventDelivered = true
        }
    }

    private fun take() {
        phase = FetchPhase.CARRY
        ballResting = true
        ballVx = 0f
        ballVh = 0f
        eventCaught = true
    }

    /**
     * Moves the creature toward [target] at up to [speed].
     *
     * The velocity chases a proportional demand rather than being set outright, so a creature
     * accelerates into a run and settles onto a spot instead of snapping between positions.
     */
    private fun drive(target: Float, speed: Float, dt: Float) {
        val demand = ((target - petX) * 6f).coerceIn(-speed, speed)
        val accel = speed * 3.2f + 0.4f
        val delta = demand - petV
        val change = (accel * dt).coerceAtMost(abs(delta))
        petV += sign(delta) * change
        petX = (petX + petV * dt).coerceIn(LEFT_EDGE, RIGHT_EDGE)
    }

    /**
     * Turns pace into a stride.
     *
     * Cadence is speed divided by stride length, so a leggy creature covering the same ground
     * takes fewer, longer bounds than a stocky one scurrying. That is the gene made visible:
     * the two run at different speeds *and* at different rhythms.
     */
    private fun stepGait(dt: Float, stride: Float) {
        val pace = abs(petV)
        gait += (pace / stride) * TWO_PI * dt
        // Wrapped on a whole cycle: the phase is unchanged by the wrap, and the foot-plant test
        // below stays a simple half-cycle comparison however long the run goes on.
        while (gait >= TWO_PI) gait -= TWO_PI
        val step = if (gait < PI.toFloat()) 0 else 1
        if (step != lastStep) {
            lastStep = step
            if (pace > 0.12f) spawnDust(pace)
        }
    }

    private fun spawnDust(pace: Float) {
        val i = dustHead * 3
        dust[i] = petX - sign(petV) * 0.012f
        dust[i + 1] = 0.01f + random.nextFloat() * 0.02f
        dust[i + 2] = (0.28f + pace * 0.3f).coerceAtMost(0.55f)
        dustHead = (dustHead + 1) % DUST
    }

    private fun ageDust(dt: Float) {
        var i = 0
        while (i < DUST) {
            val life = dust[i * 3 + 2]
            if (life > 0f) {
                dust[i * 3 + 2] = (life - dt).coerceAtLeast(0f)
                dust[i * 3 + 1] += dt * 0.05f
            }
            i += 1
        }
        trailClock += dt
        if (trailClock >= 0.02f) {
            trailClock = 0f
            trail[trailHead * 2] = ballX
            trail[trailHead * 2 + 1] = ballH
            trailHead = (trailHead + 1) % TRAIL
        }
    }

    /**
     * Folds the throw that just finished into the model.
     *
     * The error it scores itself on is the *habit's* error — how far the running mean alone was
     * from the answer — never the blended aim it actually ran to. Scoring the blend would let
     * the read feed on itself: a confident creature aims well by definition, and a player who
     * throws at random would still be "read" within four throws. Judged this way, an erratic
     * arm keeps its confidence pinned near zero, which is the honest result.
     */
    fun learn() {
        if (samples == 0) {
            meanLanding = predicted
            meanError = PRIMED_ERROR
        } else {
            meanError += (abs(meanLanding - predicted) - meanError) * ERR_ALPHA
            meanLanding += (predicted - meanLanding) * MEAN_ALPHA
        }
        samples += 1
    }
}

/**
 * Throw a ball; the creature runs it down and brings it back.
 *
 * Two things are being played at once. The near game is the throw itself — a drag whose
 * direction and speed become an angle and a power, or the same two numbers dialled in on
 * buttons. The far game is the creature: it keeps a running model of where your throws end up,
 * and the better that model gets the earlier it sets off, until it is standing on the spot
 * before the ball arrives. Fetch is not a test of your aim. It is watching something work you
 * out, which is why the guess is drawn on the ground where you can see it being wrong.
 */
@Composable
fun FetchGameScreen(viewModel: PetViewModel, onExit: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    // Frozen at entry: finishGame writes the new record before the result card renders.
    val best = remember { pet.highScores[GAME_ID] ?: 0 }
    val motion = if (ui.config.reducedMotion) 0.3f else 1f

    // The athletic genes, expressed once. Vigor is engine, limbs are gearing.
    val runSpeed = remember(pet.genome.vigor, pet.genome.limbs) {
        (0.20f + pet.genome.vigor * 0.44f) * (0.72f + pet.genome.limbs * 0.72f)
    }
    val strideLength = remember(pet.genome.limbs) { 0.055f + pet.genome.limbs * 0.075f }
    val carrySpeed = remember(runSpeed) { runSpeed * 0.92f }
    val sim = remember(pet.name, pet.generation) { FetchSim(pet.name.hashCode() xor pet.generation) }
    val creature = remember(pet.species, pet.stage, pet.branch, pet.equippedHat, pet.weightGrams) {
        CreatureSpec(
            species = pet.species,
            stage = if (pet.stage == LifeStage.EGG) LifeStage.BABY else pet.stage,
            branch = pet.branch,
            mood = Mood.HAPPY,
            hatId = pet.equippedHat,
            weightGrams = pet.weightGrams,
            morphology = pet.morphology,
        )
    }

    var time by remember { mutableFloatStateOf(0f) }
    var points by remember { mutableIntStateOf(0) }
    var thrown by remember { mutableIntStateOf(0) }
    var retrieved by remember { mutableIntStateOf(0) }
    var snatches by remember { mutableIntStateOf(0) }
    var reads by remember { mutableIntStateOf(0) }
    var readPct by remember { mutableIntStateOf(0) }
    var lastGain by remember { mutableIntStateOf(0) }
    var aimAngle by remember { mutableFloatStateOf(42f) }
    var aimPower by remember { mutableFloatStateOf(0.80f) }
    var winding by remember { mutableStateOf(false) }
    // The two bits of simulation state composition has to see: they gate the throw button and
    // the spoken description, and neither may lag a phase behind.
    var ready by remember { mutableStateOf(true) }
    var petLine by remember { mutableStateOf("is waiting for a throw.") }
    var idle by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashColor by remember { mutableStateOf(NeoColors.NeonCyan) }
    var flashTick by remember { mutableIntStateOf(0) }

    // Anything the frame loop reads that composition owns goes through here. A plain capture in
    // a withFrameNanos body is read once and then goes stale for the life of the loop.
    val sound = rememberUpdatedState(ui.config.soundEnabled)
    val pace = rememberUpdatedState(runSpeed)
    val carry = rememberUpdatedState(carrySpeed)
    val stride = rememberUpdatedState(strideLength)
    val windingNow = rememberUpdatedState(winding)

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        var previous = 0L
        while (!finished) {
            withFrameNanos { now ->
                val raw = if (previous == 0L) STEP else (now - previous) / 1_000_000_000f
                previous = now
                // Clamped, then spent in whole fixed steps. A long frame costs frames, not physics.
                val frame = raw.coerceIn(0f, MAX_FRAME)
                time += frame
                sim.accumulator += frame
                var taken = 0
                while (sim.accumulator >= STEP && taken < MAX_SUBSTEPS) {
                    sim.step(STEP, pace.value, carry.value, stride.value, windingNow.value)
                    sim.accumulator -= STEP
                    taken += 1
                }
                // Never let a backlog build up into a slow-motion catch-up.
                if (taken >= MAX_SUBSTEPS) sim.accumulator = 0f
                if (ready != sim.isAiming) ready = sim.isAiming
                val sentence = sim.phaseSentence()
                if (petLine != sentence) petLine = sentence

                if (sim.eventThrown) {
                    sim.eventThrown = false
                    thrown += 1
                    if (sound.value) ChiptuneEngine.play(Sfx.SELECT, pitch = 0.8f + aimPower * 0.4f)
                    if (sim.headStart > 0.05f) {
                        flash = "READING YOU"
                        flashColor = NeoColors.NeonPurple
                        flashTick += 1
                    }
                }
                if (sim.eventBounce) {
                    sim.eventBounce = false
                    if (sound.value) ChiptuneEngine.play(Sfx.GAME_HIT, pitch = 0.7f)
                }
                if (sim.eventCaught) {
                    sim.eventCaught = false
                    if (sim.caughtInAir) {
                        snatches += 1
                        flash = "SNATCH!"
                        flashColor = NeoColors.NeonYellow
                        flashTick += 1
                    }
                    if (sound.value) ChiptuneEngine.play(Sfx.GAME_HIT, pitch = if (sim.caughtInAir) 1.4f else 1f)
                }
                if (sim.eventDelivered) {
                    sim.eventDelivered = false
                    val readMiss = abs(sim.anticipated - sim.predicted)
                    val anticipated = readMiss < READ_HIT && sim.confidence > 0.25f
                    // Measured on where the throw would have finished, so snatching it out of
                    // the air early never costs the player the distance they earned.
                    val distanceBonus = ((sim.predicted - LAUNCH_X) * 400f).roundToInt()
                    // Weighted heavily on purpose: pace is the genome's contribution to this
                    // game, and a bonus too small to notice would have made vigor decorative.
                    val speedBonus = ((7f - sim.fetchClock) * 70f).coerceIn(0f, 400f).roundToInt()
                    val gain = 100 + distanceBonus + speedBonus +
                        (if (anticipated) 150 else 0) + (if (sim.caughtInAir) 200 else 0)
                    points += gain
                    lastGain = gain
                    retrieved += 1
                    if (anticipated) reads += 1
                    sim.learn()
                    readPct = (sim.confidence * 100f).roundToInt()
                    winding = false
                    if (sound.value) ChiptuneEngine.play(Sfx.COIN, pitch = 1f + retrieved * 0.04f)
                    if (anticipated && !sim.caughtInAir) {
                        flash = "READ +150"
                        flashColor = NeoColors.NeonGreen
                        flashTick += 1
                    }
                }

                if (time >= DURATION || retrieved >= THROWS) finished = true
            }
        }
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        viewModel.finishGame(
            won = retrieved >= THROWS,
            score = (points / TARGET_POINTS).coerceIn(0f, 1f),
            gameName = "Fetch",
            gameId = GAME_ID,
            points = points,
        )
    }

    /** The one way a ball ever leaves the hand: both input paths end up here. */
    fun requestThrow() {
        if (!started || finished || thrown >= THROWS) return
        if (!sim.isAiming || sim.pendingThrow) return
        sim.pendingAngle = aimAngle
        sim.pendingPower = aimPower
        sim.pendingThrow = true
        winding = false
        idle = false
    }

    val canThrow = started && !finished && thrown < THROWS
    val readLine = when {
        retrieved == 0 -> "It has no idea where you throw yet."
        readPct < 25 -> "Still chasing the ball down."
        readPct < 55 -> "It is starting to guess your arc."
        else -> "It is already waiting where you always throw."
    }
    val statusLine = "Score $points. Fetch ${retrieved.coerceAtMost(THROWS)} of $THROWS. Read $readPct per cent."

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        GameHeader(
            title = "Fetch",
            left = "Score $points  ·  $retrieved/$THROWS",
            right = "Read $readPct%",
            progress = (time / DURATION).coerceIn(0f, 1f),
            onExit = onExit,
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF12141B))
                .semantics {
                    contentDescription = "Fetch field. ${pet.name} $petLine $readLine"
                }
                .pointerInput(Unit) {
                    var originX = 0f
                    var originY = 0f
                    var lastLength = 0f
                    var lastTime = 0f
                    var flick = 1f
                    detectDragGestures(
                        onDragStart = {
                            // Every test here is a snapshot-state read, never a value captured
                            // when the gesture was installed: pointerInput(Unit) builds this
                            // lambda once and would otherwise judge the whole game by how things
                            // stood before the countdown finished.
                            if (!started || finished || thrown >= THROWS || !ready) {
                                return@detectDragGestures
                            }
                            originX = 0f
                            originY = 0f
                            lastLength = 0f
                            lastTime = time
                            flick = 1f
                            winding = true
                            idle = false
                        },
                        onDragEnd = {
                            if (winding) requestThrow()
                        },
                        onDragCancel = { winding = false },
                    ) { change, dragAmount ->
                        if (!winding) return@detectDragGestures
                        originX += dragAmount.x
                        originY += dragAmount.y
                        val up = -originY
                        val across = abs(originX)
                        val length = sqrt(across * across + up * up)
                        // Direction is the angle of the drag; length is the base of the power.
                        aimAngle = (atan2(up.coerceAtLeast(0f), across.coerceAtLeast(1f)) * 180f / PI.toFloat())
                            .coerceIn(MIN_ANGLE, MAX_ANGLE)
                        // Speed of the drag multiplies it, so a hard flick goes further than a
                        // slow haul over the same distance. Timed off the simulation clock, so
                        // there is no wall clock anywhere in this screen.
                        val elapsed = (time - lastTime).coerceAtLeast(0.001f)
                        val span = minOf(size.width, size.height).toFloat()
                        val rate = abs(length - lastLength) / elapsed / span
                        flick += ((0.85f + rate * 0.5f).coerceIn(0.85f, 1.2f) - flick) * 0.35f
                        lastLength = length
                        lastTime = time
                        val reach = (length / (span * 0.55f)).coerceIn(0f, 1f)
                        aimPower = (MIN_POWER + reach * (MAX_POWER - MIN_POWER)) * flick
                        aimPower = aimPower.coerceIn(MIN_POWER, MAX_POWER)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Reading the clock is what subscribes this canvas to the simulation: everything
                // else it draws lives in an object Compose cannot see changing.
                val clock = time
                val groundY = size.height * GROUND_Y
                val unit = size.minDimension * 0.42f
                drawField(groundY)

                // The hunch. Drawn wherever the creature currently believes the ball ends up —
                // its habit before a throw, its blended guess after one — and it fades up as the
                // read grows, so a player watches the guess arrive and improve. During a chase it
                // fades with the belief instead, so the moment it gives up on a wrong guess and
                // goes to look for the ball is a thing you can see happen.
                val read = if (sim.isChasing) sim.belief else sim.confidence
                if (read > 0.06f && !sim.isCarrying) {
                    val markX = if (sim.isChasing) sim.anticipated else sim.meanLanding
                    drawHunch(markX * size.width, groundY, size.minDimension, read, clock)
                }

                if (sim.isAiming) {
                    drawAimArc(aimAngle, aimPower, groundY, size.minDimension)
                }

                if (motion > 0.5f && sim.isChasing && !sim.ballResting) {
                    var i = 0
                    while (i < TRAIL) {
                        val fade = (i + 1f) / TRAIL
                        drawCircle(
                            color = NeoColors.NeonYellow.copy(alpha = 0.05f + fade * 0.10f),
                            radius = size.minDimension * 0.012f * fade,
                            center = Offset(
                                trailX(sim.trail, i) * size.width,
                                groundY - trailH(sim.trail, i) * size.height * HEIGHT_SCALE,
                            ),
                        )
                        i += 1
                    }
                }

                var d = 0
                while (d < DUST) {
                    val life = sim.dust[d * 3 + 2]
                    if (life > 0f && motion > 0.5f) {
                        drawCircle(
                            color = Color(0xFF6B7A5A).copy(alpha = life * 0.5f),
                            radius = size.minDimension * (0.006f + (0.55f - life) * 0.02f),
                            center = Offset(
                                sim.dust[d * 3] * size.width,
                                groundY - sim.dust[d * 3 + 1] * size.height * HEIGHT_SCALE,
                            ),
                        )
                    }
                    d += 1
                }

                // The creature. Genes are already in the silhouette through the morphology; the
                // gait puts them in the movement as well.
                val hop = if (abs(sim.petV) > 0.03f) abs(sin(sim.gait)) else 0f
                val lean = (sim.petV * 22f).coerceIn(-18f, 18f) * motion
                drawCreature(
                    center = Offset(sim.petX * size.width, groundY - unit * 0.16f),
                    unit = unit,
                    spec = creature,
                    frame = CreatureFrame(
                        bobY = -hop * 0.05f * motion,
                        squash = 1f + hop * 0.04f * motion,
                        mouthOpen = if (sim.isCarrying) 0.25f else 0.15f + hop * 0.25f,
                        lean = lean,
                        armSwing = sin(sim.gait) * motion,
                        gaze = if (sim.isCarrying) -0.6f else 0.8f,
                        gazeY = if (sim.isChasing && sim.ballH > 0.12f) -0.6f else 0f,
                        tailSwing = sin(clock * 9f) * 0.6f * motion,
                    ),
                )

                // The ball, last, so it reads as being in front of the snout when carried.
                val ballPx = sim.ballX * size.width
                val ballPy = groundY - sim.ballH * size.height * HEIGHT_SCALE
                val ballSize = size.minDimension * 0.11f
                if (sim.ballH > 0.02f) {
                    drawOval(
                        color = Color.Black.copy(alpha = (0.28f - sim.ballH * 0.4f).coerceAtLeast(0.05f)),
                        topLeft = Offset(ballPx - ballSize * 0.28f, groundY - ballSize * 0.06f),
                        size = Size(ballSize * 0.56f, ballSize * 0.14f),
                    )
                }
                rotate(degrees = sim.ballSpin * motion, pivot = Offset(ballPx, ballPy)) {
                    inset(
                        left = ballPx - ballSize / 2f,
                        top = ballPy - ballSize / 2f,
                        right = size.width - (ballPx + ballSize / 2f),
                        bottom = size.height - (ballPy + ballSize / 2f),
                    ) {
                        drawItem("ball", NeoColors.NeonYellow)
                    }
                }
            }

            JudgementFlash(text = flash, color = flashColor, tick = flashTick)

            BestChip(best = best, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))

            if (!started && !finished) {
                CountdownGate(onReady = { started = true })
            }
        }

        Spacer(Modifier.height(8.dp))

        // The read, spelled out. A hidden model that only expresses itself as "the pet feels
        // faster today" is a model nobody believes in.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((readPct / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(NeoColors.NeonPurple),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Read $readPct%",
                style = MaterialTheme.typography.labelSmall,
                color = NeoColors.NeonPurple,
            )
        }

        Text(
            text = readLine + if (lastGain > 0) "  (+$lastGain)" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = statusLine,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Throw",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))

        // The whole throw is available without a drag: two steppers and a button set exactly the
        // same angle and power the gesture does, and the preview arc above shows both the same way.
        // Dialling the aim counts as winding up, exactly as starting a drag does — otherwise the
        // creature would only ever anticipate players who throw with a gesture.
        AimRow(
            label = "Angle",
            value = "${aimAngle.roundToInt()}°",
            enabled = canThrow && ready,
            onLess = {
                aimAngle = (aimAngle - 4f).coerceAtLeast(MIN_ANGLE)
                winding = true
                idle = false
            },
            onMore = {
                aimAngle = (aimAngle + 4f).coerceAtMost(MAX_ANGLE)
                winding = true
                idle = false
            },
        )
        AimRow(
            label = "Power",
            value = "${powerPercent(aimPower)}%",
            enabled = canThrow && ready,
            onLess = {
                aimPower = (aimPower - 0.05f).coerceAtLeast(MIN_POWER)
                winding = true
                idle = false
            },
            onMore = {
                aimPower = (aimPower + 0.05f).coerceAtMost(MAX_POWER)
                winding = true
                idle = false
            },
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { requestThrow() },
            enabled = canThrow && ready,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics {
                    contentDescription =
                        "Throw the ball at ${aimAngle.roundToInt()} degrees, ${powerPercent(aimPower)} per cent power"
                },
        ) {
            Text("THROW", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = if (idle) "Drag up and out to throw — steeper goes higher, faster goes further." else
                "Watch where it decides to stand.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )

        if (finished) {
            GameResult(
                title = if (retrieved >= THROWS) "ALL FETCHED!" else "TIME UP",
                lines = listOf(
                    "Score $points" + if (points > best) "  ★ NEW RECORD" else "",
                    "Fetched $retrieved of $THROWS  ·  $snatches out of the air",
                    "Read $readPct%  ·  anticipated $reads throws",
                ),
                onExit = onExit,
            )
        }
    }
}

/** One labelled value with a minus and a plus. Both targets are a full 48dp square. */
@Composable
private fun AimRow(
    label: String,
    value: String,
    enabled: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = NeoColors.OnDark,
            modifier = Modifier.sizeIn(minWidth = 56.dp),
        )
        StepButton(symbol = "−", description = "Less $label", enabled = enabled, onClick = onLess)
        StepButton(symbol = "+", description = "More $label", enabled = enabled, onClick = onMore)
    }
}

/** A 48dp square nudge button. Text rather than an icon, so it needs no icon pack. */
@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(symbol, style = MaterialTheme.typography.titleMedium, color = NeoColors.OnDark)
    }
}

/** Power as a percentage of the usable range, which is what the label and the read-out both show. */
private fun powerPercent(power: Float): Int =
    (((power - MIN_POWER) / (MAX_POWER - MIN_POWER)) * 100f).roundToInt().coerceIn(0, 100)

/** Reads the x of trail sample [index] out of the flat buffer the loop writes into. */
private fun trailX(trail: FloatArray, index: Int): Float = trail[index * 2]

/** Reads the height of trail sample [index] out of the same buffer. */
private fun trailH(trail: FloatArray, index: Int): Float = trail[index * 2 + 1]

/** Grass, a horizon line and ten-percent distance marks, so a throw has something to measure against. */
private fun DrawScope.drawField(groundY: Float) {
    drawRect(
        color = Color(0xFF16241C),
        topLeft = Offset(0f, groundY),
        size = Size(size.width, size.height - groundY),
    )
    drawRect(
        color = NeoColors.NeonGreen.copy(alpha = 0.30f),
        topLeft = Offset(0f, groundY),
        size = Size(size.width, 2f),
    )
    for (i in 1..9) {
        val x = i * 0.1f * size.width
        drawRect(
            color = Color.White.copy(alpha = if (i % 5 == 0) 0.16f else 0.07f),
            topLeft = Offset(x, groundY + 2f),
            size = Size(1f, size.height * (if (i % 5 == 0) 0.05f else 0.03f)),
        )
    }
    // The mat the throw is made from.
    drawOval(
        color = Color.White.copy(alpha = 0.07f),
        topLeft = Offset(LAUNCH_X * size.width - size.minDimension * 0.09f, groundY - size.minDimension * 0.012f),
        size = Size(size.minDimension * 0.18f, size.minDimension * 0.025f),
    )
}

/**
 * The creature's guess, drawn on the ground: a ring, a slow pulse and four ticks closing in on
 * the spot. Alpha is the read itself, so a confident hunch is bright and an early one is a
 * rumour.
 */
private fun DrawScope.drawHunch(x: Float, groundY: Float, unit: Float, read: Float, clock: Float) {
    val alpha = (read * 0.85f).coerceIn(0f, 0.85f)
    val pulse = 0.5f + 0.5f * sin(clock * 3f)
    val radius = unit * (0.045f + 0.012f * pulse)
    drawCircle(
        color = NeoColors.NeonPurple.copy(alpha = alpha),
        radius = radius,
        center = Offset(x, groundY),
        style = Stroke(width = unit * 0.006f + 1f),
    )
    drawCircle(
        color = NeoColors.NeonPurple.copy(alpha = alpha * 0.5f),
        radius = unit * 0.012f,
        center = Offset(x, groundY),
    )
    for (i in 0..3) {
        val angle = i * (PI.toFloat() / 2f) + PI.toFloat() / 4f
        val inner = radius * 1.45f
        val outer = inner + unit * 0.022f * (1f - pulse * 0.4f)
        drawLine(
            color = NeoColors.NeonPurple.copy(alpha = alpha * 0.7f),
            start = Offset(x + cos(angle) * inner, groundY + sin(angle) * inner * 0.4f),
            end = Offset(x + cos(angle) * outer, groundY + sin(angle) * outer * 0.4f),
            strokeWidth = unit * 0.005f + 1f,
        )
    }
}

/**
 * Five dots along the arc the current angle and power would produce.
 *
 * Both input paths feed the same two numbers into it, which is what stops the buttons from being
 * a lesser way to play: whatever set them, the preview is the promise and the flight keeps it.
 */
private fun DrawScope.drawAimArc(angle: Float, power: Float, groundY: Float, unit: Float) {
    val radians = angle * PI.toFloat() / 180f
    val vx = cos(radians) * power
    val vh = sin(radians) * power
    val flight = (vh + sqrt(vh * vh + 2f * GRAVITY * LAUNCH_H)) / GRAVITY
    for (i in 1..5) {
        val t = flight * i / 6f
        val x = LAUNCH_X + vx * t
        val h = LAUNCH_H + vh * t - 0.5f * GRAVITY * t * t
        drawCircle(
            color = NeoColors.NeonCyan.copy(alpha = 0.42f - i * 0.05f),
            radius = unit * 0.011f,
            center = Offset(x * size.width, groundY - h * size.height * HEIGHT_SCALE),
        )
    }
}
