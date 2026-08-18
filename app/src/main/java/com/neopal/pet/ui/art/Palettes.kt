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

/** Colors for one room, in day and night flavours. */
data class RoomPalette(
    val wallTop: Color,
    val wallBottom: Color,
    val floor: Color,
    val floorShade: Color,
    val prop: Color,
    val propAccent: Color,
    val sky: Color,
    val nightTint: Color = Color(0xFF0B1030),
)

object Palettes {

    fun creature(species: Species, branch: EvolutionBranch): CreaturePalette {
        val base = when (species) {
            Species.AQUA -> CreaturePalette(
                body = Color(0xFF57C6F0),
                bodyShade = Color(0xFF2E9BC9),
                belly = Color(0xFFDDF4FF),
                accent = Color(0xFF1F6FA8),
                outline = Color(0xFF14364F),
            )
            Species.EMBER -> CreaturePalette(
                body = Color(0xFFFF8A5B),
                bodyShade = Color(0xFFE05C33),
                belly = Color(0xFFFFE3C9),
                accent = Color(0xFFC33C1C),
                outline = Color(0xFF5A2110),
            )
            Species.LEAF -> CreaturePalette(
                body = Color(0xFF7ED27F),
                bodyShade = Color(0xFF4FA85B),
                belly = Color(0xFFEAF8DC),
                accent = Color(0xFF2F7A3F),
                outline = Color(0xFF1D4423),
            )
            Species.VOLT -> CreaturePalette(
                body = Color(0xFFFFDD57),
                bodyShade = Color(0xFFE8B72E),
                belly = Color(0xFFFFF7D6),
                accent = Color(0xFFB07A00),
                outline = Color(0xFF4E3A05),
            )
        }
        // Branches recolor rather than replace, so a family still reads as one family.
        return when (branch) {
            EvolutionBranch.BALANCED -> base
            EvolutionBranch.ATHLETIC -> base.copy(
                accent = lerp(base.accent, Color(0xFF00C3E3), 0.45f),
                body = lerp(base.body, Color.White, 0.06f),
            )
            EvolutionBranch.GOURMAND -> base.copy(
                belly = lerp(base.belly, Color(0xFFFFC98F), 0.4f),
                body = lerp(base.body, Color(0xFFFFB36B), 0.12f),
            )
            EvolutionBranch.SCHOLAR -> base.copy(
                accent = lerp(base.accent, Color(0xFF9C6BFF), 0.5f),
                body = lerp(base.body, Color(0xFFBFA9FF), 0.10f),
            )
            EvolutionBranch.FERAL -> base.copy(
                body = lerp(base.body, Color(0xFF3B3B4A), 0.28f),
                bodyShade = lerp(base.bodyShade, Color.Black, 0.25f),
                accent = lerp(base.accent, Color(0xFFFF3C28), 0.4f),
                eye = Color(0xFFFF3C28),
            )
        }
    }

    fun room(themeId: String): RoomPalette = when (themeId) {
        "room_beach" -> RoomPalette(
            wallTop = Color(0xFFFFB067),
            wallBottom = Color(0xFFFF8A5B),
            floor = Color(0xFFF6DCA8),
            floorShade = Color(0xFFE0BE84),
            prop = Color(0xFF3FA9C9),
            propAccent = Color(0xFFFFFFFF),
            sky = Color(0xFFFFD9A0),
        )
        "room_space" -> RoomPalette(
            wallTop = Color(0xFF1B1E4A),
            wallBottom = Color(0xFF2B2D6E),
            floor = Color(0xFF3A3D74),
            floorShade = Color(0xFF272A55),
            prop = Color(0xFF8FA0FF),
            propAccent = Color(0xFF00C3E3),
            sky = Color(0xFF0B0D24),
            nightTint = Color(0xFF05061A),
        )
        "room_forest" -> RoomPalette(
            wallTop = Color(0xFF3E8F63),
            wallBottom = Color(0xFF2E6B4F),
            floor = Color(0xFF6B4F32),
            floorShade = Color(0xFF503A24),
            prop = Color(0xFF9BD98A),
            propAccent = Color(0xFFFFE066),
            sky = Color(0xFF8FD0A8),
        )
        "room_arcade" -> RoomPalette(
            wallTop = Color(0xFF4B1E7A),
            wallBottom = Color(0xFF6B2BA5),
            floor = Color(0xFF2B1240),
            floorShade = Color(0xFF1D0C2C),
            prop = Color(0xFF00C3E3),
            propAccent = Color(0xFFFF3C28),
            sky = Color(0xFF2A0F43),
            nightTint = Color(0xFF10041B),
        )
        else -> RoomPalette(
            wallTop = Color(0xFF6FA8D6),
            wallBottom = Color(0xFF3A6EA5),
            floor = Color(0xFFC9A277),
            floorShade = Color(0xFFA8814F),
            prop = Color(0xFFF2E6C9),
            propAccent = Color(0xFFFF8A5B),
            sky = Color(0xFF9CD3F5),
        )
    }

    /** Blends a room toward its night version. [night] is 0 at noon, 1 in the middle of the night. */
    fun applyNight(palette: RoomPalette, night: Float): RoomPalette {
        val t = night.coerceIn(0f, 1f) * 0.72f
        fun mix(c: Color) = lerp(c, palette.nightTint, t)
        return palette.copy(
            wallTop = mix(palette.wallTop),
            wallBottom = mix(palette.wallBottom),
            floor = mix(palette.floor),
            floorShade = mix(palette.floorShade),
            prop = mix(palette.prop),
            propAccent = lerp(palette.propAccent, palette.nightTint, t * 0.4f),
            sky = mix(palette.sky),
        )
    }
}
