package com.neopal.pet.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.neopal.pet.MainActivity
import com.neopal.pet.R
import com.neopal.pet.data.ModelDownloader
import com.neopal.pet.data.ModelDownloads
import com.neopal.pet.data.ModelFetchFailure
import com.neopal.pet.data.ModelFetchStatus
import com.neopal.pet.data.ModelStore
import com.neopal.pet.domain.FetchableModel
import com.neopal.pet.domain.FetchableModels
import com.neopal.pet.domain.ModelFetchRules
import java.util.concurrent.TimeUnit

/**
 * The download that does not die because the player left the app.
 *
 * ## Why WorkManager and not a coroutine in a view model
 *
 * A view model's scope ends with the screen; an application-scoped coroutine ends when Android
 * decides the process is not worth keeping, which for a backgrounded app holding a socket open is
 * soon. Neither of those is long enough for a file this size. WorkManager keeps the work across
 * both, restarts it if the process is killed, and — this is the part that matters most here —
 * holds it back until the network constraint is satisfied, which is exactly the "Wi-Fi only"
 * promise expressed as something the system enforces rather than something this app remembers to.
 *
 * The constraint and the check inside [ModelDownloader] are not redundant. The constraint decides
 * *when the work runs at all*; the check decides what to do at the moment of the first byte, which
 * is a different instant and can disagree — a phone that hands off from Wi-Fi to mobile data
 * mid-download does so without WorkManager stopping anything.
 *
 * ## Why it runs in the foreground
 *
 * An hour of downloading with nothing in the shade is an app that looks like it is doing nothing
 * and is quietly using somebody's battery and connection. A foreground service makes the work
 * visible, makes it survivable, and gives the player the one control that matters: they can see
 * it, and they know where to go to stop it.
 *
 * ## What has never been run
 *
 * All of it. There is no Android SDK in this checkout, so this file has never been compiled by the
 * real toolchain and no notification it describes has ever been on a screen. It is written to be
 * as boring as possible for that reason: no arithmetic, no condition that is not either a platform
 * version check or a direct read of a decision made somewhere that is under test.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /**
     * The catalogue entry this run is for, looked up by id rather than carried in the input.
     *
     * A worker's input survives a process restart and a reinstall of the same app; a URL and a
     * size carried in it would not survive the catalogue changing underneath, and the entry is
     * the thing that says what the bytes are supposed to be.
     */
    private val requested: FetchableModel? by lazy {
        inputData.getString(KEY_MODEL_ID)?.let(FetchableModels::byId)
    }

    override suspend fun doWork(): Result {
        val model = requested ?: return Result.failure()
        val allowMetered = inputData.getBoolean(KEY_ALLOW_METERED, false)

        // Announced before the first byte so that the shade and the screen agree from the start.
        ModelDownloads.publish(ModelFetchStatus.Running(model, startingBytes(model), model.sizeBytes))
        runCatching { setForeground(foregroundInfo(model, 0L)) }

        val outcome = ModelDownloader(applicationContext).fetch(
            model = model,
            allowMetered = allowMetered,
        ) { progress ->
            ModelDownloads.publish(progress)
            if (progress is ModelFetchStatus.Running) {
                // A best effort: the notification is a courtesy and must never be the reason a
                // download fails. On Android 13 and up it is not shown at all without the
                // notification permission, and the work carries on regardless.
                runCatching { notify(model, progress.doneBytes) }
            }
        }

        ModelDownloads.publish(outcome)
        return when (outcome) {
            is ModelFetchStatus.Stored -> Result.success()

            // The failures that are a bad minute rather than a bad plan. WorkManager backs off
            // and tries again, and because the partial is kept, a retry is cheap — it asks for
            // the rest of the file, not for the file.
            is ModelFetchStatus.Failed -> when (outcome.failure) {
                ModelFetchFailure.INTERRUPTED,
                ModelFetchFailure.NETWORK,
                ModelFetchFailure.BUSY,
                ModelFetchFailure.SERVER_ERROR,
                -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                else -> Result.failure()
            }

            // Waiting for a network is not a failure. Letting WorkManager retry it means the
            // download starts itself when the Wi-Fi comes back, which is the whole point of
            // having asked for it before going out.
            is ModelFetchStatus.Waiting -> Result.retry()

            // A gate, a consent question, no room, a broken catalogue entry: all of them need a
            // person, and retrying them on a timer would be a battery cost with no possible
            // outcome.
            else -> Result.failure()
        }
    }

    private fun startingBytes(model: FetchableModel): Long =
        runCatching { ModelStore(applicationContext).partialBytes(model) }
            .getOrDefault(0L)

    // ------------------------------------------------------------------ the shade

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = requested ?: FetchableModels.known.first()
        return foregroundInfo(model, startingBytes(model))
    }

    private fun foregroundInfo(model: FetchableModel, doneBytes: Long): ForegroundInfo {
        val notification = build(model, doneBytes)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // From API 34 a foreground service must declare what it is for, and this is a long
            // transfer the user asked for by name.
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(model: FetchableModel, doneBytes: Long) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, build(model, doneBytes))
    }

    private fun build(model: FetchableModel, doneBytes: Long): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Percent rather than bytes, because a progress bar is what this is for and because the
        // maximum has to be an Int while the file is measured in gigabytes.
        val percent = (ModelFetchRules.progressFraction(doneBytes, model.sizeBytes) * 100f).toInt()
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading ${model.displayName}")
            .setContentText(
                "${ModelFetchRules.describeBytes(doneBytes)} of " +
                    ModelFetchRules.describeBytes(model.sizeBytes),
            )
            .setProgress(100, percent, doneBytes <= 0L)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    /**
     * Its own channel, created here rather than added to [com.neopal.pet.data.Notifier].
     *
     * Not tidiness: the two channels there are about the creature, and a player who switches off
     * "milestones" has said nothing about whether they want to see a download they started. A
     * separate channel is the honest version of that distinction, and it keeps this feature's
     * files to itself while other work is going on in that one.
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL,
            "Model download",
            // Low: it is a progress bar. It has to be visible, it must never make a sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while the on-device brain is downloading."
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val UNIQUE_NAME = "neopal_model_download"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_ALLOW_METERED = "allow_metered"
        private const val CHANNEL = "neopal_model_download"
        private const val NOTIFICATION_ID = 1002

        /** Enough to ride out an evening of bad signal; few enough to stop eventually. */
        private const val MAX_ATTEMPTS = 20

        /**
         * Asks for [model], starting when the network allows it.
         *
         * [ExistingWorkPolicy.REPLACE] rather than KEEP: the only way to arrive here twice is a
         * player changing their mind — usually about mobile data — and the new answer is the one
         * they mean.
         */
        fun enqueue(context: Context, model: FetchableModel, allowMetered: Boolean) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to model.id,
                        KEY_ALLOW_METERED to allowMetered,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        // The Wi-Fi-only promise, enforced by the system rather than remembered
                        // by this app. With the override it drops to "any connection at all",
                        // which is still a constraint worth having: it is what stops the work
                        // waking up to fail with no network.
                        .setRequiredNetworkType(
                            if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                        )
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Stops the download and leaves the partial file exactly where it is. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
