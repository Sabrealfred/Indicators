package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The social half of the game. The rules worth guarding are the ones a player would notice going
 * wrong: a world that reshuffles itself on the same seed, a friendship announced twice, a
 * refusal with no reason attached, and a friend who quietly evaporates overnight.
 */
class ColonyTest {

    private val config = GameConfig.Default

    /** A grown, awake, reasonably happy pet with a founder genome and nobody in the room. */
    private fun pet(
        name: String = "Pip",
        seed: Long = 7L,
        personality: Personality = Personality.CALM,
    ): PetState = Simulation.newGame(name, Species.AQUA, 1_000_000L, seed = seed).copy(
        stage = LifeStage.ADULT,
        personality = personality,
        ageSeconds = 10_000L,
        stageStartedSeconds = 9_000L,
    )

    /** A companion far enough away genetically that breeding is never the thing being blocked. */
    private fun mira(affinity: Float = 85f, relation: Relation = Relation.FRIEND) = Pal(
        id = "pal_mira",
        name = "Mira",
        species = Species.VOLT,
        genome = Genome.fromList(List(Genome.GENE_COUNT) { 0.85f }),
        personality = Personality.CALM,
        stage = LifeStage.ADULT,
        relation = relation,
        affinity = affinity,
        metAtSeconds = 5_000L,
        lastSeenSeconds = 10_000L,
        present = true,
    )

    /** Advances the clock the way [Simulation] does, then ticks the colony over the same step. */
    private fun run(
        start: PetState,
        seconds: Long,
        step: Long,
        random: Random,
        events: MutableList<GameEvent>,
        onEachTick: (PetState) -> Unit = {},
    ): PetState {
        var s = start
        var elapsed = 0L
        while (elapsed < seconds) {
            s = s.copy(ageSeconds = s.ageSeconds + step)
            s = Colony.tick(s, config, step, random, events)
            onEachTick(s)
            elapsed += step
        }
        return s
    }

    // ---- determinism --------------------------------------------------------------------

    @Test
    fun `the same seed produces the same visitors`() {
        val firstEvents = mutableListOf<GameEvent>()
        val first = run(pet(), seconds = 24 * 3600L, step = 60L, random = Random(1234L), events = firstEvents)

        val secondEvents = mutableListOf<GameEvent>()
        val second = run(pet(), seconds = 24 * 3600L, step = 60L, random = Random(1234L), events = secondEvents)

        val met = firstEvents.filterIsInstance<GameEvent.MetPal>()
        assertTrue("a day with no visitors at all would make this test prove nothing", met.size >= 3)

        assertEquals(
            "the same seed must rebuild the same world",
            first.pals.map { it.name to it.genome },
            second.pals.map { it.name to it.genome },
        )
        assertEquals(
            met.map { it.pal.name },
            secondEvents.filterIsInstance<GameEvent.MetPal>().map { it.pal.name },
        )
    }

    @Test
    fun `a generated companion is far enough away genetically to be worth breeding with`() {
        val events = mutableListOf<GameEvent>()
        run(pet(), seconds = 48 * 3600L, step = 60L, random = Random(99L), events = events)
        val strangers = events.filterIsInstance<GameEvent.MetPal>().map { it.pal }.distinctBy { it.id }
        assertTrue("no companions were generated at all", strangers.isNotEmpty())

        val base = pet().genome
        val useful = strangers.count { Genome.distance(base, it.genome) >= Genome.MIN_USEFUL_DISTANCE }
        assertTrue(
            "most visitors must be breedable, or the colony has no second half: $useful of ${strangers.size}",
            useful * 2 >= strangers.size,
        )
        assertTrue("names must be short enough to fit a label", strangers.all { it.name.length in 2..8 })
        assertEquals("names must not collide", strangers.map { it.name }.distinct().size, strangers.size)
    }

    // ---- affinity and the once-only events ----------------------------------------------

    @Test
    fun `crossing into friendship is announced once and only once`() {
        val events = mutableListOf<GameEvent>()
        var state = pet().copy(pals = listOf(mira(affinity = 0f, relation = Relation.VISITOR)))
        val random = Random(5L)

        // Long enough to cross 50, carry on well past it, and pin against the ceiling.
        repeat(800) {
            state = state.copy(ageSeconds = state.ageSeconds + 10L)
            state = Colony.interact(state, "pal_mira", ActivityKind.SOCIALISE, 10L, random, events)
        }

        val pal = state.pals.single()
        assertTrue("company must build affinity", pal.affinity >= Pal.FRIEND_AT)
        assertEquals(
            "an event that re-fires at the boundary is the classic bug here",
            1,
            events.filterIsInstance<GameEvent.Befriended>().count { it.pal.id == "pal_mira" },
        )
        assertEquals(
            1,
            events.filterIsInstance<GameEvent.Paired>().count { it.pal.id == "pal_mira" },
        )
        assertEquals(Relation.MATE, pal.relation)
    }

    @Test
    fun `a friendship survives cooling off and is not announced a second time`() {
        val events = mutableListOf<GameEvent>()
        var state = pet().copy(pals = listOf(mira(affinity = 0f, relation = Relation.VISITOR)))
        val random = Random(11L)

        // Befriended, but stopped short of courting.
        while (state.pals.single().affinity < Pal.FRIEND_AT + 6f) {
            state = state.copy(ageSeconds = state.ageSeconds + 10L)
            state = Colony.interact(state, "pal_mira", ActivityKind.SOCIALISE, 10L, random, events)
        }
        assertEquals(1, events.filterIsInstance<GameEvent.Befriended>().size)

        // A fortnight apart, asleep so nobody else calls round, then picked up again.
        state = state.copy(pals = state.pals.map { it.copy(present = false) }, isSleeping = true)
        state = run(state, seconds = 14 * 24 * 3600L, step = 60L, random = random, events = events)
        state = state.copy(pals = state.pals.map { it.copy(present = true) }, isSleeping = false)
        repeat(200) {
            state = state.copy(ageSeconds = state.ageSeconds + 10L)
            state = Colony.interact(state, "pal_mira", ActivityKind.SOCIALISE, 10L, random, events)
        }

        assertEquals(
            "re-crossing the line must not announce the same friendship again",
            1,
            events.filterIsInstance<GameEvent.Befriended>().count { it.pal.id == "pal_mira" },
        )
    }

    @Test
    fun `affinity decay does not delete an established friend over a long absence`() {
        val events = mutableListOf<GameEvent>()
        val friend = mira(affinity = 62f, relation = Relation.FRIEND).copy(present = false)
        // Asleep, so the only thing acting on the list over the next two days is decay.
        val start = pet().copy(pals = listOf(friend), isSleeping = true)

        val after = run(start, seconds = 48 * 3600L, step = 60L, random = Random(3L), events = events)

        val pal = after.pals.single { it.id == "pal_mira" }
        assertTrue("time apart has to cost something", pal.affinity < 62f)
        assertTrue("a real friend must not evaporate over a night away", pal.isFriend)
        assertEquals("standing is never taken back", Relation.FRIEND, pal.relation)
    }

    @Test
    fun `an unknown or absent companion cannot be interacted with`() {
        val events = mutableListOf<GameEvent>()
        val state = pet().copy(pals = listOf(mira(affinity = 20f).copy(present = false)))
        val random = Random(2L)

        assertEquals(state, Colony.interact(state, "nobody", ActivityKind.SOCIALISE, 30L, random, events))
        assertEquals(state, Colony.interact(state, "pal_mira", ActivityKind.SOCIALISE, 30L, random, events))
        assertTrue(events.isEmpty())
    }

    // ---- pairing ------------------------------------------------------------------------

    /** A pet and a companion for whom nothing at all is in the way. */
    private fun readyToPair(): PetState = pet().copy(
        skills = setOf(Skill.COURT),
        pals = listOf(mira()),
    )

    @Test
    fun `a pairing blocked for each distinct reason says which reason`() {
        val ready = readyToPair()
        assertNull("this pairing is supposed to be allowed", Colony.pairingBlocker(ready, "pal_mira"))

        val blockers = listOf(
            Colony.pairingBlocker(ready, "pal_nobody"),
            Colony.pairingBlocker(ready.copy(isDead = true), "pal_mira"),
            Colony.pairingBlocker(ready.copy(pals = listOf(mira().copy(present = false))), "pal_mira"),
            Colony.pairingBlocker(ready.copy(skills = emptySet()), "pal_mira"),
            Colony.pairingBlocker(ready.copy(stage = LifeStage.CHILD), "pal_mira"),
            Colony.pairingBlocker(ready.copy(pals = listOf(mira().copy(stage = LifeStage.CHILD))), "pal_mira"),
            Colony.pairingBlocker(ready.copy(pals = listOf(mira(affinity = 60f))), "pal_mira"),
            Colony.pairingBlocker(ready.copy(nest = List(Colony.MAX_NEST_EGGS) { egg(it) }), "pal_mira"),
            Colony.pairingBlocker(ready.copy(pals = listOf(mira().copy(genome = ready.genome))), "pal_mira"),
        )

        blockers.forEachIndexed { i, blocker ->
            assertNotNull("blocked pairing $i must say why", blocker)
            assertTrue("a blocker is a sentence for the player", blocker!!.endsWith("."))
        }
        assertEquals(
            "every reason must read differently, or the UI cannot tell the player anything",
            blockers.size,
            blockers.toSet().size,
        )
    }

    @Test
    fun `two near-identical genomes are blocked rather than allowed to repeat themselves`() {
        val twin = mira().copy(genome = pet().genome)
        val state = readyToPair().copy(pals = listOf(twin))
        val blocker = Colony.pairingBlocker(state, "pal_mira")
        assertNotNull(blocker)
        assertTrue("the reason must name the bloodline", blocker!!.contains("closely related"))

        val events = mutableListOf<GameEvent>()
        val after = Colony.pair(state, "pal_mira", config, Random(1L), events)
        assertEquals("a blocked pairing must change nothing", state, after)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a successful pairing lays exactly one egg whose genome sits between the parents`() {
        val state = readyToPair()
        val events = mutableListOf<GameEvent>()
        val after = Colony.pair(state, "pal_mira", config, Random(21L), events)

        assertEquals(1, after.nest.size)
        assertEquals(1, events.filterIsInstance<GameEvent.EggLaid>().size)
        assertEquals(1, events.filterIsInstance<GameEvent.Paired>().size)
        assertEquals(Relation.MATE, after.pals.single().relation)

        val egg = after.nest.single()
        assertTrue("an egg has to be somewhere in the future", egg.hatchesAtSeconds > state.ageSeconds)
        assertEquals("Mira", egg.otherParentName)

        val mine = state.genome.toList()
        val theirs = after.pals.single().genome.toList()
        val child = egg.genome.toList()
        val tolerance = Genome.DEFAULT_MUTATION + 0.001f
        for (i in 0 until Genome.GENE_COUNT) {
            val low = minOf(mine[i], theirs[i]) - tolerance
            val high = maxOf(mine[i], theirs[i]) + tolerance
            assertTrue(
                "gene $i (${child[i]}) must sit between its parents (${mine[i]}, ${theirs[i]})",
                child[i] in low..high,
            )
        }
    }

    @Test
    fun `the nest fills up and then refuses`() {
        var state = readyToPair()
        val events = mutableListOf<GameEvent>()
        repeat(Colony.MAX_NEST_EGGS + 2) {
            state = Colony.pair(state, "pal_mira", config, Random(30L + it), events)
        }
        assertEquals(Colony.MAX_NEST_EGGS, state.nest.size)
        assertEquals(Colony.MAX_NEST_EGGS, events.filterIsInstance<GameEvent.EggLaid>().size)
        assertEquals(
            "a mate is announced once, however many eggs follow",
            1,
            events.filterIsInstance<GameEvent.Paired>().size,
        )
        assertEquals("The nest is already full.", Colony.pairingBlocker(state, "pal_mira"))
    }

    // ---- hatching -----------------------------------------------------------------------

    @Test
    fun `an egg hatches into an offspring carrying both parents' names`() {
        val events = mutableListOf<GameEvent>()
        val laid = Colony.pair(readyToPair(), "pal_mira", config, Random(42L), events)
        val egg = laid.nest.single()

        val due = laid.copy(ageSeconds = egg.hatchesAtSeconds + 30L)
        val hatched = Colony.tick(due, config, 30L, Random(43L), events)

        val born = events.filterIsInstance<GameEvent.ChildHatched>()
        assertEquals(1, born.size)
        assertTrue("the egg must leave the nest when it hatches", hatched.nest.isEmpty())

        val child = hatched.pals.single { it.relation == Relation.OFFSPRING }
        assertEquals(born.single().child.id, child.id)
        assertEquals(listOf("Pip", "Mira"), child.parentNames)
        assertEquals(egg.genome, child.genome)
        assertEquals(LifeStage.BABY, child.stage)
        assertTrue("offspring are family before they have earned it", child.affinity >= Pal.FRIEND_AT)
        assertTrue("a newborn is in the room", child.present)

        // Ticking on must not hatch it twice.
        val later = Colony.tick(hatched.copy(ageSeconds = hatched.ageSeconds + 60L), config, 60L, Random(44L), events)
        assertEquals(1, events.filterIsInstance<GameEvent.ChildHatched>().size)
        assertEquals(1, later.pals.count { it.relation == Relation.OFFSPRING })
    }

    @Test
    fun `a parent who has learned to teach passes skills on, and one who has not passes none`() {
        val schooled = readyToPair().copy(
            skills = setOf(Skill.COURT, Skill.TEACH, Skill.SELF_FEED, Skill.TIDY_UP),
        )
        assertTrue(taughtSkills(schooled).contains(Skill.SELF_FEED))
        assertTrue("teaching is capped, not a wholesale copy", taughtSkills(schooled).size <= 3)
        assertTrue(taughtSkills(readyToPair()).isEmpty())
    }

    /** Runs one pairing through to hatching and reports what the child came out knowing. */
    private fun taughtSkills(state: PetState): Set<Skill> {
        val events = mutableListOf<GameEvent>()
        val laid = Colony.pair(state, "pal_mira", config, Random(7L), events)
        val due = laid.copy(ageSeconds = laid.nest.single().hatchesAtSeconds + 30L)
        Colony.tick(due, config, 30L, Random(8L), events)
        return events.filterIsInstance<GameEvent.ChildHatched>().single().child.skills
    }

    // ---- caps ---------------------------------------------------------------------------

    @Test
    fun `the remembered pal list stays capped and keeps family first`() {
        val crowd = (0 until Colony.MAX_REMEMBERED_PALS + 6).map { i ->
            mira(affinity = i.toFloat(), relation = Relation.VISITOR).copy(
                id = "pal_$i",
                name = "Vis$i",
                present = false,
            )
        }
        val child = mira(affinity = 90f, relation = Relation.OFFSPRING).copy(id = "pal_kid", name = "Kid")
        val events = mutableListOf<GameEvent>()

        val state = pet().copy(pals = crowd + child, isSleeping = true)
        val after = Colony.tick(state.copy(ageSeconds = state.ageSeconds + 30L), config, 30L, Random(1L), events)

        assertEquals(Colony.MAX_REMEMBERED_PALS, after.pals.size)
        assertTrue("family is never the thing that gets forgotten", after.pals.any { it.id == "pal_kid" })
        assertTrue(
            "the least-liked acquaintance goes first",
            after.pals.none { it.id == "pal_0" },
        )
        assertEquals(
            "the list must keep its own order rather than reshuffling itself",
            after.pals.map { it.id },
            (crowd + child).map { it.id }.filter { id -> after.pals.any { it.id == id } },
        )
    }

    @Test
    fun `a busy fortnight never swamps the room or the save`() {
        var worstPals = 0
        var worstPresent = 0
        val events = mutableListOf<GameEvent>()
        run(pet(), seconds = 14 * 24 * 3600L, step = 60L, random = Random(77L), events = events) { s ->
            worstPals = maxOf(worstPals, s.pals.size)
            worstPresent = maxOf(worstPresent, s.presentPals.size)
        }

        assertTrue("a fortnight with no callers would prove nothing", worstPals > 0)
        assertTrue("the save must stay bounded, it is rewritten every tick: $worstPals", worstPals <= Colony.MAX_REMEMBERED_PALS)
        assertTrue("the room must stay legible: $worstPresent", worstPresent <= Colony.MAX_PRESENT_VISITORS)
        assertTrue("a visitor who is not befriended has to leave again", events.any { it is GameEvent.PalLeft })
    }

    @Test
    fun `an egg and a sleeping pet still see no visitors`() {
        val events = mutableListOf<GameEvent>()
        val asEgg = pet().copy(stage = LifeStage.EGG)
        run(asEgg, seconds = 24 * 3600L, step = 60L, random = Random(4L), events = events)
        run(pet().copy(isSleeping = true), seconds = 24 * 3600L, step = 60L, random = Random(4L), events = events)
        assertTrue("nobody calls on an egg, or at three in the morning", events.none { it is GameEvent.MetPal })
    }

    // ---- read-only helpers --------------------------------------------------------------

    @Test
    fun `the child preview is the average of the parents and never rolls the dice`() {
        val state = readyToPair()
        val preview = Colony.previewChild(state, "pal_mira")
        assertNotNull(preview)

        val mine = state.genome.toList()
        val theirs = state.pals.single().genome.toList()
        val shown = preview!!.genome.toList()
        for (i in 0 until Genome.GENE_COUNT) {
            assertEquals((mine[i] + theirs[i]) / 2f, shown[i], 0.0001f)
        }
        assertEquals(
            "a preview that changes when you look at it twice is a lie",
            preview,
            Colony.previewChild(state, "pal_mira"),
        )
        assertTrue(preview.summary.endsWith("."))
        assertNull(Colony.previewChild(state, "pal_nobody"))
    }

    @Test
    fun `the family tree is assembled from the pals list rather than stored`() {
        val events = mutableListOf<GameEvent>()
        val laid = Colony.pair(readyToPair(), "pal_mira", config, Random(9L), events)
        val hatched = Colony.tick(
            laid.copy(ageSeconds = laid.nest.single().hatchesAtSeconds + 30L),
            config,
            30L,
            Random(10L),
            events,
        )

        val tree = Colony.familyTree(hatched.copy(parentNames = listOf("Nan", "Pop")))
        assertEquals("Pip", tree.name)
        assertEquals(listOf("Nan", "Pop"), tree.parentNames)
        assertEquals(listOf("Mira"), tree.mates.map { it.name })
        assertEquals(1, tree.offspring.size)
        assertTrue(tree.expecting.isEmpty())
        assertNull("an empty nest is not due anything", Colony.nextHatchInSeconds(hatched))
        assertEquals(
            Colony.incubationSeconds(config),
            Colony.nextHatchInSeconds(laid),
        )
    }

    private fun egg(index: Int) = NestEgg(
        id = "egg_$index",
        genome = Genome(),
        species = Species.AQUA,
        laidAtSeconds = 0L,
        hatchesAtSeconds = 1_000_000L,
        otherParentId = "pal_mira",
        otherParentName = "Mira",
    )
}
