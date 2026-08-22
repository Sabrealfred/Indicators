package com.neopal.pet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Morphology
import com.neopal.pet.domain.PetAnimation
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.RetroMode
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
import kotlin.math.max
import kotlin.math.min
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
 * The part of the creature a touch landed on.
 *
 * Where a finger lands changes what it gets: the head is pleased to be stroked, the belly is
 * ticklish, and the tail is nobody's handle.
 */
internal enum class TouchZone(
    /** How the zone is named out loud, for the accessibility actions that stand in for a touch. */
    val label: String,
) {
    HEAD("head"),
    BELLY("tummy"),
    TAIL("tail"),
}

/** How long a touch reaction plays before the creature settles back, in seconds. */
private const val TOUCH_REACTION = 0.9f

/** The furthest photo mode will magnify the scene. */
private const val MAX_ZOOM = 3f

/**
 * The animated window into the pet's world: background, creature, mess, particles, floating
 * feedback and the mood bubble. One frame loop drives everything, and in pixel mode the whole
 * scene is rendered at low resolution and upscaled so it reads as real pixel art.
 *
 * The scene is also the main input surface: tap to pet, double-tap to tickle, stroke to pet
 * continuously, swipe up to toss, long-press for a photo, pinch to frame one, and tap a mess to
 * scoop it. A tap that lands on the creature is answered by the part it landed on — see
 * [TouchZone] — and every one of those parts is reachable without aiming, through the
 * accessibility actions this scene publishes.
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
    /**
     * A tug on the tail. The reaction — the flinch, the glare, the sound and the withheld
     * affection — is this scene's own; this is the seam for whatever the game decides a pulled
     * tail should cost, and it stays a no-op until something is wired to it.
     */
    onTugTail: () -> Unit = {},
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
    val onTailTug by rememberUpdatedState(onTugTail)
    // Read from inside the gesture handlers as well, and for the same reason: a handler installed
    // while sound was off must not go on believing that after the setting changes.
    val cfg by rememberUpdatedState(config)
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
    // Which part of the creature was last touched, and when. The reaction is layered on top of
    // whatever animation the ViewModel has going, so the two never fight over the same pose.
    var touched by remember { mutableStateOf<TouchZone?>(null) }
    var touchedAt by remember { mutableFloatStateOf(-99f) }
    // Photo-mode framing. The scene is drawn scaled by [zoomLevel] with its top left at
    // [frameX], [frameY]; every pointer position is mapped back through both before anything is
    // hit-tested, so a zoomed creature is still poked where it looks like it is.
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var frameX by remember { mutableFloatStateOf(0f) }
    var frameY by remember { mutableFloatStateOf(0f) }
    // True from the moment a second finger lands until the next gesture starts. It is cleared on
    // the *next* touch rather than on lift, so the tap and drag handlers still see it while they
    // are deciding what the gesture that just ended was.
    var pinching by remember { mutableStateOf(false) }
    // The scene's size, kept for the accessibility zoom actions, which have no pointer to ask.
    var stageWidth by remember { mutableFloatStateOf(0f) }
    var stageHeight by remember { mutableFloatStateOf(0f) }

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
        touched = touched,
        touchProgress = ((time - touchedAt) / TOUCH_REACTION).coerceIn(0f, 1f),
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

    // One reaction, however it was asked for: a finger on a zone, or the accessibility action
    // that stands in for one. [nx] and [ny] are normalised scene coordinates for the burst, so a
    // touched zone sparks under the finger and an announced one sparks over the part itself.
    //
    // Everything this reads it reads through a state or a rememberUpdatedState delegate, because
    // the gesture handlers and the semantics actions that call it both outlive the composition
    // they were built in.
    fun react(zone: TouchZone, nx: Float, ny: Float) {
        touched = zone
        touchedAt = time
        when (zone) {
            // Pleased. A stroke on the head is the affection this game already knows how to pay
            // for, so it goes through the same call an ordinary tap has always made.
            TouchZone.HEAD -> {
                particles.emit(ParticleKind.HEART, nx, ny, 3)
                if (cfg.soundEnabled) ChiptuneEngine.play(Sfx.HAPPY)
                if (cfg.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTap()
            }
            // Ticklish. The double-tap tickle, reached with one finger in the right place: the
            // same capped, cheap delight, so a tummy cannot be farmed any harder than before.
            TouchZone.BELLY -> {
                particles.emit(ParticleKind.NOTE, nx, ny, 4)
                if (cfg.soundEnabled) ChiptuneEngine.play(Sfx.HAPPY, pitch = 1.18f)
                if (cfg.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDoubleTap()
            }
            // Annoyed. The point of the tail is that it pays nothing: a player who pulls it gets
            // a flinch and a glare instead of the happiness and bond a stroke would have earned.
            TouchZone.TAIL -> {
                particles.emit(ParticleKind.ANGER, nx, ny, 4)
                if (cfg.soundEnabled) ChiptuneEngine.play(Sfx.DENY)
                if (cfg.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onTailTug()
            }
        }
    }

    // Zoom about the middle of the scene, for the players who are not pinching: same clamp, same
    // ceiling, so the framing cannot be walked anywhere a pinch could not have taken it.
    fun frameAt(target: Float) {
        val next = target.coerceIn(1f, MAX_ZOOM)
        val applied = next / zoomLevel
        val midX = stageWidth / 2f
        val midY = stageHeight / 2f
        frameX = clampFrame(midX - applied * (midX - frameX), stageWidth, next)
        frameY = clampFrame(midY - applied * (midY - frameY), stageHeight, next)
        zoomLevel = next
    }

    // The touch zones, said out loud. A zone that only exists for a finger that can find it is
    // not a zone everybody has, so each one is an action too — with the same anchors the burst
    // would have used, since there is no finger to spark under.
    fun touchActions(of: PetState): List<CustomAccessibilityAction> {
        if (!of.hasTouchZones()) {
            return listOf(CustomAccessibilityAction("Pet ${of.name}") { onTap(); true })
        }
        return listOf(
            CustomAccessibilityAction("Stroke the ${TouchZone.HEAD.label}") {
                react(TouchZone.HEAD, wanderX, 0.52f)
                true
            },
            CustomAccessibilityAction("Tickle the ${TouchZone.BELLY.label}") {
                react(TouchZone.BELLY, wanderX, 0.62f)
                true
            },
            CustomAccessibilityAction("Tug the ${TouchZone.TAIL.label}") {
                react(TouchZone.TAIL, (wanderX - 0.12f).coerceIn(0f, 1f), 0.64f)
                true
            },
        )
    }

    // Rebuilt only when the creature's identity changes, not on every frame this scene draws.
    val readOut = remember(state.name, state.stage, state.species, state.isEgg) {
        if (state.isEgg) {
            "${state.name}'s egg."
        } else {
            "${state.name}, ${state.stage.displayName} ${state.species.displayName}. " +
                "Touch its head to please it, its tummy to tickle it, or its tail to annoy it. " +
                "Pinch to frame a photograph, then press and hold to take it."
        }
    }

    Box(modifier = modifier.clip(RoundedCornerShape(18.dp))) {
        // Five gestures share this one surface, so the order they are settled in is written down
        // rather than left to whichever detector happens to win a race:
        //
        //  1. Two fingers is always framing. The pinch loop is declared last, which makes it the
        //     innermost pointer input and so the first to see each event; from the moment a
        //     second finger lands it consumes every change, which cancels the tap detector and
        //     the drag detector outright. It never looks at a single-finger event, so nothing
        //     below it is weakened by its presence.
        //  2. [pinching] is belt and braces for the same rule: it survives until the *next*
        //     gesture begins, so the tap and drag handlers cannot mistake the end of a pinch for
        //     a tap, a stroke or a toss even if an event reached them before it was consumed.
        //  3. One finger keeps the precedence it always had: a mess, then a prop, then the
        //     creature. Only the last of those three is new, and it is a fork inside the branch
        //     that used to call onTap unconditionally, so nothing else moved.
        //  4. Held still, one finger is still the photograph; moved, it is still a stroke, and a
        //     stroke that goes far enough up is still a toss. Both detectors are untouched apart
        //     from the [pinching] guard and mapping the pointer back through the framing.
        //  5. Everything is hit-tested in scene coordinates, never in view coordinates, so zoom
        //     cannot pull a target away from the thing the player can see.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    stageWidth = it.width.toFloat()
                    stageHeight = it.height.toFloat()
                }
                // A zone you can only reach by touching an exact part of a small drawing is no
                // zone at all for anyone driving this by screen reader, so all three are also
                // actions — as are the photograph and the framing they were added alongside.
                .semantics {
                    contentDescription = readOut
                    // The same offer a finger gets: the three zones while the creature can feel
                    // them, and the plain whole-body pet the rest of the time, which is exactly
                    // what a tap falls back to on an egg, a sleeper or a creature that is gone.
                    customActions = touchActions(state) + listOf(
                        CustomAccessibilityAction("Take a photograph") {
                            onLongPress()
                            true
                        },
                        CustomAccessibilityAction("Zoom in") {
                            frameAt(zoomLevel * 1.4f)
                            true
                        },
                        CustomAccessibilityAction("Zoom out") {
                            frameAt(zoomLevel / 1.4f)
                            true
                        },
                        CustomAccessibilityAction("Reset the framing") {
                            frameAt(1f)
                            true
                        },
                    )
                }
                .pointerInput(state.poops, state.isDead) {
                    detectTapGestures(
                        onTap = { position ->
                            if (!pinching) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val scene = scenePoint(position, frameX, frameY, zoomLevel)
                                when {
                                    hitsPoop(scene, w, h, live.poops) -> onScoop()
                                    // Furniture answers first: a finger on the lamp is not also a poke.
                                    else -> {
                                        val parallax = sceneParallax(time, pointerX)
                                        val prop = propAt(scene, w, h, live.roomTheme, parallax)
                                        if (prop != null) {
                                            props = props.withTap(prop, time)
                                        } else {
                                            // Where you touched it decides what you get. A touch
                                            // that missed the creature, or one it is in no state
                                            // to feel, is the plain pet it has always been.
                                            val zone = zoneAt(scene, w, h, wanderX, live)
                                            if (zone == null) {
                                                onTap()
                                            } else {
                                                react(zone, scene.x / w, scene.y / h)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onDoubleTap = { position ->
                            // A quick second tap on a prop is still aimed at the prop. Without this
                            // the tap detector swallows both taps and tickles the pet instead.
                            if (!pinching) {
                                val prop = propAt(
                                    scenePoint(position, frameX, frameY, zoomLevel),
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    live.roomTheme,
                                    sceneParallax(time, pointerX),
                                )
                                if (prop != null) props = props.withTap(prop, time) else onDoubleTap()
                            }
                        },
                        // The photograph is of the creature's state, not of these pixels, so it
                        // comes out the same whatever the scene is currently framed at.
                        onLongPress = { if (!pinching) onLongPress() },
                    )
                }
                .pointerInput(state.isDead) {
                    var travelled = 0f
                    var verticalTravel = 0f
                    detectDragGestures(
                        onDragStart = {
                            travelled = 0f
                            verticalTravel = 0f
                            isStroking = !pinching
                        },
                        onDragEnd = {
                            isStroking = false
                            // A flick upward tosses the pet; a sideways stroke is a long pet.
                            if (!pinching) {
                                if (verticalTravel < -size.height * 0.20f) onFlick()
                                else if (travelled > size.width * 0.25f) onTap()
                            }
                        },
                        onDragCancel = { isStroking = false },
                        onDrag = { change, dragAmount ->
                            if (!pinching) {
                                travelled += abs(dragAmount.x)
                                verticalTravel += dragAmount.y
                                val scene = scenePoint(change.position, frameX, frameY, zoomLevel)
                                pointerX = (scene.x / size.width).coerceIn(0f, 1f)
                                // Stroking sheds a slow trail of hearts.
                                if (travelled % 60f < abs(dragAmount.x)) {
                                    particles.emit(
                                        kind = ParticleKind.HEART,
                                        x = pointerX,
                                        y = (scene.y / size.height).coerceIn(0f, 1f),
                                        count = 1,
                                    )
                                }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Framing, and nothing else. This loop watches every gesture but only acts on
                    // — and only consumes — events with two or more fingers down, which is what
                    // lets it sit on the same surface as four one-finger gestures without taking
                    // a single one of them away.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pinching = false
                        var down = 0
                        do {
                            val event = awaitPointerEvent()
                            down = 0
                            for (index in event.changes.indices) {
                                if (event.changes[index].pressed) down++
                            }
                            if (down >= 2) {
                                pinching = true
                                val spread = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val next = (zoomLevel * spread).coerceIn(1f, MAX_ZOOM)
                                // The granted factor, not the requested one: at the stops the
                                // scene must stop moving too, or it slides out from under the
                                // fingers that are no longer able to zoom it.
                                val applied = next / zoomLevel
                                val width = size.width.toFloat()
                                val height = size.height.toFloat()
                                frameX = clampFrame(
                                    centroid.x + pan.x - applied * (centroid.x - frameX),
                                    width,
                                    next,
                                )
                                frameY = clampFrame(
                                    centroid.y + pan.y - applied * (centroid.y - frameY),
                                    height,
                                    next,
                                )
                                zoomLevel = next
                                for (index in event.changes.indices) event.changes[index].consume()
                            }
                        } while (down > 0)
                    }
                },
        ) {
            val amplitude = if (config.reducedMotion) 0f else shake * size.minDimension * 0.02f
            var dx = sin(time * 62f) * amplitude
            var dy = sin(time * 47f) * amplitude
            // The framing offset travels with the shake so that both land on the same grid.
            var fx = frameX
            var fy = frameY
            if (config.pixelMode) {
                // Shake in whole art pixels. A fractional offset re-samples every pixel in the
                // image on every frame, which reads as buzzing rather than as a kick.
                val block = PixelRenderer.scaleFor(size.height, config.pixelHeight).toFloat()
                dx = (dx / block).roundToInt() * block
                dy = (dy / block).roundToInt() * block
                fx = (fx / block).roundToInt() * block
                fy = (fy / block).roundToInt() * block
            }
            // Photo-mode framing wraps the finished picture rather than the world inside it, so
            // in pixel mode what grows is the art pixel: zooming in hands the player bigger
            // honest blocks instead of a smooth guess at detail the buffer never held. At a zoom
            // of 1 with nothing panned this is the identity, and the scene draws as it always did.
            translate(dx + fx, dy + fy) {
                scale(scaleX = zoomLevel, scaleY = zoomLevel, pivot = Offset.Zero) {
                    if (config.pixelMode) {
                        pixelRenderer.render(
                            target = this,
                            block = { world() },
                            softness = config.softFinish,
                            mode = config.retroMode,
                            // The handheld has four tones and no darker to go, so night cannot be
                            // drawn as shadow the way it is everywhere else — it has to be the
                            // room getting dimmer against a backlight that stays put. Lifting the
                            // exposure is what keeps a night scene off the bottom two tones.
                            exposure = if (config.retroMode == RetroMode.GREEN_LCD) 1f + 1.2f * night else 1f,
                        )
                    } else {
                        world()
                    }
                }
            }
            // Atmosphere on top of the blit: gradients belong at screen resolution, where they
            // stay smooth, while the art underneath stays chunky.
            //
            // Except on the handheld, which has already reprinted the frame in four flat tones.
            // A smooth gradient laid over that is a fifth colour and a sixth, and it undoes the
            // one thing the mode exists to do. Its night, its lights-out and its sleep are all
            // carried by the exposure passed into the blit instead.
            val ownsTheFinish = config.pixelMode && config.retroMode.replacesColour
            if (config.pixelMode && !ownsTheFinish) {
                if (state.lightsOff) drawLightsOutOverlay()
                if (state.isSleeping) drawSleepVignette(0.8f)
            }
            if (!ownsTheFinish) drawAtmosphere(night = night, strength = config.atmosphere)
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

/**
 * True while touching one part of the creature rather than another means anything at all.
 *
 * An egg has no parts to speak of, a sleeper should not be prodded awake by the tail, and the
 * dead are past being tickled — so all three keep the plain whole-body tap they have always had.
 */
private fun PetState.hasTouchZones(): Boolean = !isEgg && !isDead && !isSleeping

/**
 * Undoes the photo-mode framing: a pointer position in view pixels, as the scene is drawn at
 * [zoom] from [offsetX], [offsetY], read back as the position in the scene underneath it.
 *
 * [Offset] is a value class, so this hands back a pair of floats and allocates nothing.
 */
private fun scenePoint(position: Offset, offsetX: Float, offsetY: Float, zoom: Float): Offset =
    Offset((position.x - offsetX) / zoom, (position.y - offsetY) / zoom)

/**
 * Holds the framed scene over the whole viewport. Without this a pan could drag the room off the
 * side of the frame and leave the player looking at bare canvas.
 */
private fun clampFrame(offset: Float, extent: Float, zoom: Float): Float =
    offset.coerceIn(extent - extent * zoom, 0f)

/** Blends two numbers, for the anchors that ride the stance gene between two poses. */
private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** True when a point lies inside the ellipse of half-extents [rx], [ry] centred on the offset. */
private fun inEllipse(dx: Float, dy: Float, rx: Float, ry: Float): Boolean {
    if (rx <= 0f || ry <= 0f) return false
    val nx = dx / rx
    val ny = dy / ry
    return nx * nx + ny * ny <= 1f
}

/** Squared distance from a point to a line segment. Squared, so the hit test needs no root. */
private fun distanceToSegmentSquared(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val vx = bx - ax
    val vy = by - ay
    val lengthSquared = vx * vx + vy * vy
    val along = ((px - ax) * vx + (py - ay) * vy)
    val t = if (lengthSquared <= 0.0001f) 0f else (along / lengthSquared).coerceIn(0f, 1f)
    val dx = px - (ax + vx * t)
    val dy = py - (ay + vy * t)
    return dx * dx + dy * dy
}

/**
 * Which part of the creature is under [position], or null when the touch missed it — and null
 * too for an egg, a sleeping creature or a dead one, so that all three keep answering a tap
 * exactly as they always have.
 *
 * The zones are rebuilt from the numbers the creature is actually drawn from: where it has
 * wandered to, the body radius its life stage has grown into, the width its weight, branch and
 * build gene give it, and the stance, leg length and tail its genome expresses. Nothing here is a
 * pixel box, so a hound's head stays out on the end of its neck while a blob's stays on top of
 * its own belly, and neither has to be written down a second time when the silhouette changes.
 *
 * It runs on a finger-down and never in the draw path, and every value in it is a local float.
 */
private fun zoneAt(
    position: Offset,
    width: Float,
    height: Float,
    wanderX: Float,
    state: PetState,
): TouchZone? {
    if (!state.hasTouchZones()) return null
    val unit = min(width, height)
    // Where the scene puts the creature, without the bob: two art pixels of breathing is far
    // inside a fingertip, and reading it here would only make the zones twitch.
    val cx = width * wanderX
    val cy = height * 0.60f
    // 0.30 of the short side at hatching and 0.34 grown, the run the drawn proportions take.
    val growth = when (state.stage) {
        LifeStage.EGG, LifeStage.BABY -> 0f
        LifeStage.CHILD -> 0.25f
        LifeStage.TEEN -> 0.5f
        LifeStage.ADULT -> 1f
        LifeStage.ELDER -> 0.5f
    }
    val bodyR = unit * (0.30f + 0.04f * growth)
    val m = state.morphology
    // Morphology.bodyWidth already carries the weight, the branch and the build gene — every
    // ingredient of the drawn half-width bar a couple of per cent of per-stage trim, which is
    // nothing against a fingertip.
    val halfWidth = bodyR * m.bodyWidth
    val quad = m.quadruped.coerceIn(0f, 1f)

    // The four-legged crossfade, in the terms the art states it in: the barrel settles onto its
    // legs, the head walks forward off the shoulder, and both ride the stance gene.
    val groundY = cy + bodyR * 1.15f
    val legs = bodyR * (0.20f + 0.62f * (m.legLength / Morphology.MAX_LEG).coerceIn(0f, 1f))
    val trunkH = bodyR * mix(0.92f, 0.50f, quad)
    val trunkTop = groundY - legs - trunkH * 0.98f
    val trunkX = cx - bodyR * 0.30f * quad
    val trunkY = cy + (trunkTop - cy) * quad
    val headX = cx + bodyR * 0.66f * quad
    val headY = cy + (trunkTop - bodyR * 0.20f - cy) * quad
    val headR = bodyR * (1f - 0.30f * quad)

    // A fingertip is wider than a pixel, so both blobs are tested a little larger than drawn.
    val slop = 1.15f
    val head = inEllipse(position.x - headX, position.y - headY, headR * m.bodyWidth * slop, headR * slop)
    val belly = inEllipse(position.x - trunkX, position.y - trunkY, halfWidth * slop, trunkH * slop)
    if (head || belly) {
        // Standing upright the head *is* the body: one blob, with the face drawn across its top
        // and the belly patch across its bottom, so height alone decides and the split follows
        // the drawing rather than inventing a boundary of its own. On all fours the two have
        // come apart and each answers for itself — except where they still overlap across the
        // shoulder, where height is the tie-break again.
        val oneBlob = quad < 0.25f
        return when {
            oneBlob || (head && belly) ->
                if (position.y <= headY + headR * 0.05f) TouchZone.HEAD else TouchZone.BELLY
            head -> TouchZone.HEAD
            else -> TouchZone.BELLY
        }
    }

    // The tail, as the segment it is drawn along. A creature whose tail gene is spent down to
    // nothing has no tail drawn, and so has no tail to pull: the art and the zone agree.
    val tailUnits = 2f * m.tailLength
    if (tailUnits <= 0.01f) return null
    // The wag is left out on purpose. It is the frame's, not the body's, and a target that
    // swings with it would be a target that has to be chased.
    val baseX = mix(cx - halfWidth * 0.85f, trunkX - halfWidth * 0.88f, quad)
    val baseY = mix(cy + bodyR * 0.45f, trunkY - trunkH * 0.34f, quad)
    val reach = bodyR * 0.42f
    val tail = distanceToSegmentSquared(
        position.x,
        position.y,
        baseX,
        baseY,
        baseX - bodyR * tailUnits,
        baseY - bodyR * tailUnits * 0.5f,
    )
    return if (tail <= reach * reach) TouchZone.TAIL else null
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
    touched: TouchZone? = null,
    touchProgress: Float = 1f,
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
    val posed = when (action) {
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
    // Last word to the finger: whatever the creature was already doing, being touched somewhere
    // particular shows on top of it rather than replacing it.
    return posed.withTouch(touched, touchProgress, motion)
}

/**
 * The reaction to being touched in one place, layered over the pose the creature already had.
 *
 * Each zone answers in its own currency. The head half-closes its eyes and leans into the hand,
 * the belly screws its eyes shut and wriggles, and the tail flinches away and glares back over
 * the shoulder at whoever just pulled it. All three fade out across the reaction, and all three
 * scale their motion, so a player who has asked for less of it gets a smaller version of the
 * same expression rather than none of it.
 */
private fun CreatureFrame.withTouch(zone: TouchZone?, progress: Float, motion: Float): CreatureFrame {
    if (zone == null || progress >= 1f) return this
    val arc = sin(progress * PI.toFloat())
    val fade = 1f - progress
    return when (zone) {
        TouchZone.HEAD -> copy(
            eyeOpen = eyeOpen * (1f - 0.75f * arc),
            mouthOpen = max(mouthOpen, 0.30f * arc),
            blush = (blush + 0.35f * arc).coerceAtMost(1f),
            bobY = bobY - 0.012f * arc * motion,
            lean = lean + sin(progress * 9f) * 2.5f * fade * motion,
        )
        TouchZone.BELLY -> copy(
            eyeOpen = eyeOpen * (1f - 0.80f * arc),
            mouthOpen = max(mouthOpen, 0.85f * arc),
            blush = (blush + 0.20f * arc).coerceAtMost(1f),
            squash = squash * (1f + 0.05f * sin(progress * 26f) * fade * motion),
            lean = lean + sin(progress * 30f) * 7f * fade * motion,
            armSwing = armSwing + sin(progress * 34f) * 0.8f * fade * motion,
        )
        TouchZone.TAIL -> copy(
            eyeOpen = eyeOpen * (1f - 0.55f * arc),
            mouthOpen = max(mouthOpen, 0.18f * arc),
            // Looking back down its own side at the hand, not out at the room.
            gaze = mix(gaze, -1f, arc),
            lean = lean + 9f * fade * motion,
            bobY = bobY - 0.020f * arc * motion,
            tailSwing = tailSwing + sin(progress * 40f) * 1.2f * fade,
        )
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
