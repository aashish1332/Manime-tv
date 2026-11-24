package me.desidubanime.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.ui.theme.Primary

/** A focusable poster tile used in the Home / Search grids. */
@Composable
fun AnimeCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Show a NEW ribbon on the poster (new release since the user's first run). */
    isNew: Boolean = false,
    /** Ribbon text — "NEW" for releases, "NEW EPISODE" for fresh uploads. */
    badgeText: String = "NEW",
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        // Fill the grid cell (rows pass a width themselves); keeps the grid
        // edge-to-edge instead of leaving gutters beside fixed-width cards.
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Primary),
            ),
        ),
        // Modest lift: a 6-column grid on a 720p TV has ~16dp gutters, and the
        // old 1.12 scale pushed a focused card over its neighbours.
        scale = CardDefaults.scale(focusedScale = 1.07f),
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (anime.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(anime.posterUrl)
                        .size(300, 450)                  // 2:3 poster — downsample at decode time
                        .addHeader("User-Agent", Api.USER_AGENT)
                        .addHeader("Referer", Api.REFERER)
                        // removed crossfade for smoother scrolling
                        .build(),
                    contentDescription = anime.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No artwork (an upload whose cover has not propagated yet):
                // one opaque layer with the first letter — the grid must never
                // show a grey hole.
                Text(
                    anime.title.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (isNew) NewBadge(Modifier.align(Alignment.TopEnd), text = badgeText)
            // Source badges: show which sites have this anime (ADH > AD > DDA).
            SourceBadges(
                anime = anime,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        MarqueeText(
            text = anime.title,
            active = focused,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
}

/** Small NEW ribbon pinned to a poster corner. */
@Composable
fun NewBadge(modifier: Modifier = Modifier, text: String = "NEW") {
    Text(
        text,
        color = androidx.compose.ui.graphics.Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Standard 2:3 anime poster. */
const val POSTER_ASPECT = 2f / 3f

/**
 * Small colored source badges pinned to the poster's bottom-left corner.
 * Shows which sites have this anime: ADH (blue), AD (green), T4A (purple),
 * DDA (orange). Priority order: ADH > AD > T4A > DDA — badges render
 * left-to-right in this order, all together with the poster.
 *
 * ALWAYS DRAWN. This used to return early until Anime.sourcesResolved was set,
 * i.e. until all three site catalogs had landed (measured: ~20s, and up to the
 * 60s grace when one site was slow). The grid therefore painted bare posters
 * and the tags popped in later, or never — which is what "the provider tags do
 * not show" was. The first merge already knows every source it matched, and a
 * card that is older than a site's latest-100 window is corrected when that
 * catalog lands and the feed re-merges.
 *
 * Called from a LazyVerticalGrid — the badge list is remembered per source id
 * so a recompose doesn't rebuild it fresh every frame.
 */
@Composable
fun SourceBadges(anime: Anime, modifier: Modifier = Modifier) {
    val badges = remember(anime.adhPostId, anime.adPostId, anime.twPostId, anime.id) {
        buildList {
            if (anime.hasAdh) add("ADH" to Color(0xFF2196F3))   // blue
            if (anime.hasAd) add("AD" to Color(0xFF4CAF50))     // green
            if (anime.hasTw) add("T4A" to Color(0xFF9C27B0))   // purple
            if (anime.hasDda) add("DDA" to Color(0xFFFF9800))   // orange
        }
    }
    if (badges.isEmpty()) return
    Row(
        modifier = modifier.padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((label, color) in badges) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
