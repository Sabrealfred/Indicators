package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the direct-manipulation interactions and the records they feed. */
class InteractionsTest {

    private fun pet(): PetState = PetState(
        stage = LifeStage.CHILD,
        lastTickMillis = 1L,
        stats = Stats(satiety = 50f, happiness = 50f, energy = 60f, hygiene = 50f, health = 100f),
    )

    @Test
    fun `scooping removes exactly one mess`() {
        val messy = pet().copy(poops = 3)
        val result = CareActions.scoopPoop(messy)
        assertTrue(result.accepted)
        assertEquals(2, result.state.poops)
        assertTrue(result.state.stats.hygiene > messy.stats.hygiene)
    }

    @Test
    fun `scooping a clean room is refused`() {
        assertFalse(CareActions.scoopPoop(pet()).accepted)
    }

    @Test
    fun `tickling raises mood but is capped`() {
        val happy = pet().copy(stats = pet().stats.copy(happiness = 99.5f))
        assertFalse(CareActions.tickle(happy).accepted)
        assertTrue(CareActions.tickle(pet()).state.stats.happiness > pet().stats.happiness)
    }

    @Test
    fun `tossing costs energy and is refused when exhausted`() {
        val tired = pet().copy(stats = pet().stats.copy(energy = 3f))
        assertFalse(CareActions.toss(tired).accepted)
        val result = CareActions.toss(pet())
        assertTrue(result.accepted)
        assertTrue(result.state.stats.energy < pet().stats.energy)
    }

    @Test
    fun `a better run replaces the high score and a worse one does not`() {
        val first = CareActions.finishGame(pet(), won = true, score = 1f, gameName = "Rhythm Tap", gameId = "rhythm", points = 900)
        assertEquals(900, first.state.highScores["rhythm"])
        val worse = CareActions.finishGame(first.state, won = false, score = 0.2f, gameName = "Rhythm Tap", gameId = "rhythm", points = 300)
        assertEquals(900, worse.state.highScores["rhythm"])
        val better = CareActions.finishGame(worse.state, won = true, score = 1f, gameName = "Rhythm Tap", gameId = "rhythm", points = 1500)
        assertEquals(1500, better.state.highScores["rhythm"])
    }

    @Test
    fun `stat deltas report the biggest movers only`() {
        val before = pet()
        val after = CareActions.feed(before.copy(inventory = mapOf("meal_stew" to 1)), "meal_stew").state
        val deltas = statDeltas(before, after)
        assertTrue(deltas.isNotEmpty())
        assertTrue(deltas.size <= 3)
        assertTrue(deltas.any { it.label == "FOOD" && it.positive })
    }

    @Test
    fun `the diary records milestones and never repeats the last line`() {
        val config = GameConfig.Default
        val hatched = Chronicle.record(pet(), listOf(GameEvent.Hatched), config)
        assertEquals(1, hatched.chronicle.size)
        val again = Chronicle.record(hatched, listOf(GameEvent.Hatched), config)
        assertEquals(1, again.chronicle.size)
        val sick = Chronicle.record(again, listOf(GameEvent.GotSick), config)
        assertEquals(2, sick.chronicle.size)
        assertEquals(ChronicleKind.TROUBLE, sick.chronicle.last().kind)
    }

    @Test
    fun `a hatching run writes its first diary line`() {
        val start = 1_000_000L
        val egg = Simulation.newGame("Test", Species.LEAF, start)
        val hatched = Simulation.advance(egg, start + 120_000, GameConfig.Default).state
        assertTrue(hatched.chronicle.isNotEmpty())
    }
}
