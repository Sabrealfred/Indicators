package com.neopal.pet.domain

/**
 * Where a remote mind's reading of a finished life is allowed to land.
 *
 * [MindProvider.distil] existed, was prompted, was parsed, was tested over a socket and was gated
 * behind its own setting — and nothing ever called it. The whole of that path was dead code, and
 * dead in the way §6 of the plan warns about: invisibly. The local distillation in
 * [Lineage.distilLocally] runs at every generation and produces a perfectly good set of lessons,
 * so a switch that was never read looked exactly like a switch that was on and had nothing to add.
 *
 * The call belongs at the moment a line continues, which is the one moment the finished run is
 * still in hand. It cannot be *inside* [Simulation.nextGeneration], because that is pure and this
 * answer arrives over a network seconds later; so the heir is born with its local inheritance
 * immediately and the model's reading is folded in when and if it turns up. That ordering is also
 * what makes the feature honest offline: the network can add wording the rules could not have
 * found, and can never be the reason a child is wiser than its parent.
 */
object Distillation {

    /**
     * Folds lessons a remote mind drew from generation [fromGeneration] into the heir now living.
     *
     * Returns null — meaning leave the save alone — rather than an unchanged state, so the caller
     * has no reason to write a save, emit an event or update the UI for an answer that changed
     * nothing. Empty is the normal case: the model was unreachable, off, out of quota, or had
     * nothing to say that survived the gate.
     *
     * Three things are checked, and each is a way this could otherwise corrupt a line:
     *
     *  - Every lesson passes [Lineage.sanitise], because prose off a network is untrusted input
     *    whatever produced it, and a lesson with a strength of nine would blow past the bias cap
     *    in one generation.
     *  - The answer has to be about the life this heir actually inherited. A reply that arrives
     *    after the player has buried another pet is about a grandparent, and folding it in would
     *    hand a line the same lesson twice for one death.
     *  - A dead or unrelated heir is left alone entirely.
     */
    fun fold(heir: PetState, fromGeneration: Int, remote: List<Lesson>): PetState? {
        if (heir.generation != fromGeneration + 1) return null
        val fresh = remote.mapNotNull(Lineage::sanitise)
        if (fresh.isEmpty()) return null
        val merged = Lineage.inherit(existing = heir.lessons, fresh = fresh)
        if (merged == heir.lessons) return null
        return heir.copy(lessons = merged)
    }
}
