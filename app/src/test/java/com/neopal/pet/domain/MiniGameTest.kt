package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of games, and the one achievement that depends on it being right.
 *
 * Most of this is about save keys. A high score is filed under [MiniGame.id], so those strings are
 * not names — they are data already sitting on players' devices, and renaming one silently throws
 * away whatever was filed under the old spelling.
 */
class MiniGameTest {

    private fun pet(): PetState = Simulation
        .newGame("T", Species.LEAF, 1_000_000L)
        .copy(stage = LifeStage.ADULT)

    @Test
    fun `the three original ids are exactly what they always were`() {
        // These are in save files already. Changing one loses a player's record silently: the new
        // key reads as never played, and the old score is orphaned rather than migrated.
        assertEquals("rhythm", MiniGame.RHYTHM.id)
        assertEquals("memory", MiniGame.MEMORY.id)
        assertEquals("catch", MiniGame.CATCH.id)
    }

    @Test
    fun `no two games share a save key`() {
        val ids = MiniGame.entries.map { it.id }
        assertEquals("a shared key means two games overwriting one record", ids.size, ids.toSet().size)
        assertTrue("a blank key would collide with a missing one", ids.none { it.isBlank() })
    }

    @Test
    fun `ids are looked up by the string the save holds`() {
        MiniGame.entries.forEach { assertEquals(it, MiniGame.byId(it.id)) }
        assertNull("an id from a newer build must not resolve to something near enough",
            MiniGame.byId("game_hide"))
    }

    @Test
    fun `a game is played only once it has actually been scored in`() {
        val fresh = pet()
        assertEquals(0, MiniGame.playedCount(fresh))
        MiniGame.entries.forEach { assertFalse(it.played(fresh)) }

        // A zero never reaches highScores — a record has to beat the previous best — so a zero
        // sitting in the map at all would mean something else had gone wrong.
        val zeroed = fresh.copy(highScores = mapOf(MiniGame.CATCH.id to 0))
        assertFalse("nought is not a score", MiniGame.CATCH.played(zeroed))
    }

    @Test
    fun `the completion badge needs every game and not merely most of them`() {
        val badge = Achievements.get("all_games")
        assertNotNull(badge)

        val allButOne = pet().copy(
            highScores = MiniGame.entries.drop(1).associate { it.id to 100 },
        )
        assertFalse("six of seven is not all of them", badge!!.test(allButOne))

        val everything = pet().copy(highScores = MiniGame.entries.associate { it.id to 100 })
        assertTrue(badge.test(everything))
    }

    @Test
    fun `the badge counts games rather than entries in the map`() {
        // A save carrying keys from a build that had games this one does not must not be able to
        // unlock a badge about games that are no longer here.
        val stale = pet().copy(
            highScores = MiniGame.entries.drop(1).associate { it.id to 100 } +
                mapOf("game_that_no_longer_exists" to 999, "another_ghost" to 999),
        )
        assertFalse(Achievements.get("all_games")!!.test(stale))
    }

    @Test
    fun `every game says who it is`() {
        MiniGame.entries.forEach {
            assertTrue("a blank name would show as a blank tile", it.displayName.isNotBlank())
        }
        assertEquals(
            "duplicate names would make two cartridges indistinguishable",
            MiniGame.entries.size,
            MiniGame.entries.map { it.displayName }.toSet().size,
        )
    }
}
