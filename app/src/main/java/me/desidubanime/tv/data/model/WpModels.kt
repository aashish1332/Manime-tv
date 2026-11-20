package me.desidubanime.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** ---- WordPress wp/v2/anime ---- */

@Serializable
data class WpRendered(val rendered: String = "")

@Serializable
data class WpAnime(
    val id: Int = 0,
    val slug: String = "",
    val link: String = "",
    val date: String = "",           // first published (new release)
    val modified: String = "",       // bumped when episodes are added
    val title: WpRendered = WpRendered(),
    val content: WpRendered = WpRendered(),
    val excerpt: WpRendered = WpRendered(),
    @SerialName("featured_media") val featuredMedia: Int = 0,
    val genre: List<Int> = emptyList(),
    @SerialName("anime_status") val animeStatus: List<Int> = emptyList(),
    @SerialName("anime_type") val animeType: List<Int> = emptyList(),
    val premiered: JsonElement? = null,
    @SerialName("_embedded") val embedded: WpEmbedded? = null,
)

/** wp/v2/episode feed item — used to know which anime got fresh episodes. */
@Serializable
data class WpEpisodePost(
    val id: Int = 0,
    val slug: String = "",           // "<anime-slug>-episode-<n>"
    val date: String = "",           // upload time of this episode
    val link: String = "",
)

@Serializable
data class WpEmbedded(
    @SerialName("wp:featuredmedia") val featuredMedia: List<WpMedia> = emptyList(),
)

@Serializable
data class WpMedia(
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("media_details") val mediaDetails: WpMediaDetails? = null,
)

@Serializable
data class WpMediaDetails(
    val sizes: Map<String, WpMediaSize> = emptyMap(),
)

@Serializable
data class WpMediaSize(
    @SerialName("source_url") val sourceUrl: String = "",
)
