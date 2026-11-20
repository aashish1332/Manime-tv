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
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * AnimeDubHindi (ADH) â€” the user's second source. A standard WordPress site
 * whose posts each cover one anime season and link an adhlinks episode page
 * carrying per-episode / per-quality hubcloud download links.
 *
 * The ADH domain rotates every few months (structure never changes), so the
 * base URL comes from [AppSettings] instead of a constant. The adhlinks domain
 * is never hardcoded either â€” it is read from post content each time.
 */
class AdhScraper(private val settings: AppSettings) {

    // ---- WordPress REST ----

    suspend fun latestPosts(perPage: Int = 40, client: OkHttpClient = Network.client): List<AdhInfo> =
        fetchPosts("/wp-json/wp/v2/posts?per_page=$perPage&orderby=modified&order=desc&_embed=1", client)

    /**
     * The FULL ADH catalog (~600 posts, few pages of 100). Matching DDA's
     * romaji titles against ADH is only reliable via the learned adhlinks
     * slugs, and those need every post on hand â€” the latest-40 window left
     * most older anime without their hub servers or English names.
     * Runs on the low-priority bulk client: this is background catalog work
     * and must never starve click-time requests on the fast client.
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

    /** Single post by id â€” used to recover ADH-only anime after process death. */
    suspend fun post(id: Int): AdhInfo? = try {
        val body = get(Network.client, "${settings.adhBase()}/wp-json/wp/v2/posts/$id?_embed=1")
        Network.json.decodeFromString(AdhPost.serializer(), body).toInfo()
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchPosts(pathAndQuery: String, client: OkHttpClient = Network.client): List<AdhInfo> = try {
        val body = get(client, settings.adhBase() + pathAndQuery)
        Network.json.decodeFromString(ListSerializer(AdhPost.serializer()), body)
            .mapNotNull { it.toInfo() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun AdhPost.toInfo(): AdhInfo? {
        // "Eren the Southpaw Season 1 Hindi, Tamil â€¦ WEB-DL Episodes Download"
        //  -> "Eren the Southpaw Season 1" (see Titles.clean).
        val cut = Titles.clean(title.rendered)
        if (cut.isBlank()) return null
        val (base, season) = Titles.baseAndSeason(cut)

        val doc = Jsoup.parse(content.rendered)
        val episodesUrl = doc.select("a[href*=/episode/]").firstOrNull()?.attr("href") ?: return null
        val synopsis = doc.select("p:contains(Synopsis)").firstOrNull()?.text()
            ?.removePrefix("Synopsis:")?.trim().orEmpty()
        val poster = embedded?.featuredMedia?.firstOrNull()?.let { m ->
            m.mediaDetails?.sizes?.get("large")?.sourceUrl?.ifBlank { null } ?: m.sourceUrl.ifBlank { null }
        } ?: doc.select("figure img[src]").firstOrNull()?.attr("src").orEmpty()

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

    // ---- adhlinks episode page ----

    /**
     * Parse an adhlinks page. TWO layouts exist side by side (the site is
     * migrating page by page â€” seen live 2026-07):
     *
     *  OLD: <h2>Episode: 01</h2> then <h4>s whose text nodes are quality
     *       labels ("480P x264 [Size: 80mb]") with hubcloud anchors inside.
     *  NEW: <div class="pro-ep-card"> with <h4 class="pro-ep-title">Episode: 01</h4>,
     *       <span class="pro-ep-quality">[480P x264]</span> and a
     *       <a href="â€¦/drive/â€¦">HubCloud</a> button per quality block.
     *
     * A single DOCUMENT-ORDER node walk covers both (and future re-skins):
     * a text node "Episode: N" starts a new episode, a text node carrying a
     * resolution updates the current quality, and every hub-cloud anchor
     * (drive path or "Hcloud" label â€” see [isDriveHref]) joins the current
     * episode+quality, so drive hosts/paths can rotate without breaking the
     * parser. A "Watch online" anchor points at the matching desidubanime
     * /anime/ page â€” our definitive cross-site key.
     * [client] defaults to the fast shared client; the slug sweep passes
     * [Network.bulk] so its thousands of pages stay off the fast queue.
     */
    fun episodePage(url: String, client: OkHttpClient = Network.client): AdhEpisodePage {
        val doc = try {
            Jsoup.parse(get(client, url), url)
        } catch (e: Exception) {
            return AdhEpisodePage(emptyList())
        }

        // The "Watch online" anchor points at our DDA /anime/ page. Prefer a
        // link on the configured DDA host (the site may add its own /anime/
        // nav links someday); fall back to any /anime/ link.
        val ddaHost = Api.HOST.removePrefix("https://").removePrefix("http://")
        val ddaSlug = doc.select("a[href*=/anime/]")
            .firstOrNull { it.attr("abs:href").contains(ddaHost) }
            ?.attr("href")
            ?.let { Regex("""/anime/([^/?#]+)""").find(it)?.groupValues?.get(1) }
            ?: doc.select("a[href*=/anime/]").firstOrNull()?.attr("href")
                ?.let { Regex("""/anime/([^/?#]+)""").find(it)?.groupValues?.get(1) }

        // STRICT episode matcher. The old pattern allowed a bare "E", which
        // matched inside words: "480P x264 [Size: 69mb]" matched "e: 69" and
        // turned the FILE SIZE into the current episode number â€” real episodes
        // ended up with no links while "zombie" cards (Episode 69, 116, â€¦)
        // carried them. \b + requiring "Ep"/"Episode" makes that impossible.
        val epRx = Regex("""\bEp(?:isode)?\s*:?\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
        val qualityRx = Regex("""\d{3,4}[Pp]""", RegexOption.IGNORE_CASE)

        val byNumber = LinkedHashMap<Int, ArrayList<HubLink>>()
        var current = -1
        var quality = ""
        doc.body()?.let { body ->
            walk(body) { node ->
                when (node) {
                    is TextNode -> {
                        val t = node.text().trim()
                        if (t.isEmpty()) return@walk
                        val ep = epRx.find(t)
                        when {
                            // "Episode: 01" â€” but never the page headline
                            // ("â€¦ Episodes Download Links" has no digits after).
                            ep != null && t.length < 40 -> {
                                current = ep.groupValues[1].toIntOrNull() ?: current
                                quality = ""
                            }
                            qualityRx.containsMatchIn(t) && t.length < 80 ->
                                quality = Hosters.normalizeQuality(t)
                        }
                    }
                    is Element -> if (node.tagName() == "a" && current > 0) {
                        val href = node.attr("abs:href").ifBlank { node.attr("href") }
                        // EVERY hoster button is a file link â€” HubCloud (/drive/),
                        // GDFlix / FilePress / FileBee (/file/). Collect them all,
                        // tagged with their hoster so the player can label them and
                        // route HubCloud to the fast plain-HTTP resolver. The
                        // adhlinks site's own anchors (re.php redirects, MEGA
                        // redirect.php) never match Hosters.isFileLink and stay out.
                        val hoster = Hosters.detect(href)
                        if (hoster != null) {
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
                                        driveUrl = href,
                                        hoster = hoster,
                                        source = me.desidubanime.tv.data.model.LinkSource.ADH,
                                    ),
                                )
                        }
                    }
                }
            }
        }
        val episodes = byNumber.mapNotNull { (num, links) ->
            if (links.isEmpty()) null else HubEpisode(number = num, links = dedupe(links))
        }
        // Debug: Log what was found
        me.desidubanime.tv.data.DebugLog.log("ADH-scrape", "Found ${episodes.size} episodes with links from ${url.substringAfterLast("/")}")
        return AdhEpisodePage(episodes = episodes.sortedBy { it.number }, ddaSlug = ddaSlug)
    }

    /** One link per (quality, hoster) pair, highest resolution first, HubCloud before others. */
    private fun dedupe(links: List<HubLink>): List<HubLink> =
        links.distinctBy { "${it.quality}|${it.hoster}" }
            .sortedWith(compareByDescending<HubLink> {
                Regex("""(\d{3,4})P""", RegexOption.IGNORE_CASE).find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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
            // FORCE_NETWORK: a cached body captured by an older build must
            // never shadow today's page (see ToonWorldScraper for the full
            // cache-poisoning story).
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

