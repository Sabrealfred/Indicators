package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The half of the voice feature that can be checked without ears.
 *
 * Nobody on this project has heard a sound this app makes, and there is no Android SDK in the
 * local harness, so the speaking itself is unverified by construction. What is left is the part
 * that decides *what* the engine is set to, and that part is arithmetic: it can be wrong in ways
 * that are perfectly silent — a clamp that never fires, a NaN that walks straight through a
 * `coerceIn`, a genome term large enough to swallow the difference between a baby and an elder.
 * Every test here is one of those.
 */
class CreatureVoiceTest {

    // Everything a creature can be, for the sweeps. Extremes included, because a bred line is
    // allowed to arrive at one and a corrupted save is allowed to claim it did.
    private val genomes: List<Genome> = listOf(
        Genome(),
        Genome(build = 0f, muzzle = 0f, vigor = 0f, wit = 0f, sociability = 0f),
        Genome(build = 1f, muzzle = 1f, vigor = 1f, wit = 1f, sociability = 1f),
        Genome(build = 1f, muzzle = 0f, vigor = 1f, wit = 0f, sociability = 1f),
        Genome(build = 0f, muzzle = 1f, vigor = 0f, wit = 1f, sociability = 0f),
        Genome(build = 0.5f, muzzle = 0.5f, vigor = 0.5f, wit = 0.5f, sociability = 0.5f),
    )

    private fun everyCreature(body: (LifeStage, Personality, Genome) -> Unit) {
        LifeStage.entries.forEach { stage ->
            Personality.entries.forEach { personality ->
                genomes.forEach { genome -> body(stage, personality, genome) }
            }
        }
    }

    // ---------------------------------------------------------------- staying inside the band

    @Test
    fun `pitch and rate stay inside the band whatever the creature is`() {
        everyCreature { stage, personality, genome ->
            val voice = CreatureVoice.of(stage, personality, genome)
            val who = "$stage/$personality/${genome.build}"
            assertTrue("$who pitch too low: ${voice.pitch}", voice.pitch >= CreatureVoice.MIN_PITCH)
            assertTrue("$who pitch too high: ${voice.pitch}", voice.pitch <= CreatureVoice.MAX_PITCH)
            assertTrue("$who rate too low: ${voice.rate}", voice.rate >= CreatureVoice.MIN_RATE)
            assertTrue("$who rate too high: ${voice.rate}", voice.rate <= CreatureVoice.MAX_RATE)
        }
    }

    @Test
    fun `nothing handed to the engine is zero, negative or NaN`() {
        // Android rejects a pitch or a rate at or below zero outright, so this is the one
        // property that turns into a thrown-away utterance rather than an odd-sounding one.
        everyCreature { stage, personality, genome ->
            val voice = CreatureVoice.of(stage, personality, genome).unwell()
            assertTrue(voice.pitch > 0f)
            assertTrue(voice.rate > 0f)
            assertFalse(voice.pitch.isNaN())
            assertFalse(voice.rate.isNaN())
            assertFalse(voice.volume.isNaN())
            assertTrue(voice.volume in 0f..1f)
        }
    }

    @Test
    fun `the clamps are guards, not the working range`() {
        // If an ordinary creature ever lands exactly on a limit, the limit has become part of
        // the design and the tables above it have stopped being the thing that decides.
        everyCreature { stage, personality, genome ->
            val voice = CreatureVoice.of(stage, personality, genome)
            assertTrue(voice.pitch > CreatureVoice.MIN_PITCH && voice.pitch < CreatureVoice.MAX_PITCH)
            assertTrue(voice.rate > CreatureVoice.MIN_RATE && voice.rate < CreatureVoice.MAX_RATE)
        }
    }

    @Test
    fun `a corrupted genome cannot produce a nonsense voice`() {
        // NaN is the interesting one: it survives coerceIn untouched, because every comparison
        // against it is false. A save with one damaged float would otherwise reach the engine.
        val broken = listOf(
            Genome(build = Float.NaN, muzzle = Float.NaN, vigor = Float.NaN, wit = Float.NaN, sociability = Float.NaN),
            Genome(build = Float.POSITIVE_INFINITY, vigor = Float.NEGATIVE_INFINITY),
            Genome(build = -50f, muzzle = 900f, vigor = -0.0001f, wit = 12f, sociability = -3f),
            Genome(build = Float.MAX_VALUE, muzzle = -Float.MAX_VALUE),
        )
        broken.forEach { genome ->
            LifeStage.entries.forEach { stage ->
                val voice = CreatureVoice.of(stage, Personality.CALM, genome)
                assertFalse("pitch went bad on $genome", voice.pitch.isNaN())
                assertFalse("rate went bad on $genome", voice.rate.isNaN())
                assertTrue(voice.pitch in CreatureVoice.MIN_PITCH..CreatureVoice.MAX_PITCH)
                assertTrue(voice.rate in CreatureVoice.MIN_RATE..CreatureVoice.MAX_RATE)
                assertTrue(voice.volume in CreatureVoice.MIN_VOLUME..1f)
            }
        }
    }

    @Test
    fun `a fully damaged genome sounds like an ordinary creature of its age`() {
        // Falling back to the middle rather than to zero is what makes a corrupt save survivable:
        // the creature still sounds its age, it has just lost the part that made it itself.
        val neutral = Genome(build = 0.5f, muzzle = 0.5f, vigor = 0.5f, wit = 0.5f, sociability = 0.5f)
        val ruined = Genome(build = Float.NaN, muzzle = Float.NaN, vigor = Float.NaN, wit = Float.NaN, sociability = Float.NaN)
        LifeStage.entries.forEach { stage ->
            assertEquals(
                CreatureVoice.of(stage, Personality.SHY, neutral),
                CreatureVoice.of(stage, Personality.SHY, ruined),
            )
        }
    }

    // ---------------------------------------------------------------- age has to be audible

    @Test
    fun `a baby and an elder never sound alike`() {
        // The strong form: the *quietest* possible baby is still far above the *deepest*
        // possible elder. This is what the shade cap buys, and the reason it exists.
        var worst = Float.MAX_VALUE
        Personality.entries.forEach { babyTrait ->
            Personality.entries.forEach { elderTrait ->
                genomes.forEach { babyGenes ->
                    genomes.forEach { elderGenes ->
                        val baby = CreatureVoice.of(LifeStage.BABY, babyTrait, babyGenes)
                        val elder = CreatureVoice.of(LifeStage.ELDER, elderTrait, elderGenes)
                        worst = minOf(worst, baby.pitch - elder.pitch)
                    }
                }
            }
        }
        assertTrue("closest baby/elder pair was $worst apart", worst >= 0.40f)
    }

    @Test
    fun `growing up lowers the voice at every step`() {
        genomes.forEach { genome ->
            Personality.entries.forEach { personality ->
                val ladder = listOf(
                    LifeStage.BABY, LifeStage.CHILD, LifeStage.TEEN, LifeStage.ADULT, LifeStage.ELDER,
                ).map { CreatureVoice.of(it, personality, genome).pitch }
                ladder.zipWithNext { higher, lower ->
                    assertTrue("$personality went up on the way to old age: $ladder", lower < higher)
                }
            }
        }
    }

    @Test
    fun `temperament and genes shade a stage, they never move it`() {
        // Stated without reaching for the stage table: whatever a creature of a given age can be,
        // the whole spread of pitches available to it fits inside twice the shade cap. That is
        // what keeps two adjacent stages from swapping places.
        val span = 2f * CreatureVoice.MAX_PITCH_SHADE
        LifeStage.entries.forEach { stage ->
            val pitches = Personality.entries.flatMap { personality ->
                genomes.map { CreatureVoice.of(stage, personality, it).pitch }
            }
            val spread = pitches.max() - pitches.min()
            assertTrue("$stage spread $spread, past the $span it is allowed", spread <= span + 1e-4f)
        }
    }

    // ---------------------------------------------------------------- the same creature, always

    @Test
    fun `the same creature sounds the same every time it opens its mouth`() {
        everyCreature { stage, personality, genome ->
            val first = CreatureVoice.of(stage, personality, genome)
            repeat(4) { assertEquals(first, CreatureVoice.of(stage, personality, genome)) }
        }
    }

    @Test
    fun `the state overload agrees with the parts it is made of`() {
        val genome = Genome(build = 0.8f, muzzle = 0.3f, vigor = 0.2f, wit = 0.9f, sociability = 0.1f)
        val pet = PetState(stage = LifeStage.TEEN, personality = Personality.BRAVE, genome = genome)
        assertEquals(CreatureVoice.of(LifeStage.TEEN, Personality.BRAVE, genome), CreatureVoice.of(pet))
    }

    @Test
    fun `illness is a shade over the voice, not a new one`() {
        val genome = Genome(build = 0.7f)
        val well = CreatureVoice.of(LifeStage.ADULT, Personality.PLAYFUL, genome)
        val ill = well.unwell()
        assertTrue(ill.pitch < well.pitch)
        assertTrue(ill.rate < well.rate)
        assertTrue(ill.volume < well.volume)
        // Recovering has to give the voice back exactly, or a creature that was once sick would
        // carry it for the rest of its life.
        assertEquals(well, CreatureVoice.of(LifeStage.ADULT, Personality.PLAYFUL, genome))
        assertTrue(ill.volume >= CreatureVoice.MIN_VOLUME)
    }

    // ---------------------------------------------------------------- a bred line sounds like itself

    @Test
    fun `genes are not decoration`() {
        val slight = Genome(build = 0.1f)
        val barrel = Genome(build = 0.9f)
        val a = CreatureVoice.of(LifeStage.ADULT, Personality.CALM, slight)
        val b = CreatureVoice.of(LifeStage.ADULT, Personality.CALM, barrel)
        assertTrue("a barrel chest has to be lower than a slight one", b.pitch < a.pitch)
        assertTrue("and by enough to hear", a.pitch - b.pitch >= 0.10f)
    }

    @Test
    fun `a line bred deep breeds deep`() {
        // The payoff for four generations of choosing: not "somewhat correlated on average", but
        // every single child of two deep-voiced parents lower than an unbred creature.
        val deep = Genome(build = 0.95f)
        val neutral = CreatureVoice.of(LifeStage.ADULT, Personality.CALM, Genome()).pitch
        val random = Random(20260822)
        var sum = 0f
        repeat(200) {
            val child = Genome.breed(deep, deep, random)
            val pitch = CreatureVoice.of(LifeStage.ADULT, Personality.CALM, child).pitch
            assertTrue("a child of the line came out higher than an unbred creature", pitch < neutral)
            sum += pitch
        }
        assertTrue("the line has to be clearly, not marginally, deeper", neutral - sum / 200f > 0.03f)
    }

    @Test
    fun `a shy creature is quieter than a bold one and neither is inaudible`() {
        val plain = Genome(sociability = 0.5f)
        val shy = CreatureVoice.of(LifeStage.ADULT, Personality.SHY, plain)
        val brave = CreatureVoice.of(LifeStage.ADULT, Personality.BRAVE, plain)
        assertTrue(shy.volume < brave.volume)
        everyCreature { stage, personality, genome ->
            assertTrue(CreatureVoice.of(stage, personality, genome).volume >= CreatureVoice.MIN_VOLUME)
        }
    }

    // ---------------------------------------------------------------- silence is the default

    @Test
    fun `a fresh install says nothing in either direction`() {
        val config = VoiceConfig()
        assertFalse(config.speaks)
        assertFalse(config.listens)
        assertTrue(config.silent)
        VoiceChannel.entries.forEach { assertFalse("$it spoke unasked", config.reads(it)) }
    }

    @Test
    fun `the master switch beats every sub-toggle`() {
        val loud = VoiceConfig(speaks = false, readsChat = true, readsDiary = true, readsDecisions = true)
        VoiceChannel.entries.forEach { assertFalse(loud.reads(it)) }
        assertTrue(loud.silent)
    }

    @Test
    fun `switching the voice on reads replies and nothing else`() {
        val on = VoiceConfig(speaks = true)
        assertTrue(on.reads(VoiceChannel.CHAT))
        assertFalse(on.reads(VoiceChannel.DIARY))
        assertFalse(on.reads(VoiceChannel.DECISION))
        assertFalse(on.silent)
    }

    @Test
    fun `a damaged volume setting cannot silence or deafen`() {
        assertEquals(VoiceConfig.DEFAULT_VOLUME, VoiceConfig(volume = Float.NaN).volumeScale, 0.0001f)
        assertEquals(1f, VoiceConfig(volume = 40f).volumeScale, 0.0001f)
        assertEquals(0f, VoiceConfig(volume = -3f).volumeScale, 0.0001f)
        assertEquals(0.5f, VoiceConfig(volume = 0.5f).volumeScale, 0.0001f)
    }

    // ---------------------------------------------------------------- who gets the mouth

    @Test
    fun `a reply takes the mouth from a diary line but not the other way round`() {
        assertTrue(VoiceChannel.CHAT.interrupts(VoiceChannel.DIARY))
        assertTrue(VoiceChannel.CHAT.interrupts(VoiceChannel.DECISION))
        assertFalse(VoiceChannel.DIARY.interrupts(VoiceChannel.CHAT))
        assertFalse(VoiceChannel.DECISION.interrupts(VoiceChannel.CHAT))
    }

    @Test
    fun `the newest line of a kind replaces the one still being spoken`() {
        VoiceChannel.entries.forEach { channel ->
            assertTrue(channel.interrupts(channel))
            assertTrue("a silent mouth is always free", channel.interrupts(null))
        }
    }

    // ---------------------------------------------------------------- what is worth saying

    @Test
    fun `nothing is spoken for an empty line`() {
        assertNull(CreatureVoice.speakable(""))
        assertNull(CreatureVoice.speakable("   "))
        assertNull(CreatureVoice.speakable("\n\t  \r\n"))
        assertNull(Utterance.of("  ", VoiceChannel.CHAT, "k"))
    }

    @Test
    fun `a line keeps its words and loses its layout`() {
        assertEquals("I am hungry.", CreatureVoice.speakable("  I am\n hungry.  "))
        assertEquals("one two three", CreatureVoice.speakable("one\t\ttwo\n\n\nthree"))
        assertEquals("plain", CreatureVoice.speakable("plain"))
    }

    @Test
    fun `a long line is cut where a sentence ended, and never marked`() {
        val long = "I woke up early. " + "The room was quiet and I sat by the bowl. ".repeat(20)
        val spoken = CreatureVoice.speakable(long)
        assertNotNull(spoken)
        val said = spoken!!
        assertTrue(said.length <= CreatureVoice.MAX_SPOKEN_CHARS)
        assertTrue("a cut should land on a sentence", said.endsWith("."))
        assertFalse("an ellipsis gets read out as three dots", said.contains("..."))
    }

    @Test
    fun `a long line with no sentence in it is cut between words`() {
        val rambling = "word ".repeat(200).trim()
        val said = CreatureVoice.speakable(rambling)!!
        assertTrue(said.length <= CreatureVoice.MAX_SPOKEN_CHARS)
        assertFalse("a cut mid-word is a cut nobody can hear the end of", said.endsWith("wor"))
        assertTrue(said.endsWith("word"))
    }

    @Test
    fun `one very long word is still said rather than dropped`() {
        val said = CreatureVoice.speakable("a".repeat(900))!!
        assertEquals(CreatureVoice.MAX_SPOKEN_CHARS, said.length)
    }

    @Test
    fun `trimming a line twice changes nothing the second time`() {
        listOf("hello there", "  spaced  out  ", "long. ".repeat(200)).forEach { raw ->
            val once = CreatureVoice.speakable(raw)!!
            assertEquals(once, CreatureVoice.speakable(once))
        }
    }

    @Test
    fun `an utterance carries where it came from`() {
        val u = Utterance.of("  Hello  \n there ", VoiceChannel.DIARY, "c_12")!!
        assertEquals("Hello there", u.text)
        assertEquals(VoiceChannel.DIARY, u.channel)
        assertEquals("c_12", u.key)
    }

    // ---------------------------------------------------------------- saying it in words

    @Test
    fun `every voice can be described before it is heard`() {
        everyCreature { stage, personality, genome ->
            val words = CreatureVoice.of(stage, personality, genome).descriptor
            assertTrue(words.isNotBlank())
            assertFalse(words.contains("null"))
            assertTrue(words.contains(" and "))
        }
    }

    @Test
    fun `the description changes with the creature`() {
        val baby = CreatureVoice.of(LifeStage.BABY, Personality.PLAYFUL, Genome()).descriptor
        val elder = CreatureVoice.of(LifeStage.ELDER, Personality.CALM, Genome()).descriptor
        assertFalse("a baby and an elder must not read the same", baby == elder)
    }

    @Test
    fun `the preview is the creature talking, not a sample sentence`() {
        LifeStage.entries.forEach { stage ->
            val line = CreatureVoice.previewLine("Pip", stage)
            assertTrue(line.isNotBlank())
            assertNotNull(CreatureVoice.speakable(line))
            if (stage != LifeStage.EGG) {
                assertTrue("$stage forgot whose voice it is", line.contains("Pip"))
            }
        }
    }
}
