package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rules that decide whether the game is playable at all. The headline one: a night's
 * sleep must not be a death sentence.
 */
class BalanceTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    private fun healthyPet(): PetState = Simulation
        .advance(Simulation.newGame("Test", Species.LEAF, start), start + 120_000, config)
        .state
        .copy(stats = Stats(satiety = 90f, happiness = 90f, energy = 90f, hygiene = 90f, health = 100f))

    @Test
    fun `a healthy pet survives a full night away`() {
        val pet = healthyPet()
        val eightHours = pet.lastTickMillis + 8L * 3600L * 1000L
        val after = Simulation.advance(pet, eightHours, config).state
        assertFalse("a night's sleep must not kill the pet", after.isDead)
        assertTrue("health must not fall below the offline floor", after.stats.health >= config.offlineHealthFloor)
    }

    @Test
    fun `a healthy pet survives the longest absence the game simulates`() {
        val pet = healthyPet()
        val threeDays = pet.lastTickMillis + 72L * 3600L * 1000L
        val after = Simulation.advance(pet, threeDays, config).state
        assertFalse(after.isDead)
    }

    @Test
    fun `coming back after a night still finds a pet in trouble`() {
        val pet = healthyPet()
        val after = Simulation.advance(pet, pet.lastTickMillis + 8L * 3600L * 1000L, config).state
        // Surviving is not the same as being fine — the guilt has to survive the fix.
        assertTrue("the pet should be starving", after.stats.satiety < 20f)
        assertTrue("the pet should be unhappy", after.stats.happiness < 40f)
    }

    @Test
    fun `a pet left sick can still die while you are away`() {
        val sick = healthyPet().copy(isSick = true, stats = Stats(satiety = 20f, happiness = 20f, health = 20f))
        val after = Simulation.advance(sick, sick.lastTickMillis + 8L * 3600L * 1000L, config).state
        assertTrue("illness left untreated must remain fatal", after.isDead)
        assertEquals(DeathReason.ILLNESS, after.deathReason)
    }

    @Test
    fun `neglect while you are watching is still fatal`() {
        val starving = healthyPet().copy(stats = Stats(satiety = 1f, happiness = 1f, energy = 50f, hygiene = 1f, health = 5f))
        // A steady stream of short ticks is the app being open, not an absence.
        var current = starving
        repeat(60) {
            current = Simulation.advance(current, current.lastTickMillis + 60_000L, config).state
        }
        assertTrue("active neglect must still have consequences", current.isDead)
    }

    @Test
    fun `old age arrives even if you were away`() {
        val elder = healthyPet().copy(
            stage = LifeStage.ELDER,
            stageStartedSeconds = 0L,
            ageSeconds = Simulation.stageDuration(LifeStage.ELDER, config),
        )
        val after = Simulation.advance(elder, elder.lastTickMillis + 3_600_000L, config).state
        assertTrue(after.isDead)
        assertEquals(DeathReason.OLD_AGE, after.deathReason)
    }

    @Test
    fun `a well kept newborn is not graded on stats it has not had time to earn`() {
        val newborn = PetState(stats = Stats(satiety = 95f, happiness = 95f, energy = 95f, hygiene = 95f, health = 100f))
        assertTrue("care score should reflect care, not tenure", newborn.stats.careScore > 0.9f)
    }

    @Test
    fun `praise is a viable path to discipline without scolding`() {
        var pet = PetState(stage = LifeStage.CHILD, lastTickMillis = 1L)
        repeat(10) { pet = CareActions.praise(pet).state }
        assertTrue("praise alone should raise discipline meaningfully", pet.stats.discipline >= 60f)
    }

    @Test
    fun `the diary keeps milestones when it runs out of room`() {
        var pet = PetState(lastTickMillis = 1L)
        pet = Chronicle.record(pet, listOf(GameEvent.Hatched), config)
        repeat(200) { index ->
            pet = Chronicle.append(pet, "ordinary day $index", ChronicleKind.CARE, config)
        }
        assertTrue(
            "the hatching line must never be evicted by routine days",
            pet.chronicle.any { it.kind == ChronicleKind.MILESTONE },
        )
    }

    @Test
    fun `the album keeps evolutions when it runs out of room`() {
        val evolution = AlbumEntry(
            id = "evo_CHILD_10", title = "grew", species = Species.AQUA, stage = LifeStage.CHILD,
            branch = EvolutionBranch.BALANCED, hatId = null, roomTheme = "room_default",
            petAgeSeconds = 10, capturedAtMillis = 10,
        )
        var pet = PetState(stage = LifeStage.CHILD, lastTickMillis = 1L, album = listOf(evolution))
        repeat(80) { index -> pet = CareActions.snapshot(pet, "snap $index", index.toLong()).state }
        assertTrue(
            "an evolution must not be dropped to make room for a selfie",
            pet.album.any { it.id.startsWith("evo_") },
        )
    }
}
