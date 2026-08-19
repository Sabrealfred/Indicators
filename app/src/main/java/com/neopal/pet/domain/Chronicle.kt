package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/** What kind of moment an entry records. Drives the icon and colour in the diary. */
@Serializable
enum class ChronicleKind { MILESTONE, CARE, TROUBLE, JOY, LOSS }

/** One line in the pet's diary, written in its own voice. */
@Serializable
data class ChronicleEntry(
    val id: String,
    val petDay: Int,
    val text: String,
    val kind: ChronicleKind,
    val atSeconds: Long,
)

/**
 * The pet's diary. A virtual pet that only shows meters is a dashboard; one that remembers
 * out loud is a relationship. Entries are written from the simulation's own events, so the
 * diary can never contradict what actually happened.
 */
object Chronicle {

    private const val MAX_ENTRIES = 120

    /** Folds every event of a tick into diary lines. Returns the state with the lines appended. */
    fun record(state: PetState, events: List<GameEvent>, config: GameConfig): PetState {
        if (events.isEmpty()) return state
        var current = state
        events.forEach { event ->
            val line = lineFor(event, current) ?: return@forEach
            current = append(current, line.first, line.second, config)
        }
        return current
    }

    fun append(state: PetState, text: String, kind: ChronicleKind, config: GameConfig): PetState {
        val entry = ChronicleEntry(
            id = "c_${state.ageSeconds}_${state.chronicle.size}",
            petDay = state.ageInPetDays(config) + 1,
            text = text,
            kind = kind,
            atSeconds = state.ageSeconds,
        )
        // Never repeat the previous line verbatim; a diary that stutters reads as broken.
        if (state.chronicle.lastOrNull()?.text == text) return state
        return state.copy(chronicle = (state.chronicle + entry).takeLast(MAX_ENTRIES))
    }

    private fun lineFor(event: GameEvent, state: PetState): Pair<String, ChronicleKind>? = when (event) {
        is GameEvent.Hatched ->
            "I opened my eyes. The first thing I saw was you." to ChronicleKind.MILESTONE

        is GameEvent.Evolved -> when (event.to) {
            LifeStage.CHILD -> "I grew. My old shell doesn't fit any more." to ChronicleKind.MILESTONE
            LifeStage.TEEN -> "Everyone says I've changed. I think they mean it kindly." to ChronicleKind.MILESTONE
            LifeStage.ADULT -> "I'm grown now — a ${event.branch.displayName.lowercase()} one, apparently. That was your doing." to ChronicleKind.MILESTONE
            LifeStage.ELDER -> "My knees creak. I have earned every creak." to ChronicleKind.MILESTONE
            else -> "Something in me shifted today." to ChronicleKind.MILESTONE
        }

        is GameEvent.GotSick ->
            "I don't feel right. I hope you notice soon." to ChronicleKind.TROUBLE

        is GameEvent.Recovered ->
            "The bad feeling passed. You stayed." to ChronicleKind.CARE

        is GameEvent.LeveledUp ->
            "You're getting good at this. Level ${event.level} good." to ChronicleKind.JOY

        is GameEvent.CareMistake -> when (event.reason) {
            "starving" -> "My stomach hurt for a long time today." to ChronicleKind.TROUBLE
            "lights left on" -> "The light stayed on. I pretended to sleep." to ChronicleKind.TROUBLE
            "filthy room" -> "It smells in here. I tried not to mind." to ChronicleKind.TROUBLE
            "untreated illness" -> "Still sick. Still waiting." to ChronicleKind.TROUBLE
            "left alone" -> "Nobody came today." to ChronicleKind.TROUBLE
            else -> null
        }

        is GameEvent.Died -> when (event.reason) {
            DeathReason.OLD_AGE -> "I lived ${state.ageInPetDays(GameConfig.Default)} days and every one of them was yours." to ChronicleKind.LOSS
            DeathReason.ILLNESS -> "I got tired of being sick." to ChronicleKind.LOSS
            DeathReason.STARVATION -> "I waited for food that didn't come." to ChronicleKind.LOSS
            DeathReason.NEGLECT -> "I don't think anyone was coming back." to ChronicleKind.LOSS
        }

        is GameEvent.Unlocked -> null
        is GameEvent.Pooped, is GameEvent.FellAsleep, is GameEvent.WokeUp -> null
        is GameEvent.Message -> event.text to ChronicleKind.CARE
    }

    /** Milestones the player causes directly, written from the action rather than the tick. */
    fun forPlayerMilestone(state: PetState, config: GameConfig): PetState = when {
        state.mealsEaten == 1 -> append(state, "You fed me for the first time. I liked it.", ChronicleKind.CARE, config)
        state.gamesWon == 1 -> append(state, "We played and I won. I want to do that again.", ChronicleKind.JOY, config)
        state.cleanups == 10 -> append(state, "You keep my room clean. I notice.", ChronicleKind.CARE, config)
        state.stats.bond >= 90f && state.chronicle.none { it.text.startsWith("I would follow") } ->
            append(state, "I would follow you anywhere.", ChronicleKind.JOY, config)
        else -> state
    }
}
