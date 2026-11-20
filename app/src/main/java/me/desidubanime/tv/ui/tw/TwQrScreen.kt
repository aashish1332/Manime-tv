package me.desidubanime.tv.ui.tw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.tv.material3.Button
import androidx.compose.ui.graphics.asImageBitmap
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import me.desidubanime.tv.data.ServiceLocator
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.player.TwUnlockHolder
import me.desidubanime.tv.scrape.FileHostResolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private enum class QrState { Preparing, Ready, Unlocked, Failed }

private val NTFY_CLIENT = OkHttpClient.Builder()
    .cache(null)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * Phone-assisted TW4A unlock v2 — ntfy.sh relay.
 *  LEFT QR : fresh T4A /redirect/ token  -> user solves 3 ads on phone.
 *  RIGHT QR: https://ntfy.sh/<random>    -> its web UI lets you PUBLISH a
 *           message; user pastes the final browser URL there and sends.
 * TV polls that topic; on receiving a drive URL it verifies and stores it —
 * the next TW server pick plays it directly (TwUnlockHolder).
 */
@Composable
fun TwQrScreen(
    onClose: () -> Unit,
    onPlayTw: (animeId: Int, title: String, posterUrl: String) -> Unit = { _, _, _ -> },
    /** Player mode: target = ISI episode ka /redirect/ token. */
    targetUrl: String? = null,
    onUnlocked: () -> Unit = {},
    /** Player mode: the anime's id for the holder — cand is null in this path. */
    animeId: Int = 0,
) {
    var state by remember { mutableStateOf(QrState.Preparing) }
    var solveUrl by remember { mutableStateOf<String?>(null) }
    var cand by remember {
        mutableStateOf<me.desidubanime.tv.data.repo.AnimeRepository.TwUnlockCandidate?>(null)
    }
    var statusLine by remember { mutableStateOf("Fetching token…") }
    var polls by remember { mutableIntStateOf(0) }

    val topic = remember { "ddatv-" + java.util.UUID.randomUUID().toString().take(10).replace("-", "") }
    val publishUrl = "https://ntfy.sh/$topic"

    fun extractUrl(msg: String): String? =
        Regex("https?://\\S+").findAll(msg)
            .map { it.value.trim('"', ',', ')') }
            .firstOrNull {
                val l = it.lowercase()
                // Accept any URL that looks like a file/drive link, or any HTTP URL as fallback
                listOf("hubcloud", "gdflix", "pixeldrain", "workers.dev", "bytes-", "/drive/", "/file/", "/video/", "filepress", "filebee", "fpgo").any { k -> k in l }
                    || (it.startsWith("http") && !it.contains("ntfy.sh"))
            }

    suspend fun pollOnce(): Boolean = try {
        val req = okhttp3.Request.Builder()
            .url("https://ntfy.sh/$topic/json?poll=1&since=5m")
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .header("User-Agent", Api.USER_AGENT)
            .build()
        NTFY_CLIENT.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            me.desidubanime.tv.data.DebugLog.log("tw-unlock", "poll HTTP ${r.code} body=${body.length}b")
            if (body.isBlank()) {
                me.desidubanime.tv.data.DebugLog.log("tw-unlock", "EMPTY body")
                return@use false
            }
            // Try to parse as JSON array (ntfy returns array of messages)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            try {
                val element = json.parseToJsonElement(body)
                val messages = when (element) {
                    is kotlinx.serialization.json.JsonArray -> element
                    is kotlinx.serialization.json.JsonObject -> {
                        // Single object, treat as one message
                        listOf(element)
                    }
                    else -> {
                        me.desidubanime.tv.data.DebugLog.log("tw-unlock", "Unexpected JSON type")
                        null
                    }
                }
                if (messages != null) {
                    for (obj in messages) {
                        val objJson = if (obj is kotlinx.serialization.json.JsonObject) obj else null
                        if (objJson == null) continue
                        val msg = objJson["message"]?.jsonPrimitive?.content
                        if (msg != null) {
                            me.desidubanime.tv.data.DebugLog.log("tw-unlock", "JSON msg=$msg")
                            val url = extractUrl(msg)
                            if (url != null) {
                                me.desidubanime.tv.data.DebugLog.log("tw-unlock", "got $url from JSON")
                                statusLine = "Message received. Resolving stream..."
                                val hoster = me.desidubanime.tv.scrape.Hosters.detect(url) ?: "HubCloud"
                                val stream = kotlinx.coroutines.withTimeoutOrNull(22_000L) {
                                    FileHostResolver.resolve(url, hoster, 20_000L)
                                }
                                if (stream != null) {
                                    me.desidubanime.tv.data.DebugLog.log("tw-unlock", "resolved OK -> stream ready")
                                    TwUnlockHolder.put(stream.url, if (cand != null) cand!!.animeId else animeId)
                                    state = QrState.Unlocked
                                    statusLine = "UNLOCKED ✓"
                                    onUnlocked()
                                    return true
                                } else {
                                    me.desidubanime.tv.data.DebugLog.log("tw-unlock", "resolve FAILED for $url")
                                }
                            } else {
                                me.desidubanime.tv.data.DebugLog.log("tw-unlock", "no URL in msg")
                            }
                        } else {
                            me.desidubanime.tv.data.DebugLog.log("tw-unlock", "no message field in JSON object")
                        }
                    }
                }
            } catch (e: Exception) {
                me.desidubanime.tv.data.DebugLog.log("tw-unlock", "JSON parse failed, falling back to line scan: ${e.message}")
                // Fallback: line-by-line (for JSONL or malformed)
                for (line in body.lineSequence()) {
                    if (line.isBlank()) continue
                    me.desidubanime.tv.data.DebugLog.log("tw-unlock", "line: $line")
                    if (!line.contains("\"message\"")) {
                        continue
                    }
                    // Try to extract message field using regex (handles both JSON object or array item)
                    val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    if (msg != null) {
                        me.desidubanime.tv.data.DebugLog.log("tw-unlock", "msg=$msg")
                        val url = extractUrl(msg)
                    if (url != null) {
                        me.desidubanime.tv.data.DebugLog.log("tw-unlock", "got $url")
                        statusLine = "Message received. Resolving stream..."
                        val hoster = me.desidubanime.tv.scrape.Hosters.detect(url) ?: "HubCloud"
                            val stream = kotlinx.coroutines.withTimeoutOrNull(22_000L) {
                                FileHostResolver.resolve(url, hoster, 20_000L)
                            }
                            if (stream != null) {
                                me.desidubanime.tv.data.DebugLog.log("tw-unlock", "resolved OK -> stream ready")
                                TwUnlockHolder.put(stream.url, if (cand != null) cand!!.animeId else animeId)
                                state = QrState.Unlocked
                                statusLine = "UNLOCKED ✓"
                                onUnlocked()
                                return true
                            } else {
                                me.desidubanime.tv.data.DebugLog.log("tw-unlock", "resolve FAILED for $url")
                            }
                        } else {
                            me.desidubanime.tv.data.DebugLog.log("tw-unlock", "no matching URL in msg")
                        }
                    }
                }
            }
            false
        }
    } catch (e: Exception) {
        me.desidubanime.tv.data.DebugLog.log("tw-unlock", "poll error: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (targetUrl != null) {
                solveUrl = targetUrl
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    state = QrState.Ready
                    statusLine = "Scan with phone → solve 3 ads → copy final URL → paste+Send in RIGHT QR"
                }
            } else {
                val c = ServiceLocator.repository.twUnlockCandidate()
                cand = c
                solveUrl = c?.url
                if (solveUrl == null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        state = QrState.Failed
                        statusLine = "TW4A token not found — site may be down/changed."
                    }
                    return@withContext
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    state = QrState.Ready
                    statusLine = "Solve → copy final browser URL → open RIGHT QR → paste + Send"
                }
            }
            var unlocked = false
            repeat(40) {
                polls++
                if (pollOnce()) {
                    unlocked = true
                    return@repeat
                }
                delay(7_000)
            }
            if (!unlocked) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    state = QrState.Failed
                    statusLine = "No message received — did you open RIGHT QR and paste+Send the final URL?"
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "TW4A — Unlock with Phone",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            QrState.Preparing -> {
                Spacer(Modifier.height(100.dp))
                Text(statusLine)
            }

            QrState.Ready -> {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MiniQr(solveUrl ?: "", Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(8.dp))
                        Text("1 · Scan → solve ads", style = MaterialTheme.typography.labelMedium)
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MiniQr(publishUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(8.dp))
                        Text("2 · Scan → paste+Send URL", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(statusLine + "  (check #$polls)", style = MaterialTheme.typography.bodySmall)
            }

            QrState.Unlocked -> {
                Text("✓", color = Color(0xFF4CAF50), style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(8.dp))
                Text(statusLine, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                cand?.let { c ->
                    Button(onClick = { onPlayTw(c.animeId, c.title, c.posterUrl) }) {
                        Text("▶ Play now: ${c.title.take(34)}")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = onClose) { Text("Back") }
            }

            QrState.Failed -> {
                Text("✗", color = Color(0xFFE50914), style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(8.dp))
                Text(statusLine, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClose) { Text("Back") }
            }
        }
    }
}

@Composable
private fun MiniQr(content: String, modifier: Modifier = Modifier) {
    val bmp = remember(content) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
            val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 500, 500, hints)
            val px = IntArray(m.width * m.height) { i ->
                if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.RGB_565)
        }.getOrNull()
    }
    Box(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("QR error", color = Color.Black)
        }
    }
}
