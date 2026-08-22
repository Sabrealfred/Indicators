package com.neopal.pet.widget

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.neopal.pet.domain.RetroMode
import com.neopal.pet.domain.WidgetSnapshot
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.PixelRenderer
import kotlin.math.max
import kotlin.math.sqrt
import android.graphics.Canvas as NativeCanvas

/**
 * Runs the app's own drawing code over a plain [Bitmap], which is the only kind of picture a
 * classic app widget can show.
 *
 * `RemoteViews` cannot host Compose: it is a description of a view tree, inflated in the
 * launcher's process, and the only arbitrary image it will carry is a bitmap. That rules out
 * running the creature's art *there* — but not running it *here*. The art is a set of
 * `DrawScope` extensions, `CanvasDrawScope` will point a `DrawScope` at any canvas, and
 * `androidx.compose.ui.graphics.Canvas` will wrap the ordinary `android.graphics.Canvas` over a
 * bitmap. So the widget gets the real creature, at the cost of one bitmap per update and no new
 * dependency at all. Glance would be the other route; it would also be a new artefact in the
 * build for a picture this already draws.
 *
 * Two things are worth knowing before changing anything here.
 *
 * **The bitmap crosses a Binder transaction.** Everything in a `RemoteViews` is parcelled to the
 * launcher, and the buffer for that is shared and roughly a megabyte. A widget at the size of a
 * modern phone's four-by-two cell is around a megabyte of ARGB on its own, so the render is
 * capped by area and the `ImageView` is left to scale the rest. Raising [MAX_PIXELS] is the
 * fastest way to make a widget that works on a test device throw `TransactionTooLargeException`
 * on somebody's tablet.
 *
 * **The corners are cut here, not by the launcher.** Rounded-corner enforcement on a widget is
 * the host's business and hosts disagree about it, so the clip is applied to the canvas before
 * anything is drawn. That is also why it is done on the native canvas: it costs one path and
 * survives every save/restore the draw scope makes inside it.
 */
internal object PetWidgetBitmap {

    /**
     * The largest picture worth parcelling, in pixels. 96k is about 384 KB of ARGB — big enough
     * that the upscale is invisible on a pixel-art image whose own buffer is a hundred rows
     * tall, small enough to leave the transaction plenty of room for the rest of the views.
     */
    const val MAX_PIXELS = 96_000

    /**
     * Rows in the art buffer the scene is drawn into before it is blown back up.
     *
     * Lower than the app's own 200 on purpose: the widget's output is a few hundred pixels tall
     * at most, and a buffer that nearly matches the output means an integer scale of one, which
     * is a smooth vector picture rather than the pixel art this game is. At ~104 the factor lands
     * on two or three and the blocks are visible, which is the look.
     */
    const val PIXEL_HEIGHT = 104

    /**
     * Draws [snapshot] at up to [widthPx] × [heightPx], clipped to [cornerRadiusPx].
     *
     * Returns null rather than throwing if the size is nonsense or the allocation fails: a
     * widget with no picture and honest text is a poor widget, and a widget that crashes the
     * launcher's update is a bug report.
     */
    fun render(
        snapshot: WidgetSnapshot,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        softness: Float,
        /** Fraction of the height the text plate will cover; the creature keeps the rest. */
        bottomInset: Float,
        /** The screen the player chose to be looking at. Settled, never mid-switch: see below. */
        retroMode: RetroMode = RetroMode.NONE,
    ): Bitmap? {
        if (widthPx < 8 || heightPx < 8) return null
        val shrink = shrinkFor(widthPx, heightPx)
        val width = max(8, (widthPx * shrink).toInt())
        val height = max(8, (heightPx * shrink).toInt())

        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val native = NativeCanvas(bitmap)
        // The radius shrinks with the picture, or a downscaled bitmap comes back with corners
        // rounded twice as hard as the widget next to it.
        val radius = cornerRadiusPx * shrink
        if (radius > 0.5f) {
            val path = Path().apply {
                addRoundRect(
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    radius,
                    radius,
                    Path.Direction.CW,
                )
            }
            native.clipPath(path)
        }

        // The blit lands on a whole number of blocks and the leftover — always less than one
        // block — is split around the edges. Filling first means that leftover is the colour of
        // the wall rather than a transparent hairline against the wallpaper.
        val room = Palettes.applyNight(
            Palettes.room(snapshot.scene.roomTheme),
            if (snapshot.scene.night) 1f else 0f,
        )
        native.drawColor(room.wallBottom.toArgb())

        val canvas = Canvas(native)
        val renderer = PixelRenderer(PIXEL_HEIGHT)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            renderer.render(
                target = this,
                block = { drawWidgetScene(snapshot, bottomInset) },
                softness = softness.coerceIn(0f, 1f),
                // A player who set the app to a green handheld did not ask for one window onto
                // the room and one onto a different machine.
                mode = retroMode,
                // What the handheld's four tones are sorted from. The same number the app uses,
                // and inert for every other mode.
                exposure = if (snapshot.scene.night) 2.2f else 1f,
                // Deliberately no clock. A still frame handed a time would be caught halfway
                // through a power cycle, and the widget would show a dark screen for hours.
            )
        }
        return bitmap
    }

    /** How much of the asked-for size fits inside [MAX_PIXELS]. Never enlarges. */
    private fun shrinkFor(widthPx: Int, heightPx: Int): Float {
        val area = widthPx.toLong() * heightPx.toLong()
        if (area <= MAX_PIXELS) return 1f
        return sqrt(MAX_PIXELS.toDouble() / area.toDouble()).toFloat()
    }
}
