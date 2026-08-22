package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the creature plays on its own.
 *
 * Two things are worth pinning here and they pull against each other: the choice has to be
 * *stable*, because it is read several times for one decision and again by the screen that shows
 * what the creature is weighing up, and it has to *vary*, because a creature that played the same
 * game for its entire life would be a constant wearing a costume.
 */
class SoloPlayTest {

    private val config = GameConfig.Default

    private fun pet(genome: Genome = Genome(), intellect: Float = 50f, age: Long = 12_000L): PetState =
        Simulation
            .advance(Simulation.newGame("T", Species.LEAF, 1_000_000L), 1_120_000L, config)
            .state
            .copy(
                stage = LifeStage.ADULT,
                ageSeconds = age,
                genome = genome,
                intellect = intellect,
                autonomy = Autonomy.FULL,
                skills = Skill.entries.toSet(),
                stats = Stats(satiety = 80f, happiness = 22f, energy = 90f, hygiene = 80f),
            )

    @Test
    fun `asking twice in the same moment gives the same answer`() {
        // The brain scores options more than once per decision, and the considerations screen
        // scores them again. A rolled choice would have the screen disagree with the creature.
        val creature = pet()
        val answers = (1..25).map { SoloPlay.choice(creature) }.toSet()
        assertEquals("a preference that flickers is not a preference", 1, answers.size)
    }

    @Test
    fun `but it does not play one game for its whole life`() {
        val creature = pet()
        val overALife = (0..40)
            .map { SoloPlay.choice(creature.copy(ageSeconds = it * SoloPlay.RESTLESS_SECONDS)) }
            .toSet()
        assertTrue("a creature with one fixed hobby is a constant in a costume", overALife.size >= 3)
    }

    @Test
    fun `an athletic line goes and chases something`() {
        val athlete = pet(Genome(vigor = 0.95f, limbs = 0.85f, wit = 0.10f, sociability = 0.15f, curiosity = 0.2f))
        val picks = (0..40).map { SoloPlay.choice(athlete.copy(ageSeconds = it * SoloPlay.RESTLESS_SECONDS)) }
        val physical = picks.count { it == MiniGame.FETCH || it == MiniGame.CATCH || it == MiniGame.RHYTHM }
        assertTrue("built for running, wants to run: $picks", physical > picks.size / 2)
        assertEquals(MiniGame.FETCH, SoloPlay.favourite(athlete))
    }

    @Test
    fun `a bookish line sits down with something`() {
        val scholar = pet(
            Genome(vigor = 0.10f, limbs = 0.15f, wit = 0.95f, curiosity = 0.85f, sociability = 0.2f),
            intellect = 95f,
        )
        val picks = (0..40).map { SoloPlay.choice(scholar.copy(ageSeconds = it * SoloPlay.RESTLESS_SECONDS)) }
        val thinking = picks.count { it == MiniGame.PUZZLE || it == MiniGame.MEMORY }
        assertTrue("built for thinking, wants to think: $picks", thinking > picks.size / 2)
    }

    @Test
    fun `a sociable line would rather sing`() {
        val friendly = pet(Genome(sociability = 0.98f, vigor = 0.15f, wit = 0.15f, limbs = 0.2f, curiosity = 0.3f))
        assertEquals(MiniGame.DUET, SoloPlay.favourite(friendly))
    }

    @Test
    fun `taste follows aptitude rather than contradicting it`() {
        // A creature that always wanted the game it was worst at would read as broken, not as
        // endearing. Both extremes are checked, because only checking one lets a constant pass.
        val athlete = pet(Genome(vigor = 0.95f, limbs = 0.9f, wit = 0.05f, sociability = 0.1f))
        val scholar = pet(Genome(vigor = 0.05f, limbs = 0.1f, wit = 0.95f, curiosity = 0.9f), intellect = 95f)
        assertTrue(SoloPlay.favourite(athlete) != SoloPlay.favourite(scholar))
    }

    @Test
    fun `the brain hands the game to the activity rather than leaving it blank`() {
        var s = pet()
        val events = mutableListOf<GameEvent>()
        // Bored, rested and fed, so playing is what it should land on.
        s = Brain.tick(s, config, 1L, Random(4), events)
        if (s.activity?.kind == ActivityKind.PLAY) {
            val id = s.activity?.targetId
            assertNotNull("a play with no game is the thing this replaced", id)
            assertNotNull("and it has to be a game that exists", MiniGame.byId(id!!))
        }
    }

    @Test
    fun `it says which game it played rather than something generic`() {
        MiniGame.entries.forEach {
            assertTrue("every game needs its own line", SoloPlay.note(it).isNotBlank())
        }
        assertEquals(
            "two games sharing a line would make the diary a template",
            MiniGame.entries.size,
            MiniGame.entries.map { SoloPlay.note(it) }.toSet().size,
        )
    }

    @Test
    fun `playing alone never touches the player's records`() {
        // The high scores are the player's account of their own afternoons. A creature quietly
        // beating a record on a game nobody has opened would take something rather than add it —
        // and it would unlock the badge for scoring in all seven without the player playing any.
        var s = pet().copy(highScores = mapOf(MiniGame.CATCH.id to 900))
        repeat(400) { s = Brain.tick(s, config, 60L, Random(it), mutableListOf()) }

        assertEquals("records are the player's alone", mapOf(MiniGame.CATCH.id to 900), s.highScores)
        assertFalse(
            "nor may it unlock the badge for scoring in all seven",
            Achievements.get("all_games")!!.test(s),
        )
    }
}
