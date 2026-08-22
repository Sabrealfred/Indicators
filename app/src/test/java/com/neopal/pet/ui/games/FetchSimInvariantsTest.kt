package com.neopal.pet.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fetch, driven by its own integrator rather than by a frame loop.
 *
 * `FetchSim` is the one game in this package whose whole model is a plain class: the composable
 * owns a clock and a score, and everything else — the ball, the legs, the creature's model of
 * your arm — lives here and is advanced by a `step` the test can call as easily as a frame can.
 * So this file asks the questions that matter about a game nobody has ever played:
 *
 *  - **Does a fetch always come back?** A creature that runs to a spot the ball is not at, and
 *    stays there, has hung the game. The file names that failure in a comment on `belief`; this
 *    is the check that the fix works, over every genome and every throw the dial can produce.
 *  - **Does the arithmetic stay on the field?** A ball or a creature outside the touchlines is
 *    invisible, and invisible is indistinguishable from broken.
 *  - **Is the read honest?** The file claims a player who throws at random is never "read". That
 *    is a claim about a running mean, and it is checkable without any physics at all.
 *
 * What is deliberately *not* here is a score. The points a fetch pays are a balance decision and
 * are meant to be edited; a test that pinned them would be deleted the first time somebody tuned
 * the game, and would take these invariants with it.
 */
class FetchSimInvariantsTest {

    // ------------------------------------------------------------------ the fixed part of the game

    /** The physics timestep the composable also uses. Nothing here integrates a raw frame. */
    private val step = 1f / 120f

    /** The dial's limits, from the file. A player cannot ask for a throw outside these. */
    private val minAngle = 12f
    private val maxAngle = 80f
    private val minPower = 0.45f
    private val maxPower = 1.15f
    private val leftEdge = 0.05f
    private val rightEdge = 0.955f
    private val launchX = 0.12f

    // ---------------------------------------------------------------------------- termination

    /**
     * Every throw comes back, from every creature.
     *
     * The sweep is the whole input space rather than a sample of it: five values of vigour by
     * five of limb length — the two genes the composable turns into pace — by the full arc of
     * the aiming dial. Each cell runs the fetch to the frame the ball is delivered, and fails if
     * it never is.
     *
     * The bound is generous on purpose. The measured worst case is a stubby, listless creature
     * taking about eleven and a half seconds to walk down a long throw and trudge back; twenty
     * five is nowhere near it, and is still a third of the session, so anything that genuinely
     * stalls is caught while a balance change to pace is not.
     */
    @Test
    fun everyThrowFromEveryCreatureIsBroughtBack() {
        forEachCreatureAndThrow { vigor, limbs, angle, power ->
            val run = NpFetch(vigor, limbs)
            val seconds = run.fetch(angle, power, limitSeconds = 25f)
            assertTrue(
                "vigor $vigor, limbs $limbs, ${angle}deg at power $power: the ball was never " +
                    "brought back - the creature is stranded and the game cannot be finished",
                seconds > 0f,
            )
        }
    }

    /**
     * The failure the file's own comment describes, reproduced deliberately.
     *
     * `belief` is how much of its own guess the creature is still acting on. Without the release
     * that comment describes, a creature that has convinced itself the ball lands at one end
     * stands there for ever while the ball lies at the other, and the fetch never ends. So: give
     * it a confident and completely wrong model, throw the other way, and insist it still gets
     * home. This is the one termination test with a named villain.
     */
    @Test
    fun aConfidentlyWrongHunchStillEndsTheFetch() {
        listOf(leftEdge + 0.01f, rightEdge - 0.01f).forEach { spot ->
            forEachCreatureAndThrow(step = 2) { vigor, limbs, angle, power ->
                val run = NpFetch(vigor, limbs)
                run.launch(angle, power)
                // The hunch is overwritten after the throw rather than learned before it, because
                // a creature that has learned something cannot hold a *wrong* belief with
                // confidence: the blend in `release` drags the guess toward the truth in
                // proportion to how sure it is. This is the state the comment on `belief`
                // describes, put there directly.
                run.insistOn(spot, belief = 1f)
                val seconds = run.finish(limitSeconds = 25f)
                assertTrue(
                    "vigor $vigor, limbs $limbs, ${angle}deg at power $power while convinced " +
                        "the ball is at $spot: the creature never let go of its guess",
                    seconds > 0f,
                )
            }
        }
    }

    // -------------------------------------------------------------------------- staying on the field

    /**
     * Nothing leaves the pitch, and nothing becomes a number that cannot be drawn.
     *
     * Checked every single physics tick rather than at the end, because a ball that leaves the
     * field and comes back is exactly as broken as one that stays out, and only one of those
     * shows up in a final position.
     */
    @Test
    fun theBallAndTheCreatureStayOnTheField() {
        forEachCreatureAndThrow(step = 2) { vigor, limbs, angle, power ->
            val run = NpFetch(vigor, limbs)
            val where = "vigor $vigor, limbs $limbs, ${angle}deg at power $power"
            run.fetch(angle, power, limitSeconds = 25f) {
                assertTrue("$where: ball x ${run.ballX} is off the field", run.ballX in leftEdge..rightEdge)
                assertTrue("$where: creature x ${run.petX} is off the field", run.petX in leftEdge..rightEdge)
                assertTrue("$where: ball height ${run.ballH} is below the ground", run.ballH >= 0f)
                assertTrue("$where: the simulation produced a value that is not a number", run.finite)
            }
        }
    }

    // ---------------------------------------------------------------------------- the two integrators

    /**
     * The creature's foreknowledge is not an estimate.
     *
     * `restingPlaceOf` runs the same integrator at the same timestep as the live ball, and the
     * file says so plainly: "the answer is the answer - not an estimate the visible ball can then
     * contradict". It matters because the read, the hunch marker drawn on the ground and the
     * distance bonus are all scored against that number rather than against where the ball ends
     * up. If the two loops ever drift apart, the game starts paying for a throw that did not
     * happen, and nothing on screen would show it.
     *
     * The creature is pinned still for this (a pace of zero leaves `drive` with nothing to do),
     * and any throw it manages to snatch out of the air anyway is skipped - a caught ball has no
     * resting place to compare.
     */
    @Test
    fun whereTheBallStopsIsWhereTheThrowSaidItWould() {
        var compared = 0
        forEachThrow { angle, power ->
            val run = NpFetch(vigor = 0f, limbs = 0f, pace = 0f)
            run.launch(angle, power)
            var ticks = 0
            while (ticks < 60_000 && !run.ballResting) {
                run.tick()
                ticks += 1
            }
            if (run.caught) return@forEachThrow
            compared += 1
            assertEquals(
                "${angle}deg at power $power: the ball came to rest at ${run.ballX} but the " +
                    "throw was scored as landing at ${run.predicted}",
                run.predicted.toDouble(),
                run.ballX.toDouble(),
                1e-6,
            )
        }
        assertTrue("no throw was left uncaught to compare", compared > 20)
    }

    // -------------------------------------------------------------------------------- the genes

    /**
     * Vigour is worth having.
     *
     * Not a threshold — a threshold is a balance number — but an ordering: on the same throw the
     * liveliest creature is never behind the most listless one, and on any throw that actually
     * travels it is ahead. That is the whole reason the athletic genes are read at all, and it is
     * the property a rebalance is allowed to move without breaking anything.
     *
     * The "actually travels" clause is not a get-out. A near-vertical throw at the bottom of the
     * power dial drops back into the creature's own reach and is snatched on the way down by
     * anything with legs at all; there is no ground to cover, so there is nothing for the genes
     * to be worth. Everything past a stride from the hand is held to the strict ordering.
     */
    @Test
    fun aVigorousCreatureBeatsAListlessOneToTheBall() {
        var strict = 0
        forEachThrow(step = 2) { angle, power ->
            val slow = NpFetch(vigor = 0f, limbs = 0f)
            val quick = NpFetch(vigor = 1f, limbs = 1f)
            val listless = slow.fetch(angle, power, limitSeconds = 25f)
            val lively = quick.fetch(angle, power, limitSeconds = 25f)
            val where = "${angle}deg at power $power"
            assertTrue("$where: neither creature finished", listless > 0f && lively > 0f)
            assertTrue(
                "$where: the lively creature took ${lively}s and the listless one ${listless}s",
                lively <= listless,
            )
            if (slow.predicted > launchX + 0.15f) {
                strict += 1
                assertTrue(
                    "$where: the ball travelled to ${slow.predicted} and both creatures took the " +
                        "same time - the athletic genes are decoration",
                    lively < listless,
                )
            }
        }
        assertTrue("no throw in the sweep travelled far enough to test the legs", strict > 10)
    }

    // ---------------------------------------------------------------------------------- the read

    /** A creature that has seen nothing claims to know nothing. */
    @Test
    fun aCreatureWithNoThrowsToGoOnHasNoRead() {
        assertEquals(0.0, NpFetch(0.5f, 0.5f).confidence.toDouble(), 0.0)
    }

    /**
     * A repeated throw is learned, and an erratic one is not.
     *
     * Both halves are the same three lines of exponential mean, driven directly: `learn` reads
     * the throw that just finished and nothing else, so the physics can stay out of it.
     *
     * The second half is the one that would not have occurred to anybody to write. The file
     * scores the creature on its *habit's* error rather than on the blended aim it actually ran
     * to, and the comment explains why: score the blend and the read feeds on itself, because a
     * confident creature aims well by definition. A player throwing at random would come out
     * "read" within four throws. This asserts the honest answer instead — that an arm with no
     * habit in it is never read, however long the creature watches.
     */
    @Test
    fun onlyAHabitIsEverLearned() {
        val steady = NpFetch(0.5f, 0.5f)
        repeat(8) { steady.observe(0.62f) }
        assertTrue(
            "eight identical throws and the creature still has no read (${steady.confidence})",
            steady.confidence > 0.7f,
        )

        val erratic = NpFetch(0.5f, 0.5f)
        repeat(40) { i -> erratic.observe(if (i % 2 == 0) 0.20f else 0.90f) }
        assertTrue(
            "a player throwing to opposite ends every time was read to ${erratic.confidence}",
            erratic.confidence < 0.2f,
        )
    }

    /**
     * However well it has you worked out, it still has to run.
     *
     * The head start the creature takes while you wind up is hedged twice over — by the read, and
     * by a ceiling — so that it can never be standing on the spot before the ball is thrown. Take
     * away that ceiling and the athletic genes stop mattering: a well-read player's every throw
     * is caught on the spot by a creature that never moved. Here the read is pinned at its
     * maximum, the creature is left winding up for far longer than a real player would take, and
     * it must still be short of the landing spot when the ball leaves the hand.
     */
    @Test
    fun aReadCreatureStillHasToRunTheLastOfIt() {
        val landing = 0.80f
        val run = NpFetch(vigor = 1f, limbs = 1f)
        run.believe(landing = landing, samples = 200, error = 0f)
        repeat(1_800) { run.tick(winding = true) } // fifteen seconds of winding up
        assertTrue("the read never reached certainty (${run.confidence})", run.confidence > 0.9f)
        assertTrue(
            "with a perfect read the creature crept to ${run.petX}, which is at or past the " +
                "landing spot $landing - there is no run left to make",
            run.petX < landing - 0.05f,
        )
        assertTrue("the creature drifted behind the throwing hand", run.petX >= launchX - 1e-4f)
    }

    // ----------------------------------------------------------------------------------- driving

    private fun forEachThrow(step: Int = 1, body: (angle: Float, power: Float) -> Unit) {
        var a = 0
        while (a <= 8) {
            var p = 0
            while (p <= 6) {
                body(minAngle + a * (maxAngle - minAngle) / 8f, minPower + p * (maxPower - minPower) / 6f)
                p += step
            }
            a += step
        }
    }

    private fun forEachCreatureAndThrow(
        step: Int = 1,
        body: (vigor: Float, limbs: Float, angle: Float, power: Float) -> Unit,
    ) {
        var v = 0
        while (v <= 4) {
            var l = 0
            while (l <= 4) {
                forEachThrow(step) { angle, power -> body(v / 4f, l / 4f, angle, power) }
                l += step
            }
            v += step
        }
    }

    /**
     * The real `FetchSim`, wired up the way `FetchGameScreen` wires it.
     *
     * The two speeds and the stride are the composable's own expressions of the genome, copied
     * here because they are the only part of the game's arithmetic that lives in the screen
     * rather than in the simulation. If they move into `FetchSim` one day, this shrinks; until
     * then a test that invented its own pace would be testing a creature that does not exist.
     */
    private inner class NpFetch(vigor: Float, limbs: Float, pace: Float? = null) {
        private val sim = simCtor.newInstance(0)
        private val run = pace ?: (0.20f + vigor * 0.44f) * (0.72f + limbs * 0.72f)
        private val carry = run * 0.92f
        private val stride = 0.055f + limbs * 0.075f

        val ballX: Float get() = getBallX.invoke(sim) as Float
        val ballH: Float get() = getBallH.invoke(sim) as Float
        val petX: Float get() = getPetX.invoke(sim) as Float
        val predicted: Float get() = getPredicted.invoke(sim) as Float
        val confidence: Float get() = getConfidence.invoke(sim) as Float
        val ballResting: Boolean get() = getBallResting.invoke(sim) as Boolean
        val caught: Boolean get() = !(isChasing.invoke(sim) as Boolean)

        /** Nothing in the simulation may become a NaN or an infinity; every one of them draws. */
        val finite: Boolean
            get() = listOf(ballX, ballH, petX, predicted, getPetV.invoke(sim) as Float)
                .all { it.isFinite() }

        fun tick(winding: Boolean = false) {
            simStep.invoke(sim, step, run, carry, stride, winding)
        }

        /** Queues a throw and spends the tick that releases it. */
        fun launch(angle: Float, power: Float) {
            setPendingAngle.invoke(sim, angle)
            setPendingPower.invoke(sim, power)
            setPendingThrow.invoke(sim, true)
            tick()
        }

        /**
         * Runs one whole fetch and returns how long it took, or -1 if it never finished.
         * [onTick] is checked on every physics step, which is the only place a transient
         * excursion off the field can be seen.
         */
        fun fetch(angle: Float, power: Float, limitSeconds: Float, onTick: () -> Unit = {}): Float {
            launch(angle, power)
            onTick()
            return finish(limitSeconds, onTick)
        }

        /** The rest of a fetch already in flight. */
        fun finish(limitSeconds: Float, onTick: () -> Unit = {}): Float {
            var seconds = step
            while (seconds < limitSeconds) {
                tick()
                seconds += step
                onTick()
                if (getEventDelivered.invoke(sim) as Boolean) return seconds
            }
            return -1f
        }

        /** Plants a belief about where the loose ball is, and full commitment to it. */
        fun insistOn(spot: Float, belief: Float) {
            setAnticipated.invoke(sim, spot)
            setBelief.invoke(sim, belief)
        }

        /** Hands the creature a finished model of your arm without making it watch you throw. */
        fun believe(landing: Float, samples: Int, error: Float) {
            setMeanLanding.invoke(sim, landing)
            setMeanError.invoke(sim, error)
            setSamples.invoke(sim, samples)
        }

        /** One throw's worth of evidence, folded in exactly as the frame loop folds it. */
        fun observe(restingAt: Float) {
            setPredicted.invoke(sim, restingAt)
            simLearn.invoke(sim)
        }
    }

    private companion object {
        private const val SIM = "FetchSim"
        private val simCtor = NpGameReflect.cls(SIM)
            .getDeclaredConstructor(NpGameReflect.I).apply { isAccessible = true }
        private val simStep = NpGameReflect.member(
            SIM, "step",
            NpGameReflect.F, NpGameReflect.F, NpGameReflect.F, NpGameReflect.F, NpGameReflect.B,
        )
        private val simLearn = NpGameReflect.member(SIM, "learn")
        private val isChasing = NpGameReflect.member(SIM, "isChasing")
        private val getBallX = NpGameReflect.member(SIM, "getBallX")
        private val getBallH = NpGameReflect.member(SIM, "getBallH")
        private val getBallResting = NpGameReflect.member(SIM, "getBallResting")
        private val getPetX = NpGameReflect.member(SIM, "getPetX")
        private val getPetV = NpGameReflect.member(SIM, "getPetV")
        private val getPredicted = NpGameReflect.member(SIM, "getPredicted")
        private val getConfidence = NpGameReflect.member(SIM, "getConfidence")
        private val getEventDelivered = NpGameReflect.member(SIM, "getEventDelivered")
        private val setPendingAngle = NpGameReflect.member(SIM, "setPendingAngle", NpGameReflect.F)
        private val setPendingPower = NpGameReflect.member(SIM, "setPendingPower", NpGameReflect.F)
        private val setPendingThrow = NpGameReflect.member(SIM, "setPendingThrow", NpGameReflect.B)
        private val setPredicted = NpGameReflect.member(SIM, "setPredicted", NpGameReflect.F)
        private val setMeanLanding = NpGameReflect.member(SIM, "setMeanLanding", NpGameReflect.F)
        private val setMeanError = NpGameReflect.member(SIM, "setMeanError", NpGameReflect.F)
        private val setSamples = NpGameReflect.member(SIM, "setSamples", NpGameReflect.I)
        private val setAnticipated = NpGameReflect.member(SIM, "setAnticipated", NpGameReflect.F)
        private val setBelief = NpGameReflect.member(SIM, "setBelief", NpGameReflect.F)
    }
}
