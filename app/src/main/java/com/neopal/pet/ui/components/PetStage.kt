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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
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
import com.neopal.pet.domain.ItemCatalog
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.StatDelta
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.ParticleKind
import com.neopal.pet.ui.art.ParticleSystem
import com.neopal.pet.ui.art.PixelRenderer
import com.neopal.pet.ui.art.SceneProp
import com.neopal.pet.ui.art.ScenePropState
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawPoops
import com.neopal.pet.ui.art.drawScene
import com.neopal.pet.ui.art.drawItem
import com.neopal.pet.ui.art.drawLightsOutOverlay
import com.neopal.pet.ui.art.drawSickAura
import com.neopal.pet.ui.art.drawSleepVignette
import com.neopal.pet.ui.art.drawWeather
import com.neopal.pet.ui.art.pingPong
import com.neopal.pet.ui.art.scenePropHits
import com.neopal.pet.ui.theme.NeoColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** How long each reaction animation runs, in seconds. */
private fun durationOf(animation: PetAnimation): Float = when (animation) {
    PetAnimation.IDLE -> 0f
    PetAnimation.EAT -> 2.2f
    PetAnimation.HAPPY -> 1.4f
    PetAnimation.PLAY -> 1.6f
    PetAnimation.SLEEP -> 1.2f
    PetAnimation.WAKE -> 1.6f
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
    /** Item id being eaten right now, drawn in front of the pet while the EAT animation runs. */
    servedItemId: String? = null,
    modifier: Modifier = Modifier,
    onTapPet: () -> Unit = {},
    onDoubleTapPet: () -> Unit = {},
    onLongPressPet: () -> Unit = {},
    onScoopPoop: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    // The frame loop and the gesture handlers outlive the composition that created them, so
    // everything they read has to be kept fresh explicitly. Without this the loop keeps seeing
    // the pet as the egg it was when the screen first appeared.
    val live by rememberUpdatedState(state)
    val onTap by rememberUpdatedState(onTapPet)
    val onDoubleTap by rememberUpdatedState(onDoubleTapPet)
    val onLongPress by rememberUpdatedState(onLongPressPet)
    val onScoop by rememberUpdatedState(onScoopPoop)
    val onFlick by rememberUpdatedState(onSwipeUp)
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
    // The lamp and the poster. View state on purpose: nothing in the save file describes a room's
    // light switch, and the ViewModel is not ours to extend.
    var props by remember { mutableStateOf(ScenePropState()) }
    // Where the pet has wandered to, 0..1 across the floor, and which way it is facing.
    var wanderX by remember { mutableFloatStateOf(0.5f) }
    var wanderTarget by remember { mutableFloatStateOf(0.5f) }
    var wanderPause by remember { mutableFloatStateOf(2f) }
    // Small unprompted behaviours, so an idle pet still looks like it is thinking about something.
    var idlePose by remember { mutableStateOf(IdlePose.NONE) }
    var idlePoseStart by remember { mutableFloatStateOf(0f) }
    var nextIdlePose by remember { mutableFloatStateOf(9f) }
    // The tail and anything worn are loose masses hanging off the body rather than parts of it,
    // so they get their own damped springs: they overshoot when the pet sets off and carry on
    // swinging for a beat after it has stopped.
    var tailLag by remember { mutableFloatStateOf(0f) }
    var tailVel by remember { mutableFloatStateOf(0f) }
    var hatLag by remember { mutableFloatStateOf(0f) }
    var hatVel by remember { mutableFloatStateOf(0f) }
    var lastWanderX by remember { mutableFloatStateOf(0.5f) }

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

                if (time > nextIdlePose && !live.isSleeping && !live.isDead && !live.isEgg) {
                    idlePose = IdlePose.entries[((time * 7).toInt() % (IdlePose.entries.size - 1)) + 1]
                    idlePoseStart = time
                    nextIdlePose = time + 9f + (time % 7f)
                }
                if (idlePose != IdlePose.NONE && time - idlePoseStart > idlePose.seconds) {
                    idlePose = IdlePose.NONE
                }

                // A pet that never leaves the centre of the frame reads as a menu illustration.
                // It picks a spot, walks there, then stands around for a while.
                val canWander = !live.isSleeping && !live.isDead && !live.isEgg && !live.isSick
                if (canWander) {
                    if (wanderPause > 0f) {
                        wanderPause -= dt
                    } else if (abs(wanderX - wanderTarget) < 0.01f) {
                        // Deterministic-enough wandering: the clock picks the next spot.
                        wanderTarget = 0.28f + ((sin(time * 0.37f) + 1f) / 2f) * 0.44f
                        wanderPause = 2.5f + ((sin(time * 0.11f) + 1f) / 2f) * 5f
                    } else {
                        val direction = if (wanderTarget > wanderX) 1f else -1f
                        wanderX = (wanderX + direction * dt * 0.055f).coerceIn(0.2f, 0.8f)
                    }
                } else {
                    // Sleeping, sick or gone: settle back to the middle of the room.
                    wanderX += (0.5f - wanderX) * dt * 0.8f
                }

                // Drag on the trailing parts is the body's own velocity, pointing backwards.
                val drag = (-(wanderX - lastWanderX) / dt.coerceAtLeast(0.001f) * 7f)
                    .coerceIn(-1.2f, 1.2f)
                lastWanderX = wanderX
                tailVel += ((drag - tailLag) * 30f - tailVel * 5f) * dt
                tailLag += tailVel * dt
                hatVel += ((drag * 0.7f - hatLag) * 46f - hatVel * 7f) * dt
                hatLag += hatVel * dt

                labels.removeAll { time - it.bornAt > LABEL_LIFETIME }

                // Blink roughly every three seconds, twice as often when the pet is nervous.
                val interval = if (live.stats.happiness < 35f) 1.6f else 3.2f
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

    // Ambient particles belong to a state, so they have to keep coming while the state holds.
    // Emitting once meant a sleeping pet puffed three Zs and then slept in silence all night.
    LaunchedEffect(state.mood, state.isSick) {
        while (true) {
            if (live.mood == Mood.SLEEPING) particles.emit(ParticleKind.ZZZ, 0.58f, 0.42f, 2)
            if (live.isSick) particles.emit(ParticleKind.DUST, 0.5f, 0.45f, 3, Color(0xFF7FBF6A))
            delay(2_200)
        }
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
        walking = abs(wanderX - wanderTarget) > 0.01f && wanderPause <= 0f,
        facing = if (wanderTarget > wanderX) 1f else -1f,
        idlePose = idlePose,
        idleProgress = ((time - idlePoseStart) / idlePose.seconds).coerceIn(0f, 1f),
        tailLag = tailLag,
        hatLag = hatLag,
    )

    // The whole world in one lambda, so it can be drawn straight to the screen or through
    // the pixel buffer without duplicating a single line.
    val world: DrawScope.() -> Unit = {
        val day = state.ageInPetDays(config)
        drawScene(
            themeId = state.roomTheme,
            night = night,
            timeSeconds = time,
            // In pixel mode the light wash is drawn after the blit, at full resolution.
            lightsOff = state.lightsOff && !config.pixelMode,
            parallax = sceneParallax(time, pointerX),
            petDay = day,
            props = props,
        )
        drawPoops(state.poops, time)
        // Every third pet day turns wet, and the space and arcade rooms are indoors.
        val weather = when {
            state.roomTheme == "room_space" || state.roomTheme == "room_arcade" -> "none"
            day % 3 == 2 && state.roomTheme == "room_forest" -> "rain"
            day % 4 == 3 -> "rain"
            else -> "none"
        }
        if (weather != "none") drawWeather(weather, time, intensity = 0.7f)

        // Snapped to whole pixels: a creature drifting across the grid in fractions of a pixel
        // makes its own outline shimmer as it walks.
        val center = Offset(
            x = (size.width * wanderX).roundToInt().toFloat(),
            y = (size.height * 0.60f).roundToInt().toFloat(),
        )
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
                // The one place the genome becomes something you can look at. A save from before
                // genomes existed carries the neutral starter one, which expresses as the shape
                // this creature has always had — nobody's pet changes under them on upgrade.
                morphology = state.morphology,
            ),
            frame = frame,
        )
        // Food you can see beats crumbs you have to infer: it shrinks bite by bite.
        if (activeAction == PetAnimation.EAT && servedItemId != null) {
            ItemCatalog[servedItemId]?.let { item ->
                val bite = (1f - progress).coerceIn(0f, 1f)
                val plate = unit * 0.22f * (0.35f + bite * 0.65f)
                val fx = center.x + unit * 0.30f
                val fy = center.y + unit * 0.06f
                inset(
                    left = fx - plate / 2f,
                    top = fy - plate / 2f,
                    right = size.width - (fx + plate / 2f),
                    bottom = size.height - (fy + plate / 2f),
                ) {
                    drawItem(item.iconKey, Color(item.tint), variant = item.id)
                }
            }
        }
        particles.draw(this)
        if (state.isSleeping && !config.pixelMode) drawSleepVignette(0.8f)
    }

    Box(modifier = modifier.clip(RoundedCornerShape(18.dp))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.poops, state.isDead) {
                    detectTapGestures(
                        onTap = { position ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            when {
                                hitsPoop(position, w, h, live.poops) -> onScoop()
                                // Furniture answers first: a finger on the lamp is not also a poke.
                                else -> {
                                    val prop = propAt(position, w, h, live.roomTheme, sceneParallax(time, pointerX))
                                    if (prop != null) props = props.withTap(prop, time) else onTap()
                                }
                            }
                        },
                        onDoubleTap = { position ->
                            // A quick second tap on a prop is still aimed at the prop. Without this
                            // the tap detector swallows both taps and tickles the pet instead.
                            val prop = propAt(
                                position,
                                size.width.toFloat(),
                                size.height.toFloat(),
                                live.roomTheme,
                                sceneParallax(time, pointerX),
                            )
                            if (prop != null) props = props.withTap(prop, time) else onDoubleTap()
                        },
                        onLongPress = { onLongPress() },
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
                            if (verticalTravel < -size.height * 0.20f) onFlick()
                            else if (travelled > size.width * 0.25f) onTap()
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
            var dx = sin(time * 62f) * amplitude
            var dy = sin(time * 47f) * amplitude
            if (config.pixelMode) {
                // Shake in whole art pixels. A fractional offset re-samples every pixel in the
                // image on every frame, which reads as buzzing rather than as a kick.
                val block = PixelRenderer.scaleFor(size.height, config.pixelHeight).toFloat()
                dx = (dx / block).roundToInt() * block
                dy = (dy / block).roundToInt() * block
            }
            translate(dx, dy) {
                if (config.pixelMode) {
                    pixelRenderer.render(this, block = { world() }, softness = config.softFinish)
                } else {
                    world()
                }
            }
            // Atmosphere on top of the blit: gradients belong at screen resolution, where they
            // stay smooth, while the art underneath stays chunky.
            if (config.pixelMode) {
                if (state.lightsOff) drawLightsOutOverlay()
                if (state.isSleeping) drawSleepVignette(0.8f)
            }
            drawAtmosphere(night = night, strength = config.atmosphere)
        }

        FloatingLabels(labels = labels, now = time)
        MoodBubble(state = state, action = activeAction)
    }
}

/**
 * The finishing pass, drawn at screen resolution over everything else: a colour grade that warms
 * the picture by day and cools it at night, and a vignette that pulls the eye to the middle of
 * the room. Both are wide, smooth gradients — exactly what the low-resolution buffer cannot hold
 * without banding, and exactly what makes a frame feel composed rather than merely drawn.
 */
private fun DrawScope.drawAtmosphere(night: Float, strength: Float) {
    val amount = strength.coerceIn(0f, 1f)
    if (amount <= 0.01f) return
    val daylight = 1f - night.coerceIn(0f, 1f)
    if (daylight > 0.01f) {
        drawRect(Color(0xFFFFB870).copy(alpha = 0.055f * daylight * amount))
    }
    if (night > 0.01f) {
        drawRect(Color(0xFF6C86FF).copy(alpha = 0.10f * night * amount))
    }
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0xFF0B0D16).copy(alpha = 0.30f * amount)),
            center = Offset(size.width * 0.5f, size.height * 0.48f),
            radius = size.minDimension * 0.95f,
        ),
    )
}

/** Rounds a continuous value to [steps] discrete positions, to keep motion off sub-pixel drift. */
private fun quantise(value: Float, steps: Float): Float = (value * steps).roundToInt() / steps

/**
 * The room's parallax offset. Shared by the drawing and the hit test so that a prop and its tap
 * target cannot drift apart.
 */
private fun sceneParallax(time: Float, pointerX: Float): Float =
    quantise(sin(time * 0.12f) + (pointerX - 0.5f) * 0.6f, steps = 12f)

/**
 * The prop under a tap, or null. [scenePropHits] builds a list, so this belongs on a finger-down
 * and nowhere near the draw path.
 */
private fun propAt(
    position: Offset,
    width: Float,
    height: Float,
    themeId: String,
    parallax: Float,
): SceneProp? {
    val hits = scenePropHits(themeId, width, height, parallax)
    for (index in hits.indices) {
        val hit = hits[index]
        if (hit.bounds.contains(position)) return hit.prop
    }
    return null
}

/**
 * The lamp switches, the poster turns over, and both flinch under the finger. The variant is left
 * to run past four: the art wraps it, so there is no second copy of that number to keep in step.
 */
private fun ScenePropState.withTap(prop: SceneProp, now: Float): ScenePropState = when (prop) {
    SceneProp.LAMP -> copy(lampOn = !lampOn, tapped = prop, tappedAt = now)
    SceneProp.POSTER -> copy(posterVariant = posterVariant + 1, tapped = prop, tappedAt = now)
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
    walking: Boolean = false,
    facing: Float = 0f,
    idlePose: IdlePose = IdlePose.NONE,
    idleProgress: Float = 0f,
    tailLag: Float = 0f,
    hatLag: Float = 0f,
): CreatureFrame {
    val motion = if (reducedMotion) 0.35f else 1f
    val blink = if (blinkPhase > 0f) {
        // Fast close, slower open.
        val t = blinkPhase / 0.22f
        if (t < 0.5f) 1f - t * 2f else (t - 0.5f) * 2f
    } else 1f

    // Baseline idle: a slow breath plus a wandering gaze that snaps to your finger.
    val gaze = when {
        isStroking -> ((pointerX - 0.5f) * 2f).coerceIn(-1f, 1f)
        walking -> facing
        else -> sin(time * 0.35f)
    }
    // Affection is a slow warming rather than a switch: a new pet is barely pink, one you have
    // raised for a week is properly flushed, and a sick or miserable one loses most of it.
    val bond = (state.stats.bond / 100f).coerceIn(0f, 1f)
    val bonded = when {
        state.isDead -> 0f
        state.mood == Mood.SICK || state.mood == Mood.SAD -> bond * 0.30f
        state.mood == Mood.HAPPY -> 0.22f + bond * 0.78f
        else -> bond * 0.85f
    }
    val stroked = if (isStroking) 0.25f else 0f
    val warmth = (bonded + stroked).coerceIn(0f, 1f)

    val exhausted = ((28f - state.stats.energy) / 28f).coerceIn(0f, 1f)
    // Walking gets a faster bounce, swinging arms and a lean into the direction of travel.
    var frame = CreatureFrame(
        bobY = if (walking) abs(sin(time * 6f)) * -0.02f * motion else sin(time * 1.6f) * 0.012f * motion,
        squash = 1f + sin(time * (if (walking) 6f else 1.6f)) * 0.025f * motion,
        eyeOpen = blink,
        mouthOpen = if (isStroking) 0.25f else 0f,
        lean = if (walking) facing * 5f * motion else sin(time * 0.5f) * 1.2f * motion,
        armSwing = sin(time * (if (walking) 6f else 1.2f)) * (if (walking) 0.8f else 0.25f) * motion,
        gaze = gaze,
        crack = hatchProgress,
        blush = warmth,
        tailSwing = tailLag * motion,
        hatTilt = (hatLag * 12f * motion).coerceIn(-14f, 14f),
        sweat = if (state.isDead || state.isEgg || state.isSleeping) 0f else exhausted,
        sweatPhase = (time * 0.5f) % 1f,
        shiver = if (state.isSick && !state.isSleeping && !state.isDead) {
            sin(time * 44f) * motion
        } else {
            0f
        },
    )

    if (idlePose != IdlePose.NONE && !state.isSleeping && !state.isDead) {
        val q = idleProgress
        val arc = sin(q * PI.toFloat())
        frame = when (idlePose) {
            IdlePose.YAWN -> frame.copy(
                mouthOpen = arc,
                eyeOpen = blink * (1f - arc * 0.9f),
                squash = 1f + arc * 0.05f * motion,
                bobY = frame.bobY - arc * 0.01f,
            )
            IdlePose.SCRATCH -> frame.copy(
                armSwing = sin(q * 34f) * 0.9f * motion,
                lean = sin(q * 17f) * 4f * motion,
                eyeOpen = blink * 0.6f,
            )
            IdlePose.LOOK_UP -> frame.copy(
                gazeY = -arc,
                lean = -arc * 3f * motion,
                mouthOpen = arc * 0.15f,
            )
            IdlePose.NONE -> frame
        }
    }

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
                blush = (frame.blush + 0.28f).coerceAtMost(1f),
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
        PetAnimation.WAKE -> {
            // Waking is its own beat: reach up, hang at full extension, then flop back down
            // through a squash into the idle. Cutting straight to idle reads as a teleport.
            val stretch = when {
                p < 0.45f -> p / 0.45f
                p < 0.62f -> 1f
                else -> 1f - (p - 0.62f) / 0.38f
            }
            val settle = if (p > 0.62f) sin((p - 0.62f) / 0.38f * PI.toFloat()) else 0f
            frame.copy(
                eyeOpen = blink * (p * 2f).coerceAtMost(1f),
                squash = 1f + (stretch * 0.18f - settle * 0.12f) * motion,
                bobY = frame.bobY - stretch * 0.024f * motion,
                armsUp = stretch,
                armSwing = 0f,
                mouthOpen = stretch * 0.5f,
                lean = sin(p * 5f) * 2f * motion,
            )
        }
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

/** Unprompted little behaviours that break up standing still. */
internal enum class IdlePose(val seconds: Float) {
    NONE(1f),
    YAWN(1.6f),
    SCRATCH(1.4f),
    LOOK_UP(2.0f),
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
