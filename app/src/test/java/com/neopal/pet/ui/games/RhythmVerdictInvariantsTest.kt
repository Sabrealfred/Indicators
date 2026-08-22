package com.neopal.pet.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two oldest games, and the small amount of them that is not inside a composable.
 *
 * Rhythm Tap and Memory Match were written before the others and keep almost everything —
 * the note field, the timing windows, the pad pitches, the round target — as composition state
 * inside their screens. What sits outside is the verdict table and one pitch helper, so that is
 * what is pinned. It is not much, and the report says so; the refactor that would open the rest
 * is named there too.
 *
 * Both games have at least been played, which is more than can be said for the four that follow
 * them, so the risk here is lower than it looks.
 */
class RhythmVerdictInvariantsTest {

    /**
     * A tighter verdict never pays less, and a miss pays nothing.
     *
     * The whole feedback loop of a timing game is that landing closer is worth more; an ordering
     * that slipped would make practice pointless while leaving every window, every animation and
     * every sound exactly as it was. The colours are asserted distinct for the same reason the
     * points are asserted ordered — the flash is the only thing a player sees, and two verdicts
     * that flash the same colour are one verdict.
     */
    @Test
    fun aTighterVerdictNeverPaysLess() {
        val verdicts = judgementCls.enumConstants.toList()
        assertTrue("there is nothing to judge", verdicts.size >= 2)

        val points = verdicts.map { pointsOf.invoke(it) as Int }
        assertEquals(
            "the verdicts are not ordered best-first, so `distance <= window` cascades pay " +
                "out of order",
            points.sortedDescending(), points,
        )
        assertEquals("the worst verdict still pays", 0, points.last())
        assertTrue("two verdicts are worth the same", points.toSet().size == points.size)

        // The colour comes back as whatever primitive `Color` inlines to, not as a `Color`;
        // distinctness is all that is being asked of it, and that survives the unboxing.
        val colours = verdicts.map { colourOf.invoke(it) }
        assertEquals(
            "two verdicts flash the same colour, so they are one verdict: " +
                verdicts.map { "$it" },
            verdicts.size, colours.toSet().size,
        )
        val labels = verdicts.map { labelOf.invoke(it) as String }
        assertEquals("two verdicts read the same: $labels", verdicts.size, labels.toSet().size)
        assertTrue("a verdict has no words", labels.none { it.isBlank() })
    }

    /**
     * The pressure in Memory Match is audible, and it starts from nowhere.
     *
     * `keyLift` multiplies a pad's own pitch, so the first round has to be exactly unshifted —
     * a lift of anything but one at length one would put the whole game a hair off its own
     * tuning from the first note — and every round after has to be higher than the last, which
     * is the only thing making a long sequence *sound* harder than a short one.
     *
     * How high it may climb before `ChiptuneEngine` clamps it cannot be checked from here: the
     * pad pitches and the round target are locals of `MemoryGameScreen`. See the report.
     */
    @Test
    fun theKeyRisesWithEveryRoundAndStartsWhereItShould() {
        assertEquals(
            "the first round is not sung in the pads' own key",
            1.0, (keyLift.invoke(null, 1) as Float).toDouble(), 1e-6,
        )
        var previous = keyLift.invoke(null, 1) as Float
        for (length in 2..24) {
            val lift = keyLift.invoke(null, length) as Float
            assertTrue(
                "round $length is sung at $lift, no higher than round ${length - 1} at $previous",
                lift > previous,
            )
            previous = lift
        }
    }

    private companion object {
        val judgementCls = NpGameReflect.cls("Judgement")
        val pointsOf = NpGameReflect.member("Judgement", "getPoints")
        val colourOf = NpGameReflect.valueMember("Judgement", "getColor")
        val labelOf = NpGameReflect.member("Judgement", "getLabel")
        val keyLift = NpGameReflect.fn("MemoryGameKt", "keyLift", NpGameReflect.I)
    }
}
