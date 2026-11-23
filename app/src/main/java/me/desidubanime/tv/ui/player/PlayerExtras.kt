package me.desidubanime.tv.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Everything the player offers beyond "press OK to play": the stats pane, the
 * picture-size modes, subtitle sizing, the display frame-rate cap and the
 * VLC-style subtitle delay.
 *
 * All of it talks to the `PlayerView`/`ExoPlayer` surfaces directly, which is
 * why it lives outside the composables that build them.
 */

// ---- aspect / picture size ------------------------------------------------

/**
 * Picture-size modes in the menu order. The values are
 * [AspectRatioFrameLayout.RESIZE_MODE_*] constants; keeping them as a plain
 * Int list is what lets the choice be persisted and passed around without
 * dragging a view type through the UI state.
 */
val RESIZE_MODES: List<Int> = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
)

fun resizeLabel(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit (keep aspect)"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch (fill screen)"
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom (crop edges)"
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Crop (fill height)"
    else -> "Fit"
}

/** Short label for the stats line / chips ("Fit", "Zoom", …). */
fun resizeShort(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Crop"
    else -> "Fit"
}

/**
 * Applies a picture-size mode to the surface.
 *
 * A 2.35:1 film on a 16:9 TV leaves black bars; some encodes ship with a wrong
 * aspect flag and come out squashed. Both are fixed here without touching the
 * stream.
 */
fun applyResizeMode(view: PlayerView, mode: Int) {
    view.resizeMode = mode
}

// ---- subtitle styling -----------------------------------------------------

/**
 * Subtitle size/edge, mirroring what a set-top box's own caption settings do.
 *
 * [scale] multiplies Media3's default 1/18.75-of-height text size. The style is
 * re-applied on every track change by the player view itself, so it is safe to
 * call this more than once.
 */
fun applySubtitleStyle(view: PlayerView, scale: Float) {
    runCatching {
        val sv = view.subtitleView ?: return
        sv.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                null,
            ),
        )
        sv.setFractionalTextSize(0.0533f * scale.coerceIn(0.6f, 2.2f))
    }
}

// ---- display frame rate ---------------------------------------------------

/**
 * Hints the panel to a fixed refresh rate (API 30+).
 *
 * On a TV box the panel otherwise follows the last app that ran, and a 24 fps
 * film shown at 60 Hz judders on every pan. "auto" clears the hint and lets the
 * display decide for itself.
 */
fun applyFrameRateCap(view: PlayerView, cap: String) {
    if (Build.VERSION.SDK_INT < 30) return
    runCatching {
        val sv = view.videoSurfaceView as? android.view.SurfaceView ?: return
        val surface = sv.holder.surface ?: return
        if (!surface.isValid) return
        val fps = cap.toFloatOrNull()
        if (fps == null) {
            surface.clearFrameRate()
        } else {
            surface.setFrameRate(fps, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }
}

val FPS_CAPS: List<Pair<String, String>> = listOf(
    "auto" to "Auto",
    "24" to "24",
    "25" to "25",
    "30" to "30",
    "50" to "50",
    "60" to "60",
)

// ---- stats ----------------------------------------------------------------

/** A snapshot of what the decoder is actually doing, for the INFO pane. */
data class PlayerStats(
    val video: String = "—",
    val audio: String = "—",
    val bufferedMs: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val state: String = "—",
)

/** Polls the player at 1 Hz — stats are a readout, not an animation. */
@Composable
fun rememberPlayerStats(exo: ExoPlayer?): PlayerStats {
    var stats by remember(exo) { mutableStateOf(PlayerStats()) }
    LaunchedEffect(exo) {
        val p = exo ?: return@LaunchedEffect
        while (true) {
            val video = p.videoFormat?.let { f ->
                buildString {
                    if (f.height > 0) append("${f.height}p") else append("?")
                    if (f.width > 0 && f.height > 0) append(" (${f.width}×${f.height})")
                    if (f.frameRate > 0) append(" · %.2f fps".format(f.frameRate))
                    f.codecs?.let { append(" · $it") }
                    if (f.bitrate > 0) append(" · ${f.bitrate / 1000} kbps")
                }
            } ?: "—"
            val audio = p.audioFormat?.let { f ->
                buildString {
                    append(f.label ?: f.language?.uppercase() ?: "audio")
                    if (f.channelCount > 0) append(" · ${f.channelCount}ch")
                    if (f.sampleRate > 0) append(" · ${f.sampleRate / 1000} kHz")
                }
            } ?: "—"
            stats = PlayerStats(
                video = video,
                audio = audio,
                bufferedMs = p.bufferedPosition.coerceAtLeast(0),
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.coerceAtLeast(0),
                speed = p.playbackParameters.speed,
                state = when (p.playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> "idle"
                    androidx.media3.common.Player.STATE_BUFFERING -> "buffering"
                    androidx.media3.common.Player.STATE_READY -> if (p.playWhenReady) "playing" else "paused"
                    androidx.media3.common.Player.STATE_ENDED -> "ended"
                    else -> "—"
                },
            )
            delay(1_000)
        }
    }
    return stats
}

/** VLC's info pane: what is playing, from where, and how well. */
@Composable
fun StatsOverlay(
    stats: PlayerStats,
    resizeMode: Int,
    serverLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE614141A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Stats",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        listOf(
            "State: ${stats.state}",
            "Video: ${stats.video}",
            "Audio: ${stats.audio}",
            "Buffer: ${fmtClock(stats.bufferedMs)} / ${fmtClock(stats.durationMs)}",
            "Position: ${fmtClock(stats.positionMs)}",
            "Speed: ${formatSpeedLabel(stats.speed)} · ${resizeShort(resizeMode)}",
            "Server: ${serverLabel.ifBlank { "—" }}",
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xE6FFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun formatSpeedLabel(speed: Float): String =
    if (abs(speed - 1f) < 0.01f) "1x" else "%.2fx".format(speed).trimEnd('0').trimEnd('.')

// ---- seek badge -----------------------------------------------------------

/**
 * The seek readout shown while ◀/▶ are held.
 *
 * A remote sends a burst of key events; the player deliberately seeks once, at
 * the end of the burst. The play head therefore cannot show the target, so this
 * badge does: direction, how far, and where the picture will land.
 */
@Composable
fun SeekBadge(
    deltaMs: Long,
    targetMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (deltaMs < 0) "◀◀ " else "▶▶ ") + "%+ds".format(deltaMs / 1000),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                fmtClock(targetMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x40FFFFFF)),
        ) {
            if (durationMs > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((targetMs.toFloat() / durationMs).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            fmtClock(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xB3FFFFFF),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- subtitle delay -------------------------------------------------------

/**
 * VLC-style subtitle delay for a subtitle file this app owns.
 *
 * Media3 has no timing offset for a subtitle track, so the only place a delay
 * can live is the text itself: a local `.srt`/`.vtt` is rewritten in place and
 * the player re-reads it. Provider-hosted subtitle URLs are NOT touched —
 * rewriting someone else's file is not ours to do — and report false, which is
 * why the caller shows a "can't shift this subtitle" note instead of pretending.
 */
fun shiftSubtitleFile(uriString: String, deltaMs: Long): Boolean {
    val uri = android.net.Uri.parse(uriString)
    if (uri.scheme != "file") return false
    val file = uri.path?.let { java.io.File(it) } ?: return false
    if (!file.isFile) return false
    return runCatching {
        val before = file.readText()
        val after = shiftSubtitleTimes(before, deltaMs)
        if (after == before) {
            false
        } else {
            file.writeText(after)
            true
        }
    }.getOrDefault(false)
}

// SRT "00:00:01,000", VTT "00:00:01.000" / "00:01.000", ASS "0:00:01.00".
// The fraction width is preserved so ASS centiseconds stay centiseconds.
private val SUB_TIME = Regex(
    """(?:(?<hh>\d{1,3}):)?(?<mm>\d{1,2}):(?<ss>\d{2})[,.](?<frac>\d{1,3})""",
)

fun shiftSubtitleTimes(text: String, deltaMs: Long): String =
    SUB_TIME.replace(text) { m ->
        val hh = m.groups["hh"]?.value?.toLongOrNull() ?: 0L
        val mm = m.groups["mm"]!!.value.toLong()
        val ss = m.groups["ss"]!!.value.toLong()
        val fracRaw = m.groups["frac"]!!.value
        val fracMs = when (fracRaw.length) {
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.toLong()
        }
        var total = hh * 3_600_000 + mm * 60_000 + ss * 1000 + fracMs + deltaMs
        if (total < 0) total = 0
        val sep = if (m.value.contains(',')) ',' else '.'
        val fracOut = when (fracRaw.length) {
            1 -> (total % 1000) / 100
            2 -> (total % 1000) / 10
            else -> total % 1000
        }.toString().padStart(fracRaw.length, '0')
        val mOut = "%02d".format((total / 60_000) % 60)
        val sOut = "%02d".format((total / 1000) % 60)
        if (m.groups["hh"] != null) {
            "${total / 3_600_000}:$mOut:$sOut$sep$fracOut"
        } else {
            "${total / 60_000}:$sOut$sep$fracOut"
        }
    }

// ---- shared formatting ----------------------------------------------------

fun fmtClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
