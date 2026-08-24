package com.neopal.pet.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The parts of "fetch a large file over HTTP and be sure of what arrived" that two features share.
 *
 * ## Why this exists rather than a second downloader
 *
 * [UpdateService] already did this once, carefully, for an APK: follow the redirects by hand,
 * re-check the host on every hop, read in chunks, digest while reading, refuse to write more bytes
 * than were declared, and let a cancelled coroutine actually stop a socket that is blocked in a
 * read. The on-device model needs the same road to a different destination — a file three hundred
 * times the size, resumable, from another host.
 *
 * A second copy of all that would have been quicker to write and would have drifted. Not
 * hypothetically: three of the five things above are *refusals*, and a refusal is exactly the kind
 * of code that survives being copied with one clause missing and looks fine for a year. So the
 * refusals live here once, and each caller keeps what is genuinely its own — which failure
 * sentence a status code deserves, and what to do about it.
 *
 * ## What deliberately did **not** move here
 *
 * The status-code mapping. The updater reads 404 as "the release moved on, check again"; the model
 * downloader reads it as "this may be gated and the publisher will not admit it exists". Same
 * number, different truth, different button underneath. [open] therefore hands back whatever code
 * it was answered with and lets the caller judge it — the redirect chain is the shared part, not
 * the meaning of the answer at the end of it.
 */

/**
 * The connection that is open right now, so that a cancellation has something to close.
 *
 * A socket blocked in `read` does not notice a cancelled coroutine. Closing it underneath is the
 * one thing that does, and the hand-off has to be armed *before* the first connect — which means
 * something mutable has to be visible to both the reader and the canceller. This is that.
 */
internal class LiveConnection {
    @Volatile
    var connection: HttpURLConnection? = null

    fun disconnect() {
        runCatching { connection?.disconnect() }
    }
}

/**
 * Arms the close-on-cancel hand-off for [live] and returns the job that holds it.
 *
 * Deliberately a child coroutine parked in `awaitCancellation` rather than the obvious
 * `Job.invokeOnCompletion`, and this cost a test to find: a completion handler runs when the job
 * *completes*, and a job whose body is stuck in a socket read has not completed — it is merely
 * cancelling. The handler therefore fired only after the read gave up on its own timeout, which
 * for a button marked "cancel" is indistinguishable from nothing happening. A cancelled child, by
 * contrast, resumes at once.
 *
 * The caller must cancel the returned job when it is done, in a `finally`.
 */
internal suspend fun armDisconnectOnCancel(live: LiveConnection): Job =
    CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            live.disconnect()
        }
    }

/** What [HttpDownload.open] ended up with. */
internal sealed interface HttpOpen {
    /**
     * The server answered something that is not a redirect. [code] is untouched: whether 404 means
     * "gone" or "not for you" is the caller's question, not this layer's.
     */
    data class Answered(val connection: HttpURLConnection, val code: Int) : HttpOpen

    data class Failed(val reason: HttpOpenFailure) : HttpOpen
}

/** The three ways the redirect chain itself can end badly. Everything else is a status code. */
internal enum class HttpOpenFailure {
    /** The URL would not even open as an HTTP connection. */
    NETWORK,

    /** A hop pointed somewhere the caller's pin does not allow, or there were too many hops. */
    UNTRUSTED_REDIRECT,

    /** A redirect with no usable `Location`. */
    NO_LOCATION,
}

/** How far [HttpDownload.pump] got. */
internal sealed interface HttpPump {
    /** The stream ended. [total] counts the bytes already on disk before this call as well. */
    data class Wrote(val total: Long) : HttpPump

    /**
     * More bytes arrived than were declared. Nothing further was written.
     *
     * Only reachable on a chunked response, since a declared length is checked by the caller and
     * `HttpURLConnection` stops at it — which also means this is the one branch here no test drives.
     */
    data object TooLong : HttpPump
}

internal object HttpDownload {

    const val DEFAULT_CHUNK_BYTES = 64 * 1024
    const val DEFAULT_MAX_REDIRECTS = 5

    /**
     * Opens [startUrl], following redirects by hand and re-checking [trusted] on every hop.
     *
     * Redirects are followed here rather than by [HttpURLConnection] for two reasons: the built-in
     * follower refuses to cross between http and https and would simply stop, and it never shows
     * the caller where it went. Every hop is re-checked, because a redirect is the natural place
     * for a download to be walked somewhere else — and for both callers, "somewhere else" is the
     * whole thing being defended against.
     *
     * [onOpen] is handed each connection as it is created, so a cancellation arriving during the
     * redirect chain has something to close.
     *
     * Exceptions are not caught: both callers already wrap the whole download in a handler that
     * turns them into their own vocabulary, and swallowing an `IOException` here would only make
     * it harder to say which sentence the player sees.
     */
    fun open(
        startUrl: String,
        trusted: (String) -> Boolean,
        headers: Map<String, String>,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
        onOpen: (HttpURLConnection) -> Unit,
    ): HttpOpen {
        var url = startUrl
        var hops = 0
        while (true) {
            if (!trusted(url)) return HttpOpen.Failed(HttpOpenFailure.UNTRUSTED_REDIRECT)
            val http = URL(url).openConnection() as? HttpURLConnection
                ?: return HttpOpen.Failed(HttpOpenFailure.NETWORK)
            onOpen(http)
            http.requestMethod = "GET"
            http.connectTimeout = connectTimeoutMillis
            http.readTimeout = readTimeoutMillis
            http.instanceFollowRedirects = false
            headers.forEach { (name, value) -> http.setRequestProperty(name, value) }

            val code = http.responseCode
            if (code in 300..399) {
                val location = http.getHeaderField("Location")
                runCatching { http.disconnect() }
                if (location.isNullOrEmpty()) return HttpOpen.Failed(HttpOpenFailure.NO_LOCATION)
                if (++hops > maxRedirects) return HttpOpen.Failed(HttpOpenFailure.UNTRUSTED_REDIRECT)
                // Resolved against the current URL, because a Location header is allowed to be
                // relative even when the hosts we talk to never send a relative one.
                url = runCatching { URL(URL(url), location).toString() }.getOrNull()
                    ?: return HttpOpen.Failed(HttpOpenFailure.UNTRUSTED_REDIRECT)
                continue
            }
            return HttpOpen.Answered(http, code)
        }
    }

    /**
     * Copies [input] into [out], digesting and reporting as it goes, and stopping at [limit].
     *
     * [startedAt] is how many bytes are already in the file — zero for a fresh download, the
     * length of the partial for a resumed one — so that [limit] and the progress reported to
     * [onProgress] are both about the whole file rather than about this leg of it.
     *
     * The limit is not a nicety: without it a server that keeps sending fills the phone's disk
     * while we find out how much it has.
     *
     * Neither stream is closed here. Both callers own theirs inside a `use`, which is where they
     * have to be for the cancellation and error paths to be readable.
     */
    suspend fun pump(
        input: InputStream,
        out: FileOutputStream,
        digest: MessageDigest?,
        startedAt: Long,
        limit: Long,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
        progressStepBytes: Long,
        onProgress: (Long) -> Unit,
    ): HttpPump {
        val buffer = ByteArray(chunkBytes)
        var total = startedAt
        var announced = startedAt
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (total + read > limit) return HttpPump.TooLong
            out.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
            total += read
            if (total - announced >= progressStepBytes) {
                announced = total
                onProgress(total)
            }
        }
        out.flush()
        // A rename or a hand-off follows this, so it must not be able to name a buffer that never
        // reached the disk.
        runCatching { out.fd.sync() }
        return HttpPump.Wrote(total)
    }

    fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /**
     * Feeds the first [upTo] bytes of [file] into [digest] and says how many were read.
     *
     * This is what "a resume that verifies rather than trusts what is already on disk" is made
     * of. The alternative — remembering the digest state from the interrupted attempt — would be
     * faster and would believe a file that something else had touched in between. Reading it back
     * costs seconds on a gigabyte and answers the question honestly.
     *
     * Returns -1 if the file could not be read at all, and a short count if it turned out to be
     * shorter than [upTo]; a caller that gets back less than it asked for must not append.
     */
    suspend fun digestPrefix(file: File, upTo: Long, digest: MessageDigest): Long {
        if (upTo <= 0L) return 0L
        var read = 0L
        val buffer = ByteArray(DEFAULT_CHUNK_BYTES)
        return runCatching {
            file.inputStream().use { stream ->
                while (read < upTo) {
                    coroutineContext.ensureActive()
                    val want = minOf(buffer.size.toLong(), upTo - read).toInt()
                    val got = stream.read(buffer, 0, want)
                    if (got < 0) break
                    digest.update(buffer, 0, got)
                    read += got
                }
            }
            read
        }.getOrDefault(-1L)
    }

    /** The whole file's SHA-256 as lowercase hex, or null if it could not be read. */
    suspend fun sha256Of(file: File): String? {
        val digest = newSha256()
        val read = digestPrefix(file, Long.MAX_VALUE, digest)
        return if (read < 0L) null else digest.digest().toHexString()
    }
}

/**
 * Lowercase hex.
 *
 * Top level rather than a member so both downloaders can call it on a `digest()` result without
 * importing an object, and written out rather than reached for so it cannot depend on which JDK
 * the build happens to use.
 */
internal fun ByteArray.toHexString(): String {
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        chars[index * 2] = HEX_DIGITS[value ushr 4]
        chars[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(chars)
}

private const val HEX_DIGITS = "0123456789abcdef"
