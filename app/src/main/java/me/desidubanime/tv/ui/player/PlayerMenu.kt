package me.desidubanime.tv.ui.player

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.model.Playable
import me.desidubanime.tv.data.model.ServerGroup
import me.desidubanime.tv.player.PlayOption
import me.desidubanime.tv.player.PlaybackSession
import me.desidubanime.tv.player.PlayerUiState

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SUB_SCALES = listOf(0.8f, 1f, 1.25f, 1.5f)

/**
 * Slide-in right panel: everything that can be changed without leaving
 * playback — servers, episodes, tracks, speed, picture size, subtitle size,
 * display refresh rate, stats and an external player. D-pad driven; the panel
 * is one LazyColumn, so focus moves in reading order.
 */
@Composable
fun PlayerMenu(
    state: PlayerUiState,
    player: ExoPlayer?,
    onPick: (PlayOption) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    onJumpTo: (Int) -> Unit,
    resizeMode: Int,
    onResize: (Int) -> Unit,
    subScale: Float,
    onSubScale: (Float) -> Unit,
    fpsCap: String,
    onFpsCap: (String) -> Unit,
    statsOpen: Boolean,
    onToggleStats: () -> Unit,
    stream: Playable.Stream?,
    onAddSubtitle: (me.desidubanime.tv.data.model.SubtitleTrack) -> Unit,
    /** A local subtitle file was rewritten; the player must re-read it. */
    onSubtitleShifted: () -> Unit,
) {
    // Without an explicit focus grab the d-pad stays stuck on the video surface
    // and the menu is unusable ("up/down/ok chalna bnd ho gye" bug).
    val closeFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subDelayMs by remember { mutableStateOf(0L) }
    var subDelayNote by remember { mutableStateOf<String?>(null) }
    // The subtitle finder replaces the panel contents while it is open.
    var finding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    // Subtitle delay only exists for files this app owns; a provider-hosted
    // .srt cannot be rewritten and the row is hidden rather than lying.
    val localSubs = stream?.subtitles?.filter { it.url.startsWith("file:") }.orEmpty()

    if (finding) {
        SubtitleFinder(
            onClose = { finding = false },
            onPicked = { result ->
                finding = false
                scope.launch {
                    runCatching { me.desidubanime.tv.data.subtitles.SubApi.fetch(result) }
                        .onSuccess { track -> onAddSubtitle(track) }
                }
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp),
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

                // ---- servers ----
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

                // ---- episodes ----
                val episodes = PlaybackSession.episodes
                if (episodes.size > 1) {
                    section("Episodes")
                    items(episodes.size) { i ->
                        val ep = episodes[i]
                        MenuRow(
                            title = ep.number,
                            subtitle = ep.title.takeIf { it.isNotBlank() } ?: "Episode ${ep.numberInt}",
                            selected = i == state.index,
                            onClick = { onJumpTo(i) },
                        )
                    }
                }

                // Track menus only make sense for native playback.
                if (player != null) {
                    val quality = TrackUtils.choices(player, C.TRACK_TYPE_VIDEO)
                    val audio = TrackUtils.choices(player, C.TRACK_TYPE_AUDIO)
                    val subs = TrackUtils.choices(player, C.TRACK_TYPE_TEXT)

                    if (quality.size > 1) {
                        section("Quality")
                        item {
                            MenuRow("Auto", "adaptive", false) { TrackUtils.autoQuality(player) }
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
                    item {
                        MenuRow("Add subtitle…", "search OpenSubtitles", false) { finding = true }
                    }

                    // ---- subtitle size ----
                    item {
                        ChipRow(
                            label = "Subtitle size",
                            options = SUB_SCALES.map { s -> (if (s == subScale) "▶ " else "") + "${(s * 100).toInt()}%" },
                            selectedIndex = SUB_SCALES.indexOfFirst { it == subScale }.coerceAtLeast(0),
                            onSelect = { i -> onSubScale(SUB_SCALES[i]) },
                        )
                    }

                    // ---- subtitle delay (local files only) ----
                    if (localSubs.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(top = 8.dp)) {
                                Text(
                                    "Subtitle delay  ${"%+.1f".format(subDelayMs / 1000.0)}s",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(
                                    Modifier.padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(onClick = {
                                        subDelayNote = runCatching {
                                            val ok = localSubs.all { shiftSubtitleFile(it.url, -100) }
                                            if (ok) {
                                                subDelayMs -= 100
                                                onSubtitleShifted()
                                                null
                                            } else {
                                                "This subtitle format can't shift"
                                            }
                                        }.getOrDefault("This subtitle format can't shift")
                                    }) { Text("-0.1s") }
                                    Button(onClick = {
                                        subDelayNote = runCatching {
                                            val ok = localSubs.all { shiftSubtitleFile(it.url, 100) }
                                            if (ok) {
                                                subDelayMs += 100
                                                onSubtitleShifted()
                                                null
                                            } else {
                                                "This subtitle format can't shift"
                                            }
                                        }.getOrDefault("This subtitle format can't shift")
                                    }) { Text("+0.1s") }
                                }
                                subDelayNote?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // ---- speed ----
                    item {
                        ChipRow(
                            label = "Speed",
                            options = SPEEDS.map { s -> (if (kotlin.math.abs(s - player.playbackParameters.speed) < 0.01f) "▶ " else "") + label(s) },
                            selectedIndex = SPEEDS.indexOfFirst {
                                kotlin.math.abs(it - player.playbackParameters.speed) < 0.01f
                            }.coerceAtLeast(0),
                            onSelect = { i ->
                                player.setPlaybackSpeed(SPEEDS[i])
                                // Survives the next episode: every episode builds
                                // a new player, which would reset to 1x.
                                PlaybackSession.preferredSpeed = SPEEDS[i]
                            },
                        )
                    }
                }

                // ---- picture size ----
                item {
                    ChipRow(
                        label = "Picture size",
                        options = RESIZE_MODES.map { m -> (if (m == resizeMode) "▶ " else "") + resizeShort(m) },
                        selectedIndex = RESIZE_MODES.indexOf(resizeMode).coerceAtLeast(0),
                        onSelect = { i -> onResize(RESIZE_MODES[i]) },
                    )
                }

                // ---- display refresh rate ----
                item {
                    ChipRow(
                        label = "Frame rate",
                        options = FPS_CAPS.map { (v, text) -> (if (v == fpsCap) "▶ " else "") + text },
                        selectedIndex = FPS_CAPS.indexOfFirst { it.first == fpsCap }.coerceAtLeast(0),
                        onSelect = { i -> onFpsCap(FPS_CAPS[i].first) },
                    )
                }

                // ---- stats + external player ----
                item {
                    Row(
                        Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = onToggleStats) {
                            Text(if (statsOpen) "Hide stats" else "Show stats")
                        }
                        if (stream != null) {
                            Button(onClick = { openExternal(context, stream) }) {
                                Text("External player")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A labelled row of selectable chips — speed, picture size, frame rate, … */
@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { i, text ->
                Button(onClick = { onSelect(i) }) { Text(text, maxLines = 1) }
            }
        }
    }
}

private fun label(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

/**
 * Hands the current stream to whatever the box has installed for video.
 *
 * The headers go along as extras because these hosts require the same
 * Referer/UA the built-in player sends; without them VLC gets a 403 and the
 * viewer blames the app.
 */
private fun openExternal(context: android.content.Context, stream: Playable.Stream) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(Uri.parse(stream.url), "video/*")
            putExtra("title", PlaybackSession.animeTitle)
            stream.headers.forEach { (k, v) -> putExtra(k, v) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Play with"))
    }
}

/**
 * Online subtitle search for the episode that is playing.
 *
 * A TV has no file picker, so "import a subtitle" can only mean "find one".
 * The query is the series name (an episode title finds nothing on
 * OpenSubtitles); picking a row downloads it and rebuilds the player around it,
 * resuming at the position it was left on.
 */
@Composable
private fun SubtitleFinder(
    onClose: () -> Unit,
    onPicked: (me.desidubanime.tv.data.subtitles.SubResult) -> Unit,
) {
    val query = remember { PlaybackSession.animeTitle }
    val backFocus = remember { FocusRequester() }
    var loading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<me.desidubanime.tv.data.subtitles.SubResult>>(emptyList()) }
    LaunchedEffect(query) {
        results = runCatching { me.desidubanime.tv.data.subtitles.SubApi.search(query) }
            .getOrElse { emptyList() }
        loading = false
        runCatching { backFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                item {
                    Text("Add subtitle", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        query.ifBlank { "this episode" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Button(onClick = onClose, modifier = Modifier.focusRequester(backFocus)) {
                        Text("Back to tracks")
                    }
                }
                when {
                    loading -> item {
                        Text(
                            "Searching OpenSubtitles…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    results.isEmpty() -> item {
                        Text(
                            "Nothing found. The subtitles the source shipped are still in the track list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    else -> items(results) { r ->
                        MenuRow(
                            title = r.title.ifBlank { r.lang.uppercase() },
                            subtitle = listOf(r.lang.uppercase(), r.detail).filter { it.isNotBlank() }.joinToString(" · "),
                            selected = false,
                        ) { onPicked(r) }
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
