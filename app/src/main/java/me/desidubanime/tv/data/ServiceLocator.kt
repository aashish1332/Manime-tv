package me.desidubanime.tv.data

import me.desidubanime.tv.App
import me.desidubanime.tv.data.cache.AdhSlugStore
import me.desidubanime.tv.data.cache.EnglishTitleStore
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
            slugStore = AdhSlugStore(App.instance),
            feedStore = FeedCacheStore(App.instance),
            titleStore = EnglishTitleStore(App.instance),
        )
    }
    val watchHistory: WatchHistoryStore by lazy { WatchHistoryStore(App.instance) }
    val seenStore: SeenStore by lazy { SeenStore(App.instance) }
}
