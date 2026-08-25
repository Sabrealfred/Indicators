package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download half of the on-device brain, tested where it can be: on a plain JVM.
 *
 * Every case below is one that will happen on somebody's phone, at night, on a network this
 * machine has never seen — a dropped Wi-Fi, a proxy that ignores a range request, a partial file
 * left by a download of a file that has since been replaced, a publisher that refuses to serve
 * anything to an app that cannot sign in. None of them can be reproduced here, which is exactly
 * why the *decision* about each was written as a function that can.
 */
class ModelFetchTest {

    private val hosts = FetchableModels.TRUSTED_HOSTS

    /** A real commit hash is forty hex characters. This is a stand-in of the right shape. */
    private val pinned = "0".repeat(39) + "f"

    private fun model(
        size: Long = 3L * 1024L * 1024L * 1024L,
        repoId: String = "litert-community/Gemma3-1B-IT",
        fileName: String = "gemma3-1b-it-int4.litertlm",
        revision: String = pinned,
        sha: String? = null,
        id: String = "small",
    ) = FetchableModel(
        id = id,
        displayName = "the small brain",
        repoId = repoId,
        fileName = fileName,
        revision = revision,
        sizeBytes = size,
        sha256 = sha,
    )

    // ------------------------------------------------------------------ the catalogue entry

    @Test
    fun `a pinned entry builds the verified url shape and has no fault`() {
        val m = model()
        assertEquals(
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/$pinned/" +
                "gemma3-1b-it-int4.litertlm?download=true",
            m.downloadUrl,
        )
        assertEquals("https://huggingface.co/litert-community/Gemma3-1B-IT", m.pageUrl)
        assertNull(ModelFetchRules.fault(m, hosts))
    }

    @Test
    fun `an unpinned entry has no url at all and says which fact is missing`() {
        val m = model(revision = FetchableModels.UNPINNED)
        assertNull(m.downloadUrl)
        assertEquals(ModelDescriptorFault.UNPINNED_REVISION, ModelFetchRules.fault(m, hosts))
    }

    @Test
    fun `a branch name is not a pin`() {
        // It would work. It would also let the bytes change between two halves of a resumed
        // download, which is the one thing the pin exists to prevent — and with no published
        // checksum, nothing downstream would notice.
        assertFalse(ModelFetchRules.isPinnedRevision("main"))
        assertFalse(ModelFetchRules.isPinnedRevision("0".repeat(7)))
        assertFalse(ModelFetchRules.isPinnedRevision("g".repeat(40)))
        assertTrue(ModelFetchRules.isPinnedRevision(pinned))
    }

    @Test
    fun `an empty host pin trusts nothing`() {
        assertFalse(ModelFetchRules.isTrustedModelUrl("https://huggingface.co/x", emptySet()))
        assertEquals(ModelDescriptorFault.UNTRUSTED_URL, ModelFetchRules.fault(model(), emptySet()))
    }

    @Test
    fun `host pins match exactly, and a dot pin matches only real subdomains`() {
        assertTrue(ModelFetchRules.isTrustedModelUrl("https://huggingface.co/a/b", hosts))
        // Where the download actually lands after the publisher's redirect. Unverified from
        // here, which is why the pin is a suffix of a domain the same publisher owns.
        assertTrue(ModelFetchRules.isTrustedModelUrl("https://cdn-lfs-us-1.hf.co/a", hosts))
        assertTrue(ModelFetchRules.isTrustedModelUrl("https://cas-bridge.xethub.hf.co/a", hosts))
        // The leading dot is what stops these two.
        assertFalse(ModelFetchRules.isTrustedModelUrl("https://hf.co.evil.test/a", hosts))
        assertFalse(ModelFetchRules.isTrustedModelUrl("https://nothuggingface.co/a", hosts))
    }

    @Test
    fun `plain http, userinfo and odd ports are all refused`() {
        assertFalse(ModelFetchRules.isTrustedModelUrl("http://huggingface.co/x", hosts))
        assertFalse(ModelFetchRules.isTrustedModelUrl("https://huggingface.co@evil.test/x", hosts))
        assertFalse(ModelFetchRules.isTrustedModelUrl("https://huggingface.co:8443/x", hosts))
        // An explicit 443 is the same address as no port at all.
        assertTrue(ModelFetchRules.isTrustedModelUrl("https://huggingface.co:443/x", hosts))
    }

    @Test
    fun `a file name that is a path is refused`() {
        assertFalse(ModelFetchRules.isPlainFileName("../../databases/pet.db"))
        assertFalse(ModelFetchRules.isPlainFileName("sub/dir.litertlm"))
        assertFalse(ModelFetchRules.isPlainFileName("has space.litertlm"))
        assertFalse(ModelFetchRules.isPlainFileName(""))
        assertFalse(ModelFetchRules.isPlainFileName(".."))
        assertTrue(ModelFetchRules.isPlainFileName("gemma-4-E2B-it.litertlm"))
        assertEquals(
            ModelDescriptorFault.BAD_FILE_NAME,
            ModelFetchRules.fault(model(fileName = "../pet.db"), hosts),
        )
    }

    @Test
    fun `a repo id has to be owner slash name and nothing cleverer`() {
        assertTrue(ModelFetchRules.isRepoId("litert-community/Gemma3-1B-IT"))
        assertFalse(ModelFetchRules.isRepoId("Gemma3-1B-IT"))
        assertFalse(ModelFetchRules.isRepoId("a/b/c"))
        assertFalse(ModelFetchRules.isRepoId("../secrets"))
        assertFalse(ModelFetchRules.isRepoId("owner/name?x=1"))
        assertFalse(ModelFetchRules.isRepoId("owner/ name"))
        assertEquals(
            ModelDescriptorFault.BAD_REPO_ID,
            ModelFetchRules.fault(model(repoId = "solo"), hosts),
        )
    }

    @Test
    fun `sizes outside anything a model could be are refused`() {
        assertEquals(ModelDescriptorFault.BAD_SIZE, ModelFetchRules.fault(model(size = 4_000L), hosts))
        assertEquals(
            ModelDescriptorFault.BAD_SIZE,
            ModelFetchRules.fault(model(size = 32L * 1024L * 1024L * 1024L), hosts),
        )
    }

    @Test
    fun `a missing digest is allowed and a malformed one is not`() {
        // Null is the shipping state: the publisher's allowlist gives exact sizes and no hashes.
        assertNull(ModelFetchRules.fault(model(sha = null), hosts))
        assertEquals(ModelDescriptorFault.BAD_DIGEST, ModelFetchRules.fault(model(sha = "abc"), hosts))
        assertFalse(ModelFetchRules.isSha256Hex("A".repeat(64)))
        assertTrue(ModelFetchRules.isSha256Hex("0123456789abcdef".repeat(4)))
    }

    @Test
    fun `every catalogue entry is complete and none of them stops at a different fault`() {
        // This test used to assert that every entry stopped at UNPINNED_REVISION, because the
        // revision was the one fact the machine this was written on could not reach the host to
        // read. It can be read now -- not from the host, which is still refused, but from the
        // allowlist the publisher's own app ships, which pins each model to a commit for the same
        // reason we need one. So the assertion flips: nothing is faulty.
        //
        // The half that was always the point survives unchanged. Every entry must stop at *no*
        // fault rather than at some other one, because a different fault is what a mistyped name,
        // a wrong repository or a bad size would look like.
        assertEquals(3, FetchableModels.known.size)
        FetchableModels.known.forEach { entry ->
            assertNull("unexpected fault for ${entry.id}", ModelFetchRules.fault(entry))
            assertTrue(ModelFetchRules.isRepoId(entry.repoId))
            assertTrue(ModelFetchRules.isPlainFileName(entry.fileName))
            assertTrue(entry.fileName.endsWith(".litertlm"))
            assertTrue(ModelFetchRules.isPinnedRevision(entry.revision))
            assertTrue(entry.sizeBytes > ModelFetchRules.MIN_MODEL_BYTES)
            assertTrue(entry.sizeBytes < ModelFetchRules.MAX_MODEL_BYTES)
        }
        assertEquals(3, FetchableModels.fetchable.size)
    }

    @Test
    fun `an entry added without a revision is still refused rather than quietly offered`() {
        // The guard the flipped assertion above used to provide, kept alive on a synthetic entry.
        // The next model added to this catalogue starts blank, and the failure it must not have
        // is the silent one: appearing in `fetchable` and producing a URL that cannot resolve.
        val unpinned = FetchableModels.SMALL.copy(revision = FetchableModels.UNPINNED)
        assertEquals(ModelDescriptorFault.UNPINNED_REVISION, ModelFetchRules.fault(unpinned))
        assertNull(unpinned.downloadUrl)
        assertFalse(ModelFetchRules.isPinnedRevision("6e5c4f1e"))
        assertFalse(ModelFetchRules.isPinnedRevision("main"))
    }

    @Test
    fun `the download URL is the one the publisher's own app builds`() {
        // Character for character. A revision transcribed with one digit wrong would fail as a
        // 404, and a 404 on this host is indistinguishable from "the repository is gated" -- so a
        // typo here would be diagnosed as a licensing problem and chased in the wrong place
        // entirely.
        assertEquals(
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/" +
                "42d538a932e8d5b12e6b3b455f5572560bd60b2c/gemma3-1b-it-int4.litertlm?download=true",
            FetchableModels.SMALL.downloadUrl,
        )
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
            FetchableModels.MEDIUM.downloadUrl,
        )
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
                "28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true",
            FetchableModels.LARGE.downloadUrl,
        )
    }

    @Test
    fun `the small model is the only one a mobile-data download could be argued for`() {
        assertEquals("557.3 MB", ModelFetchRules.describeBytes(FetchableModels.SMALL.sizeBytes))
        assertEquals("2.4 GB", ModelFetchRules.describeBytes(FetchableModels.MEDIUM.sizeBytes))
        assertEquals("3.4 GB", ModelFetchRules.describeBytes(FetchableModels.LARGE.sizeBytes))
    }

    @Test
    fun `an entry with no id is refused before anything else`() {
        assertEquals(ModelDescriptorFault.NO_ID, ModelFetchRules.fault(model(id = " "), hosts))
    }

    // ------------------------------------------------------------------ room

    @Test
    fun `room is measured against what is left, not the whole file`() {
        val m = model(size = 3_000L * 1024L * 1024L)
        val fresh = ModelFetchRules.roomFor(m, usableBytes = 1_000L * 1024L * 1024L)
        assertFalse(fresh.enough)

        // Two and a half gigabytes already downloaded: what is left is 500 MB plus headroom, and
        // a device with a gigabyte free can finish. Answering "you need three gigabytes" here
        // would refuse a download that was about to succeed.
        val resumed = ModelFetchRules.roomFor(
            m,
            usableBytes = 1_000L * 1024L * 1024L,
            alreadyOnDisk = 2_500L * 1024L * 1024L,
        )
        assertTrue(resumed.enough)
        assertEquals(500L * 1024L * 1024L + ModelFetchRules.STORAGE_HEADROOM_BYTES, resumed.needed)
    }

    @Test
    fun `a partial larger than the file cannot make the requirement negative`() {
        val m = model(size = 2L * 1024L * 1024L * 1024L)
        val verdict = ModelFetchRules.roomFor(m, usableBytes = 0L, alreadyOnDisk = m.sizeBytes * 4L)
        assertEquals(ModelFetchRules.STORAGE_HEADROOM_BYTES, verdict.needed)
        assertFalse(verdict.enough)
    }

    @Test
    fun `an unreadable free-space figure is read as enough`() {
        // Some ROMs answer oddly. The write will fail honestly if there is really no room;
        // refusing on an unreadable number would make the feature unavailable there for ever.
        assertTrue(ModelFetchRules.roomFor(model(), usableBytes = -1L).enough)
    }

    // ------------------------------------------------------------------ network

    @Test
    fun `wifi only is the default and the override is per download`() {
        assertEquals(
            ModelNetworkVerdict.NEEDS_CONSENT,
            ModelFetchRules.network(FetchNetwork.METERED, allowMetered = false),
        )
        assertEquals(
            ModelNetworkVerdict.GO,
            ModelFetchRules.network(FetchNetwork.METERED, allowMetered = true),
        )
        assertEquals(
            ModelNetworkVerdict.GO,
            ModelFetchRules.network(FetchNetwork.UNMETERED, allowMetered = false),
        )
        // No network is not a failure and not a consent question: it resolves itself.
        assertEquals(
            ModelNetworkVerdict.NO_NETWORK,
            ModelFetchRules.network(FetchNetwork.NONE, allowMetered = true),
        )
    }

    // ------------------------------------------------------------------ resume

    @Test
    fun `nothing on disk starts from scratch`() {
        assertEquals(ModelResumePlan.FromScratch, ModelFetchRules.resume(0L, model()))
        assertEquals(ModelResumePlan.FromScratch, ModelFetchRules.resume(-5L, model()))
    }

    @Test
    fun `a substantial partial is resumed from exactly where it stopped`() {
        val at = 1_500L * 1024L * 1024L
        assertEquals(ModelResumePlan.Resume(at), ModelFetchRules.resume(at, model()))
    }

    @Test
    fun `a trivial partial is not worth a range request`() {
        assertEquals(ModelResumePlan.FromScratch, ModelFetchRules.resume(64L * 1024L, model()))
        assertEquals(
            ModelResumePlan.Resume(ModelFetchRules.MIN_RESUME_BYTES),
            ModelFetchRules.resume(ModelFetchRules.MIN_RESUME_BYTES, model()),
        )
    }

    @Test
    fun `a full length partial is verified rather than refetched`() {
        val m = model()
        assertEquals(ModelResumePlan.VerifyOnly, ModelFetchRules.resume(m.sizeBytes, m))
    }

    @Test
    fun `a partial longer than the model is not the model`() {
        val m = model()
        assertEquals(ModelResumePlan.Discard, ModelFetchRules.resume(m.sizeBytes + 1L, m))
    }

    // ------------------------------------------------------------------ the publisher's answer

    @Test
    fun `a refusal is an answer with a remedy, not an error`() {
        assertEquals(ModelAccess.OPEN, ModelFetchRules.judgeAccess(200))
        assertEquals(ModelAccess.OPEN, ModelFetchRules.judgeAccess(206))
        assertEquals(ModelAccess.NEEDS_SIGN_IN, ModelFetchRules.judgeAccess(401))
        assertEquals(ModelAccess.NEEDS_AGREEMENT, ModelFetchRules.judgeAccess(403))
        // A gated repository can look exactly like a missing one from outside.
        assertEquals(ModelAccess.NOT_FOUND, ModelFetchRules.judgeAccess(404))
        assertEquals(ModelAccess.BUSY, ModelFetchRules.judgeAccess(429))
        assertEquals(ModelAccess.SERVER_ERROR, ModelFetchRules.judgeAccess(503))
        assertEquals(ModelAccess.REFUSED, ModelFetchRules.judgeAccess(418))
    }

    @Test
    fun `the three refusals a player can do something about are marked as such`() {
        assertTrue(ModelFetchRules.isGate(ModelAccess.NEEDS_AGREEMENT))
        assertTrue(ModelFetchRules.isGate(ModelAccess.NEEDS_SIGN_IN))
        assertTrue(ModelFetchRules.isGate(ModelAccess.NOT_FOUND))
        // These two are somebody else's afternoon, not a gate. Retrying is the whole remedy.
        assertFalse(ModelFetchRules.isGate(ModelAccess.BUSY))
        assertFalse(ModelFetchRules.isGate(ModelAccess.SERVER_ERROR))
    }

    // ------------------------------------------------------------------ Content-Range

    @Test
    fun `content range parses the ordinary form`() {
        val range = ModelFetchRules.parseContentRange("bytes 100-999/1000")
        assertNotNull(range)
        assertEquals(100L, range!!.first)
        assertEquals(999L, range.last)
        assertEquals(1000L, range.total)
    }

    @Test
    fun `content range accepts an unknown total and rejects nonsense`() {
        assertNull(ModelFetchRules.parseContentRange("bytes 0-99/*")!!.total)
        assertNull(ModelFetchRules.parseContentRange(null))
        assertNull(ModelFetchRules.parseContentRange(""))
        assertNull(ModelFetchRules.parseContentRange("items 0-99/1000"))
        assertNull(ModelFetchRules.parseContentRange("bytes */1000"))
        assertNull(ModelFetchRules.parseContentRange("bytes 100-99/1000"))
        assertNull(ModelFetchRules.parseContentRange("bytes 0-99"))
        // The total must be past the last byte it claims to have sent.
        assertNull(ModelFetchRules.parseContentRange("bytes 0-99/50"))
    }

    // ------------------------------------------------------------------ judging the answer

    @Test
    fun `a server that ignores the range means start over`() {
        // This is the case that would otherwise glue two files together: a 200 carries the whole
        // body, and appending it to a partial produces a file of the right kind and wrong bytes.
        assertEquals(
            ModelRangeVerdict.FromZero,
            ModelFetchRules.judgeRange(200, contentRange = null, wantFrom = 1_000L, total = 5_000L),
        )
    }

    @Test
    fun `a 206 that continues the right file is accepted`() {
        val verdict = ModelFetchRules.judgeRange(206, "bytes 1000-4999/5000", 1_000L, 5_000L)
        assertEquals(ModelRangeVerdict.Continues(1_000L, 4_999L), verdict)
    }

    @Test
    fun `a 206 that sends only part of the rest is still usable`() {
        // Legal, and it simply means another resume afterwards. What matters is that the caller
        // is told how far this answer goes rather than assuming it runs to the end.
        val verdict = ModelFetchRules.judgeRange(206, "bytes 1000-2999/5000", 1_000L, 5_000L)
        assertEquals(ModelRangeVerdict.Continues(1_000L, 2_999L), verdict)
    }

    @Test
    fun `a 206 about a different file or a different offset is refused`() {
        assertEquals(
            ModelRangeVerdict.Refused(ModelRangeFault.WRONG_TOTAL),
            ModelFetchRules.judgeRange(206, "bytes 1000-4999/9999", 1_000L, 5_000L),
        )
        assertEquals(
            ModelRangeVerdict.Refused(ModelRangeFault.WRONG_START),
            ModelFetchRules.judgeRange(206, "bytes 2000-4999/5000", 1_000L, 5_000L),
        )
        assertEquals(
            ModelRangeVerdict.Refused(ModelRangeFault.UNPARSEABLE),
            ModelFetchRules.judgeRange(206, null, 1_000L, 5_000L),
        )
        // A partial answer that runs past the end of the file we think we are downloading.
        assertEquals(
            ModelRangeVerdict.Refused(ModelRangeFault.WRONG_TOTAL),
            ModelFetchRules.judgeRange(206, "bytes 1000-9999/*", 1_000L, 5_000L),
        )
    }

    @Test
    fun `a 416 says the partial is past the end`() {
        assertEquals(
            ModelRangeVerdict.Refused(ModelRangeFault.NOT_SATISFIABLE),
            ModelFetchRules.judgeRange(416, "bytes */4000", 5_000L, 5_000L),
        )
    }

    @Test
    fun `an unranged request wants the whole file`() {
        assertEquals(
            ModelRangeVerdict.FromZero,
            ModelFetchRules.judgeRange(200, null, wantFrom = 0L, total = 5_000L),
        )
        assertEquals(
            ModelRangeVerdict.Continues(0L, 4_999L),
            ModelFetchRules.judgeRange(206, "bytes 0-4999/5000", wantFrom = 0L, total = 5_000L),
        )
        assertEquals(
            ModelRangeVerdict.Refused(ModelRangeFault.WRONG_START),
            ModelFetchRules.judgeRange(206, "bytes 10-4999/5000", wantFrom = 0L, total = 5_000L),
        )
    }

    // ------------------------------------------------------------------ validators and digests

    @Test
    fun `a strong validator survives and a weak one does not`() {
        assertEquals("\"abc123\"", ModelFetchRules.normaliseValidator(" \"abc123\" "))
        assertNull(ModelFetchRules.normaliseValidator("W/\"abc123\""))
        assertNull(ModelFetchRules.normaliseValidator("abc123"))
        assertNull(ModelFetchRules.normaliseValidator("\"\""))
        assertNull(ModelFetchRules.normaliseValidator(null))
        assertNull(ModelFetchRules.normaliseValidator("\"a".repeat(200)))
    }

    @Test
    fun `a validator that happens to be a sha256 is used as one`() {
        val digest = "0123456789abcdef".repeat(4)
        assertEquals(digest, ModelFetchRules.digestFromValidator("\"$digest\""))
        // Upper case is normalised, because the comparison downstream is a string equality.
        assertEquals(digest, ModelFetchRules.digestFromValidator("\"${digest.uppercase()}\""))
        // Anything else simply is not one, and nothing depends on it being there.
        assertNull(ModelFetchRules.digestFromValidator("\"686897696a7c876b7e\""))
        assertNull(ModelFetchRules.digestFromValidator("W/\"$digest\""))
    }

    // ------------------------------------------------------------------ keeping what arrived

    @Test
    fun `the wrong length is refused however good the digest looks`() {
        val m = model(size = 5_000L)
        assertEquals(
            ModelAcceptance.WrongSize,
            ModelFetchRules.accept(m, bytesOnDisk = 4_999L, computedDigest = "a".repeat(64)),
        )
    }

    @Test
    fun `a published digest is the strongest claim and is enforced`() {
        val digest = "a".repeat(64)
        val m = model(size = 5_000L, sha = digest)
        assertEquals(
            ModelAcceptance.Accepted(ModelVerification.PUBLISHED_DIGEST),
            ModelFetchRules.accept(m, 5_000L, computedDigest = digest),
        )
        assertEquals(
            ModelAcceptance.WrongDigest,
            ModelFetchRules.accept(m, 5_000L, computedDigest = "b".repeat(64)),
        )
        // A digest that could not be computed is not a pass.
        assertEquals(
            ModelAcceptance.WrongDigest,
            ModelFetchRules.accept(m, 5_000L, computedDigest = null),
        )
    }

    @Test
    fun `with no published digest the download's own validator is used if it is one`() {
        val digest = "c".repeat(64)
        val m = model(size = 5_000L, sha = null)
        assertEquals(
            ModelAcceptance.Accepted(ModelVerification.SERVER_DIGEST),
            ModelFetchRules.accept(m, 5_000L, computedDigest = digest, serverDigest = digest),
        )
        assertEquals(
            ModelAcceptance.WrongDigest,
            ModelFetchRules.accept(m, 5_000L, computedDigest = "d".repeat(64), serverDigest = digest),
        )
    }

    @Test
    fun `with nothing to compare against the length alone is accepted, and labelled`() {
        val m = model(size = 5_000L, sha = null)
        val verdict = ModelFetchRules.accept(m, 5_000L, computedDigest = "e".repeat(64))
        assertEquals(ModelAcceptance.Accepted(ModelVerification.SIZE_ONLY), verdict)
        // And the screen says so rather than claiming more than happened.
        val note = ModelFetchPlanner.verificationNote(ModelVerification.SIZE_ONLY)
        assertTrue(note.contains("length alone"))
        assertFalse(ModelFetchPlanner.verificationNote(ModelVerification.PUBLISHED_DIGEST) == note)
    }

    // ------------------------------------------------------------------ saying it to a person

    @Test
    fun `progress never divides by zero`() {
        assertEquals(0f, ModelFetchRules.progressFraction(500L, 0L), 0f)
        assertEquals(0.5f, ModelFetchRules.progressFraction(500L, 1_000L), 0.0001f)
        // A server that sends more than it declared must not produce a meter past the end.
        assertEquals(1f, ModelFetchRules.progressFraction(2_000L, 1_000L), 0f)
    }

    @Test
    fun `sizes read the way a person would say them`() {
        assertEquals("0 B", ModelFetchRules.describeBytes(0L))
        assertEquals("512 B", ModelFetchRules.describeBytes(512L))
        assertEquals("1 KB", ModelFetchRules.describeBytes(1024L))
        assertEquals("1.0 MB", ModelFetchRules.describeBytes(1024L * 1024L))
        assertEquals("1.3 GB", ModelFetchRules.describeBytes((1.3 * 1024 * 1024 * 1024).toLong()))
        assertEquals("3.0 GB", ModelFetchRules.describeBytes(3L * 1024L * 1024L * 1024L))
    }

    @Test
    fun `the progress line is one sentence in one place`() {
        assertEquals(
            "1.5 GB of 3.0 GB — 50%",
            ModelFetchPlanner.progressLine(
                (1.5 * 1024 * 1024 * 1024).toLong(),
                3L * 1024L * 1024L * 1024L,
            ),
        )
    }

    // ------------------------------------------------------------------ the screen

    @Test
    fun `an unconfigured build offers nothing and names the missing fact`() {
        val plan = ModelFetchPlanner.plan(
            ModelFetchPhase.UNCONFIGURED,
            fault = ModelDescriptorFault.UNPINNED_REVISION,
        )
        assertNull(plan.primary)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.note!!.contains("pinned revision"))
        // Not an error: nothing is broken, there is simply nothing to fetch yet.
        assertEquals(ModelFetchTone.NEUTRAL, plan.tone)
    }

    @Test
    fun `every phase that can act offers exactly one obvious button`() {
        val silent = setOf(ModelFetchPhase.UNCONFIGURED, ModelFetchPhase.VERIFYING)
        ModelFetchPhase.entries.filterNot { it in silent }.forEach { phase ->
            val plan = ModelFetchPlanner.plan(phase, sizeBytes = 3L * 1024L * 1024L * 1024L)
            assertNotNull("no primary action for $phase", plan.primary)
        }
    }

    @Test
    fun `a gated model sends the player to the page and says retrying alone will not help`() {
        val plan = ModelFetchPlanner.plan(ModelFetchPhase.GATED)
        assertEquals(ModelFetchAction.OPEN_MODEL_PAGE, plan.primary)
        assertTrue(plan.note!!.contains("not a fault in NeoPal"))
        assertEquals(ModelFetchTone.WARN, plan.tone)
    }

    @Test
    fun `the terms are shown before anything is downloaded`() {
        val plan = ModelFetchPlanner.plan(ModelFetchPhase.NEEDS_TERMS)
        assertEquals(ModelFetchAction.ACCEPT_TERMS, plan.primary)
        assertTrue(plan.secondary.contains(ModelFetchAction.OPEN_MODEL_PAGE))
        assertTrue(plan.note!!.contains("publisher's own terms"))
    }

    @Test
    fun `the terms gate stops the offer and nothing else`() {
        assertEquals(
            ModelFetchPhase.NEEDS_TERMS,
            ModelFetchPlanner.gate(ModelFetchPhase.ABSENT, termsAccepted = false),
        )
        assertEquals(
            ModelFetchPhase.ABSENT,
            ModelFetchPlanner.gate(ModelFetchPhase.ABSENT, termsAccepted = true),
        )
        // Once something is happening, a licence dialog is a question whose answer changes
        // nothing. Every other phase passes through untouched.
        ModelFetchPhase.entries.filterNot { it == ModelFetchPhase.ABSENT }.forEach { phase ->
            assertEquals(phase, ModelFetchPlanner.gate(phase, termsAccepted = false))
        }
    }

    @Test
    fun `a stored model is deletable in one press and says what its check was worth`() {
        val plan = ModelFetchPlanner.plan(
            ModelFetchPhase.STORED,
            sizeBytes = 3L * 1024L * 1024L * 1024L,
            onDiskBytes = 3L * 1024L * 1024L * 1024L,
            verification = ModelVerification.SIZE_ONLY,
        )
        assertEquals(ModelFetchAction.DELETE, plan.primary)
        assertEquals(ModelFetchTone.GOOD, plan.tone)
        assertTrue(plan.note!!.contains("3.0 GB"))
        assertTrue(plan.note.contains("length alone"))
    }

    @Test
    fun `the metered question quotes the size`() {
        val plan = ModelFetchPlanner.plan(
            ModelFetchPhase.NEEDS_METERED_CONSENT,
            sizeBytes = FetchableModels.SMALL.sizeBytes,
        )
        assertEquals(ModelFetchAction.DOWNLOAD_ON_METERED, plan.primary)
        assertEquals(ModelFetchTone.WARN, plan.tone)
        assertTrue(plan.note!!.contains("557.3 MB"))
    }

    @Test
    fun `a failure keeps what arrived and says it will be reused`() {
        val plan = ModelFetchPlanner.plan(
            ModelFetchPhase.FAILED,
            sizeBytes = 3L * 1024L * 1024L * 1024L,
            onDiskBytes = 1L * 1024L * 1024L * 1024L,
        )
        assertEquals(ModelFetchAction.RETRY, plan.primary)
        assertTrue(plan.secondary.contains(ModelFetchAction.DELETE))
        assertTrue(plan.showsProgress)
        assertTrue(plan.note!!.contains("1.0 GB"))

        // With nothing on disk there is nothing to delete and nothing to promise.
        val bare = ModelFetchPlanner.plan(ModelFetchPhase.FAILED, sizeBytes = 3L * 1024L * 1024L * 1024L)
        assertTrue(bare.secondary.isEmpty())
        assertNull(bare.note)
    }

    @Test
    fun `downloading says the player may leave`() {
        val plan = ModelFetchPlanner.plan(
            ModelFetchPhase.DOWNLOADING,
            sizeBytes = 3L * 1024L * 1024L * 1024L,
            doneBytes = 1L * 1024L * 1024L * 1024L,
        )
        assertTrue(plan.busy)
        assertTrue(plan.showsProgress)
        assertEquals(ModelFetchAction.STOP, plan.primary)
    }
}
