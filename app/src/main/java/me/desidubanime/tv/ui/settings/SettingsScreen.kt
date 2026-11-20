package me.desidubanime.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.settings.AppSettings
import me.desidubanime.tv.data.update.ApkUpdater
import me.desidubanime.tv.data.update.UpdateApi
import me.desidubanime.tv.data.update.UpdateManager
import me.desidubanime.tv.data.update.UpdateState

/**
 * Settings — both source sites' base URLs, the self-update card and the
 * maintenance actions.
 *
 * Each site rotates its domain every few months; editing the URL here keeps
 * the sources alive without rebuilding or reinstalling the app. The update card
 * is the only other way a sideloaded TV build can learn about a newer one.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = ServiceLocator.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var ddaUrl by remember { mutableStateOf("") }
    var adUrl by remember { mutableStateOf("") }
    var twUrl by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var maintenance by remember { mutableStateOf<String?>(null) }

    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    val updateNote by UpdateManager.note.collectAsStateWithLifecycle()
    val version = UpdateManager.versionName

    LaunchedEffect(Unit) {
        url = settings.adhBase()
        ddaUrl = settings.ddaBase()
        adUrl = settings.adBase()
        twUrl = settings.twBase()
        // Keep a previous run's result visible when the screen is reopened.
        if (updateState is UpdateState.Idle) UpdateManager.note(null)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color(0xFFD0D0D0),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Color(0xFF8A8A8A),
        cursorColor = Color.White,
        focusedPlaceholderColor = Color(0xFFBDBDBD),
        unfocusedPlaceholderColor = Color(0xFF9E9E9E),
        focusedContainerColor = Color(0xFF101014),
        unfocusedContainerColor = Color(0xFF101014),
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(48.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))

        Text("AnimeDubHindi website URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "When the website domain changes (.me → .link → …), enter the new link here. The structure remains the same, only the address changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; saved = false },
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
            singleLine = true,
            colors = fieldColors,
            label = { Text("https://www.animedubhindi.link") },
        )

        Spacer(Modifier.height(16.dp))
        Text("DesiDubAnime website URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "If DDA's domain changes, put the new link here. It applies immediately after saving — no restart needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedTextField(
            value = ddaUrl,
            onValueChange = { ddaUrl = it; saved = false },
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
            singleLine = true,
            colors = fieldColors,
            label = { Text(me.desidubanime.tv.data.api.Api.DEFAULT_HOST) },
        )

        Spacer(Modifier.height(16.dp))
        Text("AnimeDrive website URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "If AnimeDrive (AD) domain changes, put the new link here. Feed will refresh after save.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedTextField(
            value = adUrl,
            onValueChange = { adUrl = it; saved = false },
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
            singleLine = true,
            colors = fieldColors,
            label = { Text(me.desidubanime.tv.data.settings.AppSettings.DEFAULT_AD_BASE) },
        )

        Spacer(Modifier.height(16.dp))
        Text("ToonWorld4All website URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "If ToonWorld4All (T4A) domain changes, put the new link here. Feed will refresh after save.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedTextField(
            value = twUrl,
            onValueChange = { twUrl = it; saved = false },
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
            singleLine = true,
            colors = fieldColors,
            label = { Text(me.desidubanime.tv.data.settings.AppSettings.DEFAULT_TW_BASE) },
        )

        Row {
            Button(onClick = {
                scope.launch {
                    settings.setAdhBaseUrl(url)
                    settings.setDdaBaseUrl(ddaUrl)
                    settings.setAdBaseUrl(adUrl)
                    settings.setTwBaseUrl(twUrl)
                    // DDA's Retrofit reads Api.HOST per request (dynamic host
                    // interceptor) — point it at the new domain right away so
                    // no restart is needed.
                    me.desidubanime.tv.data.api.Api.setHost(settings.ddaBase())
                    // Old-domain posts/cards must not linger: drop them so the
                    // next feed/detail load re-scrapes from the new sites.
                    ServiceLocator.repository.invalidateAllSourceCaches()
                    url = settings.adhBase()
                    ddaUrl = settings.ddaBase()
                    adUrl = settings.adBase()
                    twUrl = settings.twBase()
                    saved = true
                }
            }) { Text("Save") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = {
                scope.launch {
                    settings.setAdhBaseUrl("")
                    settings.setDdaBaseUrl("")
                    settings.setAdBaseUrl("")
                    settings.setTwBaseUrl("")
                    me.desidubanime.tv.data.api.Api.setHost(
                        me.desidubanime.tv.data.api.Api.DEFAULT_HOST,
                    )
                    ServiceLocator.repository.invalidateAllSourceCaches()
                    url = settings.adhBase()
                    ddaUrl = settings.ddaBase()
                    adUrl = settings.adBase()
                    twUrl = settings.twBase()
                    saved = true
                }
            }) { Text("Reset to default") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onBack) { Text("Back") }
        }
        if (saved) {
            Text(
                "Saved ✓ — all URLs applied instantly, no restart required.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        // ---- About & updates -------------------------------------------------
        // The TV has no Play Store, so this card is the only in-app way to
        // learn about a newer build. It reads the same UpdateManager the
        // launch notice uses.
        Text("About & updates", style = MaterialTheme.typography.titleMedium)
        Text(
            "Manime v$version  ·  updates from github.com/${UpdateApi.REPO}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { scope.launch { UpdateManager.checkNow() } },
                enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
            ) { Text("Check for updates") }

            when (val state = updateState) {
                is UpdateState.Available -> {
                    Button(onClick = { UpdateManager.download() }) {
                        val size = ApkUpdater.sizeLabel(state.release.apkSize)
                        Text(if (size.isBlank()) "Download & install" else "Install ${state.release.versionLabel} ($size)")
                    }
                }
                is UpdateState.Downloading -> {
                    Button(onClick = { UpdateManager.cancelDownload() }) { Text("Cancel") }
                }
                is UpdateState.Ready -> {
                    Button(onClick = { UpdateManager.installReady(ctx) }) { Text("Open installer") }
                }
                else -> Unit
            }
        }

        when (val state = updateState) {
            is UpdateState.Checking -> UpdateStatus("Checking GitHub…")
            is UpdateState.UpToDate -> UpdateStatus("Up to date ✓ (v${state.version})")
            is UpdateState.Available -> UpdateStatus(
                "Update available: ${state.release.versionLabel}" +
                    state.release.name.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
            )
            is UpdateState.Downloading -> UpdateStatus(
                if (state.total > 0) {
                    "Downloading… ${(state.read * 100 / state.total).toInt()}%  " +
                        "(${ApkUpdater.sizeLabel(state.read)} / ${ApkUpdater.sizeLabel(state.total)})"
                } else {
                    "Downloading… ${ApkUpdater.sizeLabel(state.read)}"
                },
            )
            is UpdateState.Ready -> UpdateStatus("Downloaded and verified ✓")
            is UpdateState.Failed -> UpdateStatus("Update failed: ${state.message}")
            UpdateState.Idle -> Unit
        }
        updateNote?.let { UpdateStatus(it) }

        Spacer(Modifier.height(32.dp))

        // ---- Maintenance -----------------------------------------------------
        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
        Text(
            "Clearing the image cache is safe — posters re-download as you browse. " +
                "Clearing the network cache forces the next refresh to re-scrape every source.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    maintenance = runCatching {
                        val loader = coil.Coil.imageLoader(ctx)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        "Image cache cleared ✓"
                    }.getOrElse { "Image cache: ${it.message}" }
                }
            }) { Text("Clear image cache") }

            Button(onClick = {
                scope.launch {
                    maintenance = runCatching {
                        me.desidubanime.tv.data.api.Network.client.cache?.evictAll()
                        me.desidubanime.tv.data.api.Network.feed.cache?.evictAll()
                        me.desidubanime.tv.data.cache.FeedCacheStore(ctx).clear()
                        UpdateApi.clearCache()
                        ServiceLocator.repository.invalidateAllSourceCaches()
                        "Network cache cleared ✓"
                    }.getOrElse { "Network cache: ${it.message}" }
                }
            }) { Text("Clear network cache") }

            Button(onClick = {
                scope.launch {
                    maintenance = runCatching {
                        ServiceLocator.watchHistory.clear()
                        "Watch history cleared ✓"
                    }.getOrElse { "History: ${it.message}" }
                }
            }) { Text("Clear watch history") }

            Button(onClick = {
                scope.launch {
                    maintenance = runCatching {
                        ServiceLocator.seenStore.reset()
                        "NEW badges reset ✓"
                    }.getOrElse { "Badges: ${it.message}" }
                }
            }) { Text("Reset NEW badges") }
        }
        maintenance?.let { UpdateStatus(it) }

        Spacer(Modifier.height(32.dp))

        DiagnosticLog()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(me.desidubanime.tv.R.string.made_by),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun UpdateStatus(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/**
 * Server-resolution log, on screen.
 *
 * A TV box has no adb, so "send me the logcat" is not something the user can
 * actually do. When a file host fails, the answer is here instead: whether the
 * Cloudflare challenge cleared, how many download buttons were found and which
 * one was clicked, what URL was captured, and how the probe judged it.
 */
@Composable
private fun DiagnosticLog() {
    val lines by me.desidubanime.tv.data.DebugLog.lines.collectAsStateWithLifecycle()

    Text("Server log (diagnostics)", style = MaterialTheme.typography.titleMedium)
    Text(
        "This log is the first place to look when a server fails to resolve.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Row(Modifier.padding(bottom = 12.dp)) {
        Button(onClick = { me.desidubanime.tv.data.DebugLog.clear() }) { Text("Clear log") }
    }
    if (lines.isEmpty()) {
        Text(
            "(empty — try playing a server in the player first)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Newest first: the interesting part of a failure is always the tail, and
    // this screen is already inside a scroll container.
    for (line in lines.asReversed().take(60)) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
