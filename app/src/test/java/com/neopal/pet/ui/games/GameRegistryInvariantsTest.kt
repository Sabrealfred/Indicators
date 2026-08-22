package com.neopal.pet.ui.games

import com.neopal.pet.domain.MiniGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring between the seven game files and the one enum that names them.
 *
 * This is the cheapest test in the directory and the one with the least interesting failure
 * mode, which is exactly why it is worth having: the ids drifted once already. Four of the games
 * arrived writing `game_hide` while the three before them wrote `catch`, and the two spellings
 * sat in players' save files side by side until somebody noticed by eye. A high score is filed
 * under this string; a game that files under the wrong one silently hands its record to another
 * game, and nothing on screen looks broken.
 *
 * The table below is deliberately written out rather than derived. Derived from what? Every
 * source of truth it could be derived from is one of the two things being compared.
 */
class GameRegistryInvariantsTest {

    /**
     * One row per game: the enum entry, the file the screen lives in, and the function the
     * navigation graph calls.
     */
    private val games = listOf(
        Triple(MiniGame.RHYTHM, "RhythmGameKt", "RhythmGameScreen"),
        Triple(MiniGame.MEMORY, "MemoryGameKt", "MemoryGameScreen"),
        Triple(MiniGame.CATCH, "CatchGameKt", "CatchGameScreen"),
        Triple(MiniGame.HIDE, "HideAndSeekGameKt", "HideAndSeekGameScreen"),
        Triple(MiniGame.FETCH, "FetchGameKt", "FetchGameScreen"),
        Triple(MiniGame.DUET, "DuetGameKt", "DuetGameScreen"),
        Triple(MiniGame.PUZZLE, "PuzzleGameKt", "PuzzleGameScreen"),
    )

    /**
     * The save key each game writes under is its own, and it is the one the domain names.
     *
     * `GAME_ID` is read here rather than the literal string, so a game that stops going through
     * [MiniGame] — back to a bare constant of its own — fails even if the two happen to agree
     * today. Agreeing by coincidence is how they drifted the first time.
     */
    @Test
    fun everyGameFilesItsScoresUnderItsOwnMiniGameId() {
        games.forEach { (game, file, _) ->
            assertEquals(
                "$file writes its high score under a key that is not ${game.name}'s",
                game.id,
                NpGameReflect.constant(file, "GAME_ID"),
            )
        }
    }

    /** Two games sharing a key would overwrite each other's records without a word. */
    @Test
    fun noTwoGamesShareASaveKey() {
        val keys = games.map { (_, file, _) -> NpGameReflect.constant(file, "GAME_ID") }
        assertEquals("two games file their scores under the same key: $keys", keys.size, keys.toSet().size)
    }

    /**
     * Every game the domain knows about has a screen to open.
     *
     * The arity check is `>= 2` and not `== 2` on purpose. The Compose compiler plugin appends a
     * `Composer` and a change mask to every composable, so the real build's signature has four
     * parameters and a harness without the plugin has two. Pinning the exact count would make
     * this test pass wherever it was written and fail wherever it matters.
     */
    @Test
    fun everyMiniGameHasAScreenToOpen() {
        games.forEach { (game, file, screen) ->
            val method = NpGameReflect.screenNamed(file, screen)
            assertTrue(
                "$screen is not callable from the navigation graph",
                java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers),
            )
            assertTrue(
                "$screen takes ${method.parameterCount} parameters; a game screen needs at " +
                    "least the view model and the exit callback",
                method.parameterCount >= 2,
            )
            assertEquals(
                "$screen does not take the view model first, so ${game.name} cannot report a score",
                "com.neopal.pet.ui.PetViewModel",
                method.parameterTypes[0].name,
            )
        }
    }

    /**
     * The table above accounts for every game there is.
     *
     * This is the half of the check that catches an eighth game: adding one means adding a
     * [MiniGame] entry, because that is where the save key lives, and this fails until the new
     * game is listed here and therefore covered by the two tests above.
     */
    @Test
    fun theShelfHoldsEveryGameTheDomainNames() {
        assertEquals(
            "a MiniGame exists that no row above covers",
            MiniGame.entries.toSet(),
            games.map { it.first }.toSet(),
        )
        assertEquals("two rows above name the same game", games.size, games.map { it.first }.toSet().size)
    }
}
