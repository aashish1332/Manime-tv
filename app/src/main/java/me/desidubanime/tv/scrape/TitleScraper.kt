package me.desidubanime.tv.scrape

import me.desidubanime.tv.data.api.Network
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * The Kiranime theme renders every anime name twice — an English span and a
 * romaji span (toggled by the site's language switch):
 *   <span class="group-data-[language=jp]/body:hidden ...">Eren the Southpaw</span>
 *   <span class="group-data-[language=en]/body:hidden ...">Hidarikiki no Eren</span>
 * We scrape the English one; the WP REST API only carries the romaji title.
 *
 * IMPORTANT: the slug in /watch/{slug}-episode-N/ URLs is NOT the same as the
 * REST API slug (e.g. REST: "maj-kun-wa-mie0vrzgunqcs" vs watch: "majutsushi-
 * kunon-wa-mieteiru"). The /anime/{slug}/ URL and the ld+json "url" field
 * both use the REST API slug, so we extract from those instead.
 */
object TitleScraper {

    /**
     * English title from an anime detail page. Two strategies:
     * 1. The h1 span carrying class "anime" (the language-toggle span).
     * 2. Fallback: the <dt>English</dt><dd>...</dd> info field.
     */
    fun englishFromAnimePage(html: String): String? {
        // Strategy 1: h1 language span (class includes "anime" on detail pages).
        Regex("""language=jp]/body:hidden anime">([^<]+)<""")
            .find(html)?.groupValues?.get(1)?.let { clean(it) }
            ?.takeIf { it.isNotBlank() }?.let { return it }

        // Strategy 2: <dt>English</dt><dd>...</dd> info field.
        val doc = Jsoup.parse(html)
        val dts = doc.select("dt")
        for (dt in dts) {
            if (dt.text().trim().equals("English", ignoreCase = true)) {
                val dd = dt.nextElementSibling()
                if (dd != null && dd.tagName() == "dd") {
                    val en = clean(dd.text())
                    if (en.isNotBlank() && !en.equals("N/A", ignoreCase = true)) return en
                }
            }
        }
        return null
    }

    /**
     * slug -> English title pairs from any listing page. Each anime card has:
     *  - An ld+json script whose "url" field uses the REST API slug
     *    (e.g. /anime/maj-kun-wa-mie0vrzgunqcs/) — this is the CORRECT slug,
     *    NOT the /watch/{slug}-episode-N/ slug which differs.
     *  - Two language spans: language=jp (English, shown when site=EN) and
     *    language=en (romaji, shown when site=JP). The ld+json "name" field
     *    is the ROMAJI name, so we must take the English from the span.
     */
    fun pairsFromArchive(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        val doc = Jsoup.parse(html)

        for (article in doc.select("article.anime-card")) {
            // 1) Slug from the ld+json "url" field (REST API slug).
            val script = article.selectFirst("script[type=application/ld+json]")
            val slug = script?.data()?.let {
                Regex(""""url"\s*:\s*"[^"]*/anime/([a-z0-9-]+)/?"""").find(it)?.groupValues?.get(1)
            } ?: continue

            // 2) English name from the language=jp span (first span in h3 > a).
            //    The language=jp span is hidden when site=JP, shown when site=EN,
            //    so it carries the English title. ld+json "name" is romaji — don't use it.
            val enSpan = article.selectFirst("h3 a span[class*=language=jp]")
                ?: article.selectFirst("span[class*=language=jp]")
            val en = enSpan?.text()?.let { clean(it) }
            if (slug.isNotBlank() && !en.isNullOrBlank()) {
                out.putIfAbsent(slug, en)
            }
        }

        // Fallback: regex on /anime/{slug}/ in ld+json + nearby language=jp span.
        // Catches cards whose structure differs slightly.
        if (out.isEmpty()) {
            val rx = Regex(
                """/anime/([a-z0-9-]+)/[^}]*?"name"|"name"\s*:\s*"[^"]*"[^}]*?/anime/([a-z0-9-]+)/""",
            )
            // Simpler: just find all /anime/{slug}/ in ld+json and pair with
            // the nearest language=jp span in the same article block.
            val slugRx = Regex(""""url"\s*:\s*"[^"]*/anime/([a-z0-9-]+)/?"""")
            val enRx = Regex("""language=jp]/body:hidden[^>]*>([^<]+)<""")
            val slugs = slugRx.findAll(html).map { it.groupValues[1] }.toList()
            val ens = enRx.findAll(html).map { clean(it.groupValues[1]) }.toList()
            for (i in slugs.indices) {
                if (i < ens.size && slugs[i].isNotBlank() && ens[i].isNotBlank()) {
                    out.putIfAbsent(slugs[i], ens[i])
                }
            }
        }

        return out
    }

    fun fetchPage(url: String, client: OkHttpClient = Network.client): String? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun clean(raw: String): String =
        Parser.unescapeEntities(raw, false).replace(Regex("\\s+"), " ").trim()
}