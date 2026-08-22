package com.neopal.pet.domain

/** Where one step of a plan stands, which is the only thing a reader needs from a step. */
enum class PlanStepState { DONE, CURRENT, PENDING }

/** One step, plus where it stands. */
data class PlanStepView(val step: PlanStep, val state: PlanStepState)

/**
 * A [Plan] arranged for reading.
 *
 * The plan was the one thing the creature does that had no way of being seen. The decision log
 * answers "why did it just do that" one line at a time, and that is a different question: a log
 * of three entries reads as three unrelated reactions even when they were one intention. "Eat,
 * then tidy up, then go and find Moss, because I want to be presentable before company" is only
 * legible as a whole, and a whole is what nothing rendered.
 *
 * All of the arithmetic lives here rather than in the panel, because "which step is it on", "how
 * far through is it" and "how long has it got" are questions about the plan and not about a
 * screen — and because the panel cannot be tested and this can.
 */
data class PlanBoard(
    /** What the creature is trying to achieve, in its own words. */
    val goal: String,
    val steps: List<PlanStepView>,
    val done: Int,
    /** How many times the creature has grown this plan a further step for itself. */
    val extensions: Int,
    /**
     * Seconds before the whole plan is abandoned, from [Errands.lifetimeFor].
     *
     * Zero is still alive and negative has run out, because [Plan.isStale] draws its line at
     * *strictly* past the lifetime and a countdown that disagreed with it by one second would be
     * a screen still ticking on a plan the brain had already dropped.
     */
    val secondsLeft: Long,
) {
    val total: Int get() = steps.size
    val isOutOfTime: Boolean get() = secondsLeft < 0L

    /** 0..1, for a meter. */
    val fraction: Float get() = if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** The step being worked on, or null when the plan is finished. */
    val current: PlanStep? get() = steps.firstOrNull { it.state == PlanStepState.CURRENT }?.step

    companion object {
        /**
         * Arranges [plan] as of [ageSeconds], or null when there is nothing to show.
         *
         * Null rather than an empty board on purpose: a plan needs a remote mind, and a panel that
         * reads "no plan yet" for every player who never pasted an API key is not an empty state,
         * it is an advertisement on a screen that is supposed to be about their creature.
         */
        fun of(plan: Plan?, ageSeconds: Long): PlanBoard? {
            if (plan == null) return null
            if (plan.steps.isEmpty()) return null
            val done = plan.done.coerceIn(0, plan.steps.size)
            return PlanBoard(
                goal = plan.goal,
                steps = plan.steps.mapIndexed { index, step ->
                    PlanStepView(
                        step = step,
                        state = when {
                            index < done -> PlanStepState.DONE
                            index == done -> PlanStepState.CURRENT
                            else -> PlanStepState.PENDING
                        },
                    )
                },
                done = done,
                extensions = plan.extensions,
                // Deliberately derived from the same call Plan.isStale measures against, so the
                // number on screen reaches zero exactly when the creature gives up on the plan
                // rather than a little before or after it.
                secondsLeft = Errands.lifetimeFor(plan.steps.size) - (ageSeconds - plan.madeAtSeconds),
            )
        }
    }
}
