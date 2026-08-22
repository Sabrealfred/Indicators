package com.neopal.pet.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * What a lesson changes about the creature that inherits it.
 *
 * Deliberately a small closed set rather than free text with an effect attached. A lesson has to
 * do something the player can eventually notice, and a system that accepts any instruction ends
 * up accepting ones it cannot act on — at which point the inheritance is decoration and the child
 * is not actually better than the parent, it just says it is.
 */
@Serializable
enum class LessonKind(val displayName: String) {
    /** Eat before it is urgent. Raises the appetite weighting. */
    EAT_SOONER("Eat sooner"),
    /** Rest before collapsing. Raises the value of sleep at moderate energy. */
    REST_SOONER("Rest sooner"),
    /** Clean up rather than live in it. */
    TIDY_SOONER("Tidy sooner"),
    /** Take illness seriously and early. */
    GUARD_HEALTH("Guard its health"),
    /** Study more than it plays. */
    STUDY_HARDER("Study harder"),
    /** Seek company rather than hide from it. */
    SEEK_COMPANY("Seek company"),
    /** Play more; a life spent only maintaining itself is not a life. */
    PLAY_MORE("Play more"),
}

/**
 * One thing a line has learned, carried into the next generation.
 *
 * [strength] is bounded and small on purpose. A lesson is a lean, not a rule: an inheriting child
 * that ate at 45% satiety where its parent waited for 25% is visibly wiser, while one that ate
 * whenever it was not completely full would just be broken, and the player would read that as the
 * game malfunctioning rather than as their breeding paying off.
 */
@Serializable
data class Lesson(
    val kind: LessonKind,
    /** How the creature holds it, in its own voice. Shown in the family screen and the brief. */
    val text: String,
    /** 0..1. Summed per kind and then capped; see [Lineage.MAX_BIAS]. */
    val strength: Float,
    /** Generation that learned it, so the screen can show how deep the line runs. */
    val fromGeneration: Int = 0,
)

/**
 * Lessons a line accumulates across generations.
 *
 * The whole system works with no network and no model. A language model makes the *phrasing*
 * better and can notice things a rule cannot, but the mechanism that makes a child wiser than its
 * parent is derived here, from what actually happened to the parent — because a feature whose
 * payoff is gated behind pasting an API key is a feature most players will never see, and "the
 * child is better than the parent" was the point rather than the garnish.
 */
object Lineage {

    /** Most lessons a line carries. Beyond this the oldest and weakest are forgotten. */
    const val MAX_LESSONS = 6

    /**
     * Ceiling on the summed strength for one kind. Two point five means a long, well-bred line
     * leans clearly harder than a founder, and still never reaches the point where the creature
     * stops weighing anything else.
     */
    const val MAX_BIAS = 2.5f

    /** How much one full point of strength moves a utility score. */
    private const val BIAS_PER_STRENGTH = 0.30f

    /**
     * Reads a finished life and works out what it should have done differently.
     *
     * Ordered by how badly each thing went, and capped, so a catastrophic life does not hand its
     * child six contradictory instructions at full strength. What killed the parent matters most:
     * a line that keeps starving should produce children that eat early long before it produces
     * children that study.
     */
    fun distilLocally(record: RunRecord, generation: Int): List<Lesson> {
        val out = mutableListOf<Lesson>()

        fun add(kind: LessonKind, strength: Float, text: String) {
            if (strength > 0.01f) out += Lesson(kind, text, strength.coerceIn(0f, 1f), generation)
        }

        // How the parent died is the loudest signal there is.
        when (record.deathReason) {
            DeathReason.STARVATION ->
                add(LessonKind.EAT_SOONER, 0.9f, "My parent waited too long to eat. I do not wait.")
            DeathReason.ILLNESS ->
                add(LessonKind.GUARD_HEALTH, 0.9f, "Something took my parent that could have been treated.")
            DeathReason.NEGLECT ->
                add(LessonKind.SEEK_COMPANY, 0.7f, "My parent was left alone too long. I go and find someone.")
            // Old age is not a mistake to correct. A line that dies of old age has nothing to
            // apologise for, and telling its children otherwise would be the game lying to them.
            DeathReason.OLD_AGE, null -> Unit
        }

        // Then the rate of neglect, which says how the life went rather than how it ended.
        val mistakes = record.mistakesPerHour
        if (mistakes > 0.4f) {
            add(LessonKind.TIDY_SOONER, (mistakes * 0.5f).coerceAtMost(0.7f),
                "My parent's room was not kept. I keep mine.")
        }

        // A parent that never learned anything is a specific kind of loss.
        if (record.level <= 3 && record.lifespanSeconds > 3600L) {
            add(LessonKind.STUDY_HARDER, 0.5f, "My parent never got the chance to learn much. I mean to.")
        }

        // And one that never played is another.
        if (record.gamesPlayed < 5 && record.lifespanSeconds > 3600L) {
            add(LessonKind.PLAY_MORE, 0.45f, "My parent worked at staying alive and forgot the rest.")
        }

        // A life that ran low on rest often enough to be graded badly.
        if (record.careScore < 0.55f) {
            add(LessonKind.REST_SOONER, 0.5f, "My parent ran itself down. I stop before that.")
        }

        // A good life still teaches something: keep doing what worked.
        if (record.diedOfOldAge && record.careScore >= 0.75f) {
            add(LessonKind.SEEK_COMPANY, 0.35f, "My parent lived well and was not alone. I remember how.")
        }

        return out.sortedByDescending { it.strength }.take(3)
    }

    /**
     * Folds new lessons into what the line already carried.
     *
     * Same-kind lessons merge rather than stack as separate entries, keeping the stronger wording
     * and summing toward the cap. Without merging, ten generations of starvation would produce ten
     * near-identical lines in the family screen and the real signal — that this line has learned
     * this lesson *hard* — would be buried in the repetition.
     */
    fun inherit(existing: List<Lesson>, fresh: List<Lesson>): List<Lesson> {
        if (fresh.isEmpty()) return existing.take(MAX_LESSONS)
        val byKind = LinkedHashMap<LessonKind, Lesson>()
        for (lesson in existing) byKind[lesson.kind] = lesson
        for (lesson in fresh) {
            val prior = byKind[lesson.kind]
            byKind[lesson.kind] = if (prior == null) {
                lesson
            } else {
                // Keep whichever wording is stronger; a faded old line should not outrank a fresh
                // hard-won one just for having been there first.
                val keep = if (lesson.strength >= prior.strength) lesson else prior
                keep.copy(strength = (prior.strength + lesson.strength).coerceAtMost(1f))
            }
        }
        return byKind.values.sortedByDescending { it.strength }.take(MAX_LESSONS)
    }

    /**
     * How much [lessons] should tilt the score for [kind], as a multiplier around 1.
     *
     * Multiplicative rather than additive so it scales with how much the creature already wanted
     * the thing. Adding a flat bonus would make a well-bred pet eat when it was not hungry, which
     * reads as a bug; multiplying makes it eat *earlier*, which reads as having learned something.
     */
    fun bias(lessons: List<Lesson>, kind: LessonKind): Float {
        var total = 0f
        for (lesson in lessons) if (lesson.kind == kind) total += lesson.strength
        return 1f + (total.coerceAtMost(MAX_BIAS) * BIAS_PER_STRENGTH)
    }

    /** The multiplier for an activity, or 1 when no lesson speaks to it. */
    fun biasFor(lessons: List<Lesson>, activity: ActivityKind): Float {
        if (lessons.isEmpty()) return 1f
        val kind = when (activity) {
            ActivityKind.EAT -> LessonKind.EAT_SOONER
            ActivityKind.SLEEP -> LessonKind.REST_SOONER
            ActivityKind.TIDY -> LessonKind.TIDY_SOONER
            ActivityKind.GROOM -> LessonKind.TIDY_SOONER
            ActivityKind.MEDICATE -> LessonKind.GUARD_HEALTH
            ActivityKind.STUDY -> LessonKind.STUDY_HARDER
            ActivityKind.SOCIALISE, ActivityKind.COURT -> LessonKind.SEEK_COMPANY
            ActivityKind.PLAY -> LessonKind.PLAY_MORE
            ActivityKind.EXPLORE, ActivityKind.IDLE -> return 1f
        }
        return bias(lessons, kind)
    }

    /**
     * A one-line summary of how far this line has come, for the family screen.
     * Returns null for a founder, which has nothing to summarise and should not be given a number.
     */
    fun summary(lessons: List<Lesson>, generation: Int): String? {
        if (lessons.isEmpty() || generation <= 1) return null
        val strongest = lessons.maxByOrNull { it.strength } ?: return null
        val depth = lessons.sumOf { it.strength.toDouble() }
        val weight = when {
            depth >= 2.5 -> "deeply"
            depth >= 1.2 -> "clearly"
            else -> "faintly"
        }
        return "This line has $weight learned to ${strongest.kind.displayName.lowercase()}."
    }

    /**
     * Guards a lesson that arrived from a language model.
     *
     * Anything a model returns is untrusted input: it can be too long for the screen, empty, or
     * carry a strength that would blow past the bias cap in one generation. Clamping here rather
     * than at the call site means every route into the lesson list — model, local distillation,
     * or a save written by an older build — passes the same gate.
     */
    fun sanitise(lesson: Lesson): Lesson? {
        val text = lesson.text.trim().take(MAX_LESSON_CHARS)
        if (text.isEmpty()) return null
        val strength = if (lesson.strength.isNaN()) 0f else abs(lesson.strength).coerceIn(0f, 1f)
        if (strength <= 0.01f) return null
        return lesson.copy(text = text, strength = strength)
    }

    /** Long enough for a sentence, short enough for a row on a phone. */
    const val MAX_LESSON_CHARS = 120
}
