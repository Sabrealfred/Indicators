package com.neopal.pet.domain

import kotlin.math.abs

/**
 * What the creature plays when nobody is playing with it.
 *
 * The two halves of this game had no connection at all. There are seven minigames and a creature
 * that decides for itself, and when it decided to play it played *nothing in particular* — the
 * activity was a bare word and a happiness bump. That is a strange thing for a game about a
 * creature with a personality: the shelf says who it might be and the brain never reads it.
 *
 * So a bored creature picks something off the shelf, and which one it picks is its own. A leggy,
 * vigorous line goes and chases things; a clever one sits down with the shapes; a sociable one
 * would rather sing. It is the cheapest possible way to make the genome show up somewhere the
 * player was not looking for it.
 *
 * It deliberately posts **no score**. The high scores are the player's record of their own
 * afternoons, and a creature quietly beating a record on a game the player has never opened —
 * or unlocking the badge for scoring in all seven — would take something rather than add it.
 * What this contributes is the sentence in the diary and the reason in the decision log.
 */
object SoloPlay {

    /**
     * How long the creature keeps fancying the same thing.
     *
     * Not random, and that matters more than it looks. This is read while the brain is *scoring*
     * options, which happens repeatedly for one decision and again for the considerations screen,
     * so a roll of the dice would make the creature's stated preference flicker between two reads
     * and the screen would disagree with itself. A creature is allowed to change its mind; it is
     * not allowed to change it between one glance and the next.
     */
    const val RESTLESS_SECONDS = 5_400L

    /** How much of the choice is passing fancy rather than settled taste, 0..1. */
    private const val WOBBLE = 0.38f

    /** What it feels like playing now. Pure and stable within a [RESTLESS_SECONDS] window. */
    fun choice(state: PetState): MiniGame {
        val turn = state.ageSeconds / RESTLESS_SECONDS
        return MiniGame.entries.maxBy { appetite(it, state) + WOBBLE * wobble(it, turn) }
    }

    /** What it would always pick if it never got restless. Settled taste, with no fancy in it. */
    fun favourite(state: PetState): MiniGame = MiniGame.entries.maxBy { appetite(it, state) }

    /**
     * How much this creature is built for this game, 0..1-ish.
     *
     * Weighted from the same genes the games themselves read, so a creature that is *good* at
     * fetch is also the one that wants to play it. The alternative — taste uncorrelated with
     * aptitude — produces a creature that always chooses the thing it is worst at, which reads
     * as broken rather than as endearing.
     */
    private fun appetite(game: MiniGame, state: PetState): Float {
        val g = state.genome
        val clever = (state.intellect.coerceIn(0f, 100f) / 100f)
        return when (game) {
            MiniGame.RHYTHM -> 0.30f + 0.35f * g.wit + 0.30f * g.vigor
            MiniGame.MEMORY -> 0.28f + 0.55f * g.wit + 0.20f * clever
            MiniGame.CATCH -> 0.30f + 0.55f * g.vigor
            MiniGame.HIDE -> 0.26f + 0.34f * g.curiosity + 0.30f * g.sociability
            MiniGame.FETCH -> 0.24f + 0.42f * g.vigor + 0.34f * g.limbs
            MiniGame.DUET -> 0.24f + 0.58f * g.sociability
            MiniGame.PUZZLE -> 0.22f + 0.34f * g.wit + 0.26f * g.curiosity + 0.30f * clever
        }
    }

    /**
     * A settled, repeatable number in 0..1 for this game on this turn.
     *
     * Deliberately arithmetic rather than [kotlin.random.Random]: the same creature at the same
     * moment must answer the same way however many times it is asked. See [RESTLESS_SECONDS].
     */
    private fun wobble(game: MiniGame, turn: Long): Float {
        var h = turn * 0x9E3779B9L + game.ordinal * 0x85EBCA6BL
        h = h xor (h ushr 29)
        h *= 0xBF58476D1CE4E5B9UL.toLong()
        h = h xor (h ushr 32)
        return abs(h % 1_000).toFloat() / 1_000f
    }

    /** What it says about having played [game], for the diary and the decision log. */
    fun note(game: MiniGame): String = when (game) {
        MiniGame.RHYTHM -> "Tapped out a rhythm to myself."
        MiniGame.MEMORY -> "Turned the cards over until I had them all."
        MiniGame.CATCH -> "Caught what I could and dropped the rest."
        MiniGame.HIDE -> "Hid, and nobody came. Hid better."
        MiniGame.FETCH -> "Threw it for myself, which is not the same."
        MiniGame.DUET -> "Sang both parts. The second one was better."
        MiniGame.PUZZLE -> "Sat with the shapes for a while."
    }

    /** Why it wants to, in its own voice, for the option's reason line. */
    fun reason(state: PetState, game: MiniGame): String =
        "I was bored at ${state.stats.happiness.toInt()}% and fancied ${game.displayName}."
}
