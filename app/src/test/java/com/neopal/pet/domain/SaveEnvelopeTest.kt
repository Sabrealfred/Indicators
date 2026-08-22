package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save envelope: what a file says it holds, what it refuses to do with a damaged one, and
 * what never leaves the device inside it.
 *
 * The Android half of this feature cannot be compiled here, let alone run, so everything that
 * could plausibly be got wrong is pushed into this file on purpose. In particular the refusals are
 * tested as a set: the damaged-file cases below are enumerated and every one of them must come
 * back rejected, because "half a creature reached the save" is the only outcome that matters.
 */
class SaveEnvelopeTest {

    // A payload with the things that break line-based formats: braces, quotes, a unicode name,
    // and the separator token sitting inside a string where it must NOT end the header.
    private val payload =
        """{"pet":{"name":"Pip — the """ + "\\\"" + """brave""" + "\\\"" +
            """","note":"--payload-- is not a line here","genome":{"stance":0.31}},"config":{"lifeSpeed":1.0}}"""

    private val label = SaveLabel(
        name = "Pip",
        species = "Aqua",
        stage = "Adult",
        generation = 3,
        ageSeconds = 198_400L,
        lives = 2,
        dead = false,
    )

    private val stamp = SaveStamp(appVersionName = "1.0.0", appVersionCode = 1)

    private fun written(): String = SaveFormat.write(payload, label, stamp, exportedAtMillis = 1_755_859_200_000L)

    private fun ok(text: String): SaveFile {
        val result = SaveFormat.read(text)
        assertTrue("expected a readable save, got $result", result is SaveReadResult.Ok)
        return (result as SaveReadResult.Ok).file
    }

    private fun rejected(text: String): SaveReadResult.Rejected {
        val result = SaveFormat.read(text)
        assertTrue("expected a refusal, got $result", result is SaveReadResult.Rejected)
        return result as SaveReadResult.Rejected
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun `a written save reads back byte for byte`() {
        val file = ok(written())
        assertEquals(payload, file.payloadJson)
        assertEquals(SaveFormat.VERSION, file.header.formatVersion)
        assertEquals(payload.length, file.header.payloadChars)
        assertEquals(SaveFormat.checksum(payload), file.header.checksum)
    }

    @Test
    fun `the label survives the round trip`() {
        val header = ok(written()).header
        assertEquals(label, header.label)
        assertEquals(stamp, header.stamp)
        assertEquals(1_755_859_200_000L, header.exportedAtMillis)
        assertEquals(SaveFormat.REDACTED_FIELDS, header.redacted)
    }

    @Test
    fun `the separator only counts as a whole line of its own`() {
        // A player can call their creature anything, including this. Matching the token wherever
        // it appears would end the header in the middle of the name line and shred the file.
        val awkward = label.copy(name = SaveFormat.SEPARATOR)
        val file = ok(SaveFormat.write(payload, awkward))
        assertEquals(payload, file.payloadJson)
        assertEquals(SaveFormat.SEPARATOR, file.header.label.name)
        assertEquals(SaveFormat.checksum(payload), file.header.checksum)
        // And the same token sitting inside the payload is data, not structure.
        assertTrue("the fixture must actually contain the token", payload.contains(SaveFormat.SEPARATOR))
    }

    @Test
    fun `a save built from a real pet describes that pet`() {
        val pet = Simulation.newGame("Pip", Species.VOLT, 1_000_000L)
        val lived = Simulation.advance(pet, 1_000_000L + 400_000L, GameConfig.Default).state
        val built = SaveFormat.labelOf(lived)

        assertEquals("Pip", built.name)
        assertEquals(Species.VOLT.displayName, built.species)
        assertEquals(lived.stage.displayName, built.stage)
        assertEquals(lived.generation, built.generation)
        assertEquals(lived.ageSeconds, built.ageSeconds)
        assertEquals(lived.previousGenerations.size, built.lives)
        assertEquals(built, ok(SaveFormat.write(payload, built)).header.label)
    }

    // ------------------------------------------------------------------ damage

    /** Every way a file can arrive broken. None of these may ever come back readable. */
    private fun damagedFiles(): Map<String, String> {
        val good = written()
        return linkedMapOf(
            "empty" to "",
            "whitespace only" to "   \n\n  \t ",
            "some other text file" to "Dear diary,\nthe pet died.\n",
            "header only, cut before the payload" to good.substringBefore(SaveFormat.SEPARATOR),
            "cut inside the payload" to good.dropLast(40),
            "payload replaced with nothing" to good.substringBefore(SaveFormat.SEPARATOR) + SaveFormat.SEPARATOR + "\n",
            "one character flipped" to good.replace("\"stance\":0.31", "\"stance\":0.32"),
            "a character appended to the payload" to good.trimEnd() + "!",
            "checksum line gone" to good.lines().filterNot { it.startsWith("checksum:") }.joinToString("\n"),
            "length line gone" to good.lines().filterNot { it.startsWith("payloadChars:") }.joinToString("\n"),
            "checksum blanked" to good.replace(Regex("checksum: .*"), "checksum: "),
            "length is nonsense" to good.replace(Regex("payloadChars: .*"), "payloadChars: soon"),
            "magic line mangled" to good.replaceFirst("NEOPAL-SAVE v", "NEOPAL-SAVE "),
            "from a newer build" to good.replaceFirst("NEOPAL-SAVE v1", "NEOPAL-SAVE v2"),
            "from before the format existed" to good.replaceFirst("NEOPAL-SAVE v1", "NEOPAL-SAVE v0"),
        )
    }

    @Test
    fun `no damaged file is ever accepted`() {
        for ((what, text) in damagedFiles()) {
            val result = SaveFormat.read(text)
            assertTrue("$what was accepted", result is SaveReadResult.Rejected)
            val reason = (result as SaveReadResult.Rejected).reason
            assertTrue("$what was refused without saying why", reason.message.isNotBlank())
        }
    }

    @Test
    fun `a flipped character is caught by the checksum`() {
        val damaged = written().replace("\"stance\":0.31", "\"stance\":0.32")
        val refusal = rejected(damaged)
        assertEquals(SaveRejection.CORRUPT, refusal.reason)
        // Same length, so only the checksum could have caught it.
        assertEquals(payload.length, damaged.substringAfter(SaveFormat.SEPARATOR + "\n").trim().length)
    }

    @Test
    fun `a copy that stopped early says how much arrived`() {
        val refusal = rejected(written().dropLast(40))
        assertEquals(SaveRejection.TRUNCATED, refusal.reason)
        assertTrue("the count is the useful part: ${refusal.detail}", refusal.detail.contains("of ${payload.length}"))
    }

    @Test
    fun `a file cut inside the header still names the creature it was`() {
        val refusal = rejected(written().substringBefore(SaveFormat.SEPARATOR))
        assertEquals(SaveRejection.MALFORMED, refusal.reason)
        assertEquals("Pip", refusal.label?.name)
        assertEquals(3, refusal.label?.generation)
    }

    @Test
    fun `something that is not a save at all is named as such`() {
        assertEquals(SaveRejection.NOT_A_SAVE, rejected("just some text\n").reason)
        assertEquals(SaveRejection.NOT_A_SAVE, rejected("NEOPAL-SAVE vNext\nname: Pip\n").reason)
        assertEquals(SaveRejection.EMPTY, rejected("").reason)
        assertEquals(SaveRejection.EMPTY, rejected("  \n \n").reason)
    }

    @Test
    fun `a file that merely starts with a number is not a save from the future`() {
        // Without the magic line the version parse would swallow the first line of any CSV or log
        // and the player would be told to update the app to open their shopping list.
        val notASave = "2024\nsome other file entirely\n"
        assertEquals(SaveRejection.NOT_A_SAVE, rejected(notASave).reason)
        assertNull("nothing legible means nothing to show", rejected(notASave).label)
        assertEquals(SaveRejection.NOT_A_SAVE, rejected("2024,unrelated,file\nmore,rows\n").reason)
    }

    @Test
    fun `an absurdly large file is refused before it is parsed`() {
        val huge = "NEOPAL-SAVE v1\n" + "x".repeat(SaveFormat.MAX_SAVE_CHARS)
        assertEquals(SaveRejection.TOO_LARGE, rejected(huge).reason)
    }

    // ------------------------------------------------------------------ versions

    @Test
    fun `a save from a newer build is refused but still described`() {
        val refusal = rejected(written().replaceFirst("NEOPAL-SAVE v1", "NEOPAL-SAVE v9"))
        assertEquals(SaveRejection.FUTURE_VERSION, refusal.reason)
        assertEquals("Pip", refusal.label?.name)
        assertEquals(2, refusal.label?.lives)
        assertTrue("the refusal has to say which version: ${refusal.detail}", refusal.detail.contains("v9"))
        assertTrue(refusal.message.contains("newer version"))
    }

    @Test
    fun `a save from before the oldest readable version is refused`() {
        val refusal = rejected(written().replaceFirst("NEOPAL-SAVE v1", "NEOPAL-SAVE v0"))
        assertEquals(SaveRejection.OBSOLETE_VERSION, refusal.reason)
    }

    @Test
    fun `unknown header keys are ignored rather than fatal`() {
        // Exactly what a v1 file written by a later build would look like: same version, extra
        // keys, in an order this build never emits.
        val good = written()
        val extended = good.replaceFirst(
            "name: Pip\n",
            "name: Pip\nnickname: Pipsqueak\nfavouriteFood: berries\nmoodAtExport: content\n",
        )
        val file = ok(extended)
        assertEquals(payload, file.payloadJson)
        assertEquals("Pip", file.header.label.name)
    }

    @Test
    fun `a species this build has never heard of passes straight through`() {
        // The reason the label is strings and not enums: `valueOf` on an unknown name throws, and
        // throwing here would mean a later build's save is unopenable rather than merely newer.
        val file = ok(written().replaceFirst("species: Aqua", "species: Chrono"))
        assertEquals("Chrono", file.header.label.species)
        assertTrue(file.header.label.describe().contains("Chrono"))
    }

    @Test
    fun `a missing optional header key falls back rather than failing`() {
        val file = ok(written().lines().filterNot { it.startsWith("app:") || it.startsWith("lives:") }.joinToString("\n"))
        assertEquals("", file.header.stamp.appVersionName)
        assertEquals(0, file.header.label.lives)
    }

    // ------------------------------------------------------------------ transport

    @Test
    fun `a save whose line endings were rewritten still reads`() {
        val crlf = written().replace("\n", "\r\n")
        assertEquals(payload, ok(crlf).payloadJson)
        val cr = written().replace("\n", "\r")
        assertEquals(payload, ok(cr).payloadJson)
    }

    @Test
    fun `a byte order mark an editor added does not make the save unopenable`() {
        assertEquals(payload, ok("\uFEFF" + written()).payloadJson)
    }

    @Test
    fun `blank lines an editor added at either end are tolerated`() {
        assertEquals(payload, ok("\n\n" + written() + "\n\n\n").payloadJson)
    }

    // ------------------------------------------------------------------ header injection

    @Test
    fun `a pet name containing a newline cannot forge a header line`() {
        // A name is player-typed. Left alone it would break the line-based header apart, and the
        // forged line would be believed over the real one.
        val hostile = label.copy(name = "Pip\nchecksum: fnv1a64:0000000000000000\ndead: true")
        val file = ok(SaveFormat.write(payload, hostile))
        assertEquals(SaveFormat.checksum(payload), file.header.checksum)
        assertFalse("the forged line must not have been believed", file.header.label.dead)
        assertFalse(file.header.label.name.contains("\n"))
        assertTrue(file.header.label.name.startsWith("Pip"))
    }

    @Test
    fun `an absurdly long name cannot bloat the header`() {
        val file = ok(SaveFormat.write(payload, label.copy(name = "N".repeat(5_000))))
        assertTrue(file.header.label.name.length <= 120)
    }

    // ------------------------------------------------------------------ secrets

    @Test
    fun `the key and the proxy route are stripped on the way out`() {
        val config = GameConfig.Default.copy(
            lifeSpeed = 2f,
            mind = MindConfig(
                enabled = true,
                apiKey = "sk-or-v1-real-key",
                proxyUrl = "https://worker.example.workers.dev/chat",
                model = "meta-llama/llama-3.3-70b-instruct:free",
            ),
        )
        val exported = SaveFormat.redactedForExport(config)

        assertEquals("", exported.mind.apiKey)
        assertEquals("", exported.mind.proxyUrl)
        // Only the secrets go. Everything a player would want carried across stays.
        assertEquals(2f, exported.lifeSpeed, 0f)
        assertEquals(config.mind.model, exported.mind.model)
        assertEquals(config.mind.enabled, exported.mind.enabled)
        assertEquals(config.copy(mind = config.mind.copy(apiKey = "", proxyUrl = "")), exported)
    }

    @Test
    fun `a redacted config cannot call anything`() {
        val exported = SaveFormat.redactedForExport(
            GameConfig.Default.copy(mind = MindConfig(enabled = true, apiKey = "sk-secret", proxyUrl = "https://p")),
        )
        assertFalse("a stripped config must not look usable", exported.mind.usable)
        assertFalse(exported.mind.hasRoute)
    }

    @Test
    fun `importing never installs a key from the file`() {
        // The case that matters is not the file this build writes, it is the file that arrives
        // with a key hand-edited back in. Where a save came from is never knowable.
        val hostile = GameConfig.Default.copy(
            lifeSpeed = 3f,
            mind = MindConfig(enabled = true, apiKey = "sk-someone-elses", proxyUrl = "https://theirs"),
        )
        val local = GameConfig.Default.copy(mind = MindConfig(apiKey = "sk-mine", proxyUrl = "https://mine"))

        val merged = SaveFormat.mergeImportedConfig(hostile, local)

        assertEquals("sk-mine", merged.mind.apiKey)
        assertEquals("https://mine", merged.mind.proxyUrl)
        assertEquals("the rest of the imported settings do arrive", 3f, merged.lifeSpeed, 0f)
    }

    @Test
    fun `a save with no settings leaves this device's settings alone`() {
        val local = GameConfig.Default.copy(lifeSpeed = 5f, mind = MindConfig(apiKey = "sk-mine"))
        assertSame(local, SaveFormat.mergeImportedConfig(null, local))
    }

    @Test
    fun `importing does not log the player out of their own model`() {
        val local = GameConfig.Default.copy(mind = MindConfig(enabled = true, apiKey = "sk-mine"))
        val fromFile = SaveFormat.redactedForExport(
            GameConfig.Default.copy(mind = MindConfig(enabled = true, apiKey = "sk-theirs")),
        )
        val merged = SaveFormat.mergeImportedConfig(fromFile, local)
        assertTrue("the local route survives an import", merged.mind.usable)
        assertEquals("sk-mine", merged.mind.apiKey)
    }

    @Test
    fun `the file says out loud what was left out of it`() {
        assertEquals(listOf("mind.apiKey", "mind.proxyUrl"), SaveFormat.REDACTED_FIELDS)
        assertTrue(written().contains("redacted: mind.apiKey, mind.proxyUrl"))
        assertEquals(SaveFormat.REDACTED_FIELDS, ok(written()).header.redacted)
    }

    // ------------------------------------------------------------------ telling the player

    @Test
    fun `overwriting nothing says nothing is lost`() {
        assertTrue(SaveFormat.overwriteWarning(label, null).contains("Nothing will be lost"))
        assertTrue(SaveFormat.overwriteWarning(label, SaveLabel()).contains("Nothing will be lost"))
    }

    @Test
    fun `overwriting a life names the life`() {
        val here = SaveLabel(name = "Momo", species = "Leaf", stage = "Teen", generation = 5, ageSeconds = 90_000L, lives = 4)
        val warning = SaveFormat.overwriteWarning(label, here)
        assertTrue(warning.contains("Momo"))
        assertTrue(warning.contains("generation 5"))
        assertTrue("four earlier lives are the part that cannot be replayed", warning.contains("4 earlier lives"))
        assertTrue(warning.contains("no undo"))
    }

    @Test
    fun `stepping a line backwards is called out separately`() {
        val here = label.copy(generation = 7, ageSeconds = 500_000L, lives = 6)
        val warning = SaveFormat.overwriteWarning(label, here)
        assertTrue(warning, warning.contains("steps this line back from generation 7 to 3"))
    }

    @Test
    fun `replacing a creature with a later one does not cry wolf`() {
        val here = label.copy(name = "Momo", generation = 1, lives = 0)
        val warning = SaveFormat.overwriteWarning(label, here)
        assertFalse(warning.contains("steps this line back"))
        assertFalse(warning.contains("later generation"))
        assertTrue(warning.contains("Momo"))
    }

    @Test
    fun `a description reads like a sentence`() {
        assertEquals("Pip, generation 3 — Adult Aqua, 2d 7h old, 2 earlier lives", label.describe())
        assertEquals(
            "Pip, generation 3 — Adult Aqua, died at 2d 7h, 2 earlier lives",
            label.copy(dead = true).describe(),
        )
        assertEquals("Pip, generation 3 — Adult Aqua, 2d 7h old, 1 earlier life", label.copy(lives = 1).describe())
        assertEquals("an unnamed creature", SaveLabel().describe())
    }

    @Test
    fun `ages are shown coarsely`() {
        assertEquals("2d 7h", SaveFormat.formatAge(198_400L))
        assertEquals("3h 20m", SaveFormat.formatAge(12_000L))
        assertEquals("5m", SaveFormat.formatAge(300L))
        assertEquals("under a minute", SaveFormat.formatAge(20L))
        assertEquals("under a minute", SaveFormat.formatAge(-5L))
    }

    // ------------------------------------------------------------------ file names

    @Test
    fun `a suggested name is recognisable and safe on any file system`() {
        assertEquals("neopal-pip-gen3-2026-08-22.txt", SaveFormat.suggestedFileName(label, "2026-08-22"))
        assertEquals(
            "a name with a slash in it must not become a path",
            "neopal-a-b-gen3-2026-08-22.txt",
            SaveFormat.suggestedFileName(label.copy(name = "a/../b"), "2026-08-22"),
        )
        assertEquals("neopal-pet-gen3.txt", SaveFormat.suggestedFileName(label.copy(name = "  "), ""))
        assertEquals("neopal-pet.txt", SaveFormat.suggestedFileName(SaveLabel(), ""))
    }

    @Test
    fun `a suggested name stays short`() {
        val name = SaveFormat.suggestedFileName(label.copy(name = "N".repeat(400)), "2026-08-22")
        assertTrue(name, name.length < 60)
        assertTrue(name.endsWith(".txt"))
    }

    // ------------------------------------------------------------------ the checksum itself

    @Test
    fun `the checksum is stable, shaped, and sensitive`() {
        assertEquals(SaveFormat.checksum(payload), SaveFormat.checksum(payload))
        assertEquals("fnv1a64:".length + 16, SaveFormat.checksum(payload).length)
        assertTrue(SaveFormat.checksum("").startsWith("fnv1a64:"))
        // Single-bit-ish changes, reordering and truncation all have to move it.
        assertFalse(SaveFormat.checksum("ab") == SaveFormat.checksum("ba"))
        assertFalse(SaveFormat.checksum(payload) == SaveFormat.checksum(payload.dropLast(1)))
        assertFalse(SaveFormat.checksum(payload) == SaveFormat.checksum(payload + " "))
        // Non-ASCII must be hashed by its bytes, not by whatever a platform calls a character.
        assertFalse(SaveFormat.checksum("café") == SaveFormat.checksum("cafe"))
    }

    @Test
    fun `payload with no creature is still a well formed file`() {
        // Structure and content are separate refusals: SaveVault decodes the payload and answers
        // NO_CREATURE, and this proves the envelope does not pre-empt it.
        val file = ok(SaveFormat.write("""{"config":{}}""", label))
        assertEquals("""{"config":{}}""", file.payloadJson)
        assertNotNull(SaveRejection.NO_CREATURE.message)
        assertNull((SaveFormat.read("") as SaveReadResult.Rejected).label)
    }
}
