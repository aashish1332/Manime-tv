package me.desidubanime.tv.ui.update

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.desidubanime.tv.data.update.ApkUpdater
import me.desidubanime.tv.data.update.UpdateManager
import me.desidubanime.tv.data.update.UpdateState

/**
 * The launch-time "update available" notice.
 *
 * Rendered as an in-app overlay rather than a Dialog: on a TV, a Dialog takes
 * focus ownership away from the Compose TV focus tree and the remote's first
 * D-pad press after opening it goes nowhere — an overlay keeps one focus graph,
 * so focus lands on the button the moment it appears.
 *
 * The manager decides *whether* there is something to show (never during
 * playback, never twice for the same skipped tag); this just draws it.
 */
@Composable
fun UpdatePrompt() {
    val release by UpdateManager.offered.collectAsStateWithLifecycle()
    val state by UpdateManager.state.collectAsStateWithLifecycle()
    val note by UpdateManager.note.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val offer = release ?: return

    val focus = remember(offer.tag) { FocusRequester() }
    LaunchedEffect(offer.tag) { runCatching { focus.requestFocus() } }

    val downloading = state as? UpdateState.Downloading
    val ready = state as? UpdateState.Ready
    val failed = state as? UpdateState.Failed

    Box(
        Modifier
            .fillMaxSize()
            // Heavy scrim: the grid behind must read as background, not as
            // something still interactive.
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF15151C))
                .padding(30.dp),
        ) {
            Text(
                "Update available",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${offer.versionLabel}  ·  ${offer.name.ifBlank { "Manime" }}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                buildString {
                    append("Installed: v${UpdateManager.versionName}")
                    val size = ApkUpdater.sizeLabel(offer.apkSize)
                    if (size.isNotBlank()) append("   ·   $size")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (offer.notes.isNotBlank()) {
                Text(
                    offer.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (downloading != null) {
                val total = downloading.total
                val fraction = if (total > 0) {
                    (downloading.read.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    null
                }
                Text(
                    text = if (fraction != null) {
                        "Downloading… ${(fraction * 100).toInt()}%  " +
                            "(${ApkUpdater.sizeLabel(downloading.read)} / ${ApkUpdater.sizeLabel(total)})"
                    } else {
                        "Downloading… ${ApkUpdater.sizeLabel(downloading.read)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp),
                )
                if (fraction != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF2C2C36)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            (failed?.message ?: note)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        when {
                            downloading != null -> Unit
                            ready != null -> UpdateManager.installReady(context)
                            else -> UpdateManager.download()
                        }
                    },
                    enabled = downloading == null,
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text(
                        when {
                            downloading != null -> "Downloading…"
                            ready != null -> "Open installer"
                            else -> "Update now"
                        },
                        maxLines = 1,
                    )
                }
                Button(onClick = { UpdateManager.dismiss() }) {
                    Text(if (downloading != null) "Cancel" else "Later", maxLines = 1)
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { UpdateManager.openReleasePage(context) }) {
                    Text("Release notes", maxLines = 1)
                }
            }
        }
    }
}
