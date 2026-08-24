package com.neopal.pet.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.neopal.pet.domain.FetchNetwork
import com.neopal.pet.domain.FetchableModel
import com.neopal.pet.domain.FetchableModels
import com.neopal.pet.domain.ModelAcceptance
import com.neopal.pet.domain.ModelAccess
import com.neopal.pet.domain.ModelCredential
import com.neopal.pet.domain.ModelDescriptorFault
import com.neopal.pet.domain.ModelFetchPhase
import com.neopal.pet.domain.ModelFetchRules
import com.neopal.pet.domain.ModelNetworkVerdict
import com.neopal.pet.domain.ModelRangeVerdict
import com.neopal.pet.domain.ModelResumePlan
import com.neopal.pet.domain.ModelVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Fetching the on-device brain: sockets, files, and nothing that decides anything.
 *
 * Every judgement this makes is a call into [ModelFetchRules], which is pure and tested — is there
 * room, may this network be spent, is the partial on disk worth resuming, does the server's answer
 * continue *that* file, may the finished bytes be kept. What is left here is the part that cannot
 * be tested on a machine with no Android SDK and no way to reach the publisher: opening the
 * connection, writing the file, and turning a verdict into a sentence.
 *
 * It shares its plumbing with [UpdateService] through [HttpDownload] — the redirect chain, the
 * host pin on every hop, the chunked read, the close-on-cancel hand-off — because the alternative
 * was a second copy of five refusals that would have drifted apart. The reasoning is at the top of
 * that file.
 *
 * ## What is different from the updater, and why each difference exists
 *
 * 1. **It resumes.** Ten megabytes can start again; three gigabytes cannot, or it never finishes.
 *    So a partial is kept across failures and across the app being closed, `Range` asks for the
 *    rest, and the server's answer is checked before a single byte is appended.
 * 2. **It verifies rather than trusts.** The digest of a resumed file is recomputed by reading the
 *    bytes already on disk back off the disk. Remembering the digest state from the attempt that
 *    was interrupted would be faster and would believe a file that something else had touched.
 * 3. **Wi-Fi only unless told otherwise**, decided against `NetworkCapabilities` rather than the
 *    deprecated `NetworkInfo` — see [networkKind].
 * 4. **A refusal is an answer.** The publisher may well decline to serve this to an app that
 *    cannot sign in. That is a first-class outcome with a sentence and a button, not an error.
 *
 * **None of this has been run.** There is no Android SDK in this checkout and the publishing host
 * is unreachable from it, so no byte has been downloaded, no `Range` request has been answered,
 * and no gate has been met. What can be said is that every decision above is under test on a plain
 * JVM, and that the Android between them is written to be as boring as possible for that reason.
 */
class ModelDownloader(
    context: Context,
    private val store: ModelStore = ModelStore(context),
    /**
     * How requests identify themselves.
     *
     * The seam for a sign-in this build does not have. Nothing produces anything but
     * [ModelCredential.None]; when a token flow exists it becomes a new producer of this
     * parameter rather than a change to anything below.
     */
    private val credential: ModelCredential = ModelCredential.None,
) {

    private val app: Context = context.applicationContext

    /**
     * Fetches [model], resuming whatever is already there, and reports as it goes.
     *
     * [onProgress] is called with absolute bytes-done, not deltas, and rarely enough that a
     * notification can be rebuilt from it — see [ModelDownloadPacing].
     */
    suspend fun fetch(
        model: FetchableModel,
        allowMetered: Boolean,
        onProgress: (ModelFetchStatus) -> Unit = {},
    ): ModelFetchStatus = withContext(Dispatchers.IO) {
        val fault = ModelFetchRules.fault(model)
        if (fault != null) return@withContext ModelFetchStatus.Unconfigured(fault)

        // A file that is already here and already the right length is not downloaded again. The
        // commonest way to arrive here twice is a player tapping the same button twice.
        if (store.storedBytes(model) == model.sizeBytes) {
            return@withContext ModelFetchStatus.Stored(
                model = model,
                file = store.fileFor(model),
                verification = store.readVerification(model),
            )
        }

        when (ModelFetchRules.network(networkKind(), allowMetered)) {
            ModelNetworkVerdict.NO_NETWORK ->
                return@withContext ModelFetchStatus.Waiting(model, store.partialBytes(model))
            ModelNetworkVerdict.NEEDS_CONSENT ->
                return@withContext ModelFetchStatus.NeedsConsent(model)
            ModelNetworkVerdict.GO -> Unit
        }

        if (!store.ensureDir()) {
            return@withContext ModelFetchStatus.Failed(model, ModelFetchFailure.CANNOT_WRITE, 0L)
        }

        val part = store.partFor(model)
        var have = store.partialBytes(model)
        when (ModelFetchRules.resume(have, model)) {
            is ModelResumePlan.Discard -> {
                store.discardPartial(model)
                have = 0L
            }
            is ModelResumePlan.FromScratch -> {
                if (have > 0L) store.discardPartial(model)
                have = 0L
            }
            is ModelResumePlan.VerifyOnly -> {
                // Every byte is already here. Nothing to fetch; the only question left is whether
                // they are the right bytes, which is the same question a fresh download ends on.
                onProgress(ModelFetchStatus.Verifying(model, have))
                return@withContext finish(model, part, digestOfWholeFile(part), store.readValidator(model))
            }
            is ModelResumePlan.Resume -> Unit
        }

        val room = ModelFetchRules.roomFor(model, store.usableBytes(), have)
        if (!room.enough) return@withContext ModelFetchStatus.NoRoom(model, room.needed, have)

        try {
            transfer(model, part, have, onProgress)
        } catch (cancelled: CancellationException) {
            // Deliberately *not* deleting the partial: being stopped is the ordinary way a
            // download of this size ends, and throwing the bytes away would be the one thing
            // that makes stopping expensive.
            throw cancelled
        } catch (io: IOException) {
            val space = store.usableBytes()
            val failure = if (space in 0 until MIN_WRITE_HEADROOM) {
                ModelFetchFailure.NO_SPACE
            } else {
                ModelFetchFailure.INTERRUPTED
            }
            ModelFetchStatus.Failed(model, failure, store.partialBytes(model))
        } catch (ignored: Throwable) {
            ModelFetchStatus.Failed(model, ModelFetchFailure.NETWORK, store.partialBytes(model))
        }
    }

    // ------------------------------------------------------------------ the transfer itself

    /**
     * Runs the request-and-append loop until the file is whole or something says stop.
     *
     * More than one leg, because a server is allowed to answer a range request with *part* of the
     * range. Treating that as a failure would turn a legal answer into a retry; treating it as
     * completion would hand on a short file. So each leg is asked for what is still missing, and a
     * leg that delivers nothing at all ends the loop rather than spinning on it.
     */
    private suspend fun transfer(
        model: FetchableModel,
        part: File,
        startedWith: Long,
        onProgress: (ModelFetchStatus) -> Unit,
    ): ModelFetchStatus {
        var have = startedWith
        var digest: MessageDigest? = null
        var serverDigest: String? = store.readValidator(model)?.let { ModelFetchRules.digestFromValidator(it) }
        var legs = 0

        while (have < model.sizeBytes) {
            if (++legs > MAX_LEGS) {
                return ModelFetchStatus.Failed(model, ModelFetchFailure.INTERRUPTED, have)
            }

            val live = LiveConnection()
            val closer = armDisconnectOnCancel(live)
            // Where this leg's pump actually started, which is not always where the leg began:
            // a server that ignores the range resets it to zero. Compared afterwards so that a
            // leg which delivered nothing ends the loop instead of spinning on it.
            var pumpedFrom = have
            try {
                val validator = if (have > 0L) store.readValidator(model) else null
                val opened = HttpDownload.open(
                    startUrl = model.downloadUrl ?: return ModelFetchStatus.Unconfigured(
                        ModelDescriptorFault.UNPINNED_REVISION,
                    ),
                    trusted = { url -> ModelFetchRules.isTrustedModelUrl(url, FetchableModels.TRUSTED_HOSTS) },
                    headers = headers(have, validator),
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = READ_TIMEOUT_MILLIS,
                    onOpen = { live.connection = it },
                )
                val answer = when (opened) {
                    is HttpOpen.Failed -> return ModelFetchStatus.Failed(
                        model,
                        when (opened.reason) {
                            HttpOpenFailure.NETWORK -> ModelFetchFailure.NETWORK
                            HttpOpenFailure.UNTRUSTED_REDIRECT -> ModelFetchFailure.UNTRUSTED_REDIRECT
                            HttpOpenFailure.NO_LOCATION -> ModelFetchFailure.SERVER_ERROR
                        },
                        have,
                    )
                    is HttpOpen.Answered -> opened
                }

                // The unauthenticated request is a probe, and being refused is an expected answer
                // rather than an error. Which sentence the player gets is decided in the domain.
                val access = ModelFetchRules.judgeAccess(answer.code)
                if (access != ModelAccess.OPEN && answer.code != 416) {
                    return if (ModelFetchRules.isGate(access)) {
                        ModelFetchStatus.Blocked(model, access, have)
                    } else {
                        ModelFetchStatus.Failed(model, ModelFetchFailure.of(access), have)
                    }
                }

                val verdict = ModelFetchRules.judgeRange(
                    code = answer.code,
                    contentRange = answer.connection.getHeaderField("Content-Range"),
                    wantFrom = have,
                    total = model.sizeBytes,
                )
                val through: Long
                when (verdict) {
                    is ModelRangeVerdict.FromZero -> {
                        // The server ignored the range and is sending the whole file. Legal, and
                        // the only safe reading: whatever is on disk is abandoned rather than
                        // appended to.
                        if (have > 0L) {
                            store.discardPartial(model)
                            have = 0L
                        }
                        digest = null
                        through = model.sizeBytes - 1L
                    }
                    is ModelRangeVerdict.Continues -> through = verdict.through
                    is ModelRangeVerdict.Refused -> {
                        // A partial the server will not continue is a partial that is worth
                        // nothing. Throw it away and let the next attempt start cleanly rather
                        // than leaving something on the disk that can only fail again.
                        store.discardPartial(model)
                        return ModelFetchStatus.Failed(model, ModelFetchFailure.RESUME_REFUSED, 0L)
                    }
                }

                if (have == 0L) {
                    // Recorded before the first byte, so a resume can offer it back as `If-Range`
                    // and let the server refuse a continuation of a file that has since changed.
                    val validatorNow = ModelFetchRules.normaliseValidator(
                        answer.connection.getHeaderField("ETag")
                            ?: answer.connection.getHeaderField("X-Linked-Etag"),
                    )
                    store.writeValidator(model, validatorNow)
                    serverDigest = ModelFetchRules.digestFromValidator(validatorNow)
                }

                if (digest == null) {
                    digest = HttpDownload.newSha256()
                    if (have > 0L) {
                        // Read back what is already on the disk rather than trusting a digest
                        // carried over from an attempt that did not finish. This is the whole
                        // difference between resuming and hoping.
                        val seeded = HttpDownload.digestPrefix(part, have, digest)
                        if (seeded != have) {
                            store.discardPartial(model)
                            return ModelFetchStatus.Failed(model, ModelFetchFailure.CANNOT_WRITE, 0L)
                        }
                    }
                }

                pumpedFrom = have
                answer.connection.inputStream.use { input ->
                    FileOutputStream(part, have > 0L).use { out ->
                        val pumped = HttpDownload.pump(
                            input = input,
                            out = out,
                            digest = digest,
                            startedAt = have,
                            // A server that keeps sending must not be allowed to fill the phone
                            // while we find out how much it has.
                            limit = minOf(through + 1L, model.sizeBytes),
                            chunkBytes = CHUNK_BYTES,
                            progressStepBytes = ModelDownloadPacing.PROGRESS_STEP_BYTES,
                            onProgress = { done ->
                                onProgress(ModelFetchStatus.Running(model, done, model.sizeBytes))
                            },
                        )
                        have = when (pumped) {
                            is HttpPump.TooLong -> {
                                store.discardPartial(model)
                                return ModelFetchStatus.Failed(model, ModelFetchFailure.SIZE_MISMATCH, 0L)
                            }
                            is HttpPump.Wrote -> pumped.total
                        }
                    }
                }
            } finally {
                closer.cancel()
                live.disconnect()
            }

            if (have <= pumpedFrom) {
                // A leg that delivered nothing. Keep what there is — the next attempt resumes —
                // but do not sit here asking the same question.
                return ModelFetchStatus.Failed(model, ModelFetchFailure.INTERRUPTED, have)
            }
        }

        onProgress(ModelFetchStatus.Verifying(model, have))
        val computed = digest?.digest()?.toHexString() ?: digestOfWholeFile(part)
        return finish(model, part, computed, store.readValidator(model), serverDigest)
    }

    /**
     * The last gate: is what is on the disk the file that was asked for, and may it be kept.
     *
     * The rename is what makes the file real, so nothing may be renamed that has not been through
     * here. A rejected download loses its partial as well — a file that failed its check is not a
     * head start on anything.
     */
    private suspend fun finish(
        model: FetchableModel,
        part: File,
        computedDigest: String?,
        validator: String?,
        knownServerDigest: String? = null,
    ): ModelFetchStatus {
        val serverDigest = knownServerDigest ?: ModelFetchRules.digestFromValidator(validator)
        val verdict = ModelFetchRules.accept(
            model = model,
            bytesOnDisk = part.length(),
            computedDigest = computedDigest,
            serverDigest = serverDigest,
        )
        val accepted = when (verdict) {
            is ModelAcceptance.WrongSize -> {
                // Short: the stream ended early. The bytes are kept, because they are exactly the
                // head start the next attempt needs.
                return ModelFetchStatus.Failed(model, ModelFetchFailure.INTERRUPTED, part.length())
            }
            is ModelAcceptance.WrongDigest -> {
                store.discardPartial(model)
                return ModelFetchStatus.Failed(model, ModelFetchFailure.CORRUPT, 0L)
            }
            is ModelAcceptance.Accepted -> verdict.verification
        }

        val target = store.fileFor(model)
        runCatching { target.delete() }
        if (!runCatching { part.renameTo(target) }.getOrDefault(false)) {
            return ModelFetchStatus.Failed(model, ModelFetchFailure.CANNOT_WRITE, part.length())
        }
        store.writeVerification(model, accepted)
        store.writeValidator(model, null)
        return ModelFetchStatus.Stored(model, target, accepted)
    }

    private suspend fun digestOfWholeFile(file: File): String? = HttpDownload.sha256Of(file)

    private fun headers(from: Long, validator: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/octet-stream"
        headers["User-Agent"] = USER_AGENT
        if (from > 0L) {
            headers["Range"] = "bytes=$from-"
            // Offered so the server itself can refuse to continue a file that has changed since
            // the partial was written. Without it, that check would have to be made afterwards —
            // by which point the wrong bytes are already on the disk.
            if (validator != null) headers["If-Range"] = validator
        }
        when (val credential = credential) {
            is ModelCredential.None -> Unit
            is ModelCredential.Bearer -> headers["Authorization"] = "Bearer ${credential.token}"
        }
        return headers
    }

    /**
     * What kind of connection this is, via `NetworkCapabilities`.
     *
     * **The modern API, deliberately.** The old one is `ConnectivityManager.getActiveNetworkInfo`
     * and the `NetworkInfo` it returns, both deprecated since API 29 and both prone to lying
     * about VPNs and about tethered connections. `getNetworkCapabilities` on the active network
     * answers the two questions that matter here directly: is there a usable internet connection
     * at all, and does the system consider it metered. The neighbouring updater's
     * `isActiveNetworkMetered` is not deprecated, but it collapses "no network" and "metered" into
     * one boolean, which is one distinction too few for a download that waits for Wi-Fi.
     *
     * Needs `ACCESS_NETWORK_STATE`. If that is somehow missing the answer is [FetchNetwork.METERED]
     * — the cautious one, which costs the player a question and never an unasked-for gigabyte.
     */
    private fun networkKind(): FetchNetwork = runCatching {
        val manager = app.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching FetchNetwork.METERED
        val active = manager.activeNetwork ?: return@runCatching FetchNetwork.NONE
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching FetchNetwork.NONE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@runCatching FetchNetwork.NONE
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            FetchNetwork.UNMETERED
        } else {
            FetchNetwork.METERED
        }
    }.getOrDefault(FetchNetwork.METERED)

    companion object {
        private const val USER_AGENT = "NeoPal-ModelFetch"

        private const val CONNECT_TIMEOUT_MILLIS = 20_000

        /**
         * Longer than the updater's fifteen seconds.
         *
         * This is a read timeout on a stream that is expected to be open for an hour on a slow
         * connection; a phone whose radio stalls for twenty seconds mid-file is having a normal
         * evening, and cutting it off there would cost a reconnect for nothing. The download is
         * resumable, so the cost of waiting is smaller than the cost of giving up.
         */
        private const val READ_TIMEOUT_MILLIS = 60_000

        /** Four times the updater's, because this file is three hundred times the size. */
        private const val CHUNK_BYTES = 256 * 1024

        /** A guard against a server that answers every range with a handful of bytes. */
        private const val MAX_LEGS = 64

        /** Below this much free space, an IO failure is read as the disk being full. */
        private const val MIN_WRITE_HEADROOM = 8L * 1024L * 1024L
    }
}

/**
 * How often progress is reported, in one place because two things read it.
 *
 * A notification that is rebuilt on every chunk is a notification that costs more battery than the
 * download does. Sixteen megabytes is roughly two hundred updates across the largest model, which
 * is a moving bar and not a strobe.
 */
object ModelDownloadPacing {
    const val PROGRESS_STEP_BYTES: Long = 16L * 1024L * 1024L
}

/** Why the model did not arrive, or arrived and was not fit to keep. Shown verbatim. */
enum class ModelFetchFailure(val message: String) {
    NETWORK("The download could not be started. Check the connection and try again."),
    INTERRUPTED(
        "The download stopped part way. What arrived is kept, and trying again carries on from " +
            "there rather than starting over.",
    ),
    NO_SPACE("There is not enough free space on this device to finish the download."),
    CANNOT_WRITE("The file could not be written to this device's storage."),
    SERVER_ERROR("The publisher is having trouble serving the download. Try again later."),
    REFUSED("The publisher refused the request."),
    BUSY("The publisher is not answering requests right now. Try again later."),
    UNTRUSTED_REDIRECT(
        "The download was redirected somewhere this app will not follow, so it was abandoned. " +
            "Nothing has been written.",
    ),
    RESUME_REFUSED(
        "The part that had already downloaded cannot be continued — the file on the publisher's " +
            "side is not the one it was started from. It has been thrown away; trying again " +
            "downloads it cleanly.",
    ),
    SIZE_MISMATCH(
        "The file being sent is not the size it was said to be, so it was abandoned rather than " +
            "kept.",
    ),
    CORRUPT(
        "What arrived does not match what was published, so it has been deleted rather than used.",
    );

    companion object {
        /** The refusals that are somebody's afternoon rather than something a player can fix. */
        fun of(access: ModelAccess): ModelFetchFailure = when (access) {
            ModelAccess.BUSY -> BUSY
            ModelAccess.SERVER_ERROR -> SERVER_ERROR
            else -> REFUSED
        }
    }
}

/**
 * Everything the model storage UI shows, as one value.
 *
 * [summary] is the sentence for the screen, and lives here for the same reason [UpdateStatus]'
 * does: every one of these states is reached from a branch that knows exactly why, and a screen
 * reconstructing that from flags is a screen that will one day describe the wrong thing.
 */
sealed interface ModelFetchStatus {

    val summary: String

    /** The phase the pure layer plans against. One exhaustive mapping, checked by the compiler. */
    val phase: ModelFetchPhase

    /**
     * Which model this is about, or null when it is about none of them.
     *
     * Here rather than as a `when` in the screen, and that is not a style preference. A `when`
     * over a sealed type in a composable is exhaustive only as long as somebody keeps it that
     * way, and the local diagnostic harness cannot check it: the status arrives there through
     * `collectAsState`, which has no androidx to resolve against, so its type is unknown and
     * exhaustiveness is unprovable exactly where it would be verified. Declared as a member, the
     * compiler refuses to let an eleventh status exist without answering these two questions.
     */
    val model: FetchableModel?

    /** Bytes of this model on the disk right now, finished or not. */
    val onDiskBytes: Long

    /** Nothing has been asked for. */
    data object Idle : ModelFetchStatus {
        override val summary: String get() = "No brain downloaded."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.ABSENT
        override val model: FetchableModel? get() = null
        override val onDiskBytes: Long get() = 0L
    }

    /** This build cannot fetch the model it knows about, and says which fact is missing. */
    data class Unconfigured(val fault: ModelDescriptorFault) : ModelFetchStatus {
        override val summary: String get() = fault.message
        override val phase: ModelFetchPhase get() = ModelFetchPhase.UNCONFIGURED
        override val model: FetchableModel? get() = null
        override val onDiskBytes: Long get() = 0L
    }

    /** Asked for, and waiting for a connection it is allowed to use. */
    data class Waiting(
        override val model: FetchableModel,
        override val onDiskBytes: Long,
    ) : ModelFetchStatus {
        override val summary: String
            get() = "Waiting for Wi-Fi to download ${model.displayName}."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.WAITING_FOR_WIFI
    }

    /** On mobile data, with no answer yet about whether that is acceptable. */
    data class NeedsConsent(
        override val model: FetchableModel,
        override val onDiskBytes: Long = 0L,
    ) : ModelFetchStatus {
        override val summary: String
            get() = "This connection is metered. Downloading ${model.displayName} would use " +
                "about ${ModelFetchRules.describeBytes(model.sizeBytes)} of mobile data."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.NEEDS_METERED_CONSENT
    }

    data class NoRoom(
        override val model: FetchableModel,
        val neededBytes: Long,
        override val onDiskBytes: Long,
    ) : ModelFetchStatus {
        override val summary: String
            get() = "This download needs about ${ModelFetchRules.describeBytes(neededBytes)} of " +
                "free space and there is not that much."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.NO_ROOM
    }

    data class Running(
        override val model: FetchableModel,
        val doneBytes: Long,
        val totalBytes: Long,
    ) : ModelFetchStatus {
        val fraction: Float get() = ModelFetchRules.progressFraction(doneBytes, totalBytes)

        /** What is done is what is on the disk: this writes straight into the partial file. */
        override val onDiskBytes: Long get() = doneBytes
        override val summary: String
            get() = "Downloading ${model.displayName} — " +
                "${ModelFetchRules.describeBytes(doneBytes)} of " +
                ModelFetchRules.describeBytes(totalBytes) + "."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.DOWNLOADING
    }

    data class Verifying(
        override val model: FetchableModel,
        override val onDiskBytes: Long,
    ) : ModelFetchStatus {
        override val summary: String get() = "Checking the file that arrived."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.VERIFYING
    }

    /** Downloaded, checked, and permanent until the player says otherwise. */
    data class Stored(
        override val model: FetchableModel,
        val file: File,
        val verification: ModelVerification,
    ) : ModelFetchStatus {
        override val summary: String
            get() = "${model.displayName} is on this device — " +
                "${ModelFetchRules.describeBytes(model.sizeBytes)}."
        override val phase: ModelFetchPhase get() = ModelFetchPhase.STORED
        override val onDiskBytes: Long get() = model.sizeBytes
    }

    /** The publisher will not serve it to an app that cannot sign in. */
    data class Blocked(
        override val model: FetchableModel,
        val access: ModelAccess,
        override val onDiskBytes: Long,
    ) : ModelFetchStatus {
        override val summary: String get() = access.message
        override val phase: ModelFetchPhase get() = ModelFetchPhase.GATED
    }

    data class Failed(
        override val model: FetchableModel,
        val failure: ModelFetchFailure,
        override val onDiskBytes: Long,
    ) : ModelFetchStatus {
        override val summary: String get() = failure.message
        override val phase: ModelFetchPhase get() = ModelFetchPhase.FAILED
    }
}
