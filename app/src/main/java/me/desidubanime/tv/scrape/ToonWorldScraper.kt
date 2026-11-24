package me.desidubanime.tv.scrape

import me.desidubanime.tv.data.DebugLog
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.api.Network
import me.desidubanime.tv.data.model.HubLink
import me.desidubanime.tv.data.model.LinkSource
import me.desidubanime.tv.data.model.TwEpisodePage
import me.desidubanime.tv.data.model.TwInfo
import me.desidubanime.tv.data.model.TwPost
import me.desidubanime.tv.data.model.TwEpisode
import me.desidubanime.tv.data.settings.AppSettings
import me.desidubanime.tv.util.Titles
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * ToonWorld4All (T4A) â€” fourth source. WordPress site with episode pages
 * carrying hubcloud/GDFlix/FilePress/etc links. Structure similar to ADH.
 *
 * Domain rotates, so base URL comes from [AppSettings].
 */
class ToonWorldScraper(private val settings: AppSettings) {

    // ---- WordPress REST ----

    suspend fun latestPosts(perPage: Int = 40, client: OkHttpClient = Network.client): List<TwInfo> =
        fetchPosts("/wp-json/wp/v2/posts?per_page=$perPage&orderby=modified&order=desc&_embed=1", client)

    /**
     * The FULL T4A catalog. Matching DDA's romaji titles against T4A is only
     * reliable via the learned episode-page slugs, and those need every post
     * on hand. Runs on the low-priority bulk client.
     */
    suspend fun allPosts(): List<TwInfo> {
        val out = ArrayList<TwInfo>()
        for (page in 1..8) {
            val batch = fetchPosts("/wp-json/wp/v2/posts?per_page=100&page=$page&orderby=modified&order=desc&_embed=1", Network.bulk)
            out += batch
            if (batch.size < 100) break
        }
        return out
    }

    suspend fun search(query: String): List<TwInfo> =
        fetchPosts("/wp-json/wp/v2/posts?search=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=20&_embed=1")

    /** Single post by id â€” used to recover T4A-only anime after process death. */
    suspend fun post(id: Int): TwInfo? = try {
        val body = get(Network.client, "${settings.twBase()}/wp-json/wp/v2/posts/$id?_embed=1")
        Network.json.decodeFromString(TwPost.serializer(), body).toInfo()
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchPosts(pathAndQuery: String, client: OkHttpClient = Network.client): List<TwInfo> = try {
        val body = get(client, settings.twBase() + pathAndQuery)
        Network.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TwPost.serializer()), body)
            .mapNotNull { it.toInfo() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun TwPost.toInfo(): TwInfo? {
        val cut = Titles.clean(title.rendered)
        if (cut.isBlank()) return null
        val (base, season) = Titles.baseAndSeason(cut)

        val doc = Jsoup.parse(content.rendered)
        // T4A now publishes ONE PAGE PER EPISODE ("/episode/<slug>-<season>x<ep>")
        // and lists every one of them in the post content. Collect ALL of them
        // with their numbers â€” taking only the first link silently yielded a
        // single (or zero) episode for every T4A-only anime.
        val urls = LinkedHashMap<Int, String>()
        val sxeRx = Regex("""-(\d+)x(\d+)(?:[/?#].*)?$""")
        val epSuffixRx = Regex("""-episode-0*(\d{1,4})(?:[/?#].*)?$""")
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            // Movies use /movie/<slug> pages; series use /episode/<slug>-<s>x<n>.
            if (!href.contains("/episode/") && !href.contains("/movie/")) continue
            val norm = href.substringBefore('#')
            val num = sxeRx.find(norm)?.groupValues?.get(2)?.toIntOrNull()
                ?: epSuffixRx.find(norm)?.groupValues?.get(1)?.toIntOrNull()
                ?: (if (norm.contains("/movie/")) 1 else 0)
                ?: continue
            if (num <= 0) continue
            urls.putIfAbsent(num, norm)
        }
        if (urls.isEmpty()) return null
        val firstUrl = urls.entries.minByOrNull { it.key }?.value ?: return null

        val synopsis = doc.select("p:contains(Synopsis)").firstOrNull()?.text()
            ?.removePrefix("Synopsis:")?.trim().orEmpty()
        val poster = embedded?.featuredMedia?.firstOrNull()?.let { m ->
            m.mediaDetails?.sizes?.get("large")?.sourceUrl?.ifBlank { null } ?: m.sourceUrl.ifBlank { null }
        } ?: doc.select("figure img[src]").firstOrNull()?.let { it.attr("abs:src").ifBlank { it.attr("src") } }?.ifBlank { null }
            ?: doc.select("img[src]").firstOrNull()?.attr("abs:src")?.ifBlank { null }
            ?: doc.select("meta[property=og:image]").firstOrNull()?.attr("content")?.ifBlank { null }
            .orEmpty()

        return TwInfo(
            postId = id,
            slug = slug,
            link = link,
            date = date,
            modified = modified,
            titleBase = base,
            season = season,
            posterUrl = poster,
            synopsis = synopsis,
            episodesUrl = firstUrl,
            episodeUrls = urls,
        )
    }

    // ---- Episode page parsing ----

    /**
     * Parse a T4A episode page.
     *
     * LIVE LAYOUT (2026-08): each episode has its own React-rendered page
     * ("/episode/<slug>-<s>x<n>") containing one CARD PER HOSTER:
     *     <div class="â€¦ relative â€¦">
     *       <h4>HubCloud</h4> <p>hubcloud.cx</p>
     *       <a href="/redirect/<64-hex-token>">DOWNLOAD</a>
     *     </div>
     * grouped under quality headings ("480p x264", "720p HEVC 10bit", â€¦).
     * The /redirect/ token is OPAQUE â€” the real drive URL is minted by the
     * site's worker at click time (see FileHostResolver's __PROPS__ step),
     * so we carry the absolute redirect URL plus the labelled hoster and
     * let the resolver unwrap it later.
     *
     * A legacy combined listing (several "Episode N" blocks with direct
     * file links on ONE page) is still handled for older posts.
     */
    fun episodePage(url: String, client: OkHttpClient = Network.client): TwEpisodePage {
        val html = try {
            get(client, url)
        } catch (e: Exception) {
            return TwEpisodePage(emptyList())
        }

        val sxe = Regex("""-(\d+)x(\d+)(?:[/?#].*)?$""").find(url.substringBefore('#'))
        // /movie/<slug> pages are single-file (movies), same __PROPS__ shape.
        val singleNumber = sxe?.groupValues?.get(2)?.toIntOrNull()
            ?: (if (url.contains("/movie/", ignoreCase = true)) 1 else null)

        // ---- preferred: the page embeds ALL its data as window.__PROPS__ ----
        if (singleNumber != null && singleNumber > 0) {
            val hasProps = html.contains("__PROPS__")
            val fromJson = propsEpisodes(html, url, singleNumber)
            if (fromJson.isNotEmpty()) {
                DebugLog.log("T4A-scrape", "ep $singleNumber: ${fromJson.first().links.size} links (__PROPS__) ${url.substringAfterLast("/")}")
                return TwEpisodePage(episodes = fromJson, ddaSlug = null)
            }
            // Diagnose on-device failures: was the JSON there at all?
            // (cache-poisoned body / CF challenge / shape change)
            DebugLog.log(
                "T4A-scrape",
                "ep $singleNumber: __PROPS__ missing/unparsed (len=${html.length}, hasProps=$hasProps) - DOM fallback",
            )
            // React DOM fallback (JSON shape changed / not embedded).
            val fromDom = domEpisode(html, url, singleNumber)
            if (fromDom.isNotEmpty()) {
                DebugLog.log("T4A-scrape", "ep $singleNumber: ${fromDom.first().links.size} links (DOM fallback)")
                return TwEpisodePage(episodes = fromDom, ddaSlug = null)
            }
            return TwEpisodePage(emptyList(), ddaSlug = null)
        }

        // ---- legacy combined listing: document-order walk ----
        return legacyListing(html, url)
    }

    /**
     * Parse `window.__PROPS__ = {â€¦}` out of a T4A episode page and map its
     * files[] groups onto [TwEpisode] links:
     *     files[]: { resolution, codec, readable:{codec:"720p x264", size:"178.88 MB"},
     *                files:[{host:"HubCloud", link:"/redirect/<hex>", short:"hubcloud.cx"}] }
     * The /redirect/ tokens stay opaque â€” FileHostResolver unwraps them at
     * click time (their own __PROPS__ carries domain+hidden of the real URL).
     */
    private fun propsEpisodes(html: String, url: String, number: Int): List<TwEpisode> {
        val raw = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            // RELAXED fallback: trailing content after the JSON object breaks
            // the strict pattern on some responses.
            ?: Regex("""window\.__PROPS__\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
        // REMOTE-DIAG: parse fail hone par site ka jawab dekho (log se).
        if (raw == null) {
            val i = html.indexOf("__PROPS__")
            me.desidubanime.tv.data.DebugLog.log(
                "T4A-scrape",
                if (i >= 0) "PROPS-SNIPPET: " + html.substring(i, minOf(html.length, i + 220)).replace(Regex("\\s+"), " ")
                else "NO __PROPS__ AT ALL (len=${html.length}) — challenge/different page?"
            )
        }
        val root = try {
            Network.json.parseToJsonElement(raw ?: return emptyList()).jsonObject
        } catch (e: Exception) {
            return emptyList()
        }
        // {"data":{"error":â€¦,"data":{"metadata":{â€¦},"files":[â€¦],"streams":[â€¦]}}}
        val inner = root["data"]?.jsonObject?.get("data")?.jsonObject ?: return emptyList()
        val groups = inner["encodes"]?.jsonArray ?: return emptyList()
        val origin = originOf(url)

        val links = ArrayList<HubLink>()
        for (g in groups) {
            val go = g.jsonObject
            val readable = go["readable"]?.jsonObject
            val codec = readable?.get("codec")?.jsonPrimitive?.content.orEmpty()
            val size = readable?.get("size")?.jsonPrimitive?.content.orEmpty()
            val quality = listOf(codec, size).filter { it.isNotBlank() }
                .joinToString(" - ")
                .ifBlank { go["resolution"]?.jsonPrimitive?.content.orEmpty() }
                .ifBlank { "Default" }
            val fs = go["files"]?.jsonArray ?: continue
            for (f in fs) {
                val fo = f.jsonObject
                val link = fo["link"]?.jsonPrimitive?.content ?: continue
                val abs = when {
                    link.startsWith("http") -> link
                    link.startsWith("/") -> origin + link
                    else -> continue
                }
                val hoster = canonicalHoster(
                    fo["host"]?.jsonPrimitive?.content.orEmpty(),
                    fo["short"]?.jsonPrimitive?.content.orEmpty(),
                ) ?: continue
                links.add(HubLink(quality = quality, driveUrl = abs, hoster = hoster, source = LinkSource.TW))
            }
        }
        if (links.isEmpty()) return emptyList()
        return listOf(TwEpisode(number = number, links = dedupe(links)))
    }

    /**
     * DOM fallback for a single-episode page: quality headings ("480p x264")
     * followed by per-hoster cards whose anchor is an opaque /redirect/ token;
     * the card's own h4/p carry the hoster name and domain.
     */
    private fun domEpisode(html: String, url: String, number: Int): List<TwEpisode> {
        val doc = Jsoup.parse(html, url)
        val qualityRx = Regex("""^\d{3,4}[Pp]\b""")
        val links = ArrayList<HubLink>()
        val headingsSeen = LinkedHashSet<String>()
        var quality = ""
        for (el in doc.select("h1,h2,h3,h4,h5,a[href]")) {
            when (el.tagName()) {
                "h1", "h2", "h3", "h4", "h5" -> {
                    val t = el.text().trim()
                    // Quality headings are short; page/post titles that merely
                    // CONTAIN a resolution are much longer or carry boilerplate.
                    if (t.length < 40 && !t.contains("download", true) && !t.contains("|")) {
                        qualityRx.find(t)?.let { m ->
                            if (m.value.length >= 3) {
                                quality = Hosters.normalizeQuality(t)
                                headingsSeen.add(quality)
                            }
                        }
                    }
                }
                else -> {
                    val href = el.attr("abs:href").ifBlank { el.attr("href") }
                    twLink(href, el, quality)?.let { links.add(it) }
                }
            }
        }
        if (links.isEmpty()) return emptyList()
        // AMBIGUITY GUARD: some layouts render ALL quality headings together
        // as a TAB STRIP before the cards, so every card inherits the LAST
        // tab's label â€” confidently wrong. When several distinct qualities
        // were seen yet every link ended up identical, the association is
        // meaningless; label them "Default" instead of lying.
        val finalLinks: List<HubLink> =
            if (headingsSeen.size > 1 && links.all { it.quality == links.first().quality }) {
                links.map { it.copy(quality = "Default") }
            } else {
                links
            }
        return listOf(TwEpisode(number = number, links = dedupe(finalLinks)))
    }

    /**
     * Legacy combined listing (older posts): several "Episode N" blocks with
     * direct file links on ONE page, walked in document order. Strict episode
     * matcher â€” see AdhScraper for why a bare "E" minted zombie episodes.
     */
    private fun legacyListing(html: String, url: String): TwEpisodePage {
        val doc = Jsoup.parse(html, url)
        val epRx = Regex("""\bEp(?:isode)?\s*:?\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
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
                            ep != null && t.length < 40 -> {
                                current = ep.groupValues[1].toIntOrNull() ?: current
                                quality = ""
                            }
                            t.length < 80 && Regex("""\d{3,4}[Pp]""").containsMatchIn(t) ->
                                quality = Hosters.normalizeQuality(t)
                        }
                    }
                    is Element -> if (node.tagName() == "a" && current > 0) {
                        val href = node.attr("abs:href").ifBlank { node.attr("href") }
                        twLink(href, node, quality)?.let {
                            byNumber.getOrPut(current) { ArrayList() }.add(it)
                        }
                    }
                }
            }
        }
        val episodes = byNumber.mapNotNull { (num, ls) ->
            if (ls.isEmpty()) null else TwEpisode(number = num, links = dedupe(ls))
        }
        DebugLog.log("T4A-scrape", "Found ${episodes.size} episodes with links from ${url.substringAfterLast("/")}")
        return TwEpisodePage(episodes = episodes.sortedBy { it.number }, ddaSlug = null)
    }

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: ""

    /**
     * Fresh unlock candidate for the TW4A QR flow: fetch [episodePageUrl]
     * and pull the FIRST opaque /redirect/<hex> token out of its embedded
     * __PROPS__ JSON (raw-HTML scan fallback). Absolute URL returned; the
     * token is minted per-request, so callers must use it immediately.
     */
    fun firstRedirectToken(episodePageUrl: String, client: OkHttpClient = Network.client): String? {
        val html = try {
            get(client, episodePageUrl)
        } catch (e: Exception) {
            return null
        }
        val haystack = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: html
        val hex = Regex(""""link"\s*:\s*"/redirect/([a-f0-9]{32,})"""").find(haystack)?.groupValues?.get(1)
            ?: Regex("""/redirect/([a-f0-9]{32,})""").find(haystack)?.groupValues?.get(1)
            ?: return null
        val origin = originOf(episodePageUrl).takeIf { it.isNotBlank() } ?: return null
        return "$origin/redirect/$hex"
    }

    /**
     * Build a [HubLink] from an anchor on a T4A page, or null to skip it.
     * Handles BOTH the opaque "/redirect/<hex>" tokens (hoster label taken
     * from the card's own heading/domain) and plain direct drive URLs.
     */
    private fun twLink(href: String, anchor: Element, quality: String): HubLink? {
        if (href.isBlank() || !href.startsWith("http")) return null
        if (href.contains("/redirect/", ignoreCase = true)) {
            val (label, domain) = hosterLabel(anchor)
            val hoster = canonicalHoster(label, domain) ?: return null
            return HubLink(
                quality = quality.ifBlank { "Default" },
                driveUrl = href,
                hoster = hoster,
                source = LinkSource.TW,
            )
        }
        val hoster = Hosters.detect(href) ?: return null
        // Prefer a quality found in the anchor itself over the running heading.
        val qRx = Regex("""\d{3,4}[Pp]""")
        val ownQuality = listOf(anchor.text(), anchor.attr("title"))
            .firstOrNull { qRx.containsMatchIn(it) }
            ?.let { Hosters.normalizeQuality(it) }
        return HubLink(
            quality = ownQuality ?: quality.ifBlank { "Default" },
            driveUrl = href,
            hoster = hoster,
            source = LinkSource.TW,
        )
    }

    /**
     * The card wrapping this anchor carries the hoster identity as a short
     * heading ("HubCloud") and often its domain ("hubcloud.cx") right beside
     * it. Walk up a couple of levels so minor re-skins don't break us.
     */
    private fun hosterLabel(anchor: Element): Pair<String, String> {
        var node: Element? = anchor
        repeat(4) {
            node = node?.parent() ?: return@repeat
            val cur = node ?: return@repeat
            val h = cur.selectFirst("h3,h4,h5") ?: return@repeat
            val name = h.text().trim()
            if (name.isEmpty() || name.length > 30) return@repeat
            if (Regex("""\d{3,4}[Pp]""").containsMatchIn(name)) return@repeat
            val domain = cur.selectFirst("p")?.text()?.trim().orEmpty()
            return name to domain
        }
        return "" to ""
    }

    /** Map a card label/domain to our canonical hoster names. */
    private fun canonicalHoster(label: String, domain: String): String? {
        val candidate = listOf(label, domain).firstOrNull { it.isNotBlank() } ?: return null
        val probe = if (candidate.contains('.')) "https://$candidate/" else "/file/$candidate"
        return Hosters.detect(probe) ?: candidate.replaceFirstChar { it.uppercase() }
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
        val req = okhttp3.Request.Builder()
            .url(url)
            // ALWAYS hit the network for scrape fetches. The shared client's
            // disk cache kept serving bodies captured by OLDER app versions
            // (pre-referer-fix runs got challenge/empty pages) â€” an
            // `adb install -r` keeps that cache, so every new build kept
            // re-reading the poisoned entry and __PROPS__ was "missing".
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

