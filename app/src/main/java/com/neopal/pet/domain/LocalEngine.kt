package com.neopal.pet.domain

/**
 * How an engine on the handset is set up, and which weights it is pointed at.
 *
 * ## Why this file exists at all
 *
 * The three questions below used to live in a second decision layer of their own, alongside
 * copies of the fit arithmetic and the routing table. That layer was deleted in 4ff22d8 because
 * the copies disagreed with [LocalModelFit] and [MindRouter] and the copies were the wrong ones.
 * What did not survive the deletion, and is not expressible in either survivor, is this: what
 * numbers an engine is *configured* with, and which of the files on the disk it opens. Neither is
 * a claim about what a phone can hold — [LocalModelFit] answers that and this file defers to it —
 * and neither is a claim about who answers a question, which is [MindRouter]'s. They are the
 * arguments the Android wrapper needs before it can call a constructor, and they are here rather
 * than in that wrapper for the ordinary reason: a number chosen inside a class that cannot be
 * compiled without an SDK is a number nothing can check.
 *
 * Nothing here imports Android and nothing here loads anything.
 */

/** Everything the engine wrapper needs to be told, worked out before any of it touches JNI. */
data class LocalEnginePlan(
    /** Which weights. Already checked against [LocalModelFit] by whoever built this. */
    val variant: LocalModelVariant,
    /** How many CPU threads the backend may use. */
    val threads: Int,
    /** The context window to ask the engine for, in tokens. */
    val contextTokens: Int,
    /** How long one answer may take before the written brain covers, in milliseconds. */
    val timeoutMillis: Long,
)

/** The numbers and the file choice. Pure, and the only place either is decided. */
object LocalEngine {

    /**
     * Fewest threads worth starting with.
     *
     * One thread is not a saving worth having: generation is the only thing the phone is doing
     * that the player is waiting on, and a single-threaded decode on a phone-class core is slow
     * enough that the creature stops feeling like it answered at all.
     */
    const val MIN_THREADS = 2

    /**
     * Most threads this will ever ask for, however many the phone has.
     *
     * A ceiling rather than a fraction, because the thing being protected against is not the
     * arithmetic but the phone: saturating every core is what turns a two-line answer into a warm
     * handset and a dropped frame rate everywhere else in the app. Big-little scheduling means
     * the cores past this point are usually the small ones anyway, and handing a decode more
     * little cores buys latency in the sense that it costs it.
     */
    const val MAX_THREADS = 4

    /**
     * The context window asked for, in tokens.
     *
     * One number for all three variants, deliberately. It is sized by what this game puts in a
     * prompt — a character sheet, [MindWire.MAX_HISTORY_TURNS] flattened turns and one question,
     * all of it already capped by [OnDeviceWire] — and that is the same size whichever model
     * reads it. Asking a larger model for a larger window would allocate key-value cache for a
     * conversation this app never sends, and that cache is resident memory on a device chosen for
     * having barely enough.
     */
    const val CONTEXT_TOKENS = 1536

    /**
     * How long one answer may take.
     *
     * Shorter than the remote budget in [MindConfig.timeoutMillis] and it should be: the whole
     * argument for a model on the phone is that it answers now, so a local answer that takes as
     * long as the network has lost the only race it was entered in. When it expires the written
     * brain covers and nobody is told, which is the ordinary case rather than a failure.
     */
    const val TIMEOUT_MILLIS = 6_000L

    /**
     * How many threads to give the backend on a phone reporting [cores] processors.
     *
     * Half, clamped. Half rather than all because the app is still drawing a creature, running a
     * simulation and possibly speaking while this decodes; clamped at both ends because
     * `availableProcessors` is a number Android is allowed to lie about — a container or a
     * governor can report one core on an eight-core phone, and it can report every core on a
     * phone that will only ever schedule four.
     *
     * A non-positive reading means the question could not be answered, and that comes back as
     * [MIN_THREADS] rather than as a refusal: a phone that will not say how many cores it has is
     * still a phone that can generate.
     */
    fun threadsFor(cores: Int): Int =
        if (cores <= 0) MIN_THREADS else (cores / 2).coerceIn(MIN_THREADS, MAX_THREADS)

    /** The whole configuration for one engine. */
    fun plan(variant: LocalModelVariant, cores: Int): LocalEnginePlan = LocalEnginePlan(
        variant = variant,
        threads = threadsFor(cores),
        contextTokens = CONTEXT_TOKENS,
        timeoutMillis = TIMEOUT_MILLIS,
    )

    /**
     * The catalogue entry a variant is downloaded from, or null when there is not one.
     *
     * Matched on [LocalModelVariant.fileName], which is the same string
     * [FetchableModel.fileName] carries, because both were read from the same allowlist. Matching
     * on the file name rather than on a hand-written table is what makes the two catalogues able
     * to be *checked* against each other rather than merely intended to agree — `LocalEngineTest`
     * fails the day one of them is edited alone.
     */
    fun fetchableFor(variant: LocalModelVariant): FetchableModel? =
        FetchableModels.known.firstOrNull { it.fileName == variant.fileName }

    /**
     * Which weights to load, given what the player has asked for and what is actually on the disk.
     *
     * [onDisk] is the fact and it wins over everything. [LocalMindConfig.installed] is a record
     * written by the downloader, and a record can be wrong in both directions: a player who
     * cleared the app's storage has a save that still claims a model, and a file restored
     * underneath a save that never knew about it is a model this would otherwise refuse to open.
     * The engine checks the file before opening it either way; this decides which file that is.
     *
     * The order is: what the save says was installed, then what the player asked for, then the
     * largest thing present — and every one of them is filtered through [LocalModelFit.permits]
     * first. That last filter is the one that matters, and it is not paranoia: a file can outlive
     * the phone it was downloaded for. A save restored onto a smaller handset, or an Android
     * update that lowers what this app is granted, both end with weights on the disk that this
     * device must not open, and opening them is not a slow creature — it is the process being
     * killed, which to somebody watching a tamagotchi is a pet that died.
     *
     * Returns null when the feature is switched off, when nothing is on the disk, or when
     * nothing on the disk is permitted. All three are ordinary states, not errors.
     */
    fun chooseInstalled(
        config: LocalMindConfig,
        fit: LocalModelFit,
        onDisk: Set<LocalModelVariant>,
    ): LocalModelVariant? {
        if (!config.enabled) return null
        val usable = LocalModelVariant.largestFirst.filter { it in onDisk && fit.permits(it) }
        if (usable.isEmpty()) return null
        config.installed?.takeIf { it in usable }?.let { return it }
        config.preferred?.takeIf { it in usable }?.let { return it }
        return usable.first()
    }
}
