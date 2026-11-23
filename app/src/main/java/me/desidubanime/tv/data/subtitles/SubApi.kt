package me.desidubanime.tv.data.subtitles

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.desidubanime.tv.App
import me.desidubanime.tv.data.api.Network
import me.desidubanime.tv.data.model.SubtitleTrack
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** One hit from the subtitle search, enough to show a row and download it. */
@Serializable
data class SubResult(
    val lang: String = "",
    val title: String = "",
    val downloadUrl: String = "",
    val detail: String = "",
)

/**
 * OpenSubtitles search + download for a single episode.
 *
 * Two things make this worth keeping even though the sources ship subtitles:
 * they are often English-only or missing entirely on older episodes, and the
 * REST endpoint the web front end uses needs no API key.
 *
 * Fetching rides [Network.bulk] on purpose — a subtitle search is background
 * work, so it must never take a slot from a click or the feed.
 */
object SubApi {

    private val json = Json { ignoreUnknownKeys = true }

    /** Language passed to OpenSubtitles; "eng" matches the source audio. */
    const val DEFAULT_LANG = "eng"

    suspend fun search(title: String, lang: String = DEFAULT_LANG): List<SubResult> =
        withContext(Dispatchers.IO) {
            // "Naruto Episode 12" finds nothing; the series name finds it.
            val query = title.trim()
                .replace(Regex("""(?i)\s*(?:episode|ep\.?)\s*\d+.*$"""), "")
                .trim()
            if (query.isBlank()) return@withContext emptyList()

            val url = "https://rest.opensubtitles.org/search/query-" +
                URLEncoder.encode(query, "utf-8") + "/sublanguageid-" + lang
            val body = try {
                val request = Request.Builder()
                    .url(url)
                    // The endpoint expects the desktop client it serves.
                    .header("x-user-agent", "VLSub 0.10.2")
                    .build()
                Network.bulk.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                return@withContext emptyList()
            }

            // Loose parse: the payload is an array of objects whose fields are
            // not all strings in every response.
            runCatching {
                json.decodeFromString<List<Map<String, String>>>(body).mapNotNull { row ->
                    val download = row["SubDownloadLink"].orEmpty()
                    if (download.isBlank()) return@mapNotNull null
                    SubResult(
                        lang = row["ISO639"].orEmpty(),
                        title = listOf(row["MovieName"].orEmpty(), row["SubFileName"].orEmpty())
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        downloadUrl = download,
                        detail = listOf(row["SeriesSeason"].orEmpty(), row["SeriesEpisode"].orEmpty())
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                    )
                }.take(20)
            }.getOrDefault(emptyList())
        }

    /**
     * Downloads a hit into the app's cache as a playable sidecar file.
     *
     * OpenSubtitles serves gzip regardless of the URL's extension, so the magic
     * bytes decide — not the file name. The result is a `file://` track, which
     * is also what makes the player's subtitle-delay control possible.
     */
    suspend fun fetch(result: SubResult): SubtitleTrack = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(result.downloadUrl)
            .header("x-user-agent", "VLSub 0.10.2")
            .build()
        val bytes = Network.bulk.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.bytes() ?: error("Empty subtitle")
        }
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        writeToCache(App.instance, raw, result)
    }

    private fun writeToCache(context: Context, raw: ByteArray, result: SubResult): SubtitleTrack {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        // One file per download: an old pick must not be overwritten by a new
        // one while ExoPlayer still has the previous file mapped.
        val base = result.title.ifBlank { "subtitle" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40)
        val isVtt = raw.take(16).toByteArray().toString(Charsets.UTF_8).contains("WEBVTT")
        val name = "$base-${System.currentTimeMillis()}.${if (isVtt) "vtt" else "srt"}"
        val file = File(dir, name)
        file.writeBytes(raw)
        return SubtitleTrack(
            url = "file://${file.absolutePath}",
            label = result.title.ifBlank { name },
            isVtt = isVtt,
        )
    }
}
