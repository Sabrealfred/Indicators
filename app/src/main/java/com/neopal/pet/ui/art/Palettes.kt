package com.neopal.pet.ui.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.Species

/** Colors for one creature. Everything drawn on screen is derived from these five. */
data class CreaturePalette(
    val body: Color,
    val bodyShade: Color,
    val belly: Color,
    val accent: Color,
    val outline: Color,
    val eye: Color = Color(0xFF1B1D22),
    val blush: Color = Color(0xFFFF8FA3),
)

/**
 * Colors for one room, in day and night flavours.
 *
 * These are a **ramp**, not a bag of colours: [highlight], [wallTop], [wallBottom], [propShade]
 * and [shadow] are five steps of one family, and [propAccent] is the single hue allowed to
 * disagree with it. Rooms built this way read as designed at 200 pixels tall; rooms built from
 * six unrelated hues read as clip art no matter how carefully each shape is drawn.
 */
data class RoomPalette(
    val wallTop: Color,
    val wallBottom: Color,
    val floor: Color,
    val floorShade: Color,
    val prop: Color,
    val propAccent: Color,
    val sky: Color,
    val nightTint: Color = Color(0xFF0B1030),
    /** Deepest step of the ramp: contact shadow, the line where wall meets floor, prop outlines. */
    val shadow: Color = Color(0xFF2B2434),
    /** Lightest step: lit edges, foam, glass, the top of a prop facing the window. */
    val highlight: Color = Color(0xFFFFF6E4),
    /** The dark step of the prop family, so every prop can be drawn light / mid / shadow. */
    val propShade: Color = Color(0xFF4A4458),
)

object Palettes {

    fun creature(species: Species, branch: EvolutionBranch): CreaturePalette {
        val base = when (species) {
            // Softer, slightly desaturated bodies: at 200px the creature is a big flat shape, and
            // full-chroma fills next to a full-chroma room are what made the old art shout.
            Species.AQUA -> CreaturePalette(
                body = Color(0xFF6FC8E8),
                bodyShade = Color(0xFF3F9AC4),
                belly = Color(0xFFE2F4FC),
                accent = Color(0xFF2E6F9E),
                outline = Color(0xFF1E3A4F),
            )
            Species.EMBER -> CreaturePalette(
                body = Color(0xFFFF9C72),
                bodyShade = Color(0xFFDE6B45),
                belly = Color(0xFFFFE7D2),
                accent = Color(0xFFBF4A2C),
                outline = Color(0xFF5A2A18),
            )
            Species.LEAF -> CreaturePalette(
                body = Color(0xFF8FD48C),
                bodyShade = Color(0xFF5CA968),
                belly = Color(0xFFEDF8DF),
                accent = Color(0xFF3A7B48),
                outline = Color(0xFF244728),
            )
            Species.VOLT -> CreaturePalette(
                body = Color(0xFFFFDD7A),
                bodyShade = Color(0xFFE5B84C),
                belly = Color(0xFFFFF6DC),
                accent = Color(0xFFB07F1E),
                outline = Color(0xFF4E3A12),
            )
        }
        // Branches recolor rather than replace, so a family still reads as one family.
        return when (branch) {
            EvolutionBranch.BALANCED -> base
            EvolutionBranch.ATHLETIC -> base.copy(
                accent = lerp(base.accent, Color(0xFF3FBFD8), 0.45f),
                body = lerp(base.body, Color.White, 0.06f),
            )
            EvolutionBranch.GOURMAND -> base.copy(
                belly = lerp(base.belly, Color(0xFFFFCE9E), 0.4f),
                body = lerp(base.body, Color(0xFFFFBC80), 0.12f),
            )
            EvolutionBranch.SCHOLAR -> base.copy(
                accent = lerp(base.accent, Color(0xFF9C7BEE), 0.5f),
                body = lerp(base.body, Color(0xFFC2B0F2), 0.10f),
            )
            EvolutionBranch.FERAL -> base.copy(
                body = lerp(base.body, Color(0xFF433F52), 0.28f),
                bodyShade = lerp(base.bodyShade, Color.Black, 0.25f),
                accent = lerp(base.accent, Color(0xFFE8523C), 0.4f),
                eye = Color(0xFFE8523C),
            )
        }
    }

    fun room(themeId: String): RoomPalette = when (themeId) {
        // Sand and sea: one warm ramp for everything the sun touches, one cool tone for water,
        // coral as the only accent. No pure white anywhere — foam is warm cream instead.
        "room_beach" -> RoomPalette(
            wallTop = Color(0xFFFFDCAE),
            wallBottom = Color(0xFFF7B481),
            floor = Color(0xFFF2DBA9),
            floorShade = Color(0xFFCFAE79),
            prop = Color(0xFF62BCCE),
            propAccent = Color(0xFFEF8367),
            sky = Color(0xFFFFE8C6),
            nightTint = Color(0xFF14203F),
            shadow = Color(0xFF8A5E44),
            highlight = Color(0xFFFFF4DC),
            propShade = Color(0xFF3A8299),
        )
        // Deep indigo, lit from the floor up as if the horizon were a nebula. Cyan is the accent
        // and the only bright thing in the room, so the eye always lands on it.
        "room_space" -> RoomPalette(
            wallTop = Color(0xFF161A3C),
            wallBottom = Color(0xFF2B3068),
            floor = Color(0xFF383D78),
            floorShade = Color(0xFF232750),
            prop = Color(0xFF8E9AE0),
            propAccent = Color(0xFF6FE0E8),
            sky = Color(0xFF0C0F26),
            nightTint = Color(0xFF05061A),
            shadow = Color(0xFF0C0E22),
            highlight = Color(0xFFCBD3FF),
            propShade = Color(0xFF525CA6),
        )
        // Canopy greens over a brown floor, with warm gold as the accent so the fireflies and
        // the sun through the leaves are the same colour — that repetition is the cohesion.
        "room_forest" -> RoomPalette(
            wallTop = Color(0xFF86C79C),
            wallBottom = Color(0xFF4F9270),
            floor = Color(0xFF7B5E3E),
            floorShade = Color(0xFF57432C),
            prop = Color(0xFF9BD69A),
            propAccent = Color(0xFFF0CE71),
            sky = Color(0xFFC4E7D0),
            nightTint = Color(0xFF0D1B31),
            shadow = Color(0xFF27412E),
            highlight = Color(0xFFE3F5DA),
            propShade = Color(0xFF4E8A5C),
        )
        // Purple cabinet light, neon pink accent. The room is dark so the accent can stay soft
        // and still read as neon; a saturated red on saturated purple is what buzzed before.
        "room_arcade" -> RoomPalette(
            wallTop = Color(0xFF3D1E60),
            wallBottom = Color(0xFF5B2E88),
            floor = Color(0xFF2B1642),
            floorShade = Color(0xFF1B0E2B),
            prop = Color(0xFF8E64C4),
            propAccent = Color(0xFFFF77AE),
            sky = Color(0xFF2A1145),
            nightTint = Color(0xFF10061C),
            shadow = Color(0xFF160A22),
            highlight = Color(0xFFE7CDFF),
            propShade = Color(0xFF4E2B78),
        )
        // Cozy room: dusty blue walls, oak floor, cream props, terracotta accent. Everything is
        // low chroma so the pet is the most saturated thing on screen.
        else -> RoomPalette(
            wallTop = Color(0xFFBCD0E2),
            wallBottom = Color(0xFF8FA9C6),
            floor = Color(0xFFC9A87F),
            floorShade = Color(0xFF9E7D57),
            prop = Color(0xFFEADCC0),
            propAccent = Color(0xFFE08A63),
            sky = Color(0xFFD6EAF7),
            nightTint = Color(0xFF141B36),
            shadow = Color(0xFF4B4353),
            highlight = Color(0xFFFFF6E6),
            propShade = Color(0xFFB29A78),
        )
    }

    /**
     * Blends a room toward its night version. [night] is 0 at noon, 1 in the middle of the night.
     *
     * Evening is a **hue shift**, not a dimmer. Red drains fastest, green a little slower and
     * blue is almost held up, which is how the eye reads late light; only then does the whole
     * thing settle toward the room's own [RoomPalette.nightTint]. Just multiplying everything
     * down gives you the same picture with the brightness turned off, which nobody reads as dusk.
     */
    fun applyNight(palette: RoomPalette, night: Float): RoomPalette {
        val t = night.coerceIn(0f, 1f)
        fun evening(c: Color, keep: Float = 1f): Color {
            val k = t * keep
            val shifted = Color(
                red = c.red * (1f - 0.55f * k),
                green = c.green * (1f - 0.40f * k),
                blue = c.blue * (1f - 0.22f * k),
                alpha = c.alpha,
            )
            return lerp(shifted, palette.nightTint.copy(alpha = c.alpha), 0.42f * k)
        }
        return palette.copy(
            wallTop = evening(palette.wallTop),
            wallBottom = evening(palette.wallBottom),
            floor = evening(palette.floor),
            floorShade = evening(palette.floorShade),
            prop = evening(palette.prop),
            // Lamps, neon and fireflies are the things that survive the evening, so the accent
            // and the top of the ramp are dimmed far less than the surfaces around them.
            propAccent = evening(palette.propAccent, keep = 0.30f),
            propShade = evening(palette.propShade),
            highlight = evening(palette.highlight, keep = 0.55f),
            shadow = evening(palette.shadow),
            sky = evening(palette.sky, keep = 1.15f),
        )
    }
}
