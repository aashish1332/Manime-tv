package me.desidubanime.tv.scrape

/**
 * Bridge that lets a page only a BROWSER can open be fetched from the scraper.
 *
 * AnimeDrive publishes every episode's files on `link.animedrive.in`, and that
 * host now sits behind a Cloudflare managed challenge: measured live
 * (2026-11-26) it answers `403` with `cf-mitigated: challenge` for OkHttp,
 * curl and requests alike, on every User-Agent (Android TV, desktop Chrome,
 * Googlebot, bingbot, none), on `/feed/` and on `/wp-json/` too — only
 * `/robots.txt` gets through. A real Chromium clears it in a few seconds, and
 * the page it then serves has the SAME layout the parser already handles
 * (1 `.adc-epname`, 5 `.adc-hoster-lbl`, 11 `.adc-btn`, every link a
 * `link.animedrive.in/dl/<base64>` token that decodes to a GDFlix / send.cm
 * file page).
 *
 * So the fix is not a new host and not a new parser — it is to let the app
 * fetch that one page through the WebView it already has for Cloudflare-gated
 * file hosts. The player installs the fetcher; scrapers call [html].
 */
object AdPageBrowser {

    /** Installed by the player (it owns the WebView + Context). */
    @Volatile
    private var fetcher: (suspend (String) -> String?)? = null

    fun install(block: suspend (String) -> String?) {
        fetcher = block
    }

    /** The page as a browser sees it, or null when no browser is available. */
    suspend fun html(url: String): String? = fetcher?.invoke(url)

    /** True when [body] is a Cloudflare interstitial rather than the page. */
    fun looksLikeChallenge(body: String): Boolean {
        val h = body.lowercase()
        return "just a moment" in h ||
            "__cf_chl" in h ||
            "cf-browser-verification" in h ||
            "challenge-platform" in h ||
            (h.contains("performing security verification") && h.contains("cloudflare"))
    }
}
