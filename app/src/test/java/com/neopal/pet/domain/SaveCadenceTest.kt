package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save rate, counted rather than asserted about.
 *
 * The interesting property of a save policy is not what one call returns, it is how many disk
 * writes an hour of play produces. So the tests below drive the policy through the same call
 * pattern [com.neopal.pet.ui.PetViewModel] produces — a tick every second, taps in bursts, an
 * immediate write when the app leaves the screen — and count what comes out the other end.
 *
 * The number that made this class exist: under the old rule (a plain 800ms debounce, cancelled
 * and restarted on every call) the same ten minutes produced 594 writes, because a debounce
 * shorter than the one-second clock it was debouncing never coalesces two ticks. It fired every
 * time. DataStore serialises the whole save to JSON on every one of them.
 */
class SaveCadenceTest {

    /** A model of `saveJob?.cancel(); launch { delay(wait); save() }`: one pending write. */
    private class Disk {
        private var fireAt: Long? = null
        var writes = 0
            private set

        val pending: Boolean get() = fireAt != null

        /** Runs the write if it has come due; returns true if one happened. */
        fun pump(now: Long): Boolean {
            val at = fireAt ?: return false
            if (at > now) return false
            fireAt = null
            writes++
            return true
        }

        fun schedule(now: Long, wait: Long) {
            fireAt = now + wait
        }
    }

    /**
     * Runs [minutes] of play against [cadence]. [tapsAt] are the milliseconds the player touches
     * something; every whole second is a clock tick.
     */
    private fun play(cadence: SaveCadence, minutes: Int, tapsAt: Set<Long> = emptySet()): Int {
        val disk = Disk()
        val span = minutes * 60_000L
        var now = 0L
        while (now <= span) {
            if (disk.pump(now)) cadence.written(now)
            if (now > 0 && now % 1_000L == 0L) {
                cadence.waitFor(SaveUrgency.ROUTINE, now, disk.pending)?.let { disk.schedule(now, it) }
            }
            if (now in tapsAt) {
                cadence.waitFor(SaveUrgency.SOON, now, disk.pending)?.let { disk.schedule(now, it) }
            }
            now++
        }
        return disk.writes
    }

    @Test
    fun `an idle hour writes once a minute, not once a second`() {
        val writes = play(SaveCadence(), minutes = 60)
        // 3600 clock ticks in, 59 writes out. One a minute, starting immediately because nothing
        // had been written yet; the cycle lands on every 61st tick rather than every 60th because
        // a write is recorded when it reaches the disk, a moment after the tick that asked for it,
        // and the next tick to clear the interval is therefore the one after.
        assertEquals(59, writes)
    }

    @Test
    fun `ten idle minutes cost fourteen writes at most, where they used to cost six hundred`() {
        val writes = play(SaveCadence(), minutes = 10)
        assertTrue("expected far fewer than one write a second, got $writes", writes < 20)
    }

    @Test
    fun `a burst of taps is one write, not one per tap`() {
        val cadence = SaveCadence()
        // Three taps 150ms apart: each reschedules the same pending write.
        val first = cadence.waitFor(SaveUrgency.SOON, 10_000, writePending = false)
        val second = cadence.waitFor(SaveUrgency.SOON, 10_150, writePending = true)
        val third = cadence.waitFor(SaveUrgency.SOON, 10_300, writePending = true)
        assertEquals(SaveCadence.DEBOUNCE_MILLIS, first)
        assertEquals(SaveCadence.DEBOUNCE_MILLIS, second)
        assertEquals(SaveCadence.DEBOUNCE_MILLIS, third)
        // Each one restarts the wait from where it was asked, so the write lands 800ms after the
        // last tap of the burst rather than 800ms after the first.
        assertEquals(11_100L, 10_300 + third!!)
    }

    @Test
    fun `what the player did is never made to wait for the clock's interval`() {
        val cadence = SaveCadence()
        cadence.written(50_000)
        // A tap one second after a routine write still gets its ordinary debounce...
        assertEquals(SaveCadence.DEBOUNCE_MILLIS, cadence.waitFor(SaveUrgency.SOON, 51_000, false))
        // ...and leaving the screen still writes at once.
        assertEquals(0L, cadence.waitFor(SaveUrgency.NOW, 51_000, false))
    }

    @Test
    fun `a routine tick never queues behind a write the player is waiting for`() {
        val cadence = SaveCadence()
        assertNull(cadence.waitFor(SaveUrgency.ROUTINE, 500_000, writePending = true))
    }

    @Test
    fun `a routine tick writes when nothing has been written yet`() {
        assertEquals(0L, SaveCadence().waitFor(SaveUrgency.ROUTINE, 1_000, writePending = false))
    }

    @Test
    fun `a routine tick inside the interval asks for nothing`() {
        val cadence = SaveCadence()
        cadence.written(100_000)
        for (t in 101_000L..159_000L step 1_000L) {
            assertNull("wrote at ${t - 100_000}ms after the last write",
                cadence.waitFor(SaveUrgency.ROUTINE, t, writePending = false))
        }
        assertEquals(0L, cadence.waitFor(SaveUrgency.ROUTINE, 160_000, writePending = false))
    }

    /**
     * The window a crash can cost, and why it costs nothing the player would notice.
     *
     * Whatever the clock advanced and did not save is rebuilt on the next launch by
     * [Simulation.advance], from the last save and the wall clock. Below the catch-up threshold
     * that rebuild runs at the same live rate the foreground was running at, so the pet comes
     * back where it would have been. Keeping the routine interval under that threshold is what
     * makes the delay free; this test is the thing that fails if somebody raises it past it.
     */
    @Test
    fun `the routine interval stays inside the window that replays at the live rate`() {
        assertTrue(
            "a routine save must not be allowed to age into offline territory",
            SaveCadence.ROUTINE_INTERVAL_MILLIS < 180_000L,
        )
    }

    @Test
    fun `a save the player asked for is on disk within the debounce`() {
        assertTrue(SaveCadence.DEBOUNCE_MILLIS <= 2_000L)
    }
}
