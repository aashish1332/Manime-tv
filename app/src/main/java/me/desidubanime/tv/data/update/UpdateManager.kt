package me.desidubanime.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.desidubanime.tv.App
import me.desidubanime.tv.BuildConfig
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.player.PlayerKeyBus
import java.io.File

/** What the update flow is doing right now — one source of truth for UI. */
sealed interface UpdateState {
    /** Nothing to say: no check has run, or the offered release was dismissed. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val version: String) : UpdateState

    /** A newer release exists; [release] carries the APK asset. */
    data class Available(val release: ReleaseInfo) : UpdateState

    /** [total] is 0 when the server sent no Content-Length. */
    data class Downloading(val read: Long, val total: Long) : UpdateState

    /** Downloaded and verified — the installer can be opened. */
    data class Ready(val release: ReleaseInfo, val apk: File) : UpdateState

    data class Failed(val message: String) : UpdateState
}

/**
 * The self-update flow, in one place.
 *
 * The launch notice and the Settings card are the same state machine, so a
 * download started from one is visible in the other and cannot run twice. A
 * previous version of this idea (a copy of the flow per screen) produced two
 * independent downloads and two installer prompts.
 *
 * Policy, in the order it matters on a TV box:
 *  - check at most every [AUTO_CHECK_INTERVAL_MS] on launch, and always on
 *    demand from Settings;
 *  - never offer an update while a video is playing (a dialog over a film is
 *    worse than a stale version) — the viewer can check in Settings instead;
 *  - "Later" is remembered per release tag, so a skipped version stops asking;
 *  - dismissing the notice cancels the download, so an installer can never
 *    appear out of nowhere minutes later.
 */
object UpdateManager {

    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val PREFS = "update_state.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * The release the launch notice is about. Kept separately from [state] so
     * the notice stays on screen while the state moves Available → Downloading
     * → Ready.
     */
    private val _offered = MutableStateFlow<ReleaseInfo?>(null)
    val offered: StateFlow<ReleaseInfo?> = _offered.asStateFlow()

    /** Transient line for the UI ("Installer opened", "Allow installs…"). */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    private val checkLock = Mutex()
    private var downloadJob: Job? = null
    private var downloading: ReleaseInfo? = null
    private var pendingInstall: File? = null

    /** Installed version, for the Settings row. */
    val versionName: String get() = BuildConfig.VERSION_NAME

    // ---- checking ----

    /**
     * Launch-time check. Silent by design: a failure leaves the state Idle
     * instead of putting a network error on the home screen.
     */
    suspend fun checkOnLaunch() {
        // The player owns the screen and the network; a notice now would cover
        // a film. Nothing is lost — Settings has "Check for updates".
        if (PlayerKeyBus.onMenu != null) return
        if (!checkLock.tryLock()) return
        try {
            val prefs = readPrefs()
            val last = prefs["lastCheckMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val skipped = prefs["skippedTag"]?.jsonPrimitive?.contentOrNull
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) {
                // Within the quiet window: still honour a release we already
                // know about from the cached body.
                offerFromCache(skipped)
                return
            }
            runCheck(skipped)
        } finally {
            checkLock.unlock()
        }
    }

    /** Settings → "Check for updates". Always hits the network, ignores "Later". */
    suspend fun checkNow() {
        if (!checkLock.tryLock()) return
        try {
            clearSkippedTag()
            runCheck(skipped = null)
        } finally {
            checkLock.unlock()
        }
    }

    private suspend fun runCheck(skipped: String?) {
        _state.value = UpdateState.Checking
        _note.value = null
        val release = try {
            UpdateApi.latest()
        } catch (e: UpdateApi.NoRelease) {
            markChecked()
            _state.value = UpdateState.UpToDate(versionName)
            return
        } catch (e: UpdateApi.RateLimited) {
            _state.value = UpdateState.Failed(e.message ?: "GitHub rate limit")
            return
        } catch (e: Throwable) {
            _state.value = UpdateState.Failed(e.message ?: "Check failed")
            return
        }
        markChecked()

        if (!UpdateApi.isNewer(release.tag, versionName)) {
            _state.value = UpdateState.UpToDate(versionName)
            return
        }
        if (release.apkUrl.isNullOrBlank()) {
            _state.value = UpdateState.Failed("${release.versionLabel} has no APK attached")
            return
        }
        if (skipped != null && skipped == release.tag) {
            _state.value = UpdateState.Idle
            return
        }
        _state.value = UpdateState.Available(release)
        _offered.value = release
    }

    /**
     * Off the cached release body only — no request. Used inside the quiet
     * window so a box that already knows about a release still offers it.
     */
    private suspend fun offerFromCache(skipped: String?) {
        val release = runCatching { UpdateApi.latest() }.getOrNull() ?: return
        if (!UpdateApi.isNewer(release.tag, versionName)) return
        if (release.apkUrl.isNullOrBlank()) return
        if (skipped != null && skipped == release.tag) return
        if (PlayerKeyBus.onMenu != null) return
        _state.value = UpdateState.Available(release)
        _offered.value = release
    }

    // ---- downloading ----

    fun download() {
        val release = (_offered.value ?: (_state.value as? UpdateState.Available)?.release) ?: return
        if (downloadJob?.isActive == true) return
        val url = release.apkUrl ?: return

        downloading = release
        downloadJob = ServiceLocator.appScope.launch {
            _state.value = UpdateState.Downloading(0, release.apkSize)
            _note.value = null
            try {
                val apk = ApkUpdater.download(
                    url = url,
                    fileName = release.apkName.ifBlank { "manime-${release.versionLabel}.apk" },
                    expectedSha256 = release.sha256,
                ) { read, total ->
                    _state.value = UpdateState.Downloading(read, total)
                }
                val problem = ApkUpdater.problem(App.instance, apk)
                if (problem != null) {
                    _state.value = UpdateState.Failed(problem)
                    return@launch
                }
                _state.value = UpdateState.Ready(release, apk)
                _note.value = startInstall(App.instance, apk)
            } catch (e: CancellationException) {
                // "Later" while downloading: go back to the offer, keep the
                // notice available if it was not dismissed.
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Throwable) {
                _state.value = UpdateState.Failed(e.message ?: "Download failed")
            } finally {
                downloading = null
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val release = downloading
        downloading = null
        _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    /** The viewer said not to bother with this release. */
    fun dismiss() {
        cancelDownload()
        _offered.value?.let { saveSkippedTag(it.tag) }
        _offered.value = null
        _note.value = null
    }

    // ---- installing ----

    /**
     * Opens the installer, or the per-app "install unknown apps" screen when
     * Android 8+ has not granted it yet. Returns the line to show the viewer.
     */
    private fun startInstall(context: Context, apk: File): String {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            pendingInstall = apk
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { return "Couldn't open Settings: ${it.message}" }
            return "Allow installs for Manime — the installer continues when you come back"
        }
        return runCatching {
            ApkUpdater.install(context, apk)
            "Installer opened — confirm to update"
        }.getOrElse { "Install failed: ${it.message}" }
    }

    /** Install again from a [UpdateState.Ready] download (Settings button). */
    fun installReady(context: Context): String? {
        val ready = _state.value as? UpdateState.Ready ?: return null
        return startInstall(context, ready.apk).also { _note.value = it }
    }

    /**
     * Called from Activity.onResume: returning from the unknown-sources screen
     * finishes an install that was blocked on the permission.
     */
    fun resumePendingInstall(context: Context): String? {
        val apk = pendingInstall ?: return null
        val allowed = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
        if (!allowed) return null
        pendingInstall = null
        return runCatching {
            ApkUpdater.install(context, apk)
            "Installer opened — confirm to update"
        }.getOrElse { "Install failed: ${it.message}" }.also { _note.value = it }
    }

    fun note(message: String?) {
        _note.value = message
    }

    fun openReleasePage(context: Context) {
        val url = _offered.value?.htmlUrl
            ?: "https://github.com/${UpdateApi.REPO}/releases/latest"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- persisted small state ----

    private val prefsFile: File get() = File(App.instance.filesDir, PREFS)

    private fun readPrefs(): JsonObject = runCatching {
        if (prefsFile.exists()) json.parseToJsonElement(prefsFile.readText()).jsonObject
        else JsonObject(emptyMap())
    }.getOrDefault(JsonObject(emptyMap()))

    private fun writePrefs(update: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
        runCatching {
            val map = readPrefs().toMutableMap()
            update(map)
            prefsFile.writeText(
                json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(map),
                ),
            )
        }
    }

    private fun markChecked() = writePrefs {
        it["lastCheckMs"] = kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis())
    }

    private fun saveSkippedTag(tag: String) = writePrefs {
        it["skippedTag"] = kotlinx.serialization.json.JsonPrimitive(tag)
    }

    private fun clearSkippedTag() = writePrefs { it.remove("skippedTag") }
}
