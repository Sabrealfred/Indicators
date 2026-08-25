package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Which of a plan's three events is allowed to leave a mark, and which are already on screen.
 *
 * The Mind screen's plan panel is the surface for a plan that exists: it appears when one is
 * made, it lists the steps, it counts down, and it says in words when the creature has grown
 * itself another step. Two of the three events therefore describe something the player can
 * already see, and announcing them again buys nothing.
 *
 * The third is the one the panel cannot show, because it is the panel going away. A plan whose
 * step is refused vanishes with no warning and no trace: nothing in the save, nothing in the
 * diary, nothing anywhere says the creature ever meant to do it. Running out of time is not that
 * case — the panel counts down to it and says "Out of time — it will let this go" before it
 * happens — which is why the two are told apart here rather than lumped together as "dropped".
 */
class PlanSurfaceTest {

    private val config = GameConfig.Default

    private fun pet(
        skills: Set<Skill> = Skill.entries.toSet(),
        inventory: Map<String, Int> = mapOf("meal_bowl" to 3),
    ): PetState = Simulation
        .advance(Simulation.newGame("Pip", Species.LEAF, 1_000_000L), 1_000_000L + 120_000L, config)
        .state
        .copy(
            stage = LifeStage.ADULT,
            ageSeconds = config.secondsPerPetDay / 2,
            stats = Stats(satiety = 50f, happiness = 60f, energy = 80f, hygiene = 60f),
            inventory = inventory,
            poops = 2,
            autonomy = Autonomy.FULL,
            skills = skills,
            intellect = 60f,
            genome = Genome(),
            chronicle = emptyList(),
        )

    private fun plan(vararg kinds: ActivityKind, at: Long) = Plan(
        goal = "Be presentable before company.",
        steps = kinds.map { PlanStep(it, "Because I said I would.") },
        madeAtSeconds = at,
    )

    /** Everything the diary gained from this tick's events. */
    private fun diary(state: PetState, events: List<GameEvent>): List<ChronicleEntry> =
        Chronicle.record(state, events, config).chronicle.drop(state.chronicle.size)

    @Test
    fun `a plan the world refuses is written down`() {
        // The plan says eat; the pantry is empty and it never learned to forage. The creature
        // drops the whole errand rather than standing there insisting on lunch.
        val before = pet(inventory = emptyMap(), skills = Skill.entries.toSet() - Skill.FORAGE)
            .copy(plan = plan(ActivityKind.EAT, at = config.secondsPerPetDay / 2))
        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(before, config, 1L, Random(1), events)
        assertNull(after.plan)

        val dropped = events.filterIsInstance<GameEvent.PlanAbandoned>().single()
        assertEquals("a refusal is not a deadline", PlanEnding.REFUSED, dropped.ending)

        val written = diary(after, events)
        assertEquals("the one plan event the player has no other way of seeing", 1, written.size)
        assertTrue(
            "the diary line has to name what it was for: ${written.single().text}",
            written.single().text.contains("Be presentable before company"),
        )
    }

    @Test
    fun `a plan that only ran out of day goes quietly`() {
        val stale = plan(ActivityKind.PLAY, at = 0L)
        val before = pet().copy(plan = stale)
        assertTrue(stale.isStale(before.ageSeconds))

        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(before, config, 1L, Random(1), events)
        assertNull(after.plan)

        val dropped = events.filterIsInstance<GameEvent.PlanAbandoned>().first()
        assertEquals(PlanEnding.RAN_OUT_OF_TIME, dropped.ending)
        assertTrue(
            "the panel counts this one down out loud before it happens, so the diary keeps out of it",
            diary(after, events.filterIsInstance<GameEvent.PlanAbandoned>()).isEmpty(),
        )
    }

    @Test
    fun `making a plan and growing one belong to the panel, not the diary`() {
        val state = pet()
        val noise = listOf(
            GameEvent.PlanMade("Be presentable before company.", 3),
            GameEvent.PlanExtended("Be presentable before company.", 4),
        )
        assertTrue(
            "the panel already shows both, in the creature's own words",
            diary(state, noise).isEmpty(),
        )
    }

    @Test
    fun `an abandoned plan says how far it got`() {
        // Two steps in, and the third turns out to be impossible: "it fell over immediately" and
        // "it nearly got there" are different afternoons and the event has to be able to say so.
        val events = mutableListOf<GameEvent>()
        val partway = pet().copy(plan = plan(ActivityKind.TIDY, ActivityKind.EAT, at = config.secondsPerPetDay / 2)
            .copy(done = 1))
        Errands.abandon(partway, events)

        val dropped = events.filterIsInstance<GameEvent.PlanAbandoned>().single()
        assertEquals(1, dropped.done)
        assertEquals(2, dropped.steps)
    }
}
