package me.desidubanime.tv.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.desidubanime.tv.data.DebugLog
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Playable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Minimal per-host cookie store. JavaNetCookieJar is unavailable across all
 * OkHttp versions this app may resolve, and a hand-rolled jar avoids that
 * dependency question entirely: cookies are keyed by HOST (Cloudflare's
 * cf_clearance and the hosts' own PHPSESSID are host-scoped in practice).
 */
object SessionJar : okhttp3.CookieJar {
    private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
        store[url.host]?.let { synchronized(it) { it.toList() } } ?: emptyList()
}

/**
 * Resolves a file-host page (GDFlix, FileBee, FilePress, HubCloud, …) to a
 * DIRECT video URL over plain HTTP, so playback goes through Media3 natively.
 * That matters beyond "no website inside the player": intro/outro skip (MKV
 * chapters), the quality / audio / subtitle track menus and resume all read
 * from the native player and simply don't exist for a WebView embed.
 *
 * FORMAT-AGNOSTIC BY DESIGN. These hosts rotate domains and re-skin often, so
 * nothing here keys off a CSS class or a fixed layout. Instead:
 *
 *  1. HARVEST  — pull every URL the page mentions: anchors, form actions,
 *                data-* attributes, meta-refresh, and JS string literals
 *                (including ones split across concatenation or escaped as
 *                \/ and %2F). Shortener wrappers are unwrapped (?url=…).
 *  2. SCORE    — rank by how much each URL looks like a file rather than a
 *                page: real video extension > storage/CDN/worker host >
 *                download-ish path > everything else. Junk (social, ads,
 *                login, report) is dropped outright.
 *  3. PROVE    — probe the best candidates CONCURRENTLY with a 1-byte ranged
 *                GET on a short-timeout client. A URL is accepted only if it
 *                serves non-HTML bytes, so a wrong guess can never reach the
 *                player as a web page.
 *  4. DESCEND  — a candidate that answers with HTML is an intermediate page;
 *                recurse into it, breadth-first, up to [MAX_DEPTH].
 *
 * Everything is bounded by [budgetMs]: dead mirrors used to be walked one at a
 * time, each waiting out its own socket timeout, and the player sat on
 * "loading" forever. Now the whole resolve gives up and lets the caller move
 * to the next server.
 *
 * Returns null when nothing on the page serves bytes.
 */
object FileHostResolver {

    /**
     * Escape hatch for pages plain HTTP cannot read. gdflix.dev answers OkHttp
     * with `403 / cf-mitigated: challenge` (verified live) — a Cloudflare JS
     * challenge that only a real browser engine can clear. The implementation
     * lives in the player package because it needs a WebView + Context.
     */
    interface Browser {
        suspend fun html(url: String, timeoutMs: Long): String?

        /**
         * Click the page's own download controls and capture the file URL the
         * browser then goes after. These hosts MINT the link in JS at click
         * time, so it is not in the HTML for [html] + probing to find — this is
         * the path that actually resolves GDFlix/FilePress.
         */
        suspend fun directUrl(url: String, timeoutMs: Long): String?

        fun cookiesFor(url: String): String?
    }

    /** Extensions that are a real media file. */
    private val VIDEO_EXT = listOf(".mkv", ".mp4", ".webm", ".m3u8", ".mpd", ".avi", ".mov", ".ts")

    /** Hosts/paths that typically ARE the file (or a 302 straight to it). */
    private val STRONG_HINTS = listOf(
        "workers.dev", "r2.dev", "cloudflarestorage", "backblazeb2", "b-cdn.net",
        "digitaloceanspaces", "storage.googleapis", "amazonaws", "/api/file/",
        "pixeldrain", "gpdl", "pixel.", "fsl", "gdflix.", "driveleech", "drivebot", "busycdn.xyz", "fpgo.xyz", "dotflix", "googleusercontent",
        // Verified live 2026-11: the CDN hosts these backends actually hand out.
        "instadl.rest", "fastdl-one.pages.dev", "busycdn.instadl", "bhfastdrive",
        "filepress.lat", "filebee.live", "dotflix.shop",
        "filepress", "fpgo", "filebee", "filepress.baby", "filemoon", "streamwish", "doodstream",
        "blogspot.com", "googlevideo", "ggpht", "bzzhr",
    )

    /** Weaker "this is the download button" signals. */
    private val WEAK_HINTS = listOf(
        "/dl/", "/dl?", "direct", "instant", "download", "server=", "type=direct",
        "type=instant", "cloud", "mirror", "resume", "worker", "generate", "file/",
    )

    /** Never candidates, however they are linked. */
    private val JUNK = listOf(
        "t.me", "telegram", "whatsapp", "facebook", "twitter", "x.com", "youtube",
        "instagram", "discord", "javascript:", "mailto:", "/login", "/signup",
        "/register", "/report", "privacy", "terms", "disclaimer", "dmca", "about",
        "contact", "shrinkme", "adblock", "/tos", ".css", ".js", ".png", ".jpg",
        ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".ttf",
        // Boilerplate the GDFlix pages link on every view — probing these is
        // pure budget waste (seen live: copyright-policy probed three times).
        "copyright", "policy", "/faq", "/blog", "/donate", "/premium",
        // TW4A ad-gateway hops — never candidates, always noise.
        "exe.io", "exeygo", "cdn-cgi/content",
    )

    /**
     * The byte-count slug these CDNs put in a video path
     * ("…/blob/xyz…bytes-309355430"). No extension, an unremarkable host, but
     * the count makes it unambiguous — this is the file itself. GDFlix's real
     * link was being scored below its own copyright page without this.
     */
    private val BYTES_SLUG = Regex("""bytes-\d+""")

    /**
     * Own client, built from scratch rather than reusing the shared one.
     *  - Short timeouts: the shared client waits 20-35s per call, which is
     *    right for an API request and completely wrong for probing a mirror
     *    that may simply be dead — with a dozen candidates that is minutes.
     *  - No shared header interceptor: that one pins `Referer` to the DDA site
     *    on every request, and signed file URLs (R2/workers/CDN) reject a
     *    foreign referer. Here the referer is the host's own origin.
     *  - No disk cache: these are one-shot, often signed, URLs.
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
        // SESSION COOKIES. GDFlix's flow is stateful: the 302 chain sets the
        // cookies the later API POSTs are validated against. Without a jar
        // every request looked like a fresh visitor and the minted links were
        // refused — this alone broke plain-HTTP GDFlix.
        .cookieJar(SessionJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Resolve [pageUrl] to a playable direct stream, or null.
     *
     * HubCloud keeps its dedicated two-hop resolver — its layout is known, so
     * the targeted path is faster and more reliable. The generic probe still
     * runs as a fallback when that returns nothing, since HubCloud re-skins too.
     */
    suspend fun resolve(
        pageUrl: String,
        hoster: String,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        browser: Browser? = null,
    ): Playable.Stream? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + budgetMs
        // A HOST ALREADY PROVEN UNREACHABLE MUST COST NOTHING. Without this the
        // first caller pays the origin probe (seconds) and every caller behind
        // it paid the fallbacks again; when several servers resolve in one
        // batch that stall is the batch's wall clock. [isDown] is the single
        // source of truth for that verdict, so this is the only check needed.
        if (isDown(hoster)) {
            DebugLog.log("resolve", "$hoster is marked down — failing out immediately")
            return@withContext null
        }
        DebugLog.log("resolve", "$hoster start budget=${budgetMs}ms ${short(pageUrl)}")
        DebugLog.log("resolve", "Browser available: ${browser != null}")
        // WRAPPER URLs NEVER REACH A HOST RESOLVER. AnimeDrive hands out
        // link.animedrive.me/dl/<base64> tokens and some mirrors hand out
        // ?url=<real> redirects; the host-specific paths below (GDFlix mint,
        // HubCloud two-hop, page crawl) only make sense on the REAL page, so
        // unwrap once, here, and recurse with the budget that is left.
        if (pageUrl.contains("link.animedrive.me/dl/", ignoreCase = true) ||
            Regex("""[?&](?:url|link|file|target)=""", RegexOption.IGNORE_CASE).containsMatchIn(pageUrl)
        ) {
            val real = unwrap(pageUrl)
            if (real != pageUrl && real.startsWith("http") && msLeft(deadline) >= 1_000L) {
                DebugLog.log("resolve", "unwrapped -> ${short(real)}")
                return@withContext resolve(real, hosterFromUrl(real, hoster), msLeft(deadline), browser)
            }
        }
        // ToonWorld4All wraps every link in an OPAQUE /redirect/<hex> token.
        // Its interstitial embeds the real target as JSON:
        //   window.__PROPS__ = {"destination":"https://exe.io/…"(ad),
        //                       "link":{"domain":"https://hubcloud.cx/video/","hidden":"<id>"}}
        // One plain GET, no browser, no crawl — try it before anything else.
        // (Only fires when the page actually carries that shape; harmless for
        // every other host.)
        //
        // That JSON is ONE plain GET away — no ad to watch, no QR to scan
        // (verified live against archive.toonworld4all.me).
        //
        // The card's printed label is NOT the authority: T4A prints "GDFlix"
        // on a row whose token may point anywhere. So the resolved URL is
        // re-submitted through this same entry point with the hoster derived
        // from THAT url, which routes it to the right dedicated resolver.
        // That re-dispatch is what removes the QR requirement: before it, the
        // token was only probed once and, when the guess wasn't directly
        // playable, control fell into the browser flow — the ad/QR wall.
        if (pageUrl.contains("/redirect/", ignoreCase = true)) {
            val target = redirectTargetOf(pageUrl)
            if (target != null && target.contains("mega.nz/", ignoreCase = true)) {
                // MEGA CANNOT BE RESOLVED THIS WAY, AND PRETENDING OTHERWISE
                // COSTS THE VIEWER A QR WALL. A T4A MEGA card carries no file
                // key, so domain+hidden builds a mega.nz page with no key and
                // no bytes behind it (verified live: the constructed link is
                // a "File on MEGA" shell and the file API answers ENOENT).
                DebugLog.log("resolve", "redirect -> MEGA (no key in token, unsupported)")
                return@withContext null
            }
            if (target != null && !target.contains("/redirect/", ignoreCase = true)) {
                DebugLog.log("resolve", "redirect -> ${short(target)} (direct, no ad/QR)")
                // SHARED DEADLINE. This used to hand the nested resolve the
                // FULL budget again, so one T4A token could burn 45s here and
                // another 45s below before the viewer saw anything.
                resolve(target, hosterFromUrl(target, hoster), msLeft(deadline), browser)?.let {
                    DebugLog.log("resolve", "redirect resolved -> ${short(it.url)}")
                    return@withContext it
                }
                // A generic pass over the REAL page still beats an ad wall.
                // Plain HTTP only: the browser hop here IS the QR flow, and it
                // must never be reached implicitly.
                crawl(target, deadline, browser = null)?.let { return@withContext it }
                // DEFINITIVE 404 = the upload is GONE, not ad-gated. Verified
                // live 2026-11: T4A's HubCloud tokens build
                // hubcloud.mov/drive/<id> and that URL answers "404 ! File Not
                // Found" for every card of every episode checked, while the
                // same host resolves ADH's ids fine. Nothing behind a deleted
                // file can be unlocked, so the player must not answer it with
                // the phone/QR ad wall — remember the verdict for that token.
                if (answersGone(target)) markTwDeadFile(pageUrl)
                DebugLog.log("resolve", "redirect target unresolved -> ${short(target)}")
                return@withContext null
            }
            DebugLog.log("resolve", "redirect __PROPS__ empty, falling through to generic crawl")
        }
        if (hoster.equals("HubCloud", ignoreCase = true)) {
            runCatching { HubCloudResolver.resolve(pageUrl) }.getOrNull()?.let {
                DebugLog.log("resolve", "HubCloud OK -> ${short(it.url)}")
                return@withContext it
            }
            DebugLog.log("resolve", "HubCloud resolver found nothing, trying generic")
        }
        // GDFlix has a known STATEFUL plain-HTTP flow (session cookies ->
        // instant/xtrocdn API or direct-button POST -> worker_url). Try it
        // before the generic crawl; the browser pass below remains as the
        // fallback when Cloudflare still gates the session.
        if (hoster.contains("dotflix", ignoreCase = true)) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) { dotflixResolve(pageUrl) }?.let {
                    DebugLog.log("resolve", "Dotflix OK -> ${short(it.url)}")
                    return@withContext it
                }
            } catch (e: Exception) {
                DebugLog.log("resolve", "Dotflix crashed: ${e.message}")
            }
        }
        if (hoster.contains("gdflix", ignoreCase = true)) {
            // NEWEST PROTOCOL FIRST (verified live 2026-11): gdflix.dev answers
            // plain HTTP with NO Cloudflare challenge (302 -> newN.gdflix.io,
            // 200 + 43KB of HTML). The page's own "instant" button POSTs
            //   action=instant + key + action_token  to  <host>/mfile/<id>
            // and the JSON answer carries the final CDN URL. Two requests, no
            // browser, no challenge — the legacy multi-step crawl below stays
            // only as a fallback for older/odd uploads.
            // Every stage is capped to a SLICE of the budget the caller gave us.
            //
            // This used to run 20s + 30s + three 120s browser captures back to
            // back, all inside a caller budget of 45s. The outer timeout killed
            // the resolve during the browser stage — the only stage that can
            // beat Cloudflare — so GDFlix never had a chance to succeed, and the
            // player displayed its failure message seconds later. A manual pick
            // now spends the budget where it can actually pay off.
            // The PAGE fetch is the expensive half of this stage (live: ~3.7s
            // for a 43KB page, and a TV's WebView-free HTTP stack is slower),
            // so it gets the larger slice. Killing it at 8s meant the proven
            // path never finished on a slow box and the cost was paid twice by
            // the plain-HTTP fallback below.
            val instantBudget = msLeft(deadline).coerceAtMost(12_000L)
            if (instantBudget >= 1_000L) {
                val mint = kotlinx.coroutines.withTimeoutOrNull(instantBudget) { gdflixInstant(pageUrl) }
                mint?.stream?.let {
                    DebugLog.log("resolve", "GDFlix instant OK -> ${short(it.url)}")
                    return@withContext it
                }
                if (mint?.removed == true) {
                    // The upload is GONE (site's own "file no longer
                    // available" page). No browser session can bring it back,
                    // so the 15-60s WebView pass below would only be dead
                    // waiting — which is what the viewer saw as "GDFlix says
                    // no stream found right after selecting it".
                    DebugLog.log("resolve", "GDFlix: upload removed site-side — not worth a browser pass")
                    return@withContext null
                }
            }
            try {
                val plainBudget = msLeft(deadline).coerceAtMost(8_000L)
                if (plainBudget >= 1_000L) {
                    kotlinx.coroutines.withTimeoutOrNull(plainBudget) { gdflixPlainHttp(pageUrl) }?.let {
                        DebugLog.log("resolve", "GDFlix plain-HTTP OK -> ${short(it.url)}")
                        return@withContext it
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("resolve", "GDFlix plain-HTTP crashed: ${e.javaClass.simpleName} - ${e.message}")
            }
            DebugLog.log("resolve", "GDFlix plain-HTTP found nothing, falling back to browser")
            // ONE browser attempt, sized to the budget that is actually left.
            // The old 3 x 120s retry loop could not finish inside any caller
            // budget, so its retries only ever produced a longer spinner; the
            // generic click-capture step below is the real second chance.
            if (browser != null) {
                val leftForBrowser = msLeft(deadline)
                if (leftForBrowser >= MIN_BROWSER_MS) {
                    val direct = runCatching {
                        browser.directUrl(pageUrl, leftForBrowser.coerceAtMost(BROWSER_CAP_MS))
                    }.getOrNull()
                    if (direct != null) {
                        DebugLog.log("resolve", "GDFlix browser captured: ${short(direct)}")
                        return@withContext stream(direct, browser)
                    }
                    DebugLog.log("resolve", "GDFlix browser captured nothing")
                } else {
                    DebugLog.log("resolve", "GDFlix no budget left for the browser (${leftForBrowser}ms)")
                }
            }
            DebugLog.log("resolve", "GDFlix resolution failed")
        }
        // FilePress/FileBee (fpgo/newN.filepress.*) are React SPAs. Their own
        // origin proxies the backend under `/api`; that is the only supported
        // path now (the public api.filebee.xyz fallback is dead and removed).
        // Any CF wall falls through to the browser.
        var skipPlainCrawl = false
        // The page the browser must open later. FilePress/FileBee hand out an SPA
        // link that is NOT the URL the caller passed (that one is the adhlinks or
        // interstitial page), and the browser step used to open the WRONG page —
        // it then sat out its whole budget on a page with no download button and
        // the player stayed on "loading" until the caller's timeout fired.
        var browserPageUrl = pageUrl
        if (hoster.contains("filepress", true) ||
            hoster.contains("filebee", true) ||
            pageUrl.contains("fpgo.", true) ||
            pageUrl.contains("filepress.", true) ||
            pageUrl.contains("filepress.baby", true)
        ) {
            // For FilePress, we assume the caller already gives the direct filepress page URL (fpgo/filepress).
            // If the URL doesn't look like filepress, try to extract from the episode page (fallback).
            var filepressPageUrl = pageUrl
            if (!filepressPageUrl.contains("fpgo.") && !filepressPageUrl.contains("filepress.") && !filepressPageUrl.contains("filebee.")) {
                try {
                    val html = get(pageUrl, deadline)
                    if (html != null) {
                        val candidates = harvest(html, pageUrl)
                        val filepressCandidates = candidates.filter { 
                            it.contains("fpgo.", ignoreCase = true) || it.contains("filepress.", ignoreCase = true) || it.contains("filebee.", ignoreCase = true)
                        }
                        val best = filepressCandidates.maxByOrNull { score(it) }
                        if (best != null) {
                            filepressPageUrl = best
                            DebugLog.log("resolve", "Extracted FilePress link: ${short(filepressPageUrl)} (score=${score(best)})")
                        } else {
                            DebugLog.log("resolve", "No FilePress link found in episode page, using original URL")
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.log("resolve", "Failed to extract FilePress link: ${e.javaClass.simpleName}")
                }
            }
            // Now try to resolve the filepress link (either the original if it's already filepress, or the extracted one)
            // CURRENT protocol (verified live): the domain's own `/api` proxy,
            // no Cloudflare. Later stages must open the page we actually
            // identified.
            browserPageUrl = filepressPageUrl
            // The down verdict was already applied at the top of resolve()
            // (isDown), so there is nothing to re-check here.
            val apiBudget = msLeft(deadline).coerceAtMost(12_000L)
            if (apiBudget >= 1_000L) {
                kotlinx.coroutines.withTimeoutOrNull(apiBudget) { filepressApi(filepressPageUrl) }?.let {
                    DebugLog.log("resolve", "FilePress API OK -> ${short(it.url)}")
                    return@withContext it
                }
            }
            // The old `api.filebee.xyz` fallback is GONE, not merely last: that
            // host no longer answers at all (verified live), and filebee.xyz
            // itself does not resolve for clients here. It only ever added
            // ~6s of dead waiting between the origin's own `/api` and the
            // browser step.
            // Skip plain HTTP crawl for FilePress/FileBee because their SPA link is only
            // accessible via browser click-capture; crawling wastes budget.
            skipPlainCrawl = true
        }
        // 1. Plain HTTP. Cheap, and enough for any host that isn't gated.
        if (!skipPlainCrawl) {
            crawl(pageUrl, deadline, browser = null)?.let {
                DebugLog.log("resolve", "plain-HTTP OK -> ${short(it.url)}")
                return@withContext it
            }
        } else {
            DebugLog.log("resolve", "skip plain HTTP crawl for FilePress/FileBee (SPA)")
        }
        if (browser == null) {
            DebugLog.log("resolve", "plain-HTTP failed, no browser allowed -> give up")
            return@withContext null
        }



        // 2. Browser click-and-capture. Ordered BEFORE the browser-assisted
        //    crawl on purpose: Cloudflare-gated hosts mint the link in JS at
        //    click time, so it is never in the HTML — spending the browser
        //    budget on fetching a DOM first would leave nothing for the step
        //    that actually resolves them.
        var left = deadline - System.currentTimeMillis()
        DebugLog.log("resolve", "browser click-capture starting (${left}ms left)")
        if (left >= MIN_BROWSER_MS) {
            val direct = try {
                runCatching { browser.directUrl(browserPageUrl, left.coerceAtMost(BROWSER_CAP_MS)) }.getOrNull()
            } catch (e: Exception) {
                DebugLog.log("resolve", "browser crash: ${e.javaClass.simpleName} - ${e.message}")
                null
            }
            if (direct != null) {
                // Verify like any other candidate. A captured URL is NOT trusted
                // on the strength of its file extension: handing a 403 link to
                // the player is what produced a black screen that never
                // resolved, which is strictly worse than trying the next server.
                val verdict = probe(direct, browser, deadline)
                DebugLog.log("resolve", "captured ${short(direct)} probe=$verdict")
                if (verdict == Verdict.FILE) {
                    return@withContext stream(direct, browser)
                }
                // A JSON/HTML answer means the captured URL is an envelope
                // (FilePress hands back its real link inside JSON) — crawl it
                // rather than throwing the whole attempt away.
                if (verdict == Verdict.PAGE) {
                    crawl(direct, deadline, browser)?.let {
                        DebugLog.log("resolve", "envelope -> ${short(it.url)}")
                        return@withContext it
                    }
                }
            } else {
                DebugLog.log("resolve", "browser captured NOTHING")
            }
        }

        // 3. Whatever budget is left: re-crawl with the cleared session, since
        //    the cookies earned above make the plain prober useful again.
        left = deadline - System.currentTimeMillis()
        if (left < MIN_BROWSER_MS) {
            DebugLog.log("resolve", "out of budget -> give up")
            return@withContext null
        }
        DebugLog.log("resolve", "re-crawl with cleared cookies (${left}ms left)")
        crawl(pageUrl, deadline, browser).also {
            DebugLog.log("resolve", if (it == null) "re-crawl found nothing" else "re-crawl OK -> ${short(it.url)}")
        }
    }

    /**
     * Public probe for the TW4A QR-unlock flow: resolve [interstitialUrl]
     * (archive.toonworld4all.me/redirect/<hex>) to domain+hidden via its
     * __PROPS__, then ranged-GET the constructed drive URL. A FILE verdict
     * means the site is serving real bytes to THIS client — i.e. unlocked.
     * Called repeatedly from the QR screen while the user solves ads on
     * their phone (same WiFi -> same public IP).
     */
    suspend fun verifyTwUnlock(interstitialUrl: String): Boolean = withContext(Dispatchers.IO) {
        val stream = runCatching { resolveRedirectProps(interstitialUrl) }.getOrNull()
            ?: return@withContext false
        DebugLog.log("tw-unlock", "probing ${short(stream.url)}")
        servesMedia(stream)
    }

    /** Keep log lines readable — these URLs are enormous. */
    private fun short(url: String): String =
        if (url.length <= 78) url else url.take(60) + "…" + url.takeLast(15)

    /** Milliseconds left before [deadline] (never negative). */
    private fun msLeft(deadline: Long): Long =
        (deadline - System.currentTimeMillis()).coerceAtLeast(0L)

    /** GDFlix's minting endpoint (rotates rarely; both hosts answer the same shape). */
    private const val XTROCDN_API = "https://xtrocdn.zencloud.lol/api"

    /**
     * FileBee/FilePress SPA protocol, FULLY reverse-engineered from their
     * production bundle (assets/index-*.js):
     *
     *   1. GET  {api}/file/get/<url-slug>
     *      -> file object; the interesting fields are "_id" (Mongo id, NOT
     *         the url slug!) and "method"/downloadOptions (allowed delivery).
     *   2. POST {api}/file/downlaod/  {"captchaValue":"","id":<_id>,"method":<type>}
     *      -> for DIRECT methods (gpDirectDownlaod & friends) the response
     *         body IS the final URL — the SPA literally does
     *         `window.location.href = response.data`.
     *      -> cloudR2* answers {downloadId,...} (async task) — not usable
     *         headlessly, skipped.
     *
     * The SPA's own origin rides along as Origin/Referer; any CF wall simply
     * yields null and the browser unlock below takes over.
     */
    private suspend fun resolveInstantPage(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            // Follow redirects to get the final URL, then extract the 'url' query parameter
            val client = probeClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", Api.USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                DebugLog.log("instant", "final URL: ${short(finalUrl)}")
                val param = Regex("[?&]url=([^&]+)").find(finalUrl)?.groupValues?.get(1)
                if (param != null) {
                    val decoded = runCatching { URLDecoder.decode(param, "UTF-8") }.getOrDefault(param)
                    if (decoded.startsWith("http")) return@withContext decoded
                }
                // busycdn often redirects CLIENT-SIDE (meta refresh / JS) instead
                // of an HTTP 302, so the ?url= never lands on the final URL. Scan
                // the body for the wrapped url, then a meta refresh, then the raw
                // video host literal (googleusercontent, not just googlevideo).
                val body = resp.body?.string() ?: return@withContext null
                val bodyParam = Regex("""[?&]url=([^"'&\s]+)""").find(body)?.groupValues?.get(1)
                if (bodyParam != null) {
                    val decoded = runCatching { URLDecoder.decode(bodyParam, "UTF-8") }.getOrDefault(bodyParam)
                    if (decoded.startsWith("http")) return@withContext decoded
                }
                val meta = Regex("""content=["'][^"']*url=([^"'\s]+)""", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)
                if (meta != null && meta.startsWith("http")) return@withContext meta
                val literal = Regex("""https?://[^"'\s\\]*(?:googlevideo|googleusercontent)[^"'\s\\]*""")
                    .find(body)?.value
                if (literal != null) return@withContext literal
            }
        } catch (e: Exception) {
            DebugLog.log("instant", "resolve failed: ${e.message}")
        }
        null
    }

    private suspend fun resolveCloudPage(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            // Fetch page to get dynamic key from JS
            val getReq = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", Api.USER_AGENT)
                .build()
            val getResp = probeClient.newCall(getReq).execute()
            if (!getResp.isSuccessful) {
                DebugLog.log("cloud", "Failed to fetch page for key extraction")
                return@withContext null
            }
            val html = getResp.body?.string() ?: run {
                DebugLog.log("cloud", "Empty page body")
                return@withContext null
            }
            val keyMatch = Regex("""formData\.append\("key",\s*"([a-f0-9]+)"\)""").find(html)
            val key = keyMatch?.groupValues?.get(1)
            if (key == null) {
                DebugLog.log("cloud", "No key extracted from page")
                return@withContext null
            }
            DebugLog.log("cloud", "Extracted key: $key")

            val form = okhttp3.FormBody.Builder()
                .add("action", "cloud")
                .add("key", key)
                .add("action_token", "")
                .build()
            val host = Regex("""^(https?://[^/]+)""").find(pageUrl)?.groupValues?.get(1) ?: return@withContext null
            val req = Request.Builder()
                .url(pageUrl)
                .post(form)
                .header("x-token", host.removePrefix("https://").removePrefix("http://"))
                .header("Referer", pageUrl)
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                DebugLog.log("cloud", "response: ${body.take(200)}")
                val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    ?: Regex(""""visit_url"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                if (url != null && url.startsWith("http")) return@withContext url
            }
        } catch (e: Exception) {
            DebugLog.log("cloud", "resolve failed: ${e.message}")
        }
        null
    }

    /**
     * GDFlix over PLAIN HTTP with a real session (verified against public
     * bypass scripts, adapted live):
     *
     *   1. Follow the 302 chain WITH cookies (gdflix.dev -> newN.gdflix.io).
     *      The cookies set here are validated by every later call — this is
     *      why a bare client always saw only the CF wall.
     *   2. Path A — "instant" buttons: their href carries ?keys=<token>;
     *      POST it to the xtrocdn/zencloud API and read {"url": <direct>}.
     *   3. Path B — Direct-download button (#drc): scrape `"key","…"` out of
     *      the page, multipart-POST action=direct + key with an
     *      `x-token: <host>` header to the page URL itself; the JSON's url is
     *      one more hop whose HTML carries `let worker_url = '<final>'`.
     *   4. Every candidate is probed like any other before being handed over.
     *
     * Null => Cloudflare gated us anyway; the caller falls back to browser.
     */
    private suspend fun dotflixResolve(pageUrl: String): Playable.Stream? = withContext(Dispatchers.IO) {
        try {
            val domain = Regex("""^(https?://[^/]+)""").find(pageUrl)?.groupValues?.get(1) ?: return@withContext null
            val shareCode = pageUrl.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return@withContext null
            val timestamp = System.currentTimeMillis()
            val requestId = java.util.UUID.randomUUID().toString()
            val data = java.util.Base64.getEncoder().encodeToString("0221B386".toByteArray()).reversed()
            val json = """{"requestId":"$requestId","timestamp":$timestamp,"data":"$data"}"""
            val req = okhttp3.Request.Builder()
                .url("$domain/api/extract-download")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json))
                .header("User-Agent", Api.USER_AGENT)
                .header("Content-Type", "application/json")
                .build()
            val response = probeClient.newCall(req).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val url = Regex(""""downloadUrl"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            if (url != null && url.startsWith("http")) {
                return@withContext stream(url, browser = null)
            }
        } catch (e: Exception) {
            DebugLog.log("dotflix", "resolve failed: ${e.message}")
        }
        null
    }

    private suspend fun gdflixPlainHttp(pageUrl: String): Playable.Stream? = withContext(Dispatchers.IO) {
        val noRedirects = probeClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        var current = pageUrl
        var body: String? = null
        try {
            for (hop in 0..3) { // fewer redirects to save time
                val req = Request.Builder()
                    .url(current)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .build()
                noRedirects.newCall(req).execute().use { resp ->
                    val loc = if (resp.code in 300..399) resp.header("Location") else null
                    if (loc != null) {
                        current = if (loc.startsWith("http")) loc
                        else runCatching { java.net.URI(current).resolve(loc).toString() }.getOrDefault(current)
                    } else if (resp.isSuccessful) {
                        body = readCapped(resp)
                    }
                }
                if (body != null) break
            }
        } catch (e: Exception) {
            return@withContext null
        }
        val html = body ?: run {
            DebugLog.log("gdflix", "no body fetched (redirect chain exhausted), aborting plain-HTTP")
            return@withContext null
        }
        if (looksLikeChallenge(html)) {
            DebugLog.log("gdflix", "Cloudflare challenge detected, plain-HTTP aborting")
            return@withContext null
        }
        DebugLog.log("gdflix", "page fetched (${html.length}b) url=${short(current)}")

        val candidates = ArrayList<String>()
        val origin = Regex("""^(https?://[^/]+)""").find(current)?.groupValues?.get(1) ?: ""

        // ---- Button-text flows (mirrors the production CloudStream GDFlix
        // extractor): the page's download buttons are plain anchors grouped
        // under div.text-center, each with its own resolution recipe.
        // Reuses the noRedirects client built above. ----
        val pageDoc = runCatching { Jsoup.parse(html, current) }.getOrNull()

        // Look for the "Instant DL" button
        pageDoc?.select("a[href^=https://instant.busycdn.xyz/]")?.firstOrNull()?.attr("href")?.let { instant ->
            DebugLog.log("gdflix", "Found Instant DL link: ${short(instant)}")
            candidates.add(instant)
        }

        fun locationHeader(url: String): String? = try {
            noRedirects.newCall(Request.Builder().url(url).header("Referer", current).build())
                .execute().use { resp ->
                    resp.header("Location")
                }
        } catch (e: Exception) {
            null
        }

        fun addCandidate(raw: String?) {
            val c = raw?.trim() ?: return
            if (c.startsWith("http")) candidates.add(c)
        }

        // Broaden search: any anchor or button-like element with download-related text/attributes
        pageDoc?.select("a[href], button, input[type=submit], [role=button]")?.forEach { el ->
            val text = el.text()
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            val dataUrl = el.attr("data-url").ifBlank { el.attr("data-href") }
            val combined = (text + " " + href + " " + dataUrl).lowercase()
            if (!href.startsWith("http") && !dataUrl.startsWith("http")) return@forEach
            val target = if (href.startsWith("http")) href else dataUrl
            when {
                combined.contains("instant") -> {
                    val loc = locationHeader(target)
                    if (loc != null) {
                        addCandidate(unwrap(loc))
                    } else {
                        // No redirect; the target itself is likely the direct file (e.g., instant.busycdn.xyz)
                        addCandidate(target)
                    }
                }
                combined.contains("direct") || combined.contains("pixeldrain") -> addCandidate(target)
                combined.contains("cloud") || combined.contains("r2") -> {
                    if (target.contains("url=")) addCandidate(unwrap(target)) else addCandidate(target)
                }
                combined.contains("download") || combined.contains("server") || combined.contains("worker") -> addCandidate(target)
                else -> Unit
            }
        }

        // Legacy fallbacks kept from earlier revisions:
        // (a) bare `instant`-shaped href anywhere in the HTML.
        Regex("""href="([^"]*instant[^"]*)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { instant ->
                if (instant.startsWith("http")) locationHeader(instant)?.let { addCandidate(unwrap(it)) }
            }

        // (b) xtrocdn keys POST for pages that expose a raw key param.
        val keyVal = Regex("""[?&]keys?=([^&"\s]+)""").find(html)?.groupValues?.get(1)
        if (keyVal != null) {
            try {
                val decoded = if (keyVal.contains('%')) {
                    runCatching { URLDecoder.decode(keyVal, "UTF-8") }.getOrDefault(keyVal)
                } else keyVal
                val form = okhttp3.FormBody.Builder().add("keys", decoded).build()
                val apiReq = Request.Builder()
                    .url(XTROCDN_API)
                    .post(form)
                    .header("Referer", current)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                probeClient.newCall(apiReq).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    Regex(""""url"\s*:\s*"([^"]+)"""").find(t)?.groupValues?.get(1)?.let { candidates.add(it) }
                }
            } catch (e: Exception) {
                DebugLog.log("gdflix", "xtrocdn POST failed: ${e.javaClass.simpleName}")
            }
        }

        // (c) #drc direct button: scrape "key" + signed multipart POST ->
        // one more hop whose HTML carries `let worker_url = '<final>'`.
        val key2 = Regex(""""key",\s*"(.*?)"""").find(html)?.groupValues?.get(1)
        if (key2 != null && html.contains("id=\"drc\"")) {
            try {
                val tokenHost = origin.removePrefix("https://").removePrefix("http://")
                val boundary = "----WebKitFormBoundaryi3pOrWU7hGYfwwL4"
                val data = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"action\"\r\n\r\ndirect\r\n" +
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"key\"\r\n\r\n$key2\r\n" +
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"action_token\"\r\n\r\n\r\n" +
                    "--$boundary--\r\n"
                val mpReq = Request.Builder()
                    .url(current)
                    .post(
                        okhttp3.RequestBody.create(
                            "multipart/form-data; boundary=$boundary"
                                .toMediaTypeOrNull(),
                            data,
                        ),
                    )
                    .header("x-token", tokenHost)
                    .header("Referer", current)
                    .build()
                probeClient.newCall(mpReq).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    val mid = Regex(""""url"\s*:\s*"([^"]+)"""").find(t)?.groupValues?.get(1)
                    if (mid != null && mid.startsWith("http")) {
                        probeClient.newCall(
                            Request.Builder().url(mid).header("Referer", current).build(),
                        ).execute().use { r2 ->
                            val page = r2.body?.string().orEmpty()
                            Regex("""let\s+worker_url\s*=\s*'(.*?)'""")
                                .find(page)?.groupValues?.get(1)?.let { candidates.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("gdflix", "direct POST failed: ${e.javaClass.simpleName}")
            }
        }

        val finalCandidates = mutableListOf<String>()
        for (cand in candidates.distinct()) {
            // Resolve instant.busycdn.xyz wrapper (may redirect to pages.dev with ?url=...)
            if (cand.contains("instant.busycdn.xyz") || cand.contains("fast-one.pages.dev")) {
                val resolved = resolveInstantPage(cand)
                if (resolved != null) {
                    DebugLog.log("gdflix", "Resolved instant page: ${short(resolved)}")
                    finalCandidates.add(resolved)
                    continue
                }
            }
            // Resolve /cloud/ page
            if (cand.contains("/cloud/")) {
                val resolved = resolveCloudPage(cand)
                if (resolved != null) {
                    DebugLog.log("gdflix", "Resolved cloud page: ${short(resolved)}")
                    finalCandidates.add(resolved)
                    continue
                }
            }
            finalCandidates.add(cand)
        }
        for (cand in finalCandidates.distinct()) {
            val v = verdictOf(cand, browser = null, method = "GET", ranged = true)
                ?: verdictOf(cand, browser = null, method = "GET", ranged = false)
                ?: Verdict.DEAD
            DebugLog.log("gdflix", "$v ${short(cand)}")
            if (v == Verdict.FILE) return@withContext stream(cand, browser = null)
        }
        null
    }

    /**
     * The REAL file-host URL behind a T4A `/redirect/<token>` interstitial.
     *
     * One plain GET returns the page's own props (verified live):
     *   window.__PROPS__ = {"userSystem":"24hour",
     *                       "destination":"https://shrinkme.click/…",  <- the ad
     *                       "link":{"domain":"https://gdflix.dev/file/",
     *                               "hidden":"12n00zvrf7ek31m"},"total":4}
     * `destination` is the ad-shortener the site WANTS you to open; `link` is
     * the hoster path the file actually lives behind. Joining the two needs no
     * ad view, no QR scan and no browser.
     */
    private fun redirectTargetOf(pageUrl: String): String? {
        val html = get(pageUrl, System.currentTimeMillis() + REDIRECT_BUDGET_MS) ?: return null
        // The props are a single line of JSON on the interstitial page.
        val props = Regex("""window\.__PROPS__\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        val linkObj = Regex(""""link"\s*:\s*(\{[^{}]*\})"""").find(props ?: html)?.groupValues?.get(1)
            ?: return null
        val domain = Regex(""""domain"\s*:\s*"([^"]+)"""").find(linkObj)?.groupValues?.get(1).orEmpty()
        val hidden = Regex(""""hidden"\s*:\s*"([^"]+)"""").find(linkObj)?.groupValues?.get(1).orEmpty()
        if (!domain.startsWith("http") || hidden.isBlank()) return null
        return domain.trimEnd('/') + "/" + hidden.removePrefix("/")
    }

    /**
     * Which dedicated resolver a URL deserves. Deliberately derived from the
     * RESOLVED url, not from the label the site printed on its card — T4A and
     * the ADH mirrors both mislabel rows, and a token can point anywhere.
     */
    private fun hosterFromUrl(url: String, fallback: String): String {
        val u = url.lowercase()
        return when {
            u.contains("gdflix") -> "GDFlix"
            u.contains("filepress") || u.contains("filebee") || u.contains("fpgo.") -> "FilePress"
            u.contains("hubcloud") -> "HubCloud"
            u.contains("dotflix") -> "GDFlix"
            u.contains("pixeldrain") -> "PixelDrain"
            u.contains("driveleech") -> "DriveLeech"
            u.contains("drivebot") -> "DriveBot"
            else -> fallback
        }
    }

    /**
     * GET that also reports where it LANDED. Several hosts (gdflix.dev,
     * filebee.live, fpgo.xyz) are aliases that 302 to a numbered domain, and
     * the follow-up API only exists on that final origin — so the final URL is
     * part of the answer, not a detail.
     */
    private fun fetchFollowing(url: String): Pair<String, String>? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Api.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()
        probeClient.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val body = if (resp.isSuccessful) readCapped(resp) else null
            if (body == null) {
                DebugLog.log("http", "follow ${resp.code} ${short(url)}")
                null
            } else finalUrl to body
        }
    } catch (e: Exception) {
        DebugLog.log("http", "follow failed ${e.javaClass.simpleName}: ${e.message} ${short(url)}")
        null
    }

    /**
     * One plain GET against a /redirect/ token page; when its HTML embeds
     * `window.__PROPS__ = {…"link":{"domain":D,"hidden":H}}`, D+H is the real
     * drive URL. The sibling "destination" field is an AD SHORTENER
     * (exe.io and friends) — deliberately ignored. Null when the shape isn't
     * there or nothing usable could be built.
     */
    private fun resolveRedirectProps(pageUrl: String): Playable.Stream? {
        val html = get(pageUrl, Long.MAX_VALUE) ?: return null
        val linkObj = Regex(""""link"\s*:\s*(\{[^{}]*\})""").find(html)?.groupValues?.get(1)
            ?: return null
        val domain = Regex(""""domain"\s*:\s*"([^"]+)"""").find(linkObj)?.groupValues?.get(1).orEmpty()
        val hidden = Regex(""""hidden"\s*:\s*"([^"]+)"""").find(linkObj)?.groupValues?.get(1).orEmpty()
        if (!domain.startsWith("http") || hidden.isBlank()) return null
        val url = domain.trimEnd('/') + "/" + hidden.removePrefix("/")
        // Sanity: the built URL must look like a file host, not another ad hop.
        if (!isPlausible(url) && score(url) <= 0) {
            DebugLog.log("resolve", "__PROPS__ target scored 0 -> ${short(url)}")
        }
        return Playable.Stream(
            url = url,
            headers = buildMap {
                put("User-Agent", Api.USER_AGENT)
                Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                    ?.let { put("Referer", "$it/") }
            },
            isHls = false,
        )
    }

    /** Cheap confirmation that a resolved URL actually serves bytes before we hand it over. */
    private fun servesMedia(stream: Playable.Stream): Boolean =
        probe(stream.url, browser = null, deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS) == Verdict.FILE

    /**
     * Breadth-first: probe this page's candidates, then descend into the ones
     * that turned out to be pages. Breadth-first (not depth-first) matters —
     * the real link is usually one of the first page's own buttons, so a
     * depth-first walk wastes the budget deep inside the first dead mirror.
     */
    private suspend fun crawl(startUrl: String, deadline: Long, browser: Browser?): Playable.Stream? {
        var frontier = listOf(startUrl)
        // Every URL already fetched OR probed. Without this the same dead
        // mirror, linked from two pages, burns the budget twice.
        val visited = HashSet<String>()
        for (depth in 0..MAX_DEPTH) {
            if (System.currentTimeMillis() >= deadline) return null
            val nextFrontier = ArrayList<String>()
            for (pageUrl in frontier) {
                if (!visited.add(pageUrl)) continue
                if (System.currentTimeMillis() >= deadline) return null
                val html = fetchPage(pageUrl, deadline, browser) ?: continue
                // A frontier URL that is itself a WRAPPER (dl.php?link=…,
                // st?api=…&url=…) carries the real target in its query —
                // offer the unwrapped URL as the top candidate before
                // harvesting the page body.
                val direct = unwrap(pageUrl).takeIf { it != pageUrl }
                val candidates = (listOfNotNull(direct) + harvest(html, pageUrl))
                    .filterNot { it in visited }
                    .take(MAX_CANDIDATES)
                if (candidates.isEmpty()) continue
                visited.addAll(candidates)
                val (stream, pages) = probeAll(candidates, deadline, browser)
                if (stream != null) return stream
                // Descending re-fetches these, so let them back through.
                visited.removeAll(pages.toSet())
                nextFrontier += pages
            }
            if (nextFrontier.isEmpty()) return null
            frontier = nextFrontier
        }
        return null
    }

    /**
     * Page HTML: plain HTTP first, browser only when that fails. Cloudflare-
     * challenged hosts return 403/503 to OkHttp forever, so without the browser
     * hop there is simply nothing to parse.
     */
    private suspend fun fetchPage(url: String, deadline: Long, browser: Browser?): String? {
        get(url, deadline, browser)?.let { if (!looksLikeChallenge(it)) return it }
        val left = deadline - System.currentTimeMillis()
        if (browser == null || left < MIN_BROWSER_MS) return null
        return runCatching { browser.html(url, left.coerceAtMost(BROWSER_CAP_MS)) }.getOrNull()
    }

    private fun looksLikeChallenge(html: String): Boolean {
        val h = html.lowercase()
        return "just a moment" in h || "__cf_chl" in h || "cf-browser-verification" in h
    }

    /**
     * Probe candidates concurrently, best-scored first. Returns the first URL
     * that serves real bytes, plus the ones that answered with HTML (which are
     * intermediate pages worth descending into).
     */
    private suspend fun probeAll(
        candidates: List<String>,
        deadline: Long,
        browser: Browser?,
    ): Pair<Playable.Stream?, List<String>> = coroutineScope {
        val pages = ArrayList<String>()
        for (batch in candidates.chunked(PROBE_PARALLELISM)) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            // Bound each probe by the budget that ACTUALLY remains, not by the
            // client's 8s call timeout. Without this a batch started just before
            // the deadline kept running well past it — the log showed probes
            // still landing 3 seconds after the resolve had already given up.
            val results = batch.map { url ->
                url to async {
                    kotlinx.coroutines.withTimeoutOrNull(left) { probe(url, browser, deadline) }
                        ?: Verdict.DEAD
                }
            }
            for ((url, deferred) in results) {
                val verdict = deferred.await()
                DebugLog.log("probe", "$verdict ${short(url)}")
                when (verdict) {
                    Verdict.FILE -> {
                        // Cancel the rest of this batch: we have a winner.
                        results.forEach { it.second.cancel() }
                        return@coroutineScope stream(url, browser) to pages
                    }
                    Verdict.PAGE -> pages.add(url)
                    Verdict.DEAD -> Unit
                }
            }
        }
        null to pages
    }

    private enum class Verdict { FILE, PAGE, DEAD }

    /**
     * Decide what a URL really is, without downloading it.
     *
     * Three attempts, because "it refused a ranged GET" and "it is dead" are
     * different things and confusing them costs the user a black screen:
     *   1. ranged GET  — the cheap, normal case.
     *   2. HEAD        — some CDNs answer 400/416 to a Range they didn't sign.
     *   3. plain GET   — closed immediately, for hosts that reject HEAD too.
     * Only when all three fail is the URL declared DEAD.
     *
     * This replaced a "probe failed but the URL ends in .mkv, ship it anyway"
     * shortcut. That shortcut is what made FilePress load and then show a black
     * screen: a 403 link looks exactly like a good one if you only read the
     * file extension. Now an unplayable URL is rejected and the player moves on
     * to the next server instead.
     *
     * The browser's cookies ride along — on a Cloudflare-gated host the file
     * link is only served to a session that already cleared the challenge.
     */
    private fun probe(url: String, browser: Browser?, deadline: Long): Verdict {
        verdictOf(url, browser, method = "GET", ranged = true)?.let { return it }
        // Escalation is a luxury, not a right. Each extra strategy costs up to
        // another callTimeout, and with a dozen candidates that is how GDFlix
        // burned its entire 32s budget on probing and never got to try a link.
        // Only re-examine a candidate while there is real time left to spend.
        if (System.currentTimeMillis() + ESCALATE_RESERVE_MS < deadline) {
            verdictOf(url, browser, method = "HEAD", ranged = false)?.let { return it }
            verdictOf(url, browser, method = "GET", ranged = false)?.let { return it }
        }
        return Verdict.DEAD
    }

    /** One probe attempt. Null = inconclusive (try the next strategy). */
    private fun verdictOf(url: String, browser: Browser?, method: String, ranged: Boolean): Verdict? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Api.USER_AGENT)
            .apply {
                if (ranged) header("Range", "bytes=0-1")
                browser?.cookiesFor(url)?.let { header("Cookie", it) }
                Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                    ?.let { header("Referer", "$it/") }
                if (method == "HEAD") head()
            }
            .build()
        probeClient.newCall(req).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty()
            // Real size of the resource, not of this response. A 206 answers a
            // 2-byte Range with Content-Length: 2 — reading that as the file
            // size would condemn every healthy host, so the total after the
            // slash in `Content-Range: bytes 0-1/1234567` is what counts.
            val totalBytes = resp.header("Content-Range")
                ?.substringAfter('/', "")?.toLongOrNull()
                ?: resp.header("Content-Length")?.toLongOrNull()
            // Check for ZIP archives — AnimeDrive serves seasons as ZIPs.
            // Content-Disposition sometimes reveals the real filename.
            val disp = resp.header("Content-Disposition").orEmpty().lowercase()
            val isZip = type.contains("zip") || type.contains("application/x-zip") ||
                disp.contains(".zip") || url.lowercase().contains(".zip")
            when {
                // ZIP archives: treat as intermediate page to crawl for direct link
                // GDFlix now serves ZIP but may have direct link on same page
                isZip -> Verdict.PAGE
                // An error status is conclusive ONLY for the last strategy;
                // earlier ones fall through so a Range-hostile CDN gets a
                // fair second look.
                !resp.isSuccessful -> null
                // STREAMING PROXY: gamerxyt's dl.php (and friends) declare
                // text/html while actually piping the video through — verified
                // live: ranged GET answers 206 with a multi-hundred-MB
                // Content-Range total. That shape is a real file mid-stream,
                // never an error page (an error page has no big total).
                resp.code == 206 && (totalBytes ?: 0) > MIN_FILE_BYTES -> Verdict.FILE
                // HTML *or JSON*. FilePress's download endpoint answers
                // application/json carrying the real link in its body — treating
                // that as a file handed ExoPlayer a blob of JSON, which is
                // exactly the ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED the
                // player reported. As a PAGE it gets harvested instead, and the
                // link inside it becomes the next candidate.
                type.contains("html") || type.contains("json") || type.startsWith("text/") -> Verdict.PAGE
                // Some hosts serve files as application/octet-stream with no
                // hint of the name — length is the tiebreaker: a real video is
                // never a few hundred bytes.
                type.isBlank() && (totalBytes ?: 0) < 1024 -> null
                // Same rule for a declared type: a "file" of a few hundred bytes
                // is an error envelope, not a video. An unknown size is left
                // alone — plenty of CDNs stream without announcing a length.
                totalBytes != null && totalBytes < MIN_FILE_BYTES -> Verdict.PAGE
                else -> Verdict.FILE
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Every URL the page mentions that could plausibly be the file, best first. */
    private fun harvest(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()

        doc?.select("a[href]")?.forEach { found.add(it.attr("abs:href")) }
        doc?.select("form[action]")?.forEach { found.add(it.attr("abs:action")) }
        doc?.select("[data-url], [data-link], [data-href], [data-file], [data-download]")
            ?.forEach { el ->
                el.attributes().forEach { attr ->
                    if (attr.key.startsWith("data-")) found.add(attr.value)
                }
            }
        doc?.select("meta[http-equiv=refresh]")?.forEach { m ->
            Regex("""url=([^;\s]+)""", RegexOption.IGNORE_CASE)
                .find(m.attr("content"))?.groupValues?.get(1)?.let { found.add(it) }
        }
        // Raw JS/text: catches links no element carries (window.location = …,
        // var url = '…'), including escaped forms.
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        Regex("""https?://[^\s"'<>\\)]+""").findAll(unescaped).forEach { found.add(it.value) }

        // JSON envelopes (FilePress answers its download endpoint with
        // application/json) often carry a RELATIVE path — "/download/abc123" —
        // which the absolute-URL regex above cannot see, so the crawl came back
        // empty even though the link was right there. Resolve any path-shaped
        // string value against the page it came from.
        Regex("""["'](/[^"'\s<>]{6,})["']""").findAll(unescaped).forEach { m ->
            runCatching { java.net.URI(baseUrl).resolve(m.groupValues[1]).toString() }
                .getOrNull()?.let { found.add(it) }
        }

        return found.asSequence()
            .map { unwrap(it.trim()) }
            .filter { it.startsWith("http") }
            .map { normalize(it) }
            .filter { isPlausible(it) }
            .distinct()
            .sortedByDescending { score(it) }
            .toList()
    }

    /** GDFlix API response patterns: {"url":"...", "key":"...", "file":"..."} */
    private fun extractFromJson(body: String, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        val patterns = listOf(
            Regex(""""url"\s*:\s*"([^"]+)"""),
            Regex(""""file"\s*:\s*"([^"]+)"""),
            Regex(""""link"\s*:\s*"([^"]+)"""),
            Regex(""""download"\s*:\s*"([^"]+)"""),
        )
        for (p in patterns) {
            p.findAll(body).forEach { m ->
                val v = m.groupValues[1]
                runCatching { java.net.URI(baseUrl).resolve(v).toString() }
                    .getOrNull()?.let { urls.add(it) }
            }
        }
        return urls
    }

    /** `…/st?api=X&url=<real>` shorteners — take the wrapped URL.
     *  Percent-decoding happens ONLY when '%' is present: URLDecoder would
     *  otherwise corrupt signed URLs whose payloads contain literal '+'. */
    private fun unwrap(url: String): String {
        // AnimeDrive link.animedrive.me/dl/ base64 token
        if (url.contains("link.animedrive.me/dl/")) {
            val token = url.substringAfterLast('/').substringBefore('?')
            if (token.isNotBlank() && token.length >= 8) {
                val decoders = listOf(
                    java.util.Base64.getDecoder(),
                    java.util.Base64.getMimeDecoder(),
                    java.util.Base64.getUrlDecoder(),
                )
                for (dec in decoders) {
                    val raw = try { String(dec.decode(token), Charsets.UTF_8) } catch (e: Exception) { continue }
                    val real = raw.reversed()
                    if (real.startsWith("http")) return real
                    if (raw.startsWith("http")) return raw
                }
            }
            return url // fallback
        }
        val param = Regex("""[?&](?:url|link|file|target)=([^&]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return url
        val decoded =
            if (param.contains('%')) {
                runCatching { URLDecoder.decode(param, "UTF-8") }.getOrDefault(param)
            } else param
        return if (decoded.startsWith("http")) decoded else url
    }

    /** pixeldrain /u/<id> page url -> /api/file/<id> raw file url. */
    private fun normalize(url: String): String =
        Regex("""pixeldrain\.[a-z]+/u/([A-Za-z0-9]+)""").find(url)
            ?.let { m -> url.replace("/u/${m.groupValues[1]}", "/api/file/${m.groupValues[1]}") }
            ?: url

    private fun isPlausible(url: String): Boolean {
        val u = url.lowercase()
        if (u.length > 2_000) return false
        if (JUNK.any { it in u }) return false
        if (me.desidubanime.tv.player.StreamResolver.Blocked.isAd(u)) return false
        return score(url) > 0
    }

    /** Higher = more likely to be the actual file. 0 = not worth probing. */
    private fun score(url: String): Int {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        var s = 0
        if (VIDEO_EXT.any { path.endsWith(it) }) s += 100
        // Outranks everything: a byte count in the path IS the file.
        if (BYTES_SLUG.containsMatchIn(u)) s += 120
        // GDFlix API endpoints and new flow
        if (u.contains("/api/") || u.contains("/bypass/") || u.contains("/create/") || u.contains("/mirror/") || u.contains("/link/")) s += 100
        // GDFlix xtrocdn/zencloud
        if (u.contains("xtrocdn") || u.contains("zencloud")) s += 80
        if (STRONG_HINTS.any { it in u }) s += 40
        if (WEAK_HINTS.any { it in u }) s += 10
        // Long opaque paths are usually signed file URLs, not navigation.
        if (path.length > 60) s += 5
        // A bare host with no path is the site's front page, never the file.
        if (path.trimEnd('/').count { it == '/' } <= 2) s -= 30
        return s
    }

    /**
     * Hand the player the SAME session the probe used: on a Cloudflare-gated
     * host the signed URL is only served to the cleared session, so ExoPlayer
     * needs the cookie and the matching User-Agent too — otherwise the probe
     * succeeds and playback then 403s.
     */
    private fun stream(url: String, browser: Browser?) = Playable.Stream(
        url = url,
        headers = buildMap {
            put("User-Agent", Api.USER_AGENT)
            browser?.cookiesFor(url)?.let { put("Cookie", it) }
            Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                ?.let { put("Referer", "$it/") }
        },
        isHls = url.contains(".m3u8"),
    )

    private fun get(url: String, deadline: Long, browser: Browser? = null): String? = try {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) {
            null
        } else {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", Api.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                // Reuse a clearance the browser already earned: once a host has
                // been unlocked in this session, plain HTTP is fast again and
                // no second WebView hop is needed.
                .apply { browser?.cookiesFor(url)?.let { header("Cookie", it) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val body = if (resp.isSuccessful) readCapped(resp) else null
                DebugLog.log(
                    "http",
                    "GET ${resp.code} ${body?.length ?: 0}b ${short(url)}" +
                        if (body != null && looksLikeChallenge(body)) " CHALLENGE" else "",
                )
                // A small body is an API envelope, not a page — print it. This
                // is the only way to see WHY a JSON answer yielded no candidate
                // (wrong field, a token, a relative path we failed to resolve).
                if (body != null && body.length <= LOG_BODY_MAX) {
                    DebugLog.log("http", "body: ${body.replace(Regex("\\s+"), " ").take(LOG_BODY_MAX)}")
                }
                body
            }
        }
    } catch (e: Exception) {
        DebugLog.log("http", "GET failed ${e.javaClass.simpleName}: ${e.message} ${short(url)}")
        null
    }

    /**
     * Reads at most 512KB of a response body. Pages and JSON envelopes are
     * never bigger; a host that answers a page-GET with the full VIDEO file
     * (GDFlix does this on some links) used to stream a 700MB body straight
     * into a String on a 1GB TV box — instant OOM crash mid-resolve. The cap
     * also never changes behaviour for real pages: HTML beyond the cap is
     * trailer markup (footer ads) that harvest never needs.
     */
    private fun readCapped(resp: okhttp3.Response): String? {
        val body = resp.body ?: return null
        // Cap at 512KB to avoid OOM on video files served as page body
        val bytes = body.byteStream().readNBytes(512 * 1024)
        return bytes.toString(Charsets.UTF_8)
    }

    /** Intermediate pages between the host page and the file. */
    private const val MAX_DEPTH = 2

    /** Probed per page — scored, so the real link is near the front. */
    private const val MAX_CANDIDATES = 14

    /** Probes in flight at once. */
    private const val PROBE_PARALLELISM = 4

    /**
     * Only re-probe a candidate with slower strategies while at least this much
     * budget remains — escalating on every candidate is what starved GDFlix.
     */
    private const val ESCALATE_RESERVE_MS = 8_000L

    /** Smaller than this and it's an error envelope, not a video. */
    private const val MIN_FILE_BYTES = 100_000L

    /** Bodies up to this size are printed to the log verbatim (API envelopes). */
    private const val LOG_BODY_MAX = 2_500

    /** Below this there isn't time for a browser hop to finish — don't start one. */
    private const val MIN_BROWSER_MS = 6_000L

    /**
     * GDFlix's CURRENT plain-HTTP flow — two requests, no browser, no
     * challenge. Verified live 2026-11 against a real upload:
     *
     *   gdflix.dev/file/<id>              302 -> newN.gdflix.io/file/<id>   200
     *   page JS: formData.append("key","<40 hex>") and, for its "instant"
     *            button, taskaction() POSTs to the SAME path with 'file'
     *            swapped for 'mfile'.
     *   POST <host>/mfile/<id>  action=instant & key & action_token=""
     *        header x-token: <host>
     *     -> {"error":false,"info":"Success","url":"https://video-downloads…"}
     *
     * That url answers 206 with Content-Type video/mkv and the full byte count,
     * i.e. it is the file itself. The site ALSO prints a ready CDN anchor
     * (busycdn…?bytes=97312424) but it 302s into a shortener page, so the API
     * above is the short path.
     *
     * "action=direct" / "original" are the page's other two buttons; they
     * answer {"error":true,"message":"Direct Download not Enabled!"} on files
     * that don't offer them, which is why they are only tried afterwards.
     */
    private suspend fun gdflixInstant(pageUrl: String): GdfMint = withContext(Dispatchers.IO) {
        val fetched = fetchFollowing(pageUrl) ?: return@withContext GdfMint()
        val (finalUrl, html) = fetched
        if (looksLikeChallenge(html)) {
            DebugLog.log("gdflix", "challenge on ${short(finalUrl)}")
            return@withContext GdfMint()
        }
        if (isRemovedUpload(html)) {
            DebugLog.log("gdflix", "upload removed: ${short(finalUrl)}")
            return@withContext GdfMint(removed = true)
        }
        // Key is double-quoted inside formData.append — a page without it is
        // the site's generic shell (file removed), so bail early.
        val key = Regex("""formData\.append\(\s*"key"\s*,\s*"([A-Fa-f0-9]{16,})"\s*\)""")
            .find(html)?.groupValues?.get(1)
            ?: Regex(""""key"\s*,\s*"([A-Fa-f0-9]{16,})"""").find(html)?.groupValues?.get(1)
            ?: run {
                DebugLog.log("gdflix", "no key on page (${html.length}b) — likely removed upload")
                null
            }
            ?: return@withContext GdfMint(removed = isRemovedUpload(html))
        val host = Regex("""^(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: return@withContext GdfMint()
        val tokenHost = host.removePrefix("https://").removePrefix("http://")
        val mPath = if (finalUrl.contains("/file/")) finalUrl.replace("/file/", "/mfile/") else finalUrl
        for ((action, target) in listOf("instant" to mPath, "direct" to finalUrl, "original" to finalUrl)) {
            val body = okhttp3.FormBody.Builder()
                .add("action", action)
                .add("key", key)
                .add("action_token", "")
                .build()
            val req = Request.Builder()
                .url(target)
                .post(body)
                .header("x-token", tokenHost)
                .header("Referer", finalUrl)
                .build()
            val json = runCatching { probeClient.newCall(req).execute().use { readCapped(it) } }.getOrNull()
                ?: continue
            val raw = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: Regex(""""visit_url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            if (raw == null) {
                DebugLog.log("gdflix", "$action: ${json.replace(Regex("\\s+"), " ").take(150)}")
                continue
            }
            val url = raw.replace("\\/", "/")
            if (!url.startsWith("http")) continue
            DebugLog.log("gdflix", "$action -> ${short(url)}")
            when (verdictOf(url, browser = null, method = "GET", ranged = true)) {
                Verdict.FILE -> return@withContext GdfMint(stream(url, null))
                // A PAGE answer means the mint returned a shortener/interstitial;
                // one cheap crawl still beats failing the server outright.
                Verdict.PAGE -> crawl(url, System.currentTimeMillis() + GDFLIX_CRAWL_MS, null)
                    ?.let { return@withContext GdfMint(it) }
                else -> Unit
            }
        }
        GdfMint()
    }

    /** Result of a GDFlix mint attempt; [removed] means the file is gone site-side. */
    private class GdfMint(val stream: Playable.Stream? = null, val removed: Boolean = false)

    /**
     * The host's own "this upload no longer exists" page.
     *
     * Verified live (2026-11): gdflix.vip/dev answer a removed file with HTTP
     * 200 and the generic layout whose body says "404! Page Not Found / The
     * file you are trying to download is no longer available". Nothing behind
     * it can be clicked or unlocked, so the caller must not fall through to a
     * browser session for it.
     */
    private fun isRemovedUpload(html: String): Boolean {
        val h = html.lowercase()
        return "no longer available" in h ||
            "file has been deleted" in h ||
            "invalid request" in h && h.contains("no longer")
    }

    /**
     * FilePress / FileBee (filebee.live, fpgo.xyz, newN.filepress.lat) — CURRENT
     * protocol, reverse-engineered from the live SPA and verified with real
     * links (filebee.live/file/… for Blue Box S2E06, 184 MB):
     *
     *   GET  {origin}/api/file/get/<slug>
     *     -> {"data":{"_id":…,"downloadOptions":{…},"size":…}}
     *   POST {origin}/api/file/downlaod/   {captchaValue:null,id:_id,method:…}
     *     -> {"data":"https://…"}                 ready link  (dotFlix)
     *     -> {"data":"<24-hex job id>"}            async job  (public/index/gpixel)
     *     -> CHALLENGE_REQUIRED / LOGIN_REQUIRED   browser or account only
     *
     * THE `/api` PREFIX IS THE WHOLE FIX. The SPA is served from the domain's
     * own origin, which proxies the backend (`REACT_APP_BACKEND_DOMAIN:"/api"`).
     * The old code called https://api.filebee.xyz directly — that host sits
     * behind a Cloudflare JS challenge ("Just a moment…", verified live) and
     * answered every request with an HTML wall, so FilePress never resolved
     * even though its page loads fine over plain HTTP.
     *
     * Note the backend's own spelling: "downlaod".
     */
    /**
     * The LIVE origin behind a FilePress/FileBee alias, fetched with a SHORT
     * timeout and remembered per session.
     *
     * fpgo.xyz / filepress.baby 301 to a numbered host (newN.filepress.lat).
     * While that host is alive this is one cheap redirect; while it is not, a
     * plain `fetchFollowing` waits out the full 18s call timeout on every
     * attempt. This probe gives it [FILEPRESS_PROBE_MS] instead, and records
     * "down" so repeated picks (and the browser fallback behind them) are
     * skipped rather than re-waited.
     */
    private fun filepressFetch(pageUrl: String): Pair<String, String>? {
        val quick = probeClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(FILEPRESS_PROBE_MS, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", Api.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()
        return try {
            quick.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val origin = Regex("""^(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1)
                if (origin == null) {
                    markFilepressDown("no origin for ${short(finalUrl)}")
                    return null
                }
                filepressOrigin = origin
                filepressOriginAt = System.currentTimeMillis()
                val body = if (resp.isSuccessful) readCapped(resp) else null
                if (body == null) {
                    DebugLog.log("filepress", "origin ${short(origin)} answered ${resp.code}")
                }
                Pair(finalUrl, body.orEmpty())
            }
        } catch (e: Exception) {
            // Refused / TLS stall / read timeout: the numbered origin is gone.
            markFilepressDown("${e.javaClass.simpleName} ${short(pageUrl)}")
            null
        }
    }

    private suspend fun filepressApi(pageUrl: String): Playable.Stream? = withContext(Dispatchers.IO) {
        val slug = pageUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug.length < 6) return@withContext null
        // filebee.live / fpgo.xyz are ALIASES: the page 302s to
        // newN.filepress.lat, and `/api` only exists on that final origin.
        //
        // The origin is all this stage needs — the SPA's HTML is never parsed
        // here. So once one pick has named it, later picks skip the alias hop
        // (and its page download) entirely and call `/api` directly.
        val cachedOrigin = cachedFilepressOrigin()
        val origin: String
        if (cachedOrigin != null) {
            origin = cachedOrigin
            DebugLog.log("filepress", "origin cached -> ${short(origin)}")
        } else {
            val fetched = filepressFetch(pageUrl) ?: return@withContext null
            if (fetched.second.isBlank()) return@withContext null
            if (looksLikeChallenge(fetched.second)) return@withContext null
            origin = Regex("""^(https?://[^/]+)""").find(fetched.first)?.groupValues?.get(1)
                ?: return@withContext null
        }
        val api = "$origin/api"
        val info = runCatching {
            probeClient.newCall(
                Request.Builder().url("$api/file/get/$slug")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "$origin/")
                    .build(),
            ).execute().use { readCapped(it) }
        }.getOrNull() ?: return@withContext null
        if (!info.trimStart().startsWith("{")) {
            DebugLog.log("filepress", "get blocked (${info.length}b) ${short(origin)}")
            return@withContext null
        }
        val fileId = Regex(""""_id"\s*:\s*"([A-Za-z0-9_-]{6,40})"""").find(info)?.groupValues?.get(1) ?: slug
        val opts = Regex(""""downloadOptions"\s*:\s*\{([^}]*)\}"""").find(info)?.groupValues?.get(1).orEmpty()
        val enabled = Regex(""""?([A-Za-z]+)"?\s*:\s*true"""").findAll(opts).map { it.groupValues[1] }.toSet()
        // `downloadOptions` describes the buttons the SITE chooses to show, not
        // what the backend accepts: a file advertising only telegram/index still
        // answered `publicDownlaod` with a working Drive id (verified live). So
        // the flags only ORDER the attempts — they never remove one.
        val methods = FILEPRESS_METHODS.sortedBy { if (it in enabled) 0 else 1 }
        DebugLog.log("filepress", "id=$fileId opts=$enabled ${short(origin)}")
        for (method in methods) {
            // Stage 1 — the delivery job. Answers either a ready URL (dotFlix)
            // or {"data":"<24-hex job id>"}.
            val first = filepressPost("$api/file/downlaod/", fileId, method, origin) ?: continue
            DebugLog.log("filepress", "$method -> ${first.replace(Regex("\\s+"), " ").take(140)}")
            for (url in candidateUrlsIn(first)) {
                if (probe(url, null, System.currentTimeMillis() + PROBE_DEADLINE_MS) == Verdict.FILE) {
                    return@withContext stream(url, null)
                }
            }
            val jobId = Regex(""""data"\s*:\s*"([A-Za-z0-9]{16,40})"""").find(first)?.groupValues?.get(1)
                ?: continue
            // Stage 2 — poll the job on `/downlaod2/` (the SPA's
            // `usesDownloadV2` endpoint). Verified live: this answers
            // {"data":"<33-char Google Drive file id>"}, which is the piece the
            // old code never reached, and why FilePress always failed.
            val second = filepressPost("$api/file/downlaod2/", jobId, method, origin) ?: continue
            DebugLog.log("filepress", "$method v2 -> ${second.replace(Regex("\\s+"), " ").take(140)}")
            for (url in candidateUrlsIn(second)) {
                if (probe(url, null, System.currentTimeMillis() + PROBE_DEADLINE_MS) == Verdict.FILE) {
                    return@withContext stream(url, null)
                }
            }
            Regex(""""data"\s*:\s*"([A-Za-z0-9_-]{20,60})"""").find(second)?.groupValues?.get(1)?.let { id ->
                resolveDriveFile(id)?.let { return@withContext it }
            }
        }
        null
    }

    /**
     * Google Drive delivery, as FilePress uses it. Verified live against a real
     * 184,854,656-byte upload (`206`, Content-Range total matching the file):
     *
     *   GET download?id=<id>&authuser=0
     *     small files  -> the bytes themselves
     *     large files  -> a ~2.5 KB confirmation page whose form carries
     *                      id/authuser/confirm=t/uuid
     *   GET …&export=download&confirm=t&uuid=<uuid>   -> the bytes
     *
     * The uuid is minted per session, so it MUST be read from that page rather
     * than reused; the cookie jar keeps whatever Drive set on the first hop.
     */
    private suspend fun resolveDriveFile(driveId: String): Playable.Stream? = withContext(Dispatchers.IO) {
        val base = "https://drive.usercontent.google.com/download?id=$driveId&authuser=0"
        if (probe(base, null, System.currentTimeMillis() + PROBE_DEADLINE_MS) == Verdict.FILE) {
            return@withContext stream(base, null)
        }
        val page = get(base, System.currentTimeMillis() + DRIVE_BUDGET_MS) ?: return@withContext null
        val uuid = Regex("""name="uuid"\s+value="([^"]+)"""").find(page)?.groupValues?.get(1)
        val confirm = "$base&export=download&confirm=t" + (uuid?.let { "&uuid=$it" } ?: "")
        if (probe(confirm, null, System.currentTimeMillis() + PROBE_DEADLINE_MS) == Verdict.FILE) {
            DebugLog.log("drive", "confirmed -> ${short(driveId)}")
            return@withContext stream(confirm, null)
        }
        DebugLog.log("drive", "no bytes for ${short(driveId)} (uuid=${uuid != null})")
        null
    }

    private fun filepressPost(url: String, id: String, method: String, origin: String): String? = try {
        val json = """{"captchaValue":null,"id":"$id","method":"$method"}"""
        probeClient.newCall(
            Request.Builder().url(url)
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .header("Referer", "$origin/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build(),
        ).execute().use { readCapped(it) }
    } catch (e: Exception) {
        DebugLog.log("filepress", "post failed ${e.javaClass.simpleName}")
        null
    }

    /**
     * Every ready-made delivery URL in a FilePress answer.
     *
     * Three shapes were observed live, and the third is easy to miss:
     *   {"data":"https://…"}                       single link   (dotFlix)
     *   {"url":"https://…"} / {"downloadUrl":"…"}  named field
     *   {"data":["https://…","https://…"]}        url-list      (indexDownlaod)
     * The list form is why matching `"data":"https…` literally missed the only
     * usable answer index delivery ever returned.
     */
    private fun candidateUrlsIn(json: String): List<String> =
        Regex(""""https?:\\?/\\?/[^"\s\\]+"""")
            .findAll(json)
            .map { it.value.replace("\\/", "/") }
            .filter { it.length in 12..2_000 }
            .filter { !it.contains("googleusercontent.com/…") }
            .distinct()
            .toList()

    /**
     * FilePress delivery methods, most-direct first. `dotFlixDownlaod` is the
     * one that answers a bare URL today (label "Instant Download"); the rest
     * are async jobs or gated, and are tried only because availability varies
     * per file (`downloadOptions` decides which are even attempted).
     */
    private val FILEPRESS_METHODS = listOf(
        // Proven end to end (live): job id -> /downlaod2/ -> Drive file id ->
        // confirmed 206 with the real byte count. Kept first on purpose.
        "publicDownlaod",
        "dotFlixDownlaod",
        "cloudR2Downlaod",
        "indexDownlaod",
        "gpixelFastDownload",
        "publicUserDownlaod",
    )

    /** Budget for one crawl of a minted gdflix link. */
    private const val GDFLIX_CRAWL_MS = 12_000L

    /** How long the FilePress alias may take to name its origin before we call it dead. */
    private const val FILEPRESS_PROBE_MS = 6_000L

    /** A host declared down is skipped for this long before it is probed again. */
    private const val FILEPRESS_DOWN_TTL_MS = 5 * 60_000L

    /** How long a T4A token whose file 404s is remembered as deleted. */
    private const val TW_DEAD_TTL_MS = 10 * 60_000L

    /** An origin is reused for this long; the alias rotates rarely. */
    private const val FILEPRESS_ORIGIN_TTL_MS = 30 * 60_000L

    /** Origin last resolved for a FilePress alias (see [filepressFetch]). */
    @Volatile
    private var filepressOrigin: String? = null

    @Volatile
    private var filepressOriginAt = 0L

    /** Millis until which FilePress is known unreachable. */
    @Volatile
    private var filepressDownUntil = 0L

    private fun filepressDown(): Boolean =
        System.currentTimeMillis() < filepressDownUntil

    /**
     * True when [hoster] is a FilePress/FileBee label and its origin has been
     * found unreachable this session.
     *
     * Public on purpose: the player resolves servers in batches and an
     * `awaitAll` waits for EVERY member, so one dead row made a batch of two
     * healthy servers take as long as the dead one's probe. The batch can now
     * drop it before spending a request (see PlayerViewModel.raceForStream).
     */
    fun isDown(hoster: String): Boolean {
        val h = hoster.lowercase()
        val filepressFamily = h.contains("filepress") || h.contains("filebee") || h.contains("fpgo")
        return filepressFamily && filepressDown()
    }

    /**
     * T4A /redirect/ tokens whose real host answered 404/410 — i.e. the site's
     * upload was deleted. The player asks this before offering the phone/QR
     * unlock: a deleted file has nothing to unlock, so the ad wall there is
     * pure friction. Keyed by the token URL, which is unique per episode and
     * hoster, and expires so a re-upload is probed again.
     */
    private val twDeadUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isDeadTwToken(tokenUrl: String): Boolean {
        val until = twDeadUntil[tokenUrl] ?: return false
        if (System.currentTimeMillis() < until) return true
        twDeadUntil.remove(tokenUrl)
        return false
    }

    private fun markTwDeadFile(tokenUrl: String) {
        twDeadUntil[tokenUrl] = System.currentTimeMillis() + TW_DEAD_TTL_MS
        DebugLog.log("resolve", "T4A token points at a deleted file — unlock wall not offered")
    }

    /** True when the host itself answers 404/410 for this URL. */
    private fun answersGone(url: String): Boolean = try {
        probeClient.newCall(
            Request.Builder().url(url).header("User-Agent", Api.USER_AGENT).build(),
        ).execute().use { it.code == 404 || it.code == 410 }
    } catch (e: Exception) {
        false
    }

    private fun markFilepressDown(reason: String) {
        filepressDownUntil = System.currentTimeMillis() + FILEPRESS_DOWN_TTL_MS
        DebugLog.log(
            "filepress",
            "origin unreachable ($reason) — skipping FilePress for ${FILEPRESS_DOWN_TTL_MS / 1000}s",
        )
    }

    /** The origin learned this session, if it is still fresh. */
    private fun cachedFilepressOrigin(): String? {
        val origin = filepressOrigin ?: return null
        if (System.currentTimeMillis() - filepressOriginAt > FILEPRESS_ORIGIN_TTL_MS) return null
        return origin
    }


    /**
     * A Cloudflare challenge clears in a few seconds or not at all, but on a
     * low-powered TV box the challenge, the re-render and the click-then-capture
     * round all have to fit in here. 35s: the TV WebView is slower than a phone.
     */
    private const val BROWSER_CAP_MS = 60_000L

    /** Whole-resolve budget; the caller moves to the next server after this. */
    private const val DEFAULT_BUDGET_MS = 120_000L

    /** Cap for the single confirmation probe after __PROPS__ resolution. */
    private const val PROBE_DEADLINE_MS = 8_000L

    /** One plain GET of a T4A /redirect/ interstitial must never hang a resolve. */
    private const val REDIRECT_BUDGET_MS = 12_000L

    /** Reading Drive's confirmation page (one small HTML GET). */
    private const val DRIVE_BUDGET_MS = 10_000L
}
