package me.desidubanime.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.desidubanime.tv.data.history.WatchEntry
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.data.model.HomeFeed
import me.desidubanime.tv.ui.common.AnimeCard
import me.desidubanime.tv.ui.common.ErrorScreen
import me.desidubanime.tv.ui.common.LoadingScreen
import me.desidubanime.tv.ui.common.Spinner
import me.desidubanime.tv.ui.common.UiState
import me.desidubanime.tv.ui.common.WatchCard

@Composable
fun HomeScreen(
    onOpenAnime: (Anime) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeWatch: (WatchEntry) -> Unit,
    onOpenTwAnime: () -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    // Cancel any ongoing feed refresh when user starts navigating away
    val onNavigate = { vm.cancelRefresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val selectedPage by vm.selectedPage.collectAsStateWithLifecycle()
    val totalPages by vm.totalPages.collectAsStateWithLifecycle()
    val pageAnime by vm.pageAnime.collectAsStateWithLifecycle()
    // feedLoading is deliberately NOT collected here: it flips on every load,
    // and collecting it at screen level recomposed the entire grid and sidebar
    // for a spinner. Sidebar collects it itself (see Sidebar.loadingFlow).
    // Hold OK on a Continue Watching card -> confirm-delete dialog for it.
    var deleteCandidate by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<WatchEntry?>(null)
    }

    // Refresh when Home returns to foreground immediately; any ongoing refresh
    // will be cancelled if user interacts (Continue Watching, navigate away).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Content mode: HOME or TW
    val contentMode by vm.contentMode.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize()) {
        // Left sidebar owns ALL navigation now: Home, Refresh, Search, Settings,
        // then the numbered catalog pages underneath. The old top action row is
        // gone, which also gives the grid its full height back.
        Sidebar(
            selectedPage = selectedPage,
            totalPages = totalPages,
            loadingFlow = vm.feedLoading,
            contentMode = contentMode,
            onSelect = { vm.selectPage(it); vm.cancelRefresh() },
            onSelectHome = { vm.setContentMode("HOME"); vm.selectPage(0); vm.cancelRefresh() },
            onRefresh = vm::refreshNow,
            onOpenSearch = { vm.cancelRefresh(); onOpenSearch() },
            onOpenSettings = { vm.cancelRefresh(); onOpenSettings() },
            onOpenTwAnime = { vm.cancelRefresh(); vm.setContentMode("TW") },
        )
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 24.dp, end = 32.dp)) {
            when (contentMode) {
                "TW" -> {
                    when (val s = pageAnime) {
                        is UiState.Loading -> LoadingScreen()
                        is UiState.Error -> ErrorScreen(s.message, onRetry = { vm.setContentMode("TW") })
                        is UiState.Success -> PageGrid(
                            anime = s.data,
                            onOpenAnime = { vm.cancelRefresh(); onOpenAnime(it) },
                        )
                    }
                }
                else -> {
                    if (selectedPage == 0) {
                        when (val s = state) {
                            is UiState.Loading -> LoadingScreen()
                            is UiState.Error -> ErrorScreen(s.message, onRetry = vm::load)
                            is UiState.Success -> HomeSections(
                                feed = s.data,
                                continueWatching = continueWatching,
                                onOpenAnime = { vm.cancelRefresh(); onOpenAnime(it) },
                                onResumeWatch = { vm.cancelAndScheduleRefresh(); onResumeWatch(it) },
                                onHoldWatch = { deleteCandidate = it },
                            )
                        }
                    } else {
                        when (val s = pageAnime) {
                            is UiState.Loading -> LoadingScreen()
                            is UiState.Error -> ErrorScreen(s.message, onRetry = { vm.selectPage(selectedPage) })
                            is UiState.Success -> PageGrid(
                                anime = s.data,
                                onOpenAnime = { vm.cancelRefresh(); onOpenAnime(it) },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteCandidate?.let { entry ->
        DeleteHistoryDialog(
            entry = entry,
            onConfirm = {
                vm.removeFromHistory(entry.animeId)
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }
}

@Composable
private fun HomeSections(
    feed: HomeFeed,
    continueWatching: List<WatchEntry>,
    onOpenAnime: (Anime) -> Unit,
    onResumeWatch: (WatchEntry) -> Unit,
    onHoldWatch: (WatchEntry) -> Unit,
) {
    // NEW badge on a card = the anime was published AFTER the app's first run,
    // so a fresh install never floods the grid with badges.
    val seenStore = me.desidubanime.tv.data.ServiceLocator.seenStore
    val firstRunAt = androidx.compose.runtime.remember { seenStore.firstRunAt() }
    // SeenStore keeps its state in memory, so this is an O(1) lookup; the old
    // snapshotFlow version emitted once and never again. SeenStore isn't observable â€” read it inside a snapshotFlow so ONLY the
    // affected card recomposes when its stamp changes, not the whole grid.
    fun seenStampOf(a: Anime): String = seenStore.seenStamp(a.id).orEmpty()
    fun isNewRelease(a: Anime): Boolean =
        a.publishedAt.isNotBlank() && a.publishedAt > firstRunAt
    // NEW EPISODE badge = a fresh upload since the user last opened this anime
    // (first-run stamp as the baseline so a fresh install stays clean).
    // Cleared by opening the card â€” openAnime records the stamp below.
    fun hasNewEpisode(a: Anime): Boolean {
        if (a.latestEpisodeAt.isBlank()) return false
        val seenAt = seenStampOf(a).ifBlank { firstRunAt }
        return a.latestEpisodeAt > seenAt
    }
    fun badge(a: Anime): String? = when {
        isNewRelease(a) -> "NEW"
        hasNewEpisode(a) -> "NEW EPISODE"
        else -> null
    }
    fun openAnime(a: Anime) {
        // setSeenStamp writes a JSON file. Doing that inline meant an OK press
        // sat on a disk write before the detail page could start opening.
        me.desidubanime.tv.data.ServiceLocator.appScope.launch {
            runCatching { seenStore.setSeenStamp(a.id, a.latestEpisodeAt) }
        }
        onOpenAnime(a)
    }
    if (feed.all.isEmpty()) {
        Text("No anime found.", Modifier.padding(24.dp))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Auto-rotating hero of the newest content (new episodes first).
        val heroItems = (feed.newEpisodes.take(4) + feed.newReleases.take(4))
            .distinctBy { it.id }
            .take(6)
        if (heroItems.isNotEmpty()) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                HeroSection(items = heroItems, onOpen = onOpenAnime, badgeFor = ::badge)
            }
        }
        if (continueWatching.isNotEmpty()) {
            sectionHeader("Continue Watching")
            item(key = "row-continue", span = { GridItemSpan(maxLineSpan) }) {
LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems(continueWatching, key = { it.animeId }) { entry ->
                        WatchCard(
                            entry = entry,
                            onClick = { onResumeWatch(entry) },
                            onLongClick = { onHoldWatch(entry) },
                            modifier = Modifier.width(ROW_CARD_WIDTH),
                        )
                    }
                }
            }
        }
        if (feed.newEpisodes.isNotEmpty()) {
            sectionHeader("New Episodes")
            animeRow("new-episodes", feed.newEpisodes, ::openAnime, ::badge)
        }
        if (feed.newReleases.isNotEmpty()) {
            sectionHeader("New Releases")
            animeRow("new-releases", feed.newReleases, ::openAnime, ::badge)
        }
        sectionHeader("All Anime")
        items(feed.all, key = { it.id }) { anime ->
            val b = badge(anime)
            AnimeCard(
                anime = anime,
                onClick = { openAnime(anime) },
                isNew = b != null,
                badgeText = b ?: "NEW",
            )
        }
    }
}

/**
 * Left navigation rail: app actions (Home / Refresh / Search / Settings) on top,
 * numbered catalog pages below. The refresh icon is replaced by a live spinner
 * whenever a feed load is in flight â€” cold start included.
 */
@Composable
private fun Sidebar(
    selectedPage: Int,
    totalPages: Int,
    loadingFlow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    contentMode: String,
    onSelect: (Int) -> Unit,
    onSelectHome: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTwAnime: () -> Unit = {},
) {
    // Collected here, not in HomeScreen: only this rail depends on it, so a
    // refresh now repaints the rail instead of the whole poster grid.
    val feedLoading by loadingFlow.collectAsStateWithLifecycle()
    Column(
        Modifier
            .width(176.dp)
            .padding(start = 12.dp, top = 24.dp, end = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "Manime",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "made/built by Manu",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SidebarItem("Home", selectedPage == 0 && contentMode != "TW", icon = Icons.Default.Home, onClick = onSelectHome)
        SidebarItem(
            label = if (feedLoading) "Refreshing..." else "Refresh",
            selected = false,
            icon = Icons.Default.Sync,
            spinning = feedLoading,
            enabled = !feedLoading,
            onClick = onRefresh,
        )
        Text(
            "TW4A",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 8.dp),
        )
        SidebarItem("All TW4A Anime", selected = contentMode == "TW", onClick = onOpenTwAnime)
        SidebarItem("Search", false, icon = Icons.Default.Search, onClick = onOpenSearch)
        SidebarItem("Settings", false, icon = Icons.Default.Settings, onClick = onOpenSettings)
        Text(
            "Pages",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 8.dp),
        )
        // DDA paginates 30/page; the count comes live from X-WP-TotalPages so
        // the sidebar grows automatically as new anime are uploaded.
        for (p in 1..totalPages) {
            SidebarItem("Page $p", selectedPage == p) { onSelect(p) }
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** Swap the icon for a live progress ring (refresh in flight). */
    spinning: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (spinning) {
            Spinner(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
            else androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
}

/** Realtime catalog page â€” plain poster grid, nothing cached. */
@Composable
private fun PageGrid(
    anime: List<Anime>,
    onOpenAnime: (Anime) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(anime, key = { it.id }) { a ->
            AnimeCard(anime = a, onClick = { onOpenAnime(a) })
        }
    }
}

/** Confirmation shown after holding OK on a Continue Watching card. */
@Composable
private fun DeleteHistoryDialog(
    entry: WatchEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deleteFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { deleteFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.tv.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    "Remove from Continue Watching?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${entry.animeTitle} - ${entry.episodeLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                Row {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.focusRequester(deleteFocus),
                    ) { Text("Delete") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.sectionHeader(title: String) {
    item(key = "header-$title", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.animeRow(
    key: String,
    anime: List<Anime>,
    onOpenAnime: (Anime) -> Unit,
    badge: (Anime) -> String? = { null },
) {
    item(key = "row-$key", span = { GridItemSpan(maxLineSpan) }) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            rowItems(anime, key = { it.id }) { a ->
                val b = badge(a)
                AnimeCard(
                    anime = a,
                    onClick = { onOpenAnime(a) },
                    isNew = b != null,
                    badgeText = b ?: "NEW",
                    modifier = Modifier.width(ROW_CARD_WIDTH),
                )
            }
        }
    }
}

/** Cards inside horizontal rows keep a fixed width (grid cells size themselves). */
private val ROW_CARD_WIDTH = 150.dp
