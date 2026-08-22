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
import com.neopal.pet.domain.RetroMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 *
 * Changing mode is not a cut and not a crossfade: it is a **power cycle**. The screen that is
 * being left dies the death its hardware dies, the glass goes dark, and the screen that is
 * arriving wakes up the way its hardware wakes. That is why tube-to-handheld looks like
 * unplugging one machine and switching on another rather than like one picture dissolving into
 * another, and it is why every pairing of modes works without a case for each. See [render]'s
 * `time` parameter for how a switch is noticed and driven.
 */
class PixelRenderer(private val targetHeight: Int = 200) {

    private var buffer: ImageBitmap? = null
    private var bufferCanvas: Canvas? = null
    private val bufferScope = CanvasDrawScope()

    private var mask: ImageBitmap? = null
    private var maskScope: CanvasDrawScope? = null

    private var lcdBands: Array<ColorFilter>? = null
    private var lcdBandStep = -1
    private var lcdCutStep = 0

    /** The mode drawn last frame. A change against this is what starts a switch. */
    private var lastMode: RetroMode? = null

    /** The screen being left, and when it started dying. [switchStart] is NaN when settled. */
    private var switchFrom = RetroMode.NONE
    private var switchStart = Float.NaN

    /** A switch handed in by [beginSwitch], picked up by the next frame that draws. */
    private var stagedFrom: RetroMode? = null
    private var stagedAt = 0f

    /**
     * The exposure the handheld was living at, so that when it is switched away from it dies at
     * the brightness it had rather than at whatever the incoming mode asked for.
     */
    private var lcdExposure = 1f

    /**
     * True while a mode switch is on screen, as of the last [render].
     *
     * Worth reading straight after the call: the finishing passes a caller draws on top — the
     * colour grade, the sleep vignette — are painted over a screen that is in the middle of
     * going dark, and they wash it out. Skipping them while this is true is free.
     */
    var switching = false
        private set

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
     * @param time seconds on any clock the caller likes, as long as it only goes forwards. It is
     * the whole time base for the switch animation: this class never reads a wall clock, because
     * a draw path that reads one animates differently on every device and cannot be tested. A
     * change in [mode] against the frame before is what starts the animation, so the caller has
     * nothing to remember. Leave it out — or pass anything not finite, or negative — and no
     * switch is ever animated, which is also how a caller honours a reduced-motion setting.
     */
    fun render(
        target: DrawScope,
        block: DrawScope.() -> Unit,
        softness: Float = 0f,
        mode: RetroMode = RetroMode.NONE,
        exposure: Float = 1f,
        time: Float = Float.NaN,
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

        val progress = trackSwitch(mode, time)
        switching = progress >= 0f
        val dying = progress in 0f..DEATH_SHARE
        // Which screen is on the glass. Until the halfway dark it is still the old one, however
        // long ago the setting changed; after it, the new one. Nothing crossfades, so no pairing
        // of modes needs a case of its own.
        val shown = if (dying) switchFrom else mode
        // 0 at the start of the half being played, 1 at its end, and 1 when nothing is playing —
        // which is the value every shape function below reads as "settled".
        val beat = when {
            progress < 0f -> 1f
            dying -> progress / DEATH_SHARE
            else -> (progress - DEATH_SHARE) / (1f - DEATH_SHARE)
        }

        if (shown == RetroMode.GREEN_LCD) {
            // The LCD replaces the blit rather than sitting on top of it: a full-colour copy
            // underneath would show through every tone that is not fully opaque. Its half of a
            // switch is inside the same four passes for the same reason — no fifth colour is
            // mixed for the sake of the animation. See [drawLcd].
            if (!dying) lcdExposure = exposure
            drawLcd(
                target = target,
                image = bitmap,
                offset = offset,
                destinationWidth = destinationWidth,
                destinationHeight = destinationHeight,
                exposure = if (dying) lcdExposure else exposure,
                cut = if (progress < 0f) 0f else lcdCut(dying, beat),
                inkRows = if (progress < 0f) 0 else lcdInkRows(dying, beat, height),
                inkFromTop = dying,
                rows = height,
                scale = scale,
            )
            if (progress >= 0f) {
                // The one moment the panel is a single flat tone is the one moment it can be
                // faded to black without banding, and it is exactly the moment it has to be:
                // every screen meets the same dark before the next one strikes.
                val fade = lcdFade(dying, beat)
                if (fade > MIN_VISIBLE_ALPHA) {
                    target.drawRect(
                        color = Color.Black,
                        topLeft = SCREEN_ORIGIN,
                        size = target.size,
                        alpha = fade,
                    )
                }
            }
            return
        }

        val crt = shown == RetroMode.CRT
        // How much of the raster is lit, in buffer rows. Full unless a tube is collapsing.
        val openRows = if (progress < 0f || !crt) height else crtOpenRows(dying, beat, height)
        val whole = openRows >= height
        // The glass in front of the picture: scanlines and misconvergence. It washes out before
        // the raster moves and comes back only once the tube has settled, which is both what a
        // dying tube does and what keeps a buffer-sized mask off a raster that is no longer the
        // size of the buffer.
        val glass = when {
            progress < 0f -> 1f
            crt -> crtGlass(dying, beat)
            else -> 0f
        }
        val bloom = when {
            progress < 0f -> 0f
            crt -> crtBloom(dying, beat)
            else -> cleanBloom(dying, beat)
        }
        val beamAlpha = if (progress >= 0f && crt) crtBeamAlpha(dying, beat) else 0f
        val beamCols = if (progress >= 0f && crt) crtBeamCols(dying, beat, width) else 0
        val fade = if (progress >= 0f && !crt) cleanFade(dying, beat) else 0f

        if (fade >= 1f) {
            // Fully out. Everything below would be drawn and then painted over.
            target.drawRect(color = Color.Black, topLeft = SCREEN_ORIGIN, size = target.size)
            return
        }

        if (openRows > 0) {
            blitRaster(
                target = target,
                image = bitmap,
                offset = offset,
                destinationWidth = destinationWidth,
                destinationHeight = destinationHeight,
                scale = scale,
                width = width,
                rows = height,
                openRows = openRows,
            )
        }

        if (crt && whole && glass > MIN_VISIBLE_ALPHA) {
            drawBleed(target, bitmap, offset, destinationWidth, destinationHeight, scale, glass)
        }

        val glow = softness.coerceIn(0f, 1f)
        if (glow > 0.01f && whole) {
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

        if (crt && whole && glass > MIN_VISIBLE_ALPHA) {
            // Last, so the glass darkens the bloom too rather than the bloom washing it out.
            target.drawImage(
                image = crtMask(width, height),
                dstOffset = offset,
                dstSize = IntSize(destinationWidth, destinationHeight),
                alpha = glass,
                filterQuality = FilterQuality.None,
            )
        }

        if (progress < 0f) return

        if (!whole) paintShutters(target, offset, scale, height, openRows)
        if (bloom > MIN_VISIBLE_ALPHA) paintBloom(target, offset, scale, height, openRows, bloom)
        if (beamAlpha > MIN_VISIBLE_ALPHA && beamCols > 0) {
            paintBeam(target, offset, scale, width, height, openRows, beamCols, beamAlpha)
        }
        if (fade > MIN_VISIBLE_ALPHA) {
            target.drawRect(
                color = Color.Black,
                topLeft = SCREEN_ORIGIN,
                size = target.size,
                alpha = fade,
            )
        }
    }

    /**
     * Hands this renderer a switch it could not have seen for itself.
     *
     * The mode is a saved setting, so it is usually changed on a screen that is not drawing a
     * creature; by the time this class sees the new value it has also been rebuilt, and a
     * rebuilt renderer has no previous frame to compare against and so animates nothing. A
     * caller that keeps the last mode it showed across that gap can say so here, once, and get
     * the animation on the first frame after coming back.
     *
     * Ignored if [from] is already the mode being drawn.
     */
    fun beginSwitch(from: RetroMode, at: Float) {
        stagedFrom = from
        stagedAt = at
    }

    /**
     * How far through a switch we are, 0 until 1, or -1 when there is nothing to draw.
     *
     * This is also where a switch is noticed at all: a mode that differs from the one drawn last
     * frame starts one. Doing it here rather than asking the caller to report it means the
     * caller owns no state — and state a caller has to keep in step with a renderer is state
     * that goes out of step.
     */
    private fun trackSwitch(mode: RetroMode, time: Float): Float {
        if (!time.isFinite() || time < 0f) {
            // No clock: nothing animates, and the mode is still recorded so that turning the
            // clock back on does not fire a switch for a change that already finished.
            lastMode = mode
            switchStart = Float.NaN
            stagedFrom = null
            return -1f
        }
        val staged = stagedFrom
        if (staged != null) {
            stagedFrom = null
            if (staged != mode) {
                switchFrom = staged
                switchStart = stagedAt
                lastMode = mode
            }
        }
        var progress = phaseOf(time)
        val previous = lastMode
        if (previous == null) {
            lastMode = mode
        } else if (previous != mode) {
            // Switching again mid-switch: what is being left is whatever is on the glass now,
            // which during the first half is not the mode the last switch started from.
            if (progress < 0f || progress > DEATH_SHARE) switchFrom = previous
            switchStart = time
            lastMode = mode
            progress = 0f
        }
        return progress
    }

    /** [switchStart] read against [time], clearing itself once the animation has run out. */
    private fun phaseOf(time: Float): Float {
        val start = switchStart
        if (start.isNaN()) return -1f
        val progress = (time - start) / SWITCH_SECONDS
        // A caller whose clock restarts — a recomposition, a new screen — lands before the
        // start, and that is over rather than a switch that plays backwards for a minute.
        if (progress < 0f || progress >= 1f) {
            switchStart = Float.NaN
            return -1f
        }
        return progress
    }

    /**
     * Blits the frame with only [openRows] of its rows' worth of height lit.
     *
     * A tube collapsing squeezes the picture rather than cropping it, and squeezing means
     * fewer destination rows than source rows — which is exactly the resampling this class
     * exists to prevent, if it is done by handing a smaller `dstSize` to one blit. So it is
     * done as a run of blits instead: the source is cut into whole bands of whole rows, every
     * band is drawn at the same whole [scale] as ever, and the bands are simply placed closer
     * together than they belong so that each one covers the tail of the one above it. Every
     * pixel drawn is still one square block on the same grid; what shrinks is the spacing.
     *
     * With one band per lit row this is exact nearest-neighbour decimation — the row a real
     * raster would have kept. [RASTER_BANDS] caps the band count so a tall buffer cannot turn
     * one frame into hundreds of draw calls, and above that cap each band keeps a contiguous
     * run of its rows instead of a single one. The two agree exactly at the cap, and at full
     * height the bands land where they belong and the picture is untouched.
     */
    private fun blitRaster(
        target: DrawScope,
        image: ImageBitmap,
        offset: IntOffset,
        destinationWidth: Int,
        destinationHeight: Int,
        scale: Int,
        width: Int,
        rows: Int,
        openRows: Int,
    ) {
        if (openRows >= rows) {
            target.drawImage(
                image = image,
                dstOffset = offset,
                dstSize = IntSize(destinationWidth, destinationHeight),
                filterQuality = FilterQuality.None,
            )
            return
        }
        val bands = min(openRows, RASTER_BANDS)
        val top = (rows - openRows) / 2
        var index = 0
        while (index < bands) {
            val sourceTop = index * rows / bands
            val sourceRows = (index + 1) * rows / bands - sourceTop
            if (sourceRows > 0) {
                target.drawImage(
                    image = image,
                    srcOffset = IntOffset(0, sourceTop),
                    srcSize = IntSize(width, sourceRows),
                    dstOffset = IntOffset(
                        x = offset.x,
                        y = offset.y + (top + index * openRows / bands) * scale,
                    ),
                    dstSize = IntSize(destinationWidth, sourceRows * scale),
                    filterQuality = FilterQuality.None,
                )
            }
            index++
        }
    }

    /**
     * Black over everything the collapsed raster no longer reaches.
     *
     * Full width of the *view*, not of the blit, because a tube going out takes the whole
     * screen with it including the half-block of letterbox the integer scale leaves over.
     */
    private fun paintShutters(
        target: DrawScope,
        offset: IntOffset,
        scale: Int,
        rows: Int,
        openRows: Int,
    ) {
        val top = (offset.y + (rows - openRows) / 2 * scale).toFloat()
        val bottom = top + openRows * scale
        val viewWidth = target.size.width
        val viewHeight = target.size.height
        if (top > 0f) {
            target.drawRect(
                color = Color.Black,
                topLeft = SCREEN_ORIGIN,
                size = Size(viewWidth, top),
            )
        }
        if (bottom < viewHeight) {
            target.drawRect(
                color = Color.Black,
                topLeft = Offset(0f, bottom),
                size = Size(viewWidth, viewHeight - bottom),
            )
        }
    }

    /**
     * The extra brightness of a raster that is being packed into fewer lines than it was drawn
     * for. The beam current does not fall away with the deflection, so the picture washes out
     * on its way to being a line — which is also why the line is white and the picture was not.
     */
    private fun paintBloom(
        target: DrawScope,
        offset: IntOffset,
        scale: Int,
        rows: Int,
        openRows: Int,
        bloom: Float,
    ) {
        val top = max(0f, (offset.y + (rows - openRows) / 2 * scale).toFloat())
        val bottom = min(target.size.height, top + openRows * scale)
        if (bottom <= top) return
        target.drawRect(
            color = CRT_BEAM,
            topLeft = Offset(0f, top),
            size = Size(target.size.width, bottom - top),
            alpha = bloom,
            blendMode = BlendMode.Plus,
        )
    }

    /**
     * The line the raster collapses into, and the dot it leaves behind.
     *
     * One buffer row tall and a whole number of buffer columns wide, so at its very last frame
     * it is one square art pixel — the same block everything else in this class is made of,
     * rather than a hairline that would land between two of them.
     */
    private fun paintBeam(
        target: DrawScope,
        offset: IntOffset,
        scale: Int,
        width: Int,
        rows: Int,
        openRows: Int,
        beamCols: Int,
        alpha: Float,
    ) {
        val row = if (openRows > 0) (rows - openRows) / 2 + openRows / 2 else rows / 2
        target.drawRect(
            color = CRT_BEAM,
            topLeft = Offset(
                (offset.x + (width - beamCols) / 2 * scale).toFloat(),
                (offset.y + row * scale).toFloat(),
            ),
            size = Size((beamCols * scale).toFloat(), scale.toFloat()),
            alpha = alpha,
            blendMode = BlendMode.Plus,
        )
    }

    /**
     * Reprints the frame in the four [RetroMode.GREEN_LCD] tones.
     *
     * Four passes, no per-pixel work of our own: a flat fill in the darkest tone, then one blit
     * per threshold whose colour filter turns brightness into coverage. Each pass paints its own
     * flat tone wherever the pixel underneath is brighter than that pass's threshold, so the
     * brightest pass that claims a pixel is the one you see, and colour is never consulted at
     * all — only how light the pixel was. See [STEP_GAIN] for how hard the step really is.
     *
     * A switch adds two things and no colours. [cut] raises every threshold together, which is
     * the picture sliding out from under its own tones: the highlights let go first, then the
     * mids, until the glass is one flat [LCD_INK] — every segment driven, which is what a cheap
     * panel does for an instant when its drive voltage goes and what it does again when it
     * comes back. [inkRows] covers the rows that have not been addressed yet in that same ink,
     * with the row at the boundary left in the unlit [LCD_FIELD]: the panel resolves a band at
     * a time instead of all at once, because that is the order its rows are actually driven in.
     */
    private fun drawLcd(
        target: DrawScope,
        image: ImageBitmap,
        offset: IntOffset,
        destinationWidth: Int,
        destinationHeight: Int,
        exposure: Float,
        cut: Float,
        inkRows: Int,
        inkFromTop: Boolean,
        rows: Int,
        scale: Int,
    ) {
        val size = IntSize(destinationWidth, destinationHeight)
        // The floor of the picture. Anything below the first threshold is left showing this,
        // and this also covers whatever the transparent corners of the buffer would have let
        // through — an LCD has no transparent parts.
        target.drawRect(
            color = LCD_INK,
            topLeft = Offset(offset.x.toFloat(), offset.y.toFloat()),
            size = Size(destinationWidth.toFloat(), destinationHeight.toFloat()),
        )
        // Every row driven and none of them addressed yet: the floor already is that picture,
        // and the three passes would only be painted over by the cover.
        if (inkRows >= rows) return
        for (band in lcdBandsFor(exposure, cut)) {
            target.drawImage(
                image = image,
                dstOffset = offset,
                dstSize = size,
                colorFilter = band,
                filterQuality = FilterQuality.None,
            )
        }
        if (inkRows <= 0) return
        val coveredTop = if (inkFromTop) 0 else rows - inkRows
        target.drawRect(
            color = LCD_INK,
            topLeft = Offset(offset.x.toFloat(), (offset.y + coveredTop * scale).toFloat()),
            size = Size(destinationWidth.toFloat(), (inkRows * scale).toFloat()),
        )
        if (inkRows >= rows) return
        val edge = if (inkFromTop) coveredTop + inkRows - 1 else coveredTop
        target.drawRect(
            color = LCD_FIELD,
            topLeft = Offset(offset.x.toFloat(), (offset.y + edge * scale).toFloat()),
            size = Size(destinationWidth.toFloat(), scale.toFloat()),
        )
    }

    /**
     * The three threshold filters for a given [exposure] and [cut], built once and kept.
     *
     * Exposure is quantised the way [RoomRamp] quantises the evening, so a caller that follows
     * a smooth day/night curve rebuilds three filters a dozen times a night instead of three
     * filters a frame. [cut] is quantised for the same reason and by the same trick, which is
     * what keeps a switch to about [LCD_CUT_STEPS] rebuilds in total rather than one a frame.
     *
     * A [cut] of zero rounds to a lift of exactly zero and so returns exactly the filters this
     * built before any of this existed — the settled handheld is untouched.
     */
    private fun lcdBandsFor(exposure: Float, cut: Float): Array<ColorFilter> {
        val step = (exposure.coerceIn(MIN_EXPOSURE, MAX_EXPOSURE) * EXPOSURE_STEPS).roundToInt()
        val cutStep = (cut.coerceIn(-1f, 1f) * LCD_CUT_STEPS).roundToInt()
        val cached = lcdBands
        if (cached != null && step == lcdBandStep && cutStep == lcdCutStep) return cached
        val lift = step / EXPOSURE_STEPS.toFloat()
        // A threshold is compared against brightness times exposure, so what it takes to push
        // even the lowest of them past the whitest pixel on the glass grows with the exposure.
        // Anything less and a bright night scene would refuse to go dark.
        val raise = cutStep / LCD_CUT_STEPS * (lift - LCD_THRESHOLDS[0] + LCD_BLANK_MARGIN)
        val built = arrayOf(
            thresholdFilter(LCD_THRESHOLDS[0] + raise, LCD_SHADE, lift),
            thresholdFilter(LCD_THRESHOLDS[1] + raise, LCD_MID, lift),
            thresholdFilter(LCD_THRESHOLDS[2] + raise, LCD_FIELD, lift),
        )
        lcdBands = built
        lcdBandStep = step
        lcdCutStep = cutStep
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
        strength: Float,
    ) {
        val size = IntSize(destinationWidth, destinationHeight)
        target.drawImage(
            image = image,
            dstOffset = IntOffset(offset.x - scale, offset.y),
            dstSize = size,
            alpha = BLEED_ALPHA * strength,
            colorFilter = BLEED_WARM,
            blendMode = BlendMode.Plus,
            filterQuality = FilterQuality.None,
        )
        target.drawImage(
            image = image,
            dstOffset = IntOffset(offset.x + scale, offset.y),
            dstSize = size,
            alpha = BLEED_ALPHA * strength,
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

        /**
         * How long a whole switch takes, in seconds. Long enough to be worth watching, short
         * enough that a player flicking through the three modes is not made to wait for any of
         * them. Public because a caller driving the clock may want to know when it is over.
         */
        const val SWITCH_SECONDS = 0.78f
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

// --- Switching modes -------------------------------------------------------------------------

/**
 * How much of a switch is spent killing the outgoing screen; the rest wakes the incoming one.
 *
 * Under a half on purpose. Dying is the part the eye already knows the end of, and waking is
 * the part worth watching, so the arriving screen gets the longer beat.
 */
private const val DEATH_SHARE = 0.44f

/** Below this an overlay is not worth a draw call: a 255th of full is not a visible tone. */
private const val MIN_VISIBLE_ALPHA = 0.004f

private val SCREEN_ORIGIN = Offset(0f, 0f)

/**
 * Most bands a collapsing raster is cut into.
 *
 * Below this many lit rows the cap does not bind and every lit row is its own band, which is
 * the exact decimation a real collapse performs; above it whole runs of rows survive together
 * instead, which at that stage of the squeeze is a difference nothing can see. Either way the
 * bands allocate nothing — `IntOffset` and `IntSize` are value classes over a `Long` — so what
 * the cap bounds is draw calls, sixty-four of them for the sixth of a second the raster moves.
 *
 * Sixty-four is a guess at where a frame stops being cheap, arrived at without a device to
 * measure on. It is the one number in this file worth revisiting once there is one.
 */
private const val RASTER_BANDS = 64

/** The colour of a beam that is no longer drawing a picture, just delivering current. */
private val CRT_BEAM = Color(0xFFFFF4E2)

// The beats of a tube going out: it holds and blooms, then the raster squeezes shut, then what
// is left of it contracts sideways into a dot.
private const val CRT_HOLD = 0.22f
private const val CRT_SQUEEZE_END = 0.74f

// And of one coming on: a dot strikes and opens into a line, the raster snaps open, then the
// picture settles and the glass comes back in front of it.
private const val CRT_STRIKE = 0.22f
private const val CRT_OPEN_END = 0.58f

private const val CRT_HOLD_BLOOM = 0.20f
private const val CRT_BLOOM_MAX = 0.55f
private const val CRT_SETTLE_BLOOM = 0.12f

/** How many buffer rows of the raster are lit, [rows] being all of them. */
private fun crtOpenRows(dying: Boolean, beat: Float, rows: Int): Int {
    val open = if (dying) {
        when {
            beat < CRT_HOLD -> 1f
            beat < CRT_SQUEEZE_END ->
                (1f - (beat - CRT_HOLD) / (CRT_SQUEEZE_END - CRT_HOLD)).pow(1.7f)
            else -> 0f
        }
    } else {
        when {
            beat < CRT_STRIKE -> 0f
            // Opens fast and then eases into place, because the deflection recovers long before
            // the picture does. An exponent below one is what makes the first frames the big ones.
            beat < CRT_OPEN_END -> ((beat - CRT_STRIKE) / (CRT_OPEN_END - CRT_STRIKE)).pow(0.62f)
            else -> 1f
        }
    }
    return (rows * open).roundToInt().coerceIn(0, rows)
}

/** How much white the collapsing or recovering raster is washed out with. */
private fun crtBloom(dying: Boolean, beat: Float): Float = if (dying) {
    when {
        beat < CRT_HOLD -> CRT_HOLD_BLOOM * (beat / CRT_HOLD)
        beat < CRT_SQUEEZE_END -> CRT_HOLD_BLOOM + (CRT_BLOOM_MAX - CRT_HOLD_BLOOM) *
            ((beat - CRT_HOLD) / (CRT_SQUEEZE_END - CRT_HOLD))
        else -> 0f
    }
} else {
    when {
        beat < CRT_STRIKE -> 0f
        beat < CRT_OPEN_END -> CRT_BLOOM_MAX - (CRT_BLOOM_MAX - CRT_SETTLE_BLOOM) *
            ((beat - CRT_STRIKE) / (CRT_OPEN_END - CRT_STRIKE))
        else -> CRT_SETTLE_BLOOM * (1f - (beat - CRT_OPEN_END) / (1f - CRT_OPEN_END))
    }
}

/**
 * How present the glass in front of the tube is: scanlines and misconvergence together.
 *
 * It leaves before the raster moves and comes back only once the raster is whole again. That is
 * the honest thing for a picture that is being squeezed — lines merge as they are packed
 * together, and a tube that has not settled has not converged either — and it is also the rule
 * that keeps a mask built at buffer size off a raster that is no longer that size.
 */
private fun crtGlass(dying: Boolean, beat: Float): Float = if (dying) {
    (1f - beat / CRT_HOLD).coerceIn(0f, 1f)
} else {
    ((beat - CRT_OPEN_END) / (1f - CRT_OPEN_END)).coerceIn(0f, 1f)
}

/** How bright the collapse line across the middle is. */
private fun crtBeamAlpha(dying: Boolean, beat: Float): Float = if (dying) {
    when {
        beat < CRT_HOLD -> 0f
        beat < CRT_SQUEEZE_END -> (beat - CRT_HOLD) / (CRT_SQUEEZE_END - CRT_HOLD)
        else -> {
            // Over one frame short of the end, so the last of the dot is nothing rather than a
            // fifth of a tone snapping off. A curve that only reaches zero at the boundary never
            // gets sampled there.
            val out = ((beat - CRT_SQUEEZE_END) / (1f - CRT_SQUEEZE_END) / 0.94f).coerceAtMost(1f)
            1f - out * out
        }
    }
} else {
    when {
        beat < CRT_STRIKE -> beat / CRT_STRIKE
        beat < CRT_OPEN_END -> 1f - (beat - CRT_STRIKE) / (CRT_OPEN_END - CRT_STRIKE)
        else -> 0f
    }
}

/** How wide that line is, in buffer columns. One column is the dot it dies as. */
private fun crtBeamCols(dying: Boolean, beat: Float, width: Int): Int = if (dying) {
    when {
        beat < CRT_HOLD -> 0
        beat < CRT_SQUEEZE_END -> width
        else -> {
            val out = (beat - CRT_SQUEEZE_END) / (1f - CRT_SQUEEZE_END)
            max(1, (width * (1f - out).pow(2.4f)).roundToInt())
        }
    }
} else {
    when {
        beat < CRT_STRIKE -> max(1, (width * (beat / CRT_STRIKE).pow(1.8f)).roundToInt())
        beat < CRT_OPEN_END -> width
        else -> 0
    }
}

/**
 * How long the clean mode takes to go dark, as a share of its half.
 *
 * [RetroMode.NONE] is the absence of a machine, so it has no machine's habits to imitate: it
 * simply goes out and comes back, quickly, and spends the rest of its half being the dark that
 * the screen on the other side of the switch strikes out of. Giving it a tube's collapse would
 * be inventing hardware the player just chose not to have.
 */
private const val CLEAN_FADE = 0.62f
private const val CLEAN_BLOOM = 0.22f

private fun cleanFade(dying: Boolean, beat: Float): Float = if (dying) {
    (beat / CLEAN_FADE).coerceAtMost(1f).pow(1.4f)
} else {
    (1f - beat / CLEAN_FADE).coerceAtLeast(0f).pow(1.4f)
}

/** A flash of over-brightness as the picture comes back, and none at all on the way out. */
private fun cleanBloom(dying: Boolean, beat: Float): Float =
    if (dying) 0f else CLEAN_BLOOM * (1f - beat).pow(2f)

// The beats of a handheld: the tones let go over most of the half and the last of it fades the
// flat ink to black; coming back, black lifts to ink, the ink flash holds, and then the tones
// separate out of it slowly, the way a panel with a response time in the tens of milliseconds
// separates anything.
private const val LCD_DIE_END = 0.90f

/** Where the flat ink starts going to black, early enough to have got there by the last frame. */
private const val LCD_BLACKOUT = 0.86f
private const val LCD_BLACKOUT_SPAN = 0.11f

/** How long black takes to lift back to ink on the way in. The mirror of the blackout. */
private const val LCD_UNFADE = 0.12f

/** How long every segment stays driven when the panel comes back, before the tones separate. */
private const val LCD_FLASH = 0.22f

// Where the sweep of rows being addressed starts and finishes, in each half. Dying it runs from
// the top down and finishes early, so the panel is flat well before the blackout; waking it
// starts as the flash ends, so the picture is revealed by the sweep rather than under it.
private const val LCD_DIE_SWEEP_FROM = 0.30f
private const val LCD_DIE_SWEEP_TO = 0.88f
private const val LCD_WAKE_SWEEP_TO = 0.70f

/** How far past the top threshold the lift has to reach to blank the panel outright. */
private const val LCD_BLANK_MARGIN = 0.06f

/** The lift is rounded to this many steps before the filters are rebuilt. */
private const val LCD_CUT_STEPS = 32f

/**
 * How far the panel overshoots on its way back.
 *
 * A driver settling on its bias goes a little too far and comes back, and on a slow panel that
 * shows: the picture is briefly a shade too light before it lands. Small on purpose — enough to
 * see once, not enough to look like a fault.
 */
private const val LCD_UNDERSHOOT = 0.12f

/** Peak of `v * v * v * (1 - v)`, inverted, so the overshoot term tops out at exactly one. */
private const val CUBIC_BUMP_GAIN = 9.4815f

/** How far every threshold is lifted together, 1 blanking the panel to flat ink. */
private fun lcdCut(dying: Boolean, beat: Float): Float = if (dying) {
    // Slow to let go: the panel holds its picture for a beat, then the tones go together.
    (beat / LCD_DIE_END).coerceAtMost(1f).pow(1.9f)
} else {
    if (beat < LCD_FLASH) {
        1f
    } else {
        val out = (beat - LCD_FLASH) / (1f - LCD_FLASH)
        val settling = (1f - out).pow(2.2f)
        // The overshoot rides late on the settle, peaking three quarters of the way through it,
        // so the panel is a shade too light just before it lands rather than on its way past.
        settling - LCD_UNDERSHOOT * out * out * out * (1f - out) * CUBIC_BUMP_GAIN
    }
}

/** Rows not addressed yet: counted from the top while dying, from the bottom while waking. */
private fun lcdInkRows(dying: Boolean, beat: Float, rows: Int): Int {
    val covered = if (dying) {
        smoothRamp(beat, LCD_DIE_SWEEP_FROM, LCD_DIE_SWEEP_TO)
    } else {
        1f - smoothRamp(beat, LCD_FLASH, LCD_WAKE_SWEEP_TO)
    }
    return (rows * covered).roundToInt().coerceIn(0, rows)
}

/**
 * The black either side of the flat-ink moment. A fade cannot band there because there is only
 * one tone on the glass to band between.
 */
private fun lcdFade(dying: Boolean, beat: Float): Float = if (dying) {
    ((beat - LCD_BLACKOUT) / LCD_BLACKOUT_SPAN).coerceIn(0f, 1f)
} else {
    (1f - beat / LCD_UNFADE).coerceIn(0f, 1f)
}

/** 0 before [from], 1 after [to], and eased in between so a sweep has no corners on it. */
private fun smoothRamp(value: Float, from: Float, to: Float): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
