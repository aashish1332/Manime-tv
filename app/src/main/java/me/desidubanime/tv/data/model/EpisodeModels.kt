package me.desidubanime.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ---- admin-ajax get_episodes response ---- */

@Serializable
data class GetEpisodesResponse(
    val success: Boolean = false,
    val data: GetEpisodesData = GetEpisodesData(),
)

@Serializable
data class GetEpisodesData(
    val episodes: List<WpEpisode> = emptyList(),
    @SerialName("max_episodes_page") val maxEpisodesPage: Int = 1,
    val message: String? = null,
)

@Serializable
data class WpEpisode(
    val id: Int = 0,
    val number: String = "",
    val title: String = "",
    @SerialName("post_title") val postTitle: String = "",
    val thumbnail: String = "",
    val url: String = "",
    val duration: String = "",
    val released: String = "",
)
