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
import com.neopal.pet.domain.Species
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Everything the renderer needs to know about *who* the creature is. */
data class CreatureSpec(
    val species: Species,
    val stage: LifeStage,
    val branch: EvolutionBranch,
    val mood: Mood,
    val hatId: String? = null,
    /** Body weight in grams; fattens the silhouette between 6 g and 120 g. */
    val weightGrams: Float = 12f,
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
    val palette = Palettes.creature(spec.species, spec.branch)
    if (spec.stage == LifeStage.EGG) {
        drawEgg(center, unit, palette, frame)
        return
    }

    val p = proportionsFor(spec.stage, spec.branch, spec.weightGrams)
    val bodyR = unit * p.bodyRadius
    val cy = center.y + frame.bobY * unit
    val bodyCenter = Offset(center.x, cy)

    drawGroundShadow(center.x, center.y + bodyR * 1.15f, bodyR * (1.05f - frame.bobY * 0.6f), palette)

    rotate(degrees = frame.lean, pivot = Offset(center.x, center.y + bodyR)) {
        // Back-most parts first: tail, then back limbs, then body, then face, then hat.
        drawTail(bodyCenter, bodyR, p, spec, palette, frame)
        drawLimbs(bodyCenter, bodyR, p, palette, frame, back = true)
        drawBody(bodyCenter, bodyR, p, spec, palette, frame)
        drawSpeciesFeatures(bodyCenter, bodyR, p, spec, palette, frame)
        drawLimbs(bodyCenter, bodyR, p, palette, frame, back = false)
        drawFace(bodyCenter, bodyR, p, spec, palette, frame)
        drawBranchMarks(bodyCenter, bodyR, p, spec, palette)
        spec.hatId?.let { drawHat(it, bodyCenter, bodyR, p) }
        if (spec.stage == LifeStage.ELDER) drawElderMarks(bodyCenter, bodyR, palette)
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

private fun DrawScope.drawGroundShadow(x: Float, y: Float, radius: Float, palette: CreaturePalette) {
    // Two passes: a wide faint one plus a tight core, so the contact shadow has an edge that
    // fades instead of a hard rim of near-black under the feet.
    val cast = lerp(palette.bodyShade, AMBIENT_COOL, 0.40f)
    drawOval(
        color = cast.copy(alpha = 0.10f),
        topLeft = Offset(x - radius * 1.12f, y - radius * 0.27f),
        size = Size(radius * 2.24f, radius * 0.54f),
    )
    drawOval(
        color = cast.copy(alpha = 0.20f),
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

private fun DrawScope.drawBody(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    spec: CreatureSpec,
    palette: CreaturePalette,
    frame: CreatureFrame,
) {
    val w = bodyR * p.bodyWidth / frame.squash
    val h = bodyR * frame.squash
    val tones = tonesFor(palette.body, palette.bodyShade)

    val body = bodyPath(center, w, h)
    drawPath(body, tones.mid, style = Fill)

    // Key light from the upper left: one mid tone, one shadow that wraps the lower right and
    // the underside, one lit crescent. Both bands are ~20 px across at this size — broad enough
    // to read as form rather than as noise.
    val shadow = shadowPath(center, w, h)
    drawPath(shadow, tones.shadow, style = Fill)
    drawPath(litPath(center, w, h), tones.light, style = Fill)

    drawBelly(center, w, h, palette, tones)
    // Same shadow again over the belly. Shadow-over-shadow is a no-op, so this only bends the
    // belly patch into the same light without needing a second, differently shaped path.
    drawPath(shadow, tones.shadow.copy(alpha = 0.16f), style = Fill)

    // Mood reads first as colour, before any animation: a glance should be enough.
    val wash = when (spec.mood) {
        Mood.SICK -> Color(0xFF7FBF6A).copy(alpha = 0.30f)
        Mood.HUNGRY -> Color(0xFFFFF4D6).copy(alpha = 0.22f)
        Mood.TIRED -> Color(0xFF5C6BA8).copy(alpha = 0.20f)
        Mood.SAD -> Color(0xFF6E7A99).copy(alpha = 0.18f)
        Mood.DIRTY -> Color(0xFF7A6A4F).copy(alpha = 0.20f)
        Mood.HAPPY -> Color(0xFFFFE7A8).copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    if (wash.alpha > 0f) drawPath(body, wash, style = Fill)

    drawPath(body, tones.line, style = Stroke(width = band(bodyR, 0.055f)))
    // Rim last, on top of the inner half of the outline, so the edge catches the light.
    drawPath(
        rimPath(center, w, h, core = false),
        tones.rim.copy(alpha = 0.42f),
        style = Stroke(width = band(bodyR, 0.035f), cap = StrokeCap.Round),
    )
    drawPath(
        rimPath(center, w, h, core = true),
        tones.rim.copy(alpha = 0.90f),
        style = Stroke(width = band(bodyR, 0.030f), cap = StrokeCap.Round),
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

private fun DrawScope.drawLimbs(
    center: Offset,
    bodyR: Float,
    p: Proportions,
    palette: CreaturePalette,
    frame: CreatureFrame,
    back: Boolean,
) {
    if (p.limbLength <= 0.001f) return
    val tones = tonesFor(palette.body, palette.bodyShade)
    val w = bodyR * p.bodyWidth
    val swing = frame.armSwing * bodyR * 0.35f
    val armY = center.y + bodyR * 0.18f
    val len = bodyR * p.limbLength
    val thickness = bodyR * 0.20f
    val dir = if (back) -1f else 1f

    // Arms
    listOf(-1f, 1f).forEach { side ->
        val start = Offset(center.x + side * w * 0.86f, armY)
        val end = Offset(start.x + side * len, armY + swing * side * dir)
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
    // Feet
    listOf(-1f, 1f).forEach { side ->
        val fx = center.x + side * w * 0.42f
        val fy = center.y + bodyR * 1.02f
        drawOval(
            color = tones.shadow,
            topLeft = Offset(fx - bodyR * 0.24f, fy - bodyR * 0.10f),
            size = Size(bodyR * 0.48f, bodyR * 0.24f),
        )
        // Lit top of the foot, ~2 px in from the edge on every side.
        drawOval(
            color = tones.mid,
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
) {
    if (p.tail <= 0.01f) return
    val w = bodyR * p.bodyWidth
    val wag = frame.armSwing * 0.5f
    val baseX = center.x - w * 0.85f
    val baseY = center.y + bodyR * 0.45f
    val tipX = baseX - bodyR * p.tail * (1f + wag * 0.2f)
    val tipY = baseY - bodyR * p.tail * (0.5f + wag * 0.5f)
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
    // there as a flat sticker. Each ring is about a pixel of falloff at bodyR ≈ 60.
    if (spec.mood == Mood.HAPPY || spec.mood == Mood.NEUTRAL) {
        listOf(-1f, 1f).forEach { side ->
            val bx = center.x + side * spread * 1.55f
            val by = eyeY + r * 0.7f + bodyR * 0.06f
            for (i in 0..2) {
                val k = 1f - i * 0.28f
                drawOval(
                    color = palette.blush.copy(alpha = 0.10f + i * 0.06f),
                    topLeft = Offset(bx - bodyR * 0.10f * k, by - bodyR * 0.06f * k),
                    size = Size(bodyR * 0.20f * k, bodyR * 0.12f * k),
                )
            }
        }
    }

    // The mouth carries expression, so it keeps most of the full-strength line; only the eye
    // ring, which lies directly on the pale sclera, takes the fully lifted one.
    drawMouth(center, bodyR, eyeY + r * 1.5f, spec.mood, frame, lerp(tones.line, faceLine, 0.4f))
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
) {
    val topY = center.y - bodyR * 1.02f
    val w = bodyR * p.bodyWidth
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
