package com.neopal.pet.domain

/**
 * The small decisions between [MindRouter] and the screen that calls it.
 *
 * [MindRouter] answers *who should answer*, and it answers it from the shape of the question
 * alone — deliberately, because that is the argument of `docs/CEREBRO-LOCAL.md` §4 and mixing
 * anything else into it is how routing by connectivity creeps back in. But a caller cannot ask it
 * anything without first working out two booleans, and both of them are decisions with a wrong
 * answer that fails quietly:
 *
 *  - **Is the remote worth calling for *this*?** Not just "is a key set". Each job has its own
 *    switch in settings, and a player who turned conversation off has not turned deciding off.
 *    Getting this wrong spends somebody's quota on a job they switched off, or — the direction
 *    this project has shipped twice — silently never calls at all and looks exactly like a
 *    feature that is off.
 *  - **Is there an engine on the phone right now?** Which is a question about this second, not
 *    about whether a model is installed: loading takes seconds and the engine only exists while a
 *    screen is up.
 *
 * They live here rather than inline in the view model because inline is where they cannot be
 * tested, and because both of them are read in two places — once to decide whether the composer
 * appears at all, and once to decide what to do with what the player typed into it. Two copies of
 * a readiness rule drifting apart is a screen that offers a text box that goes nowhere.
 */
object MindWiring {

    /**
     * Whether the player has left this particular job switched on for the remote brain.
     *
     * Exhaustive on purpose: a fifth job added to [MindRole] is a compile error here rather than
     * a job that quietly runs regardless of what settings says about it.
     */
    fun allows(config: MindConfig, role: MindRole): Boolean = when (role) {
        MindRole.CONVERSE -> config.conversation
        MindRole.DECIDE -> config.decidesActions
        MindRole.PLAN -> config.makesPlans
        MindRole.DISTIL -> config.lineageLessons
    }

    /**
     * Whether the remote brain is worth calling for [ask].
     *
     * [MindAsk.remoteRole] being null is the strong case and it is not a formality: it is how
     * [MindAsk.CHATTER] says *never remote, however well configured the network is*. Reading the
     * role off the ask rather than passing one in is what stops a caller from spending the
     * conversation budget on a line that had to be instant.
     *
     * @param clientReady what the provider itself says, which is the only thing that knows
     *   whether a route was ever pasted in.
     */
    fun remoteReady(config: MindConfig, ask: MindAsk, clientReady: Boolean): Boolean {
        val role = ask.remoteRole ?: return false
        return config.usable && allows(config, role) && clientReady
    }

    /**
     * Whether the model on the phone can answer right now.
     *
     * [engineReady] is the engine's own word for "loaded and answering", and it is the half that
     * cannot be inferred from the save: a save can say a model is installed while the engine is
     * still ten seconds into reading it, or while the screen that owns it has already gone.
     */
    fun onDeviceReady(config: LocalMindConfig, engineReady: Boolean): Boolean =
        config.enabled && engineReady

    /**
     * Why the talk screen cannot offer a composer, or null when it can.
     *
     * Derived from the route rather than from the settings, so that the one thing the player
     * cares about — *can this creature answer me* — is answered by whoever is actually going to
     * answer. A phone with a model loaded and no key pasted is [READY], which the old
     * settings-only reading got wrong in the direction that matters: it told a player with a
     * working brain in their hand that they had nowhere to think.
     */
    fun talkBlock(mind: MindConfig, route: MindRoute): TalkBlock? = when {
        route.runsAModel -> null
        // A remote route exists and is switched on, and conversation specifically is switched
        // off. Worth its own sentence: it is one toggle away from working, and "no brain" would
        // send the player looking for a key they already have.
        mind.usable && !mind.conversation -> TalkBlock.TALK_OFF
        else -> TalkBlock.NO_BRAIN
    }

    /**
     * Who to ask for an answer, in order, until one of them gives one.
     *
     * [MindRoute] names three parts and they are not a sequence: [MindRoute.answersNow] is who
     * writes what the player reads, [MindRoute.mayReplace] is who is allowed to improve it later,
     * and [MindRoute.ifSilent] is who covers. This turns them into the one order a caller with
     * nothing on screen yet actually wants, and the interesting entry is the middle one.
     *
     * A route with a second brain allowed to *improve* an answer certainly permits it to *give*
     * one when there is none. Without that, [MindRoute.OnDeviceThenRemote] with a local model that
     * came back empty — a small model producing prose instead of JSON, which is an ordinary
     * Tuesday — would drop straight to the written brain while a perfectly good remote route sat
     * there configured and never asked. The player would lose an answer they were entitled to,
     * and the failure would look exactly like a model with nothing to say.
     *
     * Deduplicated, because a route may name the same brain twice and asking a model that just
     * declined to answer the identical question a second time is a second wait for the same
     * silence.
     */
    fun askOrder(route: MindRoute): List<MindAnswerer> =
        listOfNotNull(route.answersNow, route.mayReplace, route.ifSilent).distinct()

    /**
     * The conversation with one of the creature's own lines rewritten, or null to leave it alone.
     *
     * This is [MindRoute.OnDeviceThenRemote] arriving: the model on the phone already said
     * something, the player has been reading it for a second or two, and now the better answer
     * has come back. Replacing rather than appending is the whole point — two answers to one
     * question is not a creature thinking harder, it is a bug that repeats itself.
     *
     * Null when the line to replace is no longer the newest thing the creature said. That is not
     * an edge case: this game ticks once a second, the creature can speak on its own, and the
     * player can send again while the network is still out. Rewriting history further back would
     * change a line the player has already read and moved past, so a late reply that missed its
     * moment is dropped and nobody is told — the same treatment every other late answer gets.
     */
    fun replacingPetLine(
        chat: List<ChatTurn>,
        previous: String,
        replacement: String,
    ): List<ChatTurn>? {
        if (replacement.isBlank() || replacement == previous) return null
        val last = chat.lastOrNull() ?: return null
        if (!last.fromPet || last.text != previous) return null
        return chat.dropLast(1) + last.copy(text = replacement)
    }
}

/** Why the talk screen has no composer. [MindWiring.talkBlock] returns null for the ready case. */
enum class TalkBlock {
    /** Nothing anywhere can answer: no key, no proxy, and no model on the phone. */
    NO_BRAIN,

    /** There is a remote brain and the player has switched talking off in settings. */
    TALK_OFF,
}
