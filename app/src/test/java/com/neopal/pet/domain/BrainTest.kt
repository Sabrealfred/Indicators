package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The brain is where an autonomous pet stops being a feature and starts being a liability: the
 * rules worth guarding are the ones whose failure the player would read as the game cheating —
 * food appearing from an empty tin, a skill it never learned being used anyway, a creature that
 * twitches between four things a second, or a run that plays out differently the second time.
 */
class BrainTest {

    private val config = GameConfig.Default

    /** Noon, so [Simulation.isNight] is false unless a test asks for the dark. */
    private val noon = 10_800L

    /** Just past one in the morning. */
    private val smallHours = 1_000L

    private fun pet(
        autonomy: Autonomy = Autonomy.FULL,
        skills: Set<Skill> = emptySet(),
        stats: Stats = Stats(),
        inventory: Map<String, Int> = mapOf("meal_bowl" to 2),
        poops: Int = 0,
        ageSeconds: Long = noon,
        stage: LifeStage = LifeStage.ADULT,
    ) = PetState(
        name = "Test",
        species = Species.LEAF,
        stage = stage,
        stats = stats,
        inventory = inventory,
        poops = poops,
        ageSeconds = ageSeconds,
        autonomy = autonomy,
        skills = skills,
        // The neutral genome, so a score in a test is the situation talking and not the animal.
        genome = Genome(),
    )

    /** Runs the brain the way the step loop does: age first, then one tick. */
    private fun drive(
        start: PetState,
        ticks: Int,
        dt: Long = 1L,
        seed: Long = 7L,
        events: MutableList<GameEvent> = mutableListOf(),
        between: (PetState) -> PetState = { it },
    ): PetState {
        val random = Random(seed)
        var s = start
        repeat(ticks) {
            s = between(s.copy(ageSeconds = s.ageSeconds + dt))
            s = Brain.tick(s, config, dt, random, events)
        }
        return s
    }

    private fun decided(events: List<GameEvent>): List<Decision> =
        events.filterIsInstance<GameEvent.Decided>().map { it.decision }

    @Test
    fun `manual autonomy is a complete no-op`() {
        val starving = pet(
            autonomy = Autonomy.OFF,
            skills = Skill.entries.toSet(),
            stats = Stats(satiety = 8f),
        )
        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(starving, config, 1L, Random(1), events)
        assertEquals("nothing may change while the player is making the calls", starving, after)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a baby has not grown a brain yet`() {
        val baby = pet(
            stage = LifeStage.BABY,
            skills = Skill.entries.toSet(),
            stats = Stats(satiety = 8f),
        )
        val events = mutableListOf<GameEvent>()
        assertEquals(baby, Brain.tick(baby, config, 1L, Random(1), events))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a dead pet stops deciding`() {
        val gone = pet(skills = Skill.entries.toSet()).copy(isDead = true, deathReason = DeathReason.OLD_AGE)
        val events = mutableListOf<GameEvent>()
        assertEquals(gone, Brain.tick(gone, config, 1L, Random(1), events))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a hungry pet that never learned to feed itself stays hungry`() {
        // Energy is low enough that the discretionary options are off the table too, so the only
        // thing standing between this pet and the bowl is the missing skill.
        val hungry = pet(
            skills = emptySet(),
            stats = Stats(satiety = 15f, energy = 12f, hygiene = 95f),
        )
        val events = mutableListOf<GameEvent>()
        val after = drive(hungry, ticks = 20, events = events)

        assertEquals("the pantry must be untouched", 2, after.inventory["meal_bowl"])
        assertEquals("hunger is not satisfied by being unable to act on it", 15f, after.stats.satiety, 0.001f)
        assertTrue("it must not have eaten", decided(events).none { it.kind == ActivityKind.EAT })
        assertEquals("it should fall through to something it can do", ActivityKind.IDLE, after.activity?.kind)
    }

    @Test
    fun `an option it cannot reach is still shown wanting it`() {
        val hungry = pet(skills = emptySet(), stats = Stats(satiety = 15f, energy = 12f, hygiene = 95f))
        val top = Brain.considerations(hungry, config).first()
        assertEquals("hunger is the strongest thing it feels", ActivityKind.EAT, top.kind)
        assertFalse(top.available)
        assertEquals("not learned yet", top.blockedBy)
    }

    @Test
    fun `feeding itself lands the pet exactly where a hand-fed meal would`() {
        val hungry = pet(
            skills = setOf(Skill.SELF_FEED),
            stats = Stats(satiety = 20f, energy = 12f, hygiene = 95f),
            inventory = mapOf("meal_bowl" to 2),
        )
        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(hungry, config, 1L, Random(3), events)
        val byHand = CareActions.feed(hungry, "meal_bowl").state

        assertEquals(byHand.stats.satiety, after.stats.satiety, 0.001f)
        assertEquals(byHand.weightGrams, after.weightGrams, 0.001f)
        assertEquals(byHand.mealsEaten, after.mealsEaten)
        assertEquals("the meal came out of the tin", 1, after.inventory["meal_bowl"])
        assertEquals(1, after.selfCareActions)
        assertEquals(ActivityKind.EAT, after.activity?.kind)
        assertEquals("meal_bowl", after.activity?.targetId)
        assertEquals(1, decided(events).size)
    }

    @Test
    fun `a decision says what actually set it off`() {
        val hungry = pet(
            skills = setOf(Skill.SELF_FEED),
            stats = Stats(satiety = 20f, energy = 12f, hygiene = 95f),
            inventory = mapOf("meal_bowl" to 2),
        )
        val reason = Brain.tick(hungry, config, 1L, Random(3), mutableListOf()).decisions.last().reason
        assertTrue("the reason must name the level that triggered it: $reason", reason.contains("20%"))
        assertTrue("the reason must name what it actually ate: $reason", reason.contains("Kibble Bowl"))
        assertTrue("it is a sentence, not a label: $reason", reason.endsWith("."))
    }

    @Test
    fun `an empty pantry does not produce a phantom meal`() {
        val hungry = pet(
            skills = setOf(Skill.SELF_FEED),
            stats = Stats(satiety = 8f, energy = 12f, hygiene = 95f),
            inventory = mapOf("medicine" to 1),
        )
        val events = mutableListOf<GameEvent>()
        val after = drive(hungry, ticks = 60, dt = 5L, events = events)

        assertTrue("nothing may be eaten out of an empty tin", decided(events).none { it.kind == ActivityKind.EAT })
        assertEquals(8f, after.stats.satiety, 0.001f)
        assertEquals(mapOf("medicine" to 1), after.inventory)
    }

    @Test
    fun `foraging feeds a pet whose pantry is empty, slowly`() {
        val hungry = pet(
            skills = setOf(Skill.SELF_FEED, Skill.FORAGE),
            stats = Stats(satiety = 8f, energy = 40f, hygiene = 95f),
            inventory = emptyMap(),
        )
        val events = mutableListOf<GameEvent>()
        val after = drive(hungry, ticks = 90, events = events)

        assertEquals(ActivityKind.EAT, after.activity?.kind)
        assertEquals(Brain.FORAGE_TARGET, after.activity?.targetId)
        assertTrue("foraging must actually feed it: ${after.stats.satiety}", after.stats.satiety > 20f)
        assertTrue("but not as well as a meal would", after.stats.satiety < 8f + 34f)
        assertTrue("nothing came out of the tin", after.inventory.isEmpty())
        assertEquals("foraged food is not a served meal", 0, after.mealsEaten)
    }

    @Test
    fun `the brain commits to a choice instead of re-deciding every tick`() {
        val messy = pet(
            autonomy = Autonomy.ASSIST,
            skills = setOf(Skill.TIDY_UP),
            stats = Stats(hygiene = 60f),
            poops = 2,
        )
        val events = mutableListOf<GameEvent>()
        val after = drive(messy, ticks = 40, events = events)

        val choices = decided(events)
        assertTrue("it must decide something", choices.isNotEmpty())
        assertTrue("forty seconds is not forty decisions: ${choices.size}", choices.size <= 3)
        assertNotNull("it should still be busy", after.activity)
        assertTrue(
            "an activity that ran its course must announce itself",
            events.any { it is GameEvent.Finished && it.kind == ActivityKind.TIDY },
        )
    }

    @Test
    fun `two decisions are never taken back to back`() {
        val messy = pet(autonomy = Autonomy.ASSIST, skills = setOf(Skill.TIDY_UP), poops = 3)
        val log = drive(messy, ticks = 600, dt = 1L) { it.copy(poops = 3) }.decisions
        assertTrue(log.size > 1)
        assertTrue(
            "every decision must sit at least the minimum gap after the last one",
            log.zipWithNext().all { (a, b) -> b.atSeconds - a.atSeconds >= Brain.MIN_DECISION_GAP_SECONDS },
        )
    }

    @Test
    fun `the decision log never grows past the cap`() {
        val messy = pet(autonomy = Autonomy.ASSIST, skills = setOf(Skill.TIDY_UP), poops = 3)
        // The mess keeps coming back, so the pet keeps deciding to deal with it.
        val after = drive(messy, ticks = 400, dt = 10L) { it.copy(poops = 3) }

        assertEquals(Simulation.MAX_DECISION_LOG, after.decisions.size)
        assertTrue(
            "the log is newest last",
            after.decisions.zipWithNext().all { (a, b) -> a.atSeconds < b.atSeconds },
        )
    }

    @Test
    fun `the same seed produces the same run`() {
        val start = pet(
            autonomy = Autonomy.ASSIST,
            skills = setOf(Skill.TIDY_UP, Skill.SELF_GROOM, Skill.SELF_SETTLE),
            stats = Stats(hygiene = 40f, energy = 55f),
            poops = 2,
        )
        val firstEvents = mutableListOf<GameEvent>()
        val secondEvents = mutableListOf<GameEvent>()
        val first = drive(start, ticks = 200, dt = 3L, seed = 99L, events = firstEvents)
        val second = drive(start, ticks = 200, dt = 3L, seed = 99L, events = secondEvents)

        assertEquals("a seeded run must replay exactly", first, second)
        assertEquals(firstEvents.size, secondEvents.size)
        assertEquals(first.decisions.map { it.reason }, second.decisions.map { it.reason })
    }

    @Test
    fun `assisted autonomy will not raid the pantry`() {
        val hungry = pet(
            autonomy = Autonomy.ASSIST,
            skills = Skill.entries.toSet(),
            stats = Stats(satiety = 10f, energy = 12f, hygiene = 95f),
            inventory = mapOf("meal_bowl" to 2),
        )
        val events = mutableListOf<GameEvent>()
        val after = drive(hungry, ticks = 30, dt = 2L, events = events)

        assertEquals("spending the player's food is not upkeep", 2, after.inventory["meal_bowl"])
        assertTrue(decided(events).none { it.kind == ActivityKind.EAT })
        assertEquals(
            "not while it is only assisting",
            Brain.considerations(hungry, config).first { it.kind == ActivityKind.EAT }.blockedBy,
        )
    }

    @Test
    fun `it will not put itself to bed in broad daylight`() {
        val sleepy = pet(
            skills = setOf(Skill.SELF_SETTLE),
            stats = Stats(energy = 12f, hygiene = 95f),
            ageSeconds = noon,
        )
        val after = drive(sleepy, ticks = 40)
        assertFalse("a daytime nap would be undone by the sleep cycle on the same step", after.isSleeping)
        assertEquals(
            "it is broad daylight",
            Brain.considerations(sleepy, config).first { it.kind == ActivityKind.SLEEP }.blockedBy,
        )
    }

    @Test
    fun `a pet that can settle itself goes to bed at night`() {
        val sleepy = pet(
            skills = setOf(Skill.SELF_SETTLE),
            stats = Stats(energy = 12f, hygiene = 95f),
            ageSeconds = smallHours,
        )
        val events = mutableListOf<GameEvent>()
        val after = drive(sleepy, ticks = 60, events = events)

        assertTrue("it should have turned in", after.isSleeping)
        assertEquals(ActivityKind.SLEEP, after.activity?.kind)
        assertEquals(
            "falling asleep is announced exactly once",
            1,
            events.count { it is GameEvent.FellAsleep },
        )
        assertEquals("it decides to sleep once, not once a second", 1, decided(events).size)
    }

    @Test
    fun `the brain never wakes the pet, so it cannot fight the sleep cycle`() {
        val asleep = pet(
            skills = Skill.entries.toSet(),
            stats = Stats(satiety = 5f, energy = 30f, hygiene = 20f),
            poops = 3,
            ageSeconds = smallHours,
        ).copy(isSleeping = true)
        val events = mutableListOf<GameEvent>()
        val after = drive(asleep, ticks = 120, events = events)

        assertTrue("waking belongs to the simulation alone", after.isSleeping)
        assertTrue(events.none { it is GameEvent.WokeUp })
        assertTrue("a sleeping pet has no turn", decided(events).isEmpty())
        assertEquals("and nothing it might have wanted may happen in its sleep", 5f, after.stats.satiety, 0.001f)
        assertEquals(3, after.poops)
        assertNull(after.activity)
    }

    @Test
    fun `being woken early abandons the sleep it had committed to`() {
        val woken = pet(
            skills = setOf(Skill.SELF_SETTLE),
            stats = Stats(energy = 12f, hygiene = 95f),
            ageSeconds = noon,
        ).copy(activity = Activity(ActivityKind.SLEEP, startedAtSeconds = 0L, endsAtSeconds = 9_000L))
        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(woken, config, 1L, Random(5), events)

        assertTrue(
            "a sleep the pet is no longer having has to be let go of",
            events.any { it is GameEvent.Finished && it.kind == ActivityKind.SLEEP },
        )
        assertTrue("and it must not be stuck sleeping while awake", after.activity?.kind != ActivityKind.SLEEP)
    }

    @Test
    fun `medicine comes out of the cupboard when it knows how`() {
        val ill = pet(
            skills = setOf(Skill.MEDICATE),
            stats = Stats(health = 40f, energy = 12f, hygiene = 95f),
            inventory = mapOf("medicine" to 1),
        ).copy(isSick = true)
        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(ill, config, 1L, Random(11), events)

        assertFalse(after.isSick)
        assertEquals(1, after.medicineDoses)
        assertEquals("the dose is spent, exactly as a keeper's would be", 0, after.inventory["medicine"] ?: 0)
        assertEquals(1, after.selfCareActions)
        assertEquals(ActivityKind.MEDICATE, after.activity?.kind)
        assertTrue(events.any { it is GameEvent.Recovered })
    }

    @Test
    fun `an illness it cannot treat is left for the keeper`() {
        val ill = pet(
            skills = emptySet(),
            stats = Stats(health = 40f, energy = 12f, hygiene = 95f),
            inventory = mapOf("medicine" to 1),
        ).copy(isSick = true)
        val after = drive(ill, ticks = 30, dt = 2L)

        assertTrue(after.isSick)
        assertEquals(1, after.inventory["medicine"])
    }

    @Test
    fun `tidying takes one mess at a time`() {
        val messy = pet(autonomy = Autonomy.ASSIST, skills = setOf(Skill.TIDY_UP), poops = 3)
        val after = Brain.tick(messy, config, 1L, Random(2), mutableListOf())

        assertEquals("a creature clears up at a creature's pace", 2, after.poops)
        assertEquals(1, after.cleanups)
        assertEquals(1, after.selfCareActions)
        assertEquals(ActivityKind.TIDY, after.activity?.kind)
    }

    @Test
    fun `grooming cleans the coat over the stretch it committed to`() {
        val grubby = pet(
            autonomy = Autonomy.ASSIST,
            skills = setOf(Skill.SELF_GROOM),
            stats = Stats(hygiene = 40f, energy = 12f),
        )
        val after = drive(grubby, ticks = 40)
        assertEquals(ActivityKind.GROOM, after.activity?.kind)
        assertTrue("a wash has to be worth having: ${after.stats.hygiene}", after.stats.hygiene > 50f)
        assertEquals(1, after.selfCareActions)
    }

    @Test
    fun `idling does not fill the log with itself`() {
        // Nothing to do, nothing it can do: the pet should say so once and then get on with it.
        val bored = pet(skills = emptySet(), stats = Stats(satiety = 90f, energy = 12f, hygiene = 95f))
        val events = mutableListOf<GameEvent>()
        val after = drive(bored, ticks = 400, dt = 2L, events = events)

        assertEquals(ActivityKind.IDLE, after.activity?.kind)
        assertEquals("one line for idling, not four hundred", 1, decided(events).size)
    }

    @Test
    fun `studying is handed to the lessons system rather than reimplemented`() {
        // Fed, rested, nothing wrong, and clever enough that a skill is actually in reach: the
        // state a curious pet reads a book in.
        val settled = pet(skills = emptySet(), stats = Stats(satiety = 70f, energy = 80f, hygiene = 90f))
            .copy(intellect = 20f)
        val after = drive(settled, ticks = 60)

        assertEquals(ActivityKind.STUDY, after.activity?.kind)
        assertTrue("the brain chooses; Learning does the work", after.intellect > settled.intellect)
        assertTrue("study time has to be banked by the system that owns it", after.studySeconds > 0L)
    }

    @Test
    fun `visiting a friend is handed to the colony rather than reimplemented`() {
        val visitor = Pal(
            id = "pal_1",
            name = "Moss",
            species = Species.AQUA,
            genome = Genome(),
            personality = Personality.CALM,
            affinity = 20f,
            present = true,
        )
        val host = pet(
            skills = setOf(Skill.SOCIALISE),
            stats = Stats(satiety = 70f, energy = 80f, hygiene = 90f),
        ).copy(pals = listOf(visitor))
        val events = mutableListOf<GameEvent>()
        val after = drive(host, ticks = 60, events = events)

        assertEquals(ActivityKind.SOCIALISE, after.activity?.kind)
        assertEquals("pal_1", after.activity?.targetId)
        assertTrue("the visit has to be worth something", after.pals.first().affinity > 20f)
        assertEquals(1, after.socialActions)
        assertEquals("a friend in the room beats a book", "Moss", decided(events).last().reason.take(4))
    }
}
