package me.desidubanime.tv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.desidubanime.tv.data.model.Anime
import me.desidubanime.tv.ui.common.AnimeCard
import me.desidubanime.tv.ui.common.Spinner
import me.desidubanime.tv.ui.common.UiState
import me.desidubanime.tv.ui.theme.Primary

/**
 * Search across every source at once.
 *
 * The input is two things on purpose:
 *  - the text field, for boxes with an IME or a USB keyboard;
 *  - an on-screen key grid, because an Android TV remote has no keyboard and
 *    many TV boxes ship no usable IME at all — without this, search could not
 *    be used with just the remote.
 *
 * A key press updates the query through the ViewModel's 400ms debounce, so
 * holding down a key does not fire a request per letter.
 */
@Composable
fun SearchScreen(
    onOpenAnime: (Anime) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    // Landing focus is the keyboard, not the field: on a TV the field opens an
    // IME overlay (when one exists) and the user cannot see the results.
    // UP still reaches the field for anyone who prefers it.
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 28.dp, end = 36.dp, bottom = 24.dp),
    ) {
        Column(Modifier.width(384.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(22.dp)
                        .height(3.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(me.desidubanime.tv.ui.theme.AccentGradient),
                )
                Text(
                    "FIND SOMETHING",
                    style = MaterialTheme.typography.labelSmall,
                    color = me.desidubanime.tv.ui.theme.Muted,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Search",
                style = MaterialTheme.typography.headlineMedium,
                color = me.desidubanime.tv.ui.theme.OnBg,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                singleLine = true,
                colors = searchFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                label = { Text("Anime name") },
            )
            Text(
                "Use the remote's arrows on the keys below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            Keyboard(
                onKey = { ch -> vm.onQueryChange(query + ch) },
                onDelete = { vm.onQueryChange(query.dropLast(1)) },
                onClear = { vm.onQueryChange("") },
                onSearch = { vm.searchNow() },
                firstKey = firstKey,
            )
        }

        Spacer(Modifier.width(32.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            when (val s = state) {
                is UiState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Spinner(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Searching \"$query\"…")
                }

                is UiState.Error -> Column(Modifier.padding(top = 8.dp)) {
                    Text(s.message ?: "Search failed")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.searchNow() }) { Text("Try again") }
                }

                is UiState.Success -> when {
                    // No search yet: instructions, not an empty-result message.
                    // "No results" before a search was asked for reads like a
                    // failure the user caused.
                    query.isBlank() -> Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            "Search every source at once",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "AnimeDubHindi, AnimeDrive and ToonWorld4All are searched " +
                                "together, so a title missing on one site still shows up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    s.data.isEmpty() -> Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            "No results for \"$query\"",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Try fewer words, or just one — titles are matched on any " +
                                "word order and small typos are tolerated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        )
                        Button(onClick = { vm.searchNow() }) { Text("Search again") }
                    }

                    else -> {
                        Text(
                            if (s.data.size == 1) "1 result" else "${s.data.size} results",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        // Adaptive, so a 720p TV shows fewer, bigger posters and
                        // a 1080p set fits more — instead of a fixed six columns
                        // that overflows the smaller UI resolution.
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 132.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(s.data, key = { it.id }, contentType = { "anime-card" }) { anime ->
                                AnimeCard(anime = anime, onClick = { onOpenAnime(anime) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Letters in reading order; the grid navigates spatially from here. */
private val KEY_ROWS = listOf("ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ")

@Composable
private fun Keyboard(
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    firstKey: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KEY_ROWS.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, ch ->
                    KeyCap(
                        label = ch.toString(),
                        modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                    ) { onKey(ch.lowercaseChar()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("SPACE", width = 164.dp) { onKey(' ') }
            KeyCap("DEL", width = 96.dp, onClick = onDelete)
            KeyCap("CLR", width = 96.dp, onClick = onClear)
        }
        Button(onClick = onSearch, modifier = Modifier.padding(top = 6.dp)) {
            Text("Search", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 42.dp,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width).height(40.dp),
        border = CardDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A3A42))),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Primary)),
        ),
        // Small lift only: 35 keys scaling up 15% collide with their neighbours
        // on a 720p UI.
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = Color(0xFFD0D0D0),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color(0xFF8A8A8A),
    cursorColor = Color.White,
    focusedContainerColor = Color(0xFF101014),
    unfocusedContainerColor = Color(0xFF101014),
)
