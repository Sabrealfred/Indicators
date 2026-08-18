package com.neopal.pet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.PetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "neopal_save")

/**
 * Single source of truth for the save file. The whole [PetState] is stored as one JSON blob:
 * the schema changes often while a game like this grows, and `ignoreUnknownKeys` plus data-class
 * defaults handle both forward and backward compatibility without migration code.
 */
class PetRepository(context: Context) {

    private val store = context.applicationContext.dataStore

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

    val configFlow: Flow<GameConfig> = store.data.map { prefs ->
        prefs[KEY_CONFIG]?.let { raw ->
            runCatching { json.decodeFromString(GameConfig.serializer(), raw) }.getOrNull()
        } ?: GameConfig.Default
    }

    suspend fun currentState(): PetState? = stateFlow.first()

    suspend fun currentConfig(): GameConfig = configFlow.first()

    suspend fun save(state: PetState) {
        val encoded = json.encodeToString(PetState.serializer(), state)
        store.edit { it[KEY_STATE] = encoded }
    }

    suspend fun saveConfig(config: GameConfig) {
        val encoded = json.encodeToString(GameConfig.serializer(), config)
        store.edit { it[KEY_CONFIG] = encoded }
    }

    /** Wipes the save. Used by "start over" in settings. */
    suspend fun clear() {
        store.edit { it.remove(KEY_STATE) }
    }

    /** Export/import so a player can move a save between devices as plain text. */
    suspend fun exportSave(): String = currentState()?.let { json.encodeToString(PetState.serializer(), it) } ?: ""

    suspend fun importSave(raw: String): Boolean = runCatching {
        val state = json.decodeFromString(PetState.serializer(), raw)
        save(state)
        true
    }.getOrDefault(false)

    private companion object {
        val KEY_STATE = stringPreferencesKey("pet_state")
        val KEY_CONFIG = stringPreferencesKey("game_config")
    }
}
