package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether to interrupt somebody.
 *
 * These exist because every failure in this file is silent. A dedupe rule that never opens, a
 * quiet-hours window that swallows the morning, a budget that spends itself on the wrong thing —
 * none of them crash, none of them log, and all of them look exactly like a well-behaved notifier
 * from the outside, which is to say like nothing at all. The only way to tell a notifier that is
 * being tactful from one that is broken is to ask it, in a test, what it decided and why.
 *
 * The long ones at the end run a whole absence a quarter of an hour at a time, because the
 * failures worth catching here are not single calls: they are what twelve hours of calls add up
 * to in somebody's notification shade.
 */
class NudgesTest {

    // ---------------------------------------------------------------- fixtures

    private val hour = 3_600_000L
    private val quarter = 15L * 60L * 1000L

    /** 09:00 on some Tuesday. Wall clock and local minute are kept in step by [at]. */
    private val start = 1_700_000_000_000L

    private fun minuteOfDay(elapsedMillis: Long, fromMinute: Int = 9 * 60): Int =
        ((fromMinute + (elapsedMillis / 60_000L)) % 1440L).toInt()

    private fun pet(
        sick: Boolean = false,
        health: Float = 100f,
        satiety: Float = 70f,
        happiness: Float = 70f,
        hygiene: Float = 90f,
        dead: Boolean = false,
        reason: DeathReason? = null,
        stage: LifeStage = LifeStage.CHILD,
        generation: Int = 1,
        poops: Int = 0,
        medicine: Int = 1,
        ageSeconds: Long = 20_000L,
        stageStartedSeconds: Long = 19_000L,
        sickSinceSeconds: Long = 0L,
    ) = PetState(
        name = "Pip",
        stage = stage,
        generation = generation,
        stats = Stats(satiety = satiety, happiness = happiness, hygiene = hygiene, health = health),
        lastTickMillis = start,
        ageSeconds = ageSeconds,
        stageStartedSeconds = stageStartedSeconds,
        poops = poops,
        isSick = sick,
        sickSinceSeconds = sickSinceSeconds,
        isDead = dead,
        deathReason = reason,
        deathAtSeconds = if (dead) ageSeconds else 0L,
        inventory = mapOf("medicine" to medicine),
    )

    /** An input at a wall clock, with the player's local time kept honest against it. */
    private fun at(
        elapsedMillis: Long,
        state: PetState,
        ledger: NudgeLedger = NudgeLedger(),
        events: List<GameEvent> = emptyList(),
        fromMinute: Int = 9 * 60,
        settings: NudgeSettings = NudgeSettings(),
        permission: NudgePermission = NudgePermission.GRANTED,
        enabled: Boolean = true,
        foreground: Boolean = false,
    ) = NudgeInput(
        state = state,
        events = events,
        nowMillis = start + elapsedMillis,
        localMinuteOfDay = minuteOfDay(elapsedMillis, fromMinute),
        ledger = ledger,
        permission = permission,
        enabled = enabled,
        settings = settings,
        appInForeground = foreground,
    )

    // ---------------------------------------------------------------- the rule

    @Test
    fun `a hungry filthy miserable creature in no danger says nothing at all`() {
        // The whole rule in one case. Every meter that used to fire a reminder is on the floor,
        // and none of it is worth a notification: it will be exactly as true when the player next
        // opens the app, and Simulation's health floor has already promised it cannot be fatal.
        val wretched = pet(satiety = 3f, happiness = 2f, hygiene = 1f, poops = 5, health = 40f)
        val outcome = Nudges.decide(at(0, wretched))
        assertNull("a low meter is state, not news", outcome.post)
        assertEquals(NudgeSilence.NOTHING_TO_SAY, outcome.silence)
    }

    @Test
    fun `illness is the one need-state that is worth an interruption`() {
        val outcome = Nudges.decide(at(0, pet(sick = true)))
        assertEquals(NudgeKind.ILLNESS, outcome.post?.kind)
        assertEquals(NudgeChannel.URGENT, outcome.post?.channel)
    }

    @Test
    fun `an illness far enough along to be fatal outranks the illness itself`() {
        val outcome = Nudges.decide(at(0, pet(sick = true, health = 12f)))
        assertEquals(NudgeKind.FADING, outcome.post?.kind)
    }

    @Test
    fun `a death outranks everything true on the same tick`() {
        val gone = pet(sick = true, health = 0f, dead = true, reason = DeathReason.ILLNESS)
        val outcome = Nudges.decide(at(0, gone, events = listOf(GameEvent.Died(DeathReason.ILLNESS))))
        assertEquals(NudgeKind.DEPARTED, outcome.post?.kind)
    }

    @Test
    fun `a death does not make a sound`() {
        // The most important thing that will ever happen, and nothing can be done about it. Those
        // two facts together describe something that belongs on the quiet channel.
        assertEquals(NudgeChannel.LIFE, NudgeKind.DEPARTED.channel)
        assertEquals(NudgeChannel.LIFE, NudgeKind.EVOLVED.channel)
        assertEquals(NudgeChannel.URGENT, NudgeKind.ILLNESS.channel)
        assertEquals(NudgeChannel.URGENT, NudgeKind.FADING.channel)
    }

    @Test
    fun `turning off come-and-look does not turn off the death of the creature`() {
        val quietMilestones = NudgeSettings(milestones = false)
        val grew = Nudges.decide(
            at(0, pet(), events = listOf(GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)), settings = quietMilestones),
        )
        assertEquals(NudgeSilence.MILESTONES_MUTED, grew.silence)

        val gone = pet(dead = true, reason = DeathReason.OLD_AGE)
        assertEquals(NudgeKind.DEPARTED, Nudges.decide(at(0, gone, settings = quietMilestones)).post?.kind)

        val ill = Nudges.decide(at(0, pet(sick = true), settings = quietMilestones))
        assertEquals(NudgeKind.ILLNESS, ill.post?.kind)
    }

    // ---------------------------------------------------------------- never twice

    @Test
    fun `the same illness across forty ticks is one notification`() {
        // A creature ill at three o'clock and still ill at four is one event, not two.
        var ledger = NudgeLedger()
        var posts = 0
        repeat(40) { i ->
            val outcome = Nudges.decide(at(i * quarter, pet(sick = true), ledger))
            if (outcome.post != null) posts++
            ledger = outcome.ledger
        }
        assertEquals("ten hours of the same illness is one piece of news", 1, posts)
    }

    @Test
    fun `opening and closing the app cannot make one illness newsworthy twice`() {
        // The obvious way to build this — reset the dedupe when the player comes back — is the
        // one that a player toggling the app in and out of the foreground can ring like a bell.
        var ledger = NudgeLedger()
        var posts = 0
        repeat(20) { i ->
            val outcome = Nudges.decide(at(i * quarter, pet(sick = true), ledger))
            if (outcome.post != null) posts++
            // The player looks in, does nothing about it, and leaves again.
            ledger = Nudges.markOpened(outcome.ledger)
        }
        assertEquals(1, posts)
    }

    @Test
    fun `the episode id carries no timestamp, so the same illness dated differently is the same`() {
        // A worker that re-simulates an absence can legitimately place the same illness at a
        // different second on every run. If the second were part of the identity, every one of
        // those runs would look like a fresh illness and the player would be told all day.
        val early = Nudges.candidates(at(0, pet(sick = true, sickSinceSeconds = 1_000L))).first()
        val late = Nudges.candidates(at(0, pet(sick = true, sickSinceSeconds = 9_999L))).first()
        assertEquals(early.episodeId, late.episodeId)
        assertFalse("no digits from a clock belong in an id", early.episodeId.contains("9999"))
    }

    @Test
    fun `a second illness after a real recovery is worth saying`() {
        val first = Nudges.decide(at(0, pet(sick = true)))
        assertEquals(NudgeKind.ILLNESS, first.post?.kind)

        // Cured. The pinned claim stops being true and comes down, and the episode is re-armed.
        val well = Nudges.decide(at(hour, pet(sick = false), first.ledger))
        assertTrue("a notification that has stopped being true is withdrawn", well.withdraw)
        assertNull(well.ledger.liveEpisodeId)

        // Ill again, well past the cooldown. This is genuinely a new episode.
        val again = Nudges.decide(at(4 * hour, pet(sick = true), well.ledger))
        assertEquals(NudgeKind.ILLNESS, again.post?.kind)
    }

    @Test
    fun `a recovery inside the cooldown does not buy a second announcement`() {
        // The re-simulating worker again: ill, then well, then ill inside the hour is three
        // observations of one illness. The wall clock is the layer that catches this one.
        val first = Nudges.decide(at(0, pet(sick = true)))
        val well = Nudges.decide(at(20 * 60_000L, pet(sick = false), first.ledger))
        val again = Nudges.decide(at(40 * 60_000L, pet(sick = true), well.ledger))
        assertNull(again.post)
        assertEquals(NudgeSilence.TOO_SOON, again.silence)
    }

    @Test
    fun `one evolution is announced once however many ticks see it`() {
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        var ledger = NudgeLedger()
        var posts = 0
        repeat(12) { i ->
            // Freshly evolved on every tick, which is exactly what a worker that throws its
            // simulation away reports: the same event, over and over.
            val state = pet(ageSeconds = 20_000L + i * 900L, stageStartedSeconds = 20_000L + i * 900L)
            val outcome = Nudges.decide(at(i * quarter, state, ledger, events = listOf(grew)))
            if (outcome.post != null) posts++
            ledger = Nudges.markOpened(outcome.ledger)
        }
        assertEquals(1, posts)
    }

    // ---------------------------------------------------------------- quiet hours

    @Test
    fun `a night stays silent and the morning still gets told`() {
        // 22:00, ill, and it stays ill until 09:00. Nobody is woken, and nothing is lost.
        var ledger = NudgeLedger()
        val nightPosts = mutableListOf<Pair<Int, NudgeKind>>()
        // 22:00 to 08:00 is forty quarter-hours.
        repeat(40) { i ->
            val outcome = Nudges.decide(at(i * quarter, pet(sick = true), ledger, fromMinute = 22 * 60))
            outcome.post?.let { nightPosts += minuteOfDay(i * quarter, 22 * 60) to it.kind }
            ledger = outcome.ledger
        }
        assertTrue("nothing may be said between 22:00 and 08:00, ever: $nightPosts", nightPosts.isEmpty())

        // 08:05. The illness was never actually said, so it is still owed.
        val morning = Nudges.decide(at(41 * quarter, pet(sick = true), ledger, fromMinute = 22 * 60))
        assertEquals(NudgeKind.ILLNESS, morning.post?.kind)
    }

    @Test
    fun `the night does not spend the budget it silenced`() {
        var ledger = NudgeLedger()
        repeat(40) { i ->
            ledger = Nudges.decide(at(i * quarter, pet(sick = true), ledger, fromMinute = 22 * 60)).ledger
        }
        assertEquals(0, ledger.deliveredSinceOpened)
        assertTrue("a nudge held back by the night was never said", ledger.said.isEmpty())
    }

    @Test
    fun `not even a death gets to wake somebody at four in the morning`() {
        val gone = pet(dead = true, reason = DeathReason.ILLNESS)
        val outcome = Nudges.decide(at(0, gone, fromMinute = 4 * 60))
        assertEquals(NudgeSilence.QUIET_HOURS, outcome.silence)
        // And it is still there to be said once the window closes.
        assertEquals(NudgeKind.DEPARTED, Nudges.decide(at(0, gone, fromMinute = 9 * 60)).post?.kind)
    }

    @Test
    fun `quiet hours are the player's clock and never the creature's`() {
        // The pet's day is six real hours, so its night lands on a different wall-clock hour four
        // times a day. Two creatures at opposite ends of their own day, at the same wall time,
        // must be treated identically.
        val config = GameConfig.Default
        val dayPet = pet(sick = true, ageSeconds = 3 * 3600L) // pet noon
        val nightPet = pet(sick = true, ageSeconds = 21_000L) // deep in the pet's night
        assertTrue(Simulation.isNight(nightPet, config))
        assertFalse(Simulation.isNight(dayPet, config))

        val a = Nudges.decide(at(0, dayPet, fromMinute = 14 * 60))
        val b = Nudges.decide(at(0, nightPet, fromMinute = 14 * 60))
        assertEquals(a.post?.kind, b.post?.kind)
        assertNotNull("two in the afternoon is two in the afternoon", a.post)
    }

    @Test
    fun `the quiet window wraps past midnight`() {
        val s = NudgeSettings(quietFromHour = 22, quietToHour = 8)
        assertTrue(s.isQuiet(23 * 60))
        assertTrue(s.isQuiet(0))
        assertTrue(s.isQuiet(7 * 60 + 59))
        assertFalse(s.isQuiet(8 * 60))
        assertFalse(s.isQuiet(21 * 60 + 59))
        assertTrue("the opening minute is inside", s.isQuiet(22 * 60))
    }

    @Test
    fun `a window that does not wrap works the same way`() {
        val s = NudgeSettings(quietFromHour = 1, quietToHour = 6)
        assertTrue(s.isQuiet(3 * 60))
        assertFalse(s.isQuiet(0))
        assertFalse(s.isQuiet(6 * 60))
    }

    @Test
    fun `a nonsense window silences nothing rather than everything`() {
        // Of the two ways to read a corrupt setting, only one can disable the whole feature
        // without anybody noticing.
        assertFalse(NudgeSettings(quietFromHour = 3, quietToHour = 3).isQuiet(3 * 60))
        assertFalse(NudgeSettings(quietHours = false).isQuiet(4 * 60))
        // And an out-of-range local minute is folded back in, not trusted.
        val s = NudgeSettings()
        assertEquals(s.isQuiet(23 * 60), s.isQuiet(23 * 60 + 1440))
        assertEquals(s.isQuiet(2 * 60), s.isQuiet(2 * 60 - 1440))
    }

    // ---------------------------------------------------------------- staleness

    @Test
    fun `an evolution four hours old is not news any more`() {
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        val old = pet(ageSeconds = 40_000L, stageStartedSeconds = 40_000L - 4 * 3600L)
        val outcome = Nudges.decide(at(0, old, events = listOf(grew)))
        assertEquals(NudgeSilence.STALE, outcome.silence)
    }

    @Test
    fun `but an illness is about now, so a late worker still gets to say it`() {
        // Same four-hour-late tick. The milestone is dropped and the jeopardy is not, because one
        // is a claim about a moment that has passed and the other is a claim about the present.
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        val old = pet(sick = true, ageSeconds = 40_000L, stageStartedSeconds = 40_000L - 4 * 3600L)
        val outcome = Nudges.decide(at(0, old, events = listOf(grew)))
        assertEquals(NudgeKind.ILLNESS, outcome.post?.kind)
    }

    @Test
    fun `a nudge about a creature that has since died is suppressed`() {
        // The illness is pinned in the shade. The creature dies. What is on screen is now a lie
        // with a button on it that would open the app to feed a corpse.
        val ill = Nudges.decide(at(0, pet(sick = true)))
        assertEquals("g1:illness", ill.ledger.liveEpisodeId)

        val gone = pet(sick = true, health = 0f, dead = true, reason = DeathReason.ILLNESS)
        val after = Nudges.decide(at(4 * hour, gone, ill.ledger))
        assertEquals(NudgeKind.DEPARTED, after.post?.kind)
        assertTrue("the illness must not still be offering medicine", after.post?.actions.isNullOrEmpty())

        // And nothing about a dead creature is ever a care nudge again.
        val later = Nudges.decide(at(9 * hour, gone, after.ledger))
        assertNull(later.post)
        assertTrue(Nudges.candidates(at(9 * hour, gone)).none { it.kind == NudgeKind.ILLNESS })
    }

    @Test
    fun `a pinned illness comes down by itself when the creature is well`() {
        val ill = Nudges.decide(at(0, pet(sick = true)))
        val well = Nudges.decide(at(hour, pet(sick = false), ill.ledger))
        assertTrue(well.withdraw)
        assertNull(well.post)
        assertEquals(NudgeSilence.NOTHING_TO_SAY, well.silence)
    }

    @Test
    fun `a milestone stays pinned, because it cannot stop having happened`() {
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        val first = Nudges.decide(at(0, pet(), events = listOf(grew)))
        assertEquals(NudgeKind.EVOLVED, first.post?.kind)
        val next = Nudges.decide(at(quarter, pet(), first.ledger))
        assertFalse("nothing withdraws an evolution", next.withdraw)
        assertEquals("g1:grew@TEEN", next.ledger.liveEpisodeId)
    }

    // ---------------------------------------------------------------- the queue of twelve

    @Test
    fun `twelve hours of a creature dying is at most three notifications`() {
        // A player who never opens the app. The absence runs from 09:00 so the quiet window plays
        // no part: this is the budget doing the work and nothing else.
        var ledger = NudgeLedger()
        val posted = mutableListOf<NudgeKind>()
        repeat(48) { i ->
            val elapsed = i * quarter
            // Well, then ill, then failing, then gone: the ordinary shape of a bad absence.
            val state = when {
                i < 8 -> pet(satiety = 40f - i)
                i < 20 -> pet(sick = true, health = 80f - i * 2f)
                i < 32 -> pet(sick = true, health = 26f - (i - 20))
                else -> pet(sick = true, health = 0f, dead = true, reason = DeathReason.ILLNESS)
            }
            val outcome = Nudges.decide(at(elapsed, state, ledger))
            outcome.post?.let { posted += it.kind }
            ledger = outcome.ledger
        }
        assertEquals("the whole story, and not one word more", listOf(NudgeKind.ILLNESS, NudgeKind.FADING, NudgeKind.DEPARTED), posted)
        assertTrue(posted.size <= Nudges.MAX_UNANSWERED)
    }

    @Test
    fun `a creature that keeps relapsing still cannot fill the shade`() {
        // The nastier version of the same absence: an illness that comes and goes all day, with a
        // milestone thrown in. Each relapse is honestly a new episode, so the episode id will not
        // save anybody here — this is the budget and the cooldown or nothing.
        var ledger = NudgeLedger()
        var posts = 0
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        repeat(48) { i ->
            val ill = (i / 4) % 2 == 0
            val state = pet(sick = ill, health = 70f)
            val events = if (i == 30) listOf(grew) else emptyList()
            val outcome = Nudges.decide(at(i * quarter, state, ledger, events = events))
            if (outcome.post != null) posts++
            ledger = outcome.ledger
        }
        assertTrue("twelve hours of relapses is not twelve notifications: was $posts", posts <= Nudges.MAX_UNANSWERED)
    }

    @Test
    fun `and the budget really does stop a fourth`() {
        // Force three deliveries, then offer a fourth thing worth saying.
        val ledger = NudgeLedger(deliveredSinceOpened = Nudges.MAX_UNANSWERED)
        val outcome = Nudges.decide(at(0, pet(sick = true), ledger))
        assertEquals(NudgeSilence.BUDGET_SPENT, outcome.silence)
    }

    @Test
    fun `coming back to the app is what buys the next notification`() {
        val spent = NudgeLedger(deliveredSinceOpened = Nudges.MAX_UNANSWERED, liveEpisodeId = "g1:illness", liveKind = NudgeKind.ILLNESS)
        val opened = Nudges.markOpened(spent)
        assertEquals(0, opened.deliveredSinceOpened)
        assertNull("what the player has now seen is not still pinned", opened.liveEpisodeId)
    }

    @Test
    fun `something already unanswered may only be spoken over by something worse`() {
        val ill = Nudges.decide(at(0, pet(sick = true)))
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        val cheerful = Nudges.decide(at(4 * hour, pet(sick = true), ill.ledger, events = listOf(grew)))
        assertNull("come and look does not get to speak over still ill", cheerful.post)

        // Worse, though, gets through, and it steps over the half-hour floor to do it.
        val worse = Nudges.decide(at(20 * 60_000L, pet(sick = true, health = 8f), ill.ledger))
        assertEquals(NudgeKind.FADING, worse.post?.kind)
    }

    @Test
    fun `two pieces of good news are one piece of good news`() {
        // A child hatched, and then the parent grew. Both are worth a look and neither is worth
        // two entries in the shade, so the second waits for the player to answer the first.
        val child = Pal(
            id = "pal_child_egg_1",
            name = "Momo",
            species = Species.LEAF,
            genome = Genome(),
            personality = Personality.CALM,
            relation = Relation.OFFSPRING,
            metAtSeconds = 19_900L,
        )
        val parent = pet().copy(pals = listOf(child))
        val born = Nudges.decide(at(0, parent, events = listOf(GameEvent.ChildHatched(child))))
        assertEquals(NudgeKind.CHILD_HATCHED, born.post?.kind)

        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        val after = Nudges.decide(at(4 * hour, parent, born.ledger, events = listOf(grew)))
        assertEquals(NudgeSilence.ALREADY_LOUDER, after.silence)

        // Once the player has looked in, it is news again.
        val seen = Nudges.decide(at(5 * hour, parent, Nudges.markOpened(born.ledger), events = listOf(grew)))
        assertEquals(NudgeKind.EVOLVED, seen.post?.kind)
    }

    // ---------------------------------------------------------------- degrading honestly

    @Test
    fun `a denied permission writes nothing down, so granting it later still tells the truth`() {
        // The trap: mark the illness as said while nothing could be delivered, and the player who
        // grants the permission an hour later gets silence about a creature that is dying.
        var ledger = NudgeLedger()
        repeat(8) { i ->
            val outcome = Nudges.decide(at(i * quarter, pet(sick = true), ledger, permission = NudgePermission.DENIED))
            assertEquals(NudgeSilence.NO_PERMISSION, outcome.silence)
            ledger = outcome.ledger
        }
        assertTrue(ledger.said.isEmpty())
        val granted = Nudges.decide(at(8 * quarter, pet(sick = true), ledger))
        assertEquals(NudgeKind.ILLNESS, granted.post?.kind)
    }

    @Test
    fun `below the version that asks, nothing is asked and everything works`() {
        val outcome = Nudges.decide(at(0, pet(sick = true), permission = NudgePermission.NOT_REQUIRED))
        assertEquals(NudgeKind.ILLNESS, outcome.post?.kind)
    }

    @Test
    fun `switching the whole thing off clears the shade as well as silencing it`() {
        val ill = Nudges.decide(at(0, pet(sick = true)))
        val off = Nudges.decide(at(hour, pet(sick = true), ill.ledger, enabled = false))
        assertEquals(NudgeSilence.MUTED, off.silence)
        assertTrue("a setting that leaves its last notification pinned did nothing", off.withdraw)
        assertNull(off.ledger.liveEpisodeId)
    }

    @Test
    fun `nothing is said about a creature the player is looking at`() {
        val outcome = Nudges.decide(at(0, pet(sick = true), foreground = true))
        assertEquals(NudgeSilence.PLAYER_IS_HERE, outcome.silence)
        assertTrue(outcome.ledger.said.isEmpty())
    }

    @Test
    fun `a save that has never ticked has nothing to report`() {
        val fresh = pet(sick = true).copy(lastTickMillis = 0L)
        assertEquals(NudgeSilence.NOTHING_TO_SAY, Nudges.decide(at(0, fresh)).silence)
    }

    // ---------------------------------------------------------------- actionable, honestly

    @Test
    fun `the medicine button is only offered when there is medicine`() {
        val stocked = Nudges.decide(at(0, pet(sick = true, medicine = 2))).post
        assertEquals(listOf(NudgeAction.MEDICINE), stocked?.actions)

        val empty = Nudges.decide(at(0, pet(sick = true, medicine = 0))).post
        assertTrue("a button that opens the app to say the cupboard is bare is a lie", empty!!.actions.isEmpty())
        assertNotNull("but the creature is still ill and still worth saying", empty.body)
    }

    @Test
    fun `nothing that cannot be acted on carries a button`() {
        val gone = Nudges.decide(at(0, pet(dead = true, reason = DeathReason.NEGLECT))).post
        assertTrue(gone!!.actions.isEmpty())
        val grew = GameEvent.Evolved(LifeStage.TEEN, LifeStage.ADULT, EvolutionBranch.SCHOLAR)
        assertTrue(Nudges.decide(at(0, pet(), events = listOf(grew))).post!!.actions.isEmpty())
    }

    // ---------------------------------------------------------------- the voice

    @Test
    fun `every line sounds like the creature and not like an app`() {
        val everything = buildList {
            add(Nudges.decide(at(0, pet(sick = true))).post!!)
            add(Nudges.decide(at(0, pet(sick = true, health = 5f))).post!!)
            DeathReason.entries.forEach { reason ->
                add(Nudges.decide(at(0, pet(dead = true, reason = reason))).post!!)
            }
            listOf(LifeStage.CHILD, LifeStage.TEEN, LifeStage.ADULT, LifeStage.ELDER).forEach { to ->
                val e = GameEvent.Evolved(LifeStage.BABY, to, EvolutionBranch.FERAL)
                add(Nudges.decide(at(0, pet(), events = listOf(e))).post!!)
            }
            add(Nudges.decide(at(0, pet(), events = listOf(GameEvent.Hatched))).post!!)
        }
        everything.forEach { nudge ->
            assertFalse("no shouting: ${nudge.body}", nudge.body.contains("!"))
            assertFalse("no app-speak: ${nudge.body}", nudge.body.lowercase().contains("your pet"))
            assertFalse("no app-speak: ${nudge.body}", nudge.body.lowercase().contains("needs attention"))
            assertFalse("no app-speak: ${nudge.body}", nudge.body.lowercase().contains("tap to"))
            assertTrue("a line ends: ${nudge.body}", nudge.body.trim().endsWith("."))
            assertTrue("the title is who it is from", nudge.title == "Pip")
            // Punctuation from the diary's own register is welcome; pictures are not.
            assertTrue("no emoji anywhere", nudge.body.none { it.isSurrogate() || it.code in 0x2600..0x27BF || it.code == 0xFE0F })
        }
    }

    @Test
    fun `one episode always says the same thing`() {
        // A notification that rewords itself when it is re-posted reads as a second notification.
        val a = Nudges.candidates(at(0, pet(sick = true))).first { it.kind == NudgeKind.ILLNESS }
        val b = Nudges.candidates(at(9 * hour, pet(sick = true, health = 40f))).first { it.kind == NudgeKind.ILLNESS }
        assertEquals(a.body, b.body)
    }

    @Test
    fun `a death names what happened rather than talking around it`() {
        val reasons = DeathReason.entries.map { reason ->
            Nudges.decide(at(0, pet(dead = true, reason = reason))).post!!.body
        }
        assertEquals("each ending has its own line", reasons.size, reasons.toSet().size)
        reasons.forEach { assertTrue(it.startsWith("Pip is gone")) }
    }

    // ---------------------------------------------------------------- eggs and children

    @Test
    fun `an egg is only mentioned when it is genuinely nearly time`() {
        val slow = GameConfig.Default.copy(lifeSpeed = 0.05f) // a fifteen-minute egg
        val total = Simulation.stageDuration(LifeStage.EGG, slow)
        val fresh = pet(stage = LifeStage.EGG, ageSeconds = 10L, stageStartedSeconds = 0L)
        val nearly = pet(stage = LifeStage.EGG, ageSeconds = total - 60L, stageStartedSeconds = 0L)

        val quietOne = Nudges.decide(at(0, fresh).copy(config = slow))
        assertNull("an egg laid a minute ago is not about to hatch", quietOne.post)
        val loudOne = Nudges.decide(at(0, nearly).copy(config = slow))
        assertEquals(NudgeKind.HATCHING, loudOne.post?.kind)
    }

    @Test
    fun `a child hatching is counted rather than named`() {
        // The child's id and name come out of the same random stream as the rest of a catch-up,
        // so they can differ between two simulations of one absence. How many there are cannot.
        val child = Pal(
            id = "pal_child_egg_1",
            name = "Momo",
            species = Species.LEAF,
            genome = Genome(),
            personality = Personality.CALM,
            relation = Relation.OFFSPRING,
            metAtSeconds = 19_900L,
        )
        val parent = pet().copy(pals = listOf(child))
        val outcome = Nudges.decide(at(0, parent, events = listOf(GameEvent.ChildHatched(child))))
        assertEquals(NudgeKind.CHILD_HATCHED, outcome.post?.kind)
        assertEquals("g1:child@1", outcome.post?.episodeId)
        assertTrue(outcome.post!!.body.startsWith("Momo hatched"))
    }

    @Test
    fun `a new generation starts the ledger's memory over without touching it`() {
        // Ids are scoped by generation, so nothing has to be cleared when a pet is replaced —
        // which matters, because there is no moment the notifier is guaranteed to see.
        val first = Nudges.decide(at(0, pet(sick = true, generation = 1)))
        val second = Nudges.decide(at(9 * hour, pet(sick = true, generation = 2), first.ledger))
        assertEquals(NudgeKind.ILLNESS, second.post?.kind)
        assertEquals("g2:illness", second.post?.episodeId)
    }

    // ---------------------------------------------------------------- the ledger on disk

    @Test
    fun `the ledger survives being written down and read back`() {
        val ledger = NudgeLedger(
            said = listOf("g1:illness", "g1:grew@TEEN", "g2:gone"),
            lastByKind = mapOf(NudgeKind.ILLNESS to 1_700_000_000_000L, NudgeKind.DEPARTED to 1_700_000_900_000L),
            liveEpisodeId = "g2:gone",
            liveKind = NudgeKind.DEPARTED,
            deliveredSinceOpened = 2,
        )
        assertEquals(ledger, Nudges.decode(Nudges.encode(ledger)))
    }

    @Test
    fun `an empty ledger round-trips too`() {
        assertEquals(NudgeLedger(), Nudges.decode(Nudges.encode(NudgeLedger())))
    }

    @Test
    fun `rubbish on disk costs one repeated notification and never a crash`() {
        assertEquals(NudgeLedger(), Nudges.decode(null))
        assertEquals(NudgeLedger(), Nudges.decode(""))
        assertEquals(NudgeLedger(), Nudges.decode("{\"said\":[\"g1:illness\"]}"))
        assertEquals(NudgeLedger(), Nudges.decode("v1\nsaid\nkinds\tNOPE=x\tILLNESS=notanumber\n"))
        // A half-written live entry keeps the escalation rule armed against a notification
        // nobody can see, so neither half counts on its own.
        assertNull(Nudges.decode("v1\nlive\tg1:illness\t\n").liveKind)
        assertNull(Nudges.decode("v1\nlive\tg1:illness\t\n").liveEpisodeId)
        assertNull(Nudges.decode("v1\nlive\t\tILLNESS\n").liveEpisodeId)
        // And a corrupt budget cannot silence the app for ever.
        assertEquals(Nudges.MAX_UNANSWERED, Nudges.decode("v1\ncount\t99999\n").deliveredSinceOpened)
    }

    @Test
    fun `the remembered list is bounded, because the save is rewritten every tick`() {
        var ledger = NudgeLedger()
        repeat(50) { i ->
            ledger = ledger.copy(said = (ledger.said + "g$i:illness").takeLast(Nudges.MAX_REMEMBERED_EPISODES))
        }
        assertEquals(Nudges.MAX_REMEMBERED_EPISODES, ledger.said.size)
        assertEquals(Nudges.MAX_REMEMBERED_EPISODES, Nudges.decode(Nudges.encode(ledger)).said.size)
    }

    @Test
    fun `process death between two ticks changes nothing`() {
        // The worker is a process that may not exist five minutes from now. Every decision is
        // therefore made from a ledger that has just come off disk.
        var raw: String? = null
        var posts = 0
        repeat(16) { i ->
            val ledger = Nudges.decode(raw)
            val outcome = Nudges.decide(at(i * quarter, pet(sick = true), ledger))
            if (outcome.post != null) posts++
            raw = Nudges.encode(outcome.ledger)
        }
        assertEquals(1, posts)
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `candidates come back worst first`() {
        val state = pet(sick = true, health = 5f)
        val grew = GameEvent.Evolved(LifeStage.CHILD, LifeStage.TEEN, EvolutionBranch.BALANCED)
        val kinds = Nudges.candidates(at(0, state, events = listOf(grew))).map { it.kind }
        assertEquals(listOf(NudgeKind.FADING, NudgeKind.ILLNESS, NudgeKind.EVOLVED), kinds)
    }

    @Test
    fun `severity is a total order with no ties across the families`() {
        val jeopardy = listOf(NudgeKind.ILLNESS, NudgeKind.FADING, NudgeKind.DEPARTED).map { it.severity }
        assertEquals(jeopardy.sorted(), jeopardy)
        assertEquals("the escalation must be strict", jeopardy.size, jeopardy.toSet().size)
        NudgeKind.entries.filter { it.isMilestone }.forEach {
            assertTrue("nothing to look at outranks anything at stake", it.severity < NudgeKind.ILLNESS.severity)
        }
    }
}
