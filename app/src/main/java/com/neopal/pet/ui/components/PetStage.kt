package com.neopal.pet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.PetAnimation
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.ParticleKind
import com.neopal.pet.ui.art.ParticleSystem
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawPoops
import com.neopal.pet.ui.art.drawScene
import com.neopal.pet.ui.art.drawSickAura
import com.neopal.pet.ui.art.drawSleepVignette
import com.neopal.pet.ui.art.pingPong
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

/**
 * The animated window into the pet's world: background, creature, mess, particles and the
 * mood bubble. One frame loop drives everything, so the whole scene stays in sync and the
 * cost is a single recomposition-free canvas redraw per frame.
 */
@Composable
fun PetStage(
    state: PetState,
    config: GameConfig,
    action: PetAnimation,
    /** Bumped by the ViewModel on every reaction so a repeated animation restarts. */
    actionId: Long = 0L,
    modifier: Modifier = Modifier,
    onTapPet: () -> Unit = {},
    onLongPressPet: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val particles = remember { ParticleSystem() }
    var time by remember { mutableFloatStateOf(0f) }
    var actionStart by remember { mutableFloatStateOf(-99f) }
    var currentAction by remember { mutableStateOf(PetAnimation.IDLE) }
    var lastBlink by remember { mutableFloatStateOf(0f) }
    var blinkPhase by remember { mutableFloatStateOf(0f) }

    // Frame loop: advance the clock and the particles once per display frame.
    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                time += dt
                particles.update(dt)
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

    // A new reaction restarts the timeline and fires its particle burst.
    LaunchedEffect(actionId) {
        if (action == PetAnimation.IDLE) return@LaunchedEffect
        currentAction = action
        actionStart = time
        if (config.hapticsEnabled) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        emitFor(action, particles)
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

    Box(modifier = modifier.clip(RoundedCornerShape(18.dp))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.isDead, state.isEgg) {
                    detectTapGestures(
                        onTap = { onTapPet() },
                        onLongPress = { onLongPressPet() },
                    )
                },
        ) {
            val hatchProgress = if (state.isEgg) Simulation.stageProgress(state, config) else 0f
            drawScene(
                themeId = state.roomTheme,
                night = night,
                timeSeconds = time,
                lightsOff = state.lightsOff,
                parallax = sin(time * 0.12f),
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
                frame = buildFrame(
                    state = state,
                    action = activeAction,
                    progress = progress,
                    time = time,
                    blinkPhase = blinkPhase,
                    reducedMotion = config.reducedMotion,
                    hatchProgress = hatchProgress,
                ),
            )
            particles.draw(this)
            if (state.isSleeping) drawSleepVignette(0.8f)
        }

        MoodBubble(state = state, action = activeAction)
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
): CreatureFrame {
    val motion = if (reducedMotion) 0.35f else 1f
    val blink = if (blinkPhase > 0f) {
        // Fast close, slower open.
        val t = blinkPhase / 0.22f
        if (t < 0.5f) 1f - t * 2f else (t - 0.5f) * 2f
    } else 1f

    // Baseline idle: a slow breath plus a wandering gaze.
    var frame = CreatureFrame(
        bobY = sin(time * 1.6f) * 0.012f * motion,
        squash = 1f + sin(time * 1.6f) * 0.025f * motion,
        eyeOpen = blink,
        mouthOpen = 0f,
        lean = sin(time * 0.5f) * 1.2f * motion,
        armSwing = sin(time * 1.2f) * 0.25f * motion,
        gaze = sin(time * 0.35f),
        crack = hatchProgress,
    )

    if (state.isSleeping) {
        frame = frame.copy(
            bobY = sin(time * 0.6f) * 0.018f * motion,
            squash = 1f - 0.05f + sin(time * 0.6f) * 0.03f * motion,
            eyeOpen = 0f,
            lean = 6f,
            armSwing = 0f,
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
            val hop = abs(sin(p * 3f * PI.toFloat()))
            frame.copy(
                bobY = -hop * 0.11f * motion,
                squash = 1f + hop * 0.10f * motion,
                mouthOpen = 0.45f + hop * 0.25f,
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
