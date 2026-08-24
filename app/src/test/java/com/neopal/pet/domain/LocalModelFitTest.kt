package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which devices may hold a model, and which may not.
 *
 * Every assertion here stands in for a phone nobody on this project owns. The failure this file
 * exists to prevent is not a wrong answer on screen — it is the kernel killing the process for
 * asking for more memory than it was budgeted, which arrives with no exception, no log line and
 * no crash report, and which a player experiences as their pet disappearing. There is no way to
 * test that on the phone it happens to, so it gets tested here, on made-up phones, before it can
 * ever happen.
 *
 * The second failure it guards is the opposite one and it is much easier to ship: a threshold set
 * so cautiously that the feature is offered to nobody. That one produces no bug reports at all.
 */
class LocalModelFitTest {

    // Four devices, written as the numbers Android would actually report. `totalMem` is always
    // well under the figure on the box: the kernel image and the hardware carve-outs are taken
    // off the top before Android ever sees the rest.
    private val flagship = DeviceMemory(
        totalRamBytes = 11_800_000_000L, // a "12 GB" phone
        availableRamBytes = 4_000_000_000L,
        memoryClassMb = 512,
        isLowRamDevice = false,
        freeDiskBytes = 60_000_000_000L,
    )
    private val goodPhone = DeviceMemory(
        totalRamBytes = 7_900_000_000L, // an "8 GB" phone
        availableRamBytes = 2_600_000_000L,
        memoryClassMb = 256,
        isLowRamDevice = false,
        freeDiskBytes = 30_000_000_000L,
    )
    private val ordinaryPhone = DeviceMemory(
        totalRamBytes = 5_600_000_000L, // a "6 GB" phone
        availableRamBytes = 2_000_000_000L,
        memoryClassMb = 192,
        isLowRamDevice = false,
        freeDiskBytes = 20_000_000_000L,
    )
    private val budget = DeviceMemory(
        totalRamBytes = 3_700_000_000L, // a "4 GB" phone
        availableRamBytes = 1_400_000_000L,
        memoryClassMb = 128,
        isLowRamDevice = false,
        freeDiskBytes = 20_000_000_000L,
    )
    private val goEdition = DeviceMemory(
        totalRamBytes = 1_900_000_000L,
        availableRamBytes = 700_000_000L,
        memoryClassMb = 96,
        isLowRamDevice = true,
        freeDiskBytes = 12_000_000_000L,
    )

    // ------------------------------------------------------------------ the measurement itself

    @Test
    fun `a flagship is offered the full model`() {
        val fit = LocalModelFit.of(flagship)
        assertEquals(DeviceTier.ROOMY, fit.tier)
        assertEquals(LocalModelVariant.LARGE, fit.recommended)
        assertEquals("everything smaller stays available too", 3, fit.allowed.size)
        assertNull("nothing is blocked, so nothing needs explaining", fit.blocker)
    }

    @Test
    fun `an eight gigabyte phone gets the standard model and never the full one`() {
        // Google publishes a twelve-gigabyte device floor for the full model. Eight is a good
        // phone and it is not that phone.
        val fit = LocalModelFit.of(goodPhone)
        assertEquals(DeviceTier.CAPABLE, fit.tier)
        assertEquals(LocalModelVariant.SMALL, fit.recommended)
        assertFalse(fit.permits(LocalModelVariant.LARGE))
    }

    @Test
    fun `an ordinary six gigabyte phone gets the compact model`() {
        // This is the tier that decides whether the feature exists for most people. If the
        // arithmetic drifts up, the answer everywhere becomes "not your phone".
        val fit = LocalModelFit.of(ordinaryPhone)
        assertEquals(DeviceTier.MODEST, fit.tier)
        assertEquals(LocalModelVariant.TINY, fit.recommended)
        assertFalse(fit.permits(LocalModelVariant.SMALL))
    }

    @Test
    fun `a four gigabyte phone is offered nothing at all`() {
        val fit = LocalModelFit.of(budget)
        assertEquals(DeviceTier.UNFIT, fit.tier)
        assertFalse(fit.hasAnything)
        assertEquals(ModelBlocker.NOT_ENOUGH_RAM, fit.blocker)
    }

    @Test
    fun `Android's own low-memory flag overrules a generous total`() {
        // A large-memory tablet shipped as Android Go. The flag exists because the whole device
        // has been tuned around not doing this, and out-arguing it from a totalMem reading is how
        // you discover the total was never the constraint.
        val fit = LocalModelFit.of(flagship.copy(isLowRamDevice = true))
        assertEquals(DeviceTier.UNFIT, fit.tier)
        assertEquals(ModelBlocker.LOW_RAM_DEVICE, fit.blocker)
    }

    @Test
    fun `the heap class floor still bites on a device with memory to spare`() {
        // Total memory alone is flattering. A device whose per-process budget is still at an old
        // figure is not one to put an inference engine on, whatever its totalMem says — and this
        // is the check that is this project's own rather than Google's.
        val roomyButMean = flagship.copy(memoryClassMb = 128)
        assertEquals(DeviceTier.MODEST, DeviceTier.of(roomyButMean))
    }

    // ------------------------------------------------- the conversion that is easy to get wrong

    @Test
    fun `a phone that meets Google's floor is not rejected for reporting less than it`() {
        // The bug this pins: comparing a nominal "8 GB" floor straight against MemoryInfo.totalMem
        // rejects every 8 GB phone ever made, because none of them report 8 GiB. The feature would
        // then be offered to nobody, on every device, forever, and nobody would file it.
        assertTrue(
            "an 8 GB phone reports well under 8 GiB and must still clear the 8 GB floor",
            goodPhone.totalRamBytes >= LocalModelVariant.SMALL.minTotalRamBytes,
        )
        assertTrue(
            "the raw nominal figure is larger than anything such a phone reports",
            goodPhone.totalRamBytes < 8L * 1_073_741_824L,
        )
    }

    @Test
    fun `the de-rating is not so generous that a smaller phone slips through`() {
        // The other side of the same constant. A "6 GB" phone must not clear the 8 GB floor.
        assertTrue(ordinaryPhone.totalRamBytes < LocalModelVariant.SMALL.minTotalRamBytes)
        assertTrue(budget.totalRamBytes < LocalModelVariant.TINY.minTotalRamBytes)
    }

    @Test
    fun `Google's floor is the strict one today, and this checks both anyway`() {
        // Both tests run because they are different claims — Google's is about devices it tested,
        // this project's is arithmetic anyone can redo. Today Google's is the binding one for all
        // three variants. If a file size grows or a floor is lowered, that stops being true, and
        // this is the assertion that says so instead of the headroom check quietly going slack.
        for (variant in LocalModelVariant.entries) {
            assertTrue(
                "${variant.name}: the published floor must leave the headroom sum room to spare",
                variant.minTotalRamBytes >=
                    variant.estimatedResidentBytes + LocalModelFit.SYSTEM_RESERVE_BYTES,
            )
        }
    }

    @Test
    fun `the resident cost is more than the download, on every variant`() {
        // Sizing an install off the file size puts a model on every phone with a big enough card.
        for (variant in LocalModelVariant.entries) {
            assertTrue(variant.estimatedResidentBytes > variant.downloadBytes)
        }
    }

    // ------------------------------------------------------------------ what the player may force

    @Test
    fun `the player may force a smaller model on a phone that could run a larger one`() {
        val fit = LocalModelFit.of(flagship)
        assertEquals(LocalModelVariant.TINY, fit.choose(LocalModelVariant.TINY))
        assertEquals(LocalModelVariant.SMALL, fit.choose(LocalModelVariant.SMALL))
        assertEquals("and the smallest thing on offer is reachable by name", LocalModelVariant.TINY, fit.smallest)
    }

    @Test
    fun `the player cannot force a model onto a phone that cannot hold it`() {
        val fit = LocalModelFit.of(ordinaryPhone)
        assertFalse(fit.permits(LocalModelVariant.LARGE))
        // Not an error and not a grant: it quietly becomes the largest thing that fits.
        assertEquals(LocalModelVariant.TINY, fit.choose(LocalModelVariant.LARGE))
        assertEquals(LocalModelVariant.TINY, fit.choose(LocalModelVariant.SMALL))
    }

    @Test
    fun `no preference whatsoever produces a variant this device is not permitted`() {
        // The asymmetry stated as a closed property rather than as three examples of it.
        for (memory in listOf(flagship, goodPhone, ordinaryPhone, budget, goEdition)) {
            val fit = LocalModelFit.of(memory)
            for (asked in listOf(null) + LocalModelVariant.entries) {
                val got = fit.choose(asked)
                if (got != null) {
                    assertTrue("${memory.totalRamBytes} asked $asked, got $got", fit.permits(got))
                }
            }
        }
    }

    @Test
    fun `preferring something on a device that holds nothing still offers nothing`() {
        assertNull(LocalModelFit.of(goEdition).choose(LocalModelVariant.TINY))
        assertNull(LocalModelFit.of(budget).choose(null))
    }

    // ------------------------------------------------------------------ disk, which is fixable

    @Test
    fun `a full disk is reported as something to fix, not as a phone that cannot`() {
        val nearlyFull = flagship.copy(freeDiskBytes = 1_000_000_000L)
        val fit = LocalModelFit.of(nearlyFull)
        assertEquals(ModelBlocker.NOT_ENOUGH_DISK, fit.blocker)
        assertEquals("the phone itself is still fine, and the message has to say so", DeviceTier.ROOMY, fit.tier)
        assertTrue("the player is told how much to clear", fit.freeUpBytes > 0L)
    }

    @Test
    fun `a phone with room for a smaller model but not the biggest still gets an offer`() {
        val squeezed = flagship.copy(freeDiskBytes = 4_000_000_000L)
        val fit = LocalModelFit.of(squeezed)
        assertEquals(listOf(LocalModelVariant.SMALL, LocalModelVariant.TINY), fit.allowed)
        assertNull("something is on offer, so nothing needs explaining away", fit.blocker)
        assertEquals(
            "and clearing this much would unlock the full one",
            LocalModelVariant.LARGE.minFreeDiskBytes - 4_000_000_000L,
            fit.freeUpBytes,
        )
    }

    @Test
    fun `a phone that cannot hold a model is never told to free space`() {
        // Both of these are short of space *and* short of memory, which is the combination that
        // matters: a phone with a full disk that would still not be offered anything must not be
        // sent off to delete photographs. It wastes their evening and then says no anyway. A
        // spacious unfit phone proves nothing here — the arithmetic returns zero for it either
        // way — so these two are deliberately squeezed on both axes.
        val squeezedAndUnfit = budget.copy(freeDiskBytes = 500_000_000L)
        assertEquals(ModelBlocker.NOT_ENOUGH_RAM, LocalModelFit.of(squeezedAndUnfit).blocker)
        assertEquals(0L, LocalModelFit.of(squeezedAndUnfit).freeUpBytes)

        val squeezedAndFlagged = goEdition.copy(freeDiskBytes = 400_000_000L)
        assertEquals(ModelBlocker.LOW_RAM_DEVICE, LocalModelFit.of(squeezedAndFlagged).blocker)
        assertEquals(0L, LocalModelFit.of(squeezedAndFlagged).freeUpBytes)

        assertEquals("and a roomy disk on an unfit phone is still zero", 0L, LocalModelFit.of(budget).freeUpBytes)
    }

    @Test
    fun `the disk gate leaves room to spare after the download`() {
        // Filling the volume to the last byte breaks the camera, the messages app and every save
        // this game has written — and the player blames whatever they open next.
        for (variant in LocalModelVariant.entries) {
            assertTrue(variant.minFreeDiskBytes > variant.downloadBytes)
        }
    }

    // ------------------------------------------------------------------ the moment of loading

    @Test
    fun `nothing loads while the system is already reclaiming`() {
        val fit = LocalModelFit.of(flagship)
        assertTrue(fit.permits(LocalModelVariant.LARGE))
        assertFalse(
            "permitted on this phone is not the same as sensible this minute",
            fit.loadableNow(LocalModelVariant.LARGE, flagship.copy(lowMemoryNow = true)),
        )
    }

    @Test
    fun `nothing loads when free memory is down to the floor`() {
        val fit = LocalModelFit.of(flagship)
        val scraping = flagship.copy(availableRamBytes = LocalModelFit.LOAD_FLOOR_BYTES - 1L)
        assertFalse(fit.loadableNow(LocalModelVariant.LARGE, scraping))
        assertTrue(fit.loadableNow(LocalModelVariant.LARGE, flagship))
    }

    @Test
    fun `a quiet moment does not make an unpermitted model loadable`() {
        // The pressure check narrows; it must never widen. A caller that only asked "is memory
        // free right now" would load the full model onto a six-gigabyte phone the moment the
        // player closed a browser tab.
        val fit = LocalModelFit.of(ordinaryPhone)
        val idle = ordinaryPhone.copy(availableRamBytes = 5_000_000_000L, lowMemoryNow = false)
        assertFalse(fit.loadableNow(LocalModelVariant.LARGE, idle))
        assertFalse(fit.loadableNow(LocalModelVariant.SMALL, idle))
    }

    @Test
    fun `the free-memory floor is a pressure signal and not a capacity figure`() {
        // Raised to the model's own size, availMem would refuse every load on every phone
        // forever: Android keeps memory usefully full, not usefully empty. The failure would be a
        // feature that silently never runs anywhere.
        assertTrue(LocalModelFit.LOAD_FLOOR_BYTES < LocalModelVariant.TINY.estimatedResidentBytes)
    }

    // ------------------------------------------------------------------ the published figures

    @Test
    fun `the variants are ordered smallest to largest on every axis that matters`() {
        // Nothing else in this file makes sense if a "bigger" variant is cheaper on some axis:
        // largestFirst would stop meaning what it says and the fallback in choose() would hand
        // back something heavier than what was refused.
        val ascending = LocalModelVariant.largestFirst.reversed()
        assertEquals(LocalModelVariant.entries.toList(), ascending)
        for ((smaller, bigger) in ascending.zipWithNext()) {
            assertTrue(smaller.downloadBytes < bigger.downloadBytes)
            assertTrue(smaller.minDeviceMemoryGb < bigger.minDeviceMemoryGb)
            assertTrue(smaller.minMemoryClassMb <= bigger.minMemoryClassMb)
        }
    }

    @Test
    fun `the file names are the ones the allowlist publishes`() {
        // The downloader and this file must not be able to disagree about which artefact a
        // variant means; a mismatch would fetch three gigabytes of the wrong model and checksum
        // it happily.
        assertEquals("gemma3-1b-it-int4.litertlm", LocalModelVariant.TINY.fileName)
        assertEquals("gemma-4-E2B-it.litertlm", LocalModelVariant.SMALL.fileName)
        assertEquals("gemma-4-E4B-it.litertlm", LocalModelVariant.LARGE.fileName)
        assertEquals(584_417_280L, LocalModelVariant.TINY.downloadBytes)
        assertEquals(2_588_147_712L, LocalModelVariant.SMALL.downloadBytes)
        assertEquals(3_659_530_240L, LocalModelVariant.LARGE.downloadBytes)
    }
}
