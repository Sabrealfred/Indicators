package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Choosing *which* food, rather than only choosing to eat.
 *
 * This is the acting-tool step, and the reason it is safe is that it adds no new trust: a mind
 * still picks an index out of a list the rules have already cleared, and the target it names is
 * re-checked at the moment it is acted on exactly as the verb always was. What it adds is that
 * the creature — with or without a model — now has a taste of its own.
 */
class FoodChoiceTest {

    private val config = GameConfig.Default

    private fun pet(
        inventory: Map<String, Int>,
        genome: Genome = Genome(),
        branch: EvolutionBranch = EvolutionBranch.BALANCED,
        satiety: Float = 30f,
    ): PetState = Simulation
        .advance(Simulation.newGame("T", Species.LEAF, 1_000_000L), 1_120_000L, config)
        .state
        .copy(
            stage = LifeStage.ADULT,
            ageSeconds = 10_800L,
            autonomy = Autonomy.FULL,
            skills = Skill.entries.toSet(),
            intellect = 60f,
            genome = genome,
            branch = branch,
            inventory = inventory,
            stats = Stats(satiety = satiety, happiness = 60f, energy = 80f, hygiene = 70f),
        )

    private fun eats(state: PetState) =
        Brain.considerations(state, config).filter { it.kind == ActivityKind.EAT }

    private val fullPantry = mapOf(
        "meal_bowl" to 1, "meal_stew" to 1, "meal_salad" to 1,
        "snack_cake" to 1, "snack_berry" to 1,
    )

    // ---- what is offered -------------------------------------------------------------------

    @Test
    fun `a stocked pantry is a menu rather than a single answer`() {
        val choices = eats(pet(fullPantry))
        assertTrue("more than one thing to eat means more than one option", choices.size > 1)
        assertEquals(
            "and each one has to say which food, or the choice is not a choice",
            choices.size,
            choices.mapNotNull { it.target }.toSet().size,
        )
        choices.forEach { assertNotNull(it.target) }
    }

    @Test
    fun `the menu is bounded, because a list of everything is not a decision`() {
        val hoard = ItemCatalog.foods.associate { it.id to 3 }
        assertTrue(eats(pet(hoard)).size <= 3)
    }

    @Test
    fun `one thing in the tin is still one option`() {
        val only = eats(pet(mapOf("meal_bowl" to 1)))
        assertEquals(1, only.size)
        assertEquals("meal_bowl", only.single().target)
    }

    @Test
    fun `an empty tin still sends it foraging rather than offering nothing`() {
        val hungry = eats(pet(emptyMap()))
        assertEquals(1, hungry.size)
        assertTrue("foraging is the pantry's understudy", hungry.single().target != null)
    }

    // ---- taste ------------------------------------------------------------------------------

    @Test
    fun `a peckish gourmand reaches past the sensible option`() {
        // Only mildly hungry, which is where a preference has room to show at all.
        val greedy = pet(
            fullPantry,
            genome = Genome(appetite = 0.98f),
            branch = EvolutionBranch.GOURMAND,
            satiety = 70f,
        )
        val plain = pet(fullPantry, genome = Genome(appetite = 0.02f), satiety = 70f)

        val greedyTop = eats(greedy).first().target
        val plainTop = eats(plain).first().target
        assertTrue(
            "a greedy gourmand and an abstemious creature should not want the same thing " +
                "($greedyTop vs $plainTop)",
            greedyTop != plainTop,
        )
        val chosen = ItemCatalog.all.first { it.id == greedyTop }
        assertTrue("and what it reached for should be the nice one", chosen.happiness >= 16f)
    }

    @Test
    fun `real hunger still beats a sweet tooth`() {
        // The counterpart rule, and the more important one: a creature two thirds empty eats what
        // fills it. A gourmand starving in front of a stew and a berry takes the stew.
        val starving = pet(
            fullPantry,
            genome = Genome(appetite = 1.0f),
            branch = EvolutionBranch.GOURMAND,
            satiety = 8f,
        )
        val top = ItemCatalog.all.first { it.id == eats(starving).first().target }
        assertTrue("a 92-point hole is not the moment for cake: took ${top.name}", top.satiety >= 34f)
    }

    @Test
    fun `an abstemious creature eats what actually fills it`() {
        val sensible = pet(fullPantry, genome = Genome(appetite = 0.0f), satiety = 30f)
        val top = ItemCatalog.all.first { it.id == eats(sensible).first().target }
        assertTrue("a 70-point hole wants a real meal, not a berry", top.satiety >= 26f)
    }

    @Test
    fun `two lines can have different favourites from the same tin`() {
        // Not asserting which — only that the quirk is doing something, so "he always goes for
        // the cake" is a thing one creature can be and another can fail to be.
        val favourites = (0..12)
            .map { pet(fullPantry, genome = Genome(hue = it / 12f, appetite = 0.55f), satiety = 72f) }
            .map { eats(it).first().target }
            .toSet()
        assertTrue("every creature having identical taste is the thing this replaced", favourites.size > 1)
    }

    @Test
    fun `a favourite does not change between one glance and the next`() {
        // options() is scored more than once per decision and again by the considerations screen.
        val creature = pet(fullPantry)
        val reads = (1..20).map { eats(creature).map { c -> c.target } }.toSet()
        assertEquals("a taste that flickers is not a taste", 1, reads.size)
    }

    // ---- acting on one ----------------------------------------------------------------------

    @Test
    fun `a named food is the food that gets eaten`() {
        val creature = pet(fullPantry)
        val after = Brain.adopt(
            state = creature,
            kind = ActivityKind.EAT,
            reason = "I wanted the cake.",
            config = config,
            random = Random(1),
            events = mutableListOf(),
            target = "snack_cake",
        )
        assertNotNull(after)
        assertEquals("snack_cake", after!!.activity?.targetId)
        assertEquals("and it came out of the tin", null, after.inventory["snack_cake"]?.takeIf { it > 0 })
    }

    @Test
    fun `a food that has gone is refused rather than quietly swapped`() {
        // The answer describes a pantry that has had seconds to change. Substituting a different
        // meal would be the rules being applied to a decision nobody made.
        val creature = pet(mapOf("meal_bowl" to 1))
        assertNull(
            Brain.adopt(
                state = creature,
                kind = ActivityKind.EAT,
                reason = "The cake, please.",
                config = config,
                random = Random(1),
                events = mutableListOf(),
                target = "snack_cake",
            ),
        )
    }

    @Test
    fun `naming nothing still works, and takes the creature's own first choice`() {
        val creature = pet(fullPantry)
        val after = Brain.adopt(creature, ActivityKind.EAT, "Hungry.", config, Random(1), mutableListOf())
        assertNotNull(after)
        assertEquals(eats(creature).first().target, after!!.activity?.targetId)
    }

    @Test
    fun `the creature still feeds itself unaided`() {
        // The whole menu is worth nothing if the ordinary path broke on the way in.
        var s = pet(fullPantry, satiety = 14f)
        s = Brain.tick(s, config, 1L, Random(3), mutableListOf())
        assertEquals(ActivityKind.EAT, s.activity?.kind)
        assertNotNull("it picked something specific", s.activity?.targetId)
    }

    @Test
    fun `the runner-up in the log is a different verb, not the same meal twice`() {
        val events = mutableListOf<GameEvent>()
        val s = Brain.tick(pet(fullPantry, satiety = 12f), config, 1L, Random(3), events)
        val decided = events.filterIsInstance<GameEvent.Decided>().firstOrNull()
        assertNotNull(decided)
        val runnerUp = decided!!.decision.runnerUp
        if (runnerUp != null) {
            assertTrue(
                "\"I nearly ate the berry instead\" is the same road, not a road not taken",
                runnerUp != s.activity?.kind,
            )
        }
    }
}
