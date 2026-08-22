package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * Something the creature can find out for itself, without changing anything.
 *
 * These are the read half of the tools a planning mind gets. They are safe by construction —
 * every one of them is a pure function of state that returns a sentence — which is why they can
 * be handed over freely while acting stays behind [Brain.adopt]. A mind that can *look* needs no
 * permission; a mind that can *do* needs checking.
 */
@Serializable
enum class ToolId(val displayName: String, val description: String) {
    LOOK_IN_PANTRY("Look in the pantry", "What there is to eat, and how much of it."),
    CHECK_SELF("Take stock", "How it feels right now, need by need."),
    CHECK_COMPANY("See who is here", "Who is in the room and how well they are known."),
    RECALL_DIARY("Remember", "The last few things worth remembering."),
    CHECK_SKILLS("Know itself", "What it has learned to do without being asked."),
    CHECK_NEST("Look at the nest", "Whether anything is waiting to hatch."),
}

/** One step of a plan: what to do, and the creature's own reason for it. */
@Serializable
data class PlanStep(
    val kind: ActivityKind,
    val why: String,
)

/**
 * A short sequence the creature means to work through, and what it is for.
 *
 * Plans exist because a single choice cannot express an intention. "Eat" is a reaction; "eat, then
 * tidy up, then go and find Moss, because I want to be presentable before company" is a creature
 * with an afternoon in mind. That difference is the whole reason to give a mind more than one
 * decision at a time.
 *
 * A plan is a *statement of intent*, never an authorisation. Each step is re-checked against the
 * rules at the moment it is executed, exactly as a single remote choice is, so a plan made against
 * a full pantry cannot produce a meal from an empty one three steps later.
 */
@Serializable
data class Plan(
    /** What the creature is trying to achieve, in its own words. */
    val goal: String,
    val steps: List<PlanStep>,
    val madeAtSeconds: Long,
    /** How many steps have been carried out. Never rewound. */
    val done: Int = 0,
    /**
     * The creature's care score when the plan was made, 0..1.
     *
     * Kept so the plan can be *graded* when it finishes rather than merely ticked off. Without a
     * before, "did that help?" is unanswerable, and a creature that cannot answer it can only ever
     * learn from what happened to its parents — never from anything it did itself.
     */
    val careAtStart: Float = 0f,
) {
    val remaining: List<PlanStep> get() = steps.drop(done)
    val isFinished: Boolean get() = done >= steps.size

    /** Expired plans are abandoned rather than resumed; see [Errands.PLAN_LIFETIME_SECONDS]. */
    fun isStale(ageSeconds: Long): Boolean =
        ageSeconds - madeAtSeconds > Errands.PLAN_LIFETIME_SECONDS
}

/**
 * The creature's small agentic loop: look, plan, work through it, and give up sensibly.
 *
 * Everything here is pure and free of the network. A planning mind lives elsewhere and produces a
 * [Plan]; this decides what a plan is allowed to be, what a tool is allowed to say, and when a
 * plan stops being worth following. Keeping those rules here rather than in the client means they
 * are the same whichever mind proposed the plan, and that they can be tested without one.
 */
object Errands {

    /**
     * Longest plan a creature will hold. Three steps is roughly an afternoon at this game's pace,
     * and it is short enough that the world has not changed out from under the last step.
     */
    const val MAX_STEPS = 3

    /**
     * A plan older than this is abandoned. Needs move; a plan made two hours ago was made about a
     * creature that no longer exists, and following it would look less like intent than like
     * sleepwalking.
     */
    const val PLAN_LIFETIME_SECONDS = 2_700L

    /** A goal has to fit a line on the screen. */
    const val MAX_GOAL_CHARS = 120

    /** Answers a read-only tool. Pure: looking never changes anything. */
    fun answer(tool: ToolId, state: PetState, config: GameConfig): String = when (tool) {
        ToolId.LOOK_IN_PANTRY -> {
            val food = state.inventory
                .filterValues { it > 0 }
                .mapNotNull { (id, n) -> ItemCatalog[id]?.let { "${it.name} x$n" } }
            if (food.isEmpty()) "The pantry is empty." else "In the pantry: ${food.joinToString(", ")}."
        }

        ToolId.CHECK_SELF -> buildString {
            val s = state.stats
            append("Satiety ${s.satiety.toInt()}%, ")
            append("happiness ${s.happiness.toInt()}%, ")
            append("energy ${s.energy.toInt()}%, ")
            append("hygiene ${s.hygiene.toInt()}%, ")
            append("health ${s.health.toInt()}%.")
            if (state.isSick) append(" Unwell.")
            if (state.poops > 0) append(" ${state.poops} mess(es) on the floor.")
        }

        ToolId.CHECK_COMPANY -> {
            val here = state.presentPals
            if (here.isEmpty()) {
                "Nobody is here."
            } else {
                here.joinToString(", ") { "${it.name} (${it.relation.displayName}, ${it.affinity.toInt()}%)" }
            }
        }

        ToolId.RECALL_DIARY -> {
            val lines = state.chronicle.takeLast(4).map { it.text }
            if (lines.isEmpty()) "Nothing written down yet." else lines.joinToString(" ")
        }

        ToolId.CHECK_SKILLS -> {
            if (state.skills.isEmpty()) {
                "It has not learned to do anything for itself yet."
            } else {
                "It can: " + state.skills.joinToString(", ") { it.displayName.lowercase() } + "."
            }
        }

        ToolId.CHECK_NEST -> {
            val next = Colony.nextHatchInSeconds(state)
            when {
                state.nest.isEmpty() -> "The nest is empty."
                next == null -> "There is an egg in the nest."
                next <= 0L -> "An egg is ready to hatch."
                else -> "An egg will hatch in about ${next / 60} minutes."
            }
        }
    }

    /**
     * Bounds a plan that arrived from a mind somewhere else, and stamps it against [state].
     *
     * Returns null for anything that is not worth holding onto. As with [Lineage.sanitise], every
     * route in passes through here — a model, a save from an older build, or a future local
     * planner — so there is one place where a plan is decided to be reasonable.
     *
     * It takes the creature rather than a bare number of seconds, and that is the whole reason
     * the signature looks like this. The stamp decides staleness: a plan stamped zero is instantly
     * expired for any creature older than [PLAN_LIFETIME_SECONDS], which is most of them, and the
     * failure is silent because the local brain covers for it perfectly — no crash, no log, just a
     * creature that mysteriously never has an errand. A `Long` parameter accepts any number a
     * caller happens to have; a `PetState` can only be the creature this plan is for.
     */
    fun sanitise(plan: Plan, state: PetState): Plan? {
        val goal = plan.goal.trim().take(MAX_GOAL_CHARS)
        if (goal.isEmpty()) return null
        val steps = plan.steps
            // Idling is not a plan. A mind that proposes three of them has proposed nothing, and
            // letting it through would park the creature for the whole plan's lifetime.
            .filter { it.kind != ActivityKind.IDLE }
            .map { PlanStep(it.kind, it.why.trim().take(MAX_GOAL_CHARS)) }
            .take(MAX_STEPS)
        if (steps.isEmpty()) return null
        return Plan(
            goal = goal,
            steps = steps,
            madeAtSeconds = state.ageSeconds,
            // Always starts unspent; a plan arriving with steps already marked done would let a
            // mind skip past the ones it did not want checked.
            done = 0,
            careAtStart = state.stats.careScore,
        )
    }

    /**
     * The step to attempt now, or null when there is nothing left to do.
     *
     * Returning a step is not a promise it will happen — [Brain.adopt] still has the final say,
     * and refusing is normal rather than exceptional.
     */
    fun nextStep(state: PetState): PlanStep? {
        val plan = state.plan ?: return null
        if (plan.isFinished || plan.isStale(state.ageSeconds)) return null
        return plan.remaining.firstOrNull()
    }

    /**
     * Marks the head of the plan as carried out, and grades the plan when the last step lands.
     *
     * Grading is the point of the whole type carrying [Plan.careAtStart]. A creature that only ever
     * learned from its parents' deaths could get wiser exactly once per generation; one that can
     * tell whether its own afternoon went well learns from every afternoon. That is the difference
     * between inheriting judgement and developing it.
     *
     * Only clear improvement teaches anything. Needs drift on their own, so a threshold below the
     * noise would reward the creature for the passage of time and the lesson would mean nothing.
     */
    fun advance(state: PetState, events: MutableList<GameEvent>): PetState {
        val plan = state.plan ?: return state
        val moved = plan.copy(done = plan.done + 1)
        if (!moved.isFinished) return state.copy(plan = moved)

        var s = state.copy(plan = null, plansFinished = state.plansFinished + 1)
        val gained = s.stats.careScore - plan.careAtStart
        if (gained < WORTH_LEARNING_FROM) return s

        // Credited to what the plan actually spent its steps on. The commonest kind wins, because
        // a plan is one intention rather than three separate ones, and splitting the credit three
        // ways would make every lesson too weak to notice.
        val kind = moved.steps
            .mapNotNull { lessonKindFor(it.kind) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: return s

        val learned = Lesson(
            kind = kind,
            text = "That worked. ${plan.goal}",
            // Deliberately weaker than a lesson bought with a parent's life. Experience should
            // accumulate; a single good afternoon should not outweigh how the last one died.
            strength = (gained * EXPERIENCE_SCALE).coerceIn(0f, MAX_EXPERIENCE_STRENGTH),
            fromGeneration = s.generation,
        )
        Lineage.sanitise(learned)?.let {
            s = s.copy(lessons = Lineage.inherit(s.lessons, listOf(it)))
            events += GameEvent.LearnedFromExperience(it)
        }
        return s
    }

    /** Which lesson an activity speaks to, or null when it teaches nothing in particular. */
    private fun lessonKindFor(kind: ActivityKind): LessonKind? = when (kind) {
        ActivityKind.EAT -> LessonKind.EAT_SOONER
        ActivityKind.SLEEP -> LessonKind.REST_SOONER
        ActivityKind.TIDY, ActivityKind.GROOM -> LessonKind.TIDY_SOONER
        ActivityKind.MEDICATE -> LessonKind.GUARD_HEALTH
        ActivityKind.STUDY -> LessonKind.STUDY_HARDER
        ActivityKind.SOCIALISE, ActivityKind.COURT -> LessonKind.SEEK_COMPANY
        ActivityKind.PLAY -> LessonKind.PLAY_MORE
        ActivityKind.EXPLORE, ActivityKind.IDLE -> null
    }

    /**
     * How much the care score has to rise across a plan before it counts as having worked.
     * Below this the creature would be learning from the ordinary drift of its own needs.
     */
    const val WORTH_LEARNING_FROM = 0.05f

    /** Turns a care improvement into a lesson strength. */
    private const val EXPERIENCE_SCALE = 1.2f

    /**
     * Ceiling on a lesson learned from one afternoon. Well under what a parent's death teaches,
     * so experience accumulates over many plans rather than arriving in one.
     */
    const val MAX_EXPERIENCE_STRENGTH = 0.22f

    /**
     * Drops a plan that cannot be continued.
     *
     * Called when a step is refused. The alternative — retrying the same step until it becomes
     * legal — produces a creature that stands in front of an empty pantry insisting on lunch,
     * which is not persistence, it is a stuck loop with a story attached.
     */
    fun abandon(state: PetState, events: MutableList<GameEvent>): PetState {
        val plan = state.plan ?: return state
        events += GameEvent.PlanAbandoned(plan.goal)
        return state.copy(plan = null)
    }

    /** Clears a plan that has run out of time, so a stale one never blocks a fresh one. */
    fun expireIfStale(state: PetState, events: MutableList<GameEvent>): PetState {
        val plan = state.plan ?: return state
        if (!plan.isStale(state.ageSeconds)) return state
        events += GameEvent.PlanAbandoned(plan.goal)
        return state.copy(plan = null)
    }
}
