package com.neopal.pet.data

import android.content.Context
import com.neopal.pet.domain.FetchableModel
import com.neopal.pet.domain.ModelVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the on-device brain lives on the disk, and the only code that decides that.
 *
 * ## Not the cache
 *
 * The updater next door keeps its APK in `cacheDir`, and that is right for a ten-megabyte file
 * that is disposable by definition: if Android reclaims it, the updater notices and offers to
 * fetch it again. It would be badly wrong here. The system empties the cache whenever it feels
 * storage pressure, with no warning and no way to ask it not to — and a player who spent a night
 * and three gigabytes of somebody's Wi-Fi, then found the download gone on its own, would rightly
 * never trust the feature again. Worse than never having offered it.
 *
 * So this is `filesDir`, which the system does not touch. The cost is that the app is now
 * responsible for the space: it is shown in Settings, and one press deletes it.
 *
 * ## Not in the backup either
 *
 * `allowBackup` is on, and gigabytes have no business in anybody's cloud backup or device
 * transfer. Nothing had to be added to the backup XML for that, and it is worth writing down why:
 * both `backup_rules.xml` and `data_extraction_rules.xml` are *include* lists naming
 * `datastore/`, so everything else — this directory included — is already excluded by
 * construction. If either file is ever switched to an exclude list, this directory has to be
 * named in it.
 *
 * ## The four files per model
 *
 * A finished model is one file. A download in progress is up to three:
 *
 * - `name.litertlm` — the finished, checked file. Its presence *is* the claim that it is good.
 * - `name.litertlm.part` — bytes so far. Never renamed onto the real name until it has been read
 *   back and checked, so a half-finished file can never be mistaken for a usable one.
 * - `name.litertlm.tag` — the validator the server gave when the partial was started, offered
 *   back as `If-Range` on the resume so the *server* can refuse a stale continuation.
 * - `name.litertlm.ok` — what the check on the finished file was actually worth, so that Settings
 *   can still say so after the process has been restarted.
 */
class ModelStore(context: Context) {

    private val app: Context = context.applicationContext

    /** Permanent, private, and not in any backup. Created lazily: most players never have one. */
    val dir: File get() = File(app.filesDir, DIRECTORY)

    fun ensureDir(): Boolean = dir.isDirectory || dir.mkdirs()

    fun fileFor(model: FetchableModel): File = File(dir, model.fileName)

    fun partFor(model: FetchableModel): File = File(dir, model.fileName + PART_SUFFIX)

    private fun tagFor(model: FetchableModel): File = File(dir, model.fileName + TAG_SUFFIX)

    private fun okFor(model: FetchableModel): File = File(dir, model.fileName + OK_SUFFIX)

    /** Bytes of the finished file, or 0 when there is not one. */
    fun storedBytes(model: FetchableModel): Long = fileFor(model).let { if (it.isFile) it.length() else 0L }

    /** Bytes of the partial, or 0. */
    fun partialBytes(model: FetchableModel): Long = partFor(model).let { if (it.isFile) it.length() else 0L }

    fun hasStored(model: FetchableModel): Boolean = storedBytes(model) > 0L

    /**
     * Everything this feature is using, finished and unfinished, for the line in Settings.
     *
     * Counted by walking the directory rather than by asking each known model, so that a file
     * left behind by a catalogue entry that has since been removed is still shown and still
     * deletable. Space the player cannot see is space they cannot get back.
     */
    fun totalBytes(): Long = runCatching {
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }.getOrDefault(0L)

    /** What the app may actually use here, or -1 when the platform will not say. */
    fun usableBytes(): Long = runCatching {
        val target = if (dir.isDirectory) dir else app.filesDir
        target.usableSpace
    }.getOrDefault(-1L)

    // ------------------------------------------------------------------ the resume sidecars

    /** The validator recorded when the partial was started, or null. */
    fun readValidator(model: FetchableModel): String? = runCatching {
        val file = tagFor(model)
        if (!file.isFile || file.length() > MAX_SIDECAR_BYTES) null else file.readText().trim().ifEmpty { null }
    }.getOrNull()

    fun writeValidator(model: FetchableModel, validator: String?) {
        runCatching {
            val file = tagFor(model)
            if (validator == null) file.delete() else file.writeText(validator)
        }
    }

    /**
     * How much the check on the stored file was worth.
     *
     * Written next to the file rather than kept in memory because the sentence in Settings has to
     * survive the process, and "downloaded and verified" versus "downloaded and it was the right
     * length" are different claims. An unreadable or missing note is read as the weakest of them,
     * which is the honest default: nothing here can prove a check that was never recorded.
     */
    fun readVerification(model: FetchableModel): ModelVerification {
        val name = runCatching {
            val file = okFor(model)
            if (!file.isFile || file.length() > MAX_SIDECAR_BYTES) null else file.readText().trim()
        }.getOrNull()
        return ModelVerification.entries.firstOrNull { it.name == name } ?: ModelVerification.SIZE_ONLY
    }

    fun writeVerification(model: FetchableModel, verification: ModelVerification) {
        runCatching { okFor(model).writeText(verification.name) }
    }

    // ------------------------------------------------------------------ the terms

    /**
     * Whether the player has been shown whose model this is, and said they have read it.
     *
     * A file next to the models rather than a preference in the save, for two reasons. The save is
     * exported, imported and backed up, and "I read a licence on my old phone" is not a fact that
     * should travel to a new one. And this feature owns this directory outright, so it can hold
     * its own state without reaching into a file another part of the app is writing.
     */
    fun termsAccepted(): Boolean = runCatching { File(dir, TERMS_FILE).isFile }.getOrDefault(false)

    fun acceptTerms() {
        runCatching {
            ensureDir()
            File(dir, TERMS_FILE).writeText(ACCEPTED)
        }
    }

    // ------------------------------------------------------------------ removing things

    /**
     * Deletes everything belonging to one model: the file, the partial and both sidecars.
     *
     * Suspending and on the IO dispatcher because deleting three gigabytes is not instant on
     * every phone, and this is called from a button.
     */
    suspend fun delete(model: FetchableModel): Boolean = withContext(Dispatchers.IO) {
        listOf(fileFor(model), partFor(model), tagFor(model), okFor(model))
            .map { runCatching { !it.exists() || it.delete() }.getOrDefault(false) }
            .all { it }
    }

    /** The one-press "get my storage back" in Settings: everything this feature has ever written. */
    suspend fun deleteEverything(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
            dir.listFiles()?.isEmpty() ?: true
        }.getOrDefault(false)
    }

    /** Throws away a partial and its validator, keeping any finished file. */
    fun discardPartial(model: FetchableModel) {
        runCatching { partFor(model).delete() }
        writeValidator(model, null)
    }

    companion object {
        /** Inside `filesDir`. Named in this one place; nothing else builds this path. */
        const val DIRECTORY = "models"

        const val PART_SUFFIX = ".part"
        const val TAG_SUFFIX = ".tag"
        const val OK_SUFFIX = ".ok"

        /**
         * Deliberately not suffixed like the three above: [deleteEverything] empties this
         * directory, and being asked to read a licence again after freeing three gigabytes is
         * the right outcome rather than an oversight — the next download is a fresh decision.
         */
        private const val TERMS_FILE = "terms-accepted"
        private const val ACCEPTED = "yes"

        private const val MAX_SIDECAR_BYTES = 4L * 1024L
    }
}
