package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * The games there are.
 *
 * This exists because the domain could not previously answer that question. Each game screen held
 * its own id as a private constant, the shelf held the same seven strings again as literals, and
 * nothing in the domain could name a single one — so an achievement about the games was
 * unwriteable and the ids were free to drift. They already had: four of them arrived prefixed
 * (`game_hide`) while the three before them were bare (`catch`), and the two spellings sat in the
 * save file side by side until someone noticed.
 *
 * [id] is the save key, and it is what it has always been. The enum name is free to change; this
 * string is not, because a player's high scores are filed under it.
 */
@Serializable
enum class MiniGame(val id: String, val displayName: String) {
    RHYTHM("rhythm", "Rhythm Tap"),
    MEMORY("memory", "Memory Match"),
    CATCH("catch", "Snack Catch"),
    HIDE("hide", "Hide and Seek"),
    FETCH("fetch", "Fetch"),
    DUET("duet", "Duet"),
    PUZZLE("puzzle", "Shape Sorter");

    /** True once the player has posted any score at all here. */
    fun played(state: PetState): Boolean = (state.highScores[id] ?: 0) > 0

    companion object {
        fun byId(id: String): MiniGame? = entries.firstOrNull { it.id == id }

        /** How many of the games have ever been scored in. */
        fun playedCount(state: PetState): Int = entries.count { it.played(state) }
    }
}
