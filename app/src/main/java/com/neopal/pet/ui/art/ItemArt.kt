package com.neopal.pet.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.floor

/** Shared ink colour so icons sit next to the creature and the room without clashing. */
private val ItemOutline = Color(0xFF2B2118)

/**
 * Draws an inventory/shop icon by key.
 *
 * [variant] lets one key render several looks — the room themes all share the `room` key but
 * need distinct thumbnails. [shinePhase] (0f..1f, wrapping) sweeps an idle glint; 0 draws none.
 */
@Composable
fun ItemIcon(
    iconKey: String,
    tint: Color,
    modifier: Modifier = Modifier,
    variant: String? = null,
    shinePhase: Float = 0f,
) {
    Canvas(modifier = modifier) { drawItem(iconKey, tint, variant, shinePhase) }
}

// ------------------------------------------------------------------ shared treatment

/**
 * Contact shadow under the icon. Drawn first so the item always looks planted rather than
 * floating, which is most of what sells these shapes at pixel-mode sizes.
 */
private fun DrawScope.itemShadow(c: Offset, u: Float, radius: Float = 0.30f, dy: Float = 0.34f) {
    drawOval(
        color = ItemOutline.copy(alpha = 0.20f),
        topLeft = Offset(c.x - u * radius, c.y + u * dy - u * radius * 0.22f),
        size = Size(u * radius * 2f, u * radius * 0.44f),
    )
}

private fun DrawScope.outlinePath(path: Path, u: Float, weight: Float = 0.035f) {
    drawPath(path, ItemOutline, style = Stroke(width = u * weight))
}

private fun DrawScope.outlineCircle(center: Offset, radius: Float, u: Float, weight: Float = 0.035f) {
    drawCircle(ItemOutline, radius, center, style = Stroke(width = u * weight))
}

private fun DrawScope.outlineOval(topLeft: Offset, ovalSize: Size, u: Float, weight: Float = 0.035f) {
    drawOval(ItemOutline, topLeft = topLeft, size = ovalSize, style = Stroke(width = u * weight))
}

private fun DrawScope.outlineRoundRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius: Float,
    u: Float,
    weight: Float = 0.035f,
) {
    drawRoundRectCompat(x, y, w, h, radius, ItemOutline, stroke = u * weight)
}

/** One soft upper-left highlight per icon — enough to read as a light source, cheap to draw. */
private fun DrawScope.itemHighlight(
    c: Offset,
    u: Float,
    dx: Float = -0.14f,
    dy: Float = -0.14f,
    r: Float = 0.08f,
) {
    drawOval(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(c.x + u * dx - u * r, c.y + u * dy - u * r * 0.72f),
        size = Size(u * r * 2f, u * r * 1.44f),
    )
}

/** Idle glint. Kept as two thin bands so it survives the pixel-mode downsample as a streak. */
private fun DrawScope.itemShine(c: Offset, u: Float, phase: Float) {
    if (phase == 0f) return
    val t = phase - floor(phase)
    val x = c.x - u * 0.55f + u * 1.10f * t
    rotate(24f, Offset(x, c.y)) {
        drawRect(
            color = Color.White.copy(alpha = 0.20f),
            topLeft = Offset(x - u * 0.05f, c.y - u * 0.40f),
            size = Size(u * 0.07f, u * 0.80f),
        )
        drawRect(
            color = Color.White.copy(alpha = 0.12f),
            topLeft = Offset(x + u * 0.05f, c.y - u * 0.40f),
            size = Size(u * 0.03f, u * 0.80f),
        )
    }
}

// ------------------------------------------------------------------ icons

fun DrawScope.drawItem(
    iconKey: String,
    tint: Color,
    variant: String? = null,
    shinePhase: Float = 0f,
) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val u = size.minDimension

    when (iconKey) {
        "bowl" -> {
            itemShadow(c, u)
            drawOval(
                Color(0xFF8A5A2B),
                topLeft = Offset(c.x - u * 0.30f, c.y - u * 0.16f),
                size = Size(u * 0.60f, u * 0.20f),
            )
            val bowl = Path().apply {
                moveTo(c.x - u * 0.32f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.32f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.22f, c.y + u * 0.24f)
                lineTo(c.x - u * 0.22f, c.y + u * 0.24f)
                close()
            }
            drawPath(bowl, tint)
            outlinePath(bowl, u)
            outlineOval(Offset(c.x - u * 0.30f, c.y - u * 0.16f), Size(u * 0.60f, u * 0.20f), u, 0.028f)
            itemHighlight(c, u, dx = -0.18f, dy = 0.08f, r = 0.07f)
        }
        "stew" -> {
            itemShadow(c, u, radius = 0.28f, dy = 0.32f)
            repeat(3) { i ->
                val x = c.x - u * 0.16f + i * u * 0.16f
                drawArc(
                    Color.White.copy(alpha = 0.6f), 200f, 140f, false,
                    Offset(x - u * 0.05f, c.y - u * 0.48f), Size(u * 0.10f, u * 0.18f),
                    style = Stroke(width = u * 0.03f),
                )
            }
            drawArc(tint, 0f, 180f, true, Offset(c.x - u * 0.30f, c.y - u * 0.20f), Size(u * 0.60f, u * 0.50f))
            drawArc(
                ItemOutline, 0f, 180f, true,
                Offset(c.x - u * 0.30f, c.y - u * 0.20f), Size(u * 0.60f, u * 0.50f),
                style = Stroke(width = u * 0.035f),
            )
            drawOval(
                Color(0xFFCF8F5C),
                topLeft = Offset(c.x - u * 0.32f, c.y - u * 0.28f),
                size = Size(u * 0.64f, u * 0.16f),
            )
            outlineOval(Offset(c.x - u * 0.32f, c.y - u * 0.28f), Size(u * 0.64f, u * 0.16f), u, 0.03f)
            itemHighlight(c, u, dx = -0.16f, dy = 0.04f, r = 0.07f)
        }
        "salad" -> {
            itemShadow(c, u, radius = 0.28f, dy = 0.30f)
            repeat(4) { i ->
                rotate(-40f + i * 28f, c) {
                    drawOval(tint, topLeft = Offset(c.x - u * 0.08f, c.y - u * 0.30f), size = Size(u * 0.16f, u * 0.28f))
                    outlineOval(Offset(c.x - u * 0.08f, c.y - u * 0.30f), Size(u * 0.16f, u * 0.28f), u, 0.025f)
                }
            }
            drawArc(Color(0xFFF2F0E6), 0f, 180f, true, Offset(c.x - u * 0.30f, c.y - u * 0.18f), Size(u * 0.60f, u * 0.46f))
            drawArc(
                ItemOutline, 0f, 180f, true,
                Offset(c.x - u * 0.30f, c.y - u * 0.18f), Size(u * 0.60f, u * 0.46f),
                style = Stroke(width = u * 0.035f),
            )
            itemHighlight(c, u, dx = -0.16f, dy = 0.02f, r = 0.07f)
        }
        "sushi" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.24f)
            drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.16f, u * 0.52f, u * 0.32f, u * 0.06f, tint)
            drawRect(
                Color(0xFF2C3B2E),
                topLeft = Offset(c.x - u * 0.09f, c.y - u * 0.16f),
                size = Size(u * 0.18f, u * 0.32f),
            )
            drawOval(
                Color(0xFFFF8A80),
                topLeft = Offset(c.x - u * 0.11f, c.y - u * 0.26f),
                size = Size(u * 0.22f, u * 0.14f),
            )
            outlineOval(Offset(c.x - u * 0.11f, c.y - u * 0.26f), Size(u * 0.22f, u * 0.14f), u, 0.028f)
            outlineRoundRect(c.x - u * 0.26f, c.y - u * 0.16f, u * 0.52f, u * 0.32f, u * 0.06f, u)
            itemHighlight(c, u, dx = -0.17f, dy = -0.06f, r = 0.06f)
        }
        "berry" -> {
            itemShadow(c, u, radius = 0.24f, dy = 0.28f)
            drawPath(
                Path().apply {
                    moveTo(c.x, c.y - u * 0.12f)
                    quadraticBezierTo(c.x + u * 0.22f, c.y - u * 0.38f, c.x + u * 0.02f, c.y - u * 0.32f)
                    close()
                },
                Color(0xFF4FA85B),
            )
            drawCircle(tint, u * 0.20f, Offset(c.x - u * 0.09f, c.y + u * 0.04f))
            outlineCircle(Offset(c.x - u * 0.09f, c.y + u * 0.04f), u * 0.20f, u, 0.03f)
            drawCircle(tint, u * 0.16f, Offset(c.x + u * 0.13f, c.y + u * 0.02f))
            outlineCircle(Offset(c.x + u * 0.13f, c.y + u * 0.02f), u * 0.16f, u, 0.03f)
            itemHighlight(c, u, dx = -0.16f, dy = -0.05f, r = 0.06f)
        }
        "cake" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.26f)
            drawLine(Color(0xFFFF8A5B), Offset(c.x, c.y - u * 0.14f), Offset(c.x, c.y - u * 0.34f), strokeWidth = u * 0.06f)
            drawCircle(Color(0xFFFFD447), u * 0.06f, Offset(c.x, c.y - u * 0.39f))
            drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.06f, u * 0.52f, u * 0.28f, u * 0.04f, Color(0xFFF6E1C3))
            drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.16f, u * 0.52f, u * 0.14f, u * 0.04f, tint)
            outlineRoundRect(c.x - u * 0.26f, c.y - u * 0.16f, u * 0.52f, u * 0.38f, u * 0.05f, u)
            drawLine(
                ItemOutline.copy(alpha = 0.55f),
                Offset(c.x - u * 0.26f, c.y - u * 0.02f),
                Offset(c.x + u * 0.26f, c.y - u * 0.02f),
                strokeWidth = u * 0.025f,
            )
            itemHighlight(c, u, dx = -0.17f, dy = -0.10f, r = 0.06f)
        }
        "icecream" -> {
            itemShadow(c, u, radius = 0.20f, dy = 0.36f)
            val cone = Path().apply {
                moveTo(c.x - u * 0.17f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.17f, c.y - u * 0.02f)
                lineTo(c.x, c.y + u * 0.34f)
                close()
            }
            drawPath(cone, Color(0xFFD9A05B))
            outlinePath(cone, u)
            drawCircle(tint, u * 0.18f, Offset(c.x + u * 0.03f, c.y - u * 0.12f))
            outlineCircle(Offset(c.x + u * 0.03f, c.y - u * 0.12f), u * 0.18f, u, 0.03f)
            drawCircle(Color(0xFFFFC0D0), u * 0.13f, Offset(c.x - u * 0.09f, c.y - u * 0.26f))
            outlineCircle(Offset(c.x - u * 0.09f, c.y - u * 0.26f), u * 0.13f, u, 0.03f)
            itemHighlight(c, u, dx = -0.13f, dy = -0.30f, r = 0.05f)
        }
        "gum" -> {
            // A slim slanted stick with a torn foil top — nothing else in the set is tall+narrow.
            itemShadow(c, u, radius = 0.20f, dy = 0.34f)
            rotate(-20f, c) {
                val x = c.x - u * 0.16f
                val y = c.y - u * 0.32f
                drawRoundRectCompat(x, y, u * 0.32f, u * 0.64f, u * 0.05f, Color(0xFFF7F3E7))
                drawRoundRectCompat(x, y, u * 0.32f, u * 0.30f, u * 0.05f, tint)
                drawLine(
                    ItemOutline.copy(alpha = 0.6f),
                    Offset(x, y + u * 0.30f),
                    Offset(x + u * 0.32f, y + u * 0.30f),
                    strokeWidth = u * 0.025f,
                )
                drawPath(
                    Path().apply {
                        moveTo(c.x + u * 0.05f, c.y + u * 0.02f)
                        lineTo(c.x - u * 0.08f, c.y + u * 0.16f)
                        lineTo(c.x - u * 0.01f, c.y + u * 0.16f)
                        lineTo(c.x - u * 0.05f, c.y + u * 0.27f)
                        lineTo(c.x + u * 0.08f, c.y + u * 0.13f)
                        lineTo(c.x + u * 0.01f, c.y + u * 0.13f)
                        close()
                    },
                    Color(0xFF7A5B00),
                )
                outlineRoundRect(x, y, u * 0.32f, u * 0.64f, u * 0.05f, u)
            }
            itemHighlight(c, u, dx = -0.13f, dy = -0.20f, r = 0.05f)
        }
        "pill" -> {
            itemShadow(c, u, radius = 0.24f, dy = 0.28f)
            rotate(-35f, c) {
                drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.13f, u * 0.52f, u * 0.26f, u * 0.13f, Color(0xFFF4F6FA))
                drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.13f, u * 0.26f, u * 0.26f, u * 0.13f, Color(0xFFFF6B57))
                outlineRoundRect(c.x - u * 0.26f, c.y - u * 0.13f, u * 0.52f, u * 0.26f, u * 0.13f, u, 0.03f)
            }
            itemHighlight(c, u, dx = -0.14f, dy = -0.12f, r = 0.06f)
        }
        "elixir" -> {
            // Round-bottom flask: fat bulb, pinched neck, cork. Reads as glassware, not a blob.
            itemShadow(c, u, radius = 0.24f, dy = 0.34f)
            val flask = Path().apply {
                moveTo(c.x - u * 0.08f, c.y - u * 0.28f)
                lineTo(c.x - u * 0.08f, c.y - u * 0.08f)
                quadraticBezierTo(c.x - u * 0.34f, c.y + u * 0.04f, c.x - u * 0.20f, c.y + u * 0.22f)
                quadraticBezierTo(c.x, c.y + u * 0.40f, c.x + u * 0.20f, c.y + u * 0.22f)
                quadraticBezierTo(c.x + u * 0.34f, c.y + u * 0.04f, c.x + u * 0.08f, c.y - u * 0.08f)
                lineTo(c.x + u * 0.08f, c.y - u * 0.28f)
                close()
            }
            drawPath(flask, tint.copy(alpha = 0.9f))
            drawOval(
                Color.White.copy(alpha = 0.22f),
                topLeft = Offset(c.x - u * 0.18f, c.y + u * 0.04f),
                size = Size(u * 0.36f, u * 0.14f),
            )
            drawCircle(Color.White.copy(alpha = 0.55f), u * 0.035f, Offset(c.x + u * 0.08f, c.y + u * 0.16f))
            outlinePath(flask, u, 0.04f)
            drawRoundRectCompat(c.x - u * 0.12f, c.y - u * 0.40f, u * 0.24f, u * 0.13f, u * 0.03f, Color(0xFFC79A5B))
            outlineRoundRect(c.x - u * 0.12f, c.y - u * 0.40f, u * 0.24f, u * 0.13f, u * 0.03f, u, 0.03f)
            itemHighlight(c, u, dx = -0.15f, dy = 0.08f, r = 0.06f)
        }
        "soap" -> {
            // Chunky bar plus a cluster of solid bubbles; the old thin bubble rings vanished small.
            itemShadow(c, u, radius = 0.28f, dy = 0.32f)
            listOf(
                Triple(-0.15f, -0.12f, 0.13f),
                Triple(0.07f, -0.26f, 0.09f),
                Triple(0.21f, -0.06f, 0.06f),
            ).forEach { (dx, dy, r) ->
                val center = Offset(c.x + u * dx, c.y + u * dy)
                drawCircle(Color(0xFFEAF8FF).copy(alpha = 0.92f), u * r, center)
                outlineCircle(center, u * r, u, 0.028f)
            }
            drawRoundRectCompat(c.x - u * 0.28f, c.y + u * 0.04f, u * 0.56f, u * 0.24f, u * 0.08f, tint)
            outlineRoundRect(c.x - u * 0.28f, c.y + u * 0.04f, u * 0.56f, u * 0.24f, u * 0.08f, u)
            drawLine(
                Color.White.copy(alpha = 0.45f),
                Offset(c.x - u * 0.18f, c.y + u * 0.11f),
                Offset(c.x + u * 0.10f, c.y + u * 0.11f),
                strokeWidth = u * 0.03f,
            )
            itemHighlight(c, u, dx = -0.18f, dy = -0.14f, r = 0.05f)
        }
        "ball" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.30f)
            drawCircle(tint, u * 0.26f, c)
            drawArc(
                Color.White, 200f, 140f, false,
                Offset(c.x - u * 0.26f, c.y - u * 0.26f), Size(u * 0.52f, u * 0.52f),
                style = Stroke(width = u * 0.05f),
            )
            outlineCircle(c, u * 0.26f, u, 0.035f)
            itemHighlight(c, u, dx = -0.11f, dy = -0.13f, r = 0.06f)
        }
        "drum" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.26f)
            drawLine(
                Color(0xFF8A5A2B),
                Offset(c.x + u * 0.18f, c.y - u * 0.30f),
                Offset(c.x + u * 0.34f, c.y - u * 0.46f),
                strokeWidth = u * 0.045f,
            )
            drawRoundRectCompat(c.x - u * 0.24f, c.y - u * 0.14f, u * 0.48f, u * 0.28f, u * 0.06f, tint)
            outlineRoundRect(c.x - u * 0.24f, c.y - u * 0.14f, u * 0.48f, u * 0.28f, u * 0.06f, u)
            drawOval(
                Color(0xFFF2E6C9),
                topLeft = Offset(c.x - u * 0.26f, c.y - u * 0.24f),
                size = Size(u * 0.52f, u * 0.18f),
            )
            outlineOval(Offset(c.x - u * 0.26f, c.y - u * 0.24f), Size(u * 0.52f, u * 0.18f), u)
            itemHighlight(c, u, dx = -0.12f, dy = -0.18f, r = 0.06f)
        }
        "cards" -> {
            // A fanned hand: three cards on one pivot, so the silhouette spreads instead of stacking.
            itemShadow(c, u, radius = 0.28f, dy = 0.30f)
            val pivot = Offset(c.x, c.y + u * 0.30f)
            val faces = listOf(-26f to tint, -3f to Color(0xFFF4F6FA), 20f to Color(0xFFF4F6FA))
            faces.forEach { (angle, fill) ->
                rotate(angle, pivot) {
                    drawRoundRectCompat(c.x - u * 0.13f, c.y - u * 0.24f, u * 0.26f, u * 0.44f, u * 0.04f, fill)
                    outlineRoundRect(c.x - u * 0.13f, c.y - u * 0.24f, u * 0.26f, u * 0.44f, u * 0.04f, u, 0.03f)
                }
            }
            rotate(20f, pivot) {
                drawPath(
                    Path().apply {
                        moveTo(c.x, c.y - u * 0.14f)
                        lineTo(c.x + u * 0.08f, c.y - u * 0.03f)
                        lineTo(c.x, c.y + u * 0.08f)
                        lineTo(c.x - u * 0.08f, c.y - u * 0.03f)
                        close()
                    },
                    tint,
                )
            }
            itemHighlight(c, u, dx = -0.20f, dy = -0.10f, r = 0.05f)
        }
        "cap" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.16f)
            drawOval(
                Color(0xFFC22A1A),
                topLeft = Offset(c.x - u * 0.02f, c.y - u * 0.02f),
                size = Size(u * 0.38f, u * 0.11f),
            )
            outlineOval(Offset(c.x - u * 0.02f, c.y - u * 0.02f), Size(u * 0.38f, u * 0.11f), u, 0.03f)
            drawArc(tint, 180f, 180f, true, Offset(c.x - u * 0.28f, c.y - u * 0.24f), Size(u * 0.56f, u * 0.46f))
            drawArc(
                ItemOutline, 180f, 180f, true,
                Offset(c.x - u * 0.28f, c.y - u * 0.24f), Size(u * 0.56f, u * 0.46f),
                style = Stroke(width = u * 0.035f),
            )
            itemHighlight(c, u, dx = -0.13f, dy = -0.12f, r = 0.06f)
        }
        "crown" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.22f)
            val crown = Path().apply {
                moveTo(c.x - u * 0.26f, c.y + u * 0.16f)
                lineTo(c.x - u * 0.26f, c.y - u * 0.20f)
                lineTo(c.x - u * 0.10f, c.y - u * 0.02f)
                lineTo(c.x, c.y - u * 0.28f)
                lineTo(c.x + u * 0.10f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.26f, c.y - u * 0.20f)
                lineTo(c.x + u * 0.26f, c.y + u * 0.16f)
                close()
            }
            drawPath(crown, tint)
            outlinePath(crown, u)
            drawCircle(Color(0xFFE0555F), u * 0.05f, Offset(c.x, c.y + u * 0.06f))
            itemHighlight(c, u, dx = -0.17f, dy = -0.02f, r = 0.05f)
        }
        "bow" -> {
            // Two swept loops, a knot, and two tails — a butterfly outline, not two ovals.
            itemShadow(c, u, radius = 0.24f, dy = 0.30f)
            val tails = Path().apply {
                moveTo(c.x - u * 0.06f, c.y + u * 0.02f)
                lineTo(c.x - u * 0.22f, c.y + u * 0.34f)
                lineTo(c.x - u * 0.02f, c.y + u * 0.22f)
                close()
                moveTo(c.x + u * 0.06f, c.y + u * 0.02f)
                lineTo(c.x + u * 0.22f, c.y + u * 0.34f)
                lineTo(c.x + u * 0.02f, c.y + u * 0.22f)
                close()
            }
            drawPath(tails, tint)
            outlinePath(tails, u, 0.03f)
            listOf(-1f, 1f).forEach { side ->
                val loop = Path().apply {
                    moveTo(c.x + side * u * 0.03f, c.y - u * 0.02f)
                    quadraticBezierTo(
                        c.x + side * u * 0.38f, c.y - u * 0.30f,
                        c.x + side * u * 0.30f, c.y + u * 0.04f,
                    )
                    quadraticBezierTo(
                        c.x + side * u * 0.26f, c.y + u * 0.24f,
                        c.x + side * u * 0.03f, c.y + u * 0.06f,
                    )
                    close()
                }
                drawPath(loop, tint)
                outlinePath(loop, u, 0.032f)
            }
            drawCircle(tint, u * 0.09f, c)
            outlineCircle(c, u * 0.09f, u, 0.03f)
            itemHighlight(c, u, dx = -0.20f, dy = -0.10f, r = 0.05f)
        }
        "goggles" -> {
            itemShadow(c, u, radius = 0.28f, dy = 0.22f)
            drawLine(ItemOutline, Offset(c.x - u * 0.34f, c.y), Offset(c.x + u * 0.34f, c.y), strokeWidth = u * 0.06f)
            listOf(-1f, 1f).forEach { side ->
                val eye = Offset(c.x + side * u * 0.17f, c.y)
                drawCircle(tint.copy(alpha = 0.85f), u * 0.16f, eye)
                outlineCircle(eye, u * 0.16f, u, 0.04f)
            }
            itemHighlight(c, u, dx = -0.21f, dy = -0.07f, r = 0.05f)
        }
        "leafhat" -> {
            itemShadow(c, u, radius = 0.26f, dy = 0.16f)
            val leaf = Path().apply {
                moveTo(c.x - u * 0.30f, c.y + u * 0.08f)
                quadraticBezierTo(c.x, c.y - u * 0.38f, c.x + u * 0.30f, c.y + u * 0.08f)
                quadraticBezierTo(c.x, c.y - u * 0.06f, c.x - u * 0.30f, c.y + u * 0.08f)
                close()
            }
            drawPath(leaf, tint)
            outlinePath(leaf, u)
            drawLine(
                ItemOutline.copy(alpha = 0.5f),
                Offset(c.x - u * 0.18f, c.y + u * 0.03f),
                Offset(c.x + u * 0.18f, c.y + u * 0.03f),
                strokeWidth = u * 0.02f,
            )
            itemHighlight(c, u, dx = -0.14f, dy = -0.12f, r = 0.05f)
        }
        "room" -> drawRoomThumb(c, u, tint, variant)
        else -> {
            itemShadow(c, u, radius = 0.24f, dy = 0.28f)
            drawCircle(tint, u * 0.24f, c)
            outlineCircle(c, u * 0.24f, u)
            itemHighlight(c, u, dx = -0.10f, dy = -0.11f, r = 0.06f)
        }
    }

    itemShine(c, u, shinePhase)
}

// ------------------------------------------------------------------ room thumbnails

/**
 * A framed miniature of the theme. All five share the frame + floor so they read as a set,
 * and differ only in the two or three props inside, which is all that survives at 44dp.
 */
private fun DrawScope.drawRoomThumb(c: Offset, u: Float, tint: Color, variant: String?) {
    itemShadow(c, u, radius = 0.32f, dy = 0.32f)

    val left = c.x - u * 0.32f
    val top = c.y - u * 0.28f
    val w = u * 0.64f
    val h = u * 0.54f
    val floorH = u * 0.13f
    val floorTop = top + h - floorH

    drawRoundRectCompat(left, top, w, h, u * 0.06f, tint)

    val floor = when (variant) {
        "room_beach" -> Color(0xFFE8C88A)
        "room_space" -> Color(0xFF1B1D3A)
        "room_forest" -> Color(0xFF3E5B33)
        "room_arcade" -> Color(0xFF2A1240)
        else -> Color(0xFF3A2C1E)
    }
    drawRect(floor, topLeft = Offset(left, floorTop), size = Size(w, floorH))

    when (variant) {
        "room_beach" -> {
            drawCircle(Color(0xFFFFE066), u * 0.08f, Offset(left + w * 0.74f, top + h * 0.26f))
            repeat(2) { i ->
                drawArc(
                    Color(0xFF7FD4F5), 200f, 140f, false,
                    Offset(left + w * (0.10f + i * 0.34f), floorTop - u * 0.10f),
                    Size(w * 0.34f, u * 0.14f),
                    style = Stroke(width = u * 0.03f),
                )
            }
            drawLine(
                Color(0xFFB4E0F0),
                Offset(left, floorTop),
                Offset(left + w, floorTop),
                strokeWidth = u * 0.025f,
            )
        }
        "room_space" -> {
            listOf(0.18f to 0.22f, 0.44f to 0.14f, 0.82f to 0.34f).forEach { (fx, fy) ->
                drawCircle(Color.White, u * 0.02f, Offset(left + w * fx, top + h * fy))
            }
            val planet = Offset(left + w * 0.66f, top + h * 0.42f)
            drawCircle(Color(0xFF7FA6F5), u * 0.09f, planet)
            outlineCircle(planet, u * 0.09f, u, 0.028f)
            rotate(-20f, planet) {
                drawOval(
                    Color(0xFFF5C542),
                    topLeft = Offset(planet.x - u * 0.15f, planet.y - u * 0.035f),
                    size = Size(u * 0.30f, u * 0.07f),
                    style = Stroke(width = u * 0.025f),
                )
            }
        }
        "room_forest" -> {
            listOf(0.28f to 0.20f, 0.66f to 0.14f).forEach { (fx, scale) ->
                val baseX = left + w * fx
                val tree = Path().apply {
                    moveTo(baseX, floorTop - u * (0.06f + scale))
                    lineTo(baseX + u * 0.12f, floorTop)
                    lineTo(baseX - u * 0.12f, floorTop)
                    close()
                }
                drawPath(tree, Color(0xFF4FA85B))
                outlinePath(tree, u, 0.028f)
            }
            drawCircle(Color(0xFFFFE066), u * 0.022f, Offset(left + w * 0.46f, top + h * 0.32f))
        }
        "room_arcade" -> {
            val cabX = left + w * 0.28f
            val cabTop = top + h * 0.22f
            val cabW = u * 0.20f
            val cabH = floorTop - cabTop
            drawRoundRectCompat(cabX, cabTop, cabW, cabH, u * 0.03f, Color(0xFF1A0E2B))
            drawRect(
                Color(0xFF3BE8C8),
                topLeft = Offset(cabX + cabW * 0.15f, cabTop + cabH * 0.14f),
                size = Size(cabW * 0.70f, cabH * 0.40f),
            )
            outlineRoundRect(cabX, cabTop, cabW, cabH, u * 0.03f, u, 0.028f)
            listOf(0.62f to 0.30f, 0.74f to 0.22f).forEach { (fx, fy) ->
                drawCircle(Color(0xFFFF5FD0), u * 0.035f, Offset(left + w * fx, top + h * fy))
            }
            drawLine(
                Color(0xFFFF5FD0),
                Offset(left + w * 0.58f, top + h * 0.52f),
                Offset(left + w * 0.86f, top + h * 0.52f),
                strokeWidth = u * 0.03f,
            )
        }
        else -> {
            val winX = left + w * 0.20f
            val winY = top + h * 0.18f
            val winS = u * 0.20f
            drawRect(Color.White.copy(alpha = 0.8f), topLeft = Offset(winX, winY), size = Size(winS, winS))
            drawLine(ItemOutline, Offset(winX + winS / 2f, winY), Offset(winX + winS / 2f, winY + winS), strokeWidth = u * 0.02f)
            drawLine(ItemOutline, Offset(winX, winY + winS / 2f), Offset(winX + winS, winY + winS / 2f), strokeWidth = u * 0.02f)
            drawRoundRectCompat(left + w * 0.60f, floorTop - u * 0.10f, u * 0.20f, u * 0.10f, u * 0.02f, Color(0xFFCF8F5C))
            outlineRoundRect(left + w * 0.60f, floorTop - u * 0.10f, u * 0.20f, u * 0.10f, u * 0.02f, u, 0.025f)
        }
    }

    drawLine(ItemOutline, Offset(left, floorTop), Offset(left + w, floorTop), strokeWidth = u * 0.025f)
    outlineRoundRect(left, top, w, h, u * 0.06f, u, 0.04f)
    itemHighlight(c, u, dx = -0.22f, dy = -0.20f, r = 0.06f)
}
