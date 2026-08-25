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
        // The streak used to be zeroed here, back when a death took the player's own history
        // with it. `Simulation.nextGeneration` now carries `careStreakDays` across on purpose --
        // "the streak counts days the player showed up, and a funeral is not a day skipped" --
        // and this roll-over was quietly undoing that a tick later. See the test below.
        assertEquals(3, rolled.careStreakDays)
    }

    @Test
    fun `a backward day jump is not an absence and does not cost the streak`() {
        // The age going *down* is not time the player was away for: it is a new generation
        // starting its own clock at zero. Grading it as a skipped day told the player they had
        // lost a streak that `nextGeneration` had just deliberately handed them.
        val pet = petOnDay(6).copy(careStreakDays = 3, bestCareStreak = 7)
        val reborn = pet.copy(ageSeconds = 0L, generation = 2)
        val events = mutableListOf<GameEvent>()
        val rolled = Missions.rollOver(reborn, config, events)

        // Asserted first, because it is the part the player actually sees.
        assertTrue(
            "the player is told they lost a streak they did not lose: " +
                events.filterIsInstance<GameEvent.Message>().map { it.text },
            events.none { it is GameEvent.Message && it.text.contains("Streak lost") },
        )
        assertEquals("the day is re-labelled onto the new clock", 0, rolled.dayLedger.dayIndex)
        assertEquals("the streak the player earned is still theirs", 3, rolled.careStreakDays)
        assertEquals("the record is untouched", 7, rolled.bestCareStreak)
    }

    @Test
    fun `a backward jump does not pay a streak bonus either`() {
        // Not grading the day has to mean *not grading it*. Re-basing the ledger must not become
        // a way to collect a perfect-day payout for a day that never happened.
        val pet = satisfyAll(petOnDay(6)).copy(careStreakDays = 3, coins = 100)
        val reborn = pet.copy(ageSeconds = 0L, generation = 2)
        val rolled = Missions.rollOver(reborn, config, mutableListOf())
        assertEquals("no payout for a day that was never lived", 100, rolled.coins)
        assertEquals("and no free step on the streak", 3, rolled.careStreakDays)
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

    @Test
    fun `a day lived well keeps the streak even after the stats have drained`() {
        // A day whose offering asks for a stat threshold. Counter goals cannot be un-finished by
        // the clock; a stat goal can, and that is the whole of this bug.
        val day = dayOffering("m_full")
        val pet = satisfyAll(petOnDay(day)).copy(lastTickMillis = start)

        // The day being lived: half a minute with every need up where the missions want them.
        val lived = Simulation.advance(pet, start + 30_000L, config).state
        assertTrue("the day really was finished while it was being lived", Missions.allComplete(lived))

        // Hours later the needs have drained, exactly as they must — satiety alone loses 20 a
        // hour. Nothing the player did has been undone; only the clock has moved.
        val tomorrow = lived.copy(
            ageSeconds = (day + 1) * config.secondsPerPetDay,
            stats = lived.stats.copy(satiety = 30f, happiness = 30f, energy = 30f, hygiene = 30f),
        )
        val rolled = Missions.rollOver(tomorrow, config, mutableListOf())
        assertEquals(
            "the day that is ending must be graded on how it was lived, not on the stats it " +
                "happens to end with",
            1,
            rolled.careStreakDays,
        )
        assertEquals(1, rolled.bestCareStreak)
    }

    @Test
    fun `a stat goal reached during the day stays finished for the rest of it`() {
        val day = dayOffering("m_bath")
        val pet = satisfyAll(petOnDay(day)).copy(lastTickMillis = start)
        val lived = Simulation.advance(pet, start + 30_000L, config).state
        val bath = Missions.today(lived).first { it.mission.id == "m_bath" }
        assertTrue("scrubbed clean is scrubbed clean", bath.complete)

        val grubbyAgain = lived.copy(stats = lived.stats.copy(hygiene = 20f))
        assertTrue(
            "a goal the player finished cannot un-finish itself while the day is still running",
            Missions.today(grubbyAgain).first { it.mission.id == "m_bath" }.complete,
        )
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
