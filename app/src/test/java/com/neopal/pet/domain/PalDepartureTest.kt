package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Leaving the room and leaving for good are not the same thing.
 *
 * Every departure in the colony raised one event carrying one name: a stranger letting itself out
 * after a quarter of an hour, a grown child going off to live its own life, and the roster giving
 * somebody up to stay inside its cap. Nothing downstream could tell them apart, so the diary and
 * the toast handler did the only thing left to do with three events wearing one label — they
 * discarded all three. "Your child moved out" is the biggest thing that happens to a household
 * short of a birth or a death, and it was being dropped on the floor to avoid announcing that a
 * visitor had gone home.
 */
class PalDepartureTest {

    private val config = GameConfig.Default

    private fun pet(): PetState = Simulation.newGame("Pip", Species.AQUA, 1_000_000L, seed = 7L).copy(
        stage = LifeStage.ADULT,
        ageSeconds = 10_000L,
        stageStartedSeconds = 9_000L,
        chronicle = emptyList(),
    )

    private fun pal(
        id: String,
        name: String,
        relation: Relation,
        stage: LifeStage = LifeStage.ADULT,
        metAt: Long = 5_000L,
        lastSeen: Long = 10_000L,
    ) = Pal(
        id = id,
        name = name,
        species = Species.VOLT,
        genome = Genome.fromList(List(Genome.GENE_COUNT) { 0.85f }),
        personality = Personality.CALM,
        stage = stage,
        relation = relation,
        affinity = 40f,
        metAtSeconds = metAt,
        lastSeenSeconds = lastSeen,
        present = true,
    )

    /** As long as it takes a child to get from newly hatched to grown. */
    private val grownUp: Long = listOf(LifeStage.BABY, LifeStage.CHILD, LifeStage.TEEN)
        .sumOf { Simulation.stageDuration(it, config) }

    /** Everything the diary gained from these events. */
    private fun diary(state: PetState, events: List<GameEvent>): List<ChronicleEntry> =
        Chronicle.record(state, events, config).chronicle.drop(state.chronicle.size)

    @Test
    fun `a grown child moving out is a different event from a visitor going home`() {
        val events = mutableListOf<GameEvent>()
        val household = pet().copy(
            ageSeconds = 10_000L + grownUp + 60L,
            pals = listOf(
                pal("pal_kid", "Sprig", Relation.OFFSPRING, stage = LifeStage.BABY, metAt = 10_000L),
                // A stranger who turned up an hour ago: well past the quarter of an hour a visit
                // lasts, so this tick sees them out too.
                pal("pal_moss", "Moss", Relation.VISITOR, lastSeen = 10_000L + grownUp - 3_600L),
            ),
        )

        Colony.tick(household, config, 60L, Random(1L), events)
        val left = events.filterIsInstance<GameEvent.PalLeft>().associateBy { it.name }

        assertEquals("a child that grew up is gone for good", Departure.MOVED_OUT, left.getValue("Sprig").departure)
        assertEquals("a visit that ran its course is not", Departure.WENT_HOME, left.getValue("Moss").departure)
    }

    @Test
    fun `moving out is a milestone the diary keeps`() {
        val events = mutableListOf<GameEvent>()
        val household = pet().copy(
            ageSeconds = 10_000L + grownUp + 60L,
            pals = listOf(pal("pal_kid", "Sprig", Relation.OFFSPRING, stage = LifeStage.BABY, metAt = 10_000L)),
        )
        val after = Colony.tick(household, config, 60L, Random(1L), events)

        val written = diary(after, events)
        assertEquals("one line, for the one thing that happened", 1, written.size)
        assertTrue("it has to name the child: ${written.single().text}", written.single().text.contains("Sprig"))
        assertEquals(
            "a household changing shape for good outranks the days either side of it",
            ChronicleKind.MILESTONE,
            written.single().kind,
        )
    }

    @Test
    fun `a visitor going home is not diary material`() {
        val events = mutableListOf<GameEvent>()
        val visited = pet().copy(
            pals = listOf(pal("pal_moss", "Moss", Relation.VISITOR, lastSeen = 5_000L)),
        )
        val after = Colony.tick(visited, config, 60L, Random(1L), events)

        assertTrue(
            "somebody has to have left, or this test proves nothing",
            events.filterIsInstance<GameEvent.PalLeft>().any { it.name == "Moss" },
        )
        assertTrue(
            "fifteen minutes of company is not a milestone",
            diary(after, events.filterIsInstance<GameEvent.PalLeft>()).isEmpty(),
        )
    }

    @Test
    fun `being let go to stay inside the roster is a third thing again`() {
        // One more than the roster holds, all family so that every eviction is announced. The
        // creature never saw any of this: it is the save keeping itself bounded.
        val brood = (0..Colony.MAX_REMEMBERED_PALS).map {
            pal("pal_grown_$it", "Grown$it", Relation.OFFSPRING, metAt = 0L, lastSeen = it * 100L)
                .copy(present = false)
        }
        val events = mutableListOf<GameEvent>()
        val crowded = pet().copy(pals = brood)
        val after = Colony.tick(crowded, config, 60L, Random(1L), events)

        val left = events.filterIsInstance<GameEvent.PalLeft>()
        assertTrue("the cap has to have bitten", left.isNotEmpty())
        assertTrue(
            "nobody moved out and nobody went home; the roster simply ran out of room",
            left.all { it.departure == Departure.FORGOTTEN },
        )
        assertTrue(
            "a limit on a data structure is not something to tell the player about",
            diary(after, left).isEmpty(),
        )
    }
}
