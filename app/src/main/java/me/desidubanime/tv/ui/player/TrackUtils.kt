package me.desidubanime.tv.ui.player

import android.annotation.SuppressLint
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import me.desidubanime.tv.player.PlaybackSession as Session

/** A selectable audio / subtitle / quality entry read from ExoPlayer's current tracks. */
data class TrackChoice(
    val label: String,
    val trackType: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val selected: Boolean,
)

@SuppressLint("UnsafeOptInUsageError")
object TrackUtils {

    fun choices(player: ExoPlayer, trackType: Int): List<TrackChoice> {
        val out = mutableListOf<TrackChoice>()
        val groups = player.currentTracks.groups
        groups.forEachIndexed { gi, group ->
            if (group.type != trackType || !group.isSupported) return@forEachIndexed
            for (ti in 0 until group.length) {
                val format = group.getTrackFormat(ti)
                out += TrackChoice(
                    label = labelFor(trackType, format, ti),
                    trackType = trackType,
                    groupIndex = gi,
                    trackIndex = ti,
                    selected = group.isTrackSelected(ti),
                )
            }
        }
        return out
    }

    fun apply(player: ExoPlayer, choice: TrackChoice) {
        val group = player.currentTracks.groups[choice.groupIndex]
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(choice.trackType, false)
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.trackIndex)),
            )
        // A manual quality pick replaces the "highest bitrate" startup default.
        if (choice.trackType == C.TRACK_TYPE_VIDEO) builder.setForceHighestSupportedBitrate(false)
        player.trackSelectionParameters = builder.build()
        remember(choice.trackType, group.getTrackFormat(choice.trackIndex))
    }

    /** Turn subtitles off entirely. */
    fun disable(player: ExoPlayer, trackType: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
        if (trackType == C.TRACK_TYPE_TEXT) Session.preferredSubLang = ""
    }

    fun autoQuality(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setForceHighestSupportedBitrate(false)
            .build()
        Session.preferredVideoHeight = null
    }

    /** Remember a manual pick so the NEXT episode's player starts the same way. */
    private fun remember(trackType: Int, f: Format) {
        when (trackType) {
            C.TRACK_TYPE_AUDIO -> Session.preferredAudioLang = keyOf(f)
            C.TRACK_TYPE_TEXT -> Session.preferredSubLang = keyOf(f)
            C.TRACK_TYPE_VIDEO -> Session.preferredVideoHeight = f.height.takeIf { it > 0 }
        }
    }

    /**
     * Re-apply the remembered audio/sub/quality picks onto a FRESH player.
     * Each episode builds a new ExoPlayer, so without this every "next
     * episode" reset the user's language/subtitle/quality choice. Called from
     * onTracksChanged: track metadata differs per file (some episodes carry
     * language tags, some only labels), so matching runs against the actual
     * tracks of THIS stream. Re-entrant safe — applying an override fires
     * onTracksChanged again, but by then the selection already matches.
     */
    fun applyPreferences(player: ExoPlayer) {
        Session.preferredAudioLang?.let { want ->
            pickTrack(player, C.TRACK_TYPE_AUDIO, want)?.let { c ->
                if (!c.selectedNow) applyQuietly(player, c)
            }
        }
        when (val want = Session.preferredSubLang) {
            null -> {}
            "" -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            else -> pickTrack(player, C.TRACK_TYPE_TEXT, want)?.let { c ->
                if (!c.selectedNow) applyQuietly(player, c)
            }
        }
        Session.preferredVideoHeight?.let { wantHeight ->
            // Exact height, else the closest one below (never silently upgrade
            // past what the user picked on a slow connection).
            val video = allTracks(player, C.TRACK_TYPE_VIDEO)
            val best = video.filter { it.format.height in 1..wantHeight }
                .maxByOrNull { it.format.height }
                ?: video.firstOrNull()
            if (best != null && !best.selectedNow) applyQuietly(player, best)
        }
    }

    private data class Found(val groupIndex: Int, val trackIndex: Int, val format: Format, val selectedNow: Boolean)

    private fun allTracks(player: ExoPlayer, trackType: Int): List<Found> {
        val out = mutableListOf<Found>()
        player.currentTracks.groups.forEachIndexed { gi, group ->
            if (group.type != trackType || !group.isSupported) return@forEachIndexed
            for (ti in 0 until group.length) {
                out += Found(gi, ti, group.getTrackFormat(ti), group.isTrackSelected(ti))
            }
        }
        return out
    }

    /** Find the track whose language code or label matches the remembered key. */
    private fun pickTrack(player: ExoPlayer, trackType: Int, want: String): Found? =
        allTracks(player, trackType).firstOrNull { keyOf(it.format).equals(want, ignoreCase = true) }

    private fun applyQuietly(player: ExoPlayer, f: Found) {
        val group = player.currentTracks.groups[f.groupIndex]
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(f.trackIndex)))
        if (group.type == C.TRACK_TYPE_VIDEO) builder.setForceHighestSupportedBitrate(false)
        player.trackSelectionParameters = builder.build()
    }

    /** Stable identity for a track across episodes: language code, else label. */
    private fun keyOf(f: Format): String = f.language ?: f.label ?: ""

    private fun labelFor(type: Int, f: Format, index: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> if (f.height > 0) "${f.height}p" else "Track ${index + 1}"
        C.TRACK_TYPE_AUDIO -> langName(f) ?: (f.label ?: "Audio ${index + 1}")
        C.TRACK_TYPE_TEXT -> langName(f) ?: (f.label ?: "Sub ${index + 1}")
        else -> "Track ${index + 1}"
    }

    private fun langName(f: Format): String? {
        val code = f.language ?: return f.label
        return try {
            Locale(code).displayLanguage.ifBlank { code }
        } catch (e: Exception) {
            code
        }
    }
}
