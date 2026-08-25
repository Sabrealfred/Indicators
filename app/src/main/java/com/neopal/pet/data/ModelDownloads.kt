package com.neopal.pet.data

import android.content.Context
import com.neopal.pet.domain.FetchableModel
import com.neopal.pet.domain.FetchableModels
import com.neopal.pet.domain.ModelDescriptorFault
import com.neopal.pet.domain.ModelFetchRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Where the model download's progress lives, so that it outlives the screen showing it.
 *
 * ## Why a process-wide object and not a view model
 *
 * The requirement is "progress that survives the screen going away". A view model scoped to the
 * settings route is cleared the moment the player backs out of it, and a download that reported
 * into one would appear to restart every time somebody went to look at their creature and came
 * back. So the state lives for the life of the *process*, which is the only scope that matches
 * how long the thing being described actually lasts.
 *
 * The download itself is not here — it is a [com.neopal.pet.work.ModelDownloadWorker], which is
 * what makes it survive the app being left entirely. This object is how the worker and any screen
 * agree about where it has got to, and the split is deliberate: work that outlives the process
 * needs WorkManager, and a screen that opens mid-download needs a value it can read at once.
 *
 * ## What happens when the process *does* die
 *
 * The flow starts at [ModelFetchStatus.Idle] again, because it is memory and memory did not
 * survive. That is not a lie for long: [refresh] rebuilds an honest state from what is on the
 * disk, and a worker that WorkManager restarts publishes into it again within a chunk. The one
 * thing that must never happen — a screen claiming a download is running when nothing is —
 * cannot, because Idle is the starting point rather than a remembered Running.
 */
object ModelDownloads {

    private val statusFlow = MutableStateFlow<ModelFetchStatus>(ModelFetchStatus.Idle)

    /** One flow, so a screen has a single thing to observe. */
    val status: StateFlow<ModelFetchStatus> = statusFlow.asStateFlow()

    /** Called by the worker as it goes. Cheap and safe from any thread. */
    fun publish(next: ModelFetchStatus) {
        statusFlow.value = next
    }

    /**
     * Which model this build would offer.
     *
     * The first one it can actually fetch, which today is none of them — the catalogue is
     * complete except for the pinned revisions, and [FetchableModels.fetchable] refuses an entry
     * without one. When they land, this becomes the smallest of the three.
     *
     * **A seam, not a decision.** *Which* model a given phone should be offered is the question of
     * what that phone can hold in memory while it runs, and that belongs to whoever owns the
     * engine and the device-fit rules. When there is a real answer, it replaces the body of this
     * one function and nothing else changes.
     */
    fun offered(): FetchableModel? = FetchableModels.fetchable.firstOrNull()

    /**
     * Rebuilds the status from what is on the disk.
     *
     * Called when a screen opens, and after a delete. It never invents a running download: the
     * most it will claim is that some bytes are present, which is a fact it just read.
     */
    suspend fun refresh(context: Context) {
        val model = offered()
        if (model == null) {
            // Not silence: the panel says which fact is missing. Anything in this project that
            // decides not to act says so out loud, because two of its bugs were features that
            // quietly did nothing and looked exactly like features that worked.
            val fault = FetchableModels.known.firstNotNullOfOrNull { ModelFetchRules.fault(it) }
                ?: ModelDescriptorFault.UNPINNED_REVISION
            publish(ModelFetchStatus.Unconfigured(fault))
            return
        }
        // A download in flight is a better answer than anything the disk can give.
        if (statusFlow.value is ModelFetchStatus.Running) return

        val store = ModelStore(context)
        val next = withContext(Dispatchers.IO) {
            val stored = store.storedBytes(model)
            val partial = store.partialBytes(model)
            when {
                stored == model.sizeBytes -> ModelFetchStatus.Stored(
                    model = model,
                    file = store.fileFor(model),
                    verification = store.readVerification(model),
                )
                partial > 0L -> ModelFetchStatus.Failed(
                    model = model,
                    failure = ModelFetchFailure.INTERRUPTED,
                    onDiskBytes = partial,
                )
                else -> ModelFetchStatus.Idle
            }
        }
        publish(next)
    }

    /** Everything this feature is using on the disk, for the line in Settings. */
    suspend fun storedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        ModelStore(context).totalBytes()
    }

    /**
     * Throws away everything downloaded, finished or not.
     *
     * The one press in Settings. It does not stop a running download — the caller does that
     * first, because deleting the file out from under a writer is how you get a file that is
     * neither deleted nor whole.
     */
    suspend fun deleteEverything(context: Context): Boolean {
        val ok = ModelStore(context).deleteEverything()
        refresh(context)
        return ok
    }
}
