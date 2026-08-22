package com.neopal.pet.domain

/**
 * The story the machinery is already telling, written down so the game can say it out loud.
 *
 * Nothing here is invention. Every line below is a restatement of something the simulation
 * already does, in the one register the game does not yet have. [Chronicle] is the creature's own
 * voice and it can only ever speak from inside a single life; the diary of a pet that starved has
 * no way to mention that its child will eat earlier, because the pet does not know that and never
 * will. Somebody has to be able to say it, and that somebody is not the creature.
 *
 * So this file holds the other two voices the design has always claimed to have and never built:
 * the appliance ([LoreVoice.DEVICE]), which reports and does not feel, and the narrator
 * ([LoreVoice.NARRATOR]), which is calm, third-person and almost documentary. The rule that keeps
 * the three from collapsing into one another is mechanical rather than a matter of taste, and it
 * is enforced by the tests: **nothing in this file is written in the first person.** A line here
 * that said "I" would be a diary line, and there is already a place for those.
 *
 * The premise, in the order the code establishes it:
 *
 *  - A vivarium is an appliance. It holds one life at a time and keeps holding it with the lid
 *    shut, which is [GameConfig.offlineDecayMultiplier] and [GameConfig.offlineHealthFloor] said
 *    in words.
 *  - What hatches is not a finished animal. [Genome] is fourteen continuous dials seeded near the
 *    family's own centre, and [Genome.houndliness] measures how far a creature has drifted from
 *    the round starter shape toward something on four legs. Nobody in the fiction knows what they
 *    turn into, because in the code nobody has decided.
 *  - Death teaches, and only failure teaches much. [Lineage.distilLocally] reads a finished run
 *    and derives what the next one should do differently — and it deliberately returns nothing to
 *    correct for [DeathReason.OLD_AGE]. A line that keeps dying well hands its children almost
 *    nothing. That is not a bug to write around; it is the best sentence in the game.
 *  - Knowledge does not travel. [PetState.skills] are re-earned every generation unless the parent
 *    lived long enough to learn [Skill.TEACH], which is the only route out of a life.
 *  - The world outlives the creature. [Simulation.nextGeneration] keeps every [Pal] and resets its
 *    affinity to zero: everyone the last one knew is still there, and none of them know this one.
 *
 * Every string is hardcoded, British, and free of exclamation marks. It must all work for a player
 * who never reads a word of it.
 */

/** Who is speaking. The creature's own voice is not here on purpose; see the file comment. */
enum class LoreVoice {
    /** The vivarium. Reports facts, has no opinion, is the only voice allowed a number. */
    DEVICE,

    /** The narrator. Third person, calm, five or six appearances in a run and then quiet. */
    NARRATOR,
}

/**
 * A point in a run or a lineage that the story can be hung on.
 *
 * Every one of these corresponds to something the simulation already emits or can already be
 * asked about — an event, a life stage, a generation count, a genome. None of them needed a new
 * mechanic, which was the condition of writing any of them.
 */
enum class LoreMoment(val voice: LoreVoice) {
    /** The frame. Shown once, or never, or in the settings screen; it is a manual page. */
    THE_VIVARIUM(LoreVoice.DEVICE),

    /** The first pet this save ever had, hatching with nothing behind it. */
    FOUNDING(LoreVoice.NARRATOR),

    /** Any later generation hatching into a room that has been lived in. */
    SUCCESSION(LoreVoice.NARRATOR),

    /** A line deep enough that the save has started to forget the beginning of it. */
    DEEP_LINE(LoreVoice.NARRATOR),

    /** Baby to child: the one change every creature makes and none of them earn. */
    FIRST_CHANGE(LoreVoice.NARRATOR),

    /** Teen to adult, where [EvolutionBranch] is settled for good. */
    SHAPE_SETTLED(LoreVoice.NARRATOR),

    /** Adult to elder, where care stops building anything. */
    GROWN_OLD(LoreVoice.NARRATOR),

    /** The first [Skill] learned in this life, which is the only way any of them are got. */
    LEARNED_UNAIDED(LoreVoice.NARRATOR),

    /** [Autonomy] raised above [Autonomy.OFF]: the keeper hands the day over. */
    DAY_HANDED_OVER(LoreVoice.NARRATOR),

    /** A visitor crossing [Pal.FRIEND_AT], which takes more than one visit by design. */
    FIRST_FRIEND(LoreVoice.NARRATOR),

    /** A pairing, which is fourteen genes deciding. */
    PAIRED(LoreVoice.NARRATOR),

    /** An egg in the nest, its genome already fixed. */
    EGG_IN_THE_NEST(LoreVoice.NARRATOR),

    /** A child hatching into the room with nothing taught to it. */
    FIRST_CHILD(LoreVoice.NARRATOR),

    /** A child hatching with skills, because the parent learned [Skill.TEACH] in time. */
    TAUGHT_ITS_OWN(LoreVoice.NARRATOR),

    /** A life that started with a lean it was never told about. */
    LINE_REMEMBERS(LoreVoice.NARRATOR),

    /** The body halfway between the shape on the box and something else. */
    SHAPE_DRIFTING(LoreVoice.NARRATOR),

    /** The body actually drawn on four legs. */
    SHAPE_QUADRUPED(LoreVoice.NARRATOR),

    /** Coming back to a pet the appliance kept alive without keeping well. */
    HELD_WHILE_AWAY(LoreVoice.DEVICE),

    /** [DeathReason.OLD_AGE]: the only ending that is not a mistake. */
    END_OLD_AGE(LoreVoice.NARRATOR),

    /** [DeathReason.STARVATION]. */
    END_STARVATION(LoreVoice.NARRATOR),

    /** [DeathReason.ILLNESS]. */
    END_ILLNESS(LoreVoice.NARRATOR),

    /** [DeathReason.NEGLECT]. */
    END_NEGLECT(LoreVoice.NARRATOR),
}

/**
 * The text for one moment.
 *
 * A list rather than a string because the two surfaces that matter most — a milestone card and a
 * memorial — are both several short lines with air between them, and joining them with a space
 * produces a paragraph that reads as a paragraph. The caller decides the spacing; this decides
 * where the breaks are.
 */
data class LorePassage(
    val moment: LoreMoment,
    val voice: LoreVoice,
    val lines: List<String>,
)

/**
 * Chooses which of the above applies, from state the game already keeps.
 *
 * Everything is a pure function of a [PetState] and, where relevant, a [GameEvent]. No clock, no
 * random source, no ordering assumptions: the same save asked twice gets the same answer, which is
 * the only way a narrator can be trusted not to contradict itself across a process restart.
 */
object Lore {

    /** A milestone card, per the writing guide. Anything longer stops being a caption. */
    const val MAX_LINE_CHARS = 140

    /** A memorial is three lines. Nothing here is allowed to be longer than a memorial. */
    const val MAX_LINES = 3

    /** Generation at which a line is old enough that the save has started losing the start of it. */
    const val DEEP_LINE_AT = 5

    /** [Morphology.houndliness] at which the creature stops reading as the shape on the box. */
    const val DRIFTING_AT = 0.30f

    /** And where it reads as an animal instead. Paired with [ON_FOUR_LEGS] so it never lies. */
    const val QUADRUPED_AT = 0.48f

    /**
     * [Morphology.quadruped] at which the renderer is genuinely drawing a four-legged creature.
     *
     * Required alongside [QUADRUPED_AT] because houndliness is a weighted blend: a long muzzle and
     * hanging ears can carry the score most of the way while the thing is still standing upright,
     * and a narrator that announces four legs over a picture of a biped has burned the one thing
     * it has, which is being right.
     */
    const val ON_FOUR_LEGS = 0.50f

    /** The world, in three lines. Everything else assumes these. */
    val frame: List<String> get() = passage(LoreMoment.THE_VIVARIUM).lines

    /** The text for [moment]. Total, never partial: a moment with no text is a bug, not a state. */
    fun passage(moment: LoreMoment): LorePassage = LorePassage(moment, moment.voice, linesFor(moment))

    /**
     * The moment [event] marks for a save standing at [state], or null when it marks none.
     *
     * [state] may be the save from either side of the event. Every "is this the first one" test
     * below excludes the event's own subject by id, so a caller does not have to snapshot the
     * state before applying an event just to ask this question — which is exactly the sort of
     * requirement that gets forgotten once and then produces a first-friend card on the ninth
     * friend for ever.
     */
    fun momentFor(event: GameEvent, state: PetState): LoreMoment? = when (event) {
        is GameEvent.Hatched -> generationMoment(state)

        is GameEvent.Evolved -> when (event.to) {
            LifeStage.CHILD -> LoreMoment.FIRST_CHANGE
            LifeStage.ADULT -> LoreMoment.SHAPE_SETTLED
            LifeStage.ELDER -> LoreMoment.GROWN_OLD
            // The teen jump runs decideBranch too, but it is provisional and the diary already
            // marks it. The narrator only gets to speak a handful of times; it spends one on the
            // verdict that sticks.
            else -> null
        }

        is GameEvent.LearnedSkill ->
            if ((state.skills - event.skill).isEmpty()) LoreMoment.LEARNED_UNAIDED else null

        is GameEvent.Befriended ->
            if (state.pals.none { it.id != event.pal.id && it.isFriend }) LoreMoment.FIRST_FRIEND else null

        is GameEvent.Paired ->
            if (state.pals.none { it.id != event.pal.id && it.relation == Relation.MATE }) LoreMoment.PAIRED else null

        is GameEvent.EggLaid ->
            if (state.nest.none { it.id != event.egg.id }) LoreMoment.EGG_IN_THE_NEST else null

        // A child that arrives already knowing things is the rarer and larger event, so it wins
        // even when it is also the first: a keeper who got Skill.TEACH in under a lifetime should
        // be told that is what happened, not congratulated on having become a parent.
        is GameEvent.ChildHatched -> when {
            event.child.skills.isNotEmpty() -> LoreMoment.TAUGHT_ITS_OWN
            state.pals.none { it.id != event.child.id && it.relation == Relation.OFFSPRING } ->
                LoreMoment.FIRST_CHILD
            else -> null
        }

        is GameEvent.Died -> endMoment(event.reason)

        else -> null
    }

    /**
     * The moment a change of [Autonomy] marks, or null when nothing was actually handed over.
     *
     * Only the first step off [Autonomy.OFF] counts. Moving between Assisted and Autonomous later
     * is a settings tweak; the line that matters is the one where the creature stops waiting, and
     * saying it twice would make it a notification rather than a moment.
     */
    fun autonomyMoment(from: Autonomy, to: Autonomy): LoreMoment? =
        if (from == Autonomy.OFF && to != Autonomy.OFF) LoreMoment.DAY_HANDED_OVER else null

    /**
     * The device line for coming back to a save the simulation had to catch up on.
     *
     * Takes the flag rather than an elapsed time because [Simulation.advance] already owns the
     * decision about what counts as having been away, and a second copy of that threshold here
     * would drift apart from the first the moment anybody tuned it.
     */
    fun returnMoment(wasCaughtUp: Boolean, state: PetState): LoreMoment? =
        if (wasCaughtUp && !state.isDead && state.stage.isHatched) LoreMoment.HELD_WHILE_AWAY else null

    /** The memorial passage for [reason]. */
    fun endMoment(reason: DeathReason): LoreMoment = when (reason) {
        DeathReason.OLD_AGE -> LoreMoment.END_OLD_AGE
        DeathReason.STARVATION -> LoreMoment.END_STARVATION
        DeathReason.ILLNESS -> LoreMoment.END_ILLNESS
        DeathReason.NEGLECT -> LoreMoment.END_NEGLECT
    }

    /**
     * How this life opens: the first of a house, one more of a house, or one of a long line.
     *
     * Keyed on [PetState.previousGenerations] as well as the counter, because a save restored from
     * a backup or written by an older build can carry a generation number with no runs behind it,
     * and calling such a pet the fourth of anything is a claim the game cannot support.
     */
    fun generationMoment(state: PetState): LoreMoment = when {
        state.generation >= DEEP_LINE_AT && state.previousGenerations.isNotEmpty() -> LoreMoment.DEEP_LINE
        state.previousGenerations.isEmpty() -> LoreMoment.FOUNDING
        else -> LoreMoment.SUCCESSION
    }

    /**
     * The shape this creature has ended up with, or null while it still looks like the picture on
     * the box. Reads the expressed [Morphology] rather than the [Genome] so a baby is never told
     * it is a hound: the body only shows the genes as it matures, and the line has to match what
     * is actually on screen.
     */
    fun shapeMoment(state: PetState): LoreMoment? {
        if (!state.stage.isHatched || state.isDead) return null
        val body = state.morphology
        return when {
            body.houndliness >= QUADRUPED_AT && body.quadruped >= ON_FOUR_LEGS -> LoreMoment.SHAPE_QUADRUPED
            body.houndliness >= DRIFTING_AT -> LoreMoment.SHAPE_DRIFTING
            else -> null
        }
    }

    /**
     * Whether this life started with a lean it was never told about, and should be told once.
     *
     * A lesson carried in from an earlier generation is the only thing in the game that changes a
     * creature's behaviour without any visible cause — the brain simply weighs eating higher and
     * nothing on any screen says why. That is the moment worth naming.
     */
    fun inheritanceMoment(state: PetState): LoreMoment? =
        if (state.stage.isHatched && inheritedLessons(state).isNotEmpty()) LoreMoment.LINE_REMEMBERS else null

    /**
     * One narrator line for a family or lineage screen: what this creature is standing on.
     *
     * Null for a founder that has not yet worked anything out for itself, which is most of the
     * first hour of a new save. A screen with nothing to say should say nothing rather than
     * printing a zero — see [PetState.previousGenerations], which draws the same distinction
     * between "did nothing" and "there is no record".
     */
    fun standing(state: PetState): String? {
        val past = state.previousGenerations.size
        val inherited = inheritedLessons(state)
        val own = state.lessons.size - inherited.size
        return when {
            past == 0 && own == 0 -> null
            past == 0 ->
                "Nothing behind this one. What it leans on, it worked out for itself this afternoon."
            inherited.isEmpty() ->
                "${lives(past)} behind this one and nothing to correct, so it starts where a founder starts."
            else -> {
                val strongest = inherited.maxByOrNull { it.strength } ?: return null
                "${lives(past)} behind this one. It arrived already inclined to " +
                    "${strongest.kind.displayName.lowercase()}, and nothing ever told it why."
            }
        }
    }

    // ---- internals ----------------------------------------------------------------------

    /**
     * Lessons that came from an earlier run rather than from this creature's own day.
     *
     * [Lineage.inherit] merges by kind and keeps the stronger wording, so the generation stamp on
     * a merged lesson is whichever of the two was kept — always an earlier one than the current
     * creature, which is all this test needs to be right about.
     */
    private fun inheritedLessons(state: PetState): List<Lesson> =
        state.lessons.filter { it.fromGeneration in 1 until state.generation }

    /** "One life" reads better than "1 lives", and the narrator does not print bad grammar. */
    private fun lives(count: Int): String = if (count == 1) "One life" else "$count lives"

    private fun linesFor(moment: LoreMoment): List<String> = when (moment) {

        // The frame. An appliance describing its own function, which is all the world-building
        // this game is allowed: no origin, no purpose, no one to blame.
        LoreMoment.THE_VIVARIUM -> listOf(
            "A vivarium is a household appliance. It holds one small life at a time, and goes on holding it with the lid shut.",
            "The nursery sends an egg only when somebody is willing to watch it. Nobody has kept a line of them long enough to say what they become.",
            "You are the keeper. That is the whole of the job.",
        )

        LoreMoment.FOUNDING -> listOf(
            "A founder. No parents, no record behind it, a shape drawn close to the middle of the family it came out of.",
            "Whatever this house turns out to be, it starts at this size.",
        )

        LoreMoment.SUCCESSION -> listOf(
            "The room is the same room. The coins, the hats, the pictures and the diary crossed over; the name, the body and the skills did not.",
            "Everyone the last one knew is still out there. None of them will know this one.",
        )

        LoreMoment.DEEP_LINE -> listOf(
            "Deep enough now that the earliest ones are only names in a record, and the record itself keeps eight.",
            "None of this was aimed anywhere. It has gone somewhere all the same.",
        )

        LoreMoment.FIRST_CHANGE -> listOf(
            "Every creature makes this change and not one of them earns it.",
            "The changes after this one are settled by how the days go.",
        )

        LoreMoment.SHAPE_SETTLED -> listOf(
            "A branch is a record, not a mark. Five ways of having been looked after, in no order, and this is the one that fits.",
        )

        LoreMoment.GROWN_OLD -> listOf(
            "Nothing is built after this. Feeding an elder buys the afternoon and nothing further, which is the plainest thing a keeper does.",
        )

        LoreMoment.LEARNED_UNAIDED -> listOf(
            "Nothing taught it that.",
            "Nor will it pass down by itself. Skills reach a child only if this one lives long enough to learn how to teach.",
        )

        LoreMoment.DAY_HANDED_OVER -> listOf(
            "The day is its own from here. It will run it worse than you would, and that is what handing it over means.",
            "Nothing has been taken away from you. It has simply stopped waiting.",
        )

        LoreMoment.FIRST_FRIEND -> listOf(
            "Nobody arranged that. Callers let themselves in on their own schedule, and it takes more than one visit before any of them counts.",
        )

        LoreMoment.PAIRED -> listOf(
            "Two lines meeting. Each of the fourteen genes goes one way, or the other, or halfway, and then something nudges it.",
            "That nudge is the only reason this line can reach a shape neither of them had.",
        )

        LoreMoment.EGG_IN_THE_NEST -> listOf(
            "Already decided. What comes out was settled the moment the egg was laid, and nothing after this alters it.",
        )

        LoreMoment.FIRST_CHILD -> listOf(
            "The nursery had no part in this one.",
            "It counts as family from the moment it is out, having so far done nothing to be counted.",
        )

        LoreMoment.TAUGHT_ITS_OWN -> listOf(
            "Three things, the easiest first, and only because this one lived long enough to learn how to teach at all.",
            "That is the only route anything known takes out of a life. The rest of what it knows stops here.",
        )

        LoreMoment.LINE_REMEMBERS -> listOf(
            "It was never told any of this.",
            "What a line hands down is not knowledge but a lean: earlier to the bowl, quicker to the door, slower to sit in a mess.",
        )

        LoreMoment.SHAPE_DRIFTING -> listOf(
            "Longer in the leg and lower to the ground than the round thing on the box. Not one shape or the other yet.",
        )

        LoreMoment.SHAPE_QUADRUPED -> listOf(
            "It does not stand up any more. It gets about on four legs, and the picture on the box is not what is in the tank.",
            "Nobody asked for this. The photographs are the only place it is written down.",
        )

        // Device voice: what the appliance did while nobody was looking. No adjectives, no
        // apology, and the second line is the whole design of the offline rules in six words.
        LoreMoment.HELD_WHILE_AWAY -> listOf(
            "The lid was shut. The regulator kept it off the bottom.",
            "Needs ran slow. Age did not.",
        )

        // The four endings. Each failure names what the line is given for it, in the same flat
        // sentence, because that repetition is the premise: the only thing reliably inherited in
        // this game is a correction, and corrections are bought with a life.
        LoreMoment.END_OLD_AGE -> listOf(
            "It reached the end of it. Nothing was cut short.",
            "Old age is the one ending a line has nothing to correct, so it hands the next one almost nothing.",
        )

        LoreMoment.END_STARVATION -> listOf(
            "It went hungry, and then it went on going hungry.",
            "The next one in this line will eat earlier. That is what it is given.",
        )

        LoreMoment.END_ILLNESS -> listOf(
            "It was ill, and it stayed ill.",
            "The next one in this line will take a fever seriously. That is what it is given.",
        )

        LoreMoment.END_NEGLECT -> listOf(
            "It was on its own long enough to stop expecting otherwise.",
            "The next one in this line will go and find company. That is what it is given.",
        )
    }
}
