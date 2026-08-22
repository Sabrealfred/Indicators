package com.neopal.pet.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.neopal.pet.domain.AppVersion
import com.neopal.pet.domain.InstalledBuild
import com.neopal.pet.domain.MarkerParse
import com.neopal.pet.domain.PublishedBuild
import com.neopal.pet.domain.ReleaseAsset
import com.neopal.pet.domain.UpdateVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Updating the game from inside the game.
 *
 * NeoPal is not on any store. It ships as a debug APK attached to a rolling GitHub pre-release
 * that CI rewrites on every push, so "update" means: ask the releases API what is published, work
 * out whether it is newer than what is running, and — only if the player says so — fetch it and
 * hand it to Android's package installer.
 *
 * Three rules shape everything below, and each of them cost code:
 *
 * 1. **Nothing happens without the player.** No download starts on its own, ever, and nothing is
 *    ever installed on their behalf. An automatic *check* is allowed, and even that is refused on
 *    a metered connection — see [CheckTrigger].
 * 2. **A failure says what failed.** The remote-brain client next door resolves every error to
 *    null on purpose, because a creature that goes quiet is a fine outcome. This is the opposite
 *    case: the player asked a direct question and pressed a button, so every path here ends in a
 *    sentence they can act on. [CheckFailure] and [DownloadFailure] carry those sentences.
 * 3. **Bytes are checked before they are offered to the installer.** See [preflight].
 *
 * ## What the integrity check is actually worth
 *
 * The APK's SHA-256 is declared in the release notes by the same CI job that uploaded the APK, and
 * fetched over the same TLS connection from the same host. So the digest **does** catch: a
 * truncated or interrupted download, a corrupted transfer, a caching proxy that mangles or
 * substitutes a stale body, and a release whose notes and asset came from different builds. It
 * **does not** protect against anyone who can write to that release — a compromised GitHub
 * account, a compromised CI token, or a repository collaborator would publish an APK and a
 * matching digest, and this code would install it happily. Against a network attacker, TLS is
 * already doing the work; the digest adds defence in depth, not a new trust boundary.
 *
 * The one check that is a real boundary is Android's own: the package installer refuses an update
 * signed by a different key than the installed copy. That is worth far more than the digest — and
 * it is only worth anything if CI signs every build with a *stable* key, which today it does not.
 * [preflight] looks for that mismatch and warns rather than letting the installer fail with a
 * message nobody can act on.
 */
class UpdateService(
    context: Context,
    /**
     * Overridable so a fork can point the updater at its own releases without a code change.
     *
     * Not a hole: whatever is passed still has to satisfy [AppVersion.isTrustedReleaseUrl] before
     * a socket is opened, so this can move the updater between GitHub repositories and nowhere
     * else.
     */
    private val releaseApiUrl: String = RELEASE_API_URL,
) {

    private val app: Context = context.applicationContext

    private val statusFlow = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)

    /** Everything the update UI needs. One flow so the screen has a single thing to observe. */
    val status: StateFlow<UpdateStatus> = statusFlow.asStateFlow()

    /**
     * Nullable rather than a sentinel, deliberately.
     *
     * The obvious `Long.MIN_VALUE` sentinel is the exact bug this project already shipped once in
     * the remote brain's throttles: `now - Long.MIN_VALUE` overflows to a large negative, the gap
     * is never satisfied, and the feature silently never runs. Null cannot be subtracted from.
     */
    private var lastAutoCheckElapsed: Long? = null

    // ------------------------------------------------------------------ checking

    /**
     * Asks the release page what is published and settles on a [UpdateStatus].
     *
     * An [CheckTrigger.AUTOMATIC] check is skipped outright on a metered connection. The request
     * is only a few kilobytes, but "the app used my data without asking" is a promise worth
     * keeping in whole rather than in spirit, and the rule is enforced here rather than at the
     * call site so no caller can forget it.
     */
    suspend fun checkForUpdate(trigger: CheckTrigger = CheckTrigger.MANUAL): UpdateStatus {
        // A check that lands mid-download would throw away a half-finished file for nothing.
        if (statusFlow.value is UpdateStatus.Downloading) return statusFlow.value

        if (trigger == CheckTrigger.AUTOMATIC) {
            if (isMetered()) return statusFlow.value
            val now = SystemClock.elapsedRealtime()
            val last = lastAutoCheckElapsed
            if (last != null && now - last < AUTO_CHECK_INTERVAL_MILLIS) return statusFlow.value
            lastAutoCheckElapsed = now
        }

        statusFlow.value = UpdateStatus.Checking
        val installed = readInstalledBuild()

        val fetched = withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) { fetchRelease() }
        } ?: return settle(UpdateStatus.CheckFailed(CheckFailure.TIMED_OUT))

        val text = when (fetched) {
            is ReleaseFetch.Failed -> return settle(UpdateStatus.CheckFailed(fetched.reason))
            is ReleaseFetch.Body -> fetched.text
        }

        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return settle(UpdateStatus.CheckFailed(CheckFailure.UNREADABLE))

        val marker = AppVersion.parseMarker(root.string("body"))
        val wanted = (marker as? MarkerParse.Ok)?.build
        val asset = wanted?.let { findAsset(root, it.assetName) }
        val verdict = AppVersion.decide(installed, marker, asset?.record)

        return settle(
            when (verdict) {
                is UpdateVerdict.Available -> {
                    val url = asset?.url
                    // The marker says *which* build; only the API's own asset record says where
                    // the bytes live, and even that is re-checked before a socket is opened.
                    if (url == null || !AppVersion.isTrustedReleaseUrl(url)) {
                        UpdateStatus.CheckFailed(CheckFailure.UNTRUSTED_ASSET_URL)
                    } else {
                        val offer = UpdateStatus.Available(
                            published = verdict.published,
                            downloadUrl = url,
                            downgradeInName = verdict.downgradeInName,
                            installedName = verdict.installed.versionName,
                        )
                        // A copy fetched earlier and never installed is still perfectly good —
                        // the commonest reason to be here twice is an install the player backed
                        // out of. Offering "download" for bytes already on the disk would be a
                        // small lie and several megabytes of somebody's allowance.
                        val onDisk = File(downloadDir, apkFileName(verdict.published))
                        val already = onDisk.isFile && onDisk.length() == verdict.published.sizeBytes &&
                            withContext(Dispatchers.IO) { digestOf(onDisk) } == verdict.published.sha256
                        if (already) finish(offer, onDisk) else offer
                    }
                }
                is UpdateVerdict.UpToDate -> {
                    // Whatever is on disk was either installed or is now moot. Either way it is
                    // several megabytes of nothing.
                    clearDownloads()
                    UpdateStatus.UpToDate(verdict.installed.versionName, verdict.installed.versionCode)
                }
                is UpdateVerdict.AheadOfPublished -> {
                    clearDownloads()
                    UpdateStatus.AheadOfPublished(
                        installedName = verdict.installed.versionName,
                        installedCode = verdict.installed.versionCode,
                        publishedCode = verdict.published.versionCode,
                    )
                }
                is UpdateVerdict.Undecidable -> UpdateStatus.Undecidable(verdict.reason.message)
            },
        )
    }

    // ------------------------------------------------------------------ downloading

    /**
     * Fetches the offered APK, verifies it, and leaves it ready to install.
     *
     * Only meaningful from [UpdateStatus.Available] or [UpdateStatus.NeedsMeteredConsent]; from
     * anything else it does nothing, because a download with no offer behind it is a download the
     * player did not ask for.
     *
     * [allowMetered] is the player's answer to the mobile-data question and defaults to no. The
     * first attempt on a metered network therefore does not fail — it parks in
     * [UpdateStatus.NeedsMeteredConsent] with the size, and the screen asks.
     */
    suspend fun download(allowMetered: Boolean = false): UpdateStatus {
        val offer = when (val current = statusFlow.value) {
            is UpdateStatus.Available -> current
            is UpdateStatus.NeedsMeteredConsent -> current.offer
            is UpdateStatus.DownloadFailed -> current.offer
            else -> return statusFlow.value
        }

        if (!allowMetered && isMetered()) {
            return settle(UpdateStatus.NeedsMeteredConsent(offer))
        }

        val build = offer.published
        val target = File(downloadDir, apkFileName(build))

        // A verified copy from an earlier attempt is worth more than a fresh download: it costs
        // nothing, and it is the difference between "the install did not take" being one tap away
        // and being another ten megabytes away.
        if (target.isFile && target.length() == build.sizeBytes) {
            val existing = withContext(Dispatchers.IO) { digestOf(target) }
            if (existing == build.sha256) return settle(finish(offer, target))
        }

        statusFlow.value = UpdateStatus.Downloading(offer, 0L, build.sizeBytes)

        val outcome = try {
            withContext(Dispatchers.IO) { fetchApk(offer, target) }
        } catch (cancelled: CancellationException) {
            runCatching { partFile(build).delete() }
            statusFlow.value = offer
            throw cancelled
        }

        return settle(
            when (outcome) {
                is ApkFetch.Failed -> UpdateStatus.DownloadFailed(offer, outcome.reason)
                is ApkFetch.Done -> finish(offer, outcome.file)
            },
        )
    }

    /** Throws away anything downloaded and forgets the offer. Safe to call at any time. */
    fun discardDownload(): UpdateStatus {
        clearDownloads()
        return settle(UpdateStatus.Idle)
    }

    // ------------------------------------------------------------------ installing

    /**
     * Whether Android will let this app ask to install a package.
     *
     * Below API 26 there is no per-app permission — there is one device-wide "unknown sources"
     * switch, and the installer itself explains it when it is off — so this reports true and the
     * system gets to have that conversation.
     */
    fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    /**
     * The settings screen where the player grants "install unknown apps" to NeoPal.
     *
     * Returned rather than launched so the caller can use an activity-result launcher and
     * re-check [canInstallPackages] the moment the player comes back. Null below API 26, where
     * there is no such screen for one app.
     */
    fun unknownSourcesSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}"),
        )
    }

    /**
     * Hands the verified APK to the system package installer.
     *
     * **The outcome cannot be observed.** `ACTION_VIEW` starts the installer and tells us nothing
     * afterwards; if it succeeds this process is killed and replaced. So the screen must not claim
     * the update worked — the honest report is that the system has taken over, and the truth
     * arrives at the next check, when the installed version either has moved or has not.
     */
    fun startInstall(): InstallLaunch {
        val ready = statusFlow.value as? UpdateStatus.ReadyToInstall
            ?: return InstallLaunch.Refused("There is no downloaded update to install.")

        if (!ready.file.isFile) {
            clearDownloads()
            settle(UpdateStatus.Idle)
            return InstallLaunch.Refused(
                "The downloaded file is no longer there. Check for the update again.",
            )
        }
        if (!canInstallPackages()) return InstallLaunch.PermissionNeeded

        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}$PROVIDER_SUFFIX", ready.file)
        }.getOrNull() ?: return InstallLaunch.Refused(
            "This build cannot pass files to the installer: its file provider is not set up. " +
                "Install the APK from the release page instead.",
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Started from the application context, so it needs its own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            app.startActivity(intent)
            InstallLaunch.Started
        } catch (missing: ActivityNotFoundException) {
            InstallLaunch.Refused("This device has nothing that can install an APK.")
        } catch (refused: SecurityException) {
            InstallLaunch.Refused("Android refused to pass the file to the installer.")
        }
    }

    // ------------------------------------------------------------------ the release API

    private sealed interface ReleaseFetch {
        data class Body(val text: String) : ReleaseFetch
        data class Failed(val reason: CheckFailure) : ReleaseFetch
    }

    private fun fetchRelease(): ReleaseFetch {
        var connection: HttpURLConnection? = null
        return try {
            if (!AppVersion.isTrustedReleaseUrl(releaseApiUrl)) {
                return ReleaseFetch.Failed(CheckFailure.UNTRUSTED_ASSET_URL)
            }
            val http = URL(releaseApiUrl).openConnection() as? HttpURLConnection
                ?: return ReleaseFetch.Failed(CheckFailure.NETWORK)
            connection = http
            http.requestMethod = "GET"
            http.connectTimeout = SOCKET_TIMEOUT_MILLIS
            http.readTimeout = SOCKET_TIMEOUT_MILLIS
            // The API answers 200 directly. A redirect here would mean something has changed
            // about GitHub that this code has not been read against, so it is a refusal.
            http.instanceFollowRedirects = false
            http.setRequestProperty("Accept", "application/vnd.github+json")
            http.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            // GitHub rejects API calls with no user agent. It names the app and nothing else:
            // no key, no identifier, nothing about the player or the device.
            http.setRequestProperty("User-Agent", USER_AGENT)

            when (val code = http.responseCode) {
                in 200..299 -> ReleaseFetch.Body(http.inputStream.use { readCapped(it, MAX_JSON_CHARS) })
                404 -> ReleaseFetch.Failed(CheckFailure.RELEASE_MISSING)
                // Unauthenticated calls share a per-address budget. GitHub says 403 for an
                // exhausted budget and 429 for a burst; the header is what tells them apart from
                // an ordinary refusal.
                403, 429 -> {
                    val remaining = http.getHeaderField("x-ratelimit-remaining")
                    if (remaining == "0" || code == 429) {
                        ReleaseFetch.Failed(CheckFailure.RATE_LIMITED)
                    } else {
                        ReleaseFetch.Failed(CheckFailure.REFUSED)
                    }
                }
                in 500..599 -> ReleaseFetch.Failed(CheckFailure.SERVER_ERROR)
                else -> ReleaseFetch.Failed(CheckFailure.REFUSED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (slow: SocketTimeoutException) {
            // Separated from the catch below because it is a different sentence on screen and a
            // different thing to do about it. The socket timeout is the one that normally fires:
            // it is shorter than the outer budget, which only exists for a server that dribbles.
            ReleaseFetch.Failed(CheckFailure.TIMED_OUT)
        } catch (ignored: Throwable) {
            // DNS, no route, a captive portal, a broken certificate: one message covers them,
            // because the player's next move is the same in every case.
            ReleaseFetch.Failed(CheckFailure.NETWORK)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** GitHub's record of the file, kept next to the one field of it the domain has no use for. */
    private data class AssetRecord(val record: ReleaseAsset, val url: String)

    private fun findAsset(release: JsonObject, name: String): AssetRecord? {
        val assets = release["assets"] as? JsonArray ?: return null
        for (element in assets) {
            val asset = element as? JsonObject ?: continue
            if (asset.string("name") != name) continue
            val size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: return null
            val url = asset.string("browser_download_url") ?: return null
            // Present since GitHub started digesting assets itself, and absent from anything
            // published before that; null simply means there is nothing to corroborate with.
            val digest = AppVersion.parseAssetDigest(asset.string("digest"))
            return AssetRecord(ReleaseAsset(size, digest), url)
        }
        return null
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    // ------------------------------------------------------------------ the download

    private sealed interface ApkFetch {
        data class Done(val file: File) : ApkFetch
        data class Failed(val reason: DownloadFailure) : ApkFetch
    }

    private suspend fun fetchApk(offer: UpdateStatus.Available, target: File): ApkFetch {
        val build = offer.published
        val dir = downloadDir
        if (!dir.isDirectory && !dir.mkdirs()) return ApkFetch.Failed(DownloadFailure.CANNOT_WRITE)
        // One APK at a time. Anything else in here is a previous offer nobody will install now.
        dir.listFiles()?.forEach { if (it.name != target.name) runCatching { it.delete() } }

        // usableSpace reports what this app may actually use, which is not the same as free
        // space on a device with a quota. Room for the file plus room for the installer to stage
        // its own copy, or the download succeeds and the install fails for the same reason.
        val needed = build.sizeBytes * 2L + INSTALL_HEADROOM_BYTES
        if (dir.usableSpace in 0 until needed) return ApkFetch.Failed(DownloadFailure.NO_SPACE)

        val part = partFile(build)
        runCatching { part.delete() }

        // Tracks whichever connection is currently open, redirect hops included. A blocked
        // socket does not notice a cancelled coroutine; closing it underneath is the one thing
        // that does, and the hand-off has to be armed *before* the first connect.
        //
        // Deliberately a child coroutine parked in `awaitCancellation` rather than the obvious
        // `Job.invokeOnCompletion`, and this cost a test to find: a completion handler runs when
        // the job *completes*, and a job whose body is stuck in a socket read has not completed —
        // it is merely cancelling. The handler therefore fired only after the read gave up on its
        // own fifteen-second timeout, which for a button marked "cancel" is indistinguishable
        // from nothing happening. A cancelled child, by contrast, resumes at once.
        var live: HttpURLConnection? = null
        val closer = CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { live?.disconnect() }
            }
        }
        return try {
            val opened = openAsset(offer.downloadUrl) { live = it }
            val http = when (opened) {
                is Opened.Failed -> return ApkFetch.Failed(opened.reason)
                is Opened.Ready -> opened.connection
            }

            val declared = http.contentLengthLong
            // GitHub always sends a length. When it disagrees with the release's own asset record
            // the two sides of this transfer do not agree about what is being transferred.
            if (declared > 0L && declared != build.sizeBytes) {
                return ApkFetch.Failed(DownloadFailure.SIZE_MISMATCH)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var announced = 0L
            val buffer = ByteArray(DOWNLOAD_CHUNK)

            http.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (written + read > build.sizeBytes) {
                            // More bytes than the release says exist. Stop rather than fill the
                            // disk finding out how many there are.
                            //
                            // Only reachable on a chunked response, since a declared length is
                            // checked above and HttpURLConnection stops at it — which also means
                            // this is the one branch here no test drives.
                            runCatching { part.delete() }
                            return ApkFetch.Failed(DownloadFailure.SIZE_MISMATCH)
                        }
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (written - announced >= PROGRESS_STEP_BYTES) {
                            announced = written
                            statusFlow.value = UpdateStatus.Downloading(offer, written, build.sizeBytes)
                        }
                    }
                    out.flush()
                    // Rename below is what makes the file installable, so it must not be able to
                    // name a buffer that never reached the disk.
                    runCatching { out.fd.sync() }
                }
            }

            if (written != build.sizeBytes) {
                // The stream ended early: connectivity lost, the server hung up, the CDN object
                // expired mid-transfer. A short file is the one outcome that must never be
                // handed to an installer.
                runCatching { part.delete() }
                return ApkFetch.Failed(DownloadFailure.INTERRUPTED)
            }
            if (digest.digest().toHex() != build.sha256) {
                runCatching { part.delete() }
                return ApkFetch.Failed(DownloadFailure.CORRUPT)
            }

            runCatching { target.delete() }
            if (!part.renameTo(target)) {
                runCatching { part.delete() }
                return ApkFetch.Failed(DownloadFailure.CANNOT_WRITE)
            }
            ApkFetch.Done(target)
        } catch (cancelled: CancellationException) {
            runCatching { part.delete() }
            throw cancelled
        } catch (io: IOException) {
            runCatching { part.delete() }
            // ENOSPC arrives as a plain IOException with a message nobody should be shown, and it
            // is by far the likeliest IO failure when writing ten megabytes to a phone.
            val space = dir.usableSpace
            ApkFetch.Failed(
                if (space in 0 until build.sizeBytes) DownloadFailure.NO_SPACE else DownloadFailure.INTERRUPTED,
            )
        } catch (ignored: Throwable) {
            runCatching { part.delete() }
            ApkFetch.Failed(DownloadFailure.NETWORK)
        } finally {
            closer.cancel()
            runCatching { live?.disconnect() }
        }
    }

    private sealed interface Opened {
        data class Ready(val connection: HttpURLConnection) : Opened
        data class Failed(val reason: DownloadFailure) : Opened
    }

    /**
     * Opens the asset, following GitHub's redirect to whichever bucket is serving today.
     *
     * Redirects are followed by hand rather than by [HttpURLConnection], for two reasons: the
     * built-in follower refuses to cross between http and https and would simply stop, and it
     * never shows the caller where it went. Every hop is re-checked against
     * [AppVersion.isTrustedReleaseUrl], because a redirect is the natural place for an update
     * flow to be walked off the release page.
     *
     * [onOpen] is handed each connection as it is created, so that a cancellation arriving during
     * the redirect chain has something to close.
     */
    private fun openAsset(startUrl: String, onOpen: (HttpURLConnection) -> Unit): Opened {
        var url = startUrl
        var hops = 0
        while (true) {
            if (!AppVersion.isTrustedReleaseUrl(url)) return Opened.Failed(DownloadFailure.UNTRUSTED_REDIRECT)
            val http = URL(url).openConnection() as? HttpURLConnection
                ?: return Opened.Failed(DownloadFailure.NETWORK)
            onOpen(http)
            http.requestMethod = "GET"
            http.connectTimeout = SOCKET_TIMEOUT_MILLIS
            http.readTimeout = SOCKET_TIMEOUT_MILLIS
            http.instanceFollowRedirects = false
            http.setRequestProperty("Accept", "application/octet-stream")
            http.setRequestProperty("User-Agent", USER_AGENT)

            val code = http.responseCode
            if (code in 300..399) {
                val location = http.getHeaderField("Location")
                runCatching { http.disconnect() }
                if (location.isNullOrEmpty()) return Opened.Failed(DownloadFailure.SERVER_ERROR)
                if (++hops > MAX_REDIRECTS) return Opened.Failed(DownloadFailure.UNTRUSTED_REDIRECT)
                // Resolved against the current URL, because a Location header is allowed to be
                // relative even when GitHub's never is.
                url = runCatching { URL(URL(url), location).toString() }.getOrNull()
                    ?: return Opened.Failed(DownloadFailure.UNTRUSTED_REDIRECT)
                continue
            }
            if (code == 404 || code == 410) {
                runCatching { http.disconnect() }
                return Opened.Failed(DownloadFailure.ASSET_GONE)
            }
            if (code !in 200..299) {
                runCatching { http.disconnect() }
                return Opened.Failed(
                    if (code in 500..599) DownloadFailure.SERVER_ERROR else DownloadFailure.REFUSED,
                )
            }
            return Opened.Ready(http)
        }
    }

    // ------------------------------------------------------------------ what came down

    private sealed interface Preflight {
        data class Passed(val caution: String?) : Preflight
        data class Rejected(val reason: DownloadFailure) : Preflight
    }

    /**
     * Reads the downloaded APK with Android's own package parser before offering it.
     *
     * The digest already proves the bytes are the ones the release describes. This asks the
     * separate question of whether the release was describing what it claimed: the app id and the
     * version code here are read out of the archive by the platform, not out of a line of text a
     * repository admin can edit. A disagreement means the notes were wrong, which makes the whole
     * "is this newer" comparison wrong, so it is refused rather than warned about.
     *
     * The signature comparison is a warning and not a refusal, because a player may well decide a
     * fresh start is worth it. It is only possible from API 28, where the platform will read v2
     * and v3 signatures out of an archive; below that the answer is "cannot tell", and cannot-tell
     * is reported as nothing at all rather than as a scare on every download.
     */
    private fun preflight(file: File, build: PublishedBuild): Preflight {
        // Some vendor ROMs decline to parse an archive outside their own staging directories.
        // The installer will parse it again in a moment and reject it if it is nonsense, so a
        // parser that will not answer is not a reason to refuse a download whose digest matched.
        val archive = runCatching {
            app.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
        }.getOrNull() ?: return Preflight.Passed(null)

        if (archive.packageName != app.packageName) return Preflight.Rejected(DownloadFailure.WRONG_APP)
        if (versionCodeOf(archive) != build.versionCode) return Preflight.Rejected(DownloadFailure.MISDECLARED)

        return Preflight.Passed(if (signedDifferently(archive)) DIFFERENT_KEY_CAUTION else null)
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0

    /** True only when both signatures could be read *and* they differ. Unknown is not different. */
    private fun signedDifferently(archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val theirs = runCatching {
            archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        }.getOrNull()
        val ours = runCatching {
            app.packageManager
                .getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        }.getOrNull()
        if (theirs.isNullOrEmpty() || ours.isNullOrEmpty()) return false
        return theirs.intersect(ours).isEmpty()
    }

    // ------------------------------------------------------------------ odds and ends

    /**
     * The last gate before a file is offered as installable.
     *
     * Applied to a file that has just been downloaded *and* to one left over from a previous
     * attempt, because a rejection has to be able to happen on both paths — the earlier draft of
     * this ran the checks only on the fresh path, which meant a rejected file could be re-offered
     * on the next tap simply for already being on disk.
     */
    private fun finish(offer: UpdateStatus.Available, file: File): UpdateStatus =
        when (val verdict = preflight(file, offer.published)) {
            is Preflight.Rejected -> {
                runCatching { file.delete() }
                UpdateStatus.DownloadFailed(offer, verdict.reason)
            }
            is Preflight.Passed -> UpdateStatus.ReadyToInstall(offer.published, file, verdict.caution)
        }

    private fun readInstalledBuild(): InstalledBuild? = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        InstalledBuild(versionCodeOf(info), info.versionName.orEmpty())
    }.getOrNull()

    /**
     * Whether the connection in use charges the player by the byte.
     *
     * Needs `ACCESS_NETWORK_STATE`. If that permission is missing, or the service is unavailable,
     * this answers *true* — the cautious answer, which costs an extra tap and never an unasked-for
     * download.
     */
    private fun isMetered(): Boolean = runCatching {
        app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    }.getOrDefault(true)

    private val downloadDir: File
        // The cache directory rather than files/: `allowBackup` is on for this app, and a ten
        // megabyte APK does not belong in somebody's cloud backup. The cost is that Android may
        // delete it under storage pressure, which costs a re-download and nothing else.
        get() = File(app.cacheDir, DOWNLOAD_DIR)

    private fun apkFileName(build: PublishedBuild): String = "neopal-${build.versionCode}.apk"

    private fun partFile(build: PublishedBuild): File = File(downloadDir, apkFileName(build) + ".part")

    private fun clearDownloads() {
        runCatching { downloadDir.listFiles()?.forEach { it.delete() } }
    }

    private fun settle(next: UpdateStatus): UpdateStatus {
        statusFlow.value = next
        return next
    }

    /**
     * Reads at most [limit] characters and abandons the rest.
     *
     * The body is a stream of unknown length from a host this app does not control. Reading it
     * whole would let one oversized answer take the process down on a phone.
     */
    private fun readCapped(stream: InputStream, limit: Int): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(READ_CHUNK)
        val out = StringBuilder()
        while (out.length < limit) {
            val want = minOf(buffer.size, limit - out.length)
            val read = reader.read(buffer, 0, want)
            if (read <= 0) break
            out.appendRange(buffer, 0, read)
        }
        return out.toString()
    }

    private fun digestOf(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DOWNLOAD_CHUNK)
        file.inputStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    companion object {
        /** Where the rolling debug build lives. Change these two if the repository moves. */
        const val REPO = "Sabrealfred/Indicators"
        const val RELEASE_TAG = "debug-latest"

        const val RELEASE_API_URL = "https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG"

        /** The human page, for the "download it yourself instead" way out of every failure. */
        const val RELEASE_PAGE_URL = "https://github.com/$REPO/releases/tag/$RELEASE_TAG"

        /** Must match `android:authorities` on the provider in the manifest. */
        const val PROVIDER_SUFFIX = ".updates"

        /** The directory inside the cache, and the `path` in the FileProvider's XML. */
        const val DOWNLOAD_DIR = "updates"

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val USER_AGENT = "NeoPal-Updater"

        private const val SOCKET_TIMEOUT_MILLIS = 15_000
        private const val CHECK_TIMEOUT_MILLIS = 20_000L
        private const val AUTO_CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L

        private const val MAX_REDIRECTS = 5
        private const val MAX_JSON_CHARS = 256 * 1024
        private const val READ_CHUNK = 8 * 1024
        private const val DOWNLOAD_CHUNK = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 128L * 1024L
        private const val INSTALL_HEADROOM_BYTES = 32L * 1024L * 1024L

        private const val HEX = "0123456789abcdef"

        private const val DIFFERENT_KEY_CAUTION =
            "This build was signed with a different key from the copy you have installed, so " +
                "Android will refuse to install it over the top. Going ahead means uninstalling " +
                "NeoPal first, which deletes the save — export it from this screen before you do."
    }
}

/** Who asked. The answer decides whether a check may spend the player's mobile data. */
enum class CheckTrigger {
    /** The player pressed the button. Runs on any connection. */
    MANUAL,

    /** The app decided, on launch or on opening settings. Refused on a metered connection. */
    AUTOMATIC,
}

/** Why the release page could not be read. Every one of these is shown to the player verbatim. */
enum class CheckFailure(val message: String) {
    NETWORK("Could not reach GitHub. Check the connection and try again."),
    TIMED_OUT("GitHub did not answer in time. Try again in a moment."),
    RATE_LIMITED(
        "GitHub is not answering any more requests from this network for now — it allows sixty " +
            "an hour to apps that do not sign in. Try again later.",
    ),
    RELEASE_MISSING(
        "There is no published build on the release page at the moment. A build may be running; " +
            "try again shortly.",
    ),
    SERVER_ERROR("GitHub is having trouble at its end. Nothing is wrong with this app."),
    REFUSED("GitHub refused the request. Try again later, or open the release page yourself."),
    UNREADABLE("GitHub's answer was not in a form this app could read."),
    UNTRUSTED_ASSET_URL(
        "The release points its download somewhere this app will not follow. It has been left " +
            "alone on purpose.",
    ),
}

/** Why the APK did not arrive, or arrived and was not fit to install. */
enum class DownloadFailure(val message: String) {
    NETWORK("The download could not be started. Check the connection and try again."),
    INTERRUPTED(
        "The download stopped part way, so the file is incomplete. Nothing has been installed. " +
            "Try again.",
    ),
    NO_SPACE("There is not enough free space on this device to download and install the update."),
    CANNOT_WRITE("The update could not be written to this device's storage."),
    ASSET_GONE(
        "The APK is no longer attached to the release — a new build has probably replaced it. " +
            "Check for updates again.",
    ),
    SERVER_ERROR("GitHub is having trouble serving the download. Try again later."),
    REFUSED("GitHub refused to serve the download."),
    UNTRUSTED_REDIRECT(
        "The download was redirected somewhere this app will not follow, so it was abandoned.",
    ),
    SIZE_MISMATCH(
        "The file being sent is not the size the release says it is. It has not been installed.",
    ),
    CORRUPT(
        "The downloaded file does not match the checksum on the release page, so it has been " +
            "deleted rather than installed.",
    ),
    WRONG_APP(
        "The downloaded package is not NeoPal. It has been deleted and nothing was installed.",
    ),
    MISDECLARED(
        "The downloaded package is not the version the release page says it is, so there is no " +
            "way to know whether it is newer. It has been deleted.",
    ),
}

/** What happened when the installer was asked for. Not whether the install worked — see [UpdateService.startInstall]. */
sealed interface InstallLaunch {
    /** The system installer is now in front of the player. Nothing more is knowable from here. */
    data object Started : InstallLaunch

    /** The player has not granted "install unknown apps" yet. Send them to the settings screen. */
    data object PermissionNeeded : InstallLaunch

    data class Refused(val message: String) : InstallLaunch
}

/**
 * Everything the update UI shows, as one value.
 *
 * [summary] is the sentence for the screen. It lives here rather than in the composable because
 * every one of these states is reached from a branch that knows why, and a screen reconstructing
 * that from flags is a screen that will one day describe the wrong thing.
 */
sealed interface UpdateStatus {

    val summary: String

    /** Nothing has been asked yet. */
    data object Idle : UpdateStatus {
        override val summary: String get() = "Not checked yet."
    }

    data object Checking : UpdateStatus {
        override val summary: String get() = "Asking GitHub what is published…"
    }

    data class UpToDate(val installedName: String, val installedCode: Long) : UpdateStatus {
        override val summary: String get() = "Up to date — $installedName (build $installedCode)."
    }

    /** The installed build is newer than the published one. A local build, or a rebuilt release. */
    data class AheadOfPublished(
        val installedName: String,
        val installedCode: Long,
        val publishedCode: Long,
    ) : UpdateStatus {
        override val summary: String
            get() = "This copy (build $installedCode) is newer than the published one " +
                "(build $publishedCode). There is nothing to install."
    }

    /** No honest comparison was possible. The message comes from the domain and says why. */
    data class Undecidable(val reason: String) : UpdateStatus {
        override val summary: String get() = reason
    }

    data class CheckFailed(val failure: CheckFailure) : UpdateStatus {
        override val summary: String get() = failure.message
    }

    /** A newer build exists and is ready to be fetched, if the player wants it. */
    data class Available(
        val published: PublishedBuild,
        val downloadUrl: String,
        val downgradeInName: Boolean,
        val installedName: String,
    ) : UpdateStatus {
        val megabytes: String get() = formatMegabytes(published.sizeBytes)

        override val summary: String
            get() = buildString {
                append("Build ${published.versionCode} (${published.versionName}) is available — $megabytes. ")
                append("You have $installedName.")
                if (downgradeInName) {
                    append(
                        " Note: the published build's name reads older than yours, even though " +
                            "its build number is higher.",
                    )
                }
            }
    }

    /** The player is on mobile data and has not yet said it is fine to spend it. */
    data class NeedsMeteredConsent(val offer: Available) : UpdateStatus {
        override val summary: String
            get() = "This connection is metered. Downloading will use about ${offer.megabytes} " +
                "of your mobile data."
    }

    data class Downloading(
        val offer: Available,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : UpdateStatus {
        /** 0f..1f, and never NaN however odd the declared size turns out to be. */
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)

        override val summary: String
            get() = "Downloading — ${formatMegabytes(bytesDone)} of ${formatMegabytes(bytesTotal)}."
    }

    data class DownloadFailed(val offer: Available, val failure: DownloadFailure) : UpdateStatus {
        override val summary: String get() = failure.message
    }

    /** Downloaded, checksummed and inspected. Nothing is installed until the player says so. */
    data class ReadyToInstall(
        val published: PublishedBuild,
        val file: File,
        /** Something the player should hear before tapping install, or null. */
        val caution: String?,
    ) : UpdateStatus {
        override val summary: String
            get() = buildString {
                append("Build ${published.versionCode} is downloaded and its checksum matches. ")
                append("Android will ask you to confirm the install.")
                if (caution != null) append(" $caution")
            }
    }
}

/** One decimal place is as much precision as a download size ever deserves. */
private fun formatMegabytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val tenths = (bytes * 10L + 524_288L) / (1024L * 1024L)
    return "${tenths / 10}.${tenths % 10} MB"
}
