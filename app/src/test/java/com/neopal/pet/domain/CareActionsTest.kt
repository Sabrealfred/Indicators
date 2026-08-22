package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareActionsTest {

    private fun pet(): PetState = PetState(
        stage = LifeStage.CHILD,
        lastTickMillis = 1L,
        stats = Stats(satiety = 40f, happiness = 50f, energy = 60f, hygiene = 70f, health = 100f),
    )

    @Test
    fun `feeding raises satiety and consumes the item`() {
        val before = pet()
        val result = CareActions.feed(before, "meal_bowl")
        assertTrue(result.accepted)
        assertTrue(result.state.stats.satiety > before.stats.satiety)
        assertEquals(1, result.state.inventory["meal_bowl"])
        assertEquals(PetAnimation.EAT, result.animation)
    }

    @Test
    fun `a full pet refuses more food`() {
        val full = pet().copy(stats = pet().stats.copy(satiety = 99f))
        val result = CareActions.feed(full, "meal_bowl")
        assertFalse(result.accepted)
        assertEquals(PetAnimation.REFUSE, result.animation)
        assertEquals(full.inventory["meal_bowl"], result.state.inventory["meal_bowl"])
    }

    @Test
    fun `a sleeping pet cannot be fed`() {
        val asleep = pet().copy(isSleeping = true)
        assertFalse(CareActions.feed(asleep, "meal_bowl").accepted)
    }

    @Test
    fun `cleaning clears the mess`() {
        val messy = pet().copy(poops = 4, stats = pet().stats.copy(hygiene = 20f))
        val result = CareActions.cleanRoom(messy)
        assertEquals(0, result.state.poops)
        assertTrue(result.state.stats.hygiene > messy.stats.hygiene)
    }

    @Test
    fun `buying fails without enough coins`() {
        val broke = pet().copy(coins = 1)
        val result = CareActions.buy(broke, "hat_crown")
        assertFalse(result.accepted)
        assertEquals(1, result.state.coins)
    }

    @Test
    fun `the shop never runs out of what it sells`() {
        // Reported as "the shop will not restock; once bought out, inventory never returns".
        // It does not reproduce, and this is the test that says so on purpose: the catalog has
        // no stock at all, so a consumable can be bought as often as there are coins for it —
        // including from an empty pantry, which is the case the report claims is stuck.
        var state = pet().copy(
            coins = 500,
            inventory = emptyMap(),
            // Nothing here is about achievements, and an unlock would pay coins into the sum.
            unlockedAchievements = Achievements.all.map { it.id }.toSet(),
        )
        repeat(10) {
            val result = CareActions.buy(state, "meal_bowl")
            assertTrue("purchase ${it + 1} was refused", result.accepted)
            state = result.state
        }
        assertEquals(10, state.inventory["meal_bowl"])
        assertEquals(500 - 10 * ItemCatalog.require("meal_bowl").price, state.coins)

        // And again after eating the lot: an empty shelf is the player's, never the shop's.
        var eaten = state
        repeat(10) { eaten = CareActions.feed(eaten.copy(stats = eaten.stats.copy(satiety = 0f)), "meal_bowl").state }
        assertEquals(null, eaten.inventory["meal_bowl"])
        assertTrue("nothing about being bought out stops the next purchase", CareActions.buy(eaten, "meal_bowl").accepted)
    }

    @Test
    fun `a toy is bought once, like every other thing you keep`() {
        val rich = pet().copy(coins = 500)
        val owned = CareActions.buy(rich, "toy_ball")
        assertTrue(owned.accepted)
        assertEquals(1, owned.state.inventory["toy_ball"])

        val again = CareActions.buy(owned.state, "toy_ball")
        assertFalse("a toy is never used up, so a second one is a coin sink and nothing else", again.accepted)
        assertEquals(owned.state.coins, again.state.coins)
    }

    @Test
    fun `winning a game pays more than losing`() {
        val won = CareActions.finishGame(pet(), won = true, score = 1f, gameName = "Test").state
        val lost = CareActions.finishGame(pet(), won = false, score = 0f, gameName = "Test").state
        assertTrue(won.coins > lost.coins)
        assertTrue(won.stats.happiness > lost.stats.happiness)
    }

    @Test
    fun `scolding is refused when the pet behaved`() {
        val good = pet().copy(stats = pet().stats.copy(discipline = 90f, happiness = 90f))
        assertFalse(CareActions.scold(good).accepted)
    }
}
