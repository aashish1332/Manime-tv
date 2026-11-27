package me.desidubanime.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.history.WatchEntry
import me.desidubanime.tv.ui.theme.Primary

/**
 * Continue Watching tile: poster + episode label + progress bar.
 * Hold OK on the remote to trigger [onLongClick] (delete-from-history).
 */
@Composable
fun WatchCard(
    entry: WatchEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Primary),
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.07f),
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Box(Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT)) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(entry.posterUrl.ifBlank { null })
                    .size(300, 450)               // 2:3 thumbnail same as AnimeCard
                    .addHeader("User-Agent", Api.USER_AGENT)
                    // removed crossfade for smoother scrolling
                    .build(),
                contentDescription = entry.animeTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Watched-progress bar pinned to the poster's bottom edge.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x66000000)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(entry.progress)
                        .fillMaxHeight()
                        .background(Primary),
                )
            }
        }
        MarqueeText(
            text = entry.animeTitle,
            active = focused,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        )
        Text(
            // Remaining time is the number a viewer actually decides with
            // ("have I got 12 minutes?"), so it replaces a bare episode label
            // whenever the saved entry knows the duration.
            text = remainingLabel(entry),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0x99FFFFFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        )
    }
}

/** "Episode 5 · 12 min left", or just the episode label when duration is unknown. */
private fun remainingLabel(entry: WatchEntry): String {
    val left = entry.durationMs - entry.positionMs
    if (entry.durationMs <= 0 || left <= 0) return entry.episodeLabel
    val minutes = (left + 59_999) / 60_000
    return if (minutes <= 1) "${entry.episodeLabel} · <1 min left"
    else "${entry.episodeLabel} · $minutes min left"
}
