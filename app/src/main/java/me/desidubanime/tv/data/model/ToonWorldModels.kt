package me.desidubanime.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ---- ToonWorld4All WordPress posts ---- */

@Serializable
data class TwPost(
    val id: Int = 0,
    val slug: String = "",
    val link: String = "",
    val date: String = "",
    val modified: String = "",
    val title: WpRendered = WpRendered(),
    val content: WpRendered = WpRendered(),
    @SerialName("_embedded") val embedded: WpEmbedded? = null,
)

/** Parsed, matchable info about one ToonWorld post (one anime season). */
data class TwInfo(
    val postId: Int,
    val slug: String,
    val link: String,
    val date: String,
    val modified: String,
    /** English title without the season/audio boilerplate. */
    val titleBase: String,
    val season: Int,
    val posterUrl: String,
    val synopsis: String,
    /**
     * The episode-list page URL found in the post content (first one; kept for
     * compatibility / logging).
     */
    val episodesUrl: String,
    /**
     * EVERY episode page URL keyed by episode number. T4A moved to ONE PAGE
     * PER EPISODE ("/episode/<slug>-<season>x<ep>"), so the single-URL model
     * silently yielded at most episode 1 — AD/TW-only shows rendered "0
     * episodes". Populated from post content anchors.
     */
    val episodeUrls: Map<Int, String> = emptyMap(),
) {
    /** Title shown in the UI when this anime exists only on ToonWorld. */
    val displayTitle: String get() = if (season > 1) "$titleBase Season $season" else titleBase
}

/** All hubcloud links for one episode, parsed from the toonworld episode page. */
data class TwEpisodePage(
    val episodes: List<TwEpisode>,
    /** See [me.desidubanime.tv.data.model.AdhEpisodePage.ok] — false = unknown. */
    val ok: Boolean = true,
)

/** One episode with its quality links. */
data class TwEpisode(
    val number: Int,
    val links: List<HubLink>,
)