package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/**
 * The portable form of a life: a text envelope wrapped around the save's own JSON.
 *
 * Everything here is pure Kotlin on purpose. The Android half of this feature ([`SaveVault`]) can
 * only ever be checked by eye, so as much of the mechanism as possible is kept where a test can
 * reach it: what a file looks like, how a damaged one is refused, what a player is told they are
 * about to lose, and which fields are never allowed to leave the device.
 *
 * ### Why a text header instead of one JSON object
 *
 * The checksum has to cover *exactly the characters that were written*. If the payload were a
 * nested JSON object, verifying it would mean re-encoding the decoded object, and a later build
 * that added one field would re-encode to different bytes — every older file would then read as
 * corrupt, which is precisely the failure this whole feature exists to prevent. Keeping the save
 * as an opaque string means the integrity check is independent of the schema inside it, forever.
 *
 * It also means the file opens in any text editor and says whose creature it holds, which is worth
 * something when the player is staring at a folder wondering which copy is the good one.
 *
 * ### The format, v1
 *
 * ```
 * NEOPAL-SAVE v1
 * name: Pip
 * species: Aqua
 * stage: Adult
 * generation: 3
 * ageSeconds: 198400
 * lives: 2
 * dead: false
 * app: 1.0.0
 * appCode: 1
 * exportedAtMillis: 1755859200000
 * redacted: mind.apiKey, mind.proxyUrl
 * payloadChars: 41822
 * checksum: fnv1a64:0f3a19c4d5e6b708
 * --payload--
 * {"pet":{...},"config":{...}}
 * ```
 *
 * ### The compatibility promise
 *
 * Three rules, which every future version of this format must keep, because a save written today
 * has to be readable by a build nobody has written yet:
 *
 *  1. The first line is always `NEOPAL-SAVE v<integer>`, and the header is always `key: value`
 *     lines terminated by [SaveFormat.SEPARATOR]. That is what lets *this* build describe a file
 *     from a *later* one instead of shrugging at it.
 *  2. **Unknown header keys are ignored**, and missing optional keys fall back to defaults. Adding
 *     a key is therefore never a breaking change.
 *  3. Inside the payload, unknown JSON fields are ignored and missing ones take their data-class
 *     default — the same rule the live save has always run on.
 *
 * A file whose version is *newer* than [SaveFormat.VERSION] is refused rather than guessed at, but
 * its header is still parsed so the refusal can name the creature and the version it needs.
 */
@Serializable
data class SavePayload(
    /**
     * Null is a rejection, not a default. `PetState` has a default for every field, so a payload
     * that lost its `pet` key to truncation would otherwise decode silently into a blank egg and
     * overwrite a real life with it.
     */
    val pet: PetState? = null,
    /**
     * Settings travel with the pet, minus the secrets — see [SaveFormat.redactedForExport]. Null
     * means the importing device keeps its own settings, which is the right answer for a save
     * written before settings were included.
     */
    val config: GameConfig? = null,
)

/**
 * What a save file says it holds, in plain strings.
 *
 * Deliberately not enums. A label exists to be shown to a player who is deciding whether to
 * overwrite a creature, and it has to survive a file written by a build that knows a species this
 * one does not. `Species.valueOf` on an unknown name throws; a string never does.
 */
data class SaveLabel(
    val name: String = "",
    val species: String = "",
    val stage: String = "",
    val generation: Int = 0,
    val ageSeconds: Long = 0L,
    /** Earlier runs the save still remembers. The part of a lineage that cannot be replayed. */
    val lives: Int = 0,
    val dead: Boolean = false,
) {
    /** True when the header carried nothing worth showing. */
    val isBlank: Boolean get() = name.isBlank() && generation <= 0 && ageSeconds <= 0L

    /** The creature's name, or something to call it when the file did not say. */
    val displayName: String get() = name.ifBlank { "an unnamed creature" }

    /** One line a confirmation dialog can show without any further formatting. */
    fun describe(): String {
        val body = buildString {
            append(displayName)
            if (generation > 0) append(", generation ").append(generation)
            val shape = listOf(stage, species).filter { it.isNotBlank() }.joinToString(" ")
            if (shape.isNotEmpty()) append(" — ").append(shape)
            if (ageSeconds > 0L) {
                append(if (dead) ", died at " else ", ").append(SaveFormat.formatAge(ageSeconds))
                if (!dead) append(" old")
            } else if (dead) {
                append(", dead")
            }
            when {
                lives == 1 -> append(", 1 earlier life")
                lives > 1 -> append(", ").append(lives).append(" earlier lives")
            }
        }
        return body
    }
}

/** Which build wrote the file. Provenance only; nothing is gated on it. */
data class SaveStamp(
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
)

/** A parsed header. [SaveFile] pairs it with the payload it vouches for. */
data class SaveHeader(
    val formatVersion: Int,
    val label: SaveLabel,
    val stamp: SaveStamp,
    val exportedAtMillis: Long,
    /** Fields the exporting build deliberately left out. Informational; see [SaveFormat.mergeImportedConfig]. */
    val redacted: List<String>,
    val payloadChars: Int,
    val checksum: String,
)

/** A file that passed every structural and integrity check. The payload is still undecoded JSON. */
data class SaveFile(
    val header: SaveHeader,
    val payloadJson: String,
)

/**
 * Why a file was refused.
 *
 * Every one of these is a refusal, never a partial import: the caller gets a [SaveFile] or it gets
 * nothing, so there is no path on which half a creature reaches the save.
 */
enum class SaveRejection(val message: String) {
    EMPTY("That file is empty."),
    TOO_LARGE("That file is far too large to be a save."),
    NOT_A_SAVE("That is not a NeoPal save file."),
    OBSOLETE_VERSION("That save is from a version too old for this build to read."),
    FUTURE_VERSION("That save was written by a newer version of NeoPal. Update the app and try again."),
    MALFORMED("That save file is damaged and cannot be read."),
    TRUNCATED("That save file is cut short — the copy did not finish."),
    CORRUPT("That save file is damaged: its contents do not match its own checksum."),
    NO_CREATURE("That file is a NeoPal save, but it holds no creature."),
}

/** The outcome of reading a file. */
sealed interface SaveReadResult {
    data class Ok(val file: SaveFile) : SaveReadResult

    /**
     * @param label whatever could be recovered from the header, so a refusal can still say what
     *   the file appears to hold. Null when nothing was legible.
     * @param detail the specific thing that was wrong, for the log and for the second line of a
     *   dialog. Never the whole explanation on its own.
     */
    data class Rejected(
        val reason: SaveRejection,
        val detail: String = "",
        val label: SaveLabel? = null,
    ) : SaveReadResult {
        /** Reason and specifics as one piece of prose. */
        val message: String get() = if (detail.isBlank()) reason.message else "${reason.message} ($detail)"
    }
}

/** Reading, writing, checking and describing save files. */
object SaveFormat {

    /** Current envelope version. Bumped only when the *header* changes shape, never for payload fields. */
    const val VERSION: Int = 1

    /** Oldest envelope version this build still reads. */
    const val OLDEST_READABLE: Int = 1

    const val MAGIC: String = "NEOPAL-SAVE v"

    /** The line that ends the header. Everything after it is payload, verbatim. */
    const val SEPARATOR: String = "--payload--"

    /**
     * Ceiling on a file this build will even try to parse.
     *
     * A save with a full album and a long diary is a few hundred kilobytes. This is the guard
     * against someone picking a video in the file picker and being handed an out-of-memory crash
     * instead of "that is not a save".
     */
    const val MAX_SAVE_CHARS: Int = 4_000_000

    /** Byte ceiling for the reader that pulls the file off disk, before it is ever a String. */
    const val MAX_SAVE_BYTES: Long = 12_000_000L

    /**
     * The settings that are stripped on the way out and refused on the way in.
     *
     * `apiKey` is the player's own credential and would bill them for a stranger's play. `proxyUrl`
     * looks harmless and is not: the whole point of the hosted route is that the endpoint holds a
     * key of its own, so anyone with the URL can spend someone else's quota. A save is a thing
     * people mail to each other; neither of these may ever be in one.
     */
    val REDACTED_FIELDS: List<String> = listOf("mind.apiKey", "mind.proxyUrl")

    /** Text MIME type. The file is plain text so that every document provider handles it correctly. */
    const val MIME_TYPE: String = "text/plain"

    // ---------------------------------------------------------------- writing

    /** Describes [state] for a header or a confirmation dialog. */
    fun labelOf(state: PetState): SaveLabel = SaveLabel(
        name = state.name,
        species = state.species.displayName,
        stage = state.stage.displayName,
        generation = state.generation,
        ageSeconds = state.ageSeconds,
        lives = state.previousGenerations.size,
        dead = state.isDead,
    )

    /**
     * Assembles a complete save file.
     *
     * [payloadJson] is written and checksummed exactly as given, after trimming the surrounding
     * whitespace that a mail client or a text editor would add anyway.
     */
    fun write(
        payloadJson: String,
        label: SaveLabel,
        stamp: SaveStamp = SaveStamp(),
        exportedAtMillis: Long = 0L,
    ): String {
        val payload = payloadJson.trim()
        return buildString {
            append(MAGIC).append(VERSION).append('\n')
            line("name", label.name)
            line("species", label.species)
            line("stage", label.stage)
            line("generation", label.generation.toString())
            line("ageSeconds", label.ageSeconds.toString())
            line("lives", label.lives.toString())
            line("dead", label.dead.toString())
            line("app", stamp.appVersionName)
            line("appCode", stamp.appVersionCode.toString())
            line("exportedAtMillis", exportedAtMillis.toString())
            line("redacted", REDACTED_FIELDS.joinToString(", "))
            line("payloadChars", payload.length.toString())
            line("checksum", checksum(payload))
            append(SEPARATOR).append('\n')
            append(payload)
            append('\n')
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Parses and verifies [text], refusing anything it cannot vouch for whole.
     *
     * Line endings are normalised first, so a save that travelled through a channel that rewrote
     * them still verifies: the checksum is defined over the payload *after* that normalisation,
     * not over whatever the transport happened to deliver.
     */
    fun read(text: String): SaveReadResult {
        if (text.isBlank()) return SaveReadResult.Rejected(SaveRejection.EMPTY)
        if (text.length > MAX_SAVE_CHARS) {
            return SaveReadResult.Rejected(SaveRejection.TOO_LARGE, "${text.length} characters")
        }

        // A byte-order mark is not whitespace and will not trim away. Editors on one platform add
        // one to a text file that never had one, and the file is then unopenable on the phone that
        // wrote it for a reason no player could ever guess at.
        val doc = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

        val firstLine = doc.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return SaveReadResult.Rejected(SaveRejection.EMPTY)
        if (!firstLine.startsWith(MAGIC)) {
            return SaveReadResult.Rejected(SaveRejection.NOT_A_SAVE, firstLine.take(40))
        }
        val version = firstLine.removePrefix(MAGIC).trim().toIntOrNull()
            ?: return SaveReadResult.Rejected(SaveRejection.NOT_A_SAVE, "unreadable version")

        // The header is parsed before the version is judged, so a refusal can still name the
        // creature in the file. That is the difference between "cannot read this" and "cannot
        // read Pip, generation 4 — update the app".
        val sepAt = separatorIndex(doc)
        val fields = headerFields(if (sepAt >= 0) doc.substring(0, sepAt) else doc)
        val label = labelFrom(fields)

        if (version > VERSION) {
            return SaveReadResult.Rejected(SaveRejection.FUTURE_VERSION, "file is v$version, this build reads v$VERSION", label)
        }
        if (version < OLDEST_READABLE) {
            return SaveReadResult.Rejected(SaveRejection.OBSOLETE_VERSION, "file is v$version, this build reads v$OLDEST_READABLE and later", label)
        }
        if (sepAt < 0) {
            return SaveReadResult.Rejected(SaveRejection.MALFORMED, "the file ends before the save data begins", label)
        }

        val declaredChars = fields["payloadChars"]?.toIntOrNull()
            ?: return SaveReadResult.Rejected(SaveRejection.MALFORMED, "no payload length in the header", label)
        val declaredSum = fields["checksum"]?.trim().orEmpty()
        if (declaredSum.isBlank()) {
            return SaveReadResult.Rejected(SaveRejection.MALFORMED, "no checksum in the header", label)
        }

        val payloadStart = (sepAt + SEPARATOR.length + 1).coerceAtMost(doc.length)
        val payload = doc.substring(payloadStart).trim()

        // Length is checked before the checksum because it is the failure that actually happens —
        // a copy that stopped early — and it is the only one that can say how much is missing.
        if (payload.length != declaredChars) {
            val detail = if (payload.length < declaredChars) {
                "${payload.length} of $declaredChars characters arrived"
            } else {
                "${payload.length} characters where the header promised $declaredChars"
            }
            return SaveReadResult.Rejected(SaveRejection.TRUNCATED, detail, label)
        }
        if (!checksum(payload).equals(declaredSum, ignoreCase = true)) {
            return SaveReadResult.Rejected(SaveRejection.CORRUPT, "checksum ${checksum(payload)}, header says $declaredSum", label)
        }

        val header = SaveHeader(
            formatVersion = version,
            label = label,
            stamp = SaveStamp(
                appVersionName = fields["app"].orEmpty(),
                appVersionCode = fields["appCode"]?.toIntOrNull() ?: 0,
            ),
            exportedAtMillis = fields["exportedAtMillis"]?.toLongOrNull() ?: 0L,
            redacted = fields["redacted"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            payloadChars = declaredChars,
            checksum = declaredSum,
        )
        return SaveReadResult.Ok(SaveFile(header, payload))
    }

    // ---------------------------------------------------------------- secrets

    /** Strips the fields in [REDACTED_FIELDS] from a config on its way into a file. */
    fun redactedForExport(config: GameConfig): GameConfig =
        config.copy(mind = config.mind.copy(apiKey = "", proxyUrl = ""))

    /**
     * Folds an imported config into the one already on this device, keeping local credentials.
     *
     * This is where "the key never travels" is actually enforced. [redactedForExport] keeps it out
     * of files this build writes; this keeps it out of the save even when the file was hand-edited
     * to put one back, which is the case that matters — a file arrives from somewhere, and where a
     * file came from is never knowable.
     *
     * It is also the reason importing does not log you out of your own model: the local route is
     * preserved rather than replaced with the blank one in the file.
     */
    fun mergeImportedConfig(imported: GameConfig?, local: GameConfig): GameConfig {
        if (imported == null) return local
        return imported.copy(
            mind = imported.mind.copy(
                apiKey = local.mind.apiKey,
                proxyUrl = local.mind.proxyUrl,
            ),
        )
    }

    // ---------------------------------------------------------------- telling the player

    /**
     * What a player stands to lose by importing [incoming] over [current].
     *
     * Written as loss first. A dialog that leads with what is arriving reads as good news, and the
     * only reason this dialog exists is that something is about to be destroyed.
     */
    fun overwriteWarning(incoming: SaveLabel, current: SaveLabel?): String {
        if (current == null || current.isBlank) {
            return "Nothing will be lost: there is no creature on this device yet."
        }
        val base = "This replaces ${current.describe()}. There is no undo."
        val extra = when {
            current.name.isNotBlank() && current.name == incoming.name && incoming.generation < current.generation ->
                " It also steps this line back from generation ${current.generation} to ${incoming.generation}."
            incoming.generation < current.generation ->
                " The creature here has reached a later generation than the one in the file."
            else -> ""
        }
        return base + extra
    }

    /**
     * A file name a player will recognise in a folder six months from now.
     *
     * [dateStamp] is passed in rather than read from a clock, because a clock in the domain is a
     * test that can only be written once.
     */
    fun suggestedFileName(label: SaveLabel, dateStamp: String): String {
        val slug = slugify(label.name).ifEmpty { "pet" }
        val date = slugify(dateStamp)
        val generation = if (label.generation > 0) "-gen${label.generation}" else ""
        val when0 = if (date.isEmpty()) "" else "-$date"
        return "neopal-$slug$generation$when0.txt"
    }

    /** Coarse, human age. Nobody reading a dialog wants seconds. */
    fun formatAge(seconds: Long): String {
        val total = seconds.coerceAtLeast(0L)
        val days = total / 86_400L
        val hours = (total % 86_400L) / 3600L
        val minutes = (total % 3600L) / 60L
        return when {
            days > 0L -> "${days}d ${hours}h"
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m"
            else -> "under a minute"
        }
    }

    // ---------------------------------------------------------------- integrity

    /**
     * 64-bit FNV-1a over the payload's UTF-8 bytes, as `fnv1a64:<16 hex digits>`.
     *
     * This detects damage, not forgery: anyone who can edit a save can recompute this, and that is
     * fine, because a player editing their own save file is not an attacker. What it does catch is
     * the copy that stopped halfway, the byte a flaky card flipped, and the paste that lost its
     * tail — which are the ways save files actually die.
     */
    fun checksum(payload: String): String {
        var hash = FNV_OFFSET
        for (byte in payload.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xFF).toLong()
            hash *= FNV_PRIME
        }
        return "fnv1a64:" + hash.toULong().toString(16).padStart(16, '0')
    }

    private const val FNV_OFFSET: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

    // ---------------------------------------------------------------- internals

    /** Index of the separator line, or -1. Matched only when it is a whole line of its own. */
    private fun separatorIndex(doc: String): Int {
        var from = 0
        while (from <= doc.length) {
            val at = doc.indexOf(SEPARATOR, from)
            if (at < 0) return -1
            val end = at + SEPARATOR.length
            val startsLine = at == 0 || doc[at - 1] == '\n'
            val endsLine = end == doc.length || doc[end] == '\n'
            if (startsLine && endsLine) return at
            from = at + 1
        }
        return -1
    }

    /**
     * `key: value` lines into a map. Unknown keys are kept, blank and malformed lines dropped.
     *
     * Later keys win over earlier ones, so a file with a duplicated line reads as its last value
     * rather than failing — the header is metadata, and refusing a save over a repeated line would
     * be refusing a life over a cosmetic problem.
     */
    private fun headerFields(headerText: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for (raw in headerText.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(MAGIC)) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return fields
    }

    private fun labelFrom(fields: Map<String, String>): SaveLabel = SaveLabel(
        name = fields["name"].orEmpty(),
        species = fields["species"].orEmpty(),
        stage = fields["stage"].orEmpty(),
        generation = fields["generation"]?.toIntOrNull() ?: 0,
        ageSeconds = fields["ageSeconds"]?.toLongOrNull() ?: 0L,
        lives = fields["lives"]?.toIntOrNull() ?: 0,
        dead = fields["dead"]?.equals("true", ignoreCase = true) ?: false,
    )

    /** One header line, with the value scrubbed of anything that would break the line-based format. */
    private fun StringBuilder.line(key: String, value: String) {
        append(key).append(": ").append(scrub(value)).append('\n')
    }

    /**
     * A header value can only ever be one line. A pet name is player-typed and could hold a
     * newline; letting one through would turn the rest of the name into a bogus header key.
     */
    private fun scrub(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_HEADER_VALUE)

    private const val MAX_HEADER_VALUE: Int = 120

    /** Lowercase, hyphenated, ASCII-safe. File names get shared across file systems that disagree. */
    private fun slugify(value: String): String {
        val out = StringBuilder()
        for (ch in value.lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> out.append(ch)
                out.isNotEmpty() && out.last() != '-' -> out.append('-')
            }
            if (out.length >= MAX_SLUG) break
        }
        return out.toString().trim('-')
    }

    private const val MAX_SLUG: Int = 24
}
