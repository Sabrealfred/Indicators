package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The room behind the pet. Four parallax layers — sky, far props, wall, floor — repainted
 * for the time of day and for whichever theme the player bought.
 */
fun DrawScope.drawScene(
    themeId: String,
    night: Float,
    timeSeconds: Float,
    lightsOff: Boolean,
    parallax: Float = 0f,
) {
    val palette = Palettes.applyNight(Palettes.room(themeId), night)
    val w = size.width
    val h = size.height
    val horizon = h * 0.68f

    // 1. Wall gradient.
    drawRect(
        brush = Brush.verticalGradient(listOf(palette.wallTop, palette.wallBottom)),
        size = Size(w, horizon),
    )

    // 2. Window with the sky behind it, plus sun or moon and stars.
    drawWindow(themeId, palette.sky, night, timeSeconds, parallax)

    // 3. Theme props sitting against the wall.
    drawThemeProps(themeId, palette, timeSeconds, horizon, parallax)

    // 4. Floor and its shadow line.
    drawRect(
        brush = Brush.verticalGradient(listOf(palette.floor, palette.floorShade)),
        topLeft = Offset(0f, horizon),
        size = Size(w, h - horizon),
    )
    drawLine(
        color = palette.floorShade.copy(alpha = 0.8f),
        start = Offset(0f, horizon),
        end = Offset(w, horizon),
        strokeWidth = h * 0.006f,
    )
    // Floorboards for depth.
    for (i in 1..5) {
        val y = horizon + (h - horizon) * (i / 6f)
        drawLine(
            color = palette.floorShade.copy(alpha = 0.35f),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = h * 0.002f,
        )
    }

    // 5. Lights-out overlay, warm and soft rather than a flat black.
    if (lightsOff) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x33FFE9B0), Color(0xE60A0C1E)),
                center = Offset(w * 0.5f, h * 0.55f),
                radius = w * 0.85f,
            ),
        )
    }
}

private fun DrawScope.drawWindow(
    themeId: String,
    sky: Color,
    night: Float,
    time: Float,
    parallax: Float,
) {
    val w = size.width
    val h = size.height
    val winW = w * 0.34f
    val winH = h * 0.26f
    val left = w * 0.60f + parallax * w * 0.02f
    val top = h * 0.12f

    drawRoundRectCompat(left, top, winW, winH, w * 0.02f, sky)

    // Sun by day, moon by night, both tracking the same arc.
    val dayProgress = ((1f - night) * 0.5f + 0.25f)
    val bodyX = left + winW * dayProgress
    val bodyY = top + winH * (0.55f - sin(dayProgress * PI.toFloat()) * 0.35f)
    if (night < 0.5f) {
        drawCircle(Color(0xFFFFE066).copy(alpha = 1f - night * 2f), winH * 0.16f, Offset(bodyX, bodyY))
    } else {
        drawCircle(Color(0xFFF2F4F8).copy(alpha = (night - 0.5f) * 2f), winH * 0.14f, Offset(bodyX, bodyY))
        // Stars twinkle on their own seeded rhythm.
        val random = Random(themeId.hashCode())
        repeat(14) {
            val sx = left + random.nextFloat() * winW
            val sy = top + random.nextFloat() * winH * 0.8f
            val twinkle = 0.4f + 0.6f * ((sin(time * 2f + sx) + 1f) / 2f)
            drawCircle(
                Color.White.copy(alpha = twinkle * (night - 0.5f) * 2f),
                winH * 0.012f * (0.6f + twinkle),
                Offset(sx, sy),
            )
        }
    }

    // Frame and cross bars.
    drawRoundRectCompat(left, top, winW, winH, w * 0.02f, Color(0xFF4A3A2C), stroke = w * 0.012f)
    drawLine(Color(0xFF4A3A2C), Offset(left + winW / 2f, top), Offset(left + winW / 2f, top + winH), strokeWidth = w * 0.010f)
    drawLine(Color(0xFF4A3A2C), Offset(left, top + winH / 2f), Offset(left + winW, top + winH / 2f), strokeWidth = w * 0.010f)
    // Sill.
    drawRect(
        color = Color(0xFF5B4635),
        topLeft = Offset(left - winW * 0.05f, top + winH),
        size = Size(winW * 1.10f, h * 0.018f),
    )
}

private fun DrawScope.drawThemeProps(
    themeId: String,
    palette: RoomPalette,
    time: Float,
    horizon: Float,
    parallax: Float,
) {
    val w = size.width
    val h = size.height
    val px = parallax * w * 0.04f

    when (themeId) {
        "room_beach" -> {
            // Palm, beach ball and a couple of waves on the horizon.
            drawPalm(Offset(w * 0.16f + px, horizon), h * 0.30f)
            val ballY = horizon - h * 0.03f + floatOffset(time, 0.6f, h * 0.012f)
            drawCircle(palette.prop, h * 0.035f, Offset(w * 0.82f + px, ballY))
            drawArc(
                color = Color.White.copy(alpha = 0.6f),
                startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.82f + px - h * 0.035f, ballY - h * 0.035f),
                size = Size(h * 0.07f, h * 0.07f),
                style = Stroke(width = h * 0.006f),
            )
        }
        "room_space" -> {
            // Slowly orbiting planet and a passing satellite.
            val planetCenter = Offset(w * 0.22f + px, h * 0.22f)
            drawCircle(palette.prop, h * 0.070f, planetCenter)
            rotate(time * 12f, planetCenter) {
                drawOval(
                    color = palette.propAccent.copy(alpha = 0.85f),
                    topLeft = Offset(planetCenter.x - h * 0.12f, planetCenter.y - h * 0.022f),
                    size = Size(h * 0.24f, h * 0.044f),
                    style = Stroke(width = h * 0.008f),
                )
            }
            val satX = (time * 0.05f % 1f) * w
            drawRect(palette.propAccent, topLeft = Offset(satX, h * 0.40f), size = Size(w * 0.03f, h * 0.012f))
        }
        "room_forest" -> {
            drawTree(Offset(w * 0.12f + px, horizon), h * 0.34f, palette)
            drawTree(Offset(w * 0.30f + px * 0.6f, horizon), h * 0.24f, palette)
            // Fireflies drifting in slow loops.
            repeat(6) { i ->
                val t = time * 0.35f + i
                val fx = w * (0.35f + 0.5f * ((sin(t) + 1f) / 2f))
                val fy = horizon - h * (0.05f + 0.18f * ((cos(t * 0.8f) + 1f) / 2f))
                drawCircle(palette.propAccent.copy(alpha = 0.35f), h * 0.014f, Offset(fx, fy))
                drawCircle(palette.propAccent, h * 0.006f, Offset(fx, fy))
            }
        }
        "room_arcade" -> {
            // Two cabinets with scrolling marquee lights.
            listOf(0.12f, 0.30f).forEachIndexed { index, x ->
                val cx = w * x + px
                val cabH = h * 0.30f
                drawRect(palette.prop.copy(alpha = 0.9f), topLeft = Offset(cx, horizon - cabH), size = Size(w * 0.14f, cabH))
                drawRect(Color(0xFF10131A), topLeft = Offset(cx + w * 0.02f, horizon - cabH * 0.86f), size = Size(w * 0.10f, cabH * 0.34f))
                val blink = ((sin(time * 3f + index) + 1f) / 2f)
                drawRect(
                    palette.propAccent.copy(alpha = 0.4f + blink * 0.6f),
                    topLeft = Offset(cx, horizon - cabH),
                    size = Size(w * 0.14f, h * 0.012f),
                )
            }
        }
        else -> {
            // Cozy room: a rug, a picture frame and a potted plant.
            drawOval(
                color = palette.propAccent.copy(alpha = 0.5f),
                topLeft = Offset(w * 0.22f, horizon + h * 0.10f),
                size = Size(w * 0.56f, h * 0.14f),
            )
            drawRoundRectCompat(w * 0.10f + px, h * 0.16f, w * 0.16f, h * 0.14f, w * 0.01f, palette.prop)
            drawRoundRectCompat(w * 0.10f + px, h * 0.16f, w * 0.16f, h * 0.14f, w * 0.01f, Color(0xFF5B4635), stroke = w * 0.010f)
            drawPot(Offset(w * 0.86f + px, horizon), h * 0.16f, palette)
        }
    }
}

// ------------------------------------------------------------------ props

private fun DrawScope.drawPalm(base: Offset, height: Float) {
    val trunk = Path().apply {
        moveTo(base.x - height * 0.05f, base.y)
        quadraticBezierTo(base.x + height * 0.10f, base.y - height * 0.5f, base.x + height * 0.02f, base.y - height)
        lineTo(base.x + height * 0.10f, base.y - height)
        quadraticBezierTo(base.x + height * 0.18f, base.y - height * 0.5f, base.x + height * 0.06f, base.y)
        close()
    }
    drawPath(trunk, Color(0xFF8A6242))
    repeat(5) { i ->
        val angle = -160f + i * 55f
        rotate(angle, Offset(base.x + height * 0.06f, base.y - height)) {
            drawOval(
                color = Color(0xFF3FA45C),
                topLeft = Offset(base.x + height * 0.06f, base.y - height - height * 0.06f),
                size = Size(height * 0.46f, height * 0.13f),
            )
        }
    }
}

private fun DrawScope.drawTree(base: Offset, height: Float, palette: RoomPalette) {
    drawRect(
        color = Color(0xFF5A3E27),
        topLeft = Offset(base.x - height * 0.05f, base.y - height * 0.45f),
        size = Size(height * 0.10f, height * 0.45f),
    )
    listOf(0.45f to 0.34f, 0.62f to 0.27f, 0.78f to 0.19f).forEach { (offsetY, radius) ->
        drawCircle(palette.prop, height * radius, Offset(base.x, base.y - height * offsetY - height * 0.10f))
    }
}

private fun DrawScope.drawPot(base: Offset, height: Float, palette: RoomPalette) {
    val potW = height * 0.55f
    val pot = Path().apply {
        moveTo(base.x - potW / 2f, base.y - height * 0.42f)
        lineTo(base.x + potW / 2f, base.y - height * 0.42f)
        lineTo(base.x + potW * 0.36f, base.y)
        lineTo(base.x - potW * 0.36f, base.y)
        close()
    }
    drawPath(pot, Color(0xFFC1673F))
    repeat(3) { i ->
        val angle = -35f + i * 35f
        rotate(angle, Offset(base.x, base.y - height * 0.42f)) {
            drawOval(
                color = palette.propAccent,
                topLeft = Offset(base.x - height * 0.06f, base.y - height * 0.95f),
                size = Size(height * 0.12f, height * 0.55f),
            )
        }
    }
}

/** Poop piles, one per unattended mess, arranged so they never cover the pet. */
fun DrawScope.drawPoops(count: Int, time: Float) {
    if (count <= 0) return
    val w = size.width
    val h = size.height
    val baseY = h * 0.86f
    repeat(count.coerceAtMost(6)) { i ->
        val x = w * (0.13f + i * 0.13f)
        val bounce = floatOffset(time + i, 1.2f, h * 0.004f)
        val r = h * 0.030f
        val y = baseY + bounce
        // Three stacked lumps.
        drawOval(Color(0xFF6B4A2F), topLeft = Offset(x - r, y - r * 0.5f), size = Size(r * 2f, r))
        drawOval(Color(0xFF7C5636), topLeft = Offset(x - r * 0.75f, y - r * 1.2f), size = Size(r * 1.5f, r * 0.85f))
        drawOval(Color(0xFF8D6340), topLeft = Offset(x - r * 0.45f, y - r * 1.75f), size = Size(r * 0.9f, r * 0.7f))
        // Flies buzzing above it.
        repeat(2) { f ->
            val t = time * 3f + f * 2f + i
            drawCircle(
                Color(0xFF2B2D31),
                h * 0.004f,
                Offset(x + sin(t) * r * 1.4f, y - r * 2.4f + cos(t * 1.3f) * r * 0.5f),
            )
        }
    }
}

/** Green sick aura pulsing behind the pet. */
fun DrawScope.drawSickAura(center: Offset, radius: Float, time: Float) {
    val pulse = 0.6f + 0.4f * ((sin(time * 2.2f) + 1f) / 2f)
    drawCircle(Color(0xFF7FBF6A).copy(alpha = 0.18f * pulse), radius * 1.25f * pulse, center)
}

/** Sleeping vignette: dims the frame from the edges inward. */
fun DrawScope.drawSleepVignette(strength: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0xFF060814).copy(alpha = 0.75f * strength)),
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            radius = size.minDimension * 0.85f,
        ),
    )
}

/** Rounded rect helper that works for both fill and stroke without repeating the geometry. */
internal fun DrawScope.drawRoundRectCompat(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    radius: Float,
    color: Color,
    stroke: Float = 0f,
) {
    val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    if (stroke > 0f) {
        drawRoundRect(color, Offset(x, y), Size(width, height), corner, style = Stroke(width = stroke))
    } else {
        drawRoundRect(color, Offset(x, y), Size(width, height), corner)
    }
}
