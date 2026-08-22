package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * Everything a language model is told about the creature, and nothing else.
 *
 * Built here, in pure Kotlin, rather than assembled wherever the network call happens, for three
 * reasons that are all really the same reason — this is the part that has to be *right*:
 *
 *  - It is testable. A prompt built inside a networking layer can only be checked by making a
 *    request, which means it is checked never.
 *  - It is auditable. Everything the model knows about a player's pet is in one place, in one
 *    type, and a person can read it and see exactly what leaves the device.
 *  - It is bounded. Free models have small context windows and the save grows without limit; the
 *    brief has to be a summary by construction rather than by whoever remembers to trim it.
 *
 * It carries no names, no identifiers and nothing about the player. It is a description of an
 * imaginary animal.
 */
@Serializable
data class PetBrief(
    val name: String,
    val species: String,
    val stage: String,
    val branch: String,
    val personality: String,
    val ageDays: Int,
    val generation: Int,
    /** Rounded to whole percentages: a model has no use for the fractional part. */
    val satiety: Int,
    val happiness: Int,
    val energy: Int,
    val hygiene: Int,
    val health: Int,
    val bond: Int,
    val intellect: Int,
    val isSick: Boolean,
    val isSleeping: Boolean,
    /** What it can actually do for itself, by display name. */
    val skills: List<String>,
    /** Who is in the room, by name and standing. */
    val company: List<String>,
    /** The last few diary lines, oldest first. The creature's own memory of itself. */
    val recentDiary: List<String>,
    /** What it last decided and why, so it can be asked about it. */
    val lastDecision: String?,
    /** Inherited lessons, phrased as the creature would hold them. */
    val inheritedLessons: List<String>,
) {
    companion object {
        /** How much history the brief carries. Small on purpose — free models have small windows. */
        const val DIARY_LINES = 6
        const val MAX_COMPANY = 4
        const val MAX_LESSONS = 4

        fun of(state: PetState, config: GameConfig): PetBrief = PetBrief(
            name = state.name,
            species = state.species.displayName,
            stage = state.stage.displayName,
            branch = state.branch.displayName,
            personality = state.personality.displayName,
            ageDays = state.ageInPetDays(config),
            generation = state.generation,
            satiety = state.stats.satiety.toInt(),
            happiness = state.stats.happiness.toInt(),
            energy = state.stats.energy.toInt(),
            hygiene = state.stats.hygiene.toInt(),
            health = state.stats.health.toInt(),
            bond = state.stats.bond.toInt(),
            intellect = state.intellect.toInt(),
            isSick = state.isSick,
            isSleeping = state.isSleeping,
            skills = state.skills.map { it.displayName },
            company = state.presentPals.take(MAX_COMPANY).map { "${it.name} (${it.relation.displayName})" },
            recentDiary = state.chronicle.takeLast(DIARY_LINES).map { it.text },
            lastDecision = state.decisions.lastOrNull()?.let { "${it.kind.displayName}: ${it.reason}" },
            inheritedLessons = state.lessons.take(MAX_LESSONS).map { it.text },
        )
    }
}

/** One line of conversation. `fromPet` false means the player said it. */
@Serializable
data class ChatTurn(
    val fromPet: Boolean,
    val text: String,
    val atSeconds: Long,
)

/** What the model said back, plus anything it wants the game to do about it. */
data class MindReply(
    val text: String,
    /** Optional mood nudge, −1..1. Applied within tight bounds; a model cannot move stats freely. */
    val warmth: Float = 0f,
)

/**
 * The model's pick among options the game had already decided were legal.
 *
 * It returns an index into the list it was given, never a free-form action. This is the single
 * most important rule in the whole feature: a language model that could name its own action would
 * eventually name one the rules do not allow — eating from an empty pantry, courting a stranger,
 * waking at three in the morning — and every guard the simulation has would have to be re-checked
 * against prose. Choosing from a validated list means the worst a bad reply can do is pick a
 * legal option badly, which is indistinguishable from a creature with poor judgement, and that is
 * a thing this game is allowed to have.
 */
data class MindChoice(
    val index: Int,
    /** Why, in the creature's own voice, for the decision log. */
    val reason: String,
)

/**
 * A brain that lives somewhere else.
 *
 * Everything here is suspending and every result is nullable, because the honest answer to "what
 * does the creature think" over a network is frequently "we do not know yet" or "we could not
 * reach it". Null is not an error path to be handled reluctantly — it is the normal case whenever
 * the player is offline, out of quota, or simply has not set this up, and the game has to be
 * exactly as playable then. The local [Brain] is not a fallback bolted on afterwards; it is the
 * default, and this is the enhancement.
 */
interface MindProvider {

    /** True when this provider is configured well enough to be worth calling. */
    val isReady: Boolean

    /** The creature answers something the player said to it. */
    suspend fun speak(brief: PetBrief, history: List<ChatTurn>, message: String): MindReply?

    /**
     * The creature chooses among [options], which the local brain has already scored and found
     * legal. Returning null means the local brain's own pick stands.
     */
    suspend fun choose(brief: PetBrief, options: List<Consideration>): MindChoice?

    /**
     * Distils a finished life into lessons the next generation inherits.
     * Returning an empty list means the line simply starts fresh, which is not a failure.
     */
    suspend fun distil(brief: PetBrief, record: RunRecord, decisions: List<Decision>): List<Lesson>

    /**
     * Sets the creature an errand: a short sequence with a goal behind it.
     *
     * [tools] is what looking around told it — the read-only answers from [ToolId], gathered
     * before the call so the whole plan is one request rather than a conversation. That is a
     * deliberate limit: a real tool loop would be several round trips per plan, and on a free
     * tier a creature that thought that hard would think three times a day.
     *
     * [options] are the activities currently legal, so a plan is at least *plausible* when it is
     * made. It is still re-checked step by step as it is followed, because plausible when made
     * and legal three steps later are different things.
     */
    suspend fun plan(
        brief: PetBrief,
        tools: Map<ToolId, String>,
        options: List<Consideration>,
    ): Plan?
}

/** A provider that is never ready and never answers. The default, and the offline case. */
object NoMind : MindProvider {
    override val isReady: Boolean get() = false
    override suspend fun speak(brief: PetBrief, history: List<ChatTurn>, message: String): MindReply? = null
    override suspend fun choose(brief: PetBrief, options: List<Consideration>): MindChoice? = null
    override suspend fun distil(brief: PetBrief, record: RunRecord, decisions: List<Decision>): List<Lesson> =
        emptyList()
    override suspend fun plan(
        brief: PetBrief,
        tools: Map<ToolId, String>,
        options: List<Consideration>,
    ): Plan? = null
}

/**
 * The four jobs a remote mind is asked to do.
 *
 * They exist as a type because they are not alike, and pretending they were is what made a single
 * model and a single token budget look reasonable. Choosing an action runs many times an hour and
 * needs one number and a sentence back; conversation runs when the player feels like it and is
 * the only place personality can show; planning happens rarely and is the one that actually needs
 * to reason; distilling happens once in a creature's life. Charging all four the same rate means
 * either the frequent job is far more expensive than it needs to be, or the rare and difficult
 * ones are starved to pay for it.
 */
@Serializable
enum class MindRole(val displayName: String) {
    /** Answering the player. */
    CONVERSE("Talking"),

    /** Picking from a list the game has already validated. Runs most often, needs least. */
    DECIDE("Deciding"),

    /** Setting itself an errand after looking around. */
    PLAN("Planning"),

    /** Turning a finished life into lessons for the next one. Once per generation. */
    DISTIL("Remembering"),
}

/**
 * Where the remote brain comes from.
 *
 * Two routes, deliberately. A hosted proxy means the feature works the moment the app is opened,
 * with no key to paste and nothing to sign up for — which is the only way most people will ever
 * try it. A player's own key means they are not subject to somebody else's rate limit or bill,
 * and that the feature keeps working if the proxy goes away.
 *
 * A key is never shipped inside the app. Anyone holding the APK can read anything compiled into
 * it, so the only two honest places for a secret are a server the player does not control or the
 * player's own device.
 */
@Serializable
data class MindConfig(
    val enabled: Boolean = false,
    /**
     * Hosted endpoint that holds its own key. Blank disables the proxy route.
     * Expected to speak the OpenAI chat-completions shape, as OpenRouter does.
     */
    val proxyUrl: String = "",
    /** The player's own endpoint. Defaults to OpenRouter, which is where the free models are. */
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** The player's own key. Stored on the device only, and never included in a brief. */
    val apiKey: String = "",
    /** A free model by default, so the feature costs nothing to try. */
    val model: String = "meta-llama/llama-3.3-70b-instruct:free",
    /**
     * An optional second, smaller model for [MindRole.DECIDE]. Blank means use [model].
     *
     * Deciding is the job that runs many times an hour, and all it has to return is an index and
     * a sentence — the hard part, working out which options are legal at all, has already been
     * done locally before the request goes out. Spending a large model on that is spending the
     * day's quota on the easiest question the creature ever asks, and the creature then has
     * nothing left for the conversation or the plan, which are the two places a big model is
     * actually worth having.
     */
    val quickModel: String = "",
    /** Let the model pick among the local brain's legal options. */
    val decidesActions: Boolean = true,
    /** Let the player talk to the creature. */
    val conversation: Boolean = true,
    /** Distil a finished life into lessons for the next one. */
    val lineageLessons: Boolean = true,
    /** Let it look around and set itself a short errand rather than one decision at a time. */
    val makesPlans: Boolean = true,
    /** Hard ceiling on reply length, in tokens. Keeps a free model inside its quota. */
    val maxTokens: Int = 220,
    /** Give up after this long and let the local brain answer. */
    val timeoutMillis: Long = 12_000L,
) {
    /** True when either route could actually be called. */
    val hasRoute: Boolean get() = proxyUrl.isNotBlank() || apiKey.isNotBlank()

    /** True when the feature should be attempted at all. */
    val usable: Boolean get() = enabled && hasRoute

    /** Which model does this job. */
    fun modelFor(role: MindRole): String = when (role) {
        MindRole.DECIDE -> quickModel.ifBlank { model }
        MindRole.CONVERSE, MindRole.PLAN, MindRole.DISTIL -> model
    }

    /**
     * How long a reply this job is allowed, in tokens.
     *
     * Deciding gets a fraction of the budget because a long answer to "which of these, and why"
     * is a worse answer — and because the ceiling is what stops a model that has started
     * rambling from spending the rest of the day's quota on one request.
     */
    fun maxTokensFor(role: MindRole): Int = when (role) {
        MindRole.DECIDE -> (maxTokens * DECIDE_TOKEN_SHARE).toInt().coerceAtLeast(MIN_TOKENS)
        MindRole.CONVERSE, MindRole.PLAN, MindRole.DISTIL -> maxTokens
    }

    /** True when a job is being sent somewhere other than where the rest go. */
    val splitsModels: Boolean get() = quickModel.isNotBlank() && quickModel != model

    /**
     * Deliberately hand-written, so the key cannot be printed by accident.
     *
     * A data class generates a `toString` containing every field, which means one stray log line,
     * one crash reporter, or one `"config is $config"` in a debug build is enough to put a
     * player's key somewhere it can be read. There is no logging in this app today; this is what
     * makes that permanently safe rather than currently true.
     */
    override fun toString(): String =
        "MindConfig(enabled=$enabled, route=$routeLabel, model=$model, apiKey=${if (apiKey.isBlank()) "unset" else "set"})"

    /** Which route a call would take, for the settings screen to say so plainly. */
    val routeLabel: String
        get() = when {
            !enabled -> "Off"
            apiKey.isNotBlank() -> "Your own key"
            proxyUrl.isNotBlank() -> "Shared service"
            else -> "No route set"
        }

    companion object {
        /** Fraction of the reply budget a decision gets. An index and one sentence needs little. */
        const val DECIDE_TOKEN_SHARE = 0.45f

        /** Floor, so a small configured budget cannot be divided down to nothing usable. */
        const val MIN_TOKENS = 48
    }
}
