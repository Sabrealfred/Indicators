package com.neopal.pet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.PetAnimation
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.StatDelta
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.ParticleKind
import com.neopal.pet.ui.art.ParticleSystem
import com.neopal.pet.ui.art.PixelRenderer
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawPoops
import com.neopal.pet.ui.art.drawScene
import com.neopal.pet.ui.art.drawSickAura
import com.neopal.pet.ui.art.drawSleepVignette
import com.neopal.pet.ui.art.pingPong
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** How long each reaction animation runs, in seconds. */
private fun durationOf(animation: PetAnimation): Float = when (animation) {
    PetAnimation.IDLE -> 0f
    PetAnimation.EAT -> 2.2f
    PetAnimation.HAPPY -> 1.4f
    PetAnimation.PLAY -> 1.6f
    PetAnimation.SLEEP -> 1.2f
    PetAnimation.WAKE -> 1.0f
    PetAnimation.CLEAN -> 1.8f
    PetAnimation.HEAL -> 1.8f
    PetAnimation.REFUSE -> 1.0f
    PetAnimation.SCOLD -> 1.4f
    PetAnimation.PRAISE -> 1.6f
    PetAnimation.EVOLVE -> 3.4f
    PetAnimation.HATCH -> 3.0f
    PetAnimation.LEVEL_UP -> 2.0f
    PetAnimation.DEAD -> 2.5f
}

/** How hard the screen kicks when a reaction lands. */
private fun shakeOf(animation: PetAnimation): Float = when (animation) {
    PetAnimation.EVOLVE -> 1f
    PetAnimation.LEVEL_UP -> 0.6f
    PetAnimation.HATCH -> 0.7f
    PetAnimation.SCOLD, PetAnimation.REFUSE -> 0.45f
    PetAnimation.DEAD -> 0.8f
    else -> 0f
}

/** A "+12 MOOD" readout drifting up from the pet. */
private data class FloatLabel(
    val text: String,
    val positive: Boolean,
    val bornAt: Float,
    val laneX: Float,
)

private const val LABEL_LIFETIME = 1.5f

/**
 * The animated window into the pet's world: background, creature, mess, particles, floating
 * feedback and the mood bubble. One frame loop drives everything, and in pixel mode the whole
 * scene is rendered at low resolution and upscaled so it reads as real pixel art.
 *
 * The scene is also the main input surface: tap to pet, double-tap to tickle, stroke to pet
 * continuously, swipe up to toss, long-press for a photo, and tap a mess to scoop it.
 */
@Composable
fun PetStage(
    state: PetState,
    config: GameConfig,
    action: PetAnimation,
    /** Bumped by the ViewModel on every reaction so a repeated animation restarts. */
    actionId: Long = 0L,
    deltas: List<StatDelta> = emptyList(),
    modifier: Modifier = Modifier,
    onTapPet: () -> Unit = {},
    onDoubleTapPet: () -> Unit = {},
    onLongPressPet: () -> Unit = {},
    onScoopPoop: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val particles = remember { ParticleSystem() }
    val pixelRenderer = remember(config.pixelHeight) { PixelRenderer(config.pixelHeight) }
    val labels = remember { mutableStateListOf<FloatLabel>() }

    var time by remember { mutableFloatStateOf(0f) }
    var actionStart by remember { mutableFloatStateOf(-99f) }
    var currentAction by remember { mutableStateOf(PetAnimation.IDLE) }
    var lastBlink by remember { mutableFloatStateOf(0f) }
    var blinkPhase by remember { mutableFloatStateOf(0f) }
    var shake by remember { mutableFloatStateOf(0f) }
    var pointerX by remember { mutableFloatStateOf(0.5f) }
    var isStroking by remember { mutableStateOf(false) }

    // Frame loop: advance the clock, the particles, the blink and the screen shake.
    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                time += dt
                particles.update(dt)
                if (shake > 0f) shake = (shake - dt * 2.4f).coerceAtLeast(0f)
                labels.removeAll { time - it.bornAt > LABEL_LIFETIME }

                // Blink roughly every three seconds, twice as often when the pet is nervous.
                val interval = if (state.stats.happiness < 35f) 1.6f else 3.2f
                if (time - lastBlink > interval) {
                    lastBlink = time
                    blinkPhase = 0.001f
                }
                if (blinkPhase > 0f) {
                    blinkPhase += dt
                    if (blinkPhase > 0.22f) blinkPhase = 0f
                }
            }
        }
    }

    // A new reaction restarts the timeline, kicks the screen and fires its particle burst.
    LaunchedEffect(actionId) {
        if (action == PetAnimation.IDLE) return@LaunchedEffect
        currentAction = action
        actionStart = time
        shake = if (config.reducedMotion) 0f else shakeOf(action)
        if (config.hapticsEnabled) {
            haptics.performHapticFeedback(
                if (shakeOf(action) > 0.5f) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove,
            )
        }
        emitFor(action, particles)
        deltas.forEachIndexed { index, delta ->
            labels += FloatLabel(
                text = "${if (delta.positive) "+" else ""}${delta.amount} ${delta.label}",
                positive = delta.positive,
                bornAt = time + index * 0.12f,
                laneX = 0.5f + (index - 1) * 0.17f,
            )
        }
    }

    // Ambient particles that belong to a state rather than to an action.
    LaunchedEffect(state.mood, state.isSick, state.poops) {
        if (state.mood == Mood.SLEEPING) particles.emit(ParticleKind.ZZZ, 0.58f, 0.42f, 3)
        if (state.isSick) particles.emit(ParticleKind.DUST, 0.5f, 0.45f, 4, Color(0xFF7FBF6A))
    }

    val night = if (Simulation.isNight(state, config)) 1f else 0f
    val progress = if (durationOf(currentAction) <= 0f) 1f
    else ((time - actionStart) / durationOf(currentAction)).coerceIn(0f, 1f)
    val activeAction = if (progress >= 1f) PetAnimation.IDLE else currentAction
    val hatchProgress = if (state.isEgg) Simulation.stageProgress(state, config) else 0f

    val frame = buildFrame(
        state = state,
        action = activeAction,
        progress = progress,
        time = time,
        blinkPhase = blinkPhase,
        reducedMotion = config.reducedMotion,
        hatchProgress = hatchProgress,
        pointerX = pointerX,
        isStroking = isStroking,
    )

    // The whole world in one lambda, so it can be drawn straight to the screen or through
    // the pixel buffer without duplicating a single line.
    val world: DrawScope.() -> Unit = {
        drawScene(
            themeId = state.roomTheme,
            night = night,
            timeSeconds = time,
            lightsOff = state.lightsOff,
            parallax = sin(time * 0.12f) + (pointerX - 0.5f) * 0.6f,
        )
        drawPoops(state.poops, time)

        val center = Offset(size.width * 0.5f, size.height * 0.60f)
        val unit = size.minDimension
        if (state.isSick) drawSickAura(center, unit * 0.34f, time)
        drawCreature(
            center = center,
            unit = unit,
            spec = CreatureSpec(
                species = state.species,
                stage = state.stage,
                branch = state.branch,
                mood = state.mood,
                hatId = state.equippedHat,
                weightGrams = state.weightGrams,
            ),
            frame = frame,
        )
        particles.draw(this)
        if (state.isSleeping) drawSleepVignette(0.8f)
    }

    Box(modifier = modifier.clip(RoundedCornerShape(18.dp))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.poops, state.isDead) {
                    detectTapGestures(
                        onTap = { position ->
                            if (hitsPoop(position, size.width.toFloat(), size.height.toFloat(), state.poops)) {
                                onScoopPoop()
                            } else {
                                onTapPet()
                            }
                        },
                        onDoubleTap = { onDoubleTapPet() },
                        onLongPress = { onLongPressPet() },
                    )
                }
                .pointerInput(state.isDead) {
                    var travelled = 0f
                    var verticalTravel = 0f
                    detectDragGestures(
                        onDragStart = {
                            travelled = 0f
                            verticalTravel = 0f
                            isStroking = true
                        },
                        onDragEnd = {
                            isStroking = false
                            // A flick upward tosses the pet; a sideways stroke is a long pet.
                            if (verticalTravel < -size.height * 0.20f) onSwipeUp()
                            else if (travelled > size.width * 0.25f) onTapPet()
                        },
                        onDragCancel = { isStroking = false },
                        onDrag = { change, dragAmount ->
                            travelled += abs(dragAmount.x)
                            verticalTravel += dragAmount.y
                            pointerX = (change.position.x / size.width).coerceIn(0f, 1f)
                            // Stroking sheds a slow trail of hearts.
                            if (travelled % 60f < abs(dragAmount.x)) {
                                particles.emit(
                                    kind = ParticleKind.HEART,
                                    x = pointerX,
                                    y = (change.position.y / size.height).coerceIn(0f, 1f),
                                    count = 1,
                                )
                            }
                        },
                    )
                },
        ) {
            val amplitude = if (config.reducedMotion) 0f else shake * size.minDimension * 0.02f
            val dx = sin(time * 62f) * amplitude
            val dy = sin(time * 47f) * amplitude
            translate(dx, dy) {
                if (config.pixelMode) pixelRenderer.render(this) { world() } else world()
            }
        }

        FloatingLabels(labels = labels, now = time)
        MoodBubble(state = state, action = activeAction)
    }
}

/** True when a tap landed on one of the piles drawn along the floor. */
private fun hitsPoop(position: Offset, width: Float, height: Float, poops: Int): Boolean {
    if (poops <= 0) return false
    val baseY = height * 0.86f
    if (abs(position.y - baseY) > height * 0.10f) return false
    repeat(poops.coerceAtMost(6)) { index ->
        val x = width * (0.13f + index * 0.13f)
        if (abs(position.x - x) < width * 0.07f) return true
    }
    return false
}

@Composable
private fun BoxScope.FloatingLabels(labels: List<FloatLabel>, now: Float) {
    labels.forEach { label ->
        val age = now - label.bornAt
        if (age < 0f) return@forEach
        val progress = (age / LABEL_LIFETIME).coerceIn(0f, 1f)
        Text(
            text = label.text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = (if (label.positive) NeoColors.NeonGreen else NeoColors.NeonRed)
                .copy(alpha = (1f - progress).coerceIn(0f, 1f)),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(
                    x = ((label.laneX - 0.5f) * 220).dp,
                    y = (-30 - progress * 70).dp,
                ),
        )
    }
}

/** Maps state + reaction + clock onto the vector parameters the renderer consumes. */
private fun buildFrame(
    state: PetState,
    action: PetAnimation,
    progress: Float,
    time: Float,
    blinkPhase: Float,
    reducedMotion: Boolean,
    hatchProgress: Float,
    pointerX: Float,
    isStroking: Boolean,
): CreatureFrame {
    val motion = if (reducedMotion) 0.35f else 1f
    val blink = if (blinkPhase > 0f) {
        // Fast close, slower open.
        val t = blinkPhase / 0.22f
        if (t < 0.5f) 1f - t * 2f else (t - 0.5f) * 2f
    } else 1f

    // Baseline idle: a slow breath plus a wandering gaze that snaps to your finger.
    val gaze = if (isStroking) ((pointerX - 0.5f) * 2f).coerceIn(-1f, 1f) else sin(time * 0.35f)
    var frame = CreatureFrame(
        bobY = sin(time * 1.6f) * 0.012f * motion,
        squash = 1f + sin(time * 1.6f) * 0.025f * motion,
        eyeOpen = blink,
        mouthOpen = if (isStroking) 0.25f else 0f,
        lean = sin(time * 0.5f) * 1.2f * motion,
        armSwing = sin(time * 1.2f) * 0.25f * motion,
        gaze = gaze,
        crack = hatchProgress,
    )

    if (state.isSleeping) {
        frame = frame.copy(
            bobY = sin(time * 0.6f) * 0.018f * motion,
            squash = 1f - 0.05f + sin(time * 0.6f) * 0.03f * motion,
            eyeOpen = 0f,
            lean = 6f,
            armSwing = 0f,
            mouthOpen = 0f,
        )
    } else if (state.isSick) {
        frame = frame.copy(
            bobY = sin(time * 3.2f) * 0.006f * motion,
            lean = sin(time * 2.4f) * 2.5f * motion,
            eyeOpen = blink * 0.55f,
        )
    } else if (state.isDead) {
        return frame.copy(eyeOpen = 0f, lean = 18f, bobY = 0.06f, squash = 0.9f, armSwing = 0f)
    }

    val p = progress
    return when (action) {
        PetAnimation.IDLE -> frame
        PetAnimation.EAT -> frame.copy(
            mouthOpen = pingPong(p * 6f, 1f),
            lean = 4f * sin(p * 12f) * motion,
            squash = 1f - 0.05f * pingPong(p * 6f, 1f) * motion,
        )
        PetAnimation.HAPPY, PetAnimation.PRAISE -> {
            // Anticipation, launch, hang, landing squash — the four beats that sell a jump.
            val jump = jumpCurve(p)
            frame.copy(
                bobY = -jump.height * 0.16f * motion,
                squash = jump.squash(motion),
                mouthOpen = 0.45f + jump.height * 0.25f,
                armSwing = sin(p * 18f) * 0.9f * motion,
                eyeOpen = 0.85f,
            )
        }
        PetAnimation.PLAY -> {
            val hop = abs(sin(p * 4f * PI.toFloat()))
            frame.copy(
                bobY = -hop * 0.13f * motion,
                lean = sin(p * 16f) * 10f * motion,
                mouthOpen = 0.5f,
                armSwing = sin(p * 22f) * 1f * motion,
            )
        }
        PetAnimation.CLEAN -> frame.copy(
            lean = sin(p * 26f) * 7f * motion,
            eyeOpen = blink * 0.6f,
            mouthOpen = 0.2f,
        )
        PetAnimation.HEAL -> frame.copy(
            mouthOpen = if (p < 0.35f) 0.7f else 0.1f,
            flash = if (p < 0.2f) (0.2f - p) * 2f else 0f,
            eyeOpen = if (p < 0.5f) 0.2f else blink,
        )
        PetAnimation.REFUSE -> frame.copy(
            lean = sin(p * 30f) * 9f * motion,
            gaze = sin(p * 30f),
            mouthOpen = 0.15f,
            eyeOpen = 0.5f,
        )
        PetAnimation.SCOLD -> frame.copy(
            bobY = 0.03f,
            lean = 8f,
            eyeOpen = 0.35f,
            squash = 0.94f,
        )
        PetAnimation.SLEEP -> frame.copy(eyeOpen = (1f - p).coerceIn(0f, 1f), lean = 6f * p)
        PetAnimation.WAKE -> frame.copy(eyeOpen = p, squash = 1f + 0.08f * (1f - p))
        PetAnimation.LEVEL_UP -> {
            val hop = abs(sin(p * 2f * PI.toFloat()))
            frame.copy(bobY = -hop * 0.14f * motion, flash = (1f - p) * 0.4f, mouthOpen = 0.6f)
        }
        PetAnimation.EVOLVE -> {
            // Stretch tall, blow out to white, then settle into the new form.
            val stretch = when {
                p < 0.35f -> 1f + p * 0.9f
                p < 0.6f -> 1.3f
                else -> 1f + (1f - p) * 0.35f
            }
            frame.copy(
                squash = stretch,
                bobY = -0.05f * sin(p * PI.toFloat()),
                flash = if (p in 0.30f..0.72f) 1f - abs(p - 0.5f) * 4f else 0f,
                eyeOpen = if (p in 0.3f..0.7f) 0f else 1f,
                lean = sin(p * 40f) * 3f * motion,
            )
        }
        PetAnimation.HATCH -> frame.copy(
            crack = p,
            lean = sin(p * 34f) * 10f * motion,
            flash = if (p > 0.85f) (p - 0.85f) * 6f else 0f,
        )
        PetAnimation.DEAD -> frame.copy(eyeOpen = 0f, lean = 18f * p, bobY = 0.06f * p)
    }
}

/** One jump, expressed as height plus the stretch that goes with it. */
private class Jump(val height: Float, private val stretch: Float) {
    fun squash(motion: Float) = 1f + stretch * motion
}

/**
 * A jump that reads as weight: crouch first, snap up, float at the top, then absorb the landing.
 * A plain sine looks like the creature is on a spring rather than pushing off the floor.
 */
private fun jumpCurve(p: Float): Jump = when {
    p < 0.14f -> Jump(height = -0.15f * (p / 0.14f), stretch = -0.10f * (p / 0.14f))
    p < 0.32f -> {
        val t = (p - 0.14f) / 0.18f
        Jump(height = t, stretch = 0.14f * (1f - t))
    }
    p < 0.62f -> {
        val t = (p - 0.32f) / 0.30f
        Jump(height = 1f - t * t * 0.35f, stretch = 0.02f)
    }
    p < 0.80f -> {
        val t = (p - 0.62f) / 0.18f
        Jump(height = 0.65f * (1f - t), stretch = -0.04f * t)
    }
    else -> {
        val t = (p - 0.80f) / 0.20f
        Jump(height = 0f, stretch = -0.16f * (1f - t))
    }
}

private fun emitFor(action: PetAnimation, particles: ParticleSystem) {
    when (action) {
        PetAnimation.EAT -> particles.emit(ParticleKind.CRUMB, 0.5f, 0.58f, 10)
        PetAnimation.HAPPY, PetAnimation.PRAISE -> particles.emit(ParticleKind.HEART, 0.5f, 0.48f, 10)
        PetAnimation.PLAY -> particles.emit(ParticleKind.NOTE, 0.5f, 0.45f, 10)
        PetAnimation.CLEAN -> particles.emit(ParticleKind.BUBBLE, 0.5f, 0.55f, 16)
        PetAnimation.HEAL -> particles.emit(ParticleKind.SPARKLE, 0.5f, 0.5f, 12, Color(0xFF7BE0C0))
        PetAnimation.REFUSE, PetAnimation.SCOLD -> particles.emit(ParticleKind.ANGER, 0.62f, 0.4f, 5)
        PetAnimation.LEVEL_UP -> {
            particles.emit(ParticleKind.STAR, 0.5f, 0.45f, 14)
            particles.emit(ParticleKind.COIN, 0.5f, 0.5f, 8)
        }
        PetAnimation.EVOLVE -> particles.emit(ParticleKind.SPARKLE, 0.5f, 0.5f, 40, spread = 0.5f)
        PetAnimation.HATCH -> particles.emit(ParticleKind.STAR, 0.5f, 0.55f, 26, spread = 0.35f)
        PetAnimation.SLEEP -> particles.emit(ParticleKind.ZZZ, 0.6f, 0.45f, 4)
        else -> Unit
    }
}

/** The little thought bubble above the pet: what it wants, or what just happened. */
@Composable
private fun BoxScope.MoodBubble(state: PetState, action: PetAnimation) {
    val text = when {
        state.isDead -> "..."
        state.stage == LifeStage.EGG -> "..."
        action == PetAnimation.REFUSE -> "No!"
        state.isSleeping -> "Zzz"
        state.isSick -> "I feel awful..."
        state.stats.satiety < 25f -> "I'm hungry!"
        state.poops >= 3 -> "It stinks in here"
        state.stats.hygiene < 30f -> "I need a bath"
        state.stats.energy < 20f -> "So sleepy..."
        state.stats.happiness < 30f -> "Play with me?"
        state.stats.happiness > 80f -> "This is the best!"
        else -> null
    }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 14.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xCC101218))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFF2F4F8),
            )
        }
    }
}
