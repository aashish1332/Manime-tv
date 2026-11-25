package me.desidubanime.tv.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.history.WatchEntry
import me.desidubanime.tv.data.model.Episode
import me.desidubanime.tv.data.model.Playable
import me.desidubanime.tv.data.model.Server
import me.desidubanime.tv.data.model.ServerGroup

data class PlayerUiState(
    val loading: Boolean = true,
    val message: String? = null,
    val episodeTitle: String = "",
    val options: List<PlayOption> = emptyList(),
    val selected: PlayOption? = null,
    val playable: Playable? = null,
    val index: Int = 0,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    /** Exact intro/outro windows read from the MKV's chapter markers. */
    val skipWindows: MkvChapters.SkipWindows? = null,
    /** Non-null -> QR unlock sheet visible for this /redirect/ token URL. */
    val twUnlockUrl: String? = null,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.repository
    private val history = ServiceLocator.watchHistory
    private val resolver = StreamResolver(app.applicationContext)

    /**
     * Browser escape hatch for Cloudflare-challenged file hosts (gdflix and
     * friends answer plain OkHttp with 403 forever). Adapts [FileHostBrowser]
     * to the resolver's Browser port so the scrape layer stays free of
     * Android/WebView types.
     */
    private val browser = object : me.desidubanime.tv.scrape.FileHostResolver.Browser {
        private val pages = FileHostBrowser(app.applicationContext)
        override suspend fun html(url: String, timeoutMs: Long): String? = pages.html(url, timeoutMs)
        override suspend fun directUrl(url: String, timeoutMs: Long): String? = pages.directUrl(url, timeoutMs)
        override fun cookiesFor(url: String): String? = pages.cookiesFor(url)
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** The active load/resolve job â€” cancelled whenever a new episode/server starts. */
    private var resolveJob: Job? = null

    /**
     * True once this episode's option list contained ToonWorld4All servers.
     * T4A gates its files behind 3 human-solved shortener ads (24h unlock),
     * so a failed TW attempt is not a dead link â€” the user just needs to
     * unlock via Home â–¸ 'TW4A ads QR'. Drives the guidance hint appended to
     * failure messages.
     */
    private var twAttempted = false

    /** TW option that offered the phone unlock (QR sheet), kept for the resume. */
    private var pendingTw: PlayOption? = null
    /** Episode-start wall clock â€” caps TOTAL resolve time so weak boxes never sit in minutes-long unlock loops (user-reported full-TV reboots). */
    private var episodeStartedAt = 0L

    fun dismissTwUnlock() {
        pendingTw = null
        _state.value = _state.value.copy(twUnlockUrl = null)
    }

    /** A phone-unlocked URL has arrived — resolve that same TW option again. */
    fun resumeTwUnlock(currentPosMs: Long) {
        val opt = pendingTw ?: return
        dismissTwUnlock()
        selectOption(opt, currentPosMs)
    }

    private val episodes: List<Episode> get() = PlaybackSession.episodes

    fun start(index: Int) = loadEpisode(index)

    fun next() {
        val i = _state.value.index + 1
        if (i in episodes.indices) loadEpisode(i)
    }

    fun previous() {
        val i = _state.value.index - 1
        if (i in episodes.indices) loadEpisode(i)
    }

    /** Called periodically by the player UI so Continue Watching stays current. */
    fun reportProgress(positionMs: Long, durationMs: Long) {
        val entry = entryFor(positionMs, durationMs) ?: return
        viewModelScope.launch { runCatching { history.record(entry) } }
    }

    /**
     * Last-chance write when the player screen goes away.
     *
     * Nav-popping the player clears this ViewModel and cancels
     * [viewModelScope], so a record() launched there can be dropped exactly
     * when the user closes the app mid-episode. This one writes on the
     * process-lifetime scope instead.
     */
    fun snapshotProgress(positionMs: Long, durationMs: Long) {
        val entry = entryFor(positionMs, durationMs) ?: return
        ServiceLocator.appScope.launch { runCatching { history.record(entry) } }
    }

    private fun entryFor(positionMs: Long, durationMs: Long): WatchEntry? {
        if (PlaybackSession.animeId == 0) return null
        val s = _state.value
        val ep = episodes.getOrNull(s.index) ?: return null
        // A finished episode must not be "resumed" at the closing credits:
        // seeking to duration-3s instantly ends again and re-opens the Up Next
        // card, which is a loop the viewer cannot escape except by backing out.
        // Finished means restart from the top next time.
        val resumeAt = if (durationMs > 0 && positionMs >= durationMs - 8_000) 0L else positionMs
        return WatchEntry(
            animeId = PlaybackSession.animeId,
            animeTitle = PlaybackSession.animeTitle,
            posterUrl = PlaybackSession.posterUrl,
            episodeIndex = s.index,
            episodeLabel = ep.number,
            positionMs = resumeAt,
            durationMs = durationMs,
            serverLabel = PlaybackSession.preferredServerLabel,
            audioLang = PlaybackSession.preferredAudioLang,
            subLang = PlaybackSession.preferredSubLang,
            videoHeight = PlaybackSession.preferredVideoHeight,
        )
    }

    /**
     * The stream resolved but ExoPlayer could not play it (dead signed link,
     * clearance expired between probe and playback). The picked server is
     * un-stuck from the sticky preference so the next episode doesn't start by
     * retrying the same broken one.
     */
    fun reportPlaybackFailure(errorCode: String) {
        val label = _state.value.selected?.label ?: "Server"
        if (PlaybackSession.preferredServerLabel == _state.value.selected?.stickyKey) {
            PlaybackSession.preferredServerLabel = null
        }
        _state.value = _state.value.copy(
            // Drop the stream: the ExoPlayer bound to it is dead but still
            // mounted, and its surface is the black rectangle the user is
            // looking at. Clearing it tears the player down so the message is
            // visible instead of a black screen with working controls.
            playable = null,
            loading = false,
            message = withTwHint(
                if (isTwSource(_state.value.selected?.sourceLabel)) {
                    "TW server failed — you need to unlock it first ($errorCode)."
                } else {
                    "$label failed ($errorCode) — try another server."
                },
            ),
        )
    }

    /** User picked a specific server from the menu. [fromPositionMs] = where
     *  playback was â€” the new server resumes there instead of restarting. */
    /**
     * Attaches a downloaded subtitle to the stream that is playing.
     *
     * Subtitles are baked into the media item when the player is built, so this
     * changes the stream and the player rebuilds around it. The caller parks the
     * current position in [PlaybackSession.resumePositionMs] first, so the
     * rebuild resumes where the viewer was instead of restarting the episode.
     */
    fun addSubtitle(track: me.desidubanime.tv.data.model.SubtitleTrack) {
        val current = _state.value.playable as? Playable.Stream ?: return
        if (current.subtitles.any { it.url == track.url }) return
        _state.value = _state.value.copy(
            playable = current.copy(subtitles = current.subtitles + track),
        )
        me.desidubanime.tv.data.DebugLog.log("player", "subtitle added: ${track.label}")
    }

    fun selectOption(option: PlayOption, fromPositionMs: Long = 0) {
        if (fromPositionMs > 0) PlaybackSession.resumePositionMs = fromPositionMs
        PlaybackSession.preferredServerLabel = option.stickyKey
        _state.value = _state.value.copy(
            selected = option,
            playable = null,
            loading = true,
            message = if (option.hoster.equals("GDFlix", ignoreCase = true)) {
                "GDFlix can take a little longer than other servers. Please wait..."
            } else {
                null
            },
        )
        me.desidubanime.tv.data.DebugLog.log("tw-unlock", "pick: ${option.label} isHub=${option.isHub} src=${option.sourceLabel}")
        // TW pick: the holder is empty (the site is ad-walled). The QR sheet is
        // NOT opened here — resolution is attempted first.
        if (option.isHub && isTwSource(option.sourceLabel)) {
            val held = TwUnlockHolder.take(PlaybackSession.animeId)
            // A phone-unlocked URL is a SHORTCUT, not a prerequisite. TW4A's
            // /redirect/ token carries the real hoster link inside its
            // __PROPS__, and plain HTTP reads that in one GET — so the QR
            // sheet must never open before resolution has even been tried.
            // The failure path further down still offers it when a link is
            // genuinely dead, which is the only case that ever needs it.
            if (held == null) {
                me.desidubanime.tv.data.DebugLog.log(
                    "tw-unlock",
                    "no phone URL yet — resolving ${option.hoster} over plain HTTP",
                )
            } else {
            me.desidubanime.tv.data.DebugLog.log("tw-unlock", "using phone URL -> $held")
            _state.value = _state.value.copy(
                playable = Playable.Stream(
                    url = held,
                    headers = mapOf(
                        "User-Agent" to me.desidubanime.tv.data.api.Api.USER_AGENT,
                        // hubcloud.cx stopped resolving (verified 2026-11); the
                        // live family is hubcloud.mov. Derive the referer from
                        // the actual file URL so a future rotation keeps working.
                        "Referer" to (
                            (Regex("""^(https?://[^/]+)""").find(held)?.groupValues?.get(1)
                                ?: "https://hubcloud.mov") + "/"
                            ),
                    ),
                    isHls = false,
                ),
                loading = false,
            )
            fetchSkipWindows(_state.value.playable!!)
            return
            }
        }
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            // A PICKED SERVER THAT HAS NO WORKING FILE MUST NOT DEAD-END.
            // Resolution stopped on the first failure ("… returned no stream —
            // pick another server") even with ten healthy rows sitting in the
            // menu, and a T4A row that failed threw the ad/QR wall on top of
            // it. Now a failure FALLS FORWARD through the preference order, so
            // picking GDFlix on an episode whose Drive copy was deleted simply
            // plays the next server instead of showing an error.
            val tried = LinkedHashSet<PlayOption>()
            var current = option
            while (true) {
                tried += current
                val resolved = try {
                    // The pick the viewer made gets the full budget; a fallback
                    // server has to be quicker than that or the pick feels
                    // stuck, so later attempts run on a tighter leash.
                    resolveOption(
                        current,
                        timeoutMs = if (tried.size == 1) MANUAL_RESOLVE_MS else FALLBACK_RESOLVE_MS,
                        allowBrowser = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                } ?: if (current.isHub) null else Playable.Embed(current.embedUrl)
                if (resolved != null) {
                    PlaybackSession.preferredServerLabel = current.stickyKey
                    _state.value = _state.value.copy(
                        selected = current,
                        playable = resolved,
                        loading = false,
                        message = null,
                    )
                    fetchSkipWindows(resolved)
                    return@launch
                }
                val next = _state.value.options.firstOrNull { it !in tried }
                if (next == null || tried.size >= MAX_PICK_FALLBACK) {
                    // Everything failed. Only NOW — as a last resort, with no
                    // other server left to try — offer the phone unlock for a
                    // T4A /redirect/ row. A dead file never opens it.
                    if (option.isHub &&
                        isTwSource(option.sourceLabel) &&
                        option.embedUrl.contains("/redirect/", ignoreCase = true)
                    ) {
                        pendingTw = option
                        _state.value = _state.value.copy(
                            loading = false,
                            playable = null,
                            twUnlockUrl = option.embedUrl,
                        )
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        playable = null,
                        message = withTwHint(
                            "${current.hoster} (${current.sourceLabel ?: "link"}) has no working file — pick another server.",
                        ),
                    )
                    return@launch
                }
                PlaybackSession.preferredServerLabel = next.stickyKey
                _state.value = _state.value.copy(
                    selected = next,
                    message = "${current.hoster} had no working file — trying ${next.hoster}…",
                )
                current = next
            }
        }
    }

    /**
     * File-host options resolve over plain HTTP; everything else goes through
     * the WebView sniffer.
     *
     * [timeoutMs] applies to BOTH paths. It used to be passed only to the
     * WebView resolver, so a file host whose probe crawled (dead mirrors, each
     * waiting out its own socket timeout) hung the whole batch â€” the player
     * just sat on "loading" forever with no way out.
     *
     * [allowBrowser] lets the resolver clear a Cloudflare challenge in an
     * offscreen WebView when plain HTTP is refused (gdflix.dev: 403,
     * cf-mitigated: challenge). That costs several seconds, so it is off while
     * auto-resolve races many servers and on once the user picks one â€” plus
     * the last-ditch stream sniffer, which also needs a real browser.
     *
     * A sniffer Embed is always discarded for file hosts: loading the host's
     * own website in the player is exactly what we're avoiding, and it breaks
     * skip-intro / quality / audio / subtitles.
     */
    private suspend fun resolveOption(
        option: PlayOption,
        timeoutMs: Long = 12_000L,
        allowBrowser: Boolean = false,
    ): Playable? {
        if (!option.isHub) return resolver.resolve(option.embedUrl, timeoutMs)
        val direct = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            me.desidubanime.tv.scrape.FileHostResolver.resolve(
                pageUrl = option.embedUrl,
                hoster = option.hoster,
                budgetMs = timeoutMs,
                browser = if (allowBrowser) browser else null,
            )
        }
        if (direct != null) return direct
        // Hub options (file hosts) CANNOT fall back to the WebView embed game.
        // A hub link like hubcloud.cx/drive/xxx or gdflix.dev/file/xxx that
        // failed to resolve is a dead link â€” the WebView sniffer would load
        // the host's own webpage and call that a "stream" (black screen).
        // Return null so the caller moves to the next server instead.
        if (!allowBrowser) return null
        return null
    }

    /**
     * Exact skip windows from the MKV's chapter markers (Intro/OP/ED) â€” two
     * small ranged GETs in the background; state updates when they arrive.
     */
    private fun fetchSkipWindows(playable: Playable) {
        val stream = playable as? Playable.Stream ?: return
        val forEpisode = _state.value.index
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val windows = MkvChapters.fetch(stream.url, stream.headers)
            if (windows != null && _state.value.index == forEpisode) {
                _state.value = _state.value.copy(skipWindows = windows)
            }
        }
    }

    private fun loadEpisode(index: Int) {
        val ep = episodes.getOrNull(index) ?: return
        // Stale one-shot resume (set by a server switch) must not leak into a
        // different episode. Continue Watching sets it AFTER navigation, and
        // start() runs loadEpisode before the player exists â€” safe to clear
        // only when switching between episodes in-session.
        if (_state.value.options.isNotEmpty()) PlaybackSession.resumePositionMs = 0
        _state.value = PlayerUiState(
            loading = true,
            episodeTitle = "${PlaybackSession.animeTitle} Â· ${ep.number}",
            index = index,
            hasNext = index + 1 in episodes.indices,
            hasPrev = index - 1 in episodes.indices,
        )
        episodeStartedAt = System.currentTimeMillis()
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            val options = try {
                buildOptions(ep)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, message = "Failed to load servers: ${t.message}")
                return@launch
            }
            if (options.isEmpty()) {
                _state.value = _state.value.copy(loading = false, message = "No servers found for this episode.")
                return@launch
            }
            twAttempted = options.any { it.isHub && isTwSource(it.sourceLabel) }
            // Auto-pick the fastest / most reliable server.
            val ordered = orderByPreference(options)
            _state.value = _state.value.copy(options = visibleOptions(options))
            autoResolve(ordered)
            // A server the resolver gave up on during this attempt should not
            // linger in the menu afterwards either.
            _state.value = _state.value.copy(options = visibleOptions(_state.value.options))
        }
    }

    /** Append the TW4A unlock hint when this episode had TW servers in play. */
    private fun withTwHint(base: String): String =
        if (twAttempted) {
            // ToonWorld4All resolves through its /redirect/ token over plain
            // HTTP, so no unlock step is required. Only a genuinely dead row
            // offers the QR sheet, and the menu should never send the viewer to
            // watch ads before trying another server.
            "$base\nToonWorld4All: try a HubCloud or GDFlix row from the server list."
        } else base

    /**
     * Fetch DDA servers (expanding CLOUD/mirror aggregators in parallel) and
     * append HubCloud quality options from AnimeDubHindi. Either site may be
     * missing an episode â€” whichever has it provides the servers.
     */
    private suspend fun buildOptions(ep: Episode): List<PlayOption> = coroutineScope {
        // Every file-host link (ADH's and AnimeDrive's) resolves over plain
        // HTTP via FileHostResolver â€” HubCloud through its dedicated two-hop
        // path, GDFlix/FileBee/FilePress through the generic probe. All of them
        // are isHub=true, i.e. they must yield a DIRECT stream for Media3.
        //
        // GDFlix & co. used to be isHub=false, which routed them to the WebView
        // sniffer and, on timeout, played the host's own web page inside the
        // player â€” no skip-intro, no quality/audio/subtitle menus, no resume.
        // Now a host that can't be resolved falls through to the next server
        // instead of loading its website.
        val hubOptions = ep.hubLinks.map { link ->
            PlayOption(
                label = "${link.source.label} - ${link.hoster} - ${link.quality}",
                group = ServerGroup.DUB,
                embedUrl = link.driveUrl,
                native = true,
                isHub = true,
                hoster = link.hoster,
                sourceLabel = link.source.label,
            )
        }
        if (ep.watchUrl.isBlank()) {
            // ADH/AD/TW-only episode: no DDA servers exist, but the hub
            // options MUST still go through the priority sort below â€”
            // returning them raw put TW after AD in the menu (the raw list is
            // merged in ADHâ†’ADâ†’TW order, not source-priority order).
            return@coroutineScope hubOptions.sortedWith(OPTION_ORDER)
        }

        val servers: List<Server> = repo.servers(ep.watchUrl)
        val dda = servers.map { s ->
            async {
                when {
                    s.isCloud -> {
                        val subs = try {
                            repo.cloudSubServers(s.embedUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            emptyList()
                        }
                        if (subs.isEmpty()) {
                            listOf(PlayOption(s.name, s.group, s.embedUrl, native = true))
                        } else {
                            subs.map { sub -> PlayOption("${s.name} - ${sub.name}", s.group, sub.playUrl, native = true) }
                        }
                    }
                    s.isMirror -> {
                        val subs = try {
                            repo.mirrorSubServers(s.embedUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            emptyList()
                        }
                        if (subs.isEmpty()) {
                            listOf(PlayOption(s.name, s.group, s.embedUrl, native = false))
                        } else {
                            subs.map { sub -> PlayOption("${s.name} - ${sub.name}", s.group, sub.playUrl, native = false) }
                        }
                    }
                    else -> listOf(PlayOption(s.name, s.group, s.embedUrl, native = false))
                }
            }
        }.awaitAll().flatten()
        // Hub options first in the visible server list â€” SAME priority as the
        // auto-resolver: ADH -> TW -> AD -> DDA.
        (hubOptions + dda).sortedWith(OPTION_ORDER)
    }

    /** Shared comparator: source tier, then HubCloud-first, native-first, SUB-first. */
    private val OPTION_ORDER =
        compareBy<PlayOption> { it.sourceTier() }
            .thenByDescending { it.isHub && it.hoster.equals("HubCloud", true) }
            .thenByDescending { it.native }
            .thenBy { it.group != ServerGroup.SUB }

    /**
     * Priority order for auto-resolve AND the visible order of the server
     * menus â€” the user's ranking:
     *
     *   1. ADH hub links      (single adhlinks page, plain-HTTP resolvers,
     *                          most reliable + fastest to resolve)
     *   2. ToonWorld4All hubs (__PROPS__ unwraps to HubCloud/GDFlix directly;
     *                          one extra GET per attempt)
     *   3. AnimeDrive hubs    (dl-token unwrap is cheap too, but the site's
     *                          links proved flakier in practice)
     *   4. DDA's own servers  (CLOUD/mirror embeds -> WebView sniffer; slowest
     *                          path and the least control, hence last)
     *
     * Within the same source tier, HubCloud leads (dedicated two-hop resolver)
     * and higher resolutions outrank lower ones via the stable input order.
     * DDA keeps native-before-embed and SUB-first inside its tier. The sticky
     * server still jumps the queue on top of all of this.
     */
    private fun orderByPreference(options: List<PlayOption>): List<PlayOption> {
        val ranked = options.sortedWith(OPTION_ORDER)
        // Sticky server: whatever played the previous episode goes first, so the
        // next episode usually resolves on the very first attempt. Matched on
        // stickyKey (label minus per-episode file size) so "HubCloud Â· [720P]
        // Â· 183 MB" still matches next episode's "Â· 190 MB" variant.
        val sticky = PlaybackSession.preferredServerLabel ?: return ranked
        val hit = ranked.firstOrNull { it.stickyKey == sticky } ?: return ranked
        return listOf(hit) + ranked.filter { it !== hit }
    }

    /**
     * Servers the viewer should still see in the menu.
     *
     * A host the resolver has proven unreachable (FilePress today: fpgo.xyz
     * 301s to newN.filepress.lat, which blackholes every connection) is a row
     * that can only ever fail, so it is hidden instead of offered. The verdict
     * is the same 5-minute memo the batch race uses, so nothing extra is
     * probed here. An all-down menu falls back to the full list rather than
     * leaving the sheet empty.
     */
    private fun visibleOptions(options: List<PlayOption>): List<PlayOption> {
        val visible = options.filterNot {
            it.isHub && me.desidubanime.tv.scrape.FileHostResolver.isDown(it.hoster)
        }
        if (visible.size != options.size) {
            me.desidubanime.tv.data.DebugLog.log(
                "resolve",
                "menu hides ${options.size - visible.size} down server(s)",
            )
        }
        return visible.ifEmpty { options }
    }

    /** TW source check — label may be "TW" or "ToonWorld" depending on LinkSource enum. */
    private fun isTwSource(label: String?) =
        label.equals("TW", ignoreCase = true) || label.equals("ToonWorld", ignoreCase = true)

    private fun isGatedHoster(hoster: String): Boolean {
        val h = hoster.lowercase()
        return h.contains("gdflix") || h.contains("filepress") || h.contains("filebee")
    }

    /** Source rank: 0=ADH, 1=ToonWorld, 2=AnimeDrive/unknown hubs, 3=DDA. */
    private fun PlayOption.sourceTier(): Int = when {
        !isHub -> 3
        sourceLabel.equals("ADH", ignoreCase = true) -> 0
        isTwSource(sourceLabel) || sourceLabel.equals("T4A", ignoreCase = true) -> 1
        sourceLabel.equals("AD", ignoreCase = true) || sourceLabel.equals("AnimeDrive", ignoreCase = true) -> 2
        else -> 2
    }

    /**
     * Race candidates in small parallel batches; keep the first one that yields
     * a direct stream. Sequential resolving took 8-12s *per* server (40s+ starts),
     * so we run [PARALLEL_RESOLVES] offscreen WebViews at once instead.
     * If none yield a stream, use the best candidate as a WebView embed so
     * playback still works.
     */
    private suspend fun autoResolve(ordered: List<PlayOption>) {
        val embeds = HashMap<PlayOption, Playable>()
        var tried = 0
        // EVERY hub gets the fast plain-HTTP pass — including the hosts that
        // USED to be reserved for the browser phase. GDFlix answers plain HTTP
        // with no challenge now (verified live: 302 -> newN.gdflix.io, 200,
        // then one POST to /mfile/<id> mints the file), so keeping it out of
        // this pass only delayed the server the viewer most likely wanted by a
        // whole browser-unlock round. `gated` below is the SECOND chance for
        // hosts whose plain path failed, not their first attempt.
        val nonGated = ordered
        val gated = ordered.filter { it.isHub && isGatedHoster(it.hoster) }
        // Keep the menu honest before the first batch even starts: a host that
        // is already marked down would only add a row that cannot play.
        if (ordered.any { it.isHub && me.desidubanime.tv.scrape.FileHostResolver.isDown(it.hoster) }) {
            _state.value = _state.value.copy(options = visibleOptions(_state.value.options))
        }
        // Sticky: if it's gated, we'll handle it separately; otherwise include in nonGated.
        val sticky = PlaybackSession.preferredServerLabel
        // First try non-gated with the usual batch race (plain HTTP, no browser)
        if (nonGated.isNotEmpty()) {
            val orderedNonGated = if (sticky != null && nonGated.firstOrNull()?.stickyKey == sticky) {
                listOf(nonGated.first()) + nonGated.drop(1)
            } else {
                nonGated
            }
            val batches = orderedNonGated.chunked(PARALLEL_RESOLVES)
            for (batch in batches) {
                _state.value = _state.value.copy(
                    message = "Searching stream... (${tried + 1}-${tried + batch.size} of ${ordered.size} servers)",
                )
                val winner = raceForStream(batch, embeds)
                tried += batch.size
                if (winner != null) {
                    PlaybackSession.preferredServerLabel = winner.first.stickyKey
                    _state.value = _state.value.copy(
                        selected = winner.first,
                        playable = winner.second,
                        loading = false,
                        message = null,
                    )
                    fetchSkipWindows(winner.second)
                    return
                }
            }
        }
        // Non-gated failed. Now try gated hosts with browser unlock.
        // Dedup by hoster to avoid repeated Cloudflare challenges.
        // ...but never spend an unlock round on a host that is already known
        // unreachable: the browser step is the most expensive thing here.
        val distinctGated = gated
            .filterNot { it.isHub && me.desidubanime.tv.scrape.FileHostResolver.isDown(it.hoster) }
            .distinctBy { it.hoster.lowercase() }
            .take(BROWSER_RETRIES)
        if (distinctGated.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - episodeStartedAt
            if (elapsed <= EPISODE_RESOLVE_CAP_MS / 2) {
                for ((i, opt) in distinctGated.withIndex()) {
                    _state.value = _state.value.copy(
                        message = "Unlocking ${opt.hoster}... (${i + 1}/${distinctGated.size})",
                    )
                    val p = try {
                        resolveOption(opt, timeoutMs = BROWSER_RESOLVE_MS, allowBrowser = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        null
                    }
            if (p is Playable.Stream) {
                me.desidubanime.tv.data.DebugLog.log("resolve", "Browser unlock succeeded for ${opt.hoster}: ${p.url}")
                PlaybackSession.preferredServerLabel = opt.stickyKey
                _state.value = _state.value.copy(
                    selected = opt,
                    playable = p,
                    loading = false,
                    message = null,
                )
                fetchSkipWindows(p)
                return
            } else {
                me.desidubanime.tv.data.DebugLog.log("resolve", "Browser unlock failed for ${opt.hoster} (${p?.javaClass?.simpleName})")
            }
                }
            }
        }
        // No native stream anywhere -> play the best embed (preference order).
        val fb = ordered.firstOrNull { it in embeds }
        if (fb != null) {
            PlaybackSession.preferredServerLabel = fb.stickyKey
            _state.value = _state.value.copy(selected = fb, playable = embeds.getValue(fb), loading = false, message = null)
        } else {
            _state.value = _state.value.copy(loading = false, message = withTwHint("Could not start any server."))
        }
    }

    /**
     * Resolve a batch concurrently, then pick the FIRST-BY-PRIORITY success â€”
     * NOT the first to FINISH. Completion-order picking broke the user rule
     * (the highest-priority working server plays, not the fastest to answer): a lower-
     * ranked server resolving faster than a higher-ranked one would win the
     * race. Now every member settles, embeds are stashed, and the earliest
     * index holding a direct stream wins. Wall-clock cost is the slowest
     * member instead of the fastest, bounded by the resolve timeout â€” probes
     * reject dead hosts in ~1-2s so batches usually settle fast anyway.
     */
    private suspend fun raceForStream(
        batch: List<PlayOption>,
        embeds: MutableMap<PlayOption, Playable>,
    ): Pair<PlayOption, Playable>? = coroutineScope {
        // A DOWN HOST MUST NOT HOLD THE BATCH. This awaits EVERY member, so a
        // FilePress row whose origin is gone used to define the batch's wall
        // clock: two healthy servers came back in ~1s and the viewer still
        // waited the dead one's probe. Drop those rows before any request is
        // made; FileHostResolver already remembers the verdict for 5 minutes.
        val (down, live) = batch.partition {
            it.isHub && me.desidubanime.tv.scrape.FileHostResolver.isDown(it.hoster)
        }
        if (down.isNotEmpty()) {
            me.desidubanime.tv.data.DebugLog.log(
                "resolve",
                "batch skips down host(s): " +
                    down.map { it.hoster }.distinct().joinToString(),
            )
        }
        if (live.isEmpty()) return@coroutineScope null
        val settled = live.map { opt ->
            async {
                val p = try {
                    resolveOption(opt, timeoutMs = 30_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
                opt to p
            }
        }.awaitAll()

        var winner: Pair<PlayOption, Playable>? = null
        for ((opt, p) in settled) {
            if (p == null) continue
            if (winner == null && p is Playable.Stream) {
                // Keep scanning: later embeds must still be recorded below.
                winner = opt to p
            } else if (p !is Playable.Stream) {
                embeds[opt] = p
            }
        }
        winner
    }

    private companion object {
        /**
         * Offscreen WebViews are heavy even with images/ads blocked â€” 4 at
         * once starved the video decoder on low-RAM TV boxes (playback lag
         * the moment servers were being probed). 2 keeps startup quick
         * without competing with the player.
         */
        const val PARALLEL_RESOLVES = 2

        /**
         * Budget for a server the USER picked â€” no other candidate is racing
         * it, and a Cloudflare-gated host needs the challenge to clear, the
         * page to render, and a click round before it gives up the file.
         */
        const val MANUAL_RESOLVE_MS = 45_000L

        /**
         * Distinct file HOSTS given a browser-unlock attempt after the fast
         * pass. 3 hosts x 30s caps the whole unlock phase at ~90s worst-case
         * — this replaced the previous five 40s attempts.
         */
        const val BROWSER_RETRIES = 3

        /** Per browser-unlock attempt: challenge + page + click + probe. */
        const val BROWSER_RESOLVE_MS = 30_000L

        /** Total wall-clock the whole resolve may take before unlock phases are skipped. */
        const val EPISODE_RESOLVE_CAP_MS = 100_000L

        /**
         * Servers a single pick may fall through before it gives up. Two is
         * enough to survive one dead host without turning a pick into a full
         * auto-resolve pass.
         */
        const val MAX_PICK_FALLBACK = 3

        /** Budget for the 2nd/3rd server a failed pick falls through to. */
        const val FALLBACK_RESOLVE_MS = 22_000L
    }
}



