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

private val Context.titleDataStore: DataStore<Preferences> by preferencesDataStore(name = "english_titles")

/**
 * Persisted desidubanime slug -> English title map. The DDA REST API only
 * serves romaji titles; English names come from scraped pages (homepage/archive
 * spans, detail-page h1, ADH match). Persisting them means a cold start already
 * knows most titles — the first home render, sidebar catalog and searches are
 * English without re-scraping, and only truly never-seen anime need one fetch.
 */
class EnglishTitleStore(private val context: Context) {
    private val key = stringPreferencesKey("map")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Map<String, String> {
        val raw = context.titleDataStore.data.first()[key]
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    /** Merge newly learned entries into the stored map. */
    suspend fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        context.titleDataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
            }.orEmpty()
            prefs[key] = json.encodeToString(current + entries)
        }
    }
}