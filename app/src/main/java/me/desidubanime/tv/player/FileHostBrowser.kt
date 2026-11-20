package me.desidubanime.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import me.desidubanime.tv.data.DebugLog
import me.desidubanime.tv.data.api.Api
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Drives an OFFSCREEN WebView against a file host (GDFlix, FilePress, FileBeeâ€¦)
 * to obtain a DIRECT file URL. Nothing is ever shown to the user â€” the result
 * is handed to Media3, so skip-intro, quality/audio/subtitle menus and resume
 * all keep working exactly as they do for HubCloud.
 *
 * WHY A BROWSER IS UNAVOIDABLE HERE (measured, not assumed):
 *   gdflix.dev/file/â€¦   -> HTTP 403, `cf-mitigated: challenge`   (Cloudflare)
 *   hubcloud.cx/drive/â€¦ -> HTTP 200
 * Full browser-like headers do not help; the challenge is JavaScript that must
 * actually execute. HubCloud answers plain OkHttp, which is why it always
 * worked while GDFlix sat on "loading" forever. Rotated domains are no way out
 * either â€” gdflix.cfd is a parked "domain for sale" page, not a mirror.
 *
 * Two capabilities, cheapest first:
 *   [html]      settled DOM after the challenge clears, for the HTTP prober.
 *   [directUrl] clicks the page's own download buttons and captures whatever
 *               file the browser then goes after. This is the reliable one:
 *               these hosts mint the real link in JS at click time, so it does
 *               not exist in the HTML to be scraped.
 *
 * Capture is triple-sourced because each host commits differently:
 *   - DownloadListener        (browser decided it's a download)
 *   - shouldOverrideUrlLoading(navigation straight to the file)
 *   - shouldInterceptRequest  (XHR/fetch for the file)
 */
class FileHostBrowser(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Ek waqt me sirf EK browser WebView â€” do heavy rasters + decoder = weak-box reboot. */
    private val gate = kotlinx.coroutines.sync.Semaphore(1)

    /** Captured XHR/fetch URLs from the page. */
    private val capturedXhrUrls = mutableListOf<String>()

    /** JavaScript interface to capture XHR/fetch URLs from injected JS. */
    private class XhrCaptureInterface(private val browser: FileHostBrowser) {
        @android.webkit.JavascriptInterface
        fun captureXhr(url: String) {
            browser.capturedXhrUrls.add(url)
        }
    }

    /** Cookies held for [url] â€” carries Cloudflare's cf_clearance. */
    fun cookiesFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    /** Reset captured XHR URLs for a new page load. */
    private fun resetCapturedXhrUrls() {
        capturedXhrUrls.clear()
    }

    /** Get all captured XHR/fetch URLs. */
    fun getCapturedXhrUrls(): List<String> = capturedXhrUrls.toList()

    /**
     * The page's HTML once the challenge has cleared, or null on timeout.
     * Polled rather than read at onPageFinished: the challenge REPLACES the
     * document a second or two later, so an early read returns the
     * "Just a momentâ€¦" interstitial instead of the page.
     */
    suspend fun html(url: String, timeoutMs: Long): String? =
        drive(url, timeoutMs, clickDownloads = false) { it.html }

    /** A direct file URL captured after clicking the host's download buttons. */
    suspend fun directUrl(url: String, timeoutMs: Long): String? =
        drive(url, timeoutMs, clickDownloads = true) { it.fileUrl }

    private class Outcome(val html: String? = null, val fileUrl: String? = null)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun drive(
        pageUrl: String,
        timeoutMs: Long,
        clickDownloads: Boolean,
        pick: (Outcome) -> String?,
    ): String? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        gate.withPermit { driveInternal(pageUrl, timeoutMs, clickDownloads, pick) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun driveInternal(
        pageUrl: String,
        timeoutMs: Long,
        clickDownloads: Boolean,
        pick: (Outcome) -> String?,
    ): String? = suspendCancellableCoroutine { cont ->
        DebugLog.log("browser", "open (${timeoutMs}ms, click=$clickDownloads)")
        main.post {
            var settled = false
            var webView: WebView? = null
            val pending = ArrayList<Runnable>()

            fun cleanup() {
                pending.forEach { main.removeCallbacks(it) }
                pending.clear()
                webView?.apply {
                    stopLoading()
                    setDownloadListener(null)
                    webViewClient = WebViewClient()
                    destroy()
                }
                webView = null
            }

            fun settle(outcome: Outcome) {
                if (settled) return
                settled = true
                cleanup()
                if (cont.isActive) cont.resume(pick(outcome))
            }
            // Safe from the Chromium worker threads the callbacks run on.
            fun settleAsync(outcome: Outcome) = main.post { settle(outcome) }

            fun post(delay: Long, body: () -> Unit) {
                val r = Runnable { if (!settled) body() }
                pending.add(r)
                main.postDelayed(r, delay)
            }

            val wv = try {
                WebView(appContext)
            } catch (t: Throwable) {
                // WebView provider missing/updating â€” caller falls back.
                if (cont.isActive) cont.resume(null)
                return@post
            }
            webView = wv
            // Never attached to a window, so measure/layout by hand. Without
            // this the page never really renders, and a Cloudflare challenge
            // that waits on paint/layout signals simply never completes â€” the
            // reason the first browser attempt at this always timed out.
            // 640x360 raster: full-HD offscreen rasters were OOM-rebooting
            // weak TV boxes mid-resolve (user-reported).
            wv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, 640, 360)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = Api.USER_AGENT
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
            }
            // Register JS interface to capture XHR/fetch URLs from React SPAs
            wv.addJavascriptInterface(XhrCaptureInterface(this), "FileHostBridge")
            CookieManager.getInstance().apply {
                setAcceptCookie(true)                       // cf_clearance lives here
                setAcceptThirdPartyCookies(wv, true)
            }

            // The browser decided the target is a download â€” the cleanest signal.
            wv.setDownloadListener { url, _, _, _, _ ->
                DebugLog.log("browser", "capture via DownloadListener")
                settleAsync(Outcome(fileUrl = url))
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (clickDownloads && looksLikeFile(u)) {
                        DebugLog.log("browser", "capture via navigation")
                        settleAsync(Outcome(fileUrl = u))
                        return true // don't actually navigate
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (clickDownloads && looksLikeFile(u)) {
                        DebugLog.log("browser", "capture via XHR intercept")
                        capturedXhrUrls.add(u)
                        settleAsync(Outcome(fileUrl = u))
                        return null
                    }
                    // Ads only. Cloudflare's own scripts MUST load or the
                    // challenge never completes.
                    return if (StreamResolver.Blocked.isAd(u)) {
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    } else {
                        null
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject JS that reports REAL file URLs from inside
                    // XHR/fetch RESPONSE BODIES. FileBee/FilePress SPAs mint
                    // the drive link inside a JSON answer â€” capturing only
                    // responseURL settled on the API endpoint instead of the
                    // file. Bodies are scanned ONLY for small text/json
                    // answers so a video stream is never buffered here.
                    view?.evaluateJavascript("""
                        (function() {
                            var FILE_RX = /(https?:\/\/[^\s"'<>\\]+(?:\.mkv|\.mp4|\.m3u8)[^\s"'<>\\]*|https?:\/\/[^\s"'<>\\]*(?:pixeldrain|workers\.dev|bytes-\d{6,}|zencloud|xtrocdn|driveleech|drivebot|r2\.dev)[^\s"'<>\\]*)/i;
                            function scan(txt) {
                                try {
                                    if (!txt || txt.length > 200000) return;
                                    var m = txt.match(FILE_RX);
                                    if (m && window.FileHostBridge) window.FileHostBridge.captureXhr(m[1]);
                                } catch (e) {}
                            }
                            var originalXHR = window.XMLHttpRequest.prototype.open;
                            window.XMLHttpRequest.prototype.open = function(method, url) {
                                this.addEventListener('load', function() {
                                    try {
                                        var ct = (this.getResponseHeader('Content-Type') || '') + '';
                                        if (/json|text|javascript/i.test(ct)) scan(this.responseText);
                                    } catch (e) {}
                                    try {
                                        if (this.responseURL) window.FileHostBridge && window.FileHostBridge.captureXhr(this.responseURL);
                                    } catch (e) {}
                                });
                                return originalXHR.apply(this, arguments);
                            };
                            var originalFetch = window.fetch;
                            window.fetch = function() {
                                return originalFetch.apply(this, arguments).then(function(response) {
                                    try {
                                        var ct = (response.headers.get('Content-Type') || '') + '';
                                        if (/json|text|javascript/i.test(ct)) response.clone().text().then(scan).catch(function(){});
                                    } catch (e) {}
                                    try {
                                        if (response.url && window.FileHostBridge) window.FileHostBridge.captureXhr(response.url);
                                    } catch (e) {}
                                    return response;
                                });
                            };
                            true;
                        })();
                    """, null)
                }
            }

            fun readDom(onHtml: (String) -> Unit) {
                val v = webView ?: return
                v.evaluateJavascript("document.documentElement.outerHTML") { value ->
                    decodeJsString(value)?.let(onHtml)
                }
            }

            // Poll for the real document; then either return it or start
            // clicking. Challenge stubs are tiny, so length is a cheap filter.
            // For FilePress/FileBee SPAs that need extra time to mount React components
            val isFilePress = pageUrl.contains("fpgo") || pageUrl.contains("filepress") || pageUrl.contains("filebee")
            var clickIndex = 0
            var sawChallenge = false
            var sawPage = false
            fun tick() {
                readDom { doc ->
                    if (doc.length < MIN_HTML || isChallenge(doc)) {
                        if (!sawChallenge) {
                            sawChallenge = true
                            DebugLog.log("browser", "challenge/stub page, waiting (${doc.length}b)")
                        }
                        return@readDom
                    }
                    if (!sawPage) {
                        sawPage = true
                        DebugLog.log("browser", "page ready (${doc.length}b)" + if (sawChallenge) " â€” challenge cleared" else "")
                    }
                    if (!clickDownloads) {
                        settle(Outcome(html = doc))
                        return@readDom
                    }
                    // A click often REVEALS the link in the DOM rather than
                    // navigating (GDFlix/FilePress mint it in JS), so scan the
                    // current document before clicking again.
                    fileUrlIn(doc)?.let {
                        DebugLog.log("browser", "file URL found in DOM")
                        settle(Outcome(fileUrl = it))
                        return@readDom
                    }
                    // Check XHR/fetch URLs captured from React SPA interactions
                    capturedXhrUrls.firstOrNull { looksLikeFile(it) }?.let {
                        DebugLog.log("browser", "file URL found in captured XHR")
                        settle(Outcome(fileUrl = it))
                        return@readDom
                    }
                    val v = webView ?: return@readDom
                    // Two per round: these pages carry several download buttons
                    // (Direct / Instant / Cloud) and only some are alive, so
                    // working through them one poll at a time wastes the budget.
// Try 4 clicks per poll for GDFlix (more chances to hit the right button)
val clicksPerPoll = if (pageUrl.contains("gdflix", ignoreCase = true) || isFilePress) 6 else 2
for (c in 0 until clicksPerPoll) {
    v.evaluateJavascript(clickJs(clickIndex++)) { r ->
        DebugLog.log("browser", "click #${clickIndex - 1} -> ${decodeJsString(r) ?: r}")
    }
}
                }
            }
            var t = if (isFilePress) 15000L else FIRST_READ_MS
// GDFlix needs extra time for React to mount; force longer initial wait if pageUrl contains gdflix
if (pageUrl.contains("gdflix", ignoreCase = true) && t < 15000L) t = 15000L  // increased initial delay for SPAs
            while (t < timeoutMs - 500) {
                post(t) { tick() }
                // Poll faster for the first few seconds (challenge may clear
                // quickly), then slow down to give SPAs time to mount.
                val step = if (isFilePress || pageUrl.contains("gdflix", ignoreCase = true)) 2000L else (if (t < 4_000) 800L else POLL_MS)
                t += step
            }

            cont.invokeOnCancellation { main.post { settled = true; cleanup() } }
            post(timeoutMs) {
                DebugLog.log("browser", "timeout â€” nothing captured")
                settle(Outcome())
            }

            // Reset captured XHR URLs for new page load
            resetCapturedXhrUrls()
            wv.loadUrl(pageUrl)
        }
    }

    /**
     * Clicks the [index]-th download-ish control on the page. Ranked by text /
     * href / class rather than by any fixed markup, so a re-skin or a different
     * host still works; obvious non-download controls are excluded so we never
     * wander off to Telegram or a login page.
     *
     * Expanded patterns for GDFlix/FilePress/FileBee SPA buttons which use
     * different text and classes than the original adhlinks pages. Also includes
     * React/Vue component patterns these SPAs render.
     */
    private fun clickJs(index: Int): String = """
        (function(i){
          try {
            var good = /(direct|instant|download|downlaod|fast\\s*cloud|cloud\\s*download|resume|generate|worker|pixeldrain|server\\s*\\d|\\bdl\\b|get\\s*link|free\\s*down|fetch|grab|api|bypass|create|mirror|link|continue|access|file|stream|watch|play|unlock|enable|allow|proceed|next|submit|filepress|filebee|filebee\\.xyz|fpgo|cloudflare|bypass|wait|timer|countdown|prepare|ready|available|click|here|go|start|open|run|initiate|launch|view|show|reveal|obtain|retrieve|get\\s+link|get\\s+file|download\\s+link|download\\s+file|direct\\s+link|direct\\s+file|download\\s+now|free\\s+download|fast\\s+download|download\\s+button|get\\s+file|open\\s+file|view\\s+file|stream\\s+link|watch\\s+now|play\\s+now|continue\\s+to\\s+file|proceed\\s+to\\s+download|start\\s+download|initiate\\s+download|gdflix|xtrocdn|zencloud|file\\/|video\\/|watch\\?v=|cdn-cgi|data-download|data-file|data-url|data-href|data-link)/i;
            var bad  = /(telegram|whatsapp|discord|report|dmca|login|sign|register|home|contact|share|copy|privacy|terms|how\s*to|premium|donate|faq|blog|about|ads|advertisement|logout|settings|profile|dashboard)/i;
            var els = [].slice.call(document.querySelectorAll('a,button,input[type=submit],[role=button],[onclick],[data-action],[data-click],[data-href],[data-url],[data-testid],[aria-label],[data-download],[data-file],[data-link],#download,.download-btn,[data-file-id],[data-resource],[class*="download"],[class*="get"],[class*="link"],[class*="file"],[class*="button"],[class*="btn"],[role="link"],[type="button"],.btn-primary,#download-btn,button[onclick*="download"],a[href*="download"],a[href*="file"],button[data-download],a[data-download],button[data-file],a[data-file]'));
            var c = els.filter(function(e){
              var t = (e.innerText || e.value || '') + ' ' +
                      (e.getAttribute('href') || '') + ' ' +
                      (e.className || '') + ' ' +
                      (e.getAttribute('data-testid') || '') + ' ' +
                      (e.getAttribute('data-action') || '') + ' ' +
                      (e.getAttribute('data-click') || '') + ' ' +
                      (e.getAttribute('data-href') || '') + ' ' +
                      (e.getAttribute('data-url') || '') + ' ' +
                      (e.id || '');
              return good.test(t) && !bad.test(t);
            });
            if (i >= c.length) { return 'no button #' + i + ' (' + c.length + ' candidates)'; }
            var el = c[i];
            var label = ((el.innerText || el.value || '') + '').replace(/\s+/g, ' ').trim().slice(0, 60);
            el.click();
            return 'clicked "' + label + '" (' + c.length + ' candidates)';
          } catch (e) { return 'click error: ' + e; }
        })($index);
    """

/**
     * First real file URL mentioned anywhere in the document â€” attributes or
     * inline JS. After a click these hosts often write the freshly minted link
     * into the DOM instead of navigating to it, so this is frequently how the
     * link is actually obtained. Escaped forms (\/ and &) are decoded first.
     * Also handles JSON responses that might contain the file URL.
     * Also checks XHR/fetch URLs captured from React SPA interactions.
     */
    private fun fileUrlIn(html: String): String? {
        val text = html.replace("\\/", "/").replace("\\u002F", "/").replace("&", "&")
        // 1. Direct URLs
        val directUrl = Regex("""https?://[^\s"'<>\\)]+""").findAll(text)
            .map { it.value.trimEnd('\\', '"', '\'') }
            .firstOrNull { looksLikeFile(it) }
        if (directUrl != null) return directUrl
        // 2. JSON-encoded URLs (e.g., {"url": "..."})
        val jsonUrl = Regex("""["']url["']\s*:\s*["']([^"']+)["']""").findAll(text)
            .map { it.groupValues[1].replace("\\/", "/").replace("\\u002F", "/") }
            .firstOrNull { looksLikeFile(it) }
        if (jsonUrl != null) return jsonUrl
        // 3. JSON with other keys
        val jsonOther = Regex("""["'](file|link|download|stream)["']\s*:\s*["']([^"']+)["']""").findAll(text)
            .map { it.groupValues[2].replace("\\/", "/").replace("\\u002F", "/") }
            .firstOrNull { looksLikeFile(it) }
        if (jsonOther != null) return jsonOther
        // 4. XHR/fetch URLs captured from React SPA interactions
        val xhrUrl = capturedXhrUrls.firstOrNull { looksLikeFile(it) }
        return xhrUrl
    }

    /** Looks like an actual media/file URL rather than another page. */
    private fun looksLikeFile(url: String): Boolean {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        if (VIDEO_EXT.any { path.endsWith(it) }) return true
        val host = Regex("""^(?:https?://)?([^/?#]+)""").find(u)?.groupValues?.get(1).orEmpty()
        // Allow direct download URLs from filepress.baby (they contain /download/ and ?sid=)
        if (host.contains("filepress.baby") && (u.contains("/download/") || u.contains("?sid="))) return true
        if (listOf("gdflix", "filepress", "fpgo", "filebee", "filemoon", "streamwish", "doodstream")
                .any { host.contains(it) }) return false
        // The GDFlix family serves its video through CDN hosts whose links
        // carry a byte-count slug ("â€¦/blobâ€¦bytes-309355430"). The extension is
        // absent and the host is a parked-looking domain, yet the bytes-count
        // in the path is unmistakable â€” that is the file, not another page.
        if (Regex("""bytes-\d+""").containsMatchIn(u)) return true
        // Storage-bucket path shapes used by the SPA backends' CDNs. NOT
        // "/file/" â€” that is the host's own page path (gdflix.dev/file/â€¦).
        if (path.contains("/blob/") || path.contains("/obj/")) return true
        if (FILE_HOST_HINTS.any { it in u }) return true
        // CDN hosts commonly used by GDFlix/FilePress/FileBee backends.
        if (CDN_HOST_FRAGMENTS.any { it in u }) return true
        return false
    }

    /** evaluateJavascript hands back a JSON-encoded string. */
    private fun decodeJsString(value: String?): String? {
        if (value == null || value == "null") return null
        return runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
    }

    private fun isChallenge(html: String): Boolean {
        val h = html.lowercase()
        return "just a moment" in h ||
            "cf-browser-verification" in h ||
            "__cf_chl" in h ||
            "checking your browser" in h ||
            "cf_chl_opt" in h ||
            "cdn-cgi/content" in h ||
            "access denied" in h
    }

    private companion object {
        val VIDEO_EXT = listOf(".mkv", ".mp4", ".webm", ".avi", ".mov", ".m3u8")
        val FILE_HOST_HINTS = listOf(
            "workers.dev", "r2.dev", "cloudflarestorage", "backblazeb2",
            "b-cdn.net", "digitaloceanspaces", "storage.googleapis",
            "amazonaws", "pixeldrain.net/api/file", "/api/file/",
            // ToonWorld's interstitial navigates to its drive partners
            // directly (hubcloud.cx/video/<id>, gdflix.dev/file/<id>).
            // NOTE: deliberately NO "/api/", "/file/downlaod/" style hints â€”
            // those are the SPA ENDPOINTS, and settling on them wasted the
            // whole browser budget probing JSON envelopes.
            // GDFlix xtrocdn/zencloud
            "xtrocdn", "zencloud", "busycdn.xyz", "fpgo.xyz",
        )
        /**
         * CDN hosts the SPA backends serve the actual file from. Deliberately
         * EXCLUDES the hosts' own domains (gdflix.dev/file/â€¦ is the PAGE, not
         * the file) â€” capturing those would early-settle the session on the
         * wrong target.
         */
        val CDN_HOST_FRAGMENTS = listOf("googlevideo", "ggpht", "googleusercontent", "blogspot.com", "zencloud", "xtrocdn", "driveleech", "drivebot", "busycdn.xyz", "fpgo.xyz")
        const val FIRST_READ_MS = 600L
        const val POLL_MS = 800L
        // Challenge stubs are tiny; a real page is bigger. React SPAs (fpgo,
        // GDFlix FileBee) are small HTML shells (~1.7KB) â€” 500 catches them.
        // But we need to distinguish from the CF challenge page which is also
        // small, so the isChallenge() check runs first.
        const val MIN_HTML = 500
    }
}

