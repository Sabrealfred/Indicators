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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a scene into a small offscreen buffer and blows it back up with nearest-neighbour
 * sampling. The vectors stay resolution-independent in code, but the player sees honest,
 * chunky pixels — the look a virtual pet is supposed to have.
 *
 * The buffer is allocated once per size change and reused every frame, so the only per-frame
 * cost is the draw itself plus one blit.
 */
class PixelRenderer(private val targetHeight: Int = 144) {

    private var buffer: ImageBitmap? = null
    private var bufferCanvas: Canvas? = null
    private val bufferScope = CanvasDrawScope()

    /** Renders [block] at low resolution and blits the result across [target]. */
    fun render(target: DrawScope, block: DrawScope.() -> Unit) {
        val height = targetHeight.coerceIn(48, 720)
        val aspect = if (target.size.height <= 0f) 1f else target.size.width / target.size.height
        val width = max(1, (height * aspect).roundToInt())

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

        target.drawImage(
            image = bitmap,
            dstSize = IntSize(target.size.width.roundToInt(), target.size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
