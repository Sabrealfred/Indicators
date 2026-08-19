package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
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
 */
class PixelRenderer(private val targetHeight: Int = 200) {

    private var buffer: ImageBitmap? = null
    private var bufferCanvas: Canvas? = null
    private val bufferScope = CanvasDrawScope()

    /** Renders [block] at low resolution and blits the result across [target]. */
    fun render(target: DrawScope, block: DrawScope.() -> Unit) {
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
        target.drawImage(
            image = bitmap,
            dstOffset = IntOffset(
                x = ((viewWidth - destinationWidth) / 2f).roundToInt(),
                y = ((viewHeight - destinationHeight) / 2f).roundToInt(),
            ),
            dstSize = IntSize(destinationWidth, destinationHeight),
            filterQuality = FilterQuality.None,
        )
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
