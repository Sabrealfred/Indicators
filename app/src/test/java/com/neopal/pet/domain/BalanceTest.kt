package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `care on a human schedule keeps the pet alive and well`() {
        // The rates were once tuned for a five-hour life; at a two-day life they demanded a meal
        // every fifteen minutes. A meal every couple of hours has to be enough.
        var pet = healthyPet().copy(inventory = mapOf("meal_bowl" to 999, "medicine" to 99))
        // Six check-ins, two hours apart: feed, tidy up, treat anything wrong, say something kind.
        repeat(6) {
            repeat(120) { pet = Simulation.advance(pet, pet.lastTickMillis + 60_000L, config).state }
            var attempts = 0
            while (attempts < 120 && (pet.stats.satiety < 70f || pet.isSick || pet.poops > 0)) {
                val before = pet
                if (pet.isSick) pet = CareActions.useMedicine(pet, "medicine").state
                if (pet.poops > 0) pet = CareActions.cleanRoom(pet).state
                if (pet.stats.satiety < 70f) pet = CareActions.feed(pet, "meal_bowl").state
                pet = CareActions.pet(pet).state
                // Asleep? Nothing lands; let a minute pass and try again.
                if (pet == before) pet = Simulation.advance(pet, pet.lastTickMillis + 60_000L, config).state
                attempts += 1
            }
        }
        assertFalse("a pet checked on every two hours must not die", pet.isDead)
        assertTrue("and must not be in crisis either", pet.stats.health > 50f)
        assertTrue("nor miserable", pet.stats.happiness > 30f)
    }

    @Test
    fun `an absent keeper raises a feral pet and an attentive one does not`() {
        // Absence: the app is opened twice a day. The keeper does the bare minimum — cures
        // illness so the pet survives — and nothing else.
        var absent = healthyPet().copy(inventory = mapOf("medicine_super" to 99))
        repeat(4) {
            absent = Simulation.advance(absent, absent.lastTickMillis + 11L * 3600_000L, config).state
            if (absent.isSick) absent = CareActions.useMedicine(absent, "medicine_super").state
        }
        assertFalse("the bare minimum should still keep it alive", absent.isDead)
        assertTrue("time away must register as neglect", absent.careMistakes >= 15)
        assertEquals(EvolutionBranch.FERAL, Simulation.decideBranch(absent))

        // Attention: fed, cleaned and praised through the same span.
        var kept = healthyPet().copy(inventory = mapOf("meal_bowl" to 999))
        repeat(44) {
            repeat(60) { kept = Simulation.advance(kept, kept.lastTickMillis + 60_000L, config).state }
            kept = CareActions.feed(kept, "meal_bowl").state
            kept = CareActions.cleanRoom(kept).state
            kept = CareActions.praise(kept).state
            kept = CareActions.pet(kept).state
            // An attentive keeper puts the light out when the pet is sleeping.
            if (kept.isSleeping && !kept.lightsOff) kept = CareActions.toggleLights(kept).state
            if (!kept.isSleeping && kept.lightsOff) kept = CareActions.toggleLights(kept).state
        }
        assertNotEquals(EvolutionBranch.FERAL, Simulation.decideBranch(kept))
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
