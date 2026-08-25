package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a toy is for.
 *
 * A toy was the one thing in the catalog with no verb. It could be bought, it was kept for ever,
 * its stat block was written out in full in the shop's effect table — and tapping "Use it" sent
 * it to the router, which had no branch for [ItemKind.TOY] and answered "Pip does not know what
 * to do with the Bounce Ball." Thirty coins for a tile that refuses.
 *
 * The catalog said what it was for and the code never read it: the three toys carry happiness,
 * a negative energy and bond, which is a *play* action written down and left unwired. The
 * descriptions said something else again — "Unlocks Ball Rally", for a game that does not exist,
 * next to two toys claiming to unlock games every save can already play from the start.
 */
class ToyPlayTest {

    private fun pet(): PetState = PetState(
        name = "Pip",
        stage = LifeStage.CHILD,
        lastTickMillis = 1L,
        stats = Stats(satiety = 60f, happiness = 50f, energy = 80f, hygiene = 70f, health = 100f),
    )

    private fun withBall(): PetState = pet().copy(inventory = mapOf("toy_ball" to 1))

    @Test
    fun `an owned toy is something to play with`() {
        val before = withBall()
        val result = CareActions.use(before, "toy_ball")

        assertTrue("a toy the player owns must not refuse: ${result.toast}", result.accepted)
        assertEquals(PetAnimation.PLAY, result.animation)
        assertTrue("playing is what a toy is for", result.state.stats.happiness > before.stats.happiness)
        assertTrue("and it costs something", result.state.stats.energy < before.stats.energy)
        assertTrue("it is played with together", result.state.stats.bond > before.stats.bond)
    }

    @Test
    fun `a toy is never used up`() {
        var state = withBall()
        repeat(3) { state = CareActions.use(state, "toy_ball").state }
        assertEquals("a ball is furniture, not a meal", 1, state.inventory["toy_ball"])
    }

    @Test
    fun `a toy you do not own is not yours to play with`() {
        val result = CareActions.use(pet(), "toy_ball")
        assertFalse(result.accepted)
        assertEquals(PetAnimation.REFUSE, result.animation)
    }

    @Test
    fun `a toy answers to the same gate as the games do`() {
        // One rule for play, not two. Whatever stops the creature starting a minigame stops it
        // picking up a ball, with the same sentence, so the player never learns two rules.
        listOf(
            withBall().copy(stats = withBall().stats.copy(energy = 4f)),
            withBall().copy(isSick = true),
            withBall().copy(isSleeping = true),
            withBall().copy(stage = LifeStage.EGG),
        ).forEach { state ->
            val blocker = CareActions.canPlay(state)
            val result = CareActions.use(state, "toy_ball")
            assertFalse("a creature that cannot play must not play with a toy either", result.accepted)
            assertEquals("and it must say the same thing the games screen says", blocker, result.toast)
        }
    }

    @Test
    fun `a toy is still not food`() {
        // The route that produced "Bounce Ball is not food." A toy reaching [CareActions.feed] is
        // a caller that has not asked what the item is for; feed goes on refusing it.
        assertFalse(CareActions.feed(withBall(), "toy_ball").accepted)
    }

    @Test
    fun `no toy claims to unlock anything`() {
        // Nothing anywhere in the game gates a minigame on an item — the games screen asks
        // [CareActions.canPlay] and nothing else, which is the right question. So a description
        // promising an unlock is not a missing feature, it is a lie on the price tag.
        ItemCatalog.ofKind(ItemKind.TOY).forEach { toy ->
            assertFalse(
                "${toy.name} promises an unlock the game has no mechanism for: ${toy.description}",
                toy.description.contains("Unlock", ignoreCase = true),
            )
        }
    }
}
