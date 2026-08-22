package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * What the creature is allowed to say out loud, and where each kind of line comes from.
 *
 * The channels exist so that "read things aloud" can be three separate decisions instead of one.
 * They are not equally welcome: a reply the player asked for is wanted, a diary line is pleasant,
 * and a decision fires many times an hour. Bundling them behind a single switch would mean the
 * only way to keep the last one quiet is to lose the first — which is how a voice feature ends up
 * switched off permanently after ten minutes.
 *
 * [priority] settles what happens when two lines want the mouth at once. Higher wins, and equal
 * wins too: the newest line of a kind replaces the one still being spoken, because a creature
 * three sentences behind the screen is worse than one that only says the latest thing.
 */
enum class VoiceChannel(val displayName: String, val priority: Int) {
    /** An answer to something the player said. The one the player is waiting for. */
    CHAT("Replies", 3),

    /** What it just decided to do, and why. */
    DECISION("Decisions", 2),

    /** A line from its own diary. */
    DIARY("Diary", 1);

    /**
     * True when this line may take the mouth from [current], which is null when nothing is being
     * spoken. Equal priority interrupts on purpose; see the note on [priority].
     */
    fun interrupts(current: VoiceChannel?): Boolean = current == null || priority >= current.priority
}

/**
 * One thing worth saying aloud, already trimmed to something an engine can read.
 *
 * [key] is what stops a line being spoken twice. The same diary entry can arrive at the speaker
 * more than once — a recomposition, a screen returned to, a state flow that re-emits — and a
 * creature that repeats itself for reasons the player cannot see reads as broken rather than
 * chatty. Callers pass whatever identifies the line at its source: a chronicle id, a decision's
 * timestamp, the index of a chat turn.
 */
data class Utterance(
    val text: String,
    val channel: VoiceChannel,
    val key: String,
) {
    companion object {
        /**
         * An utterance, or null when there is nothing worth saying.
         *
         * Null is the ordinary answer for an empty diary, a blank reply or whitespace, and the
         * caller is expected to do nothing about it. Returning an empty [Utterance] instead would
         * push that decision down into the speaker, where it would be made once per engine.
         */
        fun of(text: String, channel: VoiceChannel, key: String): Utterance? =
            CreatureVoice.speakable(text)?.let { Utterance(it, channel, key) }
    }
}

/**
 * The player's settings for the voice layer. Silent in every direction until asked otherwise.
 *
 * The two halves are deliberately not symmetrical. Speaking costs nothing — no permission, no
 * key, no network — so its only gate is taste. Listening needs `RECORD_AUDIO`, which is a
 * decision about the device and not about the game, so [listens] being true is necessary but
 * never sufficient: the runtime permission is asked for separately and refusing it is a normal
 * answer that leaves everything else working.
 */
@Serializable
data class VoiceConfig(
    /**
     * Master switch for the creature speaking aloud.
     *
     * False, and it stays false through an upgrade, because a game that starts talking the first
     * time it is opened after an update is a game someone is holding on a train.
     */
    val speaks: Boolean = false,
    /** Read chat replies aloud. On by default *within* [speaks]: it is the reason to switch it on. */
    val readsChat: Boolean = true,
    /** Read new diary lines aloud. */
    val readsDiary: Boolean = false,
    /**
     * Read decisions aloud.
     *
     * Off even once the voice is on. An autonomous creature decides something every few minutes
     * and narrating each one turns a pet into a running commentary — the same reasoning that
     * keeps meals out of the unprompted remarks.
     */
    val readsDecisions: Boolean = false,
    /** Master switch for the microphone. Inert without the runtime permission. */
    val listens: Boolean = false,
    /**
     * Ask the system recogniser to stay on the device when it can.
     *
     * On by default. It is the player's own sentence being recognised, and an on-device
     * recogniser is the only arrangement where nothing about it leaves the handset at all.
     */
    val preferOnDevice: Boolean = true,
    /** Master volume for spoken lines, 0..1. Multiplies the creature's own loudness. */
    val volume: Float = 0.9f,
) {
    /** True when a line of this kind should actually be spoken. */
    fun reads(channel: VoiceChannel): Boolean = speaks && when (channel) {
        VoiceChannel.CHAT -> readsChat
        VoiceChannel.DIARY -> readsDiary
        VoiceChannel.DECISION -> readsDecisions
    }

    /** True when nothing at all would be spoken, whatever happens. Lets settings say so plainly. */
    val silent: Boolean get() = VoiceChannel.entries.none { reads(it) }

    /**
     * [volume] made safe. A settings slider cannot produce a NaN, but a hand-edited or truncated
     * save can, and NaN survives [Float.coerceIn] untouched — every comparison against it is
     * false, so the clamp passes it straight through to the engine.
     */
    val volumeScale: Float
        get() = if (volume.isNaN()) DEFAULT_VOLUME else volume.coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_VOLUME = 0.9f
    }
}

/**
 * The *expressed voice*: what a text-to-speech engine is actually set to before it reads a line.
 *
 * The sibling of [Morphology], and here for the same reason. A creature's voice is not a property
 * of the phone, it is a property of the creature — its stage, its temperament and the genes it
 * inherited — and keeping that arithmetic in the domain means the Android layer never has to know
 * what a life stage is. It asks for a voice and gets three numbers.
 *
 * It also means the interesting half can be tested. There is no Android SDK in this project's
 * local harness and nobody has heard a single sound this app makes, so anything that can only be
 * checked by listening is not checked at all. What *can* be checked is that the numbers are
 * always in range, that a corrupted save cannot produce a scream, that a baby and an elder are
 * far apart, and that the same creature sounds the same every time it opens its mouth.
 */
data class CreatureVoice(
    /** Multiplier on the engine's normal pitch. 1.0 is neutral. */
    val pitch: Float,
    /** Multiplier on the engine's normal speaking rate. 1.0 is neutral. */
    val rate: Float,
    /** How loudly this creature speaks, 0..1, before the player's own master volume. */
    val volume: Float,
) {
    /**
     * The voice of a creature that is currently ill, as a shade over the constitutional one.
     *
     * Kept as a separate step rather than an argument to [of] so that "what this creature sounds
     * like" stays a function of what it *is*. Illness passes; the voice underneath it does not
     * change, and one call away from proving that is worth more than one fewer function.
     */
    fun unwell(): CreatureVoice = CreatureVoice(
        pitch = clampPitch(pitch - 0.08f),
        rate = clampRate(rate - 0.10f),
        volume = clampVolume(volume - 0.12f),
    )

    /** Two words for the settings screen, so a voice can be described before it is heard. */
    val descriptor: String
        get() {
            val height = when {
                pitch >= 1.34f -> "high"
                pitch >= 1.12f -> "bright"
                pitch >= 0.95f -> "even"
                pitch >= 0.82f -> "low"
                else -> "deep"
            }
            val pace = when {
                rate >= 1.09f -> "quick"
                rate >= 1.02f -> "brisk"
                rate >= 0.95f -> "steady"
                rate >= 0.88f -> "unhurried"
                else -> "slow"
            }
            return "$height and $pace"
        }

    companion object {
        // ---- the band the engine is held inside -------------------------------------------
        //
        // Android rejects a pitch or a rate at or below zero outright, and engines clamp whatever
        // else they are handed to limits they do not publish. Rather than find out per device,
        // everything here stays inside the range every engine tested in the wild handles the same
        // way. These are guards, not the working range: the tables below never reach them on
        // their own, so a value arriving at a limit means a gene was corrupt.
        const val MIN_PITCH = 0.55f
        const val MAX_PITCH = 1.90f
        const val MIN_RATE = 0.60f
        const val MAX_RATE = 1.60f

        /** A creature is never made completely inaudible by its own temperament. */
        const val MIN_VOLUME = 0.45f

        /**
         * How far temperament and genes may move a voice away from its stage.
         *
         * This cap is the reason a stage always reads as itself. Without it a shy, barrel-chested
         * baby and a bold, slight elder could meet in the middle, and the single most legible
         * thing about the voice — that the creature grew up — would be the thing most easily
         * lost. Genes decide who this creature is *within* its age, never instead of it.
         */
        const val MAX_PITCH_SHADE = 0.16f
        const val MAX_RATE_SHADE = 0.10f

        /** Longest line that will be read aloud. See [speakable]. */
        const val MAX_SPOKEN_CHARS = 320

        /**
         * The voice of [state].
         *
         * Species is deliberately absent. It is already in here — a founder genome is drawn
         * around its species' own centre, so an Ember starts out broader in the chest than an
         * Aqua and is lower for that reason. Adding the species again on top would count it
         * twice and would make a cross-bred line sound like its label rather than its parents.
         */
        fun of(state: PetState): CreatureVoice = of(state.stage, state.personality, state.genome)

        fun of(stage: LifeStage, personality: Personality, genome: Genome): CreatureVoice {
            // Age is the load-bearing term: it is the one difference a player will hear without
            // being told to listen for it.
            val stagePitch = when (stage) {
                // An egg does not speak; the wiring never asks. The value exists so the function
                // is total, and it is the baby's, because that is what is in there.
                LifeStage.EGG -> 1.58f
                LifeStage.BABY -> 1.58f
                LifeStage.CHILD -> 1.32f
                LifeStage.TEEN -> 1.12f
                LifeStage.ADULT -> 1.00f
                LifeStage.ELDER -> 0.82f
            }
            val stageRate = when (stage) {
                LifeStage.EGG -> 1.12f
                LifeStage.BABY -> 1.12f
                LifeStage.CHILD -> 1.08f
                LifeStage.TEEN -> 1.03f
                LifeStage.ADULT -> 1.00f
                // Elders are not slow because they are tired. They are slow because they are the
                // only stage that has ever had the option.
                LifeStage.ELDER -> 0.84f
            }

            // Temperament. Small next to the stage, and small next to the genes: personality is
            // rolled once at the egg and never bred for, so it should not be the loudest thing
            // about a line the player spent four generations shaping.
            val personalityPitch = when (personality) {
                Personality.PLAYFUL -> 0.06f
                Personality.SHY -> 0.03f
                Personality.GREEDY -> -0.02f
                Personality.BRAVE -> -0.07f
                Personality.CALM -> -0.02f
            }
            val personalityRate = when (personality) {
                Personality.PLAYFUL -> 0.06f
                // Not slow — hesitant. The pause is the whole character.
                Personality.SHY -> -0.05f
                Personality.GREEDY -> 0.04f
                Personality.BRAVE -> 0.02f
                Personality.CALM -> -0.04f
            }

            // The heritable half, and the reason this is worth doing at all. Only genes with a
            // physical claim on a voice are read: a bigger chest resonates lower and a longer
            // muzzle is a longer tube, so those two set the pitch, while how much the creature
            // moves and how quickly it thinks set the pace. A shaggy coat and a hue shift are
            // not audible and are not consulted — a voice derived from every gene at once would
            // be derived from none of them.
            val build = gene(genome.build)
            val muzzle = gene(genome.muzzle)
            val vigor = gene(genome.vigor)
            val wit = gene(genome.wit)
            val sociability = gene(genome.sociability)

            val genomePitch = -(build - 0.5f) * 0.20f - (muzzle - 0.5f) * 0.12f
            val genomeRate = (vigor - 0.5f) * 0.14f + (wit - 0.5f) * 0.08f

            val pitchShade = (personalityPitch + genomePitch)
                .coerceIn(-MAX_PITCH_SHADE, MAX_PITCH_SHADE)
            val rateShade = (personalityRate + genomeRate)
                .coerceIn(-MAX_RATE_SHADE, MAX_RATE_SHADE)

            // Loudness carries the sociable half of the temperament, which has nowhere else to
            // go: a shy creature that talks to itself is quieter than one performing for a room.
            val personalityVolume = when (personality) {
                Personality.SHY -> -0.14f
                Personality.BRAVE -> 0.04f
                Personality.PLAYFUL -> 0.02f
                Personality.GREEDY -> 0f
                Personality.CALM -> -0.03f
            }

            return CreatureVoice(
                pitch = clampPitch(stagePitch + pitchShade),
                rate = clampRate(stageRate + rateShade),
                volume = clampVolume(0.94f + personalityVolume + (sociability - 0.5f) * 0.14f),
            )
        }

        /**
         * A short line in this creature's own words, for the settings screen to try the voice on.
         *
         * The preview has to be the creature saying something rather than a sample sentence,
         * because the whole claim being made on that screen is that this is not the phone reading
         * text out. A player who taps it and hears a stock phrase has been told the opposite.
         */
        fun previewLine(name: String, stage: LifeStage): String = when (stage) {
            LifeStage.EGG -> "Something inside the egg is listening."
            LifeStage.BABY -> "Hi hi hi. I am $name."
            LifeStage.CHILD -> "I am $name, and I can talk now."
            LifeStage.TEEN -> "It is $name. You wanted to hear me?"
            LifeStage.ADULT -> "$name here. Go on, I am listening."
            LifeStage.ELDER -> "It is only me. $name. Still here."
        }

        /**
         * [text] as an engine should receive it, or null when there is nothing to say.
         *
         * This is the single place that decides what is spoken aloud, which is the point: a
         * reader who wants to know what this app can say out loud has one function to read.
         *
         * Long lines are cut at the last sentence that fits rather than mid-word, and nothing is
         * appended to mark the cut — several engines read an ellipsis out as three dots, so the
         * punctuation meant to say "there was more" becomes the most memorable part of the line.
         */
        fun speakable(text: String): String? {
            val flat = buildString(text.length) {
                var lastWasSpace = false
                for (ch in text) {
                    // Newlines, tabs and stray control characters from a model reply are all just
                    // gaps once spoken; collapsing them here keeps the cap counting real words.
                    val space = ch.isWhitespace() || ch.isISOControl()
                    if (space) {
                        if (!lastWasSpace && isNotEmpty()) append(' ')
                    } else {
                        append(ch)
                    }
                    lastWasSpace = space
                }
            }.trim()
            if (flat.isEmpty()) return null
            if (flat.length <= MAX_SPOKEN_CHARS) return flat

            val window = flat.take(MAX_SPOKEN_CHARS)
            val sentenceEnd = window.indexOfLast { it == '.' || it == '!' || it == '?' }
            if (sentenceEnd >= MAX_SPOKEN_CHARS / 3) return window.take(sentenceEnd + 1)
            val wordEnd = window.lastIndexOf(' ')
            val cut = if (wordEnd > 0) window.take(wordEnd) else window
            return cut.trim().ifEmpty { null }
        }

        /**
         * A gene, made safe.
         *
         * NaN is the case that matters and the only one that needs code: it survives
         * [Float.coerceIn] because every comparison with it is false, so a single corrupt float
         * in a save would otherwise travel all the way to the engine and take a whole utterance
         * with it. It falls back to the middle of the range, so a damaged gene produces an
         * unremarkable voice rather than silence or a shriek.
         */
        private fun gene(value: Float): Float =
            if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)

        private fun clampPitch(value: Float): Float =
            if (value.isNaN()) 1f else value.coerceIn(MIN_PITCH, MAX_PITCH)

        private fun clampRate(value: Float): Float =
            if (value.isNaN()) 1f else value.coerceIn(MIN_RATE, MAX_RATE)

        private fun clampVolume(value: Float): Float =
            if (value.isNaN()) 1f else value.coerceIn(MIN_VOLUME, 1f)
    }
}
