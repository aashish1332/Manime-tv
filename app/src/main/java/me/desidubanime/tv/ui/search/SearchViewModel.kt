package me.desidubanime.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.ui.common.UiState

class SearchViewModel : ViewModel() {
    private val repo = ServiceLocator.repository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<UiState<List<Anime>>>(UiState.Success(emptyList()))
    val state: StateFlow<UiState<List<Anime>>> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Every key press restarts a short debounce, so typing "demon slayer" on the
     * remote's key grid costs ONE search instead of fourteen. The on-screen
     * keyboard relies on this — it fires once per letter.
     */
    fun onQueryChange(q: String) {
        _query.value = q
        job?.cancel()
        if (q.isBlank()) {
            _state.value = UiState.Success(emptyList())
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(q)
        }
    }

    /** Explicit Search (button / IME action): skip the debounce wait. */
    fun searchNow() {
        val q = _query.value
        job?.cancel()
        if (q.isBlank()) {
            _state.value = UiState.Success(emptyList())
            return
        }
        job = viewModelScope.launch { runSearch(q) }
    }

    private suspend fun runSearch(q: String) {
        _state.value = UiState.Loading
        runCatching { repo.search(q) }
            // Ignore a result that arrived after the query moved on: with the
            // key grid a slower earlier search could otherwise overwrite the
            // newer one's results.
            .onSuccess { if (_query.value == q) _state.value = UiState.Success(it) }
            .onFailure {
                if (_query.value == q) {
                    _state.value = UiState.Error(it.message ?: "Search failed")
                }
            }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
