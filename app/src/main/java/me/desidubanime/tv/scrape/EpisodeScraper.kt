package me.desidubanime.tv.scrape

import android.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.api.Network
import me.desidubanime.tv.data.model.Server
import me.desidubanime.tv.data.model.ServerGroup
import me.desidubanime.tv.data.model.SubServer
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Scrapes an episode /watch/ page and decodes its server list.
 *
 * Each server button carries  data-embed-id="BASE64(name):BASE64(url)".
 * Servers are grouped into .player-sub / .player-dub containers.
 */
object EpisodeScraper {

    private fun fetch(url: String): Document {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Api.USER_AGENT)
            .header("Referer", Api.REFERER)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()
        Network.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return Jsoup.parse(body, url)
        }
    }

    private fun decode(b64: String): String =
        try {
            String(Base64.decode(b64.trim(), Base64.DEFAULT))
        } catch (e: Exception) {
            ""
        }

    /** Parse the "BASE64(name):BASE64(url)" attribute value. */
    private fun parseEmbedId(raw: String): Pair<String, String>? {
        val idx = raw.indexOf(':')
        if (idx <= 0) return null
        val name = decode(raw.substring(0, idx))
        val url = decode(raw.substring(idx + 1))
        if (name.isBlank() || !url.startsWith("http")) return null
        return name to url
    }

    fun servers(watchUrl: String): List<Server> {
        val doc = try {
            fetch(watchUrl)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = LinkedHashMap<String, Server>()

        // Any element carrying data-embed-id, remembering which sub/dub box it sits in.
        for (el in doc.select("[data-embed-id]")) {
            val parsed = parseEmbedId(el.attr("data-embed-id")) ?: continue
            val (name, url) = parsed
            val group = when {
                el.closest(".player-dub") != null -> ServerGroup.DUB
                el.closest(".player-sub") != null -> ServerGroup.SUB
                else -> ServerGroup.SUB
            }
            val isCloud = url.contains("cloud.desidubanime") || url.contains("/external/")
            val isMirror = url.contains("gdmirrorbot") || name.contains("mirror", ignoreCase = true)
            // de-dupe on url
            out.putIfAbsent(
                url,
                Server(name = name, embedUrl = url, group = group, isCloud = isCloud, isMirror = !isCloud && isMirror),
            )
        }
        return out.values.toList()
    }

    /**
     * A CLOUD server page contains  const sources = [{"name":"SERVER 1","url":"/play/..."}].
     * Expand it into absolute sub-server play URLs.
     */
    fun cloudSubServers(cloudUrl: String): List<SubServer> {
        val html = try {
            val req = Request.Builder()
                .url(cloudUrl)
                .header("User-Agent", Api.USER_AGENT)
                .header("Referer", Api.REFERER)
                .build()
            Network.client.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            return emptyList()
        }

        val origin = originOf(cloudUrl)
        val match = Regex("""const\s+sources\s*=\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        // Pull each {"name":"..","url":".."} pair without a full JSON parser (html is messy).
        val items = Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
            .findAll(match)
        return items.map { m ->
            val name = m.groupValues[1]
            var u = m.groupValues[2].replace("\\/", "/")
            if (u.startsWith("/")) u = origin + u
            SubServer(name = name, playUrl = u)
        }.toList()
    }

    /**
     * Mirrordub (gdmirrorbot) is a multi-host aggregator. Its page JS POSTs the
     * sid to /embedhelper.php and builds each sub-server URL as
     * siteUrls[key] + base64decode(mresult)[key]. The endpoint 302s to another
     * host and expects the POST to be re-sent there (OkHttp would downgrade the
     * redirect to GET, so we follow manually).
     */
    fun mirrorSubServers(mirrorUrl: String): List<SubServer> {
        val sid = mirrorUrl.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (sid.isBlank()) return emptyList()
        val origin = originOf(mirrorUrl)
        val mirrorHost = origin.removePrefix("https://").removePrefix("http://")
        val siteHost = Api.HOST.removePrefix("https://")

        val form = FormBody.Builder()
            .add("sid", sid)
            .add("UserFavSite", "")
            .add("currentDomain", """["$siteHost","$mirrorHost"]""")
            .build()

        val noRedirects = Network.client.newBuilder().followRedirects(false).build()
        var url = "$origin/embedhelper.php"
        var body: String? = null
        try {
            for (hop in 0 until 4) {
                val req = Request.Builder()
                    .url(url)
                    .post(form)
                    .header("User-Agent", Api.USER_AGENT)
                    .header("Referer", mirrorUrl)
                    .header("Origin", origin)
                    .build()
                var next: String? = null
                noRedirects.newCall(req).execute().use { resp ->
                    if (resp.code in 300..399) {
                        val loc = resp.header("Location")
                        if (loc != null) next = if (loc.startsWith("http")) loc else origin + loc
                    } else if (resp.isSuccessful) {
                        body = resp.body?.string()
                    }
                }
                if (body != null) break
                url = next ?: return emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val text = body ?: return emptyList()

        return try {
            val root = Network.json.parseToJsonElement(text).jsonObject
            val siteUrls = root["siteUrls"]?.jsonObject ?: return emptyList()
            val friendly = root["siteFriendlyNames"]?.jsonObject
            val idsB64 = root["mresult"]?.jsonPrimitive?.content ?: return emptyList()
            val ids = Network.json.parseToJsonElement(decode(idsB64)).jsonObject
            ids.mapNotNull { (key, idEl) ->
                val base = siteUrls[key]?.jsonPrimitive?.content ?: return@mapNotNull null
                val fileId = idEl.jsonPrimitive.content
                if (fileId.isBlank()) return@mapNotNull null
                val name = friendly?.get(key)?.jsonPrimitive?.content ?: key
                SubServer(name = name, playUrl = base + fileId)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun originOf(url: String): String {
        val m = Regex("""^(https?://[^/]+)""").find(url) ?: return Api.HOST
        return m.groupValues[1]
    }
}
