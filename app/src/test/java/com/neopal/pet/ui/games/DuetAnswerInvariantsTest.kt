package com.neopal.pet.ui.games

import com.neopal.pet.domain.Personality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The duet's reply, which is the whole game.
 *
 * Nothing in this file measures whether the creature answered *well*. There is no phrase the
 * player was supposed to sing and no distance from one being measured — the game says so
 * outright — so a test that scored the answer would be inventing a target the game refuses to
 * have. What is checkable is narrower and more useful:
 *
 *  - **Can the answer be sung at all?** Every degree it produces is fed to a synthesiser with a
 *    hard pitch clamp. A degree off the end of the ladder is a note that comes out at the wrong
 *    pitch or not at all, and since a verse is only *heard*, nothing on screen would show it.
 *  - **Can a verse end?** The turn lasts as long as the answer, one beat per note. An unbounded
 *    answer is a verse that never finishes and a song that never reaches its sixth.
 *  - **Is a personality still recognisable?** Each of the five ends its phrase somewhere
 *    characteristic. That is the part a player can actually hear, and the part a refactor of
 *    `trimTo` could silently destroy — the trim removes from the middle precisely so the last
 *    note survives.
 *
 * PLAN.md §6.7 records that not one note of this game has ever been listened to. These tests do
 * not fix that. They establish what a listener would be hearing.
 */
class DuetAnswerInvariantsTest {

    /**
     * Everything the creature can be handed as a call.
     *
     * Mostly that is what the player sings: five consecutive pads starting at [padLow], up to
     * [maxPhrase] notes. But not only — hand over without singing and the game passes the *hook*
     * as the call instead, and the hook's second note is the creature's own last note, which
     * comes from a register the pads cannot reach. So a call can carry a degree well above the
     * pad row, and any transformation that adds to the top of one has to survive it. Leaving
     * those out is what would make the range check vacuous: with pad degrees alone, nothing any
     * voice produces ever comes near the top of the ladder, and the coercion that guards it is
     * never exercised.
     */
    private fun eachCall(body: (IntArray) -> Unit) {
        // The two calls the game invents when the player says nothing: the hook, and the opening
        // phrase used before there is one.
        for (high in listOf(topDegree, topDegree - 1, home)) {
            body(intArrayOf(padLow, high))
            body(intArrayOf(high, padLow))
            body(intArrayOf(high, high))
        }
        body(intArrayOf(home, home + 1, home - 2))

        // The shapes a phrase can have, at every length the game allows: flat, rising, falling,
        // one note repeated, and a couple of wandering lines that are neither.
        for (n in 1..maxPhrase) {
            body(IntArray(n) { padLow })
            body(IntArray(n) { padLow + pads - 1 })
            body(IntArray(n) { padLow + it % pads })
            body(IntArray(n) { padLow + (pads - 1) - it % pads })
            body(IntArray(n) { padLow + (it * 3) % pads })
            body(IntArray(n) { padLow + if (it % 2 == 0) 0 else pads - 1 })
        }
    }

    /** The hooks a reply can be asked to open on: none yet, and the two-note motif thereafter. */
    private fun eachMotif(body: (IntArray) -> Unit) {
        body(IntArray(0))
        body(intArrayOf(padLow, home))
        body(intArrayOf(padLow + pads - 1, topDegree))
        body(intArrayOf(0, 0))
        body(intArrayOf(topDegree, topDegree))
    }

    private fun eachAnswer(body: (Personality, IntArray, IntArray, IntArray) -> Unit) {
        Personality.entries.forEach { personality ->
            eachCall { call ->
                eachMotif { motif ->
                    for (seed in 0 until 6) {
                        val reply = answerOf.invoke(
                            null, personality, call, motif, Random(seed * 31L + call.size),
                        ) as IntArray
                        body(personality, call, motif, reply)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ singability

    /**
     * Every note of every answer is on the ladder, and there is always at least one.
     *
     * An empty answer is a turn with nothing in it, which the handover would still wait out; a
     * degree off either end is a note the engine cannot pitch. Both are silent failures on a
     * screen whose whole output is sound.
     */
    @Test
    fun everyAnswerIsSingable() {
        eachAnswer { personality, call, motif, reply ->
            val where = "$personality answering ${call.toList()} on hook ${motif.toList()}"
            assertTrue("$where: the answer is empty", reply.isNotEmpty())
            reply.forEach { degree ->
                assertTrue(
                    "$where: degree $degree is off the ladder (0..$topDegree)",
                    degree in 0..topDegree,
                )
            }
        }
    }

    /**
     * The whole ladder fits the synthesiser, and climbs.
     *
     * The file says the scale was cut to fit `ChiptuneEngine`, which clamps its pitch multiplier
     * to 0.5..2.0 against a 520 Hz blip: exactly two octaves, and the ladder's top is exactly
     * two octaves above its root. That is a claim nobody could check by ear on a screen nobody has
     * heard, and it is the difference between a top note and a top note that comes out flat.
     */
    @Test
    fun theWholeLadderFitsTheSynthesiser() {
        var previous = -1f
        for (degree in 0..topDegree) {
            val hz = degreeHz.invoke(null, degree) as Float
            val pitch = hz / voiceBaseHz
            assertTrue(
                "degree $degree asks for a pitch of $pitch, outside the engine's 0.5..2.0 clamp",
                pitch in 0.5f..2.0f,
            )
            assertTrue("degree $degree ($hz Hz) is not above degree ${degree - 1}", hz > previous)
            previous = hz
        }
        assertEquals(
            "the bottom of the ladder is not the bottom of the engine's range",
            0.5, ((degreeHz.invoke(null, 0) as Float) / voiceBaseHz).toDouble(), 1e-4,
        )
        assertEquals(
            "the top of the ladder is not the top of the engine's range",
            2.0, ((degreeHz.invoke(null, topDegree) as Float) / voiceBaseHz).toDouble(), 1e-4,
        )
    }

    // ------------------------------------------------------------------------------- termination

    /**
     * No answer can make a verse outlast the song.
     *
     * The creature's turn runs for `HandoverPause + (notes + holdLast) * slot`, then a settle,
     * and only then does the verse tick over. So the length of the reply is the length of the
     * turn, and an answer that grew without bound would be a song stuck on verse one with no
     * error, no crash and a pad row that never comes back.
     *
     * The bound is the game's own: `MaxReply`, read from the file, so tuning the cap moves the
     * bound with it and only losing the cap fails. The measured worst turn across every
     * personality and every phrase is under four seconds.
     */
    @Test
    fun noAnswerCanOutlastItsVerse() {
        val slowest = Personality.entries.maxOf { slotOf(it) }
        val longestHold = Personality.entries.maxOf { holdOf(it) }
        val worstPossibleTurn = handoverPause + (maxReply + longestHold) * slowest + settlePause
        assertTrue(
            "even a capped answer can hold the pads for ${worstPossibleTurn}s, which is not a turn",
            worstPossibleTurn < 8f,
        )
        eachAnswer { personality, call, motif, reply ->
            val where = "$personality answering ${call.toList()} on hook ${motif.toList()}"
            assertTrue(
                "$where: the answer runs to ${reply.size} notes, past the file's own cap of $maxReply",
                reply.size <= maxReply,
            )
            val turn = handoverPause + (reply.size + holdOf(personality)) * slotOf(personality) + settlePause
            assertTrue("$where: the turn lasts ${turn}s", turn <= worstPossibleTurn + 1e-4f)
        }
    }

    /** Handed nothing at all, it still starts something rather than singing an empty bar. */
    @Test
    fun anEmptyCallIsAnsweredWithANote() {
        Personality.entries.forEach { personality ->
            val reply = answerOf.invoke(
                null, personality, IntArray(0), IntArray(0), Random(1),
            ) as IntArray
            assertEquals("$personality answered an empty call with ${reply.toList()}", 1, reply.size)
            assertEquals("$personality did not start from home", home, reply[0])
        }
    }

    // -------------------------------------------------------------------------- the five habits

    /**
     * The shy one never out-sings you.
     *
     * "It answers from underneath your line, and stops before you did" is what the screen reads
     * out to a player who cannot hear it, so it had better be true of the notes. The trim is
     * relative to the call rather than to a fixed number, which is the only reason a two-note
     * phrase does not come back as five.
     */
    @Test
    fun theShyOneAlwaysStopsBeforeYouDid() {
        eachAnswer { personality, call, motif, reply ->
            if (personality != Personality.SHY) return@eachAnswer
            assertTrue(
                "shy answered ${call.toList()} (hook ${motif.toList()}) with ${reply.toList()}, " +
                    "which is longer than you sang",
                reply.size <= call.size,
            )
        }
    }

    /**
     * The brave one always finishes on the ceiling, the calm one always settles on home, and the
     * greedy one always ends holding the best note you gave it.
     *
     * All three are the last note of the phrase, and all three depend on one detail of `trimTo`:
     * it removes from `size - 2`, never the end, precisely so that a windy answer loses its
     * waffle rather than its shape. A trim that took from the tail instead would compile, run,
     * and quietly turn three distinct personalities into one.
     */
    @Test
    fun eachVoiceEndsWhereItsHabitSays() {
        eachAnswer { personality, call, motif, reply ->
            val where = "$personality answering ${call.toList()} on hook ${motif.toList()} " +
                "gave ${reply.toList()}"
            when (personality) {
                Personality.BRAVE -> assertEquals(
                    "$where, which does not finish at the top of its range", topDegree, reply.last(),
                )
                Personality.CALM -> assertEquals(
                    "$where, which does not settle on home", home, reply.last(),
                )
                Personality.GREEDY -> assertEquals(
                    "$where, which does not end on the note it kept", call.max(), reply.last(),
                )
                Personality.PLAYFUL -> assertTrue(
                    "$where, which does not run off the end of your line",
                    reply.last() >= minOf(call.max() + 3, topDegree),
                )
                Personality.SHY -> assertTrue(
                    "$where, which is not below the line you sang",
                    reply.last() <= call.max(),
                )
            }
        }
    }

    /**
     * The hook, once there is one, opens the answer.
     *
     * This is the only thing holding six verses together as one song rather than six exercises,
     * and it survives every transformation except the trims that cannot fit it. Asserted where it
     * can be: whenever the reply is long enough to have kept the whole motif.
     */
    @Test
    fun theHookOpensTheAnswerWhenThereIsRoomForIt() {
        var checked = 0
        eachAnswer { personality, call, motif, reply ->
            if (motif.isEmpty() || reply.size <= motif.size) return@eachAnswer
            checked += 1
            assertEquals(
                "$personality answering ${call.toList()} dropped the hook ${motif.toList()}: " +
                    "${reply.toList()}",
                motif.toList(),
                reply.take(motif.size).toList(),
            )
        }
        assertTrue("no answer in the sweep had room for its hook", checked > 100)
    }

    // ---------------------------------------------------------------------------------- the echo

    /**
     * An echo is heard by scale step, not by pitch.
     *
     * The creature answers in registers the five pads cannot reach, so asking for the literal
     * degree back would make the echo impossible to perform and the "listening" half of the score
     * unreachable. Two checks: a phrase always quotes itself, and quoting it an octave away still
     * counts.
     */
    @Test
    fun anEchoIsHeardByStepAndNotByPitch() {
        eachCall { call ->
            if (call.size < 2) {
                assertTrue(
                    "a single note was taken as a quote of ${call.toList()}",
                    !(quotes.invoke(null, call, call) as Boolean),
                )
                return@eachCall
            }
            assertTrue(
                "${call.toList()} does not quote itself",
                quotes.invoke(null, call, call) as Boolean,
            )
            val octaveUp = IntArray(call.size) { call[it] + 5 }
            assertTrue(
                "${call.toList()} sung back an octave up (${octaveUp.toList()}) was not heard as a quote",
                quotes.invoke(null, call, octaveUp) as Boolean,
            )
        }
    }

    // ---------------------------------------------------------------------------------- the number

    /**
     * The score never punishes taking part.
     *
     * Not the values — those are balance and are meant to move — but the direction. This game has
     * no losing move by design: the pads contain no wrong note and there is nothing to be
     * incorrect about. A tally that could fall when the player sang one more note would
     * contradict the whole screen, and since the number is hidden until the song is over, nobody
     * would find out during a game.
     */
    @Test
    fun singingMoreNeverScoresLess() {
        val base = intArrayOf(4, 3, 1, 2)
        for (which in base.indices) {
            for (n in 0..12) {
                val less = base.copyOf().also { it[which] = n }
                val more = base.copyOf().also { it[which] = n + 1 }
                val lessScore = tally.invoke(null, less[0], less[1], less[2], less[3]) as Int
                val moreScore = tally.invoke(null, more[0], more[1], more[2], more[3]) as Int
                assertTrue(
                    "one more of argument $which took the score from $lessScore to $moreScore",
                    moreScore >= lessScore,
                )
            }
        }
        assertEquals("a silent song is not worth nothing", 0, tally.invoke(null, 0, 0, 0, 0) as Int)
    }

    // ----------------------------------------------------------------------------------- driving

    private fun slotOf(personality: Personality): Float =
        voiceSlot.invoke(voiceOf.invoke(null, personality)) as Float

    private fun holdOf(personality: Personality): Int =
        voiceHold.invoke(voiceOf.invoke(null, personality)) as Int

    private companion object {
        private const val FILE = "DuetGameKt"

        val answerOf = NpGameReflect.fn(
            FILE, "answerOf",
            Personality::class.java, IntArray::class.java, IntArray::class.java, NpGameReflect.RANDOM,
        )
        val quotes = NpGameReflect.fn(FILE, "quotes", IntArray::class.java, IntArray::class.java)
        val degreeHz = NpGameReflect.fn(FILE, "degreeHz", NpGameReflect.I)
        val voiceOf = NpGameReflect.fn(FILE, "voiceOf", Personality::class.java)
        val tally = NpGameReflect.fn(
            FILE, "tally", NpGameReflect.I, NpGameReflect.I, NpGameReflect.I, NpGameReflect.I,
        )

        val voiceSlot = NpGameReflect.member("Voice", "getSlot")
        val voiceHold = NpGameReflect.member("Voice", "getHoldLast")

        // Read from the file rather than restated here, so that tuning any of them moves the
        // bounds below with it and only *losing* one fails the tests.
        val topDegree = NpGameReflect.constant(FILE, "TopDegree") as Int
        val home = NpGameReflect.constant(FILE, "HomeDegree") as Int
        val padLow = NpGameReflect.constant(FILE, "PadLowDegree") as Int
        val pads = NpGameReflect.constant(FILE, "Pads") as Int
        val maxPhrase = NpGameReflect.constant(FILE, "MaxPhrase") as Int
        val maxReply = NpGameReflect.constant(FILE, "MaxReply") as Int
        val voiceBaseHz = NpGameReflect.constant(FILE, "VoiceBaseHz") as Float
        val handoverPause = NpGameReflect.constant(FILE, "HandoverPause") as Float
        val settlePause = NpGameReflect.constant(FILE, "SettlePause") as Float
    }
}
