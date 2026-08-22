package com.neopal.pet.domain

/** Why a save was asked for, which is what decides how soon it has to happen. */
enum class SaveUrgency {
    /**
     * Write it now. For the moments where a loss would be visible and unrecoverable: a new game,
     * a purchase, a generation, and the app leaving the screen.
     */
    NOW,

    /**
     * The player did something. Coalesce a burst — a run of taps is one intention — and then
     * write. Bounded by [SaveCadence.DEBOUNCE_MILLIS], so a crash can cost at most that much of
     * what the player actually did.
     */
    SOON,

    /**
     * The one-second clock moved the world on and nobody touched anything.
     *
     * This state is *derived*, not authored: [Simulation.advance] rebuilds it from the last save
     * and the wall clock, and for any gap under its catch-up threshold it rebuilds it at exactly
     * the live rate the foreground was running at. So a routine save that has not happened yet
     * costs nothing on the next launch — which is why it is allowed to wait.
     */
    ROUTINE,
}

/**
 * How often the world is written to disk.
 *
 * The save goes through DataStore, which serialises the whole state to JSON on every write. That
 * makes the write *rate* a real cost — flash wear and CPU, for as long as the app is open — and
 * this game had it pinned at one write per second: the clock ticks once a second, every tick
 * changes the state (if only its `lastTickMillis`), and every change asked to be saved through a
 * debounce of 800ms. A debounce shorter than the interval of the thing it is debouncing does not
 * coalesce anything; it just adds latency. It fired every single time.
 *
 * Pure, and separate from the view model, because the interesting thing about a save policy is
 * the rate it produces over time, and that is a thing worth being able to count in a test.
 */
class SaveCadence(
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val routineIntervalMillis: Long = ROUTINE_INTERVAL_MILLIS,
) {

    /** When a write last actually reached the disk, or null if none has yet. */
    private var lastWriteAtMillis: Long? = null

    /**
     * How long to wait before writing a change asked for at [nowMillis], or null to ask for no
     * write at all.
     *
     * [writePending] says whether a write is already scheduled or in flight. It only matters to
     * [SaveUrgency.ROUTINE], which will never queue behind a write the player is waiting for —
     * the state it wants saved is carried by that write anyway, since whoever performs it writes
     * the newest state rather than the one that was current when it was scheduled.
     */
    fun waitFor(urgency: SaveUrgency, nowMillis: Long, writePending: Boolean): Long? = when (urgency) {
        SaveUrgency.NOW -> 0L
        SaveUrgency.SOON -> debounceMillis
        SaveUrgency.ROUTINE -> {
            val last = lastWriteAtMillis
            when {
                writePending -> null
                last != null && nowMillis - last < routineIntervalMillis -> null
                else -> 0L
            }
        }
    }

    /** Records that a write actually reached the disk at [nowMillis]. */
    fun written(nowMillis: Long) {
        lastWriteAtMillis = nowMillis
    }

    companion object {
        /**
         * A burst of taps is one intention, and this is how long the game waits to be sure the
         * burst is over. It is also the most of the player's own doing a crash can cost.
         */
        const val DEBOUNCE_MILLIS = 800L

        /**
         * The longest the clock's derived state is allowed to go unwritten.
         *
         * Comfortably under [Simulation]'s catch-up threshold of three minutes, which is the
         * line above which a gap starts being replayed at the gentler offline rates instead of
         * the live one. Below that line the replay is the same simulation the foreground would
         * have run, so nothing about the pet comes back different.
         */
        const val ROUTINE_INTERVAL_MILLIS = 60_000L
    }
}
