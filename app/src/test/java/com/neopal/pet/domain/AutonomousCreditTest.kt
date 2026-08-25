package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Who gets the credit when the creature does something nobody asked for.
 *
 * The autonomous half runs the player's own care actions, and every one of those pays the *keeper*
 * — experience, a level, a badge and its coins, and a tick on today's mission. Left alone that
 * makes a switch in Settings the fastest way to level up, and it makes "Serve 50 meals" a badge
 * you earn by not being there. Worse, it is a lie about what happened: the player did not serve
 * those meals.
 *
 * The rule this file pins down is the same one [SoloPlay] already states for the minigames — the
 * creature playing alone posts no score — extended to the rest of what it can do for itself. The
 * world moves (it is fed, dosed, tidied, and the tin really empties); the keeper's ledger does not.
 *
 * Skills were the last thing left outside the rule and are now inside it: a skill the creature
 * worked out unasked still lands, still gets announced, still changes what the creature can do —
 * and pays the keeper nothing. A lesson still pays in full. See [Learning] for the argument.
 */
class AutonomousCreditTest {

    private val config = GameConfig.Default

    /** Noon, so nothing here is decided by the dark. */
    private val noon = 10_800L

    private fun pet(
        skills: Set<Skill> = emptySet(),
        stats: Stats = Stats(),
        inventory: Map<String, Int> = mapOf("meal_bowl" to 2),
        poops: Int = 0,
        autonomy: Autonomy = Autonomy.FULL,
    ) = PetState(
        name = "Test",
        species = Species.LEAF,
        stage = LifeStage.ADULT,
        stats = stats,
        inventory = inventory,
        poops = poops,
        ageSeconds = noon,
        autonomy = autonomy,
        skills = skills,
        genome = Genome(),
    )

    private fun hungry() = pet(
        skills = setOf(Skill.SELF_FEED),
        stats = Stats(satiety = 20f, energy = 12f, hygiene = 95f),
        inventory = mapOf("meal_bowl" to 2),
    )

    // ------------------------------------------------------------------ the keeper's ledger

    @Test
    fun `a creature that feeds itself earns the keeper no experience`() {
        val before = hungry()
        val after = Brain.tick(before, config, 1L, Random(3), mutableListOf())

        assertEquals("the meal happened", 1, after.inventory["meal_bowl"])
        assertEquals("but it is not the keeper's experience", before.xp, after.xp)
        assertEquals(before.level, after.level)
    }

    @Test
    fun `a creature that feeds itself does not advance the keeper's meal record`() {
        val before = hungry()
        val after = Brain.tick(before, config, 1L, Random(3), mutableListOf())

        assertTrue("it really ate", after.stats.satiety > before.stats.satiety)
        assertEquals("nobody served that meal", before.mealsEaten, after.mealsEaten)
    }

    @Test
    fun `a creature that feeds itself does not tick today's feeding mission`() {
        val start = hungry().let { it.copy(dayLedger = DayLedger.of(it, it.ageInPetDays(config))) }
        val meal = Missions.pool.first { it.id == "m_feed" }
        val before = meal.progressOf(start, start.dayLedger)
        val after = Brain.tick(start, config, 1L, Random(3), mutableListOf())

        assertEquals("a mission is a thing the player did", before, meal.progressOf(after, after.dayLedger))
    }

    @Test
    fun `a creature that tidies up does not earn the keeper's tidying badge`() {
        // One mess short of the badge, so a single self-scoop would be enough to unlock it.
        val messy = pet(autonomy = Autonomy.ASSIST, skills = setOf(Skill.TIDY_UP), poops = 3)
            .copy(cleanups = 24)
        val events = mutableListOf<GameEvent>()
        val after = Brain.tick(messy, config, 1L, Random(2), events)

        assertEquals("the mess is really gone", 2, after.poops)
        assertEquals("but the keeper did not clean it", 24, after.cleanups)
        assertFalse("tidy", "tidy" in after.unlockedAchievements)
        assertTrue("and no badge was announced", events.none { it is GameEvent.Unlocked })
        assertEquals("nor were its coins paid", messy.coins, after.coins)
    }

    @Test
    fun `a creature that doses itself does not advance the keeper's cure record`() {
        val ill = pet(
            skills = setOf(Skill.MEDICATE),
            stats = Stats(health = 40f, energy = 12f, hygiene = 95f),
            inventory = mapOf("medicine" to 1),
        ).copy(isSick = true)
        val after = Brain.tick(ill, config, 1L, Random(11), mutableListOf())

        assertFalse("it really got better", after.isSick)
        assertEquals("the dose really left the cupboard", 0, after.inventory["medicine"] ?: 0)
        assertEquals("but the keeper nursed nobody", ill.medicineDoses, after.medicineDoses)
        assertEquals(ill.xp, after.xp)
    }

    /**
     * The same rule, reached the long way round: a skill that lands inside [Brain]'s own study.
     *
     * [Learning.learn] is the only place a skill arrives, and it is reached down two paths — a
     * lesson the player chose, and a study the creature committed to itself at FULL autonomy. This
     * is the second one, driven end to end rather than by calling [Learning] directly, because the
     * whole claim is about who was in the room.
     */
    @Test
    fun `a skill the creature finishes on its own earns the keeper no experience`() {
        // One second short of feeding itself, with the intellect the rung needs, so exactly one
        // tick of the brain's own study is what lands it.
        val nearly = pet(stats = Stats(energy = 80f, satiety = 80f, hygiene = 95f, happiness = 80f))
            .copy(
                intellect = 30f,
                studying = Skill.SELF_FEED,
                studySeconds = Skill.SELF_FEED.studySeconds - 1,
                xp = 55,
                level = 1,
            )
        val events = mutableListOf<GameEvent>()
        val studying = Brain.adopt(nearly, ActivityKind.STUDY, "I fancied my book.", config, Random(5), events)
            ?: error("a fully autonomous adult with energy to spare can always sit down with a book")
        val after = Brain.tick(studying, config, 1L, Random(5), events)

        assertTrue("it really worked it out", Skill.SELF_FEED in after.skills)
        assertTrue("and said so", events.any { it is GameEvent.LearnedSkill })
        assertEquals("but nobody taught it", nearly.xp, after.xp)
        assertEquals(1, after.level)
        assertTrue(events.none { it is GameEvent.LeveledUp })
    }

    /** And the lesson still pays, which is the half of the split that must not be over-applied. */
    @Test
    fun `a skill finished by the player's own lesson still pays the keeper`() {
        val nearly = pet(stats = Stats(energy = 80f, satiety = 80f, hygiene = 95f, happiness = 80f))
            .copy(intellect = 30f, studying = Skill.SELF_FEED, studySeconds = Skill.SELF_FEED.studySeconds - 1)
        val events = mutableListOf<GameEvent>()
        val after = Learning.teach(nearly, events)

        assertTrue(Skill.SELF_FEED in after.skills)
        assertTrue("the keeper sat down with it, so the keeper is paid", after.xp > nearly.xp)
    }

    // ------------------------------------------------------------------ the creature's own ledger

    @Test
    fun `what it did unasked is still counted, in its own column`() {
        val before = hungry()
        val after = Brain.tick(before, config, 1L, Random(3), mutableListOf())
        assertEquals("the Mind screen's own tally is the point of the feature", 1, after.selfCareActions)
        assertTrue("and it still says why it did it", after.decisions.isNotEmpty())
    }

    // ------------------------------------------------------------------ the keeper still gets paid

    @Test
    fun `a meal served by hand still pays the keeper everything it used to`() {
        val before = hungry()
        val result = CareActions.feed(before, "meal_bowl")

        assertTrue("the keeper's experience is untouched by this change", result.state.xp > before.xp)
        assertEquals(before.mealsEaten + 1, result.state.mealsEaten)
    }

    @Test
    fun `a room cleaned by hand still unlocks the keeper's badge`() {
        val messy = pet(poops = 3).copy(cleanups = 24)
        val result = CareActions.cleanRoom(messy)

        assertEquals(25, result.state.cleanups)
        assertTrue("tidy", "tidy" in result.state.unlockedAchievements)
        assertTrue(result.events.any { it is GameEvent.Unlocked })
    }
}
