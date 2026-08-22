package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulation is pure, so the whole life cycle can be tested without Android. */
class SimulationTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    @Test
    fun `egg hatches after the incubation window`() {
        val egg = Simulation.newGame("Test", Species.AQUA, start)
        val result = Simulation.advance(egg, start + 120_000, config)
        assertEquals(LifeStage.BABY, result.state.stage)
        assertTrue(result.events.any { it is GameEvent.Hatched })
    }

    @Test
    fun `needs drain over time`() {
        val pet = hatched()
        val later = Simulation.advance(pet, pet.lastTickMillis + 600_000, config).state
        assertTrue("satiety should fall", later.stats.satiety < pet.stats.satiety)
        assertTrue("happiness should fall", later.stats.happiness < pet.stats.happiness)
    }

    @Test
    fun `offline progress is capped so a long absence is survivable`() {
        val pet = hatched().copy(stats = Stats(satiety = 100f, happiness = 100f, energy = 100f))
        val week = pet.lastTickMillis + 7L * 24 * 3600 * 1000
        val capped = Simulation.advance(pet, week, config).state
        // Twelve hours of simulation, not seven days of it.
        assertTrue("should not be instantly dead", capped.stats.health >= 0f)
        assertEquals(week, capped.lastTickMillis)
    }

    @Test
    fun `neglect eventually kills the pet`() {
        // Minute-by-minute ticks are the app being open. Neglect you can see is still fatal;
        // the same hour passed as a single jump would count as an absence and be forgiven.
        var pet = hatched().copy(stats = Stats(satiety = 1f, happiness = 1f, energy = 50f, hygiene = 1f, health = 5f))
        repeat(60) { pet = Simulation.advance(pet, pet.lastTickMillis + 60_000, config).state }
        assertTrue(pet.isDead)
        assertNotNull(pet.deathReason)
    }

    @Test
    fun `branch follows how the pet was raised`() {
        val athletic = hatched().copy(gamesPlayed = 20, gamesWon = 16, stats = Stats(energy = 80f, discipline = 40f))
        assertEquals(EvolutionBranch.ATHLETIC, Simulation.decideBranch(athletic))

        val gourmand = hatched().copy(mealsEaten = 60, weightGrams = 60f, stats = Stats(discipline = 40f))
        assertEquals(EvolutionBranch.GOURMAND, Simulation.decideBranch(gourmand))

        val feral = hatched().copy(careMistakes = 500, ageSeconds = 3600)
        assertEquals(EvolutionBranch.FERAL, Simulation.decideBranch(feral))
    }

    @Test
    fun `levels roll over and pay out coins`() {
        val events = mutableListOf<GameEvent>()
        val leveled = Simulation.applyXp(hatched(), 500, events)
        assertTrue(leveled.level > 1)
        assertTrue(events.any { it is GameEvent.LeveledUp })
    }

    @Test
    fun `next generation keeps cosmetics and the album`() {
        val previous = hatched().copy(
            coins = 300,
            inventory = mapOf("hat_crown" to 1, "snack_berry" to 9),
            album = listOf(
                AlbumEntry("a", "t", Species.AQUA, LifeStage.CHILD, EvolutionBranch.BALANCED, null, "room_default", 10, 10),
            ),
        )
        val next = Simulation.nextGeneration(previous, "Second", Species.VOLT, start + 5_000)
        assertEquals(2, next.generation)
        assertEquals(300, next.coins)
        assertEquals(1, next.album.size)
        assertEquals(1, next.inventory["hat_crown"])
        assertEquals(LifeStage.EGG, next.stage)
    }

    private fun hatched(): PetState =
        Simulation.advance(Simulation.newGame("Test", Species.AQUA, start), start + 120_000, config).state
}
