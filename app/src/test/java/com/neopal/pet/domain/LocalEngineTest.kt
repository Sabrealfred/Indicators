package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers an engine is built with, and which file it opens.
 *
 * None of this can be checked on the machine that runs it — there is no SDK here and no handset —
 * so it is checked here instead, where it is arithmetic and a lookup. The two failures this file
 * is really guarding are both quiet ones: a thread count that saturates a phone, which shows up
 * as "the game got hot" months later and is blamed on the graphics, and a variant chosen that
 * this device cannot hold, which shows up as the process being killed and is indistinguishable
 * from the pet dying.
 */
class LocalEngineTest {

    // ------------------------------------------------------------------------------- threads

    @Test
    fun `half the cores, never all of them`() {
        assertEquals(4, LocalEngine.threadsFor(8))
        assertEquals(3, LocalEngine.threadsFor(6))
        assertEquals(2, LocalEngine.threadsFor(4))
    }

    @Test
    fun `a phone that will not say how many cores it has still gets to think`() {
        // Android is allowed to report nonsense here, and a refusal to load over it would be a
        // feature switched off by a number nobody looked at.
        assertEquals(LocalEngine.MIN_THREADS, LocalEngine.threadsFor(0))
        assertEquals(LocalEngine.MIN_THREADS, LocalEngine.threadsFor(-1))
    }

    @Test
    fun `a phone with many cores does not get all of them`() {
        for (cores in 1..64) {
            val threads = LocalEngine.threadsFor(cores)
            assertTrue("$cores cores gave $threads", threads in LocalEngine.MIN_THREADS..LocalEngine.MAX_THREADS)
        }
        assertEquals(LocalEngine.MAX_THREADS, LocalEngine.threadsFor(64))
    }

    @Test
    fun `the plan carries the same numbers whichever model it is for`() {
        // One context window for all three, sized by what this game puts in a prompt rather than
        // by what the model could take. See the constant's own note.
        for (variant in LocalModelVariant.entries) {
            val plan = LocalEngine.plan(variant, cores = 8)
            assertEquals(variant, plan.variant)
            assertEquals(LocalEngine.CONTEXT_TOKENS, plan.contextTokens)
            assertEquals(LocalEngine.TIMEOUT_MILLIS, plan.timeoutMillis)
            assertEquals(4, plan.threads)
        }
    }

    @Test
    fun `the local budget is shorter than the remote one`() {
        // The entire argument for a model on the phone is that it answers now. A local budget as
        // long as the network's would have lost the only race it was entered in.
        assertTrue(LocalEngine.TIMEOUT_MILLIS < MindConfig().timeoutMillis)
    }

    // --------------------------------------------------------------- the two catalogues agree

    @Test
    fun `every variant has exactly one entry to download it from`() {
        for (variant in LocalModelVariant.entries) {
            val matches = FetchableModels.known.filter { it.fileName == variant.fileName }
            assertEquals("one entry for ${variant.fileName}", 1, matches.size)
            assertEquals(matches.single(), LocalEngine.fetchableFor(variant))
        }
    }

    @Test
    fun `the size in the fit table is the size in the download table`() {
        // Two files, two authors, one allowlist. The player is shown one of these figures before
        // they spend a night of somebody's Wi-Fi, and the other decides whether there is room for
        // it. They cannot be allowed to drift.
        for (variant in LocalModelVariant.entries) {
            val fetchable = LocalEngine.fetchableFor(variant)
            assertNotNull(fetchable)
            assertEquals(variant.downloadBytes, fetchable!!.sizeBytes)
        }
    }

    // ------------------------------------------------------------------ which weights to open

    private val roomy = LocalModelFit(
        tier = DeviceTier.ROOMY,
        allowed = LocalModelVariant.largestFirst,
        recommended = LocalModelVariant.LARGE,
        blocker = null,
        freeUpBytes = 0L,
    )

    private val modest = LocalModelFit(
        tier = DeviceTier.MODEST,
        allowed = listOf(LocalModelVariant.TINY),
        recommended = LocalModelVariant.TINY,
        blocker = null,
        freeUpBytes = 0L,
    )

    private val unfit = LocalModelFit(
        tier = DeviceTier.UNFIT,
        allowed = emptyList(),
        recommended = null,
        blocker = ModelBlocker.NOT_ENOUGH_RAM,
        freeUpBytes = 0L,
    )

    @Test
    fun `what the save says was installed is opened`() {
        val config = LocalMindConfig(installed = LocalModelVariant.SMALL)
        assertEquals(
            LocalModelVariant.SMALL,
            LocalEngine.chooseInstalled(config, roomy, setOf(LocalModelVariant.SMALL, LocalModelVariant.TINY)),
        )
    }

    @Test
    fun `a save that claims a model the disk does not have opens what is there`() {
        // A player who cleared the app's storage keeps a save that still claims a model. The disk
        // is the fact; the save is a record.
        val config = LocalMindConfig(installed = LocalModelVariant.LARGE)
        assertEquals(
            LocalModelVariant.TINY,
            LocalEngine.chooseInstalled(config, roomy, setOf(LocalModelVariant.TINY)),
        )
    }

    @Test
    fun `a file the save never knew about is still opened`() {
        assertEquals(
            LocalModelVariant.TINY,
            LocalEngine.chooseInstalled(LocalMindConfig(), roomy, setOf(LocalModelVariant.TINY)),
        )
    }

    @Test
    fun `the player's smaller choice is honoured`() {
        val config = LocalMindConfig(preferred = LocalModelVariant.TINY)
        assertEquals(
            LocalModelVariant.TINY,
            LocalEngine.chooseInstalled(config, roomy, setOf(LocalModelVariant.LARGE, LocalModelVariant.TINY)),
        )
    }

    @Test
    fun `with nothing asked for, the largest present is opened`() {
        assertEquals(
            LocalModelVariant.LARGE,
            LocalEngine.chooseInstalled(LocalMindConfig(), roomy, LocalModelVariant.entries.toSet()),
        )
    }

    @Test
    fun `weights this phone cannot hold are not opened, whatever the save says`() {
        // The case that costs a process: a save restored onto a smaller handset, or a file left
        // behind by a phone that could take it. Loading it is not a slow creature — it is the
        // kernel taking the app mid-sentence.
        val config = LocalMindConfig(installed = LocalModelVariant.LARGE, preferred = LocalModelVariant.LARGE)
        assertEquals(
            LocalModelVariant.TINY,
            LocalEngine.chooseInstalled(config, modest, setOf(LocalModelVariant.LARGE, LocalModelVariant.TINY)),
        )
    }

    @Test
    fun `a phone that can hold nothing opens nothing`() {
        val config = LocalMindConfig(installed = LocalModelVariant.TINY)
        assertNull(LocalEngine.chooseInstalled(config, unfit, LocalModelVariant.entries.toSet()))
    }

    @Test
    fun `an empty disk opens nothing`() {
        val config = LocalMindConfig(installed = LocalModelVariant.TINY)
        assertNull(LocalEngine.chooseInstalled(config, roomy, emptySet()))
    }

    @Test
    fun `switching the feature off stops the load, not just the offer`() {
        val config = LocalMindConfig(enabled = false, installed = LocalModelVariant.TINY)
        assertNull(LocalEngine.chooseInstalled(config, roomy, setOf(LocalModelVariant.TINY)))
    }

    @Test
    fun `nothing chosen is ever something the fit refuses`() {
        // The one property worth stating over every combination rather than over an example: no
        // arguments to this function return a variant permits would reject.
        val configs = listOf(
            LocalMindConfig(),
            LocalMindConfig(installed = LocalModelVariant.LARGE),
            LocalMindConfig(preferred = LocalModelVariant.LARGE),
            LocalMindConfig(installed = LocalModelVariant.SMALL, preferred = LocalModelVariant.LARGE),
        )
        val disks = listOf(
            emptySet(),
            setOf(LocalModelVariant.TINY),
            setOf(LocalModelVariant.LARGE),
            LocalModelVariant.entries.toSet(),
        )
        for (fit in listOf(roomy, modest, unfit)) {
            for (config in configs) {
                for (disk in disks) {
                    val chosen = LocalEngine.chooseInstalled(config, fit, disk) ?: continue
                    assertTrue("$chosen on ${fit.tier}", fit.permits(chosen))
                    assertTrue("$chosen is not on the disk", chosen in disk)
                }
            }
        }
    }
}
