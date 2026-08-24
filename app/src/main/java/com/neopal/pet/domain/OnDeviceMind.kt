package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/*
 * The rules for a model that runs on the handset itself.
 *
 * Every decision an on-device model needs taken *before* it is loaded lives here, in pure Kotlin,
 * because all three of them fail in ways that cannot be seen from outside the phone:
 *
 *  - Deciding it does not fit is silent. The creature simply answers from the local [Brain], which
 *    is what it does when nothing is configured at all. A memory check that is wrong in the
 *    permissive direction is not silent, though: it is the process being killed, which the player
 *    reads as the pet dying. That asymmetry is why this is arithmetic with a test suite and not a
 *    line inside an Android class nobody can run.
 *  - Deciding which jobs it may be asked is the whole of §5 of docs/CEREBRO-LOCAL.md, and a
 *    mistake there is a 4 B model generating every few minutes, all day, with the app closed.
 *  - Clamping the budgets is the difference between a creature that answers and one that heats the
 *    phone until the system throttles it.
 *
 * Nothing here knows what Android is, what LiteRT-LM is, or where the weights came from.
 */

/**
 * Which weights the engine would load, and what they cost.
 *
 * **This is a seam, not a decision.** Working out which of the three steps a given handset can
 * actually sustain belongs to the model-fit types in this package, and at the time this was
 * written those did not exist on this tree. This type is the narrow thing the engine wrapper
 * needs handed to it once that decision has been taken somewhere else: a path, a size, and the
 * floor its publisher declares. When the fit types land, they should produce one of these — or
 * this should be deleted and replaced by whatever they produce. It is deliberately four fields
 * with no logic so that either is a small change.
 *
 * [residentBytes] is the size of the `.litertlm` file, which is what it occupies once it is
 * resident. [deviceGigabytes] is `minDeviceMemoryInGb` from Google's own model list — the total
 * device RAM they declare the model needs, which for the three steps in the design is 6, 8 and 12.
 */
@Serializable
data class OnDeviceModel(
    /** Stable identifier, for a settings screen to name what is installed. Never shown to a model. */
    val id: String,
    /** Absolute path on permanent private storage. Never `cacheDir`: see §5 of the design. */
    val absolutePath: String,
    /** Bytes the weights occupy once loaded — in practice, the size of the file. */
    val residentBytes: Long,
    /** Whole gigabytes of device RAM the model's publisher requires. */
    val deviceGigabytes: Int,
)

/**
 * What the player has switched on, and how much rope the engine is given.
 *
 * Not part of [GameConfig], deliberately and for now. Adding a field to the saved configuration
 * touches the save envelope and the settings screen, both of which belong to other work in
 * flight; this is `@Serializable` so that adding it later is one field and no migration. Until
 * then the wrapper is constructed with one of these directly and a player cannot reach it.
 */
@Serializable
data class OnDeviceMindConfig(
    /** Off until the player asks for it. A 3 GB download is never a default. */
    val enabled: Boolean = false,
    /** Null until something has been downloaded and found to fit. */
    val model: OnDeviceModel? = null,
    /**
     * The context window handed to the engine, in tokens — prompt *and* reply together.
     *
     * It has to clear the character sheet, the brief and eight turns of conversation with room to
     * answer, and every token above that is memory and latency spent on nothing. It is also the
     * only ceiling on a model that has started repeating itself that does not depend on a clock.
     */
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    /**
     * How long one answer may take before the local brain covers for it.
     *
     * Shorter than the remote budget on purpose. The whole argument for a model on the handset is
     * that it answers *now* — see §4 of the design — so a local model that takes as long as the
     * network has thrown away the only advantage it had.
     */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /** True when there is something to load and the player has asked for it to be loaded. */
    val usable: Boolean
        get() = enabled && model != null && model.absolutePath.isNotBlank() && model.residentBytes > 0L

    /**
     * The same settings with every number inside its bounds and an incoherent model dropped.
     *
     * Called before the config is used rather than trusted where it was written, because it can
     * arrive from a save file written by an older build, and a context window of zero is not a
     * setting — it is an engine that fails to initialise for a reason nobody will ever diagnose.
     */
    fun sanitised(): OnDeviceMindConfig = copy(
        contextTokens = contextTokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS),
        timeoutMillis = timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS),
        model = model?.takeIf { it.absolutePath.isNotBlank() && it.residentBytes > 0L && it.deviceGigabytes > 0 },
    )

    companion object {
        /**
         * Room for the character sheet, the brief, eight turns and a two-line answer, and no more.
         *
         * Measured against nothing — there is no device here. It is an estimate from the prompt
         * this actually sends, and the first handset to run it is what settles the number.
         */
        const val DEFAULT_CONTEXT_TOKENS = 1536
        const val MIN_CONTEXT_TOKENS = 512
        const val MAX_CONTEXT_TOKENS = 4096

        /**
         * Six seconds. Long enough for a small model to prefill a brief and produce two lines on a
         * mid-range phone, short enough that a player who asked a question has not yet decided the
         * creature is broken.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 6_000L

        /**
         * The same floor and ceiling the remote route uses, restated rather than imported: the
         * remote bounds live on `MindWire`, which is inside the data layer, and the domain does
         * not depend on the data layer in this project. If one pair moves the other must.
         */
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 60_000L
    }
}

/**
 * What the handset says about its own memory, as four numbers and two flags.
 *
 * Read from `ActivityManager` at the moment of loading and handed straight here. It is a snapshot
 * and it is stale the instant it is taken, which is why [OnDeviceMind.verdict] leaves a margin
 * rather than treating it as a promise.
 */
data class OnDeviceMemory(
    /** `MemoryInfo.totalMem`: physical RAM the kernel can see. Always less than the box claims. */
    val totalBytes: Long,
    /** `MemoryInfo.availMem`: what is free right now. */
    val availableBytes: Long,
    /** `MemoryInfo.threshold`: below this the system starts killing background processes. */
    val thresholdBytes: Long,
    /** `MemoryInfo.lowMemory`: it is already killing them. */
    val lowMemoryNow: Boolean,
    /** `ActivityManager.isLowRamDevice`: the handset was built to run without much. */
    val lowRamDevice: Boolean,
) {
    companion object {
        /** A reading that could not be taken. Refuses, because unknown is not the same as fine. */
        val UNKNOWN = OnDeviceMemory(0L, 0L, 0L, lowMemoryNow = false, lowRamDevice = false)
    }
}

/**
 * Whether the weights may be loaded, and if not, whether asking again later could change it.
 *
 * The split between [NotNow] and [TooBig] is the useful part. "Too big" is a fact about this
 * handset and this model and it will be true tomorrow, so the offer should stop being made. "Not
 * now" is a fact about this minute — a browser with forty tabs, a camera that just closed — and
 * the same request in five minutes may well succeed. Collapsing them into one "no" means either
 * nagging a phone that can never run it or permanently writing off a phone that was merely busy.
 *
 * Every reason is a sentence, for the same reason [com.neopal.pet.data.Heard] is: a screen that
 * says "unavailable" has told the player nothing they can act on.
 */
sealed interface OnDeviceVerdict {

    /** Load it. */
    data object Fits : OnDeviceVerdict

    /** Not at this moment. Worth trying again; do not withdraw the feature. */
    data class NotNow(val reason: String) : OnDeviceVerdict

    /** Not on this handset, ever, at this size. Withdraw the offer and stop asking. */
    data class TooBig(val reason: String) : OnDeviceVerdict
}

/**
 * The three rules an on-device model is held to before it is allowed to exist.
 */
object OnDeviceMind {

    /**
     * The only job the on-device model is ever asked to do.
     *
     * This is §5 of docs/CEREBRO-LOCAL.md written as code rather than as a comment, and it is a
     * limit rather than a preference. The autonomous half of this game runs every few minutes,
     * all day, with the app closed; the two [MindProvider] methods it calls are `choose` (from the
     * reconsider throttle) and `plan` (from the errand throttle). A 4 B model generating on that
     * schedule is continuous battery and continuous heat for something nobody asked for, and the
     * player would never find out why their phone was warm.
     *
     * `distil` is refused as well, and that is a smaller call. It runs once per life rather than
     * on a timer, so it is not the autonomous loop — but it is the single largest generation in
     * the game, it happens while the player is looking at a memorial rather than at a conversation,
     * and [Lineage.distilLocally] has already given the heir its inheritance by the time it would
     * be asked. Nothing is lost by refusing it, and refusing it means there is exactly one method
     * on this provider with a path to the engine.
     *
     * The index-into-a-validated-list contract described on [MindChoice] is what would make a
     * small model safe to let decide, and it still holds — it is simply not reachable, because the
     * one call site of `choose` in this app is a timer.
     */
    val SERVES: Set<MindRole> = setOf(MindRole.CONVERSE)

    /** True when the on-device engine may be asked to do [role] at all. */
    fun serves(role: MindRole): Boolean = role in SERVES

    /**
     * How much total RAM a handset must report before a model declaring [deviceGigabytes] is
     * considered at all.
     *
     * Not simply `gigabytes * 2^30`. `MemoryInfo.totalMem` is what the kernel can see after the
     * bootloader, the modem and the graphics carve-out have taken their share, so a handset sold
     * as 8 GB reports somewhere in the sevens. Comparing against the round number would refuse
     * every phone in the tier the number was written for, which is the failure that looks like the
     * feature being broken rather than the feature being careful.
     *
     * [ADVERTISED_PERCENT] is an allowance for that carve-out, not a discount on the requirement.
     */
    fun requiredDeviceBytes(deviceGigabytes: Int): Long =
        if (deviceGigabytes <= 0) 0L else deviceGigabytes.toLong() * GIB / 100L * ADVERTISED_PERCENT

    /**
     * Free memory a load needs, beyond what the file itself occupies.
     *
     * The weights are the floor, not the total: prefill allocates a key-value cache that grows
     * with the context window, and the runtime wants scratch on top. [WORKING_PERCENT] covers
     * both, and [SPARE_BYTES] is left over the top so that the rest of this app — a Compose
     * screen, a save, a speech engine — is not the thing that gets squeezed out by its own
     * creature.
     */
    fun neededBytes(residentBytes: Long): Long =
        if (residentBytes <= 0L) Long.MAX_VALUE else residentBytes / 100L * (100L + WORKING_PERCENT) + SPARE_BYTES

    /**
     * Whether to load, checked *before* loading rather than after failing.
     *
     * That ordering is the whole point of this function. Running out of memory here is not an
     * exception that can be caught and degraded from: the kernel's out-of-memory killer takes the
     * process, the app vanishes mid-sentence, and to a player who has been raising this creature
     * for a week that is indistinguishable from it having died. There is no `catch` that reaches
     * that, so the only defence is not to start.
     *
     * The order of the checks is from most permanent to most transient, so that the reason the
     * player is given is the most useful true one.
     */
    fun verdict(model: OnDeviceModel, memory: OnDeviceMemory): OnDeviceVerdict {
        if (model.absolutePath.isBlank() || model.residentBytes <= 0L) {
            return TOO_BIG_NO_MODEL
        }
        // A handset the manufacturer flagged as low-RAM has told us the answer itself. Android
        // uses this flag to switch off features far cheaper than this one.
        if (memory.lowRamDevice) return TOO_BIG_LOW_RAM

        val required = requiredDeviceBytes(model.deviceGigabytes)
        // A total of zero means the reading failed rather than that the phone has no memory, so it
        // is not treated as a permanent verdict here — it falls through to the headroom check
        // below, which refuses it as "not now". Unknown is refused either way; the difference is
        // only whether the offer is withdrawn for good on the strength of a failed read.
        if (memory.totalBytes > 0L && memory.totalBytes < required) {
            return OnDeviceVerdict.TooBig(
                "This phone has less memory than this brain needs. The smaller one may still fit.",
            )
        }

        if (memory.lowMemoryNow) return NOT_NOW_PRESSURE

        // The threshold is the line at which the system starts killing things. Memory below it is
        // not ours to spend, so it is subtracted rather than counted.
        val headroom = memory.availableBytes - memory.thresholdBytes.coerceAtLeast(0L)
        if (headroom < neededBytes(model.residentBytes)) return NOT_NOW_HEADROOM

        return OnDeviceVerdict.Fits
    }

    /**
     * How many CPU threads the engine is given.
     *
     * Half the cores, never fewer than two and never more than four. Not all of them, deliberately:
     * the creature is being drawn and possibly spoken aloud on the same handset at the same moment,
     * and a decode that saturates every core is the sustained heat §5 of the design warns about —
     * which throttles the phone and makes everything else in the app worse, including the frame
     * the player is looking at while they wait.
     */
    fun threadsFor(cores: Int): Int = if (cores <= 0) MIN_THREADS else (cores / 2).coerceIn(MIN_THREADS, MAX_THREADS)

    /** Share of advertised RAM the kernel actually reports. See [requiredDeviceBytes]. */
    const val ADVERTISED_PERCENT = 85L

    /** Extra over the weights for the key-value cache and the runtime's scratch. */
    const val WORKING_PERCENT = 20L

    /** Left free on top, so this app's own screen is not what gets squeezed out. 192 MiB. */
    const val SPARE_BYTES = 192L * 1024L * 1024L

    const val MIN_THREADS = 2
    const val MAX_THREADS = 4

    private const val GIB = 1024L * 1024L * 1024L

    private val TOO_BIG_NO_MODEL = OnDeviceVerdict.TooBig("There is no brain installed to load.")

    private val TOO_BIG_LOW_RAM = OnDeviceVerdict.TooBig(
        "This phone is built to run on little memory, so a brain this size cannot live on it.",
    )

    private val NOT_NOW_PRESSURE = OnDeviceVerdict.NotNow(
        "The phone is short of memory at the moment. Closing something else and trying again works.",
    )

    private val NOT_NOW_HEADROOM = OnDeviceVerdict.NotNow(
        "There is not enough free memory right now to think with. Try again in a moment.",
    )
}
