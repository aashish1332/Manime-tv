package me.desidubanime.tv.scrape

import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Playable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Resolves a hubcloud /drive/ page to a direct video URL — pure OkHttp, no
 * WebView needed (verified: both hops are plain HTML):
 *
 *   1. drive page JS carries   var url = 'https://gamerxyt.com/hubcloud.php?…'
 *   2. that page has the final buttons:
 *        #fsl        -> signed R2/S3 direct link  (best: plain file host)
 *        pixel/gpdl  -> 10Gbps redirectors (302 chain to the real host)
 *        pixeldrain  -> /u/<id> page; /api/file/<id> is the raw file
 *
 * Old uploads rot: the pixeldrain copy gets deleted (404) and the redirector
 * 500s while the buttons still render (seen live: Slime S1 -> black screen).
 * So every candidate is probed with a 1-byte ranged GET and only a URL that
 * actually serves bytes is handed to the player; null means "all dead, try
 * another server".
 *
 * pixel.hubcloud.cx redirector: 302 -> pixel.<id>.workers.dev -> 302 ->
 * gamerxyt.com/dl.php?link=<real-url>. The dl.php page is HTML with the real
 * link as a JS variable OR the HTTP redirect itself points at the file.
 * Some dead episodes still resolve through this path when the direct worker
 * links have expired (403) — HubCloud sometimes keeps the pixel redirector
 * alive to serve the file. So when the "dead" worker links 403 but pixel
 * is reachable, we follow it to the end and probe whatever it lands on.
 */
object HubCloudResolver {

    /**
     * HubCloud's own client — the shared client pins Referer to the DDA site via
     * an interceptor, and signed R2 / worker URLs reject a foreign referer with
     * 403. Using the shared client for the probe was the root cause of the FSL
     * server's 403 — every signed file URL got the DDA site as Referer.
     *
     * Cookie jar added: hubcloud/gamerxyt hops set cookies the later hops are
     * validated against (same session-state requirement as GDFlix).
     */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            val origin = Regex("""^(https?://[^/]+)""").find(req.url.toString())?.groupValues?.get(1)
            chain.proceed(
                req.newBuilder()
                    .header("User-Agent", Api.USER_AGENT)
                    .apply { origin?.let { header("Referer", "$it/") } }
                    .build(),
            )
        }
        .cookieJar(me.desidubanime.tv.scrape.SessionJar)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun resolve(driveUrl: String): Playable.Stream? {
        val drive = get(driveUrl) ?: return null
        // Three layouts seen live; the "#download" anchor is what CloudStream's
        // production extractor uses, so try it FIRST, then the legacy JS vars:
        //   1. <a id="download" href="…hubcloud.php…">   (current)
        //   2. var url = '…hubcloud.php…'                (/drive/ pages)
        //   3. href="…hubcloud.php…" anywhere            (/video/ pages)
        val doc = Jsoup.parse(drive, driveUrl)
        val downloadHref = doc.selectFirst("#download")
            ?.attr("abs:href")
            ?.takeIf { it.startsWith("http") }
        val hubUrl = downloadHref
            ?: Regex("""var\s+url\s*=\s*'([^']+)'""").find(drive)?.groupValues?.get(1)
            ?: Regex("""href="([^"]*hubcloud\.php[^"]*)"""", RegexOption.IGNORE_CASE)
                .find(drive)?.groupValues?.get(1)
            ?: return null
        val hub = get(hubUrl, referer = driveUrl) ?: return null
        val hubDoc = Jsoup.parse(hub, hubUrl)

        // Candidates in preference order. The real pixeldrain link is set by
        // page JS — var pxl = "https://…/u/ID" — while the static anchor
        // keeps a stale decoy id. Parse BOTH; the JS one outranks anchors.
        //
        // FSL (signed R2) and 10Gbps servers are often down (403/500).
        // PixelDrain is the most reliable fallback (1 MB/s but always works).
        // The pixel redirector sometimes outlives dead worker links.
        val candidates = buildList {
            // JS pxl variable — the authoritative pixeldrain link (often the
            // only one alive for old uploads like Tsukimichi S1).
            Regex("""var\s+pxl\s*=\s*"([^"]+)"""").find(hub)?.groupValues?.get(1)
                ?.let { add(pixeldrainApi(it)) }
            // FSL signed R2 link — best when it works (plain file, fast).
            hubDoc.selectFirst("a#fsl")?.attr("href")?.let { add(it) }
            // Pixel redirectors and other download buttons (on the HUB page).
            hubDoc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (!href.startsWith("http")) return@forEach
                when {
                    href.contains("pixel.") || href.contains("gpdl.") -> {
                        add(href)
                        resolvePixelRedirect(href)?.let { add(it) }
                    }
                    href.contains("pixeldrain") -> add(pixeldrainApi(href))
                }
            }
            // Buzz server: the button itself 403s, but GETting {link}/download
            // WITHOUT following redirects answers with an `hx-redirect`
            // HEADER carrying the final file (CloudStream's trick — verified
            // against their production extractor).
            hubDoc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http") &&
                    (a.text().contains("Buzz", ignoreCase = true) || href.contains("bzzhr"))
                ) {
                    buzzFinalLink(href)?.let { add(it) }
                }
            }
        }.distinct()

        // Probe each candidate; skip ZIP archives and error pages.
        val url = candidates.firstOrNull { servesVideo(it) && !isZip(it) } ?: return null
        return Playable.Stream(
            url = url,
            headers = buildMap {
                put("User-Agent", Api.USER_AGENT)
                // Set Referer to the hub page's origin, not the DDA site —
                // signed R2 URLs reject foreign referers.
                Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                    ?.let { put("Referer", "$it/") }
            },
            isHls = false,
        )
    }

    /** Client that stops at the FIRST response so custom headers are visible. */
    private val noRedirectClient by lazy {
        probeClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * bzzhr.co/<id> -> GET <link>/download without redirects; the response's
     * `hx-redirect` header IS the direct file link. Null when absent.
     */
    private fun buzzFinalLink(buzzUrl: String): String? = try {
        val req = Request.Builder()
            .url(buzzUrl.trimEnd('/') + "/download")
            .header("User-Agent", Api.USER_AGENT)
            .header("Referer", buzzUrl)
            .build()
        noRedirectClient.newCall(req).execute().use { resp ->
            resp.header("hx-redirect")?.takeIf { it.startsWith("http") }
        }
    } catch (e: Exception) {
        null
    }

    /** pixeldrain /u/<id> page url -> /api/file/<id> raw file url. */
    private fun pixeldrainApi(href: String): String =
        Regex("""pixeldrain\.[a-z]+/u/([A-Za-z0-9]+)""").find(href)
            ?.let { m -> href.replace("/u/${m.groupValues[1]}", "/api/file/${m.groupValues[1]}") }
            ?: href

    /**
     * Detect ZIP archives — AnimeDrive serves seasons as ZIP files which
     * ExoPlayer cannot stream. Content-Type may lie (application/octet-stream),
     * so check the URL too.
     */
    private fun isZip(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".zip") || u.contains(".zip?")
    }

    /**
     * pixel.hubcloud.cx 302s to another workers.dev, which 302s to
     * gamerxyt.com/dl.php?link=<real-file>. The dl.php page is HTML with the
     * real link as a JS `link` variable OR the last redirect lands on the
     * file itself. We follow the chain — workers.dev links from old uploads
     * 403 immediately, so this is the last-chance fallback for those episodes.
     */
    private fun resolvePixelRedirect(pixelUrl: String): String? {
        try {
            var current = pixelUrl
            // Follow up to 4 hops — the chain can grow when HubCloud adds
            // more redirect layers. The old 2-hop limit missed the final file.
            for (hop in 0..3) {
                val req = Request.Builder()
                    .url(current)
                    .header("User-Agent", Api.USER_AGENT)
                    // hubcloud.cx no longer resolves; hubcloud.mov is the live
                    // domain (2026-11). Derive it from the hop itself so the
                    // next rotation does not need a code change.
                    .header(
                        "Referer",
                        if (hop == 0) {
                            (Regex("""^(https?://[^/]+)""").find(pixelUrl)?.groupValues?.get(1)
                                ?: "https://hubcloud.mov") + "/"
                        } else current,
                    )
                    .head()
                    .build()
                val next = probeClient.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        301, 302, 303, 307, 308 -> resp.header("Location")
                        else -> null
                    }
                } ?: break
                if (next == current) break // redirect loop guard
                current = next

                // gamerxyt's dl.php is a WRAPPER: its ?link=<signed-url> query
                // carries the REAL file (verified live: extracted param answers
                // video/mkv with the full movie size, while dl.php itself
                // serves a tiny HTML shell). Decode ONLY when percent-encoded —
                // URLDecoder would otherwise turn '+' chars in signed URLs
                // into spaces (CloudStream uses the raw value for this reason).
                if (current.contains("gamerxyt.com/dl.php")) {
                    val param = Regex("""[?&]link=([^&]+)""").find(current)?.groupValues?.get(1)
                    val real = param?.let { p ->
                        if (p.contains('%')) {
                            runCatching { java.net.URLDecoder.decode(p, "UTF-8") }.getOrDefault(p)
                        } else p
                    }
                    if (real != null && real.startsWith("http")) return real
                    return current
                }
                // Legacy hubcloud.php hop still carries the real link in JS.
                if (current.contains("hubcloud.php")) {
                    val page = get(current, referer = pixelUrl) ?: break
                    val extracted = Regex("""var\s+link\s*=\s*'([^']+)'""").find(page)?.groupValues?.get(1)
                        ?: Regex("""var\s+url\s*=\s*'([^']+)'""").find(page)?.groupValues?.get(1)
                        ?: Regex("""href="([^"]*(?:googlevideo|\.mkv|\.mp4|workers\.dev|pixeldrain)[^"]*)"""")
                            .find(page)?.groupValues?.get(1)
                    if (extracted != null) return extracted
                    break
                }
                // If the redirect itself is a video file, that's our answer.
                if (current.contains(".mkv") || current.contains(".mp4") || current.contains("pixeldrain")) {
                    return pixeldrainApi(current)
                }
            }
            // Return the last hop if it looks like a file URL.
            if (current.contains("dl.php")) {
                val param = Regex("""[?&]link=([^&]+)""").find(current)?.groupValues?.get(1)
                val real = param?.let { p ->
                    if (p.contains('%')) {
                        runCatching { java.net.URLDecoder.decode(p, "UTF-8") }.getOrDefault(p)
                    } else p
                }
                return real?.takeIf { it.startsWith("http") } ?: current
            }
            if (current.contains("workers.dev")) {
                val page = get(current, referer = pixelUrl)
                return Regex("""var\s+link\s*=\s*'([^']+)'""").find(page.orEmpty())?.groupValues?.get(1)
                    ?: Regex("""var\s+url\s*=\s*'([^']+)'""").find(page.orEmpty())?.groupValues?.get(1)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 1-byte ranged GET following redirects: alive when 2xx and the payload is
     * not an HTML page (dead links serve 404/500 or an error page).
     *
     * EXCEPTION (verified live): gamerxyt's dl.php streaming proxy answers a
     * ranged GET with 206 + `Content-Type: text/html` while piping the actual
     * video through. A 206 whose Content-Range total is movie-sized IS the
     * file — accept it despite the lying content type.
     */
    private fun servesVideo(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Api.USER_AGENT)
            .header("Range", "bytes=0-1")
            .build()
        probeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val type = resp.header("Content-Type").orEmpty()
            if (!type.startsWith("text/html")) return@use true
            val total = resp.header("Content-Range")
                ?.substringAfter('/', "")?.toLongOrNull() ?: 0L
            total > MIN_STREAM_BYTES
        }
    } catch (e: Exception) {
        false
    }

    private const val MIN_STREAM_BYTES = 100_000L

    private fun get(url: String, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Api.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        probeClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
