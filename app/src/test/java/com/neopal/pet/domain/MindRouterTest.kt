package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who answers what.
 *
 * Every wrong answer in this file fails quietly, which is why the file exists. A route that
 * wrongly says "scripted" gives a duller creature and nobody files it. A route that wrongly
 * reaches the network spends somebody's daily quota on a line that had to arrive instantly. And a
 * route that wrongly runs a model in the autonomous loop produces a warm phone and a flat battery
 * all day, which the player will blame on this app and be right to.
 */
class MindRouterTest {

    /** Every combination of the two things the router is told about the world. */
    private val worlds: List<Pair<Boolean, Boolean>> =
        listOf(false to false, true to false, false to true, true to true)

    private fun routes(ask: MindAsk, playerPresent: Boolean = true): List<MindRoute> =
        worlds.map { (onDevice, remote) -> MindRouter.route(ask, onDevice, remote, playerPresent) }

    // -------------------------------------------------------------------------- the hard limit

    @Test
    fun `the autonomous loop gets neither model, with everything switched on`() {
        // The most important rule in the feature, and the only one that is not a preference.
        for (route in routes(MindAsk.AUTONOMOUS_LOOP)) {
            assertEquals(MindRoute.Scripted, route)
            assertFalse("no model may run while nobody is watching", route.runsAModel)
        }
    }

    @Test
    fun `the limit is a property of the question, not of the caller's flags`() {
        // A caller in the background half cannot route around it by claiming somebody is there.
        assertFalse(MindAsk.AUTONOMOUS_LOOP.mayRunAModel)
        val route = MindRouter.route(
            MindAsk.AUTONOMOUS_LOOP,
            onDeviceReady = true,
            remoteReady = true,
            playerPresent = true,
        )
        assertEquals(MindRoute.Scripted, route)
    }

    @Test
    fun `nothing runs a model when nobody is looking at the screen`() {
        // The second gate, redundant with the first on purpose. A caller that mislabels a
        // background tick as a foreground one is still refused.
        for (ask in MindAsk.entries) {
            for (route in routes(ask, playerPresent = false)) {
                assertFalse("$ask must not run a model with the screen away", route.runsAModel)
            }
        }
    }

    // ------------------------------------------------------------------- instant, or worthless

    @Test
    fun `chatter is answered on the phone even when the remote model is sitting there idle`() {
        val route = MindRouter.route(MindAsk.CHATTER, onDeviceReady = true, remoteReady = true)
        assertEquals(MindRoute.OnDevice, route)
        assertFalse("a tap must not wait on a network", MindAnswerer.REMOTE in route.engines)
    }

    @Test
    fun `chatter with no local model falls to the written brain rather than to the network`() {
        // The tempting wrong answer: a remote route is configured, so use it. That trades an
        // instant line for a two-second one, which is not a better line.
        val route = MindRouter.route(MindAsk.CHATTER, onDeviceReady = false, remoteReady = true)
        assertEquals(MindRoute.Scripted, route)
    }

    @Test
    fun `nothing that has to be instant is ever answered first by the network`() {
        for (route in routes(MindAsk.CHATTER)) {
            assertFalse(MindAnswerer.REMOTE == route.answersNow)
            assertNull("and nothing arrives later to replace it either", route.mayReplace)
        }
    }

    // ----------------------------------------------------------------------------- the talk screen

    @Test
    fun `a talk reply is written locally now and the remote one may replace it`() {
        val route = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = true, remoteReady = true)
        // The state this type exists for. As two booleans it would be indistinguishable from
        // "ask both and take whichever" and from "ask the remote and fall back".
        assertEquals(MindRoute.OnDeviceThenRemote, route)
        assertEquals(MindAnswerer.ON_DEVICE, route.answersNow)
        assertEquals(MindAnswerer.REMOTE, route.mayReplace)
        assertEquals(setOf(MindAnswerer.ON_DEVICE, MindAnswerer.REMOTE), route.engines)
    }

    @Test
    fun `the talk screen is the only place an answer gets replaced`() {
        for (ask in MindAsk.entries) {
            for (route in routes(ask)) {
                if (route.mayReplace != null) {
                    assertEquals("only a talk reply is written twice", MindAsk.TALK_REPLY, ask)
                }
            }
        }
    }

    @Test
    fun `a talk reply with no local model waits for the remote one`() {
        val route = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = false, remoteReady = true)
        assertEquals(MindRoute.Remote(MindAnswerer.SCRIPTED), route)
    }

    @Test
    fun `a talk reply with neither model is still answered`() {
        assertEquals(
            MindRoute.Scripted,
            MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = false, remoteReady = false),
        )
    }

    // ------------------------------------------------- the three that are worth waiting seconds for

    @Test
    fun `a plan prefers the remote model even when the local one is loaded`() {
        // The inverse of the chatter rule, and the reason routing is by question rather than by
        // model: here the slow, better answer is the one worth having.
        val route = MindRouter.route(MindAsk.PLAN, onDeviceReady = true, remoteReady = true)
        assertEquals(MindAnswerer.REMOTE, route.answersNow)
        assertEquals("and the phone covers a timeout", MindAnswerer.ON_DEVICE, route.ifSilent)
    }

    @Test
    fun `deciding and distilling follow the same rule as planning`() {
        for (ask in listOf(MindAsk.CHOICE, MindAsk.DISTIL)) {
            val both = MindRouter.route(ask, onDeviceReady = true, remoteReady = true)
            assertEquals("$ask", MindRoute.Remote(MindAnswerer.ON_DEVICE), both)
            val localOnly = MindRouter.route(ask, onDeviceReady = true, remoteReady = false)
            assertEquals("$ask", MindRoute.OnDevice, localOnly)
        }
    }

    @Test
    fun `a plan with no remote route is made on the phone rather than not at all`() {
        assertEquals(
            MindRoute.OnDevice,
            MindRouter.route(MindAsk.PLAN, onDeviceReady = true, remoteReady = false),
        )
    }

    @Test
    fun `a remote answer with no local model still names who covers a timeout`() {
        val route = MindRouter.route(MindAsk.DISTIL, onDeviceReady = false, remoteReady = true)
        assertEquals(MindRoute.Remote(MindAnswerer.SCRIPTED), route)
    }

    // -------------------------------------------------------------------------- closed properties

    @Test
    fun `every route that reaches the network spends a named budget`() {
        // A request sent under no role is a request with nobody's token allowance behind it. The
        // enum carries the link so a route and a budget cannot drift apart.
        for (ask in MindAsk.entries) {
            for (present in listOf(true, false)) {
                for (route in routes(ask, present)) {
                    if (MindAnswerer.REMOTE in route.engines) {
                        assertNotNull("$ask reached the network with no role", ask.remoteRole)
                    }
                }
            }
        }
    }

    @Test
    fun `an ask with no remote role never reaches the network`() {
        for (ask in MindAsk.entries.filter { it.remoteRole == null }) {
            for (route in routes(ask)) {
                assertFalse("$ask", MindAnswerer.REMOTE in route.engines)
            }
        }
    }

    @Test
    fun `the player is never left with nothing`() {
        // Every route either answers from the written brain, which cannot fail, or names who does
        // when the model it asked comes back empty. A model returning nothing is the ordinary
        // case, not the exceptional one.
        for (ask in MindAsk.entries) {
            for (route in routes(ask)) {
                val covered = route.answersNow == MindAnswerer.SCRIPTED || route.ifSilent != null
                assertTrue("$ask leaves a silence with nothing in it", covered)
            }
        }
    }

    @Test
    fun `a game with nothing configured routes everything to the written brain`() {
        // The state most saves are in, and it has to be the whole game rather than a broken one.
        for (ask in MindAsk.entries) {
            assertEquals(
                "$ask",
                MindRoute.Scripted,
                MindRouter.route(ask, onDeviceReady = false, remoteReady = false),
            )
        }
    }

    @Test
    fun `the scripted brain is not counted as an engine`() {
        // engines is what costs battery or quota. Counting the hand-written brain in it would
        // make runsAModel true for the autonomous loop, which is the one thing it must never be.
        assertTrue(MindRoute.Scripted.engines.isEmpty())
        assertFalse(MindRoute.Scripted.runsAModel)
        assertEquals(setOf(MindAnswerer.ON_DEVICE), MindRoute.OnDevice.engines)
    }
}
