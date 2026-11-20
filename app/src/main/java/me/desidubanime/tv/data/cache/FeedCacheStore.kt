package me.desidubanime.tv.data.cache

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.data.model.HomeFeed
import java.io.File

/**
 * Last successful home feed, persisted as one JSON file. On a cold start the
 * home screen renders from this instantly (posters come from Coil's disk
 * cache) while the fresh feed loads behind it — the TV equivalent of
 * stale-while-revalidate. Losing it is harmless; it regenerates on next load.
 */
class FeedCacheStore(context: Context) {
    private val file = File(context.filesDir, "home_feed.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): HomeFeed? = try {
        if (!file.exists()) null
        else json.decodeFromString<FeedDto>(file.readText()).toFeed()
    } catch (e: Exception) {
        null
    }

    /** Drop the persisted feed (e.g. the ADH domain changed — cached cards
     *  would point at the old site). Harmless: regenerates on next load. */
    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // Best-effort.
        }
    }

    fun save(feed: HomeFeed) {
        try {
            // Cap "all" so the file stays small enough to parse in a few ms.
            val dto = FeedDto(
                newEpisodes = feed.newEpisodes.map { it.toDto() },
                newReleases = feed.newReleases.map { it.toDto() },
                all = feed.all.take(MAX_ALL).map { it.toDto() },
            )
            file.writeText(json.encodeToString(dto))
        } catch (e: Exception) {
            // Best-effort cache; never let it break the feed path.
        }
    }

    @Serializable
    private data class AnimeDto(
        val id: Int,
        val slug: String,
        val title: String,
        val synopsis: String = "",
        val posterUrl: String = "",
        val link: String = "",
        val publishedAt: String = "",
        val updatedAt: String = "",
        val adhPostId: Int? = null,
        val adhEpisodesUrl: String? = null,
        val latestEpisodeAt: String = "",
        // These were missing, and their absence was visible: a cached card lost
        // its AnimeDrive match on every cold start, so the feed rendered
        // "DDA (+ADH)" and then popped an AD badge in seconds later once the
        // network merge redid the work the cache had thrown away.
        val adPostId: Int? = null,
        val adEpisodesUrl: String? = null,
        // Same for ToonWorld4All — without these the T4A badge (and its
        // episode pages) vanished on every restart and popped back in after
        // the fresh merge, breaking the "badges appear once, together" rule.
        val twPostId: Int? = null,
        val twEpisodesUrl: String? = null,
        // Upcoming cards must STAY hidden across restarts; dropping the flag
        // resurrected them until the first fresh merge re-filtered the feed.
        val upcoming: Boolean = false,
        val sourcesResolved: Boolean = false,
    )

    @Serializable
    private data class FeedDto(
        val newEpisodes: List<AnimeDto>,
        val newReleases: List<AnimeDto>,
        val all: List<AnimeDto>,
    )

    private fun Anime.toDto() = AnimeDto(
        id, slug, title, synopsis, posterUrl, link, publishedAt, updatedAt, adhPostId, adhEpisodesUrl,
        latestEpisodeAt = latestEpisodeAt,
        adPostId = adPostId,
        adEpisodesUrl = adEpisodesUrl,
        twPostId = twPostId,
        twEpisodesUrl = twEpisodesUrl,
        upcoming = upcoming,
        sourcesResolved = sourcesResolved,
    )

    private fun AnimeDto.toAnime() = Anime(
        id, slug, title, synopsis, posterUrl, link, publishedAt, updatedAt, adhPostId, adhEpisodesUrl,
        adPostId = adPostId,
        adEpisodesUrl = adEpisodesUrl,
        twPostId = twPostId,
        twEpisodesUrl = twEpisodesUrl,
        latestEpisodeAt = latestEpisodeAt,
        upcoming = upcoming,
        sourcesResolved = sourcesResolved,
    )

    private fun FeedDto.toFeed() = HomeFeed(
        newEpisodes = newEpisodes.map { it.toAnime() },
        newReleases = newReleases.map { it.toAnime() },
        all = all.map { it.toAnime() },
    )

    private companion object {
        const val MAX_ALL = 120
    }
}
