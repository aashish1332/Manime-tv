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
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Playable
import me.desidubanime.tv.data.model.SubtitleTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Loads an embed URL in an OFFSCREEN WebView and watches the network for a real
 * video stream (.m3u8 / .mp4 / .ts). If one appears we hand it (plus the request
 * headers) to Media3 for native playback. If nothing shows up within [timeoutMs]
 * we fall back to playing the embed itself in a visible WebView.
 *
 * We never parse the site's obfuscated JS — we only watch what it *requests*,
 * so this keeps working when the obfuscation changes.
 */
class StreamResolver(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(embedUrl: String, timeoutMs: Long = 12_000L): Playable =
        suspendCancellableCoroutine { cont ->
            main.post {
                var settled = false
                var webView: WebView? = null
                var timeout: Runnable? = null

                // MAIN THREAD ONLY — WebView methods crash if touched from any
                // other thread (shouldInterceptRequest runs on a Chromium worker).
                fun cleanup() {
                    timeout?.let { main.removeCallbacks(it) }
                    timeout = null
                    webView?.apply {
                        stopLoading()
                        webViewClient = WebViewClient()
                        destroy()
                    }
                    webView = null
                }

                // Safe to call from any thread: hops to main before touching the WebView.
                fun settle(result: Playable) {
                    main.post {
                        if (settled) return@post
                        settled = true
                        cleanup()
                        if (cont.isActive) cont.resume(result)
                    }
                }

                val wv = try {
                    WebView(appContext)
                } catch (t: Throwable) {
                    // WebView provider missing/updating — don't crash, just fall back.
                    if (cont.isActive) cont.resume(Playable.Embed(embedUrl))
                    return@post
                }
                webView = wv
                // Never attached to a window — measure/layout manually so the
                // page renders and synthetic taps have real coordinates.
                // 640x360, not full HD: each racing WebView's raster buffer is
                // pure overhead, and on low-RAM TV boxes two 720p rasters +
                // the video decoder were enough to OOM-REBOOT the whole TV.
                // Taps aim at the relative centre so the size doesn't matter.
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
                )
                wv.layout(0, 0, 640, 360)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = Api.USER_AGENT
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // We only sniff network requests — pixels are dead weight.
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.webViewClient = object : WebViewClient() {
                    /** Subtitle files the page requests (often right beside the stream). */
                    val subs = CopyOnWriteArrayList<SubtitleTrack>()
                    @Volatile var streamFound = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Many embed hosts (esp. mirror sub-servers) gate the stream
                        // behind "click play to verify you are human". A TV remote
                        // can't click inside a WebView, so click for the user:
                        // JS clicker for same-origin players + a real synthetic tap
                        // for cross-origin iframes JS can't reach. Fire now and
                        // again shortly after, since some players build DOM late.
                        val v = view ?: return
                        fun poke() {
                            v.evaluateJavascript(AutoPlay.JS, null)
                            AutoPlay.tapCenter(v)
                        }
                        poke()
                        main.postDelayed({ if (!streamFound) poke() }, 2_500)
                        main.postDelayed({ if (!streamFound) poke() }, 5_000)
                        main.postDelayed({ if (!streamFound) poke() }, 8_000)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (isSubtitle(url)) {
                            subs.addIfAbsent(subtitleTrack(url))
                            return null
                        }
                        if (isStream(url) && !streamFound) {
                            streamFound = true
                            val headers = HashMap<String, String>()
                            request.requestHeaders?.let { headers.putAll(it) }
                            headers.putIfAbsent("User-Agent", Api.USER_AGENT)
                            headers.putIfAbsent("Referer", refererFor(embedUrl))
                            val stream = Playable.Stream(
                                url = url,
                                headers = headers,
                                isHls = url.contains(".m3u8"),
                            )
                            // Give the page a short grace window to also request its
                            // subtitle files (players usually fetch them together),
                            // then settle with whatever we collected. Cancel the
                            // embed-fallback timeout so it can't win during the grace.
                            main.post {
                                timeout?.let { main.removeCallbacks(it) }
                                timeout = null
                                main.postDelayed(
                                    { settle(stream.copy(subtitles = subs.toList())) },
                                    SUBTITLE_GRACE_MS,
                                )
                            }
                            return null
                        }
                        // Starve ads/trackers/media-decoration so the player JS
                        // (the thing that requests the real stream) runs sooner.
                        if (shouldBlock(url)) return emptyResponse()
                        return null // let the request proceed normally
                    }
                }

                cont.invokeOnCancellation {
                    main.post {
                        settled = true
                        cleanup()
                    }
                }

                // Timeout -> fall back to embed playback.
                timeout = Runnable { settle(Playable.Embed(embedUrl)) }
                    .also { main.postDelayed(it, timeoutMs) }

                wv.loadUrl(embedUrl)
            }
        }

    private fun isStream(url: String): Boolean {
        val u = url.lowercase()
        // Ignore analytics/ads that sometimes carry .mp4 in query strings.
        if (u.contains("google") || u.contains("doubleclick") || u.contains("/ads")) return false
        return u.contains(".m3u8") ||
            u.contains("/master") ||
            (u.contains(".mp4") && !u.contains("preview")) ||
            u.contains(".mkv") ||
            u.contains(".webm") ||
            u.contains(".mpd")
    }

    private fun isSubtitle(url: String): Boolean {
        val path = url.lowercase().substringBefore('?')
        return path.endsWith(".vtt") || path.endsWith(".srt")
    }

    private fun subtitleTrack(url: String): SubtitleTrack {
        val file = url.substringBefore('?').substringAfterLast('/')
        val label = file.substringBeforeLast('.')
            .replace(Regex("[-_.]+"), " ")
            .trim()
            .ifBlank { "Subtitles" }
        return SubtitleTrack(url = url, label = label, isVtt = url.lowercase().substringBefore('?').endsWith(".vtt"))
    }

    private fun shouldBlock(url: String): Boolean = Blocked.isAdOrDecoration(url)

    private fun emptyResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        java.io.ByteArrayInputStream(ByteArray(0)),
    )

    private fun refererFor(embedUrl: String): String {
        val origin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1)
        return if (origin != null) "$origin/" else ""
    }

    private companion object {
        /** After the stream shows up, wait this long for sidecar subtitle requests. */
        const val SUBTITLE_GRACE_MS = 1_200L
    }

    object AutoPlay {
        /**
         * A real synthetic tap at the view's centre. Unlike the JS clicker this
         * reaches CROSS-ORIGIN iframes too (mirror sub-servers put their
         * "click play to verify you are a human" gate in one), because it goes
         * through the normal input pipeline like a finger would.
         */
        fun tapCenter(wv: android.webkit.WebView) {
            val w = wv.width.takeIf { it > 0 } ?: return
            val h = wv.height.takeIf { it > 0 } ?: return
            val x = w / 2f
            val y = h / 2f
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0,
            )
            val up = android.view.MotionEvent.obtain(
                now, now + 50, android.view.MotionEvent.ACTION_UP, x, y, 0,
            )
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }

        /**
         * Clicks whatever looks like a play button (jwplayer / video.js / plyr /
         * custom overlays) and calls play() on any <video>, walking same-origin
         * iframes too. Safe to run repeatedly. Shared with the visible embed
         * fallback player — TV remotes can't click inside a WebView at all.
         */
        const val JS = """
            (function autoPlay(doc) {
              try {
                var sels = [
                  '.jw-icon-display', '.jw-display-icon-container', '.jwplayer .jw-media',
                  '.vjs-big-play-button', '.plyr__control--overlaid',
                  '[class*="play-button"]', '[class*="playbutton"]', '[class*="btn-play"]',
                  '[id*="play"]', '[class*="poster"]', '[class*="overlay"]',
                  'button[aria-label*="lay"]', 'video',
                ];
                for (var i = 0; i < sels.length; i++) {
                  var els = doc.querySelectorAll(sels[i]);
                  for (var j = 0; j < els.length && j < 3; j++) {
                    try { els[j].click(); } catch (e) {}
                  }
                }
                var vids = doc.querySelectorAll('video');
                for (var k = 0; k < vids.length; k++) {
                  try { vids[k].muted = true; vids[k].play(); } catch (e) {}
                }
                if (doc.defaultView && doc.defaultView.jwplayer) {
                  try { doc.defaultView.jwplayer().play(); } catch (e) {}
                }
                var frames = doc.querySelectorAll('iframe');
                for (var f = 0; f < frames.length; f++) {
                  try {
                    var fd = frames[f].contentDocument;   // throws cross-origin
                    if (fd) autoPlay(fd);
                  } catch (e) {}
                }
              } catch (e) {}
            })(document);
        """
    }

    object Blocked {
        /** Ad/tracker networks commonly injected by these embed hosts. */
        val BLOCKED_HOST_FRAGMENTS = listOf(
            "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
            "googletagservices", "adsterra", "exoclick", "juicyads", "popads",
            "propeller", "hilltopads", "onclicka", "onclickperformance", "adskeeper",
            "mgid.com", "taboola", "outbrain", "popunder", "adnium", "trafficjunky",
            "hotjar", "yandex.ru/metrika", "mc.yandex", "facebook.net/en_us/fbevents",
            "adsco.re", "a-ads", "acscdn", "pemsrv", "realsrv", "magsrv",
        )
        val BLOCKED_SUFFIXES = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".otf", ".css",
        )

        /** Ads/trackers + media decoration (images/fonts/css) — offscreen sniffer. */
        fun isAdOrDecoration(url: String): Boolean {
            val u = url.lowercase()
            if (BLOCKED_HOST_FRAGMENTS.any { it in u }) return true
            // Images / fonts / css never lead to the stream URL.
            return BLOCKED_SUFFIXES.any { u.substringBefore('?').endsWith(it) }
        }

        /** Ads/trackers only — visible embed player (keep its images/css). */
        fun isAd(url: String): Boolean {
            val u = url.lowercase()
            return BLOCKED_HOST_FRAGMENTS.any { it in u }
        }
    }
}
