package me.desidubanime.tv

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.launch

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        // Start the launch feed NOW instead of after the splash + first Home
        // composition. The fetch (or the cached feed it warms) is ready by the
        // time Home appears, and HomeViewModel's own load() joins the same
        // in-flight fetch rather than starting a second one. The app always
        // refreshes on start — this just removes the dead wait before it does.
        //
        // The saved DDA host is read INSIDE this coroutine, not with runBlocking
        // on the main thread. The first DataStore read is disk I/O, and blocking
        // the UI thread on it delayed the very first frame — one of the reasons
        // the app felt sticky right after opening. Nothing needs the host before
        // a request is made: WpService's interceptor resolves Api.HOST per call,
        // and this runs before the first one.
        // Warm the seen-state store off the main thread. Its first call parses
        // /seen_state.json, and the first call is made from the Home grid's
        // composition (firstRunAt + a lookup per poster). Doing it here means
        // the grid reads an in-memory map instead of touching the disk while it
        // is trying to paint its first frames.
        me.desidubanime.tv.data.ServiceLocator.appScope.launch {
            runCatching { me.desidubanime.tv.data.ServiceLocator.seenStore.firstRunAt() }
        }
        // ANIMEDRIVE'S EPISODE PAGES NEED A BROWSER, AND THE DETAIL SCREEN
        // NEEDS THEM BEFORE THE PLAYER EXISTS.
        //
        // link.animedrive.in answers every plain HTTP client with a Cloudflare
        // managed challenge (`403 cf-mitigated: challenge`, measured on OkHttp,
        // curl and requests alike), so the only way to read an episode page is
        // the WebView bridge. That bridge used to be installed by the PLAYER's
        // ViewModel — i.e. AFTER the screen that builds the episode list had
        // already run. The detail screen therefore got an empty AD episode page
        // and AnimeDrive contributed no servers at all, which is exactly the
        // "card shows ADH + AD + T4A, but the player only offered ADH" report.
        // Installing it here means every scrape in the process can use it.
        me.desidubanime.tv.scrape.AdPageBrowser.install { url -> adBrowser.html(url, AD_PAGE_MS) }
        me.desidubanime.tv.data.ServiceLocator.appScope.launch {
            runCatching { me.desidubanime.tv.data.ServiceLocator.repository.homeFeed() }
        }
        // Self-update check, deliberately a few seconds BEHIND the feed: the
        // home grid and the GitHub lookup must never compete for the launch
        // network, and the notice should not land on top of a cold splash.
        me.desidubanime.tv.data.ServiceLocator.appScope.launch {
            kotlinx.coroutines.delay(5_000)
            runCatching { me.desidubanime.tv.data.update.UpdateManager.checkOnLaunch() }
        }
    }

    /**
     * Persists the stack trace + recent debug-log tail to disk on a crash.
     * Next launch the contents surface in Settings → Server log, so the user
     * can copy the exact failure from a TV box that has no adb access.
     */
    private fun installCrashHandler() {
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                val tail = me.desidubanime.tv.data.DebugLog.snapshot()
                    .takeLast(120).joinToString("\n")
                crashFile.writeText(
                    "THREAD: ${thread.name}\n${sw}\n\n--- RECENT LOG ---\n$tail",
                )
            }
            previous?.uncaughtException(thread, error) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        // Surface the previous run's crash into the visible diagnostic log.
        // OFF THE MAIN THREAD: this is a file read plus up to 90 log appends,
        // and running it inside onCreate() put storage I/O in front of the
        // first frame — exactly the "sticky right after opening" feel a cold
        // start on a slow TV box has.
        me.desidubanime.tv.data.ServiceLocator.appScope.launch {
            if (!crashFile.exists()) return@launch
            runCatching {
                crashFile.readText().lineSequence()
                    .filter { it.isNotBlank() }
                    .take(90)
                    .forEach { me.desidubanime.tv.data.DebugLog.log("crash", it) }
            }
        }
    }

    /**
     * TV-tuned Coil loader. TV boxes have little RAM but plenty of flash, so:
     * RGB_565 halves every poster's memory footprint (no alpha needed),
     * the memory cache is capped small, and a big disk cache makes revisited
     * screens render instantly with zero network. Poster URLs are immutable,
     * so server cache headers are ignored — once cached, cached forever.
     * Sharing [me.desidubanime.tv.data.api.Network.client] reuses its
     * connection pool and auto-applies the UA/Referer Cloudflare headers.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Dedicated image lane: poster traffic must never queue ahead of a
        // click-time request on the shared client's dispatcher.
        .okHttpClient { me.desidubanime.tv.data.api.Network.images }
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowRgb565(true)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .respectCacheHeaders(false)
        // Off by default: a 100-card grid fade is 100 short animations on a GPU
        // that is also compositing focus scaling. The hero (one large image)
        // opts back in per request.
        .crossfade(false)
        .build()

    /**
     * The offscreen browser used for Cloudflare-gated scrape pages.
     *
     * One instance for the process: it serialises its own WebView behind a
     * semaphore, so reuse is what keeps a burst of episode scrapes from opening
     * several heavy rasters at once on a TV box.
     */
    private val adBrowser by lazy { me.desidubanime.tv.player.FileHostBrowser(this) }

    companion object {
        lateinit var instance: App
            private set

        /** Budget for one gated episode page through the WebView. */
        private const val AD_PAGE_MS = 25_000L
    }
}
