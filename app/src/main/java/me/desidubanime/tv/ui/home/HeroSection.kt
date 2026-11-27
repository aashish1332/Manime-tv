package me.desidubanime.tv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Anime

/**
 * Auto-rotating hero banner: cycles through the newest content every few
 * seconds with a crossfade. Poster on the right, title/synopsis on the left,
 * a focusable "View" button opens the current anime, and dots show position.
 * Rotation pauses while the button is focused so the banner never swaps out
 * from under the user's selection — while focused, ◀/▶ change the slide
 * manually instead of moving focus away.
 */
@Composable
fun HeroSection(
    items: List<Anime>,
    onOpen: (Anime) -> Unit,
    modifier: Modifier = Modifier,
    /** "NEW" / "NEW EPISODE" for the slide, so the flag matches the card. */
    badgeFor: (Anime) -> String? = { null },
) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }

    LaunchedEffect(items, paused) {
        if (paused) return@LaunchedEffect
        while (true) {
            delay(ROTATE_MS)
            index = (index + 1) % items.size
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0A0A)),
    ) {
        AnimatedContent(
            targetState = items[index % items.size],
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "hero",
        ) { anime ->
            // One request per slide, not per recomposition. heroImage() used to
            // be called inline, so every recompose of the rotating banner built
            // a fresh ImageRequest and re-ran the pipeline; on a 1GB TV box the
            // crossfade at 1280x720 was also decoding ~3.7MP per slide for a
            // 240dp strip. The decode size now matches what is actually drawn.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val backdrop = remember(anime.posterUrl) { heroImage(ctx, anime.posterUrl, 640, 360) }
            val poster = remember(anime.posterUrl) { heroImage(ctx, anime.posterUrl, 256, 384) }
            Box(Modifier.fillMaxSize()) {
                // Backdrop: the poster blown up + darkened, so every anime has art.
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.35f,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to Color(0xE6000000),
                                0.55f to Color(0x99000000),
                                1.0f to Color.Transparent,
                            ),
                        ),
                )
                Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The hero rotates the newest content, but not every
                        // slide is a new RELEASE — some are a new EPISODE of an
                        // older show. Say which one it is instead of stamping
                        // "NEW" on everything.
                        Text(
                            badgeFor(anime) ?: "FEATURED",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            anime.title,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (anime.synopsis.isNotBlank()) {
                            Text(
                                anime.synopsis,
                                color = Color(0xB3FFFFFF),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = { onOpen(anime) },
                                modifier = Modifier
                                    .onFocusChanged { paused = it.isFocused }
                                    .onPreviewKeyEvent { e ->
                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (e.key) {
                                            Key.DirectionLeft -> {
                                                index = (index - 1 + items.size) % items.size
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                index = (index + 1) % items.size
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                            ) { Text("▶  View") }
                            Dots(count = items.size, active = index)
                        }
                    }
                    // Foreground poster card on the right.
                    AsyncImage(
                        model = poster,
                        contentDescription = anime.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(128.dp)
                            .height(192.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Dots(count: Int, active: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == active) 8.dp else 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (i == active) MaterialTheme.colorScheme.primary else Color(0x55FFFFFF)),
            )
        }
    }
}

/**
 * Hero image request at the size it is drawn at. `allowHardware` keeps the
 * bitmap in GPU memory so the 6s rotation never copies it back to the heap.
 */
private fun heroImage(
    ctx: android.content.Context,
    url: String,
    width: Int,
    height: Int,
): ImageRequest = ImageRequest.Builder(ctx)
    .data(url.ifBlank { null })
    .size(width, height)
    .addHeader("User-Agent", Api.USER_AGENT)
    .crossfade(true)
    .build()

private const val ROTATE_MS = 6_000L
