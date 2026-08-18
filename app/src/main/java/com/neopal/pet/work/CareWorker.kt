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
 * Keeps the world turning while the app is closed. Runs the same [Simulation] the UI uses,
 * writes the result back to the save, and posts at most one reminder per run.
 */
class CareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = PetRepository(applicationContext)
        val state = repo.currentState() ?: return Result.success()
        val config = repo.currentConfig()

        val result = Simulation.advance(state, System.currentTimeMillis(), config)
        repo.save(result.state)

        if (config.notificationsEnabled) {
            Notifications.careMessage(result.state)?.let { (title, text) ->
                Notifications.notifyCare(applicationContext, title, text)
            }
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
