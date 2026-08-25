package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two booleans the router is asked, and what is done with the answer it gives.
 *
 * Both of these fail silently when they are wrong, which is the reason they are not left inline
 * in a view model where nothing can look at them. A readiness rule that is too strict switches a
 * feature off and generates no complaints; one that is too loose spends somebody's quota on a job
 * they turned off in settings.
 */
class MindWiringTest {

    private val configured = MindConfig(enabled = true, apiKey = "k")

    // ------------------------------------------------------------------ the remote's own switches

    @Test
    fun `each job reads its own switch`() {
        assertTrue(MindWiring.allows(configured.copy(conversation = true), MindRole.CONVERSE))
        assertFalse(MindWiring.allows(configured.copy(conversation = false), MindRole.CONVERSE))
        assertFalse(MindWiring.allows(configured.copy(decidesActions = false), MindRole.DECIDE))
        assertFalse(MindWiring.allows(configured.copy(makesPlans = false), MindRole.PLAN))
        assertFalse(MindWiring.allows(configured.copy(lineageLessons = false), MindRole.DISTIL))
    }

    @Test
    fun `turning talking off leaves deciding on`() {
        val talkless = configured.copy(conversation = false)
        assertFalse(MindWiring.allows(talkless, MindRole.CONVERSE))
        assertTrue(MindWiring.allows(talkless, MindRole.DECIDE))
    }

    @Test
    fun `a talk reply asks the remote only when everything says so`() {
        assertTrue(MindWiring.remoteReady(configured, MindAsk.TALK_REPLY, clientReady = true))
        assertFalse(MindWiring.remoteReady(configured, MindAsk.TALK_REPLY, clientReady = false))
        assertFalse(MindWiring.remoteReady(configured.copy(enabled = false), MindAsk.TALK_REPLY, true))
        assertFalse(MindWiring.remoteReady(MindConfig(enabled = true), MindAsk.TALK_REPLY, true))
        assertFalse(MindWiring.remoteReady(configured.copy(conversation = false), MindAsk.TALK_REPLY, true))
    }

    @Test
    fun `chatter never reaches the network, however well configured it is`() {
        // The row of the table people argue with. A remark on the spot is worth having because it
        // is there when the finger lands; a better one two seconds later is a broken toy.
        assertFalse(MindWiring.remoteReady(configured, MindAsk.CHATTER, clientReady = true))
    }

    @Test
    fun `the autonomous loop never reaches the network either`() {
        assertFalse(MindWiring.remoteReady(configured, MindAsk.AUTONOMOUS_LOOP, clientReady = true))
    }

    @Test
    fun `every ask that can route remote has a budget to spend`() {
        // Belt to MindRouterTest's braces, from the other side: a request routed to the network
        // with no role behind it would be one sent on nobody's allowance.
        for (ask in MindAsk.entries) {
            if (MindWiring.remoteReady(configured, ask, clientReady = true)) {
                assertEquals(
                    "$ask reached the network",
                    true,
                    ask.remoteRole != null,
                )
            }
        }
    }

    // ---------------------------------------------------------------------- the engine on the phone

    @Test
    fun `an installed model with no engine loaded is not ready`() {
        val installed = LocalMindConfig(installed = LocalModelVariant.TINY)
        assertFalse(MindWiring.onDeviceReady(installed, engineReady = false))
        assertTrue(MindWiring.onDeviceReady(installed, engineReady = true))
    }

    @Test
    fun `switching the feature off silences an engine that is already loaded`() {
        val off = LocalMindConfig(enabled = false, installed = LocalModelVariant.TINY)
        assertFalse(MindWiring.onDeviceReady(off, engineReady = true))
    }

    // ------------------------------------------------------------------------- what the screen says

    @Test
    fun `a loaded model with no key is a screen that can talk`() {
        // The case the old settings-only reading got wrong, and it got it wrong in the direction
        // that matters: it told a player with a working brain in their hand to go and find a key.
        val route = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = true, remoteReady = false)
        assertNull(MindWiring.talkBlock(MindConfig(), route))
    }

    @Test
    fun `nothing anywhere reads as no brain`() {
        val route = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = false, remoteReady = false)
        assertEquals(TalkBlock.NO_BRAIN, MindWiring.talkBlock(MindConfig(), route))
    }

    @Test
    fun `a key with talking switched off says so, rather than saying there is no brain`() {
        val route = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = false, remoteReady = false)
        val off = configured.copy(conversation = false)
        assertEquals(TalkBlock.TALK_OFF, MindWiring.talkBlock(off, route))
    }

    @Test
    fun `talking switched off is still overridden by a model on the phone`() {
        // The switch is the remote brain's switch. It has never had anything to say about a model
        // that costs nothing and calls nobody.
        val route = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = true, remoteReady = false)
        assertNull(MindWiring.talkBlock(configured.copy(conversation = false), route))
    }

    // ------------------------------------------------------------------------- who to ask, in order

    @Test
    fun `a local model that says nothing does not cost the player the remote answer`() {
        // The case worth a test of its own: a small model returning prose instead of JSON is an
        // ordinary outcome, and without the middle entry the player would drop to the written
        // brain with a configured remote route sitting there never asked.
        val both = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = true, remoteReady = true)
        assertEquals(
            listOf(MindAnswerer.ON_DEVICE, MindAnswerer.REMOTE, MindAnswerer.SCRIPTED),
            MindWiring.askOrder(both),
        )
    }

    @Test
    fun `a route with one brain asks it and then gives up to the written one`() {
        val local = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = true, remoteReady = false)
        assertEquals(listOf(MindAnswerer.ON_DEVICE, MindAnswerer.SCRIPTED), MindWiring.askOrder(local))

        val remote = MindRouter.route(MindAsk.TALK_REPLY, onDeviceReady = false, remoteReady = true)
        assertEquals(listOf(MindAnswerer.REMOTE, MindAnswerer.SCRIPTED), MindWiring.askOrder(remote))
    }

    @Test
    fun `nobody is asked twice for the same question`() {
        // A route may name the same brain as its cover as well as its answer. Asking a model that
        // just declined the identical question again is a second wait for the same silence.
        val distil = MindRouter.route(MindAsk.DISTIL, onDeviceReady = true, remoteReady = true)
        val order = MindWiring.askOrder(distil)
        assertEquals(order.distinct(), order)
    }

    @Test
    fun `the scripted brain is never asked before a model that could answer`() {
        for (ask in MindAsk.entries) {
            for (onDevice in listOf(false, true)) {
                for (remote in listOf(false, true)) {
                    val order = MindWiring.askOrder(MindRouter.route(ask, onDevice, remote))
                    val scripted = order.indexOf(MindAnswerer.SCRIPTED)
                    if (scripted < 0) continue
                    assertEquals("$ask asked the written brain first", order.size - 1, scripted)
                }
            }
        }
    }

    // ------------------------------------------------------------------ the second answer landing

    private fun chat(vararg turns: Pair<Boolean, String>): List<ChatTurn> =
        turns.mapIndexed { i, (fromPet, text) -> ChatTurn(fromPet, text, i.toLong()) }

    @Test
    fun `the better answer replaces the first one rather than following it`() {
        val log = chat(false to "hello", true to "hi")
        val replaced = MindWiring.replacingPetLine(log, previous = "hi", replacement = "hello to you")
        assertEquals(2, replaced!!.size)
        assertEquals("hello to you", replaced.last().text)
        assertTrue(replaced.last().fromPet)
        // Everything before it is untouched, including the timestamp of the line rewritten.
        assertEquals(log.first(), replaced.first())
        assertEquals(log.last().atSeconds, replaced.last().atSeconds)
    }

    @Test
    fun `a late answer that missed its moment is dropped`() {
        // The creature spoke again, or the player did, while the network was still out. Rewriting
        // further back would change a line already read.
        val movedOn = chat(false to "hello", true to "hi", false to "still there?")
        assertNull(MindWiring.replacingPetLine(movedOn, "hi", "hello to you"))
    }

    @Test
    fun `an answer that lands after a different line was written is dropped`() {
        val other = chat(false to "hello", true to "something else")
        assertNull(MindWiring.replacingPetLine(other, "hi", "hello to you"))
    }

    @Test
    fun `an identical or empty second answer changes nothing`() {
        val log = chat(false to "hello", true to "hi")
        assertNull(MindWiring.replacingPetLine(log, "hi", "hi"))
        assertNull(MindWiring.replacingPetLine(log, "hi", "   "))
    }

    @Test
    fun `an empty conversation is left alone`() {
        assertNull(MindWiring.replacingPetLine(emptyList(), "hi", "hello to you"))
    }
}
