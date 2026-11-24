package me.desidubanime.tv.data.api

import kotlinx.serialization.json.Json
import me.desidubanime.tv.App
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared OkHttp + JSON config. Always spoofs a browser UA + Referer for Cloudflare. */
object Network {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Referer is PER-REQUEST ORIGIN, not pinned to the DDA site. The pinned
     * referer was meant to satisfy Cloudflare on DDA itself, but it poisoned
     * every THIRD-PARTY fetch riding this client: adhlinks/animedrive/
     * toonworld episode pages received a foreign cross-site referer, which is
     * exactly the pattern CF rules flag — scrapes came back challenges or
     * empties while the same URLs worked from a clean session. A same-origin
     * referer keeps DDA requests carrying DDA (scheme/host are identical for
     * them) and stops lying about who is asking elsewhere.
     */
    private val headerInterceptor = Interceptor { chain ->
        val req = chain.request()
        req.newBuilder()
            .header("User-Agent", Api.USER_AGENT)
            .header("Referer", "${req.url.scheme}://${req.url.host}/")
            .header("Accept", "application/json, text/html, */*")
            .build()
            .let { chain.proceed(it) }
    }

    /**
     * CLICK-TIME lane. Everything the user is waiting on right now: opening a
     * detail page, loading episodes, resolving a server. Nothing else may use
     * it — the whole point is that this queue stays empty until a click lands.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        // Disk cache: WP/Cloudflare responses revalidate cheaply (ETag/304),
        // which makes cold screens noticeably faster on slow connections.
        .cache(Cache(File(App.instance.cacheDir, "http_cache"), 20L * 1024 * 1024))
        .dispatcher(
            Dispatcher().apply {
                // Generous per-host: a detail-open fans out into several
                // adhlinks/DDA requests at once and they should all fly.
                maxRequests = 24
                maxRequestsPerHost = 12
            },
        )
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * HOME FEED lane. The feed is the app's first job on open, so it gets its
     * own DISPATCHER — a separate request queue from the click lane, so a feed
     * load in flight can no longer sit ahead of a click. (The connection pool
     * is inherited from [client] via newBuilder; pooling only affects socket
     * reuse, the queueing that caused the stall is the dispatcher's.)
     *
     * Like [bulk] it yields to clicks, but with a short cap: the feed IS what
     * the user is looking at during cold start, so it must not stall for long.
     */
    val feed: OkHttpClient = client.newBuilder()
        .cache(Cache(File(App.instance.cacheDir, "http_cache_feed"), 8L * 1024 * 1024))
        .addInterceptor { chain ->
            InteractionGate.awaitIdle(maxWaitMs = 1_500)
            chain.proceed(chain.request())
        }
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 6
            },
        )
        // Tighter than the click lane on purpose. The feed awaits five source
        // lanes in parallel and cannot finish until the slowest answers, so one
        // dead mirror used to hold the whole launch for the 35s call timeout.
        // 20s is past any healthy response on these sites and keeps a dead one
        // from owning the spinner.
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Low-priority client for background catalog work (full ADH catalog, the
     * slug sweep, merged-catalog paging, English-title scrapes). Own dispatcher
     * (small per-host concurrency), own connection pool, NO disk cache — so a
     * cold-start burst can never starve the user's click-time requests, which
     * all go through [client]. The data it fetches is re-fetched on a 10-min
     * TTL anyway, so skipping the cache costs nothing.
     *
     * Separate pools alone weren't enough: the sweeps saturate the DEVICE's
     * uplink and the remote host's rate limits, so every bulk request also
     * parks at [InteractionGate] while a click is being served.
     */
    /**
     * IMAGE lane. Posters are the single biggest request volume in the app (a
     * 100-card grid is 100 downloads), and Coil used to ride [client] — so a
     * grid filling in after a refresh could occupy every per-host slot the
     * click path needed, which is exactly the "click does nothing for a few
     * seconds" feel. Own dispatcher, own queue, same browser headers.
     */
    val images: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            },
        )
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val bulk: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .addInterceptor { chain ->
            // Parks for clicks AND for the home-feed build: both are what the
            // viewer is waiting on, and a sweep request stealing a host slot
            // from either is felt as a slow screen.
            InteractionGate.awaitForeground()
            chain.proceed(chain.request())
        }
        .dispatcher(Dispatcher().apply {
            // Deliberately small. These sweeps have no deadline — they only
            // need to finish eventually, and every slot they take is bandwidth
            // the foreground could have used. Two per host (was three): each
            // in-flight page is also a Jsoup parse on a 4-core TV box, and the
            // sweep already breathes between pages.
            maxRequests = 4
            maxRequestsPerHost = 2
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
