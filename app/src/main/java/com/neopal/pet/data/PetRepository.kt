package com.neopal.pet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.sanitised
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "neopal_save")

/**
 * A second store, holding only the player's own API key, and kept in a separate file for one
 * reason: **the save is backed up and the key must not be.**
 *
 * The backup rules deliberately include the whole `datastore/` directory so a creature survives a
 * reinstall, which is the right instinct — losing a lineage is the worst thing this app could do
 * to somebody. But the key used to live inside `GameConfig`, in that same file, so it rode along
 * to Google's backup servers and onto any device the player transferred to.
 *
 * Splitting the file is what lets the two have different fates: the save is included, this is
 * excluded by name in both `backup_rules.xml` and `data_extraction_rules.xml`. A key is
 * re-pasteable in ten seconds; a five-generation lineage is not.
 */
private val Context.secretStore: DataStore<Preferences> by preferencesDataStore(name = "neopal_secrets")

/**
 * Single source of truth for the save file. The whole [PetState] is stored as one JSON blob:
 * the schema changes often while a game like this grows, and `ignoreUnknownKeys` plus data-class
 * defaults handle both forward and backward compatibility without migration code.
 */
class PetRepository(context: Context) {

    private val store = context.applicationContext.dataStore
    private val secrets = context.applicationContext.secretStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * The save, or null when there has never been one.
     *
     * A blob that is *present but unreadable* is deliberately not null here, because null means
     * "new player" to everything above and the app would offer onboarding and then overwrite the
     * only copy. `ignoreUnknownKeys` covers unknown *keys*, not unknown enum *values* — one
     * `RetroMode` or `Species` from a newer build makes the whole blob throw — so a sideloaded
     * older APK, a Play rollback, or a save carried between two devices on different versions
     * could destroy a lineage in one launch. The unreadable blob is kept aside instead.
     */
    val stateFlow: Flow<PetState?> = store.data.map { prefs ->
        prefs[KEY_STATE]?.let { raw ->
            runCatching { json.decodeFromString(PetState.serializer(), raw) }
                .onFailure { unreadable = raw }
                .getOrNull()
                ?.also { unreadable = null }
        }
    }

    /**
     * The last blob that would not decode, if any. Held so [save] can refuse to bury it.
     */
    @Volatile
    private var unreadable: String? = null

    /**
     * Settings, with the key put back on afterwards.
     *
     * The rest of the app sees an ordinary [GameConfig] with the key on it, exactly as before;
     * only the two files underneath know they are separate. Doing the split here rather than in
     * the type meant nothing above this line had to change, which is also the reason it can be
     * relied upon — there is one place to get it wrong instead of every call site.
     *
     * Sanitised on the way out of the file, for the same reason [PetState] is. Settings looked
     * like the safe half of the save — they are "just preferences" — but the day length is a
     * divisor, and the only thing that ever brought it into range was the slider's own clamp,
     * which a value read off disk never passes through. See [GameConfig.sanitised].
     */
    val configFlow: Flow<GameConfig> = combine(store.data, secrets.data) { prefs, secret ->
        val stored = prefs[KEY_CONFIG]?.let { raw ->
            runCatching { json.decodeFromString(GameConfig.serializer(), raw) }.getOrNull()
        } ?: GameConfig.Default
        val key = secret[KEY_API] ?: stored.mind.apiKey
        stored.sanitised().copy(mind = stored.mind.copy(apiKey = key))
    }

    suspend fun currentState(): PetState? = stateFlow.first()

    suspend fun currentConfig(): GameConfig = configFlow.first()

    /**
     * Writes the save, and cannot throw.
     *
     * Two guards, both of which existed as crashes before.
     *
     * The encode is not allowed to fail. This `Json` does not set
     * `allowSpecialFloatingPointValues`, so a single `NaN` anywhere in the tree throws — and
     * because the save is one blob, that kills the *whole* write, permanently, on every
     * subsequent attempt. `persist` launches this bare into `viewModelScope`, so the throw landed
     * uncaught on the main thread. Sanitising first removes the NaN rather than discovering it.
     *
     * And a save that could not be read is not overwritten. Burying an unreadable blob under a
     * fresh one turns "this build cannot open your save" into "your creature is gone".
     */
    suspend fun save(state: PetState) {
        if (unreadable != null) return
        val encoded = runCatching {
            json.encodeToString(PetState.serializer(), state.sanitised())
        }.getOrNull() ?: return
        store.edit { it[KEY_STATE] = encoded }
    }

    /** True when there is a save on disk this build cannot read. The UI must not offer a new game. */
    suspend fun hasUnreadableSave(): Boolean {
        stateFlow.first()
        return unreadable != null
    }

    suspend fun saveConfig(config: GameConfig) {
        // Blanked before it is written, not after. Writing the whole thing and tidying up later
        // would leave the key on disk in the backed-up file for however long "later" turns out
        // to be, which on a crash is forever.
        val withoutKey = config.copy(mind = config.mind.copy(apiKey = ""))
        val encoded = json.encodeToString(GameConfig.serializer(), withoutKey)
        store.edit { it[KEY_CONFIG] = encoded }
        secrets.edit {
            if (config.mind.apiKey.isBlank()) it.remove(KEY_API) else it[KEY_API] = config.mind.apiKey
        }
    }

    /** Wipes the save. Used by "start over" in settings. */
    suspend fun clear() {
        store.edit { it.remove(KEY_STATE) }
    }

    /** Export/import so a player can move a save between devices as plain text. */
    suspend fun exportSave(): String = currentState()?.let { json.encodeToString(PetState.serializer(), it) } ?: ""

    /**
     * Takes a save from outside and makes it safe before it is kept.
     *
     * What arrives here is a string a player pasted, which means it is untrusted input in the
     * same sense a model's reply is. The list caps elsewhere in this app are applied when the
     * game *appends* — so a hostile save carrying two hundred thousand decisions is not trimmed
     * until the creature next decides, which in manual mode is never, and the screen that lists
     * them is not lazy. [PetState.sanitised] is the one boundary where that is dealt with.
     */
    suspend fun importSave(raw: String): Boolean = runCatching {
        val state = json.decodeFromString(PetState.serializer(), raw)
        save(state.sanitised())
        true
    }.getOrDefault(false)

    private companion object {
        val KEY_STATE = stringPreferencesKey("pet_state")
        val KEY_CONFIG = stringPreferencesKey("game_config")

        /** Lives in [secretStore], which the backup rules exclude by name. */
        val KEY_API = stringPreferencesKey("mind_api_key")
    }
}
