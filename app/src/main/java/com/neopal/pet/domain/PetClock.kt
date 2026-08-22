package com.neopal.pet.domain

import kotlin.math.roundToInt

/**
 * The day-length setting: what it is allowed to be, and what changing it must not cost.
 *
 * Both halves of this were wrong in the settings screen and both were invisible until you touched
 * the control. The range was a pair of literals that did not include the value the game actually
 * ships with, and the day index the missions and the streak are graded against is derived from the
 * clock, so moving the clock moved the calendar under a streak that had done nothing wrong.
 *
 * It lives in the domain because "which values are legal" and "what has to be re-based when they
 * change" are rules about the game, not about a slider, and because a slider cannot be tested here
 * and this can.
 */
object PetClock {

    /** Short enough to watch a day turn while you sit there. */
    const val MIN_MINUTES_PER_DAY = 5

    /**
     * Long enough to include [GameConfig.secondsPerPetDay]'s own default.
     *
     * This is the whole of the first bug: the slider read 5..240 while the game shipped at 360, so
     * the control opened pinned to its maximum, misreporting the setting by two hours, and the
     * first touch anywhere on it -- including a touch that meant "leave this alone" -- silently cut
     * the day by a third or more. A control whose range excludes its own default cannot show the
     * truth and cannot be cancelled. There is a test that pins the two together.
     */
    const val MAX_MINUTES_PER_DAY = 360

    /** The stop the slider snaps to, so the value lands on the grid the same way the art does. */
    const val MINUTES_PER_NOTCH = 5

    /** Intervals between [MIN_MINUTES_PER_DAY] and [MAX_MINUTES_PER_DAY], for the slider. */
    val NOTCHES: Int get() = (MAX_MINUTES_PER_DAY - MIN_MINUTES_PER_DAY) / MINUTES_PER_NOTCH

    /** What the setting currently says, in minutes. */
    fun minutesOf(config: GameConfig): Int = (config.secondsPerPetDay / 60L).toInt()

    /** A raw slider position, rounded to a notch and clamped into range. */
    fun snapMinutes(minutes: Float): Int {
        val notch = (minutes / MINUTES_PER_NOTCH).roundToInt() * MINUTES_PER_NOTCH
        return notch.coerceIn(MIN_MINUTES_PER_DAY, MAX_MINUTES_PER_DAY)
    }

    /** [config] with the day length set from a slider position. */
    fun withMinutes(config: GameConfig, minutes: Float): GameConfig =
        config.copy(secondsPerPetDay = snapMinutes(minutes) * 60L)

    /**
     * Re-bases the pet's day counter onto a new clock, so changing the day length does not read as
     * days having passed.
     *
     * This is the second bug. [Missions.rollOver] grades the day that just ended by comparing
     * `ageInPetDays` against the ledger's stored index, and both halves of that comparison are
     * measured in *different* units the instant the clock changes. Anything but a step of exactly
     * one day is treated -- correctly, for its own purposes -- as time the player was not there
     * for, so the streak is dropped and the player is told they lost it. They lost nothing: the
     * pet is the same age it was a second ago, and the only thing that moved was a ruler.
     *
     * Only the index is re-stamped. The counters snapshotted at the start of the day stay exactly
     * as they were, so today's mission progress and anything already claimed survive the change
     * too -- the day is re-labelled, not restarted.
     *
     * It takes [from] as well as [to], and does nothing at all when the two agree, because the
     * guard has to be structural rather than a rule about who is allowed to call it. Re-stamping
     * the index on an *unchanged* clock would swallow a real day boundary and hand the player a
     * streak that could never be broken -- the mirror image of the bug, and a worse one. That is
     * not hypothetical: the first version took only the new config and the test named "a real day
     * boundary is still graded" caught it.
     */
    fun reclock(state: PetState, from: GameConfig, to: GameConfig): PetState {
        if (from.secondsPerPetDay == to.secondsPerPetDay) return state
        val today = state.ageInPetDays(to)
        if (today == state.dayLedger.dayIndex) return state
        return state.copy(dayLedger = state.dayLedger.copy(dayIndex = today))
    }
}
