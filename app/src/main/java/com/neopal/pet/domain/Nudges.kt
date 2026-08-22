package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * What the creature is allowed to interrupt somebody for, how often, and in what words.
 *
 * ### The rule
 *
 * **A notification is earned only when coming back *now* rather than later changes what happens
 * to the creature — or when something has just happened that will never happen again.**
 *
 * Two families fall out of that, and nothing else does:
 *
 *  - **Jeopardy.** Illness, and illness far enough along to be fatal. Read [Simulation.advance]:
 *    while the app is closed the catch-up applies [GameConfig.offlineHealthFloor] to health
 *    *unless the pet is already ill*. The simulation therefore already guarantees that absence
 *    alone cannot kill a healthy creature. A hungry pet is not in danger. A filthy pet is not in
 *    danger. A sick one is the only one whose outcome the player's return actually changes, and
 *    that is why illness is the only need-state in this file.
 *  - **Once in a life.** Hatching, an evolution, a child hatching, and the death itself.
 *    Unrepeatable, and no amount of opening the app later gets them back.
 *
 * Everything else is *state*, not event. Satiety at 64% will be exactly as true when the player
 * next opens the app, and the game has already promised it cannot be fatal in the meantime. State
 * never notifies. That is the line the old hungry/dirty/lonely reminder set failed, and a creature
 * that pings you about a meter is a creature you mute — after which it can never tell you about
 * the illness either. **The scarce resource being protected here is the channel, not the pet.**
 *
 * ### Never twice for the same thing
 *
 * Every nudge carries an *episode id*, and **no episode id contains a timestamp**. It is the
 * generation plus the thing itself: `g3:illness`, `g3:grew@ADULT`, `g3:gone`. A timestamp is
 * precisely what would make the dedupe both gameable and fragile — fragile because
 * [Simulation.advance] is only deterministic for a fixed `now`, so a background worker that
 * re-simulates the same absence every quarter of an hour can legitimately place the same illness
 * at a different second each time, and every one of those would look like a fresh episode.
 *
 * A jeopardy id is *re-armed* only when the notifier has seen the creature well again, which is
 * the one thing that genuinely means "a new episode". Nothing about the app's lifecycle re-arms
 * anything: opening and closing it fifty times cannot make one illness newsworthy twice.
 *
 * Three independent layers hold that line, because the first two can each be defeated by a
 * background worker that replays an absence: the episode id, a wall-clock cooldown per kind, and
 * the rule that something already pinned and unanswered may only be spoken over by something
 * strictly worse.
 *
 * ### Pure on purpose
 *
 * No Android here, and no clock: [NudgeInput.nowMillis] and [NudgeInput.localMinuteOfDay] arrive
 * as parameters. This is the half that can be wrong without anything looking wrong — a dedupe
 * rule that quietly never opens produces no crash and no log, only silence, which is exactly what
 * a correctly behaving notifier also produces. So it is tested rather than trusted.
 */
object Nudges {

    // ---------------------------------------------------------------- tunables

    /**
     * How many notifications the player may be sent between two openings of the app.
     *
     * Three, because in the worst honest case the three are `ill` → `fading` → `gone`: the whole
     * story of a death, in the order it happened, and not one word more. A player who never opens
     * the app cannot accumulate a queue of twelve. The fourth thing to go wrong is not news, it is
     * nagging, and the evidence for that is that the first three did not bring them back.
     */
    const val MAX_UNANSWERED = 3

    /** Episode ids remembered. Comfortably more than one generation can produce. */
    const val MAX_REMEMBERED_EPISODES = 32

    /**
     * How long ago a *milestone* may have happened and still be worth saying, in pet seconds.
     *
     * Doze and battery optimisation can hold a worker for hours. "Come and see, it is happening"
     * is simply false about something that happened four hours ago, and a notification the player
     * has learned is false is a notification they switch off. The age is read off the creature
     * ([PetState.secondsInStage] and friends) rather than off the wall clock, so it stays correct
     * whether the worker saves its simulation or throws it away.
     *
     * Jeopardy is exempt: it is a claim about the present tense, re-derived from live state every
     * time, so a pet that is ill *now* is worth saying however late the worker was.
     */
    const val FRESH_SECONDS = 45L * 60L

    /**
     * Wall-clock milliseconds before the same kind may be raised again.
     *
     * This is the layer that does not care how the episode id was derived, and it exists for the
     * case where a re-simulating worker sees the creature ill, then well, then ill again inside an
     * hour — three observations of one illness that the id alone would read as two episodes.
     */
    const val SAME_KIND_COOLDOWN_MILLIS = 3L * 3600_000L

    /**
     * Floor between any two notifications, whatever they are about. Something strictly more
     * serious may step over it — that is the difference between escalating and nagging.
     */
    const val ANY_GAP_MILLIS = 30L * 60_000L

    /** Health at or below which an illness has become a genuine danger of dying. */
    const val FADING_HEALTH = 30f

    /** How close to hatching an egg has to be before it is worth mentioning. */
    const val HATCH_WARNING_SECONDS = 10L * 60L

    /** The item the medicine action spends. Matches the catalogue's starter medicine. */
    const val MEDICINE_ITEM = "medicine"

    // ---------------------------------------------------------------- the decision

    /**
     * Decides the one thing worth saying, if anything is.
     *
     * At most one notification exists at a time. One creature, one line: a shade holding four
     * entries from the same pet is a shade the player clears without reading, and the fourth entry
     * is how the first one came to be ignored.
     */
    fun decide(input: NudgeInput): NudgeOutcome {
        val state = input.state
        val candidates = candidates(input)
        val standing = candidates.mapTo(HashSet()) { it.episodeId }

        // A jeopardy episode is forgotten once the creature has been seen well again, and only
        // then. That is what lets a second illness be announced without letting the first one be
        // announced twice, and it depends on nothing but the creature.
        val rearmed = input.ledger.said.filterNot { said ->
            NudgeKind.entries.any { it.isLiveClaim && liveClaimId(state, it) == said && said !in standing }
        }
        val rearmedLedger =
            if (rearmed.size == input.ledger.said.size) input.ledger else input.ledger.copy(said = rearmed)

        // Whether whatever is pinned in the shade is still true. Only jeopardy is a live claim; a
        // milestone is a fact about a moment that has passed, and it cannot stop having happened.
        val liveGone = rearmedLedger.liveKind?.let { kind ->
            kind.isLiveClaim && rearmedLedger.liveEpisodeId !in standing
        } ?: false
        // Taken down here rather than on the way out, because a pin that has stopped being true
        // must also stop blocking. Clearing it only on the silent paths left a dead creature's
        // illness holding the escalation rule shut against the next creature's.
        val ledger = if (liveGone) rearmedLedger.copy(liveEpisodeId = null, liveKind = null) else rearmedLedger

        fun quiet(reason: NudgeSilence, withdraw: Boolean = liveGone): NudgeOutcome =
            NudgeOutcome(post = null, withdraw = withdraw, ledger = ledger, silence = reason)

        // The master switch is the player's, and switching it off should clear the shade too: a
        // setting that leaves its last notification pinned reads as a setting that did nothing.
        if (!input.enabled) {
            return NudgeOutcome(
                post = null,
                withdraw = rearmedLedger.liveKind != null,
                ledger = ledger.copy(liveEpisodeId = null, liveKind = null),
                silence = NudgeSilence.MUTED,
            )
        }
        // Nothing is worth telling somebody about a creature they are looking at.
        if (input.appInForeground) return quiet(NudgeSilence.PLAYER_IS_HERE)
        // Denied is a legitimate answer, and the game is complete without this file. Nothing is
        // written down as said when nothing could be said, so a player who grants the permission
        // later still hears about the illness they are living through instead of a silence.
        if (input.permission == NudgePermission.DENIED) return quiet(NudgeSilence.NO_PERMISSION)
        if (state.lastTickMillis == 0L) return quiet(NudgeSilence.NOTHING_TO_SAY)
        if (candidates.isEmpty()) return quiet(NudgeSilence.NOTHING_TO_SAY)

        // Quiet hours and the budget belong to the moment rather than to any one candidate, and
        // both are checked *before* anything is written down: a nudge held back by the night was
        // never said, so it is still owed in the morning.
        if (input.settings.isQuiet(input.localMinuteOfDay)) return quiet(NudgeSilence.QUIET_HOURS)
        if (ledger.deliveredSinceOpened >= MAX_UNANSWERED) return quiet(NudgeSilence.BUDGET_SPENT)

        var firstRefusal: NudgeSilence? = null
        for (candidate in candidates) {
            val refusal = refuse(candidate, input, ledger)
            if (refusal == null) {
                return NudgeOutcome(
                    post = candidate,
                    withdraw = false,
                    ledger = ledger.recording(candidate, input.nowMillis),
                    silence = null,
                )
            }
            if (firstRefusal == null) firstRefusal = refusal
        }
        return quiet(firstRefusal ?: NudgeSilence.NOTHING_TO_SAY)
    }

    /** Why this particular candidate may not be said right now, or null if it may. */
    private fun refuse(candidate: Nudge, input: NudgeInput, ledger: NudgeLedger): NudgeSilence? {
        if (candidate.kind.isMilestone && !input.settings.milestones) return NudgeSilence.MILESTONES_MUTED
        if (candidate.episodeId in ledger.said) return NudgeSilence.ALREADY_SAID
        // A milestone dug out of a twelve-hour catch-up is about a moment long gone.
        if (candidate.kind.isMilestone && candidate.sinceSeconds > FRESH_SECONDS) return NudgeSilence.STALE
        // Something is already pinned and unanswered. Only a strictly worse turn may speak over
        // it; anything else is a second way of saying what the player has already declined.
        val live = ledger.liveKind
        if (live != null && candidate.kind.severity <= live.severity) return NudgeSilence.ALREADY_LOUDER
        val lastOfKind = ledger.lastByKind[candidate.kind] ?: 0L
        if (lastOfKind > 0L && input.nowMillis - lastOfKind < SAME_KIND_COOLDOWN_MILLIS) {
            return NudgeSilence.TOO_SOON
        }
        val lastAny = ledger.lastDeliveredAtMillis
        val worseThanLast = candidate.kind.severity > (ledger.lastKind?.severity ?: 0)
        if (lastAny > 0L && input.nowMillis - lastAny < ANY_GAP_MILLIS && !worseThanLast) {
            return NudgeSilence.TOO_SOON
        }
        return null
    }

    /**
     * Everything true about this creature right now that is worth a notification, worst first.
     *
     * Ordering is by [NudgeKind.severity], and it matters: [decide] takes the first candidate that
     * survives its filters, so a death always outranks the illness that caused it.
     */
    fun candidates(input: NudgeInput): List<Nudge> {
        val state = input.state
        val out = ArrayList<Nudge>(3)

        if (state.isDead) {
            // Nothing else about a dead creature is worth saying, and most of it is not true.
            out += departed(state)
            return out
        }

        if (state.isSick) {
            if (state.stats.health <= FADING_HEALTH) out += fading(state)
            out += ill(state)
        }

        // How long ago each of these happened is read off the creature, not off the tick, so it
        // survives a worker that re-simulates the same absence over and over. Capped absences
        // (see [GameConfig.maxOfflineSeconds]) compress the game clock, which is the right thing
        // to measure against anyway: it is the clock the player will find when they open the app.
        input.events.filterIsInstance<GameEvent.ChildHatched>().lastOrNull()?.let { event ->
            out += childHatched(state, event.child)
        }
        // The furthest it got. A catch-up crossing two stages is one piece of news.
        input.events.filterIsInstance<GameEvent.Evolved>().lastOrNull()?.let { event ->
            out += evolved(state, event)
        }
        if (input.events.any { it is GameEvent.Hatched }) out += hatched(state)

        if (state.isEgg) {
            val total = Simulation.stageDuration(LifeStage.EGG, input.config)
            val left = total - state.secondsInStage
            // Relative as well as absolute, because "nearly time" has to mean nearly. At the
            // default pace an egg hatches in ninety seconds, and a fixed ten-minute warning
            // would fire the instant the egg was laid.
            if (left in 0..minOf(HATCH_WARNING_SECONDS, total / 2)) out += hatching(state)
        }

        return out.sortedByDescending { it.kind.severity }
    }

    // ---------------------------------------------------------------- the words
    //
    // First person, spare, unsentimental: the voice of the diary in [Chronicle], because it is the
    // same creature writing. No exclamation marks, no "your pet needs attention", nothing that
    // reads as a task an app has assigned. A line is chosen per episode rather than at random, so
    // that one episode always says the same thing — a notification that rewords itself when it is
    // re-posted looks like two notifications.

    private fun ill(state: PetState): Nudge = nudge(
        state = state,
        kind = NudgeKind.ILLNESS,
        lines = listOf(
            "Something in me has gone wrong. I would rather you knew.",
            "I don't feel right, and it isn't passing.",
            "I'm ill. I would rather not wait it out on my own.",
        ),
        actions = remedies(state),
    )

    private fun fading(state: PetState): Nudge = nudge(
        state = state,
        kind = NudgeKind.FADING,
        lines = listOf(
            "It's worse than it was. I don't know how much longer I have.",
            "I'm running out. Come if you can.",
            "I can't shake this on my own.",
        ),
        actions = remedies(state),
    )

    /**
     * A notification worth acting on from the shade, and only where the action is honest.
     *
     * The button appears only when there is medicine in the inventory. A "Give medicine" that
     * opens the app to announce an empty cupboard is worse than no button at all: it is the app
     * having lied in order to get itself opened.
     */
    private fun remedies(state: PetState): List<NudgeAction> =
        if ((state.inventory[MEDICINE_ITEM] ?: 0) > 0) listOf(NudgeAction.MEDICINE) else emptyList()

    private fun departed(state: PetState): Nudge = Nudge(
        episodeId = "g${state.generation}:gone",
        kind = NudgeKind.DEPARTED,
        title = state.name,
        // Third person, and the only nudge that is. The diary is written by the creature, and the
        // creature is not there to write this one.
        body = when (state.deathReason) {
            DeathReason.OLD_AGE -> "${state.name} is gone, at the end of a long life. Nothing was left undone."
            DeathReason.ILLNESS -> "${state.name} is gone. The illness didn't pass."
            DeathReason.STARVATION -> "${state.name} is gone. There was nothing left to eat."
            DeathReason.NEGLECT -> "${state.name} is gone. It was a long time alone."
            null -> "${state.name} is gone."
        },
        // Nothing to do. A button here would be the app asking for a tap at the one moment it has
        // no business asking for anything.
        actions = emptyList(),
        sinceSeconds = 0L,
    )

    private fun evolved(state: PetState, event: GameEvent.Evolved): Nudge = Nudge(
        episodeId = "g${state.generation}:grew@${event.to.name}",
        kind = NudgeKind.EVOLVED,
        title = state.name,
        body = when (event.to) {
            LifeStage.CHILD -> "I've outgrown the shape you last saw me in."
            LifeStage.TEEN -> "I changed while you were out. Come and look."
            LifeStage.ADULT -> "I'm grown — a ${event.branch.displayName.lowercase()} one. That was your doing."
            LifeStage.ELDER -> "I've gone grey. I earned every bit of it."
            else -> "Something in me shifted. You'd know it if you saw me."
        },
        actions = emptyList(),
        sinceSeconds = state.secondsInStage,
    )

    private fun hatched(state: PetState): Nudge = Nudge(
        episodeId = "g${state.generation}:hatched",
        kind = NudgeKind.EVOLVED,
        title = state.name,
        // The diary's version of this line ends "the first thing I saw was you". If this
        // notification is being written, that is not what happened.
        body = "I opened my eyes. You weren't the first thing I saw.",
        actions = emptyList(),
        sinceSeconds = state.secondsInStage,
    )

    private fun hatching(state: PetState): Nudge = nudge(
        state = state,
        kind = NudgeKind.HATCHING,
        lines = listOf(
            "The shell is thinner than it was.",
            "It's nearly time. I'd like you here for it.",
        ),
        actions = emptyList(),
    )

    private fun childHatched(state: PetState, child: Pal): Nudge = Nudge(
        // Counted rather than named. The child's id and name are drawn from the same random
        // stream as everything else in a catch-up, so they can differ between two simulations of
        // one absence; how many children there are cannot.
        episodeId = "g${state.generation}:child@${state.pals.count { it.relation == Relation.OFFSPRING }}",
        kind = NudgeKind.CHILD_HATCHED,
        title = state.name,
        body = "${child.name} hatched. I stayed with the egg the whole time.",
        actions = emptyList(),
        sinceSeconds = (state.ageSeconds - child.metAtSeconds).coerceAtLeast(0L),
    )

    /** A jeopardy or waiting nudge: one per generation per kind, worded from the episode id. */
    private fun nudge(
        state: PetState,
        kind: NudgeKind,
        lines: List<String>,
        actions: List<NudgeAction>,
    ): Nudge {
        val episodeId = liveClaimId(state, kind)
        return Nudge(
            episodeId = episodeId,
            kind = kind,
            title = state.name,
            body = pick(episodeId + state.name, lines),
            actions = actions,
            sinceSeconds = 0L,
        )
    }

    /**
     * The id for a claim about the present tense. No timestamp, by design — see the note at the
     * top of the file. One per generation per kind, re-armed by the creature getting better.
     */
    private fun liveClaimId(state: PetState, kind: NudgeKind): String =
        "g${state.generation}:${kind.name.lowercase()}"

    /**
     * Same episode, same line, for ever. [String.hashCode] would do it, but its value is a
     * platform promise this file would rather not lean on, so the fold is written out.
     */
    private fun pick(key: String, lines: List<String>): String {
        var acc = 0
        for (c in key) acc = (acc * 31 + c.code) and 0x3FFFFFF
        return lines[acc % lines.size]
    }

    // ---------------------------------------------------------------- the ledger

    /** The player came back. Clears the budget and takes down whatever was pinned. */
    fun markOpened(ledger: NudgeLedger): NudgeLedger =
        ledger.copy(liveEpisodeId = null, liveKind = null, deliveredSinceOpened = 0)

    private fun NudgeLedger.recording(nudge: Nudge, nowMillis: Long): NudgeLedger = copy(
        said = (said + nudge.episodeId).takeLast(MAX_REMEMBERED_EPISODES),
        lastByKind = lastByKind + (nudge.kind to nowMillis),
        liveEpisodeId = nudge.episodeId,
        liveKind = nudge.kind,
        deliveredSinceOpened = deliveredSinceOpened + 1,
    )

    /**
     * The ledger as one flat string, because it has to outlive the process.
     *
     * Hand-rolled rather than serialised, for one reason: this format is the thing standing
     * between the player and being told twice, so it is worth being able to test the round trip
     * in the pure suite rather than only on a device. Unknown lines are ignored and a malformed
     * field falls back to its default, so a truncated write costs at most one repeated
     * notification and never a crash.
     */
    fun encode(ledger: NudgeLedger): String = buildString {
        append("v1\n")
        append("said\t").append(ledger.said.joinToString("\t")).append('\n')
        append("kinds\t").append(
            ledger.lastByKind.entries
                .sortedBy { it.key.ordinal }
                .joinToString("\t") { "${it.key.name}=${it.value}" },
        ).append('\n')
        append("live\t").append(ledger.liveEpisodeId.orEmpty()).append('\t')
            .append(ledger.liveKind?.name.orEmpty()).append('\n')
        append("count\t").append(ledger.deliveredSinceOpened).append('\n')
    }

    fun decode(raw: String?): NudgeLedger {
        if (raw.isNullOrBlank()) return NudgeLedger()
        var said = emptyList<String>()
        var kinds = emptyMap<NudgeKind, Long>()
        var liveId: String? = null
        var liveKind: NudgeKind? = null
        var count = 0
        raw.lineSequence().forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "said" -> said = parts.drop(1).filter { it.isNotEmpty() }.takeLast(MAX_REMEMBERED_EPISODES)
                "kinds" -> kinds = parts.drop(1).mapNotNull { field ->
                    val kind = NudgeKind.entries.firstOrNull { it.name == field.substringBefore('=', "") }
                    val at = field.substringAfter('=', "").toLongOrNull()
                    if (kind != null && at != null) kind to at else null
                }.toMap()
                "live" -> {
                    liveId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    liveKind = NudgeKind.entries.firstOrNull { it.name == parts.getOrNull(2) }
                }
                "count" -> count = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, MAX_UNANSWERED) ?: 0
            }
        }
        // Half a live entry would keep the escalation rule permanently armed against a
        // notification nobody can see. Neither half counts on its own.
        val id = liveId
        val kind = liveKind
        return if (id == null || kind == null) {
            NudgeLedger(said, kinds, null, null, count)
        } else {
            NudgeLedger(said, kinds, id, kind, count)
        }
    }
}

/**
 * What a nudge is about, and how badly. [severity] is the whole escalation order: something
 * already pinned and unanswered may only be spoken over by something strictly worse.
 */
enum class NudgeKind(
    val severity: Int,
    /** Milestones are the "come and look" half, and the player can mute them separately. */
    val isMilestone: Boolean,
    /**
     * True when the nudge asserts something about the present tense. Such a nudge stops being
     * true when the creature changes, so it can be withdrawn from the shade and its episode
     * re-armed. A milestone happened; it cannot stop having happened.
     */
    val isLiveClaim: Boolean,
) {
    HATCHING(1, isMilestone = true, isLiveClaim = false),
    EVOLVED(1, isMilestone = true, isLiveClaim = false),
    CHILD_HATCHED(1, isMilestone = true, isLiveClaim = false),
    ILLNESS(2, isMilestone = false, isLiveClaim = true),
    FADING(3, isMilestone = false, isLiveClaim = true),

    /**
     * Not a milestone, deliberately: turning off "come and look" must not also turn off the one
     * notification a player would never forgive the game for withholding.
     */
    DEPARTED(4, isMilestone = false, isLiveClaim = false),
    ;

    /** Which channel carries it. Two, so the player can mute the pleasant half and keep the rest. */
    val channel: NudgeChannel
        // Death goes on the quiet channel with the milestones. It is the most important thing that
        // will ever happen and there is nothing to be done about it, and those two facts together
        // are an exact description of something that should not make a sound.
        get() = if (this == ILLNESS || this == FADING) NudgeChannel.URGENT else NudgeChannel.LIFE
}

/** The two notification channels. Named for what they carry, not for how loud they are. */
enum class NudgeChannel { URGENT, LIFE }

/**
 * A button on the notification itself.
 *
 * There is exactly one, and that is a consequence of the rule rather than an oversight: illness is
 * the only need-state worth interrupting for, and medicine is the only thing that answers it.
 */
enum class NudgeAction(val id: String, val label: String) {
    MEDICINE("neopal.nudge.MEDICINE", "Give medicine"),
}

/** One thing worth saying, already worded. */
data class Nudge(
    val episodeId: String,
    val kind: NudgeKind,
    val title: String,
    val body: String,
    val actions: List<NudgeAction>,
    /**
     * How long ago this happened, in pet seconds, for the things that happened. Zero for the ones
     * that are about now or about what is coming. See [Nudges.FRESH_SECONDS].
     */
    val sinceSeconds: Long,
) {
    val channel: NudgeChannel get() = kind.channel
}

/** Whether the platform will actually deliver anything. See [NudgeSilence.NO_PERMISSION]. */
enum class NudgePermission {
    /** Below API 33, where there is nothing to ask for. */
    NOT_REQUIRED,
    GRANTED,
    DENIED,
}

/** Why nothing was said. Every branch here is a decision, so every branch is nameable. */
enum class NudgeSilence {
    NOTHING_TO_SAY,
    MUTED,
    MILESTONES_MUTED,
    NO_PERMISSION,
    PLAYER_IS_HERE,
    QUIET_HOURS,
    ALREADY_SAID,
    ALREADY_LOUDER,
    BUDGET_SPENT,
    TOO_SOON,
    STALE,
}

/**
 * The player's settings for all of this.
 *
 * Quiet hours run on **the player's** local clock and not the creature's. The pet's day is
 * [GameConfig.secondsPerPetDay] long — six real hours by default — so its night falls four times
 * in a real day and lands on a different wall-clock hour each time. Silencing on the creature's
 * clock would mute the app over lunch and let it through at 04:00, which is the precise opposite
 * of what quiet hours are for. The pet's clock governs the pet; the phone is on somebody's
 * nightstand, and that is a fact about the person.
 */
@Serializable
data class NudgeSettings(
    /** The "come and look" half: hatching, evolutions, a child. Jeopardy is unaffected. */
    val milestones: Boolean = true,
    val quietHours: Boolean = true,
    /** Local hour the quiet window opens, 0..23. */
    val quietFromHour: Int = 22,
    /** Local hour it closes, 0..23. Wraps past midnight, which is the normal case. */
    val quietToHour: Int = 8,
) {
    /**
     * True inside the quiet window. [localMinuteOfDay] is minutes since the player's local
     * midnight; anything out of range is folded back in rather than trusted, because a corrupt
     * save must not be able to make the app either permanently silent or permanently loud.
     *
     * Nothing is exempt, a death included. The alternative is a game that wakes somebody at 04:00
     * to do a chore, and that game has its notifications revoked by morning — after which it can
     * never tell them anything again, about any creature, for the rest of the lineage. Losing a
     * pet is content: there is a memorial for it, the lessons carry forward, the next generation
     * starts. Losing the channel is permanent.
     */
    fun isQuiet(localMinuteOfDay: Int): Boolean {
        if (!quietHours) return false
        val from = quietFromHour.coerceIn(0, 23) * 60
        val to = quietToHour.coerceIn(0, 23) * 60
        // An empty window means "no quiet hours", never "always quiet". Of the two ways to read a
        // nonsense setting, only one of them can silently disable the whole feature.
        if (from == to) return false
        val minute = ((localMinuteOfDay % 1440) + 1440) % 1440
        return if (from < to) minute in from until to else minute >= from || minute < to
    }
}

/**
 * What has already been said, and what is still pinned. Persisted by the caller and reloaded
 * before every decision, so process death costs nothing.
 */
data class NudgeLedger(
    /** Episode ids already delivered, oldest first. Only *deliveries* are written here. */
    val said: List<String> = emptyList(),
    /** When each kind was last actually delivered, in wall-clock millis. */
    val lastByKind: Map<NudgeKind, Long> = emptyMap(),
    /** The episode currently occupying the shade, if any. */
    val liveEpisodeId: String? = null,
    val liveKind: NudgeKind? = null,
    /** Delivered since the player last opened the app. Bounded by [Nudges.MAX_UNANSWERED]. */
    val deliveredSinceOpened: Int = 0,
) {
    val lastDeliveredAtMillis: Long get() = lastByKind.values.maxOrNull() ?: 0L

    /** The kind of the most recent delivery, whatever it was about. */
    val lastKind: NudgeKind? get() = lastByKind.entries.maxByOrNull { it.value }?.key
}

/**
 * Everything the decision needs, and nothing it could have got for itself.
 *
 * [state] is the creature as it stands *now* — the caller advances the simulation first, so the
 * jeopardy half is always a claim about the present tense however late the worker was. How stale
 * a milestone is comes off the creature instead ([Nudge.sinceSeconds]), which is why nothing here
 * describes the tick that produced [events].
 */
data class NudgeInput(
    val state: PetState,
    val config: GameConfig = GameConfig.Default,
    val events: List<GameEvent> = emptyList(),
    val nowMillis: Long,
    /** Minutes since the player's local midnight, 0..1439. */
    val localMinuteOfDay: Int,
    val ledger: NudgeLedger = NudgeLedger(),
    val permission: NudgePermission = NudgePermission.GRANTED,
    /** The master switch, [GameConfig.notificationsEnabled]. */
    val enabled: Boolean = true,
    val settings: NudgeSettings = NudgeSettings(),
    /** True while the player is looking at the creature. Nothing is worth saying then. */
    val appInForeground: Boolean = false,
)

/**
 * What to do about it. [post] and [silence] are exclusive — exactly one is non-null — so a caller
 * that logs the silences can always answer "why did nothing happen".
 */
data class NudgeOutcome(
    val post: Nudge?,
    /** Take down what is pinned: it has stopped being true, or the player switched it all off. */
    val withdraw: Boolean,
    /** Store this. It is the ledger whether or not anything was posted. */
    val ledger: NudgeLedger,
    val silence: NudgeSilence?,
)
