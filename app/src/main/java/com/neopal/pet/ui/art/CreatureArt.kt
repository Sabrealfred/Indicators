package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Morphology
import com.neopal.pet.domain.Species
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything the renderer needs to know about *who* the creature is. */
data class CreatureSpec(
    val species: Species,
    val stage: LifeStage,
    val branch: EvolutionBranch,
    val mood: Mood,
    val hatId: String? = null,
    /** Body weight in grams; fattens the silhouette between 6 g and 120 g. */
    val weightGrams: Float = 12f,
    /**
     * The expressed genome, if this creature has one.
     *
     * Null is not "an average creature": it is the pre-genetics silhouette, drawn exactly as it
     * always was. Anything derived from a morphology is therefore reached only through a
     * non-null [Pose], and every crossfade in this file is written so that a morphology whose
     * genes all sit at zero lands back on the same numbers the null path uses.
     */
    val morphology: com.neopal.pet.domain.Morphology? = null,
)

/** Everything the renderer needs to know about *what it is doing* this frame. */
data class CreatureFrame(
    /** Vertical bob, in fractions of the creature height. Negative is up. */
    val bobY: Float = 0f,
    /** 1 = neutral, >1 stretched tall, <1 squashed wide. */
    val squash: Float = 1f,
    /** 0 = closed eyelids, 1 = wide open. */
    val eyeOpen: Float = 1f,
    /** 0 = closed mouth, 1 = wide open. */
    val mouthOpen: Float = 0f,
    /** Body tilt in degrees. */
    val lean: Float = 0f,
    /** −1..1, swings the arms and feet. */
    val armSwing: Float = 0f,
    /** 0..1 white flash used by evolution and level-up. */
    val flash: Float = 0f,
    /** Horizontal look direction, −1 left .. 1 right. */
    val gaze: Float = 0f,
    /** Vertical look direction, −1 up .. 1 down. */
    val gazeY: Float = 0f,
    /** Egg-only: 0 = intact, 1 = fully cracked. */
    val crack: Float = 0f,
    /** 0..1 warmth in the cheeks. Rides the bond stat, so affection shows as a slow blush. */
    val blush: Float = 0f,
    /** Extra tail swing on top of the wag, fed by the spring that lets it lag behind the body. */
    val tailSwing: Float = 0f,
    /** 0 = arms at rest, 1 = reaching straight up. */
    val armsUp: Float = 0f,
    /** 0..1 sweat visibility. */
    val sweat: Float = 0f,
    /** 0..1 loop position of the sweat beads running down the face. */
    val sweatPhase: Float = 0f,
    /** −1..1 tremor. Applied as a single art pixel, so anything finer is simply not drawn. */
    val shiver: Float = 0f,
    /** Accessory lag in degrees; a hat keeps leaning after the head has stopped. */
    val hatTilt: Float = 0f,
)

/** Per-stage proportions. Babies are all head and eyes; elders shrink and droop. */
private data class Proportions(
    val bodyRadius: Float,
    val bodyWidth: Float,
    val eyeRadius: Float,
    val eyeSpread: Float,
    val eyeHeight: Float,
    val limbLength: Float,
    val crest: Float,
    val tail: Float,
)

private fun proportionsFor(stage: LifeStage, branch: EvolutionBranch, weight: Float): Proportions {
    val base = when (stage) {
        LifeStage.EGG, LifeStage.BABY -> Proportions(0.30f, 1.02f, 0.098f, 0.34f, -0.05f, 0.10f, 0.05f, 0.22f)
        LifeStage.CHILD -> Proportions(0.31f, 1.00f, 0.082f, 0.36f, -0.06f, 0.16f, 0.12f, 0.30f)
        LifeStage.TEEN -> Proportions(0.32f, 0.96f, 0.070f, 0.37f, -0.08f, 0.24f, 0.22f, 0.40f)
        LifeStage.ADULT -> Proportions(0.34f, 0.96f, 0.064f, 0.38f, -0.09f, 0.28f, 0.30f, 0.46f)
        LifeStage.ELDER -> Proportions(0.32f, 1.00f, 0.055f, 0.36f, -0.07f, 0.24f, 0.26f, 0.42f)
    }
    // Weight widens the body without changing its height.
    val fat = ((weight - 12f) / 108f).coerceIn(0f, 1f)
    val branchWidth = when (branch) {
        EvolutionBranch.ATHLETIC -> -0.06f
        EvolutionBranch.GOURMAND -> 0.10f
        else -> 0f
    }
    return base.copy(bodyWidth = base.bodyWidth + fat * 0.30f + branchWidth)
}

// ------------------------------------------------------------------ inherited shape
//
// A [Morphology] arrives in fractions of the creature's own *height*. This drawing thinks in
// bodyR, which is half that height, so everything is converted once here — at the top of the
// frame, before a single shape is drawn — and handed on as a [Pose]. That keeps the knowledge
// of what a gene is out of every draw function, and it keeps the conversion in one place where
// the buffer-pixel budget can be checked against it.

/** Creature height in bodyR: the blob reaches bodyR above its centre and about as far below. */
private const val HEIGHT_IN_BODY_R = 2f

/**
 * Where every part of one creature goes this frame, with the genome already spent.
 *
 * The upright and four-legged poses are not two drawings. They are one set of anchors that
 * crossfade: at [quad] 0 the head *is* the body and the trunk is the same blob hidden exactly
 * behind it, and at 1 the head has walked forward onto a neck while the trunk has flattened
 * into a barrel carried on four legs. Every value below is written so that [quad] = 0 reproduces
 * the pre-genetics numbers exactly, which is what lets an intermediate value read as a crouch
 * rather than as a broken halfway house.
 */
private class Pose(
    /** 0 = upright, 1 = on all fours. */
    val quad: Float,
    /** Centre of the head blob — the body's own centre while [quad] is 0. */
    val headCenter: Offset,
    val headR: Float,
    /** Multiplier on the head's width: a muzzled head narrows as it comes forward. */
    val headWidth: Float,
    val trunkCenter: Offset,
    /** Half-width and half-height of the barrel. */
    val trunkW: Float,
    val trunkH: Float,
    /** The purely genetic part of body width, with weight and branch already removed. */
    val widthMul: Float,
    /** Leg length as a fraction of its own ceiling; 0.34 is the value the old art was drawn at. */
    val legFrac: Float,
    /** Ground clearance the barrel is carried at once the creature is fully four-legged. */
    val legPx: Float,
    val muzzlePx: Float,
    val muzzleFrac: Float,
    val earPx: Float,
    val earDroop: Float,
    /** Tail length in the same units the old [Proportions.tail] used: multiples of bodyR. */
    val tailUnits: Float,
    val shag: Float,
    /** The line the feet stand on. Fixed, so growing legs raises the body instead of sinking it. */
    val groundY: Float,
)

/**
 * Resolves [m] against the body this frame.
 *
 * Offsets from the body centre are rounded to whole art pixels for the same reason the bob is:
 * a head sitting on a half pixel resamples its own outline. They are rounded as *offsets* and
 * not as absolute positions, so a zero offset stays bit-for-bit zero and the creature never
 * shifts by half a pixel relative to where it used to be drawn.
 */
private fun poseFor(
    m: Morphology,
    bodyCenter: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
): Pose {
    val q = m.quadruped.coerceIn(0f, 1f)
    // Matches the ground shadow's own y, so the feet land where the contact shadow is drawn.
    val groundY = bodyCenter.y + bodyR * 1.15f

    // p.bodyWidth already carries weight and branch, and Morphology.bodyWidth carries the same
    // two plus the build gene. Dividing by what Morphology would have produced for a *mid-build*
    // creature of this weight and branch leaves only the genetic part, so nothing is counted
    // twice and a mid-build creature comes out at exactly 1.
    val fat = ((spec.weightGrams - 12f) / 108f).coerceIn(0f, 1f)
    val branchWidth = when (spec.branch) {
        EvolutionBranch.ATHLETIC -> -0.06f
        EvolutionBranch.GOURMAND -> 0.10f
        else -> 0f
    }
    val widthMul = (m.bodyWidth / (0.98f + fat * 0.30f + branchWidth)).coerceIn(0.78f, 1.28f)

    val legFrac = (m.legLength / Morphology.MAX_LEG).coerceIn(0f, 1f)
    // Ground clearance. The floor of 0.20 is there because a quadruped with no legs at all is a
    // slug; the 0.62 span puts a maximally leggy hound's belly at about half its own height.
    val legPx = bodyR * (0.20f + 0.62f * legFrac)

    // The barrel starts life *inside* the body blob and flattens and lengthens as the creature
    // goes over. Starting it a whisker smaller than the head rather than the same size is what
    // buys the crossfade its silence: for the first tenth of the stance gene the barrel is
    // wholly hidden behind the head it is drawn beneath, so a founder with a stance of 0.05
    // shows no second outline at all, and by the time the barrel does clear the head it is
    // clearing it backwards — which is a body growing out behind the shoulders, not a seam.
    val trunkW = lerpF(bodyR * p.bodyWidth * widthMul * 0.92f, bodyR * p.bodyWidth * widthMul, q)
    val trunkH = lerpF(bodyR * 0.92f, bodyR * 0.50f, q)
    val trunkTargetY = groundY - legPx - trunkH * 0.98f
    val trunkDx = -bodyR * 0.30f * q
    val trunkDy = (trunkTargetY - bodyCenter.y) * q
    val trunkCenter = Offset(
        bodyCenter.x + trunkDx.roundToInt().toFloat(),
        bodyCenter.y + trunkDy.roundToInt().toFloat(),
    )

    // The head keeps the face, so it shrinks rather than vanishes: at full quadruped it is 70%
    // of the old blob, far enough forward to clear the shoulder and a little above the back.
    val headDx = bodyR * 0.66f * q
    val headDy = (trunkTargetY - bodyR * 0.20f - bodyCenter.y) * q
    val headCenter = Offset(
        bodyCenter.x + headDx.roundToInt().toFloat(),
        bodyCenter.y + headDy.roundToInt().toFloat(),
    )

    return Pose(
        quad = q,
        headCenter = headCenter,
        headR = bodyR * (1f - 0.30f * q),
        headWidth = 1f - 0.12f * q,
        trunkCenter = trunkCenter,
        trunkW = trunkW,
        trunkH = trunkH,
        widthMul = widthMul,
        legFrac = legFrac,
        legPx = legPx,
        // A snout that juts at the viewer out of a front-facing face reads as a chin, so an
        // upright creature only spends about two thirds of its muzzle gene; the rest arrives as
        // the head turns side-on and the snout has somewhere to go.
        muzzlePx = bodyR * HEIGHT_IN_BODY_R * m.muzzleLength * lerpF(0.55f, 0.86f, q),
        muzzleFrac = (m.muzzleLength / Morphology.MAX_MUZZLE).coerceIn(0f, 1f),
        earPx = bodyR * HEIGHT_IN_BODY_R * m.earLength * 0.80f,
        earDroop = m.earDroop.coerceIn(0f, 1f),
        tailUnits = HEIGHT_IN_BODY_R * m.tailLength,
        shag = m.shagginess.coerceIn(0f, 1f),
        groundY = groundY,
    )
}

/** Blends two anchors. At [t] = 0 the result is [a] to the bit, which several crossfades rely on. */
private fun lerpOffset(a: Offset, b: Offset, t: Float) =
    Offset(lerpF(a.x, b.x, t), lerpF(a.y, b.y, t))

/** 1/|v|, so a hand-picked direction can be made a unit vector without a vector type. */
private fun invLength(dx: Float, dy: Float): Float {
    val d = dx * dx + dy * dy
    return if (d < 1e-6f) 0f else 1f / sqrt(d)
}

/**
 * Deterministic 0..1 from an index. Fur has to sit in exactly the same place every frame — a
 * tuft that redraws itself somewhere new is the one thing worse than no tuft at all — and this
 * is cheaper and more predictable than carrying a seeded generator through the draw call.
 */
private fun hashUnit(i: Int): Float {
    var h = i * 374761393 + 668265263
    h = (h xor (h shr 13)) * 1274126177
    return ((h xor (h shr 16)) and 0xFFFF) / 65535f
}

/**
 * Rotates one colour's hue by a precomputed angle, on the usual luminance-preserving RGB matrix.
 *
 * A proper HSV round trip would be more correct and would cost three branches per channel; at
 * the ±15° this is ever asked for, the matrix is indistinguishable and allocation-free, because
 * [Color] is a value class over a packed long.
 */
private fun hueRotate(c: Color, cosA: Float, sinA: Float): Color {
    val flat = (1f - cosA) / 3f
    val skew = 0.57735f * sinA
    val m0 = cosA + flat
    val m1 = flat - skew
    val m2 = flat + skew
    return Color(
        (c.red * m0 + c.green * m1 + c.blue * m2).coerceIn(0f, 1f),
        (c.red * m2 + c.green * m0 + c.blue * m1).coerceIn(0f, 1f),
        (c.red * m1 + c.green * m2 + c.blue * m0).coerceIn(0f, 1f),
        c.alpha,
    )
}

/**
 * The species palette, rotated far enough that two siblings are told apart and not so far that
 * either stops being its own species.
 *
 * `Morphology.hueShift` is centred on 0 and only ever reaches about ±0.25, so 60° of gain gives
 * a real lineage roughly ±15° — a blue that has gone slightly green, not a blue that has gone
 * purple. The eye colour is left alone: it is nearly black, and rotating a near-black tints it
 * without ever changing its hue in a way anyone can see.
 */
private fun hueShifted(p: CreaturePalette, shift: Float): CreaturePalette {
    val rad = shift.coerceIn(-1f, 1f) * 60f * PI.toFloat() / 180f
    val ca = cos(rad)
    val sa = sin(rad)
    return p.copy(
        body = hueRotate(p.body, ca, sa),
        bodyShade = hueRotate(p.bodyShade, ca, sa),
        belly = hueRotate(p.belly, ca, sa),
        accent = hueRotate(p.accent, ca, sa),
        outline = hueRotate(p.outline, ca, sa),
        blush = hueRotate(p.blush, ca, sa),
    )
}

// ------------------------------------------------------------------ light and tone
//
// The scene is drawn into a ~200 px-tall buffer and blown back up with nearest-neighbour, and
// the creature fills roughly 120 px of it. bodyR is therefore about 60 *buffer pixels*, so one
// pixel is 1/60 ≈ 0.017f * bodyR. Anything expressed as a smaller fraction than that lands on
// a sub-pixel and is simply thrown away by the downscale — 0.02f * bodyR is the honest floor
// (1.2 px), 0.035f is a comfortable two pixels, 0.05f is three. Every stroke width and every
// tone band in this file is sized against that scale, and [band] enforces the floor.

/** One buffer pixel, as a fraction of bodyR, rounded up so a "1 px" line is never 0.9 px. */
private const val MIN_BAND = 0.02f

/** Warm key light. Everything lit and every rim is tinted toward this, never toward pure white. */
private val SCENE_LIGHT = Color(0xFFFFF2CC)

/** Cool sky fill. Shadows lean toward this instead of toward black, which is what stops them muddying. */
private val AMBIENT_COOL = Color(0xFF6E7BB8)

/** Below this luminance a line stops reading as "dark colour" and starts reading as soot. */
private const val OUTLINE_FLOOR = 0.25f

/** Clamps a stroke or band to at least one buffer pixel. */
private fun band(bodyR: Float, fraction: Float) = bodyR * max(fraction, MIN_BAND)

/**
 * The tone ladder one shape is painted with. Three body tones only: at 60 px across, three
 * broad bands read as deliberate painting and ten read as mud.
 */
private class Tones(
    val light: Color,
    val mid: Color,
    val shadow: Color,
    /** Outline against the body itself. */
    val line: Color,
    /** Outline where the shape meets the belly or another light area, so it does not punch a hole. */
    val lineSoft: Color,
    val rim: Color,
)

private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

private fun desaturate(c: Color, t: Float): Color {
    val mean = (c.red + c.green + c.blue) / 3f
    return Color(lerpF(c.red, mean, t), lerpF(c.green, mean, t), lerpF(c.blue, mean, t), c.alpha)
}

private fun darken(c: Color, amount: Float): Color {
    val k = (1f - amount).coerceIn(0f, 1f)
    return Color(c.red * k, c.green * k, c.blue * k, c.alpha)
}

/** Scales a colour up until it clears [floor], keeping its hue. */
private fun liftTo(c: Color, floor: Float): Color {
    val l = luminance(c)
    if (l >= floor || l <= 0.001f) return c
    val k = floor / l
    return Color(
        (c.red * k).coerceAtMost(1f),
        (c.green * k).coerceAtMost(1f),
        (c.blue * k).coerceAtMost(1f),
        c.alpha,
    )
}

/**
 * The single biggest change in this file. The palette's `outline` is nearly black, and a black
 * key line is what makes hand-drawn character art look cheap. This derives the line from the
 * shape's own colour instead: pull a little saturation out so it reads as *dark colour* rather
 * than ink, darken it, tip it a touch toward the ambient, and never let it fall past 25%
 * luminance. [lift] raises it further for edges that sit against the belly or the floor.
 */
private fun outlineFor(base: Color, lift: Float = 0f): Color {
    val muted = desaturate(base, 0.20f + lift * 0.10f)
    val darkened = darken(muted, 0.58f - lift * 0.26f)
    return liftTo(lerp(darkened, AMBIENT_COOL, 0.08f), OUTLINE_FLOOR + lift * 0.10f)
}

private fun tonesFor(base: Color, shade: Color? = null): Tones {
    val core = shade ?: darken(base, 0.26f)
    return Tones(
        light = lerp(base, SCENE_LIGHT, 0.24f),
        mid = base,
        shadow = lerp(core, AMBIENT_COOL, 0.14f),
        line = outlineFor(base),
        lineSoft = outlineFor(base, lift = 0.45f),
        rim = lerp(base, SCENE_LIGHT, 0.66f),
    )
}

/**
 * Moves a point [k] of the way toward [pivot]; the cheap stand-in for a clip path. Shrinking a
 * convex shape toward a point inside it keeps every copy inside the silhouette, which is how the
 * features get their tone bands without clipping.
 */
private fun Offset.toward(pivot: Offset, k: Float) =
    Offset(pivot.x + (x - pivot.x) * k, pivot.y + (y - pivot.y) * k)

/**
 * A weighted blend of three corners. Weights are positive and sum to one, so the result is
 * always inside the triangle they span — that is the guarantee [toward] needs from its pivot.
 * Leaning the weights toward the tip and the shaded corner is what puts the lit band up-left.
 */
private fun inside(a: Offset, b: Offset, c: Offset, wa: Float, wb: Float, wc: Float) =
    Offset(a.x * wa + b.x * wb + c.x * wc, a.y * wa + b.y * wb + c.y * wc)

/**
 * Draws the whole creature centred in [center], sized against [unit] (usually the smaller
 * side of the canvas). Every part is a vector path, so the art scales to any screen and to
 * any stage without a single bitmap.
 */
fun DrawScope.drawCreature(
    center: Offset,
    unit: Float,
    spec: CreatureSpec,
    frame: CreatureFrame,
) {
    val morph = spec.morphology
    val palette = Palettes.creature(spec.species, spec.branch).let {
        // Below a quarter of a degree the rotation cannot survive the downscale, and skipping it
        // is also what guarantees an ungenomed creature keeps the palette it has always had.
        if (morph == null || abs(morph.hueShift) < 0.004f) it else hueShifted(it, morph.hueShift)
    }
    if (spec.stage == LifeStage.EGG) {
        drawEgg(center, unit, palette, frame)
        return
    }

    val p = proportionsFor(spec.stage, spec.branch, spec.weightGrams)
    val bodyR = unit * p.bodyRadius
    // Whole art pixels only. A body that bobs in fractions of a pixel resamples its own outline
    // every frame, which reads as a shimmer rather than as breathing.
    val cy = center.y + (frame.bobY * unit).roundToInt()
    val tremor = (frame.shiver * unit * 0.007f).roundToInt().toFloat()
    val bodyCenter = Offset(center.x + tremor, cy)

    val pose = morph?.let { poseFor(it, bodyCenter, bodyR, p, spec) }
    // Everything the face wears — eyes, ears, snout, hat, sweat, glasses — hangs off these two
    // instead of off the body. With no morphology they *are* the body, which is what keeps the
    // ungenomed render identical: same anchors, same arguments, same calls.
    val headC = pose?.headCenter ?: bodyCenter
    val headR = pose?.headR ?: bodyR

    // Height is only legible through the shadow: it tightens and thins on the way up and is
    // back at full weight the instant the feet land. A creature on four feet spreads its
    // contact patch over a body's length instead of a body's width, so the pool grows with it.
    val lift = (-frame.bobY * 6.25f).coerceIn(0f, 1f)
    drawGroundShadow(
        x = center.x,
        y = center.y + bodyR * 1.15f,
        radius = bodyR * (1.05f - lift * 0.40f) * (1f + (pose?.quad ?: 0f) * 0.42f),
        palette = palette,
        strength = 1f - lift * 0.58f,
    )

    rotate(degrees = frame.lean, pivot = Offset(center.x + tremor, center.y + bodyR)) {
        // Back-most parts first: tail, then back limbs, then body, then face, then hat.
        drawTail(bodyCenter, bodyR, p, spec, palette, frame, pose)
        drawLimbs(bodyCenter, bodyR, p, palette, frame, back = true, pose = pose)
        if (pose != null) drawTrunk(pose, bodyR, spec, palette, frame)
        drawBody(headC, headR, p, spec, palette, frame, bandR = bodyR, pose = pose)
        drawSpeciesFeatures(headC, headR, p, spec, palette, frame)
        if (pose != null) drawEars(headC, headR, p, pose, palette, frame)
        drawLimbs(bodyCenter, bodyR, p, palette, frame, back = false, pose = pose)
        drawFace(headC, headR, p, spec, palette, frame, pose)
        drawBranchMarks(headC, headR, p, spec, palette)
        spec.hatId?.let { drawHat(it, headC, headR, p, frame) }
        drawSweat(headC, headR, p, frame)
        if (spec.stage == LifeStage.ELDER) drawElderMarks(headC, headR, palette)
    }

    if (frame.flash > 0.01f) {
        drawCircle(
            color = Color.White.copy(alpha = frame.flash.coerceIn(0f, 1f)),
            radius = bodyR * (1.4f + frame.flash),
            center = bodyCenter,
        )
    }
}

// ------------------------------------------------------------------ body parts

private fun DrawScope.drawGroundShadow(
    x: Float,
    y: Float,
    radius: Float,
    palette: CreaturePalette,
    strength: Float = 1f,
) {
    // Two passes: a wide faint one plus a tight core, so the contact shadow has an edge that
    // fades instead of a hard rim of near-black under the feet.
    val cast = lerp(palette.bodyShade, AMBIENT_COOL, 0.40f)
    val k = strength.coerceIn(0f, 1f)
    drawOval(
        color = cast.copy(alpha = 0.10f * k),
        topLeft = Offset(x - radius * 1.12f, y - radius * 0.27f),
        size = Size(radius * 2.24f, radius * 0.54f),
    )
    drawOval(
        color = cast.copy(alpha = 0.20f * k),
        topLeft = Offset(x - radius, y - radius * 0.22f),
        size = Size(radius * 2f, radius * 0.44f),
    )
}

/** The silhouette, unchanged: the same control points the old flat fill used. */
private fun bodyPath(c: Offset, w: Float, h: Float): Path {
    val bw = w
    val bh = h
    return Path().apply {
        moveTo(c.x, c.y - bh)
        cubicTo(c.x + bw * 1.05f, c.y - bh * 0.92f, c.x + bw * 1.12f, c.y + bh * 0.35f, c.x + bw * 0.92f, c.y + bh * 0.80f)
        cubicTo(c.x + bw * 0.70f, c.y + bh * 1.10f, c.x - bw * 0.70f, c.y + bh * 1.10f, c.x - bw * 0.92f, c.y + bh * 0.80f)
        cubicTo(c.x - bw * 1.12f, c.y + bh * 0.35f, c.x - bw * 1.05f, c.y - bh * 0.92f, c.x, c.y - bh)
        close()
    }
}

// The shading bands below reuse exact sub-segments of the silhouette curves (split with de
// Casteljau and baked in as constants), so a band's outer edge lies *on* the body outline
// rather than near it. Inset factor 0.98 pulls them ~1.2 px inward, which is inside the 1.65 px
// that the 0.055f outline stroke covers, so no seam shows.
private const val BAND_INSET = 0.98f

/** Lower-right of the body plus the underside: everything past the terminator. */
private fun shadowPath(c: Offset, w: Float, h: Float): Path {
    val bw = w * BAND_INSET
    val bh = h * BAND_INSET
    return Path().apply {
        moveTo(c.x + bw * 0.7728f, c.y - bh * 0.5648f)
        // Right edge from the terminator down to the hip, then the whole underside.
        cubicTo(c.x + bw * 1.0659f, c.y - bh * 0.1315f, c.x + bw * 1.05f, c.y + bh * 0.5075f, c.x + bw * 0.92f, c.y + bh * 0.80f)
        cubicTo(c.x + bw * 0.70f, c.y + bh * 1.10f, c.x - bw * 0.70f, c.y + bh * 1.10f, c.x - bw * 0.92f, c.y + bh * 0.80f)
        // Terminator: bows with the body instead of cutting a straight diagonal across it.
        cubicTo(c.x - bw * 0.34f, c.y + bh * 0.42f, c.x + bw * 0.30f, c.y + bh * 0.02f, c.x + bw * 0.7728f, c.y - bh * 0.5648f)
        close()
    }
}

/** The lit plane: a broad crescent up the top-left edge, where the key light lands. */
private fun litPath(c: Offset, w: Float, h: Float): Path {
    val bw = w * BAND_INSET
    val bh = h * BAND_INSET
    return Path().apply {
        moveTo(c.x - bw * 0.9850f, c.y - bh * 0.0050f)
        cubicTo(c.x - bw * 0.9072f, c.y - bh * 0.4756f, c.x - bw * 0.63f, c.y - bh * 0.952f, c.x, c.y - bh)
        cubicTo(c.x - bw * 0.20f, c.y - bh * 0.78f, c.x - bw * 0.56f, c.y - bh * 0.36f, c.x - bw * 0.9850f, c.y - bh * 0.0050f)
        close()
    }
}

/**
 * Rim light path along the top-left. Inset 0.955 so its 2 px stroke sits just *inside* the
 * outline rather than straddling the silhouette. [core] is the short bright section over the
 * shoulder; the long one runs underneath it at low alpha, which fakes a taper that a uniform
 * stroke cannot give on its own.
 */
private fun rimPath(c: Offset, w: Float, h: Float, core: Boolean): Path {
    val bw = w * 0.955f
    val bh = h * 0.955f
    return Path().apply {
        if (core) {
            moveTo(c.x - bw * 0.7727f, c.y - bh * 0.5648f)
            cubicTo(c.x - bw * 0.6149f, c.y - bh * 0.7982f, c.x - bw * 0.3675f, c.y - bh * 0.972f, c.x, c.y - bh)
            cubicTo(c.x + bw * 0.105f, c.y - bh * 0.992f, c.x + bw * 0.2002f, c.y - bh * 0.9721f, c.x + bw * 0.2863f, c.y - bh * 0.9423f)
        } else {
            moveTo(c.x - bw * 0.9850f, c.y - bh * 0.0050f)
            cubicTo(c.x - bw * 0.9072f, c.y - bh * 0.4756f, c.x - bw * 0.63f, c.y - bh * 0.952f, c.x, c.y - bh)
            cubicTo(c.x + bw * 0.189f, c.y - bh * 0.9856f, c.x + bw * 0.3462f, c.y - bh * 0.9326f, c.x + bw * 0.4759f, c.y - bh * 0.8528f)
        }
    }
}

/**
 * The body blob, unchanged in every particular except where it is drawn and how wide.
 *
 * [bandR] is the radius every stroke width is measured against, and it is deliberately *not*
 * this shape's own radius: a quadruped's head is 70% of the old body, and letting its outline
 * shrink with it would drop the key line under a buffer pixel while the barrel beside it kept
 * a fat one. One creature, one line weight.
 */
private fun DrawScope.drawBody(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
    palette: CreaturePalette,
    frame: CreatureFrame,
    bandR: Float = bodyR,
    pose: Pose? = null,
) {
    val w = bodyR * p.bodyWidth * (pose?.headWidth ?: 1f) * (pose?.widthMul ?: 1f) / frame.squash
    val h = bodyR * frame.squash
    drawBlob(
        c = center,
        w = w,
        h = h,
        bandR = bandR,
        mood = spec.mood,
        palette = palette,
        shag = pose?.shag ?: 0f,
        // The crown and the back of the neck: the arc a hand would run the wrong way up.
        furFrom = 196f,
        furTo = 322f,
    )
}

/**
 * One shaded egg — the shape the head, and on a quadruped the barrel too, are both made of.
 *
 * Split out of [drawBody] verbatim so that a second blob costs nothing new. Note that the
 * fills are opaque: where the head overlaps the trunk it paints over it completely, which is
 * why the mood wash and the belly never double up in the overlap.
 */
private fun DrawScope.drawBlob(
    c: Offset,
    w: Float,
    h: Float,
    bandR: Float,
    mood: Mood,
    palette: CreaturePalette,
    shag: Float,
    furFrom: Float,
    furTo: Float,
) {
    val tones = tonesFor(palette.body, palette.bodyShade)
    // Fur first, so the tufts are rooted *under* the silhouette and only their ends show.
    if (shag > 0.02f) drawFur(c, w, h, bandR, shag, furFrom, furTo, tones)

    val body = bodyPath(c, w, h)
    drawPath(body, tones.mid, style = Fill)

    // Key light from the upper left: one mid tone, one shadow that wraps the lower right and
    // the underside, one lit crescent. Both bands are ~20 px across at this size — broad enough
    // to read as form rather than as noise.
    val shadow = shadowPath(c, w, h)
    drawPath(shadow, tones.shadow, style = Fill)
    drawPath(litPath(c, w, h), tones.light, style = Fill)

    drawBelly(c, w, h, palette, tones)
    // Same shadow again over the belly. Shadow-over-shadow is a no-op, so this only bends the
    // belly patch into the same light without needing a second, differently shaped path.
    drawPath(shadow, tones.shadow.copy(alpha = 0.16f), style = Fill)

    // Mood reads first as colour, before any animation: a glance should be enough.
    val wash = when (mood) {
        Mood.SICK -> Color(0xFF7FBF6A).copy(alpha = 0.30f)
        Mood.HUNGRY -> Color(0xFFFFF4D6).copy(alpha = 0.22f)
        Mood.TIRED -> Color(0xFF5C6BA8).copy(alpha = 0.20f)
        Mood.SAD -> Color(0xFF6E7A99).copy(alpha = 0.18f)
        Mood.DIRTY -> Color(0xFF7A6A4F).copy(alpha = 0.20f)
        Mood.HAPPY -> Color(0xFFFFE7A8).copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    if (wash.alpha > 0f) drawPath(body, wash, style = Fill)

    drawPath(body, tones.line, style = Stroke(width = band(bandR, 0.055f)))
    // Rim last, on top of the inner half of the outline, so the edge catches the light.
    drawPath(
        rimPath(c, w, h, core = false),
        tones.rim.copy(alpha = 0.42f),
        style = Stroke(width = band(bandR, 0.035f), cap = StrokeCap.Round),
    )
    drawPath(
        rimPath(c, w, h, core = true),
        tones.rim.copy(alpha = 0.90f),
        style = Stroke(width = band(bandR, 0.030f), cap = StrokeCap.Round),
    )
}

/**
 * The belly is four nested ovals rather than one ellipse. Each step drops the top edge by
 * ~2.5 buffer pixels and adds a quarter of the belly colour, which is the softest blend a
 * 60-unit body can carry before the steps themselves start to show.
 */
private fun DrawScope.drawBelly(c: Offset, w: Float, h: Float, palette: CreaturePalette, tones: Tones) {
    val steps = 4
    for (i in 0 until steps) {
        val t = i / (steps - 1f)
        val bw = w * (1.04f - t * 0.10f)
        val bh = h * (0.95f - t * 0.16f)
        val top = c.y - h * 0.05f + h * 0.13f * t
        drawOval(
            color = lerp(tones.mid, palette.belly, 0.40f + t * 0.60f),
            topLeft = Offset(c.x - bw / 2f, top),
            size = Size(bw, bh),
        )
    }
}

/**
 * Chunky fur tufts poking out past the outline.
 *
 * Coat is the one gene with no room to be subtle: at bodyR ≈ 60 px a strand of fur is a third
 * of a pixel and downsamples into a smear, so the coat is spent on a handful of tufts three to
 * six pixels long instead — few enough to count, big enough to survive. They are drawn as
 * round-capped lines rather than paths so that a shaggy creature costs no allocations at all,
 * and they lean backward, because fur that radiates evenly reads as a sea urchin.
 */
private fun DrawScope.drawFur(
    c: Offset,
    w: Float,
    h: Float,
    bandR: Float,
    shag: Float,
    fromDeg: Float,
    toDeg: Float,
    tones: Tones,
) {
    val count = 3 + (shag * 5f).roundToInt()
    for (i in 0 until count) {
        val t = if (count == 1) 0.5f else i / (count - 1f)
        val jitter = hashUnit(i)
        // ±5° of scatter: enough that the row is not a comb, small enough that it is still a row.
        val a = (lerpF(fromDeg, toDeg, t) + (jitter - 0.5f) * 10f) * PI.toFloat() / 180f
        val ca = cos(a)
        val sa = sin(a)
        // 0.94 sinks the root a pixel inside the silhouette so no tuft floats free of the body.
        val root = Offset(c.x + w * ca * 0.94f, c.y + h * sa * 0.94f)
        val dx = ca - 0.26f
        val dy = sa - 0.06f
        val inv = invLength(dx, dy)
        val len = bandR * (0.09f + 0.15f * shag) * (0.74f + jitter * 0.52f)
        val tip = Offset(root.x + dx * inv * len, root.y + dy * inv * len)
        drawLine(tones.lineSoft, root, tip, strokeWidth = band(bandR, 0.075f), cap = StrokeCap.Round)
        drawLine(tones.shadow, root, tip, strokeWidth = band(bandR, 0.045f), cap = StrokeCap.Round)
    }
}

/**
 * The barrel a four-legged creature carries between its legs, and the neck that reaches from it
 * to the head.
 *
 * Rotating the upright body toward horizontal is the obvious implementation and the wrong one:
 * every shading path in this file is a baked sub-segment of the silhouette, so the whole tone
 * ladder would rotate with the shape and the key light would end up coming from underneath.
 * The barrel is therefore the same egg drawn wide and shallow. The silhouette is what the eye
 * reads as a body lying along the ground; the light stays where it belongs.
 */
private fun DrawScope.drawTrunk(
    pose: Pose,
    bodyR: Float,
    spec: CreatureSpec,
    palette: CreaturePalette,
    frame: CreatureFrame,
) {
    // Under this the barrel is smaller than the head, in the head's own place and drawn behind
    // it, so it contributes nothing to the silhouette. Skipping it there costs nothing and
    // spares an upright creature two dozen draw calls it would never see the result of.
    if (pose.quad < 0.04f) return
    // Squash belongs to the head. A body lying along the ground takes only a share of it,
    // because it stretches along its own length rather than upward.
    val s = 1f + (frame.squash - 1f) * (1f - pose.quad * 0.65f)
    drawBlob(
        c = pose.trunkCenter,
        w = pose.trunkW / s,
        h = pose.trunkH * s,
        bandR = bodyR,
        mood = spec.mood,
        palette = palette,
        shag = pose.shag,
        // Rump, up over the back, stopping short of the shoulder where the neck lands.
        furFrom = 150f,
        furTo = 296f,
    )

    // Neck. A plain capsule: it is only ever seen in the gap between two blobs that are already
    // shaded, so tone bands of its own would be three pixels of detail nobody can read.
    val tones = tonesFor(palette.body, palette.bodyShade)
    val shoulder = Offset(
        pose.trunkCenter.x + pose.trunkW * 0.44f,
        pose.trunkCenter.y - pose.trunkH * 0.30f,
    )
    val nape = Offset(
        pose.headCenter.x - pose.headR * 0.22f,
        pose.headCenter.y + pose.headR * 0.30f,
    )
    val thick = pose.headR * (0.48f + 0.24f * (1f - pose.quad))
    drawLine(tones.lineSoft, shoulder, nape, strokeWidth = thick + band(bodyR, 0.06f), cap = StrokeCap.Round)
    drawLine(tones.mid, shoulder, nape, strokeWidth = thick, cap = StrokeCap.Round)
    drawLine(
        tones.light,
        Offset(shoulder.x, shoulder.y - thick * 0.24f),
        Offset(nape.x, nape.y - thick * 0.24f),
        strokeWidth = thick * 0.30f,
        cap = StrokeCap.Round,
    )
}

/**
 * Arms and feet — or, once the genome says so, four legs.
 *
 * The crossfade is the point: a biped already has four limbs, so nothing is grown or discarded.
 * The arms swing down and forward into the front legs, the feet walk back under the rump and
 * grow a shank each, and every position between is a real intermediate rather than a dissolve
 * between two drawings. Every quadruped target is reached through [lerpOffset] at [Pose.quad],
 * so a stance of 0 lands on the biped numbers exactly.
 */
private fun DrawScope.drawLimbs(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    palette: CreaturePalette,
    frame: CreatureFrame,
    back: Boolean,
    pose: Pose?,
) {
    if (p.limbLength <= 0.001f) return
    val tones = tonesFor(palette.body, palette.bodyShade)
    val q = pose?.quad ?: 0f
    // 0.34 is the leg gene the old proportions were drawn at, so an average creature keeps the
    // limbs it always had and only a deviation from average lengthens or shortens them.
    val legFrac = pose?.legFrac ?: 0.34f
    val w = bodyR * p.bodyWidth * (pose?.widthMul ?: 1f)
    val swing = frame.armSwing * bodyR * 0.35f
    val armY = center.y + bodyR * 0.18f
    val len = bodyR * p.limbLength * (1f + 0.55f * (legFrac - 0.34f))
    val thickness = bodyR * 0.20f
    val dir = if (back) -1f else 1f
    // Reaching up pulls the hands in as well as up, which is what stops a stretch reading as a
    // T-pose. The shoulders stay put; only the ends travel.
    val reach = frame.armsUp.coerceIn(0f, 1f)

    // Arms
    listOf(-1f, 1f).forEach { side ->
        var start = Offset(center.x + side * w * 0.86f, armY)
        var end = Offset(
            start.x + side * len * (1f - reach * 0.55f),
            armY + swing * side * dir - reach * (len + bodyR * 0.55f),
        )
        if (pose != null) {
            // Front legs hang off the chest, at the shoulder end of the barrel. The two sides
            // are pushed a pixel or two apart in x rather than mirrored: at this size that is
            // the whole of the depth cue, and mirroring would put the far leg through the near.
            val shoulder = Offset(
                pose.trunkCenter.x + pose.trunkW * 0.52f + side * bodyR * 0.10f,
                pose.trunkCenter.y + pose.trunkH * 0.42f,
            )
            val paw = Offset(
                shoulder.x + side * bodyR * 0.05f + swing * 0.55f * dir,
                // Rearing keeps working on all fours: armsUp lifts the front feet off the floor.
                pose.groundY - bodyR * 0.06f - reach * (pose.legPx + bodyR * 0.5f),
            )
            start = lerpOffset(start, shoulder, q)
            end = lerpOffset(end, paw, q)
        }
        if (back) {
            // Behind the body: shadow tone only, so the arm sits back instead of competing.
            drawLine(tones.shadow.copy(alpha = 0.85f), start, end, strokeWidth = thickness, cap = StrokeCap.Round)
            drawCircle(tones.shadow.copy(alpha = 0.85f), radius = thickness * 0.62f, center = end)
            return@forEach
        }
        drawLine(tones.mid, start, end, strokeWidth = thickness, cap = StrokeCap.Round)
        // The two accent lines are inset far enough (offset + half width < half thickness) that
        // they stay inside the arm and never widen its silhouette.
        val lift = bodyR * 0.030f
        drawLine(
            tones.shadow, Offset(start.x, start.y + lift), Offset(end.x, end.y + lift),
            strokeWidth = thickness * 0.45f, cap = StrokeCap.Round,
        )
        drawLine(
            tones.light, Offset(start.x - lift * 0.5f, start.y - lift), Offset(end.x - lift * 0.5f, end.y - lift),
            strokeWidth = thickness * 0.30f, cap = StrokeCap.Round,
        )
        drawCircle(tones.mid, radius = thickness * 0.62f, center = end)
        drawCircle(tones.light, radius = thickness * 0.30f, center = Offset(end.x - lift, end.y - lift))
    }

    if (back) return
    // Feet, which are also the hind paws: they walk back under the rump as the stance drops.
    listOf(-1f, 1f).forEach { side ->
        // A leggy biped stands a little taller off its feet, a stubby one sits down on them.
        // The swing is small on purpose — the feet must not leave the contact shadow.
        var fx = center.x + side * w * 0.42f
        var fy = center.y + bodyR * (1.02f + 0.16f * (legFrac - 0.34f))
        var top = tones.mid
        if (pose != null) {
            val hip = Offset(
                pose.trunkCenter.x - pose.trunkW * 0.50f + side * bodyR * 0.10f,
                pose.trunkCenter.y + pose.trunkH * 0.40f,
            )
            // Contra-lateral to the front paw, so a walk cycle reads as a walk and not a hop.
            val paw = Offset(hip.x - bodyR * 0.04f - swing * 0.55f * side, pose.groundY - bodyR * 0.08f)
            fx = lerpF(fx, paw.x, q)
            fy = lerpF(fy, paw.y, q)
            // The far hind leg sinks toward the shadow tone rather than being drawn again in a
            // separate pass: one flat tone step is all the depth two pixels of offset can carry.
            if (side < 0f) top = lerp(tones.mid, tones.shadow, q * 0.75f)
            if (q > 0.04f) {
                val shank = band(bodyR, 0.19f * q)
                drawLine(tones.lineSoft, hip, Offset(fx, fy), strokeWidth = shank + band(bodyR, 0.05f), cap = StrokeCap.Round)
                drawLine(if (side < 0f) tones.shadow else tones.mid, hip, Offset(fx, fy), strokeWidth = shank, cap = StrokeCap.Round)
            }
        }
        drawOval(
            color = tones.shadow,
            topLeft = Offset(fx - bodyR * 0.24f, fy - bodyR * 0.10f),
            size = Size(bodyR * 0.48f, bodyR * 0.24f),
        )
        // Lit top of the foot, ~2 px in from the edge on every side.
        drawOval(
            color = top,
            topLeft = Offset(fx - bodyR * 0.21f, fy - bodyR * 0.085f),
            size = Size(bodyR * 0.40f, bodyR * 0.17f),
        )
        drawOval(
            color = tones.lineSoft,
            topLeft = Offset(fx - bodyR * 0.24f, fy - bodyR * 0.10f),
            size = Size(bodyR * 0.48f, bodyR * 0.24f),
            style = Stroke(width = band(bodyR, 0.035f)),
        )
    }
}

private fun DrawScope.drawTail(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
    palette: CreaturePalette,
    frame: CreatureFrame,
    pose: Pose?,
) {
    // The tail gene is a fraction of height; [Proportions.tail] is a multiple of bodyR, which is
    // half of it. Converting once here means the four species tails below are untouched.
    val tailUnits = pose?.tailUnits ?: p.tail
    if (tailUnits <= 0.01f) return
    val w = bodyR * p.bodyWidth * (pose?.widthMul ?: 1f)
    // The wag is the pose; [CreatureFrame.tailSwing] is the spring that lets the tip carry on
    // after the body has stopped.
    val wag = (frame.armSwing * 0.5f + frame.tailSwing).coerceIn(-1.6f, 1.6f)
    var baseX = center.x - w * 0.85f
    var baseY = center.y + bodyR * 0.45f
    if (pose != null) {
        // Off the back of the barrel and slightly above it, so a wagging tail clears the rump
        // instead of sweeping through it.
        baseX = lerpF(baseX, pose.trunkCenter.x - pose.trunkW * 0.88f, pose.quad)
        baseY = lerpF(baseY, pose.trunkCenter.y - pose.trunkH * 0.34f, pose.quad)
    }
    val tipX = baseX - bodyR * tailUnits * (1f + wag * 0.20f)
    val tipY = baseY - bodyR * tailUnits * (0.5f + wag * 0.5f)
    val accent = tonesFor(palette.accent)

    when (spec.species) {
        Species.AQUA -> {
            // Fish fin: two arcs meeting at the base.
            val fa = Offset(baseX, baseY)
            val fb = Offset(tipX, tipY - bodyR * 0.18f)
            val fc = Offset(tipX - bodyR * 0.05f, tipY + bodyR * 0.26f)
            val pivot = inside(fa, fb, fc, 0.30f, 0.42f, 0.28f)
            fun fin(k: Float): Path {
                val a = fa.toward(pivot, k)
                val b = fb.toward(pivot, k)
                val c = fc.toward(pivot, k)
                return Path().apply {
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                }
            }
            drawPath(fin(1f), accent.shadow)
            drawPath(fin(0.82f), accent.mid)
            drawPath(fin(0.48f), accent.light)
            drawPath(fin(1f), accent.line, style = Stroke(width = band(bodyR, 0.04f)))
        }
        Species.EMBER -> {
            // Flame tail: three stacked teardrops, each with its own hot core.
            for (i in 0..2) {
                val t = i / 2f
                val fx = lerpF(baseX, tipX, t)
                val fy = lerpF(baseY, tipY, t)
                val r = bodyR * (0.16f - t * 0.05f)
                val hot = lerp(palette.accent, Color(0xFFFFD447), t)
                drawCircle(color = lerp(hot, accent.shadow, 0.45f), radius = r, center = Offset(fx, fy))
                drawCircle(color = hot, radius = r * 0.76f, center = Offset(fx - r * 0.12f, fy - r * 0.14f))
                drawCircle(
                    color = lerp(hot, SCENE_LIGHT, 0.45f),
                    radius = r * 0.38f,
                    center = Offset(fx - r * 0.24f, fy - r * 0.28f),
                )
            }
        }
        Species.LEAF -> {
            val stem = Path().apply {
                moveTo(baseX, baseY)
                quadraticBezierTo(tipX + bodyR * 0.1f, tipY + bodyR * 0.1f, tipX, tipY)
            }
            drawPath(stem, accent.shadow, style = Stroke(width = band(bodyR, 0.08f), cap = StrokeCap.Round))
            drawPath(stem, accent.mid, style = Stroke(width = band(bodyR, 0.045f), cap = StrokeCap.Round))
            drawOval(
                color = accent.shadow,
                topLeft = Offset(tipX - bodyR * 0.20f, tipY - bodyR * 0.12f),
                size = Size(bodyR * 0.32f, bodyR * 0.22f),
            )
            drawOval(
                color = accent.mid,
                topLeft = Offset(tipX - bodyR * 0.18f, tipY - bodyR * 0.105f),
                size = Size(bodyR * 0.25f, bodyR * 0.16f),
            )
            drawOval(
                color = accent.light,
                topLeft = Offset(tipX - bodyR * 0.155f, tipY - bodyR * 0.09f),
                size = Size(bodyR * 0.12f, bodyR * 0.07f),
            )
        }
        Species.VOLT -> {
            // Lightning bolt tail.
            val bolt = Path().apply {
                moveTo(baseX, baseY)
                lineTo(baseX - bodyR * 0.18f, baseY - bodyR * 0.06f)
                lineTo(baseX - bodyR * 0.10f, baseY - bodyR * 0.22f)
                lineTo(tipX, tipY)
                lineTo(baseX - bodyR * 0.22f, baseY - bodyR * 0.16f)
                lineTo(baseX - bodyR * 0.14f, baseY + bodyR * 0.02f)
                close()
            }
            // The only feature that stays flat. Its arms taper to a point and are barely three
            // buffer pixels across, so an inner tone band would land under one pixel and vanish;
            // it gets a hot fill and a soft line instead, which is honest at this size.
            drawPath(bolt, lerp(accent.mid, accent.light, 0.4f))
            drawPath(bolt, accent.line, style = Stroke(width = band(bodyR, 0.035f)))
        }
    }
}

/** Ears, fins, crests — the silhouette cue that tells the four families apart at a glance. */
private fun DrawScope.drawSpeciesFeatures(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
    palette: CreaturePalette,
    frame: CreatureFrame,
) {
    val w = bodyR * p.bodyWidth
    val topY = center.y - bodyR * frame.squash
    val accent = tonesFor(palette.accent)
    val body = tonesFor(palette.body, palette.bodyShade)
    when (spec.species) {
        Species.AQUA -> {
            // Head fin that grows with the stage. The pivot leans left of centre so the shrunk
            // copies stack toward the light. It is built from the fin's own boundary points —
            // note the quad tops out halfway to its control point, not at it.
            val fa = Offset(center.x - w * 0.30f, topY + bodyR * 0.08f)
            val fb = Offset(center.x + w * 0.30f, topY + bodyR * 0.08f)
            val ctrl = Offset(center.x, topY - bodyR * (0.24f + p.crest))
            val peak = Offset(center.x, topY - bodyR * (0.08f + p.crest * 0.5f))
            val pivot = inside(fa, fb, peak, 0.40f, 0.20f, 0.40f)
            fun fin(k: Float): Path {
                val a = fa.toward(pivot, k)
                val c = ctrl.toward(pivot, k)
                val b = fb.toward(pivot, k)
                return Path().apply {
                    moveTo(a.x, a.y); quadraticBezierTo(c.x, c.y, b.x, b.y); close()
                }
            }
            drawPath(fin(1f), accent.shadow)
            drawPath(fin(0.84f), accent.mid)
            drawPath(fin(0.50f), accent.light)
            drawPath(fin(1f), accent.line, style = Stroke(width = band(bodyR, 0.035f)))
            // Side fins.
            listOf(-1f, 1f).forEach { side ->
                val fx = center.x + side * w * 1.0f - bodyR * 0.10f
                val fy = center.y - bodyR * 0.12f
                drawOval(
                    color = accent.shadow.copy(alpha = 0.9f),
                    topLeft = Offset(fx, fy),
                    size = Size(bodyR * 0.20f, bodyR * 0.34f),
                )
                drawOval(
                    color = accent.mid.copy(alpha = 0.9f),
                    topLeft = Offset(fx + bodyR * 0.02f, fy + bodyR * 0.025f),
                    size = Size(bodyR * 0.15f, bodyR * 0.26f),
                )
            }
        }
        Species.EMBER -> {
            // Two horns and a flame crest.
            listOf(-1f, 1f).forEach { side ->
                val ha = Offset(center.x + side * w * 0.45f, topY + bodyR * 0.10f)
                val hb = Offset(
                    center.x + side * w * (0.62f + p.crest * 0.5f),
                    topY - bodyR * (0.22f + p.crest),
                )
                val hc = Offset(center.x + side * w * 0.20f, topY + bodyR * 0.02f)
                val pivot = inside(ha, hb, hc, 0.25f, 0.40f, 0.35f)
                fun horn(k: Float): Path {
                    val a = ha.toward(pivot, k)
                    val b = hb.toward(pivot, k)
                    val c = hc.toward(pivot, k)
                    return Path().apply {
                        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                    }
                }
                drawPath(horn(1f), accent.shadow)
                drawPath(horn(0.80f), accent.mid)
                drawPath(horn(0.46f), accent.light)
                drawPath(horn(1f), accent.line, style = Stroke(width = band(bodyR, 0.03f)))
            }
        }
        Species.LEAF -> {
            // A sprout with two leaves.
            val stemTop = topY - bodyR * (0.20f + p.crest)
            drawLine(
                color = accent.shadow,
                start = Offset(center.x, topY + bodyR * 0.05f),
                end = Offset(center.x, stemTop),
                strokeWidth = band(bodyR, 0.07f),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent.mid,
                start = Offset(center.x - bodyR * 0.012f, topY + bodyR * 0.05f),
                end = Offset(center.x - bodyR * 0.012f, stemTop),
                strokeWidth = band(bodyR, 0.035f),
                cap = StrokeCap.Round,
            )
            listOf(-1f, 1f).forEach { side ->
                val lx = center.x + side * bodyR * 0.02f - if (side < 0) bodyR * 0.30f else 0f
                val ly = stemTop - bodyR * 0.06f
                drawOval(
                    color = accent.shadow,
                    topLeft = Offset(lx, ly),
                    size = Size(bodyR * 0.30f, bodyR * 0.18f),
                )
                drawOval(
                    color = accent.mid,
                    topLeft = Offset(lx + bodyR * 0.018f, ly + bodyR * 0.018f),
                    size = Size(bodyR * 0.25f, bodyR * 0.13f),
                )
                drawOval(
                    color = accent.light,
                    topLeft = Offset(lx + bodyR * 0.035f, ly + bodyR * 0.030f),
                    size = Size(bodyR * 0.13f, bodyR * 0.06f),
                )
            }
        }
        Species.VOLT -> {
            // Two pointed ears with inner shading.
            listOf(-1f, 1f).forEach { side ->
                val ea = Offset(center.x + side * w * 0.30f, topY + bodyR * 0.16f)
                val eb = Offset(
                    center.x + side * w * (0.55f + p.crest * 0.4f),
                    topY - bodyR * (0.30f + p.crest),
                )
                val ec = Offset(center.x + side * w * 0.72f, topY + bodyR * 0.22f)
                val pivot = inside(ea, eb, ec, 0.35f, 0.40f, 0.25f)
                fun ear(k: Float): Path {
                    val a = ea.toward(pivot, k)
                    val b = eb.toward(pivot, k)
                    val c = ec.toward(pivot, k)
                    return Path().apply {
                        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                    }
                }
                drawPath(ear(1f), body.shadow)
                drawPath(ear(0.84f), body.mid)
                drawPath(ear(0.50f), body.light)
                drawPath(ear(1f), body.line, style = Stroke(width = band(bodyR, 0.035f)))
                val ia = Offset(center.x + side * w * 0.38f, topY + bodyR * 0.14f)
                val ib = Offset(
                    center.x + side * w * (0.52f + p.crest * 0.3f),
                    topY - bodyR * (0.18f + p.crest * 0.7f),
                )
                val ic = Offset(center.x + side * w * 0.60f, topY + bodyR * 0.16f)
                val innerPivot = inside(ia, ib, ic, 0.35f, 0.40f, 0.25f)
                fun inner(k: Float): Path {
                    val a = ia.toward(innerPivot, k)
                    val b = ib.toward(innerPivot, k)
                    val c = ic.toward(innerPivot, k)
                    return Path().apply {
                        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                    }
                }
                drawPath(inner(1f), accent.shadow.copy(alpha = 0.8f))
                drawPath(inner(0.72f), lerp(accent.mid, palette.blush, 0.25f).copy(alpha = 0.8f))
            }
        }
    }
}

private fun DrawScope.drawFace(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
    palette: CreaturePalette,
    frame: CreatureFrame,
    pose: Pose?,
) {
    val eyeY = center.y + bodyR * p.eyeHeight
    val spread = bodyR * p.eyeSpread
    val r = bodyR * p.eyeRadius * 3.2f
    val gaze = frame.gaze * r * 0.28f
    val gazeUp = frame.gazeY * r * 0.22f
    val open = frame.eyeOpen.coerceIn(0f, 1f)
    val tones = tonesFor(palette.body, palette.bodyShade)
    // The face sits on the belly, so its lines use the lifted outline: a dark key line against
    // a pale patch is exactly where near-black looks worst.
    val faceLine = tones.lineSoft
    // Not pure white — a sclera tinted toward the belly keeps the eye inside the palette.
    val sclera = lerp(Color.White, palette.belly, 0.30f)

    // The snout goes on before the eyes, so its root passes under them rather than over them,
    // and it takes the mouth with it: a nose and a mouth left flat on the body while a muzzle
    // grows out beneath them is the single thing that stops a long face reading as a long face.
    // Under about four buffer pixels there is no snout worth drawing and the face is the old one.
    var mouthAt = center
    var mouthY = eyeY + r * 1.5f
    if (pose != null && pose.muzzlePx > bodyR * 0.06f) {
        val tip = drawMuzzle(center, bodyR, pose, palette, frame, eyeY)
        mouthAt = Offset(tip.x, center.y)
        mouthY = tip.y + bodyR * 0.07f
    }

    listOf(-1f, 1f).forEach { side ->
        val ex = center.x + side * spread
        if (spec.mood == Mood.SLEEPING || open < 0.06f) {
            // Closed eyes: a calm downward arc.
            drawArc(
                color = faceLine,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(ex - r, eyeY - r * 0.7f),
                size = Size(r * 2f, r * 1.4f),
                style = Stroke(width = band(bodyR, 0.045f), cap = StrokeCap.Round),
            )
        } else {
            drawOval(
                color = sclera,
                topLeft = Offset(ex - r, eyeY - r * open),
                size = Size(r * 2f, r * 2f * open),
            )
            // Lid shadow inside the top of the eye. An arc, not a filled oval: an oval wide
            // enough to read would bulge past the sclera at its waist. Inset 0.86 leaves more
            // room than the stroke's half width, and the stroke thins with the blink so it
            // never spills — [band] still holds it at a whole pixel.
            drawArc(
                color = lerp(sclera, tones.shadow, 0.35f),
                startAngle = 195f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(ex - r * 0.86f, eyeY - r * 0.86f * open),
                size = Size(r * 1.72f, r * 1.72f * open),
                style = Stroke(width = band(bodyR, 0.05f * (0.5f + 0.5f * open)), cap = StrokeCap.Round),
            )
            // Iris in two tones: dark top, lighter lower half, which is what makes an eye read
            // as glass rather than as a hole.
            val ix = ex - r * 0.52f + gaze
            val iy = eyeY - r * 0.60f * open + gazeUp
            val iw = r * 1.04f
            val ih = r * 1.2f * open
            drawOval(color = palette.eye, topLeft = Offset(ix, iy), size = Size(iw, ih))
            drawOval(
                color = lerp(palette.eye, palette.body, 0.55f),
                topLeft = Offset(ix + iw * 0.10f, iy + ih * 0.42f),
                size = Size(iw * 0.80f, ih * 0.52f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = r * 0.22f * open,
                center = Offset(ex - r * 0.18f + gaze, eyeY - r * 0.28f * open + gazeUp),
            )
            // Second catchlight opposite the first: two points of light is the whole trick.
            drawCircle(
                color = lerp(Color.White, palette.belly, 0.4f).copy(alpha = 0.55f),
                radius = r * 0.12f * open,
                center = Offset(ex + r * 0.26f + gaze, eyeY + r * 0.30f * open + gazeUp),
            )
            drawOval(
                color = faceLine,
                topLeft = Offset(ex - r, eyeY - r * open),
                size = Size(r * 2f, r * 2f * open),
                style = Stroke(width = band(bodyR, 0.035f)),
            )
        }
        // Eyebrows carry a lot of the mood.
        val browOffset = when (spec.mood) {
            Mood.SAD, Mood.SICK -> bodyR * 0.06f
            Mood.HUNGRY, Mood.TIRED -> bodyR * 0.04f
            else -> bodyR * 0.02f
        }
        if (spec.mood != Mood.SLEEPING && spec.mood != Mood.HAPPY) {
            drawLine(
                color = tones.line,
                start = Offset(ex - r * 0.9f, eyeY - r - browOffset + if (spec.mood == Mood.SAD) 0f else bodyR * 0.03f * side),
                end = Offset(ex + r * 0.9f, eyeY - r - browOffset - if (spec.mood == Mood.SAD) bodyR * 0.05f * side else 0f),
                strokeWidth = band(bodyR, 0.04f),
                cap = StrokeCap.Round,
            )
        }
    }

    // Blush: three nested ovals so it fades outward like a soft airbrush instead of sitting
    // there as a flat sticker. Each ring is about a pixel of falloff at bodyR ≈ 60. Intensity
    // is continuous, so a new pet is barely pink and an old friend is properly warm.
    val blush = frame.blush.coerceIn(0f, 1f)
    if (blush > 0.03f) {
        val grow = 0.80f + blush * 0.26f
        for (s in 0..1) {
            val side = if (s == 0) -1f else 1f
            val bx = center.x + side * spread * 1.55f
            val by = eyeY + r * 0.7f + bodyR * 0.06f
            for (i in 0..2) {
                val k = (1f - i * 0.28f) * grow
                drawOval(
                    color = palette.blush.copy(alpha = (0.10f + i * 0.06f) * blush),
                    topLeft = Offset(bx - bodyR * 0.10f * k, by - bodyR * 0.06f * k),
                    size = Size(bodyR * 0.20f * k, bodyR * 0.12f * k),
                )
            }
        }
    }

    // The mouth carries expression, so it keeps most of the full-strength line; only the eye
    // ring, which lies directly on the pale sclera, takes the fully lifted one.
    drawMouth(mouthAt, bodyR, mouthY, spec.mood, frame, lerp(tones.line, faceLine, 0.4f))
}

/**
 * The snout, and the nose riding on the end of it. Returns the tip, because the mouth has to
 * follow it there.
 *
 * It is a capsule — a fat round-capped line with a thicker line of outline underneath it and a
 * thin lit one along the top — for the same reason the arms are: three [drawLine] calls survive
 * the downscale as a shaded tube, cost nothing, and cannot come apart at the join the way a
 * separate path and outline can. Its direction is the other half of the quadruped crossfade:
 * on an upright, front-facing head it hangs down the face like a shallow chin, and as the head
 * turns side-on it swings forward into a proper muzzle.
 */
private fun DrawScope.drawMuzzle(
    center: Offset,
    bodyR: Float,
    pose: Pose,
    palette: CreaturePalette,
    frame: CreatureFrame,
    eyeY: Float,
): Offset {
    val tones = tonesFor(palette.body, palette.bodyShade)
    // Gaze pulls the snout with the eyes, by about a pixel at full deflection.
    val dx = lerpF(0.20f, 0.94f, pose.quad) + frame.gaze * 0.14f
    val dy = lerpF(0.94f, 0.26f, pose.quad)
    val inv = invLength(dx, dy)
    val root = Offset(center.x + dx * inv * bodyR * 0.10f, eyeY + bodyR * 0.26f)
    val tip = Offset(root.x + dx * inv * pose.muzzlePx, root.y + dy * inv * pose.muzzlePx)
    // A long snout is a narrow snout; a short one is a broad pad across the whole lower face.
    val thick = bodyR * (0.52f - 0.14f * pose.muzzleFrac)
    drawLine(tones.lineSoft, root, tip, strokeWidth = thick + band(bodyR, 0.06f), cap = StrokeCap.Round)
    drawLine(tones.mid, root, tip, strokeWidth = thick, cap = StrokeCap.Round)
    drawLine(
        tones.light,
        Offset(root.x, root.y - thick * 0.24f),
        Offset(tip.x, tip.y - thick * 0.24f),
        strokeWidth = thick * 0.30f,
        cap = StrokeCap.Round,
    )
    // Nose: on top of the tip, not centred on it, so it reads as sitting on the snout.
    val noseR = bodyR * 0.13f
    val nose = Offset(tip.x + dx * inv * noseR * 0.30f, tip.y - noseR * 0.45f)
    drawCircle(tones.line, noseR, nose)
    drawCircle(lerp(tones.line, SCENE_LIGHT, 0.40f), noseR * 0.36f, Offset(nose.x - noseR * 0.28f, nose.y - noseR * 0.30f))
    return tip
}

/**
 * Inherited ears, on top of whatever crest the species already wears.
 *
 * Two segments, not one: the bend between them is where droop lives. A pricked ear runs almost
 * straight out and up from the head; a hound's goes out first and then falls, and because the
 * fall is expressed as +y in the creature's own space it keeps hanging correctly when the body
 * has gone horizontal and when the whole creature is leaning. They are anchored on the sides of
 * the head rather than its crown so they do not fight the species crest for the same pixels.
 */
private fun DrawScope.drawEars(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    pose: Pose,
    palette: CreaturePalette,
    frame: CreatureFrame,
) {
    // Below six buffer pixels an ear is a bump on the outline and not worth the two draw calls.
    if (pose.earPx < bodyR * 0.10f) return
    val tones = tonesFor(palette.body, palette.bodyShade)
    val inner = lerp(palette.blush, tones.shadow, 0.45f)
    // Squash and stretch belong to the head, and the ears are attached to it: the same divide
    // and multiply the body blob uses, so they ride the bob instead of floating beside it.
    val w = bodyR * p.bodyWidth * pose.headWidth / frame.squash
    val d = pose.earDroop
    // A short ear must be a short *nub*, not a wide flap: capping the thickness against the
    // ear's own length is what stops a barely-expressed ear gene drawing a paddle.
    val base = (bodyR * 0.30f).coerceAtMost(pose.earPx * 0.62f)
    listOf(-1f, 1f).forEach { side ->
        val anchor = Offset(center.x + side * w * 0.68f, center.y - bodyR * 0.58f * frame.squash)
        // First segment: out of the head, upward when pricked, barely rising when floppy.
        val ax = side * lerpF(0.42f, 0.66f, d)
        val ay = lerpF(-0.92f, -0.34f, d)
        val ai = invLength(ax, ay)
        val joint = Offset(anchor.x + ax * ai * pose.earPx * 0.42f, anchor.y + ay * ai * pose.earPx * 0.42f)
        // Second segment: still climbing when pricked, straight down under its own weight when
        // not. The swing term is what makes long ears flap a beat behind a walking creature.
        val bx = side * lerpF(0.30f, 0.20f, d) + frame.armSwing * 0.12f * d
        val by = lerpF(-0.95f, 0.96f, d)
        val bi = invLength(bx, by)
        val tip = Offset(joint.x + bx * bi * pose.earPx * 0.58f, joint.y + by * bi * pose.earPx * 0.58f)
        // Far ear one tone down: the same flat depth step the far hind leg takes.
        val skin = if (side < 0f) lerp(tones.mid, tones.shadow, 0.55f) else tones.mid
        drawLine(tones.lineSoft, anchor, joint, strokeWidth = base + band(bodyR, 0.06f), cap = StrokeCap.Round)
        drawLine(tones.lineSoft, joint, tip, strokeWidth = base * 0.74f + band(bodyR, 0.06f), cap = StrokeCap.Round)
        drawLine(skin, anchor, joint, strokeWidth = base, cap = StrokeCap.Round)
        drawLine(skin, joint, tip, strokeWidth = base * 0.74f, cap = StrokeCap.Round)
        // A single pixel of inner ear down the middle of the near one. Any more and it is noise.
        if (side > 0f) {
            drawLine(inner, anchor, joint, strokeWidth = base * 0.34f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawMouth(
    center: Offset,
    bodyR: Float,
    mouthY: Float,
    mood: Mood,
    frame: CreatureFrame,
    line: Color,
) {
    val open = frame.mouthOpen.coerceIn(0f, 1f)
    val w = bodyR * 0.30f
    when {
        open > 0.08f -> {
            // Open mouth: a filled ellipse with a tongue.
            val h = bodyR * (0.10f + 0.22f * open)
            drawOval(
                color = lerp(Color(0xFF6B2233), line, 0.22f),
                topLeft = Offset(center.x - w * 0.6f, mouthY - h * 0.2f),
                size = Size(w * 1.2f, h),
            )
            drawOval(
                color = Color(0xFFE8657F),
                topLeft = Offset(center.x - w * 0.34f, mouthY + h * 0.30f),
                size = Size(w * 0.68f, h * 0.5f),
            )
            drawOval(
                color = line,
                topLeft = Offset(center.x - w * 0.6f, mouthY - h * 0.2f),
                size = Size(w * 1.2f, h),
                style = Stroke(width = band(bodyR, 0.03f)),
            )
        }
        mood == Mood.SAD || mood == Mood.SICK || mood == Mood.HUNGRY -> {
            drawArc(
                color = line,
                startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(center.x - w * 0.5f, mouthY),
                size = Size(w, bodyR * 0.16f),
                style = Stroke(width = band(bodyR, 0.04f), cap = StrokeCap.Round),
            )
        }
        mood == Mood.SLEEPING -> {
            drawCircle(color = line, radius = band(bodyR, 0.04f), center = Offset(center.x, mouthY + bodyR * 0.04f))
        }
        else -> {
            drawArc(
                color = line,
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(center.x - w * 0.5f, mouthY - bodyR * 0.12f),
                size = Size(w, bodyR * 0.20f),
                style = Stroke(width = band(bodyR, 0.04f), cap = StrokeCap.Round),
            )
        }
    }
}

/** Extra marks that only appear on certain evolution branches. */
private fun DrawScope.drawBranchMarks(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
    palette: CreaturePalette,
) {
    if (spec.stage.order < LifeStage.TEEN.order) return
    val eyeY = center.y + bodyR * p.eyeHeight
    val spread = bodyR * p.eyeSpread
    val r = bodyR * p.eyeRadius * 3.2f
    val accent = tonesFor(palette.accent)
    when (spec.branch) {
        EvolutionBranch.SCHOLAR -> {
            // Round glasses, with a highlight along the top of each lens.
            listOf(-1f, 1f).forEach { side ->
                drawCircle(
                    color = accent.mid,
                    radius = r * 1.15f,
                    center = Offset(center.x + side * spread, eyeY),
                    style = Stroke(width = band(bodyR, 0.035f)),
                )
                drawArc(
                    color = accent.rim.copy(alpha = 0.7f),
                    startAngle = 195f, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(center.x + side * spread - r * 1.15f, eyeY - r * 1.15f),
                    size = Size(r * 2.30f, r * 2.30f),
                    style = Stroke(width = band(bodyR, 0.02f), cap = StrokeCap.Round),
                )
            }
            drawLine(
                color = accent.mid,
                start = Offset(center.x - spread + r * 1.1f, eyeY),
                end = Offset(center.x + spread - r * 1.1f, eyeY),
                strokeWidth = band(bodyR, 0.03f),
            )
        }
        EvolutionBranch.ATHLETIC -> {
            // Headband: a shadow edge under the lit face of the strap.
            drawLine(
                color = accent.shadow,
                start = Offset(center.x - bodyR * p.bodyWidth * 0.85f, center.y - bodyR * 0.52f),
                end = Offset(center.x + bodyR * p.bodyWidth * 0.85f, center.y - bodyR * 0.52f),
                strokeWidth = band(bodyR, 0.10f),
            )
            drawLine(
                color = accent.mid,
                start = Offset(center.x - bodyR * p.bodyWidth * 0.85f, center.y - bodyR * 0.545f),
                end = Offset(center.x + bodyR * p.bodyWidth * 0.85f, center.y - bodyR * 0.545f),
                strokeWidth = band(bodyR, 0.05f),
            )
        }
        EvolutionBranch.FERAL -> {
            // Spiky fur along the back.
            for (i in 0..3) {
                val t = i / 3f
                val x = center.x - bodyR * p.bodyWidth * (0.9f - t * 0.5f)
                val y = center.y - bodyR * (0.55f - t * 0.35f)
                val sa = Offset(x, y)
                val sb = Offset(x - bodyR * 0.14f, y - bodyR * 0.18f)
                val sc = Offset(x + bodyR * 0.04f, y - bodyR * 0.06f)
                val pivot = inside(sa, sb, sc, 0.30f, 0.45f, 0.25f)
                fun spike(k: Float): Path {
                    val a = sa.toward(pivot, k)
                    val b = sb.toward(pivot, k)
                    val c = sc.toward(pivot, k)
                    return Path().apply {
                        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                    }
                }
                drawPath(spike(1f), accent.shadow)
                drawPath(spike(0.74f), accent.mid)
            }
        }
        EvolutionBranch.GOURMAND -> {
            // A napkin round the neck.
            val napkin = Color(0xFFF6F2E8)
            drawOval(
                color = lerp(napkin, tonesFor(palette.body, palette.bodyShade).shadow, 0.22f),
                topLeft = Offset(center.x - bodyR * 0.34f, center.y + bodyR * 0.16f),
                size = Size(bodyR * 0.68f, bodyR * 0.30f),
            )
            drawOval(
                color = napkin,
                topLeft = Offset(center.x - bodyR * 0.32f, center.y + bodyR * 0.17f),
                size = Size(bodyR * 0.60f, bodyR * 0.25f),
            )
        }
        EvolutionBranch.BALANCED -> Unit
    }
}

private fun DrawScope.drawElderMarks(center: Offset, bodyR: Float, palette: CreaturePalette) {
    // Whiskers and a small walking stick.
    val line = outlineFor(palette.body, lift = 0.5f)
    listOf(-1f, 1f).forEach { side ->
        drawLine(
            color = line.copy(alpha = 0.7f),
            start = Offset(center.x + side * bodyR * 0.30f, center.y + bodyR * 0.30f),
            end = Offset(center.x + side * bodyR * 0.72f, center.y + bodyR * 0.24f),
            strokeWidth = band(bodyR, 0.028f),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Two beads running down the temples when energy is nearly gone. They are deliberately small and
 * low-contrast: exhaustion should be something you notice on the second look, not a klaxon.
 */
private fun DrawScope.drawSweat(center: Offset, bodyR: Float, p: Proportions, frame: CreatureFrame) {
    val amount = frame.sweat.coerceIn(0f, 1f)
    if (amount < 0.05f) return
    val w = bodyR * p.bodyWidth
    val eyeY = center.y + bodyR * p.eyeHeight
    val drop = Color(0xFFC8E8FF)
    for (i in 0..1) {
        val side = if (i == 0) 1f else -1f
        val phase = (frame.sweatPhase + i * 0.5f) % 1f
        // Fade in as the bead forms, fade out as it runs off the chin.
        val fade = if (phase < 0.15f) phase / 0.15f else 1f - (phase - 0.15f) / 0.85f
        val alpha = amount * fade * 0.80f
        if (alpha < 0.02f) continue
        val r = bodyR * (0.045f + amount * 0.020f) * (if (i == 0) 1f else 0.75f)
        val x = (center.x + side * w * 0.90f).roundToInt().toFloat()
        val y = (eyeY - bodyR * 0.36f + phase * bodyR * 0.62f).roundToInt().toFloat()
        drawCircle(drop.copy(alpha = alpha), r, Offset(x, y))
        drawCircle(
            Color.White.copy(alpha = alpha * 0.75f),
            r * 0.38f,
            Offset(x - r * 0.30f, y - r * 0.32f),
        )
    }
}

private fun DrawScope.drawEgg(center: Offset, unit: Float, palette: CreaturePalette, frame: CreatureFrame) {
    val w = unit * 0.26f
    val h = unit * 0.34f
    val wobble = frame.lean
    val shell = Color(0xFFF6F1E4)
    val shellShadow = lerp(shell, lerp(palette.bodyShade, AMBIENT_COOL, 0.25f), 0.30f)
    val shellLight = lerp(shell, SCENE_LIGHT, 0.55f)
    val line = outlineFor(palette.body, lift = 0.35f)
    drawGroundShadow(center.x, center.y + h * 1.05f, w * 1.05f, palette)
    rotate(degrees = wobble, pivot = Offset(center.x, center.y + h)) {
        // Same pivot trick as the body features: shrink the shell toward the upper left to get
        // a lit plane without a clip path. At w ≈ 52 px, k = 0.90 is a 5 px shadow band.
        val pivot = Offset(center.x - w * 0.30f, center.y - h * 0.34f)
        fun egg(k: Float): Path {
            val top = Offset(center.x, center.y - h).toward(pivot, k)
            val bottom = Offset(center.x, center.y + h).toward(pivot, k)
            val r1 = Offset(center.x + w * 1.25f, center.y - h * 0.45f).toward(pivot, k)
            val r2 = Offset(center.x + w * 1.10f, center.y + h).toward(pivot, k)
            val l1 = Offset(center.x - w * 1.10f, center.y + h).toward(pivot, k)
            val l2 = Offset(center.x - w * 1.25f, center.y - h * 0.45f).toward(pivot, k)
            return Path().apply {
                moveTo(top.x, top.y)
                cubicTo(r1.x, r1.y, r2.x, r2.y, bottom.x, bottom.y)
                cubicTo(l1.x, l1.y, l2.x, l2.y, top.x, top.y)
                close()
            }
        }
        drawPath(egg(1f), shellShadow)
        drawPath(egg(0.90f), shell)
        drawPath(egg(0.40f), shellLight)
        // Species-tinted spots.
        listOf(
            Offset(center.x - w * 0.35f, center.y + h * 0.10f) to w * 0.22f,
            Offset(center.x + w * 0.30f, center.y - h * 0.20f) to w * 0.16f,
            Offset(center.x + w * 0.10f, center.y + h * 0.45f) to w * 0.19f,
        ).forEach { (pos, r) ->
            drawCircle(lerp(palette.body, shellShadow, 0.25f).copy(alpha = 0.85f), r, pos)
            drawCircle(palette.body.copy(alpha = 0.85f), r * 0.78f, Offset(pos.x - r * 0.12f, pos.y - r * 0.14f))
        }
        drawPath(egg(1f), line, style = Stroke(width = max(unit * 0.012f, 1f)))

        // Cracks grow as the hatch timer fills.
        if (frame.crack > 0.02f) {
            val crack = Path().apply {
                moveTo(center.x - w * 0.8f, center.y - h * 0.05f)
                var x = center.x - w * 0.8f
                var y = center.y - h * 0.05f
                val steps = (frame.crack * 6).toInt().coerceAtLeast(1)
                repeat(steps) { i ->
                    x += w * 0.30f
                    y += if (i % 2 == 0) -h * 0.12f else h * 0.12f
                    lineTo(x, y)
                }
            }
            drawPath(crack, line, style = Stroke(width = max(unit * 0.014f, 1f)))
        }
    }
}

/** Cosmetic hats, drawn last so they sit on top of ears and crests. */
private fun DrawScope.drawHat(
    hatId: String,
    center: Offset,
    bodyR: Float,
    p: Proportions,
    frame: CreatureFrame,
) {
    // A hat is a loose mass, not a decal. It rides the head as the body stretches and squashes,
    // overshoots that motion a little so a jump throws it up and a landing drops it back on,
    // and keeps leaning for a beat after the pet has stopped walking.
    val head = center.y - bodyR * (frame.squash + 0.02f)
    val topY = head - bodyR * (frame.squash - 1f) * 0.55f
    val w = bodyR * p.bodyWidth
    rotate(degrees = frame.hatTilt, pivot = Offset(center.x, topY + bodyR * 0.30f)) {
        drawHatShape(hatId, center, bodyR, topY, w)
    }
}

private fun DrawScope.drawHatShape(
    hatId: String,
    center: Offset,
    bodyR: Float,
    topY: Float,
    w: Float,
) {
    when (hatId) {
        "hat_cap" -> {
            val cap = Path().apply {
                moveTo(center.x - w * 0.72f, topY + bodyR * 0.12f)
                quadraticBezierTo(center.x, topY - bodyR * 0.34f, center.x + w * 0.72f, topY + bodyR * 0.12f)
                close()
            }
            drawPath(cap, Color(0xFFFF3C28))
            drawPath(
                cap,
                Color(0xFFFF8A6B).copy(alpha = 0.85f),
                style = Stroke(width = band(bodyR, 0.025f)),
            )
            drawOval(
                color = Color(0xFFC22A1A),
                topLeft = Offset(center.x + w * 0.10f, topY + bodyR * 0.06f),
                size = Size(w * 0.90f, bodyR * 0.12f),
            )
        }
        "hat_crown" -> {
            val crown = Path().apply {
                moveTo(center.x - w * 0.55f, topY + bodyR * 0.10f)
                lineTo(center.x - w * 0.55f, topY - bodyR * 0.20f)
                lineTo(center.x - w * 0.26f, topY - bodyR * 0.02f)
                lineTo(center.x, topY - bodyR * 0.30f)
                lineTo(center.x + w * 0.26f, topY - bodyR * 0.02f)
                lineTo(center.x + w * 0.55f, topY - bodyR * 0.20f)
                lineTo(center.x + w * 0.55f, topY + bodyR * 0.10f)
                close()
            }
            drawPath(crown, Color(0xFFF5C542))
            drawPath(crown, Color(0xFFA98212), style = Stroke(width = band(bodyR, 0.03f)))
        }
        "hat_bow" -> {
            listOf(-1f, 1f).forEach { side ->
                drawOval(
                    color = Color(0xFFF56AA5),
                    topLeft = Offset(center.x + side * w * 0.10f - if (side < 0) w * 0.42f else 0f, topY - bodyR * 0.06f),
                    size = Size(w * 0.42f, bodyR * 0.22f),
                )
            }
            drawCircle(Color(0xFFD44C87), bodyR * 0.07f, Offset(center.x, topY + bodyR * 0.05f))
        }
        "hat_goggles" -> {
            val strap = Color(0xFF4A4F5E)
            listOf(-1f, 1f).forEach { side ->
                drawCircle(
                    color = Color(0xFF00C3E3).copy(alpha = 0.75f),
                    radius = bodyR * 0.16f,
                    center = Offset(center.x + side * w * 0.34f, topY + bodyR * 0.16f),
                )
                drawCircle(
                    color = strap,
                    radius = bodyR * 0.16f,
                    center = Offset(center.x + side * w * 0.34f, topY + bodyR * 0.16f),
                    style = Stroke(width = band(bodyR, 0.04f)),
                )
            }
            drawLine(
                color = strap,
                start = Offset(center.x - w * 0.70f, topY + bodyR * 0.16f),
                end = Offset(center.x + w * 0.70f, topY + bodyR * 0.16f),
                strokeWidth = band(bodyR, 0.05f),
            )
        }
        "hat_leafhat" -> {
            val leaf = Path().apply {
                moveTo(center.x - w * 0.62f, topY + bodyR * 0.10f)
                quadraticBezierTo(center.x, topY - bodyR * 0.40f, center.x + w * 0.62f, topY + bodyR * 0.10f)
                quadraticBezierTo(center.x, topY - bodyR * 0.02f, center.x - w * 0.62f, topY + bodyR * 0.10f)
                close()
            }
            drawPath(leaf, Color(0xFF6FCF74))
            drawPath(leaf, Color(0xFF3F9E55), style = Stroke(width = band(bodyR, 0.03f)))
        }
    }
}

// ------------------------------------------------------------------ small helpers

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A soft wobble used by several idle animations. */
fun wobble(timeSeconds: Float, speed: Float = 1f, amplitude: Float = 1f): Float =
    (sin(timeSeconds * speed * 2f * PI.toFloat()) * amplitude)

/** Triangle wave in 0..1, handy for hops and chews. */
fun pingPong(timeSeconds: Float, period: Float): Float {
    val t = (timeSeconds % period) / period
    return 1f - abs(t * 2f - 1f)
}

/** Circle helper used by the scene layer. */
internal fun DrawScope.drawSoftCircle(center: Offset, radius: Float, color: Color, alpha: Float = 1f) {
    drawCircle(color.copy(alpha = alpha), radius, center)
}

/** Rect helper, kept here so the scene layer stays declarative. */
internal fun DrawScope.drawRectIn(rect: Rect, color: Color) {
    drawRect(color, topLeft = rect.topLeft, size = rect.size)
}

/** Rotates [point] around [pivot] by [degrees]; used by orbiting props. */
internal fun rotatePoint(point: Offset, pivot: Offset, degrees: Float): Offset {
    val rad = degrees * PI.toFloat() / 180f
    val dx = point.x - pivot.x
    val dy = point.y - pivot.y
    return Offset(
        pivot.x + dx * cos(rad) - dy * sin(rad),
        pivot.y + dx * sin(rad) + dy * cos(rad),
    )
}

/** Translates a whole block by whole pixels, avoiding sub-pixel shimmer on pixel-art props. */
internal fun DrawScope.translated(dx: Float, dy: Float, block: DrawScope.() -> Unit) {
    translate(dx, dy) { block() }
}
