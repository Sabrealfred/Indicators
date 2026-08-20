package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Daily missions and the care streak. The rules worth guarding are the ones a player would
 * feel cheated by: progress that vanishes, a reward paid twice, or a streak that survives a
 * day the player was not there for.
 */
class MissionsTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    /** A hatched pet sitting at the start of day [day] with an already-opened ledger. */
    private fun petOnDay(day: Int): PetState {
        val base = Simulation
            .advance(Simulation.newGame("Test", Species.LEAF, start), start + 120_000, config)
            .state
        val state = base.copy(ageSeconds = day * config.secondsPerPetDay)
        return state.copy(dayLedger = DayLedger.of(state, day))
    }

    /** First day index whose offering contains [id]. */
    private fun dayOffering(id: String): Int =
        (0..500).first { day -> Missions.forDay(day).any { it.id == id } }

    @Test
    fun `a day offers a stable set of missions`() {
        val first = Missions.forDay(12).map { it.id }
        val again = Missions.forDay(12).map { it.id }
        assertEquals("the same day must always offer the same goals", first, again)
        assertEquals(Missions.PER_DAY, first.size)
    }

    @Test
    fun `different days offer different missions`() {
        val sets = (0..20).map { Missions.forDay(it).map { m -> m.id }.toSet() }.distinct()
        assertTrue("21 days that all offer the same three goals would be a bug", sets.size > 3)
    }

    @Test
    fun `progress is measured against the ledger, not from zero`() {
        val day = dayOffering("m_feed")
        val pet = petOnDay(day).copy(mealsEaten = 40).let { it.copy(dayLedger = DayLedger.of(it, day)) }
        val feed = Missions.today(pet).first { it.mission.id == "m_feed" }
        assertEquals("yesterday's 40 meals must not count toward today", 0, feed.done)

        val fed = pet.copy(mealsEaten = 42)
        assertEquals(2, Missions.today(fed).first { it.mission.id == "m_feed" }.done)
    }

    @Test
    fun `claiming pays out once and only once`() {
        val day = dayOffering("m_feed")
        val pet = petOnDay(day).copy(mealsEaten = 0).let { it.copy(dayLedger = DayLedger.of(it, day)) }
        val done = pet.copy(mealsEaten = 5)
        assertTrue(Missions.today(done).first { it.mission.id == "m_feed" }.complete)

        val events = mutableListOf<GameEvent>()
        val (paid, reward) = Missions.claim(done, events)
        assertTrue("a finished mission must pay something", reward.coins > 0)
        assertEquals(done.coins + reward.coins, paid.coins)

        val (again, second) = Missions.claim(paid, mutableListOf())
        assertEquals("a claimed mission must not pay a second time", 0, second.coins)
        assertEquals(paid.coins, again.coins)
    }

    @Test
    fun `an unfinished mission cannot be claimed`() {
        val day = dayOffering("m_feast")
        val pet = petOnDay(day)
        val (unchanged, reward) = Missions.claim(pet.copy(mealsEaten = pet.dayLedger.meals + 1), mutableListOf())
        assertEquals(0, reward.coins)
        assertEquals(pet.coins, unchanged.coins)
    }

    @Test
    fun `a perfect day advances the streak and clears the claims`() {
        val day = 3
        val pet = petOnDay(day)
        // Satisfy whatever today happens to ask for, then roll into tomorrow.
        val perfect = satisfyAll(pet)
        assertTrue(Missions.allComplete(perfect))

        val tomorrow = perfect.copy(
            ageSeconds = (day + 1) * config.secondsPerPetDay,
            claimedMissionIds = setOf("m_feed"),
        )
        val rolled = Missions.rollOver(tomorrow, config, mutableListOf())
        assertEquals(1, rolled.careStreakDays)
        assertEquals(1, rolled.bestCareStreak)
        assertTrue("claims belong to the day that is over", rolled.claimedMissionIds.isEmpty())
        assertEquals(day + 1, rolled.dayLedger.dayIndex)
    }

    @Test
    fun `an unfinished day breaks the streak`() {
        val day = 3
        val pet = petOnDay(day).copy(careStreakDays = 5, bestCareStreak = 5)
        val tomorrow = pet.copy(ageSeconds = (day + 1) * config.secondsPerPetDay)
        val rolled = Missions.rollOver(tomorrow, config, mutableListOf())
        assertEquals(0, rolled.careStreakDays)
        assertEquals("the best is a record, not a current value", 5, rolled.bestCareStreak)
    }

    @Test
    fun `a skipped day breaks the streak even if the missions look finished`() {
        val day = 3
        val perfect = satisfyAll(petOnDay(day)).copy(careStreakDays = 4)
        // Two days later: the player was not there for the day in between.
        val later = perfect.copy(ageSeconds = (day + 2) * config.secondsPerPetDay)
        val rolled = Missions.rollOver(later, config, mutableListOf())
        assertEquals(0, rolled.careStreakDays)
        assertEquals(day + 2, rolled.dayLedger.dayIndex)
    }

    @Test
    fun `a save with no ledger is adopted without a spurious perfect day`() {
        // What an upgrade from a build that predates missions looks like: real counters, but a
        // ledger still sitting at its defaults.
        val pet = petOnDay(0).copy(
            ageSeconds = 4 * config.secondsPerPetDay,
            mealsEaten = 60,
            gamesPlayed = 30,
            gamesWon = 20,
            cleanups = 40,
            praises = 15,
            dayLedger = DayLedger(),
        )
        val events = mutableListOf<GameEvent>()
        val rolled = Missions.rollOver(pet, config, events)
        assertEquals(0, rolled.careStreakDays)
        assertEquals(4, rolled.dayLedger.dayIndex)
        assertEquals("the adopted ledger must capture the counters as they stand", 60, rolled.dayLedger.meals)
        assertTrue(Missions.today(rolled).none { it.complete })
    }

    @Test
    fun `a new generation resets the ledger instead of grading a failed day`() {
        val pet = petOnDay(6).copy(careStreakDays = 3)
        val reborn = pet.copy(ageSeconds = 0L, generation = 2)
        val rolled = Missions.rollOver(reborn, config, mutableListOf())
        assertEquals(0, rolled.dayLedger.dayIndex)
        assertEquals(0, rolled.careStreakDays)
    }

    @Test
    fun `the streak bonus stops growing so it cannot inflate the economy`() {
        assertEquals(0, Missions.streakBonus(0))
        assertTrue(Missions.streakBonus(3) > Missions.streakBonus(1))
        assertEquals(Missions.streakBonus(7), Missions.streakBonus(400))
    }

    @Test
    fun `rolling over on the same day changes nothing`() {
        val pet = petOnDay(2).copy(coins = 123)
        assertEquals(pet, Missions.rollOver(pet, config, mutableListOf()))
    }

    @Test
    fun `the simulation rolls the day over on its own`() {
        val pet = petOnDay(1).copy(lastTickMillis = start)
        val nextDay = start + config.secondsPerPetDay * 1000L
        val after = Simulation.advance(pet, nextDay, config.copy(maxOfflineSeconds = config.secondsPerPetDay)).state
        assertTrue("advance() must close the day out; nothing else calls rollOver", after.dayLedger.dayIndex > 1)
    }

    /** Nudges every counter and stat far enough that today's three are all finished. */
    private fun satisfyAll(state: PetState): PetState {
        val l = state.dayLedger
        return state.copy(
            mealsEaten = l.meals + 10,
            gamesPlayed = l.games + 10,
            gamesWon = l.wins + 10,
            cleanups = l.cleanups + 10,
            praises = l.praises + 10,
            medicineDoses = l.medicine + 10,
            careMistakes = l.mistakes,
            stats = state.stats.copy(satiety = 95f, happiness = 95f, energy = 95f, hygiene = 95f),
        )
    }
}
