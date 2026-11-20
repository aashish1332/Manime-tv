package me.desidubanime.tv.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One resumable entry per anime — the last episode the user was watching. */
@Serializable
data class WatchEntry(
    val animeId: Int,
    val animeTitle: String,
    val posterUrl: String,
    val episodeIndex: Int,
    val episodeLabel: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    /**
     * Server label that was playing ("HubCloud · [720P x264]", "CLOUD · SERVER 1").
     * Carries the server, quality and (via SUB/DUB grouping) language choice —
     * restored into PlaybackSession on resume so the player starts on the same
     * setup after an app restart.
     */
    val serverLabel: String? = null,
    /** Audio language playing when the app closed (see PlaybackSession). */
    val audioLang: String? = null,
    /** Subtitle language; "" = subtitles were OFF. */
    val subLang: String? = null,
    /** Video height picked in the menu; null = auto/highest. */
    val videoHeight: Int? = null,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "watch_history")

/**
 * Continue-watching store. Keeps one entry per anime keyed by id, capped to the
 * most recent [MAX_ENTRIES], persisted as a JSON blob in Preferences DataStore.
 */
class WatchHistoryStore(private val context: Context) {
    private val key = stringPreferencesKey("entries")
    private val json = Json { ignoreUnknownKeys = true }

    val entries: Flow<List<WatchEntry>> = context.historyDataStore.data.map { prefs ->
        decode(prefs[key]).sortedByDescending { it.updatedAt }
    }

    /** Record (or update) where the user is in an anime. */
    suspend fun record(entry: WatchEntry) {
        context.historyDataStore.edit { prefs ->
            val current = decode(prefs[key]).filter { it.animeId != entry.animeId }
            val updated = (listOf(entry.copy(updatedAt = System.currentTimeMillis())) + current)
                .take(MAX_ENTRIES)
            prefs[key] = json.encodeToString(updated)
        }
    }

    /** Drop every Continue Watching entry (Settings → clear watch history). */
    suspend fun clear() {
        context.historyDataStore.edit { prefs -> prefs.remove(key) }
    }

    suspend fun remove(animeId: Int) {
        context.historyDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filter { it.animeId != animeId })
        }
    }

    private fun decode(raw: String?): List<WatchEntry> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<WatchEntry>>(raw) }.getOrDefault(emptyList())

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
