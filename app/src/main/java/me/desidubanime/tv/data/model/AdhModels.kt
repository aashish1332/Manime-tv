package me.desidubanime.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ---- AnimeDubHindi (ADH) WordPress posts ---- */

@Serializable
data class AdhPost(
    val id: Int = 0,
    val slug: String = "",
    val link: String = "",
    val date: String = "",           // post created (new release on ADH)
    val modified: String = "",       // bumped when new episode links are added
    val title: WpRendered = WpRendered(),
    val content: WpRendered = WpRendered(),
    @SerialName("_embedded") val embedded: WpEmbedded? = null,
)

/** Parsed, matchable info about one ADH post (one anime season). */
data class AdhInfo(
    val postId: Int,
    val slug: String,
    val link: String,
    val date: String,
    val modified: String,
    /** English title without the season/audio boilerplate ("Eren the Southpaw"). */
    val titleBase: String,
    val season: Int,
    val posterUrl: String,
    val synopsis: String,
    /** The adhlinks episode-list page URL found in the post content. */
    val episodesUrl: String,
) {
    /** Title shown in the UI when this anime exists only on ADH. */
    val displayTitle: String get() = if (season > 1) "$titleBase Season $season" else titleBase
}

/** Which site a hub link was scraped from — both carry HubCloud, GDFlix, … */
enum class LinkSource(val label: String) {
    ADH("ADH"),
    AD("AnimeDrive"),
    TW("ToonWorld"),
}

/** One quality option pointing at a file-host drive page. */
data class HubLink(
    val quality: String,             // "1080P x265 10bit · 253mb"
    val driveUrl: String,            // https://hubcloud.xxx/drive/<id> or gdflix.dev/file/xxx
    /** Which file host this link points to — "HubCloud", "GDFlix", "FilePress", etc.
     *  Used for server labels and priority ordering (HubCloud > GDFlix > others). */
    val hoster: String = "HubCloud",
    /**
     * Which SITE supplied the link. Both sites host the same file hosts, so
     * "HubCloud · 720p" alone is ambiguous — the user could not tell an ADH
     * server from an AnimeDrive one, or notice when only one site's links were
     * showing. The player labels every option with this.
     */
    val source: LinkSource = LinkSource.ADH,
)

/** All hubcloud links for one episode, parsed from the adhlinks page. */
data class HubEpisode(
    val number: Int,
    val links: List<HubLink>,
)

/** Everything parsed from an adhlinks episode page. */
data class AdhEpisodePage(
    val episodes: List<HubEpisode>,
    /**
     * True when the page was really fetched and parsed. FALSE means "unknown"
     * (Cloudflare challenge, timeout, non-2xx): the page may well have
     * episodes, so it must never be remembered as an empty one — that is how
     * a gated link.animedrive.in would hide a real AnimeDrive-only title.
     */
    val ok: Boolean = true,
)
