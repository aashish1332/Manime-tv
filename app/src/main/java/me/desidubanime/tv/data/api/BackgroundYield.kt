package me.desidubanime.tv.data.api

import kotlinx.coroutines.delay

/**
 * Cooperative pause for the long catalog jobs (slug sweep, title sweep, catalog
 * warm-ups).
 *
 * Those jobs are hundreds of HTML fetches with Jsoup parses. On a TV box that is
 * real CPU, and it is the same CPU the video decoder and the Compose UI thread
 * need. Cloudflare rate limits are the second half: while playback is running,
 * every sweep request the app makes is bandwidth the stream wanted.
 *
 * So playback holds this flag, and each sweep checks it between pages instead of
 * running to completion underneath the player. Unlike [InteractionGate] it
 * pauses the *job*, not the HTTP client — the player's own requests are never
 * affected.
 */
object BackgroundYield {

    @Volatile
    var paused: Boolean = false

    /** Suspend while the app is playing something. Cheap when not paused. */
    suspend fun waitIfPaused() {
        while (paused) delay(750)
    }
}
