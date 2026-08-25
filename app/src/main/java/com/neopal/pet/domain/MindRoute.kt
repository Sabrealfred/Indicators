package com.neopal.pet.domain

/**
 * What the creature has been asked for. Not *who* is asking and not *what the network is doing* —
 * only the shape of the question.
 *
 * ## Why routing by connectivity is wrong
 *
 * "If there is internet, use the big one" sounds right and produces a worse game. The remote model
 * is not simply better: it is slower, it spends somebody's quota, and it fails in ways the local
 * one cannot — DNS, a 429, a proxy that went down last Tuesday. The on-device model is instant and
 * free. Routing on connectivity throws that advantage away every time the phone happens to have a
 * signal, which is most of the time.
 *
 * So the routing is by the question. A two-line remark when the creature is poked does not need
 * four and a half billion parameters, and waiting two seconds for it breaks the toy. A plan, a
 * distilled life, a decision with consequences: those are the three things that genuinely benefit
 * from a large model, and all three tolerate seconds. The local model is not a degraded stand-in
 * for the remote one — it is the one that answers *fast*, and the remote one is the one that
 * answers *well*.
 */
enum class MindAsk(
    val displayName: String,
    /**
     * Whether any generative model may run for this at all.
     *
     * False for exactly one entry, and it is the most important rule in the feature. See
     * [AUTONOMOUS_LOOP].
     */
    val mayRunAModel: Boolean,
    /**
     * Which remote budget this spends, or null when the remote is never asked.
     *
     * The link exists so a route and a token budget cannot disagree. A null here and a route that
     * reaches [MindAnswerer.REMOTE] would be a request sent with nobody's allowance, which
     * [MindRouterTest] makes impossible rather than unlikely.
     */
    val remoteRole: MindRole?,
) {
    /**
     * A line when the creature is tapped, a remark it makes on its own, ambient chatter.
     *
     * Local, always, even when a remote route is configured and idle. This is the row of the
     * table that people argue with, so: the value of these lines is entirely that they are *there
     * when the finger lands*. A better sentence that arrives two seconds later is not a better
     * line, it is a broken toy, and the creature saying nothing for two seconds while a spinner
     * turns is worse than either.
     */
    CHATTER("A line on the spot", mayRunAModel = true, remoteRole = null),

    /**
     * An answer on the talk screen, where the player has typed something and is waiting.
     *
     * The only row where both models run. See [MindRoute.OnDeviceThenRemote].
     */
    TALK_REPLY("An answer on the talk screen", mayRunAModel = true, remoteRole = MindRole.CONVERSE),

    /**
     * A decision the player will see the consequences of, made while they are watching.
     *
     * Distinct from [AUTONOMOUS_LOOP] even though both end in the creature choosing an activity,
     * and the distinction is not the decision — it is who is in the room. This one is throttled,
     * visible, and happens because somebody is looking at the pet.
     */
    CHOICE("A decision with consequences", mayRunAModel = true, remoteRole = MindRole.DECIDE),

    /** An errand: a short sequence with a goal behind it. Rare, and worth thinking about. */
    PLAN("An errand", mayRunAModel = true, remoteRole = MindRole.PLAN),

    /** A finished life turned into lessons for the next one. Once per generation. */
    DISTIL("A life's lesson", mayRunAModel = true, remoteRole = MindRole.DISTIL),

    /**
     * Anything the autonomous half asks while nobody is looking.
     *
     * **Neither model. Ever.** This is not a preference to be overridden by a good enough reason;
     * it is the limit the rest of the feature is built inside. The autonomous half runs every few
     * minutes, all day, with the app closed. A model generating there is continuous battery and
     * continuous heat for output nobody is present to read, and no player would ask for it — they
     * would simply notice the phone getting warm in their pocket and uninstall the thing that was
     * doing it.
     *
     * The hand-written brain already decides perfectly well here, has done since before any of
     * this existed, and costs nothing.
     */
    AUTONOMOUS_LOOP("The autonomous loop", mayRunAModel = false, remoteRole = null),
}

/** The three things that can produce an answer. */
enum class MindAnswerer(val displayName: String) {
    /**
     * The hand-written brain: [Brain], [CreatureVoice], [TalkVoice]. Not a model, never fails,
     * never absent, costs nothing, and is what the game has always run on. Everything else in
     * this file is an enhancement on top of it.
     */
    SCRIPTED("Its own head"),

    /** The model on the phone. Instant and free, and only awake while somebody is watching. */
    ON_DEVICE("The model on this phone"),

    /** The model somewhere else. Slow, better, and allowed to be unreachable. */
    REMOTE("The model on the internet"),
}

/**
 * Who answers, and what happens to that answer afterwards.
 *
 * A sealed set rather than a pair of booleans, because the interesting case is not "which model"
 * but *"the local one now, and the remote one may overwrite it"* — a sequence with two answers in
 * it, one of which may never arrive. Encoded as flags, that state reads as `useLocal = true,
 * useRemote = true`, which is indistinguishable from "ask both and take whichever" and from "ask
 * the remote and fall back", and a caller reading those flags has to reconstruct the intent from
 * memory. Naming the state means the caller cannot get it wrong, and means this file can say
 * plainly that the local reply is not a placeholder: if the remote never lands, nobody ever knows
 * it was asked.
 */
sealed interface MindRoute {

    /** Who writes the words the player sees first. */
    val answersNow: MindAnswerer

    /** Who is allowed to overwrite those words later, or null when the first answer is final. */
    val mayReplace: MindAnswerer?

    /** Who answers instead when [answersNow] comes back empty, or null when it cannot. */
    val ifSilent: MindAnswerer?

    /** Every generative model this route may actually run. Empty means none, which is a state. */
    val engines: Set<MindAnswerer>
        get() = setOfNotNull(answersNow, mayReplace, ifSilent) - MindAnswerer.SCRIPTED

    /** True when this route costs battery or quota. The autonomous half must never see this true. */
    val runsAModel: Boolean get() = engines.isNotEmpty()

    /**
     * No model runs. The hand-written brain answers, as it always can.
     *
     * Not silence and not a failure: it is the game as it shipped before any of this, and it is
     * the answer for every request in the autonomous loop, for every phone that cannot hold a
     * model, and for every player who has not downloaded one.
     */
    data object Scripted : MindRoute {
        override val answersNow = MindAnswerer.SCRIPTED
        override val mayReplace: MindAnswerer? = null
        override val ifSilent: MindAnswerer? = null
    }

    /** The model on the phone answers, and its answer stands. */
    data object OnDevice : MindRoute {
        override val answersNow = MindAnswerer.ON_DEVICE
        override val mayReplace: MindAnswerer? = null

        // A small model returns nothing more often than a large one does, and the brief already
        // treats that as an ordinary Tuesday rather than an error. The written brain covers.
        override val ifSilent = MindAnswerer.SCRIPTED
    }

    /**
     * The local model writes while the remote one thinks, and the remote reply replaces it if it
     * arrives.
     *
     * The one row of the table where both models run, and the reason this type is sealed rather
     * than a flag. The player is never waiting: they get a reply immediately, and it is a real
     * reply rather than a placeholder — if the network is down, or slow, or out of quota, nobody
     * ever learns that a second answer was asked for. The replacement is visible when it happens,
     * because a creature that thinks again and says it better is a creature, not a bug.
     */
    data object OnDeviceThenRemote : MindRoute {
        override val answersNow = MindAnswerer.ON_DEVICE
        override val mayReplace = MindAnswerer.REMOTE
        override val ifSilent = MindAnswerer.SCRIPTED
    }

    /**
     * The remote model is asked and the player waits, because what they asked for is worth the
     * wait.
     *
     * [ifSilent] is not an afterthought. On a free tier, "did not come back" is a normal outcome,
     * and naming who covers is what stops a timeout from being a screen with nothing on it.
     */
    data class Remote(override val ifSilent: MindAnswerer) : MindRoute {
        override val answersNow = MindAnswerer.REMOTE
        override val mayReplace: MindAnswerer? = null
    }
}

/**
 * The table in `docs/CEREBRO-LOCAL.md` §4, as a function.
 *
 * Pure, and tested, for the same reason [Cadence] is: every wrong answer here fails quietly. A
 * route that wrongly says "scripted" produces a creature that is merely duller, which nobody
 * reports; a route that wrongly reaches the remote model spends somebody's quota on a line that
 * had to be instant; and a route that wrongly runs a model in the autonomous loop produces a warm
 * phone and a flat battery, which the player will blame on this app and be right to.
 */
object MindRouter {

    /**
     * Who answers [ask].
     *
     * The two gates at the top are deliberately redundant. [MindAsk.mayRunAModel] makes the hard
     * limit a property of the question — a caller in the autonomous loop cannot route around it by
     * passing the right flags — and [playerPresent] makes it a property of the moment, so a caller
     * that mislabels a background tick as a foreground one is still refused. Either alone would be
     * enough on a day when everybody wires it up correctly. The rule is important enough not to
     * depend on that.
     *
     * @param onDeviceReady the engine is loaded and the model is on disk. Not "a model could be
     *   installed" — loading takes seconds, so this is the state of the engine right now.
     * @param remoteReady a remote route is configured, switched on, and worth calling.
     * @param playerPresent somebody is looking at the screen. False means the app is not in front
     *   of anybody, whatever [ask] claims.
     */
    fun route(
        ask: MindAsk,
        onDeviceReady: Boolean,
        remoteReady: Boolean,
        playerPresent: Boolean = true,
    ): MindRoute {
        if (!ask.mayRunAModel || !playerPresent) return MindRoute.Scripted
        return when (ask) {
            // Instant or worthless. The remote model is not asked even when it is sitting there
            // configured and idle, because the two seconds it costs are the whole thing.
            MindAsk.CHATTER ->
                if (onDeviceReady) MindRoute.OnDevice else MindRoute.Scripted

            MindAsk.TALK_REPLY -> when {
                onDeviceReady && remoteReady -> MindRoute.OnDeviceThenRemote
                onDeviceReady -> MindRoute.OnDevice
                // No local model to write in the meantime, so this is the one place the player
                // watches a spinner. The scripted brain covers a timeout.
                remoteReady -> MindRoute.Remote(ifSilent = MindAnswerer.SCRIPTED)
                else -> MindRoute.Scripted
            }

            // The three that tolerate seconds and are actually improved by a larger model. Here,
            // and only here, remote is the preference rather than the enhancement.
            MindAsk.CHOICE, MindAsk.PLAN, MindAsk.DISTIL -> when {
                remoteReady -> MindRoute.Remote(
                    ifSilent = if (onDeviceReady) MindAnswerer.ON_DEVICE else MindAnswerer.SCRIPTED,
                )
                onDeviceReady -> MindRoute.OnDevice
                else -> MindRoute.Scripted
            }

            // Unreachable: the gate above already returned. Present because this `when` has no
            // `else`, so adding a request kind is a compile error here rather than a silent
            // default somewhere — which is how a new kind of background work would otherwise
            // acquire a model without anybody deciding it should.
            MindAsk.AUTONOMOUS_LOOP -> MindRoute.Scripted
        }
    }
}
