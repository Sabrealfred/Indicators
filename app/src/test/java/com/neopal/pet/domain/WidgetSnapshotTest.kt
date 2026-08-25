package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The widget is drawn once and then left on someone's home screen for hours, where nobody is
 * watching it be wrong. These are the questions that would otherwise only be answered by looking
 * at a phone: does every state of the game get a face, does the widget ever say something the
 * game would not say, and can it be talked into a blank.
 */
class WidgetSnapshotTest {

    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------ the four faces

    @Test
    fun `no save at all is its own face and never borrows a number`() {
        val snap = WidgetSnapshot.of(null, nowMillis = now)
        assertEquals(WidgetFace.EMPTY, snap.face)
        assertNull(snap.creature)
        assertNull(snap.need)
        assertNull(snap.offer)
        assertTrue(snap.headline.isNotBlank())
        assertTrue(snap.detail.isNotBlank())
        // There is still a room to draw, so an empty widget is a picture rather than a hole.
        assertEquals("room_default", snap.scene.roomTheme)
    }

    @Test
    fun `an unhatched egg says so and is offered nothing`() {
        val egg = Simulation.newGame("Pip", Species.LEAF, now)
        val snap = WidgetSnapshot.of(egg, nowMillis = now)
        assertEquals(WidgetFace.EGG, snap.face)
        assertNotNull(snap.creature)
        assertEquals(LifeStage.EGG, snap.creature!!.stage)
        assertNull(snap.need)
        assertNull(snap.offer)
        assertTrue(snap.headline.contains("Pip"))
        assertEquals("Warming up", snap.detail)
    }

    @Test
    fun `an egg about to go says so`() {
        val egg = Simulation.newGame("Pip", Species.LEAF, now)
            .let { it.copy(ageSeconds = (Simulation.stageDuration(LifeStage.EGG, GameConfig.Default) * 0.8f).toLong()) }
        assertEquals("Hatching soon", WidgetSnapshot.of(egg, nowMillis = now).detail)
    }

    @Test
    fun `a dead pet names what took it and is never asked to do anything`() {
        val dead = alive().copy(
            isDead = true,
            deathReason = DeathReason.OLD_AGE,
            stats = Stats(satiety = 0f, happiness = 0f, hygiene = 0f, energy = 0f, health = 0f),
            poops = 5,
        )
        val snap = WidgetSnapshot.of(dead, nowMillis = now)
        assertEquals(WidgetFace.GONE, snap.face)
        assertNull(snap.need)
        assertNull(snap.offer)
        assertTrue(snap.detail.contains("old age"))
        // A dead pet is still drawn: the empty face belongs to a phone with no save, not to a
        // pet that lived and died.
        assertNotNull(snap.creature)
        assertTrue(snap.creature!!.isDead)
    }

    @Test
    fun `a living pet leads with its name and its stage`() {
        val snap = WidgetSnapshot.of(alive(), nowMillis = now)
        assertEquals(WidgetFace.ALIVE, snap.face)
        assertEquals("Pip · Teen", snap.headline)
    }

    @Test
    fun `a sleeping pet is not nagged`() {
        val sleepy = alive().copy(
            isSleeping = true,
            lightsOff = true,
            stats = Stats(energy = 10f, satiety = 80f, happiness = 80f, hygiene = 90f, health = 90f),
        )
        val snap = WidgetSnapshot.of(sleepy, nowMillis = now)
        assertEquals("Asleep", snap.detail)
        // Nothing is offered over a sleeping pet — the same three states the app's own
        // quick-care button stays quiet for. A button under the word "Asleep" asking to be
        // pressed is the widget arguing with itself.
        assertNull(snap.offer)
        assertNull(WidgetSnapshot.of(sleepy.copy(isSick = true, poops = 4), nowMillis = now).offer)
    }

    // ------------------------------------------------------------------ agreement with the game

    @Test
    fun `the urgent need is always the one the game itself would name`() {
        var seenNeeds = 0
        sweep(350) { saved, at ->
            val snap = WidgetSnapshot.of(saved, nowMillis = at)
            val projected = Simulation.advance(saved, at, GameConfig.Default).state
            val gameSays = CareActions.topNeed(projected)
            if (snap.face == WidgetFace.ALIVE) {
                assertEquals("need disagrees with topNeed", gameSays, snap.need)
                if (snap.need != null) seenNeeds++
            } else {
                assertNull(snap.need)
            }
        }
        assertTrue("the sweep never produced a needy pet", seenNeeds > 30)
    }

    @Test
    fun `every need the game can name has a face on the widget`() {
        // One state per branch of CareActions.topNeed, so a sixth need added there fails here
        // rather than showing up on a home screen as a chip that never appears.
        //
        // The assertion used to compare `snap.need?.label` against the English `topNeed`
        // returned, which is why the enum here and the enum in the game were allowed to be two
        // different enums with two different vocabularies. Both are `PetNeed` now, so the test
        // compares identities and the compiler carries the rest.
        fun needOf(state: PetState) = WidgetSnapshot.of(state, nowMillis = now).need
        val full = Stats(satiety = 100f, happiness = 100f, energy = 100f, hygiene = 100f, health = 100f)
        assertEquals(PetNeed.SICK, needOf(alive().copy(stats = full, isSick = true)))
        assertEquals(PetNeed.HUNGRY, needOf(alive().copy(stats = full.copy(satiety = 0f))))
        assertEquals(PetNeed.BORED, needOf(alive().copy(stats = full.copy(happiness = 0f))))
        assertEquals(PetNeed.SLEEPY, needOf(alive().copy(stats = full.copy(energy = 0f))))
        assertEquals(PetNeed.DIRTY, needOf(alive().copy(stats = full.copy(hygiene = 0f))))
        // And a settled pet is left alone.
        assertNull(needOf(alive().copy(stats = full)))
    }

    @Test
    fun `an offer is never something the game would refuse`() {
        var offers = 0
        sweep(350) { saved, at ->
            val snap = WidgetSnapshot.of(saved, nowMillis = at)
            val offer = snap.offer ?: return@sweep
            offers++
            assertTrue(offer.label.isNotBlank())
            assertEquals(WidgetFace.ALIVE, snap.face)
            val projected = Simulation.advance(saved, at, GameConfig.Default).state
            val result = WidgetSnapshot.apply(saved, GameConfig.Default, at, offer.action)
            assertNotNull("the game refused an offer the widget made", result)
            if (offer.action == WidgetAction.FEED || offer.action == WidgetAction.MEDICATE) {
                val id = offer.itemId
                assertNotNull(id)
                assertTrue("offered an item that is not in the tin", (projected.inventory[id] ?: 0) > 0)
            }
        }
        assertTrue("the sweep never produced an offer", offers > 30)
    }

    @Test
    fun `a tap is re-derived from the state it lands on, not the one it was drawn from`() {
        val hungry = alive().copy(stats = alive().stats.copy(satiety = 5f))
        val offer = WidgetSnapshot.of(hungry, nowMillis = now).offer
        assertNotNull(offer)
        assertEquals(WidgetAction.FEED, offer!!.action)

        // Somebody fed it from the app between the widget being drawn and the finger landing.
        val fed = hungry.copy(stats = hungry.stats.copy(satiety = 99f))
        assertNull(WidgetSnapshot.apply(fed, GameConfig.Default, now, offer.action))
        // And on the state it was drawn from, it still works.
        assertNotNull(WidgetSnapshot.apply(hungry, GameConfig.Default, now, offer.action))
    }

    // ------------------------------------------------------------------ what it spends

    @Test
    fun `the widget serves exactly what the app's own quick-care button would serve`() {
        // HomeScreen.quickCareFor picks foods.filter { owned }.maxByOrNull { it.satiety }, and
        // the strongest medicine that is not the soap. Two answers to one question is the thing
        // this reproduces the rule to avoid, so these are checked against the rule itself.
        val stocked = alive().copy(
            stats = alive().stats.copy(satiety = 2f),
            inventory = mapOf("snack_berry" to 2, "meal_bowl" to 2, "meal_stew" to 1, "meal_sushi" to 1),
        )
        val app = ItemCatalog.foods
            .filter { (stocked.inventory[it.id] ?: 0) > 0 }
            .maxByOrNull { it.satiety }!!
            .id
        assertEquals("meal_stew", app)
        assertEquals(app, WidgetSnapshot.of(stocked, nowMillis = now).offer?.itemId)
    }

    @Test
    fun `a mess on the floor is scooped, and a merely grubby pet is scrubbed`() {
        val messy = alive().copy(
            stats = alive().stats.copy(hygiene = 10f),
            poops = 3,
            inventory = mapOf("soap" to 1),
        )
        assertEquals(WidgetAction.CLEAN, WidgetSnapshot.of(messy, nowMillis = now).offer?.action)

        val grubby = messy.copy(poops = 0)
        val offer = WidgetSnapshot.of(grubby, nowMillis = now).offer
        assertEquals(WidgetAction.BATHE, offer?.action)
        assertEquals("soap", offer?.itemId)

        // And with no soap in the cupboard it falls back to tidying, exactly as the app does.
        val bare = grubby.copy(inventory = emptyMap())
        assertEquals(WidgetAction.CLEAN, WidgetSnapshot.of(bare, nowMillis = now).offer?.action)
    }

    @Test
    fun `nothing in the tin is said out loud rather than shown as a missing button`() {
        val starving = alive().copy(stats = alive().stats.copy(satiety = 2f), inventory = emptyMap())
        val snap = WidgetSnapshot.of(starving, nowMillis = now)
        assertEquals(PetNeed.HUNGRY, snap.need)
        assertNull(snap.offer)
        assertTrue(snap.detail.contains("nothing left"))
    }

    @Test
    fun `soap is never handed to a sick pet as medicine`() {
        val ill = alive().copy(
            isSick = true,
            stats = alive().stats.copy(health = 70f),
            inventory = mapOf("soap" to 3),
        )
        assertNull(WidgetSnapshot.bestMedicineFor(ill))
        val snap = WidgetSnapshot.of(ill, nowMillis = now)
        assertEquals(PetNeed.SICK, snap.need)
        assertNull(snap.offer)
    }

    @Test
    fun `the strongest dose in the cupboard is the one offered`() {
        val ill = alive().copy(
            isSick = true,
            stats = alive().stats.copy(health = 40f),
            inventory = mapOf("medicine" to 1, "medicine_super" to 1),
        )
        // The app's rule, kept on purpose: the surest cure, not the cheapest one that might do.
        assertEquals("medicine_super", WidgetSnapshot.bestMedicineFor(ill))
        assertEquals("medicine", WidgetSnapshot.bestMedicineFor(ill.copy(inventory = mapOf("medicine" to 1))))
    }

    // ------------------------------------------------------------------ never blank, never sore

    @Test
    fun `nothing the widget shows is ever blank`() {
        sweep(350) { saved, at ->
            val snap = WidgetSnapshot.of(saved, nowMillis = at)
            assertTrue(snap.name.isNotBlank())
            assertTrue(snap.headline.isNotBlank())
            assertTrue(snap.detail.isNotBlank())
            assertTrue(snap.key.isNotBlank())
            assertTrue(snap.name.length <= WidgetSnapshot.MAX_NAME_LENGTH)
            assertTrue((snap.creature != null) == (snap.face != WidgetFace.EMPTY))
        }
    }

    @Test
    fun `a save with a nonsense name still has a line to show`() {
        assertEquals("Your pet", WidgetSnapshot.of(alive().copy(name = "   "), nowMillis = now).name)
        val long = WidgetSnapshot.of(alive().copy(name = "Bartholomew the Magnificent"), nowMillis = now)
        assertTrue(long.name.length <= WidgetSnapshot.MAX_NAME_LENGTH)
        assertTrue(long.headline.isNotBlank())
    }

    @Test
    fun `a save full of nonsense numbers draws something rather than throwing`() {
        val broken = alive().copy(
            stats = Stats(
                satiety = Float.NaN,
                happiness = Float.NEGATIVE_INFINITY,
                energy = Float.POSITIVE_INFINITY,
                hygiene = -900f,
                health = 5_000f,
            ),
            weightGrams = Float.NaN,
            poops = 99,
            ageSeconds = Long.MAX_VALUE / 4,
        )
        val snap = WidgetSnapshot.of(broken, nowMillis = now)
        assertTrue(snap.headline.isNotBlank())
        assertTrue(snap.detail.isNotBlank())
        assertTrue(snap.scene.poops in 0..6)
        assertNotNull(snap.creature)
        // The body the renderer will be handed. A not-a-number here is not a wrong shape, it is
        // an invisible one: every coordinate derived from it comes out NaN and every shape draws
        // nothing at all, on the one screen where nobody would ever be told.
        val body = snap.creature!!.morphology
        listOf(
            body.muzzleLength, body.earLength, body.earDroop, body.legLength,
            body.quadruped, body.tailLength, body.bodyWidth, body.shagginess, body.hueShift,
        ).forEach { assertTrue("the drawn body carries $it", it.isFinite()) }
        assertTrue(snap.creature!!.weightGrams.isFinite())
        assertTrue(snap.creature!!.stageProgress.isFinite())
        assertTrue(snap.creature!!.bond.isFinite())
    }

    @Test
    fun `a genome full of holes still expresses a body that can be drawn`() {
        val broken = alive().copy(
            genome = Genome(muzzle = Float.NaN, stance = Float.POSITIVE_INFINITY, build = Float.NaN),
        )
        val body = WidgetSnapshot.of(broken, nowMillis = now).creature!!.morphology
        assertTrue(body.muzzleLength.isFinite())
        assertTrue(body.quadruped.isFinite())
        assertTrue(body.bodyWidth.isFinite())
    }

    @Test
    fun `the same save drawn twice is the same widget`() {
        val state = alive()
        assertEquals(
            WidgetSnapshot.of(state, nowMillis = now),
            WidgetSnapshot.of(state, nowMillis = now),
        )
    }

    // ------------------------------------------------------------------ honesty about time

    @Test
    fun `the refresh it asks for is one that would actually change the picture`() {
        var landed = 0
        sweep(50) { saved, at ->
            val snap = WidgetSnapshot.of(saved, nowMillis = at)
            val next = WidgetSnapshot.nextLook(saved, nowMillis = at)
            assertTrue(next >= WidgetSnapshot.MIN_LOOK_SECONDS)
            assertTrue(next <= WidgetSnapshot.MAX_LOOK_SECONDS)
            if (next < WidgetSnapshot.MAX_LOOK_SECONDS) {
                val later = WidgetSnapshot.of(saved, nowMillis = at + next * 1000L)
                assertFalse(
                    "asked to be woken at a moment nothing had changed",
                    later.key == snap.key,
                )
                landed++
            }
        }
        assertTrue("nothing in the sweep ever asked for an early look", landed > 5)
    }

    @Test
    fun `a phone with no pet on it is never woken to redraw nothing`() {
        assertEquals(WidgetSnapshot.MAX_LOOK_SECONDS, WidgetSnapshot.nextLook(null, nowMillis = now))
    }

    @Test
    fun `a dead pet asks for the longest sleep there is`() {
        val dead = alive().copy(isDead = true, deathReason = DeathReason.NEGLECT)
        assertEquals(WidgetSnapshot.MAX_LOOK_SECONDS, WidgetSnapshot.nextLook(dead, nowMillis = now))
    }

    @Test
    fun `a pet about to get hungry is looked at sooner than a comfortable one`() {
        val base = alive().copy(inventory = mapOf("meal_bowl" to 5))
        val soon = WidgetSnapshot.nextLook(base.copy(stats = base.stats.copy(satiety = 36f)), nowMillis = now)
        val settled = WidgetSnapshot.nextLook(base.copy(stats = base.stats.copy(satiety = 100f)), nowMillis = now)
        assertTrue("hungry-soon $soon vs settled $settled", soon < settled)
    }

    @Test
    fun `a save older than the catch-up cap is flagged as such`() {
        val state = alive()
        val threeDays = now + 3L * 24L * 3600L * 1000L
        val snap = WidgetSnapshot.of(state, nowMillis = threeDays)
        assertTrue(snap.cappedCatchUp)
        assertTrue(snap.saveAgeSeconds > GameConfig.Default.maxOfflineSeconds)
        // Fresh reads are not flagged.
        assertFalse(WidgetSnapshot.of(state, nowMillis = now + 60_000L).cappedCatchUp)
    }

    @Test
    fun `a widget drawn now is drawn from now, not from the last thing written down`() {
        val hungryLater = alive().copy(
            stats = alive().stats.copy(satiety = 40f, happiness = 100f, hygiene = 100f, energy = 100f),
        )
        val atOnce = WidgetSnapshot.of(hungryLater, nowMillis = now)
        assertNull("nothing is urgent yet", atOnce.need)

        // Three hours of drain, written down nowhere, and the widget still knows about it.
        val at = now + 3L * 3600L * 1000L
        val later = WidgetSnapshot.of(hungryLater, nowMillis = at)
        val projected = Simulation.advance(hungryLater, at, GameConfig.Default).state
        assertTrue("the save itself never moved", hungryLater.stats.satiety - projected.stats.satiety > 20f)
        assertNotNull("three hours of neglect and the widget says nothing", later.need)
        assertEquals(CareActions.topNeed(projected), later.need)
    }

    // ------------------------------------------------------------------ helpers

    private fun alive(): PetState = Simulation.newGame("Pip", Species.AQUA, now).copy(
        stage = LifeStage.TEEN,
        stageStartedSeconds = 0L,
        ageSeconds = 6_000L,
        stats = Stats(satiety = 70f, happiness = 70f, energy = 80f, hygiene = 90f, health = 100f),
        inventory = mapOf("meal_bowl" to 2, "snack_berry" to 3, "medicine" to 1),
        lastTickMillis = now,
    )

    /** Random saves across every stage, mood and larder, each read at a random distance away. */
    private fun sweep(count: Int, block: (PetState, Long) -> Unit) {
        val random = Random(20260822)
        repeat(count) {
            val stage = LifeStage.entries[random.nextInt(LifeStage.entries.size)]
            val species = Species.entries[random.nextInt(Species.entries.size)]
            fun stat() = random.nextFloat() * 100f
            val inventory = buildMap {
                ItemCatalog.all.forEach { item ->
                    if (random.nextFloat() < 0.35f) put(item.id, 1 + random.nextInt(3))
                }
            }
            val state = Simulation.newGame("Pip", species, now, seed = random.nextLong()).copy(
                stage = stage,
                branch = EvolutionBranch.entries[random.nextInt(EvolutionBranch.entries.size)],
                ageSeconds = random.nextLong(0, 200_000L),
                stageStartedSeconds = 0L,
                stats = Stats(
                    satiety = stat(), happiness = stat(), energy = stat(),
                    hygiene = stat(), health = stat(),
                    discipline = stat(), bond = stat(),
                ),
                weightGrams = 6f + random.nextFloat() * 100f,
                poops = random.nextInt(0, 7),
                isSick = random.nextFloat() < 0.2f,
                isSleeping = random.nextFloat() < 0.2f,
                lightsOff = random.nextFloat() < 0.3f,
                isDead = random.nextFloat() < 0.1f,
                deathReason = DeathReason.entries[random.nextInt(DeathReason.entries.size)],
                inventory = inventory,
                roomTheme = ItemCatalog.rooms[random.nextInt(ItemCatalog.rooms.size)].id,
                equippedHat = if (random.nextFloat() < 0.3f) "hat_cap" else null,
                lastTickMillis = now,
            )
            block(state, now + random.nextLong(0, 40L * 3600L * 1000L))
        }
    }
}
