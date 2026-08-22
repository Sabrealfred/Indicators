package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the one thing a narrator has: being right, and being the only one saying it.
 *
 * Text is the easiest part of a codebase to break silently — a moment that returns nothing shows
 * an empty card, a line copied between two moments makes the game look like it has one thing to
 * say, and a selector that answers differently on two identical saves makes the narrator a liar.
 * None of those fail a compile, so they are all asserted here instead.
 */
class LoreTest {

    // ---- fixtures -----------------------------------------------------------------------

    private fun pet(
        generation: Int = 1,
        stage: LifeStage = LifeStage.ADULT,
        genome: Genome = Genome(),
        lessons: List<Lesson> = emptyList(),
        pals: List<Pal> = emptyList(),
        nest: List<NestEgg> = emptyList(),
        skills: Set<Skill> = emptySet(),
        previous: List<RunRecord> = emptyList(),
        isDead: Boolean = false,
        parentNames: List<String> = emptyList(),
        ageSeconds: Long = 0L,
        stageStartedSeconds: Long = 0L,
    ): PetState = PetState(
        name = "Pip",
        species = Species.LEAF,
        stage = stage,
        generation = generation,
        genome = genome,
        lessons = lessons,
        pals = pals,
        nest = nest,
        skills = skills,
        previousGenerations = previous,
        isDead = isDead,
        parentNames = parentNames,
        ageSeconds = ageSeconds,
        stageStartedSeconds = stageStartedSeconds,
    )

    /** An elder that has been one for [fraction] of the stage the simulation gives an elder. */
    private fun elder(fraction: Float, isDead: Boolean = false): PetState {
        val full = Simulation.stageDuration(LifeStage.ELDER, GameConfig.Default)
        return pet(
            stage = LifeStage.ELDER,
            isDead = isDead,
            ageSeconds = (full * fraction).toLong(),
            stageStartedSeconds = 0L,
        )
    }

    private fun pal(
        id: String,
        relation: Relation = Relation.VISITOR,
        affinity: Float = 0f,
        skills: Set<Skill> = emptySet(),
    ) = Pal(
        id = id,
        name = id,
        species = Species.AQUA,
        genome = Genome(),
        personality = Personality.CALM,
        relation = relation,
        affinity = affinity,
        skills = skills,
    )

    private fun egg(id: String) = NestEgg(
        id = id,
        genome = Genome(),
        species = Species.AQUA,
        laidAtSeconds = 0L,
        hatchesAtSeconds = 100L,
        otherParentId = "p",
        otherParentName = "P",
    )

    private val record = RunRecord(generation = 1, name = "Ash", lifespanSeconds = 3600L)

    /** All fourteen genes at one: the far end of the drift, drawn on four legs. */
    private val houndGenome = Genome.fromList(List(Genome.GENE_COUNT) { 1f })

    /** Long face, hanging ears, long legs — and still standing up. Houndish score, biped body. */
    private val uprightButLongGenome = Genome().copy(
        muzzle = 1f, ears = 1f, earDroop = 1f, limbs = 1f, stance = 0f,
    )

    private fun lesson(kind: LessonKind, from: Int, strength: Float = 0.5f) =
        Lesson(kind = kind, text = "carried", strength = strength, fromGeneration = from)

    /** Every moment, reached only through the public selectors — never through `entries`. */
    private fun reachedMoments(): Set<LoreMoment> {
        val found = mutableSetOf<LoreMoment>()

        // The frame is reached by asking for it.
        if (Lore.frame.isNotEmpty()) found += LoreMoment.THE_VIVARIUM

        found += Lore.generationMoment(pet(generation = 1))
        found += Lore.generationMoment(pet(generation = 2, previous = listOf(record)))
        found += Lore.generationMoment(pet(generation = Lore.DEEP_LINE_AT, previous = listOf(record)))

        listOf(LifeStage.CHILD, LifeStage.TEEN, LifeStage.ADULT, LifeStage.ELDER).forEach { to ->
            Lore.momentFor(
                GameEvent.Evolved(LifeStage.BABY, to, EvolutionBranch.BALANCED),
                pet(),
            )?.let { found += it }
        }

        Lore.momentFor(GameEvent.LearnedSkill(Skill.SELF_FEED), pet(skills = setOf(Skill.SELF_FEED)))
            ?.let { found += it }
        Lore.momentFor(
            GameEvent.LearnedSkill(Skill.TEACH),
            pet(skills = setOf(Skill.SELF_FEED, Skill.TEACH)),
        )?.let { found += it }

        Lore.momentFor(GameEvent.Recovered, pet())?.let { found += it }

        val caller = pal("v1")
        Lore.momentFor(GameEvent.MetPal(caller), pet(pals = listOf(caller)))?.let { found += it }

        Lore.lateLifeMoment(elder(0.9f))?.let { found += it }
        Lore.legacyMoment(
            pet(isDead = true, pals = listOf(pal("c9", Relation.OFFSPRING))),
        )?.let { found += it }

        val friend = pal("f1", Relation.FRIEND, affinity = 80f)
        Lore.momentFor(GameEvent.Befriended(friend), pet(pals = listOf(friend)))?.let { found += it }

        val mate = pal("m1", Relation.MATE, affinity = 90f)
        Lore.momentFor(GameEvent.Paired(mate), pet(pals = listOf(mate)))?.let { found += it }

        val e = egg("e1")
        Lore.momentFor(GameEvent.EggLaid(e), pet(nest = listOf(e)))?.let { found += it }

        val plainChild = pal("c1", Relation.OFFSPRING, affinity = 88f)
        Lore.momentFor(GameEvent.ChildHatched(plainChild), pet(pals = listOf(plainChild)))
            ?.let { found += it }

        val taughtChild = pal("c2", Relation.OFFSPRING, affinity = 88f, skills = setOf(Skill.SELF_FEED))
        Lore.momentFor(GameEvent.ChildHatched(taughtChild), pet(pals = listOf(taughtChild)))
            ?.let { found += it }

        DeathReason.entries.forEach { found += Lore.endMoment(it) }
        DeathReason.entries.forEach { reason ->
            Lore.momentFor(GameEvent.Died(reason), pet(isDead = true))?.let { found += it }
        }

        Lore.autonomyMoment(Autonomy.OFF, Autonomy.ASSIST)?.let { found += it }
        Lore.returnMoment(wasCaughtUp = true, state = pet())?.let { found += it }

        Lore.inheritanceMoment(
            pet(generation = 3, lessons = listOf(lesson(LessonKind.EAT_SOONER, from = 2))),
        )?.let { found += it }

        Lore.shapeMoment(pet(genome = houndGenome))?.let { found += it }
        Lore.shapeMoment(pet(genome = uprightButLongGenome))?.let { found += it }

        return found
    }

    // ---- the four the brief asks for -----------------------------------------------------

    @Test
    fun `every moment has text`() {
        LoreMoment.entries.forEach { moment ->
            val passage = Lore.passage(moment)
            assertEquals("passage reports the wrong moment", moment, passage.moment)
            assertEquals("passage reports the wrong voice", moment.voice, passage.voice)
            assertTrue("$moment has no lines", passage.lines.isNotEmpty())
            assertTrue(
                "$moment has ${passage.lines.size} lines, more than a memorial",
                passage.lines.size <= Lore.MAX_LINES,
            )
        }
    }

    @Test
    fun `every reachable moment is reached by a selector`() {
        assertEquals(
            "a moment nothing can produce is text nobody will ever see",
            LoreMoment.entries.toSet(),
            reachedMoments(),
        )
    }

    @Test
    fun `no two moments share a line`() {
        val all = LoreMoment.entries.flatMap { Lore.passage(it).lines }
        assertEquals("a line is used by more than one moment", all.size, all.toSet().size)
    }

    @Test
    fun `nothing is blank`() {
        LoreMoment.entries.forEach { moment ->
            Lore.passage(moment).lines.forEach { line ->
                assertTrue("$moment has a blank line", line.isNotBlank())
                assertEquals("$moment has a line with loose whitespace", line.trim(), line)
                assertTrue(
                    "$moment has a ${line.length}-character line, longer than a milestone card",
                    line.length <= Lore.MAX_LINE_CHARS,
                )
            }
        }
        // The dynamic surfaces have the same duty.
        val standing = Lore.standing(
            pet(generation = 3, previous = listOf(record), lessons = listOf(lesson(LessonKind.EAT_SOONER, 2))),
        )
        assertNotNull(standing)
        assertTrue(standing!!.isNotBlank())
        assertTrue(standing.length <= Lore.MAX_LINE_CHARS)
    }

    @Test
    fun `selection is deterministic for a given state`() {
        val states = listOf(
            pet(generation = 1),
            pet(generation = 2, previous = listOf(record)),
            pet(generation = 6, previous = listOf(record, record), lessons = listOf(lesson(LessonKind.GUARD_HEALTH, 4))),
            pet(genome = houndGenome),
            pet(genome = uprightButLongGenome, stage = LifeStage.BABY),
            pet(stage = LifeStage.EGG),
        )
        states.forEach { state ->
            // Same object twice, and an equal-but-distinct copy: neither may disagree.
            val twin = state.copy()
            assertEquals(Lore.generationMoment(state), Lore.generationMoment(twin))
            assertEquals(Lore.shapeMoment(state), Lore.shapeMoment(twin))
            assertEquals(Lore.inheritanceMoment(state), Lore.inheritanceMoment(twin))
            assertEquals(Lore.standing(state), Lore.standing(twin))
            assertEquals(
                Lore.momentFor(GameEvent.Hatched, state),
                Lore.momentFor(GameEvent.Hatched, twin),
            )
            assertEquals(Lore.passage(LoreMoment.FOUNDING), Lore.passage(LoreMoment.FOUNDING))
        }
    }

    // ---- voice ---------------------------------------------------------------------------

    @Test
    fun `nothing here speaks in the first person`() {
        // The diary owns "I". If a line in this file ever used it, the two voices would be one
        // voice and the split that justifies this file existing would be gone.
        val firstPerson = Regex("""\b(I|I'm|I'd|I've|me|my|mine|we|us|our)\b""")
        (LoreMoment.entries.flatMap { Lore.passage(it).lines } + listOfNotNull(
            Lore.standing(pet(generation = 3, previous = listOf(record), lessons = listOf(lesson(LessonKind.EAT_SOONER, 2)))),
            Lore.standing(pet(generation = 2, previous = listOf(record))),
            Lore.standing(pet(generation = 1, lessons = listOf(lesson(LessonKind.PLAY_MORE, 1)))),
        )).forEach { line ->
            assertFalse("first person in the narrator's mouth: $line", firstPerson.containsMatchIn(line))
        }
    }

    @Test
    fun `nothing shouts and nothing is american`() {
        val americanisms = listOf(
            "color", "behavior", "neighbor", "realize", "apologize", "recognize",
            "favorite", "traveled", "gray", "practicing",
        )
        LoreMoment.entries.flatMap { Lore.passage(it).lines }.forEach { line ->
            assertFalse("exclamation mark in $line", line.contains("!"))
            val lower = line.lowercase()
            americanisms.forEach { word ->
                assertFalse("american spelling '$word' in: $line", lower.contains(word))
            }
        }
    }

    // ---- the selectors do not lie ---------------------------------------------------------

    @Test
    fun `a first moment does not fire on the second one`() {
        val first = pal("f1", Relation.FRIEND, affinity = 80f)
        val second = pal("f2", Relation.FRIEND, affinity = 80f)
        assertEquals(
            LoreMoment.FIRST_FRIEND,
            Lore.momentFor(GameEvent.Befriended(first), pet(pals = listOf(first))),
        )
        assertNull(Lore.momentFor(GameEvent.Befriended(second), pet(pals = listOf(first, second))))

        val one = egg("e1")
        val two = egg("e2")
        assertEquals(LoreMoment.EGG_IN_THE_NEST, Lore.momentFor(GameEvent.EggLaid(one), pet(nest = listOf(one))))
        assertNull(Lore.momentFor(GameEvent.EggLaid(two), pet(nest = listOf(one, two))))

        assertEquals(
            LoreMoment.LEARNED_UNAIDED,
            Lore.momentFor(GameEvent.LearnedSkill(Skill.SELF_FEED), pet(skills = setOf(Skill.SELF_FEED))),
        )
        assertNull(
            Lore.momentFor(
                GameEvent.LearnedSkill(Skill.TIDY_UP),
                pet(skills = setOf(Skill.SELF_FEED, Skill.TIDY_UP)),
            ),
        )
    }

    @Test
    fun `a first moment reads the same either side of its own event`() {
        // The caller must not have to snapshot the state before applying the event.
        val friend = pal("f1", Relation.FRIEND, affinity = 80f)
        val before = pet(pals = listOf(pal("f1", Relation.VISITOR, affinity = 49f)))
        val after = pet(pals = listOf(friend))
        assertEquals(
            Lore.momentFor(GameEvent.Befriended(friend), before),
            Lore.momentFor(GameEvent.Befriended(friend), after),
        )

        val child = pal("c1", Relation.OFFSPRING, affinity = 88f)
        assertEquals(
            Lore.momentFor(GameEvent.ChildHatched(child), pet()),
            Lore.momentFor(GameEvent.ChildHatched(child), pet(pals = listOf(child))),
        )
    }

    @Test
    fun `only the first caller of a life is called the first`() {
        val one = pal("v1")
        val two = pal("v2")
        assertEquals(LoreMoment.FIRST_CALLER, Lore.momentFor(GameEvent.MetPal(one), pet(pals = listOf(one))))
        assertNull(Lore.momentFor(GameEvent.MetPal(two), pet(pals = listOf(one, two))))
        // And it reads the same from the state before the pal was added, like every other first.
        assertEquals(LoreMoment.FIRST_CALLER, Lore.momentFor(GameEvent.MetPal(one), pet()))
    }

    @Test
    fun `teaching outranks being the first skill`() {
        // TEACH sits near the top of the ladder so it is never really first, but the ordering has
        // to be stated rather than relied on: it is the only skill that outlives the creature.
        assertEquals(
            LoreMoment.LEARNED_TO_TEACH,
            Lore.momentFor(GameEvent.LearnedSkill(Skill.TEACH), pet(skills = setOf(Skill.TEACH))),
        )
        assertEquals(
            LoreMoment.LEARNED_TO_TEACH,
            Lore.momentFor(
                GameEvent.LearnedSkill(Skill.TEACH),
                pet(skills = setOf(Skill.SELF_FEED, Skill.TIDY_UP, Skill.TEACH)),
            ),
        )
        // And the passage has to be true of the mechanic: teaching is what makes a child arrive
        // knowing anything at all, so the two moments must not contradict each other.
        assertTrue(Skill.TEACH.intellectRequired > Skill.SELF_FEED.intellectRequired)
    }

    @Test
    fun `an elder is only called worn out once it has been one for a while`() {
        assertNull("a fresh elder has not worn out yet", Lore.lateLifeMoment(elder(0.1f)))
        assertEquals(LoreMoment.WEARING_OUT, Lore.lateLifeMoment(elder(0.6f)))
        // A dead elder gets a memorial, not a bulletin about how it is getting on.
        assertNull(Lore.lateLifeMoment(elder(0.9f, isDead = true)))
        // And nothing younger is an elder, however long it has been in its stage.
        assertNull(Lore.lateLifeMoment(pet(stage = LifeStage.ADULT, ageSeconds = 10_000_000L)))
    }

    @Test
    fun `the elder line follows the pace the simulation is actually run at`() {
        // A second opinion about how long an elder lasts would put the narrator and the death
        // check on different calendars, so the threshold has to move with the config.
        val quick = GameConfig.Default.copy(lifeSpeed = 8f)
        val half = Simulation.stageDuration(LifeStage.ELDER, quick) / 2
        val old = pet(stage = LifeStage.ELDER, ageSeconds = half + 1)
        assertEquals(LoreMoment.WEARING_OUT, Lore.lateLifeMoment(old, quick))
        assertNull("the same creature is young for its stage at the default pace", Lore.lateLifeMoment(old))
    }

    @Test
    fun `a death only mentions children when there are children`() {
        val child = pal("c1", Relation.OFFSPRING, affinity = 88f)
        assertEquals(
            LoreMoment.LINE_CONTINUES,
            Lore.legacyMoment(pet(isDead = true, pals = listOf(child))),
        )
        assertNull(Lore.legacyMoment(pet(isDead = true, pals = listOf(pal("f1", Relation.FRIEND)))))
        assertNull(Lore.legacyMoment(pet(isDead = true)))
        // Not while it is still alive: the whole point of the line is what comes next.
        assertNull(Lore.legacyMoment(pet(pals = listOf(child))))
        // An egg in the nest is not a child in the room. It cannot be stood up in the tank.
        assertNull(Lore.legacyMoment(pet(isDead = true, nest = listOf(egg("e1")))))
    }

    @Test
    fun `a recovery is remarked on every time and a memorial is not a recovery`() {
        assertEquals(LoreMoment.FEVER_BROKE, Lore.momentFor(GameEvent.Recovered, pet()))
        assertEquals(LoreMoment.END_ILLNESS, Lore.momentFor(GameEvent.Died(DeathReason.ILLNESS), pet(isDead = true)))
        // Getting ill is the nudge layer's business; the narrator only speaks about the outcome.
        assertNull(Lore.momentFor(GameEvent.GotSick, pet()))
    }

    @Test
    fun `a taught child outranks a first child`() {
        val taught = pal("c1", Relation.OFFSPRING, skills = setOf(Skill.SELF_FEED, Skill.TIDY_UP))
        assertEquals(
            LoreMoment.TAUGHT_ITS_OWN,
            Lore.momentFor(GameEvent.ChildHatched(taught), pet()),
        )
    }

    @Test
    fun `four legs is never claimed for a creature drawn standing up`() {
        val upright = pet(genome = uprightButLongGenome)
        assertTrue("fixture is not houndish enough to be a real test", upright.morphology.houndliness >= Lore.QUADRUPED_AT)
        assertTrue("fixture is not upright", upright.morphology.quadruped < Lore.ON_FOUR_LEGS)
        assertEquals(LoreMoment.SHAPE_DRIFTING, Lore.shapeMoment(upright))

        val hound = pet(genome = houndGenome)
        assertEquals(LoreMoment.SHAPE_QUADRUPED, Lore.shapeMoment(hound))
    }

    @Test
    fun `the starter shape gets no shape line at all`() {
        assertNull(Lore.shapeMoment(pet(genome = Genome())))
        // Nor does an egg, whatever it is carrying.
        assertNull(Lore.shapeMoment(pet(stage = LifeStage.EGG, genome = houndGenome)))
        // Nor a baby: the body has not expressed the genome yet, so the art would contradict it.
        assertNull(Lore.shapeMoment(pet(stage = LifeStage.BABY, genome = houndGenome)))
    }

    @Test
    fun `each death reason gets its own memorial`() {
        val moments = DeathReason.entries.map { Lore.endMoment(it) }
        assertEquals("two deaths share a memorial", moments.size, moments.toSet().size)
        // Old age is the one ending the line has nothing to correct, and the text has to agree
        // with Lineage.distilLocally, which returns nothing for it.
        assertTrue(Lineage.distilLocally(record.copy(deathReason = DeathReason.OLD_AGE), 1).none {
            it.kind == LessonKind.EAT_SOONER || it.kind == LessonKind.GUARD_HEALTH
        })
        assertTrue(
            "starvation must teach eating sooner for its memorial to be true",
            Lineage.distilLocally(record.copy(deathReason = DeathReason.STARVATION), 1)
                .any { it.kind == LessonKind.EAT_SOONER },
        )
        assertTrue(
            "illness must teach guarding health for its memorial to be true",
            Lineage.distilLocally(record.copy(deathReason = DeathReason.ILLNESS), 1)
                .any { it.kind == LessonKind.GUARD_HEALTH },
        )
        assertTrue(
            "neglect must teach seeking company for its memorial to be true",
            Lineage.distilLocally(record.copy(deathReason = DeathReason.NEGLECT), 1)
                .any { it.kind == LessonKind.SEEK_COMPANY },
        )
    }

    @Test
    fun `a generation count with no records behind it is not called a line`() {
        // A save restored from a backup, or written by a build that predates the run log.
        assertEquals(LoreMoment.FOUNDING, Lore.generationMoment(pet(generation = 9)))
        assertEquals(
            LoreMoment.DEEP_LINE,
            Lore.generationMoment(pet(generation = 9, previous = listOf(record))),
        )
    }

    @Test
    fun `standing says nothing about a founder that has nothing behind it`() {
        assertNull(Lore.standing(pet(generation = 1)))
        assertNotNull(Lore.standing(pet(generation = 1, lessons = listOf(lesson(LessonKind.PLAY_MORE, from = 1)))))

        // One life reads as a life, not as "1 lives".
        val one = Lore.standing(pet(generation = 2, previous = listOf(record)))
        assertNotNull(one)
        assertTrue(one!!.startsWith("One life"))

        val two = Lore.standing(pet(generation = 3, previous = listOf(record, record)))
        assertNotNull(two)
        assertTrue(two!!.startsWith("2 lives"))
    }

    @Test
    fun `an inherited lean is named and an afternoon's own lesson is not`() {
        val inherited = pet(generation = 3, lessons = listOf(lesson(LessonKind.EAT_SOONER, from = 2)))
        assertEquals(LoreMoment.LINE_REMEMBERS, Lore.inheritanceMoment(inherited))

        // Learned by this creature, in this life: the diary already has that one.
        val ownWork = pet(generation = 3, lessons = listOf(lesson(LessonKind.EAT_SOONER, from = 3)))
        assertNull(Lore.inheritanceMoment(ownWork))

        // And an egg is told nothing, whatever it is carrying.
        assertNull(Lore.inheritanceMoment(inherited.copy(stage = LifeStage.EGG)))
    }

    @Test
    fun `the day is only handed over once`() {
        assertEquals(LoreMoment.DAY_HANDED_OVER, Lore.autonomyMoment(Autonomy.OFF, Autonomy.ASSIST))
        assertEquals(LoreMoment.DAY_HANDED_OVER, Lore.autonomyMoment(Autonomy.OFF, Autonomy.FULL))
        assertNull(Lore.autonomyMoment(Autonomy.ASSIST, Autonomy.FULL))
        assertNull(Lore.autonomyMoment(Autonomy.FULL, Autonomy.OFF))
        assertNull(Lore.autonomyMoment(Autonomy.OFF, Autonomy.OFF))
    }

    @Test
    fun `the appliance only speaks about an absence it actually covered`() {
        assertEquals(LoreMoment.HELD_WHILE_AWAY, Lore.returnMoment(wasCaughtUp = true, state = pet()))
        assertNull(Lore.returnMoment(wasCaughtUp = false, state = pet()))
        assertNull(Lore.returnMoment(wasCaughtUp = true, state = pet(isDead = true)))
        assertNull(Lore.returnMoment(wasCaughtUp = true, state = pet(stage = LifeStage.EGG)))
    }

    @Test
    fun `the frame is the vivarium's own passage`() {
        assertEquals(Lore.passage(LoreMoment.THE_VIVARIUM).lines, Lore.frame)
        assertEquals(LoreVoice.DEVICE, Lore.passage(LoreMoment.THE_VIVARIUM).voice)
        // The keeper is the only word the game has for the player, so the frame has to use it.
        assertTrue(Lore.frame.any { it.contains("keeper") })
    }
}
