package me.desidubanime.tv.ui.tw

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.desidubanime.tv.data.DebugLog
import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.model.Playable
import me.desidubanime.tv.scrape.FileHostResolver
import me.desidubanime.tv.scrape.Hosters
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

private enum class Phase { Waiting, Checking, Unlocked, TimedOut }

/**
 * Phone-assisted ToonWorld4All unlock.
 *
 * WHY A PHONE IS REQUIRED (measured 2026-11, not assumed): T4A hands out a
 * DECOY file id until its own ad shortener has been solved. The gate is a
 * Cloudflare Turnstile whose token field stays empty and whose Continue button
 * stays disabled until a human ticks the box, so no app-side request, crawler
 * UA, data-route or offscreen WebView can clear it. A human on a phone can.
 *
 * The UX is built so the phone only has to solve the ads — nothing has to be
 * typed on the TV and no code has to be copied:
 *
 *  1. The TV shows ONE QR: the episode's own /redirect/ page.
 *  2. The phone opens it and solves the shortener ads until a file page loads.
 *  3. The TV polls that token in the background. The moment the site starts
 *     answering with a REAL hoster page instead of the decoy, the TV resolves
 *     it and playback starts by itself.
 *
 * Only if that automatic pickup does not happen (for example when the site's
 * unlock is bound to the phone's own cookies) does the relay step appear: a
 * second QR opens a private one-time topic, the phone pastes the file page URL
 * it ended on, and the TV resolves it. Both paths land in the same place.
 */
@Composable
fun TwUnlockScreen(
    tokenUrl: String,
    onUnlocked: (Playable.Stream) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var phase by remember { mutableStateOf(Phase.Waiting) }
    var status by remember { mutableStateOf("Waiting for your phone…") }
    var attempts by remember { mutableIntStateOf(0) }
    var showRelay by remember { mutableStateOf(false) }
    var manualCheck by remember { mutableIntStateOf(0) }
    var unlockedOnce by remember { mutableStateOf(false) }

    // A private topic for the fallback. Random so nobody else can publish into
    // it, and short enough to read out loud if scanning fails.
    val topic = remember { "ddatv-" + UUID.randomUUID().toString().replace("-", "").take(12) }
    val relayUrl = "https://ntfy.sh/$topic"
    val relayOn by rememberUpdatedState(showRelay)

    val checkFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { checkFocus.requestFocus() } }

    suspend fun deliver(target: String): Boolean {
        status = "Found the file page. Resolving stream…"
        val hoster = Hosters.detect(target) ?: "HubCloud"
        val stream = withTimeoutOrNull(25_000L) {
            runCatching { FileHostResolver.resolve(target, hoster, 22_000L, browser = null) }.getOrNull()
        }
        if (stream != null) {
            unlockedOnce = true
            phase = Phase.Unlocked
            status = "Unlocked — starting playback…"
            onUnlocked(stream)
            return true
        }
        status = "That link did not serve a video. Waiting for another…"
        return false
    }

    /** One automatic check: has the token started pointing at a real hoster page? */
    suspend fun tryToken(): Boolean {
        val target = runCatching { FileHostResolver.twTokenTarget(tokenUrl) }.getOrNull()
            ?: return false
        DebugLog.log("tw-unlock", "token now points at ${target.take(60)}")
        return deliver(target)
    }

    /** One relay check: did the phone publish a file page URL? */
    suspend fun tryRelay(): Boolean {
        val message = runCatching { TwRelay.poll(topic) }.getOrNull() ?: return false
        val url = TwRelay.firstUrlIn(message) ?: return false
        DebugLog.log("tw-unlock", "relay url ${url.take(60)}")
        return deliver(url)
    }

    // Main poll loop. Cancelled with the screen, and every network call is on
    // IO with its own short timeout, so leaving the player can never leave a
    // request running behind it.
    LaunchedEffect(tokenUrl) {
        repeat(30) { round ->
            if (!isActive || unlockedOnce) return@LaunchedEffect
            attempts = round + 1
            phase = Phase.Checking
            status = if (showRelay && round % 2 == 1) {
                "Checking for the link you sent…"
            } else {
                "Waiting for your phone to finish the ads…"
            }
            if (tryToken()) return@LaunchedEffect
            if (relayOn && round % 2 == 1 && tryRelay()) return@LaunchedEffect
            // The relay is the reliable path once the automatic one has had a
            // fair chance; surface it instead of leaving the viewer guessing.
            if (!showRelay && round >= 8) showRelay = true
            // Quick while the viewer is likely still tapping ads, then back off:
            // T4A rate-limits repeated token reads, and a solve can take minutes.
            delay(if (round < 12) 4_000 else 10_000)
        }
        if (!unlockedOnce) {
            phase = Phase.TimedOut
            status = "Still locked. You can retry, or send the link from your phone."
            showRelay = true
        }
    }

    // "Check now" — one immediate pass, without resetting the loop above.
    LaunchedEffect(manualCheck) {
        if (manualCheck == 0 || unlockedOnce) return@LaunchedEffect
        phase = Phase.Checking
        status = "Checking now…"
        if (!tryToken() && !tryRelay()) {
            status = "Still locked — finish the ads on your phone, then check again."
            phase = Phase.Waiting
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xF50A0C11)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ToonWorld4All unlock",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "one-time · phone only",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF8A94A6),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "BACK · cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF8A94A6),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "T4A keeps its files behind an ad captcha that only a person can pass. " +
                    "Solve it once on your phone — this screen picks the file up on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFC6CDDB),
            )
            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // ---- Step 1: the QR the phone scans ----
                Panel(Modifier.weight(1.05f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        QrCode(tokenUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "1 · Scan with your phone",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "2 · Solve the ads until a file page opens",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFC6CDDB),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // ---- Live status + fallback ----
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            when (phase) {
                                Phase.Checking -> "CHECKING"
                                Phase.Unlocked -> "UNLOCKED"
                                Phase.TimedOut -> "NOT YET"
                                Phase.Waiting -> "WAITING"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = when (phase) {
                                Phase.Unlocked -> Color(0xFF6BE08A)
                                Phase.TimedOut -> Color(0xFFFFB86B)
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(status, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "3 · The TV starts playing as soon as the unlock is seen." +
                                if (attempts > 0) "  (check $attempts)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A94A6),
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { manualCheck++ },
                                modifier = Modifier.focusRequester(checkFocus),
                            ) { Text("Check now") }
                            Button(onClick = { showRelay = !showRelay }) {
                                Text(if (showRelay) "Hide phone link" else "Send link from phone")
                            }
                        }

                        if (showRelay) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "If the TV does not unlock by itself: open this second code on the " +
                                    "phone, paste the file page URL you ended on, and send it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC6CDDB),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                QrCode(
                                    relayUrl,
                                    Modifier.width(132.dp).aspectRatio(1f),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        "Private topic",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF8A94A6),
                                    )
                                    Text(
                                        topic,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "ntfy.sh · paste + Send",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8A94A6),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onClose) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF14171E)),
    ) { content() }
}

/**
 * A QR of [content] on white, sized by the caller. Generated off the main
 * thread: a 640px encode is a few milliseconds, but this screen must never add
 * work to the frame that is already starting playback.
 */
@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, content) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val hints = mapOf(
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                )
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640, hints)
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix.get(i % matrix.width, i / matrix.width)) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
                android.graphics.Bitmap.createBitmap(
                    pixels,
                    matrix.width,
                    matrix.height,
                    android.graphics.Bitmap.Config.RGB_565,
                )
            }.getOrNull()
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                content,
                color = Color.Black,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The fallback relay over ntfy.sh.
 *
 * Own client, short timeouts, `cache(null)`: this runs while the player is
 * starting up and must never queue behind — or be answered from — an HTTP
 * cache. `poll=1` makes the request return immediately instead of streaming.
 */
private object TwRelay {

    private val client = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** The newest message body in [topic], or null. */
    suspend fun poll(topic: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://ntfy.sh/$topic/json?poll=1&since=10m")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", Api.USER_AGENT)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                // ntfy answers JSONL: one message object per line.
                body.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        runCatching {
                            val obj = kotlinx.serialization.json.Json
                                .parseToJsonElement(line)
                                .let { it as? kotlinx.serialization.json.JsonObject }
                            obj?.get("message")
                                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                                ?.content
                        }.getOrNull()
                    }
                    .lastOrNull { firstUrlIn(it) != null }
            }
        } catch (e: Exception) {
            DebugLog.log("tw-unlock", "relay poll failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** First http(s) URL in a message, preferring a file-host link. */
    fun firstUrlIn(message: String): String? {
        val urls = Regex("""https?://\S+""").findAll(message)
            .map { it.value.trimEnd('"', '\'', ',', ')', '.') }
            .filter { it.length in 12..2_000 && !it.contains("ntfy.sh") }
            .toList()
        return urls.firstOrNull { looksLikeFileHost(it) } ?: urls.firstOrNull()
    }

    private fun looksLikeFileHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "hubcloud", "gdflix", "driveleech", "drivebot", "pixeldrain", "workers.dev",
            "/drive/", "/file/", "/video/", "googleusercontent", "bytes-", "r2.dev",
        ).any { it in u }
    }
}
