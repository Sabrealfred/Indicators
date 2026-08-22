package com.neopal.pet.domain

/**
 * How often the creature is allowed to think somewhere other than here.
 *
 * This is three constants and two lines of arithmetic, and it lives in the domain rather than in
 * the view model for one reason: it is the code that decides *not* to call the network, and that
 * decision fails silently. When it wrongly says "too soon", nothing crashes, nothing is logged,
 * and the local [Brain] answers exactly as it would if the player had never switched the remote
 * mind on. A creature that has quietly stopped thinking and a creature that was never configured
 * to think look identical from the outside.
 *
 * So it is pure, and it is tested. Everything Android-shaped stays in the view model; the
 * arithmetic that can be wrong without anyone noticing does not.
 */
object Cadence {

    /**
     * "Has not happened yet."
     *
     * Not `Long.MIN_VALUE`, and that is the whole point of naming it. [due] subtracts this from an
     * age, and `age - Long.MIN_VALUE` overflows back into a large negative for any age at all — so
     * the gap reads as unmet forever and the first call never goes out. Halving it leaves the
     * subtraction room to stay positive for longer than any creature will live.
     */
    const val NEVER = Long.MIN_VALUE / 2

    /**
     * Pet seconds between reconsiderations. Five minutes is frequent enough that a player watching
     * for a while sees it happen, and rare enough that a free tier lasts the day.
     */
    const val RECONSIDER_SECONDS = 300L

    /**
     * Pet seconds between errands. Longer than the reconsider gap because a plan is meant to be
     * worked through rather than replaced: setting a new one every few minutes would mean the
     * creature never finished an afternoon it started.
     */
    const val PLAN_SECONDS = 1_800L

    /**
     * Pet seconds between unprompted remarks. Long — over an hour — because the whole value of the
     * creature speaking first is that it is rare enough to be worth reading.
     */
    const val SPEAK_FIRST_SECONDS = 4_000L

    /** Narrowest and widest the intellect scaling may stretch a gap. */
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 1.5f

    /**
     * True when enough pet time has passed since [lastAtSeconds] to act again.
     *
     * [lastAtSeconds] of [NEVER] always answers true: something that has never happened is due.
     */
    fun due(ageSeconds: Long, lastAtSeconds: Long, gapSeconds: Long): Boolean =
        ageSeconds - lastAtSeconds >= gapSeconds

    /**
     * How often a creature of this intellect stops to think, as a gap in pet seconds.
     *
     * A bright creature thinks more often than a newborn, which is the point — but the range is
     * deliberately narrow. The reason is not caution about how clever it is allowed to be: it is
     * that every one of these is a request, most people will run this on a free tier, and a
     * creature that thought twice as hard would spend the day's allowance by lunch and then think
     * not at all. A narrow band is what keeps a clever creature clever all day rather than
     * brilliant for an hour.
     */
    fun gapFor(base: Long, intellect: Float): Long {
        val scale = (MAX_SCALE - (intellect.coerceIn(0f, 100f) / 100f) * 0.9f)
            .coerceIn(MIN_SCALE, MAX_SCALE)
        return (base * scale).toLong()
    }
}
