package me.desidubanime.tv.data.cache

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What each source post was last PROVEN to hold.
 *
 * A card on Home promises episodes. All three sites publish a *series* page
 * weeks before the first file is uploaded (measured 2026-11-27: an announced
 * ToonWorld/AnimeDrive season answers with a normal page holding zero episode
 * links), so "post exists" is not the same as "has an episode". The only
 * honest proof is reading that series page — one request per post — and the
 * answer is worth remembering: it never gets cheaper, it changes rarely, and
 * losing it costs a refetch, never correctness.
 *
 * Keyed `<source>:<postId>` (adh/ad/tw). [zeros] counts how many consecutive
 * checks found nothing, because ONE empty parse can also be a dropped
 * connection; a card is only declared empty after two independent looks.
 */
@Serializable
data class ProbeEntry(
    /** Episode links found the last time this page was parsed. */
    val count: Int = 0,
    /** Wall clock of that look. */
    val at: Long = 0L,
    /** Consecutive looks that found nothing. */
    val zeros: Int = 0,
)

class EpisodeProbeStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    private val file: File
        get() = File(context.filesDir, "episode_probes.json")

    fun load(): Map<String, ProbeEntry> = synchronized(lock) {
        runCatching {
            if (!file.exists()) return emptyMap()
            json.decodeFromString(SERIAL, file.readText())
        }.getOrDefault(emptyMap())
    }

    /** Merge + persist. Bounded, newest first, so a long-lived install stays small. */
    fun put(entries: Map<String, ProbeEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            runCatching {
                val merged = HashMap(load())
                merged.putAll(entries)
                val trimmed = merged.entries
                    .sortedByDescending { it.value.at }
                    .take(MAX_ENTRIES)
                    .associate { it.key to it.value }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(SERIAL, trimmed))
                if (!tmp.renameTo(file)) {
                    file.writeText(json.encodeToString(SERIAL, trimmed))
                    tmp.delete()
                }
            }
        }
    }

    private companion object {
        val SERIAL = MapSerializer(String.serializer(), ProbeEntry.serializer())

        /** ~600 posts covers the visible catalogue several times over, at ~40 bytes each. */
        const val MAX_ENTRIES = 600
    }
}
