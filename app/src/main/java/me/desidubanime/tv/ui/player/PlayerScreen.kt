package me.desidubanime.tv.ui.player

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Playable
import me.desidubanime.tv.player.PlaybackSession
import me.desidubanime.tv.player.PlayerKeyBus
import me.desidubanime.tv.player.PlayerViewModel
import me.desidubanime.tv.player.StreamResolver
import me.desidubanime.tv.data.DebugLog
import me.desidubanime.tv.data.ServiceLocator

private enum class Overlay { NONE, CONTROLS, SERVERS, MENU, NEXT }

/** How long the "Up Next" card waits before it advances by itself. */
private const val NEXT_COUNTDOWN_S = 10

@Composable
fun PlayerScreen(
    startIndex: Int,
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showLogs by remember { mutableStateOf(false) }
    val logs by DebugLog.lines.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        showLogs = ServiceLocator.settings.getShowPlayerLogs()
    }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var showSeekHud by remember { mutableStateOf(false) }
    var seekHudTick by remember { mutableIntStateOf(0) }
    var showHint by remember { mutableStateOf(true) }
    // Binge flow: when an episode ends, offer the next one for a few seconds
    // instead of jumping under the viewer's hands. OK plays now, BACK cancels.
    var upNextSeconds by remember { mutableIntStateOf(NEXT_COUNTDOWN_S) }
    var upNextDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.start(if (startIndex >= 0) startIndex else PlaybackSession.startIndex)
    }

    // Catalog sweeps (hundreds of HTML fetches plus Jsoup parses) share the TV
    // box's cores and uplink with the decoder. Park them for the whole player
    // session — resolution WebViews need that headroom too, not just playback.
    DisposableEffect(Unit) {
        me.desidubanime.tv.data.api.BackgroundYield.paused = true
        onDispose { me.desidubanime.tv.data.api.BackgroundYield.paused = false }
    }

    DisposableEffect(Unit) {
        PlayerKeyBus.onMenu = { overlay = if (overlay == Overlay.MENU) Overlay.NONE else Overlay.MENU }
        PlayerKeyBus.onBack = {
            if (overlay != Overlay.NONE) {
                overlay = Overlay.NONE
                true
            } else {
                false
            }
        }
        onDispose { PlayerKeyBus.onMenu = null; PlayerKeyBus.onBack = null }
    }

    // A fresh episode re-arms auto-advance: saying "no" to episode 5's card
    // must not silence episode 6's.
    LaunchedEffect(state.index) {
        upNextDismissed = false
        upNextSeconds = NEXT_COUNTDOWN_S
    }

    // Up Next countdown. Keyed on the overlay, so cancelling, switching server
    // or leaving the player aborts the timer by simply closing the card.
    LaunchedEffect(overlay) {
        if (overlay != Overlay.NEXT) return@LaunchedEffect
        while (upNextSeconds > 0) {
            delay(1_000)
            upNextSeconds--
        }
        overlay = Overlay.NONE
        vm.next()
    }

    val stream = state.playable as? Playable.Stream
    val exo = rememberStreamPlayer(
        stream = stream,
        // Read the CURRENT state instead of a captured one: this listener is
        // installed once per player, so a captured `state` would be the one
        // from the moment the stream opened.
        onEnded = {
            if (vm.state.value.hasNext && !upNextDismissed) {
                upNextSeconds = NEXT_COUNTDOWN_S
                overlay = Overlay.NEXT
            }
        },
        // The stream resolved but will not play (dead signed link, 403 after
        // the clearance expired). Surface it and open the server list so the
        // user can pick another one — previously this was a black screen with
        // no explanation and no way forward except BACK.
        onDead = { code ->
            vm.reportPlaybackFailure(code)
            overlay = Overlay.SERVERS
        },
    )
    val rootFocus = remember { FocusRequester() }
    // Live handle on the embed WebView so remote keys can tap into it.
    val embedWebView = remember { mutableStateOf<WebView?>(null) }
    // Set by SkipPrompts while a prompt is visible; OK fires it directly
    // (the root Box owns the d-pad, so the chip can't take focus itself).
    val skipAction = remember { mutableStateOf<(() -> Unit)?>(null) }

    // Player gone (server switch / next episode) -> a player-bound overlay makes no sense.
    LaunchedEffect(exo) { if (exo == null && overlay == Overlay.CONTROLS) overlay = Overlay.NONE }

    // Whenever no overlay wants the d-pad, park focus on the video surface so
    // OK/arrows land in our key handler instead of dying in the PlayerView.
    LaunchedEffect(overlay, state.playable) {
        if (overlay == Overlay.NONE) runCatching { rootFocus.requestFocus() }
    }

    // Key hint fades out a few seconds after playback starts.
    LaunchedEffect(state.playable) {
        showHint = true
        delay(6_000)
        showHint = false
    }

    // Continue Watching: seek to the saved spot once, then report progress
    // every few seconds while the stream plays.
    LaunchedEffect(exo) {
        val p = exo ?: return@LaunchedEffect
        PlaybackSession.resumePositionMs.takeIf { it > 0 }?.let {
            p.seekTo(it)
            PlaybackSession.resumePositionMs = 0
        }
        // Every write re-serialises the whole Continue Watching list to
        // DataStore. At 5s that was a disk write (and a JSON encode) every few
        // seconds for the entire episode — on a TV box that is measurable I/O
        // next to the decoder. 15s is still a fine-grained resume point, and
        // exiting the player writes the exact position anyway.
        while (true) {
            if (p.isPlaying) vm.reportProgress(p.currentPosition, p.duration.coerceAtLeast(0))
            delay(15_000)
        }
    }

    // The 5s ticker writes through the ViewModel scope, which is cancelled the
    // moment this screen pops. Leaving the player is the last chance to persist
    // the exact position, so that one write runs on its own IO scope.
    DisposableEffect(exo) {
        onDispose {
            exo?.let { p ->
                val pos = p.currentPosition
                val dur = p.duration
                if (pos > 0 && dur > 0) vm.snapshotProgress(pos, dur)
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val p = exo ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, dur))
        showSeekHud = true
        seekHudTick++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (overlay != Overlay.NONE) {
                    // Overlays own the d-pad; BACK closes them (also covered by PlayerKeyBus).
                    return@onPreviewKeyEvent if (e.key == Key.Back) {
                        overlay = Overlay.NONE
                        true
                    } else {
                        false
                    }
                }
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionDown ->
                        if (skipAction.value != null) {
                            skipAction.value?.invoke(); true
                        } else if (exo != null) {
                            overlay = Overlay.CONTROLS; true
                        } else {
                            // Embed playback: OK = a real tap at the centre, so the
                            // "click play to verify you are human" gate is clickable
                            // from a TV remote.
                            embedWebView.value?.let { StreamResolver.AutoPlay.tapCenter(it); true } ?: false
                        }
                    Key.DirectionUp -> { overlay = Overlay.SERVERS; true }
                    Key.DirectionLeft, Key.MediaRewind ->
                        if (exo != null) { seekBy(-10_000); true } else false
                    Key.DirectionRight, Key.MediaFastForward ->
                        if (exo != null) { seekBy(10_000); true } else false
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar ->
                        if (exo != null) { exo.playWhenReady = !exo.playWhenReady; true } else false
                    Key.MediaNext -> if (state.hasNext) { vm.next(); true } else false
                    Key.MediaPrevious -> if (state.hasPrev) { vm.previous(); true } else false
                    Key.Menu -> { overlay = Overlay.MENU; true }
                    else -> false
                }
            },
    ) {
        when (val p = state.playable) {
            is Playable.Stream -> if (exo != null) NativePlayerView(exo)
            is Playable.Embed -> EmbedPlayer(p.url, onWebView = { embedWebView.value = it })
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.loading) CircularProgressIndicator()
                    Text(state.message ?: "Loading…", color = Color.White)
                }
            }
        }

        if (exo != null) BufferingOverlay(exo)

        // Skip prompts from the MKV's own chapter markers (Intro/OP/ED) —
        // exact per-episode timings, the same info VLC shows on its seekbar.
        if (exo != null && overlay == Overlay.NONE) {
            SkipPrompts(
                exo = exo,
                hasNext = state.hasNext,
                windows = state.skipWindows,
                onSeekTo = { pos -> exo.seekTo(pos) },
                onNext = { vm.next() },
                setAction = { skipAction.value = it },
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
            )
        } else {
            skipAction.value = null
        }

        if (showHint && overlay == Overlay.NONE && state.playable != null) {
            Text(
                if (exo != null) "OK · controls      ▲ · servers      ◀ ▶ · seek" else "OK · play      ▲ · servers      MENU · settings",
                color = Color(0x88FFFFFF),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }

        if (showSeekHud && overlay == Overlay.NONE && exo != null) {
            SeekHud(
                exo = exo,
                tick = seekHudTick,
                onHide = { showSeekHud = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        when (overlay) {
            Overlay.CONTROLS -> if (exo != null) {
                ControlsOverlay(
                    state = state,
                    exo = exo,
                    onPrev = { overlay = Overlay.NONE; vm.previous() },
                    onNext = { overlay = Overlay.NONE; vm.next() },
                    onSeek = { seekBy(it) },
                    onPickOption = { overlay = Overlay.NONE; vm.selectOption(it, exo.currentPosition) },
                    onDismiss = { overlay = Overlay.NONE },
                )
            }
            Overlay.SERVERS -> ServerQuickList(
                options = state.options,
                selected = state.selected,
                onPick = { overlay = Overlay.NONE; vm.selectOption(it, exo?.currentPosition ?: 0) },
                onDismiss = { overlay = Overlay.NONE },
            )
            Overlay.MENU -> PlayerMenu(
                state = state,
                player = exo,
                onPick = { vm.selectOption(it, exo?.currentPosition ?: 0); overlay = Overlay.NONE },
                onNext = { vm.next(); overlay = Overlay.NONE },
                onPrev = { vm.previous(); overlay = Overlay.NONE },
                onClose = { overlay = Overlay.NONE },
            )
            Overlay.NEXT -> UpNextCard(
                nextLabel = PlaybackSession.episodes.getOrNull(state.index + 1)?.number
                    ?: "Next episode",
                secondsLeft = upNextSeconds,
                onPlayNow = { overlay = Overlay.NONE; vm.next() },
                onCancel = { upNextDismissed = true; overlay = Overlay.NONE },
            )
            Overlay.NONE -> {}
        }

        // TW4A phone-unlock sheet — only show when no other overlay is active
        if (overlay == Overlay.NONE) {
            state.twUnlockUrl?.let { u ->
                me.desidubanime.tv.ui.tw.TwQrScreen(
                    targetUrl = u,
                    onClose = { vm.dismissTwUnlock() },
                    onUnlocked = { vm.resumeTwUnlock(exo?.currentPosition ?: 0L) },
                    animeId = PlaybackSession.animeId,
                )
            }
        }

        // Logs toggle button — only while a player overlay is open, not permanent.
        if (overlay != Overlay.NONE) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                androidx.tv.material3.Button(
                    onClick = {
                        val next = !showLogs
                        showLogs = next
                        coroutineScope.launch {
                            ServiceLocator.settings.setShowPlayerLogs(next)
                        }
                    },
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = if (showLogs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(if (showLogs) "Hide Logs" else "Show Logs")
                }
            }
        }
        if (showLogs) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color(0xCC000000))
                    .padding(8.dp)
            ) {
                Column {
                    logs.takeLast(20).forEach { line ->
                        Text(
                            line,
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Auto skip prompts from the stream's own chapter markers.
 * MKV chapter markers ("Intro"/"OP"/"ED") read off the stream itself —
 * exact per-episode, the same data VLC shows on its seekbar. The chip stays up 5 seconds with a countdown; while visible OK fires it
 * (the root Box routes DPAD_CENTER here via setAction).
 */
@Composable
private fun SkipPrompts(
    exo: ExoPlayer,
    hasNext: Boolean,
    windows: me.desidubanime.tv.player.MkvChapters.SkipWindows?,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    setAction: ((() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) { onDispose { setAction(null) } }
    if (windows == null) return

    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var introDismissed by remember(exo) { mutableStateOf(false) }
    var outroDismissed by remember(exo) { mutableStateOf(false) }
    LaunchedEffect(exo) {
        while (true) {
            pos = exo.currentPosition
            dur = exo.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    // OP = the ~90s opening song; ED = the ending song. Skipping seeks past
    // the chapter's END — content after the ED (PV/after-credits) still plays.
    val introStart = windows.intro?.startMs ?: -1L
    val introEnd = windows.intro?.endMs?.takeIf { it > 0 } ?: -1L
    val outroStart = windows.outro?.startMs ?: -1L
    val outroEnd = windows.outro?.endMs?.takeIf { it > 0 } ?: -1L

    val inIntro = !introDismissed && introStart >= 0 && introEnd > 0 &&
        pos in introStart..(introEnd - 2_000)
    val inOutro = !inIntro && !outroDismissed && outroStart > 0 && outroEnd > 0 &&
        pos in outroStart..(outroEnd - 2_000)

    // 5s visibility window from the moment a prompt first triggers.
    var shownAt by remember { mutableStateOf(0L) }
    var shownKind by remember { mutableStateOf("") }
    val kind = if (inIntro) "intro" else if (inOutro) "outro" else ""
    if (kind != shownKind) {
        shownKind = kind
        if (kind.isNotEmpty()) shownAt = android.os.SystemClock.elapsedRealtime()
    }
    val elapsed = android.os.SystemClock.elapsedRealtime() - shownAt
    val visible = kind.isNotEmpty() && elapsed < 5_000
    val secondsLeft = ((5_000 - elapsed) / 1000 + 1).coerceIn(1, 5)

    val action: (() -> Unit)? = when {
        visible && inIntro -> {
            {
                introDismissed = true
                onSeekTo(introEnd)
            }
        }
        visible && inOutro -> {
            {
                outroDismissed = true
                onSeekTo(outroEnd)
            }
        }
        else -> null
    }
    // Republish on every state change; cleared by the outer DisposableEffect.
    setAction(action)

    if (!visible) return
    androidx.tv.material3.Surface(
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = Color(0xDD000000),
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            if (inIntro) "OK · Skip Intro ↷ (${secondsLeft}s)" else "OK · Skip Outro ↷ (${secondsLeft}s)",
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * Spinner shown while the player rebuffers mid-playback.
 *
 * Shown only after a short grace period: a seek or a 200ms hiccup flips
 * through STATE_BUFFERING constantly, and a spinner that appears for one frame
 * reads as a stutter rather than as information.
 */
@Composable
private fun BufferingOverlay(exo: ExoPlayer) {
    var buffering by remember(exo) { mutableStateOf(exo.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
    var visible by remember(exo) { mutableStateOf(false) }
    LaunchedEffect(buffering) {
        if (!buffering) {
            visible = false
            return@LaunchedEffect
        }
        delay(350)
        visible = true
    }
    if (visible) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/**
 * "Up Next" card shown when an episode ends: what is coming, how long until it
 * starts, and a way to say no. Auto-advance used to fire instantly, which cut
 * the last frame (post-credit scenes, episode previews) and gave the remote no
 * way to stop it.
 */
@Composable
private fun UpNextCard(
    nextLabel: String,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.BottomEnd) {
        androidx.tv.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.padding(32.dp).width(420.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Up Next in ${secondsLeft}s",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    nextLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.tv.material3.Button(
                        onClick = onPlayNow,
                        modifier = Modifier.focusRequester(playFocus),
                    ) { Text("▶  Play now") }
                    androidx.tv.material3.Button(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

/** Builds/rebuilds an ExoPlayer for the current stream and releases it on change/dispose. */@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun rememberStreamPlayer(
    stream: Playable.Stream?,
    onEnded: () -> Unit,
    onDead: (String) -> Unit,
): ExoPlayer? {
    if (stream == null) return null
    val context = LocalContext.current
    // `isLowRamDevice` is the documented way to size buffers for TV hardware
    // (device RAM alone is not): a 1 GB stick must not buffer as if it were a
    // 2 GB box, or the player becomes the process the system kills first.
    val lowRam = remember(context) {
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.isLowRamDevice == true
    }
    // The listener below is installed once per player; rememberUpdatedState
    // keeps its callbacks pointing at this composition's logic.
    val currentOnEnded by androidx.compose.runtime.rememberUpdatedState(onEnded)
    val currentOnDead by androidx.compose.runtime.rememberUpdatedState(onDead)
    val exo = remember(stream.url) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.headers["User-Agent"] ?: Api.USER_AGENT)
            .setDefaultRequestProperties(stream.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
        // Sidecar subtitles the resolver sniffed next to the stream (.vtt/.srt).
        val subtitleConfigs = stream.subtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(if (sub.isVtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLabel(sub.label)
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
        ExoPlayer.Builder(
            context,
            // A codec the box cannot decode (10-bit HEVC on older Amlogic
            // chips) used to fail the stream outright; the fallback renderer
            // lets the hardware decoder fail over to software instead.
            androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true),
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            // Bursty hosts (R2/pixeldrain/CLOUD) hand out media in clumps with
            // dead patches; a short forward buffer drains mid-clump and the
            // picture stutters. 45s/75s rides those gaps out while the byte cap
            // keeps the heap honest. The old fixed 180s buffer could hold
            // ~120 MB at 5 Mbps, which is how a 1 GB box ends up killing the
            // player mid-episode.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ if (lowRam) 15_000 else 20_000,
                        /* maxBufferMs = */ if (lowRam) 45_000 else 75_000,
                        /* bufferForPlaybackMs = */ 3_000,
                        /* bufferForPlaybackAfterRebufferMs = */ if (lowRam) 6_000 else 8_000,
                    )
                    // The byte cap is the real memory guard on a 1-2 GB box.
                    // prioritizeTimeOverSizeThresholds stays false (default),
                    // so this cap wins over the time budget.
                    .setTargetBufferBytes(if (lowRam) 32 * 1024 * 1024 else 64 * 1024 * 1024)
                    // The back buffer is drawn from the same allocator as the
                    // forward buffer. A small one makes ◀ scrubs cheap; none at
                    // all on low-RAM boxes, where every megabyte belongs to
                    // the stream in front of the viewer.
                    .setBackBuffer(if (lowRam) 0 else 10_000, true)
                    .build(),
            )
            .build().apply {
                // TV + wifi: start at the top rung instead of adaptive's lowest.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setForceHighestSupportedBitrate(false)
                    .build()
                // Output is a movie, and audio focus must be handled: on a TV
                // the stream often goes to a soundbar, and pausing/ducking for
                // other apps (or the system) has to reach it.
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // Some TV firmware drops Wi-Fi into power-save between HLS
                // segments; a session-long network wake lock removes that
                // source of periodic stalls.
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                // Speed chosen for the previous episode (binge continuity).
                PlaybackSession.preferredSpeed?.let { setPlaybackSpeed(it) }
                setMediaItem(mediaItem)
                prepare()
                // Resume BEFORE the first frame. Seeking from a LaunchedEffect
                // ran a frame or two after playback had already started, so a
                // resumed episode visibly flashed its opening seconds first.
                val startAt = PlaybackSession.resumePositionMs
                if (startAt > 0) {
                    seekTo(startAt)
                    PlaybackSession.resumePositionMs = 0
                }
                playWhenReady = true
            }
    }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentOnEnded()
            }

            // Fresh player per episode -> re-apply the user's audio/subtitle/
            // quality picks once this stream's tracks are known.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!tracks.isEmpty) TrackUtils.applyPreferences(exo)
            }

            // Flaky hosts drop the connection mid-file; without this the
            // player just dies ("thodi der theek, fir lag, fir crash").
            // Re-prepare resumes from the current position — up to 3 times.
            // An error in the last ~20s (or after retries run out near the
            // end) means the file is effectively over — auto-next instead of
            // freezing on the last frame.
            var retries = 0
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val dur = exo.duration
                val nearEnd = dur > 0 && exo.currentPosition >= dur - 20_000
                if (nearEnd) {
                    currentOnEnded()
                    return
                }
                if (retries < 3) {
                    retries++
                    DebugLog.log("player", "error ${error.errorCodeName}, retry $retries/3")
                    exo.seekTo(exo.currentPosition)
                    exo.prepare()
                    exo.playWhenReady = true
                    return
                }
                // Out of retries and nothing ever played: the stream is not
                // going to start. Say so instead of sitting on a black screen
                // forever, which is what this branch used to do.
                DebugLog.log("player", "gave up: ${error.errorCodeName}")
                currentOnDead(error.errorCodeName)
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener); exo.release() }
    }
    // Some hosts accept the connection and then never send a frame: no error
    // is ever raised, so the retry path above never runs and the screen stays
    // black with working controls. Give every stream a fixed window to produce
    // a first frame, then treat silence as failure.
    LaunchedEffect(exo) {
        kotlinx.coroutines.delay(45_000)
        // playWhenReady guard: a viewer who paused while the first frame was
        // still loading must not be told the server is dead. Only a player that
        // is TRYING to play and has produced nothing is stuck.
        if (exo.playWhenReady && exo.currentPosition <= 0L && exo.playbackState != Player.STATE_ENDED) {
            DebugLog.log("player", "no frame in 45s (state=${exo.playbackState}) -> dead")
            currentOnDead("NO_DATA")
        }
    }
    return exo
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun NativePlayerView(exo: ExoPlayer) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                // Controls are Compose overlays; the PlayerView must never grab
                // d-pad focus or every overlay goes dead (the old menu bug).
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                keepScreenOn = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { it.player = exo },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedPlayer(url: String, onWebView: (WebView?) -> Unit = {}) {
    DisposableEffect(Unit) { onDispose { onWebView(null) } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                onWebView(this)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = Api.USER_AGENT
                // Ad JS opens popups/redirects that pile up until the TV box
                // OOMs ("30 sec baad app crash") — keep window.open dead and
                // block ad/tracker requests before they run.
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = object : android.webkit.WebViewClient() {
                    private val startHost = android.net.Uri.parse(url).host.orEmpty()

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        return if (StreamResolver.Blocked.isAd(u)) {
                            android.webkit.WebResourceResponse(
                                "text/plain", "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0)),
                            )
                        } else {
                            null
                        }
                    }

                    // Ad clicks try to yank the whole WebView to another site;
                    // keep top-level navigation pinned to the embed's host.
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean {
                        if (request?.isForMainFrame != true) return false
                        val host = request.url?.host.orEmpty()
                        return host != startHost
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        val v = view ?: return
                        fun poke() {
                            v.evaluateJavascript(StreamResolver.AutoPlay.JS, null)
                            StreamResolver.AutoPlay.tapCenter(v)
                        }
                        poke()
                        v.postDelayed({ poke() }, 2_500)
                        v.postDelayed({ poke() }, 6_000)
                    }
                }
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                loadUrl(url, mapOf("Referer" to Api.REFERER))
            }
        },
        onRelease = { wv ->
            // Compose keeps the AndroidView alive across recompositions; on
            // real teardown the WebView must die or its renderer leaks.
            wv.stopLoading()
            wv.destroy()
        },
    )
}
