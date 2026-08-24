package com.neopal.pet.data

import com.neopal.pet.domain.ChatTurn
import com.neopal.pet.domain.MindReply
import com.neopal.pet.domain.PetBrief

/**
 * What is handed to a model running on the handset, and what is made of what comes back.
 *
 * This is a separate file from [OnDeviceMindClient] for one reason, and it is the reason
 * `RemoteMindClient.kt` has no Android imports either: **this file compiles here.** There is no
 * Android SDK in this project's local harness and `com.google.ai.edge.litertlm` cannot be resolved
 * from it, so anything in the same file as the engine is checked by nothing until CI. Everything
 * that could be got wrong without a device — how much of a conversation goes into the prompt, what
 * happens to the player's own words on the way in, what happens to the model's on the way out —
 * lives on this side of that line and has a test suite.
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
}
