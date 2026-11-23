package me.desidubanime.tv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * User-tweakable settings. AnimeDubHindi rotates its domain every few months
 * (.me -> .link -> ...) while the site structure stays identical, so the base
 * URL is editable at runtime instead of being baked into the APK.
 * Same for ToonWorld4All.
 */
class AppSettings(private val context: Context) {

    private val adhKey = stringPreferencesKey("adh_base_url")
    private val ddaKey = stringPreferencesKey("dda_base_url")
    private val adKey = stringPreferencesKey("ad_base_url")
    private val twKey = stringPreferencesKey("tw_base_url")
    private val showPlayerLogsKey = booleanPreferencesKey("show_player_logs")
    private val maxFpsKey = stringPreferencesKey("player_max_fps")
    private val subScaleKey = stringPreferencesKey("player_sub_scale")
    private val resizeModeKey = stringPreferencesKey("player_resize_mode")

    val adhBaseUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[adhKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADH_BASE
    }

    suspend fun adhBase(): String = adhBaseUrl.first()

    suspend fun setAdhBaseUrl(raw: String) {
        context.settingsDataStore.edit { prefs ->
            val normalized = normalize(raw)
            if (normalized.isBlank()) prefs.remove(adhKey) else prefs[adhKey] = normalized
        }
    }

    val ddaBaseUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[ddaKey]?.takeIf { it.isNotBlank() } ?: me.desidubanime.tv.data.api.Api.DEFAULT_HOST
    }

    suspend fun ddaBase(): String = ddaBaseUrl.first()

    suspend fun setDdaBaseUrl(raw: String) {
        context.settingsDataStore.edit { prefs ->
            val normalized = normalize(raw)
            if (normalized.isBlank()) prefs.remove(ddaKey) else prefs[ddaKey] = normalized
        }
    }

    /** AnimeDrive.in — domain editable too, so a rotation never needs a rebuild. */
    val adBaseUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[adKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_AD_BASE
    }

    suspend fun adBase(): String = adBaseUrl.first()

    suspend fun setAdBaseUrl(raw: String) {
        context.settingsDataStore.edit { prefs ->
            val normalized = normalize(raw)
            if (normalized.isBlank()) prefs.remove(adKey) else prefs[adKey] = normalized
        }
    }

    /** ToonWorld4All — domain editable too. */
    val twBaseUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[twKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_TW_BASE
    }

    suspend fun twBase(): String = twBaseUrl.first()

    suspend fun setTwBaseUrl(raw: String) {
        context.settingsDataStore.edit { prefs ->
            val normalized = normalize(raw)
            if (normalized.isBlank()) prefs.remove(twKey) else prefs[twKey] = normalized
        }
    }

    /** Accepts "animedubhindi.xyz", "www.animedubhindi.xyz/", full https URLs … */
    private fun normalize(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    val showPlayerLogs: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[showPlayerLogsKey] ?: false
    }

    suspend fun setShowPlayerLogs(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[showPlayerLogsKey] = enabled
        }
    }

    suspend fun getShowPlayerLogs(): Boolean = showPlayerLogs.first()

    /**
     * Player picture-size mode, stored as the int value of an
     * `AspectRatioFrameLayout.RESIZE_MODE_*` constant.
     * Persisted because a box with a mis-set aspect is a permanent condition,
     * not a per-episode choice.
     */
    val resizeMode: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[resizeModeKey]?.toIntOrNull() ?: 0
    }

    suspend fun resizeMode(): Int = resizeMode.first()

    suspend fun setResizeMode(mode: Int) {
        context.settingsDataStore.edit { prefs -> prefs[resizeModeKey] = mode.toString() }
    }

    /** Subtitle text size multiplier (1.0 = Media3 default). */
    val subtitleScale: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        prefs[subScaleKey]?.toFloatOrNull() ?: 1f
    }

    suspend fun subtitleScale(): Float = subtitleScale.first()

    suspend fun setSubtitleScale(scale: Float) {
        context.settingsDataStore.edit { prefs -> prefs[subScaleKey] = scale.toString() }
    }

    /** Display refresh-rate hint: "auto" or a fixed fps (24/25/30/50/60). */
    val maxFps: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[maxFpsKey]?.takeIf { it.isNotBlank() } ?: "auto"
    }

    suspend fun maxFps(): String = maxFps.first()

    suspend fun setMaxFps(value: String) {
        context.settingsDataStore.edit { prefs -> prefs[maxFpsKey] = value }
    }

    companion object {
        const val DEFAULT_ADH_BASE = "https://www.animedubhindi.link"
        const val DEFAULT_AD_BASE = "https://animedrive.me"
        const val DEFAULT_TW_BASE = "https://toonworld4all.me"
    }
}
