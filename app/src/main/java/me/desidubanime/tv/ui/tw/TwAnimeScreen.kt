package me.desidubanime.tv.ui.tw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.ui.common.AnimeCard
import me.desidubanime.tv.ui.common.ErrorScreen
import me.desidubanime.tv.ui.common.LoadingScreen
import me.desidubanime.tv.ui.common.UiState

/**
 * Standalone TW4A grid: every anime that exists on ToonWorld4All, newest
 * first. Cards open the normal detail route — negative TW ids flow through
 * the TW-only paths (pure TW episodes + TW servers).
 */
class TwAnimeViewModel : ViewModel() {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<UiState<List<Anime>>>(UiState.Loading)
    val state: kotlinx.coroutines.flow.StateFlow<UiState<List<Anime>>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { ServiceLocator.repository.twOnlyAnime() }
                .onSuccess { list ->
                    _state.value = if (list.isEmpty()) {
                        UiState.Error("TW4A list is empty — no catalog retrieved from the site.")
                    } else {
                        UiState.Success(list)
                    }
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "Failed to load TW4A") }
        }
    }
}

@Composable
fun TwAnimeScreen(
    onOpenAnime: (Anime) -> Unit,
    vm: TwAnimeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp)) {
        Text(
            "TW4A — ToonWorld4All",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        when (val s = state) {
            is UiState.Loading -> LoadingScreen()
            is UiState.Error -> ErrorScreen(s.message, onRetry = vm::refresh)
            is UiState.Success -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(s.data, key = { it.id }) { anime ->
                    AnimeCard(anime = anime, onClick = { onOpenAnime(anime) })
                }
            }
        }
    }
}
