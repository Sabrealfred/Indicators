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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The room behind the pet. Layers back to front: wall, sky/window, theme backdrop, props,
 * floor, light shaft, theme overlay and an out-of-focus foreground.
 *
 * Everything here is rendered into a ~144px buffer and upscaled with nearest neighbour, so
 * shapes are deliberately chunky: 1px detail simply vanishes at that resolution. All motion is
 * a pure function of (themeId, timeSeconds) so frames never flicker randomly.
 */
fun DrawScope.drawScene(
    themeId: String,
    night: Float,
    timeSeconds: Float,
    lightsOff: Boolean,
    parallax: Float = 0f,
) {
    val n = night.coerceIn(0f, 1f)
    val palette = Palettes.applyNight(Palettes.room(themeId), n)
    val w = size.width
    val h = size.height
    val horizon = h * 0.68f
    val twilight = twilightAmount(n)
    val daylight = 1f - smoothStep(0.30f, 0.72f, n)

    // 1. Wall gradient.
    drawRect(
        brush = Brush.verticalGradient(listOf(palette.wallTop, palette.wallBottom)),
        size = Size(w, horizon),
    )

    // 2. Dawn/dusk band. A horizontal wash of warm light that only exists while the sun is
    // near the horizon, which is what sells a *continuous* cycle rather than a day/night flip.
    if (twilight > 0.01f) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color(0xFFFF9A4D).copy(alpha = 0.60f * twilight),
                1f to Color(0xFFFF6B3D).copy(alpha = 0.22f * twilight),
                startY = horizon * 0.10f,
                endY = horizon,
            ),
            size = Size(w, horizon),
        )
    }

    // 3. Theme backdrop (sea, void, canopy) behind the window and props.
    drawThemeBackdrop(themeId, palette, n, timeSeconds, horizon, parallax)

    // 4. Window with the sky behind it, plus sun, moon, stars and clouds.
    drawWindow(themeId, palette.sky, n, twilight, timeSeconds, parallax)

    // 5. Theme props sitting against the wall.
    drawThemeProps(themeId, palette, timeSeconds, horizon, parallax)

    // 6. Floor and its shadow line.
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

    // 7. Daylight pouring through the window onto the floor.
    if (!lightsOff && daylight > 0.02f) {
        drawLightShaft(daylight, twilight, parallax)
    }

    // 8. Per-theme full-frame overlay (arcade CRT).
    drawThemeOverlay(themeId, timeSeconds)

    // 9. Out-of-focus foreground; moves against the parallax so it reads as very close.
    drawForeground(themeId, palette, parallax)

    // 10. A last warm wash at dusk, over everything, tying the frame together.
    if (twilight > 0.01f) {
        drawRect(Color(0xFFFF8A3D).copy(alpha = 0.10f * twilight))
    }

    // 11. Lights-out overlay, warm and soft rather than a flat black.
    if (lightsOff) drawLightsOutOverlay()
}

/**
 * The dark wash for a room with the light off. Kept as its own function because in pixel mode
 * it is drawn at full resolution *after* the blit: a smooth gradient squeezed into a 200-pixel
 * buffer and then magnified twelve times turns into a staircase of hard bands, which looks like
 * a rendering bug rather than like lamplight.
 */
fun DrawScope.drawLightsOutOverlay() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x33FFE9B0), Color(0xE60A0C1E)),
            center = Offset(size.width * 0.5f, size.height * 0.55f),
            radius = size.width * 0.85f,
        ),
    )
}

// ------------------------------------------------------------------ sky & light

private fun DrawScope.drawWindow(
    themeId: String,
    sky: Color,
    night: Float,
    twilight: Float,
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
    // Warm haze low in the pane at dawn/dusk.
    if (twilight > 0.01f) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color(0xFFFFA152).copy(alpha = 0.75f * twilight),
                startY = top,
                endY = top + winH,
            ),
            topLeft = Offset(left, top),
            size = Size(winW, winH),
        )
    }

    // Sun and moon cross-fade instead of swapping, and ride opposite ends of the same arc.
    val sunAlpha = smoothStep(0.62f, 0.34f, night)
    val moonAlpha = smoothStep(0.38f, 0.66f, night)
    if (sunAlpha > 0.01f) {
        val p = (1f - night) * 0.5f + 0.25f
        val bx = left + winW * p
        val by = top + winH * (0.58f - sin(p * PI.toFloat()) * 0.34f)
        // The disc reddens as it sinks, which is most of the dusk read.
        val low = 1f - sunAlpha
        drawCircle(Color(0xFFFFF0A8).copy(alpha = 0.28f * sunAlpha), winH * 0.30f, Offset(bx, by))
        drawCircle(
            Color(1f, 0.88f - low * 0.30f, 0.40f - low * 0.28f, sunAlpha),
            winH * 0.16f,
            Offset(bx, by),
        )
    }
    if (moonAlpha > 0.01f) {
        val p = night * 0.5f + 0.25f
        val bx = left + winW * p
        val by = top + winH * (0.58f - sin(p * PI.toFloat()) * 0.34f)
        drawCircle(Color(0xFFF2F4F8).copy(alpha = moonAlpha), winH * 0.14f, Offset(bx, by))
        // Crater bite, drawn in sky colour so the moon keeps a crescent silhouette.
        drawCircle(sky.copy(alpha = moonAlpha * 0.85f), winH * 0.10f, Offset(bx + winH * 0.07f, by - winH * 0.04f))

        val random = Random(themeId.hashCode())
        repeat(14) {
            val sx = left + random.nextFloat() * winW
            val sy = top + random.nextFloat() * winH * 0.8f
            val twinkle = 0.4f + 0.6f * ((sin(time * 2f + sx) + 1f) / 2f)
            drawCircle(
                Color.White.copy(alpha = twinkle * moonAlpha),
                winH * 0.014f * (0.6f + twinkle),
                Offset(sx, sy),
            )
        }
    }

    // Clouds only in the rooms that have a real sky.
    if (themeId != "room_space" && themeId != "room_arcade") {
        val cloudAlpha = (0.20f + 0.55f * (1f - night)) * (1f - moonAlpha * 0.5f)
        drawWindowClouds(left, top, winW, winH, time, cloudAlpha, twilight)
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

/** Three fat cloud blobs scrolling through the pane, clipped by hand since we never clipRect. */
private fun DrawScope.drawWindowClouds(
    left: Float,
    top: Float,
    winW: Float,
    winH: Float,
    time: Float,
    alpha: Float,
    twilight: Float,
) {
    if (alpha <= 0.02f) return
    val body = Color(1f, 0.98f - twilight * 0.10f, 0.95f - twilight * 0.22f)
    repeat(3) { i ->
        val speed = 0.020f + i * 0.008f
        val span = winW + winW * 0.6f
        val cx = left - winW * 0.3f + wrap01(time * speed + i * 0.37f) * span
        val cy = top + winH * (0.20f + i * 0.17f)
        val rx = winW * (0.16f - i * 0.02f)
        val ry = rx * 0.52f
        // Fade at the panes edges so the hand-clip never pops.
        val edge = min(cx - left, left + winW - cx) / (winW * 0.22f)
        val a = alpha * edge.coerceIn(0f, 1f)
        if (a <= 0.02f) return@repeat
        val c = body.copy(alpha = a)
        clampedOval(cx, cy, rx, ry, c, left, left + winW)
        clampedOval(cx - rx * 0.7f, cy + ry * 0.35f, rx * 0.7f, ry * 0.75f, c, left, left + winW)
        clampedOval(cx + rx * 0.65f, cy + ry * 0.30f, rx * 0.6f, ry * 0.70f, c, left, left + winW)
    }
}

/** The wedge of sunlight the window throws across the wall and floor. */
private fun DrawScope.drawLightShaft(daylight: Float, twilight: Float, parallax: Float) {
    val w = size.width
    val h = size.height
    val winW = w * 0.34f
    val left = w * 0.60f + parallax * w * 0.02f
    val topY = h * 0.12f + h * 0.26f
    val shaft = Path().apply {
        moveTo(left, topY)
        lineTo(left + winW, topY)
        lineTo(left + winW - w * 0.06f, h)
        lineTo(left - w * 0.34f, h)
        close()
    }
    val warm = Color(1f, 0.94f - twilight * 0.10f, 0.72f - twilight * 0.24f)
    drawPath(
        path = shaft,
        brush = Brush.verticalGradient(
            0f to warm.copy(alpha = 0.30f * daylight),
            0.55f to warm.copy(alpha = 0.14f * daylight),
            1f to Color.Transparent,
            startY = topY,
            endY = h,
        ),
    )
}

// ------------------------------------------------------------------ theme layers

/** Ambient motion that lives *behind* the props: sea, starfield, canopy. */
private fun DrawScope.drawThemeBackdrop(
    themeId: String,
    palette: RoomPalette,
    night: Float,
    time: Float,
    horizon: Float,
    parallax: Float,
) {
    val w = size.width
    val h = size.height
    val px = parallax * w * 0.02f

    when (themeId) {
        "room_beach" -> {
            // A sun that sinks with the clock, then the sea rolling in front of it.
            val sunY = horizon - h * 0.22f + night * h * 0.24f
            val sunAlpha = (1f - smoothStep(0.55f, 0.95f, night)).coerceIn(0f, 1f)
            if (sunAlpha > 0.02f) {
                drawCircle(Color(0xFFFFD37A).copy(alpha = 0.30f * sunAlpha), h * 0.16f, Offset(w * 0.50f + px, sunY))
                drawCircle(Color(0xFFFFC24D).copy(alpha = sunAlpha), h * 0.085f, Offset(w * 0.50f + px, sunY))
            }
            val seaTop = horizon - h * 0.13f
            drawRect(
                brush = Brush.verticalGradient(listOf(palette.prop, palette.prop.copy(alpha = 0.85f))),
                topLeft = Offset(0f, seaTop),
                size = Size(w, horizon - seaTop),
            )
            // Sun glitter on the water, wide and blocky so it survives downsampling.
            if (sunAlpha > 0.02f) {
                repeat(3) { row ->
                    val y = seaTop + (horizon - seaTop) * (0.25f + row * 0.25f)
                    val width = w * (0.16f - row * 0.03f) * (0.7f + 0.3f * sin(time * 2f + row))
                    drawRect(
                        Color(0xFFFFE7A8).copy(alpha = 0.35f * sunAlpha),
                        topLeft = Offset(w * 0.50f + px - width / 2f, y),
                        size = Size(width, h * 0.012f),
                    )
                }
            }
            // Rolling crests: three rows scrolling at different speeds.
            repeat(3) { row ->
                val y = seaTop + (horizon - seaTop) * (0.28f + row * 0.26f)
                val speed = 0.05f + row * 0.035f
                val spacing = w * (0.26f - row * 0.04f)
                val scroll = wrap01(time * speed) * spacing
                var x = -spacing + scroll
                while (x < w + spacing) {
                    val bob = sin(time * 1.6f + x * 0.02f) * h * 0.004f
                    drawOval(
                        color = Color.White.copy(alpha = 0.55f - row * 0.12f),
                        topLeft = Offset(x, y + bob),
                        size = Size(spacing * 0.45f, h * 0.016f),
                    )
                    x += spacing
                }
            }
        }
        "room_space" -> {
            // Constellations: fixed seeded points, only the brightness moves.
            val random = Random(991)
            repeat(22) { i ->
                val sx = random.nextFloat() * w
                val sy = random.nextFloat() * horizon * 0.92f
                val big = i % 7 == 0
                val twinkle = 0.35f + 0.65f * ((sin(time * 1.7f + i * 1.9f) + 1f) / 2f)
                val r = (if (big) h * 0.014f else h * 0.008f) * (0.7f + twinkle * 0.6f)
                drawCircle(Color.White.copy(alpha = 0.35f + 0.55f * twinkle), r, Offset(sx, sy))
                if (big) {
                    drawCircle(palette.propAccent.copy(alpha = 0.25f * twinkle), r * 2.4f, Offset(sx, sy))
                }
            }
            // A comet crossing on a long loop, tail made of shrinking blobs.
            val t = wrap01(time * 0.035f)
            val cx = -w * 0.15f + t * w * 1.3f
            val cy = horizon * 0.12f + t * horizon * 0.30f
            repeat(5) { i ->
                val f = i / 5f
                drawCircle(
                    Color(0xFFBFE7FF).copy(alpha = (1f - f) * 0.7f),
                    h * 0.018f * (1f - f * 0.7f),
                    Offset(cx - f * w * 0.14f, cy - f * horizon * 0.03f),
                )
            }
        }
        "room_forest" -> {
            // Far canopy: overlapping dark blobs swaying as one mass.
            val sway = sin(time * 0.5f) * w * 0.008f
            repeat(7) { i ->
                val x = w * (i / 6f)
                drawCircle(
                    palette.prop.copy(alpha = 0.30f),
                    h * 0.16f,
                    Offset(x + sway * (if (i % 2 == 0) 1f else -1f), horizon - h * 0.30f),
                )
            }
        }
        "room_arcade" -> {
            // Marquee bulb strip running along the top of the wall.
            val bulbs = 11
            val step = w / bulbs
            val phase = floor(time * 4f).toInt()
            repeat(bulbs) { i ->
                val lit = ((i + phase) % 3) == 0
                val c = if (lit) palette.propAccent else palette.prop.copy(alpha = 0.35f)
                drawCircle(c, h * 0.018f, Offset(step * (i + 0.5f), h * 0.045f))
                if (lit) drawCircle(palette.propAccent.copy(alpha = 0.25f), h * 0.040f, Offset(step * (i + 0.5f), h * 0.045f))
            }
        }
    }
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
            drawPalm(Offset(w * 0.16f + px, horizon), h * 0.30f, sin(time * 0.7f) * 5f)
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
            // Trunks stay put, canopies sway; leaves come off the near tree.
            drawTree(Offset(w * 0.12f + px, horizon), h * 0.34f, palette, sin(time * 0.8f) * 3.5f)
            drawTree(Offset(w * 0.30f + px * 0.6f, horizon), h * 0.24f, palette, sin(time * 0.8f + 1.2f) * 3.0f)
            drawFallingLeaves(time, horizon, palette)
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
                // Attract-mode blocks jittering behind the glass.
                repeat(3) { r ->
                    val bx = cx + w * 0.02f + w * 0.10f * wrap01(time * (0.25f + r * 0.1f) + r * 0.3f)
                    drawRect(
                        palette.propAccent.copy(alpha = 0.75f),
                        topLeft = Offset(min(bx, cx + w * 0.10f), horizon - cabH * (0.78f - r * 0.08f)),
                        size = Size(w * 0.02f, h * 0.014f),
                    )
                }
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

/** Full-frame theme treatment applied after the room is built. */
private fun DrawScope.drawThemeOverlay(themeId: String, time: Float) {
    if (themeId != "room_arcade") return
    val w = size.width
    val h = size.height
    // Scanlines thick enough to survive the downsample, plus a rolling refresh band.
    val lines = 12
    repeat(lines) { i ->
        drawRect(
            Color(0xFF000000).copy(alpha = 0.16f),
            topLeft = Offset(0f, h * (i / lines.toFloat())),
            size = Size(w, h * 0.022f),
        )
    }
    val bandY = wrap01(time * 0.28f) * h
    drawRect(Color.White.copy(alpha = 0.05f), topLeft = Offset(0f, bandY), size = Size(w, h * 0.08f))
    // Mains-hum flicker: two detuned sines so the beat never looks periodic.
    val flicker = 0.030f + 0.022f * sin(time * 31f) + 0.014f * sin(time * 7.3f)
    drawRect(Color(0xFF9CFFFF).copy(alpha = flicker.coerceIn(0f, 0.08f)))
}

/**
 * The very front of the frame: one big soft shape that shifts *against* the parallax, which is
 * what makes the room behind it feel deep. Blur is faked with stacked translucent bands.
 */
private fun DrawScope.drawForeground(themeId: String, palette: RoomPalette, parallax: Float) {
    val w = size.width
    val h = size.height
    val fx = -parallax * w * 0.06f
    val top = h * 0.885f

    val (base, trim) = when (themeId) {
        "room_beach" -> palette.floor to Color(0xFFFFF0C4)
        "room_space" -> Color(0xFF171A38) to palette.propAccent
        "room_arcade" -> Color(0xFF120722) to palette.propAccent
        "room_forest" -> Color(0xFF2C4A2E) to palette.prop
        else -> palette.propAccent to Color(0xFFFFF3DC)
    }

    // Soft edge: three fading bands above the solid body read as out-of-focus at 144px.
    repeat(3) { i ->
        drawRect(
            base.copy(alpha = 0.18f + i * 0.22f),
            topLeft = Offset(0f, top - h * (0.030f - i * 0.010f)),
            size = Size(w, h * 0.030f),
        )
    }

    when (themeId) {
        "room_beach" -> {
            // Sand ridge: a wide shallow dome with a lit crest.
            drawOval(base.copy(alpha = 0.95f), topLeft = Offset(fx - w * 0.25f, top), size = Size(w * 1.5f, h * 0.30f))
            drawOval(trim.copy(alpha = 0.35f), topLeft = Offset(fx - w * 0.25f, top), size = Size(w * 1.5f, h * 0.030f))
        }
        "room_space", "room_arcade" -> {
            // Console lip with a glowing strip and two chunky buttons.
            drawRect(base.copy(alpha = 0.96f), topLeft = Offset(0f, top), size = Size(w, h - top))
            drawRect(trim.copy(alpha = 0.55f), topLeft = Offset(0f, top), size = Size(w, h * 0.012f))
            repeat(2) { i ->
                drawCircle(trim.copy(alpha = 0.8f), h * 0.020f, Offset(fx + w * (0.24f + i * 0.52f), top + h * 0.055f))
            }
        }
        "room_forest" -> {
            // A mossy bank with two grass tufts.
            drawOval(base.copy(alpha = 0.95f), topLeft = Offset(fx - w * 0.20f, top), size = Size(w * 1.4f, h * 0.30f))
            repeat(3) { i ->
                drawOval(
                    trim.copy(alpha = 0.45f),
                    topLeft = Offset(fx + w * (0.12f + i * 0.34f), top - h * 0.018f),
                    size = Size(w * 0.10f, h * 0.045f),
                )
            }
        }
        else -> {
            // Rug edge with a lighter fringe.
            drawOval(base.copy(alpha = 0.85f), topLeft = Offset(fx - w * 0.30f, top), size = Size(w * 1.6f, h * 0.30f))
            drawOval(trim.copy(alpha = 0.35f), topLeft = Offset(fx - w * 0.30f, top), size = Size(w * 1.6f, h * 0.026f))
        }
    }
}

// ------------------------------------------------------------------ props

private fun DrawScope.drawPalm(base: Offset, height: Float, sway: Float) {
    val trunk = Path().apply {
        moveTo(base.x - height * 0.05f, base.y)
        quadraticBezierTo(base.x + height * 0.10f, base.y - height * 0.5f, base.x + height * 0.02f, base.y - height)
        lineTo(base.x + height * 0.10f, base.y - height)
        quadraticBezierTo(base.x + height * 0.18f, base.y - height * 0.5f, base.x + height * 0.06f, base.y)
        close()
    }
    drawPath(trunk, Color(0xFF8A6242))
    val crown = Offset(base.x + height * 0.06f, base.y - height)
    repeat(5) { i ->
        val angle = -160f + i * 55f + sway
        rotate(angle, crown) {
            drawOval(
                color = Color(0xFF3FA45C),
                topLeft = Offset(crown.x, crown.y - height * 0.06f),
                size = Size(height * 0.46f, height * 0.13f),
            )
        }
    }
}

private fun DrawScope.drawTree(base: Offset, height: Float, palette: RoomPalette, sway: Float) {
    drawRect(
        color = Color(0xFF5A3E27),
        topLeft = Offset(base.x - height * 0.05f, base.y - height * 0.45f),
        size = Size(height * 0.10f, height * 0.45f),
    )
    // Pivot at the trunk top so the whole canopy leans as one piece.
    rotate(sway, Offset(base.x, base.y - height * 0.45f)) {
        listOf(0.45f to 0.34f, 0.62f to 0.27f, 0.78f to 0.19f).forEach { (offsetY, radius) ->
            drawCircle(palette.prop, height * radius, Offset(base.x, base.y - height * offsetY - height * 0.10f))
        }
    }
}

/** Leaves falling in fixed lanes; seeded once per lane so nothing jitters between frames. */
private fun DrawScope.drawFallingLeaves(time: Float, horizon: Float, palette: RoomPalette) {
    val w = size.width
    val h = size.height
    val random = Random(4207)
    repeat(8) { i ->
        val laneX = random.nextFloat() * w
        val speed = 0.055f + random.nextFloat() * 0.06f
        val phase = random.nextFloat()
        val fall = wrap01(time * speed + phase)
        val y = horizon * 0.10f + fall * (horizon * 0.95f)
        val x = laneX + sin(time * 1.1f + i * 2.1f) * w * 0.05f
        val alpha = (1f - smoothStep(0.85f, 1f, fall)) * 0.9f
        rotate(sin(time * 2f + i) * 40f, Offset(x, y)) {
            drawOval(
                color = (if (i % 2 == 0) palette.prop else palette.propAccent).copy(alpha = alpha),
                topLeft = Offset(x - w * 0.016f, y - h * 0.008f),
                size = Size(w * 0.032f, h * 0.016f),
            )
        }
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

/**
 * Poop piles, one per unattended mess, arranged so they never cover the pet. Each pile is
 * seeded from its index so the row reads as several different messes rather than one stamp
 * repeated; the lane centres stay put because the tap hit-test depends on them.
 */
fun DrawScope.drawPoops(count: Int, time: Float) {
    if (count <= 0) return
    val w = size.width
    val h = size.height
    val baseY = h * 0.86f
    repeat(count.coerceAtMost(6)) { i ->
        val random = Random(i * 7919 + 31)
        val scale = 0.78f + random.nextFloat() * 0.50f
        val tilt = -16f + random.nextFloat() * 32f
        val threeLumps = random.nextFloat() > 0.4f
        val jitterX = (random.nextFloat() - 0.5f) * w * 0.035f
        val jitterY = (random.nextFloat() - 0.5f) * h * 0.020f
        val squat = 0.85f + random.nextFloat() * 0.45f

        val x = w * (0.13f + i * 0.13f) + jitterX
        val bounce = floatOffset(time + i, 1.2f, h * 0.004f)
        val r = h * 0.030f * scale
        val y = baseY + bounce + jitterY

        rotate(tilt, Offset(x, y)) {
            drawOval(Color(0xFF6B4A2F), topLeft = Offset(x - r, y - r * 0.5f), size = Size(r * 2f, r * squat))
            drawOval(
                Color(0xFF7C5636),
                topLeft = Offset(x - r * 0.75f, y - r * 1.2f * squat),
                size = Size(r * 1.5f, r * 0.85f * squat),
            )
            if (threeLumps) {
                drawOval(
                    Color(0xFF8D6340),
                    topLeft = Offset(x - r * 0.45f, y - r * 1.85f * squat),
                    size = Size(r * 0.9f, r * 0.70f * squat),
                )
            }
        }
        // Flies buzzing above it.
        repeat(2) { f ->
            val t = time * 3f + f * 2f + i
            drawCircle(
                Color(0xFF2B2D31),
                h * 0.005f,
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

/**
 * Optional weather pass, drawn over a finished scene. Not part of [drawScene]; callers decide
 * when it rains. Streaks and flakes are deliberately fat — anything thinner than a couple of
 * source pixels disappears in the upscale.
 */
fun DrawScope.drawWeather(kind: String, timeSeconds: Float, intensity: Float = 1f) {
    val strength = intensity.coerceIn(0f, 1f)
    if (strength <= 0.01f) return
    val w = size.width
    val h = size.height

    when (kind) {
        "rain" -> {
            drawRect(Color(0xFF3E5A78).copy(alpha = 0.16f * strength))
            val drops = (14 + 30 * strength).toInt()
            val random = Random(1301)
            repeat(drops) {
                val laneX = random.nextFloat()
                val speed = 0.9f + random.nextFloat() * 0.8f
                val phase = random.nextFloat()
                val len = h * (0.10f + random.nextFloat() * 0.08f)
                val y = wrap01(timeSeconds * speed + phase) * (h + len) - len
                val x = laneX * (w + w * 0.18f) - w * 0.18f + (y / h) * w * 0.16f
                drawLine(
                    color = Color(0xFFBFE2FF).copy(alpha = (0.35f + 0.35f * random.nextFloat()) * strength),
                    start = Offset(x, y),
                    end = Offset(x - w * 0.03f, y + len),
                    strokeWidth = h * 0.010f,
                )
            }
            // Splash marks along the floor line so the rain feels like it lands somewhere.
            repeat((4 + 6 * strength).toInt()) { i ->
                val sx = w * wrap01(i * 0.173f + floor(timeSeconds * 3f) * 0.061f)
                drawOval(
                    Color(0xFFCDE8FF).copy(alpha = 0.30f * strength),
                    topLeft = Offset(sx, h * 0.90f),
                    size = Size(w * 0.05f, h * 0.012f),
                )
            }
        }
        "snow" -> {
            drawRect(Color(0xFFDCE9F5).copy(alpha = 0.08f * strength))
            val flakes = (12 + 26 * strength).toInt()
            val random = Random(7717)
            repeat(flakes) { i ->
                val laneX = random.nextFloat()
                val speed = 0.10f + random.nextFloat() * 0.14f
                val phase = random.nextFloat()
                val big = random.nextFloat() > 0.6f
                val fall = wrap01(timeSeconds * speed + phase)
                val y = fall * (h + h * 0.06f) - h * 0.03f
                val x = laneX * w + sin(timeSeconds * 0.9f + i * 1.7f) * w * 0.045f
                val r = if (big) h * 0.018f else h * 0.011f
                drawCircle(Color.White.copy(alpha = (if (big) 0.95f else 0.7f) * strength), r, Offset(x, y))
            }
            // Settled snow along the bottom edge.
            drawRect(
                Color.White.copy(alpha = 0.55f * strength),
                topLeft = Offset(0f, h * 0.955f),
                size = Size(w, h * 0.045f),
            )
        }
        else -> Unit
    }
}

// ------------------------------------------------------------------ helpers

/** 1 at the middle of dawn/dusk, 0 outside roughly 0.3..0.7 of the night curve. */
private fun twilightAmount(night: Float): Float =
    (1f - abs(night - 0.5f) / 0.2f).coerceIn(0f, 1f)

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Fractional part, always positive, for looping animations off a rising clock. */
private fun wrap01(v: Float): Float = v - floor(v)

/** An oval cut off at the given x bounds; stands in for clipRect, which this file never uses. */
private fun DrawScope.clampedOval(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    color: Color,
    minX: Float,
    maxX: Float,
) {
    val x0 = max(cx - rx, minX)
    val x1 = min(cx + rx, maxX)
    if (x1 <= x0) return
    drawOval(color, topLeft = Offset(x0, cy - ry), size = Size(x1 - x0, ry * 2f))
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
