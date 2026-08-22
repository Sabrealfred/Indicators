package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the plan panel.
 *
 * A [Plan] existed, the creature could form one and follow one, and nothing in the app drew it.
 * These are the sums the panel needs and the one rule that decides whether it appears at all.
 */
class PlanBoardTest {

    private fun plan(
        steps: List<ActivityKind> = listOf(ActivityKind.EAT, ActivityKind.GROOM, ActivityKind.SOCIALISE),
        done: Int = 0,
        madeAt: Long = 1_000L,
        extensions: Int = 0,
    ) = Plan(
        goal = "I want to be presentable before company.",
        steps = steps.map { PlanStep(it, "because of ${it.displayName}") },
        madeAtSeconds = madeAt,
        done = done,
        extensions = extensions,
    )

    @Test
    fun `no plan draws no panel`() {
        assertNull("an empty panel on every offline save is an advert, not an empty state",
            PlanBoard.of(null, 1_000L))
    }

    @Test
    fun `a plan with no steps draws no panel either`() {
        assertNull(PlanBoard.of(plan(steps = emptyList()), 1_000L))
    }

    @Test
    fun `the step it is on is the one after the ones it has finished`() {
        val board = PlanBoard.of(plan(done = 1), 1_000L)!!
        assertEquals(
            listOf(PlanStepState.DONE, PlanStepState.CURRENT, PlanStepState.PENDING),
            board.steps.map { it.state },
        )
        assertEquals(ActivityKind.GROOM, board.current?.kind)
    }

    @Test
    fun `a plan nothing has been done to is on its first step`() {
        val board = PlanBoard.of(plan(done = 0), 1_000L)!!
        assertEquals(PlanStepState.CURRENT, board.steps.first().state)
        assertEquals(ActivityKind.EAT, board.current?.kind)
        assertEquals(0f, board.fraction, 0.0001f)
    }

    @Test
    fun `a finished plan has nothing current left`() {
        val board = PlanBoard.of(plan(done = 3), 1_000L)!!
        assertTrue(board.steps.all { it.state == PlanStepState.DONE })
        assertNull("there is no step four to point at", board.current)
        assertEquals(1f, board.fraction, 0.0001f)
    }

    @Test
    fun `progress is a fraction of the steps, done over total`() {
        val board = PlanBoard.of(plan(done = 1), 1_000L)!!
        assertEquals(1, board.done)
        assertEquals(3, board.total)
        assertEquals(1f / 3f, board.fraction, 0.0001f)
    }

    @Test
    fun `the clock runs out at exactly the moment the creature abandons the plan`() {
        // The panel's countdown and Plan.isStale must not disagree: a screen still counting down
        // on a plan the brain has already dropped is a screen lying about the creature.
        val p = plan(madeAt = 1_000L)
        val life = Errands.lifetimeFor(p.steps.size)
        val lastGoodAge = 1_000L + life

        assertFalse(p.isStale(lastGoodAge))
        assertFalse(PlanBoard.of(p, lastGoodAge)!!.isOutOfTime)

        assertTrue(p.isStale(lastGoodAge + 1))
        assertTrue(PlanBoard.of(p, lastGoodAge + 1)!!.isOutOfTime)
    }

    @Test
    fun `a plan that grew itself says so`() {
        assertEquals(2, PlanBoard.of(plan(extensions = 2), 1_000L)!!.extensions)
    }

    @Test
    fun `a done count from a corrupt save cannot run off the end of the list`() {
        val board = PlanBoard.of(plan(done = 99), 1_000L)!!
        assertEquals(3, board.done)
        assertEquals(3, board.steps.count { it.state == PlanStepState.DONE })
        assertEquals(1f, board.fraction, 0.0001f)
    }
}
