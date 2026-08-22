package com.neopal.pet.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neopal.pet.data.PetRepository
import com.neopal.pet.domain.Simulation
import java.util.concurrent.TimeUnit

/**
 * Looks in on the creature while the app is closed, and says something if it is worth saying.
 *
 * **It deliberately does not write.** That is the whole design of this class and it was arrived
 * at the hard way, so it is worth setting down.
 *
 * [Simulation.advance] computes its two protections once per call, from the state it is handed:
 * the twelve-hour ceiling on a single catch-up, and the health floor that exists so that "losing
 * a pet should be something you did, not something that happened while you slept". Both are
 * properties of *an absence*. This worker used to advance and save every fifteen minutes, which
 * turned one absence into ninety-six of them — so the ceiling applied ninety-six times over, and
 * the floor vanished the moment a chop rolled illness, because every later chop entered already
 * sick and `protectHealth` is `isCatchUp && !state.isSick`.
 *
 * Measured over sixty seeds on a healthy, well-fed creature: a single catch-up killed **none** at
 * any absence length. The same absence in fifteen-minute chops killed 27 of 60 at twelve hours
 * and **60 of 60 at forty-eight**. A sixty-minute cadence was no better, so waiting for Doze to
 * defer it does not help. The player closed the app on a healthy creature and came back to a
 * corpse, and the save on disk agreed.
 *
 * Nothing is lost by not writing. The simulation is driven from `lastTickMillis`, so whenever the
 * app next opens it catches up from the real elapsed time in one call — with both protections
 * intact. Not writing also removes this worker from the race with the view model's own writes,
 * which is a second bug it no longer has.
 */
class CareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = PetRepository(applicationContext)
        val state = repo.currentState() ?: return Result.success()
        val config = repo.currentConfig()
        if (!config.notificationsEnabled) return Result.success()

        // Advanced into a local value and thrown away. This is a question — "would the creature
        // want me by now?" — and asking it must not change the answer.
        val glimpse = Simulation.advance(state, System.currentTimeMillis(), config).state
        Notifications.careMessage(glimpse)?.let { (title, text) ->
            Notifications.notifyCare(applicationContext, title, text)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "neopal_care_worker"

        /** 15 minutes is the floor WorkManager allows for periodic work. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CareWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
