package me.desidubanime.tv.scrape

/**
 * File-host detection shared by the ADH and AnimeDrive scrapers. Both sites
 * point at the same hoster ecosystem (HubCloud, GDFlix, FilePress, FileBee…)
 * under rotating domains — always identify the hoster from the URL HOST, never
 * from the page text (AD labels them with emojis: "☁️ HubCloud", "🟣 FilePress").
 */
object Hosters {

    /** Known hoster display names, matched as substrings of the (prefix-stripped) host. */
    private val KNOWN = listOf(
        "hubcloud" to "HubCloud",
        "gdflix" to "GDFlix",
        "filepress" to "FilePress",
        "fpgo" to "FilePress",
        "filebee" to "FileBee",
        "filemoon" to "FileMoon",
        "streamwish" to "StreamWish",
        "doodstream" to "DoodStream",
        "driveleech" to "DriveLeech",
        "drivebot" to "DriveBot",
        "gdtot" to "GDTot",
        "dropload" to "DropLoad",
        "mega" to "MEGA",
    )

    /** Lowercased host name with common CDN prefixes stripped. */
    private fun hostOf(url: String): String {
        val host = Regex("""^(?:https?://)?([^/?#]+)""").find(url.trim())?.groupValues?.get(1)
            ?: return ""
        return host.lowercase()
            .removePrefix("www.")
            .removePrefix("new.")
            .removePrefix("new2.")
            .removePrefix("new3.")
            .removePrefix("new4.")
            .removePrefix("new5.")
            .removePrefix("new6.")
            .removePrefix("new7.")
            .removePrefix("new8.")
            .removePrefix("new9.")
            .removePrefix("new10.")
            .removePrefix("new11.")
            .removePrefix("new12.")
            .removePrefix("new13.")
            .removePrefix("new14.")
            .removePrefix("new15.")
    }

    /** Looks like a file-host link at all (drive/file/video/download page). */
    fun isFileLink(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/drive/") || u.contains("/file/") || u.contains("/video/") || u.contains("/download/") || u.contains("/get/") || u.contains("/api/")
    }

    /**
     * Hoster display name for a drive/file URL, or null when the URL isn't a
     * file-host link. Falls back to the host's first label so unknown hosters
     * still surface instead of being silently dropped.
     */
    fun detect(url: String): String? {
        val host = hostOf(url)
        if (host.isBlank() || !isFileLink(url)) return null
        for ((frag, name) in KNOWN) if (host.contains(frag)) return name
        val label = host.substringBefore('.').trim()
        if (label.isBlank()) return null
        return label.replaceFirstChar { it.uppercase() }
    }

    /**
     * Normalize a quality label for the server menu:
     *   "📥 480p — 97.89 MB"      -> "480p · 97.89 MB"   (AD: emoji prefix)
     *   "480P x264 [Size: 80mb]"  -> "480P x264 · 80mb"  (ADH old layout)
     *   "[480P x264]"             -> "480P x264"         (ADH new layout)
     */
    fun normalizeQuality(raw: String): String =
        raw.replace(Regex("""\[Size:\s*([^\]]+)]"""), "- $1")
            .replace(Regex("""\[Upload[^\]]*]"""), "")
            .replace(Regex("""^\[([^\]]+)]$"""), "$1")          // "[480P x264]" first — the icon strip below would eat the "[".
            .replaceFirst(Regex("""^[^\p{L}\p{N}]+"""), "")     // emoji/icon prefix
            .replace(Regex("""[—–-]"""), "-")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
