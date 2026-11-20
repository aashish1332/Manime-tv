package me.desidubanime.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import me.desidubanime.tv.data.model.ServerGroup
import me.desidubanime.tv.player.PlayOption
import me.desidubanime.tv.player.PlayerUiState

/** Slide-in right panel: servers · quality · audio · subtitles · next/prev. D-pad driven. */
@Composable
fun PlayerMenu(
    state: PlayerUiState,
    player: ExoPlayer?,
    onPick: (PlayOption) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    // Without an explicit focus grab the d-pad stays stuck on the video surface
    // and the menu is unusable ("up/down/ok chalna bnd ho gye" bug).
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                item {
                    Text(state.episodeTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onPrev, enabled = state.hasPrev) { Text("◀ Prev") }
                        Button(onClick = onNext, enabled = state.hasNext) { Text("Next ▶") }
                        Button(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) { Text("Close") }
                    }
                }

                section("Servers")
                items(state.options) { opt ->
                    val selected = opt == state.selected
                    MenuRow(
                        title = opt.label,
                        // Which site the server came from matters: both ADH and
                        // AnimeDrive serve HubCloud, so without it two rows read
                        // identically and a dead one can't be told from its twin.
                        subtitle = listOfNotNull(
                            opt.sourceLabel,
                            tag(opt.group),
                            if (opt.native) "native" else "embed",
                        ).joinToString(" - "),
                        selected = selected,
                        onClick = { onPick(opt) },
                    )
                }

                // Track menus only make sense for native playback.
                if (player != null) {
                    val quality = TrackUtils.choices(player, C.TRACK_TYPE_VIDEO)
                    val audio = TrackUtils.choices(player, C.TRACK_TYPE_AUDIO)
                    val subs = TrackUtils.choices(player, C.TRACK_TYPE_TEXT)

                    if (quality.size > 1) {
                        section("Quality")
                        item {
                            MenuRow("Auto", "adaptive", false) {
                                TrackUtils.autoQuality(player)
                            }
                        }
                        items(quality) { c ->
                            MenuRow(c.label, "video", c.selected) { TrackUtils.apply(player, c) }
                        }
                    }

                    if (audio.size > 1) {
                        section("Audio / Language")
                        items(audio) { c ->
                            MenuRow(c.label, "audio", c.selected) { TrackUtils.apply(player, c) }
                        }
                    }

                    section("Subtitles")
                    item {
                        MenuRow("Off", "no subtitles", subs.none { it.selected }) {
                            TrackUtils.disable(player, C.TRACK_TYPE_TEXT)
                        }
                    }
                    items(subs) { c ->
                        MenuRow(c.label, "subtitle", c.selected) { TrackUtils.apply(player, c) }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun tag(group: ServerGroup): String = when (group) {
    ServerGroup.SUB -> "SUB"
    ServerGroup.DUB -> "DUB"
    ServerGroup.RAW -> "RAW"
}
