package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inherited lessons: the mechanism behind "the child is better than the parent".
 *
 * The rules worth guarding are the ones that would quietly turn the feature into decoration — a
 * lesson that never reaches the brain, a bias that grows without limit, or a line that is told it
 * did something wrong when it did not.
 */
class LineageTest {

    private val config = GameConfig.Default
    private val start = 1_000_000L

    private fun record(
        reason: DeathReason? = null,
        careScore: Float = 0.8f,
        mistakes: Int = 0,
        lifespan: Long = 6L * 3600L,
        level: Int = 8,
        games: Int = 20,
    ) = RunRecord(
        generation = 1,
        name = "Parent",
        lifespanSeconds = lifespan,
        deathReason = reason,
        careScore = careScore,
        careMistakes = mistakes,
        gamesPlayed = games,
        level = level,
    )

    @Test
    fun `a starved parent teaches its child to eat sooner`() {
        val lessons = Lineage.distilLocally(record(reason = DeathReason.STARVATION), 1)
        val eat = lessons.firstOrNull { it.kind == LessonKind.EAT_SOONER }
        assertNotNull("starvation has to produce the lesson about eating", eat)
        assertTrue("and it should be the loudest one", lessons.first().kind == LessonKind.EAT_SOONER)
    }

    @Test
    fun `dying of old age is not treated as a mistake to correct`() {
        // A line that reaches the end of a well-kept life has nothing to apologise for, and
        // telling its children otherwise would be the game lying to them.
        val lessons = Lineage.distilLocally(
            record(reason = DeathReason.OLD_AGE, careScore = 0.85f, mistakes = 0),
            1,
        )
        assertTrue(
            "a good long life must not produce a corrective lesson",
            lessons.none {
                it.kind == LessonKind.EAT_SOONER ||
                    it.kind == LessonKind.GUARD_HEALTH ||
                    it.kind == LessonKind.REST_SOONER
            },
        )
    }

    @Test
    fun `lessons of the same kind merge instead of stacking as duplicates`() {
        val first = listOf(Lesson(LessonKind.EAT_SOONER, "One.", 0.4f, 1))
        val second = listOf(Lesson(LessonKind.EAT_SOONER, "Two.", 0.5f, 2))
        val merged = Lineage.inherit(first, second)
        assertEquals("ten generations of the same lesson must not be ten rows", 1, merged.size)
        assertTrue("but the line should hold it harder", merged.first().strength > 0.5f)
    }

    @Test
    fun `a line never carries more lessons than it can show`() {
        var carried = emptyList<Lesson>()
        LessonKind.entries.forEachIndexed { i, kind ->
            carried = Lineage.inherit(carried, listOf(Lesson(kind, "Lesson $i.", 0.6f, i)))
        }
        assertTrue("the list has to stay bounded", carried.size <= Lineage.MAX_LESSONS)
    }

    @Test
    fun `bias is a multiplier around one and is capped`() {
        assertEquals("no lessons means no tilt", 1f, Lineage.bias(emptyList(), LessonKind.EAT_SOONER), 0.0001f)

        val one = listOf(Lesson(LessonKind.EAT_SOONER, "x", 1f, 1))
        assertTrue("a lesson should tilt the score", Lineage.bias(one, LessonKind.EAT_SOONER) > 1f)

        // Twenty generations of the same lesson must not produce a creature that only ever eats.
        val many = List(20) { Lesson(LessonKind.EAT_SOONER, "x", 1f, it) }
        val capped = Lineage.bias(many, LessonKind.EAT_SOONER)
        assertTrue("the bias has to stop growing", capped <= 1f + Lineage.MAX_BIAS * 0.31f)
    }

    @Test
    fun `a lesson only tilts the activity it is about`() {
        val eat = listOf(Lesson(LessonKind.EAT_SOONER, "x", 1f, 1))
        assertTrue(Lineage.biasFor(eat, ActivityKind.EAT) > 1f)
        assertEquals(1f, Lineage.biasFor(eat, ActivityKind.STUDY), 0.0001f)
        assertEquals("idling is never something a line learns", 1f, Lineage.biasFor(eat, ActivityKind.IDLE), 0.0001f)
    }

    @Test
    fun `anything a model returns is clamped before it is kept`() {
        assertNull("an empty lesson is not a lesson", Lineage.sanitise(Lesson(LessonKind.EAT_SOONER, "   ", 0.5f)))
        assertNull("nor is one with no strength", Lineage.sanitise(Lesson(LessonKind.EAT_SOONER, "x", 0f)))

        val huge = Lineage.sanitise(Lesson(LessonKind.EAT_SOONER, "y".repeat(500), 9f))
        assertNotNull(huge)
        assertTrue("text has to fit a row", huge!!.text.length <= Lineage.MAX_LESSON_CHARS)
        assertEquals("strength has to stay in range", 1f, huge.strength, 0.0001f)

        val negative = Lineage.sanitise(Lesson(LessonKind.EAT_SOONER, "z", -0.5f))
        assertNotNull(negative)
        assertTrue("a negative strength must not invert the bias", negative!!.strength > 0f)

        assertNull("NaN must not reach the arithmetic", Lineage.sanitise(Lesson(LessonKind.EAT_SOONER, "z", Float.NaN)))
    }

    @Test
    fun `the next generation actually inherits what the last one learned`() {
        // The whole feature, through the one call the game really makes.
        val parent = Simulation
            .advance(Simulation.newGame("Parent", Species.LEAF, start), start + 120_000, config)
            .state
            .copy(
                stage = LifeStage.ADULT,
                ageSeconds = 8L * 3600L,
                isDead = true,
                deathReason = DeathReason.STARVATION,
                generation = 1,
            )
        val child = Simulation.nextGeneration(parent, "Child", Species.LEAF, start + 200_000)

        assertTrue("the child has to carry something", child.lessons.isNotEmpty())
        assertTrue(
            "and specifically the lesson its parent died for",
            child.lessons.any { it.kind == LessonKind.EAT_SOONER },
        )
    }

    @Test
    fun `an inherited lesson changes what the brain actually chooses`() {
        // Without this the whole system is decoration: lessons stored, shown, and ignored.
        fun peckish(lessons: List<Lesson>): Float {
            val pet = Simulation
                .advance(Simulation.newGame("T", Species.LEAF, start), start + 120_000, config)
                .state
                .copy(
                    stage = LifeStage.ADULT,
                    ageSeconds = config.secondsPerPetDay / 2,
                    stats = Stats(satiety = 55f, happiness = 70f, energy = 80f, hygiene = 90f),
                    inventory = mapOf("meal_bowl" to 4),
                    autonomy = Autonomy.FULL,
                    skills = Skill.entries.toSet(),
                    intellect = 60f,
                    genome = Genome(),
                    lessons = lessons,
                )
            return Brain.considerations(pet, config).first { it.kind == ActivityKind.EAT }.utility
        }

        val plain = peckish(emptyList())
        val taught = peckish(listOf(Lesson(LessonKind.EAT_SOONER, "My parent starved.", 1f, 1)))
        assertTrue(
            "a child of a starved parent has to want to eat earlier than a founder does",
            taught > plain,
        )
    }

    @Test
    fun `a founder is not given a lineage summary`() {
        assertNull(Lineage.summary(emptyList(), 1))
        assertNull("generation one has no line behind it", Lineage.summary(
            listOf(Lesson(LessonKind.EAT_SOONER, "x", 1f, 1)), 1,
        ))
        assertNotNull(Lineage.summary(listOf(Lesson(LessonKind.EAT_SOONER, "x", 1f, 1)), 4))
    }

    @Test
    fun `the brief carries the lessons and never the player`() {
        val pet = Simulation
            .advance(Simulation.newGame("Pip", Species.LEAF, start), start + 120_000, config)
            .state
            .copy(lessons = listOf(Lesson(LessonKind.EAT_SOONER, "My parent starved.", 1f, 1)))
        val brief = PetBrief.of(pet, config)

        assertEquals("Pip", brief.name)
        assertTrue(brief.inheritedLessons.contains("My parent starved."))
        assertTrue("the brief is bounded", brief.recentDiary.size <= PetBrief.DIARY_LINES)
    }
}
