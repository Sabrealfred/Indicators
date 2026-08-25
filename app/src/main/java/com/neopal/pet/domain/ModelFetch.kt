package com.neopal.pet.domain

/**
 * Fetching and keeping the on-device brain: every decision, none of the Android.
 *
 * The creature can be given a language model that lives on the phone. The file is not in the APK
 * and never will be — it is between half a gigabyte and three and a half, the game is complete
 * without it, and it is downloaded on purpose by a player who has been told what it costs. This
 * file is the half of that story which can be wrong quietly, so it is the half that is pure and
 * tested: is there room, may this network be spent, does the thing on disk deserve to be resumed,
 * did the server answer about the file we asked for, and what does the player see.
 *
 * The Android half ([com.neopal.pet.data.ModelStore] and [com.neopal.pet.data.ModelDownloader])
 * opens sockets and writes files and does no arithmetic that is not decided here.
 *
 * ## Where the numbers come from
 *
 * The repository ids, file names and byte counts in [FetchableModels] are read off the allowlist
 * that Google's own on-device sample ships, not recalled: they are exact, and the URL they are
 * assembled into is the shape that app uses —
 * `https://huggingface.co/{repoId}/resolve/{revision}/{fileName}?download=true`. The revision is
 * a commit hash rather than a branch on purpose. A branch would let the bytes change underneath a
 * download that is being resumed across a night, and there is no published checksum to catch it.
 *
 * ## What is still missing, and is therefore still a parameter
 *
 * 1. ~~**The commit hash.**~~ **Filled in.** The publishing host is still unreachable from the
 *    machine this was written on — `huggingface.co` is refused at the proxy gateway — so the
 *    revisions were not read from it. They come from the same place every other number in this
 *    file does: the allowlist Google's own on-device app ships, which pins each model to a commit
 *    precisely because a branch would let the bytes move. That is a better source than a
 *    `git ls-remote` would have been, because it is the revision Google itself ships against.
 *
 *    [ModelFetchRules.fault] still refuses a blank revision, and [FetchableModels.UNPINNED] still
 *    exists, because the next model added to this catalogue will start blank and must not be
 *    silently fetchable.
 * 2. **The checksum.** The allowlist gives sizes and no digests, and a fabricated digest would be
 *    worse than none — it would fail every download for a reason nobody could act on. So
 *    [FetchableModel.sha256] is nullable, and what a download is worth without one is spelled out
 *    at [ModelVerification].
 * 3. **Whether the repository is gated.** Google's own app treats an unauthenticated fetch as a
 *    *probe*: on anything other than 200 it starts a sign-in and an agreement flow. So a non-200
 *    here is a first-class answer with a sentence and a button — see [ModelAccess] — and not an
 *    error toast. This build cannot sign anybody in; what it can do is say exactly what is being
 *    refused and open the page where the player can agree to it.
 *
 * ## Why resume gets this much attention
 *
 * The updater next door downloads about ten megabytes: an interruption costs a retry and nobody
 * notices. This downloads up to three and a half thousand megabytes, on a phone, over a night. It
 * *will* be interrupted — the screen locks, the Wi-Fi drops, the process is killed for memory,
 * the player walks out of the house. A download that starts from zero every time never finishes,
 * and a download that blindly appends to whatever is on disk eventually hands the engine two
 * halves of two different files glued together.
 *
 * So resuming is a chain of small refusals, and each link is a function below:
 * [ModelFetchRules.resume] decides whether the bytes on disk may be built on at all,
 * [ModelFetchRules.judgeRange] decides whether the server's answer actually continues *that*
 * file, the validator recorded when the partial started is offered back as `If-Range` so the
 * server itself can refuse a stale resume, and the digest is recomputed over the bytes read back
 * off the disk rather than carried forward from the interrupted attempt. Nothing on disk is
 * trusted; everything is re-derived.
 */

/**
 * One model that can be fetched, described completely enough to verify it.
 *
 * Every field is a fact about a published artefact rather than a preference, and all of them are
 * needed *before* the first byte arrives: the size decides whether there is room and lets a short
 * file be caught the moment it ends, and the digest — when there is one — is the only thing that
 * can tell a finished download from a plausible one.
 */
data class FetchableModel(
    /** Stable key. Survives in saved state, so it must not change when a display name does. */
    val id: String,
    /** What the player is offered, e.g. "the small brain". */
    val displayName: String,
    /** Publisher's repository, `owner/name`. */
    val repoId: String,
    /** The file inside that repository. A bare file name: no directories, no traversal. */
    val fileName: String,
    /**
     * The commit the file is pinned to.
     *
     * Blank means unpinned, which is refused: see the file header. A branch name would compile
     * and would work and would quietly break resume the day the branch moves.
     */
    val revision: String,
    /** Exact length in bytes. A file that is not exactly this is not this model. */
    val sizeBytes: Long,
    /**
     * Lowercase hex SHA-256 of the whole file, or null when the publisher does not give one.
     *
     * Null is honest, not lazy — see [ModelVerification] for what is lost with it and what still
     * catches the failures that actually happen.
     */
    val sha256: String? = null,
) {
    /** The repository's own page: where a licence is read and a gate is agreed to. */
    val pageUrl: String get() = "https://${ModelFetchRules.PUBLISHER_HOST}/$repoId"

    /** Where the bytes come from. Null when this entry is not pinned to a revision. */
    val downloadUrl: String?
        get() = if (revision.isBlank()) {
            null
        } else {
            "https://${ModelFetchRules.PUBLISHER_HOST}/$repoId/resolve/$revision/$fileName?download=true"
        }
}

/**
 * The artefacts this build knows how to fetch, and where they are allowed to come from.
 *
 * Three, not two. The design document weighed a 2.4 GB model against a 3.4 GB one; the small one
 * below is neither, and it is the only one of the three a download over mobile data is even
 * arguable for. Which of them a given phone should be *offered* is not decided here — that is the
 * question of what a phone can hold in memory, and it belongs to whoever owns that.
 */
object FetchableModels {

    /**
     * Hosts a model may be fetched from, checked on the first request and on every redirect.
     *
     * An entry beginning with a dot is a suffix pin (`.hf.co` matches `cdn-lfs-us-1.hf.co` and
     * not `hf.co.evil.test`); anything else is an exact host. Same shape as the updater's pin and
     * for the same reason: a redirect is where a download gets walked somewhere else.
     *
     * The first entry is the address in the URL and is verified. **The other two are not**: a
     * download from that host answers with a redirect to whichever content network is serving,
     * and the host it lands on could not be observed from the machine this was written on. Both
     * are domains the same publisher owns, and the failure if they are wrong is loud and specific
     * rather than silent — the download stops and says it was redirected somewhere this app will
     * not follow, which names exactly what has to be added here.
     */
    val TRUSTED_HOSTS: Set<String> = setOf(
        "huggingface.co",
        ".hf.co",
        ".huggingface.co",
    )

    /**
     * Small enough to argue about over mobile data, and the only one of the three that is.
     *
     * Whether one billion parameters is enough for a creature that speaks in two-line sentences
     * is a measurement nobody has made yet. It is the cheapest way to find out.
     */
    val SMALL = FetchableModel(
        id = "gemma3-1b-it-int4",
        displayName = "the small brain",
        repoId = "litert-community/Gemma3-1B-IT",
        fileName = "gemma3-1b-it-int4.litertlm",
        revision = "42d538a932e8d5b12e6b3b455f5572560bd60b2c",
        sizeBytes = 584_417_280L,
    )

    val MEDIUM = FetchableModel(
        id = "gemma-4-e2b-it",
        displayName = "the everyday brain",
        repoId = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        revision = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        sizeBytes = 2_588_147_712L,
    )

    val LARGE = FetchableModel(
        id = "gemma-4-e4b-it",
        displayName = "the big brain",
        repoId = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        revision = "28299f30ee4d43294517a4ac93abd6163412f07f",
        sizeBytes = 3_659_530_240L,
    )

    val known: List<FetchableModel> = listOf(SMALL, MEDIUM, LARGE)

    /**
     * The subset that can actually be downloaded by this build.
     *
     * All three today. It was empty until the revisions were filled in, and the machinery for
     * that state is kept rather than deleted: the next model added here starts unpinned, and must
     * be refused loudly rather than offered as a download that cannot resolve. This project has
     * shipped two bugs whose only symptom was a working-looking screen doing nothing at all, and
     * the rule that came out of them is that anything which decides *not* to act says so out loud.
     */
    val fetchable: List<FetchableModel> get() = known.filter { ModelFetchRules.fault(it) == null }

    fun byId(id: String): FetchableModel? = known.firstOrNull { it.id == id }

    /** A revision that has not been filled in yet. */
    const val UNPINNED = ""
}

/** Why a [FetchableModel] cannot be fetched as described. Each one is a fault in the catalogue. */
enum class ModelDescriptorFault(val message: String) {
    NO_ID("This model has no id, so nothing could keep track of it."),
    BAD_REPO_ID("This model does not say which repository it comes from."),
    UNPINNED_REVISION(
        "This build does not have a pinned revision for this model, so there is no exact file " +
            "for it to ask for. Nothing is broken; the fact is simply not filled in yet.",
    ),
    UNTRUSTED_URL("This model's address is not one this build will download from."),
    BAD_FILE_NAME("This model's file name is not a plain file name."),
    BAD_SIZE("This model's declared size is not a size a model file could have."),
    BAD_DIGEST("This model's checksum is not a SHA-256, so a download could not be verified."),
}

/**
 * How much a finished download is really worth.
 *
 * Ordered weakest last, and the difference matters enough to be said on screen:
 *
 * - [PUBLISHED_DIGEST] — the publisher said what the bytes hash to, and they do. This catches a
 *   truncated transfer, a corrupted one, a caching proxy that served something stale, and a file
 *   that was replaced between two halves of a resumed download.
 * - [SERVER_DIGEST] — no published digest, but the response carried a validator that is itself a
 *   SHA-256 of the stored object, and the bytes match it. Weaker: it is the same party attesting
 *   to what it just sent. It still catches every accident, which is what actually happens.
 * - [SIZE_ONLY] — the length is exactly right and nothing else could be checked. This catches the
 *   dominant real failure (a transfer that stopped early) and misses a corrupted middle. Against
 *   a network attacker it is worth nothing on its own — but the transport is TLS to a pinned
 *   host, which is where that protection was always coming from.
 */
enum class ModelVerification { PUBLISHED_DIGEST, SERVER_DIGEST, SIZE_ONLY }

/** Whether a downloaded file may be kept, and why not. */
sealed interface ModelAcceptance {
    data class Accepted(val verification: ModelVerification) : ModelAcceptance
    data object WrongSize : ModelAcceptance
    data object WrongDigest : ModelAcceptance
}

/**
 * What the publisher said when asked for the file without signing in.
 *
 * Google's own app treats the unauthenticated request as a probe and expects it to be refused:
 * on anything but 200 it goes off to sign the player in and have them accept an agreement, and
 * only then retries. This build cannot do the sign-in half — no OAuth flow here has ever been
 * run, and inventing one would be inventing the part that has to be tested against a real
 * service. What it does instead is name the refusal exactly and hand the player the page where
 * the refusal can be lifted.
 */
enum class ModelAccess(val message: String) {
    /** The file is being served. */
    OPEN("Open."),

    /**
     * Refused pending an agreement. The player has to accept the model's terms on its own page.
     */
    NEEDS_AGREEMENT(
        "This model is gated: the publisher will not serve it until you have opened its page, " +
            "signed in there and accepted its terms. NeoPal cannot do that for you.",
    ),

    /** Refused pending a sign-in. Same remedy from the player's side: the page. */
    NEEDS_SIGN_IN(
        "The publisher will not serve this model to an app that is not signed in. NeoPal has no " +
            "way to sign in to it, so this model cannot be downloaded from inside the game.",
    ),

    /**
     * Not found — which for a gated repository is sometimes what "not for you" looks like.
     *
     * Said as both possibilities rather than as a flat "gone", because the two are
     * indistinguishable from here and the player's next move (open the page) is the same.
     */
    NOT_FOUND(
        "The publisher does not have this file at this revision — or it does, and will not admit " +
            "it to an app that is not signed in. Its page will say which.",
    ),

    /** Too many requests, or the publisher is having a bad day. Try later. */
    BUSY("The publisher is not answering requests right now. Try again later."),

    SERVER_ERROR("The publisher is having trouble at its end. Nothing is wrong with this app."),

    REFUSED("The publisher refused the request."),
}

/**
 * How a request identifies itself, if at all.
 *
 * The seam for the sign-in that this build does not have. [None] is what ships; [Bearer] exists so
 * that the request-building code has always had a place to put a token, and so that adding the
 * flow later is a new producer of this type rather than a change to the downloader.
 *
 * Nothing in this build produces a [Bearer], and no code path here has ever been run against a
 * gated repository.
 */
sealed interface ModelCredential {
    data object None : ModelCredential
    data class Bearer(val token: String) : ModelCredential
}

/** Whether the connection in front of us may be spent, and how much of it. */
enum class FetchNetwork {
    /** No usable network at all. */
    NONE,

    /** A connection the player pays for by the byte. */
    METERED,

    /** Wi-Fi, Ethernet, or a mobile connection the system reports as unmetered. */
    UNMETERED,
}

/** What to do about the network in front of us. */
enum class ModelNetworkVerdict {
    GO,

    /** Metered, and the player has not said it is fine. Ask, with the size in the question. */
    NEEDS_CONSENT,

    /** Nothing to download over. Not a failure: a state that resolves itself. */
    NO_NETWORK,
}

/** Whether the file fits, and what it would take. */
data class ModelSpaceVerdict(
    /** Bytes still to be written, plus headroom. */
    val needed: Long,
    /** What the app may actually use, or a negative number when that could not be read. */
    val usable: Long,
    val enough: Boolean,
)

/** What to do with whatever is already on disk. */
sealed interface ModelResumePlan {
    /** Nothing usable is there. Write from byte zero. */
    data object FromScratch : ModelResumePlan

    /** Ask for `bytes=$from-` and append. */
    data class Resume(val from: Long) : ModelResumePlan

    /** The partial is already the full declared length. Do not fetch; check what is there. */
    data object VerifyOnly : ModelResumePlan

    /** Longer than the model is. Whatever it is, it is not this. Delete it and start again. */
    data object Discard : ModelResumePlan
}

/** A parsed `Content-Range` response header. [total] is null for the `*` form. */
data class ModelContentRange(val first: Long, val last: Long, val total: Long?)

/** Why a ranged request's answer cannot be appended to what is on disk. */
enum class ModelRangeFault(val message: String) {
    UNPARSEABLE("The server answered a partial download without saying which part."),
    WRONG_START("The server sent a different part of the file than the one asked for."),
    WRONG_TOTAL("The file on the server is not the length this download expects."),
    EMPTY("The server said there is nothing left to send, which cannot be true here."),
    NOT_SATISFIABLE("The server says the part already downloaded is past the end of the file."),
}

/** What a download response means for the bytes already on disk. */
sealed interface ModelRangeVerdict {
    /**
     * The server is sending the whole file. Legal even when a range was asked for, and the only
     * safe reading is to throw the partial away and write from zero.
     */
    data object FromZero : ModelRangeVerdict

    /** The server honoured the range: append, starting at [at], expecting up to [through]. */
    data class Continues(val at: Long, val through: Long) : ModelRangeVerdict

    /** Nothing may be appended. */
    data class Refused(val why: ModelRangeFault) : ModelRangeVerdict
}

/**
 * The arithmetic and the refusals. Everything here is a pure function of numbers and strings.
 */
object ModelFetchRules {

    /** The host in every catalogue URL. Verified as the shape Google's own sample uses. */
    const val PUBLISHER_HOST = "huggingface.co"

    /** Bigger than any phone-sized model; a guard against a catalogue entry with a silly size. */
    const val MAX_MODEL_BYTES: Long = 8L * 1024L * 1024L * 1024L

    /** Smaller than any real model, and large enough that an error page cannot pass for one. */
    const val MIN_MODEL_BYTES: Long = 16L * 1024L * 1024L

    /**
     * Kept free beyond the file itself.
     *
     * Much smaller than the updater's headroom, and deliberately: an APK has to be downloaded
     * *and* staged a second time by the installer, so that path needs twice the file plus room.
     * Nothing copies this file. It is written once and read in place, so the only reason for
     * headroom at all is that a phone with literally zero bytes free stops working in ways that
     * have nothing to do with us.
     */
    const val STORAGE_HEADROOM_BYTES: Long = 256L * 1024L * 1024L

    /**
     * Below this, a partial is not worth resuming.
     *
     * A range request has its own failure modes — a proxy that ignores it, a server that answers
     * about a file that has since changed — and taking that risk to save a few hundred kilobytes
     * of a three-gigabyte download is a bad trade. Above a megabyte it always pays.
     */
    const val MIN_RESUME_BYTES: Long = 1L * 1024L * 1024L

    private const val SHA256_HEX_CHARS = 64
    private const val MAX_VALIDATOR_CHARS = 128

    // ------------------------------------------------------------------ the catalogue entry

    /**
     * Whether a catalogue entry describes something that can be fetched safely, and what is wrong
     * if not.
     *
     * Checked before a socket is opened rather than trusted, because it is a constant in this
     * source file: a constant is exactly the kind of thing that gets edited in a hurry, and every
     * field here is load-bearing for either where the bytes come from or whether they are the
     * right ones.
     */
    fun fault(
        model: FetchableModel,
        hosts: Set<String> = FetchableModels.TRUSTED_HOSTS,
    ): ModelDescriptorFault? {
        if (model.id.isBlank()) return ModelDescriptorFault.NO_ID
        if (!isRepoId(model.repoId)) return ModelDescriptorFault.BAD_REPO_ID
        if (!isPlainFileName(model.fileName)) return ModelDescriptorFault.BAD_FILE_NAME
        if (!isPinnedRevision(model.revision)) return ModelDescriptorFault.UNPINNED_REVISION
        val url = model.downloadUrl ?: return ModelDescriptorFault.UNPINNED_REVISION
        if (!isTrustedModelUrl(url, hosts)) return ModelDescriptorFault.UNTRUSTED_URL
        if (model.sizeBytes < MIN_MODEL_BYTES || model.sizeBytes > MAX_MODEL_BYTES) {
            return ModelDescriptorFault.BAD_SIZE
        }
        val digest = model.sha256
        if (digest != null && !isSha256Hex(digest)) return ModelDescriptorFault.BAD_DIGEST
        return null
    }

    /**
     * Whether a URL may be fetched as part of a model download.
     *
     * The same shape as [AppVersion.isTrustedReleaseUrl] and for the same reasons, but with the
     * host list passed in: the publisher's content network is a fact this build has not been able
     * to observe, and a pin is worth more when the thing being pinned can be corrected in one
     * place without touching the checking.
     *
     * An empty [hosts] therefore trusts *nothing*, which is the correct behaviour for a build
     * that has not been told where a model may come from.
     */
    fun isTrustedModelUrl(url: String, hosts: Set<String>): Boolean {
        if (hosts.isEmpty()) return false
        val host = AppVersion.httpsHostOrNull(url) ?: return false
        return hosts.any { pin ->
            val lower = pin.lowercase()
            if (lower.startsWith(".")) host.endsWith(lower) else host == lower
        }
    }

    /** `owner/name`, and nothing that could climb out of that shape into another path. */
    fun isRepoId(repoId: String): Boolean {
        if (repoId.isEmpty() || repoId.length > 128) return false
        val parts = repoId.split('/')
        if (parts.size != 2) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part != "." &&
                part != ".." &&
                part.none { it.isWhitespace() || it == '?' || it == '#' || it == '\\' }
        }
    }

    /**
     * A name that becomes a file inside our own directory and nothing else.
     *
     * The name comes from a constant today. It is checked anyway, because the whole point of
     * keeping it a parameter is that one day it may come from somewhere less trustworthy, and
     * `../../databases/pet.db` is the oldest trick there is.
     */
    fun isPlainFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 128) return false
        if (name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it.isWhitespace() || it == '?' || it == '#' }
    }

    /**
     * A full commit hash, and nothing shorter.
     *
     * A branch name would work perfectly and would be wrong: the point of the pin is that the
     * bytes cannot change between two halves of a resumed download. An abbreviated hash is
     * refused too — it is still a name that can come to mean a different commit.
     */
    fun isPinnedRevision(revision: String): Boolean =
        revision.length == 40 && revision.all { it in '0'..'9' || it in 'a'..'f' }

    fun isSha256Hex(digest: String): Boolean =
        digest.length == SHA256_HEX_CHARS && digest.all { it in '0'..'9' || it in 'a'..'f' }

    // ------------------------------------------------------------------ room, network, resume

    /**
     * Whether the rest of the download fits.
     *
     * [alreadyOnDisk] is subtracted because a resumed download only has to find room for what is
     * left — the commonest reason to ask this question twice is a resume, and answering "you need
     * three gigabytes" to someone who already has two and a half of them would refuse a download
     * that was about to finish.
     *
     * A negative [usableBytes] means the platform would not say, and is read as "enough": the
     * write itself will fail honestly if it is not, and refusing on an unreadable number would
     * make the feature unavailable on whichever ROM answers oddly.
     */
    fun roomFor(model: FetchableModel, usableBytes: Long, alreadyOnDisk: Long = 0L): ModelSpaceVerdict {
        val have = alreadyOnDisk.coerceIn(0L, model.sizeBytes)
        val needed = model.sizeBytes - have + STORAGE_HEADROOM_BYTES
        return ModelSpaceVerdict(
            needed = needed,
            usable = usableBytes,
            enough = usableBytes < 0L || usableBytes >= needed,
        )
    }

    /**
     * Whether this network may be spent on gigabytes.
     *
     * Wi-Fi only by default, and the override is a separate answer rather than a setting that
     * quietly stays on: gigabytes over mobile data is done once and never forgiven, so it is
     * asked per download with the size in the question.
     */
    fun network(kind: FetchNetwork, allowMetered: Boolean): ModelNetworkVerdict = when (kind) {
        FetchNetwork.NONE -> ModelNetworkVerdict.NO_NETWORK
        FetchNetwork.METERED -> if (allowMetered) ModelNetworkVerdict.GO else ModelNetworkVerdict.NEEDS_CONSENT
        FetchNetwork.UNMETERED -> ModelNetworkVerdict.GO
    }

    /**
     * What to do with the partial file that is already there.
     *
     * Note what this does *not* decide: whether those bytes are the right ones. Nothing short of
     * reading them can answer that, and the reading happens where the digest is computed. This
     * only says whether they are worth reading.
     */
    fun resume(partialBytes: Long, model: FetchableModel): ModelResumePlan = when {
        partialBytes <= 0L -> ModelResumePlan.FromScratch
        partialBytes > model.sizeBytes -> ModelResumePlan.Discard
        partialBytes == model.sizeBytes -> ModelResumePlan.VerifyOnly
        partialBytes < MIN_RESUME_BYTES -> ModelResumePlan.FromScratch
        else -> ModelResumePlan.Resume(partialBytes)
    }

    // ------------------------------------------------------------------ what the server answered

    /**
     * What the publisher's status code means for a download that carried no credentials.
     *
     * The first request is a *probe*, not an attempt that either works or errors. Google's own
     * app does exactly this and expects to be refused. Which of these a gated repository actually
     * answers with is not verified from here — the host is unreachable from this machine — so all
     * four refusals are given a sentence and the same remedy, which is the model's own page.
     */
    fun judgeAccess(code: Int): ModelAccess = when {
        code == 200 || code == 206 -> ModelAccess.OPEN
        code == 401 -> ModelAccess.NEEDS_SIGN_IN
        code == 403 -> ModelAccess.NEEDS_AGREEMENT
        code == 404 || code == 410 -> ModelAccess.NOT_FOUND
        code == 429 -> ModelAccess.BUSY
        code in 500..599 -> ModelAccess.SERVER_ERROR
        else -> ModelAccess.REFUSED
    }

    /** Whether a refusal is one the player can lift by opening the model's page. */
    fun isGate(access: ModelAccess): Boolean =
        access == ModelAccess.NEEDS_AGREEMENT ||
            access == ModelAccess.NEEDS_SIGN_IN ||
            access == ModelAccess.NOT_FOUND

    /**
     * Reads a `Content-Range` response header.
     *
     * Only the `bytes first-last/total` form is accepted, with `*` allowed for the total. The
     * `bytes * /total` form that accompanies a 416 carries no first or last and is rejected here;
     * the 416 itself is what that case is read from, in [judgeRange].
     */
    fun parseContentRange(header: String?): ModelContentRange? {
        val raw = header?.trim() ?: return null
        if (!raw.startsWith("bytes ", ignoreCase = true)) return null
        val body = raw.substring("bytes ".length).trim()
        val slash = body.lastIndexOf('/')
        if (slash <= 0 || slash == body.length - 1) return null
        val span = body.substring(0, slash)
        val totalText = body.substring(slash + 1)
        val dash = span.indexOf('-')
        if (dash <= 0 || dash == span.length - 1) return null
        val first = span.substring(0, dash).toLongOrNull() ?: return null
        val last = span.substring(dash + 1).toLongOrNull() ?: return null
        if (first < 0L || last < first) return null
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
        if (total != null && total <= last) return null
        return ModelContentRange(first, last, total)
    }

    /**
     * What a download response means for the bytes already on disk.
     *
     * This is the link in the resume chain that stops two different files being glued together.
     * A server that ignores `Range` answers 200 with the whole body and is perfectly within its
     * rights; appending that to a partial would produce a file of the right *kind* and the wrong
     * length — and, on the day the length happened to work out, the wrong bytes at the right
     * length. So 200 means start over, always.
     *
     * [wantFrom] of zero is the un-ranged case: the only acceptable answer is the whole file.
     */
    fun judgeRange(
        code: Int,
        contentRange: String?,
        wantFrom: Long,
        total: Long,
    ): ModelRangeVerdict {
        if (code == 416) return ModelRangeVerdict.Refused(ModelRangeFault.NOT_SATISFIABLE)
        if (code != 206) return ModelRangeVerdict.FromZero
        val range = parseContentRange(contentRange)
            ?: return ModelRangeVerdict.Refused(ModelRangeFault.UNPARSEABLE)
        if (range.total != null && range.total != total) {
            return ModelRangeVerdict.Refused(ModelRangeFault.WRONG_TOTAL)
        }
        if (range.first != maxOf(wantFrom, 0L)) return ModelRangeVerdict.Refused(ModelRangeFault.WRONG_START)
        if (range.last < range.first) return ModelRangeVerdict.Refused(ModelRangeFault.EMPTY)
        if (range.last > total - 1L) return ModelRangeVerdict.Refused(ModelRangeFault.WRONG_TOTAL)
        return ModelRangeVerdict.Continues(range.first, range.last)
    }

    /**
     * The response's validator, cleaned up, or null if there is nothing usable.
     *
     * Kept next to the partial file and offered back as `If-Range` on the resume, which is what
     * lets the *server* refuse a stale continuation rather than this code having to detect one
     * afterwards. A weak validator (`W/"…"`) is dropped: weak means "same content, near enough",
     * and near enough is not a property one can append to.
     */
    fun normaliseValidator(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_VALIDATOR_CHARS) return null
        if (trimmed.startsWith("W/")) return null
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"") || trimmed.length < 3) return null
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isEmpty() || inner.any { it == '"' || it.isWhitespace() }) return null
        return trimmed
    }

    /**
     * A SHA-256 read out of a validator, when it happens to be one.
     *
     * The publisher stores large files by content hash and its own client uses the linked
     * validator as that hash, so for these files the quoted string is expected to *be* the
     * digest. **Expected, not verified** — the host is unreachable from here. So this is used
     * opportunistically: a 64-hex validator upgrades the check from length-only to a digest, and
     * anything else simply leaves it where it was. Nothing depends on it being there.
     */
    fun digestFromValidator(raw: String?): String? {
        val normalised = normaliseValidator(raw) ?: return null
        val inner = normalised.substring(1, normalised.length - 1).lowercase()
        return if (isSha256Hex(inner)) inner else null
    }

    /**
     * Whether the finished file may be kept.
     *
     * The size is checked first and always, because it is the one fact the publisher gives for
     * every model and because a short file is what an interrupted download leaves behind.
     */
    fun accept(
        model: FetchableModel,
        bytesOnDisk: Long,
        computedDigest: String?,
        serverDigest: String? = null,
    ): ModelAcceptance {
        if (bytesOnDisk != model.sizeBytes) return ModelAcceptance.WrongSize
        val published = model.sha256
        if (published != null) {
            if (computedDigest == null || computedDigest != published) return ModelAcceptance.WrongDigest
            return ModelAcceptance.Accepted(ModelVerification.PUBLISHED_DIGEST)
        }
        val fromServer = serverDigest?.takeIf { isSha256Hex(it) }
        if (fromServer != null) {
            if (computedDigest == null || computedDigest != fromServer) return ModelAcceptance.WrongDigest
            return ModelAcceptance.Accepted(ModelVerification.SERVER_DIGEST)
        }
        return ModelAcceptance.Accepted(ModelVerification.SIZE_ONLY)
    }

    // ------------------------------------------------------------------ saying it to a person

    /** 0f..1f, and never NaN however odd the declared size turns out to be. */
    fun progressFraction(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /**
     * A size a person can read.
     *
     * Gigabytes matter here in a way they never did for the updater's ten-megabyte APK, so this
     * carries one decimal place from a megabyte up and none below. Powers of 1024, because that
     * is what every other number the phone will show about this file uses.
     */
    fun describeBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024L
        val mb = kb * 1024L
        val gb = mb * 1024L
        return when {
            bytes >= gb -> "${oneDecimal(bytes, gb)} GB"
            bytes >= mb -> "${oneDecimal(bytes, mb)} MB"
            bytes >= kb -> "${(bytes + kb / 2) / kb} KB"
            else -> "$bytes B"
        }
    }

    private fun oneDecimal(bytes: Long, unit: Long): String {
        val tenths = (bytes * 10L + unit / 2L) / unit
        return "${tenths / 10}.${tenths % 10}"
    }
}

/**
 * Where a model download has got to, reduced to the states the screen draws differently.
 *
 * Coarser than the downloader's own status on purpose — the same rule the updater's
 * [UpdatePhase] follows. Two states that produce the same headline and the same buttons are one
 * screen, and separate phases would only invite them to drift.
 */
enum class ModelFetchPhase {
    /** This build knows of no model it can fetch. Said out loud rather than hidden. */
    UNCONFIGURED,

    /** Nothing downloaded, nothing running, and the terms have not been shown. The offer. */
    ABSENT,

    /** The player has to see whose model this is and what it is licensed under first. */
    NEEDS_TERMS,

    /** Downloading would spend mobile data and the player has not said yes. */
    NEEDS_METERED_CONSENT,

    /** Asked for, waiting for Wi-Fi. Nothing is wrong; nothing is happening either. */
    WAITING_FOR_WIFI,

    /** Not enough room on the device. */
    NO_ROOM,

    /** Bytes are arriving. */
    DOWNLOADING,

    /** All the bytes arrived; what is on disk is being checked. */
    VERIFYING,

    /** On the disk, checked, and it will still be there tomorrow. */
    STORED,

    /** The publisher will not serve it to an app that cannot sign in. */
    GATED,

    /** It stopped, and the reason is in the status sentence. */
    FAILED,
}

/** A button the model storage panel can offer. Each maps to exactly one call on the downloader. */
enum class ModelFetchAction(val label: String) {
    DOWNLOAD("Download"),
    DOWNLOAD_ON_METERED("Use mobile data"),
    ACCEPT_TERMS("I have read the terms"),
    OPEN_MODEL_PAGE("Open the model's page"),
    STOP("Stop"),
    RETRY("Try again"),
    DELETE("Delete"),
}

/**
 * How loudly a state should read. Not a colour — the screen picks those from its own palette.
 *
 * Separate from the updater's [UpdateTone] rather than shared, even though the four words are the
 * same: that enum is named for the updater's screen and lives in the updater's file, and one of
 * the two will grow a fifth state one day. Four words are a cheaper duplicate than a coupling
 * between two unrelated screens.
 */
enum class ModelFetchTone { NEUTRAL, GOOD, WARN, BAD }

/** Everything the storage panel needs beyond the downloader's own sentence for the state. */
data class ModelFetchPlan(
    val headline: String,
    val tone: ModelFetchTone,
    /** The one button the player most likely wants, or null when there is nothing to press. */
    val primary: ModelFetchAction?,
    val secondary: List<ModelFetchAction> = emptyList(),
    /** True while something is running: buttons stay visible but stop responding. */
    val busy: Boolean = false,
    /** True when a progress meter is meaningful. */
    val showsProgress: Boolean = false,
    /** What the state's own message does not say, or null when it says enough. */
    val note: String? = null,
) {
    /** Every button on the panel, primary first. Saves the composable a null check. */
    val actions: List<ModelFetchAction> get() = listOfNotNull(primary) + secondary
}

/**
 * What the storage panel offers, given where the download got to.
 *
 * The same division of labour as [UpdatePlanner]: the composable maps the downloader's status
 * onto a [ModelFetchPhase] with one exhaustive `when` — a translation the compiler checks — and
 * then draws whatever this hands back. No `if` about what the player sees lives in the UI.
 */
object ModelFetchPlanner {

    fun plan(
        phase: ModelFetchPhase,
        /** The size of the artefact, for the sentences that quote it. Zero when unknown. */
        sizeBytes: Long = 0L,
        /** What is on the disk right now, complete or partial. */
        onDiskBytes: Long = 0L,
        /** How much of the download is done, for [ModelFetchPhase.DOWNLOADING]. */
        doneBytes: Long = 0L,
        /** Which fact is missing, for [ModelFetchPhase.UNCONFIGURED]. */
        fault: ModelDescriptorFault? = null,
        /** How much the stored file's check was worth, for [ModelFetchPhase.STORED]. */
        verification: ModelVerification = ModelVerification.SIZE_ONLY,
    ): ModelFetchPlan {
        val size = ModelFetchRules.describeBytes(sizeBytes)
        return when (phase) {
            ModelFetchPhase.UNCONFIGURED -> ModelFetchPlan(
                headline = "No brain to download yet",
                tone = ModelFetchTone.NEUTRAL,
                primary = null,
                note = (fault?.message?.plus(" ") ?: "") +
                    "The creature thinks with what it has, exactly as it does today; nothing is " +
                    "missing from the game and nothing is broken.",
            )

            ModelFetchPhase.ABSENT -> ModelFetchPlan(
                headline = "Give the creature a brain of its own",
                tone = ModelFetchTone.NEUTRAL,
                primary = ModelFetchAction.DOWNLOAD,
                note = "$size, downloaded once and kept on this device. It is not part of the " +
                    "app and the game is complete without it. Wi-Fi only unless you say " +
                    "otherwise, and it picks up where it left off if it is interrupted.",
            )

            ModelFetchPhase.NEEDS_TERMS -> ModelFetchPlan(
                headline = "Whose brain this is",
                tone = ModelFetchTone.NEUTRAL,
                primary = ModelFetchAction.ACCEPT_TERMS,
                secondary = listOf(ModelFetchAction.OPEN_MODEL_PAGE),
                note = "The model is not NeoPal's work and is not covered by NeoPal's licence. " +
                    "It comes from its publisher under the publisher's own terms, which are on " +
                    "its page. Nothing is downloaded until you have said you have read them.",
            )

            ModelFetchPhase.NEEDS_METERED_CONSENT -> ModelFetchPlan(
                headline = "This is mobile data",
                tone = ModelFetchTone.WARN,
                primary = ModelFetchAction.DOWNLOAD_ON_METERED,
                secondary = listOf(ModelFetchAction.STOP),
                note = "Downloading now would use about $size of your allowance. Waiting for " +
                    "Wi-Fi costs nothing but time.",
            )

            ModelFetchPhase.WAITING_FOR_WIFI -> ModelFetchPlan(
                headline = "Waiting for Wi-Fi",
                tone = ModelFetchTone.NEUTRAL,
                primary = ModelFetchAction.STOP,
                secondary = listOf(ModelFetchAction.DOWNLOAD_ON_METERED),
                showsProgress = onDiskBytes > 0L,
                note = if (onDiskBytes > 0L) {
                    "${ModelFetchRules.describeBytes(onDiskBytes)} of $size is already here and " +
                        "will not be downloaded twice."
                } else {
                    "It will start on its own, and carry on while you are elsewhere in the app."
                },
            )

            ModelFetchPhase.NO_ROOM -> ModelFetchPlan(
                headline = "Not enough room",
                tone = ModelFetchTone.BAD,
                primary = ModelFetchAction.RETRY,
                secondary = listOf(ModelFetchAction.DELETE),
                note = "Free some space and try again. Nothing else in NeoPal is affected — the " +
                    "save, the album and the memorial together are a rounding error next to $size.",
            )

            ModelFetchPhase.DOWNLOADING -> ModelFetchPlan(
                headline = "Downloading",
                tone = ModelFetchTone.NEUTRAL,
                primary = ModelFetchAction.STOP,
                busy = true,
                showsProgress = true,
                note = "${ModelFetchRules.describeBytes(doneBytes)} of $size. You can leave this " +
                    "screen, or the app; it keeps going and picks up where it left off.",
            )

            ModelFetchPhase.VERIFYING -> ModelFetchPlan(
                headline = "Checking what arrived",
                tone = ModelFetchTone.NEUTRAL,
                primary = null,
                busy = true,
                showsProgress = true,
                note = "Reading the whole file back to make sure it is the one that was " +
                    "published. A resumed download is checked the same way as a fresh one.",
            )

            ModelFetchPhase.STORED -> ModelFetchPlan(
                headline = "On this device",
                tone = ModelFetchTone.GOOD,
                primary = ModelFetchAction.DELETE,
                note = "${ModelFetchRules.describeBytes(onDiskBytes)} in NeoPal's own storage. " +
                    "Android will not reclaim it on its own; it stays until you delete it here " +
                    "or uninstall the app. " + verificationNote(verification),
            )

            ModelFetchPhase.GATED -> ModelFetchPlan(
                headline = "The publisher will not serve this",
                tone = ModelFetchTone.WARN,
                primary = ModelFetchAction.OPEN_MODEL_PAGE,
                secondary = listOf(ModelFetchAction.RETRY),
                note = "This is not a fault in NeoPal and retrying on its own will not change " +
                    "it. Open the page, sign in there, accept whatever it asks for, and try " +
                    "again — if the publisher then serves the file to an app that is not signed " +
                    "in, this will work. If it does not, this model cannot be downloaded from " +
                    "inside the game at all.",
            )

            ModelFetchPhase.FAILED -> ModelFetchPlan(
                headline = "The download stopped",
                tone = ModelFetchTone.BAD,
                primary = ModelFetchAction.RETRY,
                secondary = if (onDiskBytes > 0L) listOf(ModelFetchAction.DELETE) else emptyList(),
                showsProgress = onDiskBytes > 0L,
                note = if (onDiskBytes > 0L) {
                    "${ModelFetchRules.describeBytes(onDiskBytes)} of $size is still here. " +
                        "Trying again continues from there rather than starting over."
                } else {
                    null
                },
            )
        }
    }

    /**
     * What the check on the stored file was actually worth, in a sentence.
     *
     * Said rather than left implied, because "downloaded and verified" and "downloaded and it was
     * the right length" are different claims and only one of them is true for a publisher that
     * gives no checksum.
     */
    fun verificationNote(verification: ModelVerification): String = when (verification) {
        ModelVerification.PUBLISHED_DIGEST ->
            "Its checksum matches the one the publisher declared."
        ModelVerification.SERVER_DIGEST ->
            "The publisher declares no checksum for it, so it was checked against the one the " +
                "download itself carried: right length, right hash, same source for both."
        ModelVerification.SIZE_ONLY ->
            "The publisher declares no checksum for it, so it was checked by length alone. That " +
                "catches a download that stopped early — which is what actually goes wrong — and " +
                "would not catch a corrupted middle."
    }

    /**
     * The terms gate, applied to a phase before it is planned against.
     *
     * Google's own on-device sample shows the model's terms before it downloads anything, and
     * that is right for a file that is somebody else's work under somebody else's licence. Only
     * the *starting* state is gated: once bytes are moving, or a download has failed, or a file is
     * on the disk, putting a licence in front of the player would be asking a question whose
     * answer no longer changes anything.
     *
     * Here rather than in the composable because it is an `if` about what the player sees, which
     * in this project is domain logic by definition.
     */
    fun gate(phase: ModelFetchPhase, termsAccepted: Boolean): ModelFetchPhase =
        if (!termsAccepted && phase == ModelFetchPhase.ABSENT) ModelFetchPhase.NEEDS_TERMS else phase

    /** The one line under the meter. Kept here so two screens cannot phrase it differently. */
    fun progressLine(doneBytes: Long, totalBytes: Long): String {
        val percent = (ModelFetchRules.progressFraction(doneBytes, totalBytes) * 100f).toInt()
        return "${ModelFetchRules.describeBytes(doneBytes)} of " +
            "${ModelFetchRules.describeBytes(totalBytes)} — $percent%"
    }
}
