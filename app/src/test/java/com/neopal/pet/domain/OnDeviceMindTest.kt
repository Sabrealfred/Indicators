package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides whether a 3 GB model is allowed to exist on this phone.
 *
 * Two of the three things checked here fail silently and one fails catastrophically, and that is
 * the reason the suite exists.
 *
 * Silently: a verdict that is wrong in the *refusing* direction produces a creature that answers
 * from the local brain, which is exactly what a creature with no model installed does. Nothing
 * crashes, nothing is logged, and the feature is simply off for everyone on a whole class of
 * handset. Same shape as the overflowing throttles in docs/PLAN.md.
 *
 * Catastrophically: a verdict that is wrong in the *permissive* direction is not an exception. The
 * kernel kills the process, the app disappears, and the player concludes the pet died. There is no
 * `catch` for that, so the check has to be right before the load, and "right" has to mean
 * something a machine can confirm.
 */
class OnDeviceMindTest {

    // The three steps from docs/CEREBRO-LOCAL.md §1, with the corrected figures.
    private val small = OnDeviceModel("gemma3-1b-it-int4", "/data/x/small.litertlm", 584_417_280L, 6)
    private val medium = OnDeviceModel("gemma-4-E2B-it", "/data/x/medium.litertlm", 2_588_147_712L, 8)
    private val large = OnDeviceModel("gemma-4-E4B-it", "/data/x/large.litertlm", 3_659_530_240L, 12)

    private fun phone(
        totalGb: Double,
        freeGb: Double,
        thresholdMb: Long = 256L,
        lowMemoryNow: Boolean = false,
        lowRam: Boolean = false,
    ) = OnDeviceMemory(
        totalBytes = (totalGb * 1024 * 1024 * 1024).toLong(),
        availableBytes = (freeGb * 1024 * 1024 * 1024).toLong(),
        thresholdBytes = thresholdMb * 1024 * 1024,
        lowMemoryNow = lowMemoryNow,
        lowRamDevice = lowRam,
    )

    // ------------------------------------------------------------------ the §5 limit

    @Test
    fun `the on-device model answers the player and does nothing else`() {
        // This is the most important assertion in the file and it is deliberately an equality
        // rather than four separate checks: widening the set is what this is here to catch, and a
        // test that only asserts what is absent can be satisfied by adding something new.
        assertEquals(setOf(MindRole.CONVERSE), OnDeviceMind.SERVES)
    }

    @Test
    fun `the two jobs the autonomous loop calls are refused`() {
        // `choose` runs from the reconsider throttle and `plan` from the errand throttle. Both run
        // on a timer, every few minutes, with the app closed. Section 5 of the design calls this a
        // limit and not a preference; this is where that is enforced.
        assertFalse(OnDeviceMind.serves(MindRole.DECIDE))
        assertFalse(OnDeviceMind.serves(MindRole.PLAN))
    }

    @Test
    fun `distilling a life is refused too, so exactly one method can reach the engine`() {
        assertFalse(OnDeviceMind.serves(MindRole.DISTIL))
        assertTrue(OnDeviceMind.serves(MindRole.CONVERSE))
    }

    @Test
    fun `every role is accounted for, so a new one cannot default to allowed`() {
        // If a fifth MindRole is ever added it lands outside SERVES and is refused, which is the
        // safe direction. Asserted rather than assumed, because the opposite default is the one
        // that would put a model in a background loop by accident.
        val refused = MindRole.entries.filterNot { OnDeviceMind.serves(it) }
        assertEquals(MindRole.entries.size - 1, refused.size)
    }

    // ------------------------------------------------------------------ the memory verdict

    @Test
    fun `a low-RAM handset is refused permanently, whatever it happens to have free`() {
        // Plenty free at this instant, and it still refuses: the flag is the manufacturer saying
        // the device was built without the headroom, and it will not be true later.
        val verdict = OnDeviceMind.verdict(small, phone(totalGb = 6.0, freeGb = 4.0, lowRam = true))
        assertTrue(verdict is OnDeviceVerdict.TooBig)
    }

    @Test
    fun `a phone below the declared floor is refused permanently and told the smaller one may fit`() {
        // A 6 GB phone against the model Google declares needs 12 GB.
        val verdict = OnDeviceMind.verdict(large, phone(totalGb = 5.6, freeGb = 4.5))
        assertTrue(verdict is OnDeviceVerdict.TooBig)
        assertTrue((verdict as OnDeviceVerdict.TooBig).reason.contains("smaller"))
    }

    @Test
    fun `an eight gigabyte phone reporting seven and a half still gets the eight gigabyte model`() {
        // The bug this exists to stop: totalMem never equals the number on the box. A handset sold
        // as 8 GB reports somewhere in the sevens once the bootloader, the modem and the graphics
        // carve-out have taken theirs. Comparing against a round 8 GiB refuses the entire tier the
        // requirement was written for, and the feature looks broken rather than careful.
        assertEquals(OnDeviceVerdict.Fits, OnDeviceMind.verdict(medium, phone(totalGb = 7.4, freeGb = 4.2)))
    }

    @Test
    fun `the allowance is an allowance and not a whole tier of slack`() {
        // 6.6 GiB reported is a 7 GB-class phone, not an 8 GB one, and the 8 GB model stays out.
        assertTrue(OnDeviceMind.verdict(medium, phone(totalGb = 6.6, freeGb = 5.0)) is OnDeviceVerdict.TooBig)
    }

    @Test
    fun `memory pressure right now is temporary, not a verdict on the handset`() {
        val verdict = OnDeviceMind.verdict(small, phone(totalGb = 5.7, freeGb = 3.0, lowMemoryNow = true))
        assertTrue(verdict is OnDeviceVerdict.NotNow)
    }

    @Test
    fun `a phone big enough but busy is refused for now and not for good`() {
        // Twelve-gigabyte handset, right model for it, but only 900 MB free this minute.
        val verdict = OnDeviceMind.verdict(large, phone(totalGb = 11.2, freeGb = 0.9))
        assertTrue(verdict is OnDeviceVerdict.NotNow)
    }

    @Test
    fun `free memory below the killing threshold is not free memory`() {
        // 4.7 GiB free sounds like room for a 3.41 GiB model until the system's own threshold is
        // subtracted, and everything below that line is memory the kernel is about to reclaim.
        val needed = OnDeviceMind.neededBytes(large.residentBytes)
        val thresholdMb = 1_024L
        val availableGb = (needed + thresholdMb * 1024 * 1024 - 1) / 1024.0 / 1024.0 / 1024.0
        val verdict = OnDeviceMind.verdict(
            large,
            phone(totalGb = 11.2, freeGb = availableGb, thresholdMb = thresholdMb),
        )
        assertTrue("one byte short of the requirement must refuse", verdict is OnDeviceVerdict.NotNow)
    }

    @Test
    fun `the requirement is the weights plus working room plus something left over`() {
        // Not just the file size. Prefill allocates a key-value cache and the runtime wants
        // scratch; loading exactly the file size is how a load succeeds and the first sentence
        // kills the process.
        assertTrue(OnDeviceMind.neededBytes(large.residentBytes) > large.residentBytes)
        assertTrue(OnDeviceMind.neededBytes(large.residentBytes) > large.residentBytes + OnDeviceMind.SPARE_BYTES)
    }

    @Test
    fun `a reading that could not be taken refuses, and refuses recoverably`() {
        // Every field zero is what an unreadable ActivityManager looks like. Unknown must not read
        // as fine — but it must also not permanently write the handset off on the strength of a
        // failed call, so it is NotNow rather than TooBig.
        val verdict = OnDeviceMind.verdict(small, OnDeviceMemory.UNKNOWN)
        assertTrue(verdict is OnDeviceVerdict.NotNow)
    }

    @Test
    fun `a model with no file behind it is refused before anything else is considered`() {
        val nothing = OnDeviceModel("none", "", 0L, 6)
        assertTrue(OnDeviceMind.verdict(nothing, phone(totalGb = 11.2, freeGb = 9.0)) is OnDeviceVerdict.TooBig)
    }

    @Test
    fun `a roomy phone with the small model loads`() {
        assertEquals(OnDeviceVerdict.Fits, OnDeviceMind.verdict(small, phone(totalGb = 5.6, freeGb = 2.4)))
    }

    @Test
    fun `no arithmetic here can overflow on the largest model a phone could hold`() {
        val absurd = OnDeviceModel("absurd", "/x", Long.MAX_VALUE / 4, 512)
        assertTrue(OnDeviceMind.neededBytes(absurd.residentBytes) > 0L)
        assertTrue(OnDeviceMind.requiredDeviceBytes(absurd.deviceGigabytes) > 0L)
        assertTrue(OnDeviceMind.verdict(absurd, phone(totalGb = 11.2, freeGb = 9.0)) !is OnDeviceVerdict.Fits)
    }

    @Test
    fun `a model declaring nothing needs no particular handset, and is judged on free memory alone`() {
        assertEquals(0L, OnDeviceMind.requiredDeviceBytes(0))
        assertEquals(0L, OnDeviceMind.requiredDeviceBytes(-4))
    }

    // ------------------------------------------------------------------ threads and budgets

    @Test
    fun `the engine gets half the cores, and never all of them`() {
        assertEquals(2, OnDeviceMind.threadsFor(4))
        assertEquals(3, OnDeviceMind.threadsFor(6))
        assertEquals(4, OnDeviceMind.threadsFor(8))
        // A sixteen-core phone still gets four: past that it is heat rather than speed, and the
        // frame the player is watching while they wait is drawn on the same silicon.
        assertEquals(4, OnDeviceMind.threadsFor(16))
    }

    @Test
    fun `a phone that will not say how many cores it has still gets a working engine`() {
        assertEquals(OnDeviceMind.MIN_THREADS, OnDeviceMind.threadsFor(0))
        assertEquals(OnDeviceMind.MIN_THREADS, OnDeviceMind.threadsFor(-1))
        assertEquals(OnDeviceMind.MIN_THREADS, OnDeviceMind.threadsFor(1))
    }

    @Test
    fun `a config with nothing installed is not usable`() {
        assertFalse(OnDeviceMindConfig().usable)
        assertFalse(OnDeviceMindConfig(enabled = true).usable)
        assertFalse(OnDeviceMindConfig(enabled = true, model = small.copy(absolutePath = "")).usable)
        assertFalse(OnDeviceMindConfig(enabled = false, model = small).usable)
        assertTrue(OnDeviceMindConfig(enabled = true, model = small).usable)
    }

    @Test
    fun `budgets from an older save are clamped rather than believed`() {
        val silly = OnDeviceMindConfig(enabled = true, model = small, contextTokens = 0, timeoutMillis = 0L)
        val fixed = silly.sanitised()
        assertEquals(OnDeviceMindConfig.MIN_CONTEXT_TOKENS, fixed.contextTokens)
        assertEquals(OnDeviceMindConfig.MIN_TIMEOUT_MILLIS, fixed.timeoutMillis)

        val greedy = OnDeviceMindConfig(contextTokens = 1_000_000, timeoutMillis = 10 * 60_000L)
        assertEquals(OnDeviceMindConfig.MAX_CONTEXT_TOKENS, greedy.sanitised().contextTokens)
        assertEquals(OnDeviceMindConfig.MAX_TIMEOUT_MILLIS, greedy.sanitised().timeoutMillis)
    }

    @Test
    fun `a half-written model entry is dropped by sanitising rather than loaded`() {
        val broken = OnDeviceMindConfig(enabled = true, model = small.copy(residentBytes = 0L))
        assertEquals(null, broken.sanitised().model)
        assertFalse(broken.sanitised().usable)
    }

    @Test
    fun `sanitising a sensible config changes nothing`() {
        val fine = OnDeviceMindConfig(enabled = true, model = medium)
        assertEquals(fine, fine.sanitised())
    }

    @Test
    fun `the local budget is tighter than the remote one, because that is the whole point`() {
        // Section 4 of the design: the on-device model is the one that answers *now*. A local
        // model given the remote route's twelve seconds has thrown away its only advantage.
        assertTrue(OnDeviceMindConfig.DEFAULT_TIMEOUT_MILLIS < MindConfig().timeoutMillis)
    }
}
