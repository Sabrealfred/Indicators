package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intellect, study sessions and skills. The rules worth guarding are the ones a player would
 * notice being broken: a target that moves while they watch, a skill that arrives before it was
 * earned, a bar that fills to the top and stops the game, or an hour of study that banked nothing.
 */
class LearningTest {

    /** A pet old enough to study, with everything else deliberately neutral. */
    private fun pet(
        intellect: Float = 20f,
        wit: Float = 0.5f,
        curiosity: Float = 0.5f,
        skills: Set<Skill> = emptySet(),
        stage: LifeStage = LifeStage.CHILD,
        stats: Stats = Stats(),
    ): PetState = PetState(
        name = "Pip",
        stage = stage,
        stats = stats,
        genome = Genome(wit = wit, curiosity = curiosity),
        intellect = intellect,
        skills = skills,
    )

    private fun study(state: PetState, ticks: Int, dt: Long = 1L): PetState {
        var s = state
        val sink = mutableListOf<GameEvent>()
        repeat(ticks) { s = Learning.progress(s, dt, sink) }
        return s
    }

    /** Ticks of study needed before [skill] lands, or -1 if it never did within [limit]. */
    private fun ticksToLearn(state: PetState, skill: Skill, dt: Long = 1L, limit: Int = 4000): Int {
        var s = state
        val sink = mutableListOf<GameEvent>()
        for (tick in 1..limit) {
            s = Learning.progress(s, dt, sink)
            if (skill in s.skills) return tick
        }
        return -1
    }

    // ---- the intellect gate -----------------------------------------------------------------

    @Test
    fun `a skill above the intellect gate is never learned however long the pet studies`() {
        val start = pet(intellect = 10f)
        val end = study(start, ticks = 3000)

        assertFalse("teaching needs 78 intellect and this pet never got near it", Skill.TEACH in end.skills)
        assertFalse(Skill.COURT in end.skills)
        assertFalse(Skill.FORAGE in end.skills)
        assertTrue(
            "nothing may be learned that the pet was never clever enough to reach",
            end.skills.all { it.intellectRequired <= end.intellect },
        )
    }

    @Test
    fun `a blocked skill says why it is blocked`() {
        val young = pet(intellect = 10f, stage = LifeStage.BABY)
        assertFalse(Learning.isReachable(young, Skill.SELF_FEED))
        assertTrue(Learning.whyBlocked(young, Skill.SELF_FEED)!!.contains("too young"))

        val dim = pet(intellect = 15f)
        assertFalse(Learning.isReachable(dim, Skill.TEACH))
        assertTrue("the reason has to name the number to aim for", Learning.whyBlocked(dim, Skill.TEACH)!!.contains("78"))

        assertTrue(Learning.isReachable(dim, Skill.SELF_FEED))
        assertNull(Learning.whyBlocked(dim, Skill.SELF_FEED))

        val known = pet(intellect = 40f, skills = setOf(Skill.SELF_FEED))
        assertFalse(Learning.isReachable(known, Skill.SELF_FEED))
        assertTrue(Learning.whyBlocked(known, Skill.SELF_FEED)!!.contains("already knows"))
    }

    @Test
    fun `studying with nothing in reach still raises intellect toward the next gate`() {
        val start = pet(intellect = 5f)
        assertNull("nothing is reachable at 5 intellect", Learning.nextSkill(start))

        val end = study(start, ticks = 350)
        assertTrue("study has to be able to open the first gate", end.intellect > 12f)
        assertEquals("and then settle straight onto what it opened", Skill.SELF_FEED, end.studying)
        assertTrue(end.studySeconds > 0L)
    }

    // ---- study sessions ---------------------------------------------------------------------

    @Test
    fun `a reachable skill is learned after exactly its study time`() {
        val start = pet(intellect = 15f)
        assertEquals(Skill.SELF_FEED, Learning.nextSkill(start))

        val almost = study(start, ticks = Skill.SELF_FEED.studySeconds.toInt() - 1)
        assertFalse("one second short is still short", Skill.SELF_FEED in almost.skills)
        assertEquals(Skill.SELF_FEED.studySeconds - 1L, almost.studySeconds)

        val events = mutableListOf<GameEvent>()
        val done = Learning.progress(almost, 1L, events)
        assertTrue(Skill.SELF_FEED in done.skills)
        assertEquals("the bank is cleared for the next target", 0L, done.studySeconds)
        assertTrue(events.any { it is GameEvent.LearnedSkill && it.skill == Skill.SELF_FEED })
    }

    @Test
    fun `learning a skill is announced once and not again`() {
        val events = mutableListOf<GameEvent>()
        var s = pet(intellect = 15f)
        repeat(1200) { s = Learning.progress(s, 1L, events) }

        val announcements = events.filterIsInstance<GameEvent.LearnedSkill>().count { it.skill == Skill.SELF_FEED }
        assertEquals("a skill is news exactly once", 1, announcements)
        assertTrue(Skill.SELF_FEED in s.skills)
    }

    @Test
    fun `the study target does not change between ticks`() {
        val start = pet(intellect = 50f)
        assertEquals(Learning.nextSkill(start), Learning.nextSkill(start))

        var s = Learning.progress(start, 1L, mutableListOf())
        val target = s.studying
        assertNotNull(target)
        repeat(100) {
            s = Learning.progress(s, 1L, mutableListOf())
            assertEquals("the target may only move when the skill is finished", target, s.studying)
        }
        assertTrue("and the bank only ever goes up while it is the target", s.studySeconds > 100L)
    }

    @Test
    fun `progress toward the current skill reads as a fraction`() {
        val idle = pet(intellect = 5f)
        assertEquals("a pet with nothing in reach is not at 99 per cent", 0f, Learning.studyFraction(idle), 0.001f)

        val half = study(pet(intellect = 15f), ticks = (Skill.SELF_FEED.studySeconds / 2).toInt())
        assertEquals(0.5f, Learning.studyFraction(half), 0.02f)
    }

    // ---- rate ------------------------------------------------------------------------------

    @Test
    fun `reading halves the time the next skill takes`() {
        val plain = ticksToLearn(pet(intellect = 30f), Skill.SELF_FEED)
        val reader = ticksToLearn(pet(intellect = 30f, skills = setOf(Skill.READ)), Skill.SELF_FEED)

        assertEquals(Skill.SELF_FEED.studySeconds.toInt(), plain)
        assertEquals("reading is meant to be worth exactly double", plain / 2, reader)
        assertTrue(Learning.studyRate(pet(skills = setOf(Skill.READ))) > Learning.studyRate(pet()))
    }

    @Test
    fun `a witty pet learns faster than a dull one`() {
        val sharp = ticksToLearn(pet(intellect = 30f, wit = 1f), Skill.SELF_FEED, dt = 10L)
        val dull = ticksToLearn(pet(intellect = 30f, wit = 0f), Skill.SELF_FEED, dt = 10L)
        assertTrue("wit has to be visible in how long a skill takes", sharp < dull)
    }

    @Test
    fun `a sick pet studies badly rather than not at all`() {
        val well = ticksToLearn(pet(intellect = 30f), Skill.SELF_FEED, dt = 10L)
        val ill = ticksToLearn(pet(intellect = 30f).copy(isSick = true), Skill.SELF_FEED, dt = 10L)

        assertTrue("being ill must not stop learning outright", ill > 0)
        assertEquals("illness halves the rate", well * 2, ill)
    }

    @Test
    fun `a starving or exhausted pet does not study at all`() {
        val hungry = pet(intellect = 30f, stats = Stats(satiety = 8f))
        val tired = pet(intellect = 30f, stats = Stats(energy = 8f))

        for (blocked in listOf(hungry, tired)) {
            val events = mutableListOf<GameEvent>()
            var s = blocked
            repeat(600) { s = Learning.progress(s, 1L, events) }
            assertEquals(0L, s.studySeconds)
            assertNull(s.studying)
            assertEquals(blocked.intellect, s.intellect, 0.0001f)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `a baby cannot study`() {
        val baby = pet(intellect = 60f, stage = LifeStage.BABY)
        val events = mutableListOf<GameEvent>()
        var s = baby
        repeat(2000) { s = Learning.progress(s, 1L, events) }

        assertTrue("babies are just babies", s.skills.isEmpty())
        assertEquals(0L, s.studySeconds)
        assertNull(s.studying)
        assertEquals(baby.intellect, s.intellect, 0.0001f)
        assertEquals(baby, Learning.observe(baby, 600L, events))
        assertEquals(baby, Learning.teach(baby, events))
        assertFalse(Learning.canTeach(baby))
        assertTrue(events.isEmpty())
    }

    // ---- intellect ---------------------------------------------------------------------------

    @Test
    fun `intellect saturates instead of climbing to a hundred`() {
        val start = pet(intellect = 5f, skills = Skill.entries.toSet())
        val sink = mutableListOf<GameEvent>()

        var s = start
        repeat(30) { s = Learning.progress(s, 60L, sink) }
        val firstHalfHour = s.intellect - start.intellect

        var long = s
        repeat(1_637) { long = Learning.progress(long, 60L, sink) }
        val before = long.intellect
        repeat(30) { long = Learning.progress(long, 60L, sink) }
        val lastHalfHour = long.intellect - before

        assertTrue("the first half hour has to be worth something", firstHalfHour > 20f)
        assertTrue("and the same study a day later must be worth far less", lastHalfHour < firstHalfHour / 20f)
        assertTrue("a pet left studying forever must never simply max out", long.intellect < Learning.MAX_INTELLECT)
        assertTrue(long.intellect < 99f)
    }

    @Test
    fun `intellect growth is announced sparingly`() {
        val events = mutableListOf<GameEvent>()
        var s = pet(intellect = 5f)
        repeat(600) { s = Learning.progress(s, 1L, events) }

        val grew = events.filterIsInstance<GameEvent.IntellectGrew>()
        assertTrue("something has to be said as the pet gets cleverer", grew.isNotEmpty())
        assertTrue("but not on every tick — that is 600 of them", grew.size < 10)
        assertTrue(grew.all { it.to > it.from })
    }

    @Test
    fun `idle observation drifts slowly and stalls short of the study ceiling`() {
        val start = pet(intellect = 5f)
        val sink = mutableListOf<GameEvent>()

        var idle = start
        var studied = start
        repeat(100) {
            idle = Learning.observe(idle, 60L, sink)
            studied = Learning.progress(studied, 60L, sink)
        }
        assertTrue("living has to teach something", idle.intellect > start.intellect)
        assertTrue("but far less than sitting down with a book", idle.intellect < studied.intellect / 2f)

        var forever = start
        repeat(20_000) { forever = Learning.observe(forever, 60L, sink) }
        assertTrue("drift has to get the pet off the floor", forever.intellect > 35f)
        assertTrue("but never past the passive ceiling", forever.intellect < Learning.OBSERVE_CEILING)
        assertTrue("observation alone can never learn a skill", forever.skills.isEmpty())
    }

    // ---- teaching ----------------------------------------------------------------------------

    @Test
    fun `a lesson is worth more than the same time spent studying alone`() {
        val start = pet(intellect = 15f)
        val taught = Learning.teach(start, mutableListOf())
        val alone = study(start, ticks = Learning.LESSON_SECONDS.toInt())

        assertTrue("a lesson has to beat the pet muddling through", taught.studySeconds > alone.studySeconds)
        assertEquals(Skill.SELF_FEED, taught.studying)
        assertTrue("and it should cost the pet's attention", taught.stats.energy < start.stats.energy)
        assertTrue("and be worth a little closeness", taught.stats.bond > start.stats.bond)
    }

    @Test
    fun `teaching cannot be spammed into instant mastery`() {
        var s = pet(intellect = 15f)
        val events = mutableListOf<GameEvent>()
        repeat(100) { s = Learning.teach(s, events) }

        assertFalse("the pet runs out of attention long before the ladder runs out", Learning.canTeach(s))
        assertTrue(Learning.teachBlockedReason(s)!!.contains("tired"))
        assertTrue("a hundred lessons must not hand over the whole ladder", s.skills.size <= 3)
        assertTrue(s.intellect < 45f)

        val settled = s
        assertEquals("a refused lesson changes nothing at all", settled, Learning.teach(settled, events))
    }

    @Test
    fun `a sleeping pet refuses lessons`() {
        val asleep = pet(intellect = 40f).copy(isSleeping = true)
        val events = mutableListOf<GameEvent>()
        assertEquals(asleep, Learning.teach(asleep, events))
        assertTrue(Learning.teachBlockedReason(asleep)!!.contains("asleep"))
        assertTrue(events.isEmpty())
    }

    // ---- rewards -----------------------------------------------------------------------------

    /**
     * Was: the same assertion driven by [Learning.progress], from before experience was the
     * keeper's alone. The claim it was making — a skill is worth enough XP to carry a level — is
     * still true and still worth pinning; it is the *lesson* that pays it, so the lesson is what
     * drives it now. See `a skill the creature works out alone pays the keeper nothing` for the
     * other half of the contract.
     */
    @Test
    fun `a skill finished by a lesson pays XP through the level system`() {
        val start = pet(intellect = 15f).copy(xp = 55, level = 1)
        assertEquals(60, start.xpForNextLevel)

        val events = mutableListOf<GameEvent>()
        var s = start
        // Four lessons cover SELF_FEED's 240 seconds at three times the rate, well inside the
        // energy a full pet has to spend on them.
        repeat(8) { if (Skill.SELF_FEED !in s.skills) s = Learning.teach(s, events) }

        assertTrue(Skill.SELF_FEED in s.skills)
        assertEquals("a skill has to be able to carry the keeper over a level boundary", 2, s.level)
        assertTrue(events.any { it is GameEvent.LeveledUp && it.level == 2 })
        assertTrue("the level-up must not eat the leftover XP", s.xp in 1..59)
    }

    /**
     * The other half of the contract, and the reason the test above had to change.
     *
     * A skill the creature ground out on its own is a real thing that really happened — it is in
     * the set, it was announced, the intellect it took is banked, and the creature can do the
     * thing from here on. It is simply not a line in the keeper's ledger, for the reason
     * [CareActions.Actor] gives: that ledger is a record of what the *player* did, and nobody sat
     * down with that book.
     */
    @Test
    fun `a skill the creature works out alone pays the keeper nothing`() {
        val start = pet(intellect = 15f).copy(xp = 55, level = 1)
        assertEquals("one skill would carry this pet over a level, if it counted", 60, start.xpForNextLevel)

        val events = mutableListOf<GameEvent>()
        var s = start
        repeat(Skill.SELF_FEED.studySeconds.toInt()) { s = Learning.progress(s, 1L, events) }

        assertTrue("it really did learn it", Skill.SELF_FEED in s.skills)
        assertTrue("and said so", events.any { it is GameEvent.LearnedSkill && it.skill == Skill.SELF_FEED })
        assertTrue("and got cleverer doing it", s.intellect > start.intellect)
        assertEquals("but the keeper studied nothing", start.xp, s.xp)
        assertEquals(1, s.level)
        assertTrue("and no level was announced", events.none { it is GameEvent.LeveledUp })
    }

    /**
     * The narrowness of the split, stated so it cannot quietly widen.
     *
     * Everything short of the experience is paid whoever did the work, because all of it is news
     * about the creature rather than about the player. A version that gated the skill itself, or
     * the announcement, would have hollowed out the autonomous half instead of tidying the ledger.
     */
    @Test
    fun `self-taught and taught land exactly the same skill`() {
        val alone = run {
            var s = pet(intellect = 15f)
            repeat(Skill.SELF_FEED.studySeconds.toInt()) { s = Learning.progress(s, 1L, mutableListOf()) }
            s
        }
        val taught = run {
            var s = pet(intellect = 15f)
            repeat(8) { if (Skill.SELF_FEED !in s.skills) s = Learning.teach(s, mutableListOf()) }
            s
        }

        assertTrue(Skill.SELF_FEED in alone.skills)
        assertTrue(Skill.SELF_FEED in taught.skills)
        assertEquals("both moved on to the same next rung", taught.studying, alone.studying)
        assertEquals("both counted the session", taught.studySessions, alone.studySessions)
        assertTrue("only the ledger differs", taught.xp > alone.xp || taught.level > alone.level)
    }

    /**
     * The change costs a Manual or Assisted keeper nothing at all, and this is why: below FULL
     * autonomy the creature never commits a STUDY of its own, so [Learning.progress] has no
     * production caller and every skill still arrives through a lesson.
     */
    @Test
    fun `below full autonomy the creature is never offered a study of its own`() {
        val base = PetState(
            name = "Pip",
            stage = LifeStage.ADULT,
            genome = Genome(),
            intellect = 40f,
            ageSeconds = 10_800L,
        )
        val study = { s: PetState ->
            Brain.considerations(s, GameConfig.Default).first { it.kind == ActivityKind.STUDY }
        }

        assertNull("a fully autonomous creature reads its book", study(base.copy(autonomy = Autonomy.FULL)).blockedBy)
        assertNotNull(
            "an assisting one was asked for the chores, not for an education of its own",
            study(base.copy(autonomy = Autonomy.ASSIST)).blockedBy,
        )
    }

    @Test
    fun `a finished session opens the next one and counts it`() {
        val start = pet(intellect = 40f)
        assertEquals(0, start.studySessions)

        var s = Learning.progress(start, 1L, mutableListOf())
        assertEquals("taking up a topic is one session", 1, s.studySessions)

        val events = mutableListOf<GameEvent>()
        repeat(Skill.SELF_FEED.studySeconds.toInt() - 1) { s = Learning.progress(s, 1L, events) }
        assertTrue(Skill.SELF_FEED in s.skills)
        assertEquals("and the next target is picked straight away", Skill.TIDY_UP, s.studying)
        assertEquals(2, s.studySessions)
        assertEquals(0L, s.studySeconds)
    }

    @Test
    fun `a pet that knows everything in reach simply gets cleverer`() {
        val start = pet(intellect = 50f, skills = Skill.entries.filter { it.intellectRequired <= 50f }.toSet())
        assertNull(Learning.nextSkill(start))

        val events = mutableListOf<GameEvent>()
        var s = start
        repeat(200) { s = Learning.progress(s, 1L, events) }

        assertTrue("study is still worth something with the ladder out of reach", s.intellect > start.intellect)
        assertTrue("but there is nothing to bank it against", s.intellect < Skill.READ.intellectRequired)
        assertNull(s.studying)
        assertEquals(0L, s.studySeconds)
        assertTrue(events.none { it is GameEvent.LearnedSkill })
    }
}
