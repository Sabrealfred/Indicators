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

/** Draws an inventory/shop icon by key. Every icon is a handful of vector shapes. */
@Composable
fun ItemIcon(iconKey: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) { drawItem(iconKey, tint) }
}

fun DrawScope.drawItem(iconKey: String, tint: Color) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val u = size.minDimension
    val outline = Color(0xFF2B2118)

    when (iconKey) {
        "bowl" -> {
            val bowl = Path().apply {
                moveTo(c.x - u * 0.32f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.32f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.22f, c.y + u * 0.22f)
                lineTo(c.x - u * 0.22f, c.y + u * 0.22f)
                close()
            }
            drawOval(Color(0xFF8A5A2B), topLeft = Offset(c.x - u * 0.30f, c.y - u * 0.16f), size = Size(u * 0.60f, u * 0.20f))
            drawPath(bowl, tint)
            drawPath(bowl, outline, style = Stroke(width = u * 0.03f))
        }
        "stew" -> {
            drawArc(tint, 0f, 180f, true, Offset(c.x - u * 0.30f, c.y - u * 0.20f), Size(u * 0.60f, u * 0.50f))
            drawOval(Color(0xFFCF8F5C), topLeft = Offset(c.x - u * 0.30f, c.y - u * 0.26f), size = Size(u * 0.60f, u * 0.16f))
            repeat(3) { i ->
                val x = c.x - u * 0.16f + i * u * 0.16f
                drawArc(
                    Color.White.copy(alpha = 0.6f), 200f, 140f, false,
                    Offset(x - u * 0.05f, c.y - u * 0.46f), Size(u * 0.10f, u * 0.18f),
                    style = Stroke(width = u * 0.025f),
                )
            }
        }
        "salad" -> {
            drawArc(Color(0xFFF2F0E6), 0f, 180f, true, Offset(c.x - u * 0.30f, c.y - u * 0.18f), Size(u * 0.60f, u * 0.46f))
            repeat(4) { i ->
                rotate(-40f + i * 28f, c) {
                    drawOval(tint, topLeft = Offset(c.x - u * 0.08f, c.y - u * 0.28f), size = Size(u * 0.16f, u * 0.26f))
                }
            }
        }
        "sushi" -> {
            drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.16f, u * 0.52f, u * 0.32f, u * 0.06f, tint)
            drawRect(Color(0xFF2C3B2E), topLeft = Offset(c.x - u * 0.08f, c.y - u * 0.16f), size = Size(u * 0.16f, u * 0.32f))
            drawOval(Color(0xFFFF8A80), topLeft = Offset(c.x - u * 0.10f, c.y - u * 0.24f), size = Size(u * 0.20f, u * 0.12f))
        }
        "berry" -> {
            drawCircle(tint, u * 0.20f, Offset(c.x - u * 0.08f, c.y + u * 0.04f))
            drawCircle(tint, u * 0.16f, Offset(c.x + u * 0.12f, c.y + u * 0.02f))
            drawPath(
                Path().apply {
                    moveTo(c.x, c.y - u * 0.14f)
                    quadraticBezierTo(c.x + u * 0.18f, c.y - u * 0.34f, c.x + u * 0.02f, c.y - u * 0.30f)
                    close()
                },
                Color(0xFF4FA85B),
            )
        }
        "cake" -> {
            drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.06f, u * 0.52f, u * 0.28f, u * 0.04f, Color(0xFFF6E1C3))
            drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.14f, u * 0.52f, u * 0.12f, u * 0.04f, tint)
            drawLine(Color(0xFFFF8A5B), Offset(c.x, c.y - u * 0.14f), Offset(c.x, c.y - u * 0.34f), strokeWidth = u * 0.05f)
            drawCircle(Color(0xFFFFD447), u * 0.05f, Offset(c.x, c.y - u * 0.38f))
        }
        "icecream" -> {
            val cone = Path().apply {
                moveTo(c.x - u * 0.16f, c.y - u * 0.02f)
                lineTo(c.x + u * 0.16f, c.y - u * 0.02f)
                lineTo(c.x, c.y + u * 0.34f)
                close()
            }
            drawPath(cone, Color(0xFFD9A05B))
            drawCircle(tint, u * 0.18f, Offset(c.x, c.y - u * 0.12f))
            drawCircle(Color(0xFFFFC0D0), u * 0.12f, Offset(c.x - u * 0.08f, c.y - u * 0.24f))
        }
        "gum" -> {
            drawRoundRectCompat(c.x - u * 0.22f, c.y - u * 0.14f, u * 0.44f, u * 0.28f, u * 0.06f, tint)
            drawPath(
                Path().apply {
                    moveTo(c.x + u * 0.02f, c.y - u * 0.10f)
                    lineTo(c.x - u * 0.10f, c.y + u * 0.02f)
                    lineTo(c.x, c.y + u * 0.02f)
                    lineTo(c.x - u * 0.02f, c.y + u * 0.12f)
                    lineTo(c.x + u * 0.10f, c.y - u * 0.02f)
                    lineTo(c.x, c.y - u * 0.02f)
                    close()
                },
                Color(0xFF7A5B00),
            )
        }
        "pill" -> {
            rotate(-35f, c) {
                drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.12f, u * 0.52f, u * 0.24f, u * 0.12f, Color(0xFFF4F6FA))
                drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.12f, u * 0.26f, u * 0.24f, u * 0.12f, Color(0xFFFF6B57))
                drawRoundRectCompat(c.x - u * 0.26f, c.y - u * 0.12f, u * 0.52f, u * 0.24f, u * 0.12f, outline, stroke = u * 0.02f)
            }
        }
        "elixir" -> {
            drawPath(
                Path().apply {
                    moveTo(c.x - u * 0.10f, c.y - u * 0.30f)
                    lineTo(c.x + u * 0.10f, c.y - u * 0.30f)
                    lineTo(c.x + u * 0.10f, c.y - u * 0.14f)
                    lineTo(c.x + u * 0.22f, c.y + u * 0.24f)
                    lineTo(c.x - u * 0.22f, c.y + u * 0.24f)
                    lineTo(c.x - u * 0.10f, c.y - u * 0.14f)
                    close()
                },
                tint.copy(alpha = 0.85f),
            )
            drawRect(Color(0xFF8A6A00), topLeft = Offset(c.x - u * 0.12f, c.y - u * 0.36f), size = Size(u * 0.24f, u * 0.08f))
        }
        "soap" -> {
            drawRoundRectCompat(c.x - u * 0.24f, c.y - u * 0.06f, u * 0.48f, u * 0.24f, u * 0.08f, tint)
            listOf(0.16f to -0.22f, 0.10f to -0.34f, 0.07f to -0.14f).forEachIndexed { i, (r, dy) ->
                drawCircle(
                    Color.White.copy(alpha = 0.85f),
                    u * r,
                    Offset(c.x + u * (-0.10f + i * 0.12f), c.y + u * dy),
                    style = Stroke(width = u * 0.02f),
                )
            }
        }
        "ball" -> {
            drawCircle(tint, u * 0.26f, c)
            drawArc(
                Color.White, 200f, 140f, false,
                Offset(c.x - u * 0.26f, c.y - u * 0.26f), Size(u * 0.52f, u * 0.52f),
                style = Stroke(width = u * 0.05f),
            )
            drawCircle(outline, u * 0.26f, c, style = Stroke(width = u * 0.03f))
        }
        "drum" -> {
            drawRoundRectCompat(c.x - u * 0.24f, c.y - u * 0.14f, u * 0.48f, u * 0.28f, u * 0.06f, tint)
            drawOval(Color(0xFFF2E6C9), topLeft = Offset(c.x - u * 0.24f, c.y - u * 0.22f), size = Size(u * 0.48f, u * 0.16f))
            drawLine(Color(0xFF8A5A2B), Offset(c.x + u * 0.18f, c.y - u * 0.30f), Offset(c.x + u * 0.34f, c.y - u * 0.44f), strokeWidth = u * 0.035f)
        }
        "cards" -> {
            rotate(-12f, c) {
                drawRoundRectCompat(c.x - u * 0.24f, c.y - u * 0.22f, u * 0.34f, u * 0.44f, u * 0.04f, Color(0xFFF4F6FA))
            }
            rotate(10f, c) {
                drawRoundRectCompat(c.x - u * 0.10f, c.y - u * 0.22f, u * 0.34f, u * 0.44f, u * 0.04f, tint)
            }
        }
        "cap" -> {
            drawArc(tint, 180f, 180f, true, Offset(c.x - u * 0.28f, c.y - u * 0.20f), Size(u * 0.56f, u * 0.44f))
            drawOval(Color(0xFFC22A1A), topLeft = Offset(c.x, c.y - u * 0.02f), size = Size(u * 0.36f, u * 0.10f))
        }
        "crown" -> {
            drawPath(
                Path().apply {
                    moveTo(c.x - u * 0.26f, c.y + u * 0.16f)
                    lineTo(c.x - u * 0.26f, c.y - u * 0.18f)
                    lineTo(c.x - u * 0.10f, c.y - u * 0.02f)
                    lineTo(c.x, c.y - u * 0.26f)
                    lineTo(c.x + u * 0.10f, c.y - u * 0.02f)
                    lineTo(c.x + u * 0.26f, c.y - u * 0.18f)
                    lineTo(c.x + u * 0.26f, c.y + u * 0.16f)
                    close()
                },
                tint,
            )
        }
        "bow" -> {
            listOf(-1f, 1f).forEach { side ->
                drawOval(
                    tint,
                    topLeft = Offset(c.x + side * u * 0.04f - if (side < 0) u * 0.26f else 0f, c.y - u * 0.14f),
                    size = Size(u * 0.26f, u * 0.28f),
                )
            }
            drawCircle(Color(0xFFD44C87), u * 0.07f, c)
        }
        "goggles" -> {
            listOf(-1f, 1f).forEach { side ->
                drawCircle(tint.copy(alpha = 0.8f), u * 0.15f, Offset(c.x + side * u * 0.16f, c.y))
                drawCircle(outline, u * 0.15f, Offset(c.x + side * u * 0.16f, c.y), style = Stroke(width = u * 0.035f))
            }
            drawLine(outline, Offset(c.x - u * 0.32f, c.y), Offset(c.x + u * 0.32f, c.y), strokeWidth = u * 0.04f)
        }
        "leafhat" -> {
            drawPath(
                Path().apply {
                    moveTo(c.x - u * 0.30f, c.y + u * 0.08f)
                    quadraticBezierTo(c.x, c.y - u * 0.36f, c.x + u * 0.30f, c.y + u * 0.08f)
                    quadraticBezierTo(c.x, c.y - u * 0.06f, c.x - u * 0.30f, c.y + u * 0.08f)
                    close()
                },
                tint,
            )
        }
        "room" -> {
            drawRoundRectCompat(c.x - u * 0.30f, c.y - u * 0.24f, u * 0.60f, u * 0.48f, u * 0.05f, tint)
            drawRect(Color.White.copy(alpha = 0.75f), topLeft = Offset(c.x - u * 0.10f, c.y - u * 0.14f), size = Size(u * 0.22f, u * 0.20f))
            drawRect(Color(0xFF3A2C1E), topLeft = Offset(c.x - u * 0.30f, c.y + u * 0.10f), size = Size(u * 0.60f, u * 0.14f))
        }
        else -> {
            drawCircle(tint, u * 0.24f, c)
            drawCircle(outline, u * 0.24f, c, style = Stroke(width = u * 0.03f))
        }
    }
}
