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
     * The creature's care score when the plan was made, 0..1, or [Errands.UNGRADED].
     *
     * Kept so the plan can be *graded* when it finishes rather than merely ticked off. Without a
     * before, "did that help?" is unanswerable, and a creature that cannot answer it can only ever
     * learn from what happened to its parents — never from anything it did itself.
     *
     * The default is deliberately not zero. A plan that arrives without this field — from an older
     * save, or from any caller that did not go through [Errands.sanitise] — would otherwise appear
     * to have started from total neglect, so every such plan would look like a triumph: a lesson
     * every time, and a plan that extended itself until it hit its cap. Better to say plainly that
     * there is no before, and grade nothing.
     */
    val careAtStart: Float = Errands.UNGRADED,
    /**
     * How many times the creature has grown this plan a further step for itself.
     *
     * Bounded by [Errands.MAX_EXTENSIONS]. A plan that could extend without limit would be a
     * creature that never finished anything and therefore never graded anything — the opposite of
     * the point, which is to let a plan that is evidently working run its course.
     */
    val extensions: Int = 0,
) {
    val remaining: List<PlanStep> get() = steps.drop(done)
    val isFinished: Boolean get() = done >= steps.size

    /**
     * Expired plans are abandoned rather than resumed; see [Errands.lifetimeFor].
     *
     * The budget is per step rather than per plan, because a six-step plan given a three-step
     * plan's clock would be abandoned halfway every single time — and it would look like the
     * creature losing interest rather than like a deadline nobody could have met.
     */
    fun isStale(ageSeconds: Long): Boolean =
        ageSeconds - madeAtSeconds > Errands.lifetimeFor(steps.size)
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
     * Longest plan a newly hatched creature will hold. Three steps is roughly an afternoon at this
     * game's pace, and it is short enough that the world has not changed out from under the last
     * step.
     */
    const val MAX_STEPS = 3

    /**
     * Longest plan a fully clever one will hold.
     *
     * Intellect has to buy something you can see, and this is the most visible thing it buys: a
     * bright creature does not merely think more often, it holds a longer thought. The ceiling is
     * six rather than open-ended because every step is re-checked against a world that keeps
     * moving, and past six the tail of a plan is reliably about a creature that no longer exists.
     */
    const val MAX_STEPS_BRIGHT = 6

    /** Below this, a plan is followed but never grown. */
    const val MIN_INTELLECT_TO_EXTEND = 55f

    /**
     * How many times one plan may grow itself.
     *
     * A plan that could extend forever would never be graded, and grading is where the creature
     * learns from its own afternoons rather than only from its parents' deaths.
     */
    const val MAX_EXTENSIONS = 3

    /**
     * How long each step of a plan gets before the whole thing is abandoned. Needs move; a step
     * still pending an hour later was queued for a creature that no longer exists, and following
     * it would look less like intent than like sleepwalking.
     */
    const val SECONDS_PER_STEP = 900L

    /** The clock a plan of this many steps is held to. */
    fun lifetimeFor(steps: Int): Long = SECONDS_PER_STEP * steps.coerceAtLeast(1)

    /**
     * Longest plan this creature can hold, from three steps at hatching to [MAX_STEPS_BRIGHT] at
     * full intellect.
     */
    fun maxStepsFor(intellect: Float): Int {
        val t = intellect.coerceIn(0f, 100f) / 100f
        return MAX_STEPS + ((MAX_STEPS_BRIGHT - MAX_STEPS) * t).toInt()
    }

    /** A goal has to fit a line on the screen. */
    const val MAX_GOAL_CHARS = 120

    /**
     * "There is no before." A plan carrying this is followed but never graded — no lesson from it,
     * and no growing itself a further step, because both of those are claims about improvement and
     * there is nothing to measure the improvement against.
     */
    const val UNGRADED = -1f

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
     * expired for any creature older than a step's worth of seconds, which is most of them, and the
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
            .take(maxStepsFor(state.intellect))
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
    fun advance(
        state: PetState,
        events: MutableList<GameEvent>,
        grow: (PetState, Plan) -> PlanStep? = { _, _ -> null },
    ): PetState {
        val plan = state.plan ?: return state
        val moved = plan.copy(done = plan.done + 1)
        if (!moved.isFinished) return state.copy(plan = moved)

        // An ungraded plan reports no gain rather than a huge one; see [Plan.careAtStart].
        val gained = if (plan.careAtStart < 0f) 0f else state.stats.careScore - plan.careAtStart

        // A plan that is visibly working, in a creature bright enough to notice, grows rather than
        // ends. This is the one place the creature decides for itself how long a thought is, and
        // it deliberately needs no model at all: the same evidence that would earn it a lesson is
        // the evidence that it is worth carrying on.
        if (mayExtend(state, moved, gained)) {
            grow(state, moved)?.let { next ->
                val grown = moved.copy(steps = moved.steps + next, extensions = moved.extensions + 1)
                events += GameEvent.PlanExtended(grown.goal, grown.steps.size)
                return state.copy(plan = grown)
            }
        }

        var s = state.copy(plan = null, plansFinished = state.plansFinished + 1)
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

    /**
     * Whether a finished plan should carry on instead of stopping.
     *
     * Three conditions, and each one is load-bearing. It has to be *working*, or the creature is
     * merely stubborn. It has to be bright enough, or intellect buys nothing visible. And it has
     * to be under [MAX_EXTENSIONS], because a plan that never ends is never graded — and the
     * grading is the only way the creature learns from an afternoon of its own.
     */
    fun mayExtend(state: PetState, plan: Plan, gained: Float): Boolean =
        gained >= WORTH_LEARNING_FROM &&
            state.intellect >= MIN_INTELLECT_TO_EXTEND &&
            plan.extensions < MAX_EXTENSIONS &&
            plan.steps.size < maxStepsFor(state.intellect) + MAX_EXTENSIONS &&
            !plan.isStale(state.ageSeconds)

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
