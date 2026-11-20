package me.desidubanime.tv.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.adhSlugDataStore: DataStore<Preferences> by preferencesDataStore(name = "adh_slug_map")

/**
 * Persisted ADH postId -> desidubanime slug map, learned from the "Watch
 * online" anchor on adhlinks episode pages ("" = page checked, no link).
 * Each post only ever needs one adhlinks fetch to learn its slug, so keeping
 * the map across restarts makes cold-start merges instant and network-free.
 */
class AdhSlugStore(private val context: Context) {
    private val key = stringPreferencesKey("map")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Map<Int, String> {
        val raw = context.adhSlugDataStore.data.first()[key]
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<Int, String>>(raw) }.getOrDefault(emptyMap())
    }

    /** Merge newly learned entries into the stored map. */
    suspend fun putAll(entries: Map<Int, String>) {
        if (entries.isEmpty()) return
        context.adhSlugDataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<Map<Int, String>>(raw) }.getOrDefault(emptyMap())
            }.orEmpty()
            prefs[key] = json.encodeToString(current + entries)
        }
    }
}
