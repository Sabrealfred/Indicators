package com.neopal.pet.domain

import kotlin.math.roundToLong

/**
 * Getting cleverer, and turning that into things the creature can actually do.
 *
 * Two numbers carry the whole system. [PetState.intellect] is the ceiling — it decides which
 * skills are within reach at all — and [PetState.studySeconds] is the grind toward the one skill
 * being worked on right now. Splitting them is the point: intellect is slow, shared and can never
 * be lost, so it reads as the pet growing up, while a study session is short, visible and finishes
 * with something new on the skills screen. One number doing both jobs was the first attempt and it
 * failed in the obvious way — either every skill arrived at once at the moment the bar filled, or
 * nothing arrived for an hour.
 *
 * Nothing here reads a clock or a random source. The tick loop hands over a [dt] and gets a new
 * state back, which is what lets a week of absence be caught up in two thousand steps and still
 * land on exactly the state a player who sat and watched would have seen.
 */
object Learning {

    // ---- tunables ---------------------------------------------------------------------------

    /** Intellect is a 0..100 stat like every other, so the UI never needs a special case. */
    const val MAX_INTELLECT = 100f

    /**
     * Where passive drift gives out.
     *
     * Simply living teaches a creature the basics and then stops teaching it anything. Without a
     * separate, lower ceiling for [observe], a pet left alone long enough would eventually out-read
     * a pet that was actually taught, which would make the whole study loop optional.
     */
    const val OBSERVE_CEILING = 45f

    /** Intellect per second of study at an average wit. Roughly an hour of study to reach 50. */
    private const val STUDY_INTELLECT_RATE = 0.03f

    /** Intellect per second of merely living. About a sixth of studying, and capped much lower. */
    private const val OBSERVE_INTELLECT_RATE = 0.006f

    /** [Skill.READ] does what it says on the card: everything after it is learned twice as fast. */
    private const val READ_MULTIPLIER = 2f

    /** An ill pet still takes some of it in. It is not a wasted afternoon, only a poor one. */
    private const val SICK_MULTIPLIER = 0.5f

    /** Below these the pet cannot concentrate at all. Matched to the tired and hungry moods. */
    private const val MIN_SATIETY = 20f
    private const val MIN_ENERGY = 20f

    /** How long one player-led lesson lasts, in pet seconds. */
    const val LESSON_SECONDS = 20L

    /**
     * What a lesson is worth against the same time spent studying alone.
     *
     * Three, because someone is answering the questions instead of the pet guessing. Much higher
     * and teaching stops being an accelerator and becomes the only sensible way to play.
     */
    private const val LESSON_MULTIPLIER = 3f

    /** A lesson costs the pet's attention, not the player's patience. See [teach]. */
    private const val LESSON_ENERGY = 5f
    private const val LESSON_BOND = 1.5f

    /** Study seconds per point of XP when a skill lands. Long skills are worth more. */
    private const val SECONDS_PER_XP = 24L
    private const val MIN_SKILL_XP = 10

    /**
     * The ladder, taken once.
     *
     * [Skill.ladder] sorts the enum on every read, and this runs inside a loop that can execute
     * two thousand times in one catch-up. The order is a property of the enum and cannot change at
     * runtime, so holding one snapshot costs a list and saves every tick from allocating one.
     */
    private val ladder: List<Skill> = Skill.ladder

    /**
     * Intellect values worth interrupting the player for: every fifth point, plus every point that
     * opens a skill gate. Sorted ascending so [crossedMilestone] can stop early.
     */
    private val milestones: FloatArray =
        (Skill.entries.map { it.intellectRequired } + (1..20).map { it * 5f })
            .distinct().sorted().toFloatArray()

    // ---- reading the state ------------------------------------------------------------------

    /**
     * The skill the pet would pick to work on next, or null when nothing is within reach.
     *
     * First reachable rung of the ladder, always. It has to be a pure function of the state and
     * nothing else: a target that re-rolled between ticks would move the progress bar backwards
     * while the player watched, and no amount of cleverness in the choice is worth that. Weighting
     * the pick by what the pet currently needs — foraging while hungry, medicating while ill — was
     * the alternative, and it produced exactly that flicker as the needs moved.
     *
     * Reach is about intellect only. Whether the pet is old enough, awake, or well enough to sit
     * down and study is a question for [progress] and [teach], which are the things that act.
     */
    fun nextSkill(state: PetState): Skill? =
        ladder.firstOrNull { it !in state.skills && state.intellect >= it.intellectRequired }

    /** True when [skill] could be learned as the state stands. See [whyBlocked] for the reason. */
    fun isReachable(state: PetState, skill: Skill): Boolean = whyBlocked(state, skill) == null

    /**
     * Why [skill] cannot be learned yet, or null when it can.
     *
     * The skills screen shows a locked row either way; the difference between "not yet" and "never
     * from here" is the only thing that tells a player whether to keep studying or to go and teach.
     */
    fun whyBlocked(state: PetState, skill: Skill): String? = when {
        skill in state.skills -> "${state.name} already knows this."
        !state.isMindAwake -> "${state.name} is still too young to learn this."
        state.intellect < skill.intellectRequired ->
            "Needs an intellect of ${skill.intellectRequired.toInt()} — " +
                "${state.name} is at ${state.intellect.toInt()}."
        else -> null
    }

    /**
     * How far through the current study session the pet is, 0..1.
     *
     * Zero when there is no target, which is the honest answer: a pet with nothing in reach has
     * not stalled at 99%, it simply is not studying anything.
     */
    fun studyFraction(state: PetState): Float {
        val target = state.studying ?: return 0f
        return (state.studySeconds.toFloat() / target.studySeconds.coerceAtLeast(1L)).coerceIn(0f, 1f)
    }

    /**
     * Study seconds banked per real second right now — wit, reading and health rolled together.
     *
     * Public because the stats screen wants to show it: "learns at 2.4x" is the single clearest
     * piece of evidence that breeding for wit did anything at all.
     */
    fun studyRate(state: PetState): Float {
        if (!canConcentrate(state)) return 0f
        val read = if (Skill.READ in state.skills) READ_MULTIPLIER else 1f
        val condition = if (state.isSick) SICK_MULTIPLIER else 1f
        return witFactor(state.genome) * read * condition
    }

    /** Whether a player-led lesson would be accepted. See [teachBlockedReason] for the refusal. */
    fun canTeach(state: PetState): Boolean = teachBlockedReason(state) == null

    /**
     * Why a lesson would be refused, or null when it would be accepted.
     *
     * A starving or exhausted pet is refused outright rather than taught slowly. Hunger and
     * tiredness are states the player can fix in one tap, and a lesson that quietly does almost
     * nothing teaches the player nothing either — being told to feed it first does.
     */
    fun teachBlockedReason(state: PetState): String? = when {
        state.isDead -> "${state.name} is no longer with us."
        state.isEgg -> "The egg is still warming up."
        !state.isMindAwake -> "${state.name} is far too young for lessons."
        state.isSleeping -> "${state.name} is fast asleep."
        state.stats.satiety < MIN_SATIETY -> "${state.name} is too hungry to take anything in."
        state.stats.energy < MIN_ENERGY -> "${state.name} is too tired to concentrate."
        else -> null
    }

    // ---- acting -----------------------------------------------------------------------------

    /**
     * Advances an in-progress study session by [dt] seconds.
     *
     * Two things happen at once and they are deliberately independent. Intellect always rises,
     * even when there is nothing left in reach to learn — otherwise a pet that had exhausted the
     * reachable ladder could never study its way up to the next gate, and would sit at 46 forever
     * looking at a skill needing 52. Banking toward a skill only happens when there is one.
     *
     * Nothing here touches the needs. Studying is tiring, but the tick loop already owns the decay
     * and a second drain applied here would quietly double-charge every autonomous pet.
     */
    fun progress(state: PetState, dt: Long, events: MutableList<GameEvent>): PetState {
        if (dt <= 0L) return state
        val rate = studyRate(state)
        if (rate <= 0f) return state

        var s = retarget(state)
        s = grow(s, STUDY_INTELLECT_RATE * rate * dt * approach(s.intellect, MAX_INTELLECT), events)
        // A tick can never bank nothing. Whole seconds are the only unit the save has and there is
        // nowhere to keep a fractional carry, so at one-second ticks a slow learner would round to
        // zero on every single one and never finish anything. The floor makes a dim or poorly pet
        // slow over any stretch long enough to measure, rather than permanently stuck.
        return bank(s, (rate * dt).roundToLong().coerceAtLeast(1L), events)
    }

    /**
     * A player-driven lesson: one short burst of teaching, worth more than the same time studying
     * alone, plus a little of the bond that comes from sitting down with something.
     *
     * The bound on spamming it is the pet's own energy rather than a cooldown. A cooldown would
     * need a timestamp on the save, and — worse — it would be a rule about the player rather than
     * about the creature. Energy is already there, already visible, and only comes back with
     * sleep, so a full night buys about a dozen lessons and then the answer is "it needs a nap",
     * which is a thing the game already knows how to say. Intellect gained this way runs through
     * the same saturating curve as everything else, so no number of lessons reaches 100.
     */
    fun teach(state: PetState, events: MutableList<GameEvent>): PetState {
        if (teachBlockedReason(state) != null) return state
        val rate = studyRate(state)
        if (rate <= 0f) return state

        var s = state.copy(
            stats = state.stats.copy(
                energy = state.stats.energy - LESSON_ENERGY,
                bond = state.stats.bond + LESSON_BOND,
            ).coerced(),
        )
        s = retarget(s)
        val worth = rate * LESSON_SECONDS * LESSON_MULTIPLIER
        s = grow(s, STUDY_INTELLECT_RATE * worth * approach(s.intellect, MAX_INTELLECT), events)
        return bank(s, worth.roundToLong().coerceAtLeast(1L), events)
    }

    /**
     * Slow passive intellect drift from simply living — playing, exploring, being talked to.
     *
     * Deliberately weak and capped at [OBSERVE_CEILING]. Its whole job is to stop a pet that has
     * never been taught anything from being locked out of the first rung of the ladder, so that
     * "teach it to feed itself" is always reachable from wherever the player picks the game up.
     * Hunger and tiredness do not stop it: you still notice the world while you are waiting to be
     * fed, and gating idle drift on the needs would mean a neglected pet stopped growing up at all.
     */
    fun observe(state: PetState, dt: Long, events: MutableList<GameEvent>): PetState {
        if (dt <= 0L) return state
        if (!state.isMindAwake || state.isSleeping) return state
        if (state.intellect >= OBSERVE_CEILING) return state
        val rate = witFactor(state.genome) * curiosityFactor(state.genome)
        return grow(state, OBSERVE_INTELLECT_RATE * rate * dt * approach(state.intellect, OBSERVE_CEILING), events)
    }

    // ---- internals ---------------------------------------------------------------------------

    /** Everything that has to be true before a creature can sit down and study at all. */
    private fun canConcentrate(state: PetState): Boolean =
        state.isMindAwake &&
            !state.isSleeping &&
            state.stats.satiety >= MIN_SATIETY &&
            state.stats.energy >= MIN_ENERGY

    /** Wit spans 0.6x to 1.4x, centred so an average genome learns at exactly the quoted rate. */
    private fun witFactor(genome: Genome): Float = 0.6f + genome.wit.coerceIn(0f, 1f) * 0.8f

    /** Curiosity only weights idle noticing; deliberate study is wit alone. */
    private fun curiosityFactor(genome: Genome): Float = 0.6f + genome.curiosity.coerceIn(0f, 1f) * 0.8f

    /**
     * How much of the gain the pet actually gets, given how far up it already is.
     *
     * Squared headroom, which makes the approach to [ceiling] hyperbolic rather than exponential:
     * every ten points costs several times what the last ten cost, and the ceiling is never
     * reached from below. A pet left studying overnight ends up clever, not finished — which is
     * the only reason the last rungs of the ladder mean anything.
     */
    private fun approach(intellect: Float, ceiling: Float): Float {
        val room = ((ceiling - intellect) / ceiling).coerceIn(0f, 1f)
        return room * room
    }

    /**
     * Adds [gain] intellect, announcing the crossing if it landed on something worth saying.
     *
     * Returns the state untouched when the gain rounds away, so a saturated pet stops allocating
     * a fresh copy on every tick of a catch-up loop.
     */
    private fun grow(state: PetState, gain: Float, events: MutableList<GameEvent>): PetState {
        if (gain <= 0f) return state
        val next = (state.intellect + gain).coerceIn(0f, MAX_INTELLECT)
        if (next <= state.intellect) return state
        if (crossedMilestone(state.intellect, next)) {
            events += GameEvent.IntellectGrew(state.intellect.toInt(), next.toInt())
        }
        return state.copy(intellect = next)
    }

    /**
     * True when the step from [from] to [to] passed a milestone.
     *
     * Announcing every whole point would fire on most ticks early on and say nothing; announcing
     * nothing until a skill unlocked would leave the slow middle silent. Every fifth point plus
     * every gate gives roughly twenty notes across a life, each of which is either "you are a fifth
     * of the way" or "something new just came into reach". At most one event per step, however long
     * the step was, because a catch-up that crossed four of them should not shout four times.
     */
    private fun crossedMilestone(from: Float, to: Float): Boolean {
        if (to <= from || from.toInt() == to.toInt()) return false
        for (m in milestones) {
            if (m <= from) continue
            return m <= to
        }
        return false
    }

    /**
     * Makes sure the pet is working on something it can actually finish.
     *
     * Only ever changes the target when there is no target, or when the stored one has become
     * meaningless (already known, from a save written before it was learned elsewhere). Re-picking
     * a better target mid-session would throw away banked time the player watched accumulate.
     */
    private fun retarget(state: PetState): PetState {
        val current = state.studying
        if (current != null && current !in state.skills && state.intellect >= current.intellectRequired) {
            return state
        }
        val next = nextSkill(state)
            ?: return if (current == null) state else state.copy(studying = null, studySeconds = 0L)
        return state.copy(
            studying = next,
            studySeconds = 0L,
            // One session is one skill, start to finish. Counting sittings would make the number a
            // measure of how often the app was opened, and counting ticks would make it noise.
            studySessions = state.studySessions + 1,
        )
    }

    /** Banks [seconds] toward the current target, learning the skill if that finishes it. */
    private fun bank(state: PetState, seconds: Long, events: MutableList<GameEvent>): PetState {
        val target = state.studying ?: return state
        val filled = state.studySeconds + seconds
        if (filled < target.studySeconds) return state.copy(studySeconds = filled)
        return learn(state, target, events)
    }

    /**
     * The moment it lands. Overshoot is dropped rather than carried into the next skill: a long
     * catch-up step should not hand the player two skills for one session's work.
     */
    private fun learn(state: PetState, skill: Skill, events: MutableList<GameEvent>): PetState {
        events += GameEvent.LearnedSkill(skill)
        // Retargeting after the skill is in the set, so it picks what comes next rather than the
        // thing that was just finished.
        val s = retarget(state.copy(skills = state.skills + skill, studySeconds = 0L, studying = null))
        // Through applyXp rather than added raw, so a skill can push the keeper over a level
        // boundary and the level-up is announced like any other.
        return Simulation.applyXp(s, xpFor(skill), events)
    }

    /** XP a skill is worth. Scaled by what it cost to learn, so the long ones pay for themselves. */
    private fun xpFor(skill: Skill): Int =
        (skill.studySeconds / SECONDS_PER_XP).toInt().coerceAtLeast(MIN_SKILL_XP)
}
