package com.neopal.pet.domain

/**
 * Which build is newer, and is the one on the release page worth offering.
 *
 * This is the half of the self-updater that can be wrong quietly, so it is the half that is kept
 * free of Android and tested. Everything here is a pure function of two descriptions of a build;
 * nothing here opens a socket, touches a file or knows what an APK is.
 *
 * ## Why a marker line rather than the tag
 *
 * The APK is published to a *rolling* pre-release: CI deletes and recreates the `debug-latest`
 * tag on every push. So the tag is a constant and carries no information at all — comparing
 * against it can only ever say "same". `versionName` is no better on its own: it is a string a
 * human edits, it stays `1.0.0` across a hundred builds, and Android does not order installs by
 * it.
 *
 * The only number Android itself orders installs by is `versionCode`, so that is the only number
 * this file will order by. For the comparison to be honest the release therefore has to *carry*
 * the `versionCode` of the APK attached to it, and the one place CI already writes free text is
 * the release notes. Hence one machine-readable line in the body:
 *
 * ```
 * neopal-update: code=42 name=1.0.0+42 sha256=<64 hex> size=8123456 asset=neopal-debug.apk
 * ```
 *
 * It may be wrapped in an HTML comment so it does not show on the release page. The exact CI
 * change that produces it is in the report accompanying this file; without that change every
 * verdict here is [UpdateVerdict.Undecidable], which is the correct answer to "is this newer"
 * when nothing on the page says what it is.
 *
 * ## What is deliberately *not* trusted
 *
 * The marker is free text in a field a repository admin can edit. So the `sha256` it declares is
 * checked against the bytes we downloaded *and* against the digest GitHub computes over the stored
 * asset itself, the `size` it declares is cross-checked against the size that same asset record
 * reports, and the download URL is never taken from the marker at all — it comes from the API's
 * asset object and is re-checked with [isTrustedReleaseUrl]. The marker gets to say *which* build;
 * it does not get to say *where from*, and it does not get the last word on what the bytes are.
 */
object AppVersion {

    /** How a marker line announces itself. Matched after decoration is stripped. */
    const val MARKER_PREFIX = "neopal-update:"

    /**
     * `versionCode` is an `int` in the Android manifest, so anything outside that range did not
     * come from a real build and is refused rather than silently truncated.
     */
    const val MAX_VERSION_CODE: Long = Int.MAX_VALUE.toLong()

    /** Far larger than this app will ever be; a guard against a body that declares a silly size. */
    const val MAX_APK_BYTES: Long = 512L * 1024L * 1024L

    private const val MAX_LINE_CHARS = 1024
    private const val MAX_LINES_SCANNED = 500
    private const val MAX_NAME_CHARS = 64
    private const val MAX_ASSET_NAME_CHARS = 128
    private const val SHA256_HEX_CHARS = 64

    /**
     * Finds the marker in a release body.
     *
     * A line that announces itself as a marker and then fails to parse is skipped rather than
     * ending the scan, because the likeliest way for one to appear is a human quoting an old one
     * in the notes; the likeliest way for a *good* one to appear is CI writing it. [Malformed] is
     * therefore only reported when there was a marker line and none of them parsed — which is a
     * CI bug, and worth saying so rather than showing "no update".
     */
    fun parseMarker(body: String?): MarkerParse {
        if (body.isNullOrEmpty()) return MarkerParse.Absent
        var sawMarkerLine = false
        var scanned = 0
        for (rawLine in body.lineSequence()) {
            if (++scanned > MAX_LINES_SCANNED) break
            if (rawLine.length > MAX_LINE_CHARS) continue
            val line = undecorate(rawLine)
            if (!line.startsWith(MARKER_PREFIX)) continue
            sawMarkerLine = true
            val build = readFields(line.removePrefix(MARKER_PREFIX))
            if (build != null) return MarkerParse.Ok(build)
        }
        return if (sawMarkerLine) MarkerParse.Malformed else MarkerParse.Absent
    }

    /**
     * The verdict the UI acts on.
     *
     * [asset] is GitHub's own record of the file the marker names, or null when the release has no
     * such file. It is only consulted once an update would actually be offered: a release whose
     * asset failed to upload is nobody's problem while the build it describes is the one already
     * installed, and saying so would be alarming and useless.
     */
    fun decide(
        installed: InstalledBuild?,
        marker: MarkerParse,
        asset: ReleaseAsset?,
    ): UpdateVerdict {
        val published = when (marker) {
            is MarkerParse.Ok -> marker.build
            MarkerParse.Malformed -> return UpdateVerdict.Undecidable(UpdateUnknown.MARKER_MALFORMED)
            MarkerParse.Absent -> return UpdateVerdict.Undecidable(UpdateUnknown.NO_MARKER)
        }
        if (installed == null) return UpdateVerdict.Undecidable(UpdateUnknown.INSTALLED_UNKNOWN)

        // Strictly less, strictly more, or equal — and each of the three gets its own answer.
        // "Ahead" is a real state here, not a theoretical one: the owner sideloads local builds,
        // and CI can republish an older commit. Offering a downgrade would fail at the installer
        // anyway, so it is better to say what is going on.
        if (published.versionCode < installed.versionCode) {
            return UpdateVerdict.AheadOfPublished(installed, published)
        }
        if (published.versionCode == installed.versionCode) {
            return UpdateVerdict.UpToDate(installed, published)
        }
        if (asset == null) {
            return UpdateVerdict.Undecidable(UpdateUnknown.ASSET_MISSING)
        }
        if (asset.sizeBytes != published.sizeBytes) {
            return UpdateVerdict.Undecidable(UpdateUnknown.ASSET_SIZE_MISMATCH)
        }
        // GitHub computes this itself over the bytes it is storing, so where it is present it
        // turns the notes' digest from an assertion into something a party that did not write the
        // notes agrees with. Where it is absent there is nothing to corroborate against, and the
        // digest is still checked against the bytes that actually arrive.
        if (asset.sha256 != null && asset.sha256 != published.sha256) {
            return UpdateVerdict.Undecidable(UpdateUnknown.ASSET_DIGEST_MISMATCH)
        }
        return UpdateVerdict.Available(
            published = published,
            installed = installed,
            downgradeInName = readsAsOlder(published.versionName, installed.versionName),
        )
    }

    /**
     * Reads GitHub's `digest` field, which arrives as `sha256:<hex>`.
     *
     * Anything else — a missing field, an algorithm this code does not implement, a malformed hex
     * string — is null, meaning "GitHub did not say", which counts as no corroboration rather than
     * as a mismatch. An algorithm added after this code was written must not read as tampering.
     */
    fun parseAssetDigest(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        val hex = value.removePrefix("sha256:")
        if (hex.length == value.length) return null
        if (hex.length != SHA256_HEX_CHARS || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex
    }

    /**
     * Splits a `versionName` into something orderable, or null if it is not shaped like one.
     *
     * Used for **display and for the sanity flag only** — never for the update decision. A name
     * is whatever a human last typed into `build.gradle.kts`; ordering installs by it is exactly
     * the mistake this file exists to avoid.
     *
     * Follows semantic versioning where it is cheap to: build metadata after `+` does not affect
     * order, and a pre-release suffix after `-` sorts *below* the same numbers without one.
     */
    fun parseVersionName(raw: String): VersionName? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_CHARS) return null
        val withoutBuild = trimmed.substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val suffix = withoutBuild.substringAfter('-', missingDelimiterValue = "")
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = IntArray(3)
        for (index in parts.indices) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value < 0) return null
            numbers[index] = value
        }
        return VersionName(numbers[0], numbers[1], numbers[2], suffix)
    }

    /**
     * Whether a URL may be fetched as part of an update.
     *
     * Applied to the asset URL the API hands back *and* to every redirect hop, because a redirect
     * is the one place an update flow can be walked off the release page without anybody noticing.
     * Plain HTTP is refused outright: the whole integrity story below rests on the transport.
     */
    fun isTrustedReleaseUrl(url: String): Boolean {
        val scheme = "https://"
        if (!url.startsWith(scheme, ignoreCase = true)) return false
        val rest = url.substring(scheme.length)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) rest else rest.substring(0, cut)
        // `https://github.com@evil.example/...` is a hostile URL that reads as a friendly one.
        if (authority.contains('@')) return false
        val host: String
        if (authority.contains(':')) {
            val port = authority.substringAfterLast(':')
            if (port != "443") return false
            host = authority.substringBeforeLast(':').lowercase()
        } else {
            host = authority.lowercase()
        }
        if (host.isEmpty()) return false
        return host == "github.com" ||
            host == "api.github.com" ||
            host == "codeload.github.com" ||
            // GitHub serves release assets off rotating buckets under this domain; the exact
            // sub-domain has changed at least twice, so the suffix is what is pinned. The leading
            // dot matters: without it `githubusercontent.com.evil.example` would pass.
            host.endsWith(".githubusercontent.com")
    }

    // ------------------------------------------------------------------ internals

    /**
     * Strips whatever markdown or HTML the line is wearing.
     *
     * The marker is meant to be invisible on the release page, which means it will normally
     * arrive wrapped in an HTML comment; a human copying it will just as normally wrap it in
     * backticks instead.
     */
    private fun undecorate(rawLine: String): String {
        var line = rawLine.trim().trim('`').trim()
        if (line.startsWith("<!--")) line = line.removePrefix("<!--").trim()
        if (line.endsWith("-->")) line = line.removeSuffix("-->").trim()
        return line
    }

    /** Every field is required; a marker missing one describes a build we cannot verify. */
    private fun readFields(tail: String): PublishedBuild? {
        val fields = HashMap<String, String>()
        for (token in tail.split(' ', '\t').filter { it.isNotEmpty() }) {
            val split = token.indexOf('=')
            if (split <= 0) return null
            val key = token.substring(0, split)
            // A repeated key is ambiguous rather than merely odd, and ambiguity here decides
            // which bytes get installed.
            if (fields.put(key, token.substring(split + 1)) != null) return null
        }

        val code = fields["code"]?.toLongOrNull() ?: return null
        if (code < 1L || code > MAX_VERSION_CODE) return null

        val size = fields["size"]?.toLongOrNull() ?: return null
        if (size < 1L || size > MAX_APK_BYTES) return null

        val sha = fields["sha256"]?.lowercase() ?: return null
        if (sha.length != SHA256_HEX_CHARS || !sha.all { it in '0'..'9' || it in 'a'..'f' }) return null

        val name = fields["name"] ?: return null
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return null

        val asset = fields["asset"] ?: return null
        if (!isPlainFileName(asset)) return null

        return PublishedBuild(
            versionCode = code,
            versionName = name,
            sha256 = sha,
            sizeBytes = size,
            assetName = asset,
        )
    }

    /**
     * The asset name is matched against GitHub's own asset list and is never used to build a
     * path, but it is remote text and one day somebody will be tempted, so it is confined to
     * something that could only ever be a file name.
     */
    private fun isPlainFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_ASSET_NAME_CHARS) return false
        if (name == "." || name == "..") return false
        if (name.any { it == '/' || it == '\\' || it == ':' || it.code < 0x20 }) return false
        return true
    }

    /**
     * True when the published name reads as older than the installed one despite a higher code.
     *
     * Not an error and not a veto — `versionCode` still decides. It is surfaced so the screen can
     * mention it, because the one thing worse than a confusing update prompt is a confusing
     * update prompt that pretends nothing is odd.
     */
    private fun readsAsOlder(publishedName: String, installedName: String): Boolean {
        val published = parseVersionName(publishedName) ?: return false
        val installed = parseVersionName(installedName) ?: return false
        return published < installed
    }
}

/** The build in the player's hand, as the package manager reports it. */
data class InstalledBuild(val versionCode: Long, val versionName: String)

/** The build the rolling release says it is carrying. */
data class PublishedBuild(
    val versionCode: Long,
    val versionName: String,
    /** Lowercase hex. Checked against the bytes actually downloaded, never trusted on its own. */
    val sha256: String,
    val sizeBytes: Long,
    /** Which attached file to fetch, matched by name against the release's own asset list. */
    val assetName: String,
)

/** The outcome of looking for a marker line in a release body. */
sealed interface MarkerParse {
    data class Ok(val build: PublishedBuild) : MarkerParse

    /** There was a marker line and it did not parse. A broken publish, not a missing one. */
    data object Malformed : MarkerParse

    /** No line even claimed to be one. An older release, or notes written by hand. */
    data object Absent : MarkerParse
}

/** A `versionName`, ordered for display purposes only. See [AppVersion.parseVersionName]. */
data class VersionName(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** The pre-release tail after `-`, empty when there is none. Build metadata is discarded. */
    val suffix: String,
) : Comparable<VersionName> {

    override fun compareTo(other: VersionName): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // A release outranks its own pre-releases: 1.2.0-rc1 comes before 1.2.0.
        val mine = suffix.isEmpty()
        val theirs = other.suffix.isEmpty()
        if (mine != theirs) return if (mine) 1 else -1
        return suffix.compareTo(other.suffix)
    }
}

/** Why no honest answer was possible. Each one is something the player can be told. */
enum class UpdateUnknown(val message: String) {
    NO_MARKER(
        "The release page does not say which build it is carrying, so there is no way to tell " +
            "whether it is newer than yours.",
    ),
    MARKER_MALFORMED(
        "The release page says which build it is carrying, but not in a form this app can read. " +
            "That is a fault in the build that published it.",
    ),
    INSTALLED_UNKNOWN(
        "This app could not read its own version, so it cannot compare itself to anything.",
    ),
    ASSET_MISSING(
        "The release names an APK that is not attached to it. The build that published it " +
            "probably failed part way.",
    ),
    ASSET_SIZE_MISMATCH(
        "The APK attached to the release is not the size the release says it should be. It is " +
            "safer to skip this one than to install it.",
    ),
    ASSET_DIGEST_MISMATCH(
        "The release notes and the attached APK disagree about what the file is. They did not " +
            "come from the same build, so this one is being skipped.",
    ),
}

/**
 * GitHub's own record of a file attached to a release, as distinct from what the notes claim.
 *
 * [sha256] is lowercase hex, and null when GitHub supplied no digest for the asset — which is the
 * case for anything published before that field existed.
 */
data class ReleaseAsset(val sizeBytes: Long, val sha256: String?)

/** What the check concluded. Exactly one of these is true of any release. */
sealed interface UpdateVerdict {

    /** Strictly newer, with an asset present and consistent. The only state that offers a download. */
    data class Available(
        val published: PublishedBuild,
        val installed: InstalledBuild,
        /** The published name reads older than the installed one. Odd, shown, not acted on. */
        val downgradeInName: Boolean,
    ) : UpdateVerdict

    data class UpToDate(val installed: InstalledBuild, val published: PublishedBuild) : UpdateVerdict

    /** The installed build is newer than the published one — a local build, or a rebuilt release. */
    data class AheadOfPublished(
        val installed: InstalledBuild,
        val published: PublishedBuild,
    ) : UpdateVerdict

    data class Undecidable(val reason: UpdateUnknown) : UpdateVerdict
}
