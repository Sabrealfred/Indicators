package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * Which screen the player is pretending to look at.
 *
 * This lives in the domain rather than beside the renderer that implements it, for the same
 * reason every other setting does: it is saved. A player who picks the green handheld expects to
 * still be looking at it tomorrow, and that means it has to survive serialisation — which in turn
 * means it has to be a type the save format knows, not a string the renderer parses back into one.
 *
 * None of the modes is allowed to move the art off its grid. The upscale stays a whole number and
 * nearest-neighbour in all three; a filter only ever paints *over* the blit, in whole buffer
 * pixels, or replaces its colours one for one.
 */
@Serializable
enum class RetroMode(val displayName: String, val description: String) {

    /** No filter. The default, and byte for byte the picture the renderer has always drawn. */
    NONE("Clean", "The art as drawn."),

    /**
     * A tube: scanlines that bow away from the middle of the glass, a stepped vignette, and a
     * pixel of colour bleed either side of every edge.
     *
     * The scanlines and the vignette are baked into a mask the size of the *buffer*, so one
     * scanline is one art pixel tall however dense the screen is. Drawn at device resolution
     * they came out as hairlines that vanished entirely on a phone.
     */
    CRT("Tube", "Scanlines, colour bleed and a curved vignette."),

    /**
     * A 1997 handheld: four shades of green and nothing else.
     *
     * Colour is discarded outright — the filter reads brightness only and reprints it in four
     * flat tones — so this is a palette reduction rather than a green wash over the picture that
     * is already there. Night is expressed by lifting the exposure instead of by darkening,
     * because a screen with four tones has no darker to go.
     */
    GREEN_LCD("Handheld", "Four shades of green, and nothing else.");

    /** True when the mode repaints the frame's colours and so owns the whole finish. */
    val replacesColour: Boolean get() = this == GREEN_LCD
}
