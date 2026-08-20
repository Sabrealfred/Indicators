package com.neopal.pet.ui.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.Species
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * The fixed set of tones one room is allowed to paint with, in one lighting condition.
 *
 * A scene that mixes its colours freely ends up with hundreds of near-identical shades, and
 * hundreds of shades magnified through a nearest-neighbour filter read as blurred vector art
 * rather than as pixel art. Every derived colour is therefore pushed back onto this ramp before
 * it is drawn, which is the discipline the look was missing.
 *
 * Held as one flat [FloatArray] because [nearest] runs a few dozen times per frame: it walks the
 * whole ramp on every call and must never allocate while doing it.
 */
class ColorRamp internal constructor(colors: List<Color>) {

    private val count = colors.size
    private val channels = FloatArray(count * 3)

    init {
        for (i in 0 until count) {
            val c = colors[i]
            channels[i * 3] = c.red
            channels[i * 3 + 1] = c.green
            channels[i * 3 + 2] = c.blue
        }
    }

    /**
     * The ramp tone closest to [color], keeping its alpha. [strength] below 1 snaps only part
     * of the way, for the few passes that want to stay loose.
     *
     * A colour the ramp has no answer for is nudged instead of replaced. Forcing a distant
     * match would be worse than leaving the colour alone — a warm lamp would jump to the nearest
     * cold surface tone — so the pull fades out with distance and reaches zero well before the
     * match becomes a different hue.
     */
    fun nearest(color: Color, strength: Float = 1f): Color {
        if (count == 0 || strength <= 0f) return color
        val r = color.red
        val g = color.green
        val b = color.blue
        var best = 0
        var bestDistance = Float.MAX_VALUE
        var i = 0
        while (i < count) {
            val dr = r - channels[i * 3]
            val dg = g - channels[i * 3 + 1]
            val db = b - channels[i * 3 + 2]
            // Weighted toward green, the channel the eye reads brightness from.
            val d = dr * dr * 0.30f + dg * dg * 0.45f + db * db * 0.25f
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
            i++
        }
        val distance = sqrt(bestDistance)
        val pull = strength.coerceIn(0f, 1f) *
            (1f - ((distance - SNAP_EXACT) / (SNAP_NONE - SNAP_EXACT)).coerceIn(0f, 1f))
        if (pull <= 0.002f) return color
        return Color(
            red = r + (channels[best * 3] - r) * pull,
            green = g + (channels[best * 3 + 1] - g) * pull,
            blue = b + (channels[best * 3 + 2] - b) * pull,
            alpha = color.alpha,
        )
    }

    companion object {
        /** Snaps nothing. The graceful default for a palette that was built without a ramp. */
        val NONE: ColorRamp = ColorRamp(emptyList())

        /** Below this distance the match is close enough to take whole. */
        private const val SNAP_EXACT = 0.085f

        /** Beyond this the ramp clearly has no answer, so the colour is left as it was. */
        private const val SNAP_NONE = 0.26f
    }
}

/**
 * One room's ramp, resolved at every step of the evening.
 *
 * Night is a palette rotation rather than a dimmer: the tones are transformed once, up front,
 * so that at any hour the room still paints from exactly the same number of colours it does at
 * noon. Blending toward night at draw time would put every in-between shade back on screen and
 * undo the whole point of the ramp.
 */
class RoomRamp internal constructor(private val steps: Array<ColorRamp>) {

    /** The ramp for a given amount of [night], 0 at noon and 1 in the middle of the night. */
    fun at(night: Float): ColorRamp =
        steps[(night.coerceIn(0f, 1f) * (steps.size - 1)).roundToInt()]

    companion object {
        val NONE: RoomRamp = RoomRamp(arrayOf(ColorRamp.NONE))
    }
}

/**
 * Colors for one room, in day and night flavours.
 *
 * These are a **ramp**, not a bag of colours: [highlight], [wallTop], [wallBottom], [propShade]
 * and [shadow] are five steps of one family, and [propAccent] is the single hue allowed to
 * disagree with it. Rooms built this way read as designed at 200 pixels tall; rooms built from
 * six unrelated hues read as clip art no matter how carefully each shape is drawn.
 *
 * The eleven fields below are the anchors an artist thinks in. [ramp] is the full set of tones
 * the room may actually use — the anchors plus the steps between them — and scene drawing snaps
 * every mixed colour onto it.
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
    /** Every tone this room is allowed to use, at every hour. */
    val ramps: RoomRamp = RoomRamp.NONE,
    /** The [ramps] entry for the hour this palette is lit for. */
    val ramp: ColorRamp = ramps.at(0f),
)

/** How many discrete lightings a room has between noon and midnight. */
private const val NIGHT_STEPS = 12

/**
 * Drains one colour toward evening. Red goes fastest, green a little slower and blue is almost
 * held up, which is how the eye reads late light; only then does the tone settle toward the
 * room's own night tint. [keep] below 1 protects a tone that is its own light source.
 */
private fun eveningTone(color: Color, tint: Color, night: Float, keep: Float = 1f): Color {
    val k = night * keep
    val shifted = Color(
        red = color.red * (1f - 0.55f * k),
        green = color.green * (1f - 0.40f * k),
        blue = color.blue * (1f - 0.22f * k),
        alpha = color.alpha,
    )
    return lerp(shifted, tint.copy(alpha = color.alpha), 0.42f * k)
}

/** Night on the same grid the ramps are built on, so anchors and ramp always agree. */
private fun quantiseNight(night: Float): Float =
    (night.coerceIn(0f, 1f) * NIGHT_STEPS).roundToInt() / NIGHT_STEPS.toFloat()

/**
 * Light and dark tone for each season, in the order [Season] declares them.
 *
 * Deliberately shared by every room: a spring that is pink in the bedroom and green on the
 * beach reads as five unrelated effects, while one repeated pair reads as a season.
 */
private val SEASON_TONES: List<Color> = listOf(
    Color(0xFFFFC6DA), Color(0xFFE07FA6), // spring blossom
    Color(0xFFFFE9A8), Color(0xFFF0B457), // summer sun
    Color(0xFFF3B067), Color(0xFFC26A3A), // autumn amber
    Color(0xFFE2EEFF), Color(0xFF9BC0E4), // winter frost
)

/** Tones every room needs on top of its own family: the seasons, dusk and the sun's disc. */
private val SHARED_TONES: List<Color> = SEASON_TONES + listOf(
    Color(0xFFFFAE6B), // dusk warmth
    Color(0xFFFFF3C4), // sun core
)

/**
 * Builds one room's ramp at every hour. [accent], [highlight] and [sky] are passed a second
 * time because [Palettes.applyNight] protects them from the evening more than it does the
 * surfaces, and a ramp that lacked those brighter variants would drag every lamp back down.
 */
private fun roomRamp(
    base: List<Color>,
    tint: Color,
    accent: Color,
    highlight: Color,
    sky: Color,
): RoomRamp = RoomRamp(
    Array(NIGHT_STEPS + 1) { step ->
        val t = step / NIGHT_STEPS.toFloat()
        val tones = ArrayList<Color>(base.size + SHARED_TONES.size + 3)
        base.forEach { tones += eveningTone(it, tint, t) }
        SHARED_TONES.forEach { tones += eveningTone(it, tint, t) }
        tones += eveningTone(accent, tint, t, keep = 0.30f)
        tones += eveningTone(highlight, tint, t, keep = 0.55f)
        tones += eveningTone(sky, tint, t, keep = 1.15f)
        ColorRamp(tones)
    },
)

// Each room's own nineteen tones: the anchors, plus the steps the shading actually lands on.
// Anything drawn in this room is pushed onto one of these, so the whole scene stays countable.

private val BEACH_RAMP = roomRamp(
    base = listOf(
        Color(0xFF8A5E44), Color(0xFFD1966C), Color(0xFFF7B481), Color(0xFFFBC898),
        Color(0xFFFFDCAE), Color(0xFFB38E64), Color(0xFFCFAE79), Color(0xFFE0C491),
        Color(0xFFF2DBA9), Color(0xFF5E7273), Color(0xFF3A8299), Color(0xFF50A2B6),
        Color(0xFF62BCCE), Color(0xFF99D0D3), Color(0xFFCC765B), Color(0xFFEF8367),
        Color(0xFFF5B096), Color(0xFFFFE8C6), Color(0xFFFFF4DC),
    ),
    tint = Color(0xFF14203F),
    accent = Color(0xFFEF8367),
    highlight = Color(0xFFFFF4DC),
    sky = Color(0xFFFFE8C6),
)

private val SPACE_RAMP = roomRamp(
    base = listOf(
        Color(0xFF0C0E22), Color(0xFF202450), Color(0xFF2B3068), Color(0xFF202552),
        Color(0xFF161A3C), Color(0xFF1A1D3E), Color(0xFF232750), Color(0xFF2E3264),
        Color(0xFF383D78), Color(0xFF32396B), Color(0xFF525CA6), Color(0xFF737EC6),
        Color(0xFF8E9AE0), Color(0xFFA3AEEB), Color(0xFF4C96A3), Color(0xFF6FE0E8),
        Color(0xFF94DBF1), Color(0xFF0C0F26), Color(0xFFCBD3FF),
    ),
    tint = Color(0xFF05061A),
    accent = Color(0xFF6FE0E8),
    highlight = Color(0xFFCBD3FF),
    sky = Color(0xFF0C0F26),
)

private val FOREST_RAMP = roomRamp(
    base = listOf(
        Color(0xFF27412E), Color(0xFF417659), Color(0xFF4F9270), Color(0xFF6AAC86),
        Color(0xFF86C79C), Color(0xFF44422D), Color(0xFF57432C), Color(0xFF695035),
        Color(0xFF7B5E3E), Color(0xFF3C6947), Color(0xFF4E8A5C), Color(0xFF78B47E),
        Color(0xFF9BD69A), Color(0xFFB4E1B0), Color(0xFFAA9D5A), Color(0xFFF0CE71),
        Color(0xFFEBDE9B), Color(0xFFC4E7D0), Color(0xFFE3F5DA),
    ),
    tint = Color(0xFF0D1B31),
    accent = Color(0xFFF0CE71),
    highlight = Color(0xFFE3F5DA),
    sky = Color(0xFFC4E7D0),
)

private val ARCADE_RAMP = roomRamp(
    base = listOf(
        Color(0xFF160A22), Color(0xFF432164), Color(0xFF5B2E88), Color(0xFF4C2674),
        Color(0xFF3D1E60), Color(0xFF190C27), Color(0xFF1B0E2B), Color(0xFF231236),
        Color(0xFF2B1642), Color(0xFF351C51), Color(0xFF4E2B78), Color(0xFF714AA2),
        Color(0xFF8E64C4), Color(0xFFAD89D9), Color(0xFFAD517D), Color(0xFFFF77AE),
        Color(0xFFF599CE), Color(0xFF2A1145), Color(0xFFE7CDFF),
    ),
    tint = Color(0xFF10061C),
    accent = Color(0xFFFF77AE),
    highlight = Color(0xFFE7CDFF),
    sky = Color(0xFF2A1145),
)

private val COZY_RAMP = roomRamp(
    base = listOf(
        Color(0xFF4B4353), Color(0xFF77859E), Color(0xFF8FA9C6), Color(0xFFA6BCD4),
        Color(0xFFBCD0E2), Color(0xFF7D6655), Color(0xFF9E7D57), Color(0xFFB4926B),
        Color(0xFFC9A87F), Color(0xFF847367), Color(0xFFB29A78), Color(0xFFD1BEA0),
        Color(0xFFEADCC0), Color(0xFFF1E5CD), Color(0xFFAC715D), Color(0xFFE08A63),
        Color(0xFFECB597), Color(0xFFD6EAF7), Color(0xFFFFF6E6),
    ),
    tint = Color(0xFF141B36),
    accent = Color(0xFFE08A63),
    highlight = Color(0xFFFFF6E6),
    sky = Color(0xFFD6EAF7),
)

// Sand and sea: one warm ramp for everything the sun touches, one cool tone for water,
// coral as the only accent. No pure white anywhere — foam is warm cream instead.
private val BEACH_ROOM = RoomPalette(
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
    ramps = BEACH_RAMP,
)

// Deep indigo, lit from the floor up as if the horizon were a nebula. Cyan is the accent
// and the only bright thing in the room, so the eye always lands on it.
private val SPACE_ROOM = RoomPalette(
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
    ramps = SPACE_RAMP,
)

// Canopy greens over a brown floor, with warm gold as the accent so the fireflies and
// the sun through the leaves are the same colour — that repetition is the cohesion.
private val FOREST_ROOM = RoomPalette(
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
    ramps = FOREST_RAMP,
)

// Purple cabinet light, neon pink accent. The room is dark so the accent can stay soft
// and still read as neon; a saturated red on saturated purple is what buzzed before.
private val ARCADE_ROOM = RoomPalette(
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
    ramps = ARCADE_RAMP,
)

// Cozy room: dusty blue walls, oak floor, cream props, terracotta accent. Everything is
// low chroma so the pet is the most saturated thing on screen.
private val COZY_ROOM = RoomPalette(
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
    ramps = COZY_RAMP,
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

    /** The rooms are immutable and their ramps are built once, so every caller shares one. */
    fun room(themeId: String): RoomPalette = when (themeId) {
        "room_beach" -> BEACH_ROOM
        "room_space" -> SPACE_ROOM
        "room_forest" -> FOREST_ROOM
        "room_arcade" -> ARCADE_ROOM
        else -> COZY_ROOM
    }

    /** The light or dark tone of [season], the same pair in every room. */
    fun seasonTone(season: Season, light: Boolean): Color =
        SEASON_TONES[season.ordinal * 2 + if (light) 0 else 1]

    /**
     * Blends a room toward its night version. [night] is 0 at noon, 1 in the middle of the night.
     *
     * Evening is a **hue shift**, not a dimmer, and it is stepped rather than continuous: the
     * room swaps to the next ramp in [RoomPalette.ramps] instead of drifting between two, which
     * is what keeps a night-time scene as countable as a daytime one. Just multiplying
     * everything down gives you the same picture with the brightness turned off, which nobody
     * reads as dusk.
     */
    fun applyNight(palette: RoomPalette, night: Float): RoomPalette {
        val t = quantiseNight(night)
        val tint = palette.nightTint
        return palette.copy(
            wallTop = eveningTone(palette.wallTop, tint, t),
            wallBottom = eveningTone(palette.wallBottom, tint, t),
            floor = eveningTone(palette.floor, tint, t),
            floorShade = eveningTone(palette.floorShade, tint, t),
            prop = eveningTone(palette.prop, tint, t),
            // Lamps, neon and fireflies are the things that survive the evening, so the accent
            // and the top of the ramp are dimmed far less than the surfaces around them.
            propAccent = eveningTone(palette.propAccent, tint, t, keep = 0.30f),
            propShade = eveningTone(palette.propShade, tint, t),
            highlight = eveningTone(palette.highlight, tint, t, keep = 0.55f),
            shadow = eveningTone(palette.shadow, tint, t),
            sky = eveningTone(palette.sky, tint, t, keep = 1.15f),
            ramp = palette.ramps.at(t),
        )
    }
}
