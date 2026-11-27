package me.desidubanime.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ---- WordPress shared shapes (AnimeDubHindi, AnimeDrive, ToonWorld4All) ---- */

@Serializable
data class WpRendered(val rendered: String = "")

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
