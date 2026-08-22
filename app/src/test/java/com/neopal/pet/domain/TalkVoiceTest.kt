package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the talking screen obeys, held where a machine can check them.
 *
 * None of this can be checked by looking at the screen — there is no SDK here, nothing has been
 * run on a device, and the failures these guard against are all silent: a reply read out twice,
 * a permission prompt on launch, a microphone button offered by a phone that cannot listen, a
 * dictated sentence that quietly overwrites what was already typed. Each one looks like working
 * software right up until someone uses it.
 */
class TalkVoiceTest {

    private fun petSaid(text: String, at: Long = 10L) =
        ChatTurn(fromPet = true, text = text, atSeconds = at)

    private fun playerSaid(text: String, at: Long = 10L) =
        ChatTurn(fromPet = false, text = text, atSeconds = at)

    private val speaking = VoiceConfig(speaks = true, readsChat = true)

    // ---------------------------------------------------------------- the microphone offer

    @Test
    fun `nothing is offered and nothing is said while listening is switched off`() {
        // The default state of the game, and it must be completely quiet about itself: a player
        // who never wanted a microphone should not be able to tell this feature exists.
        val surface = MicSurface.of(
            listens = false,
            recogniserPresent = true,
            permission = MicPermission.GRANTED,
            listening = false,
            ready = true,
        )
        assertEquals(MicOffer.HIDDEN, surface.offer)
        assertFalse(surface.actionable)
        assertNull(surface.note)
    }

    @Test
    fun `a device with no recogniser says so instead of showing a button`() {
        val surface = MicSurface.of(
            listens = true,
            recogniserPresent = false,
            permission = MicPermission.GRANTED,
            listening = false,
            ready = true,
        )
        assertEquals(MicOffer.HIDDEN, surface.offer)
        assertNotNull("a missing recogniser must be explained, not merely absent", surface.note)
        assertTrue(surface.note!!.isNotBlank())
    }

    @Test
    fun `a refused microphone points at the keyboard and does not ask again`() {
        val surface = MicSurface.of(
            listens = true,
            recogniserPresent = true,
            permission = MicPermission.REFUSED,
            listening = false,
            ready = true,
        )
        assertEquals(MicOffer.HIDDEN, surface.offer)
        assertNotNull(surface.note)
        // The whole point of the refusal path: it names the way that still works.
        assertTrue(surface.note!!.contains("Typing"))
    }

    @Test
    fun `the permission is only ever asked for behind a press`() {
        // The one rule that cannot be verified from the screen and matters most: there is no
        // state in which the surface asks for anything on its own. UNASKED yields a button, and
        // the button is what asks.
        val surface = MicSurface.of(
            listens = true,
            recogniserPresent = true,
            permission = MicPermission.UNASKED,
            listening = false,
            ready = true,
        )
        assertEquals(MicOffer.ASK, surface.offer)
        assertTrue(surface.actionable)
        assertTrue(surface.readOut.isNotBlank())
    }

    @Test
    fun `a granted microphone listens, and an open one offers to stop`() {
        val idle = MicSurface.of(true, true, MicPermission.GRANTED, listening = false, ready = true)
        assertEquals(MicOffer.LISTEN, idle.offer)
        assertNull("a working microphone needs no explanation", idle.note)

        val open = MicSurface.of(true, true, MicPermission.GRANTED, listening = true, ready = true)
        assertEquals(MicOffer.STOP, open.offer)
        assertTrue(open.actionable)
    }

    @Test
    fun `a screen that cannot take a typed message offers nothing about microphones`() {
        // The composer is gone — the creature is an egg, asleep, or has no brain — and the panel
        // in its place is already saying why. A note about speech recognition underneath it is an
        // answer to a question nobody asked.
        MicPermission.entries.forEach { permission ->
            listOf(true, false).forEach { recogniser ->
                val surface = MicSurface.of(
                    listens = true,
                    recogniserPresent = recogniser,
                    permission = permission,
                    listening = false,
                    ready = false,
                )
                assertEquals(MicOffer.HIDDEN, surface.offer)
                assertNull(surface.note)
            }
        }
    }

    @Test
    fun `every actionable offer has a face and a sentence, every hidden one has neither`() {
        // A sweep rather than a case, because the failure this catches is a branch added later
        // that returns a button with no label on it.
        listOf(true, false).forEach { listens ->
            listOf(true, false).forEach { recogniser ->
                MicPermission.entries.forEach { permission ->
                    listOf(true, false).forEach { listening ->
                        listOf(true, false).forEach { ready ->
                            val s = MicSurface.of(listens, recogniser, permission, listening, ready)
                            val who = "$listens/$recogniser/$permission/$listening/$ready"
                            if (s.actionable) {
                                assertTrue("$who has no label", s.label.isNotBlank())
                                assertTrue("$who has no read-out", s.readOut.isNotBlank())
                            } else {
                                assertEquals("$who labels a button that is not there", "", s.label)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- what gets read out

    @Test
    fun `silence by default`() {
        // GameConfig ships silent both ways. If this ever passes with the shipped default, the
        // creature has started talking to people on trains.
        val chat = listOf(petSaid("Hello."))
        assertNull(TalkVoice.replyToSpeak(chat, VoiceConfig(), alreadySaid = null))
    }

    @Test
    fun `the newest reply is spoken once and then not again`() {
        val chat = listOf(playerSaid("Hi"), petSaid("Hello there."))
        val first = TalkVoice.replyToSpeak(chat, speaking, alreadySaid = null)
        assertNotNull(first)
        assertEquals("Hello there.", first!!.text)
        assertEquals(VoiceChannel.CHAT, first.channel)

        assertNull(
            "the same reply must not be read out twice",
            TalkVoice.replyToSpeak(chat, speaking, alreadySaid = first.key),
        )
    }

    @Test
    fun `the player's own line is never read back at them`() {
        val chat = listOf(petSaid("Hello."), playerSaid("How are you?"))
        assertNull(TalkVoice.replyToSpeak(chat, speaking, alreadySaid = null))
        assertNull(TalkVoice.newestReplyKey(chat))
    }

    @Test
    fun `switching chat replies off silences the voice without silencing the rest`() {
        val chat = listOf(petSaid("Hello."))
        val diaryOnly = VoiceConfig(speaks = true, readsChat = false, readsDiary = true)
        assertNull(TalkVoice.replyToSpeak(chat, diaryOnly, alreadySaid = null))
        assertFalse("the config is not silent — only this channel is", diaryOnly.silent)
    }

    @Test
    fun `a key survives the log dropping its oldest turns`() {
        // The regression this exists for: keying by index means every new turn renumbers the one
        // being spoken, and the creature reads its last reply out a second time.
        val reply = petSaid("The same sentence.", at = 42L)
        val early = listOf(playerSaid("a"), reply)
        val later = List(30) { playerSaid("filler $it") } + reply
        assertEquals(TalkVoice.keyOf(reply), TalkVoice.newestReplyKey(early))
        assertEquals(TalkVoice.newestReplyKey(early), TalkVoice.newestReplyKey(later))
    }

    @Test
    fun `two different replies at the same moment are different lines`() {
        val a = petSaid("Yes.", at = 7L)
        val b = petSaid("No.", at = 7L)
        assertTrue(TalkVoice.keyOf(a) != TalkVoice.keyOf(b))
    }

    @Test
    fun `a blank reply is nothing to say rather than an empty utterance`() {
        assertNull(TalkVoice.replyToSpeak(listOf(petSaid("   ")), speaking, alreadySaid = null))
        assertNull(TalkVoice.replyToSpeak(emptyList(), speaking, alreadySaid = null))
    }

    @Test
    fun `a long reply is cut to something an engine will read`() {
        val long = "This is a sentence. ".repeat(60)
        val utterance = TalkVoice.replyToSpeak(listOf(petSaid(long)), speaking, alreadySaid = null)
        assertNotNull(utterance)
        assertTrue(utterance!!.text.length <= CreatureVoice.MAX_SPOKEN_CHARS)
    }

    // ---------------------------------------------------------------- whose voice it is

    @Test
    fun `a bred line sounds like itself and not like the phone`() {
        val heavy = PetState(
            stage = LifeStage.ADULT,
            personality = Personality.CALM,
            genome = Genome(build = 1f, muzzle = 1f, vigor = 0f, wit = 0f, sociability = 0f),
        )
        val slight = heavy.copy(
            genome = Genome(build = 0f, muzzle = 0f, vigor = 1f, wit = 1f, sociability = 1f),
        )
        assertTrue(
            "two genomes at opposite corners must not produce the same voice",
            TalkVoice.voiceOf(heavy).pitch < TalkVoice.voiceOf(slight).pitch,
        )
    }

    @Test
    fun `illness shades the voice and recovery restores it exactly`() {
        val well = PetState(stage = LifeStage.TEEN, personality = Personality.PLAYFUL)
        val ill = well.copy(isSick = true)
        val illVoice = TalkVoice.voiceOf(ill)
        assertTrue(illVoice.pitch < TalkVoice.voiceOf(well).pitch)
        assertTrue(illVoice.rate < TalkVoice.voiceOf(well).rate)
        // Recovery is the case that would rot if illness were folded into the stored voice.
        assertEquals(TalkVoice.voiceOf(well), TalkVoice.voiceOf(ill.copy(isSick = false)))
    }

    @Test
    fun `an ill creature is still inside the band the engine accepts`() {
        LifeStage.entries.forEach { stage ->
            Personality.entries.forEach { personality ->
                val voice = TalkVoice.voiceOf(
                    PetState(stage = stage, personality = personality, isSick = true),
                )
                assertTrue("$stage/$personality", voice.pitch >= CreatureVoice.MIN_PITCH)
                assertTrue("$stage/$personality", voice.rate >= CreatureVoice.MIN_RATE)
                assertTrue("$stage/$personality", voice.volume >= 0f)
            }
        }
    }

    // ---------------------------------------------------------------- dictation into the composer

    @Test
    fun `dictation is added to what was typed, never over it`() {
        assertEquals("Hello there", VoiceComposer.blend("Hello", "there", 400))
        assertEquals("Hello there", VoiceComposer.blend("Hello ", "  there ", 400))
        assertEquals("there", VoiceComposer.blend("", "there", 400))
    }

    @Test
    fun `nothing heard leaves the draft exactly as it was`() {
        assertEquals("Hello ", VoiceComposer.blend("Hello ", "", 400))
        assertEquals("Hello ", VoiceComposer.blend("Hello ", "   ", 400))
    }

    @Test
    fun `dictation cannot push the draft past what the field will send`() {
        val draft = "x".repeat(395)
        val blended = VoiceComposer.blend(draft, "a much longer spoken sentence", 400)
        assertEquals(400, blended.length)
        assertTrue(blended.startsWith(draft))
    }

    @Test
    fun `a limit of zero produces nothing rather than throwing`() {
        assertEquals("", VoiceComposer.blend("", "anything", 0))
        assertEquals("", VoiceComposer.blend("", "anything", -5))
    }
}
