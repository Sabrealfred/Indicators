package com.neopal.pet.widget

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.neopal.pet.domain.WidgetCreature
import com.neopal.pet.domain.WidgetFace
import com.neopal.pet.domain.WidgetSnapshot
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.art.drawPoops
import com.neopal.pet.ui.art.drawScene
import com.neopal.pet.ui.art.drawSickAura
import kotlin.math.min

/**
 * The picture on the home screen: the same room, drawn by the same code, with the same creature
 * in it.
 *
 * This file is the whole reason the widget is worth having. A generic sprite would throw away
 * the one thing this app is: a body drawn from a genome, so a bred line looks like itself. The
 * way that survives the trip to a `RemoteViews` is that none of it is Compose *runtime* — the
 * art is a set of `DrawScope` extensions, and a `DrawScope` can be pointed at any canvas,
 * including one over a plain `Bitmap`. See `PetWidgetBitmap`, which does exactly that.
 *
 * Deliberately free of `android.*`: everything here is arithmetic and draw calls, so it can be
 * compiled and run against the same stand-in `DrawScope` the creature's invariant tests use.
 *
 * Nothing here reads a clock. Every animation in the app is a function of a time that only goes
 * forwards; a widget is a still, so time is fixed at zero and the pose is chosen from the state
 * instead. That also makes the same snapshot draw the same pixels every time it is rendered,
 * which is what lets a host skip a redraw it does not need.
 */
fun DrawScope.drawWidgetScene(snapshot: WidgetSnapshot, bottomInset: Float = 0f) {
    val scene = snapshot.scene
    drawScene(
        themeId = scene.roomTheme,
        night = if (scene.night) 1f else 0f,
        timeSeconds = 0f,
        lightsOff = scene.lightsOff,
        petDay = scene.petDay,
    )
    // The mess is on the floor before the creature stands in front of it, and it is the reason
    // the widget says "Dirty" — a chip that reports something the picture does not show is the
    // kind of small dishonesty this whole file exists to avoid.
    drawPoops(scene.poops, 0f)

    val creature = snapshot.creature ?: return
    // The bottom of the picture belongs to the text plate, which is a real view laid over this
    // bitmap and whose height is decided by the font the reader chose — so the caller measures
    // it and the creature lives in what is left. Without this a four-by-two widget on a large
    // font setting is a plate with a pair of feet behind it.
    val room = size.height * (1f - bottomInset.coerceIn(0f, 0.6f))
    // Sized off the shorter side so a tall two-by-two and a wide four-by-two both get a whole
    // creature rather than one cropped at the ears.
    val unit = min(room * CREATURE_HEIGHT_SHARE, size.width * CREATURE_WIDTH_SHARE)
    val centre = Offset(size.width * 0.5f, room * CREATURE_CENTRE_Y)

    if (creature.isSick) drawSickAura(centre, unit * 0.34f, 0f)
    drawCreature(centre, unit, specFor(creature), frameFor(creature))

    // A dead pet is drawn, not hidden — but the room it is in is not a room anyone is being
    // invited to play in, and a flat wash says so without a caption.
    if (snapshot.face == WidgetFace.GONE) {
        drawRect(color = Palettes.room(scene.roomTheme).nightTint.copy(alpha = 0.34f))
    }
}

/**
 * How much of the space above the plate the creature is allowed, against each side, and where
 * its middle sits in that space. The centre is above the halfway line because a creature is
 * drawn from its body outward and stands on ground that is below it: [drawCreature] puts the
 * shadow a body's radius under the centre it is given.
 */
private const val CREATURE_HEIGHT_SHARE = 0.86f
private const val CREATURE_WIDTH_SHARE = 0.62f
private const val CREATURE_CENTRE_Y = 0.52f

private fun specFor(creature: WidgetCreature): CreatureSpec = CreatureSpec(
    species = creature.species,
    stage = creature.stage,
    branch = creature.branch,
    mood = creature.mood,
    hatId = creature.hatId,
    weightGrams = if (creature.weightGrams.isFinite()) creature.weightGrams else 12f,
    // The one line that makes this a picture of *your* pet rather than of the species.
    morphology = creature.morphology,
    stageProgress = safe(creature.stageProgress),
)

/**
 * The pose.
 *
 * The three poses that carry meaning are lifted from the app's own idle frames rather than
 * invented here, because a creature that lies differently on the home screen than it does in the
 * app is a second creature. Everything that only exists to move — the bob, the blink, the tail
 * spring — is left at rest.
 */
private fun frameFor(creature: WidgetCreature): CreatureFrame {
    val blush = (safe(creature.bond) / 100f).coerceIn(0f, 1f)
    val base = CreatureFrame(
        eyeOpen = 1f,
        crack = safe(creature.hatchProgress),
        // Affection is the one stat that shows on the body itself, and it is the one worth
        // spending the widget's only piece of expression on.
        blush = if (creature.isDead) 0f else blush * 0.85f,
    )
    return when {
        creature.isDead -> base.copy(eyeOpen = 0f, lean = 18f, bobY = 0.06f, squash = 0.9f, blush = 0f)
        creature.isSleeping -> base.copy(eyeOpen = 0f, squash = 0.95f, lean = 4f)
        creature.isSick -> base.copy(eyeOpen = 0.55f, lean = 2f)
        else -> base
    }
}

/** A save can carry anything, including a not-a-number. The art may never be handed one. */
private fun safe(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
