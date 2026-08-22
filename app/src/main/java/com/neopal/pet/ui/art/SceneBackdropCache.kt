package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import kotlin.math.ceil

/**
 * A [SceneBackdrop] that paints the room's two unmoving layers once and then blits them.
 *
 * The room's wall and floor are a pure function of `(themeId, night, size)`. Drawn the long way
 * they are around two thousand small rects a frame — most of them one art pixel tall, because
 * that is what an ordered dither costs — and a phone was re-issuing every one of them sixty
 * times a second to repaint a picture that had not changed since the room was opened. Here each
 * layer goes into an offscreen bitmap the first time it is asked for and comes back as a single
 * `drawImage` until one of the three things it depends on changes.
 *
 * **It is the same picture, not a similar one.** The layer is drawn through a [CanvasDrawScope]
 * whose `size` is the caller's own size, not the bitmap's, so every art-grid snap, every band
 * count and every dither threshold inside lands on exactly the coordinate it would have landed
 * on had it been drawn straight to the screen. The bitmap is that size rounded up to whole
 * pixels and is blitted 1:1 at the origin, so no sampling happens either.
 *
 * **Two bitmaps, not one.** [drawScene] draws the theme backdrop and the window between the two
 * layers. Merging them into one cached image would hoist the floor in front of the window.
 *
 * Costs two ARGB bitmaps the size of the draw surface. In the default pixel mode that surface is
 * the ~200px buffer [PixelRenderer] renders into, so the pair is a few hundred kilobytes; with
 * pixel mode off it is the stage. Hold one per stage — `remember { CachedSceneBackdrop() }` —
 * and it will resize itself when the stage does.
 */
class CachedSceneBackdrop : SceneBackdrop {

    private val wallLayer = Layer()
    private val floorLayer = Layer()

    override fun wall(scope: DrawScope, themeId: String, night: Float) =
        wallLayer.paint(scope, themeId, night) { drawRoomWall(themeId, night) }

    override fun floor(scope: DrawScope, themeId: String, night: Float) =
        floorLayer.paint(scope, themeId, night) { drawRoomFloor(themeId, night) }

    /** One cached layer: the bitmap, the canvas over it, and what was drawn into it. */
    private class Layer {
        private var bitmap: ImageBitmap? = null
        private var canvas: Canvas? = null
        private val scope = CanvasDrawScope()

        private var themeId: String? = null
        private var night = Float.NaN
        private var width = 0
        private var height = 0

        fun paint(target: DrawScope, themeId: String, night: Float, block: DrawScope.() -> Unit) {
            val w = ceil(target.size.width).toInt()
            val h = ceil(target.size.height).toInt()
            if (w < 1 || h < 1) return

            var image = bitmap
            var surface = canvas
            val resized = image == null || surface == null || width != w || height != h
            if (resized) {
                image = ImageBitmap(w, h)
                // The canvas wraps the bitmap, so the two are rebuilt together and then reused.
                surface = Canvas(image)
                bitmap = image
                canvas = surface
                width = w
                height = h
            }

            if (resized || this.themeId != themeId || this.night != night) {
                scope.draw(
                    // The caller's density and its exact size: the layer has to be drawn as if
                    // it were being drawn to the screen, or the art grid shifts under it.
                    density = Density(target.density, target.fontScale),
                    layoutDirection = target.layoutDirection,
                    canvas = surface!!,
                    size = target.size,
                ) {
                    // The bitmap is reused across themes and across dusk, so wipe it first.
                    drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
                    block()
                }
                this.themeId = themeId
                this.night = night
            }

            target.drawImage(image!!, Offset.Zero)
        }
    }
}
