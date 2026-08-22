package com.neopal.pet.domain

/**
 * Whether the player still owes this death a visit to the memorial.
 *
 * The home screen opens the memorial the moment the pet is dead, which is right; what was wrong is
 * that it had no way of remembering it had already done so. Navigation Compose disposes a
 * destination's composition while another is on top of it, so pressing "Stay a moment" popped back
 * to a home screen that was being built from scratch, saw a dead pet, and navigated straight to the
 * memorial again. The player could not get out, and "Stay a moment" — a button whose entire purpose
 * is to let someone sit with the pet a while longer — was the one thing that could not happen.
 *
 * A death needs an identity for this, and "is dead" is not one: two generations can die at the same
 * age, which is not far-fetched at all when both die of old age. [deathKey] pairs the generation
 * with the moment, so a second death is a second memorial and the same death is never two.
 *
 * This is here rather than in the composable because it is a rule with a wrong answer, and because
 * a rule with a wrong answer belongs somewhere a test can reach it.
 */
object Memorial {

    /**
     * A stable name for the death this pet is in, or null while it is alive.
     *
     * Generation as well as the moment: a new run resets `ageSeconds`, so `deathAtSeconds` alone
     * would let one line's second death wear its first death's name and be silently swallowed.
     */
    fun deathKey(state: PetState): String? =
        if (!state.isDead) null else "${state.generation}:${state.deathAtSeconds}"

    /**
     * True when the memorial should be opened for [key] — a death that has not been mourned yet.
     *
     * [mourned] is the key of the last death the player was shown, which is navigation state and
     * has to be held somewhere that outlives the home screen's composition. See `NeoPalNav`.
     */
    fun shouldOpen(key: String?, mourned: String?): Boolean = key != null && key != mourned
}
