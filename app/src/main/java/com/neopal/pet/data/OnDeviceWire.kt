package com.neopal.pet.data

import com.neopal.pet.domain.ChatTurn
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.Decision
import com.neopal.pet.domain.Lesson
import com.neopal.pet.domain.MindChoice
import com.neopal.pet.domain.MindReply
import com.neopal.pet.domain.PetBrief
import com.neopal.pet.domain.Plan
import com.neopal.pet.domain.RunRecord
import com.neopal.pet.domain.ToolId

/**
 * What is handed to a model running on the handset, and what is made of what comes back.
 *
 * This is a separate file from the engine for one reason, and it is the reason
 * `RemoteMindClient.kt` has no Android imports either: **this file compiles here.** There is no
 * Android SDK in this project's local harness and `com.google.ai.edge.litertlm` cannot be resolved
 * from it, so anything in the same file as the engine is checked by nothing until CI. That
 * separation earned itself twice over: the engine was reverted when the library turned out to
 * need a newer Kotlin than this project compiles with, and this file did not move — then the
 * engine came back on a newer Kotlin, and this file did not have to move for that either.
 * Everything that could be got wrong without a device — how much of a conversation goes into the
 * prompt, what happens to the player's own words on the way in, what happens to the model's on the
 * way out — lives on this side of that line and has a test suite.
 *
 * ---- what is *not* re-implemented here ----------------------------------------------------
 *
 * The character sheet, the brief, and every parser. [MindWire.speakSystemPrompt] already contains
 * the prompt that stops a model answering like a service desk, and [MindWire.interpretReply] is
 * already strict about the reply in exactly the way a small model needs — it insists on the JSON
 * object rather than falling back to using the raw text as speech, which matters far more here
 * than it does remotely, because a 1 B model ignores a format instruction much more often than a
 * 70 B one does. A second copy of either would drift, and the copy in the newer file would be the
 * one nobody remembered to fix.
 *
 * ---- what is different, and why -----------------------------------------------------------
 *
 * The remote route sends the conversation as *actual* chat messages with roles, because that is
 * the shape of the endpoint. This one sends a single block of text through
 * `createConversation()` and `sendMessage(text)`.
 *
 * The multi-message form does exist at the pinned version — `ConversationConfig(systemInstruction,
 * initialMessages)` was read at the tag rather than assumed — so this is a choice and the trade
 * should be stated rather than dressed up as a limit. Against it: `Contents`, `Message.user`,
 * `Message.model` and two more constructor parameters are four more pieces of a surface this
 * project cannot compile against locally, for a prompt whose quality nobody here can measure. For
 * it, and it is the better argument: real turns make forging the scaffolding *impossible* rather
 * than *defended against*. If someone with a handset is improving this, that is the change to
 * make, and this comment is the reason it was not made blind.
 *
 * Until then [MindWire.flatten] is load-bearing rather than tidy. The turns below are written as
 * labelled lines inside one prompt, so a newline in a turn would let the player — or the model's
 * own previous answer — forge a line of this scaffolding that is indistinguishable from a real
 * one. Collapsing whitespace removes the only tool that attack has, and it is the same defence
 * the remote route already relies on for `Decision.reason` and `Lesson.text`.
 */
internal object OnDeviceWire {

    /**
     * The JSON shape the reply must take, repeated as the last thing the model reads.
     *
     * [MindWire.speakSystemPrompt] already ends with it — but remotely that text is a *system*
     * message and the player's words arrive in a separate one after it. Here everything is one
     * block, so the format instruction would otherwise sit several hundred tokens from the end,
     * and a small model follows the last instruction it saw far more reliably than the best one.
     *
     * Kept identical to the remote wording on purpose, and `OnDeviceWireTest` fails if the two
     * ever drift apart. A duplicated string that nothing checks is a string that is already wrong.
     */
    const val REPLY_SHAPE =
        """{"say": "<what you say out loud>", "warmth": <number from -1 to 1, how kindly you feel toward them>}"""

    /**
     * The whole prompt for one turn of conversation, or null when there is nothing to ask.
     *
     * Ordered so that the last thing read is the question and the shape of the answer. Everything
     * before it — who the creature is, how it feels, what was just said — is context it needs but
     * will not be judged on.
     */
    fun conversation(brief: PetBrief, history: List<ChatTurn>, message: String): String? {
        val asked = MindWire.flatten(message).take(MindWire.MAX_INBOUND_CHARS)
        if (asked.isEmpty()) return null

        return buildString {
            appendLine(MindWire.speakSystemPrompt(brief).trimEnd())
            appendLine()
            val recent = transcript(brief, history)
            if (recent.isNotEmpty()) {
                appendLine("What was said just now, oldest first:")
                for (line in recent) appendLine(line)
                appendLine()
            }
            appendLine("They say to you:")
            appendLine(asked)
            appendLine()
            appendLine("Reply with one JSON object and nothing else, in this exact shape:")
            append(REPLY_SHAPE)
        }
    }

    /**
     * The last few turns, flattened to one line each and labelled.
     *
     * The same [MindWire.MAX_HISTORY_TURNS] the remote route uses. The reason is different, and
     * sharper: remotely the limit protects a free tier's context window, here it protects a
     * context window that is a fixed number of tokens allocated in the handset's memory, and a
     * prompt that overruns it does not cost money — it pushes the actual question out of the
     * window, so the creature answers something it can no longer see.
     *
     * Blank turns are dropped rather than rendered as empty labelled lines, which a model reads as
     * a speaker who said nothing and imitates.
     */
    fun transcript(brief: PetBrief, history: List<ChatTurn>): List<String> {
        val out = ArrayList<String>(MindWire.MAX_HISTORY_TURNS)
        for (turn in history.takeLast(MindWire.MAX_HISTORY_TURNS)) {
            val cap = if (turn.fromPet) MindWire.MAX_REPLY_CHARS else MindWire.MAX_INBOUND_CHARS
            // Flattened before it is labelled, not after. A turn carrying a newline could
            // otherwise write its own "They say to you:" line and be believed.
            val text = MindWire.flatten(turn.text).take(cap)
            if (text.isEmpty()) continue
            out += if (turn.fromPet) "${brief.name}: $text" else "They: $text"
        }
        return out
    }

    /**
     * What the creature actually said, or null.
     *
     * The cap is [MindWire.MAX_RESPONSE_CHARS], the same one the network read is bounded by, and
     * it is not redundant here just because nothing crossed a wire. A model decoding on the
     * handset can loop, and `MindWire.extractJson` is a brace matcher that restarts its scan at
     * every failed opener — quadratic in the number of openers. The remote client measured 8.7
     * seconds on a reply of 131,072 open braces. A local model producing that costs no quota and
     * no network, which makes it *more* likely to happen here, not less.
     *
     * [MindWire.extractContent] is the one piece of the remote reading path that is deliberately
     * not reused: it digs a message out of a chat-completions envelope, and there is no envelope
     * here — the engine hands back the assistant's text directly. Calling it would return null for
     * every well-formed local reply.
     */
    fun answer(raw: String?): MindReply? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        return MindWire.interpretReply(text.take(MindWire.MAX_RESPONSE_CHARS))
    }

    // ---- the other three jobs the router can send here ----------------------------------------
    //
    // `docs/CEREBRO-LOCAL.md` §4 sends a decision, an errand and a distilled life to the remote
    // model when there is one and **to the phone when there is not** — and `MindRouter` implements
    // exactly that. So the prompts exist here rather than the engine refusing three of the four
    // things it can be asked. Which of them actually runs is the router's business and not this
    // file's; this file's business is that when one is asked for, the question is well formed.
    //
    // Every one of them is the same construction as [conversation]: the remote route's own system
    // and user prompts, unmodified, with the shape of the answer repeated at the very end. Not one
    // instruction is rewritten for a smaller model. If a 1 B model needs different words, that is
    // a thing to find out from a handset and change with a measurement in hand — inventing a
    // second set of prompts here, unmeasured, would mean two sets to keep in step and no way to
    // tell which was better.

    /**
     * The shape of the answer, taken from the end of the prompt that already states it.
     *
     * Every `…SystemPrompt` in [MindWire] ends with the JSON object it wants back, because
     * remotely that is a system message and the player's words arrive after it. Here everything is
     * one block, so the instruction would otherwise sit hundreds of tokens from the end — and a
     * small model follows the last instruction it read far more reliably than the best one.
     *
     * Read off the prompt rather than copied into four constants. A copy is a thing that can
     * disagree, and the copy nobody remembers to update is always the one in the newer file. This
     * cannot disagree: it is the same characters.
     */
    private fun shapeOf(systemPrompt: String): String =
        systemPrompt.trimEnd().substringAfterLast('\n')

    /** System prompt, then the question, then the shape again. The order everything here uses. */
    private fun oneBlock(systemPrompt: String, userPrompt: String): String = buildString {
        appendLine(systemPrompt.trimEnd())
        appendLine()
        appendLine(userPrompt.trimEnd())
        appendLine()
        appendLine("Answer with one JSON object and nothing else, in this exact shape:")
        append(shapeOf(systemPrompt))
    }

    /**
     * The prompt for picking one of the options the game has already ruled legal, or null when
     * there is nothing worth asking about.
     *
     * The same two guards the remote route uses, and for the same reason rather than out of
     * symmetry: an empty list has no answer, and a list where nothing is available has only one,
     * so generating either is heat spent to be told what was already known.
     */
    fun decision(brief: PetBrief, options: List<Consideration>): String? {
        if (options.isEmpty() || options.none { it.available }) return null
        return oneBlock(MindWire.chooseSystemPrompt(brief), MindWire.chooseUserPrompt(brief, options))
    }

    /** The prompt for setting the creature an errand, or null when it could not begin anything. */
    fun errand(brief: PetBrief, tools: Map<ToolId, String>, options: List<Consideration>): String? {
        if (options.none { it.available }) return null
        return oneBlock(MindWire.planSystemPrompt(brief), MindWire.planUserPrompt(brief, tools, options))
    }

    /**
     * The prompt for turning a finished life into what the next one inherits.
     *
     * No guard, because there is no such thing as a life with nothing in it: by the time this is
     * asked, `Lineage.distilLocally` has already written the child's inheritance and anything this
     * produces can only reword it or leave it alone.
     */
    fun distillation(brief: PetBrief, record: RunRecord, decisions: List<Decision>): String =
        oneBlock(MindWire.distilSystemPrompt(), MindWire.distilUserPrompt(brief, record, decisions))

    /**
     * What the creature chose, or null.
     *
     * The index is validated against the very list that was sent, exactly as it is remotely. That
     * is what makes a small model safe to let decide at all: it is choosing a temperament from a
     * menu the simulation already ruled legal, so the worst a confused reply can do is choose a
     * legal thing badly — a creature with poor judgement, which this game is allowed to have.
     */
    fun choice(raw: String?, options: List<Consideration>): MindChoice? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        return MindWire.interpretChoice(text.take(MindWire.MAX_RESPONSE_CHARS), options)
    }

    /**
     * The errand the creature set itself, or null.
     *
     * **Unstamped**, the same as the remote route's: `Errands.sanitise` takes `madeAtSeconds` from
     * whatever it is handed and there is no clock on this side, so the caller must re-stamp it
     * against the creature's own age before storing it. A plan left at zero is not merely
     * inaccurate — `Plan.isStale` measures from that field, so every plan would arrive expired.
     */
    fun errandOf(raw: String?): Plan? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        return MindWire.interpretPlan(text.take(MindWire.MAX_RESPONSE_CHARS))
    }

    /** What the life taught, or nothing — which is not a failure, only a line that starts fresh. */
    fun lessons(raw: String?, generation: Int): List<Lesson> {
        val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
        return MindWire.interpretLessons(text.take(MindWire.MAX_RESPONSE_CHARS), generation)
    }
}
