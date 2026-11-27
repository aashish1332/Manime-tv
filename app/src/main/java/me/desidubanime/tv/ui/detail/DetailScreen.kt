package me.desidubanime.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Episode
import me.desidubanime.tv.ui.common.ErrorScreen
import me.desidubanime.tv.ui.common.LoadingScreen
import me.desidubanime.tv.ui.common.UiState
import me.desidubanime.tv.ui.theme.AccentGradient
import me.desidubanime.tv.ui.theme.HairLine
import me.desidubanime.tv.ui.theme.Muted
import me.desidubanime.tv.ui.theme.OnBg
import me.desidubanime.tv.ui.theme.Primary
import me.desidubanime.tv.ui.theme.SurfaceHi

@Composable
fun DetailScreen(
    animeId: Int,
    onPlayEpisode: (episodeIndex: Int) -> Unit,
    onOpenSeason: (me.desidubanime.tv.data.model.Anime) -> Unit = {},
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(animeId) { vm.load(animeId) }
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is UiState.Loading -> LoadingScreen()
        is UiState.Error -> ErrorScreen(s.message, onRetry = { vm.load(animeId) })
        is UiState.Success -> DetailContent(s.data, onPlayEpisode, onOpenSeason)
    }
}

/**
 * One LazyVerticalGrid drives the whole screen: the info header is a full-span
 * item, so it scrolls away with the episodes instead of staying pinned.
 */
@Composable
private fun DetailContent(
    data: DetailData,
    onPlayEpisode: (Int) -> Unit,
    onOpenSeason: (me.desidubanime.tv.data.model.Anime) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // One FocusRequester per episode so ◀/▶ can wrap across row ends.
    val requesters = remember(data.episodes.size) { List(data.episodes.size) { FocusRequester() } }
    // Season row is an extra full-span item before the episode cells when shown.
    val headerItems = if (data.seasons.size > 1) 3 else 2
    // Landing focus goes to the thing the user came here to do (resume/play),
    // not to an arbitrary episode tile. Keyed on resumeIndex too: the season
    // list arrives after the episodes and must not steal it back.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(data.anime.id, data.resumeIndex) {
        runCatching { playFocus.requestFocus() }
    }

    /** Move focus to episode [target], scrolling it into view first if needed. */
    fun focusEpisode(target: Int) {
        if (target !in data.episodes.indices) return
        scope.launch {
            // Skip the header/season/title span items that precede the episode cells.
            gridState.animateScrollToItem(target + headerItems)
            runCatching { requesters[target].requestFocus() }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(EPISODE_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 40.dp, end = 40.dp, top = 24.dp, bottom = 24.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            InfoHeader(
                data = data,
                playLabel = data.resumeIndex
                    ?.let { "▶  Resume ${data.episodes.getOrNull(it)?.number ?: "Episode ${it + 1}"}" }
                    ?: "▶  Play Episode 1",
                // With no episodes there is nothing to start — the button is
                // shown disabled rather than hidden so the header keeps its
                // shape on upcoming shows.
                playEnabled = data.episodes.isNotEmpty(),
                onPlay = {
                    onPlayEpisode(data.resumeIndex ?: 0)
                },
                playFocus = playFocus,
            )
        }
        if (data.seasons.size > 1) {
            item(key = "seasons", span = { GridItemSpan(maxLineSpan) }) {
                SeasonRow(
                    seasons = data.seasons,
                    currentId = data.anime.id,
                    onOpenSeason = onOpenSeason,
                )
            }
        }
        item(key = "episodes-title", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(15.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(AccentGradient),
                    )
                    Text(
                        "EPISODES",
                        style = MaterialTheme.typography.titleSmall,
                        color = OnBg,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        "${data.episodes.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                    )
                }
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(HairLine))
                // Nothing uploaded on any site yet. Home promises this cannot
                // happen (cards are pruned once a series page proves empty),
                // but a card opened seconds before its first upload must say
                // so plainly instead of showing an empty grid.
                if (data.episodes.isEmpty()) {
                    Text(
                        "No episodes uploaded yet. Check back after the next release.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
        items(
            count = data.episodes.size,
            key = { data.episodes[it].id },
            // Every cell here is the same shape, so Compose can reuse slots
            // instead of rebuilding them while the grid scrolls on D-pad.
            contentType = { "episode-card" },
        ) { index ->
            val isRowEnd = index % EPISODE_COLUMNS == EPISODE_COLUMNS - 1 || index == data.episodes.lastIndex
            val isRowStart = index % EPISODE_COLUMNS == 0
                            EpisodeCard(
                    episode = data.episodes[index],
                    // Hub-only episodes (ADH/AD/T4A) carry no thumbnail; the show's
                    // poster fills in so the grid never renders grey skeletons.
                    fallbackPoster = data.anime.posterUrl,
                    isNew = data.newAboveEpisode?.let { data.episodes[index].numberInt > it } == true,
                    progress = data.progress[index] ?: 0f,
                    watched = (data.progress[index] ?: 0f) >= 0.95f,
                    onClick = { onPlayEpisode(index) },
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            // Right at a row end -> jump to the next row's first card.
                            e.key == Key.DirectionRight && isRowEnd && index < data.episodes.lastIndex -> {
                                focusEpisode(index + 1); true
                            }
                            // Left at a row start -> jump to the previous row's last card.
                            e.key == Key.DirectionLeft && isRowStart && index > 0 -> {
                                focusEpisode(index - 1); true
                            }
                            else -> false
                        }
                    },
            )
        }
    }
}

/**
 * Season chips under the info header. The season being viewed is highlighted
 * and not clickable; picking another season swaps the whole detail page.
 */
@Composable
private fun SeasonRow(
    seasons: List<me.desidubanime.tv.data.model.Anime>,
    currentId: Int,
    onOpenSeason: (me.desidubanime.tv.data.model.Anime) -> Unit,
) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            "SEASONS",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp, start = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            seasons.forEach { season ->
                val n = me.desidubanime.tv.util.Titles.baseAndSeason(season.title).second
                val isCurrent = season.id == currentId
                androidx.tv.material3.Button(
                    onClick = { if (!isCurrent) onOpenSeason(season) },
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = if (isCurrent) Primary else SurfaceHi,
                        contentColor = if (isCurrent) OnBg else Muted,
                        focusedContainerColor = SurfaceHi,
                        focusedContentColor = OnBg,
                    ),
                    shape = androidx.tv.material3.ButtonDefaults.shape(shape = RoundedCornerShape(9.dp)),
                ) {
                    Text(
                        "Season $n",
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoHeader(
    data: DetailData,
    playLabel: String,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    playFocus: FocusRequester,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(data.anime.posterUrl.ifBlank { null })
                .size(360, 500)                  // detail poster: ~180dp x 250dp @2x
                .addHeader("User-Agent", Api.USER_AGENT)
                .build(),
            contentDescription = data.anime.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(180.dp)
                .height(250.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Column(Modifier.padding(start = 26.dp).fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .width(22.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AccentGradient),
                )
                Text(
                    "SERIES",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                data.anime.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = OnBg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (data.episodes.size == 1) "1 episode" else "${data.episodes.size} episodes")
                    // Where the anime also lives elsewhere, say so: it is why
                    // the server list can contain four sites' links.
                    val sources = listOfNotNull(
                        "ADH".takeIf { data.anime.hasAdh },
                        "AD".takeIf { data.anime.hasAd },
                        "T4A".takeIf { data.anime.hasTw },
                    )
                    if (sources.isNotEmpty()) append("  ·  " + sources.joinToString(" · "))
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            androidx.tv.material3.Button(
                onClick = onPlay,
                enabled = playEnabled,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .focusRequester(playFocus),
            ) { Text(playLabel, fontWeight = FontWeight.Bold) }
            Text(
                data.anime.synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
    fallbackPoster: String = "",
    /** Watched fraction from Continue Watching (0 = never played). */
    progress: Float = 0f,
    /** Finished episodes get a check instead of a red NEW flag. */
    watched: Boolean = false,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(
        onClick = onClick,
        modifier = modifier,
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Primary)),
        ),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        glow = CardDefaults.glow(),
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(episode.thumbnail.ifBlank { fallbackPoster.ifBlank { null } })
                    .size(320, 192)              // episode card: full width of card x 96dp @2x
                    .addHeader("User-Agent", Api.USER_AGENT)
                        .build(),
                contentDescription = episode.number,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isNew && !watched) {
                me.desidubanime.tv.ui.common.NewBadge(
                    Modifier.align(androidx.compose.ui.Alignment.TopEnd),
                )
            }
            if (watched) {
                Text(
                    "✓",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopStart)
                        .padding(6.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(Primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            // Watched progress along the card's bottom edge, same read as the
            // Continue Watching tile.
            if (progress > 0.01f && !watched) {
                Box(
                    Modifier
                        .align(androidx.compose.ui.Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(androidx.compose.ui.graphics.Color(0x66000000)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Primary),
                    )
                }
            }
        }
        Text(
            episode.number,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
    }
}

internal const val EPISODE_COLUMNS = 5
