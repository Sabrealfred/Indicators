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
