package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The day-length setting.
 *
 * Two failures met in one control: a range that did not contain the value the game ships with, so
 * merely touching the slider changed the game; and a day counter derived from that value, so
 * changing it looked to the mission roll-over like days going by and took the care streak with it.
 */
class PetClockTest {

    private fun keeper(config: GameConfig, streak: Int = 5, ageSeconds: Long = 0L): PetState {
        val base = PetState(
            name = "Pip",
            species = Species.LEAF,
            stage = LifeStage.ADULT,
            ageSeconds = ageSeconds,
            careStreakDays = streak,
            bestCareStreak = streak,
        )
        return base.copy(dayLedger = DayLedger.of(base, base.ageInPetDays(config)))
    }

    // ------------------------------------------------------------------ the range

    @Test
    fun `the slider's range contains the value the game actually ships with`() {
        val shipped = PetClock.minutesOf(GameConfig.Default)
        assertTrue(
            "a control that cannot show its own default reports a lie and cannot be cancelled: " +
                "default is $shipped minutes, range is " +
                "${PetClock.MIN_MINUTES_PER_DAY}..${PetClock.MAX_MINUTES_PER_DAY}",
            shipped in PetClock.MIN_MINUTES_PER_DAY..PetClock.MAX_MINUTES_PER_DAY,
        )
    }

    @Test
    fun `the default sits exactly on a notch, so it survives a round trip through the control`() {
        val shipped = PetClock.minutesOf(GameConfig.Default)
        assertEquals(
            "reading the setting and writing it straight back must be a no-op",
            GameConfig.Default.secondsPerPetDay,
            PetClock.withMinutes(GameConfig.Default, shipped.toFloat()).secondsPerPetDay,
        )
    }

    @Test
    fun `every notch is a whole number of five-minute stops inside the range`() {
        assertEquals(0, (PetClock.MAX_MINUTES_PER_DAY - PetClock.MIN_MINUTES_PER_DAY) % PetClock.MINUTES_PER_NOTCH)
        assertTrue(PetClock.NOTCHES > 0)
    }

    @Test
    fun `a slider position off the grid lands on the nearest stop, and never outside the range`() {
        assertEquals(60, PetClock.snapMinutes(58.4f))
        assertEquals(PetClock.MIN_MINUTES_PER_DAY, PetClock.snapMinutes(-40f))
        assertEquals(PetClock.MAX_MINUTES_PER_DAY, PetClock.snapMinutes(99_999f))
    }

    // ------------------------------------------------------------------ the streak

    @Test
    fun `changing the day length does not cost the player their streak`() {
        // Half a day into a six-hour day, five days into a streak.
        val config = GameConfig.Default
        val pet = keeper(config, streak = 5, ageSeconds = config.secondsPerPetDay * 3 + 100L)
        val faster = config.copy(secondsPerPetDay = 3_600L)

        val events = mutableListOf<GameEvent>()
        val after = Missions.rollOver(PetClock.reclock(pet, config, faster), faster, events)

        assertEquals("the pet is the same age it was a second ago; only a ruler moved", 5, after.careStreakDays)
        assertTrue(
            "and nobody should be told they lost anything: ${events.map { it }}",
            events.none { it is GameEvent.Message && it.text.contains("Streak lost") },
        )
    }

    @Test
    fun `the day is re-labelled, not restarted`() {
        val config = GameConfig.Default
        val pet = keeper(config, ageSeconds = config.secondsPerPetDay * 3 + 100L)
            .let { it.copy(mealsEaten = it.mealsEaten + 2, claimedMissionIds = setOf("m_feed")) }
        val slower = config.copy(secondsPerPetDay = 12L * 3600L)

        val after = PetClock.reclock(pet, config, slower)

        assertEquals("today's counters were snapshotted at dawn and dawn has not moved",
            pet.dayLedger.meals, after.dayLedger.meals)
        assertEquals("nor has anything already collected", pet.claimedMissionIds, after.claimedMissionIds)
        assertEquals("only the index moves", after.ageInPetDays(slower), after.dayLedger.dayIndex)
    }

    @Test
    fun `a clock that did not change changes nothing at all`() {
        val config = GameConfig.Default
        val pet = keeper(config, ageSeconds = config.secondsPerPetDay * 2 + 5L)
        assertEquals(pet, PetClock.reclock(pet, config, config))
    }

    @Test
    fun `a real day boundary is still graded, so the fix cannot be used to freeze a streak`() {
        // The mirror-image bug, and the worse one. Re-stamping the index on an unchanged clock
        // would swallow every real day boundary and hand the player an unbreakable streak. This
        // is why reclock takes the old config as well as the new one and refuses when they agree.
        val config = GameConfig.Default
        val pet = keeper(config, streak = 5, ageSeconds = 10L)
            .let { it.copy(ageSeconds = config.secondsPerPetDay + 10L) }

        val after = Missions.rollOver(PetClock.reclock(pet, config, config), config, mutableListOf())
        assertEquals("a day with no missions finished breaks the streak, as it always did",
            0, after.careStreakDays)
    }
}
