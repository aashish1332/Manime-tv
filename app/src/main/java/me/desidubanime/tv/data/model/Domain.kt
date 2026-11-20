package me.desidubanime.tv.data.model

/** Clean domain models used by the UI layer (decoded / normalised). */

data class Anime(
    val id: Int,                     // DDA post id; ADH-only anime use -adhPostId
    val slug: String,
    val title: String,
    val synopsis: String,
    val posterUrl: String,
    val link: String,
    val publishedAt: String = "",    // ISO date the anime post was created
    val updatedAt: String = "",      // ISO date of the last update (episode add)
    /** Set when this anime also (or only) exists on AnimeDubHindi. */
    val adhPostId: Int? = null,
    val adhEpisodesUrl: String? = null,
    /** Set when this anime also exists on AnimeDrive.in (third source). */
    val adPostId: Int? = null,
    val adEpisodesUrl: String? = null,
    /** Set when this anime also exists on ToonWorld4All (fourth source). */
    val twPostId: Int? = null,
    val twEpisodesUrl: String? = null,
    /**
     * True when DDA marks this "Upcoming" / "Not yet released" — the card has
     * 0 episodes and must stay off the home screen until the first upload.
     */
    val upcoming: Boolean = false,
    /**
     * When this anime's newest episode landed (max across DDA episode feed and
     * ADH post modified). Set on home-feed entries; drives NEW EPISODE badges.
     */
    val latestEpisodeAt: String = "",
    /**
     * True once this card was built with ALL full site catalogs on hand, so
     * its source set is final. Cards built from a partial catalog (cold start,
     * cached feed) leave it false and render NO badges — badges appear once, together.
     */
    val sourcesResolved: Boolean = false,
) {
    /** Which sources have this anime — for source badges on cards. */
    val hasDda: Boolean get() = id > 0
    val hasAdh: Boolean get() = adhPostId != null
    val hasAd: Boolean get() = adPostId != null
    val hasTw: Boolean get() = twPostId != null
}

/** Sections shown on the home screen. */
data class HomeFeed(
    /** Anime ordered by when their newest episode was uploaded. */
    val newEpisodes: List<Anime>,
    /** Recently added anime (new releases), newest first. */
    val newReleases: List<Anime>,
    /** Everything we know about, most recently updated first. */
    val all: List<Anime>,
)

data class Episode(
    val id: Int,
    val number: String,          // "Episode 1"
    val numberInt: Int,          // 1  (parsed for ordering / next)
    val title: String,
    val thumbnail: String,
    val watchUrl: String,        // /watch/... page ("" when the episode is ADH-only)
    /** HubCloud quality links from AnimeDubHindi for this episode. */
    val hubLinks: List<HubLink> = emptyList(),
)

enum class ServerGroup { SUB, DUB, RAW }

/** A playable server option decoded from the episode page. */
data class Server(
    val name: String,            // "CLOUD", "Mirrordub", ...
    val embedUrl: String,        // the iframe/embed url
    val group: ServerGroup,
    /** CLOUD-type servers expand into sub-servers (SERVER 1 / SERVER 2 ...). */
    val isCloud: Boolean = false,
    /** Mirrordub/gdmirrorbot aggregators expand into named sub-servers too. */
    val isMirror: Boolean = false,
)

/** A sub-server inside a CLOUD/mirror aggregator. */
data class SubServer(
    val name: String,            // "SERVER 1"
    val playUrl: String,         // absolute url to the /play/ page
)

/** Result of resolving a server to something the player can use. */
sealed interface Playable {
    /** A direct stream URL (m3u8/mp4) + headers -> native Media3. */
    data class Stream(
        val url: String,
        val headers: Map<String, String>,
        val isHls: Boolean,
        /** Sidecar subtitle files (.vtt/.srt) intercepted alongside the stream. */
        val subtitles: List<SubtitleTrack> = emptyList(),
    ) : Playable

    /** Could not extract a direct stream -> play this embed in a WebView. */
    data class Embed(val url: String) : Playable
}

/** A sidecar subtitle file for native playback. */
data class SubtitleTrack(
    val url: String,
    val label: String,           // best-effort, from the file name
    val isVtt: Boolean,
)
