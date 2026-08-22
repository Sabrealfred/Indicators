package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop, and the rule that breaks it.
 *
 * `HomeScreen` opens the memorial when the pet is dead. Coming back disposes and rebuilds the home
 * screen, which saw a dead pet and opened the memorial again — so "Stay a moment" and the system
 * back button both bounced the player straight back, forever.
 *
 * [`the loop, reproduced`] below drives that sequence with the gate wired in as the navigation
 * graph wires it. Take the gate out — `shouldOpen` returning `key != null` — and it fails.
 */
class MemorialTest {

    private fun pet(
        dead: Boolean = true,
        generation: Int = 1,
        deathAt: Long = 40_000L,
    ) = PetState(
        name = "Pip",
        species = Species.LEAF,
        stage = LifeStage.ELDER,
        generation = generation,
        isDead = dead,
        deathReason = if (dead) DeathReason.OLD_AGE else null,
        deathAtSeconds = if (dead) deathAt else 0L,
    )

    @Test
    fun `a living pet has no death to mourn`() {
        assertNull(Memorial.deathKey(pet(dead = false)))
        assertFalse(Memorial.shouldOpen(Memorial.deathKey(pet(dead = false)), null))
    }

    @Test
    fun `the loop, reproduced`() {
        // Exactly the sequence a player performs. `mourned` is what the navigation graph holds.
        var mourned: String? = null
        val gone = pet()
        val key = Memorial.deathKey(gone)

        // 1. The pet dies while the player is on the home screen.
        assertTrue("the memorial has to open by itself, or a death goes unremarked",
            Memorial.shouldOpen(key, mourned))
        mourned = key

        // 2. The player reads it and presses "Stay a moment". Home is rebuilt from scratch and
        //    asks again -- this is the exact moment the old code navigated straight back.
        assertFalse("this is the loop", Memorial.shouldOpen(key, mourned))

        // 3. And it stays broken however many times the screen is rebuilt.
        repeat(5) { assertFalse(Memorial.shouldOpen(key, mourned)) }
    }

    @Test
    fun `the next generation's death opens the memorial again`() {
        val first = pet(generation = 1, deathAt = 40_000L)
        val mourned = Memorial.deathKey(first)
        // A new run resets ageSeconds, so an heir can plausibly die at the very same age -- both of
        // old age, at the end of the same-length life. The generation is what tells them apart.
        val second = pet(generation = 2, deathAt = 40_000L)

        assertNotEquals(mourned, Memorial.deathKey(second))
        assertTrue("a second death is a second memorial",
            Memorial.shouldOpen(Memorial.deathKey(second), mourned))
    }

    @Test
    fun `two deaths of the same line at different moments are different deaths`() {
        assertNotEquals(
            Memorial.deathKey(pet(generation = 2, deathAt = 40_000L)),
            Memorial.deathKey(pet(generation = 2, deathAt = 41_000L)),
        )
    }

    @Test
    fun `the key does not change while the player is looking at it`() {
        // It is read on every recomposition; a key that drifted would reopen the memorial on its own.
        val gone = pet()
        assertEquals(Memorial.deathKey(gone), Memorial.deathKey(gone))
    }
}
