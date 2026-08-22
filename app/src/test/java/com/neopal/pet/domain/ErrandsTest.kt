package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The creature's small agentic loop.
 *
 * A plan is a statement of intent, never an authorisation, and most of what is worth testing here
 * is that difference: a plan made against one world must not be able to act on another, and a plan
 * that cannot proceed must end rather than spin.
 */
class ErrandsTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    private fun pet(
        skills: Set<Skill> = Skill.entries.toSet(),
        stats: Stats = Stats(satiety = 50f, happiness = 60f, energy = 80f, hygiene = 60f),
        inventory: Map<String, Int> = mapOf("meal_bowl" to 3),
        poops: Int = 2,
    ): PetState = Simulation
        .advance(Simulation.newGame("T", Species.LEAF, start), start + 120_000, config)
        .state
        .copy(
            stage = LifeStage.ADULT,
            ageSeconds = config.secondsPerPetDay / 2,
            stats = stats,
            inventory = inventory,
            poops = poops,
            autonomy = Autonomy.FULL,
            skills = skills,
            intellect = 60f,
            genome = Genome(),
        )

    private fun plan(vararg kinds: ActivityKind, at: Long) = Plan(
        goal = "Be presentable before company.",
        steps = kinds.map { PlanStep(it, "Because I said I would.") },
        madeAtSeconds = at,
    )

    // ---- tools ---------------------------------------------------------------------------

    @Test
    fun `looking never changes anything`() {
        val before = pet()
        ToolId.entries.forEach { Errands.answer(it, before, config) }
        assertEquals("a tool that reads must not write", before, pet())
    }

    @Test
    fun `the pantry tool tells the truth about an empty pantry`() {
        assertTrue(
            Errands.answer(ToolId.LOOK_IN_PANTRY, pet(inventory = emptyMap()), config)
                .contains("empty", ignoreCase = true),
        )
        assertTrue(
            Errands.answer(ToolId.LOOK_IN_PANTRY, pet(), config).contains("x3"),
        )
    }

    @Test
    fun `taking stock reports the mess it is standing in`() {
        val answer = Errands.answer(ToolId.CHECK_SELF, pet(poops = 3), config)
        assertTrue(answer.contains("3 mess"))
    }

    // ---- what a plan is allowed to be ----------------------------------------------------

    @Test
    fun `a plan of nothing but idling is not a plan`() {
        val idle = Plan("Do nothing", listOf(PlanStep(ActivityKind.IDLE, "x")), 0L)
        assertNull("three shrugs is not an afternoon", Errands.sanitise(idle, pet()))
    }

    @Test
    fun `a plan is bounded and always starts unspent`() {
        val long = Plan(
            goal = "g".repeat(500),
            steps = List(20) { PlanStep(ActivityKind.PLAY, "why") },
            madeAtSeconds = 0L,
            // A mind marking its own steps done would skip the ones it did not want checked.
            done = 15,
        )
        val clean = Errands.sanitise(long, pet())
        assertNotNull(clean)
        assertTrue(clean!!.steps.size <= Errands.maxStepsFor(pet().intellect))
        assertTrue(clean.goal.length <= Errands.MAX_GOAL_CHARS)
        assertEquals("a plan may not arrive half spent", 0, clean.done)
        assertEquals("and it is stamped against the creature it is for", pet().ageSeconds, clean.madeAtSeconds)
    }

    @Test
    fun `a blank goal is refused`() {
        assertNull(Errands.sanitise(Plan("   ", listOf(PlanStep(ActivityKind.PLAY, "x")), 0L), pet()))
    }

    // ---- following one -------------------------------------------------------------------

    @Test
    fun `the creature works through a plan in order`() {
        var s = pet().copy(plan = plan(ActivityKind.TIDY, ActivityKind.PLAY, at = config.secondsPerPetDay / 2))
        val events = mutableListOf<GameEvent>()

        s = Brain.tick(s, config, 1L, Random(1), events)
        assertEquals("the first step is the first step", ActivityKind.TIDY, s.activity?.kind)
        assertEquals("and it is marked off", 1, s.plan?.done)
    }

    @Test
    fun `a plan outranks what it would otherwise most want`() {
        // Starving, with food to hand: scoring would say eat. The plan says tidy up.
        var s = pet(stats = Stats(satiety = 12f, happiness = 60f, energy = 80f, hygiene = 60f))
            .copy(plan = plan(ActivityKind.TIDY, at = config.secondsPerPetDay / 2))
        s = Brain.tick(s, config, 1L, Random(1), mutableListOf())
        assertEquals(
            "a creature that re-scored from scratch would never finish an errand",
            ActivityKind.TIDY,
            s.activity?.kind,
        )
    }

    @Test
    fun `a step that cannot be done ends the plan instead of spinning on it`() {
        // The plan says eat; the pantry is empty and it never learned to forage.
        var s = pet(inventory = emptyMap(), skills = Skill.entries.toSet() - Skill.FORAGE)
            .copy(plan = plan(ActivityKind.EAT, at = config.secondsPerPetDay / 2))
        val events = mutableListOf<GameEvent>()
        s = Brain.tick(s, config, 1L, Random(1), events)

        assertNull("insisting on lunch at an empty pantry is a stuck loop", s.plan)
        assertTrue(events.any { it is GameEvent.PlanAbandoned })
    }

    @Test
    fun `a plan made about a creature that no longer exists is dropped`() {
        val stale = plan(ActivityKind.PLAY, at = 0L)
        var s = pet().copy(plan = stale)
        assertTrue(stale.isStale(s.ageSeconds))

        val events = mutableListOf<GameEvent>()
        s = Brain.tick(s, config, 1L, Random(1), events)
        assertNull(s.plan)
        assertTrue(events.any { it is GameEvent.PlanAbandoned })
    }

    @Test
    fun `finishing the last step clears the plan`() {
        var s = pet().copy(plan = plan(ActivityKind.TIDY, at = config.secondsPerPetDay / 2))
        s = Brain.tick(s, config, 1L, Random(1), mutableListOf())
        assertNull("a finished plan is not kept around empty", s.plan)
    }

    @Test
    fun `no plan means the creature simply decides for itself`() {
        val s = Brain.tick(pet(), config, 1L, Random(1), mutableListOf())
        assertNull(s.plan)
        assertNotNull("it still does something", s.activity)
    }

    @Test
    fun `manual mode ignores plans entirely`() {
        val s = pet().copy(
            autonomy = Autonomy.OFF,
            plan = plan(ActivityKind.TIDY, at = config.secondsPerPetDay / 2),
        )
        val after = Brain.tick(s, config, 1L, Random(1), mutableListOf())
        assertNull("a player who took the wheel keeps it", after.activity)
        assertEquals("and the plan is left untouched rather than quietly spent", s.plan, after.plan)
    }

    @Test
    fun `advancing past the end never rewinds or overruns`() {
        val one = pet().copy(plan = plan(ActivityKind.PLAY, at = 0L))
        val spent = Errands.advance(one, mutableListOf())
        assertNull(spent.plan)
        assertEquals("advancing nothing is not an error", spent, Errands.advance(spent, mutableListOf()))
    }

    @Test
    fun `an errand that visibly helped teaches the creature something`() {
        // Finished the last step, and the creature is measurably better off than when it started.
        val before = pet(stats = Stats(satiety = 30f, happiness = 30f, energy = 30f, hygiene = 30f))
        val improved = before.copy(
            stats = Stats(satiety = 90f, happiness = 90f, energy = 90f, hygiene = 90f),
            plan = Plan(
                goal = "Sort myself out.",
                steps = listOf(PlanStep(ActivityKind.EAT, "hungry")),
                madeAtSeconds = before.ageSeconds,
                careAtStart = before.stats.careScore,
            ),
        )
        val events = mutableListOf<GameEvent>()
        val after = Errands.advance(improved, events)

        assertTrue("a plan that worked has to leave something behind", after.lessons.isNotEmpty())
        assertEquals(LessonKind.EAT_SOONER, after.lessons.first().kind)
        assertTrue(events.any { it is GameEvent.LearnedFromExperience })
        assertEquals("and it counts as followed through", 1, after.plansFinished)
    }

    @Test
    fun `an errand that changed nothing teaches nothing`() {
        // Needs drift on their own. Learning from that would be learning from the passage of time.
        val flat = pet().let {
            it.copy(
                plan = Plan(
                    goal = "Potter about.",
                    steps = listOf(PlanStep(ActivityKind.PLAY, "why not")),
                    madeAtSeconds = it.ageSeconds,
                    careAtStart = it.stats.careScore,
                ),
            )
        }
        val events = mutableListOf<GameEvent>()
        val after = Errands.advance(flat, events)

        assertTrue("no measurable gain means no lesson", after.lessons.isEmpty())
        assertTrue(events.none { it is GameEvent.LearnedFromExperience })
        assertEquals("but it still followed through", 1, after.plansFinished)
    }

    @Test
    fun `one good afternoon never outweighs how a parent died`() {
        val before = pet(stats = Stats(satiety = 10f, happiness = 10f, energy = 10f, hygiene = 10f))
        val improved = before.copy(
            stats = Stats(satiety = 100f, happiness = 100f, energy = 100f, hygiene = 100f),
            plan = Plan(
                goal = "Everything at once.",
                steps = listOf(PlanStep(ActivityKind.EAT, "x")),
                madeAtSeconds = before.ageSeconds,
                careAtStart = before.stats.careScore,
            ),
        )
        val after = Errands.advance(improved, mutableListOf())
        val learned = after.lessons.single()
        assertTrue(
            "experience accumulates; it does not arrive in one afternoon",
            learned.strength <= Errands.MAX_EXPERIENCE_STRENGTH,
        )
        val fromDeath = Lineage.distilLocally(
            RunRecord(deathReason = DeathReason.STARVATION, lifespanSeconds = 3600L), 1,
        ).first()
        assertTrue("a parent's death still teaches harder", fromDeath.strength > learned.strength)
    }

    @Test
    fun `only the middle of a plan is unfinished business`() {
        // Two steps: the first must not grade the plan, the second must.
        val two = pet().let {
            it.copy(
                stats = Stats(satiety = 95f, happiness = 95f, energy = 95f, hygiene = 95f),
                plan = Plan(
                    goal = "Two things.",
                    steps = listOf(PlanStep(ActivityKind.EAT, "a"), PlanStep(ActivityKind.PLAY, "b")),
                    madeAtSeconds = it.ageSeconds,
                    careAtStart = 0f,
                ),
            )
        }
        val midway = Errands.advance(two, mutableListOf())
        assertEquals("still going", 1, midway.plan?.done)
        assertTrue("nothing learned halfway", midway.lessons.isEmpty())

        val done = Errands.advance(midway, mutableListOf())
        assertNull(done.plan)
        assertTrue("graded at the end", done.lessons.isNotEmpty())
    }

    // ---- how long a thought is -----------------------------------------------------------

    @Test
    fun `a brighter creature holds a longer thought`() {
        val newborn = Errands.maxStepsFor(0f)
        val clever = Errands.maxStepsFor(100f)
        assertEquals("three steps is where everyone starts", Errands.MAX_STEPS, newborn)
        assertEquals(Errands.MAX_STEPS_BRIGHT, clever)
        assertTrue(clever > newborn)
    }

    @Test
    fun `plan length is monotone in intellect and never leaves its band`() {
        var previous = 0
        (0..100 step 5).forEach { i ->
            val steps = Errands.maxStepsFor(i.toFloat())
            assertTrue("intellect $i went backwards", steps >= previous)
            assertTrue(steps in Errands.MAX_STEPS..Errands.MAX_STEPS_BRIGHT)
            previous = steps
        }
        // A corrupted save must not buy an unbounded plan.
        assertEquals(Errands.MAX_STEPS, Errands.maxStepsFor(-900f))
        assertEquals(Errands.MAX_STEPS_BRIGHT, Errands.maxStepsFor(9_000f))
    }

    @Test
    fun `a longer plan gets a longer clock, per step rather than per plan`() {
        // Otherwise a six-step plan on a three-step deadline is abandoned halfway, every time,
        // and it reads as the creature losing interest rather than as an impossible deadline.
        assertTrue(Errands.lifetimeFor(6) > Errands.lifetimeFor(3))
        assertEquals(Errands.SECONDS_PER_STEP * 3, Errands.lifetimeFor(3))
        assertTrue("an empty plan still gets a clock", Errands.lifetimeFor(0) > 0L)
    }

    // ---- growing one ---------------------------------------------------------------------

    /** A plan whose last step is about to land, started from [from] care. */
    private fun finishing(intellect: Float, from: Float, now: Float): PetState {
        val base = pet(stats = Stats(satiety = now, happiness = now, energy = now, hygiene = now))
        return base.copy(
            intellect = intellect,
            plan = Plan(
                goal = "Sort myself out.",
                steps = listOf(PlanStep(ActivityKind.EAT, "hungry")),
                madeAtSeconds = base.ageSeconds,
                careAtStart = from,
            ),
        )
    }

    @Test
    fun `a plan that is visibly working grows itself another step`() {
        val events = mutableListOf<GameEvent>()
        val after = Errands.advance(finishing(intellect = 90f, from = 0.2f, now = 95f), events) { _, _ ->
            PlanStep(ActivityKind.PLAY, "and now something nice")
        }
        assertEquals("it carried on rather than stopping", 2, after.plan?.steps?.size)
        assertEquals(1, after.plan?.extensions)
        assertTrue(events.any { it is GameEvent.PlanExtended })
        assertEquals("carrying on is not finishing", 0, after.plansFinished)
    }

    @Test
    fun `a plan that changed nothing stops rather than growing`() {
        val events = mutableListOf<GameEvent>()
        // Bright enough, but the care score has not moved.
        val flat = finishing(intellect = 90f, from = 0.9f, now = 90f)
        val after = Errands.advance(flat, events) { _, _ -> PlanStep(ActivityKind.PLAY, "more") }
        assertNull("stubbornness is not intelligence", after.plan)
        assertEquals(1, after.plansFinished)
    }

    @Test
    fun `a newborn follows a plan but never grows one`() {
        val after = Errands.advance(finishing(intellect = 10f, from = 0.2f, now = 95f), mutableListOf()) { _, _ ->
            PlanStep(ActivityKind.PLAY, "more")
        }
        assertNull("intellect has to buy it", after.plan)
    }

    @Test
    fun `growing stops at the cap so the plan is eventually graded`() {
        val base = finishing(intellect = 100f, from = 0.1f, now = 100f)
        val maxed = base.copy(plan = base.plan!!.copy(extensions = Errands.MAX_EXTENSIONS))
        val after = Errands.advance(maxed, mutableListOf()) { _, _ -> PlanStep(ActivityKind.PLAY, "more") }
        assertNull("a plan that never ends is a plan that never teaches", after.plan)
        assertTrue("and ending it is where the lesson comes from", after.lessons.isNotEmpty())
    }

    @Test
    fun `a plan with nowhere legal to go stops instead of growing an impossible step`() {
        val after = Errands.advance(finishing(intellect = 90f, from = 0.2f, now = 95f), mutableListOf()) { _, _ ->
            null
        }
        assertNull(after.plan)
    }

    @Test
    fun `a plan that arrived with no before is followed but never graded`() {
        // The default `careAtStart`. Treating it as zero would make every such plan a triumph:
        // a lesson every time, and growth right up to the cap.
        val ungraded = pet(stats = Stats(satiety = 95f, happiness = 95f, energy = 95f, hygiene = 95f))
            .let {
                it.copy(
                    intellect = 100f,
                    plan = Plan("Whatever this was.", listOf(PlanStep(ActivityKind.EAT, "x")), it.ageSeconds),
                )
            }
        val events = mutableListOf<GameEvent>()
        val after = Errands.advance(ungraded, events)
        assertNull("it ends", after.plan)
        assertTrue("and teaches nothing it cannot prove", after.lessons.isEmpty())
        assertTrue(events.none { it is GameEvent.LearnedFromExperience })
    }

    @Test
    fun `a stale plan is never grown, however well it was going`() {
        val base = finishing(intellect = 100f, from = 0.1f, now = 100f)
        val old = base.copy(plan = base.plan!!.copy(madeAtSeconds = 0L))
        assertTrue(old.plan!!.isStale(old.ageSeconds))
        val after = Errands.advance(old, mutableListOf()) { _, _ -> PlanStep(ActivityKind.PLAY, "more") }
        assertNull("a plan out of time does not get more time by succeeding", after.plan)
    }

    @Test
    fun `a sleeping creature does not sleepwalk through its plan`() {
        val s = pet().copy(isSleeping = true, plan = plan(ActivityKind.TIDY, at = config.secondsPerPetDay / 2))
        val after = Brain.tick(s, config, 1L, Random(1), mutableListOf())
        assertEquals("the plan waits", s.plan?.done, after.plan?.done)
        assertFalse(after.activity?.kind == ActivityKind.TIDY)
    }
}
