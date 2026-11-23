package me.desidubanime.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.delay
import me.desidubanime.tv.data.model.ServerGroup
import me.desidubanime.tv.player.PlayOption
import me.desidubanime.tv.player.PlayerUiState

/** Live playback progress, polled off the player on the main thread. */
data class PlayerProgress(
    val position: Long = 0L,
    val duration: Long = 0L,
    val buffered: Long = 0L,
    val playing: Boolean = true,
)

@Composable
fun rememberPlayerProgress(exo: ExoPlayer): PlayerProgress {
    var progress by remember(exo) { mutableStateOf(PlayerProgress()) }
    LaunchedEffect(exo) {
        while (true) {
            progress = PlayerProgress(
                position = exo.currentPosition.coerceAtLeast(0L),
                duration = exo.duration.coerceAtLeast(0L),
                buffered = exo.bufferedPosition.coerceAtLeast(0L),
                playing = exo.playWhenReady,
            )
            delay(400)
        }
    }
    return progress
}

/** Which picker panel is open above the control bar. */
private enum class Picker { NONE, SERVER, QUALITY, AUDIO, SUBS, SPEED }

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

/**
 * All-in-one bottom control bar shown on OK: title, seek bar, transport, and
 * status chips (Server / Quality / Audio / Subs / Speed) that each show their
 * CURRENT value and open a picker panel right above the bar. All-Compose so
 * the d-pad navigates it natively. Auto-hides after 6s idle (pickers pin it).
 */
@Composable
fun ControlsOverlay(
    state: PlayerUiState,
    exo: ExoPlayer,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPickOption: (PlayOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val progress = rememberPlayerProgress(exo)
    val playFocus = remember { FocusRequester() }
    val pickerFocus = remember { FocusRequester() }
    var interaction by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(Picker.NONE) }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    LaunchedEffect(picker) {
        if (picker != Picker.NONE) {
            runCatching { pickerFocus.requestFocus() }
        } else {
            // Panel just closed (row picked / toggled shut): without an owner
            // the next OK lands on the first button ("Prev") and jumps an
            // episode back — park focus on Play instead.
            runCatching { playFocus.requestFocus() }
        }
    }
    LaunchedEffect(interaction, picker) {
        if (picker == Picker.NONE) {
            delay(6_000)
            onDismiss()
        }
    }

    // Track choices refresh with the progress tick, so labels stay current
    // after manifest parsing finishes or the user changes a track.
    val quality = remember(progress) { TrackUtils.choices(exo, C.TRACK_TYPE_VIDEO) }
    val audio = remember(progress) { TrackUtils.choices(exo, C.TRACK_TYPE_AUDIO) }
    val subs = remember(progress) { TrackUtils.choices(exo, C.TRACK_TYPE_TEXT) }
    val speed = exo.playbackParameters.speed

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    1.0f to Color(0xE6000000),
                ),
            )
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) interaction++
                false
            },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- picker panel (above the bar) ----
            when (picker) {
                Picker.SERVER -> PickerPanel("Server") {
                    state.options.forEachIndexed { i, opt ->
                        PickerRow(
                            title = opt.label,
                            subtitle = serverTag(opt),
                            selected = opt == state.selected,
                            focusRequester = pickerFocus.takeIf { i == 0 },
                        ) { picker = Picker.NONE; onPickOption(opt) }
                    }
                }
                Picker.QUALITY -> PickerPanel("Quality") {
                    PickerRow("Auto", "adaptive", quality.none { it.selected }, pickerFocus) {
                        TrackUtils.autoQuality(exo); picker = Picker.NONE
                    }
                    quality.forEach { c ->
                        PickerRow(c.label, "video", c.selected) { TrackUtils.apply(exo, c); picker = Picker.NONE }
                    }
                }
                Picker.AUDIO -> PickerPanel("Audio / Language") {
                    audio.forEachIndexed { i, c ->
                        PickerRow(c.label, "audio", c.selected, pickerFocus.takeIf { i == 0 }) {
                            TrackUtils.apply(exo, c); picker = Picker.NONE
                        }
                    }
                }
                Picker.SUBS -> PickerPanel("Subtitles") {
                    PickerRow("Off", "no subtitles", subs.none { it.selected }, pickerFocus) {
                        TrackUtils.disable(exo, C.TRACK_TYPE_TEXT); picker = Picker.NONE
                    }
                    subs.forEach { c ->
                        PickerRow(c.label, "subtitle", c.selected) { TrackUtils.apply(exo, c); picker = Picker.NONE }
                    }
                }
                Picker.SPEED -> PickerPanel("Speed") {
                    SPEEDS.forEachIndexed { i, s ->
                        PickerRow(speedLabel(s), "playback speed", s == speed, pickerFocus.takeIf { i == 0 }) {
                            exo.setPlaybackSpeed(s)
                            // Survives the next episode: every episode builds a
                            // new player, which would otherwise reset to 1x.
                            me.desidubanime.tv.player.PlaybackSession.preferredSpeed = s
                            picker = Picker.NONE
                        }
                    }
                }
                Picker.NONE -> {}
            }

            Text(
                state.episodeTitle,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            // The timeline is focusable, and while it holds focus LEFT/RIGHT
            // is one ±10% step — the convention of every streaming player, and
            // the only way to cross a 50-minute episode in a few presses. It
            // has to be handled HERE: the screen's root key handler stands
            // down while any overlay is open, so it never sees these keys.
            val scrubberFocus = remember { FocusRequester() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(scrubberFocus)
                    .focusable()
                    .clip(RoundedCornerShape(6.dp))
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = (progress.duration / 10).coerceAtLeast(10_000L)
                        when (e.key) {
                            Key.DirectionLeft -> { onSeek(-step); true }
                            Key.DirectionRight -> { onSeek(step); true }
                            else -> false
                        }
                    },
            ) {
                ProgressBarRow(progress)
            }

            // ---- transport ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Button(onClick = onPrev, enabled = state.hasPrev) { Text("|◀ Prev") }
                Button(onClick = { onSeek(-10_000) }) { Text("◀◀ 10s") }
                Button(
                    onClick = { exo.playWhenReady = !exo.playWhenReady; interaction++ },
                    modifier = Modifier.focusRequester(playFocus),
                ) { Text(if (progress.playing) "❚❚ Pause" else "▶ Play") }
                Button(onClick = { onSeek(10_000) }) { Text("10s ▶▶") }
                Button(onClick = onNext, enabled = state.hasNext) { Text("Next ▶|") }
            }

            // ---- status chips: label shows the CURRENT value ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                StatusChip("Server", state.selected?.label ?: "-") {
                    picker = if (picker == Picker.SERVER) Picker.NONE else Picker.SERVER
                }
                StatusChip("Quality", quality.firstOrNull { it.selected }?.label ?: "Auto") {
                    picker = if (picker == Picker.QUALITY) Picker.NONE else Picker.QUALITY
                }
                if (audio.isNotEmpty()) {
                    StatusChip("Audio", audio.firstOrNull { it.selected }?.label ?: "Default") {
                        picker = if (picker == Picker.AUDIO) Picker.NONE else Picker.AUDIO
                    }
                }
                StatusChip("Subs", subs.firstOrNull { it.selected }?.label ?: "Off") {
                    picker = if (picker == Picker.SUBS) Picker.NONE else Picker.SUBS
                }
                StatusChip("Speed", speedLabel(speed)) {
                    picker = if (picker == Picker.SPEED) Picker.NONE else Picker.SPEED
                }
            }
        }
    }
}

/** "Label · current-value" button — the all-in-one bar's status readout. */
@Composable
private fun StatusChip(label: String, value: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text("$label: ", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium)
        Text(value.take(28), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PickerPanel(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.width(460.dp),
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).fillMaxWidth(),
    )
}

/** Transient progress readout shown while seeking with ◀/▶ — never takes focus. */
@Composable
fun SeekHud(
    exo: ExoPlayer,
    tick: Int,
    deltaMs: Long = 0L,
    targetMs: Long = 0L,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = rememberPlayerProgress(exo)
    LaunchedEffect(tick) {
        delay(1_500)
        onHide()
    }
    Column(
        modifier.padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // While a burst is pending the play head is still at the old spot, so
        // the badge is the only thing that can say where the seek will land.
        if (deltaMs != 0L) {
            SeekBadge(
                deltaMs = deltaMs,
                targetMs = if (targetMs > 0) targetMs else progress.position,
                durationMs = progress.duration,
            )
        }
        Row(
            Modifier
                .fillMaxWidth(0.62f)
                .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            ProgressBarRow(progress)
        }
    }
}

/** Compact top panel opened with ▲: pick a server without leaving playback. */
@Composable
fun ServerQuickList(
    options: List<PlayOption>,
    selected: PlayOption?,
    onPick: (PlayOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusIndex = options.indexOf(selected).takeIf { it >= 0 } ?: 0
    val focus = remember { FocusRequester() }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(interaction) {
        delay(10_000)
        onDismiss()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) interaction++
                false
            },
    ) {
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).width(500.dp),
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Servers",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (options.isEmpty()) {
                    Text("No servers yet — still loading…")
                }
                options.forEachIndexed { i, opt ->
                    ListItem(
                        selected = opt == selected,
                        onClick = { onPick(opt) },
                        headlineContent = {
                            Text(opt.label, fontWeight = if (opt == selected) FontWeight.Bold else FontWeight.Normal)
                        },
                        supportingContent = { Text(serverTag(opt)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = (if (i == focusIndex) Modifier.focusRequester(focus) else Modifier).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBarRow(p: PlayerProgress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(p.position), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x40FFFFFF)),
        ) {
            val dur = p.duration.takeIf { it > 0 }
            if (dur != null) {
                Box(
                    Modifier
                        .fillMaxWidth((p.buffered.toFloat() / dur).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color(0x66FFFFFF)),
                )
                Box(
                    Modifier
                        .fillMaxWidth((p.position.toFloat() / dur).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(formatTime(p.duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun serverTag(opt: PlayOption): String {
    val group = when (opt.group) {
        ServerGroup.SUB -> "SUB"
        ServerGroup.DUB -> "DUB"
        ServerGroup.RAW -> "RAW"
    }
    return group + if (opt.native) " · native" else " · embed"
}
