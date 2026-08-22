package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam nobody else tests.
 *
 * Brain, Colony and Learning each have their own suite, and each of those calls its own object
 * directly. That proves the three systems work; it proves nothing about whether the simulation
 * actually calls them, in the right order, with the clock in the right place. Wiring is exactly
 * the kind of thing that can be silently absent — the game would simply behave as it always did,
 * every unit test would stay green, and the whole feature would ship inert.
 *
 * Everything here therefore goes through [Simulation.advance] and nothing else.
 */
class AutonomyIntegrationTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    /** A grown, capable, autonomous pet standing at midday with food in the cupboard. */
    private fun keeper(
        autonomy: Autonomy = Autonomy.FULL,
        skills: Set<Skill> = Skill.entries.toSet(),
        stats: Stats = Stats(satiety = 70f, happiness = 70f, energy = 80f, hygiene = 90f),
        inventory: Map<String, Int> = mapOf("meal_bowl" to 8, "medicine" to 2),
    ): PetState {
        val base = Simulation
            .advance(Simulation.newGame("Test", Species.LEAF, start), start + 120_000, config)
            .state
        return base.copy(
            stage = LifeStage.ADULT,
            stageStartedSeconds = base.ageSeconds,
            // Midday, so nothing here is decided by the sleep cycle.
            ageSeconds = config.secondsPerPetDay / 2,
            stats = stats,
            inventory = inventory,
            autonomy = autonomy,
            skills = skills,
            intellect = 60f,
            genome = Genome(),
        )
    }

    /** Advances [state] by [seconds] of live time in one-minute steps, collecting every event. */
    private fun live(
        state: PetState,
        seconds: Long,
        events: MutableList<GameEvent> = mutableListOf(),
    ): PetState {
        var s = state
        var t = s.lastTickMillis
        // A minute a step: short enough to stay under the catch-up threshold, so this exercises
        // the live path rather than the offline one.
        repeat((seconds / 60L).toInt()) {
            t += 60_000L
            val result = Simulation.advance(s, t, config)
            s = result.state
            events += result.events
        }
        return s
    }

    @Test
    fun `an autonomous pet feeds itself through the simulation, not just through Brain`() {
        val hungry = keeper(stats = Stats(satiety = 18f, happiness = 70f, energy = 80f, hygiene = 90f))
        val after = live(hungry, 1_800)

        assertTrue("it should have eaten something", after.selfCareActions > hungry.selfCareActions)
        assertTrue("and the tin should have come out of the cupboard",
            (after.inventory["meal_bowl"] ?: 0) < (hungry.inventory["meal_bowl"] ?: 0))
        assertTrue("which is the point: it is no longer starving", after.stats.satiety > hungry.stats.satiety)
        // See [CareActions.Actor]: the meal is real, the keeper's record of meals served is not.
        assertEquals("nobody served it", hungry.mealsEaten, after.mealsEaten)
    }

    @Test
    fun `the same pet with autonomy off simply goes hungry`() {
        val hungry = keeper(
            autonomy = Autonomy.OFF,
            stats = Stats(satiety = 18f, happiness = 70f, energy = 80f, hygiene = 90f),
        )
        val after = live(hungry, 1_800)

        assertEquals("nothing was eaten", hungry.selfCareActions, after.selfCareActions)
        assertEquals("the cupboard is untouched", hungry.inventory["meal_bowl"], after.inventory["meal_bowl"])
        assertTrue("and it is worse off than it started", after.stats.satiety < hungry.stats.satiety)
        assertNull("nothing was ever decided", after.activity)
    }

    private fun assertNull(message: String, value: Any?) = assertTrue(message, value == null)

    @Test
    fun `decisions reach the save, with reasons attached`() {
        val after = live(keeper(), 1_800)

        assertTrue("the brain should have logged something", after.decisions.isNotEmpty())
        assertTrue("every decision has to say why", after.decisions.all { it.reason.isNotBlank() })
        assertTrue("and the log is bounded", after.decisions.size <= Simulation.MAX_DECISION_LOG)
    }

    @Test
    fun `the colony runs even when the player is driving`() {
        // Asserted through an egg rather than through an arrival. Arrivals are a per-hour dice
        // roll, so a test that waits for one either fishes for a lucky seed or fails on an
        // unlucky one; an egg hatches on the clock. Autonomy is OFF, because the world does not
        // stop having other creatures in it just because the player prefers to drive.
        val expecting = keeper(autonomy = Autonomy.OFF).let { pet ->
            pet.copy(
                nest = listOf(
                    NestEgg(
                        id = "egg_1",
                        genome = Genome(),
                        species = Species.LEAF,
                        laidAtSeconds = pet.ageSeconds,
                        hatchesAtSeconds = pet.ageSeconds + 600L,
                        otherParentId = "pal_x",
                        otherParentName = "Moss",
                    ),
                ),
            )
        }
        val events = mutableListOf<GameEvent>()
        val after = live(expecting, 1_800, events)

        assertTrue("the egg has to hatch", events.any { it is GameEvent.ChildHatched })
        assertTrue("and the nest empties", after.nest.isEmpty())
        assertTrue("leaving a child behind", after.pals.any { it.relation == Relation.OFFSPRING })
    }

    @Test
    fun `a pet that put itself to bed is not billed for the light`() {
        // Night, lights on, low energy: exactly the situation that used to charge an hourly care
        // mistake to a keeper whose pet had just handled its own bedtime.
        val night = keeper(stats = Stats(satiety = 70f, happiness = 70f, energy = 10f, hygiene = 90f))
            .copy(ageSeconds = (config.secondsPerPetDay * 23) / 24, lightsOff = false)
        val events = mutableListOf<GameEvent>()
        live(night, 4L * 3600L, events)

        // Asserted on the event, not on the closing flag: four hours is long enough for it to
        // sleep, refill and be woken again by the sleep cycle, and an end-state check would call
        // a full night's rest a failure to go to bed.
        assertTrue(
            "it should have gone to sleep on its own",
            events.any { it is GameEvent.FellAsleep },
        )
        assertFalse(
            "a pet that settled itself must not be billed for the light",
            events.filterIsInstance<GameEvent.CareMistake>().any { it.reason == "lights left on" },
        )
    }

    @Test
    fun `the same night with the player in charge still bills for the light`() {
        // The guard above must not have quietly deleted the rule for everybody.
        val night = keeper(
            autonomy = Autonomy.OFF,
            stats = Stats(satiety = 70f, happiness = 70f, energy = 10f, hygiene = 90f),
        ).copy(ageSeconds = (config.secondsPerPetDay * 23) / 24, lightsOff = false)
        val events = mutableListOf<GameEvent>()
        live(night, 4L * 3600L, events)

        assertTrue(
            "with nobody settling it, the light is still the keeper's fault",
            events.filterIsInstance<GameEvent.CareMistake>().any { it.reason == "lights left on" },
        )
    }

    @Test
    fun `an autonomous life is reproducible from its seed`() {
        val a = live(keeper(), 3_600)
        val b = live(keeper(), 3_600)
        assertEquals(a.decisions.map { it.kind }, b.decisions.map { it.kind })
        assertEquals(a.pals.map { it.name }, b.pals.map { it.name })
        assertEquals(a.mealsEaten, b.mealsEaten)
    }

    @Test
    fun `a baby is left alone by all of it`() {
        // stageStartedSeconds has to move with the age, or the fixture is a baby that is already
        // hours overdue for its evolution and grows out of being one on the very first step.
        val baby = keeper().let {
            it.copy(
                stage = LifeStage.BABY,
                stageStartedSeconds = it.ageSeconds,
                skills = emptySet(),
                intellect = 5f,
            )
        }
        val after = live(baby, 1_800)

        assertNull("a baby decides nothing", after.activity)
        assertTrue("and learns nothing on its own", after.skills.isEmpty())
    }

    /**
     * A companion standing in the room, fond of nobody yet.
     *
     * Genetically far from [keeper]'s flat genome on purpose, so that nothing downstream of this
     * fixture is ever really being blocked by the bloodline rule.
     */
    private fun moss(at: Long) = Pal(
        id = "pal_moss",
        name = "Moss",
        species = Species.AQUA,
        genome = Genome.fromList(List(Genome.GENE_COUNT) { 0.85f }),
        personality = Personality.CALM,
        stage = LifeStage.ADULT,
        relation = Relation.VISITOR,
        affinity = 0f,
        metAtSeconds = at,
        lastSeenSeconds = at,
        present = true,
    )

    @Test
    fun `company counts while the player is driving`() {
        // The whole colony hangs off one number: fondness. Friendship, courting, an egg and a
        // child are all thresholds on it. Fondness used to move only inside the creature's own
        // SOCIALISE activity — which is a decision, which needs FULL autonomy, which is not the
        // default — so the default player got visitors who arrived at nothing, left at nothing,
        // and a colony that could never once do anything. Skills are empty here too: standing in
        // a room somebody else is standing in is not an act the creature performs.
        val manual = keeper(autonomy = Autonomy.OFF, skills = emptySet()).let {
            it.copy(intellect = 5f, pals = listOf(moss(it.ageSeconds)))
        }

        val after = live(manual, 600)
        val pal = after.pals.single { it.id == "pal_moss" }

        assertTrue("ten minutes in the same room has to count for something", pal.affinity > 0f)
        assertNull("and none of it is the creature deciding anything", after.activity)
    }
}
