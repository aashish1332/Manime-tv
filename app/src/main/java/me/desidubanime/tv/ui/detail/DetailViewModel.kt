package me.desidubanime.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.data.model.Episode
import me.desidubanime.tv.ui.common.UiState

data class DetailData(
    val anime: Anime,
    val episodes: List<Episode>,
    /** All seasons of this show (incl. this one), season-ascending. */
    val seasons: List<Anime> = emptyList(),
    /** Episodes with number above this get a NEW badge (null = no badges). */
    val newAboveEpisode: Int? = null,
    /** episode index -> watched fraction (0..1), from Continue Watching. */
    val progress: Map<Int, Float> = emptyMap(),
    /** Episode to offer "Resume" for, when one is part-watched. */
    val resumeIndex: Int? = null,
)

class DetailViewModel : ViewModel() {
    private val repo = ServiceLocator.repository
    private val seen = ServiceLocator.seenStore

    private val _state = MutableStateFlow<UiState<DetailData>>(UiState.Loading)
    val state: StateFlow<UiState<DetailData>> = _state.asStateFlow()

    private var loadedId = -1

    /** episode index -> exact position to resume, filled on load. */
    private var resumePositions: Map<Int, Long> = emptyMap()

    /** Where to resume [index], or 0 when it was never part-watched. */
    fun resumePositionFor(index: Int): Long = resumePositions[index] ?: 0L

    fun load(animeId: Int) {
        // Re-entering the same detail page is free — EXCEPT when the last load
        // came back with 0 episodes (transient miss on a fresh upload): then a
        // revisit must retry instead of showing the stuck empty state.
        val cur = _state.value
        if (loadedId == animeId && cur is UiState.Success && cur.data.episodes.isNotEmpty()) return
        loadedId = animeId
        _state.value = UiState.Loading
        viewModelScope.launch {
            runCatching {
                val anime = repo.anime(animeId)
                val eps = repo.episodes(animeId)
                // NEW badges on episodes above the highest one the user has
                // PLAYED (not merely seen listed) — they stay visible until
                // that episode is actually watched, so "which ones are new"
                // survives multiple visits and multi-episode drops. First-ever
                // open records a baseline instead of flooding a fresh install.
                val lastSeen = seen.lastSeen(animeId)
                val maxNow = eps.maxOfOrNull { it.numberInt } ?: 0
                if (lastSeen == null && maxNow > 0) seen.setLastSeen(animeId, maxNow)
                // Resume state: the Continue Watching entry for THIS anime tells
                // us which episode was part-watched and how far in. Only one
                // episode per anime is tracked, which is exactly what a resume
                // action needs. A failure here must never break the page, so
                // the whole lookup is best-effort.
                val latest = runCatching {
                    me.desidubanime.tv.data.ServiceLocator.watchHistory.entries
                        .first().firstOrNull { it.animeId == animeId }
                }.getOrNull()
                val partWatched = latest
                    ?.takeIf { it.progress in 0.02f..0.95f && it.episodeIndex in eps.indices }
                resumePositions = partWatched
                    ?.let { mapOf(it.episodeIndex to it.positionMs) }
                    .orEmpty()
                DetailData(
                    anime = anime,
                    episodes = eps,
                    newAboveEpisode = lastSeen?.takeIf { maxNow > it },
                    progress = latest
                        ?.takeIf { it.episodeIndex in eps.indices }
                        ?.let { mapOf(it.episodeIndex to it.progress) }
                        .orEmpty(),
                    resumeIndex = partWatched?.episodeIndex,
                )
            }.onSuccess { data ->
                _state.value = UiState.Success(data)
                // Season list arrives late (it needs cross-site searches) —
                // episodes render immediately, the switcher pops in after.
                launch {
                    runCatching { repo.seasons(data.anime) }.getOrNull()
                        ?.takeIf { it.size > 1 }
                        ?.let { seasons ->
                            (_state.value as? UiState.Success)?.let { cur2 ->
                                if (cur2.data.anime.id == data.anime.id) {
                                    _state.value = UiState.Success(cur2.data.copy(seasons = seasons))
                                }
                            }
                        }
                }
            }.onFailure { _state.value = UiState.Error(it.message ?: "Failed to load") }
        }
    }

    /** The user pressed play on an episode — its NEW badge (and everything
     *  below it) clears; badges above stay until those are played too. */
    fun markPlayed(animeId: Int, episodeNumber: Int) {
        val prev = seen.lastSeen(animeId) ?: 0
        if (episodeNumber > prev) seen.setLastSeen(animeId, episodeNumber)
        (_state.value as? UiState.Success)?.let { cur ->
            if (cur.data.anime.id == animeId) {
                val base = cur.data.newAboveEpisode
                if (base != null && episodeNumber > base) {
                    _state.value = UiState.Success(cur.data.copy(newAboveEpisode = episodeNumber))
                }
            }
        }
    }
}
