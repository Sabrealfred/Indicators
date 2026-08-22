package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An optional filter laid over the finished frame.
 *
 * [NONE] is what every caller gets unless it says otherwise, and it draws exactly the frame the
 * renderer drew before these existed: one crisp blit, plus the soft finish. The other two are
 * stylisations the player turns on.
 *
 * Neither of them is allowed to move the art off its grid. The upscale stays a whole number and
 * nearest-neighbour in all three modes; the filters only ever paint *over* the blit, in whole
 * buffer pixels, or replace its colours one for one.
 */
enum class RetroMode {

    /** No filter. The default, and byte for byte the picture this class has always drawn. */
    NONE,

    /**
     * A tube: scanlines that bow away from the middle of the glass, a stepped vignette, and a
     * pixel of colour bleed either side of every edge.
     *
     * The scanlines and the vignette are baked into a mask the size of the *buffer*, so one
     * scanline is one art pixel tall however dense the screen is. Drawn at device resolution
     * they came out as hairlines that vanished entirely on a phone.
     */
    CRT,

    /**
     * A 1997 handheld: four shades of green and nothing else.
     *
     * The same discipline as [ColorRamp], taken to its limit. Colour is discarded outright — the
     * filter reads brightness only, and reprints it in four flat tones — so this is a palette
     * reduction rather than a green wash over the picture that is already there.
     */
    GREEN_LCD,
}

/**
 * Draws a scene into a small offscreen buffer and blows it back up with nearest-neighbour
 * sampling, so the art reads as real pixel art rather than smooth vectors.
 *
 * The rule that makes or breaks this is **integer scaling**. Blitting a 144-pixel buffer onto
 * an 1120-pixel view means a scale factor of 7.78, so some source pixels land 7 device pixels
 * tall and their neighbours land 8. Straight lines come out wobbly, edges buzz as anything
 * moves, and the whole image looks dirty rather than chunky. Instead the buffer is sized *from*
 * the view so the factor is a whole number and every source pixel becomes exactly one square
 * block. The leftover, always less than one block, is split evenly around the edges.
 *
 * [RetroMode] hangs off the same blit. Everything a mode needs — the mask, the colour filters —
 * is built when the buffer is built and then reused, because this runs inside a draw path.
 */
class PixelRenderer(private val targetHeight: Int = 200) {

    private var buffer: ImageBitmap? = null
    private var bufferCanvas: Canvas? = null
    private val bufferScope = CanvasDrawScope()

    private var mask: ImageBitmap? = null
    private var maskScope: CanvasDrawScope? = null

    private var lcdBands: Array<ColorFilter>? = null
    private var lcdBandStep = -1

    /**
     * Renders [block] at low resolution and blits the result across [target].
     *
     * @param softness 0 leaves the blocks razor-hard. Above zero the buffer is composited a
     * second time, slightly larger and bilinearly sampled, at low alpha — a cheap bloom that
     * rounds the corner of every block and lets light bleed a pixel or two past an edge. The
     * pixels stay honest because the crisp copy underneath is what you actually read; this only
     * takes the glare off them.
     * @param mode the filter over the finished frame. [RetroMode.NONE] adds nothing at all:
     * same draw calls, same arguments, same picture as before this parameter existed.
     * [RetroMode.GREEN_LCD] ignores [softness], because a bloom would put a fifth and sixth
     * tone back on a screen that is only allowed four.
     * @param exposure how much to brighten the picture before [RetroMode.GREEN_LCD] sorts it
     * into its four tones; ignored by the other modes. 1 bands the scene as drawn, which is
     * right at noon. A room after dark sits at well under half the brightness it has at noon
     * and collapses into the bottom two tones, so pass roughly `1 + 1.2 * night` to keep all
     * four in play. Quantised, so a moving value does not rebuild the filters every frame.
     */
    fun render(
        target: DrawScope,
        block: DrawScope.() -> Unit,
        softness: Float = 0f,
        mode: RetroMode = RetroMode.NONE,
        exposure: Float = 1f,
    ) {
        val viewWidth = target.size.width
        val viewHeight = target.size.height
        if (viewWidth < 1f || viewHeight < 1f) return

        val scale = scaleFor(viewHeight, targetHeight)
        // Size the buffer from the view, not the other way round, so the factor stays whole.
        val width = max(1, ceil(viewWidth / scale).toInt())
        val height = max(1, ceil(viewHeight / scale).toInt())

        var bitmap = buffer
        var canvas = bufferCanvas
        if (bitmap == null || canvas == null || bitmap.width != width || bitmap.height != height) {
            bitmap = ImageBitmap(width, height)
            // The canvas wraps the bitmap, so both are rebuilt together and reused every frame.
            canvas = Canvas(bitmap)
            buffer = bitmap
            bufferCanvas = canvas
        }

        bufferScope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            // The buffer is reused, so wipe last frame before drawing this one.
            drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
            block()
        }

        val destinationWidth = width * scale
        val destinationHeight = height * scale
        val offset = IntOffset(
            x = ((viewWidth - destinationWidth) / 2f).roundToInt(),
            y = ((viewHeight - destinationHeight) / 2f).roundToInt(),
        )

        if (mode == RetroMode.GREEN_LCD) {
            // The LCD replaces the blit rather than sitting on top of it: a full-colour copy
            // underneath would show through every tone that is not fully opaque.
            drawLcd(target, bitmap, offset, destinationWidth, destinationHeight, exposure)
            return
        }

        target.drawImage(
            image = bitmap,
            dstOffset = offset,
            dstSize = IntSize(destinationWidth, destinationHeight),
            filterQuality = FilterQuality.None,
        )

        if (mode == RetroMode.CRT) {
            drawBleed(target, bitmap, offset, destinationWidth, destinationHeight, scale)
        }

        val glow = softness.coerceIn(0f, 1f)
        if (glow > 0.01f) {
            val grow = max(1, (scale * 0.7f).roundToInt())
            target.drawImage(
                image = bitmap,
                dstOffset = IntOffset(offset.x - grow, offset.y - grow),
                dstSize = IntSize(destinationWidth + grow * 2, destinationHeight + grow * 2),
                alpha = 0.22f * glow,
                filterQuality = FilterQuality.Medium,
                blendMode = BlendMode.Plus,
            )
        }

        if (mode == RetroMode.CRT) {
            // Last, so the glass darkens the bloom too rather than the bloom washing it out.
            target.drawImage(
                image = crtMask(width, height),
                dstOffset = offset,
                dstSize = IntSize(destinationWidth, destinationHeight),
                filterQuality = FilterQuality.None,
            )
        }
    }

    /**
     * Reprints the frame in the four [RetroMode.GREEN_LCD] tones.
     *
     * Four passes, no per-pixel work of our own: a flat fill in the darkest tone, then one blit
     * per threshold whose colour filter turns brightness into coverage. Each pass paints its own
     * flat tone wherever the pixel underneath is brighter than that pass's threshold, so the
     * brightest pass that claims a pixel is the one you see, and colour is never consulted at
     * all — only how light the pixel was. See [STEP_GAIN] for how hard the step really is.
     */
    private fun drawLcd(
        target: DrawScope,
        image: ImageBitmap,
        offset: IntOffset,
        destinationWidth: Int,
        destinationHeight: Int,
        exposure: Float,
    ) {
        val bands = lcdBandsFor(exposure)
        val size = IntSize(destinationWidth, destinationHeight)
        // The floor of the picture. Anything below the first threshold is left showing this,
        // and this also covers whatever the transparent corners of the buffer would have let
        // through — an LCD has no transparent parts.
        target.drawRect(
            color = LCD_INK,
            topLeft = Offset(offset.x.toFloat(), offset.y.toFloat()),
            size = Size(destinationWidth.toFloat(), destinationHeight.toFloat()),
        )
        for (band in bands) {
            target.drawImage(
                image = image,
                dstOffset = offset,
                dstSize = size,
                colorFilter = band,
                filterQuality = FilterQuality.None,
            )
        }
    }

    /**
     * The three threshold filters for a given [exposure], built once and kept.
     *
     * Exposure is quantised the way [RoomRamp] quantises the evening, so a caller that follows
     * a smooth day/night curve rebuilds three filters a dozen times a night instead of three
     * filters a frame.
     */
    private fun lcdBandsFor(exposure: Float): Array<ColorFilter> {
        val step = (exposure.coerceIn(MIN_EXPOSURE, MAX_EXPOSURE) * EXPOSURE_STEPS).roundToInt()
        val cached = lcdBands
        if (cached != null && step == lcdBandStep) return cached
        val lift = step / EXPOSURE_STEPS.toFloat()
        val built = arrayOf(
            thresholdFilter(LCD_THRESHOLDS[0], LCD_SHADE, lift),
            thresholdFilter(LCD_THRESHOLDS[1], LCD_MID, lift),
            thresholdFilter(LCD_THRESHOLDS[2], LCD_FIELD, lift),
        )
        lcdBands = built
        lcdBandStep = step
        return built
    }

    /**
     * Two more copies of the frame, one art pixel to each side, tinted warm and cool.
     *
     * The offset is exactly [scale] device pixels, which is one buffer pixel, so both copies
     * land on the same grid as the frame underneath and the fringe is a clean pixel wide rather
     * than a smear. Anything smaller is a fraction of an art pixel and would resample the image.
     */
    private fun drawBleed(
        target: DrawScope,
        image: ImageBitmap,
        offset: IntOffset,
        destinationWidth: Int,
        destinationHeight: Int,
        scale: Int,
    ) {
        val size = IntSize(destinationWidth, destinationHeight)
        target.drawImage(
            image = image,
            dstOffset = IntOffset(offset.x - scale, offset.y),
            dstSize = size,
            alpha = BLEED_ALPHA,
            colorFilter = BLEED_WARM,
            blendMode = BlendMode.Plus,
            filterQuality = FilterQuality.None,
        )
        target.drawImage(
            image = image,
            dstOffset = IntOffset(offset.x + scale, offset.y),
            dstSize = size,
            alpha = BLEED_ALPHA,
            colorFilter = BLEED_COOL,
            blendMode = BlendMode.Plus,
            filterQuality = FilterQuality.None,
        )
    }

    /**
     * The scanline and vignette mask, at buffer resolution, built once per buffer size.
     *
     * It is a bitmap rather than a few hundred rectangles a frame for the obvious reason, and it
     * is the size of the *buffer* rather than the view for the important one: blitted back at
     * the same whole scale as the frame, one mask pixel covers exactly one art pixel, so a
     * scanline is one art pixel tall on every screen ever made.
     */
    private fun crtMask(width: Int, height: Int): ImageBitmap {
        val existing = mask
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        val bitmap = ImageBitmap(width, height)
        val scope = maskScope ?: CanvasDrawScope().also { maskScope = it }
        scope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
            paintCrtMask(width, height)
        }
        mask = bitmap
        return bitmap
    }

    companion object {
        /**
         * How many device pixels wide one art pixel is. Callers need this to keep their own
         * motion on the same grid — a shake or a walk measured in fractions of a device pixel
         * slides the whole nearest-neighbour grid under the image and makes it crawl.
         */
        fun scaleFor(viewHeight: Float, targetHeight: Int): Int =
            max(1, (viewHeight / targetHeight.coerceIn(48, 720)).roundToInt())
    }
}

// --- Green LCD -------------------------------------------------------------------------------

// Four tones, spaced evenly in brightness — about 0.10, 0.28, 0.51, 0.70 — because four tones
// that are not evenly spaced waste one of them. The lightest is the unlit field of the glass
// and the darkest is a fully driven segment.
private val LCD_INK = Color(0xFF0E2410)
private val LCD_SHADE = Color(0xFF2F5A2C)
private val LCD_MID = Color(0xFF6E9648)
private val LCD_FIELD = Color(0xFFAEC46B)

/**
 * Where one tone gives way to the next, in brightness.
 *
 * Not round numbers, and not evenly spaced: they sit in the widest gaps of the brightness
 * histogram the room ramps and creature palettes actually produce at noon, so a threshold never
 * lands on top of a tone the art uses. That keeps the sorting predictable — every outline and
 * eye below the first, body shading and contact shadow between the first and second, bodies,
 * floors and walls between the second and third, bellies, highlights and sky above the third —
 * and it keeps the creature legible against the room in every mood and at every life stage,
 * because its outline is always at least two tones from whatever it stands in front of.
 */
private val LCD_THRESHOLDS = floatArrayOf(0.305f, 0.566f, 0.830f)

/** Rec. 601 brightness. Green carries most of it, which is what the eye does too. */
private const val LUMA_R = 0.299f
private const val LUMA_G = 0.587f
private const val LUMA_B = 0.114f

/**
 * Steepness of the step from "below this threshold" to "above it".
 *
 * A colour matrix is linear, so a hard step has to be a very steep ramp that clamps at both
 * ends. At this gain the ramp is one part in four thousand wide, sixteen times finer than the
 * eight-bit tones the buffer can hold, so all but a handful of tones are fully in or fully out
 * and the screen shows the four greens and nothing else. A tone that lands inside that sliver
 * comes out as a blend of two of them: across every tone the room ramps and creature palettes
 * can produce, between none and five of the fourteen hundred do, depending on the exposure.
 *
 * Steeper would shrink that further, but not by much and not for free: the coefficients here
 * already reach sixteen thousand at the top of the exposure range, and a colour matrix is not
 * guaranteed full float precision on the way to the screen. This is the point where the step is
 * as hard as it can be without the numbers getting big enough to worry about.
 */
private const val STEP_GAIN = 4096f

/** Exposure is rounded to this many steps per unit before the filters are rebuilt. */
private const val EXPOSURE_STEPS = 8f
private const val MIN_EXPOSURE = 0.5f
private const val MAX_EXPOSURE = 4f

/**
 * A filter that paints [tone] flat wherever the pixel is brighter than [threshold], scaled by
 * [exposure], and leaves everything else untouched.
 *
 * The trick is the fourth column. Brightness goes into the *alpha* row, which turns "is this
 * pixel bright enough" into coverage, and the constant term of that row rides on the source's
 * own alpha rather than on the matrix's translation column. That is deliberate: the translation
 * column is documented in 0..255 on one backend and 0..1 on another, whereas a coefficient on a
 * channel means the same thing everywhere. The colour rows work the same way, so an opaque
 * source pixel comes out as exactly [tone] whatever colour it started as.
 */
private fun thresholdFilter(threshold: Float, tone: Color, exposure: Float): ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, tone.red, 0f,
                0f, 0f, 0f, tone.green, 0f,
                0f, 0f, 0f, tone.blue, 0f,
                STEP_GAIN * exposure * LUMA_R,
                STEP_GAIN * exposure * LUMA_G,
                STEP_GAIN * exposure * LUMA_B,
                0.5f - STEP_GAIN * threshold,
                0f,
            ),
        ),
    )

// --- CRT -------------------------------------------------------------------------------------

/** One dark line every this many buffer rows. */
private const val CRT_PERIOD = 3

/** Darkness is counted in these, so scanline and vignette share one quantised scale. */
private const val DARK_STEP = 0.04f

/** How dark a scanline is, in [DARK_STEP]s. */
private const val CRT_SCAN_STEPS = 8

/** How dark the corners go, in [DARK_STEP]s, and in how many visible bands. */
private const val CRT_VIGNETTE_STEPS = 7

/** Distance from the middle, corner being 1, at which the vignette starts. */
private const val CRT_VIGNETTE_START = 0.55f

/**
 * How far the raster bows, in buffer pixels, at the corners.
 *
 * Only the *scanlines* bow; the picture stays flat. Warping the frame itself would mean sampling
 * it off the pixel grid, which is the one thing this class exists to prevent, and a bent pattern
 * over straight art reads as glass in front of a picture, which is what a curved tube is.
 *
 * It has to stay below [CRT_PERIOD]. Shifting a repeating pattern by a whole period leaves it
 * exactly where it was, so at a bow of three the corners came out *aligned* with the middle of
 * the screen again and the bend visibly reset near the edge instead of carrying on outwards.
 * Two is the largest displacement a period of three can actually show.
 */
private const val CRT_BOW = 2f

/**
 * Paints the mask: one run of rectangles per buffer row.
 *
 * Both the bow and the vignette are integers here, so along a row the darkness only changes a
 * dozen or so times and each row is a handful of rectangles rather than a few hundred. It runs
 * once per buffer size, never per frame.
 */
private fun DrawScope.paintCrtMask(width: Int, height: Int) {
    val centreX = (width - 1) * 0.5f
    val centreY = (height - 1) * 0.5f
    var y = 0
    while (y < height) {
        val ny = if (centreY > 0f) (y - centreY) / centreY else 0f
        var runStart = 0
        var runDark = darknessAt(0, y, ny, centreX)
        var x = 1
        while (x <= width) {
            // -1 at the far edge is a sentinel that never matches, so the last run is flushed.
            val dark = if (x == width) -1 else darknessAt(x, y, ny, centreX)
            if (dark != runDark) {
                if (runDark > 0) {
                    drawRect(
                        color = Color(0f, 0f, 0f, runDark * DARK_STEP),
                        topLeft = Offset(runStart.toFloat(), y.toFloat()),
                        size = Size((x - runStart).toFloat(), 1f),
                    )
                }
                runStart = x
                runDark = dark
            }
            x++
        }
        y++
    }
}

/** How dark one mask pixel is, in [DARK_STEP]s: scanline plus vignette. */
private fun darknessAt(x: Int, y: Int, ny: Float, centreX: Float): Int {
    val nx = if (centreX > 0f) (x - centreX) / centreX else 0f
    // The raster is flat across the middle of the tube and bends further the closer to a corner
    // it gets, which is why the bow is squared in x and linear in y. Rounding to whole buffer
    // pixels is what makes it a staircase rather than a smooth line, and a staircase is the only
    // curve pixel art has. The test is against zero, so a negative bow needs no wrapping.
    val bow = (CRT_BOW * nx * nx * ny).roundToInt()
    val onScanline = (y + bow) % CRT_PERIOD == 0
    val radius = sqrt((nx * nx + ny * ny) * 0.5f)
    val vignette = ((radius - CRT_VIGNETTE_START) / (1f - CRT_VIGNETTE_START) * CRT_VIGNETTE_STEPS)
        .roundToInt()
        .coerceIn(0, CRT_VIGNETTE_STEPS)
    return (if (onScanline) CRT_SCAN_STEPS else 0) + vignette
}

/** How strong each side of the colour bleed is. */
private const val BLEED_ALPHA = 0.16f

// The two halves of the bleed: the red end of the frame lands a pixel left of where it should
// and the blue end a pixel right, which is what a misconverged tube does to a hard edge.
private val BLEED_WARM = ColorFilter.tint(Color(0xFFFF3A28), BlendMode.Modulate)
private val BLEED_COOL = ColorFilter.tint(Color(0xFF2846FF), BlendMode.Modulate)
