package com.neopal.pet.data

import com.neopal.pet.domain.ActivityKind
import com.neopal.pet.domain.ChatTurn
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.Decision
import com.neopal.pet.domain.Errands
import com.neopal.pet.domain.Lesson
import com.neopal.pet.domain.LessonKind
import com.neopal.pet.domain.Lineage
import com.neopal.pet.domain.MindChoice
import com.neopal.pet.domain.MindConfig
import com.neopal.pet.domain.MindRole
import com.neopal.pet.domain.MindProvider
import com.neopal.pet.domain.MindReply
import com.neopal.pet.domain.PetBrief
import com.neopal.pet.domain.Plan
import com.neopal.pet.domain.PlanStep
import com.neopal.pet.domain.RunRecord
import com.neopal.pet.domain.ToolId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The remote brain, over one plain HTTP request.
 *
 * Written against [java.net.HttpURLConnection] rather than a client library, because the only
 * thing this layer does is post a few kilobytes of JSON and read a few back. A dependency would
 * buy connection pooling and interceptors that nothing here wants, and it would be carried by
 * every player including the overwhelming majority who never switch this feature on.
 *
 * The config is read through [configProvider] on every call rather than captured once, because
 * the player edits it in settings and a client holding a stale key would keep failing silently
 * long after they had fixed it.
 *
 * Nothing in here throws. Every path that could — DNS, timeout, a 401, a 429, prose where JSON
 * was asked for, an empty `choices` array, a refusal — resolves to null, and null means the local
 * brain answers instead. See the KDoc on [MindProvider]: that is the ordinary case, not a failure
 * to be reported. The player is never shown a network error for a feature they may not know is on.
 */
class RemoteMindClient(private val configProvider: () -> MindConfig) : MindProvider {

    /**
     * Asked before every call, and it has to agree with [MindWire.routeOf] or it is a liar.
     *
     * `usable` only asks whether a key *or* a proxy is set; the key route additionally needs a
     * base URL, and that field is a free text box a player can empty in one gesture. When they
     * did, this said yes, `routeOf` said no, no socket was ever opened, and the settings screen
     * went on reporting "Your own key" while the creature was permanently mute.
     */
    override val isReady: Boolean get() = MindWire.routeOf(configProvider()) != null

    /**
     * The creature answers, in its own voice.
     *
     * Gated on [MindConfig.conversation] as well as readiness: a player who wants the creature to
     * make its own decisions but not to talk has said something specific, and this is where it is
     * honoured rather than at the call site, so no caller can forget.
     */
    override suspend fun speak(brief: PetBrief, history: List<ChatTurn>, message: String): MindReply? {
        val config = configProvider()
        if (!config.usable || !config.conversation) return null
        if (message.isBlank()) return null

        val messages = ArrayList<Message>(history.size + 2)
        messages += Message(ROLE_SYSTEM, MindWire.speakSystemPrompt(brief))
        // The recent turns go over as an actual conversation rather than as a transcript pasted
        // into the prompt. Models hold character markedly better when the shape of the request
        // matches the shape of the thing being asked for, and it costs nothing to do it properly.
        for (turn in history.takeLast(MindWire.MAX_HISTORY_TURNS)) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            messages += Message(if (turn.fromPet) ROLE_ASSISTANT else ROLE_USER, text)
        }
        messages += Message(ROLE_USER, message.trim().take(MindWire.MAX_INBOUND_CHARS))

        val content = request(config, MindRole.CONVERSE, messages) ?: return null
        return MindWire.interpretReply(content)
    }

    /**
     * The creature picks among options the local brain has already ruled legal.
     *
     * The index is validated against the very list that was sent, and a blocked option is refused
     * outright, so the worst a hostile or confused reply can do is choose a legal option badly.
     * That is a creature with poor judgement, which the game is allowed to have — see [MindChoice].
     */
    override suspend fun choose(brief: PetBrief, options: List<Consideration>): MindChoice? {
        val config = configProvider()
        if (!config.usable || !config.decidesActions) return null
        if (options.isEmpty()) return null
        // Nothing to decide, and no reason to spend a request or a token on saying so.
        if (options.none { it.available }) return null

        val messages = listOf(
            Message(ROLE_SYSTEM, MindWire.chooseSystemPrompt(brief)),
            Message(ROLE_USER, MindWire.chooseUserPrompt(brief, options)),
        )
        val content = request(config, MindRole.DECIDE, messages) ?: return null
        return MindWire.interpretChoice(content, options)
    }

    /**
     * Turns a finished life into lessons the next generation inherits.
     *
     * Returns an empty list on every failure, which is indistinguishable to the caller from a
     * model that had nothing to say. [Lineage.distilLocally] has already produced a usable set of
     * lessons by the time this is called; this can only make the wording better or leave it alone.
     */
    override suspend fun distil(
        brief: PetBrief,
        record: RunRecord,
        decisions: List<Decision>,
    ): List<Lesson> {
        val config = configProvider()
        if (!config.usable || !config.lineageLessons) return emptyList()

        val messages = listOf(
            Message(ROLE_SYSTEM, MindWire.distilSystemPrompt()),
            Message(ROLE_USER, MindWire.distilUserPrompt(brief, record, decisions)),
        )
        val content = request(config, MindRole.DISTIL, messages) ?: return emptyList()
        return MindWire.interpretLessons(content, brief.generation)
    }

    /**
     * Sets the creature an errand, from one look around and one request.
     *
     * The tool answers arrive already gathered rather than being fetched in a conversation with
     * the model, which is the whole reason this is affordable: a genuine tool loop is several
     * round trips per plan, and on a free tier a creature that thought that hard would get to
     * think about three times a day. One look, one plan, and the steps are re-checked as they
     * come up.
     *
     * **The returned plan is unstamped.** [Errands.sanitise] sets `madeAtSeconds` to whatever it
     * is handed, and this has no clock and no `ageSeconds` — [PetBrief] carries the creature's age
     * in days, which is far too coarse to time a plan by. So it is stamped zero here and the
     * caller must re-stamp it against `state.ageSeconds` before storing it. A plan left at zero is
     * not merely inaccurate: [Plan.isStale] measures from that field, so any creature older than
     * [Errands.lifetimeFor] would find every remote plan already expired on arrival.
     */
    override suspend fun plan(
        brief: PetBrief,
        tools: Map<ToolId, String>,
        options: List<Consideration>,
    ): Plan? {
        val config = configProvider()
        if (!config.usable || !config.makesPlans) return null
        // Nothing it could legally begin means nothing worth spending a request to intend.
        if (options.none { it.available }) return null

        val messages = listOf(
            Message(ROLE_SYSTEM, MindWire.planSystemPrompt(brief)),
            Message(ROLE_USER, MindWire.planUserPrompt(brief, tools, options)),
        )
        val content = request(config, MindRole.PLAN, messages) ?: return null
        return MindWire.interpretPlan(content)
    }

    // ------------------------------------------------------------------ the wire

    /**
     * Posts [messages] and returns the assistant's raw text, or null for any reason at all.
     *
     * [withTimeoutOrNull] wraps the socket timeouts rather than replacing them. The socket
     * timeouts bound each individual read; only the outer one bounds the *call*, which is what
     * [MindConfig.timeoutMillis] promises the player — a server that dribbles a byte every second
     * would satisfy a read timeout forever.
     */
    private suspend fun request(config: MindConfig, role: MindRole, messages: List<Message>): String? {
        val route = MindWire.routeOf(config) ?: return null
        val body = MindWire.requestBody(config, messages, role)
        val budget = config.timeoutMillis.coerceIn(MindWire.MIN_TIMEOUT_MILLIS, MindWire.MAX_TIMEOUT_MILLIS)
        // The parse is inside the timeout and off the main thread, and both matter. `extractJson`
        // is a brace matcher that restarts its scan at every failed opener, so it is quadratic in
        // the number of openers: a reply that is 131,072 open braces measured at 8.7 seconds, and
        // it used to run on the caller's dispatcher, which for all four callers is the main
        // thread. That is an ANR from one hostile reply. Under the timeout it is bounded; off the
        // main thread it cannot freeze the app even while it is bounded.
        return withTimeoutOrNull(budget) {
            withContext(Dispatchers.IO) {
                post(route, body, config.timeoutMillis)?.let { MindWire.extractContent(it) }
            }
        }
    }

    /**
     * One request, and a cancellation that can actually reach it.
     *
     * `suspendCancellableCoroutine` rather than a completion handler, because that is the only
     * hook that runs *while* the job is being cancelled. The previous version registered
     * `invokeOnCompletion`, which fires when a job completes — and a job blocked in a socket read
     * cannot complete, so the disconnect that would have ended the read was waiting on the read.
     * A deadlock by construction, and the comment sitting over it claimed the opposite.
     *
     * The failure needs a server that dribbles rather than one that goes silent, which is why the
     * existing timeout test never caught it: a silent server trips the JDK's own read timeout and
     * never exercises the coroutine machinery at all. Dribbling is not exotic — OpenRouter emits
     * keep-alive comment lines during a slow request. Measured symptom: a request still running
     * twenty-five seconds into a 1.5-second budget, `thinking` latched on for the rest of the
     * process, and the remote brain silently off with the local one covering for it.
     *
     * Called from `Dispatchers.IO`, so blocking inside the block is deliberate.
     */
    private suspend fun post(route: MindWire.Route, body: String, timeoutMillis: Long): String? =
        suspendCancellableCoroutine { continuation ->
            val timeout = timeoutMillis.coerceIn(MindWire.MIN_TIMEOUT_MILLIS, MindWire.MAX_TIMEOUT_MILLIS).toInt()
            var connection: HttpURLConnection? = null
            try {
                val open = URL(route.endpoint).openConnection()
                val http = open as? HttpURLConnection
                if (http == null) {
                    continuation.resume(null) { _, _, _ -> }
                    return@suspendCancellableCoroutine
                }
                connection = http
                // Closing the connection is the one thing that ends a blocked read. Registered
                // before the first byte moves, so there is no window where a cancel is dropped.
                continuation.invokeOnCancellation { runCatching { http.disconnect() } }

                http.requestMethod = "POST"
                http.connectTimeout = timeout
                http.readTimeout = timeout
                http.doOutput = true
                // Redirects are refused rather than followed, because following one would re-send
                // the player's key to whatever host the first one nominated.
                http.instanceFollowRedirects = false
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                http.setRequestProperty("Accept", "application/json")
                // Sent only as a header, never as part of any prompt or body, and never logged.
                route.bearer?.let { http.setRequestProperty("Authorization", "Bearer $it") }
                // OpenRouter attributes free-tier traffic by these and rejects some requests
                // without them. They name the app and nothing about the player.
                http.setRequestProperty("HTTP-Referer", MindWire.APP_URL)
                http.setRequestProperty("X-Title", MindWire.APP_NAME)

                http.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = http.responseCode
                val answer = if (code !in 200..299) {
                    // The error body is drained and dropped rather than surfaced. It is written
                    // by a service we do not control, it can echo the request, and anything
                    // returned here could end up on the player's screen.
                    runCatching { http.errorStream?.use { it.readBytes() } }
                    null
                } else {
                    http.inputStream.use { readCapped(it) }
                }
                continuation.resume(answer) { _, _, _ -> }
            } catch (ignored: Throwable) {
                // Deliberately total. Every distinction this could draw — unknown host, refused
                // connection, bad certificate, malformed URL the player typed into settings, or
                // the stream being closed out from under us by a cancellation — leads to the same
                // place: the local brain answers and the game carries on. Resuming after a cancel
                // is a no-op, so the cancelled case needs no special handling here.
                runCatching { continuation.resume(null) { _, _, _ -> } }
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

    /**
     * Reads at most [MindWire.MAX_RESPONSE_CHARS] and abandons the rest.
     *
     * The response is attacker-shaped by definition — it is a stream of unknown length from a host
     * the player pasted into a settings box. Reading it whole would let one bad reply take the
     * process down on a phone, which is a far worse outcome than a truncated answer that then
     * fails to parse and falls back to the local brain.
     */
    private fun readCapped(stream: InputStream): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(READ_CHUNK)
        val out = StringBuilder()
        while (out.length < MindWire.MAX_RESPONSE_CHARS) {
            val want = minOf(buffer.size, MindWire.MAX_RESPONSE_CHARS - out.length)
            val read = reader.read(buffer, 0, want)
            if (read <= 0) break
            out.appendRange(buffer, 0, read)
        }
        return out.toString()
    }

    /** One turn of the request, in the shape the chat-completions endpoint expects. */
    internal data class Message(val role: String, val content: String)

    private companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val READ_CHUNK = 8 * 1024
    }
}

/**
 * Everything about talking to a remote brain that does not need a socket.
 *
 * Split out and left pure on purpose. A prompt assembled inside the networking call can only be
 * checked by making a request, which in practice means it is never checked at all — and the prompt
 * is the part that decides whether the creature stays in character, whether the player's key can
 * leak into a body, and whether a reply that says `"index": 47` gets to act on the game. All of
 * that is testable here, offline, and all of it is.
 */
internal object MindWire {

    /** Long enough for the creature to say something, short enough for a speech bubble. */
    const val MAX_REPLY_CHARS = 400

    /** A decision-log row is one line on a phone. */
    const val MAX_REASON_CHARS = 140

    /** What the player is allowed to say in one go, before it starts costing quota. */
    const val MAX_INBOUND_CHARS = 500

    /** How much of the conversation goes back over the wire. Free models have small windows. */
    const val MAX_HISTORY_TURNS = 8

    /**
     * Lessons accepted from one finished life. Matched to [Lineage.distilLocally], which also
     * takes three: a life that handed its child six instructions would not be teaching it, and
     * the family screen has room to show what was learned rather than a wall of it.
     */
    const val MAX_LESSONS_PER_RUN = 3

    /**
     * How many proposed steps are read before the rest are ignored.
     *
     * [Errands.MAX_STEPS] is the real limit and it is applied by [Errands.sanitise], which owns
     * what a plan is allowed to be. This is only a floor under the arithmetic: idling is stripped
     * out before the limit bites, so a reply of two hundred steps still has to be walked, and a
     * generous multiple of the real cap leaves room for that without letting one bad reply
     * allocate a list as long as it likes.
     */
    const val MAX_STEPS_CONSIDERED = 12

    /** Ceiling on the response we will read at all. Roughly 128 KiB of text. */
    const val MAX_RESPONSE_CHARS = 128 * 1024

    /** Floors and ceilings on the player's timeout, so a typo cannot hang or instantly fail. */
    const val MIN_TIMEOUT_MILLIS = 1_000L
    const val MAX_TIMEOUT_MILLIS = 60_000L

    /** Identifies the app to the endpoint. Carries nothing about the player. */
    const val APP_NAME = "NeoPal"
    /**
     * Sent as the referer so a shared endpoint can attribute traffic. It has to be a real address:
     * an invented one is a claim about a project that does not exist, made to a third party, on
     * every single request.
     */
    const val APP_URL = "https://github.com/Sabrealfred/Indicators"

    /** The path every endpoint here speaks, appended when the player gave only a base. */
    private const val CHAT_PATH = "/chat/completions"

    /**
     * Lenient on purpose. This parses two very different things — a well-formed envelope from the
     * service, and whatever the model put inside it — and the second one is routinely sloppy.
     * Being strict about a trailing comma would throw away an otherwise perfectly good answer.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Where a call goes and what, if anything, authorises it. */
    internal data class Route(val endpoint: String, val bearer: String?)

    /**
     * Picks the route, key first.
     *
     * A player who has pasted their own key has opted out of somebody else's rate limit and bill,
     * and silently preferring the shared proxy would take that back without telling them.
     */
    fun routeOf(config: MindConfig): Route? = when {
        !config.usable -> null
        config.apiKey.isNotBlank() && config.baseUrl.isNotBlank() ->
            // The key route, and the only one that carries a secret, so it is the only one that
            // insists on transport. A player who pastes an http endpoint here would otherwise
            // send their bearer token in plain text to every hop in between.
            chatEndpoint(config.baseUrl).takeIf { carriesSecretsSafely(it) }
                ?.let { Route(it, config.apiKey.trim()) }
        // The proxy route sends no secret at all, so http is allowed. That is not laxity: a
        // self-hosted gateway on a home network has no certificate and refusing it would rule
        // out the whole use case the proxyUrl field exists for.
        config.proxyUrl.isNotBlank() -> Route(chatEndpoint(config.proxyUrl), null)
        else -> null
    }

    /**
     * Whether a bearer token may be sent to this endpoint.
     *
     * HTTPS, or loopback. Loopback is allowed because a request that never leaves the device
     * cannot be intercepted on the way, and refusing it would rule out a gateway running on the
     * phone itself.
     */
    fun carriesSecretsSafely(endpoint: String): Boolean {
        val lower = endpoint.trim().lowercase()
        if (lower.startsWith("https://")) return true
        if (!lower.startsWith("http://")) return false
        val host = lower.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }

    /** Accepts either a base URL or a full completions URL, because players paste both. */
    fun chatEndpoint(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.endsWith(CHAT_PATH)) trimmed else trimmed + CHAT_PATH
    }

    /**
     * The request body.
     *
     * No `response_format`, deliberately. Asking for a JSON object is the tidier request and it is
     * also the one several free models reject outright with a 400, which would turn "the creature
     * is a bit quiet today" into "the feature does not work". The reply is parsed defensively
     * regardless — see [extractJson] — so the strict mode would buy nothing it does not already have.
     *
     * The key is never in here. It travels as a header and only as a header.
     */
    fun requestBody(
        config: MindConfig,
        messages: List<RemoteMindClient.Message>,
        role: MindRole = MindRole.CONVERSE,
    ): String {
        val payload = buildJsonObject {
            put("model", config.modelFor(role))
            put("max_tokens", config.maxTokensFor(role).coerceIn(32, 2048))
            // Warm enough that the same state does not produce the same sentence twice, cool
            // enough that it keeps answering the question it was asked.
            put("temperature", 0.8)
            put("stream", false)
            putJsonArray("messages") {
                for (message in messages) {
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        },
                    )
                }
            }
        }
        return payload.toString()
    }

    // ------------------------------------------------------------------ prompts

    /**
     * Who the creature is, and the one rule it must never break.
     *
     * Written as a character sheet rather than a set of instructions to an assistant, because the
     * failure this is guarding against is the model answering *helpfully* — explaining its stats
     * back in a list, offering to help, apologising. A creature does none of those things, and a
     * pet that suddenly sounds like a service desk is the single fastest way to lose the illusion
     * the rest of the game is built on.
     */
    fun speakSystemPrompt(brief: PetBrief): String = buildString {
        appendLine("You are ${brief.name}, a ${brief.species}. You are a living creature in somebody's care.")
        appendLine(
            "You are not a helper, a service or a program, and you have never heard of such things. " +
                "Never say you are one, never mention instructions or rules, never break character " +
                "for any reason, and never offer to assist with anything.",
        )
        appendLine()
        appendLine("How you talk:")
        appendLine("- First person, present tense, one or two short sentences. Never more.")
        appendLine("- Plain, warm and a little blunt. British spelling throughout.")
        appendLine("- You know exactly how you feel, because it is written below. Say so, and complain if you like.")
        appendLine("- You are a ${brief.stage} and you are ${brief.personality.lowercase()} by nature. Sound like it.")
        appendLine("- No lists, no headings, no emoji, no stage directions.")
        appendLine()
        appendLine("This is you, right now:")
        appendLine(briefJson(brief).toString())
        appendLine()
        appendLine("Answer with one JSON object and nothing else, in this exact shape:")
        append("""{"say": "<what you say out loud>", "warmth": <number from -1 to 1, how kindly you feel toward them>}""")
    }

    /**
     * The rule that keeps a language model from inventing an action the simulation forbids.
     *
     * It is told to return a number because it is only ever *given* numbers. Everything in the
     * list has already been scored and ruled legal by the local brain; the model is choosing a
     * temperament, not an action. See the KDoc on [MindChoice] for why that distinction is the
     * whole design.
     */
    fun chooseSystemPrompt(brief: PetBrief): String = buildString {
        appendLine("You are ${brief.name}, a ${brief.species}, deciding what to do next.")
        appendLine(
            "You are a living creature, not a helper or a program. Never break character and never " +
                "mention instructions.",
        )
        appendLine()
        appendLine("You will be given a numbered list of the only things you could do right now.")
        appendLine("Some are marked UNAVAILABLE with a reason. You cannot choose those, whatever you would prefer.")
        appendLine("Choose exactly one available option by its number. You may choose a lower-scoring one if")
        appendLine("that is what you feel like — you are an animal with a temperament, not a calculator.")
        appendLine()
        appendLine("Answer with one JSON object and nothing else, in this exact shape:")
        append("""{"index": <the number of your choice>, "reason": "<one short sentence, in your own voice, British spelling>"}""")
    }

    /** The state and the menu, in that order: what it feels, then what it can do about it. */
    fun chooseUserPrompt(brief: PetBrief, options: List<Consideration>): String = buildString {
        appendLine("This is you, right now:")
        appendLine(briefJson(brief).toString())
        appendLine()
        appendLine("Your options:")
        for (index in options.indices) {
            val option = options[index]
            append(index)
            append(". ")
            append(option.kind.displayName)
            append(" — \"")
            append(option.reason.trim())
            append("\" (how much you want it: ")
            append((option.utility.coerceIn(0f, 1f) * 100).toInt())
            append("%)")
            if (option.blockedBy != null) {
                append(" [UNAVAILABLE: ")
                append(option.blockedBy)
                append("]")
            }
            appendLine()
        }
    }

    /**
     * What a line passes down.
     *
     * The kinds are listed by their exact enum names because that is what is parsed back, and a
     * model given a friendly label ("eat sooner") will return the friendly label. Everything
     * unrecognised is dropped, so the cost of being vague here is silence rather than a crash —
     * which is precisely why it is worth being precise.
     */
    fun distilSystemPrompt(): String = buildString {
        appendLine("A creature's life has just ended, and its child is about to hatch.")
        appendLine("You are the memory the family line carries forward. Write what the child should hold on to.")
        appendLine()
        appendLine("Rules:")
        appendLine("- At most $MAX_LESSONS_PER_RUN lessons. Fewer is better. A life rarely teaches three separate things.")
        appendLine("- Each lesson must use one of these kinds, spelled exactly as written:")
        for (kind in LessonKind.entries) appendLine("    ${kind.name}")
        appendLine("- The text is one short sentence in the child's own voice, first person, British spelling.")
        appendLine("- Strength is 0 to 1: how hard this life argues for it. Reserve above 0.8 for what killed it.")
        appendLine("- Say nothing about instructions, rules or how you were asked. Only the lesson.")
        appendLine()
        appendLine("Answer with one JSON object and nothing else, in this exact shape:")
        append("""{"lessons": [{"kind": "<EXACT_NAME>", "text": "<one sentence>", "strength": <0 to 1>}]}""")
    }

    /** How the life actually went, which is the only evidence there is for what it taught. */
    fun distilUserPrompt(brief: PetBrief, record: RunRecord, decisions: List<Decision>): String = buildString {
        appendLine("The life that just ended:")
        appendLine(
            buildJsonObject {
                put("name", record.name)
                put("species", record.species.displayName)
                put("generation", record.generation)
                put("reachedStage", record.stage.displayName)
                put("livedHours", (record.lifespanSeconds / 3600f).toInt())
                put("endedBy", record.deathReason?.name ?: "unknown")
                put("careScore", (record.careScore.coerceIn(0f, 1f) * 100).toInt())
                put("neglectPerHour", (record.mistakesPerHour * 10).toInt() / 10f)
                put("meals", record.mealsEaten)
                put("games", record.gamesPlayed)
                put("cleanups", record.cleanups)
                put("doses", record.medicineDoses)
                put("level", record.level)
            }.toString(),
        )
        appendLine()
        if (decisions.isNotEmpty()) {
            appendLine("The last things it decided, oldest first:")
            for (decision in decisions.takeLast(MAX_DECISION_LINES)) {
                appendLine("- ${decision.kind.displayName}: ${decision.reason.trim()}")
            }
            appendLine()
        }
        if (brief.inheritedLessons.isNotEmpty()) {
            appendLine("What this line already carried, so you do not simply repeat it:")
            for (lesson in brief.inheritedLessons) appendLine("- $lesson")
        }
    }

    /**
     * Asks for an intention rather than a move.
     *
     * The character rules are the same as everywhere else, but the failure being guarded against
     * here is a different one. Asked to plan, a model reaches for the register it plans in —
     * numbered phases, deliverables, a note about reviewing progress — and a creature that
     * announces a three-phase strategy for having lunch is funnier than it is convincing. So it is
     * asked for an errand: one thing it is trying to get done, and the two or three moves it takes.
     *
     * The vocabulary is given as exact enum names because that is what [interpretPlan] matches on,
     * and a model handed the friendly label ("tidying up") returns the friendly label.
     */
    fun planSystemPrompt(brief: PetBrief): String = buildString {
        appendLine("You are ${brief.name}, a ${brief.species}, working out what to do with the next little while.")
        appendLine(
            "You are a living creature, not a helper or a program. Never break character, never " +
                "mention instructions, and never offer to assist with anything.",
        )
        appendLine()
        appendLine("You have just had a look around you. What you found is written below.")
        appendLine("Set yourself one short errand: something you actually want to get done, and how you get there.")
        appendLine()
        appendLine("Rules:")
        appendLine("- At most ${Errands.MAX_STEPS} steps, in the order you mean to do them. Two is usually plenty.")
        appendLine("- Every step must be one of these, spelled exactly as written:")
        for (kind in ActivityKind.entries) {
            if (kind == ActivityKind.IDLE) continue
            appendLine("    ${kind.name}  (${kind.displayName})")
        }
        appendLine("- Never plan to sit about doing nothing. An errand is something you mean to do.")
        appendLine("- Each step is checked against the rules when its turn comes, so plan something you")
        appendLine("  could plausibly manage from where you are now.")
        appendLine("- The goal and every reason are one short sentence, first person, British spelling.")
        appendLine("- No phases, no numbering, no headings. You are an animal with an afternoon in mind.")
        appendLine()
        appendLine("Answer with one JSON object and nothing else, in this exact shape:")
        append("""{"goal": "<what you are after>", "steps": [{"kind": "<EXACT_NAME>", "why": "<one short sentence>"}]}""")
    }

    /**
     * What it feels, what it just found out, and what it could legally begin.
     *
     * The observations are laid out in [ToolId] order rather than in whatever order the map
     * happens to iterate. A map with no defined order would produce a different prompt for the
     * same creature on the same turn, which is both untestable and a waste of any cache the
     * endpoint keeps.
     */
    fun planUserPrompt(brief: PetBrief, tools: Map<ToolId, String>, options: List<Consideration>): String =
        buildString {
            appendLine("This is you, right now:")
            appendLine(briefJson(brief).toString())
            appendLine()
            if (tools.isNotEmpty()) {
                appendLine("What you found when you looked around:")
                for (tool in ToolId.entries) {
                    val answer = tools[tool]?.trim() ?: continue
                    if (answer.isEmpty()) continue
                    appendLine("- ${tool.displayName}: $answer")
                }
                appendLine()
            }
            appendLine("What you could begin right now:")
            for (option in options) {
                append("- ")
                append(option.kind.name)
                append(" (")
                append(option.kind.displayName)
                append(")")
                if (option.blockedBy != null) {
                    append(" — NOT NOW: ")
                    appendLine(option.blockedBy)
                } else {
                    append(" — how much you want it: ")
                    append((option.utility.coerceIn(0f, 1f) * 100).toInt())
                    appendLine("%")
                }
            }
        }

    /**
     * Exactly what leaves the device about the creature, built field by field.
     *
     * Hand-assembled rather than serialised from [PetBrief] wholesale, and that is the point: a
     * field added to the brief for a screen to use does not silently start being uploaded. It has
     * to be added here, by somebody who has read this comment. Empty collections are dropped too,
     * which keeps a hatchling's brief short enough to leave room for the actual question.
     */
    fun briefJson(brief: PetBrief): JsonObject = buildJsonObject {
        put("name", brief.name)
        put("species", brief.species)
        put("stage", brief.stage)
        put("branch", brief.branch)
        put("nature", brief.personality)
        put("ageInDays", brief.ageDays)
        put("generation", brief.generation)
        putJsonObject("feelings") {
            put("fullness", brief.satiety)
            put("happiness", brief.happiness)
            put("energy", brief.energy)
            put("cleanliness", brief.hygiene)
            put("health", brief.health)
            put("bondWithKeeper", brief.bond)
            put("cleverness", brief.intellect)
        }
        put("unwell", brief.isSick)
        put("asleep", brief.isSleeping)
        if (brief.skills.isNotEmpty()) putJsonArray("thingsICanDoMyself") { for (s in brief.skills) add(s) }
        if (brief.company.isNotEmpty()) putJsonArray("whoIsHere") { for (c in brief.company) add(c) }
        if (brief.recentDiary.isNotEmpty()) putJsonArray("whatIRemember") { for (d in brief.recentDiary) add(d) }
        brief.lastDecision?.let { put("whatIJustDid", it) }
        if (brief.inheritedLessons.isNotEmpty()) {
            putJsonArray("whatMyLineTaughtMe") { for (l in brief.inheritedLessons) add(l) }
        }
    }

    // ------------------------------------------------------------------ reading replies

    /**
     * Digs the assistant's text out of the chat-completions envelope.
     *
     * An empty `choices` array is a real and routine answer — a content filter fired, the quota
     * ran out mid-stream — and it means the same as everything else that goes wrong here.
     */
    fun extractContent(raw: String): String? {
        val root = parseOrNull(raw) as? JsonObject ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject
        val content = message?.text("content")
            // Some endpoints answer the older completions shape even at this path.
            ?: first.text("text")
        return content?.takeIf { it.isNotBlank() }
    }

    /**
     * What the creature said, capped and clamped.
     *
     * Strict about the JSON rather than falling back to using the whole raw text as speech. A
     * model that ignored the format has usually also ignored the character — the raw text is
     * typically an apology or a paragraph of analysis — and showing that in a speech bubble is a
     * worse outcome than the creature simply not answering and the local brain speaking instead.
     */
    fun interpretReply(raw: String): MindReply? {
        val obj = extractJson(raw) as? JsonObject ?: return null
        val said = (obj.text("say") ?: obj.text("text") ?: obj.text("reply") ?: obj.text("message"))
            ?.trim()
            ?.take(MAX_REPLY_CHARS)
        if (said.isNullOrEmpty()) return null
        val warmth = (obj["warmth"] as? JsonPrimitive)?.floatOrNull ?: 0f
        val safeWarmth = if (warmth.isNaN()) 0f else warmth.coerceIn(-1f, 1f)
        return MindReply(text = said, warmth = safeWarmth)
    }

    /**
     * The chosen option, or null if the model chose something it was not offered.
     *
     * Both guards matter and they fail for different reasons. An out-of-range index is a model
     * that lost count; a blocked index is a model that read the list and wanted the thing anyway.
     * The second is the more likely of the two and by far the more damaging — it is exactly how a
     * remote brain would come to eat from an empty pantry — so neither is treated as recoverable.
     */
    fun interpretChoice(raw: String, options: List<Consideration>): MindChoice? {
        if (options.isEmpty()) return null
        val obj = extractJson(raw) as? JsonObject ?: return null
        val index = obj.int("index") ?: obj.int("choice") ?: obj.int("option") ?: return null
        if (index !in options.indices) return null
        val option = options[index]
        if (option.blockedBy != null) return null
        // A pick with no explanation still stands; the option's own wording is a true account of
        // why it was on the list, and a decision log with a blank line in it is worse than one
        // that quietly falls back to the local phrasing.
        val reason = obj.text("reason")?.let { flatten(it) }?.take(MAX_REASON_CHARS)?.takeIf { it.isNotEmpty() }
            ?: option.reason
        return MindChoice(index = index, reason = reason)
    }

    /**
     * Lessons the model proposed, after every one of them has been made safe.
     *
     * Unknown kinds are dropped rather than mapped to something near enough. A lesson's kind is
     * what it *does* to the child — "study harder" is a different animal from "eat sooner" — so
     * guessing at a misspelling would silently breed a creature the player did not ask for, and
     * the honest alternative is to lose that one lesson and keep the rest.
     */
    fun interpretLessons(raw: String, generation: Int): List<Lesson> {
        val root = extractJson(raw) ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["lessons"] ?: root["items"]) as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        val out = LinkedHashMap<LessonKind, Lesson>()
        for (element in array) {
            val entry = element as? JsonObject ?: continue
            val name = entry.text("kind")?.trim() ?: continue
            val kind = LessonKind.entries.firstOrNull { it.name == name } ?: continue
            val text = (entry.text("text") ?: entry.text("lesson"))?.let { flatten(it) } ?: continue
            val strength = (entry["strength"] as? JsonPrimitive)?.floatOrNull ?: DEFAULT_STRENGTH
            val lesson = Lineage.sanitise(
                Lesson(kind = kind, text = text, strength = strength, fromGeneration = generation),
            ) ?: continue
            // Same kind twice is a model repeating itself, not a line that learned it twice over.
            val prior = out[kind]
            if (prior == null || lesson.strength > prior.strength) out[kind] = lesson
        }
        return out.values.sortedByDescending { it.strength }.take(MAX_LESSONS_PER_RUN)
    }

    /**
     * The errand the creature set itself, or null if it did not manage to set one.
     *
     * Two things happen here and only two. Unrecognised activity names are dropped rather than
     * guessed at — an invented step is a step the simulation has no rule for, and the nearest
     * legal-looking match would be the client quietly deciding what the creature meant. Everything
     * that survives then goes through [Errands.sanitise], which is where a plan is actually
     * decided to be reasonable: idling stripped, goal and reasons bounded, length capped, progress
     * reset. None of that is re-implemented here, so a plan from a model and a plan read back out
     * of an old save are held to exactly the same standard.
     *
     * The plan comes back stamped zero. See [RemoteMindClient.plan]: this has no clock, and the
     * caller must re-stamp before storing.
     */
    fun interpretPlan(raw: String): Plan? {
        val obj = extractJson(raw) as? JsonObject ?: return null
        val goal = (obj.text("goal") ?: obj.text("aim") ?: obj.text("errand"))?.trim() ?: return null
        val proposed = (obj["steps"] ?: obj["plan"] ?: obj["actions"]) as? JsonArray ?: return null

        if (goal.isBlank()) return null
        val steps = ArrayList<PlanStep>(Errands.MAX_STEPS)
        for (element in proposed.take(MAX_STEPS_CONSIDERED)) {
            val entry = element as? JsonObject ?: continue
            val name = (entry.text("kind") ?: entry.text("activity") ?: entry.text("action"))?.trim() ?: continue
            val kind = ActivityKind.entries.firstOrNull { it.name == name } ?: continue
            // A step with no stated reason still stands, and it borrows the goal's words. The
            // reason is shown to the player as the creature's own account of itself, and "because
            // of what I am trying to do" is true, in voice, and better than a blank row.
            val why = (entry.text("why") ?: entry.text("reason"))?.let { flatten(it) }?.takeIf { it.isNotEmpty() } ?: goal
            steps += PlanStep(kind = kind, why = why)
        }
        if (steps.isEmpty()) return null

        // Deliberately not stamped or sanitised here. Errands.sanitise now takes the creature
        // rather than a number of seconds, precisely so that the stamp can only be applied where
        // the clock is; this returns a proposal, and the caller decides whether it is one the
        // creature can hold. What comes back has been checked for *shape* only.
        return Plan(goal = goal, steps = steps, madeAtSeconds = UNSTAMPED)
    }

    // ------------------------------------------------------------------ defensive parsing

    /**
     * Finds the first parseable JSON value inside [raw], whatever is wrapped around it.
     *
     * Models put JSON inside fenced code blocks, introduce it ("Sure, here is my choice:"), and
     * add a paragraph afterwards explaining it. Every one of those is a perfectly good answer with
     * packaging on it, and refusing them all would mean the feature only works with the handful of
     * models that happen to be obedient today.
     *
     * The scan is a brace-matcher that respects strings and escapes rather than a regular
     * expression, because a reply's own text routinely contains braces and quotes and a regex
     * would stop at the first one.
     */
    fun extractJson(raw: String): JsonElement? {
        if (raw.isBlank()) return null
        val text = stripFences(raw)
        var from = 0
        while (from < text.length) {
            val start = nextOpener(text, from) ?: return null
            val end = balancedEnd(text, start)
            if (end > start) {
                parseOrNull(text.substring(start, end + 1))?.let { return it }
            }
            from = start + 1
        }
        return null
    }

    /**
     * Flattens a string the model wrote before it is stored.
     *
     * Everything from a model is untrusted, and these strings do not merely reach the screen —
     * `Decision.reason` and `Lesson.text` are written back into a *later* prompt as plain lines
     * of a bulleted list. A reason containing a newline can therefore forge a line of the
     * harness's own scaffolding, indistinguishable from the real ones, and carry an instruction
     * across turns. Collapsing whitespace removes the only tool it has to do that, and it also
     * keeps a reason to the one line the decision log is laid out for.
     */
    fun flatten(raw: String): String = raw.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    /** Drops fence markers so the scanner is not distracted by the language tag. */
    private fun stripFences(raw: String): String = raw.replace(FENCE, " ")

    private fun nextOpener(text: String, from: Int): Int? {
        for (i in from until text.length) if (text[i] == '{' || text[i] == '[') return i
        return null
    }

    /** Index of the bracket closing the one at [start], or -1 when it never closes. */
    private fun balancedEnd(text: String, start: Int): Int {
        val opener = text[start]
        val closer = if (opener == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == opener -> depth++
                c == closer -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun parseOrNull(raw: String): JsonElement? =
        try {
            json.parseToJsonElement(raw)
        } catch (ignored: Throwable) {
            null
        }

    /** A string field, treating JSON null and non-primitives as absent. */
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** An integer field, accepting `2` and `"2"` alike; models produce both. */
    private fun JsonObject.int(name: String): Int? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }

    /**
     * The `madeAtSeconds` a freshly parsed plan carries out of here.
     *
     * Zero rather than a guess. This layer has no clock — [PetBrief] gives the creature's age in
     * whole days, which cannot time a plan whose lifetime is measured in minutes — and a plausible
     * invented number would be worse than an obviously wrong one, because it would look stamped.
     */
    private const val UNSTAMPED = 0L

    /** Middling by default: a lesson with no stated weight should lean, not shove. */
    private const val DEFAULT_STRENGTH = 0.5f

    /** How much of the decision log the distillation sees. Enough for a shape, not a transcript. */
    private const val MAX_DECISION_LINES = 12

    private val FENCE = Regex("```[A-Za-z0-9_-]*")
}

