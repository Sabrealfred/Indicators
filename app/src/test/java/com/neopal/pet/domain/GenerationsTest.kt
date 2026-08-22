package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-generation log: what a finished run leaves behind, and what a save without one must not. */
class GenerationsTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    @Test
    fun `a rollover seals the run that just ended`() {
        val lived = starved(
            hatched().copy(
                mealsEaten = 14,
                gamesPlayed = 9,
                gamesWon = 6,
                cleanups = 4,
                medicineDoses = 2,
                careMistakes = 3,
                level = 5,
            ),
        )
        val next = Simulation.nextGeneration(lived, "Second", Species.VOLT, lived.lastTickMillis + 5_000)

        assertEquals(1, next.previousGenerations.size)
        val record = next.previousGenerations.single()
        assertEquals(1, record.generation)
        assertEquals("Test", record.name)
        assertEquals(Species.AQUA, record.species)
        assertEquals(lived.stage, record.stage)
        assertEquals(lived.ageSeconds, record.lifespanSeconds)
        assertNotNull("the cause of death is the whole point of the record", record.deathReason)
        assertEquals(14, record.mealsEaten)
        assertEquals(9, record.gamesPlayed)
        assertEquals(6, record.gamesWon)
        assertEquals(4, record.cleanups)
        assertEquals(2, record.medicineDoses)
        assertEquals(5, record.level)
        assertTrue("mistakes are counted, not reset", record.careMistakes >= 3)
        assertTrue("a life that was lived has a care grade", record.careScore > 0f)
    }

    @Test
    fun `a run that never left the egg still leaves a record`() {
        // The album only files evolutions, so this is exactly the run the old card could not see.
        val egg = Simulation.newGame("Yolk", Species.LEAF, start)
        val ticked = Simulation.advance(egg, start + 30_000, config).state
        val next = Simulation.nextGeneration(ticked, "Second", Species.EMBER, start + 40_000)

        val record = next.previousGenerations.single()
        assertEquals(LifeStage.EGG, record.stage)
        assertEquals("Yolk", record.name)
        assertNull("it did not die, it was replaced", record.deathReason)
        assertTrue(record.lifespanSeconds > 0L)
    }

    @Test
    fun `history keeps the most recent runs and drops the rest`() {
        var pet = hatched()
        val rollovers = Simulation.MAX_REMEMBERED_GENERATIONS + 4
        repeat(rollovers) { i ->
            pet = Simulation.nextGeneration(pet, "Gen$i", Species.AQUA, start + 10_000L * (i + 1))
        }

        assertEquals(rollovers + 1, pet.generation)
        assertEquals(Simulation.MAX_REMEMBERED_GENERATIONS, pet.previousGenerations.size)
        // Oldest first, newest last, and the four oldest are gone rather than the four newest.
        assertEquals(pet.generation - 1, pet.previousGenerations.last().generation)
        assertEquals(rollovers - Simulation.MAX_REMEMBERED_GENERATIONS + 1, pet.previousGenerations.first().generation)
        assertEquals(
            pet.previousGenerations.map { it.generation }.sorted(),
            pet.previousGenerations.map { it.generation },
        )
    }

    @Test
    fun `a save written before the log has no history rather than empty runs`() {
        // What a save upgraded from an older build looks like: a generation count and nothing else.
        val upgraded = PetState(generation = 4, bornAtMillis = start, lastTickMillis = start)
        assertTrue(upgraded.previousGenerations.isEmpty())

        val next = Simulation.nextGeneration(upgraded, "Fifth", Species.LEAF, start + 5_000)
        assertEquals(5, next.generation)
        // Only the run that actually ended here is known; generations 1..3 stay missing.
        assertEquals(1, next.previousGenerations.size)
        assertEquals(4, next.previousGenerations.single().generation)
    }

    @Test
    fun `every recorded field has a default so an old blob still loads`() {
        val blank = RunRecord()
        assertEquals(0, blank.generation)
        assertEquals(0L, blank.lifespanSeconds)
        assertNull(blank.deathReason)
        assertTrue(PetState().previousGenerations.isEmpty())
    }

    @Test
    fun `the care grade covers the whole life instead of the moment of death`() {
        val kept = kept()
        val ended = starved(kept)
        assertTrue("dying leaves the closing stats on the floor", ended.stats.careScore < 0.2f)
        assertTrue(
            "the years of good care still count for something",
            ended.lifetimeCareScore > ended.stats.careScore + 0.2f,
        )
    }

    @Test
    fun `a neglected run grades below a cared-for one`() {
        val good = kept().lifetimeCareScore
        var bad = hatched().copy(stats = Stats(satiety = 3f, happiness = 5f, energy = 10f, hygiene = 8f, health = 40f))
        repeat(30) { bad = Simulation.advance(bad, bad.lastTickMillis + 60_000, config).state }
        assertTrue(good > bad.lifetimeCareScore)
    }

    @Test
    fun `an unsampled state grades on what it has, not on zero`() {
        val fresh = PetState(stats = Stats(satiety = 90f, happiness = 90f, energy = 90f, hygiene = 90f, health = 90f))
        assertEquals(fresh.stats.careScore, fresh.lifetimeCareScore, 0.0001f)
    }

    @Test
    fun `peak bond outlives the decay that follows it`() {
        var pet = hatched().copy(stats = hatched().stats.copy(bond = 80f))
        repeat(20) { pet = Simulation.advance(pet, pet.lastTickMillis + 600_000, config).state }
        assertTrue("bond should have drained", pet.stats.bond < 80f)
        assertTrue("the peak is what the run is remembered by", pet.peakBond >= 80f)

        val next = Simulation.nextGeneration(pet, "Second", Species.AQUA, pet.lastTickMillis + 1_000)
        assertEquals(pet.peakBond, next.previousGenerations.single().peakBond, 0.01f)
        assertEquals(0f, next.peakBond, 0.0001f)
    }

    @Test
    fun `mistakes are rated per hour so a short run cannot look tidy`() {
        val short = RunRecord(careMistakes = 6, lifespanSeconds = 3_600L)
        val long = RunRecord(careMistakes = 12, lifespanSeconds = 36_000L)
        assertTrue("twelve mistakes over ten hours is the better keeper", long.mistakesPerHour < short.mistakesPerHour)
    }

    @Test
    fun `the player's own records outlive the creature that set them`() {
        val lived = starved(hatched()).copy(
            highScores = mapOf("rhythm" to 4_200, "catch" to 910),
            level = 7,
            xp = 40,
            careStreakDays = 5,
            bestCareStreak = 9,
        )
        val next = Simulation.nextGeneration(lived, "Second", Species.VOLT, lived.lastTickMillis + 5_000)

        assertEquals(
            "a minigame record was set by the player's thumbs, not by the pet",
            mapOf("rhythm" to 4_200, "catch" to 910),
            next.highScores,
        )
        assertEquals("the achievement calls it keeper level, and keepers do not die", 7, next.level)
        assertEquals("progress toward the next keeper level goes with the level", 40, next.xp)
        assertEquals(
            "burying one pet and adopting another on the same day is not a day missed",
            5,
            next.careStreakDays,
        )
        assertEquals("a best is a record of the player, and records are not undone", 9, next.bestCareStreak)
    }

    @Test
    fun `a wiped high score would make the seven-game badge unwinnable across lives`() {
        val played = hatched().copy(highScores = MiniGame.entries.associate { it.id to 100 })
        assertEquals(MiniGame.entries.size, MiniGame.playedCount(played))

        val next = Simulation.nextGeneration(played, "Second", Species.LEAF, played.lastTickMillis + 5_000)
        assertEquals(
            "\"score in all seven\" says ever, and ever has to outlive one creature",
            MiniGame.entries.size,
            MiniGame.playedCount(next),
        )
    }

    @Test
    fun `what belonged to the creature is still buried with it`() {
        val lived = starved(hatched()).copy(
            mealsEaten = 30,
            gamesPlayed = 12,
            gamesWon = 7,
            cleanups = 8,
            praises = 9,
            scolds = 2,
            medicineDoses = 4,
            careMistakes = 11,
            peakBond = 77f,
            intellect = 60f,
            skills = setOf(Skill.TIDY_UP, Skill.SELF_GROOM),
            studySessions = 5,
        )
        val next = Simulation.nextGeneration(lived, "Second", Species.VOLT, lived.lastTickMillis + 5_000)

        assertEquals("meals were eaten by a mouth that is gone", 0, next.mealsEaten)
        assertEquals(0, next.gamesPlayed)
        assertEquals(0, next.gamesWon)
        assertEquals(0, next.cleanups)
        assertEquals(0, next.praises)
        assertEquals(0, next.scolds)
        assertEquals(0, next.medicineDoses)
        assertEquals("a new pet has not been neglected yet", 0, next.careMistakes)
        assertEquals(0, next.studySessions)
        assertEquals(0f, next.peakBond, 0.0001f)
        assertTrue("a bond is with somebody in particular", next.stats.bond < 20f)
        assertTrue("nothing is inherited but what a parent could teach", next.skills.isEmpty())
        assertTrue("intellect is a brain, and this is a different brain", next.intellect < 10f)
        assertEquals(LifeStage.EGG, next.stage)
        assertEquals(0L, next.ageSeconds)
        assertTrue(!next.isDead)
        assertNull(next.deathReason)
    }

    @Test
    fun `a toy paid for once is not repossessed by the funeral`() {
        val furnished = starved(hatched()).copy(
            inventory = mapOf(
                "hat_crown" to 1,
                "room_beach" to 1,
                "toy_ball" to 1,
                "toy_drum" to 1,
                "meal_stew" to 4,
            ),
        )
        val next = Simulation.nextGeneration(furnished, "Second", Species.VOLT, furnished.lastTickMillis + 5_000)

        assertEquals("a hat outlives its wearer", 1, next.inventory["hat_crown"])
        assertEquals(1, next.inventory["room_beach"])
        assertEquals("a ball is furniture, not a meal — nothing about it was used up", 1, next.inventory["toy_ball"])
        assertEquals(1, next.inventory["toy_drum"])
        assertEquals("food does not keep; the new pet gets its own starter pack", null, next.inventory["meal_stew"])
        assertEquals(3, next.inventory["snack_berry"])
    }

    private fun hatched(): PetState =
        Simulation.advance(Simulation.newGame("Test", Species.AQUA, start), start + 120_000, config).state

    /** An hour of a pet whose needs are topped up every tick. */
    private fun kept(): PetState {
        var pet = hatched()
        repeat(60) {
            pet = pet.copy(stats = Stats(satiety = 95f, happiness = 95f, energy = 95f, hygiene = 95f, health = 100f))
            pet = Simulation.advance(pet, pet.lastTickMillis + 60_000, config).state
        }
        return pet
    }

    /** Minute-by-minute neglect, which the simulation treats as neglect the player could see. */
    private fun starved(state: PetState): PetState {
        var pet = state.copy(stats = state.stats.copy(satiety = 1f, happiness = 1f, hygiene = 1f, health = 5f))
        repeat(120) {
            if (!pet.isDead) pet = Simulation.advance(pet, pet.lastTickMillis + 60_000, config).state
        }
        return pet
    }
}
