package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * Everything the player has said about the model on their phone.
 *
 * Preferences and one fact. [installed] is the fact — a record of what was actually downloaded,
 * written by whatever does the downloading — and it lives here rather than being probed off the
 * filesystem every time because three different screens need to know, and because a save that
 * remembers is a save that can say "you have this, and it takes 2.4 GB, and here is the button
 * that deletes it" without waiting on I/O. The engine still checks the file before loading it;
 * this is a record, not a promise.
 *
 * Every field has a default, so a save written before any of this existed loads into exactly the
 * game the player left: nothing installed, nothing offered yet, and Wi-Fi assumed.
 */
@Serializable
data class LocalMindConfig(
    /**
     * Whether the on-device model may be used, and whether the download may be offered.
     *
     * On by default, which costs nothing: the offer is gated behind everything in
     * [LocalModelOffer.decide] and there is no model until somebody downloads one. Off means the
     * player has said no to the whole idea, and it stops the asking as well as the running.
     */
    val enabled: Boolean = true,

    /**
     * Which variant the player has asked for, or null for "whatever fits".
     *
     * Honoured only where [LocalModelFit.permits] it. A player who has asked for something their
     * phone cannot hold gets the largest that fits instead, silently — see [LocalModelFit.choose]
     * for why that direction is not negotiable.
     */
    val preferred: LocalModelVariant? = null,

    /** What is on disk. Null means nothing has been downloaded. */
    val installed: LocalModelVariant? = null,

    /**
     * Wi-Fi only, by default.
     *
     * Three gigabytes over mobile data is a thing that happens once and is never forgiven. The
     * default is the cautious one and the override is explicit and per-download rather than a
     * switch that quietly stays on — see [DownloadGate].
     */
    val wifiOnly: Boolean = true,

    /** How many times the download has been offered without being asked for. */
    val offersMade: Int = 0,

    /** Which generation the last unprompted offer was made in. Zero means none has been. */
    val lastOfferGeneration: Int = 0,
) {
    /** True when there is a model to run. */
    val hasModel: Boolean get() = installed != null

    /** True when the on-device model should actually be used. */
    val usable: Boolean get() = enabled && installed != null

    /**
     * This config after the offer has been shown once.
     *
     * Kept here rather than in the screen so that the throttle cannot be forgotten by whoever
     * wires the dialog up — an offer that does not record itself is an offer that appears on
     * every single frame.
     */
    fun afterOffering(generation: Int): LocalMindConfig =
        copy(offersMade = offersMade + 1, lastOfferGeneration = generation)
}

/** What the phone's connection is doing, as much of it as this decision needs. */
enum class NetworkKind {
    /** Nothing usable. */
    NONE,

    /** Wi-Fi, or anything else the system does not consider metered. */
    UNMETERED,

    /** Mobile data, or a hotspot the player has flagged. Somebody is paying by the gigabyte. */
    METERED,
}

/** Whether a download may start right now. */
enum class DownloadGate {
    /** Start it. */
    GO,

    /**
     * It would spend mobile data and the player has not said that is fine.
     *
     * Deliberately not "refuse". The player may be on a plan where this is nothing, or may be
     * about to board a plane, and an app that decides for them is an app they have to fight.
     * What they get is the size, in plain figures, and a button. What they never get is the
     * download starting without that conversation.
     */
    NEEDS_MOBILE_CONSENT,

    /** No connection at all. Nothing to consent to. */
    NO_NETWORK,
}

/** Why the download is not being offered right now, or that it is. */
enum class OfferVerdict {
    /** Show it. */
    OFFER,

    /** This phone cannot hold any of them. See [LocalModelFit.blocker] for which reason. */
    DEVICE_CANNOT,

    /** There is already one on disk. */
    ALREADY_INSTALLED,

    /** The player has switched the whole feature off. */
    TURNED_OFF,

    /** The creature is not yet somebody worth spending gigabytes on. */
    TOO_EARLY,

    /** It has been offered before, in this life or enough times. Settings still has it. */
    ASKED_ENOUGH,

    /** Something is wrong with the creature and this is not the moment. */
    BAD_MOMENT,
}

/**
 * Whether to put the download in front of the player, and what to say.
 *
 * ## When, and why not on the first run
 *
 * The instinct in `docs/CEREBRO-LOCAL.md` §8 is right and this implements it: asking somebody for
 * gigabytes two minutes into an egg is the fastest route to an uninstall. They have nothing yet.
 * The download is expensive, the benefit is abstract, and the thing it improves is a creature they
 * have not met — so the honest reading of the prompt is "this game wants three gigabytes", and the
 * honest response is to delete it.
 *
 * So the trigger is read out of the save's own state, and the question it asks is *does this
 * player have something to lose*. Three signals answer yes, and any one is enough:
 *
 *  - **The creature grew up.** Reaching [LifeStage.TEEN] is not something that happens to a pet
 *    left alone; somebody fed it through childhood. It is the plainest evidence the save has that
 *    this person is still here.
 *  - **The bond got high.** [PetState.peakBond] is the save's own measurement of attachment, and
 *    it is the *peak* rather than the current value, so a good relationship that has since drifted
 *    still counts. It should: they had it once.
 *  - **They are on a second generation.** The strongest signal of the three by a distance. The
 *    worst thing this game can do to somebody has already happened to them and they started
 *    again. Nobody does that by accident.
 *
 * And two things hold it back even then. It is asked at most twice, once per generation, because
 * an offer that returns is an advertisement. And it is never asked while the creature is ill or
 * badly hurt — a player dealing with a sick pet is being asked to context-switch to a download
 * screen, which reads as an app that is not paying attention to the thing it just told them to
 * worry about.
 *
 * Settings has the download permanently, for anybody who wants it on day one. This decides only
 * whether the game *brings it up*.
 */
data class LocalModelOffer(
    val verdict: OfferVerdict,
    /** What would be downloaded. Null unless [verdict] is [OfferVerdict.OFFER]. */
    val variant: LocalModelVariant?,
    /** Its exact size, so the figure the player sees is the figure they spend. */
    val downloadBytes: Long,
    /** True when the download will wait for Wi-Fi rather than start on mobile data. */
    val waitsForWifi: Boolean,
    val headline: String?,
    val note: String?,
) {
    /** The only question the caller usually has. */
    val shouldShow: Boolean get() = verdict == OfferVerdict.OFFER

    companion object {
        /** Grown up under this player's care. */
        val GROWN_UP = LifeStage.TEEN

        /** Peak bond that counts as attachment. Out of 100, and reached by looking after it. */
        const val ATTACHED_BOND = 60f

        /** Buried one and came back. */
        const val COMMITTED_GENERATION = 2

        /** Unprompted offers in the life of a save. Twice is a reminder; three times is nagging. */
        const val MAX_OFFERS = 2

        /** Health under which the player has more pressing things on. */
        const val CALM_HEALTH = 50f

        /**
         * The decision.
         *
         * Ordered so that the answer is the most specific true thing rather than the first
         * plausible one: a phone that cannot hold a model is told that, not "too early", because
         * "too early" implies waiting will help and it will not.
         */
        fun decide(
            pet: PetState,
            config: GameConfig,
            fit: LocalModelFit,
        ): LocalModelOffer {
            val local = config.localMind
            val variant = fit.choose(local.preferred)

            val verdict = when {
                // Nothing this phone can hold. Permanent, and said as such.
                variant == null -> OfferVerdict.DEVICE_CANNOT
                local.installed != null -> OfferVerdict.ALREADY_INSTALLED
                !local.enabled -> OfferVerdict.TURNED_OFF

                // An egg or a baby has nothing to say and nothing to lose. This is the gate the
                // whole section is about.
                !pet.isMindAwake -> OfferVerdict.TOO_EARLY
                !hasSomethingToLose(pet) -> OfferVerdict.TOO_EARLY

                // Asked before. Settings still has it; the game stops bringing it up.
                local.offersMade >= MAX_OFFERS -> OfferVerdict.ASKED_ENOUGH
                pet.generation <= local.lastOfferGeneration -> OfferVerdict.ASKED_ENOUGH

                // Checked last, so a sick creature reads as "not now" rather than as "never".
                pet.isSick || pet.stats.health < CALM_HEALTH -> OfferVerdict.BAD_MOMENT

                else -> OfferVerdict.OFFER
            }

            if (verdict != OfferVerdict.OFFER || variant == null) {
                return LocalModelOffer(
                    verdict = verdict,
                    variant = null,
                    downloadBytes = 0L,
                    waitsForWifi = local.wifiOnly,
                    headline = null,
                    note = null,
                )
            }

            return LocalModelOffer(
                verdict = verdict,
                variant = variant,
                downloadBytes = variant.downloadBytes,
                waitsForWifi = local.wifiOnly,
                // Written in the second person and about the creature, because that is what the
                // player is being asked to spend the space on. Not "enable on-device inference".
                headline = "${pet.name} could think for itself",
                note = "There is a model that runs on this phone, with no internet and no " +
                    "account. It would make ${pet.name} quicker to answer and better company " +
                    "when you are somewhere without a signal. It is a ${variant.displayName} " +
                    "download and it is entirely optional — the game is complete without it, " +
                    "and you can delete it again in one tap.",
            )
        }

        /**
         * Whether this save represents somebody with something to lose.
         *
         * Any one of the three. They are not degrees of the same thing — a gen-2 child, a
         * much-loved baby and a teenager raised by somebody quiet are three different players,
         * and all three have earned the question.
         */
        fun hasSomethingToLose(pet: PetState): Boolean =
            pet.generation >= COMMITTED_GENERATION ||
                pet.peakBond >= ATTACHED_BOND ||
                pet.stage.order >= GROWN_UP.order

        /**
         * Whether the download may start on the connection the phone has.
         *
         * [consentedToMobile] is a per-download answer rather than a stored setting, on purpose.
         * "Yes, this once, I know" and "always use my data allowance for large files" are
         * different sentences, and only the first is one anybody actually means.
         */
        fun gate(
            network: NetworkKind,
            wifiOnly: Boolean,
            consentedToMobile: Boolean = false,
        ): DownloadGate = when {
            network == NetworkKind.NONE -> DownloadGate.NO_NETWORK
            network == NetworkKind.UNMETERED -> DownloadGate.GO
            !wifiOnly || consentedToMobile -> DownloadGate.GO
            else -> DownloadGate.NEEDS_MOBILE_CONSENT
        }
    }
}
