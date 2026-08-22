package com.neopal.pet.ui.games

import com.neopal.pet.domain.Genome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hide and Seek's genome read, its room, and the seam where the round can be reasoned about.
 *
 * This game keeps more of itself inside the composable than any other in the package: the five
 * phases, the search order, the hiding choice and the whole cooperative half of the round live in
 * one `LaunchedEffect`, closed over two dozen pieces of composition state. None of that is
 * reachable from a unit test, and pretending otherwise by copying it here would produce a test of
 * the copy. What the file *does* put outside the loop is the part everything else is built on —
 * `HideInstincts`, which is the genome and nothing but the genome, and `buildHideBoard`, which is
 * the room — plus the numbers the round is timed against.
 *
 * So the questions asked here are the ones that survive that boundary:
 *
 *  - **Can the round do anything at all?** A dwell of zero would make the seeker thrash; a walk
 *    of zero would leave it standing still until the cap called the round. Either would look,
 *    from outside, exactly like a creature being slow.
 *  - **Do two different lineages play differently?** That is the entire premise — "the only way
 *    to change how it plays is to breed for it" — and it is an ordering, not a number, so it
 *    survives balance changes.
 *  - **Is the round long enough to contain the game it is timing?** A cap that expires before the
 *    seeker can reach one prop turns every hiding round into a free win, silently.
 *
 * What is *not* covered, and is the reason this file is shorter than the others, is listed at the
 * end of the report: the search order, the doubling back, the hunch and the hiding choice all
 * need the frame loop.
 */
class HideAndSeekInstinctsTest {

    /** Every corner of the four genes this game reads, plus the middle and the quarters. */
    private fun eachTemperament(body: (Genome) -> Unit) {
        val values = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        values.forEach { vigor ->
            values.forEach { curiosity ->
                values.forEach { wit ->
                    values.forEach { sociability ->
                        body(
                            Genome(
                                vigor = vigor,
                                curiosity = curiosity,
                                wit = wit,
                                sociability = sociability,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------- the round can proceed

    /**
     * No genome produces a creature that cannot play.
     *
     * Each of these is a divisor or a countdown inside the round: the walk divides a distance,
     * the dwell counts down while it peers into a prop, the tell period sets when it next gives
     * itself away. A zero or a negative in any of them is a seeker that never arrives, a peer
     * that never ends, or a rustle every frame — and none of the three would raise anything. They
     * would simply be a round in which nothing happened.
     *
     * The chances are bounded because they are compared against `nextFloat`, which is `[0, 1)`: a
     * chance at or above one is a certainty dressed up as a probability.
     */
    @Test
    fun noGenomeMakesACreatureThatCannotPlay() {
        eachTemperament { genome ->
            val it = NpInstincts(genome)
            val who = "vigor ${genome.vigor}, curiosity ${genome.curiosity}, " +
                "wit ${genome.wit}, sociability ${genome.sociability}"
            assertTrue("$who: walks at ${it.walkSpeed}, so it never reaches a prop", it.walkSpeed > 0f)
            assertTrue("$who: dwells for ${it.dwell}s, so its search never advances", it.dwell > 0f)
            assertTrue("$who: rustles every ${it.tellPeriod}s", it.tellPeriod > 0f)
            assertTrue("$who: repeats with certainty (${it.repeatChance})", it.repeatChance in 0f..0.999f)
            assertTrue("$who: hunch is out of range (${it.hunch})", it.hunch in 0f..1f)
            assertTrue("$who: oddness is out of range (${it.oddness})", it.oddness in 0f..1f)
            assertTrue("$who: shyness is out of range (${it.shyness})", it.shyness in 0f..1f)
            assertTrue("$who: memory is out of range (${it.memory})", it.memory in 0f..1f)
        }
    }

    /**
     * Two lineages play this differently, and in the direction the read-out promises.
     *
     * Orderings rather than values, so that retuning any of these coefficients leaves the test
     * alone and only reversing one — or dropping a gene out of a formula, which is the way this
     * kind of thing usually dies — fails it. Each pair below is also a sentence the game says out
     * loud to the player in `hideInstinctSentence`, so a reversal here would make the screen lie.
     */
    @Test
    fun theGenesShowInTheWayItPlays() {
        val calm = NpInstincts(Genome(vigor = 0f))
        val lively = NpInstincts(Genome(vigor = 1f))
        assertTrue(
            "a vigorous creature (${lively.walkSpeed}) does not cross the room faster than a " +
                "listless one (${calm.walkSpeed})",
            lively.walkSpeed > calm.walkSpeed,
        )
        assertTrue(
            "a vigorous creature holds still longer (${lively.tellPeriod}s) than a calm one " +
                "(${calm.tellPeriod}s) - the fidget is the wrong way round",
            lively.tellPeriod < calm.tellPeriod,
        )

        assertTrue(
            "a curious creature dwells longer over each prop than an incurious one",
            NpInstincts(Genome(curiosity = 1f)).dwell < NpInstincts(Genome(curiosity = 0f)).dwell,
        )
        assertTrue(
            "curiosity does not make the search order any odder",
            NpInstincts(Genome(curiosity = 1f)).oddness > NpInstincts(Genome(curiosity = 0f)).oddness,
        )

        assertTrue(
            "wit does not stop it looking twice in the same place",
            NpInstincts(Genome(wit = 1f)).repeatChance < NpInstincts(Genome(wit = 0f)).repeatChance,
        )
        assertTrue(
            "wit does not help it avoid the place it hid in last time",
            NpInstincts(Genome(wit = 1f)).memory > NpInstincts(Genome(wit = 0f)).memory,
        )

        assertTrue(
            "a sociable creature does not drift towards you",
            NpInstincts(Genome(sociability = 1f)).hunch > NpInstincts(Genome(sociability = 0f)).hunch,
        )
        assertTrue(
            "a sociable creature is the shy one",
            NpInstincts(Genome(sociability = 1f)).shyness < NpInstincts(Genome(sociability = 0f)).shyness,
        )
    }

    // ------------------------------------------------------------------------------------ the room

    /**
     * The room is always six distinct props, always the same ladder of promise, never the same
     * way round twice.
     *
     * The ladder is shuffled rather than randomised on purpose: every run has one obvious hiding
     * place and one hopeless one, so a player learns the creature instead of learning the room.
     * If a shuffle ever dealt two props the same promise, or dealt the ladder in a fixed order,
     * the taste the creature's shyness expresses would stop being a preference over anything.
     */
    @Test
    fun theRoomIsAlwaysSixDistinctPropsOnTheSameLadder() {
        val ladder = listOf(0.95f, 0.78f, 0.60f, 0.42f, 0.24f, 0.06f)
        val arrangements = mutableSetOf<List<Float>>()
        for (seed in 0 until 200) {
            @Suppress("UNCHECKED_CAST")
            val board = (buildHideBoard.invoke(null, Random(seed.toLong())) as List<Any>)
                .map { NpSpot(it) }
            assertEquals("the room is not six props (seed $seed)", spotCount, board.size)
            assertEquals(
                "a prop appears twice in the room (seed $seed): ${board.map { it.prop }}",
                spotCount, board.map { it.prop }.toSet().size,
            )
            val promise = board.map { it.obviousness }
            assertEquals(
                "the ladder of promise was not dealt whole (seed $seed): $promise",
                ladder.sorted(), promise.sorted(),
            )
            assertEquals(
                "two props are equally promising (seed $seed): $promise",
                spotCount, promise.toSet().size,
            )
            board.forEach {
                assertTrue("a prop sits off the floor plan at (${it.x}, ${it.y})", it.x in 0f..1f && it.y in 0f..1f)
            }
            arrangements += promise
        }
        assertTrue(
            "every run laid the room out the same way, so the room can be learned instead of " +
                "the creature",
            arrangements.size > 1,
        )
    }

    // ------------------------------------------------------------------------------- the clock

    /**
     * The round is long enough to contain a search.
     *
     * The seeking half of a round ends either when the creature finds you or when `ROUND_CAP`
     * expires, and there is a degenerate version of this game where the cap always wins: if the
     * slowest creature cannot cross the room and peer into a single prop before time is up, every
     * hiding round is a free four hundred points and the creature's search is decoration. So this
     * takes the worst case the room and the genome can jointly produce — the longest walk any
     * prop is from where the creature starts, at the slowest possible pace, plus the longest
     * possible dwell — and insists the cap is bigger than it.
     *
     * The same cap governs the half where the player searches, and there the question is whether
     * the creature ever gives itself away at all: the first rustle comes at six tenths of a tell
     * period, and a creature that never rustled would leave a player with nothing but taps.
     */
    @Test
    fun aRoundIsLongEnoughForTheSearchItTimes() {
        val slowest = NpInstincts(Genome(vigor = 0f, curiosity = 0f))
        val stillest = NpInstincts(Genome(vigor = 0f))

        var longestWalk = 0f
        for (seed in 0 until 50) {
            @Suppress("UNCHECKED_CAST")
            val board = (buildHideBoard.invoke(null, Random(seed.toLong())) as List<Any>).map { NpSpot(it) }
            board.forEach { spot ->
                // Where the seeker stands at the start of a round, and where it stops in front
                // of a prop: the round resets it to the middle of the floor.
                val dx = spot.x - 0.5f
                val dy = (spot.y + 0.15f) - floorY
                val walk = sqrt(dx * dx + dy * dy)
                if (walk > longestWalk) longestWalk = walk
            }
        }

        val worstCheck = longestWalk / slowest.walkSpeed + slowest.dwell
        assertTrue(
            "the slowest creature needs ${worstCheck}s to reach and search the furthest prop, " +
                "but a round is only ${roundCap}s - every hiding round is a free win",
            worstCheck < roundCap,
        )
        assertTrue(
            "the stillest creature waits ${stillest.tellPeriod * 0.6f}s before its first rustle, " +
                "which is past the ${roundCap}s round - the player never gets a tell",
            stillest.tellPeriod * 0.6f < roundCap,
        )
        assertTrue("a round of ${roundCap}s cannot contain anything", roundCap > 0f)
        assertTrue("the breather between rounds never ends", roundBreak > 0f)
        assertTrue("dithering over a hiding place is never called", chooseCap > 0f)
        assertTrue("the count-in never finishes", countIn > 0f)
        assertTrue("the game is not made of rounds", rounds > 0)
    }

    // -------------------------------------------------------------------------------- the hints

    /**
     * A hint never points the wrong way.
     *
     * It is the only thing a searching player gets, and it is directional rather than
     * warm-or-cold because six props on a phone screen would otherwise be solved on the second
     * tap. A sign slip would be worse than no hint at all: the player would systematically search
     * away from the creature and read it as the game cheating.
     */
    @Test
    fun aHintNeverPointsTheWrongWay() {
        @Suppress("UNCHECKED_CAST")
        val board = (buildHideBoard.invoke(null, Random(4L)) as List<Any>)
        for (from in board) {
            for (to in board) {
                val hint = hideHintFor.invoke(null, from, to) as String
                val a = NpSpot(from)
                val b = NpSpot(to)
                val where = "from (${a.x}, ${a.y}) to (${b.x}, ${b.y}): \"$hint\""
                assertTrue("$where says nothing at all", hint.isNotBlank())
                if (hint.contains("to the right")) {
                    assertTrue("$where, but it is to the left", b.x > a.x)
                }
                if (hint.contains("to the left")) {
                    assertTrue("$where, but it is to the right", b.x < a.x)
                }
                if (hint.contains("nearer the front")) {
                    assertTrue("$where, but it is further back", b.y > a.y)
                }
                if (hint.contains("further back")) {
                    assertTrue("$where, but it is nearer the front", b.y < a.y)
                }
                if (from === to) {
                    assertTrue("$where, about the prop it was just opened", hint.contains("Close"))
                }
            }
        }
    }

    /**
     * The time a round took is readable, and is the time the round took.
     *
     * `hideOneDecimal` exists to avoid dragging a formatter in, which means it is arithmetic
     * rather than formatting, and arithmetic can be wrong. It goes into the sentence a player
     * reads at the end of every round, so a value that lost its magnitude would be the only
     * record of how long they held out.
     */
    @Test
    fun theTimeARoundTookIsReadable() {
        var value = 0f
        while (value <= roundCap + 1f) {
            val shown = hideOneDecimal.invoke(null, value) as String
            val parsed = shown.toFloatOrNull()
            assertTrue("\"$shown\" is not a number a player can read", parsed != null)
            assertTrue(
                "$value was shown as \"$shown\"",
                kotlin.math.abs(parsed!! - value) <= 0.05f + 1e-4f,
            )
            assertTrue(
                "\"$shown\" carries more than one decimal place",
                !shown.contains('.') || shown.substringAfter('.').length <= 1,
            )
            value += 0.037f
        }
    }

    // ----------------------------------------------------------------------------------- driving

    /** A `HideInstincts`, which is a private top-level class of the game file. */
    private class NpInstincts(genome: Genome) {
        private val raw = instinctsCtor.newInstance(genome)
        val walkSpeed = walkSpeedOf.invoke(raw) as Float
        val dwell = dwellOf.invoke(raw) as Float
        val oddness = oddnessOf.invoke(raw) as Float
        val repeatChance = repeatChanceOf.invoke(raw) as Float
        val hunch = hunchOf.invoke(raw) as Float
        val shyness = shynessOf.invoke(raw) as Float
        val tellPeriod = tellPeriodOf.invoke(raw) as Float
        val memory = memoryOf.invoke(raw) as Float
    }

    /** A `HideSpot`, likewise. */
    private class NpSpot(raw: Any) {
        val prop: Any = spotProp.invoke(raw)
        val x = spotX.invoke(raw) as Float
        val y = spotY.invoke(raw) as Float
        val obviousness = spotObviousness.invoke(raw) as Float
    }

    private companion object {
        private const val FILE = "HideAndSeekGameKt"

        val buildHideBoard = NpGameReflect.fn(FILE, "buildHideBoard", NpGameReflect.RANDOM)
        val hideHintFor = NpGameReflect.fn(
            FILE, "hideHintFor", NpGameReflect.cls("HideSpot"), NpGameReflect.cls("HideSpot"),
        )
        val hideOneDecimal = NpGameReflect.fn(FILE, "hideOneDecimal", NpGameReflect.F)

        val instinctsCtor = NpGameReflect.cls("HideInstincts")
            .getDeclaredConstructor(Genome::class.java).apply { isAccessible = true }
        val walkSpeedOf = NpGameReflect.member("HideInstincts", "getWalkSpeed")
        val dwellOf = NpGameReflect.member("HideInstincts", "getDwell")
        val oddnessOf = NpGameReflect.member("HideInstincts", "getOddness")
        val repeatChanceOf = NpGameReflect.member("HideInstincts", "getRepeatChance")
        val hunchOf = NpGameReflect.member("HideInstincts", "getHunch")
        val shynessOf = NpGameReflect.member("HideInstincts", "getShyness")
        val tellPeriodOf = NpGameReflect.member("HideInstincts", "getTellPeriod")
        val memoryOf = NpGameReflect.member("HideInstincts", "getMemory")

        val spotProp = NpGameReflect.member("HideSpot", "getProp")
        val spotX = NpGameReflect.member("HideSpot", "getX")
        val spotY = NpGameReflect.member("HideSpot", "getY")
        val spotObviousness = NpGameReflect.member("HideSpot", "getObviousness")

        val spotCount = NpGameReflect.constant(FILE, "SPOT_COUNT") as Int
        val rounds = NpGameReflect.constant(FILE, "ROUNDS") as Int
        val roundCap = NpGameReflect.constant(FILE, "ROUND_CAP") as Float
        val roundBreak = NpGameReflect.constant(FILE, "ROUND_BREAK") as Float
        val chooseCap = NpGameReflect.constant(FILE, "CHOOSE_CAP") as Float
        val countIn = NpGameReflect.constant(FILE, "COUNT_IN") as Float
        val floorY = NpGameReflect.constant(FILE, "FLOOR_Y") as Float
    }
}
