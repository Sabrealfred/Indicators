package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    drawOval(
        color = palette.outline.copy(alpha = 0.22f),
        topLeft = Offset(x - radius, y - radius * 0.22f),
        size = Size(radius * 2f, radius * 0.44f),
    )
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
    val outline = bodyR * 0.055f

    // Main blob: a rounded pear, wider at the bottom.
    val body = Path().apply {
        moveTo(center.x, center.y - h)
        cubicTo(center.x + w * 1.05f, center.y - h * 0.92f, center.x + w * 1.12f, center.y + h * 0.35f, center.x + w * 0.92f, center.y + h * 0.80f)
        cubicTo(center.x + w * 0.70f, center.y + h * 1.10f, center.x - w * 0.70f, center.y + h * 1.10f, center.x - w * 0.92f, center.y + h * 0.80f)
        cubicTo(center.x - w * 1.12f, center.y + h * 0.35f, center.x - w * 1.05f, center.y - h * 0.92f, center.x, center.y - h)
        close()
    }
    drawPath(body, palette.body, style = Fill)

    // Shading on the lower right, then the belly patch on the front.
    val shade = Path().apply {
        moveTo(center.x + w * 0.20f, center.y + h * 1.02f)
        cubicTo(center.x + w * 1.02f, center.y + h * 0.72f, center.x + w * 1.10f, center.y - h * 0.10f, center.x + w * 0.86f, center.y - h * 0.55f)
        cubicTo(center.x + w * 1.16f, center.y + h * 0.20f, center.x + w * 0.92f, center.y + h * 0.95f, center.x + w * 0.20f, center.y + h * 1.02f)
        close()
    }
    drawPath(shade, palette.bodyShade.copy(alpha = 0.65f), style = Fill)

    drawOval(
        color = palette.belly,
        topLeft = Offset(center.x - w * 0.52f, center.y - h * 0.05f),
        size = Size(w * 1.04f, h * 0.95f),
    )
    drawPath(body, palette.outline, style = Stroke(width = outline))

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
    val w = bodyR * p.bodyWidth
    val swing = frame.armSwing * bodyR * 0.35f
    val armY = center.y + bodyR * 0.18f
    val len = bodyR * p.limbLength
    val thickness = bodyR * 0.20f
    val alpha = if (back) 0.75f else 1f
    val dir = if (back) -1f else 1f

    // Arms
    listOf(-1f, 1f).forEach { side ->
        val start = Offset(center.x + side * w * 0.86f, armY)
        val end = Offset(start.x + side * len, armY + swing * side * dir)
        drawLine(palette.bodyShade.copy(alpha = alpha), start, end, strokeWidth = thickness, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(palette.body.copy(alpha = alpha), radius = thickness * 0.62f, center = end)
    }

    if (back) return
    // Feet
    listOf(-1f, 1f).forEach { side ->
        val fx = center.x + side * w * 0.42f
        val fy = center.y + bodyR * 1.02f
        drawOval(
            color = palette.bodyShade,
            topLeft = Offset(fx - bodyR * 0.24f, fy - bodyR * 0.10f),
            size = Size(bodyR * 0.48f, bodyR * 0.24f),
        )
        drawOval(
            color = palette.outline,
            topLeft = Offset(fx - bodyR * 0.24f, fy - bodyR * 0.10f),
            size = Size(bodyR * 0.48f, bodyR * 0.24f),
            style = Stroke(width = bodyR * 0.035f),
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

    when (spec.species) {
        Species.AQUA -> {
            // Fish fin: two arcs meeting at the base.
            val fin = Path().apply {
                moveTo(baseX, baseY)
                lineTo(tipX, tipY - bodyR * 0.18f)
                lineTo(tipX - bodyR * 0.05f, tipY + bodyR * 0.26f)
                close()
            }
            drawPath(fin, palette.accent)
            drawPath(fin, palette.outline, style = Stroke(width = bodyR * 0.04f))
        }
        Species.EMBER -> {
            // Flame tail: three stacked teardrops.
            for (i in 0..2) {
                val t = i / 2f
                val fx = lerpF(baseX, tipX, t)
                val fy = lerpF(baseY, tipY, t)
                drawCircle(
                    color = lerp(palette.accent, Color(0xFFFFD447), t),
                    radius = bodyR * (0.16f - t * 0.05f),
                    center = Offset(fx, fy),
                )
            }
        }
        Species.LEAF -> {
            val stem = Path().apply {
                moveTo(baseX, baseY)
                quadraticBezierTo(tipX + bodyR * 0.1f, tipY + bodyR * 0.1f, tipX, tipY)
            }
            drawPath(stem, palette.accent, style = Stroke(width = bodyR * 0.08f))
            drawOval(
                color = palette.accent,
                topLeft = Offset(tipX - bodyR * 0.20f, tipY - bodyR * 0.12f),
                size = Size(bodyR * 0.32f, bodyR * 0.22f),
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
            drawPath(bolt, palette.accent)
            drawPath(bolt, palette.outline, style = Stroke(width = bodyR * 0.035f))
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
    when (spec.species) {
        Species.AQUA -> {
            // Head fin that grows with the stage.
            val fin = Path().apply {
                moveTo(center.x - w * 0.30f, topY + bodyR * 0.08f)
                quadraticBezierTo(center.x, topY - bodyR * (0.24f + p.crest), center.x + w * 0.30f, topY + bodyR * 0.08f)
                close()
            }
            drawPath(fin, palette.accent)
            drawPath(fin, palette.outline, style = Stroke(width = bodyR * 0.035f))
            // Side fins.
            listOf(-1f, 1f).forEach { side ->
                drawOval(
                    color = palette.accent.copy(alpha = 0.9f),
                    topLeft = Offset(center.x + side * w * 1.0f - bodyR * 0.10f, center.y - bodyR * 0.12f),
                    size = Size(bodyR * 0.20f, bodyR * 0.34f),
                )
            }
        }
        Species.EMBER -> {
            // Two horns and a flame crest.
            listOf(-1f, 1f).forEach { side ->
                val horn = Path().apply {
                    moveTo(center.x + side * w * 0.45f, topY + bodyR * 0.10f)
                    lineTo(center.x + side * w * (0.62f + p.crest * 0.5f), topY - bodyR * (0.22f + p.crest))
                    lineTo(center.x + side * w * 0.20f, topY + bodyR * 0.02f)
                    close()
                }
                drawPath(horn, palette.accent)
                drawPath(horn, palette.outline, style = Stroke(width = bodyR * 0.03f))
            }
        }
        Species.LEAF -> {
            // A sprout with two leaves.
            val stemTop = topY - bodyR * (0.20f + p.crest)
            drawLine(
                color = palette.accent,
                start = Offset(center.x, topY + bodyR * 0.05f),
                end = Offset(center.x, stemTop),
                strokeWidth = bodyR * 0.07f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            listOf(-1f, 1f).forEach { side ->
                drawOval(
                    color = palette.accent,
                    topLeft = Offset(center.x + side * bodyR * 0.02f - if (side < 0) bodyR * 0.30f else 0f, stemTop - bodyR * 0.06f),
                    size = Size(bodyR * 0.30f, bodyR * 0.18f),
                )
            }
        }
        Species.VOLT -> {
            // Two pointed ears with inner shading.
            listOf(-1f, 1f).forEach { side ->
                val ear = Path().apply {
                    moveTo(center.x + side * w * 0.30f, topY + bodyR * 0.16f)
                    lineTo(center.x + side * w * (0.55f + p.crest * 0.4f), topY - bodyR * (0.30f + p.crest))
                    lineTo(center.x + side * w * 0.72f, topY + bodyR * 0.22f)
                    close()
                }
                drawPath(ear, palette.body)
                drawPath(ear, palette.outline, style = Stroke(width = bodyR * 0.035f))
                val inner = Path().apply {
                    moveTo(center.x + side * w * 0.38f, topY + bodyR * 0.14f)
                    lineTo(center.x + side * w * (0.52f + p.crest * 0.3f), topY - bodyR * (0.18f + p.crest * 0.7f))
                    lineTo(center.x + side * w * 0.60f, topY + bodyR * 0.16f)
                    close()
                }
                drawPath(inner, palette.accent.copy(alpha = 0.8f))
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

    listOf(-1f, 1f).forEach { side ->
        val ex = center.x + side * spread
        if (spec.mood == Mood.SLEEPING || open < 0.06f) {
            // Closed eyes: a calm downward arc.
            drawArc(
                color = palette.outline,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(ex - r, eyeY - r * 0.7f),
                size = Size(r * 2f, r * 1.4f),
                style = Stroke(width = bodyR * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        } else {
            // Sclera, iris, highlight — squashed vertically by the blink amount.
            drawOval(
                color = Color.White,
                topLeft = Offset(ex - r, eyeY - r * open),
                size = Size(r * 2f, r * 2f * open),
            )
            drawOval(
                color = palette.eye,
                topLeft = Offset(ex - r * 0.52f + gaze, eyeY - r * 0.60f * open + gazeUp),
                size = Size(r * 1.04f, r * 1.2f * open),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = r * 0.22f * open,
                center = Offset(ex - r * 0.18f + gaze, eyeY - r * 0.28f * open + gazeUp),
            )
            drawOval(
                color = palette.outline,
                topLeft = Offset(ex - r, eyeY - r * open),
                size = Size(r * 2f, r * 2f * open),
                style = Stroke(width = bodyR * 0.035f),
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
                color = palette.outline,
                start = Offset(ex - r * 0.9f, eyeY - r - browOffset + if (spec.mood == Mood.SAD) 0f else bodyR * 0.03f * side),
                end = Offset(ex + r * 0.9f, eyeY - r - browOffset - if (spec.mood == Mood.SAD) bodyR * 0.05f * side else 0f),
                strokeWidth = bodyR * 0.04f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }

    // Blush marks for the cheerful moods.
    if (spec.mood == Mood.HAPPY || spec.mood == Mood.NEUTRAL) {
        listOf(-1f, 1f).forEach { side ->
            drawOval(
                color = palette.blush.copy(alpha = 0.45f),
                topLeft = Offset(center.x + side * spread * 1.55f - bodyR * 0.10f, eyeY + r * 0.7f),
                size = Size(bodyR * 0.20f, bodyR * 0.12f),
            )
        }
    }

    drawMouth(center, bodyR, eyeY + r * 1.5f, spec.mood, palette, frame)
}

private fun DrawScope.drawMouth(
    center: Offset,
    bodyR: Float,
    mouthY: Float,
    mood: Mood,
    palette: CreaturePalette,
    frame: CreatureFrame,
) {
    val open = frame.mouthOpen.coerceIn(0f, 1f)
    val w = bodyR * 0.30f
    when {
        open > 0.08f -> {
            // Open mouth: a filled ellipse with a tongue.
            val h = bodyR * (0.10f + 0.22f * open)
            drawOval(
                color = Color(0xFF6B2233),
                topLeft = Offset(center.x - w * 0.6f, mouthY - h * 0.2f),
                size = Size(w * 1.2f, h),
            )
            drawOval(
                color = Color(0xFFE8657F),
                topLeft = Offset(center.x - w * 0.34f, mouthY + h * 0.30f),
                size = Size(w * 0.68f, h * 0.5f),
            )
            drawOval(
                color = palette.outline,
                topLeft = Offset(center.x - w * 0.6f, mouthY - h * 0.2f),
                size = Size(w * 1.2f, h),
                style = Stroke(width = bodyR * 0.03f),
            )
        }
        mood == Mood.SAD || mood == Mood.SICK || mood == Mood.HUNGRY -> {
            drawArc(
                color = palette.outline,
                startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(center.x - w * 0.5f, mouthY),
                size = Size(w, bodyR * 0.16f),
                style = Stroke(width = bodyR * 0.04f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        mood == Mood.SLEEPING -> {
            drawCircle(color = palette.outline, radius = bodyR * 0.04f, center = Offset(center.x, mouthY + bodyR * 0.04f))
        }
        else -> {
            drawArc(
                color = palette.outline,
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(center.x - w * 0.5f, mouthY - bodyR * 0.12f),
                size = Size(w, bodyR * 0.20f),
                style = Stroke(width = bodyR * 0.04f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
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
    when (spec.branch) {
        EvolutionBranch.SCHOLAR -> {
            // Round glasses.
            listOf(-1f, 1f).forEach { side ->
                drawCircle(
                    color = palette.accent,
                    radius = r * 1.15f,
                    center = Offset(center.x + side * spread, eyeY),
                    style = Stroke(width = bodyR * 0.035f),
                )
            }
            drawLine(
                color = palette.accent,
                start = Offset(center.x - spread + r * 1.1f, eyeY),
                end = Offset(center.x + spread - r * 1.1f, eyeY),
                strokeWidth = bodyR * 0.03f,
            )
        }
        EvolutionBranch.ATHLETIC -> {
            // Headband.
            drawLine(
                color = palette.accent,
                start = Offset(center.x - bodyR * p.bodyWidth * 0.85f, center.y - bodyR * 0.52f),
                end = Offset(center.x + bodyR * p.bodyWidth * 0.85f, center.y - bodyR * 0.52f),
                strokeWidth = bodyR * 0.10f,
            )
        }
        EvolutionBranch.FERAL -> {
            // Spiky fur along the back.
            for (i in 0..3) {
                val t = i / 3f
                val x = center.x - bodyR * p.bodyWidth * (0.9f - t * 0.5f)
                val y = center.y - bodyR * (0.55f - t * 0.35f)
                val spike = Path().apply {
                    moveTo(x, y)
                    lineTo(x - bodyR * 0.14f, y - bodyR * 0.18f)
                    lineTo(x + bodyR * 0.04f, y - bodyR * 0.06f)
                    close()
                }
                drawPath(spike, palette.accent)
            }
        }
        EvolutionBranch.GOURMAND -> {
            // A napkin round the neck.
            drawOval(
                color = Color(0xFFF6F2E8),
                topLeft = Offset(center.x - bodyR * 0.34f, center.y + bodyR * 0.16f),
                size = Size(bodyR * 0.68f, bodyR * 0.30f),
            )
        }
        EvolutionBranch.BALANCED -> Unit
    }
}

private fun DrawScope.drawElderMarks(center: Offset, bodyR: Float, palette: CreaturePalette) {
    // Whiskers and a small walking stick.
    listOf(-1f, 1f).forEach { side ->
        drawLine(
            color = palette.outline.copy(alpha = 0.7f),
            start = Offset(center.x + side * bodyR * 0.30f, center.y + bodyR * 0.30f),
            end = Offset(center.x + side * bodyR * 0.72f, center.y + bodyR * 0.24f),
            strokeWidth = bodyR * 0.028f,
        )
    }
}

private fun DrawScope.drawEgg(center: Offset, unit: Float, palette: CreaturePalette, frame: CreatureFrame) {
    val w = unit * 0.26f
    val h = unit * 0.34f
    val wobble = frame.lean
    drawGroundShadow(center.x, center.y + h * 1.05f, w * 1.05f, palette)
    rotate(degrees = wobble, pivot = Offset(center.x, center.y + h)) {
        val egg = Path().apply {
            moveTo(center.x, center.y - h)
            cubicTo(center.x + w * 1.25f, center.y - h * 0.45f, center.x + w * 1.10f, center.y + h, center.x, center.y + h)
            cubicTo(center.x - w * 1.10f, center.y + h, center.x - w * 1.25f, center.y - h * 0.45f, center.x, center.y - h)
            close()
        }
        drawPath(egg, Color(0xFFF6F1E4))
        // Species-tinted spots.
        listOf(
            Offset(center.x - w * 0.35f, center.y + h * 0.10f) to w * 0.22f,
            Offset(center.x + w * 0.30f, center.y - h * 0.20f) to w * 0.16f,
            Offset(center.x + w * 0.10f, center.y + h * 0.45f) to w * 0.19f,
        ).forEach { (pos, r) -> drawCircle(palette.body.copy(alpha = 0.85f), r, pos) }
        drawPath(egg, palette.outline, style = Stroke(width = unit * 0.012f))

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
            drawPath(crack, palette.outline, style = Stroke(width = unit * 0.014f))
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
            drawPath(crown, Color(0xFF8A6A00), style = Stroke(width = bodyR * 0.03f))
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
            listOf(-1f, 1f).forEach { side ->
                drawCircle(
                    color = Color(0xFF00C3E3).copy(alpha = 0.75f),
                    radius = bodyR * 0.16f,
                    center = Offset(center.x + side * w * 0.34f, topY + bodyR * 0.16f),
                )
                drawCircle(
                    color = Color(0xFF2B2D31),
                    radius = bodyR * 0.16f,
                    center = Offset(center.x + side * w * 0.34f, topY + bodyR * 0.16f),
                    style = Stroke(width = bodyR * 0.04f),
                )
            }
            drawLine(
                color = Color(0xFF2B2D31),
                start = Offset(center.x - w * 0.70f, topY + bodyR * 0.16f),
                end = Offset(center.x + w * 0.70f, topY + bodyR * 0.16f),
                strokeWidth = bodyR * 0.05f,
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
            drawPath(leaf, Color(0xFF2F7A3F), style = Stroke(width = bodyR * 0.03f))
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
