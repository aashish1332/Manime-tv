package me.desidubanime.tv.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.desidubanime.tv.App
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One release of this project's own GitHub repository.
 *
 * [sha256] is GitHub's own asset digest (the `digest` field of a release asset,
 * `"sha256:<hex>"`). It is the only cheap way to prove the APK arriving on a
 * TV box is byte-for-byte what was published — a truncated download over a bad
 * Wi-Fi link used to reach the installer and fail there with a useless message.
 */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkName: String,
    val apkSize: Long,
    val sha256: String?,
) {
    /** "v1.1.0" — what the UI shows. */
    val versionLabel: String get() = tag.ifBlank { name }
}

/**
 * GitHub release lookup for the TV APK.
 *
 * Two deliberate differences from a plain `releases/latest` call:
 *
 *  1. **Conditional requests.** The response's ETag is stored next to the body
 *     and replayed as `If-None-Match`. GitHub does not count a 304 against the
 *     60-requests-per-hour unauthenticated limit, so a box that is power-cycled
 *     every day can still check every launch without ever being rate limited.
 *  2. **A dedicated client.** Updates must not queue behind (or ahead of) a
 *     scrape. No scrape UA/Referer spoofing either — api.github.com is not
 *     behind the sites' Cloudflare rules, and a 28 MB APK needs a read timeout
 *     no catalog request would tolerate.
 */
object UpdateApi {

    /** Change this if the project ever moves. */
    const val REPO = "aashish1332/Manime-tv"

    private val json = Json { ignoreUnknownKeys = true }

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // The release JSON is tiny; the APK needs no overall deadline on a
        // 2 Mbps TV box, so 0 = "as long as the socket keeps delivering".
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** No release published yet (GitHub answers 404). Not an error. */
    class NoRelease : Exception("No releases published yet")

    /** GitHub says come back later (403 secondary limit / 429). */
    class RateLimited : Exception("GitHub rate limit — try again later")

    private val bodyFile: File get() = File(App.instance.cacheDir, "update/release.json")
    private val etagFile: File get() = File(App.instance.cacheDir, "update/release.etag")

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val etag = readEtag()
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                // Unchanged: reuse the cached body. Cached ETag with no body
                // (cache cleared) falls through to a normal 200 retry below.
                response.code == 304 -> {
                    val cached = readBody()
                        ?: throw IllegalStateException("Cached release info was lost")
                    parse(cached)
                }
                response.code == 404 -> throw NoRelease()
                response.code == 403 || response.code == 429 -> throw RateLimited()
                !response.isSuccessful -> throw IllegalStateException("HTTP ${response.code}")
                else -> {
                    val raw = response.body?.string().orEmpty()
                    writeCache(response.header("ETag"), raw)
                    parse(raw)
                }
            }
        }
    }

    private fun parse(raw: String): ReleaseInfo {
        val obj = json.parseToJsonElement(raw).jsonObject
        val assets = obj["assets"] as? JsonArray ?: JsonArray(emptyList())
        val apk = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
            asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true
        }
        // "sha256:abc…" -> "abc…"; anything shorter than a real digest is
        // treated as absent rather than compared loosely.
        val digest = apk?.get("digest")?.jsonPrimitive?.contentOrNull
            ?.substringAfter(':', "")
            ?.lowercase()
            ?.takeIf { it.length == 64 }
        return ReleaseInfo(
            tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().take(1200),
            htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull
                ?: "https://github.com/$REPO/releases/latest",
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
            apkName = apk?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
            sha256 = digest,
        )
    }

    /**
     * Semantic comparison of "v1.1.0" style tags against the installed
     * versionName. Missing segments count as 0, so "v1.1" == "1.1.0" and
     * "v1.1.1" > "1.1". Non-numeric ordering only matters for pre-release
     * suffixes, which this project does not publish.
     */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(t: String) = t.trim().trimStart('v', 'V')
            .split('.', '-', '+')
            .map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        if (r.isEmpty() || r.all { it == 0 }) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    // ---- small disk cache (body + ETag) ----

    private fun readBody(): String? = runCatching {
        bodyFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readEtag(): String? = runCatching {
        etagFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeCache(etag: String?, body: String) {
        runCatching {
            bodyFile.parentFile?.mkdirs()
            bodyFile.writeText(body)
            if (etag.isNullOrBlank()) etagFile.delete() else etagFile.writeText(etag)
        }
    }

    /** Drop the cached release body (Settings → clear cache). */
    fun clearCache() {
        runCatching { bodyFile.delete() }
        runCatching { etagFile.delete() }
    }
}
