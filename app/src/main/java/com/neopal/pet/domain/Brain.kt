package com.neopal.pet.domain

import kotlin.math.abs
import kotlin.random.Random

/**
 * One option the brain weighed, ready to be shown next to the others.
 *
 * The blocked options are in here on purpose. A pet that is starving but has never learned to
 * work the pantry has to be *visibly* starving-and-unable, because that is the moment the player
 * is supposed to realise the thing needs teaching. Dropping the option from the list would leave
 * the screen saying the pet simply did not fancy eating, which is a lie about the one mechanic
 * skills exist to create.
 */
data class Consideration(
    val kind: ActivityKind,
    /** 0..1-ish. How badly the pet wanted this, whether or not it could have it. */
    val utility: Float,
    /** What the pet would say if it picked this. */
    val reason: String,
    /** Null when the pet could actually do it; otherwise the short reason it cannot. */
    val blockedBy: String?,
    /**
     * Which thing, when the kind alone does not say — the food to eat, the game to play.
     *
     * Two options of the same kind and different targets are genuinely different choices, so a
     * mind picking between them has to be able to say *which*, and whatever executes the pick has
     * to be told. Without this the index a model returns would be resolved back to the first
     * option of that kind, and a creature asked for the cake would be handed the berry.
     */
    val target: String? = null,
) {
    val available: Boolean get() = blockedBy == null
}

/**
 * The creature's decision-making, run once per simulation step.
 *
 * Utility scoring rather than a behaviour tree or a priority list. A priority list ("eat if
 * hungry, else sleep if tired, else play") was the first attempt and it produced a pet with no
 * personality at all: every creature of every genome behaved identically, because the ordering
 * was in the code rather than in the animal. Scoring every option and taking the best one lets a
 * greedy genome eat at 60% satiety while a curious one is still reading at 40%, from the same
 * rules — and it gives the log a number to show for how badly the pet wanted what it chose.
 *
 * Three properties are load-bearing and easy to lose:
 *
 *  - **It commits.** A brain that re-scores every second picks a marginally different winner
 *    every second and the creature twitches. Every choice buys a stretch of time, and there is a
 *    floor under the gap between choices on top of that.
 *  - **Capability gates the option, not the need.** Hunger is scored the same for every pet;
 *    whether *eating* is on the menu depends on [PetState.canAct]. A pet that cannot feed itself
 *    stays hungry and does something else, which is the entire point of the skill tree.
 *  - **It never fights the simulation.** Anything discrete — a meal, a dose, a scooped mess —
 *    goes through [CareActions] so a self-fed pet lands exactly where a hand-fed one would, and
 *    sleep is only ever switched *on* here (see [commit]).
 */
object Brain {

    // How long each choice is committed to, in pet seconds. These are the numbers that decide
    // whether the creature reads as deliberate or frantic; they are all comfortably longer than
    // a step, and the discrete ones are only as long as the animation needs.
    private const val EAT_SECONDS = 20L
    private const val FORAGE_SECONDS = 95L
    private const val SLEEP_SECONDS = 1_800L
    private const val PLAY_SECONDS = 60L
    private const val GROOM_SECONDS = 45L
    private const val TIDY_SECONDS = 25L
    private const val STUDY_SECONDS = 90L
    private const val MEDICATE_SECONDS = 15L
    private const val SOCIAL_SECONDS = 120L
    private const val EXPLORE_SECONDS = 80L
    private const val IDLE_SECONDS = 40L

    /**
     * Floor under the gap between two decisions.
     *
     * The commitment window already covers the usual case; this covers the nasty one, where an
     * activity is abandoned the instant it starts (the meal was refused, the visitor left) and
     * the brain would otherwise re-score on the very next step, forever.
     */
    const val MIN_DECISION_GAP_SECONDS = 15L

    /** Marks an [Activity.targetId] as foraged food rather than a pantry item. */
    const val FORAGE_TARGET = "forage"

    /** Enough noise to break ties differently on different runs, far too little to beat a need. */
    private const val JITTER = 0.05f

    /**
     * Advances whatever the pet is doing, and picks something new when it is between things.
     *
     * Returns [state] untouched for a pet that is not allowed a mind of its own, has not grown
     * one yet, or has died — the caller can therefore run this unconditionally inside the step
     * loop without knowing any of the rules.
     */
    fun tick(
        state: PetState,
        config: GameConfig,
        dt: Long,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        if (state.autonomy == Autonomy.OFF) return state
        if (state.isDead || !state.isMindAwake) return state

        var s = state
        val activity = s.activity
        if (activity != null) {
            // An activity and the sleep flag can disagree in two ways: the player switched the
            // lights off mid-game, or woke the pet before it had finished sleeping. Either way
            // the committed activity is the stale one, so it is dropped rather than argued with.
            val stale = s.isSleeping != (activity.kind == ActivityKind.SLEEP)
            if (!stale && !activity.isOver(s.ageSeconds)) {
                return run(s, activity, dt, random, events)
            }
            events += GameEvent.Finished(activity.kind, closingNote(activity))
            s = s.copy(activity = null)
        }

        // Waking is [Simulation]'s alone (see [commit]), so a sleeping pet simply has no turn.
        if (s.isSleeping) return s

        val last = s.decisions.lastOrNull()
        if (last != null && s.ageSeconds - last.atSeconds < MIN_DECISION_GAP_SECONDS) return s

        // A plan that has run out of time is dropped before it can be followed. Needs move: a plan
        // made forty-five minutes ago was made about a creature that no longer exists.
        s = Errands.expireIfStale(s, events)

        // A plan outranks the scoring, which is the entire point of having one. Scoring answers
        // "what does it most want right now"; a plan answers "what was it in the middle of". A
        // creature that re-scored from scratch every time would abandon the errand it set out on
        // the moment anything became marginally more appealing, and never finish anything.
        Errands.nextStep(s)?.let { step ->
            val planned = options(s, config).firstOrNull { it.kind == step.kind && it.blockedBy == null }
            if (planned != null) {
                val started = commit(
                    state = s,
                    option = Option(
                        kind = planned.kind,
                        utility = planned.utility,
                        durationSeconds = planned.durationSeconds,
                        target = planned.target,
                        reason = step.why.ifBlank { planned.reason },
                        blockedBy = null,
                    ),
                    runnerUp = null,
                    random = random,
                    events = events,
                )
                if (started != null) {
                    // Errands decides *whether* a working plan should grow; the next step has to
                    // come from here, because this is the only place that knows what is currently
                    // legal. A plan that grew itself an impossible step would be abandoned on the
                    // very next tick, which is a worse creature than one that simply stopped.
                    return Errands.advance(started, events) { after, plan ->
                        options(after, config)
                            .filter {
                                it.blockedBy == null &&
                                    it.kind != ActivityKind.IDLE &&
                                    it.kind != plan.steps.last().kind
                            }
                            .maxByOrNull { it.utility }
                            ?.let { PlanStep(it.kind, it.reason) }
                    }
                }
            }
            // The step cannot be done. Abandon the plan rather than retrying it: a creature that
            // insists on lunch in front of an empty pantry is not persistent, it is stuck.
            s = Errands.abandon(s, events)
        }

        val ranked = options(s, config)
            .filter { it.blockedBy == null }
            .map { it to it.utility + random.nextFloat() * JITTER }
            .sortedByDescending { it.second }

        for (i in ranked.indices) {
            val option = ranked[i].first
            // Skipped past its own kind: "I nearly ate the berry instead" is not a road not
            // taken, it is the same road. The runner-up is only interesting as another verb.
            val runnerUp = ranked.drop(i + 1).firstOrNull { it.first.kind != option.kind }?.first?.kind
            // A discrete action can still be refused at the last moment — the pet is fuller than
            // it thought, the mess was already scooped. Fall through to the next best rather than
            // burning the turn, which is also what stops an empty pantry becoming a phantom meal.
            val started = commit(s, option, runnerUp, random, events) ?: continue
            return started
        }
        return s
    }

    /**
     * Commits the pet to [kind] because something outside this object asked it to — today that is
     * a language model that was shown the same options and picked differently.
     *
     * Returns null when the option is not available *right now*, and that re-check is the whole
     * point of the function existing. A remote brain is asked over a network: seconds pass, the
     * simulation keeps ticking, and the option it was shown may have been eaten, scooped, or
     * slept through by the time its answer lands. Trusting a decision that was legal when it was
     * made would let a model quietly do things the rules forbid — not because it lied, but
     * because it answered a question about a world that has moved on.
     *
     * The reason is the model's own words, so the decision log stays in the creature's voice
     * whether the choice came from here or from the local scoring.
     */
    fun adopt(
        state: PetState,
        kind: ActivityKind,
        reason: String,
        config: GameConfig,
        random: Random,
        events: MutableList<GameEvent>,
        target: String? = null,
    ): PetState? {
        if (state.autonomy == Autonomy.OFF || !state.isMindAwake || state.isDead) return null
        if (state.isSleeping) return null
        // A named target must still be there. This is the same re-check as the kind and it exists
        // for the same reason: the answer describes a pantry that has had seconds to change, and
        // silently substituting a different food would be the rules being applied to a decision
        // nobody actually made. Refusing is right; the local brain then simply chooses.
        val option = options(state, config)
            .firstOrNull { it.kind == kind && it.blockedBy == null && (target == null || it.target == target) }
            ?: return null
        val spoken = reason.trim().take(MAX_ADOPTED_REASON_CHARS).ifBlank { option.reason }
        val runnerUp = options(state, config)
            .filter { it.blockedBy == null && it.kind != kind }
            .maxByOrNull { it.utility }
            ?.kind
        return commit(
            state = state,
            option = Option(
                kind = option.kind,
                utility = option.utility,
                durationSeconds = option.durationSeconds,
                target = option.target,
                reason = spoken,
                blockedBy = null,
            ),
            runnerUp = runnerUp,
            random = random,
            events = events,
        )
    }

    /** A reason has to fit a row in the decision log, whoever wrote it. */
    private const val MAX_ADOPTED_REASON_CHARS = 160

    /**
     * Every option the pet is weighing right now, best first, blocked ones included.
     *
     * Pure and free of the RNG, so the skills screen can show the same numbers the brain used
     * without the act of looking changing what the pet does next.
     */
    fun considerations(state: PetState, config: GameConfig): List<Consideration> =
        options(state, config)
            .map { Consideration(it.kind, it.utility, it.reason, it.blockedBy, it.target) }
            .sortedByDescending { it.utility }

    // ------------------------------------------------------------------ scoring

    /** A scored candidate, plus everything [commit] needs to actually start it. */
    private class Option(
        val kind: ActivityKind,
        val utility: Float,
        val durationSeconds: Long,
        val target: String?,
        val reason: String,
        val blockedBy: String?,
    )

    /**
     * Why the pet cannot use [skill] right now, phrased for the player rather than the compiler.
     *
     * Split from the scoring so the three failures stay distinguishable: never taught, taught but
     * held back by the autonomy setting, and "you are doing this by hand" are three different
     * conversations, and collapsing them into "cannot" loses the only one that is actionable.
     */
    private fun gate(state: PetState, skill: Skill): String? = when {
        state.autonomy == Autonomy.OFF -> "you are making the calls"
        skill !in state.skills -> "not learned yet"
        !state.canAct(skill) -> "not while it is only assisting"
        else -> null
    }

    private fun pct(value: Float): String = "${value.toInt()}%"

    private fun options(state: PetState, config: GameConfig): List<Option> {
        val g = state.genome
        val st = state.stats
        val hunger = ((100f - st.satiety) / 100f).coerceIn(0f, 1f)
        val tired = ((100f - st.energy) / 100f).coerceIn(0f, 1f)
        val bored = ((100f - st.happiness) / 100f).coerceIn(0f, 1f)
        val grubby = maxOf((100f - st.hygiene) / 100f, (state.poops / 4f)).coerceIn(0f, 1f)
        val night = Simulation.isNight(state, config)
        // Anything optional gets shelved while the pet is starving or exhausted. Without this a
        // curious genome reads a book on an empty stomach, which looks less like temperament and
        // more like a bug.
        val spare = ((1f - hunger * 0.7f) * (1f - tired * 0.7f)).coerceIn(0f, 1f)

        val out = ArrayList<Option>(11)

        // ---- eating -------------------------------------------------------------------
        // Squared, so hunger is quiet at 70% and shouts at 20%. A linear need would have the pet
        // grazing constantly, which empties the pantry and the player's patience together.
        if (st.satiety < 82f) {
            val want = (hunger * hunger * (0.72f + 0.56f * g.appetite)).coerceIn(0f, 1f)
            val larder = foodChoices(state, 100f - st.satiety)
            if (larder.isNotEmpty()) {
                // Every food in reach is offered separately rather than one being picked here.
                // That is the whole of "tools that act": a mind choosing by index among options
                // the rules have already cleared can now say *which* thing, not merely which
                // verb, and it gains that without a single new thing being taken on trust.
                //
                // It also gives the creature a taste of its own even with no model at all. The
                // list is ordered by appeal, and appeal is not just which food fits the hole.
                val blocked = gate(state, Skill.SELF_FEED)
                    ?: if (st.satiety >= 96f) "already full" else null
                larder.forEachIndexed { rank, food ->
                    out += Option(
                        kind = ActivityKind.EAT,
                        // A narrow spread, so the ranking is real but every one of them still
                        // plainly reads as "eat" against the other things it could be doing.
                        utility = (want * (1f - rank * SECOND_HELPING_PENALTY)).coerceIn(0f, 1f),
                        durationSeconds = EAT_SECONDS,
                        target = food.id,
                        reason = "I was down to ${pct(st.satiety)} and there was a ${food.name} in the tin.",
                        blockedBy = blocked,
                    )
                }
            } else {
                // Foraging is the pantry's understudy: slower, thinner, and only worth learning
                // because the alternative is waiting for a keeper who is not coming.
                out += Option(
                    kind = ActivityKind.EAT,
                    utility = want * 0.62f,
                    durationSeconds = FORAGE_SECONDS,
                    target = FORAGE_TARGET,
                    reason = "The tin was empty and I was on ${pct(st.satiety)}, so I went and found something.",
                    blockedBy = gate(state, Skill.FORAGE),
                )
            }
        }

        // ---- sleep --------------------------------------------------------------------
        // Only ever offered when the room is already a place sleep can happen. That is not a
        // nicety: Simulation wakes anything that is asleep in daylight with the lights on, so an
        // afternoon nap would be undone on the same step and re-taken on the next one, forever.
        if (!state.isSleeping) {
            val want = (0.55f * tired + if (night) 0.45f else 0f).coerceIn(0f, 1f)
            out += Option(
                kind = ActivityKind.SLEEP,
                utility = want,
                durationSeconds = SLEEP_SECONDS,
                target = null,
                reason = if (night) {
                    "It had gone dark and I was down to ${pct(st.energy)}, so I turned in."
                } else {
                    "The lights were off and I was running on ${pct(st.energy)}."
                },
                blockedBy = gate(state, Skill.SELF_SETTLE) ?: when {
                    !(night || state.lightsOff) -> "it is broad daylight"
                    st.energy >= 90f -> "wide awake"
                    else -> null
                },
            )
        }

        // ---- upkeep -------------------------------------------------------------------
        if (state.poops > 0 || grubby > 0.3f) {
            out += Option(
                kind = ActivityKind.TIDY,
                utility = ((state.poops / 3f).coerceAtMost(1f) * 0.62f + grubby * 0.25f).coerceIn(0f, 1f),
                durationSeconds = TIDY_SECONDS,
                target = null,
                reason = if (state.poops == 1) {
                    "There was a mess on the floor, so I saw to it."
                } else {
                    "There were ${state.poops} messes on the floor, so I set about them."
                },
                blockedBy = gate(state, Skill.TIDY_UP)
                    ?: if (state.poops == 0) "the floor is clear" else null,
            )
        }

        if (st.hygiene < 80f) {
            out += Option(
                kind = ActivityKind.GROOM,
                utility = (((100f - st.hygiene) / 100f) * 0.72f).coerceIn(0f, 1f),
                durationSeconds = GROOM_SECONDS,
                target = null,
                reason = "My coat was down to ${pct(st.hygiene)}, so I gave it a proper wash.",
                blockedBy = gate(state, Skill.SELF_GROOM),
            )
        }

        if (state.isSick || st.health < 60f) {
            val dose = bestMedicine(state)
            out += Option(
                kind = ActivityKind.MEDICATE,
                utility = if (state.isSick) 0.92f else 0.48f,
                durationSeconds = MEDICATE_SECONDS,
                target = dose?.id,
                reason = if (state.isSick) {
                    "I felt rotten, and there was a ${dose?.name ?: "dose"} in the cupboard."
                } else {
                    "I was not right at ${pct(st.health)} health, so I took something for it."
                },
                blockedBy = gate(state, Skill.MEDICATE)
                    ?: if (dose == null) "the cupboard is bare" else null,
            )
        }

        // ---- the discretionary half ---------------------------------------------------
        // All of it is FULL-autonomy only. Someone who asked for "just handle the chores" did not
        // ask for a pet that wanders off, and PetState.ASSIST_SKILLS says so for the skills; these
        // three have no skill of their own, so they have to say it themselves.
        val assisting = if (state.autonomy != Autonomy.FULL) "not while it is only assisting" else null

        // A bored creature picks something off the shelf rather than playing nothing in
        // particular. The choice is settled rather than rolled, because this runs once for the
        // decision and again for the considerations screen, and a creature whose stated
        // preference changed between two reads would be a screen arguing with itself.
        val fancied = SoloPlay.choice(state)
        out += Option(
            kind = ActivityKind.PLAY,
            utility = (bored * (0.55f + 0.45f * g.vigor) * spare).coerceIn(0f, 1f),
            durationSeconds = PLAY_SECONDS,
            target = fancied.id,
            reason = SoloPlay.reason(state, fancied),
            blockedBy = assisting ?: if (st.energy < 25f) "too tired for it" else null,
        )

        out += Option(
            kind = ActivityKind.STUDY,
            utility = ((0.26f + 0.30f * g.curiosity + 0.24f * g.wit) * spare).coerceIn(0f, 1f),
            durationSeconds = STUDY_SECONDS,
            target = null,
            reason = "Nothing was pressing, so I sat down with my book.",
            blockedBy = assisting ?: when {
                st.energy < 15f -> "too tired to concentrate"
                state.skills.size >= Skill.entries.size -> "there is nothing left to learn"
                else -> null
            },
        )

        out += Option(
            kind = ActivityKind.EXPLORE,
            utility = ((0.16f + 0.34f * g.curiosity) * spare).coerceIn(0f, 1f),
            durationSeconds = EXPLORE_SECONDS,
            target = null,
            reason = "Nothing needed me, so I went to see what was about.",
            blockedBy = assisting ?: if (st.energy < 30f) "too tired to wander" else null,
        )

        // ---- company ------------------------------------------------------------------
        val here = state.presentPals
        val friend = here.maxByOrNull { it.affinity }
        if (friend != null) {
            out += Option(
                kind = ActivityKind.SOCIALISE,
                utility = (0.30f + 0.45f * g.sociability + 0.25f * bored).coerceIn(0f, 1f) * spare,
                durationSeconds = SOCIAL_SECONDS,
                target = friend.id,
                reason = "${friend.name} was in the room, and I wanted the company.",
                blockedBy = gate(state, Skill.SOCIALISE),
            )
        }
        val sweetheart = here.firstOrNull { it.canCourt }
        if (sweetheart != null) {
            out += Option(
                kind = ActivityKind.COURT,
                utility = (0.52f + 0.30f * g.sociability).coerceIn(0f, 1f) * spare,
                durationSeconds = SOCIAL_SECONDS,
                target = sweetheart.id,
                reason = "${sweetheart.name} keeps coming back, and I am rather taken with them.",
                blockedBy = gate(state, Skill.COURT) ?: when {
                    state.stage.order < LifeStage.TEEN.order -> "far too young for that"
                    st.satiety < 30f -> "too hungry for romance"
                    else -> null
                },
            )
        }

        // The floor. Something has to win, and a pet that stands perfectly still between two
        // needs looks broken rather than content.
        out += Option(
            kind = ActivityKind.IDLE,
            utility = 0.12f,
            durationSeconds = IDLE_SECONDS,
            target = null,
            reason = "Nothing wanted me, so I sat and watched the room.",
            blockedBy = null,
        )

        // Everything the line has learned is applied here, in one place, rather than woven into
        // each option's own arithmetic. Two reasons: every option gets the same treatment whether
        // or not whoever wrote it remembered lessons existed, and the bias stays visible as a
        // single step that can be turned off, read, and tested on its own.
        //
        // Multiplicative on purpose. A flat bonus would make a well-bred pet eat when it was not
        // hungry, which reads as a bug; multiplying an existing want makes it eat *earlier*, which
        // reads as having learned something from a parent that starved.
        if (state.lessons.isEmpty()) return out
        for (i in out.indices) {
            val option = out[i]
            val bias = Lineage.biasFor(state.lessons, option.kind)
            if (bias != 1f) {
                out[i] = Option(
                    kind = option.kind,
                    utility = (option.utility * bias).coerceIn(0f, 1f),
                    durationSeconds = option.durationSeconds,
                    target = option.target,
                    reason = option.reason,
                    blockedBy = option.blockedBy,
                )
            }
        }
        return out
    }

    /**
     * The food that best fits a [deficit]-sized hole in the pet, or null when there is none.
     *
     * Walks the catalog rather than the inventory map so the choice never depends on the order
     * keys happen to sit in a save file — a seeded run has to replay identically.
     */
    /** How many foods the creature will hold in mind at once. More is a menu, not a decision. */
    private const val MAX_FOOD_CHOICES = 3

    /** How much each successive helping is discounted, so the ranking shows without dominating. */
    private const val SECOND_HELPING_PENALTY = 0.06f

    /** What is in reach, best first. */
    private fun foodChoices(state: PetState, deficit: Float): List<Item> =
        ItemCatalog.foods
            .filter { (state.inventory[it.id] ?: 0) > 0 }
            .sortedByDescending { appeal(it, deficit, state) }
            .take(MAX_FOOD_CHOICES)

    private fun bestFood(state: PetState, deficit: Float): Item? =
        foodChoices(state, deficit).firstOrNull()

    /**
     * How much this creature wants this particular food, rather than how well it fits.
     *
     * Fit alone made every creature identical at the tin: the same arithmetic, the same answer,
     * whatever it had been bred from. Appetite and the gourmand line now pull toward whatever is
     * nicest rather than whatever is most sensible, which is the point of having either — and a
     * small settled quirk means two creatures with the same genes still have different
     * favourites, so "he always goes for the cake" is a thing a player can notice.
     */
    private fun appeal(item: Item, deficit: Float, state: PetState): Float {
        // Reward filling the hole, penalise overshooting it: an animal with one stew and a
        // slightly empty stomach should reach for the berry.
        val fit = (minOf(item.satiety, deficit) - 0.35f * (item.satiety - deficit).coerceAtLeast(0f)) /
            deficit.coerceAtLeast(1f)
        val treat = (item.happiness / 40f).coerceIn(0f, 1f)
        val sweetTooth = 0.75f * state.genome.appetite +
            if (state.branch == EvolutionBranch.GOURMAND) 0.45f else 0f
        // Hunger beats taste, but only while the hunger is real. A creature two thirds empty eats
        // whatever fills it and there is nothing to discuss; a peckish one is exactly where a
        // preference has room to show. Without this the fit term wins at every level and the
        // genes are decoration — which is how the first version of this came out.
        val indulgence = 0.30f + 0.95f * (1f - (deficit / 100f).coerceIn(0f, 1f))
        return fit + indulgence * (treat * sweetTooth + FAVOURITE_WEIGHT * quirk(item, state))
    }

    /** How much of the choice is simply this creature's own taste. */
    private const val FAVOURITE_WEIGHT = 0.22f

    /**
     * A settled number in 0..1 for this creature and this food.
     *
     * Arithmetic rather than a roll, because [options] is scored repeatedly for one decision and
     * again for the screen that lists what the creature is weighing up. A favourite that changed
     * between two reads would not be a favourite.
     */
    private fun quirk(item: Item, state: PetState): Float {
        var h = item.id.hashCode().toLong() * 0x9E3779B9L +
            (state.genome.hue * 1000f).toLong() * 0x85EBCA6BL +
            state.generation * 0x27D4EB2FL
        h = h xor (h ushr 29)
        h *= -0x40A7B892E31B1A47L
        h = h xor (h ushr 32)
        return abs(h % 1_000).toFloat() / 1_000f
    }

    private fun bestMedicine(state: PetState): Item? =
        ItemCatalog.ofKind(ItemKind.MEDICINE)
            .filter { it.health > 0f && (state.inventory[it.id] ?: 0) > 0 }
            .minByOrNull { it.health }

    // ------------------------------------------------------------------ committing

    /**
     * Starts [option], or returns null when the world refused it after all.
     *
     * Everything discrete happens here, at the moment of choosing, and through [CareActions]:
     * the meal is eaten, the dose swallowed and the mess scooped as the activity begins, and the
     * committed stretch afterwards is the creature being visibly busy with it. Draining a meal
     * out over its duration was the alternative and it was worse in every direction — it forked
     * the feeding arithmetic in two, and a pet interrupted halfway through got a half-priced meal
     * for a whole item.
     *
     * Sleep is the one flag that is only ever switched *on*. Waking belongs to the simulation's
     * own sleep cycle, which runs after this one; if both were allowed an opinion they would take
     * turns flipping it and the pet would strobe. The brain also never offers sleep in conditions
     * that cycle would immediately undo, so the two can only ever agree.
     */
    private fun commit(
        state: PetState,
        option: Option,
        runnerUp: ActivityKind?,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState? {
        var s = state
        when (option.kind) {
            ActivityKind.EAT -> if (option.target != FORAGE_TARGET) {
                val itemId = option.target ?: return null
                val result = CareActions.feed(s, itemId)
                if (!result.accepted) return null
                events += result.events
                s = result.state
                s = s.copy(selfCareActions = s.selfCareActions + 1)
            } else {
                s = s.copy(selfCareActions = s.selfCareActions + 1)
            }

            ActivityKind.MEDICATE -> {
                val itemId = option.target ?: return null
                val result = CareActions.useMedicine(s, itemId)
                if (!result.accepted) return null
                events += result.events
                s = result.state.copy(selfCareActions = result.state.selfCareActions + 1)
            }

            // One pile per stint. Clearing the whole room in a single thought is a keeper's
            // action; a creature tidying up should visibly take as long as the mess deserves.
            ActivityKind.TIDY -> {
                val result = CareActions.scoopPoop(s)
                if (!result.accepted) return null
                events += result.events
                s = result.state.copy(selfCareActions = result.state.selfCareActions + 1)
            }

            ActivityKind.SLEEP -> {
                s = s.copy(isSleeping = true, selfCareActions = s.selfCareActions + 1)
                // The sleep cycle stays silent when it finds the pet already asleep, so the
                // announcement has to come from whoever actually made the decision.
                events += GameEvent.FellAsleep
            }

            ActivityKind.GROOM -> s = s.copy(selfCareActions = s.selfCareActions + 1)

            ActivityKind.SOCIALISE, ActivityKind.COURT -> {
                if (option.target == null) return null
                s = s.copy(socialActions = s.socialActions + 1)
            }

            else -> Unit
        }

        val decision = Decision(
            atSeconds = s.ageSeconds,
            kind = option.kind,
            reason = option.reason,
            utility = option.utility,
            runnerUp = runnerUp,
        )
        // Idling logs the moment it starts idling and then shuts up. Forty consecutive lines of
        // "nothing wanted me" is not an account of a life, and it would push everything the pet
        // actually did off the end of the log.
        if (option.kind != ActivityKind.IDLE || state.decisions.lastOrNull()?.kind != ActivityKind.IDLE) {
            events += GameEvent.Decided(decision)
            s = s.copy(decisions = (s.decisions + decision).takeLast(Simulation.MAX_DECISION_LOG))
        }
        return s.copy(
            activity = Activity(
                kind = option.kind,
                startedAtSeconds = s.ageSeconds,
                // A few seconds of slack, so two pets on the same schedule drift apart instead of
                // marching in step.
                endsAtSeconds = s.ageSeconds + option.durationSeconds + random.nextInt(0, 7),
                targetId = option.target,
            ),
        )
    }

    // ------------------------------------------------------------------ running

    /**
     * One step of whatever is already under way.
     *
     * The kinds that had their whole effect at the moment of choosing return the state untouched
     * rather than copying it: this runs up to two thousand times in a catch-up loop, and the
     * commonest case must not allocate.
     */
    private fun run(
        state: PetState,
        activity: Activity,
        dt: Long,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        val d = dt.toFloat()
        val st = state.stats
        return when (activity.kind) {
            // Studying and company are their own systems; the brain's job was choosing them.
            ActivityKind.STUDY -> Learning.progress(state, dt, events)

            ActivityKind.SOCIALISE, ActivityKind.COURT -> {
                val palId = activity.targetId ?: return state.copy(activity = null)
                Colony.interact(state, palId, activity.kind, dt, random, events)
            }

            ActivityKind.EAT -> if (activity.targetId == FORAGE_TARGET) {
                // Thin stuff, and slow. Foraging has to be worth learning without ever being
                // worth preferring to a stocked pantry.
                state.copy(
                    stats = st.copy(
                        satiety = st.satiety + 0.20f * d,
                        happiness = st.happiness + 0.01f * d,
                        energy = st.energy - 0.02f * d,
                    ).coerced(),
                )
            } else {
                state
            }

            ActivityKind.PLAY -> state.copy(
                stats = st.copy(
                    happiness = st.happiness + 0.09f * d * state.species.playBias,
                    energy = st.energy - 0.035f * d,
                    satiety = st.satiety - 0.010f * d,
                ).coerced(),
            )

            ActivityKind.GROOM -> state.copy(
                stats = st.copy(
                    hygiene = st.hygiene + 0.34f * d,
                    happiness = st.happiness + 0.02f * d,
                ).coerced(),
            )

            ActivityKind.EXPLORE -> state.copy(
                stats = st.copy(
                    happiness = st.happiness + 0.05f * d,
                    energy = st.energy - 0.030f * d,
                    satiety = st.satiety - 0.008f * d,
                ).coerced(),
            )

            else -> state
        }
    }

    /** What the pet has to say for itself as an activity runs out. */
    private fun closingNote(activity: Activity): String = when (activity.kind) {
        ActivityKind.EAT ->
            if (activity.targetId == FORAGE_TARGET) "Found enough out there to get by." else "Licked the bowl clean."
        ActivityKind.SLEEP -> "Slept it off."
        ActivityKind.PLAY ->
            activity.targetId?.let { MiniGame.byId(it) }?.let { SoloPlay.note(it) }
                ?: "That was a good runaround."
        ActivityKind.GROOM -> "Much better."
        ActivityKind.TIDY -> "That is one mess fewer."
        ActivityKind.STUDY -> "Head is full."
        ActivityKind.MEDICATE -> "Horrid, but it helped."
        ActivityKind.SOCIALISE -> "Good to see them."
        ActivityKind.COURT -> "That went rather well."
        ActivityKind.EXPLORE -> "Nothing out there today, but I looked."
        ActivityKind.IDLE -> "Right, what next."
    }
}
