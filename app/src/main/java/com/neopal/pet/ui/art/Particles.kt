package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The shapes the emitter knows how to draw. */
enum class ParticleKind { HEART, SPARKLE, CRUMB, BUBBLE, ZZZ, NOTE, STAR, DUST, ANGER, COIN }

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    val size: Float,
    val kind: ParticleKind,
    val color: Color,
    var spin: Float = 0f,
    val spinSpeed: Float = 0f,
)

/**
 * A tiny CPU particle system. Positions are normalised 0..1 against the canvas, so the same
 * emitter works on a phone, a foldable and a tablet without retuning.
 */
class ParticleSystem(private val random: Random = Random(1)) {

    private val particles = ArrayDeque<Particle>()
    private val maxParticles = 160

    val isIdle: Boolean get() = particles.isEmpty()

    fun clear() = particles.clear()

    /** Emits [count] particles around the normalised point ([x], [y]). */
    fun emit(kind: ParticleKind, x: Float, y: Float, count: Int = 8, color: Color = defaultColor(kind), spread: Float = 0.10f) {
        repeat(count) {
            if (particles.size >= maxParticles) particles.removeFirstOrNull()
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val speed = 0.05f + random.nextFloat() * 0.12f
            val life = 0.7f + random.nextFloat() * 0.9f
            particles.addLast(
                Particle(
                    x = x + (random.nextFloat() - 0.5f) * spread,
                    y = y + (random.nextFloat() - 0.5f) * spread,
                    vx = kotlin.math.cos(angle) * speed * horizontalBias(kind),
                    vy = kotlin.math.sin(angle) * speed - risingBias(kind),
                    life = life,
                    maxLife = life,
                    size = 0.018f + random.nextFloat() * 0.022f,
                    kind = kind,
                    color = color,
                    spinSpeed = (random.nextFloat() - 0.5f) * 240f,
                ),
            )
        }
    }

    /** Advances the simulation by [dt] seconds and drops dead particles. */
    fun update(dt: Float) {
        // One pass, no allocation: this runs sixty times a second behind the whole scene.
        for (p in particles) {
            p.life -= dt
            if (p.life <= 0f) continue
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += gravity(p.kind) * dt
            p.vx *= 0.98f
            p.spin += p.spinSpeed * dt
        }
        particles.removeAll { it.life <= 0f }
    }

    fun draw(scope: DrawScope) = with(scope) {
        particles.forEach { p ->
            val alpha = (p.life / p.maxLife).coerceIn(0f, 1f)
            // Snapped to the same grid as everything else: sub-pixel particles smear into
            // half-lit blocks that flicker instead of reading as sparks.
            val center = Offset(
                x = kotlin.math.round(p.x * size.width),
                y = kotlin.math.round(p.y * size.height),
            )
            val r = p.size * size.minDimension
            rotate(p.spin, center) {
                when (p.kind) {
                    ParticleKind.HEART -> drawHeart(center, r, p.color.copy(alpha = alpha))
                    ParticleKind.SPARKLE, ParticleKind.STAR -> drawSparkle(center, r, p.color.copy(alpha = alpha))
                    ParticleKind.CRUMB -> drawRect(
                        color = p.color.copy(alpha = alpha),
                        topLeft = Offset(center.x - r * 0.4f, center.y - r * 0.4f),
                        size = Size(r * 0.8f, r * 0.8f),
                    )
                    ParticleKind.BUBBLE -> {
                        drawCircle(p.color.copy(alpha = alpha * 0.35f), r * 0.9f, center)
                        drawCircle(Color.White.copy(alpha = alpha * 0.8f), r * 0.9f, center, style = Stroke(width = r * 0.14f))
                    }
                    ParticleKind.ZZZ -> drawZ(center, r, p.color.copy(alpha = alpha))
                    ParticleKind.NOTE -> drawNote(center, r, p.color.copy(alpha = alpha))
                    ParticleKind.DUST -> drawCircle(p.color.copy(alpha = alpha * 0.5f), r * 0.6f, center)
                    ParticleKind.ANGER -> drawAnger(center, r, p.color.copy(alpha = alpha))
                    ParticleKind.COIN -> {
                        drawCircle(p.color.copy(alpha = alpha), r * 0.7f, center)
                        drawCircle(Color(0xFF8A6A00).copy(alpha = alpha), r * 0.7f, center, style = Stroke(width = r * 0.12f))
                    }
                }
            }
        }
    }

    private fun gravity(kind: ParticleKind) = when (kind) {
        ParticleKind.CRUMB, ParticleKind.COIN -> 0.55f
        ParticleKind.HEART, ParticleKind.ZZZ, ParticleKind.BUBBLE, ParticleKind.NOTE -> -0.05f
        else -> 0.08f
    }

    private fun risingBias(kind: ParticleKind) = when (kind) {
        ParticleKind.HEART, ParticleKind.ZZZ, ParticleKind.BUBBLE, ParticleKind.NOTE -> 0.10f
        else -> 0f
    }

    private fun horizontalBias(kind: ParticleKind) = when (kind) {
        ParticleKind.ZZZ -> 0.4f
        else -> 1f
    }

    private fun defaultColor(kind: ParticleKind) = when (kind) {
        ParticleKind.HEART -> Color(0xFFFF5CA8)
        ParticleKind.SPARKLE, ParticleKind.STAR -> Color(0xFFFFE066)
        ParticleKind.CRUMB -> Color(0xFFC98A4B)
        ParticleKind.BUBBLE -> Color(0xFF9BE3FF)
        ParticleKind.ZZZ -> Color(0xFFD7E1FF)
        ParticleKind.NOTE -> Color(0xFF9C6BFF)
        ParticleKind.DUST -> Color(0xFFBBB1A0)
        ParticleKind.ANGER -> Color(0xFFFF3C28)
        ParticleKind.COIN -> Color(0xFFFFD447)
    }
}

// ------------------------------------------------------------------ shapes

private fun DrawScope.drawHeart(center: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + r * 0.75f)
        cubicTo(center.x - r * 1.5f, center.y - r * 0.2f, center.x - r * 0.5f, center.y - r * 1.1f, center.x, center.y - r * 0.35f)
        cubicTo(center.x + r * 0.5f, center.y - r * 1.1f, center.x + r * 1.5f, center.y - r * 0.2f, center.x, center.y + r * 0.75f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawSparkle(center: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticBezierTo(center.x + r * 0.18f, center.y - r * 0.18f, center.x + r, center.y)
        quadraticBezierTo(center.x + r * 0.18f, center.y + r * 0.18f, center.x, center.y + r)
        quadraticBezierTo(center.x - r * 0.18f, center.y + r * 0.18f, center.x - r, center.y)
        quadraticBezierTo(center.x - r * 0.18f, center.y - r * 0.18f, center.x, center.y - r)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawZ(center: Offset, r: Float, color: Color) {
    val w = r * 0.9f
    val path = Path().apply {
        moveTo(center.x - w, center.y - w)
        lineTo(center.x + w, center.y - w)
        lineTo(center.x - w, center.y + w)
        lineTo(center.x + w, center.y + w)
    }
    drawPath(path, color, style = Stroke(width = r * 0.28f))
}

private fun DrawScope.drawNote(center: Offset, r: Float, color: Color) {
    drawOval(color, topLeft = Offset(center.x - r * 0.6f, center.y), size = Size(r * 1.0f, r * 0.7f))
    drawLine(color, Offset(center.x + r * 0.40f, center.y + r * 0.3f), Offset(center.x + r * 0.40f, center.y - r), strokeWidth = r * 0.2f)
    drawLine(color, Offset(center.x + r * 0.40f, center.y - r), Offset(center.x + r * 0.95f, center.y - r * 0.75f), strokeWidth = r * 0.2f)
}

private fun DrawScope.drawAnger(center: Offset, r: Float, color: Color) {
    listOf(0f, 90f).forEach { angle ->
        rotate(angle, center) {
            drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeWidth = r * 0.28f)
        }
    }
}

/** A gentle bobbing offset shared by several floating props. */
fun floatOffset(time: Float, speed: Float, amplitude: Float): Float =
    sin(time * speed * 2f * PI.toFloat()) * amplitude
