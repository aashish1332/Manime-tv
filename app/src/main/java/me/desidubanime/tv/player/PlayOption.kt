package me.desidubanime.tv.player

import me.desidubanime.tv.data.model.ServerGroup

/** One selectable playback source shown in the player's server menu. */
data class PlayOption(
    val label: String,          // "ADH · HubCloud · 720P x264", "CLOUD · SERVER 1", …
    val group: ServerGroup,
    val embedUrl: String,       // url handed to StreamResolver (hub: the /drive/ url)
    val native: Boolean,        // true = expected to yield a direct stream (own CLOUD)
    /** File-host option resolved over plain HTTP by a FileHostResolver, no WebView. */
    val isHub: Boolean = false,
    /** Which file host this link points to — "HubCloud", "GDFlix", "FilePress", etc. */
    val hoster: String = "HubCloud",
    /** Which SITE this option came from — shown in the menu so identical
     *  hoster+quality options from ADH and AnimeDrive stay tellable apart. */
    val sourceLabel: String? = null,
) {
    /**
     * Identity that survives across episodes for the sticky-server logic.
     * HubCloud labels embed the file size ("HubCloud · [720P x264] · 183 MB")
     * which differs every episode — matching on the raw label made stickiness
     * silently fail and the next episode fell back to a different quality.
     * Strip sizes/dates; keep server name + resolution.
     */
    val stickyKey: String = label
        .replace(Regex("""·\s*[\d.]+\s*[KMG]B""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[(Size|Upload)[^\]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
