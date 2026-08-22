package com.neopal.pet.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Shape Sorter's half of the game that the player does not play.
 *
 * This is the first game where watching the creature *is* the game, so the interesting object is
 * its judgement: which loose piece it picks up, which hole it talks itself into, and how wrong it
 * is allowed to be. All of that is four small functions — `pickPiece`, `guessSocket`, `advance`
 * and the `confusable` table — and none of them needs a frame or a canvas to run.
 *
 * The deliberation *around* them does need one: the six-state `Ponder` machine that turns a guess
 * into a carried piece lives inside the composable's frame loop and cannot be reached from here.
 * What can be reached is the thing that machine depends on, and the property that matters most
 * is one about it: **a creature can always make progress.** If `guessSocket` could hand back a
 * hole that is already full, or nothing at all while holes stood open, the board would stop
 * emptying and the level would never end — and at low intellect, where the creature is *supposed*
 * to look useless, nobody would be able to tell that from the design.
 */
class PuzzleSolverInvariantsTest {

    /** The board sizes the game deals: three holes on the first board, six on the fourth. */
    private val dealtSizes = 3..6

    /**
     * The band of accuracy the composable can produce, from a newborn with no reading to a
     * full-grown one that has learned to read. Never certainty: the file caps it at 0.94.
     */
    private val dimmest = 0.34f
    private val brightest = 0.94f

    /** The band of walking pace, likewise. Both ends are above zero, which is what matters. */
    private val slowestWalk = 0.32f
    private val quickestWalk = 0.94f

    private val empty = 0

    // ------------------------------------------------------------------------ the board it is given

    /**
     * Every piece has exactly one hole, and every hole exactly one piece.
     *
     * The file leans on this: "a board never repeats a shape, so every loose piece has exactly
     * one hole it belongs in", and it matters more for the creature than for the player, because
     * an unambiguous puzzle is what makes a wrong placement unambiguously a misjudgement rather
     * than bad luck. If a shuffle ever dealt a shape twice, `homeOf` would quietly point both
     * copies at the first hole and one piece would become unplaceable.
     */
    @Test
    fun everyPieceHasExactlyOneHome() {
        forEachBoard { count, board, seed ->
            val sockets = board.sockets
            val pieces = board.pieces
            assertEquals("board of $count (seed $seed) dealt the wrong number of holes", count, sockets.size)
            assertEquals("board of $count (seed $seed) dealt the wrong number of pieces", count, pieces.size)
            assertEquals("a shape appears in two holes (seed $seed): $sockets", count, sockets.toSet().size)
            assertEquals("a shape appears on two pieces (seed $seed): $pieces", count, pieces.toSet().size)
            assertEquals(
                "the tray and the holes are not the same set of shapes (seed $seed)",
                sockets.toSet(), pieces.toSet(),
            )
            val homes = (0 until count).map { board.homeOf(it) }
            assertTrue("a piece has no home on board $count (seed $seed): $homes", homes.all { it >= 0 })
            assertEquals(
                "two pieces share a home on board $count (seed $seed): $homes",
                count, homes.toSet().size,
            )
        }
    }

    /**
     * The holes and the tray are laid out without overlapping, at every size the game deals.
     *
     * Two cells sharing ground is not a cosmetic problem here: the tap targets are derived from
     * these same rectangles, so overlapping cells mean a thumb aimed at one piece picks up
     * another, and the creature's own targets are derived from them too. The five-piece board is
     * the one worth watching — its last row is centred on its own width rather than left-aligned,
     * which is a separate arithmetic path from every other size.
     */
    @Test
    fun theHolesAndTheTrayAreLaidOutWithoutOverlap() {
        dealtSizes.forEach { count ->
            val columns = columnsFor.invoke(null, count) as Int
            assertTrue("a board of $count wants $columns columns, which is more than a thumb likes", columns in 1..3)
            listOf("holes" to (0.08f to 0.56f), "tray" to (0.66f to 0.98f)).forEach { (what, band) ->
                val (top, bottom) = band
                @Suppress("UNCHECKED_CAST")
                val slots = (gridSlots.invoke(null, count, columns, top, bottom) as List<Any>).map { NpSlot(it) }
                assertEquals("$what: $count cells asked for, ${slots.size} laid out", count, slots.size)
                slots.forEachIndexed { i, s ->
                    assertTrue(
                        "$what cell $i of $count sits at ${s.cy} with height ${s.h}, outside $top..$bottom",
                        s.cy - s.h / 2f >= top - 1e-4f && s.cy + s.h / 2f <= bottom + 1e-4f,
                    )
                    assertTrue(
                        "$what cell $i of $count runs off the side of the board (${s.cx}, width ${s.w})",
                        s.cx - s.w / 2f >= -1e-4f && s.cx + s.w / 2f <= 1f + 1e-4f,
                    )
                }
                for (i in slots.indices) for (j in i + 1 until slots.size) {
                    assertFalse(
                        "$what cells $i and $j of $count overlap, so one thumb hits both",
                        overlap(slots[i], slots[j]),
                    )
                }
            }
        }
    }

    // ----------------------------------------------------------------------------- the confusion table

    /**
     * Confusion runs both ways and never points at itself.
     *
     * The table is the difference between a creature that looks nearly right and one that looks
     * broken: wrong guesses are drawn from it first, so "it mixed up the square and the diamond"
     * is a mistake you can forgive. A one-sided entry would make that charm depend on which of
     * the two shapes happened to be in the creature's hand, which is not a thing anybody could
     * have intended - and the arithmetic in `guessSocket` only ever asks one way round.
     */
    @Test
    fun confusionIsMutualAndNeverSelf() {
        shapes.forEach { a ->
            assertFalse("$a is confusable with itself, which would let it guess its own hole twice", ask(a, a))
            shapes.forEach { b ->
                assertEquals(
                    "$a is confusable with $b but not the other way round",
                    ask(a, b), ask(b, a),
                )
            }
        }
        assertTrue(
            "no two shapes are confusable at all, so every wrong guess looks like nonsense",
            shapes.any { a -> shapes.any { b -> ask(a, b) } },
        )
    }

    // --------------------------------------------------------------------------------- the guess

    /**
     * The creature is never sent to a hole that is already full, and never left with nowhere to
     * try while a hole stands open.
     *
     * These are the two halves of "the board keeps emptying". The first is what stops the
     * deliberation ending in the `filled[hole] != EMPTY` branch every time, which reads on screen
     * as a creature that has given up. The second is the real hang: `guessSocket` returning -1
     * with holes still open would leave the solver holding a piece it can never put down.
     *
     * The states swept are not just fresh boards. Half-cleared boards are where this can go
     * wrong, so every subset of filled holes is tried on the small boards and a spread of them on
     * the larger ones.
     */
    @Test
    fun theCreatureAlwaysHasAHoleToTryAndNeverAFullOne() {
        forEachBoard { count, board, seed ->
            forEachFilling(count) { filled ->
                val openHoles = (0 until count).count { filled[it] == empty }
                for (piece in 0 until count) {
                    listOf(dimmest, 0.6f, brightest).forEach { accuracy ->
                        val rng = Random(seed * 131L + piece)
                        repeat(12) {
                            val hole = guessSocket.invoke(null, board.raw, filled, piece, accuracy, rng) as Int
                            if (openHoles == 0) {
                                assertEquals(
                                    "board of $count (seed $seed): every hole is full and the " +
                                        "creature was still sent to $hole",
                                    -1, hole,
                                )
                            } else {
                                assertTrue(
                                    "board of $count (seed $seed) with $openHoles holes open: " +
                                        "the creature was left with nowhere to put piece $piece",
                                    hole >= 0,
                                )
                                assertTrue("hole $hole is not on a board of $count", hole in 0 until count)
                                assertEquals(
                                    "board of $count (seed $seed): the creature was sent to " +
                                        "hole $hole, which is already full",
                                    empty, filled[hole],
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * At the top of its range the creature is right whenever it can be — and no further.
     *
     * `accuracy` is capped below certainty on purpose ("a creature that is always right is a
     * cutscene"), but the arithmetic still has to mean what it says: handed a probability of one,
     * the guess is the home hole every time. Testing it at 1 rather than at the game's 0.94 is
     * the only way to separate "the right answer is preferred" from "the right answer came up".
     */
    @Test
    fun aCertainCreatureNeverMisjudges() {
        forEachBoard { count, board, seed ->
            forEachFilling(count) { filled ->
                for (piece in 0 until count) {
                    val home = board.homeOf(piece)
                    if (filled[home] != empty) continue
                    val rng = Random(seed * 17L + piece)
                    repeat(20) {
                        assertEquals(
                            "board of $count (seed $seed): a certain creature put piece $piece " +
                                "somewhere other than its own hole",
                            home,
                            guessSocket.invoke(null, board.raw, filled, piece, 1f, rng) as Int,
                        )
                    }
                }
            }
        }
    }

    /**
     * A wrong guess is a near miss, whenever a near miss is available.
     *
     * This is the charm of the whole screen stated as arithmetic. Driven at an accuracy of zero,
     * which is below anything the game produces: the point is not that the creature guesses this
     * badly but that *when* it guesses wrongly, the hole it reaches for is one holding a shape
     * genuinely like the one in its hand. Only when no such hole is left may it fall back to
     * anything open.
     */
    @Test
    fun aWrongGuessIsANearMissWhileOneIsLeft() {
        var tested = 0
        forEachBoard { count, board, seed ->
            forEachFilling(count) { filled ->
                for (piece in 0 until count) {
                    val home = board.homeOf(piece)
                    val shape = board.pieces[piece]
                    val nearMisses = (0 until count).filter {
                        it != home && filled[it] == empty && ask(shape, board.sockets[it])
                    }
                    if (nearMisses.isEmpty()) continue
                    tested += 1
                    val rng = Random(seed * 977L + piece)
                    repeat(20) {
                        val hole = guessSocket.invoke(null, board.raw, filled, piece, 0f, rng) as Int
                        assertTrue(
                            "board of $count (seed $seed): the ${shape} went at hole $hole " +
                                "(${board.sockets[hole]}) when $nearMisses were lookalikes",
                            hole in nearMisses,
                        )
                    }
                }
            }
        }
        assertTrue("no board in the sweep offered a lookalike hole to test", tested > 50)
    }

    // ------------------------------------------------------------------------------- the piece

    /** The creature never reaches for a piece that is placed, or for the one in the player's hand. */
    @Test
    fun theCreatureOnlyReachesForALoosePiece() {
        forEachBoard { count, board, seed ->
            forEachFilling(count) { filled ->
                val placed = MutableList(count) { filled[it] != empty }
                for (held in -1 until count) {
                    val free = (0 until count).count { !placed[it] && it != held }
                    val rng = Random(seed * 41L + held)
                    repeat(12) {
                        val piece = pickPiece.invoke(null, board.raw, placed, held, rng) as Int
                        if (free == 0) {
                            assertEquals(
                                "nothing is loose and the creature reached for $piece anyway",
                                -1, piece,
                            )
                        } else {
                            assertTrue("piece $piece is not on a board of $count", piece in 0 until count)
                            assertFalse("the creature reached for placed piece $piece", placed[piece])
                            assertTrue("the creature reached for the piece the keeper is holding", piece != held)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- termination

    /**
     * The dimmest creature the game can produce still empties a board.
     *
     * This is the termination test, and it is the one place in this file that stands in for the
     * frame loop, so what it assumes is worth stating: the composable's `Ponder.LAND` branch fits
     * a piece when the hole it carried it to is that piece's home and otherwise puts it back and
     * starts again. That is the whole rule, and it is restated in three lines below. Everything
     * else - the walking, the pauses, the changes of mind - only decides *when* an attempt
     * happens, never whether it can succeed, so a board that can be cleared by this loop can be
     * cleared by the game.
     *
     * The bound is not a measurement of the game's difficulty. At the lowest accuracy the game
     * can produce, a six-piece board measured out at thirty-one attempts; five hundred is far
     * enough away that only an actual dead end - a piece with nowhere to go, a hole that can
     * never be hit - can reach it.
     */
    @Test
    fun theDimmestCreatureStillClearsEveryBoard() {
        forEachBoard(seeds = 120) { count, board, seed ->
            val filled = MutableList(count) { empty }
            val placed = MutableList(count) { false }
            val rng = Random(seed.toLong())
            var attempts = 0
            while (placed.any { !it } && attempts < 500) {
                attempts += 1
                val piece = pickPiece.invoke(null, board.raw, placed, -1, rng) as Int
                assertTrue("pieces are still loose but none was offered", piece >= 0)
                val hole = guessSocket.invoke(null, board.raw, filled, piece, dimmest, rng) as Int
                assertTrue("holes are still open but none was offered", hole >= 0)
                if (board.homeOf(piece) == hole) {
                    filled[hole] = 2
                    placed[piece] = true
                }
            }
            assertTrue(
                "a board of $count (seed $seed) was not cleared in $attempts attempts - " +
                    "the level cannot end and the creature cannot help",
                placed.all { it },
            )
        }
    }

    /**
     * Whatever the creature is walking towards, it gets there.
     *
     * `advance` is the only thing standing between "it decided where to go" and every state that
     * follows: `Ponder.APPROACH` and `Ponder.CARRY` both wait on it returning true, so a walk
     * that never arrives is a piece never picked up and a board never cleared. The sweep is the
     * full width of the playfield at the game's slowest pace and its shortest frame.
     */
    @Test
    fun theCreatureAlwaysReachesWhatItIsWalkingTowards() {
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0.5f)
        listOf(slowestWalk, quickestWalk).forEach { speed ->
            listOf(1f / 120f, 1f / 60f, 0.05f).forEach { dt ->
                corners.forEach { (fromX, fromY) ->
                    corners.forEach { (toX, toY) ->
                        val solver = solverCtor.newInstance()
                        setX.invoke(solver, fromX)
                        setY.invoke(solver, fromY)
                        setTx.invoke(solver, toX)
                        setTy.invoke(solver, toY)
                        var steps = 0
                        var arrived = false
                        while (steps < 10_000 && !arrived) {
                            arrived = advance.invoke(null, solver, speed, dt) as Boolean
                            steps += 1
                            val x = getX.invoke(solver) as Float
                            val y = getY.invoke(solver) as Float
                            assertTrue(
                                "the walk left the playfield at ($x, $y)",
                                x in min(fromX, toX) - 1e-3f..max(fromX, toX) + 1e-3f &&
                                    y in min(fromY, toY) - 1e-3f..max(fromY, toY) + 1e-3f,
                            )
                        }
                        assertTrue(
                            "at pace $speed and a frame of $dt the creature never got from " +
                                "($fromX, $fromY) to ($toX, $toY)",
                            arrived,
                        )
                        assertEquals(toX.toDouble(), (getX.invoke(solver) as Float).toDouble(), 1e-6)
                        assertEquals(toY.toDouble(), (getY.invoke(solver) as Float).toDouble(), 1e-6)
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------- driving

    private fun ask(a: Any, b: Any): Boolean = confusable.invoke(null, a, b) as Boolean

    private fun overlap(a: NpSlot, b: NpSlot): Boolean =
        abs(a.cx - b.cx) < (a.w + b.w) / 2f - 1e-4f && abs(a.cy - b.cy) < (a.h + b.h) / 2f - 1e-4f

    private fun forEachBoard(seeds: Int = 40, body: (count: Int, board: NpBoard, seed: Int) -> Unit) {
        dealtSizes.forEach { count ->
            for (seed in 0 until seeds) {
                body(count, NpBoard(buildBoard.invoke(null, Random(seed * 7919L + count), count)), seed)
            }
        }
    }

    /**
     * Every pattern of filled holes on the small boards, and a spread of them on the larger ones.
     * A half-cleared board is where a guess has the fewest places left to go and is therefore
     * where it is most likely to have none.
     */
    private fun forEachFilling(count: Int, body: (MutableList<Int>) -> Unit) {
        val masks = if (count <= 4) (0 until (1 shl count)) else (0 until (1 shl count) step 3)
        masks.forEach { mask ->
            body(MutableList(count) { if (mask shr it and 1 == 1) 1 else empty })
        }
    }

    /** A `Slot`, which is a private data class of the game file. */
    private class NpSlot(raw: Any) {
        val cx = slotCx.invoke(raw) as Float
        val cy = slotCy.invoke(raw) as Float
        val w = slotW.invoke(raw) as Float
        val h = slotH.invoke(raw) as Float
    }

    /** A `PuzzleBoard`, likewise. */
    private class NpBoard(val raw: Any) {
        @Suppress("UNCHECKED_CAST")
        val sockets = boardSockets.invoke(raw) as List<Any>

        @Suppress("UNCHECKED_CAST")
        val pieces = boardPieces.invoke(raw) as List<Any>

        fun homeOf(piece: Int): Int = boardHomeOf.invoke(raw, piece) as Int
    }

    private companion object {
        private const val FILE = "PuzzleGameKt"
        private val shapeCls = NpGameReflect.cls("PuzzleShape")
        private val boardCls = NpGameReflect.cls("PuzzleBoard")
        private val solverCls = NpGameReflect.cls("Solver")

        val shapes: List<Any> = shapeCls.enumConstants.toList()

        val confusable = NpGameReflect.fn(FILE, "confusable", shapeCls, shapeCls)
        val buildBoard = NpGameReflect.fn(FILE, "buildBoard", NpGameReflect.RANDOM, NpGameReflect.I)
        val columnsFor = NpGameReflect.fn(FILE, "columnsFor", NpGameReflect.I)
        val gridSlots = NpGameReflect.fn(
            FILE, "gridSlots", NpGameReflect.I, NpGameReflect.I, NpGameReflect.F, NpGameReflect.F,
        )
        val pickPiece = NpGameReflect.fn(
            FILE, "pickPiece", boardCls, NpGameReflect.LIST, NpGameReflect.I, NpGameReflect.RANDOM,
        )
        val guessSocket = NpGameReflect.fn(
            FILE, "guessSocket", boardCls, NpGameReflect.LIST, NpGameReflect.I, NpGameReflect.F,
            NpGameReflect.RANDOM,
        )
        val advance = NpGameReflect.fn(FILE, "advance", solverCls, NpGameReflect.F, NpGameReflect.F)

        val boardSockets = NpGameReflect.member("PuzzleBoard", "getSockets")
        val boardPieces = NpGameReflect.member("PuzzleBoard", "getPieces")
        val boardHomeOf = NpGameReflect.member("PuzzleBoard", "homeOf", NpGameReflect.I)

        val slotCx = NpGameReflect.member("Slot", "getCx")
        val slotCy = NpGameReflect.member("Slot", "getCy")
        val slotW = NpGameReflect.member("Slot", "getW")
        val slotH = NpGameReflect.member("Slot", "getH")

        val solverCtor = solverCls.getDeclaredConstructor().apply { isAccessible = true }
        val setX = NpGameReflect.member("Solver", "setX", NpGameReflect.F)
        val setY = NpGameReflect.member("Solver", "setY", NpGameReflect.F)
        val setTx = NpGameReflect.member("Solver", "setTx", NpGameReflect.F)
        val setTy = NpGameReflect.member("Solver", "setTy", NpGameReflect.F)
        val getX = NpGameReflect.member("Solver", "getX")
        val getY = NpGameReflect.member("Solver", "getY")
    }
}
