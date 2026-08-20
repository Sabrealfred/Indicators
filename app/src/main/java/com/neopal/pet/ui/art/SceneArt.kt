package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The room behind the pet. Layers back to front: wall, sky/window, theme backdrop, props,
 * floor, contact shadow, window light, theme overlay and an out-of-focus foreground.
 *
 * Two rules run through the whole file.
 *
 * **No smooth gradients.** Everything is rendered into a ~200px buffer and magnified with
 * nearest neighbour, so a vertical gradient turns into a staircase of visible bands. Instead
 * every ramp is quantised to a handful of palette tones and the seams between them are hidden
 * with an ordered Bayer dither ([drawDitheredVertical]). The pattern depends on pixel
 * coordinates alone, never on the clock, so it sits still instead of crawling.
 *
 * **Whole pixels only.** Positions are snapped to the art grid with [snapTo] before anything is
 * drawn; a prop drifting by a third of a pixel shimmers once it is magnified twelve times.
 *
 * All motion is a pure function of (themeId, timeSeconds) so frames never flicker randomly.
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
    val c = artPixel()
    val w = size.width
    val h = size.height
    val horizon = snapTo(h * 0.68f, c)
    val twilight = twilightAmount(n)
    val daylight = 1f - smoothStep(0.30f, 0.72f, n)

    // 1. Wall: four tones of the room ramp, interleaved at the seams.
    drawDitheredVertical(palette.wallTop, palette.wallBottom, Rect(0f, 0f, w, horizon))

    // 2. Dawn/dusk warmth, strongest just above the floor. Stacked translucent slabs with
    // dithered edges rather than one gradient, for the same reason as the wall.
    if (twilight > 0.01f) {
        drawDitherFadeOut(
            color = Color(0xFFFFAE6B),
            rect = Rect(0f, horizon * 0.18f, w, horizon),
            alpha = 0.42f * twilight,
            bands = 3,
            fromTop = false,
        )
    }

    // 3. Theme backdrop (sea, void, canopy) behind the window and props.
    drawThemeBackdrop(themeId, palette, n, timeSeconds, horizon, parallax)

    // 4. Window with the sky behind it, plus sun, moon, stars and clouds.
    drawWindow(themeId, palette, n, twilight, timeSeconds, parallax)

    // 5. Floor. It goes down before the props so that each prop can drop its own shadow onto
    // it; drawing the floor last would paint over every contact shadow in the room.
    drawDitheredVertical(palette.floor, palette.floorShade, Rect(0f, horizon, w, h), bands = 4)
    drawFloorboards(palette, horizon, c)

    // 6. Ambient occlusion along the join. Cheap, and the single change that stops the room
    // looking like two rectangles stacked on top of each other.
    drawContactShadow(palette, horizon, c)

    // 7. Theme props, each sitting in its own pool of shade.
    drawThemeProps(themeId, palette, timeSeconds, horizon, parallax)

    // 8. Daylight pouring through the window onto the floor.
    if (!lightsOff && daylight > 0.02f) {
        drawWindowLight(palette, daylight, twilight, horizon, parallax)
    }

    // 9. Per-theme full-frame overlay (arcade CRT).
    drawThemeOverlay(themeId, palette, timeSeconds)

    // 10. Out-of-focus foreground; moves against the parallax so it reads as very close.
    drawForeground(themeId, palette, parallax)

    // 11. A last warm wash at dusk, over everything, tying the frame together. Flat on purpose:
    // a full-frame tint has no edge to band against.
    if (twilight > 0.01f) {
        drawRect(Color(0xFFFF9A55).copy(alpha = 0.08f * twilight))
    }

    // 12. Lights-out overlay, warm and soft rather than a flat black.
    if (lightsOff) drawLightsOutOverlay()
}

/**
 * The dark wash for a room with the light off. Kept as its own function because in pixel mode
 * it is drawn at full resolution *after* the blit, where a real radial gradient is smooth and
 * costs nothing; inside the 200px buffer the same gradient would need dithering.
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

// ------------------------------------------------------------------ ordered dither

/**
 * Bayer 8x8. The classic ordered-dither matrix: its values are spread so that any threshold
 * picks a set of pixels that is even in every direction, which is why it reads as texture
 * rather than as clumps or as stripes.
 */
private val BAYER_8 = intArrayOf(
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
)

/** Threshold in 0..1 for one art pixel. Depends on the grid only, so the pattern never moves. */
private fun bayer(x: Int, y: Int): Float =
    (BAYER_8[(y and 7) * 8 + (x and 7)] + 0.5f) / 64f

/** How far a seam between two tones is allowed to spread. Higher keeps more of the flat core. */
private const val SEAM_SHARPNESS = 4.5f

/** The buffer is authored at this height; everything else is measured in fractions of it. */
private const val ART_HEIGHT = 200f

/**
 * Width of one art pixel in the current draw space. It is 1 inside the pixel buffer and a few
 * device pixels when the scene is drawn at full resolution, which keeps the dither on the same
 * visual grid in both modes instead of dissolving into noise on a big canvas.
 */
private fun DrawScope.artPixel(): Float = max(1f, floor(size.height / ART_HEIGHT))

/** Snaps a coordinate onto the art grid. */
private fun snapTo(value: Float, cell: Float): Float = (value / cell).roundToInt() * cell

/**
 * One dithered scanline: [color] at [density] coverage between [left] and [right].
 * Runs of adjacent lit pixels are merged into a single rect, which is what keeps a full-screen
 * dither affordable at 60fps.
 */
private fun DrawScope.ditherRow(
    color: Color,
    left: Float,
    right: Float,
    gy: Int,
    density: Float,
    cell: Float,
) {
    if (density <= 0.004f || right <= left) return
    val gx0 = floor(left / cell).toInt()
    val gx1 = ceil(right / cell).toInt()
    if (gx1 <= gx0) return
    val y = gy * cell
    if (density >= 0.996f) {
        drawRect(color, Offset(gx0 * cell, y), Size((gx1 - gx0) * cell, cell))
        return
    }
    var gx = gx0
    while (gx < gx1) {
        if (bayer(gx, gy) < density) {
            var end = gx + 1
            while (end < gx1 && bayer(end, gy) < density) end++
            drawRect(color, Offset(gx * cell, y), Size((end - gx) * cell, cell))
            gx = end
        } else {
            gx++
        }
    }
}

/**
 * Fills [rect] with a vertical ramp from [topColor] to [bottomColor] as flat bands of quantised
 * colour whose seams are dithered together. This is the single most important function in the
 * file: at 200 pixels a real gradient bands, but two palette tones interleaved in a stable
 * pattern read as one soft colour that the eye blends for itself.
 *
 * [bands] defaults to a count derived from the height, which keeps the step size roughly
 * constant whatever the rect.
 */
internal fun DrawScope.drawDitheredVertical(
    topColor: Color,
    bottomColor: Color,
    rect: Rect,
    bands: Int = 0,
) {
    val cell = artPixel()
    val gy0 = floor(rect.top / cell).toInt()
    val gy1 = ceil(rect.bottom / cell).toInt()
    val rows = gy1 - gy0
    if (rows <= 0 || rect.width <= 0f) return
    val gx0 = floor(rect.left / cell).toInt()
    val gx1 = ceil(rect.right / cell).toInt()
    val x = gx0 * cell
    val width = (gx1 - gx0) * cell

    val steps = if (bands > 0) bands.coerceIn(2, 8) else (rows / 26 + 3).coerceIn(3, 6)
    val ramp = List(steps) { lerp(topColor, bottomColor, it / (steps - 1f)) }

    for (gy in gy0 until gy1) {
        val t = (gy - gy0 + 0.5f) / rows * (steps - 1)
        val low = floor(t).toInt().coerceIn(0, steps - 2)
        val frac = t - low
        drawRect(ramp[low], Offset(x, gy * cell), Size(width, cell))
        val mix = ((frac - 0.5f) * SEAM_SHARPNESS + 0.5f).coerceIn(0f, 1f)
        if (mix > 0.004f) ditherRow(ramp[low + 1], rect.left, rect.right, gy, mix, cell)
    }
}

/**
 * A wash that is strongest at one edge of [rect] and dissolves to nothing at the other, built
 * from [bands] stacked translucent slabs whose trailing rows fade out through the dither.
 * Used for light, warmth and shadow, where the shape matters more than the exact tone.
 */
private fun DrawScope.drawDitherFadeOut(
    color: Color,
    rect: Rect,
    alpha: Float,
    bands: Int = 3,
    fromTop: Boolean = true,
) {
    val strength = alpha.coerceIn(0f, 1f)
    if (strength <= 0.01f) return
    val cell = artPixel()
    val gy0 = floor(rect.top / cell).toInt()
    val gy1 = ceil(rect.bottom / cell).toInt()
    val rows = gy1 - gy0
    if (rows <= 0 || rect.width <= 0f) return
    val gx0 = floor(rect.left / cell).toInt()
    val gx1 = ceil(rect.right / cell).toInt()
    val x = gx0 * cell
    val width = (gx1 - gx0) * cell

    val n = bands.coerceIn(1, 5)
    // n identical layers compose to `strength`, so each one is thin enough that its own edge is
    // barely visible even before the dither softens it.
    val layer = color.copy(alpha = 1f - (1f - strength).pow(1f / n))
    val seam = (rows / (n * 4)).coerceIn(1, 6)

    for (i in 1..n) {
        val span = rows * i / n
        val solid = max(0, span - seam)
        if (fromTop) {
            if (solid > 0) drawRect(layer, Offset(x, gy0 * cell), Size(width, solid * cell))
            for (k in 0 until seam) {
                val gy = gy0 + solid + k
                if (gy >= gy1) break
                ditherRow(layer, rect.left, rect.right, gy, 1f - (k + 0.5f) / seam, cell)
            }
        } else {
            if (solid > 0) drawRect(layer, Offset(x, (gy1 - solid) * cell), Size(width, solid * cell))
            for (k in 0 until seam) {
                val gy = gy1 - solid - 1 - k
                if (gy < gy0) break
                ditherRow(layer, rect.left, rect.right, gy, 1f - (k + 0.5f) / seam, cell)
            }
        }
    }
}

// ------------------------------------------------------------------ ambient occlusion

/** Where the wall meets the floor. Two or three dithered bands, never a gradient. */
private fun DrawScope.drawContactShadow(palette: RoomPalette, horizon: Float, cell: Float) {
    val w = size.width
    val h = size.height
    // Light does not reach the last few pixels of wall above the floor...
    drawDitherFadeOut(
        color = palette.shadow,
        rect = Rect(0f, horizon - h * 0.060f, w, horizon),
        alpha = 0.30f,
        bands = 3,
        fromTop = false,
    )
    // ...and the floor is darkest where it tucks under the wall.
    drawDitherFadeOut(
        color = palette.shadow,
        rect = Rect(0f, horizon, w, horizon + h * 0.040f),
        alpha = 0.38f,
        bands = 3,
        fromTop = true,
    )
    // One hard line: a shadow that is soft everywhere reads as fog rather than as a corner.
    drawRect(lerp(palette.shadow, palette.floorShade, 0.30f), Offset(0f, horizon - cell), Size(w, cell))
}

/**
 * The dark patch a prop sits in. Three dithered bands of shrinking width, so it reads as a soft
 * pool without ever being a gradient.
 */
private fun DrawScope.drawPropShadow(
    centerX: Float,
    baseY: Float,
    width: Float,
    palette: RoomPalette,
    strength: Float = 1f,
) {
    if (width <= 0f || strength <= 0.02f) return
    val cell = artPixel()
    val color = palette.shadow.copy(alpha = 0.45f * strength.coerceIn(0f, 1f))
    val rowsPerBand = max(1, (size.height * 0.006f / cell).roundToInt())
    val gTop = floor((baseY - cell) / cell).toInt()
    floatArrayOf(0.90f, 0.55f, 0.24f).forEachIndexed { band, density ->
        val span = width * (1f - band * 0.20f)
        val left = snapTo(centerX - span / 2f, cell)
        repeat(rowsPerBand) { r ->
            ditherRow(color, left, left + span, gTop + band * rowsPerBand + r, density, cell)
        }
    }
}

/** Floorboard seams, on whole pixels and only two tones so they never read as scan lines. */
private fun DrawScope.drawFloorboards(palette: RoomPalette, horizon: Float, cell: Float) {
    val w = size.width
    val h = size.height
    val seam = lerp(palette.floorShade, palette.shadow, 0.35f).copy(alpha = 0.55f)
    val lit = palette.highlight.copy(alpha = 0.10f)
    for (i in 1..4) {
        // Boards get further apart toward the viewer, which is all the perspective this needs.
        val t = (i / 5f).pow(0.78f)
        val y = snapTo(horizon + (h - horizon) * t, cell)
        drawRect(seam, Offset(0f, y), Size(w, cell))
        drawRect(lit, Offset(0f, y + cell), Size(w, cell))
    }
}

// ------------------------------------------------------------------ sky & light

private fun DrawScope.drawWindow(
    themeId: String,
    palette: RoomPalette,
    night: Float,
    twilight: Float,
    time: Float,
    parallax: Float,
) {
    val cell = artPixel()
    val w = size.width
    val h = size.height
    val winW = snapTo(w * 0.34f, cell)
    val winH = snapTo(h * 0.26f, cell)
    val left = snapTo(w * 0.60f + parallax * w * 0.02f, cell)
    val top = snapTo(h * 0.12f, cell)
    val pane = Rect(left, top, left + winW, top + winH)

    // The sky is built from the room's own ramp — a light step above, the wall tone mixed in
    // below — so the outside belongs to the same picture as the inside.
    val skyHigh = lerp(palette.sky, palette.highlight, 0.24f)
    val skyLow = lerp(palette.sky, palette.wallBottom, 0.32f)
    drawDitheredVertical(skyHigh, skyLow, pane, bands = 3)

    if (twilight > 0.01f) {
        drawDitherFadeOut(Color(0xFFFFB271), pane, 0.55f * twilight, bands = 3, fromTop = false)
    }

    // Sun and moon cross-fade instead of swapping, and ride opposite ends of the same arc.
    val sunAlpha = smoothStep(0.62f, 0.34f, night)
    val moonAlpha = smoothStep(0.38f, 0.66f, night)
    if (sunAlpha > 0.01f) {
        val p = (1f - night) * 0.5f + 0.25f
        val bx = snapTo(left + winW * p, cell)
        val by = snapTo(top + winH * (0.58f - sin(p * PI.toFloat()) * 0.34f), cell)
        val low = 1f - sunAlpha
        // The disc reddens as it sinks, which is most of the dusk read.
        val core = lerp(Color(0xFFFFF3C4), Color(0xFFFFA94F), low)
        drawCircle(core.copy(alpha = 0.24f * sunAlpha), snapTo(winH * 0.30f, cell), Offset(bx, by))
        drawCircle(core.copy(alpha = sunAlpha), snapTo(winH * 0.17f, cell), Offset(bx, by))
        drawCircle(
            palette.highlight.copy(alpha = 0.55f * sunAlpha),
            snapTo(winH * 0.08f, cell),
            Offset(bx - cell, by - cell),
        )
    }
    if (moonAlpha > 0.01f) {
        val p = night * 0.5f + 0.25f
        val bx = snapTo(left + winW * p, cell)
        val by = snapTo(top + winH * (0.58f - sin(p * PI.toFloat()) * 0.34f), cell)
        val disc = lerp(palette.highlight, Color(0xFFF4F6FF), 0.5f)
        drawCircle(disc.copy(alpha = 0.18f * moonAlpha), snapTo(winH * 0.24f, cell), Offset(bx, by))
        drawCircle(disc.copy(alpha = moonAlpha), snapTo(winH * 0.14f, cell), Offset(bx, by))
        // Crater bite, drawn in sky colour so the moon keeps a crescent silhouette.
        drawCircle(
            skyHigh.copy(alpha = moonAlpha * 0.9f),
            snapTo(winH * 0.10f, cell),
            Offset(bx + snapTo(winH * 0.07f, cell), by - snapTo(winH * 0.04f, cell)),
        )

        val random = Random(themeId.hashCode())
        repeat(14) {
            val sx = snapTo(left + random.nextFloat() * winW, cell)
            val sy = snapTo(top + random.nextFloat() * winH * 0.8f, cell)
            // Only the brightness moves; the star itself is nailed to the grid.
            val twinkle = 0.4f + 0.6f * ((sin(time * 2f + sx) + 1f) / 2f)
            drawRect(
                palette.highlight.copy(alpha = twinkle * moonAlpha),
                Offset(sx, sy),
                Size(cell, cell),
            )
        }
    }

    // Clouds only in the rooms that have a real sky.
    if (themeId != "room_space" && themeId != "room_arcade") {
        val cloudAlpha = (0.20f + 0.55f * (1f - night)) * (1f - moonAlpha * 0.5f)
        drawWindowClouds(palette, left, top, winW, winH, time, cloudAlpha, twilight, cell)
    }

    // Frame in three tones of one wood, lit from the top left like everything else.
    val frameMid = lerp(palette.floorShade, palette.shadow, 0.45f)
    val frameLight = lerp(frameMid, palette.highlight, 0.35f)
    val frameDark = lerp(frameMid, palette.shadow, 0.55f)
    val bar = max(cell, snapTo(w * 0.013f, cell))
    drawRect(frameLight, Offset(left - bar, top - bar), Size(winW + bar * 2, bar))
    drawRect(frameMid, Offset(left - bar, top), Size(bar, winH))
    drawRect(frameDark, Offset(left + winW, top), Size(bar, winH))
    drawRect(frameDark, Offset(left - bar, top + winH), Size(winW + bar * 2, bar))
    // Mullions.
    drawRect(frameMid, Offset(snapTo(left + winW / 2f - bar / 2f, cell), top), Size(bar, winH))
    drawRect(frameMid, Offset(left, snapTo(top + winH / 2f - bar / 2f, cell)), Size(winW, bar))
    // Sill, with its lit edge and the shadow it casts on the wall.
    val sillH = max(cell, snapTo(h * 0.016f, cell))
    val sillY = top + winH + bar
    drawRect(frameMid, Offset(left - bar * 2, sillY), Size(winW + bar * 4, sillH))
    drawRect(frameLight, Offset(left - bar * 2, sillY), Size(winW + bar * 4, cell))
    drawPropShadow(left + winW / 2f, sillY + sillH, winW * 1.15f, palette, strength = 0.7f)
}

/** Three fat cloud blobs scrolling through the pane, clipped by hand since we never clipRect. */
private fun DrawScope.drawWindowClouds(
    palette: RoomPalette,
    left: Float,
    top: Float,
    winW: Float,
    winH: Float,
    time: Float,
    alpha: Float,
    twilight: Float,
    cell: Float,
) {
    if (alpha <= 0.02f) return
    // Clouds take the room's highlight, warmed at dusk: white clouds are the fastest way to
    // knock a soft palette out of balance.
    val body = lerp(palette.highlight, Color(0xFFFFC79B), twilight * 0.55f)
    val shade = lerp(body, palette.wallBottom, 0.30f)
    repeat(3) { i ->
        val speed = 0.020f + i * 0.008f
        val span = winW + winW * 0.6f
        val cx = snapTo(left - winW * 0.3f + wrap01(time * speed + i * 0.37f) * span, cell)
        val cy = snapTo(top + winH * (0.20f + i * 0.17f), cell)
        val rx = snapTo(winW * (0.16f - i * 0.02f), cell)
        val ry = max(cell, snapTo(rx * 0.52f, cell))
        // Fade at the pane's edges so the hand-clip never pops.
        val edge = min(cx - left, left + winW - cx) / (winW * 0.22f)
        val a = alpha * edge.coerceIn(0f, 1f)
        if (a <= 0.02f) return@repeat
        clampedOval(cx - rx * 0.7f, cy + ry * 0.35f, rx * 0.7f, ry * 0.75f, shade.copy(alpha = a), left, left + winW)
        clampedOval(cx + rx * 0.65f, cy + ry * 0.30f, rx * 0.6f, ry * 0.70f, shade.copy(alpha = a), left, left + winW)
        clampedOval(cx, cy, rx, ry, body.copy(alpha = a), left, left + winW)
    }
}

/**
 * The wedge of daylight the window throws down the wall and across the floor.
 *
 * The patch is tinted toward the *sky* colour rather than simply brightened. Light that comes
 * from outside carries the colour of outside with it, and that one change is the difference
 * between a warm afternoon and a torch pointed at the carpet.
 */
private fun DrawScope.drawWindowLight(
    palette: RoomPalette,
    daylight: Float,
    twilight: Float,
    horizon: Float,
    parallax: Float,
) {
    val cell = artPixel()
    val w = size.width
    val h = size.height
    val winW = snapTo(w * 0.34f, cell)
    val left = snapTo(w * 0.60f + parallax * w * 0.02f, cell)
    val topY = snapTo(h * 0.12f + h * 0.26f, cell)
    val warm = lerp(palette.sky, Color(0xFFFFE9BC), 0.40f + 0.35f * twilight)

    val gy0 = (topY / cell).toInt()
    val gy1 = ceil(h / cell).toInt()
    val rows = gy1 - gy0
    if (rows <= 0) return
    // Four flat slabs down the wedge, each two rows shorter than it looks: those two rows are
    // dithered into the slab below, which is what dissolves the step between them.
    val slabs = 4
    val seam = 2
    for (gy in gy0 until gy1) {
        val t = (gy - gy0).toFloat() / rows
        val onFloor = gy * cell >= horizon
        val l = max(0f, left - t * w * 0.34f)
        val r = min(w, left + winW - t * w * 0.06f)
        val slab = (t * slabs).toInt().coerceIn(0, slabs - 1)
        val fade = 1f - slab / slabs.toFloat()
        // The wall only catches a glancing amount; the floor is where the light pools.
        val strength = daylight * fade * (if (onFloor) 0.42f else 0.20f)
        val intoSlab = gy - gy0 - rows * slab / slabs
        val density = if (intoSlab < seam && slab > 0) 0.5f else 1f
        ditherRow(warm.copy(alpha = strength.coerceIn(0f, 0.55f)), l, r, gy, density, cell)
    }
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
    val cell = artPixel()
    val w = size.width
    val h = size.height
    val px = snapTo(parallax * w * 0.02f, cell)

    when (themeId) {
        "room_beach" -> {
            // A sun that sinks with the clock, then the sea rolling in front of it.
            val sunY = snapTo(horizon - h * 0.22f + night * h * 0.24f, cell)
            val sunX = snapTo(w * 0.50f, cell) + px
            val sunAlpha = (1f - smoothStep(0.55f, 0.95f, night)).coerceIn(0f, 1f)
            if (sunAlpha > 0.02f) {
                drawCircle(palette.propAccent.copy(alpha = 0.26f * sunAlpha), snapTo(h * 0.16f, cell), Offset(sunX, sunY))
                drawCircle(lerp(palette.propAccent, palette.highlight, 0.55f).copy(alpha = sunAlpha), snapTo(h * 0.085f, cell), Offset(sunX, sunY))
            }
            // Water: the same two-tone dither as the wall, so the sea is part of the ramp.
            val seaTop = snapTo(horizon - h * 0.13f, cell)
            drawDitheredVertical(
                lerp(palette.prop, palette.highlight, 0.22f),
                palette.propShade,
                Rect(0f, seaTop, w, horizon),
                bands = 3,
            )
            // Sun glitter, wide and blocky so it survives the downsample.
            if (sunAlpha > 0.02f) {
                repeat(3) { row ->
                    val y = snapTo(seaTop + (horizon - seaTop) * (0.25f + row * 0.25f), cell)
                    val width = snapTo(w * (0.16f - row * 0.03f) * (0.7f + 0.3f * sin(time * 2f + row)), cell)
                    drawRect(
                        palette.highlight.copy(alpha = 0.40f * sunAlpha),
                        Offset(sunX - width / 2f, y),
                        Size(width, cell * 2f),
                    )
                }
            }
            // Rolling crests: three rows scrolling at different speeds, each a two-tone chip.
            repeat(3) { row ->
                val y = snapTo(seaTop + (horizon - seaTop) * (0.28f + row * 0.26f), cell)
                val speed = 0.05f + row * 0.035f
                val spacing = snapTo(w * (0.26f - row * 0.04f), cell)
                if (spacing <= 0f) return@repeat
                val scroll = snapTo(wrap01(time * speed) * spacing, cell)
                var x = -spacing + scroll
                val crest = palette.highlight.copy(alpha = 0.60f - row * 0.14f)
                val under = palette.propShade.copy(alpha = 0.35f)
                while (x < w + spacing) {
                    val bob = snapTo(sin(time * 1.6f + x * 0.02f) * h * 0.005f, cell)
                    val len = snapTo(spacing * 0.45f, cell)
                    drawRect(crest, Offset(snapTo(x, cell), y + bob), Size(len, cell))
                    drawRect(under, Offset(snapTo(x + cell, cell), y + bob + cell), Size(len, cell))
                    x += spacing
                }
            }
        }
        "room_space" -> {
            // Constellations: fixed seeded points, only the brightness moves.
            val random = Random(991)
            repeat(22) { i ->
                val sx = snapTo(random.nextFloat() * w, cell)
                val sy = snapTo(random.nextFloat() * horizon * 0.92f, cell)
                val big = i % 7 == 0
                val twinkle = 0.35f + 0.65f * ((sin(time * 1.7f + i * 1.9f) + 1f) / 2f)
                val s = if (big) cell * 2f else cell
                drawRect(palette.highlight.copy(alpha = 0.35f + 0.55f * twinkle), Offset(sx, sy), Size(s, s))
                if (big) {
                    drawCircle(palette.propAccent.copy(alpha = 0.22f * twinkle), s * 2.2f, Offset(sx + cell / 2f, sy + cell / 2f))
                }
            }
            // A comet crossing on a long loop, tail made of shrinking blocks.
            val t = wrap01(time * 0.035f)
            val cx = snapTo(-w * 0.15f + t * w * 1.3f, cell)
            val cy = snapTo(horizon * 0.12f + t * horizon * 0.30f, cell)
            repeat(5) { i ->
                val f = i / 5f
                val tone = lerp(palette.highlight, palette.propAccent, f)
                drawCircle(
                    tone.copy(alpha = (1f - f) * 0.7f),
                    max(cell, snapTo(h * 0.018f * (1f - f * 0.7f), cell)),
                    Offset(snapTo(cx - f * w * 0.14f, cell), snapTo(cy - f * horizon * 0.03f, cell)),
                )
            }
        }
        "room_forest" -> {
            // Far canopy: overlapping blobs, darker than the wall so the near trees pop off it.
            val sway = snapTo(sin(time * 0.5f) * w * 0.008f, cell)
            val far = lerp(palette.wallBottom, palette.shadow, 0.30f)
            repeat(7) { i ->
                val x = snapTo(w * (i / 6f), cell)
                drawCircle(
                    far,
                    snapTo(h * 0.16f, cell),
                    Offset(x + sway * (if (i % 2 == 0) 1f else -1f), snapTo(horizon - h * 0.30f, cell)),
                )
            }
            // A lit rim along the top of the mass, in the accent, so the canopy has a light source.
            repeat(7) { i ->
                val x = snapTo(w * (i / 6f), cell)
                drawCircle(
                    lerp(far, palette.propAccent, 0.22f),
                    snapTo(h * 0.16f, cell),
                    Offset(x + sway * (if (i % 2 == 0) 1f else -1f), snapTo(horizon - h * 0.31f, cell) - cell * 2f),
                )
            }
        }
        "room_arcade" -> {
            // Marquee bulb strip running along the top of the wall.
            val bulbs = 11
            val step = w / bulbs
            val phase = floor(time * 4f).toInt()
            val y = snapTo(h * 0.045f, cell)
            repeat(bulbs) { i ->
                val lit = ((i + phase) % 3) == 0
                val x = snapTo(step * (i + 0.5f), cell)
                if (lit) {
                    drawCircle(palette.propAccent.copy(alpha = 0.22f), snapTo(h * 0.042f, cell), Offset(x, y))
                    drawCircle(palette.propAccent, snapTo(h * 0.018f, cell), Offset(x, y))
                    drawRect(palette.highlight.copy(alpha = 0.7f), Offset(x - cell, y - cell), Size(cell, cell))
                } else {
                    drawCircle(palette.propShade, snapTo(h * 0.018f, cell), Offset(x, y))
                }
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
    val cell = artPixel()
    val w = size.width
    val h = size.height
    val px = snapTo(parallax * w * 0.04f, cell)

    when (themeId) {
        "room_beach" -> {
            val palmX = snapTo(w * 0.16f, cell) + px
            drawPropShadow(palmX, horizon, h * 0.14f, palette)
            drawPalm(Offset(palmX, horizon), snapTo(h * 0.30f, cell), sin(time * 0.7f) * 5f, palette, cell)

            val ballX = snapTo(w * 0.82f, cell) + px
            val ballY = snapTo(horizon - h * 0.03f + floatOffset(time, 0.6f, h * 0.012f), cell)
            val r = snapTo(h * 0.035f, cell)
            drawPropShadow(ballX, horizon, r * 2.2f, palette, strength = 0.8f)
            drawCircle(palette.propShade, r, Offset(ballX, ballY))
            drawCircle(palette.prop, r, Offset(ballX - cell, ballY - cell))
            drawArc(
                color = palette.highlight,
                startAngle = 200f, sweepAngle = 130f, useCenter = false,
                topLeft = Offset(ballX - r, ballY - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = max(cell, r * 0.30f)),
            )
            drawRect(palette.propAccent, Offset(ballX - cell, ballY - r), Size(cell * 2f, r * 0.8f))
        }
        "room_space" -> {
            // Slowly orbiting planet: lit crescent, dark body, accent ring.
            val center = Offset(snapTo(w * 0.22f, cell) + px, snapTo(h * 0.22f, cell))
            val r = snapTo(h * 0.070f, cell)
            drawCircle(palette.propShade, r, center)
            drawCircle(palette.prop, r, Offset(center.x - cell, center.y - cell))
            drawCircle(lerp(palette.prop, palette.highlight, 0.5f), r * 0.45f, Offset(center.x - r * 0.35f, center.y - r * 0.35f))
            rotate(quantiseAngle(time * 12f), center) {
                drawOval(
                    color = palette.propAccent.copy(alpha = 0.85f),
                    topLeft = Offset(center.x - snapTo(h * 0.12f, cell), center.y - snapTo(h * 0.022f, cell)),
                    size = Size(snapTo(h * 0.24f, cell), snapTo(h * 0.044f, cell)),
                    style = Stroke(width = max(cell, h * 0.008f)),
                )
            }
            // Satellite: three tones on a whole-pixel track.
            val satX = snapTo(wrap01(time * 0.05f) * w, cell)
            val satY = snapTo(h * 0.40f, cell)
            drawRect(palette.propShade, Offset(satX, satY), Size(snapTo(w * 0.03f, cell), cell * 3f))
            drawRect(palette.prop, Offset(satX, satY), Size(snapTo(w * 0.03f, cell), cell))
            drawRect(palette.propAccent, Offset(satX + snapTo(w * 0.03f, cell), satY), Size(cell, cell * 2f))
        }
        "room_forest" -> {
            val nearX = snapTo(w * 0.12f, cell) + px
            val farX = snapTo(w * 0.30f, cell) + snapTo(px * 0.6f, cell)
            drawPropShadow(farX, horizon, h * 0.13f, palette, strength = 0.7f)
            drawTree(Offset(farX, horizon), snapTo(h * 0.24f, cell), palette, sin(time * 0.8f + 1.2f) * 3.0f, cell, far = true)
            drawPropShadow(nearX, horizon, h * 0.18f, palette)
            drawTree(Offset(nearX, horizon), snapTo(h * 0.34f, cell), palette, sin(time * 0.8f) * 3.5f, cell, far = false)
            drawFallingLeaves(time, horizon, palette, cell)
            // Fireflies drifting in slow loops: accent core, accent halo, nothing else.
            repeat(6) { i ->
                val t = time * 0.35f + i
                val fx = snapTo(w * (0.35f + 0.5f * ((sin(t) + 1f) / 2f)), cell)
                val fy = snapTo(horizon - h * (0.05f + 0.18f * ((cos(t * 0.8f) + 1f) / 2f)), cell)
                drawCircle(palette.propAccent.copy(alpha = 0.30f), snapTo(h * 0.016f, cell), Offset(fx, fy))
                drawRect(palette.propAccent, Offset(fx, fy), Size(cell, cell))
            }
        }
        "room_arcade" -> {
            listOf(0.12f, 0.30f).forEachIndexed { index, x ->
                val cx = snapTo(w * x, cell) + px
                val cabW = snapTo(w * 0.14f, cell)
                val cabH = snapTo(h * 0.30f, cell)
                val top = horizon - cabH
                drawPropShadow(cx + cabW / 2f, horizon, cabW * 1.2f, palette)
                // Body, lit left edge, shaded right edge: the same three tones as every prop.
                drawRect(palette.prop, Offset(cx, top), Size(cabW, cabH))
                drawRect(lerp(palette.prop, palette.highlight, 0.35f), Offset(cx, top), Size(cell * 2f, cabH))
                drawRect(palette.propShade, Offset(cx + cabW - cell * 2f, top), Size(cell * 2f, cabH))
                // Screen recessed into the cabinet.
                val scX = snapTo(cx + w * 0.02f, cell)
                val scY = snapTo(top + cabH * 0.14f, cell)
                val scW = snapTo(w * 0.10f, cell)
                val scH = snapTo(cabH * 0.34f, cell)
                drawRect(palette.shadow, Offset(scX - cell, scY - cell), Size(scW + cell * 2f, scH + cell * 2f))
                drawRect(lerp(palette.shadow, palette.sky, 0.45f), Offset(scX, scY), Size(scW, scH))
                // Attract-mode blocks jittering behind the glass, on whole pixels.
                repeat(3) { r ->
                    val bx = snapTo(scX + (scW - w * 0.02f) * wrap01(time * (0.25f + r * 0.1f) + r * 0.3f), cell)
                    drawRect(
                        palette.propAccent.copy(alpha = 0.85f),
                        Offset(bx, snapTo(scY + scH * (0.18f + r * 0.28f), cell)),
                        Size(snapTo(w * 0.02f, cell), max(cell, snapTo(h * 0.014f, cell))),
                    )
                }
                // Marquee: quantised blink so it pulses in steps rather than breathing.
                val blink = ((sin(time * 3f + index) + 1f) / 2f)
                drawRect(palette.propAccent.copy(alpha = 0.45f + (blink * 4f).toInt() / 4f * 0.55f), Offset(cx, top), Size(cabW, cell * 2f))
                drawRect(palette.highlight.copy(alpha = 0.5f), Offset(cx, top), Size(cabW, cell))
            }
        }
        else -> {
            // Cozy room: a rug, a picture frame and a potted plant.
            val rugY = snapTo(horizon + h * 0.10f, cell)
            drawOval(
                color = lerp(palette.propAccent, palette.floorShade, 0.30f).copy(alpha = 0.85f),
                topLeft = Offset(snapTo(w * 0.22f, cell), rugY),
                size = Size(snapTo(w * 0.56f, cell), snapTo(h * 0.14f, cell)),
            )
            drawOval(
                color = palette.propAccent.copy(alpha = 0.9f),
                topLeft = Offset(snapTo(w * 0.25f, cell), rugY + cell * 2f),
                size = Size(snapTo(w * 0.50f, cell), snapTo(h * 0.10f, cell)),
            )
            drawOval(
                color = lerp(palette.prop, palette.propAccent, 0.35f),
                topLeft = Offset(snapTo(w * 0.34f, cell), rugY + cell * 4f),
                size = Size(snapTo(w * 0.32f, cell), snapTo(h * 0.06f, cell)),
            )

            // Framed picture: mount, frame, and a hint of a landscape in the room's own tones.
            val fx = snapTo(w * 0.10f, cell) + px
            val fy = snapTo(h * 0.16f, cell)
            val fw = snapTo(w * 0.16f, cell)
            val fh = snapTo(h * 0.14f, cell)
            val wood = lerp(palette.floorShade, palette.shadow, 0.40f)
            drawRect(wood, Offset(fx - cell * 2f, fy - cell * 2f), Size(fw + cell * 4f, fh + cell * 4f))
            drawRect(lerp(wood, palette.highlight, 0.35f), Offset(fx - cell * 2f, fy - cell * 2f), Size(fw + cell * 4f, cell))
            drawRect(palette.prop, Offset(fx, fy), Size(fw, fh))
            drawRect(lerp(palette.sky, palette.prop, 0.35f), Offset(fx, fy), Size(fw, snapTo(fh * 0.55f, cell)))
            drawRect(lerp(palette.propAccent, palette.prop, 0.45f), Offset(fx, snapTo(fy + fh * 0.55f, cell)), Size(fw, cell * 2f))
            drawPropShadow(fx + fw / 2f, fy + fh + cell * 3f, fw * 1.1f, palette, strength = 0.6f)

            val potX = snapTo(w * 0.86f, cell) + px
            drawPropShadow(potX, horizon, h * 0.12f, palette)
            drawPot(Offset(potX, horizon), snapTo(h * 0.16f, cell), palette, cell)
        }
    }
}

/** Full-frame theme treatment applied after the room is built. */
private fun DrawScope.drawThemeOverlay(themeId: String, palette: RoomPalette, time: Float) {
    if (themeId != "room_arcade") return
    val cell = artPixel()
    val w = size.width
    val h = size.height
    // Scan lines: one art pixel dark, spaced so they read as texture rather than as stripes.
    val step = cell * 3f
    var y = 0f
    while (y < h) {
        drawRect(palette.shadow.copy(alpha = 0.16f), Offset(0f, y), Size(w, cell))
        y += step
    }
    // Rolling refresh band, on whole pixels so its edge does not crawl.
    val bandY = snapTo(wrap01(time * 0.28f) * h, cell)
    drawRect(palette.highlight.copy(alpha = 0.05f), Offset(0f, bandY), Size(w, snapTo(h * 0.08f, cell)))
    // Mains-hum flicker: two detuned sines so the beat never looks periodic.
    val flicker = 0.024f + 0.016f * sin(time * 31f) + 0.010f * sin(time * 7.3f)
    drawRect(palette.propAccent.copy(alpha = flicker.coerceIn(0f, 0.06f)))
}

/**
 * The very front of the frame: one big soft shape that shifts *against* the parallax, which is
 * what makes the room behind it feel deep. The out-of-focus edge is a dithered fade, not a
 * stack of alpha bands, so it dissolves instead of stepping.
 */
private fun DrawScope.drawForeground(themeId: String, palette: RoomPalette, parallax: Float) {
    val cell = artPixel()
    val w = size.width
    val h = size.height
    val fx = snapTo(-parallax * w * 0.06f, cell)
    val top = snapTo(h * 0.885f, cell)

    // Every foreground is the room's own shadow step plus its own highlight: the near shape is
    // in shade by definition, so it never needs a colour of its own.
    val base = when (themeId) {
        "room_beach" -> lerp(palette.floorShade, palette.shadow, 0.35f)
        "room_forest" -> lerp(palette.wallBottom, palette.shadow, 0.55f)
        else -> lerp(palette.floorShade, palette.shadow, 0.60f)
    }
    val trim = lerp(base, palette.highlight, 0.40f)

    // Soft edge: the top few rows dissolve upward into the floor.
    drawDitherFadeOut(
        color = base,
        rect = Rect(0f, top - h * 0.035f, w, top),
        alpha = 0.85f,
        bands = 3,
        fromTop = false,
    )

    when (themeId) {
        "room_beach" -> {
            drawOval(base, topLeft = Offset(fx - w * 0.25f, top), size = Size(w * 1.5f, h * 0.30f))
            drawRect(trim.copy(alpha = 0.45f), Offset(0f, top), Size(w, cell))
        }
        "room_space", "room_arcade" -> {
            // Console lip with a glowing strip and two chunky buttons.
            drawRect(base, Offset(0f, top), Size(w, h - top))
            drawRect(palette.propAccent.copy(alpha = 0.55f), Offset(0f, top), Size(w, cell * 2f))
            drawRect(trim.copy(alpha = 0.35f), Offset(0f, top + cell * 2f), Size(w, cell))
            repeat(2) { i ->
                val bx = snapTo(fx + w * (0.24f + i * 0.52f), cell)
                val by = snapTo(top + h * 0.055f, cell)
                drawCircle(palette.propShade, snapTo(h * 0.022f, cell), Offset(bx, by))
                drawCircle(palette.propAccent.copy(alpha = 0.85f), snapTo(h * 0.018f, cell), Offset(bx, by))
                drawCircle(palette.highlight.copy(alpha = 0.5f), snapTo(h * 0.008f, cell), Offset(bx - cell, by - cell))
            }
        }
        "room_forest" -> {
            drawOval(base, topLeft = Offset(fx - w * 0.20f, top), size = Size(w * 1.4f, h * 0.30f))
            repeat(3) { i ->
                drawOval(
                    trim.copy(alpha = 0.50f),
                    topLeft = Offset(snapTo(fx + w * (0.12f + i * 0.34f), cell), top - snapTo(h * 0.018f, cell)),
                    size = Size(snapTo(w * 0.10f, cell), snapTo(h * 0.045f, cell)),
                )
            }
        }
        else -> {
            drawOval(base, topLeft = Offset(fx - w * 0.30f, top), size = Size(w * 1.6f, h * 0.30f))
            drawRect(palette.propAccent.copy(alpha = 0.35f), Offset(0f, top), Size(w, cell))
        }
    }
}

// ------------------------------------------------------------------ props

/** Palm in three tones: trunk from the floor ramp, fronds from sea + sand so they stay in family. */
private fun DrawScope.drawPalm(
    base: Offset,
    height: Float,
    sway: Float,
    palette: RoomPalette,
    cell: Float,
) {
    val trunkDark = lerp(palette.floorShade, palette.shadow, 0.45f)
    val trunkLight = lerp(palette.floorShade, palette.highlight, 0.30f)
    val trunk = Path().apply {
        moveTo(base.x - height * 0.05f, base.y)
        quadraticBezierTo(base.x + height * 0.10f, base.y - height * 0.5f, base.x + height * 0.02f, base.y - height)
        lineTo(base.x + height * 0.10f, base.y - height)
        quadraticBezierTo(base.x + height * 0.18f, base.y - height * 0.5f, base.x + height * 0.06f, base.y)
        close()
    }
    drawPath(trunk, trunkDark)
    drawPath(trunk, trunkLight, style = Stroke(width = cell))

    // Sea plus sand makes a muted sage: a true leaf green would be the only green in the room.
    val frond = lerp(palette.prop, palette.floor, 0.45f)
    val frondShade = lerp(frond, palette.propShade, 0.45f)
    val frondLight = lerp(frond, palette.highlight, 0.30f)
    val crown = Offset(snapTo(base.x + height * 0.06f, cell), snapTo(base.y - height, cell))
    repeat(5) { i ->
        val angle = quantiseAngle(-160f + i * 55f + sway)
        rotate(angle, crown) {
            drawOval(frondShade, topLeft = Offset(crown.x, crown.y - height * 0.05f), size = Size(height * 0.46f, height * 0.13f))
            drawOval(frond, topLeft = Offset(crown.x, crown.y - height * 0.06f), size = Size(height * 0.44f, height * 0.11f))
            drawOval(frondLight, topLeft = Offset(crown.x + height * 0.04f, crown.y - height * 0.06f), size = Size(height * 0.22f, height * 0.04f))
        }
    }
    drawCircle(lerp(palette.propAccent, palette.shadow, 0.25f), cell * 2f, Offset(crown.x, crown.y + cell))
}

private fun DrawScope.drawTree(
    base: Offset,
    height: Float,
    palette: RoomPalette,
    sway: Float,
    cell: Float,
    far: Boolean,
) {
    val trunk = lerp(palette.floor, palette.shadow, if (far) 0.55f else 0.35f)
    val trunkLight = lerp(trunk, palette.highlight, 0.22f)
    val trunkW = max(cell * 2f, snapTo(height * 0.10f, cell))
    val trunkTop = snapTo(base.y - height * 0.45f, cell)
    drawRect(trunk, Offset(snapTo(base.x - trunkW / 2f, cell), trunkTop), Size(trunkW, base.y - trunkTop))
    drawRect(trunkLight, Offset(snapTo(base.x - trunkW / 2f, cell), trunkTop), Size(cell, base.y - trunkTop))

    // Three tones of one green, pivoted at the trunk top so the canopy leans as one piece.
    val mid = if (far) lerp(palette.prop, palette.propShade, 0.45f) else palette.prop
    val dark = lerp(mid, palette.propShade, 0.65f)
    val light = lerp(mid, palette.highlight, 0.30f)
    rotate(quantiseAngle(sway), Offset(base.x, trunkTop)) {
        listOf(0.45f to 0.34f, 0.62f to 0.27f, 0.78f to 0.19f).forEach { (offsetY, radius) ->
            val cx = snapTo(base.x, cell)
            val cy = snapTo(base.y - height * offsetY - height * 0.10f, cell)
            val r = snapTo(height * radius, cell)
            drawCircle(dark, r, Offset(cx, cy + cell))
            drawCircle(mid, r, Offset(cx, cy))
            drawCircle(light, r * 0.45f, Offset(cx - r * 0.35f, cy - r * 0.35f))
        }
    }
}

/** Leaves falling in fixed lanes; seeded once per lane so nothing jitters between frames. */
private fun DrawScope.drawFallingLeaves(time: Float, horizon: Float, palette: RoomPalette, cell: Float) {
    val w = size.width
    val h = size.height
    val random = Random(4207)
    repeat(8) { i ->
        val laneX = random.nextFloat() * w
        val speed = 0.055f + random.nextFloat() * 0.06f
        val phase = random.nextFloat()
        val fall = wrap01(time * speed + phase)
        val y = snapTo(horizon * 0.10f + fall * (horizon * 0.95f), cell)
        val x = snapTo(laneX + sin(time * 1.1f + i * 2.1f) * w * 0.05f, cell)
        val alpha = (1f - smoothStep(0.85f, 1f, fall)) * 0.9f
        // Leaves alternate between the canopy green and the accent gold, nothing else.
        val tone = if (i % 2 == 0) lerp(palette.prop, palette.propShade, 0.3f) else palette.propAccent
        rotate(quantiseAngle(sin(time * 2f + i) * 40f), Offset(x, y)) {
            drawOval(
                color = tone.copy(alpha = alpha),
                topLeft = Offset(x - snapTo(w * 0.016f, cell), y - max(cell, snapTo(h * 0.008f, cell))),
                size = Size(snapTo(w * 0.032f, cell), max(cell * 2f, snapTo(h * 0.016f, cell))),
            )
        }
    }
}

private fun DrawScope.drawPot(base: Offset, height: Float, palette: RoomPalette, cell: Float) {
    val potW = snapTo(height * 0.55f, cell)
    val clay = lerp(palette.propAccent, palette.floorShade, 0.30f)
    val clayDark = lerp(clay, palette.shadow, 0.40f)
    val clayLight = lerp(clay, palette.highlight, 0.30f)
    val rim = snapTo(base.y - height * 0.42f, cell)
    val pot = Path().apply {
        moveTo(base.x - potW / 2f, rim)
        lineTo(base.x + potW / 2f, rim)
        lineTo(base.x + potW * 0.36f, base.y)
        lineTo(base.x - potW * 0.36f, base.y)
        close()
    }
    drawPath(pot, clay)
    drawRect(clayDark, Offset(base.x + potW * 0.20f, rim), Size(potW * 0.30f, base.y - rim))
    drawRect(clayLight, Offset(base.x - potW / 2f, rim), Size(potW, cell * 2f))

    // Leaves in a muted sage mixed from the wall and the floor, so the plant belongs to the room.
    val leaf = lerp(palette.wallBottom, palette.floor, 0.50f)
    val leafDark = lerp(leaf, palette.shadow, 0.35f)
    val leafLight = lerp(leaf, palette.highlight, 0.28f)
    repeat(3) { i ->
        val angle = quantiseAngle(-35f + i * 35f)
        rotate(angle, Offset(base.x, rim)) {
            drawOval(leafDark, topLeft = Offset(base.x - height * 0.06f, base.y - height * 0.94f), size = Size(height * 0.12f, height * 0.55f))
            drawOval(leaf, topLeft = Offset(base.x - height * 0.05f, base.y - height * 0.95f), size = Size(height * 0.10f, height * 0.52f))
            drawOval(leafLight, topLeft = Offset(base.x - height * 0.03f, base.y - height * 0.90f), size = Size(height * 0.04f, height * 0.22f))
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
    val cell = artPixel()
    val w = size.width
    val h = size.height
    val baseY = snapTo(h * 0.86f, cell)
    // Three tones of one brown, lit from the top left like every other prop in the room.
    val dark = Color(0xFF5A3D26)
    val mid = Color(0xFF74512F)
    val light = Color(0xFF8F663D)
    repeat(count.coerceAtMost(6)) { i ->
        val random = Random(i * 7919 + 31)
        val scale = 0.78f + random.nextFloat() * 0.50f
        val tilt = quantiseAngle(-16f + random.nextFloat() * 32f)
        val threeLumps = random.nextFloat() > 0.4f
        val jitterX = (random.nextFloat() - 0.5f) * w * 0.035f
        val jitterY = (random.nextFloat() - 0.5f) * h * 0.020f
        val squat = 0.85f + random.nextFloat() * 0.45f

        val x = snapTo(w * (0.13f + i * 0.13f) + jitterX, cell)
        val bounce = floatOffset(time + i, 1.2f, h * 0.004f)
        val r = max(cell * 2f, snapTo(h * 0.030f * scale, cell))
        val y = snapTo(baseY + bounce + jitterY, cell)

        drawPropShadowNeutral(x, y + r * 0.5f, r * 2.4f, dark, cell, size.height)
        rotate(tilt, Offset(x, y)) {
            drawOval(dark, topLeft = Offset(x - r, y - r * 0.5f), size = Size(r * 2f, r * squat))
            drawOval(mid, topLeft = Offset(x - r, y - r * 0.5f - cell), size = Size(r * 2f, r * squat * 0.8f))
            drawOval(
                mid,
                topLeft = Offset(x - r * 0.75f, y - r * 1.2f * squat),
                size = Size(r * 1.5f, r * 0.85f * squat),
            )
            drawOval(
                light,
                topLeft = Offset(x - r * 0.70f, y - r * 1.2f * squat - cell),
                size = Size(r * 1.1f, r * 0.55f * squat),
            )
            if (threeLumps) {
                drawOval(
                    light,
                    topLeft = Offset(x - r * 0.45f, y - r * 1.85f * squat),
                    size = Size(r * 0.9f, r * 0.70f * squat),
                )
            }
        }
        // Flies buzzing above it, on whole pixels so they read as dots rather than as smears.
        repeat(2) { f ->
            val t = time * 3f + f * 2f + i
            drawRect(
                Color(0xFF2B2D31),
                Offset(snapTo(x + sin(t) * r * 1.4f, cell), snapTo(y - r * 2.4f + cos(t * 1.3f) * r * 0.5f, cell)),
                Size(cell, cell),
            )
        }
    }
}

/** [drawPropShadow] for things drawn without a room palette to hand. */
private fun DrawScope.drawPropShadowNeutral(
    centerX: Float,
    baseY: Float,
    width: Float,
    tone: Color,
    cell: Float,
    height: Float,
) {
    val color = tone.copy(alpha = 0.35f)
    val rowsPerBand = max(1, (height * 0.005f / cell).roundToInt())
    val gTop = floor(baseY / cell).toInt()
    floatArrayOf(0.85f, 0.45f).forEachIndexed { band, density ->
        val span = width * (1f - band * 0.25f)
        val left = snapTo(centerX - span / 2f, cell)
        repeat(rowsPerBand) { r ->
            ditherRow(color, left, left + span, gTop + band * rowsPerBand + r, density, cell)
        }
    }
}

/** Green sick aura pulsing behind the pet. */
fun DrawScope.drawSickAura(center: Offset, radius: Float, time: Float) {
    val cell = artPixel()
    // Quantised pulse: a radius that changes by fractions of a pixel makes the edge crawl.
    val pulse = 0.6f + 0.4f * ((sin(time * 2.2f) + 1f) / 2f)
    val r = snapTo(radius * 1.25f * pulse, cell)
    val c = Offset(snapTo(center.x, cell), snapTo(center.y, cell))
    drawCircle(Color(0xFF8FC77A).copy(alpha = 0.14f), r + cell * 2f, c)
    drawCircle(Color(0xFF7FBF6A).copy(alpha = 0.18f), r, c)
}

/** Sleeping vignette: dims the frame from the edges inward. Drawn at full resolution. */
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
 * when it rains. Streaks and flakes are deliberately fat and snapped to the art grid — anything
 * thinner than a couple of source pixels disappears in the upscale, and anything on a fractional
 * position buzzes instead of falling.
 */
fun DrawScope.drawWeather(kind: String, timeSeconds: Float, intensity: Float = 1f) {
    val strength = intensity.coerceIn(0f, 1f)
    if (strength <= 0.01f) return
    val cell = artPixel()
    val w = size.width
    val h = size.height

    when (kind) {
        "rain" -> {
            drawRect(Color(0xFF41607F).copy(alpha = 0.14f * strength))
            val drops = (14 + 30 * strength).toInt()
            val random = Random(1301)
            // Three tones: a dark leading edge, the body of the streak, a bright head.
            val dark = Color(0xFF6E93B8)
            val body = Color(0xFFA9CFEC)
            val head = Color(0xFFDCEEFF)
            repeat(drops) {
                val laneX = random.nextFloat()
                val speed = 0.9f + random.nextFloat() * 0.8f
                val phase = random.nextFloat()
                val len = max(cell * 4f, snapTo(h * (0.10f + random.nextFloat() * 0.08f), cell))
                val y = snapTo(wrap01(timeSeconds * speed + phase) * (h + len) - len, cell)
                val x = snapTo(laneX * (w + w * 0.18f) - w * 0.18f + (y / h) * w * 0.16f, cell)
                val a = (0.32f + 0.30f * random.nextFloat()) * strength
                drawLine(dark.copy(alpha = a * 0.7f), Offset(x + cell, y), Offset(x - w * 0.03f + cell, y + len), strokeWidth = cell)
                drawLine(body.copy(alpha = a), Offset(x, y), Offset(x - w * 0.03f, y + len), strokeWidth = cell)
                drawRect(head.copy(alpha = a), Offset(x - w * 0.03f, y + len - cell), Size(cell, cell))
            }
            // Splash marks along the floor line so the rain feels like it lands somewhere.
            repeat((4 + 6 * strength).toInt()) { i ->
                val sx = snapTo(w * wrap01(i * 0.173f + floor(timeSeconds * 3f) * 0.061f), cell)
                val sy = snapTo(h * 0.90f, cell)
                drawRect(body.copy(alpha = 0.28f * strength), Offset(sx, sy), Size(snapTo(w * 0.05f, cell), cell))
                drawRect(head.copy(alpha = 0.22f * strength), Offset(sx + cell, sy - cell), Size(cell, cell))
            }
        }
        "snow" -> {
            drawRect(Color(0xFFDCE9F5).copy(alpha = 0.07f * strength))
            val flakes = (12 + 26 * strength).toInt()
            val random = Random(7717)
            val core = Color(0xFFFFFFFF)
            val mid = Color(0xFFE4EEF8)
            val shade = Color(0xFFB9CCE0)
            repeat(flakes) { i ->
                val laneX = random.nextFloat()
                val speed = 0.10f + random.nextFloat() * 0.14f
                val phase = random.nextFloat()
                val big = random.nextFloat() > 0.6f
                val fall = wrap01(timeSeconds * speed + phase)
                val y = snapTo(fall * (h + h * 0.06f) - h * 0.03f, cell)
                val x = snapTo(laneX * w + sin(timeSeconds * 0.9f + i * 1.7f) * w * 0.045f, cell)
                val a = (if (big) 0.95f else 0.7f) * strength
                if (big) {
                    drawRect(shade.copy(alpha = a * 0.8f), Offset(x, y + cell), Size(cell * 2f, cell * 2f))
                    drawRect(mid.copy(alpha = a), Offset(x, y), Size(cell * 2f, cell * 2f))
                    drawRect(core.copy(alpha = a), Offset(x, y), Size(cell, cell))
                } else {
                    drawRect(mid.copy(alpha = a), Offset(x, y), Size(cell, cell))
                }
            }
            // Settled snow along the bottom edge: a lit crest over a shaded body.
            val lineY = snapTo(h * 0.955f, cell)
            drawRect(mid.copy(alpha = 0.55f * strength), Offset(0f, lineY), Size(w, h - lineY))
            drawRect(core.copy(alpha = 0.65f * strength), Offset(0f, lineY), Size(w, cell))
            drawDitherFadeOut(core, Rect(0f, lineY - h * 0.02f, w, lineY), alpha = 0.55f * strength, bands = 2, fromTop = false)
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

/**
 * Rotation in whole degrees-ish steps. A rotated shape resampled at a slightly different angle
 * every frame changes which pixels it covers, which is the same shimmer as sub-pixel movement.
 */
private fun quantiseAngle(degrees: Float): Float = (degrees * 0.5f).roundToInt() * 2f

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
