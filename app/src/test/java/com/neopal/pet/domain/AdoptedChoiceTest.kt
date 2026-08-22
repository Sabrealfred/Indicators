package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The guard between a remote brain and the rules.
 *
 * A model is asked over a network. Seconds pass, the simulation keeps ticking, and by the time an
 * answer lands the world it was asked about may be gone. Everything here is about the same thing:
 * a choice that was legal when it was made is not automatically legal when it is applied, and the
 * one place that can be enforced is the moment of application.
 */
class AdoptedChoiceTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    private fun pet(
        autonomy: Autonomy = Autonomy.FULL,
        skills: Set<Skill> = Skill.entries.toSet(),
        stats: Stats = Stats(satiety = 50f, happiness = 70f, energy = 80f, hygiene = 90f),
        inventory: Map<String, Int> = mapOf("meal_bowl" to 3, "medicine" to 1),
    ): PetState = Simulation
        .advance(Simulation.newGame("T", Species.LEAF, start), start + 120_000, config)
        .state
        .copy(
            stage = LifeStage.ADULT,
            ageSeconds = config.secondsPerPetDay / 2,
            stats = stats,
            inventory = inventory,
            autonomy = autonomy,
            skills = skills,
            intellect = 60f,
            genome = Genome(),
        )

    @Test
    fun `a legal choice is adopted and logged in the words it came with`() {
        val events = mutableListOf<GameEvent>()
        val after = Brain.adopt(
            pet(), ActivityKind.EAT, "You looked like you wanted me to.", config, Random(1), events,
        )
        assertNotNull("eating is available to a half-full pet with food in the tin", after)
        assertEquals(ActivityKind.EAT, after!!.activity?.kind)
        assertEquals(
            "the log has to keep the reason it was given",
            "You looked like you wanted me to.",
            after.decisions.last().reason,
        )
    }

    @Test
    fun `a choice the pet cannot make is refused`() {
        // No skill for it: the option exists in the list but is blocked, and blocked is not legal.
        val unskilled = pet(skills = emptySet())
        assertNull(
            "a pet that never learned to work the pantry must not be talked into eating",
            Brain.adopt(unskilled, ActivityKind.EAT, "Go on.", config, Random(1), mutableListOf()),
        )
    }

    @Test
    fun `a choice made against a world that has moved on is refused`() {
        // The model was shown a pantry with food in it; by the time the answer arrives it is empty.
        // Foraging is withheld deliberately: a pet that can forage still has a legal way to eat,
        // which is correct behaviour and would hide the property under test.
        val emptied = pet(inventory = emptyMap(), skills = Skill.entries.toSet() - Skill.FORAGE)
        assertNull(
            "an answer about a pantry that is now empty must not produce a meal",
            Brain.adopt(emptied, ActivityKind.EAT, "There was a bowl.", config, Random(1), mutableListOf()),
        )
    }

    @Test
    fun `a pet that can forage still eats when the pantry empties`() {
        // The other half of the rule above, so the guard cannot be tightened into a bug later.
        val emptied = pet(inventory = emptyMap())
        val after = Brain.adopt(emptied, ActivityKind.EAT, "I will find something.", config, Random(1), mutableListOf())
        assertNotNull("foraging is a legal way to eat", after)
        assertEquals(0, after!!.mealsEaten)
    }

    @Test
    fun `manual mode refuses every remote choice`() {
        assertNull(
            "a player who took the wheel keeps it",
            Brain.adopt(
                pet(autonomy = Autonomy.OFF), ActivityKind.EAT, "x", config, Random(1), mutableListOf(),
            ),
        )
    }

    @Test
    fun `a sleeping pet is not talked into anything`() {
        val asleep = pet().copy(isSleeping = true)
        assertNull(Brain.adopt(asleep, ActivityKind.PLAY, "x", config, Random(1), mutableListOf()))
    }

    @Test
    fun `a baby and a dead pet are both left alone`() {
        val baby = pet().let { it.copy(stage = LifeStage.BABY, stageStartedSeconds = it.ageSeconds) }
        assertNull(Brain.adopt(baby, ActivityKind.PLAY, "x", config, Random(1), mutableListOf()))

        val gone = pet().copy(isDead = true, deathReason = DeathReason.OLD_AGE)
        assertNull(Brain.adopt(gone, ActivityKind.PLAY, "x", config, Random(1), mutableListOf()))
    }

    @Test
    fun `an adopted meal spends the tin exactly as a chosen one would`() {
        val before = pet()
        val after = Brain.adopt(before, ActivityKind.EAT, "Hungry.", config, Random(1), mutableListOf())
        assertNotNull(after)
        assertTrue("the food has to actually leave the cupboard",
            (after!!.inventory["meal_bowl"] ?: 0) < (before.inventory["meal_bowl"] ?: 0))
        assertTrue("and the meal has to count", after.mealsEaten > before.mealsEaten)
    }

    @Test
    fun `a blank or enormous reason is handled rather than shown`() {
        val blank = Brain.adopt(pet(), ActivityKind.EAT, "   ", config, Random(1), mutableListOf())
        assertNotNull(blank)
        assertTrue("a blank reason falls back to the creature's own words",
            blank!!.decisions.last().reason.isNotBlank())

        val huge = Brain.adopt(pet(), ActivityKind.EAT, "x".repeat(4000), config, Random(1), mutableListOf())
        assertNotNull(huge)
        assertTrue("a reason has to fit a row", huge!!.decisions.last().reason.length <= 200)
    }
}
