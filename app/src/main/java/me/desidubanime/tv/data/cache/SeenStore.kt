package me.desidubanime.tv.data.cache

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the user has already seen, for the NEW badges.
 *
 * - [firstRunAt]: recorded once at first launch. A home card gets a NEW badge
 *   only when the anime was PUBLISHED after this — so a fresh install never
 *   paints the whole grid with badges.
 * - [lastSeen]: per-anime max episode number at the moment the user last
 *   opened its detail page. Episodes above it get a NEW badge; first-ever
 *   open records a baseline instead of showing badges.
 */
class SeenStore(context: Context) {
    private val file = File(context.filesDir, "seen_state.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    @Serializable
    private data class State(
        val firstRunAt: String = "",
        /** animeId -> highest episode number seen on last detail open. */
        val lastSeen: Map<Int, Int> = emptyMap(),
        /** animeId -> latestEpisodeAt stamp when the user last opened the card. */
        val seenStamp: Map<Int, String> = emptyMap(),
    )

    @Volatile
    private var cached: State? = null

    private fun state(): State = cached ?: synchronized(lock) {
        cached ?: run {
            val loaded = try {
                if (file.exists()) json.decodeFromString<State>(file.readText()) else null
            } catch (e: Exception) {
                null
            } ?: State(firstRunAt = nowIso())
            if (!file.exists()) persist(loaded)
            loaded.also { cached = it }
        }
    }

    private fun persist(s: State) {
        try {
            file.writeText(json.encodeToString(s))
        } catch (e: Exception) {
            // Best-effort — losing it only re-baselines the badges.
        }
    }

    /** ISO timestamp of the first app launch (site date format, comparable). */
    fun firstRunAt(): String = state().firstRunAt

    /** Highest episode number the user saw for this anime, or null if never opened. */
    fun lastSeen(animeId: Int): Int? = state().lastSeen[animeId]

    /** Record that the user has now seen everything up to [maxEpisode]. */
    fun setLastSeen(animeId: Int, maxEpisode: Int) = synchronized(lock) {
        val s = state()
        if (s.lastSeen[animeId] == maxEpisode) return@synchronized
        val next = s.copy(lastSeen = s.lastSeen + (animeId to maxEpisode))
        cached = next
        persist(next)
    }

    /** Latest-episode stamp at the user's last open of this anime, or null. */
    fun seenStamp(animeId: Int): String? = state().seenStamp[animeId]

    /** The user just opened this anime — its NEW EPISODE badge clears. */
    fun setSeenStamp(animeId: Int, stamp: String) = synchronized(lock) {
        if (stamp.isBlank()) return@synchronized
        val s = state()
        if (s.seenStamp[animeId] == stamp) return@synchronized
        val next = s.copy(seenStamp = s.seenStamp + (animeId to stamp))
        cached = next
        persist(next)
    }

    /**
     * Forget every "seen" stamp (Settings → reset NEW badges). The first-run
     * timestamp is re-baselined to now, so the grid does not come back painted
     * with NEW badges on every card.
     */
    fun reset() = synchronized(lock) {
        val next = State(firstRunAt = nowIso())
        cached = next
        persist(next)
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}
