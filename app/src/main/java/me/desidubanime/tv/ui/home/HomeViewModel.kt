package me.desidubanime.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.history.WatchEntry
import me.desidubanime.tv.data.model.HomeFeed
import me.desidubanime.tv.ui.common.UiState

class HomeViewModel : ViewModel() {
    private val repo = ServiceLocator.repository

    private val _state = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)
    val state: StateFlow<UiState<HomeFeed>> = _state.asStateFlow()

    /**
     * True while ANY feed load is in flight — cold start, ON_RESUME refresh, or
     * the refresh button. The sidebar's refresh icon spins off this, so every
     * load the user triggers (especially app open) is visibly a load.
     */
    private val _feedLoading = MutableStateFlow(false)
    val feedLoading: StateFlow<Boolean> = _feedLoading.asStateFlow()

    /** Concurrent loads (init + ON_RESUME fire together) share one spinner. */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Run a feed load with the spinner up. The repo answers from its 60s cache
     * in ~0ms on a back-navigation, which would flash the spinner for a single
     * frame; [MIN_SPIN_MS] holds it long enough to actually read as a refresh.
     */
    private suspend fun <T> tracked(block: suspend () -> T): T {
        val startedAt = System.currentTimeMillis()
        if (inFlight.incrementAndGet() == 1) _feedLoading.value = true
        try {
            return block()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val left = MIN_SPIN_MS - (System.currentTimeMillis() - startedAt)
                if (left > 0) kotlinx.coroutines.delay(left)
                if (inFlight.decrementAndGet() == 0) _feedLoading.value = false
            }
        }
    }

    /** Continue Watching entries, newest first (live from DataStore). */
    val continueWatching: StateFlow<List<WatchEntry>> = ServiceLocator.watchHistory.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Catalog page browsing (left sidebar): 0 = the cached Home feed; N>0 =
     * realtime page N of All Anime (never cached, loaded on demand).
     */
    private val _selectedPage = MutableStateFlow(0)
    val selectedPage: StateFlow<Int> = _selectedPage.asStateFlow()

    // Content mode: HOME (default) or TW (show only TW anime)
    private val _contentMode = MutableStateFlow("HOME")
    val contentMode: StateFlow<String> = _contentMode.asStateFlow()

    fun setContentMode(mode: String) {
        _contentMode.value = mode
        if (mode == "TW") {
            // Load TW-only list when switching to TW mode
            viewModelScope.launch {
                _pageAnime.value = UiState.Loading
                val list = runCatching { repo.twOnlyAnime() }.getOrDefault(emptyList())
                _pageAnime.value = if (list.isEmpty()) {
                    UiState.Error("TW4A list is empty — no catalog retrieved from the site.")
                } else {
                    UiState.Success(list)
                }
            }
        }
    }

    /** Sidebar page count — live from the API, grows as anime are added. */
    private val _totalPages = MutableStateFlow(20)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _pageAnime = MutableStateFlow<UiState<List<me.desidubanime.tv.data.model.Anime>>>(UiState.Loading)
    val pageAnime: StateFlow<UiState<List<me.desidubanime.tv.data.model.Anime>>> = _pageAnime.asStateFlow()

    private var pageJob: kotlinx.coroutines.Job? = null

    fun selectPage(page: Int) {
        _selectedPage.value = page
        if (page <= 0) return
        _pageAnime.value = UiState.Loading
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            // Fetch actual page count on first sidebar navigation (not at app start)
            if (_totalPages.value == 20) {
                runCatching { repo.totalAnimePages() }.onSuccess { _totalPages.value = it }
            }
            val list = runCatching { repo.animePage(page) }.getOrDefault(emptyList())
            _pageAnime.value = if (list.isEmpty()) {
                UiState.Error("Is page pe kuch nahi mila.")
            } else {
                UiState.Success(list)
            }
        }
    }

    /** Hold-to-delete on a Continue Watching card. */
    fun removeFromHistory(animeId: Int) {
        viewModelScope.launch {
            runCatching { ServiceLocator.watchHistory.remove(animeId) }
        }
    }

    init {
        load()
        // Don't fetch totalAnimePages() at app start — it triggers
        // mergedCatalog() which fetches the full ADH + DDA catalogs (18
        // requests). The default estimate of 20 pages is fine; the actual
        // count is fetched lazily on first sidebar navigation in selectPage().
    }

    /**
     * Silent refresh — called every time the Home screen comes back to the
     * foreground (RESUMED). TV apps stay alive for days, so without this the
     * feed freezes at whatever launch time saw; repo's 60s TTL keeps rapid
     * back-and-forth navigation free.
     */
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var delayedRefreshJob: kotlinx.coroutines.Job? = null

    fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        delayedRefreshJob?.cancel()
        delayedRefreshJob = null
    }

    fun cancelAndScheduleRefresh() {
        // Cancel current fetch and schedule a new one after 5s (for background refresh)
        refreshJob?.cancel()
        refreshJob = null
        delayedRefreshJob?.cancel()
        delayedRefreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            // Only start if no other fetch is running and we're still active
            if (refreshJob == null) {
                refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runCatching { tracked { repo.homeFeed() } }
                .onSuccess { _state.value = UiState.Success(it) }
            // Failure: keep showing what we have.
        }
    }

    /**
     * Manual refresh (refresh button): bypasses the 60s cache TTL so the feed
     * REALLY updates. The spinner runs off [feedLoading] like every other load.
     */
    fun refreshNow() {
        if (_feedLoading.value) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runCatching { tracked { repo.refreshHomeFeed() } }
                .onSuccess { _state.value = UiState.Success(it) }
            // Failure: keep whatever we have.
        }
    }

    /**
     * Stale-while-revalidate: paint the last persisted feed instantly (cold
     * start feels immediate — posters come from Coil's disk cache), then swap
     * in the fresh feed when the network answers. Errors only surface when
     * there was nothing cached to show.
     */
    fun load() {
        val hadData = _state.value is UiState.Success
        if (!hadData) _state.value = UiState.Loading
        viewModelScope.launch {
            if (!hadData) {
                repo.cachedFeed()?.let { cached ->
                    if (_state.value is UiState.Loading) _state.value = UiState.Success(cached)
                }
            }
            runCatching { tracked { repo.homeFeed() } }
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = UiState.Error(it.message ?: "Failed to load")
                    }
                }
        }
    }

    private companion object {
        /** Floor on how long the refresh spinner stays visible (cache hits are instant). */
        const val MIN_SPIN_MS = 200L
    }
}