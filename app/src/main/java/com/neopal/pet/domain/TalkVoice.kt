package com.neopal.pet.domain

/**
 * The decisions the talking screen has to make about the voice layer, kept out of the screen.
 *
 * Everything here is arithmetic over four things the screen already knows — the conversation, the
 * player's [VoiceConfig], whether this handset can recognise speech at all, and whether the
 * microphone has been granted — and none of it touches Android. That split is the whole point:
 * [com.neopal.pet.data.CreatureEars] and [com.neopal.pet.data.CreatureSpeaker] cannot be compiled
 * without an SDK, let alone run, so any rule that lives inside them is a rule nobody can check.
 * The rules that decide what the player *sees* live here, where a test can hold them.
 */

/**
 * What the player has already answered about the microphone.
 *
 * [UNASKED] and [REFUSED] are deliberately different states even though neither can listen. A
 * player who has never been asked should be offered the button; a player who said no should not
 * be asked again by the screen they said it on, because a prompt that reappears is how an app
 * teaches someone to distrust its permission dialogs.
 */
enum class MicPermission { UNASKED, GRANTED, REFUSED }

/** What the microphone control actually is at this moment. */
enum class MicOffer {
    /** No control at all. Something on screen says why, unless the reason is the player's own setting. */
    HIDDEN,

    /** A live button whose first press is the system permission prompt. */
    ASK,

    /** A live button that opens the microphone. */
    LISTEN,

    /** The microphone is open; pressing again gives up on it. */
    STOP,
}

/**
 * The microphone control, and the sentence beside it.
 *
 * [note] is the honest half. A device with no recogniser, a permission that was declined and a
 * setting that was never switched on are three different absences, and each of them gets said out
 * loud rather than being expressed as a button that does nothing. Where the absence is the
 * player's own choice — the setting is off — there is no note: they know, and being told is
 * nagging.
 */
data class MicSurface(
    val offer: MicOffer,
    /** Two or three words for the button face. Empty when there is no button. */
    val label: String,
    /** The whole sentence for a screen reader, which never gets to see the layout. */
    val readOut: String,
    /** A plain sentence about why speaking is not on offer, or null when nothing needs saying. */
    val note: String?,
) {
    /** True when the control should be drawn and can be pressed. */
    val actionable: Boolean get() = offer != MicOffer.HIDDEN

    companion object {
        /**
         * What to offer, in the order the reasons actually rule each other out.
         *
         * [ready] comes first because a screen that cannot take a typed message cannot take a
         * spoken one either, and the panel that replaced the composer is already explaining why —
         * a second sentence about microphones underneath it is noise about the wrong problem.
         *
         * The recogniser check comes before the permission check for the reason named in
         * [com.neopal.pet.data.CreatureEars.availableOn]: asking for a microphone that leads
         * nowhere is a prompt that should never have been shown.
         */
        fun of(
            listens: Boolean,
            recogniserPresent: Boolean,
            permission: MicPermission,
            listening: Boolean,
            ready: Boolean,
        ): MicSurface = when {
            !ready -> MicSurface(MicOffer.HIDDEN, "", "", null)

            !listens -> MicSurface(
                offer = MicOffer.HIDDEN,
                label = "",
                readOut = "",
                // No note. The player switched listening off themselves, or never switched it on,
                // and a keyboard that keeps mentioning a microphone is a keyboard with an agenda.
                note = null,
            )

            !recogniserPresent -> MicSurface(
                offer = MicOffer.HIDDEN,
                label = "",
                readOut = "",
                note = "This device has no speech recognition, so typing is the way in. " +
                    "Nothing else is missing.",
            )

            permission == MicPermission.REFUSED -> MicSurface(
                offer = MicOffer.HIDDEN,
                label = "",
                readOut = "",
                note = "The microphone stays off. Typing works just as well — nothing here needs it.",
            )

            listening -> MicSurface(
                offer = MicOffer.STOP,
                label = "LISTENING",
                readOut = "Listening. Press to stop.",
                note = "Say it. It stops listening when you do.",
            )

            permission == MicPermission.UNASKED -> MicSurface(
                offer = MicOffer.ASK,
                label = "SPEAK",
                readOut = "Speak instead of typing. This asks for the microphone first.",
                note = "The microphone is asked for the first time you press this, and no sooner.",
            )

            else -> MicSurface(
                offer = MicOffer.LISTEN,
                label = "SPEAK",
                readOut = "Speak instead of typing.",
                note = null,
            )
        }
    }
}

/**
 * The voice half of the talking screen: which line is worth reading out, and in whose voice.
 *
 * Nothing here starts an engine or opens a microphone. It answers "is there a new thing to say,
 * and what does this creature sound like saying it" — and the Android layer does as it is told.
 */
object TalkVoice {

    /**
     * What identifies one line of the conversation for as long as it is on screen.
     *
     * Not the index. The log is capped at [Simulation.MAX_CHAT_TURNS] and drops from the front, so
     * the index of a line the creature has already spoken slides down by one every time a new turn
     * is added — and a key that slides is a key that lets the same reply be read out twice. The
     * creature's own age at the moment it spoke, plus the text, does not move.
     */
    fun keyOf(turn: ChatTurn): String =
        "chat:${turn.atSeconds}:${turn.text.length}:${turn.text.hashCode()}"

    /**
     * The key of the newest thing the creature said, or null when the last word was not its own.
     *
     * A screen calls this once when it opens and treats the answer as already spoken. That is the
     * difference between a creature that answers you and one that reads its last reply out again
     * every time you walk back into the room.
     */
    fun newestReplyKey(chat: List<ChatTurn>): String? =
        chat.lastOrNull()?.takeIf { it.fromPet }?.let { keyOf(it) }

    /**
     * The line to read out now, or null — which is the ordinary answer.
     *
     * Null covers all of: the voice is off, chat is not one of the channels the player wants read,
     * the last word was the player's own, there is nothing there at all, and the newest reply is
     * the one already spoken. Only the creature's *latest* line is ever a candidate: a reply that
     * arrived while the phone was in a pocket has been read on screen by the time anyone looks,
     * and a backlog read aloud in order is the behaviour that gets a voice feature switched off.
     */
    fun replyToSpeak(chat: List<ChatTurn>, config: VoiceConfig, alreadySaid: String?): Utterance? {
        if (!config.reads(VoiceChannel.CHAT)) return null
        val newest = chat.lastOrNull() ?: return null
        if (!newest.fromPet) return null
        val key = keyOf(newest)
        if (key == alreadySaid) return null
        return Utterance.of(newest.text, VoiceChannel.CHAT, key)
    }

    /**
     * How this creature sounds right now.
     *
     * [CreatureVoice.of] gives the constitutional voice — the one that comes from its age, its
     * temperament and the genes it was bred from. Illness is layered on top rather than folded in,
     * so that recovering restores the voice exactly, with nothing to remember and nothing to
     * drift: a creature that has been ill twice sounds no different from one that never was.
     */
    fun voiceOf(pet: PetState): CreatureVoice {
        val own = CreatureVoice.of(pet)
        return if (pet.isSick) own.unwell() else own
    }
}

/**
 * What a recognised sentence does to what the player was already typing.
 *
 * Recognition lands in the composer and is *not* sent. Every speech recogniser mishears, and the
 * difference between a feature that mishears and a feature that is broken is entirely whether the
 * player got to see the sentence before it went to their pet. It is also what makes a refused
 * microphone a complete answer: the keyboard was never bypassed, so there is nothing to fall back
 * to.
 */
object VoiceComposer {

    /**
     * [draft] with [words] added, never longer than [limit].
     *
     * Blank recognition changes nothing — [com.neopal.pet.data.Heard] already has its own outcome
     * for "nothing was said", and this is not the place to invent a second one. Appending rather
     * than replacing is what makes dictating half a sentence, thinking, and dictating the rest
     * work at all; the space between them is added here because a recogniser never returns one.
     */
    fun blend(draft: String, words: String, limit: Int): String {
        val spoken = words.trim()
        if (spoken.isEmpty()) return draft
        val cap = limit.coerceAtLeast(0)
        val head = draft.trimEnd()
        val joined = if (head.isEmpty()) spoken else "$head $spoken"
        return if (joined.length <= cap) joined else joined.take(cap).trimEnd()
    }
}
