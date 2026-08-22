package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides not to call the network.
 *
 * Every test here exists because this code can be wrong without anything looking wrong. A throttle
 * that never opens produces no crash and no log — only a creature that decides everything for
 * itself, which is exactly what a correctly disabled remote mind looks like. These are the checks
 * that tell the two apart.
 */
class CadenceTest {

    @Test
    fun `something that has never happened is due immediately`() {
        // The bug this branch shipped: with Long.MIN_VALUE as the sentinel, this subtraction
        // overflowed to a large negative and the very first call never went out. Not once.
        assertTrue(Cadence.due(0L, Cadence.NEVER, Cadence.RECONSIDER_SECONDS))
        assertTrue(Cadence.due(1L, Cadence.NEVER, Cadence.PLAN_SECONDS))
        assertTrue(Cadence.due(500_000L, Cadence.NEVER, Cadence.SPEAK_FIRST_SECONDS))
    }

    @Test
    fun `the sentinel leaves room to subtract from any age a creature will ever reach`() {
        // A thousand years of pet seconds, well past the longest possible life.
        val absurd = 1_000L * 365L * 24L * 3600L
        assertTrue("the gap must not wrap round", absurd - Cadence.NEVER > 0L)
        assertTrue(Cadence.due(absurd, Cadence.NEVER, Long.MAX_VALUE / 4))
    }

    @Test
    fun `too soon is too soon and one second later is not`() {
        val last = 10_000L
        assertFalse(Cadence.due(last + 299L, last, Cadence.RECONSIDER_SECONDS))
        assertTrue(Cadence.due(last + 300L, last, Cadence.RECONSIDER_SECONDS))
    }

    @Test
    fun `acting at the very moment it was last done is not due again`() {
        assertFalse(Cadence.due(10_000L, 10_000L, 1L))
    }

    @Test
    fun `a bright creature thinks more often than a newborn`() {
        val dim = Cadence.gapFor(Cadence.RECONSIDER_SECONDS, 0f)
        val bright = Cadence.gapFor(Cadence.RECONSIDER_SECONDS, 100f)
        assertTrue("intellect has to buy something", bright < dim)
    }

    @Test
    fun `but never so much more often that it spends a free tier by lunch`() {
        val base = Cadence.PLAN_SECONDS
        // Sweep the whole range, including values a corrupted save could hold.
        listOf(-500f, 0f, 25f, 50f, 75f, 100f, 9_999f).forEach { intellect ->
            val gap = Cadence.gapFor(base, intellect)
            assertTrue(
                "intellect $intellect stretched the gap outside its band",
                gap >= (base * Cadence.MIN_SCALE).toLong() && gap <= (base * Cadence.MAX_SCALE).toLong(),
            )
            assertTrue("a gap of zero would call on every tick", gap > 0L)
        }
    }

    @Test
    fun `nonsense intellect is clamped rather than trusted`() {
        assertEquals(Cadence.gapFor(1_000L, 0f), Cadence.gapFor(1_000L, -40f))
        assertEquals(Cadence.gapFor(1_000L, 100f), Cadence.gapFor(1_000L, 400f))
    }

    @Test
    fun `the three gaps stay in the order the design depends on`() {
        // A plan replaced faster than it is reconsidered would never be finished, and a creature
        // that spoke up more often than it thought would be all mouth and no mind.
        assertTrue(Cadence.RECONSIDER_SECONDS < Cadence.PLAN_SECONDS)
        assertTrue(Cadence.PLAN_SECONDS < Cadence.SPEAK_FIRST_SECONDS)
    }
}
