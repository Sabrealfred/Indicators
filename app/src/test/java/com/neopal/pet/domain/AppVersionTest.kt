package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the self-updater that decides whether to hand a player a new APK.
 *
 * Worth this many tests because every failure mode here is quiet. A comparison that always says
 * "up to date" looks exactly like an app that is up to date; a marker parser that accepts a
 * half-written line looks exactly like one that read a good one. Nothing on screen would differ.
 *
 * The cases that are not obvious — same version, an installed build ahead of the release, a name
 * with a pre-release suffix, an integer past `Int.MAX_VALUE` — are the ones that motivated
 * splitting this out of the service in the first place.
 */
class AppVersionTest {

    private val goodSha = "a".repeat(64)

    private fun marker(
        code: String = "42",
        name: String = "1.0.0+42",
        sha: String = goodSha,
        size: String = "8123456",
        asset: String = "neopal-debug.apk",
    ) = "neopal-update: code=$code name=$name sha256=$sha size=$size asset=$asset"

    private fun installed(code: Long, name: String = "1.0.0") = InstalledBuild(code, name)

    private fun asset(size: Long, sha: String? = null) = ReleaseAsset(size, sha)

    private fun published(
        code: Long = 42L,
        name: String = "1.0.0+42",
        size: Long = 8_123_456L,
    ) = PublishedBuild(code, name, goodSha, size, "neopal-debug.apk")

    // ------------------------------------------------------------------ finding the marker

    @Test
    fun `a plain marker line parses into the build it describes`() {
        val parsed = AppVersion.parseMarker("Debug build of abc123.\n${marker()}\n")
        val build = (parsed as MarkerParse.Ok).build
        assertEquals(42L, build.versionCode)
        assertEquals("1.0.0+42", build.versionName)
        assertEquals(goodSha, build.sha256)
        assertEquals(8_123_456L, build.sizeBytes)
        assertEquals("neopal-debug.apk", build.assetName)
    }

    @Test
    fun `the marker is found inside an HTML comment, which is how it stays off the page`() {
        val body = "Download the APK below.\n\n<!-- ${marker()} -->\n"
        assertTrue(AppVersion.parseMarker(body) is MarkerParse.Ok)
    }

    @Test
    fun `the marker is found inside backticks, which is how a human would paste it`() {
        assertTrue(AppVersion.parseMarker("`${marker()}`") is MarkerParse.Ok)
    }

    @Test
    fun `a body with CRLF line endings parses, because that is what the API returns`() {
        val body = "Notes.\r\n${marker()}\r\nMore notes.\r\n"
        assertEquals(42L, (AppVersion.parseMarker(body) as MarkerParse.Ok).build.versionCode)
    }

    @Test
    fun `fields may be separated by tabs as well as spaces`() {
        val body = "neopal-update:\tcode=7\tname=1.0.0\tsha256=$goodSha\tsize=10\tasset=a.apk"
        assertEquals(7L, (AppVersion.parseMarker(body) as MarkerParse.Ok).build.versionCode)
    }

    @Test
    fun `an unknown field is ignored so a later CI can add one without breaking old installs`() {
        val body = marker() + " commit=abc123 channel=debug"
        assertTrue(AppVersion.parseMarker(body) is MarkerParse.Ok)
    }

    @Test
    fun `a body with no marker at all is absent, not malformed`() {
        val real = "Debug build of 01534c2. Download `neopal-debug.apk` on your phone."
        assertEquals(MarkerParse.Absent, AppVersion.parseMarker(real))
        assertEquals(MarkerParse.Absent, AppVersion.parseMarker(""))
        assertEquals(MarkerParse.Absent, AppVersion.parseMarker(null))
    }

    @Test
    fun `a marker line that does not parse is malformed, which is a different problem`() {
        // Reported apart from Absent on purpose: this one means the publish is broken, and the
        // player should hear that rather than a shrug about there being no update.
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker("neopal-update: code=42"))
    }

    @Test
    fun `a broken marker does not shadow a good one further down`() {
        // The likeliest source of a broken line is a human quoting an old one in the notes; the
        // likeliest source of a good one is CI. So the scan keeps going.
        val body = "neopal-update: code=oops\n${marker(code = "51")}\n"
        assertEquals(51L, (AppVersion.parseMarker(body) as MarkerParse.Ok).build.versionCode)
    }

    @Test
    fun `an uppercase digest is accepted and normalised, since hex has two spellings`() {
        val upper = "AB".repeat(32)
        val build = (AppVersion.parseMarker(marker(sha = upper)) as MarkerParse.Ok).build
        assertEquals(upper.lowercase(), build.sha256)
    }

    @Test
    fun `a digest of the wrong length or the wrong alphabet is refused`() {
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(sha = "a".repeat(63))))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(sha = "a".repeat(65))))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(sha = "g".repeat(64))))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(sha = "")))
    }

    @Test
    fun `a version code outside the range Android can hold is refused, not truncated`() {
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(code = "0")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(code = "-3")))
        // One past Int.MAX_VALUE. `toLongOrNull` is perfectly happy with this, which is exactly
        // why the bound is checked explicitly rather than left to the parse.
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(code = "2147483648")))
        assertTrue(AppVersion.parseMarker(marker(code = "2147483647")) is MarkerParse.Ok)
    }

    @Test
    fun `a version code too large even for a Long is refused rather than wrapping`() {
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(code = "99999999999999999999")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(code = "42.0")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(code = " 42")))
    }

    @Test
    fun `a declared size that is zero or absurd is refused`() {
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(size = "0")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(size = "-1")))
        val tooBig = (AppVersion.MAX_APK_BYTES + 1).toString()
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(size = tooBig)))
    }

    @Test
    fun `an asset name that could be a path is refused`() {
        // Nothing builds a path out of this today. It is confined anyway, because the day
        // somebody uses it to name a file is not the day they will re-read this parser.
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(asset = "../../etc/passwd")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(asset = "a/b.apk")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(asset = "a\\b.apk")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(asset = "..")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(asset = "")))
    }

    @Test
    fun `a repeated field is ambiguous about which bytes to install, so it is refused`() {
        val body = marker() + " code=99"
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(body))
    }

    @Test
    fun `a token that is not a key equals value is refused`() {
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker() + " garbage"))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker() + " =novalue"))
    }

    @Test
    fun `an empty name is refused and a very long one is refused`() {
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(name = "")))
        assertEquals(MarkerParse.Malformed, AppVersion.parseMarker(marker(name = "n".repeat(65))))
    }

    // ------------------------------------------------------------------ the verdict

    @Test
    fun `a strictly higher code is the only thing that offers an update`() {
        val verdict = AppVersion.decide(installed(41L), MarkerParse.Ok(published(42L)), asset(8_123_456L))
        val available = verdict as UpdateVerdict.Available
        assertEquals(42L, available.published.versionCode)
        assertFalse(available.downgradeInName)
    }

    @Test
    fun `the same code is up to date, not an update`() {
        val verdict = AppVersion.decide(installed(42L), MarkerParse.Ok(published(42L)), asset(8_123_456L))
        assertTrue(verdict is UpdateVerdict.UpToDate)
    }

    @Test
    fun `an installed build ahead of the release says so instead of offering a downgrade`() {
        // Real, not theoretical: the owner sideloads local builds, and CI can republish an older
        // commit. The installer would refuse the downgrade anyway; better to explain than to fail.
        val verdict = AppVersion.decide(installed(60L), MarkerParse.Ok(published(42L)), asset(8_123_456L))
        val ahead = verdict as UpdateVerdict.AheadOfPublished
        assertEquals(60L, ahead.installed.versionCode)
        assertEquals(42L, ahead.published.versionCode)
    }

    @Test
    fun `no marker means no honest comparison, and that is what is reported`() {
        val verdict = AppVersion.decide(installed(1L), MarkerParse.Absent, asset(8_123_456L))
        assertEquals(UpdateUnknown.NO_MARKER, (verdict as UpdateVerdict.Undecidable).reason)
    }

    @Test
    fun `a malformed marker is reported as a broken publish`() {
        val verdict = AppVersion.decide(installed(1L), MarkerParse.Malformed, asset(8_123_456L))
        assertEquals(UpdateUnknown.MARKER_MALFORMED, (verdict as UpdateVerdict.Undecidable).reason)
    }

    @Test
    fun `an app that cannot read its own version compares itself to nothing`() {
        val verdict = AppVersion.decide(null, MarkerParse.Ok(published()), asset(8_123_456L))
        assertEquals(UpdateUnknown.INSTALLED_UNKNOWN, (verdict as UpdateVerdict.Undecidable).reason)
    }

    @Test
    fun `a newer release whose APK never uploaded is undecidable, not offered`() {
        val verdict = AppVersion.decide(installed(41L), MarkerParse.Ok(published(42L)), null)
        assertEquals(UpdateUnknown.ASSET_MISSING, (verdict as UpdateVerdict.Undecidable).reason)
    }

    @Test
    fun `notes and asset that disagree about size mean the two did not come from one build`() {
        // CI deletes and recreates the release on every push, so notes and asset normally land
        // together. When they do not, one of them is stale, and there is no way to know which.
        val verdict = AppVersion.decide(installed(41L), MarkerParse.Ok(published(42L)), asset(9_000_000L))
        assertEquals(UpdateUnknown.ASSET_SIZE_MISMATCH, (verdict as UpdateVerdict.Undecidable).reason)
    }

    @Test
    fun `a broken release that matches the installed build is not worth alarming anyone about`() {
        // The asset checks come after the comparison on purpose. Nothing here is going to be
        // downloaded, so a missing asset is nobody's problem.
        assertTrue(AppVersion.decide(installed(42L), MarkerParse.Ok(published(42L)), null) is UpdateVerdict.UpToDate)
        assertTrue(
            AppVersion.decide(installed(50L), MarkerParse.Ok(published(42L)), null)
                is UpdateVerdict.AheadOfPublished,
        )
    }

    @Test
    fun `a higher code with a lower-reading name is still offered, and flagged`() {
        val verdict = AppVersion.decide(
            installed(41L, name = "1.4.0"),
            MarkerParse.Ok(published(42L, name = "1.0.0")),
            asset(8_123_456L),
        )
        assertTrue((verdict as UpdateVerdict.Available).downgradeInName)
    }

    @Test
    fun `a name that does not parse is not treated as a disagreement`() {
        val verdict = AppVersion.decide(
            installed(41L, name = "nightly"),
            MarkerParse.Ok(published(42L, name = "1.0.0")),
            asset(8_123_456L),
        )
        assertFalse((verdict as UpdateVerdict.Available).downgradeInName)
    }

    @Test
    fun `GitHub's own digest corroborates the notes, and a disagreement stops the update`() {
        // Worth having because GitHub computes this over the bytes it is storing, so it is the
        // one part of the release description that was not written by whoever wrote the notes.
        val agreeing = AppVersion.decide(installed(41L), MarkerParse.Ok(published(42L)), asset(8_123_456L, goodSha))
        assertTrue(agreeing is UpdateVerdict.Available)

        val disagreeing = AppVersion.decide(
            installed(41L),
            MarkerParse.Ok(published(42L)),
            asset(8_123_456L, "c".repeat(64)),
        )
        assertEquals(
            UpdateUnknown.ASSET_DIGEST_MISMATCH,
            (disagreeing as UpdateVerdict.Undecidable).reason,
        )
    }

    @Test
    fun `a release with no digest is still offered, because absent is not a mismatch`() {
        // The field was not present on this repository's own release the day this was written.
        val verdict = AppVersion.decide(installed(41L), MarkerParse.Ok(published(42L)), asset(8_123_456L, null))
        assertTrue(verdict is UpdateVerdict.Available)
    }

    @Test
    fun `the digest field is read out of its algorithm prefix`() {
        val hex = "4690b286c9760109e9e98b64bf52af06cc11f78de3b206e88fa3e94183b380bd"
        assertEquals(hex, AppVersion.parseAssetDigest("sha256:$hex"))
        assertEquals(hex, AppVersion.parseAssetDigest("  SHA256:${hex.uppercase()}  "))
    }

    @Test
    fun `an unprefixed, short or unknown digest reads as no digest rather than a bad one`() {
        // An algorithm added to the API after this code was written must not look like tampering.
        assertNull(AppVersion.parseAssetDigest(null))
        assertNull(AppVersion.parseAssetDigest(""))
        assertNull(AppVersion.parseAssetDigest("a".repeat(64)))
        assertNull(AppVersion.parseAssetDigest("sha512:" + "a".repeat(128)))
        assertNull(AppVersion.parseAssetDigest("sha256:" + "a".repeat(63)))
        assertNull(AppVersion.parseAssetDigest("sha256:" + "z".repeat(64)))
    }

    // ------------------------------------------------------------------ version names

    @Test
    fun `a three part name parses`() {
        assertEquals(VersionName(1, 2, 3, ""), AppVersion.parseVersionName("1.2.3"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertEquals(VersionName(1, 0, 0, ""), AppVersion.parseVersionName("1"))
        assertEquals(VersionName(1, 2, 0, ""), AppVersion.parseVersionName("1.2"))
    }

    @Test
    fun `build metadata after a plus does not change the version`() {
        assertEquals(AppVersion.parseVersionName("1.0.0"), AppVersion.parseVersionName("1.0.0+42"))
        assertEquals(VersionName(1, 0, 0, "rc1"), AppVersion.parseVersionName("1.0.0-rc1+42"))
    }

    @Test
    fun `a pre-release suffix sorts below the release it precedes`() {
        val rc = AppVersion.parseVersionName("1.2.0-rc1")!!
        val release = AppVersion.parseVersionName("1.2.0")!!
        assertTrue(rc < release)
        assertTrue(release > rc)
        assertTrue(AppVersion.parseVersionName("1.2.0-rc1")!! < AppVersion.parseVersionName("1.2.0-rc2")!!)
    }

    @Test
    fun `names order by the numbers before anything else`() {
        val ordered = listOf("0.9.9", "1.0.0-alpha", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
            .map { AppVersion.parseVersionName(it)!! }
        for (index in 1 until ordered.size) {
            assertTrue("${ordered[index - 1]} should sort below ${ordered[index]}", ordered[index - 1] < ordered[index])
        }
    }

    @Test
    fun `a name that is not a version at all is null rather than a guess`() {
        assertNull(AppVersion.parseVersionName(""))
        assertNull(AppVersion.parseVersionName("   "))
        assertNull(AppVersion.parseVersionName("nightly"))
        assertNull(AppVersion.parseVersionName("1.2.3.4"))
        assertNull(AppVersion.parseVersionName("1.x.0"))
        assertNull(AppVersion.parseVersionName("-1.0.0"))
    }

    @Test
    fun `a name whose numbers overflow an Int is null, not a wrapped negative`() {
        assertNull(AppVersion.parseVersionName("99999999999.0.0"))
        assertNull(AppVersion.parseVersionName("2147483648"))
        assertEquals(VersionName(2147483647, 0, 0, ""), AppVersion.parseVersionName("2147483647"))
    }

    // ------------------------------------------------------------------ where bytes may come from

    @Test
    fun `the release API and the asset buckets are trusted`() {
        assertTrue(AppVersion.isTrustedReleaseUrl("https://api.github.com/repos/a/b/releases/tags/x"))
        assertTrue(AppVersion.isTrustedReleaseUrl("https://github.com/a/b/releases/download/x/y.apk"))
        assertTrue(AppVersion.isTrustedReleaseUrl("https://objects.githubusercontent.com/x?token=1"))
        assertTrue(AppVersion.isTrustedReleaseUrl("https://release-assets.githubusercontent.com/x"))
        assertTrue(AppVersion.isTrustedReleaseUrl("HTTPS://API.GITHUB.COM/repos"))
        assertTrue(AppVersion.isTrustedReleaseUrl("https://github.com:443/a/b"))
    }

    @Test
    fun `plain HTTP is refused, because the integrity story rests on the transport`() {
        assertFalse(AppVersion.isTrustedReleaseUrl("http://github.com/a/b"))
        assertFalse(AppVersion.isTrustedReleaseUrl("ftp://github.com/a/b"))
        assertFalse(AppVersion.isTrustedReleaseUrl(""))
        assertFalse(AppVersion.isTrustedReleaseUrl("https://"))
    }

    @Test
    fun `a host that merely contains a trusted name is refused`() {
        assertFalse(AppVersion.isTrustedReleaseUrl("https://github.com.evil.example/a"))
        assertFalse(AppVersion.isTrustedReleaseUrl("https://githubusercontent.com.evil.example/a"))
        assertFalse(AppVersion.isTrustedReleaseUrl("https://evil.example/github.com/a"))
        assertFalse(AppVersion.isTrustedReleaseUrl("https://notgithub.com/a"))
    }

    @Test
    fun `a userinfo prefix cannot be used to make a hostile URL read as a friendly one`() {
        assertFalse(AppVersion.isTrustedReleaseUrl("https://github.com@evil.example/a"))
        assertFalse(AppVersion.isTrustedReleaseUrl("https://user:pass@github.com/a"))
    }

    @Test
    fun `a non-standard port is refused`() {
        assertFalse(AppVersion.isTrustedReleaseUrl("https://github.com:8443/a"))
        assertFalse(AppVersion.isTrustedReleaseUrl("https://github.com:80/a"))
    }

    // ------------------------------------------------------------------ end to end

    @Test
    fun `the exact notes CI is asked to publish drive a correct offer`() {
        // Kept verbatim from the CI snippet in the report, so that a change to one and not the
        // other fails here rather than on somebody's phone.
        val body = """
            Debug build of 0123456789abcdef0123456789abcdef01234567.

            Download `neopal-debug.apk` on your phone and allow installing from unknown sources.

            <!-- neopal-update: code=137 name=1.0.0+137 sha256=${"b".repeat(64)} size=9437184 asset=neopal-debug.apk -->
        """.trimIndent()

        val parse = AppVersion.parseMarker(body)
        val build = (parse as MarkerParse.Ok).build
        assertEquals(137L, build.versionCode)

        val offered = AppVersion.decide(installed(136L, "1.0.0+136"), parse, asset(9_437_184L))
        assertTrue(offered is UpdateVerdict.Available)

        val already = AppVersion.decide(installed(137L, "1.0.0+137"), parse, asset(9_437_184L))
        assertTrue(already is UpdateVerdict.UpToDate)
    }
}
