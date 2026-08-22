package com.neopal.pet.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.SaveFormat
import com.neopal.pet.domain.SaveHeader
import com.neopal.pet.domain.SaveLabel
import com.neopal.pet.domain.SavePayload
import com.neopal.pet.domain.SaveReadResult
import com.neopal.pet.domain.SaveRejection
import com.neopal.pet.domain.SaveStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a life is kept so that deleting the app does not end it.
 *
 * ### What Android actually promises, which is less than it sounds
 *
 * The save lives in DataStore, in this app's private directory. Nothing in there survives an
 * uninstall. There are exactly three ways out, and only one of them is a guarantee:
 *
 * **1. Auto Backup.** `android:allowBackup` plus the two rules files hand the whole `datastore/`
 * directory to the system, which restores it silently the next time the app is installed. When it
 * works it is perfect: the player reinstalls and their creature is simply there. But it is
 * *best-effort by design* — it needs a backup transport on the device, the player's backup setting
 * switched on, a suitable network, enough free quota inside the 25 MB per-app cap, and the system's
 * own opinion about when to run, which is roughly daily and only while charging and idle. A day of
 * play can be lost to it, and on a device with backup off, everything can. It is the common case,
 * not the promise, and nothing in this file pretends otherwise.
 *
 * **2. A mirror in shared storage.** [mirror] keeps a copy in `Documents/NeoPal`. That file outlives
 * the uninstall outright — it is not in this app's sandbox and the system does not sweep it. What
 * scoped storage will not allow is reading it back silently: after a reinstall this is a different
 * installation, the MediaStore rows it owned are gone, and the file is invisible to us until a
 * human points at it. So the mirror does not restore anything by itself. What it does is make the
 * guaranteed path cost one tap instead of an evening: the file is already written, already sitting
 * in a folder with the app's name on it, and [pickerStartUri] opens the picker looking at it.
 *
 * **3. Export and import.** The only mechanism that always works, because the player drives it. It
 * is also the only one that crosses a factory reset or a move to a different phone.
 *
 * All three carry the *same bytes* in the same format, so a mirror found in a folder is a save the
 * import path already knows how to read.
 *
 * ### What never leaves
 *
 * The player's API key and their proxy URL are stripped by [SaveFormat.redactedForExport] on the
 * way out and refused by [SaveFormat.mergeImportedConfig] on the way in. A save is a thing people
 * mail to each other, and neither of those may ever be inside one.
 */
class SaveVault(context: Context) {

    private val app = context.applicationContext

    /**
     * Lenient in exactly the way the live save is lenient: unknown fields are dropped and missing
     * ones take their data-class default, so a save written by a later build still opens here and
     * one written today still opens in a later build. The envelope around it carries the version
     * that decides whether opening it is allowed at all.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** Guarded by nothing: only ever touched from the single-threaded caller and used as a hint. */
    private var lastMirrorAtMillis = 0L

    /**
     * Whether the mirror in `Documents/NeoPal` is kept up to date.
     *
     * On by default. A backup a player has to remember to make is a backup that does not exist on
     * the day it is needed, and this one costs them nothing: no permission prompt, no dialog, one
     * small text file in a folder named after the app.
     */
    var mirrorEnabled: Boolean = true

    // ---------------------------------------------------------------- composing and parsing

    /** Builds the exact text of a save file. Same bytes for an export and for a mirror. */
    fun compose(state: PetState, config: GameConfig, nowMillis: Long): String {
        val payload = json.encodeToString(
            SavePayload.serializer(),
            SavePayload(pet = state, config = SaveFormat.redactedForExport(config)),
        )
        return SaveFormat.write(payload, SaveFormat.labelOf(state), stamp, nowMillis)
    }

    /**
     * Turns file text into a creature, or into a refusal that says why.
     *
     * Structure is checked first by [SaveFormat.read] — version, length, checksum — and only a file
     * that passes all of it is decoded. The decode is still wrapped, because a payload can be
     * perfectly intact and still not be JSON this build understands.
     */
    fun parse(text: String): SaveImport {
        val file = when (val envelope = SaveFormat.read(text)) {
            is SaveReadResult.Rejected -> return SaveImport.Failed(envelope)
            is SaveReadResult.Ok -> envelope.file
        }

        val payload = runCatching { json.decodeFromString(SavePayload.serializer(), file.payloadJson) }
            .getOrElse {
                return SaveImport.Failed(
                    SaveReadResult.Rejected(
                        SaveRejection.MALFORMED,
                        it.message?.take(120).orEmpty(),
                        file.header.label,
                    ),
                )
            }
        val pet = payload.pet
            ?: return SaveImport.Failed(SaveReadResult.Rejected(SaveRejection.NO_CREATURE, "", file.header.label))

        return SaveImport.Ok(
            ImportedSave(
                header = file.header,
                // The header is metadata a text editor can rewrite. What the player is shown before
                // they overwrite a life has to come from the creature that is actually in the file.
                label = SaveFormat.labelOf(pet),
                pet = pet,
                config = payload.config,
            ),
        )
    }

    /**
     * The name to offer in the create-document dialog.
     *
     * The formatter is built per call rather than held as a field: `SimpleDateFormat` is not thread
     * safe, and this is cheap enough that sharing one is a race bought for nothing.
     */
    fun suggestedFileName(state: PetState, nowMillis: Long): String {
        val stampedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
        return SaveFormat.suggestedFileName(SaveFormat.labelOf(state), stampedDate)
    }

    // ---------------------------------------------------------------- the player-driven half

    /**
     * Writes [text] to a document the player chose with `ACTION_CREATE_DOCUMENT`.
     *
     * Opened `"wt"` rather than `"w"`: several providers do not truncate on plain write, and a
     * shorter save written over a longer one would keep the old tail and fail its own length check
     * on the way back in.
     */
    suspend fun export(uri: Uri, text: String): SaveWriteResult = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = text.encodeToByteArray()
            app.contentResolver.openOutputStream(uri, "wt").use { stream ->
                checkNotNull(stream) { "the file could not be opened for writing" }
                stream.write(bytes)
                stream.flush()
            }
            SaveWriteResult(ok = true, bytes = bytes.size)
        }.getOrElse { SaveWriteResult(ok = false, error = it.message.orEmpty()) }
    }

    /** Reads and parses a document the player chose with `ACTION_OPEN_DOCUMENT`. */
    suspend fun import(uri: Uri): SaveImport = withContext(Dispatchers.IO) {
        readCapped(uri).fold(
            onSuccess = { parse(it) },
            onFailure = {
                val reason = if (it is TooLargeException) SaveRejection.TOO_LARGE else SaveRejection.MALFORMED
                SaveImport.Failed(SaveReadResult.Rejected(reason, it.message.orEmpty()))
            },
        )
    }

    /**
     * Reads at most [SaveFormat.MAX_SAVE_BYTES] and refuses anything longer.
     *
     * The cap is applied while streaming rather than by asking the provider how big the file is,
     * because a provider is free to answer `-1` or to lie, and the cost of believing it is an
     * out-of-memory crash on a file the player only meant to glance at.
     */
    private fun readCapped(uri: Uri): Result<String> = runCatching {
        app.contentResolver.openInputStream(uri).use { stream ->
            checkNotNull(stream) { "the file could not be opened" }
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > SaveFormat.MAX_SAVE_BYTES) throw TooLargeException(total)
                collected.write(buffer, 0, read)
            }
            collected.toByteArray().toString(Charsets.UTF_8)
        }
    }

    /**
     * A document-tree URI to hand to `EXTRA_INITIAL_URI` so the picker opens on the mirror folder.
     *
     * A hint, not a contract: the constant here is the built-in external-storage provider's, and a
     * device whose picker is something else will simply ignore it and open wherever it likes.
     */
    fun pickerStartUri(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$MIRROR_FOLDER")
        }.getOrNull()
    }

    // ---------------------------------------------------------------- the mirror

    /** Where the mirror lives, for a settings screen to say so out loud. */
    val mirrorFolder: String get() = MIRROR_FOLDER

    /** True when this device can hold a mirror at all. */
    val mirrorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Answers, cheaply, whether a mirror would actually be written right now.
     *
     * Separate from [mirror] so a caller on the save path can skip encoding a whole save it is
     * about to throw away. Null means it would be written.
     */
    fun mirrorGate(nowMillis: Long, force: Boolean = false): MirrorOutcome? = when {
        !mirrorEnabled -> MirrorOutcome.DISABLED
        // Below Android 10 this needs WRITE_EXTERNAL_STORAGE and a runtime prompt. The manifest
        // deliberately asks for nothing it does not need, so on those devices the player gets Auto
        // Backup and an explicit export — and is told so, rather than left to assume a mirror.
        !mirrorSupported -> MirrorOutcome.UNSUPPORTED
        // `now - last >= interval`, with `last` starting at zero rather than at `Long.MIN_VALUE`:
        // this project has already shipped that exact overflow once. The subtraction wraps
        // negative, the gap is never satisfied, and the feature simply never runs while looking in
        // every other way like it is switched on.
        !force && nowMillis - lastMirrorAtMillis < MIRROR_INTERVAL_MILLIS -> MirrorOutcome.SKIPPED
        else -> null
    }

    /**
     * Keeps `Documents/NeoPal/neopal-latest.txt` current.
     *
     * Rate-limited, because this hangs off the same save the simulation triggers every few seconds
     * and a MediaStore write per tick would be absurd. [force] is for the moments worth a copy
     * whatever the clock says: a death, a new generation, the app going to background.
     */
    suspend fun mirror(
        state: PetState,
        config: GameConfig,
        nowMillis: Long,
        force: Boolean = false,
    ): MirrorResult {
        val gate = mirrorGate(nowMillis, force)
        if (gate != null) {
            val detail = if (gate == MirrorOutcome.UNSUPPORTED) "needs Android 10 or later" else ""
            return MirrorResult(gate, detail)
        }
        val text = compose(state, config, nowMillis)
        return withContext(Dispatchers.IO) {
            runCatching {
                writeMirror(text)
                lastMirrorAtMillis = nowMillis
                MirrorResult(MirrorOutcome.WRITTEN, "$MIRROR_FOLDER/$MIRROR_LATEST")
            }.getOrElse { MirrorResult(MirrorOutcome.FAILED, it.message.orEmpty()) }
        }
    }

    /**
     * Writes the mirror through a scratch file, then rotates.
     *
     * Never truncates the good copy to write the new one. The order is: fill the scratch file
     * completely, demote the current copy to `previous`, then promote the scratch. Interrupted at
     * any point that leaves at least one complete, readable file on disk — which is the entire
     * reason this is three steps instead of one `openOutputStream`.
     */
    private fun writeMirror(text: String) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = app.contentResolver

        // A scratch file left behind by an interrupted run is stale by definition.
        idOf(collection, MIRROR_SCRATCH)?.let { resolver.delete(fileUri(collection, it), null, null) }

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MIRROR_SCRATCH)
            put(MediaStore.MediaColumns.MIME_TYPE, SaveFormat.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, MIRROR_FOLDER)
            // Invisible to everything else until the bytes are all there, so no other app can read
            // half a save and no file manager shows a torn one.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val scratch = resolver.insert(collection, pending)
            ?: throw IOException("$MIRROR_FOLDER could not be written to")

        resolver.openOutputStream(scratch, "wt").use { stream ->
            checkNotNull(stream) { "the mirror could not be opened for writing" }
            stream.write(text.encodeToByteArray())
            stream.flush()
        }
        resolver.update(scratch, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

        // Demote the copy that is currently good, discarding the one before it.
        //
        // After a reinstall this query finds nothing even though a mirror is sitting right there:
        // the file belongs to the installation that wrote it, and that installation is gone. The
        // rename below then collides and MediaStore appends a counter instead of overwriting.
        // That is the outcome to want, not one to fix — the file it declines to overwrite is the
        // one holding the creature the player is trying to get back.
        idOf(collection, MIRROR_LATEST)?.let { latest ->
            idOf(collection, MIRROR_PREVIOUS)?.let { resolver.delete(fileUri(collection, it), null, null) }
            rename(collection, latest, MIRROR_PREVIOUS)
        }
        rename(collection, ContentUris.parseId(scratch), MIRROR_LATEST)
    }

    /**
     * The row id of a file of ours by name.
     *
     * Name alone is enough of a filter. Without a storage permission a MediaStore query returns
     * only rows this installation owns, so there is nothing else here to collide with — and
     * matching on `RELATIVE_PATH` as well would be matching on a string the system normalises
     * differently across vendors.
     */
    private fun idOf(collection: Uri, displayName: String): Long? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        // The cursor is unwrapped before `use` rather than reached through `?.use`: a non-local
        // return out of a lambda passed to an inline function through a safe call does not compile.
        val cursor = app.contentResolver.query(collection, projection, selection, arrayOf(displayName), null)
            ?: return null
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun rename(collection: Uri, id: Long, to: String) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, to) }
        app.contentResolver.update(fileUri(collection, id), values, null, null)
    }

    private fun fileUri(collection: Uri, id: Long): Uri = ContentUris.withAppendedId(collection, id)

    // ---------------------------------------------------------------- provenance

    /** Which build wrote a file. Recorded for the player's benefit; nothing is gated on it. */
    private val stamp: SaveStamp by lazy {
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
            SaveStamp(appVersionName = info.versionName.orEmpty(), appVersionCode = code)
        }.getOrDefault(SaveStamp())
    }

    private class TooLargeException(bytes: Long) : IOException("$bytes bytes")

    private companion object {
        const val MIRROR_FOLDER = "Documents/NeoPal"
        const val MIRROR_LATEST = "neopal-latest.txt"
        const val MIRROR_PREVIOUS = "neopal-previous.txt"
        const val MIRROR_SCRATCH = "neopal-writing.txt"

        /** Twenty minutes. Often enough that little is ever lost, rare enough to be invisible. */
        const val MIRROR_INTERVAL_MILLIS = 20L * 60L * 1000L

        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}

/** A save read off the device, decoded, and not yet kept. */
data class ImportedSave(
    /** Derived from the creature itself, never from the header a text editor could have rewritten. */
    val label: SaveLabel,
    val pet: PetState,
    /** Null when the file carried no settings; the device then keeps its own. */
    val config: GameConfig?,
    val header: SaveHeader,
)

/** The outcome of reading a save. Either a whole creature or nothing at all. */
sealed interface SaveImport {
    data class Ok(val save: ImportedSave) : SaveImport

    /** Carries the domain's own refusal, so every reason and its wording live in one tested place. */
    data class Failed(val rejection: SaveReadResult.Rejected) : SaveImport
}

/** The outcome of writing a save to a document the player chose. */
data class SaveWriteResult(
    val ok: Boolean,
    val bytes: Int = 0,
    val error: String = "",
)

/** What happened to the mirror in shared storage. */
enum class MirrorOutcome {
    WRITTEN,

    /** Too soon since the last one. Not a failure. */
    SKIPPED,

    /** Turned off by the player. */
    DISABLED,

    /** Android 9 or earlier: shared storage would need a permission this app does not ask for. */
    UNSUPPORTED,

    FAILED,
}

/**
 * The mirror's result, kept explicit rather than a boolean.
 *
 * A silent no-op and a success look identical from the outside, and this project has already been
 * bitten twice by exactly that: a feature that quietly declined to run while every visible sign
 * said it was on. Anything that decides not to write has to be able to say so.
 */
data class MirrorResult(
    val outcome: MirrorOutcome,
    val detail: String = "",
) {
    val wrote: Boolean get() = outcome == MirrorOutcome.WRITTEN
}
