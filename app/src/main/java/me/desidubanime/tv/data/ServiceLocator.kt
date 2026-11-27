package me.desidubanime.tv.data

import me.desidubanime.tv.App
import me.desidubanime.tv.data.cache.EpisodeProbeStore
import me.desidubanime.tv.data.cache.FeedCacheStore
import me.desidubanime.tv.data.cache.SeenStore
import me.desidubanime.tv.data.history.WatchHistoryStore
import me.desidubanime.tv.data.repo.AnimeRepository
import me.desidubanime.tv.data.settings.AppSettings

/** Tiny manual DI — a single shared repository instance. */
object ServiceLocator {
    /**
     * Process-lifetime IO scope for writes that must outlive a screen.
     * Nav-popping the player cancels its ViewModel scope, so the final
     * "save where I stopped" write cannot live there.
     */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    val settings: AppSettings by lazy { AppSettings(App.instance) }
    val repository: AnimeRepository by lazy {
        AnimeRepository(
            settings = settings,
            feedStore = FeedCacheStore(App.instance),
            probeStore = EpisodeProbeStore(App.instance),
        )
    }
    val watchHistory: WatchHistoryStore by lazy { WatchHistoryStore(App.instance) }
    val seenStore: SeenStore by lazy { SeenStore(App.instance) }
}
