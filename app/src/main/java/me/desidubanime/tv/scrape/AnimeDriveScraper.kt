package me.desidubanime.tv.scrape

import kotlinx.serialization.builtins.ListSerializer
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.api.Network
import me.desidubanime.tv.data.model.AdhEpisodePage
import me.desidubanime.tv.data.model.AdhInfo
import me.desidubanime.tv.data.model.AdhPost
import me.desidubanime.tv.data.model.HubEpisode
import me.desidubanime.tv.data.model.HubLink
import me.desidubanime.tv.data.settings.AppSettings
import me.desidubanime.tv.util.Titles
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder

/**
 * AnimeDrive.in â€” a WordPress site structurally identical to AnimeDubHindi:
 * each post covers one anime season and links a `link.animedrive.in` episode
 * page carrying per-episode / per-quality HubCloud download links.
 *
 * Unlike ADH's adhlinks page, the AnimeDrive link page wraps every HubCloud
 * drive URL in a `shrinkme.io/st?api=â€¦&url=<hubcloud-url>` shortener. The
 * real drive URL is extracted from the `url` query parameter â€” no HTTP hop
 * to shrinkme.io is needed, so resolution stays instant and ad-free.
 *
 * Reuses [AdhPost] / [AdhInfo] / [AdhEpisodePage] / [HubLink] / [HubEpisode]
 * because the data shapes are identical to ADH's. The [AdhInfo.episodesUrl]
 * field stores the `link.animedrive.in` URL (analogous to ADH's adhlinks URL).
 */
class AnimeDriveScraper(private val settings: AppSettings) {

    /**
     * AnimeDrive base â€” like ADH, the domain rotates, so it comes from
     * [AppSettings] instead of a hardcoded constant. The episode-page host
     * (link.animedrive.in) is read from post content each time, never baked in.
     */
    private suspend fun base(): String = settings.adBase()

    // ---- WordPress REST ----

    suspend fun latestPosts(perPage: Int = 40, client: OkHttpClient = Network.client): List<AdhInfo> =
        fetchPosts("/wp-json/wp/v2/posts?per_page=$perPage&orderby=modified&order=desc&_embed=1", client)

    /**
     * The FULL AnimeDrive catalog. Runs on the low-priority bulk client so
     * it never starves click-time requests on the fast client.
     */
    suspend fun allPosts(): List<AdhInfo> {
        val out = ArrayList<AdhInfo>()
        for (page in 1..8) {
            val batch = fetchPosts("/wp-json/wp/v2/posts?per_page=100&page=$page&orderby=modified&order=desc&_embed=1", Network.bulk)
            out += batch
            if (batch.size < 100) break
        }
        return out
    }

    suspend fun search(query: String): List<AdhInfo> =
        fetchPosts("/wp-json/wp/v2/posts?search=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=20&_embed=1")

    /** Single post by id â€” used to recover AnimeDrive-only anime after process death. */
    suspend fun post(id: Int): AdhInfo? = try {
        val baseUrl = base()
        val body = get(Network.client, "$baseUrl/wp-json/wp/v2/posts/$id?_embed=1")
        Network.json.decodeFromString(AdhPost.serializer(), body).toInfo(baseUrl)
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchPosts(pathAndQuery: String, client: OkHttpClient = Network.client): List<AdhInfo> = try {
        val baseUrl = base()
        val body = get(client, baseUrl + pathAndQuery)
        Network.json.decodeFromString(ListSerializer(AdhPost.serializer()), body)
            .mapNotNull { it.toInfo(baseUrl) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun AdhPost.toInfo(baseUrl: String): AdhInfo? {
        // "Oshi no Ko Season 2 English, Japanese (Dual Audio) WEB-DL Episodes
        //  Download" -> "Oshi no Ko Season 2". Titles.clean handles every
        // boilerplate variant this site uses; a hardcoded "cut at Hindi" missed
        // the English-only and BluRay-Movie posts and left junk in the match key.
        val cut = Titles.clean(title.rendered)
        if (cut.isBlank()) return null
        val (base, season) = Titles.baseAndSeason(cut)

        val doc = Jsoup.parse(content.rendered)
        // The "Download / Watch" link points at the episode-page host (e.g.
        // link.animedrive.in). Derive it from the settings base host so a
        // domain rotation keeps working; the two are always the same site.
        val baseHost = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val linkHost = if (baseHost.startsWith("link.")) baseHost else "link.$baseHost"
        val episodesUrl = doc.select("a[href*=$linkHost]").firstOrNull()?.attr("abs:href")
            ?: doc.select("a[href*=/episode/], a[href*=/links/]").firstOrNull()?.attr("abs:href")
            ?: return null
        val synopsis = doc.select("p:contains(Synopsis)").firstOrNull()?.text()
            ?.removePrefix("Synopsis:")?.trim().orEmpty()
        val poster = embedded?.featuredMedia?.firstOrNull()?.let { m ->
            m.mediaDetails?.sizes?.get("large")?.sourceUrl?.ifBlank { null } ?: m.sourceUrl.ifBlank { null }
        } ?: doc.select("figure img[src]").firstOrNull()?.let { it.attr("abs:src").ifBlank { it.attr("src") } }?.ifBlank { null }.orEmpty()

        return AdhInfo(
            postId = id,
            slug = slug,
            link = link,
            date = date,
            modified = modified,
            titleBase = base,
            season = season,
            posterUrl = poster,
            synopsis = synopsis,
            episodesUrl = episodesUrl,
        )
    }

    // ---- link.animedrive.in episode page ----

    /**
     * Parse a `link.animedrive.in` episode page. The page has a series of
     * episode blocks, each with:
     *   <span class="adc-epname">Episode N</span>
     *   <div class="adc-hoster-lbl">HubCloud</div>
     *   <div class="adc-btns">
     *     <a class="adc-btn hc q480" href="https://shrinkme.io/st?api=â€¦&url=https://hubcloud.cx/drive/xxx">480p x264 â€” 109mb</a>
     *     <a class="adc-btn hc q720" href="â€¦">720p x264 â€” 228mb</a>
     *     ...
     *   </div>
     *   <div class="adc-hoster-lbl">GDFlix</div>
     *   <div class="adc-btns">
     *     <a class="adc-btn fp q480" href="https://shrinkme.io/st?api=â€¦&url=https://gdflix.dev/file/xxx">480p x264 â€” 109mb</a>
     *     ...
     *   </div>
     *
     * Multiple hosters (HubCloud, GDFlix, FilePress) appear per episode â€” we
     * collect ALL of them, tagged with their [hoster] name. The shrinkme.io
     * wrapper's `url` parameter carries the real drive URL; it is URL-decoded
     * and used directly so no shortener hop is needed.
     *
     */
    suspend fun episodePage(url: String, client: OkHttpClient = Network.client): AdhEpisodePage {
        // PLAIN HTTP FIRST, THEN THE BROWSER. link.animedrive.in now answers a
        // Cloudflare managed challenge (403 cf-mitigated: challenge) to any
        // non-browser client — measured 2026-11-26 across OkHttp, curl and
        // requests, on every UA tried. Only a real engine gets the page, and
        // what it serves keeps the layout parsed below.
        var body = runCatching { get(client, url) }.getOrNull()
        if (body == null || AdPageBrowser.looksLikeChallenge(body)) {
            val viaBrowser = runCatching { AdPageBrowser.html(url) }.getOrNull()
            if (viaBrowser != null && !AdPageBrowser.looksLikeChallenge(viaBrowser)) {
                me.desidubanime.tv.data.DebugLog.log(
                    "AD-scrape",
                    "episode page needed the browser (${viaBrowser.length}b) ${url.substringAfterLast("/")}",
                )
                body = viaBrowser
            } else if (body != null) {
                me.desidubanime.tv.data.DebugLog.log(
                    "AD-scrape",
                    "episode page is Cloudflare-gated and no browser cleared it ${url.substringAfterLast("/")}",
                )
            }
        }
        val html = body ?: return AdhEpisodePage(emptyList())
        val doc = Jsoup.parse(html, url)

        // STRICT episode matcher â€” see AdhScraper: a loose bare-"E" pattern
        // matched inside "[Size: 69mb]" and minted zombie episode numbers.
        val epRx = Regex("""\bEp(?:isode)?\s*:?\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
        val qualityRx = Regex("""\d{3,4}[Pp]""", RegexOption.IGNORE_CASE)

        val byNumber = LinkedHashMap<Int, ArrayList<HubLink>>()
        var current = -1
        var quality = ""

        // Walk episode blocks: each episode is a container with an adc-epname
        // span and one or more adc-hoster sections. We walk in document order.
        doc.body()?.let { body ->
            walk(body) { node ->
                when (node) {
                    is org.jsoup.nodes.TextNode -> {
                        val t = node.text().trim()
                        if (t.isEmpty()) return@walk
                        val ep = epRx.find(t)
                        when {
                            ep != null && t.length < 40 -> {
                                current = ep.groupValues[1].toIntOrNull() ?: current
                                quality = ""
                            }
                            qualityRx.containsMatchIn(t) && t.length < 80 -> {
                                // "ðŸ“¥ 480p â€” 97.89 MB" -> "480p Â· 97.89 MB"
                                quality = Hosters.normalizeQuality(t)
                            }
                        }
                    }
                    is Element -> {
                        if (node.tagName() == "a" && current > 0) {
                            val href = node.attr("abs:href").ifBlank { node.attr("href") }
                            val extracted = extractDriveUrl(href)
                            if (extracted != null) {
                                val (driveUrl, hoster) = extracted
                                // Extract quality from anchor text, title, or data attributes
                                val anchorText = node.text().trim()
                                val anchorTitle = node.attr("title").trim()
                                val anchorDataQuality = node.attr("data-quality").trim()
                                val anchorClass = node.attr("class").trim()
                                val candidateQuality = listOf(anchorText, anchorTitle, anchorDataQuality, anchorClass)
                                    .firstOrNull { qualityRx.containsMatchIn(it) }
                                    ?.let { Hosters.normalizeQuality(it) }
                                val finalQuality = candidateQuality ?: quality.ifBlank { "Default" }
                                byNumber.getOrPut(current) { ArrayList() }
                                    .add(
                                        HubLink(
                                            quality = finalQuality,
                                            driveUrl = driveUrl,
                                            hoster = hoster,
                                            source = me.desidubanime.tv.data.model.LinkSource.AD,
                                        ),
                                    )
                            }
                        }
                    }
                }
            }
        }

        // Debug: Log what was found
        me.desidubanime.tv.data.DebugLog.log("AD-scrape", "Found ${byNumber.size} episodes with links from ${url.substringAfterLast("/")}")

        val episodes = byNumber.mapNotNull { (num, links) ->
            if (links.isEmpty()) null else HubEpisode(number = num, links = dedupe(links))
        }
        return AdhEpisodePage(episodes = episodes.sortedBy { it.number })
    }

    /**
     * Extract the real drive URL from whatever wrapper the link carries.
     * THREE layouts seen live (2026-08), all resolved locally with no HTTP hop:
     *
     *  1. shrinkme.io/st?api=â€¦&url=<drive-url>   (old)
     *  2. link.animedrive.in/dl/<BASE64>         (current)
     *     The path segment base64-decodes to the drive URL REVERSED â€”
     *     "dWUyMmYxâ€¦cHR0aA==" -> "ue22f1rtvepj2tj/evird/â€¦" and reversing the
     *     characters gives https://hubcloud.ist/drive/jt2jpevtf1f22eu.
     *     Verified live against the same file linked directly on adhlinks.
     *  3. a direct drive URL (hubcloud.cx/video/<id>, gdflix.dev/file/<id>)
     *
     * Returns (decodedUrl, hosterName); null for non-file links.
     */
    private fun extractDriveUrl(href: String): Pair<String, String>? {
        val decoded = when {
            href.contains("/dl/") -> decodeDlToken(href) ?: return null
            href.contains("shrinkme.io") || href.contains("url=") -> {
                val urlParam = Regex("""[?&]url=([^&]+)""").find(href)?.groupValues?.get(1)
                    ?: return null
                try { URLDecoder.decode(urlParam, "UTF-8") } catch (e: Exception) { urlParam }
            }
            else -> href
        }

        // Identify the hoster from the URL host (shared with the ADH scraper).
        val hoster = Hosters.detect(decoded) ?: return null
        return decoded to hoster
    }

    /**
     * link.animedrive.in/dl/<token> -> real drive URL. Token is standard
     * base64 of the REVERSED url. Anything undecodable returns null so the
     * link is dropped instead of reaching the player as garbage.
     */
    private fun decodeDlToken(href: String): String? {
        val token = href.substringAfterLast('/').substringBefore('?')
        if (token.isBlank() || token.length < 8) return null
        val decoders = listOf(
            java.util.Base64.getDecoder(),
            java.util.Base64.getMimeDecoder(),
            java.util.Base64.getUrlDecoder(),
        )
        for (dec in decoders) {
            val raw = try {
                String(dec.decode(token), Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            val real = raw.reversed()
            if (real.startsWith("http")) return real
            // Already-plain URLs are occasionally stored un-reversed.
            if (raw.startsWith("http")) return raw
        }
        return null
    }

    /** One link per (quality, hoster) pair, highest resolution first, HubCloud before others. */
    private fun dedupe(links: List<HubLink>): List<HubLink> =
        links.distinctBy { "${it.quality}|${it.hoster}" }
            .sortedWith(compareByDescending<HubLink> {
                Regex("""(\d{3,4})[Pp]""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy { if (it.hoster == "HubCloud") 0 else 1 })

    private fun walk(root: Element, visit: (org.jsoup.nodes.Node) -> Unit) {
        for (node in root.childNodes()) {
            visit(node)
            if (node is Element) walk(node, visit)
        }
    }

    // ---- plumbing ----

    private fun get(client: OkHttpClient, url: String): String {
        val req = Request.Builder()
            .url(url)
            // FORCE_NETWORK â€” see ToonWorldScraper (cache-poisoning guard).
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .header("User-Agent", Api.USER_AGENT)
            .header("Accept", "application/json, text/html, */*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }
}
