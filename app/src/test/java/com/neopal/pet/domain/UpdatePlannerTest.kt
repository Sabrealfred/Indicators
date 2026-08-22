package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the update screen is allowed to say.
 *
 * These are not cosmetic assertions. Each one pins a sentence that only ever appears on somebody
 * else's phone, on a path nobody exercises by hand: an install button that cannot install, a
 * broken publish greeted as "up to date", a local build reported as an error. None of those would
 * come back as a bug report — the player would just conclude the updater does not work.
 */
class UpdatePlannerTest {

    // ------------------------------------------------------------------ the permission gate

    @Test
    fun `a downloaded build with no install permission offers the settings screen, not install`() {
        val plan = UpdatePlanner.plan(
            phase = UpdatePhase.READY_TO_INSTALL,
            canInstallPackages = false,
            hasPermissionScreen = true,
        )
        assertEquals(UpdateAction.ALLOW_INSTALLS, plan.primary)
        assertFalse("install must not be offered when it cannot work", UpdateAction.INSTALL in plan.actions)
        assertNotNull("the player has to be told why", plan.note)
    }

    @Test
    fun `the permission gate says the download is not lost`() {
        val plan = UpdatePlanner.plan(UpdatePhase.READY_TO_INSTALL, canInstallPackages = false)
        // A trip to system settings looks like leaving; the note is the only thing that says the
        // megabytes already spent are still there when they come back.
        assertTrue(plan.note!!.contains("waiting"))
    }

    @Test
    fun `without a per-app permission screen the install button stays, because the installer asks`() {
        // Below API 26 there is one device-wide switch and no screen to deep-link to. A button
        // that opens nothing is worse than letting the system have the conversation.
        val plan = UpdatePlanner.plan(
            phase = UpdatePhase.READY_TO_INSTALL,
            canInstallPackages = false,
            hasPermissionScreen = false,
        )
        assertEquals(UpdateAction.INSTALL, plan.primary)
        assertFalse(UpdateAction.ALLOW_INSTALLS in plan.actions)
    }

    @Test
    fun `permission granted, checksum matched - the screen says tap to install`() {
        val plan = UpdatePlanner.plan(UpdatePhase.READY_TO_INSTALL, canInstallPackages = true)
        assertEquals(UpdateAction.INSTALL, plan.primary)
        assertEquals(UpdateTone.GOOD, plan.tone)
        assertTrue("a downloaded build must always be discardable", UpdateAction.DISCARD in plan.actions)
    }

    @Test
    fun `a caution turns the ready state from reassuring to careful`() {
        val calm = UpdatePlanner.plan(UpdatePhase.READY_TO_INSTALL, cautioned = false)
        val warned = UpdatePlanner.plan(UpdatePhase.READY_TO_INSTALL, cautioned = true)
        assertEquals(UpdateTone.GOOD, calm.tone)
        // The only caution the service raises costs the player their save if they read past it.
        assertEquals(UpdateTone.WARN, warned.tone)
        assertEquals("it is still the same install", UpdateAction.INSTALL, warned.primary)
    }

    // ------------------------------------------------------------------ cannot tell

    @Test
    fun `a malformed marker is reported as a broken publish, not as no update`() {
        val plan = UpdatePlanner.plan(
            phase = UpdatePhase.UNDECIDABLE,
            unknown = UpdateUnknown.MARKER_MALFORMED,
        )
        assertEquals(UpdateTone.BAD, plan.tone)
        assertEquals("The published build is broken", plan.headline)
        assertTrue(plan.note!!.contains("build job"))
        // The distinction that matters: this must not read like the up-to-date screen.
        assertTrue(plan.headline != UpdatePlanner.plan(UpdatePhase.UP_TO_DATE).headline)
    }

    @Test
    fun `a missing marker is nobodys fault and says so`() {
        val plan = UpdatePlanner.plan(UpdatePhase.UNDECIDABLE, unknown = UpdateUnknown.NO_MARKER)
        assertEquals(UpdateTone.WARN, plan.tone)
        assertEquals("Cannot tell", plan.headline)
    }

    @Test
    fun `every asset disagreement is laid at the publisher's door`() {
        listOf(
            UpdateUnknown.ASSET_MISSING,
            UpdateUnknown.ASSET_SIZE_MISMATCH,
            UpdateUnknown.ASSET_DIGEST_MISMATCH,
            UpdateUnknown.MARKER_MALFORMED,
        ).forEach {
            assertEquals("$it", UpdateFault.PUBLISHER, UpdatePlanner.faultFor(it))
        }
    }

    @Test
    fun `a version this app cannot read is this app's fault`() {
        assertEquals(UpdateFault.THIS_APP, UpdatePlanner.faultFor(UpdateUnknown.INSTALLED_UNKNOWN))
    }

    @Test
    fun `an unrecognised reason is blamed on nobody`() {
        // Guessing "your CI is broken" from a sentence this code does not know is a worse error
        // than admitting the check came back inconclusive.
        assertEquals(UpdateFault.NOBODY, UpdatePlanner.faultFor(null))
        assertEquals(UpdateTone.WARN, UpdatePlanner.plan(UpdatePhase.UNDECIDABLE).tone)
    }

    // ------------------------------------------------------------------ the message round trip

    @Test
    fun `every unknown reason can be recovered from the sentence it produces`() {
        // The service flattens the enum to its message before the screen ever sees it. This is
        // the seam that puts it back, and it is only sound while the mapping is a bijection.
        UpdateUnknown.entries.forEach {
            assertEquals(it, UpdatePlanner.reasonFor(it.message))
        }
    }

    @Test
    fun `the unknown reasons have distinct messages`() {
        val messages = UpdateUnknown.entries.map { it.message }
        assertEquals(
            "two reasons sharing a sentence would make reasonFor pick arbitrarily",
            messages.size,
            messages.toSet().size,
        )
    }

    @Test
    fun `a sentence no reason owns comes back null`() {
        assertNull(UpdatePlanner.reasonFor("Could not reach GitHub."))
        assertNull(UpdatePlanner.reasonFor(""))
    }

    // ------------------------------------------------------------------ ahead of published

    @Test
    fun `a build newer than the published one is not an error and offers no install`() {
        val plan = UpdatePlanner.plan(UpdatePhase.AHEAD_OF_PUBLISHED)
        assertEquals(UpdateTone.NEUTRAL, plan.tone)
        assertEquals(UpdateAction.CHECK, plan.primary)
        assertFalse(UpdateAction.INSTALL in plan.actions)
        assertFalse(UpdateAction.DOWNLOAD in plan.actions)
        assertTrue(plan.note!!.contains("nothing to install"))
    }

    // ------------------------------------------------------------------ the download path

    @Test
    fun `a metered connection asks before spending, and taking no is possible`() {
        val plan = UpdatePlanner.plan(UpdatePhase.METERED_CONSENT)
        assertEquals(UpdateAction.DOWNLOAD_ON_METERED, plan.primary)
        assertTrue("there has to be a way to say no", UpdateAction.DISCARD in plan.actions)
        assertFalse("the plain download button would spend the data silently", UpdateAction.DOWNLOAD in plan.actions)
    }

    @Test
    fun `a download in flight can be stopped and shows a meter`() {
        val plan = UpdatePlanner.plan(UpdatePhase.DOWNLOADING)
        assertTrue(plan.busy)
        assertTrue(plan.showsProgress)
        assertNull(plan.primary)
        assertEquals(listOf(UpdateAction.CANCEL_DOWNLOAD), plan.secondary)
    }

    @Test
    fun `a failed download offers another go rather than only an apology`() {
        val plan = UpdatePlanner.plan(UpdatePhase.DOWNLOAD_FAILED)
        assertEquals(UpdateAction.DOWNLOAD, plan.primary)
        assertEquals(UpdateTone.BAD, plan.tone)
    }

    @Test
    fun `checking shows no buttons to press twice`() {
        val plan = UpdatePlanner.plan(UpdatePhase.CHECKING)
        assertTrue(plan.busy)
        assertTrue(plan.actions.isEmpty())
    }

    // ------------------------------------------------------------------ across every phase

    @Test
    fun `no phase leaves the player with nothing at all to do`() {
        // A screen with no button and no work in progress is a dead end, and the update screen is
        // reached from a menu the player had to go looking for.
        UpdatePhase.entries.forEach { phase ->
            val plan = UpdatePlanner.plan(phase)
            assertTrue("$phase", plan.actions.isNotEmpty() || plan.busy)
        }
    }

    @Test
    fun `no phase is left without a headline`() {
        UpdatePhase.entries.forEach { phase ->
            assertTrue("$phase", UpdatePlanner.plan(phase).headline.isNotBlank())
        }
    }

    @Test
    fun `install is only ever offered once bytes are on the disk`() {
        UpdatePhase.entries
            .filter { it != UpdatePhase.READY_TO_INSTALL }
            .forEach { phase ->
                val plan = UpdatePlanner.plan(phase, canInstallPackages = true)
                assertFalse("$phase", UpdateAction.INSTALL in plan.actions)
            }
    }

    @Test
    fun `download is only offered where there is an offer behind it`() {
        val offering = setOf(UpdatePhase.OFFER, UpdatePhase.METERED_CONSENT, UpdatePhase.DOWNLOAD_FAILED)
        UpdatePhase.entries.filter { it !in offering }.forEach { phase ->
            val plan = UpdatePlanner.plan(phase)
            assertFalse(
                "$phase",
                UpdateAction.DOWNLOAD in plan.actions || UpdateAction.DOWNLOAD_ON_METERED in plan.actions,
            )
        }
    }

    @Test
    fun `every button carries a label a player can read`() {
        UpdateAction.entries.forEach { assertTrue("$it", it.label.isNotBlank()) }
    }
}
