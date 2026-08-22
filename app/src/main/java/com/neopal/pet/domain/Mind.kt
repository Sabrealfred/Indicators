package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * How much of its own life the creature is allowed to run.
 *
 * This is a setting rather than a stage because it is a question about the player, not about the
 * pet: some people want a thing that needs them, and some want a thing that lives. Turning it up
 * is not "easy mode" — an autonomous pet makes worse decisions than an attentive keeper would,
 * and it can only act on skills it has actually learned.
 */
@Serializable
enum class Autonomy(val displayName: String, val description: String) {
    OFF("Manual", "You make every call. The pet waits to be looked after."),
    ASSIST("Assisted", "It handles its own basics — sleep, tidying, grooming — and asks for the rest."),
    FULL("Autonomous", "It runs its own day: eats, studies, plays, and seeks company on its own."),
}

/** One thing a creature can be doing. The brain picks exactly one at a time. */
@Serializable
enum class ActivityKind(val displayName: String) {
    IDLE("idling"),
    EAT("eating"),
    SLEEP("sleeping"),
    PLAY("playing"),
    GROOM("grooming"),
    TIDY("tidying up"),
    STUDY("studying"),
    MEDICATE("taking medicine"),
    SOCIALISE("visiting a friend"),
    COURT("courting"),
    EXPLORE("exploring"),
}

/**
 * What the creature is doing right now and until when.
 *
 * Activities have a duration because a brain that re-decides every tick produces a creature that
 * twitches between four things a second. Committing to a choice for a while is most of what makes
 * autonomous behaviour look like intent rather than noise.
 */
@Serializable
data class Activity(
    val kind: ActivityKind,
    val startedAtSeconds: Long,
    val endsAtSeconds: Long,
    /** Pal this is aimed at, for the social kinds. */
    val targetId: String? = null,
) {
    fun isOver(ageSeconds: Long): Boolean = ageSeconds >= endsAtSeconds
}

/**
 * A line in the decision log: what the brain chose, and why it chose that over everything else.
 *
 * The "why" is the feature. An autonomous pet that simply acts is indistinguishable from a random
 * one; a pet that says "I ate because I was down to 22% and I had a berry" is a pet the player can
 * argue with, and arguing with it is the relationship.
 */
@Serializable
data class Decision(
    val atSeconds: Long,
    val kind: ActivityKind,
    val reason: String,
    /** Score the winning option got, 0..1-ish. Shown as a confidence bar. */
    val utility: Float,
    /** What it nearly did instead, for the log. Null when nothing else scored. */
    val runnerUp: ActivityKind? = null,
    /**
     * How it went, in the creature's voice — written when the activity this line chose ends.
     *
     * Null while the activity is still running, and null forever for a line whose activity was
     * cut short by the player rather than finishing. A decision is a sentence with two halves
     * ("I'm going to tidy up" / "that is one mess fewer") and the log used to print only the
     * first: the second half was composed on every finish and thrown away.
     */
    val outcome: String? = null,
)

/**
 * Something a creature can learn to do for itself.
 *
 * Skills are the gate between "autonomous" and "capable". Turning autonomy on does not hand the
 * pet the whole list — a baby that cannot yet work a food bowl will sit there hungry and complain,
 * which is the honest outcome and also the reason to teach it.
 */
@Serializable
enum class Skill(
    val displayName: String,
    val description: String,
    /** Intellect needed before this can be learned at all. */
    val intellectRequired: Float,
    /** Study seconds needed once the intellect gate is open. */
    val studySeconds: Long,
) {
    SELF_FEED("Feeding itself", "Opens the pantry and eats when hungry.", 12f, 240L),
    TIDY_UP("Tidying up", "Cleans up after itself instead of waiting.", 20f, 300L),
    SELF_GROOM("Grooming", "Keeps its own coat clean.", 28f, 360L),
    SELF_SETTLE("Settling down", "Puts itself to bed at a sensible hour.", 34f, 300L),
    MEDICATE("Medicating", "Takes its own medicine when ill.", 46f, 600L),
    SOCIALISE("Making friends", "Approaches visitors instead of hiding.", 40f, 420L),
    FORAGE("Foraging", "Finds its own food when the pantry is empty.", 58f, 720L),
    COURT("Courting", "Can pair off with a companion it trusts.", 66f, 720L),
    TEACH("Teaching", "Passes its skills to its own offspring.", 78f, 900L),
    READ("Reading", "Studies twice as fast, and remembers it.", 52f, 600L),
    ;

    companion object {
        /** In the order a creature would plausibly reach them. Drives the skills screen. */
        val ladder: List<Skill> get() = entries.sortedBy { it.intellectRequired }
    }
}

/** How a companion stands with the player's pet. */
@Serializable
enum class Relation(val displayName: String) {
    VISITOR("Visitor"),
    FRIEND("Friend"),
    MATE("Mate"),
    OFFSPRING("Offspring"),
    PARENT("Parent"),
}

/**
 * Another creature in the pet's world.
 *
 * Companions are deliberately shallow — a genome, a name, a mood-ish affinity — rather than full
 * [PetState] simulations. A world with six fully-simulated pets is six pets to neglect, and the
 * game only has room for one relationship that can fail. These exist to be met, liked, learned
 * from and bred with.
 */
@Serializable
data class Pal(
    val id: String,
    val name: String,
    val species: Species,
    val genome: Genome,
    val personality: Personality,
    val stage: LifeStage = LifeStage.ADULT,
    val relation: Relation = Relation.VISITOR,
    /** 0..100 toward the player's pet. Friendship at 50, courtship possible at 80. */
    val affinity: Float = 0f,
    val metAtSeconds: Long = 0L,
    val lastSeenSeconds: Long = 0L,
    /** Set for offspring, so the family tree can be drawn without guessing. */
    val parentNames: List<String> = emptyList(),
    /** Skills this companion knows, and can therefore teach. */
    val skills: Set<Skill> = emptySet(),
    /** True while the companion is actually in the room; visitors come and go. */
    val present: Boolean = false,
) {
    val isFriend: Boolean get() = affinity >= FRIEND_AT
    val canCourt: Boolean get() = affinity >= COURT_AT && stage.order >= LifeStage.TEEN.order

    companion object {
        const val FRIEND_AT = 50f
        const val COURT_AT = 80f
    }
}

/**
 * An egg in the nest, waiting on the clock.
 *
 * The child's genome is fixed the moment the egg is laid rather than when it hatches. A player who
 * has just bred a pairing they liked should not be able to lose that roll by closing the app, and
 * a genome decided at hatch time would do exactly that.
 */
@Serializable
data class NestEgg(
    val id: String,
    val genome: Genome,
    val species: Species,
    val laidAtSeconds: Long,
    val hatchesAtSeconds: Long,
    val otherParentId: String,
    val otherParentName: String,
    /**
     * The pet that laid it, by name.
     *
     * Redundant while the pet that laid the egg is the pet holding the nest — which is why it
     * was not here to begin with — and load-bearing the moment it is not. An egg outlives the
     * creature that laid it, and the child that hatches afterwards has to be able to say whose
     * it is; reading the current pet's name would credit a stranger with somebody else's child.
     * Blank on a save written before this existed, meaning "whoever is holding the nest".
     */
    val parentName: String = "",
) {
    fun isReady(ageSeconds: Long): Boolean = ageSeconds >= hatchesAtSeconds

    /**
     * The same egg on a clock that restarts at zero.
     *
     * Both timestamps are in the *pet's* age seconds, and a new generation puts that back to
     * zero, so an egg carried across a death has to be shifted with it. Shifting both by the
     * same amount keeps the time it has left and the fraction already incubated, which is what
     * the nest meter draws; a rebase to zero would silently restart the incubation.
     */
    fun rebasedFrom(previousAgeSeconds: Long): NestEgg = copy(
        laidAtSeconds = laidAtSeconds - previousAgeSeconds,
        hatchesAtSeconds = hatchesAtSeconds - previousAgeSeconds,
    )
}
