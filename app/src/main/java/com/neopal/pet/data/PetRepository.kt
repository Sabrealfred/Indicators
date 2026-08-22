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

    val stateFlow: Flow<PetState?> = store.data.map { prefs ->
        prefs[KEY_STATE]?.let { raw ->
            runCatching { json.decodeFromString(PetState.serializer(), raw) }.getOrNull()
        }
    }

    /**
     * Settings, with the key put back on afterwards.
     *
     * The rest of the app sees an ordinary [GameConfig] with the key on it, exactly as before;
     * only the two files underneath know they are separate. Doing the split here rather than in
     * the type meant nothing above this line had to change, which is also the reason it can be
     * relied upon — there is one place to get it wrong instead of every call site.
     */
    val configFlow: Flow<GameConfig> = combine(store.data, secrets.data) { prefs, secret ->
        val stored = prefs[KEY_CONFIG]?.let { raw ->
            runCatching { json.decodeFromString(GameConfig.serializer(), raw) }.getOrNull()
        } ?: GameConfig.Default
        val key = secret[KEY_API] ?: stored.mind.apiKey
        stored.copy(mind = stored.mind.copy(apiKey = key))
    }

    suspend fun currentState(): PetState? = stateFlow.first()

    suspend fun currentConfig(): GameConfig = configFlow.first()

    suspend fun save(state: PetState) {
        val encoded = json.encodeToString(PetState.serializer(), state)
        store.edit { it[KEY_STATE] = encoded }
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
