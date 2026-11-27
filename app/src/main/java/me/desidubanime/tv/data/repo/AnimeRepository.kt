package me.desidubanime.tv.data.repo

import me.desidubanime.tv.data.DebugLog
import me.desidubanime.tv.data.api.InteractionGate
import me.desidubanime.tv.data.api.Network
import me.desidubanime.tv.data.cache.AdhSlugStore
import me.desidubanime.tv.data.cache.EnglishTitleStore
import me.desidubanime.tv.data.cache.FeedCacheStore
import me.desidubanime.tv.data.cache.FeedCursor
import me.desidubanime.tv.data.model.AdhEpisodePage
import me.desidubanime.tv.data.model.AdhInfo
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.data.model.Episode
import me.desidubanime.tv.data.model.HomeFeed
import me.desidubanime.tv.data.model.HubEpisode
import me.desidubanime.tv.data.model.HubLink
import me.desidubanime.tv.data.model.LinkSource
import me.desidubanime.tv.data.model.Server
import me.desidubanime.tv.data.model.SubServer
import me.desidubanime.tv.data.model.TwEpisode
import me.desidubanime.tv.data.model.TwEpisodePage
import me.desidubanime.tv.data.model.TwInfo
import me.desidubanime.tv.data.settings.AppSettings
import me.desidubanime.tv.scrape.AdhScraper
import me.desidubanime.tv.scrape.AnimeDriveScraper
import me.desidubanime.tv.scrape.ToonWorldScraper
import me.desidubanime.tv.util.SearchMatch
import me.desidubanime.tv.util.Titles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.parser.Parser

class AnimeRepository(
    private val settings: AppSettings,
    private val slugStore: AdhSlugStore? = null,
    private val feedStore: FeedCacheStore? = null,
    private val titleStore: EnglishTitleStore? = null,
) {
    private val adh = AdhScraper(settings)
    private val ad = AnimeDriveScraper(settings)
    private val tw = ToonWorldScraper(settings)

    // ---- Only streamable hosters — download-only hosts (Mega, Appdrive,
    // GDrive, GDTot) must never reach the player as "hubs" (they'd load a web
    // page instead of playing). Applied at the link level for every source.
    private val STREAMABLE_HOSTERS = setOf("HubCloud", "GDFlix", "FilePress", "FileBee", "FileMoon", "StreamWish", "DoodStream", "DriveLeech", "DriveBot")


    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    /**
     * Completes once the persisted slug/title maps are in memory. Declared here,
     * BEFORE the loader that completes it (see the init block at the bottom of
     * the class): Kotlin runs property initialisers in declaration order, so a
     * deferred declared below its user is still null when the loader coroutine
     * starts on another thread.
     */
    private val storesLoaded = CompletableDeferred<Unit>()

    @Volatile
    private var sweepsStarted = false

    @Volatile
    private var catalogWarmStarted = false

    /**
     * Set [SOURCES_BADGE_GRACE_MS] after the first feed: from then on
     * [sourcesComplete] returns true even if a site's catalog never landed,
     * so source badges render (together) instead of staying hidden forever
     * behind a dead host.
     */
    @Volatile
    private var sourcesGraceElapsed = false

    /**
     * Fetch ALL full site catalogs. Cheap (~14 REST requests, no HTML pages)
     * and started immediately at process start, unlike the sweeps below — a
     * card's badge set is only final once ALL catalogs are on hand
     * ([sourcesComplete]), so the sooner they land the sooner badges appear.
     * This is not what caused the old cold-start stall; that was the ~600-page
     * slug sweep, which still waits for the first feed.
     */
    private fun startCatalogWarm() {
        if (catalogWarmStarted) return
        catalogWarmStarted = true
        bgScope.launch { runCatching { warmAdhCatalog() } }
        // Staggered. Three catalogs fetched at once means three simultaneous
        // page+JSON storms over the TV's single Wi-Fi radio while the viewer is
        // already scrolling the feed that just landed. A small offset keeps the
        // device responsive and costs the catalogs nothing in the end.
        bgScope.launch {
            kotlinx.coroutines.delay(CATALOG_STAGGER_MS)
            runCatching { warmAdCatalog() }
        }
        bgScope.launch {
            kotlinx.coroutines.delay(2 * CATALOG_STAGGER_MS)
            runCatching { warmTwCatalog() }
        }
    }

    /**
     * Kick the background sweeps — called once, after the FIRST home feed lands
     * (success or failure). Cold start used to fire ~600 background pages the
     * instant the process started, competing with both the feed the user is
     * staring at and their first click. Everything here is on the bulk lane,
     * which additionally yields to clicks at [InteractionGate].
     */
    private fun startBackgroundWork() {
        if (sweepsStarted) return
        sweepsStarted = true
        // THE UI COMES FIRST. This used to fire the three catalog fetches — and
        // the ~600-page slug sweep behind them — the instant the first feed
        // landed, while the grid was still binding posters and the viewer was
        // already moving the focus ring. Every one of those requests is an HTML
        // fetch plus a Jsoup parse on the same cores the renderer needs, which
        // is exactly the "sticky right after opening" the user reported.
        //
        // Now the chain waits out a settle window, then for the app to go
        // quiet (no click and no feed build in flight), and only then trickles
        // — the ADH catalog warm starts the slug sweep when it finishes, and
        // that sweep waits for quiet again before its first page.
        bgScope.launch {
            kotlinx.coroutines.delay(BACKGROUND_SETTLE_MS)
            InteractionGate.awaitQuiet(QUIET_BEFORE_BACKGROUND_MS)
            runCatching { startCatalogWarm() }
        }
        // The title sweep is the least urgent job in the app (it only improves
        // names already readable). It gets the longest delay so it lands after
        // the sweep above has begun and the viewer has settled into a screen.
        // BADGE RELEASE VALVE. sourcesComplete() waits for ALL three catalogs
        // so badges appear once, together — but a dead or rate-limited site
        // must not keep every card badge-less forever. After this grace the
        // gate opens with whatever the source maps know; a later catalog
        // landing still invalidates and re-merges (badges only ever GROW,
        // in one batch).
        bgScope.launch {
            kotlinx.coroutines.delay(SOURCES_BADGE_GRACE_MS)
            // Open the gate first, THEN refresh once — the re-merge stamps
            // cards with sourcesResolved=true and the source sets are final.
            sourcesGraceElapsed = true
            if (!sourcesCompleteRaw()) refreshFeedIfSourcesComplete(delayMs = 0L)
        }
    }

    /** True when ALL catalogs are genuinely on hand (no grace applied). */
    private fun sourcesCompleteRaw(): Boolean =
        adhCatalog?.value?.isNotEmpty() == true && adCatalog?.value?.isNotEmpty() == true && twCatalog?.value?.isNotEmpty() == true

    /**
     * True when a merge running right now can see every post on all sites, so
     * the cards it produces carry their FINAL source set. Recorded on
     * [Anime.sourcesResolved]; badges are no longer gated on it (they render
     * from whatever the merge matched), so this now only says whether the set
     * is final or may still gain a source when a catalogue lands.
     *
     * After the grace period ([sourcesGraceElapsed]) this relaxes to true —
     * one unreachable site must not keep claiming every set is provisional.
     */
    private fun sourcesComplete(): Boolean =
        sourcesGraceElapsed || sourcesCompleteRaw()

    // In-memory caches so back-navigation and re-opens render instantly.
    private data class Cached<T>(val at: Long, val value: T)

    private var feedCache: Cached<HomeFeed>? = null

    /** In-flight feed load — concurrent callers join it instead of duplicating. */
    private val feedLoadLock = Any()
    private var feedLoad: CompletableDeferred<HomeFeed>? = null

    /**
     * Bounded LRU map — the caches below outlive the activity but not the
     * process, so they must have an entry limit and evict oldest accessed.
     * Synchronized LinkedHashMap is cheap here: the maps are small (max ~200),
     * and every access is a hit (no misses in practice — misses trigger fresh
     * work, not map lookups).
     */
    private class LruMap<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(max, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }
        @Synchronized fun getValue(key: K): V? = map[key]
        @Synchronized fun putValue(key: K, value: V) { map[key] = value }
        @Synchronized fun remove(key: K): V? = map.remove(key)
        @Synchronized fun clear() { map.clear() }
        // thread-safe [] operators for existing ConcurrentHashMap-style usage
        operator fun get(key: K): V? = getValue(key)
        operator fun set(key: K, value: V) = putValue(key, value)
    }

    private val animeCache = LruMap<Int, Cached<Anime>>(200)
    private val episodesCache = LruMap<Int, Cached<List<Episode>>>(200)
    private val seasonsCache = LruMap<Int, Cached<List<Anime>>>(200)

    /**
     * Recent searches keyed by normalized query. The screen debounces typing,
     * but a back-navigation or the detail screen's season switcher (which
     * searches by base name) repeats the exact same query — and that used to
     * re-ask all four sites every single time.
     */
    private val searchCache = LruMap<String, Cached<List<Anime>>>(SEARCH_CACHE_ENTRIES)

    /** slug -> English title, learned from scraped pages; grows for the process lifetime. */
    private val englishTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Set whenever a new English title is learned; cleared when flushed to disk. */
    @Volatile
    private var titlesDirty = false

    /**
     * English titles are cosmetic (never block rendering), so every learn goes
     * through here and is persisted lazily on the bulk path — never in the
     * click-time request.
     */
    private fun learnTitle(slug: String, title: String) {
        if (title.isBlank()) return
        englishTitles[slug] = title
        titlesDirty = true
    }

    private fun learnTitles(entries: Map<String, String>) {
        entries.forEach { (slug, title) -> learnTitle(slug, title) }
    }

    private suspend fun flushTitleLearns() {
        val store = titleStore ?: return
        if (!titlesDirty) return
        // Whole map flush: the store merges, so a persistent snapshot is all
        // we need — no per-entry bookkeeping.
        store.putAll(HashMap(englishTitles))
        titlesDirty = false
    }

    /** animeId -> matched ADH post (positive ids = merged, negative = ADH-only). */
    private val adhByAnimeId = java.util.concurrent.ConcurrentHashMap<Int, AdhInfo>()

    /** animeId -> matched AnimeDrive post. AD-only ids use -(postId + AD_ID_OFFSET). */
    private val adByAnimeId = java.util.concurrent.ConcurrentHashMap<Int, AdhInfo>()

    /** animeId -> matched ToonWorld post. TW-only ids use -(postId + TW_ID_OFFSET). */
    private val twByAnimeId = java.util.concurrent.ConcurrentHashMap<Int, TwInfo>()

    /**
     * ADH postId -> desidubanime slug learned from the adhlinks page's
     * "Watch online" link ("" = page checked, no link). Backed by [slugStore]
     * so each post is fetched for this at most once across app restarts.
     * AD/TW posts never enter this map — their pages carry no DDA slug.
     */
    private val learnedDdaSlug = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** dda slug -> anime fetched by slug (outside the feed window); null = slug not on DDA. */
    private class SlugFetch(val anime: Anime?)
    private val fetchedBySlug = LruMap<String, SlugFetch>(100)

    /** adhlinks page URL -> parsed page (episode/quality/drive links + dda slug). */
    private val adhPageCache = LruMap<String, Cached<AdhEpisodePage>>(100)

    /** Full ADH catalog (~600 posts), refreshed every [ADH_CATALOG_TTL_MS]. */
    private var adhCatalog: Cached<List<AdhInfo>>? = null

    /** Full AnimeDrive catalog, refreshed every [ADH_CATALOG_TTL_MS]. */
    private var adCatalog: Cached<List<AdhInfo>>? = null

    /** Full ToonWorld catalog, refreshed every [ADH_CATALOG_TTL_MS]. */
    private var twCatalog: Cached<List<TwInfo>>? = null

    /** AD-only / TW-only / ADH-only episode-page caches — bounds at 100 each. */
    private val adPageCache = LruMap<String, Cached<AdhEpisodePage>>(100)
    private val twPageCache = LruMap<String, Cached<TwEpisodePage>>(100)

    @Volatile
    private var catalogFetching = false

    /**
     * ADH posts for merging, WITHOUT blocking the home feed on ~6 heavy
     * requests: return the cached full catalog when we have it; otherwise
     * return the fast latest-100 window and warm the full catalog in the
     * background (feed cache invalidated when it lands, so the next refresh
     * merges everything).
     */
    /**
     * The user changed the ADH domain in settings — drop everything derived
     * from the old base URL so the next load re-scrapes from the new site.
     * Learned slugs and English titles are keyed by WP post id (stable across
     * domain rotations — same database), so they survive the swap.
     */
    fun invalidateAdhCaches() {
        synchronized(feedLoadLock) { feedCache = null }
        pageCatalog = null
        adhCatalog = null
        adhByAnimeId.clear()
        adhPageCache.clear()
        feedStore?.clear()
    }

    /** AnimeDrive domain changed — drop the AD catalog + merged feed. */
    fun invalidateAdCaches() {
        synchronized(feedLoadLock) { feedCache = null }
        pageCatalog = null
        adCatalog = null
        adByAnimeId.clear()
        feedStore?.clear()
    }

    /** ToonWorld4All domain changed — drop the TW catalog + merged feed. */
    fun invalidateTwCaches() {
        synchronized(feedLoadLock) { feedCache = null }
        pageCatalog = null
        twCatalog = null
        twByAnimeId.clear()
        twPageCache.clear()
        feedStore?.clear()
    }

    /** Any source domain changed — full reset, next load re-scrapes all. */
    fun invalidateAllSourceCaches() {
        invalidateAdhCaches()
        invalidateAdCaches()
        invalidateTwCaches()
    }

    /**
     * Fetch the FULL ADH catalog into [adhCatalog] and kick the slug sweep.
     * Bulk lane, never on the critical path — callers that need posts NOW use
     * [adhPostsForMerge], which answers from cache or the fast latest window.
     */
    private suspend fun warmAdhCatalog() {
        if (adhCatalog.fresh(ADH_CATALOG_TTL_MS) != null) return
        if (catalogFetching) return
        catalogFetching = true
        try {
            val posts = adh.allPosts()
            if (posts.isNotEmpty()) {
                adhCatalog = Cached(System.currentTimeMillis(), posts)
                invalidateFeed()
                // Only if the first feed has already landed. The catalog fetch
                // itself now runs at process start, but the ~600-page slug
                // sweep it used to chain into must NOT — that was the whole
                // cause of the cold-start stall. startBackgroundWork() picks it
                // up if the catalog beat the feed here.
                if (sweepsStarted) startSlugSweep(posts)
                refreshFeedIfSourcesComplete(BADGE_REFIRE_MS)
            }
        } finally {
            catalogFetching = false
        }
    }

    /** Same as [warmAdhCatalog] for AnimeDrive. */
    private suspend fun warmAdCatalog() {
        if (adCatalog.fresh(ADH_CATALOG_TTL_MS) != null) return
        val posts = ad.allPosts()
        if (posts.isNotEmpty()) {
            adCatalog = Cached(System.currentTimeMillis(), posts)
            invalidateFeed()
            refreshFeedIfSourcesComplete(BADGE_REFIRE_MS)
        }
    }

    /** Same as [warmAdhCatalog] for ToonWorld4All. */
    private suspend fun warmTwCatalog() {
        if (twCatalog.fresh(ADH_CATALOG_TTL_MS) != null) return
        val posts = tw.allPosts()
        if (posts.isNotEmpty()) {
            twCatalog = Cached(System.currentTimeMillis(), posts)
            invalidateFeed()
            refreshFeedIfSourcesComplete(BADGE_REFIRE_MS)
        }
    }

    /**
     * Re-merge the feed the moment ALL catalogs are in, so every card's source
     * set is final without the user navigating anywhere. Badges render either
     * way; this merge is what adds a source to a card that only matched after
     * the full catalogue was on hand.
     */
    private var remergeJob: kotlinx.coroutines.Job? = null

    /**
     * Re-merge the feed after a short settle window, at most once per window.
     *
     * Each of the three catalogs landing used to fire its own full merge, and
     * every merge re-ran the scrape passes — so a cold start did three or four
     * complete merges (dozens of requests each) while the viewer was trying to
     * scroll. One debounced merge produces the same final feed.
     */
    /**
     * ONE LOW-PRIORITY THREAD for the feed's CPU-bound merge.
     *
     * The merge (title matching over thousands of posts, duplicate collapsing,
     * three full-list sorts) is pure computation, and on a TV box it ran on the
     * ordinary IO pool at normal priority. That starved the UI thread while the
     * grid was painting — the "laggy and stuttery right after opening, fine
     * once the refresh finishes" report. A single minimum-priority thread makes
     * the merge yield to the frame instead of competing with it; the feed is
     * already painted from cache by then, so nothing is waiting on its speed.
     */
    private val mergeLane = java.util.concurrent.Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "feed-merge").apply { priority = Thread.MIN_PRIORITY }
        }
        .asCoroutineDispatcher()

    private fun refreshFeedIfSourcesComplete(delayMs: Long = RE_MERGE_DEBOUNCE_MS) {
        if (!sourcesComplete()) return
        synchronized(feedLoadLock) {
            remergeJob?.cancel()
            remergeJob = bgScope.launch {
                kotlinx.coroutines.delay(delayMs)
                // A re-merge swaps the whole grid (every card rebinds its
                // poster). It must never land while the viewer is clicking
                // through the app — that is a visible hitch, not a refresh.
                InteractionGate.awaitQuiet(RE_MERGE_QUIET_MS)
                runCatching { fetchHomeFeed() }
            }
        }
    }

    /**
     * ADH posts for merging, WITHOUT ever blocking on the ~6-page full catalog:
     * cached catalog if we have one, else the fast latest-100 window. The full
     * catalog is warmed in the background by [startBackgroundWork].
     *
     * [client] picks the lane — the feed passes [Network.feed], click-time
     * callers (findAdhFor) pass the default click lane.
     */
    private suspend fun adhPostsForMerge(client: OkHttpClient = Network.client): List<AdhInfo> =
        adhCatalog.fresh(ADH_CATALOG_TTL_MS)
            ?: adhCatalog?.value
            ?: adh.latestPosts(perPage = 100, client = client)

    /** AnimeDrive posts for merging — same lazy pattern as [adhPostsForMerge]. */
    private suspend fun adPostsForMerge(client: OkHttpClient = Network.client): List<AdhInfo> =
        adCatalog.fresh(ADH_CATALOG_TTL_MS)
            ?: adCatalog?.value
            ?: ad.latestPosts(perPage = 100, client = client)

    /** ToonWorld4All posts for merging — same lazy pattern. */
    private suspend fun twPostsForMerge(client: OkHttpClient = Network.client): List<TwInfo> =
        twCatalog.fresh(ADH_CATALOG_TTL_MS)
            ?: twCatalog?.value
            ?: tw.latestPosts(perPage = 100, client = client)

    /** Drop the merged feed so the next load re-merges with fresher sources. */
    /**
     * Identity of a hub link for dedup: same site + same host + same quality.
     * The source matters — ADH's HubCloud 720p and AD's HubCloud 720p are two
     * different files and both belong in the server list.
     */
    private fun linkKey(l: HubLink): String =
        "${l.source.name}|${l.hoster.lowercase()}|${l.quality.lowercase()}"

    /**
     * Block until the persisted English-title / slug maps are in memory.
     *
     * MUST be awaited before any `withEnglishTitle()` on a code path that then
     * MATCHES titles across sites. DDA's REST titles are romaji; the English
     * names live in [englishTitles], loaded off disk asynchronously at startup.
     * A feed load that won that race rendered romaji cards AND compared romaji
     * against ADH/AnimeDrive's English titles, so every cross-site match failed
     * — the user saw "Tensei shitara Slime…" with only a DDA badge, and the AD
     * badge appeared only after a detail-open had warmed the map.
     *
     * Bounded: a store that never loads must not wedge the feed forever.
     */
    private suspend fun awaitStores() {
        kotlinx.coroutines.withTimeoutOrNull(STORES_WAIT_MS) { storesLoaded.await() }
    }

    /**
     * Stamp every card with the sources we ACTUALLY know about.
     *
     * [adhByAnimeId] / [adByAnimeId] / [twByAnimeId] are the same maps [episodes] reads when it
     * builds the server list, but a card only picked up `adhPostId` if an ADH
     * post happened to match during this merge. Matches discovered later — by
     * `findAdhFor` on a detail-open, or by the background slug sweep — landed
     * in the maps and never on the card. That is why an anime could play ADH
     * servers while its card showed no ADH badge.
     *
     * Running this last makes the badges and the server list agree by
     * construction, whenever the match was found.
     */
    private fun attachKnownSources(list: List<Anime>): List<Anime> {
        val complete = sourcesComplete()
        return list.map { a ->
            val adh = adhByAnimeId[a.id]
            val ad = adByAnimeId[a.id]
            val tw = twByAnimeId[a.id]
            a.copy(
                adhPostId = a.adhPostId ?: adh?.postId,
                adhEpisodesUrl = a.adhEpisodesUrl ?: adh?.episodesUrl,
                adPostId = a.adPostId ?: ad?.postId,
                adEpisodesUrl = a.adEpisodesUrl ?: ad?.episodesUrl,
                twPostId = a.twPostId ?: tw?.postId,
                twEpisodesUrl = a.twEpisodesUrl ?: tw?.episodesUrl,
                sourcesResolved = complete,
            )
        }
    }

    private fun invalidateFeed() {
        synchronized(feedLoadLock) { feedCache = null }
    }

    @Volatile
    private var slugSweepStarted = false

    /**
     * One-time background sweep: learn the adhlinks ddaSlug for EVERY catalog
     * post that doesn't have one yet. The per-merge cap (MAX_SLUG_LOOKUPS)
     * keeps feed loads fast but would take ~15 refreshes to cover the ~600
     * post catalog — until then older anime show no hub servers and keep their
     * romaji names. Results persist, so this costs the full catalog only once
     * per install; later sweeps only fetch what's new.
     *
     * The persisted map is loaded HERE, before filtering — mergeAdh loads it
     * lazily and can lose the race to this sweep, which would re-fetch every
     * post's adhlinks page on EVERY cold start (the exact cause of the
     * "clicks do nothing for ~60s after open" stall: ~600 pages hogging the
     * shared client's per-host slots). Pages are fetched on the bulk client so
     * they never queue ahead of click-time requests.
     */
    private fun startSlugSweep(posts: List<AdhInfo>) {
        if (slugSweepStarted) return
        slugSweepStarted = true
        bgScope.launch {
            storesLoaded.await()
            // Never start ~600 page fetches in the middle of a scroll. The
            // catalog warm already waited for quiet, but the viewer can start
            // navigating again in the second it took to answer.
            InteractionGate.awaitQuiet(QUIET_BEFORE_BACKGROUND_MS)
            val missing = posts.filter { learnedDdaSlug[it.postId].isNullOrBlank() }
            if (missing.isEmpty()) return@launch
            // 2 at a time, and every request also parks at InteractionGate while
            // a click is being served and at BackgroundYield during playback —
            // this sweep has no deadline, the user does. Two pages in flight is
            // also two Jsoup parses, which is what a 4-core TV box can absorb
            // without making the UI thread wait its turn.
            val sem = kotlinx.coroutines.sync.Semaphore(2)
            val learned = java.util.concurrent.ConcurrentHashMap<Int, String>()
            missing.map { post ->
                launch {
                    sem.withPermit {
                        me.desidubanime.tv.data.api.BackgroundYield.waitIfPaused()
                        // Breath between pages: the parser and the JSON/HTML
                        // pipeline share cores with the renderer.
                        kotlinx.coroutines.delay(SWEEP_PAGE_GAP_MS)
                        val page = adhEpisodePage(post.episodesUrl, Network.bulk)
                        if (page.episodes.isNotEmpty() || page.ddaSlug != null) {
                            val slug = page.ddaSlug.orEmpty()
                            learnedDdaSlug[post.postId] = slug
                            learned[post.postId] = slug
                        }
                    }
                }
            }.joinAll()
            if (learned.isNotEmpty()) {
                slugStore?.putAll(learned)
                // Next home refresh re-merges with full coverage; refresh now so
                // the newly-learned slugs show up (HubCloud servers, English names)
                // without the user having to navigate away and back. Debounced:
                // the sweep runs while the viewer is using Home.
                invalidateFeed()
                refreshFeedIfSourcesComplete(delayMs = 1_000L)
            }
        }
    }

    private fun <T> Cached<T>?.fresh(ttlMs: Long): T? =
        if (this != null && System.currentTimeMillis() - at < ttlMs) value else null

    /** Full merged catalog for the sidebar pages (10-min TTL). */
    private var pageCatalog: Cached<List<Anime>>? = null

    /**
     * The browseable catalog: EVERY anime from either site, merged.
     *  - ADH posts are primary; one with a known DDA match becomes a merged
     *    card (positive DDA id -> both sites' servers on open).
     *  - ADH posts without a match stay ADH-only (negative id, hub servers).
     *  - DDA anime with no ADH post are appended too — DDA-only shows must
     *    not disappear from browsing.
     * The DDA paging runs on the bulk client (sidebar data, not click-critical).
     */
    private suspend fun mergedCatalog(): List<Anime> = withContext(Dispatchers.IO) {
        pageCatalog.fresh(ADH_CATALOG_TTL_MS)?.let { return@withContext it }
        awaitStores()
        coroutineScope {
            // Sidebar data, not click-critical — everything runs on the bulk
            // lane, which yields to clicks at InteractionGate.
            val adhDeferred = async { runCatching { adhPostsForMerge(Network.bulk) }.getOrDefault(emptyList()) }
            val adDeferred = async { runCatching { adPostsForMerge(Network.bulk) }.getOrDefault(emptyList()) }
            val twDeferred = async { runCatching { twPostsForMerge(Network.bulk) }.getOrDefault(emptyList()) }
            val mergedAdh = mergeAdhFirst(adhDeferred.await(), Network.bulk)
            // Same duplicate collapsing as the home feed — browsing the catalog
            // pages must not show the same anime as two or three cards either.
            // Upcoming (0-episode placeholder) cards stay out of browsing.
            val merged = attachKnownSources(
                collapseDuplicates(
                    mergeTw(
                        mergeAd(mergedAdh, adDeferred.await()),
                        twDeferred.await(),
                    ).sortedByDescending { maxOf(it.updatedAt, it.publishedAt) },
                ),
            )
                .filter { !it.upcoming }
            if (merged.isNotEmpty()) {
                pageCatalog = Cached(System.currentTimeMillis(), merged)
                runCatching { flushTitleLearns() }
            }
            merged
        }
    }

    /** Sidebar page count — merged catalog, [PAGE_SIZE] cards per page. */
    suspend fun totalAnimePages(): Int {
        val n = mergedCatalog().size
        return ((n + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    }

    /** Page N (1+) of the merged catalog, newest-updated first. */
    suspend fun animePage(page: Int): List<Anime> =
        mergedCatalog().drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)

    /**
     * Home sections, merged across BOTH sites — ADH is the primary source:
     * every ADH post yields a card even when DDA is down or slow (mergeAdh
     * turns unmatched posts into ADH-only cards with hub servers). DDA adds
     * its identity/episode pages on top when reachable.
     *
     * Cold start fires load() (init) and refresh() (ON_RESUME) back-to-back;
     * without the join guard below both would run a full feed fetch and
     * double the request burst that starves click-time requests. The first
     * caller wins, everyone waits on the SAME fetch, and the result is shared.
     */
    /**
     * Manual refresh from the home screen's refresh button: bypasses the 60s
     * TTL so it ALWAYS re-fetches. The join guard in [homeFeed] still dedupes
     * concurrent fetches, and the old feed stays visible until the new one
     * lands (no blank flash).
     */
    suspend fun refreshHomeFeed(): HomeFeed = withContext(Dispatchers.IO) {
        val base = feedCache?.value ?: feedStore?.load()
        val cursor = loadFeedCursor()
        if (base != null && cursor != null && cursor.savedAt > 0L) {
            // Biased towards rebuilding: if the probe itself fails, fall through
            // to the full merge rather than serving a possibly stale feed.
            val changed = runCatching { probeForNewContent(cursor) }.getOrDefault(true)
            if (!changed) {
                DebugLog.log("feed", "refresh: nothing new since last build — cache reused")
                feedCache = Cached(System.currentTimeMillis(), base)
                // The probe may be the only thing this session ran, and the
                // sweeps still need their kick. Idempotent.
                startBackgroundWork()
                return@withContext base
            }
            DebugLog.log("feed", "refresh: something new — rebuilding")
        }
        synchronized(feedLoadLock) { feedCache = null }
        homeFeed()
    }

    /**
     * Last build's coverage, read from disk on each refresh instead of kept in
     * memory: the feed cache is dropped in several places (a changed ADH domain,
     * a settings reset), and a cursor that outlived those would describe a build
     * the app has already discarded — letting a refresh answer "nothing new"
     * about a feed it no longer has. One small file read per manual refresh is
     * cheaper than that class of bug.
     */
    private fun loadFeedCursor(): FeedCursor? = feedStore?.loadCursor()

    private fun saveFeedCursor(cursor: FeedCursor) {
        feedStore?.saveCursor(cursor)
    }

    /** Lexicographic max of ISO-8601 stamps — the ordering this app already uses. */
    private fun newestStamp(values: List<String>): String =
        values.filter { it.isNotBlank() }.maxOrNull().orEmpty()

    /**
     * Is anything newer than the last successful build?
     *
     * Every source is asked the same question — "what is your newest stamp?" —
     * and the answer is compared against the one the last build recorded. DDA
     * answers it with a ONE-ITEM collection ordered exactly like its catalogs
     * (`modified` for anime, `date` for episodes); the scraped sites answer from
     * the latest window already in memory, so they cost no network while the
     * catalog cache is warm. Total: two tiny requests and no catalog work,
     * whatever the size of the catalog.
     *
     * Not `modified_after` / `after`. Measured against a live WordPress these
     * are silently ignored when the post type does not declare support for
     * them: the collection comes back unfiltered, the probe sees items, and the
     * refresh rebuilds forever — the feature would quietly do nothing. Ordering
     * by the field and reading one item cannot fail that way, and it also
     * catches EDITS, which bump an anime's `modified` and therefore make it the
     * newest row.
     *
     * Fails open: a failed request or a blank stamp reports "changed", so a
     * refresh degrades to the old full rebuild instead of hiding new episodes.
     */
    private suspend fun probeForNewContent(cursor: FeedCursor): Boolean = withContext(Dispatchers.IO) {
        // AnimeDubHindi is the primary source now, so its newest post is the
        // first thing worth asking about. (This probe used to lead with two
        // DesiDubAnime REST calls, which can only ever answer 403.)
        val adh = adhPostsForMerge(Network.feed)
        if (adh.isNotEmpty() && newestStamp(adh.map { it.modified }) > cursor.adhModified) {
            DebugLog.log("feed", "probe: AnimeDubHindi changed")
            return@withContext true
        }
        val ad = adPostsForMerge(Network.feed)
        if (ad.isNotEmpty() && newestStamp(ad.map { it.modified }) > cursor.adModified) {
            DebugLog.log("feed", "probe: AnimeDrive changed")
            return@withContext true
        }
        val tw = twPostsForMerge(Network.feed)
        if (tw.isNotEmpty() && newestStamp(tw.map { it.modified }) > cursor.twModified) {
            DebugLog.log("feed", "probe: ToonWorld4All changed")
            return@withContext true
        }
        DebugLog.log("feed", "probe: nothing newer than the cached build")
        false
    }

    suspend fun homeFeed(): HomeFeed = withContext(Dispatchers.IO) {
        feedCache.fresh(FEED_TTL_MS)?.let { return@withContext it }
        val deferred = synchronized(feedLoadLock) {
            feedLoad ?: CompletableDeferred<HomeFeed>().also { d ->
                feedLoad = d
                bgScope.launch {
                    runCatching { fetchHomeFeed() }
                        .onSuccess { d.complete(it) }
                        .onFailure {
                            // A failed feed does NOT start the sweeps — they
                            // would then run every launch trying to re-fetch
                            // and starve the user's click on a slow connection.
                            // The sweeps only start after a successful feed.
                            d.completeExceptionally(it)
                        }
                }
            }
        }
        try {
            deferred.await()
        } finally {
            synchronized(feedLoadLock) { if (feedLoad === deferred) feedLoad = null }
        }
    }

    /**
     * A feed build is exactly what the viewer is waiting on at launch, so it
     * holds the priority lane: background requests park at [InteractionGate]
     * for its duration instead of competing for host bandwidth and CPU with
     * the merge that paints Home. [fetchHomeFeedInner] is never called directly.
     */
    private suspend fun fetchHomeFeed(): HomeFeed =
        InteractionGate.holdFeed { fetchHomeFeedInner() }

    private suspend fun fetchHomeFeedInner(): HomeFeed = withContext(Dispatchers.IO) {
        // Before ANY title mapping: romaji-vs-English comparisons are the root
        // of the duplicate-card and wrong-badge reports. See awaitStores().
        awaitStores()
        coroutineScope {
            // Everything here runs on the FEED lane (wpFeed / Network.feed) so a
            // feed load and a click never share a connection queue.
            val adhDeferred = async { runCatching { adhPostsForMerge(Network.feed) }.getOrDefault(emptyList()) }
            val adDeferred = async { runCatching { adPostsForMerge(Network.feed) }.getOrDefault(emptyList()) }
            val twDeferred = async { runCatching { twPostsForMerge(Network.feed) }.getOrDefault(emptyList()) }

            // ---- merge in AnimeDubHindi (PRIMARY) ----
            val adhPosts = adhDeferred.await()
            var all = withContext(mergeLane) { mergeAdhFirst(adhPosts, Network.feed) }

            // ---- merge in AnimeDrive.in ----
            val adPosts = adDeferred.await()
            all = withContext(mergeLane) { mergeAd(all, adPosts) }

            // ---- merge in ToonWorld4All ----
            val twPosts = twDeferred.await()
            all = withContext(mergeLane) { mergeTw(all, twPosts) }

            // Recency order (ADH modified / DDA episode feed) so "All Anime"
            // renders newest-first — ADH-first merging appends DDA-only anime
            // at the end, which must not sink them out of the section.
            all = withContext(mergeLane) { all.sortedByDescending { maxOf(it.updatedAt, it.publishedAt) } }

            // Collapse near-duplicates into ONE card per anime: when matching
            // fails, the same show arrives as separate ADH-only / AD-only / DDA
            // cards. Runs BEFORE the recency stamping below so the fused card's
            // stamps are computed from every source it absorbed.
            all = withContext(mergeLane) { collapseDuplicates(all) }

            // Stamp sources learned outside this merge (detail-open findAdhFor,
            // background slug sweep) so badges match the server list. Before
            // the upcoming-filter below, which keys off adhEpisodesUrl.
            all = withContext(mergeLane) { attachKnownSources(all) }

            // Placeholder "not yet released" posts have 0 episodes. All three
            // live sites create them, so the flag alone is the filter.
            all = all.filter { !it.upcoming }

            // Recency across both sites: DDA's episode feed / modified stamp vs
            // ADH's post modified stamp (bumped when new episode links go up).
            // Stamped onto each anime — the home screen compares it against the
            // per-anime "seen" stamp for NEW EPISODE badges.
            fun recency(a: Anime): String {
                val adhMod = a.adhPostId?.let { adhByAnimeId[a.id]?.modified }.orEmpty()
                val adMod = a.adPostId?.let { adByAnimeId[a.id]?.modified }.orEmpty()
                val twMod = a.twPostId?.let { twByAnimeId[a.id]?.modified }.orEmpty()
                return maxOf(a.updatedAt, adhMod, adMod, twMod)
            }
            // A release is "NEW" from when it first appeared on ANY site —
            // the EARLIER of the dates. (max() was wrong: ADH re-creating/
            // editing a post bumped an old show to the top of New Releases,
            // clashing with New Episodes.)
            fun released(a: Anime): String {
                val adhDate = a.adhPostId?.let { adhByAnimeId[a.id]?.date }.orEmpty()
                val adDate = a.adPostId?.let { adByAnimeId[a.id]?.date }.orEmpty()
                val twDate = a.twPostId?.let { twByAnimeId[a.id]?.date }.orEmpty()
                val dates = listOf(a.publishedAt, adhDate, adDate, twDate).filter { it.isNotBlank() }
                return dates.minOrNull() ?: a.publishedAt
            }
            all = withContext(mergeLane) {
                all.map { it.copy(latestEpisodeAt = recency(it), publishedAt = released(it)) }
            }
            val newEpisodes = all.sortedByDescending { it.latestEpisodeAt }.take(NEW_EPISODES_COUNT)
            val newReleases = all.sortedByDescending { it.publishedAt }.take(NEW_RELEASES_COUNT)

            HomeFeed(newEpisodes = newEpisodes, newReleases = newReleases, all = all)
                .also { feedCache = Cached(System.currentTimeMillis(), it) }
                // DISK WRITES ARE NOT ON THE PAINT PATH. Serialising the whole
                // feed and the cursor before returning made Home wait for two
                // file writes it does not need; they now run after the feed is
                // already on screen.
                .also { feed -> bgScope.launch { runCatching { feedStore?.save(feed) } } }
                // Record what this build covered, so the next refresh can ask
                // "anything new?" instead of rebuilding. Stamped FROM the
                // sources, not from the clock: a source that was slow but
                // already current must not look newer than its own newest post.
                .also {
                    val cursor = FeedCursor(
                        // Left blank for cursor-file compatibility; the two
                        // DesiDubAnime-driven stamps they carried are gone.
                        ddaModified = "",
                        episodeDate = "",
                        adhModified = newestStamp(adhPosts.map { p -> p.modified }),
                        adModified = newestStamp(adPosts.map { p -> p.modified }),
                        twModified = newestStamp(twPosts.map { p -> p.modified }),
                        savedAt = System.currentTimeMillis(),
                    )
                    bgScope.launch {
                        runCatching { saveFeedCursor(cursor) }
                        runCatching { flushTitleLearns() }
                    }
                }
                // The user is looking at a feed now — background sweeps may
                // start. Until this point they'd be competing with the one
                // thing the app owes the user on open.
                .also { startBackgroundWork() }
        }
    }

    /**
     * Last persisted feed for instant cold-start paint (may be hours old).
     * Null on first-ever launch. Cheap: one small JSON file off disk.
     */
    suspend fun cachedFeed(): HomeFeed? = withContext(Dispatchers.IO) {
        feedCache.fresh(FEED_TTL_MS)?.let { return@withContext it }
        feedStore?.load()
    }

    /**
     * EVERY AnimeDubHindi post becomes a card on its own: ADH carries the
     * English title, the poster, the dates and the hub links.
     *
     * This used to be a three-pass merge against DesiDubAnime's catalogue (title
     * key, learned adhlinks slug, then a DDA fetch by slug for posts outside the
     * window) whose only product was a DDA id on the card. That site now answers
     * every request with Cloudflare's challenge, so all three passes could only
     * add requests and latency to the launch path while producing identical
     * cards with a dead id.
     */
    private suspend fun mergeAdhFirst(
        adhPosts: List<AdhInfo>,
        client: OkHttpClient = Network.client,
    ): List<Anime> = coroutineScope {
        if (adhPosts.isEmpty()) return@coroutineScope emptyList()
        val merged = ArrayList<Anime>()
        for (post in adhPosts) {
            // Hard guard: an ADH post id must never cross into the AD-only or
            // TW-only id ranges, which are offset by a million.
            if (post.postId >= AD_ID_OFFSET) continue
            val id = -post.postId
            adhByAnimeId[id] = post
            merged.add(
                Anime(
                    id = id,
                    slug = post.slug,
                    title = post.displayTitle,
                    synopsis = post.synopsis,
                    posterUrl = post.posterUrl,
                    link = post.link,
                    publishedAt = post.date,
                    updatedAt = post.modified,
                    adhPostId = post.postId,
                    adhEpisodesUrl = post.episodesUrl,
                ),
            )
        }
        merged
    }

    /**
     * Match AnimeDrive posts onto the already-DDA+ADH-merged catalog.
     * AnimeDrive posts don't carry a "Watch online" link to desidubanime, so
     * matching is by normalised title+season key only (pass 1 of mergeAdh).
     * Unmatched posts become AD-only entries with ids -(postId + AD_ID_OFFSET)
     * to stay distinct from ADH-only ids (which are -postId).
     *
     * Runs AFTER mergeAdh, so a DDA anime already merged with ADH gets its AD
     * link attached too — one card, three sources. AD-only anime (no DDA, no
     * ADH match) get their own cards so they're browseable and playable.
     *
     * CRITICAL: also matches against ADH-only entries (negative ids) — without
     * this, the same anime on ADH and AD would show as two separate cards.
     * When an AD post matches an ADH-only card, the ADH card gets the AD link
     * attached and the AD-only duplicate is NOT created.
     */
    private fun mergeAd(ddaAll: List<Anime>, adPosts: List<AdhInfo>): List<Anime> {
        if (adPosts.isEmpty()) return ddaAll
        val byKey = HashMap<String, Anime>()
        for (a in ddaAll) for (k in Titles.keys(a.title)) byKey[k] = a

        // English-name index: DDA cards are romaji, so an AnimeDrive post can
        // only key-match them once their English name is known. Without this,
        // every AD post whose twin is a romaji DDA card minted a lone "AD only"
        // duplicate (the user's report) until a detail-open learned the name.
        val byEnglishKey = HashMap<String, Anime>()
        for (a in ddaAll) {
            val en = englishTitles[a.slug]?.takeIf { it.isNotBlank() } ?: continue
            for (k in Titles.keys(en)) byEnglishKey.putIfAbsent(k, a)
        }

        // Fallback index for AD posts whose wording no exact key can match.
        // Keyed by season so "Season 1" of two different shows can't collide,
        // and looked up through the shared token index so it stays cheap.
        val tokensById = HashMap<Int, Set<String>>()
        val bySeasonToken = HashMap<String, MutableList<Anime>>()
        for (a in ddaAll) {
            val t = Titles.baseTokens(a.title)
            if (t.isEmpty()) continue
            tokensById[a.id] = t
            val season = Titles.baseAndSeason(Titles.clean(a.title)).second
            for (w in t) bySeasonToken.getOrPut("$season|$w") { mutableListOf() }.add(a)
        }
        fun fuzzyHit(post: AdhInfo): Anime? {
            val t = Titles.baseTokens(post.displayTitle)
            if (t.isEmpty()) return null
            val season = Titles.baseAndSeason(Titles.clean(post.displayTitle)).second
            return t.asSequence()
                .flatMap { w -> bySeasonToken["$season|$w"].orEmpty().asSequence() }
                .distinctBy { it.id }
                .firstOrNull { Titles.sameShow(t, tokensById[it.id].orEmpty()) }
        }

        val merged = ddaAll.toMutableList()
        val adOnly = ArrayList<Anime>()
        for (post in adPosts) {
            val keys = Titles.keys(post.displayTitle)
            val hit = keys.firstNotNullOfOrNull { byKey[it] }
                ?: keys.firstNotNullOfOrNull { byEnglishKey[it] }
                ?: fuzzyHit(post)
            if (hit != null) {
                adByAnimeId[hit.id] = post
                // Learn the English name (AD posts are English) and re-apply it:
                // a romaji DDA card was already through withEnglishTitle() before
                // this merge, so without the re-apply the card keeps its romaji
                // name even though we just proved it is the same show.
                learnTitle(hit.slug, post.displayTitle)
                val i = merged.indexOfFirst { it.id == hit.id }
                if (i >= 0) {
                    merged[i] = merged[i]
                        .copy(adPostId = post.postId, adEpisodesUrl = post.episodesUrl)
                        .withEnglishTitle()
                }
            } else {
                // Skip if this id would collide with the TW-only tier (guard only).
                if (post.postId + AD_ID_OFFSET >= TW_ID_OFFSET) continue
                val id = -(post.postId + AD_ID_OFFSET)
                adByAnimeId[id] = post
                adOnly.add(
                    Anime(
                        id = id,
                        slug = post.slug,
                        title = post.displayTitle,
                        synopsis = post.synopsis,
                        posterUrl = post.posterUrl,
                        link = post.link,
                        publishedAt = post.date,
                        updatedAt = post.modified,
                        adPostId = post.postId,
                        adEpisodesUrl = post.episodesUrl,
                    ),
                )
            }
        }
        return merged + adOnly
    }

    /**
     * Merge ToonWorld4All posts into the anime list.
     * Same pattern as mergeAd: match by title key, then fuzzy. Unmatched
     * posts become TW-only entries with -(postId + TW_ID_OFFSET) ids.
     */
    private fun mergeTw(ddaAll: List<Anime>, twPosts: List<TwInfo>): List<Anime> {
        if (twPosts.isEmpty()) return ddaAll
        val byKey = HashMap<String, Anime>()
        for (a in ddaAll) for (k in Titles.keys(a.title)) byKey[k] = a

        val tokensById = HashMap<Int, Set<String>>()
        val bySeasonToken = HashMap<String, MutableList<Anime>>()
        for (a in ddaAll) {
            val t = Titles.baseTokens(a.title)
            if (t.isEmpty()) continue
            tokensById[a.id] = t
            val season = Titles.baseAndSeason(Titles.clean(a.title)).second
            for (w in t) bySeasonToken.getOrPut("$season|$w") { mutableListOf() }.add(a)
        }
        fun fuzzyHit(post: TwInfo): Anime? {
            val t = Titles.baseTokens(post.displayTitle)
            if (t.isEmpty()) return null
            val season = Titles.baseAndSeason(Titles.clean(post.displayTitle)).second
            return t.asSequence()
                .flatMap { w -> bySeasonToken["$season|$w"].orEmpty().asSequence() }
                .distinctBy { it.id }
                .firstOrNull { Titles.sameShow(t, tokensById[it.id].orEmpty()) }
        }

        val merged = ddaAll.toMutableList()
        val twOnly = ArrayList<Anime>()
        for (post in twPosts) {
            val hit = Titles.keys(post.displayTitle).firstNotNullOfOrNull { byKey[it] }
                ?: fuzzyHit(post)
            if (hit != null) {
                twByAnimeId[hit.id] = post
                val i = merged.indexOfFirst { it.id == hit.id }
                if (i >= 0) {
                    merged[i] = merged[i].copy(twPostId = post.postId, twEpisodesUrl = post.episodesUrl)
                }
            } else {
                val id = -(post.postId + TW_ID_OFFSET)
                twByAnimeId[id] = post
                twOnly.add(
                    Anime(
                        id = id,
                        slug = post.slug,
                        title = post.displayTitle,
                        synopsis = post.synopsis,
                        posterUrl = post.posterUrl,
                        link = post.link,
                        publishedAt = post.date,
                        updatedAt = post.modified,
                        twPostId = post.postId,
                        twEpisodesUrl = post.episodesUrl,
                    ),
                )
            }
        }
        return merged + twOnly
    }

    /**
     * COLLAPSE near-duplicate cards into a single card carrying every source.
     *
     * When cross-site matching fails (romaji vs English title, different season
     * numbering, one site wording the name differently), the same anime arrives
     * as separate ADH-only / AD-only / DDA cards. The old pass merely parked
     * them next to each other, so the user still saw the same show two or three
     * times, and each copy carried only its own site's badge and servers.
     *
     * Grouping is two-tier, because exact keys alone left most AnimeDrive cards
     * unmatched:
     *  1. exact title key (base + season), then
     *  2. SAME SEASON + a strong token-set match ([Titles.sameShow]) — this is
     *     what catches "Wistoria: Wand and Sword" vs "Wistoria Wand & Sword".
     * Tier 2 only compares cards that share at least one significant word (via
     * the inverted index below), so this stays far from an O(n²) scan.
     *
     * The group is then fused: one card keeps the richest identity (poster,
     * synopsis, English title) and absorbs the others' source links, so its
     * badges and its server list agree. [adhByAnimeId] / [adByAnimeId] are
     * re-pointed at the surviving id — [episodes] reads those maps, so without
     * the re-point the absorbed source's episodes would vanish.
     *
     * Two entries that both carry a DISTINCT positive DDA id are never fused:
     * that means DDA itself has them as separate anime, and overriding that is
     * how unrelated shows get swallowed. Those stay adjacent instead.
     */
    private fun collapseDuplicates(all: List<Anime>): List<Anime> {
        if (all.size < 2) return all
        // Union-find over both tiers: entries that match join one group.
        val parent = IntArray(all.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Compare on the LEARNED English name when we have one: a DDA card's
        // own title is romaji, so it can never key/token-match its English
        // ADH/AD twin — that mismatch is exactly the duplicate card the user
        // sees, and the one that only "heals" after a detail-open learns the
        // name. Every slug with a learned English title compares on that name;
        // every other card keeps its own title.
        val effective = all.map { a -> englishTitles[a.slug]?.takeIf { it.isNotBlank() } ?: a.title }

        // Tier 1: exact key.
        val firstForKey = HashMap<String, Int>()
        for (i in effective.indices) {
            for (k in Titles.keys(effective[i])) {
                val prev = firstForKey.putIfAbsent(k, i)
                if (prev != null) union(prev, i)
            }
        }

        // Tier 2: same season + strong token match, restricted to cards sharing
        // a word. Season comes from the cleaned title, so "Season 04" == "4".
        val tokens = effective.map { Titles.baseTokens(it) }
        val seasons = effective.map { Titles.baseAndSeason(Titles.clean(it)).second }
        val byToken = HashMap<String, MutableList<Int>>()
        for ((i, t) in tokens.withIndex()) {
            for (w in t) byToken.getOrPut(w) { mutableListOf() }.add(i)
        }
        val compared = HashSet<Long>()
        for ((_, idxs) in byToken) {
            // A word shared by half the catalog ("season", "world") says
            // nothing — skip those buckets instead of comparing everything.
            if (idxs.size > TOKEN_BUCKET_LIMIT) continue
            for (x in idxs.indices) {
                for (y in x + 1 until idxs.size) {
                    val i = idxs[x]
                    val j = idxs[y]
                    if (seasons[i] != seasons[j]) continue
                    if (find(i) == find(j)) continue
                    val pair = i.toLong() * all.size + j
                    if (!compared.add(pair)) continue
                    if (Titles.sameShow(tokens[i], tokens[j])) union(i, j)
                }
            }
        }

        // Group members, preserving feed order within each group.
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in all.indices) groups.getOrPut(find(i)) { mutableListOf() }.add(i)

        val result = ArrayList<Anime>(all.size)
        val emitted = HashSet<Int>()      // group roots already emitted
        for ((idx, anime) in all.withIndex()) {
            val root = find(idx)
            if (!emitted.add(root)) continue
            val members = groups[root] ?: listOf(idx)
            if (members.size == 1) {
                result.add(anime)
                continue
            }
            val cards = members.map { all[it] }
            // Refuse to fuse two different DDA anime — keep them adjacent.
            val ddaIds = cards.mapNotNull { it.id.takeIf { id -> id > 0 } }.distinct()
            if (ddaIds.size > 1) {
                result.addAll(cards)
                continue
            }
            result.add(fuse(cards))
        }
        return result
    }

    /**
     * Fuse one duplicate group into a single card. The primary (richest source)
     * supplies identity; the rest contribute whichever source links it lacks.
     */
    private fun fuse(cards: List<Anime>): Anime {
        // Richest first: a card carrying more sites beats a lone twin, and a
        // DDA-backed card (positive id) carries the poster/synopsis/episodes.
        // TW/AD also count: a fused "TW+AD" is better than a lone TW-only one.
        val primary = cards.minByOrNull { a ->
            when {
                a.adhPostId != null && a.id > 0 && a.adPostId != null && a.twPostId != null -> 0  // ALL
                a.adhPostId != null && a.id > 0 -> 1   // merged with DDA
                a.id > 0 -> 2                          // DDA only
                a.adhPostId != null && a.twPostId != null -> 3  // ADH+TW
                a.adhPostId != null -> 4               // ADH-only
                a.adPostId != null && a.twPostId != null -> 5   // AD+TW
                a.adPostId != null -> 6                // AD-only
                else -> 7                              // TW-only
            }
        } ?: cards.first()
        val others = cards.filter { it !== primary }

        val adhSource = cards.firstOrNull { it.adhPostId != null }
        val adSource = cards.firstOrNull { it.adPostId != null }
        val twSource = cards.firstOrNull { it.twPostId != null }
        val fused = primary.copy(
            // ADH titles are English and carry the season — the best label we
            // have. AD is English too and is preferred over a romaji primary
            // (a DDA-only card outranks AD in [primary], so without this an
            // AD+DDA group kept the romaji DDA title).
            title = adhSource?.title ?: adSource?.title ?: primary.title,
            synopsis = primary.synopsis.ifBlank { others.firstOrNull { it.synopsis.isNotBlank() }?.synopsis.orEmpty() },
            posterUrl = primary.posterUrl.ifBlank { others.firstOrNull { it.posterUrl.isNotBlank() }?.posterUrl.orEmpty() },
            adhPostId = primary.adhPostId ?: adhSource?.adhPostId,
            adhEpisodesUrl = primary.adhEpisodesUrl ?: adhSource?.adhEpisodesUrl,
            adPostId = primary.adPostId ?: adSource?.adPostId,
            adEpisodesUrl = primary.adEpisodesUrl ?: adSource?.adEpisodesUrl,
            twPostId = primary.twPostId ?: twSource?.twPostId,
            twEpisodesUrl = primary.twEpisodesUrl ?: twSource?.twEpisodesUrl,
            // Recency/first-release across every copy.
            updatedAt = cards.maxOf { it.updatedAt },
            latestEpisodeAt = cards.maxOf { it.latestEpisodeAt },
            publishedAt = cards.mapNotNull { it.publishedAt.ifBlank { null } }.minOrNull()
                ?: primary.publishedAt,
            // Upcoming only if EVERY copy is — one site having episodes is enough.
            upcoming = cards.all { it.upcoming },
        )
        // Re-point the source maps at the surviving id, otherwise episodes()
        // looks up the absorbed card's id and finds nothing.
        for (c in others) {
            adhByAnimeId[c.id]?.let { adhByAnimeId.putIfAbsent(fused.id, it) }
            adByAnimeId[c.id]?.let { adByAnimeId.putIfAbsent(fused.id, it) }
            twByAnimeId[c.id]?.let { twByAnimeId.putIfAbsent(fused.id, it) }
        }
        return fused
    }

    /**
     * Search across all four sites, ranked by how well each result's TITLE
     * matches the query.
     *
     * ACCURACY — WordPress' `search` parameter matches the post BODY as well as
     * the title, and results come back in recency order, so a plain merge puts
     * shows whose synopsis merely mentions the word ahead of the show being
     * looked for. Results are therefore partitioned on their OWN titles after
     * the merge ([rankSearch]): exact > starts-with > contains > word-order >
     * one-typo. A content-only hit is hidden only when some real title match
     * exists, so recall is never silently lost. The in-memory catalogs are
     * scanned too: the sites' own search cannot find word-order variants
     * ("slime reincarnated") or typos, and those are exactly what a TV remote's
     * on-screen keyboard produces.
     *
     * SPEED — two things made searches slow: every press re-asked all four
     * sites (the screen debounces, but back-navigation and the season switcher
     * re-ran the whole thing), and the merge ran the [MAX_SLUG_LOOKUPS] HTML
     * lane. Results are cached per normalized query for [SEARCH_TTL_MS] on a
     * small LRU, and the merge runs the feed lane ([Network.feed]), which caps
     * adhlinks page fetches at [FEED_SLUG_LOOKUPS].
     */
    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val qNorm = SearchMatch.normalize(query)
        if (qNorm.isEmpty()) return@withContext emptyList()
        searchCache[qNorm].fresh(SEARCH_TTL_MS)?.let { return@withContext it }
        awaitStores()
        coroutineScope {
            // ADH first-class: WP search + a local scan of the cached catalog.
            val adhDeferred = async { runCatching { adh.search(query) }.getOrDefault(emptyList()) }
            val adDeferred = async { runCatching { ad.search(query) }.getOrDefault(emptyList()) }
            val twDeferred = async { runCatching { tw.search(query) }.getOrDefault(emptyList()) }
            val qTokens = SearchMatch.tokens(query)
            val scannable = qNorm.length >= SearchMatch.MIN_QUERY_LENGTH
            // STRICT title scan of the in-memory catalogs: no network once they
            // are warm, and the part that still works when a site's own search
            // is down, rate-limited, or has filled its 20 result slots with
            // content matches. The typo pass deliberately lives further down.
            fun strictHit(title: String): Boolean =
                scannable && SearchMatch.tier(title, qNorm, qTokens) > SearchMatch.TIER_NONE
            var fromAdhCatalog = adhCatalog?.value.orEmpty().filter { strictHit(it.displayTitle) }
            var fromAdCatalog = adCatalog?.value.orEmpty().filter { strictHit(it.displayTitle) }
            var fromTwCatalog = twCatalog?.value.orEmpty().filter { strictHit(it.displayTitle) }

            val adhSearch = adhDeferred.await()
            val adSearch = adDeferred.await()
            val twSearch = twDeferred.await()

            // TYPO PASS — only when NOTHING matched a title strictly. An answer
            // that already exists must never pay for a whole-catalog scan on
            // every key press: this box has few cores and little RAM. A dead end
            // is the one case where the scan is worth its cost, because the
            // viewer is looking at "No results".
            val anyStrict = fromAdhCatalog.isNotEmpty() || fromAdCatalog.isNotEmpty() ||
                fromTwCatalog.isNotEmpty() ||
                (adhSearch + adSearch).any {
                    SearchMatch.tier(it.displayTitle, qNorm, qTokens) > SearchMatch.TIER_NONE
                } ||
                twSearch.any { SearchMatch.tier(it.displayTitle, qNorm, qTokens) > SearchMatch.TIER_NONE }
            if (!anyStrict && qNorm.length >= SearchMatch.FUZZY_MIN_LENGTH) {
                fun typoHit(title: String): Boolean =
                    SearchMatch.fuzzyTier(title, qTokens) > SearchMatch.TIER_NONE
                fromAdhCatalog = adhCatalog?.value.orEmpty().filter { typoHit(it.displayTitle) }
                fromAdCatalog = adCatalog?.value.orEmpty().filter { typoHit(it.displayTitle) }
                fromTwCatalog = twCatalog?.value.orEmpty().filter { typoHit(it.displayTitle) }
            }

            val adhResults = (adhSearch + fromAdhCatalog).distinctBy { it.postId }
            val adResults = (adSearch + fromAdCatalog).distinctBy { it.postId }
            val twResults = (twSearch + fromTwCatalog).distinctBy { it.postId }
            // Feed lane: a search is a critical path too, and the background
            // slug sweep finishes whatever this small budget skips.
            val merged = mergeAdhFirst(adhResults, Network.feed)
            // Collapse cross-site twins here too — a search for one show used to
            // return it two or three times, once per site. Different seasons
            // have different title keys, so the season switcher still sees them
            // all as separate entries. Upcoming placeholders stay hidden here
            // as well.
            val ranked = rankSearch(
                collapseDuplicates(attachKnownSources(mergeTw(mergeAd(merged, adResults), twResults)))
                    .filter { !it.upcoming },
                query,
            )
            // Never cache an EMPTY answer: a site hiccup would then read as "no
            // such anime" for the whole TTL.
            if (ranked.isNotEmpty()) searchCache[qNorm] = Cached(System.currentTimeMillis(), ranked)
            ranked
        }
    }

    /**
     * Order search hits by how well their title matches, then by recency.
     *
     * WordPress matches the search term against the post body, which is why a
     * query for one show also returned unrelated posts whose synopsis happens to
     * contain the word — in recency order, so they could sit above the show
     * itself. Tiering after the merge fixes the ORDER without narrowing what the
     * sites were asked for: content-only hits are hidden only when at least one
     * real title hit exists, and returned (recency-ordered, as before) when none
     * does. The search box must never answer "nothing" because the one show the
     * user wanted was titled differently from its own description.
     */
    private fun rankSearch(list: List<Anime>, query: String): List<Anime> {
        if (list.size < 2) return list
        val qNorm = SearchMatch.normalize(query)
        if (qNorm.isEmpty()) return list
        val qTokens = SearchMatch.tokens(query)
        val tiers = IntArray(list.size) { SearchMatch.tier(list[it].title, qNorm, qTokens) }
        // Typo pass ONLY when no title matched strictly: it exists to turn a
        // dead-end search into a result, so a key press that already had a real
        // answer never pays for it.
        if (tiers.none { it > SearchMatch.TIER_NONE } && qNorm.length >= SearchMatch.FUZZY_MIN_LENGTH) {
            for (i in tiers.indices) tiers[i] = SearchMatch.fuzzyTier(list[i].title, qTokens)
        }
        val titled = tiers.any { it > SearchMatch.TIER_NONE }
        val keep = if (titled) tiers.indices.filter { tiers[it] > SearchMatch.TIER_NONE } else tiers.indices.toList()
        return keep.sortedWith(
            compareByDescending<Int> { tiers[it] }
                .thenByDescending { maxOf(list[it].latestEpisodeAt, list[it].updatedAt, list[it].publishedAt) },
        ).map { list[it] }
    }

    /**
     * All seasons of one show, season-ascending, for the detail screen's
     * season switcher. Both sites are searched by the English base name; the
     * merge machinery pulls in cross-site identities (an ADH-only old season
     * still shows up, as does a DDA season ADH never uploaded). Only entries
     * whose base name matches exactly survive — "Slime" must not drag in
     * "I've Been Killing Slimes for 300 Years".
     */
    suspend fun seasons(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        seasonsCache[anime.id].fresh(ANIME_TTL_MS)?.let { return@withContext it }
        val (base, _) = Titles.baseAndSeason(anime.title)
        val baseKey = base.lowercase().filter { it.isLetterOrDigit() }
        if (baseKey.isBlank()) return@withContext listOf(anime)
        val results = try {
            search(Titles.searchQuery(base))
        } catch (e: Exception) {
            emptyList()
        }
        val sameShow = results.filter {
            Titles.baseAndSeason(it.title).first.lowercase().filter(Char::isLetterOrDigit) == baseKey
        }
        (sameShow + anime)
            .distinctBy { it.id }
            // The same show can appear twice per season: the DDA card and an
            // ADH-only twin the merge hasn't collapsed yet (romaji vs English
            // title). One entry per (adhPostId) and one per season number —
            // prefer the merged/DDA card (positive id) over the ADH-only one.
            .sortedByDescending { if (it.id == anime.id) 2 else if (it.id > 0) 1 else 0 }
            .distinctBy { it.adhPostId ?: it.id }
            .distinctBy { Titles.baseAndSeason(it.title).second }
            .sortedBy { Titles.baseAndSeason(it.title).second }
            .also { seasonsCache[anime.id] = Cached(System.currentTimeMillis(), it) }
    }

    suspend fun anime(id: Int): Anime = withContext(Dispatchers.IO) {
        animeCache[id].fresh(ANIME_TTL_MS)?.let { return@withContext it }
        // Click-time: the user is staring at a spinner. Background sweeps park
        // at the gate until this returns.
        InteractionGate.priority { animeUncached(id) }
    }

    private suspend fun animeUncached(id: Int): Anime = withContext(Dispatchers.IO) {
        awaitStores()
        // TW-only anime (id <= -TW_ID_OFFSET) live entirely on ToonWorld4All.
        if (id <= -TW_ID_OFFSET) {
            val twPostId = -id - TW_ID_OFFSET
            val info = twByAnimeId[id] ?: tw.post(twPostId)?.also { twByAnimeId[id] = it }
                ?: throw java.io.IOException("ToonWorld4All post $twPostId not found")
            return@withContext Anime(
                id = id,
                slug = info.slug,
                title = info.displayTitle,
                synopsis = info.synopsis,
                posterUrl = info.posterUrl,
                link = info.link,
                publishedAt = info.date,
                updatedAt = info.modified,
                twPostId = info.postId,
                twEpisodesUrl = info.episodesUrl,
            ).also { animeCache[id] = Cached(System.currentTimeMillis(), it) }
        }
        // AD-only anime (id <= -AD_ID_OFFSET, but > -TW_ID_OFFSET) live entirely on AnimeDrive.
        if (id <= -AD_ID_OFFSET && id > -TW_ID_OFFSET) {
            val adPostId = -id - AD_ID_OFFSET
            val info = adByAnimeId[id] ?: ad.post(adPostId)?.also { adByAnimeId[id] = it }
                ?: throw java.io.IOException("AnimeDrive post $adPostId not found")
            return@withContext Anime(
                id = id,
                slug = info.slug,
                title = info.displayTitle,
                synopsis = info.synopsis,
                posterUrl = info.posterUrl,
                link = info.link,
                publishedAt = info.date,
                updatedAt = info.modified,
                adPostId = info.postId,
                adEpisodesUrl = info.episodesUrl,
            ).also { animeCache[id] = Cached(System.currentTimeMillis(), it) }
        }
        // ADH-only anime (negative id, but > -AD_ID_OFFSET) live on AnimeDubHindi.
        if (id < 0) {
            val info = adhByAnimeId[id] ?: adh.post(-id)?.also { adhByAnimeId[id] = it }
                ?: throw java.io.IOException("ADH post ${-id} not found")
            return@withContext Anime(
                id = id,
                slug = info.slug,
                title = info.displayTitle,
                synopsis = info.synopsis,
                posterUrl = info.posterUrl,
                link = info.link,
                publishedAt = info.date,
                updatedAt = info.modified,
                adhPostId = info.postId,
                adhEpisodesUrl = info.episodesUrl,
            ).also { animeCache[id] = Cached(System.currentTimeMillis(), it) }
        }
        // NOTHING LEFT TO LOAD. A positive id was a DesiDubAnime post id, and
        // that site answers every request with Cloudflare's challenge, so there
        // is no catalogue to read. Cached feeds from older builds can still
        // carry such ids, so this is a normal miss, not an impossible state.
        throw java.io.IOException("Anime $id is no longer available")
    }

    /**
     * Find the AnimeDrive post for an anime the feed merge didn't cover —
     * the AD counterpart of [findAdhFor], minus the slug machinery (AD posts
     * carry no "Watch online" link back to DDA, so title matching is all we
     * have). Catalog first, then AD's own search.
     */
    private suspend fun findAdFor(a: Anime): AdhInfo? = withContext(Dispatchers.IO) {
        val myKeys = Titles.keys(a.title)
        val myTokens = Titles.baseTokens(a.title)
        val mySeason = Titles.baseAndSeason(Titles.clean(a.title)).second
        fun matches(post: AdhInfo): Boolean {
            if (Titles.keys(post.displayTitle).any { it in myKeys }) return true
            if (Titles.baseAndSeason(Titles.clean(post.displayTitle)).second != mySeason) return false
            return Titles.sameShow(myTokens, Titles.baseTokens(post.displayTitle))
        }
        adCatalog?.value.orEmpty().firstOrNull(::matches)?.let { return@withContext it }
        val (englishBase, _) = Titles.baseAndSeason(a.title)
        runCatching { ad.search(Titles.searchQuery(englishBase)) }
            .getOrDefault(emptyList())
            .firstOrNull(::matches)
    }

    /**
     * Find the ToonWorld4All post for an anime the feed merge didn't cover.
     * Same pattern as findAdFor: title-key match from the catalog, then search.
     */
    private suspend fun findTwFor(a: Anime): TwInfo? = withContext(Dispatchers.IO) {
        val myKeys = Titles.keys(a.title)
        val myTokens = Titles.baseTokens(a.title)
        val mySeason = Titles.baseAndSeason(Titles.clean(a.title)).second
        fun matches(post: TwInfo): Boolean {
            if (Titles.keys(post.displayTitle).any { it in myKeys }) return true
            if (Titles.baseAndSeason(Titles.clean(post.displayTitle)).second != mySeason) return false
            return Titles.sameShow(myTokens, Titles.baseTokens(post.displayTitle))
        }
        twCatalog?.value.orEmpty().firstOrNull(::matches)?.let { return@withContext it }
        val (englishBase, _) = Titles.baseAndSeason(a.title)
        runCatching { tw.search(Titles.searchQuery(englishBase)) }
            .getOrDefault(emptyList())
            .firstOrNull(::matches)
    }

    /**
     * Find the ADH post for a DDA anime the feed merge didn't cover. ADH's WP
     * search can't find romaji titles ("Tian Guan Ci Fu" -> 0 results for an
     * English-titled post), so the catalog + learned adhlinks slugs are
     * checked FIRST — the slug link is definitive. English search remains as
     * the fallback for anime whose English name we do know.
     */
    private suspend fun findAdhFor(a: Anime): AdhInfo? = withContext(Dispatchers.IO) {
        // 1) Any already-learned slug pointing at this anime?
        val catalog = adhCatalog?.value ?: runCatching { adhPostsForMerge() }.getOrDefault(emptyList())
        catalog.firstOrNull { learnedDdaSlug[it.postId] == a.slug }
            ?.let { return@withContext it }

        // Both sides are English-titled now: AnimeDubHindi names its posts in
        // English, so the old "scrape the English name off the DDA page first"
        // step went with the site.
        val self = a
        val (englishBase, season) = Titles.baseAndSeason(self.title)
        val myKeys = Titles.keys(self.title)

        // 2) Title-key match against the full catalog (works once the DDA side
        //    carries its English name).
        catalog.firstOrNull { post -> Titles.keys(post.displayTitle).any { it in myKeys } }
            ?.let { return@withContext it }

        // 3) Candidates to CONFIRM via their adhlinks page (fetched once,
        //    persisted). English search ALWAYS runs alongside the related-name
        //    filter — gating it behind ifEmpty broke on false positives (slug
        //    word "dou" in tsu-ga-mic-ise-dou matched "BAKI-DOU", which then
        //    shadowed the real Tsukimichi post the search would have found).
        //    Posts whose learned slug is BLANK are retried too — "" may be a
        //    stale miss from an old parser and used to poison the post forever.
        val unlearned = catalog.filter { learnedDdaSlug[it.postId].isNullOrBlank() }
        val byName = unlearned
            .filter { Titles.related(it.titleBase, self.title) || Titles.related(it.titleBase, a.slug.replace('-', ' ')) }
            .take(5)
        val bySearch = runCatching { adh.search(Titles.searchQuery(englishBase)) }
            .getOrDefault(emptyList())
            .take(5)
        val candidates = (bySearch + byName)
            .distinctBy { it.postId }
            .sortedBy { if (it.season == season) 0 else 1 }
            .ifEmpty { unlearned.take(8) } // catalog is modified-desc: newest first
        // Fetch every unresolved candidate's adhlinks page IN PARALLEL (bulk
        // client, 3-per-host): the sequential loop turned one detail-open into
        // up to 8 back-to-back HTML round-trips — the worst of the cold-start
        // "clicks respond late" stalls. A blank "" slug is a stale miss and is
        // retried here too.
        val needFetch = candidates.filter { learnedDdaSlug[it.postId].isNullOrBlank() }
        // On the SHARED client (parallel, cached after first fetch): this runs
        // on the detail-open critical path and must not queue behind the
        // cold-start sweep flood on the bulk lane.
        needFetch.map { post ->
            post to async { adhEpisodePage(post.episodesUrl) }
        }.forEach { (post, deferred) ->
            val page = deferred.await()
            if (page.episodes.isNotEmpty() || page.ddaSlug != null) {
                val slug = page.ddaSlug.orEmpty()
                learnedDdaSlug[post.postId] = slug
                slugStore?.putAll(mapOf(post.postId to slug))
            }
        }
        for (post in candidates) {
            val slug = learnedDdaSlug[post.postId]?.takeIf { it.isNotBlank() }
            if (slug == a.slug) return@withContext post
        }
        null
    }

    /**
     * All episodes for an anime, ascending, merged across both sites:
     * DDA episodes get hub quality links attached by number; episodes that only
     * exist on ADH yet are appended with a blank watchUrl (hub servers only).
     */
    suspend fun episodes(animeId: Int): List<Episode> = withContext(Dispatchers.IO) {
        episodesCache[animeId].fresh(EPISODES_TTL_MS)?.let { return@withContext it }
        // Click-time (detail open / Continue Watching -> player): holds the gate
        // so the sweeps stop competing for connections while this resolves.
        InteractionGate.priority { episodesUncached(animeId) }
    }

    private suspend fun episodesUncached(animeId: Int): List<Episode> = withContext(Dispatchers.IO) {
        coroutineScope {
            // TW-only anime (id <= -TW_ID_OFFSET): fetch from ToonWorld4All only.
            if (animeId <= -TW_ID_OFFSET) {
                val twPostId = -animeId - TW_ID_OFFSET
                val twInfo = twByAnimeId[animeId] ?: tw.post(twPostId)?.also { twByAnimeId[animeId] = it }
                val eps = twInfo?.let { info -> twEpisodePages(info) } ?: emptyList()
                return@coroutineScope eps.map { h ->
                    Episode(
                        id = -(animeId.hashCode() * 1000 + h.number),
                        number = "Episode ${h.number}",
                        numberInt = h.number,
                        title = "",
                        thumbnail = "",
                        watchUrl = "",
                        hubLinks = h.links.filter { it.hoster in STREAMABLE_HOSTERS },
                    )
                }.filter { it.hubLinks.isNotEmpty() }.sortedBy { it.numberInt }.also {
                    if (it.isNotEmpty()) episodesCache[animeId] = Cached(System.currentTimeMillis(), it)
                }
            }

            // AD-only anime (id <= -AD_ID_OFFSET, but > -TW_ID_OFFSET): fetch from AnimeDrive only.
            if (animeId <= -AD_ID_OFFSET && animeId > -TW_ID_OFFSET) {
                val adPostId = -animeId - AD_ID_OFFSET
                val adInfo = adByAnimeId[animeId] ?: ad.post(adPostId)?.also { adByAnimeId[animeId] = it }
                val adPage = adInfo?.let { adEpisodePage(it.episodesUrl) } ?: AdhEpisodePage(emptyList())
                return@coroutineScope adPage.episodes.map { h ->
                    Episode(
                        id = -(animeId.hashCode() * 1000 + h.number),
                        number = "Episode ${h.number}",
                        numberInt = h.number,
                        title = "",
                        thumbnail = "",
                        watchUrl = "",
                        hubLinks = h.links.filter { it.hoster in STREAMABLE_HOSTERS },
                    )
                }.filter { it.hubLinks.isNotEmpty() }.sortedBy { it.numberInt }.also {
                    if (it.isNotEmpty()) episodesCache[animeId] = Cached(System.currentTimeMillis(), it)
                }
            }

            // ADH-only anime may need their post re-fetched (e.g. Continue
            // Watching straight after process death, before any feed load).
            // DDA anime missing their ADH match (older ADH post outside the
            // feed window) get the on-demand search via anime() -> findAdhFor.
            var adhInfo = adhByAnimeId[animeId]
                ?: if (animeId < 0) {
                    adh.post(-animeId)?.also { adhByAnimeId[animeId] = it }
                } else {
                    runCatching { anime(animeId) }.getOrNull()
                    adhByAnimeId[animeId]
                }
            var hubDeferred = adhInfo?.let { info -> async { adhEpisodePage(info.episodesUrl) } }

            // No ADH match known yet? Search RIGHT NOW on detail open — even
            // when DDA has episodes. (Old bug: the fallback only ran when DDA
            // was empty, so romaji-titled anime with DDA episodes — Tsukimichi
            // S2 — never got their hub servers until the background sweep
            // finished.) findAdhFor is cheap after the first time: learned
            // slugs persist and adhlinks pages are cached.
            //
            // MUST run BEFORE the AnimeDrive/ToonWorld lookups below: after a
            // cold process death those maps start empty, and anime() (called
            // here on a miss) is what repopulates all three via findAdhFor /
            // findAdFor / findTwFor. Reading them first left AD/TW null even
            // though the warm-up had just filled their maps — merged anime
            // then opened with ADH servers only.
            if (adhInfo == null && animeId > 0) {
                val a = animeCache[animeId]?.value ?: runCatching { anime(animeId) }.getOrNull()
                if (a != null) {
                    adhInfo = findAdhFor(a)?.also {
                        adhByAnimeId[animeId] = it
                        animeCache.remove(animeId) // next anime() rebuilds with the link
                        invalidateFeed()           // and the card gets its ADH badge
                    }
                    hubDeferred = adhInfo?.let { info -> async { adhEpisodePage(info.episodesUrl) } }
                }
            }

            // AnimeDrive info — read AFTER the ADH warm-up above, plus its own
            // on-demand search so an AD post outside the merge window still
            // contributes servers on detail open (same pattern as findAdhFor).
            // EVERY card, whatever its id sign. This used to be gated behind
            // `animeId > 0` — i.e. only a card that had matched a DesiDubAnime
            // entry ever searched AnimeDrive on demand. With that catalogue gone
            // every card has a NEGATIVE id, so the lookup never ran and the
            // episode list came back AnimeDubHindi-only: the "card shows
            // ADH + AD + T4A, but the player only had ADH" report.
            var adInfo = adByAnimeId[animeId]
            if (adInfo == null) {
                val a = animeCache[animeId]?.value ?: runCatching { anime(animeId) }.getOrNull()
                if (a != null) {
                    adInfo = findAdFor(a)?.also {
                        adByAnimeId[animeId] = it
                        invalidateFeed()
                    }
                }
            }
            var adHubDeferred = adInfo?.let { info -> async { adEpisodePage(info.episodesUrl) } }

            // ToonWorld4All — same treatment. T4A now serves ONE PAGE PER
            // EPISODE, so every page of the post is fetched, not just the first.
            var twInfo = twByAnimeId[animeId]
            if (twInfo == null) {
                val a = animeCache[animeId]?.value ?: runCatching { anime(animeId) }.getOrNull()
                if (a != null) {
                    twInfo = findTwFor(a)?.also {
                        twByAnimeId[animeId] = it
                        invalidateFeed()
                    }
                }
            }
            var twHubDeferred: kotlinx.coroutines.Deferred<List<TwEpisode>>? =
                twInfo?.let { info -> async { twEpisodePages(info) } }

            // ToonWorld4All gets the same on-demand treatment.
            // (Moved ABOVE the awaits — see the ADH block comment.)

            val hub = hubDeferred?.await()?.episodes.orEmpty()
                .filter { it.number in 1..MAX_HUB_EPISODE_NUMBER }
                .map { ep ->
                    HubEpisode(
                        number = ep.number,
                        links = ep.links.filter { it.hoster in STREAMABLE_HOSTERS },
                    )
                }.filter { it.links.isNotEmpty() }
            val adHub = adHubDeferred?.await()?.episodes.orEmpty()
                .filter { it.number in 1..MAX_HUB_EPISODE_NUMBER }
                .map { ep ->
                    HubEpisode(
                        number = ep.number,
                        links = ep.links.filter { it.hoster in STREAMABLE_HOSTERS },
                    )
                }.filter { it.links.isNotEmpty() }
            val twHub = twHubDeferred?.await().orEmpty()
                .filter { it.number in 1..MAX_HUB_EPISODE_NUMBER }
                .map { tw ->
                    HubEpisode(
                        number = tw.number,
                        links = tw.links.filter { it.hoster in STREAMABLE_HOSTERS },
                    )
                }.filter { it.links.isNotEmpty() }

            // Debug: Log what was collected
            if (hub.isNotEmpty()) {
                me.desidubanime.tv.data.DebugLog.log("episodes", "ADH: ${hub.size} episodes (${hub.map { it.number }.joinToString(",")})")
            }
            if (adHub.isNotEmpty()) {
                me.desidubanime.tv.data.DebugLog.log("episodes", "AD: ${adHub.size} episodes (${adHub.map { it.number }.joinToString(",")})")
            }
            if (twHub.isNotEmpty()) {
                me.desidubanime.tv.data.DebugLog.log("episodes", "TW: ${twHub.size} episodes (${twHub.map { it.number }.joinToString(",")})")
            }

            // Merge hub links per episode: ADH links first (source priority
            // ADH > AD), then AD's.
            //
            // The dedup key INCLUDES the source. It used to be (quality, hoster)
            // only, which silently threw away every AnimeDrive link whose
            // quality+hoster already existed on ADH — both sites serve HubCloud
            // 720p, so the user only ever saw ONE site's HubCloud servers and
            // had no way to fall back to the other when a link was dead.
            val hubByNumber = HashMap<Int, List<HubLink>>()
            for (h in hub) hubByNumber[h.number] = h.links
            for (h in adHub) {
                val existing = hubByNumber[h.number]
                if (existing == null) {
                    hubByNumber[h.number] = h.links
                } else {
                    val existingKeys = existing.map { linkKey(it) }.toSet()
                    hubByNumber[h.number] = existing + h.links.filter { linkKey(it) !in existingKeys }
                }
            }
            for (h in twHub) {
                val existing = hubByNumber[h.number]
                if (existing == null) {
                    hubByNumber[h.number] = h.links
                } else {
                    val existingKeys = existing.map { linkKey(it) }.toSet()
                    hubByNumber[h.number] = existing + h.links.filter { linkKey(it) !in existingKeys }
                }
            }

            // EVERY source's links for a number become that episode's
            // servers: AnimeDubHindi first (its parse order), then AnimeDrive,
            // then ToonWorld4All. A number only T4A carries is still a real
            // episode — its rows are marked "· unlock" in the menu, never hidden.
            val merged = hubByNumber.entries.sortedBy { it.key }.map { (num, links) ->
                Episode(
                    id = -(animeId.hashCode() * 1000 + num),
                    number = "Episode $num",
                    numberInt = num,
                    title = "",
                    thumbnail = "",
                    watchUrl = "",
                    hubLinks = links,
                )
            }
            merged.also {
                // NEVER cache an empty list — a transient miss on a fresh
                // upload would otherwise stick as "0 episodes" for the TTL.
                if (it.isNotEmpty()) episodesCache[animeId] = Cached(System.currentTimeMillis(), it)
            }
        }
    }

    /**
     * Parse an adhlinks page with cache. The slug sweep passes [Network.bulk]
     * so its thousands of pages never queue ahead of click-time requests on
     * the fast client; results are still cached for the fast path to reuse.
     */
    private fun adhEpisodePage(url: String, client: OkHttpClient = Network.client): AdhEpisodePage {
        adhPageCache[url].fresh(EPISODES_TTL_MS)?.let { return it }
        val page = adh.episodePage(url, client)
        // Cache only parsed pages; a Cloudflare hiccup shouldn't stick as
        // "no hub links" until the TTL runs out.
        if (page.episodes.isNotEmpty() || page.ddaSlug != null) {
            adhPageCache[url] = Cached(System.currentTimeMillis(), page)
        }
        return page
    }

    /** link.animedrive.in page cache — same pattern as [adhEpisodePage]. */

    private suspend fun adEpisodePage(url: String, client: OkHttpClient = Network.client): AdhEpisodePage {
        adPageCache[url].fresh(EPISODES_TTL_MS)?.let { return it }
        val page = ad.episodePage(url, client)
        if (page.episodes.isNotEmpty()) {
            adPageCache[url] = Cached(System.currentTimeMillis(), page)
        }
        return page
    }

    /** ToonWorld4All episode page cache — same pattern. */

    private fun twEpisodePage(url: String, client: OkHttpClient = Network.client): TwEpisodePage {
        twPageCache[url].fresh(EPISODES_TTL_MS)?.let { return it }
        val page = tw.episodePage(url, client)
        if (page.episodes.isNotEmpty()) {
            twPageCache[url] = Cached(System.currentTimeMillis(), page)
        }
        return page
    }

    /**
     * EVERY episode of a T4A post. T4A moved to one PAGE PER EPISODE
     * ("/episode/<slug>-<s>x<n>"), each carrying its own __PROPS__ JSON with
     * that episode's quality/hoster links — fetching only [TwInfo.episodesUrl]
     * (the first URL) is why T4A-sourced shows rendered 0 or 1 episodes.
     * Pages run in small parallel batches on the click-time client; results
     * land in the per-URL cache above, so a revisit costs nothing.
     */
    private suspend fun twEpisodePages(info: TwInfo): List<TwEpisode> = coroutineScope {
        val urls = info.episodeUrls.values.filter { it.isNotBlank() }.distinct()
            .ifEmpty {
                info.episodesUrl.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            }
        if (urls.isEmpty()) return@coroutineScope emptyList()
        val sem = kotlinx.coroutines.sync.Semaphore(FEED_LOOKUP_PARALLELISM)
        urls.map { u ->
            async {
                sem.withPermit {
                    // Best-effort per page: one dead/404 episode page must not
                    // blank out the whole season.
                    runCatching { twEpisodePage(u) }.getOrNull()?.episodes.orEmpty()
                }
            }
        }.awaitAll()
            .flatten()
            .filter { it.links.isNotEmpty() && it.number in 1..MAX_HUB_EPISODE_NUMBER }
            .sortedBy { it.number }
    }

    // ---- TW4A standalone page + QR unlock ----

    /**
     * Every anime that exists on ToonWorld4All, newest-updated first —
     * data for the dedicated TW4A grid page. Cards carry negative
     * -(postId+TW_ID_OFFSET) ids, so detail/episodes flow through the
     * existing TW-only paths (pure TW episodes + TW servers).
     */
    suspend fun twOnlyAnime(): List<Anime> = withContext(Dispatchers.IO) {
        awaitStores()
        val posts = twCatalog?.value
            ?: runCatching { tw.latestPosts(perPage = 100, client = Network.bulk) }.getOrDefault(emptyList())
        posts.sortedByDescending { it.modified }
            .map { info ->
                Anime(
                    id = -(info.postId + TW_ID_OFFSET),
                    slug = info.slug,
                    title = info.displayTitle,
                    synopsis = info.synopsis,
                    posterUrl = info.posterUrl,
                    link = info.link,
                    publishedAt = info.date,
                    updatedAt = info.modified,
                    twPostId = info.postId,
                    twEpisodesUrl = info.episodesUrl,
                )
            }
    }

    // ---- mappers ----

    /**
     * Swap the romaji title for the site's English one when we know it.
     * Season must survive the swap: if the romaji name carries a season marker
     * ("Season 2", "2nd Season", trailing "2") and the English one doesn't,
     * append it.
     */
    private fun Anime.withEnglishTitle(): Anime {
        val en = englishTitles[slug] ?: return this
        if (en.equals(title, ignoreCase = true)) return this
        val season = Titles.seasonOf(title)
        val finalTitle =
            if (season != null && Titles.seasonOf(en) == null) "$en Season $season" else en
        return copy(title = finalTitle)
    }

    /** Strip HTML tags/entities that WordPress rendered fields carry. */
    private fun clean(html: String): String =
        Parser.unescapeEntities(html.replace(Regex("<[^>]*>"), " "), false)
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Load the persisted slug/title maps — pure disk reads, no network. They
     * make a cold start's first merge as good as the last session's, and must
     * be in memory BEFORE the first feed merges (a late slug-store load used to
     * re-fetch every adhlinks page on every launch).
     *
     * LAST in the class on purpose: init blocks run in declaration order, and
     * this coroutine touches maps declared throughout the body. Started from
     * higher up, it could read a still-null field from its IO thread.
     */
    init {
        bgScope.launch {
            runCatching {
                slugStore?.load()?.forEach { (k, v) -> learnedDdaSlug.putIfAbsent(k, v) }
            }
            val loaded = runCatching { titleStore?.load() }
                .getOrDefault(emptyMap()) ?: emptyMap()
            loaded.forEach { (k, v) -> englishTitles.putIfAbsent(k, v) }
            titlesDirty = false
            storesLoaded.complete(Unit)
        }
        // Catalog warm is DEFERRED to startBackgroundWork() — it fires AFTER
        // the first feed has loaded and the user has had a chance to interact.
        // Starting it here competed with the feed at cold start (the sweepers
        // and catalogs all hit the same hosts and queues before the user's
        // first click could get a word in).
    }

    private companion object {
        const val NEW_RELEASES_COUNT = 12
        const val PAGE_SIZE = 30                   // sidebar catalog cards per page
        const val NEW_EPISODES_COUNT = 20
        const val FEED_TTL_MS = 60_000L            // home refreshes at most once a minute
        const val ADH_CATALOG_TTL_MS = 10 * 60_000L // full ADH catalog (~6 requests) refetch
        const val ANIME_TTL_MS = 10 * 60_000L      // detail info rarely changes
        /** Search answers kept per normalized query; back-nav and seasons() reuse them. */
        const val SEARCH_TTL_MS = 3 * 60_000L
        const val SEARCH_CACHE_ENTRIES = 12
        const val EPISODES_TTL_MS = 2 * 60_000L    // new episodes should show up quickly
        const val MAX_SLUG_LOOKUPS = 20            // adhlinks/slug fetches per merge (learned once per post, persisted)
        /** Feed-lane cap: the sweep finishes the rest off the critical path. */
        const val FEED_SLUG_LOOKUPS = 6
        const val FEED_LOOKUP_PARALLELISM = 4      // ...of which this many run at once (scrapers use sync execute(), which Dispatcher doesn't throttle)
        /** Settle window before a background catalog/sweep result re-merges the feed. */
        const val RE_MERGE_DEBOUNCE_MS = 6_000L
        /** ...and the app must have been idle this long before that merge starts. */
        const val RE_MERGE_QUIET_MS = 1_500L
        /** Gap between the three catalog warm-ups (one Wi-Fi radio, weak SoC). */
        const val CATALOG_STAGGER_MS = 1_500L
        /**
         * Dead time after the first feed before ANY background job may start.
         * Covers the launch burst: poster decodes, the first focus moves and
         * the ON_RESUME refresh. Background work has no deadline; the user's
         * first impression does.
         */
        const val BACKGROUND_SETTLE_MS = 10_000L
        /** Idle window required before a background job may begin. */
        const val QUIET_BEFORE_BACKGROUND_MS = 2_000L
        /** Below this many learned names, the title listings stay on the feed path. */
        /** Last of the three jobs: names already render, so this can wait. */
        /** Breath between sweep pages so parses do not own every core. */
        const val SWEEP_PAGE_GAP_MS = 250L
        const val TOKEN_BUCKET_LIMIT = 60          // words shared by more cards than this carry no signal for dedup
        const val STORES_WAIT_MS = 4_000L          // cap on waiting for the persisted title/slug maps
        const val AD_ID_OFFSET = 1_000_000         // AD-only ids: -(postId + offset), keeps them distinct from ADH-only (-postId)
        const val TW_ID_OFFSET = 2_000_000         // TW-only ids: -(postId + offset), keeps them distinct from ADH-only (-postId) and AD-only

        /**
         * Sanity cap on hub episode numbers. The scrapers used to mint
         * "episodes" from file sizes ("[Size: 69mb]" -> episode 69), which is
         * how the detail page filled up with grey zombie cards; the parsers
         * are fixed now, but a hard bound here means a future parse slip can
         * never resurrect them.
         */
        const val MAX_HUB_EPISODE_NUMBER = 2999

        /** How long badges wait for ALL source catalogs before releasing anyway. */
        const val SOURCES_BADGE_GRACE_MS = 60_000L
        /**
         * Settle window before a catalogue landing re-merges the feed. Much
         * shorter than [RE_MERGE_DEBOUNCE_MS]: this merge exists to complete the
         * source sets behind the badges, and it should land right after the
         * last catalogue rather than six seconds later.
         */
        const val BADGE_REFIRE_MS = 1_500L
    }
}
