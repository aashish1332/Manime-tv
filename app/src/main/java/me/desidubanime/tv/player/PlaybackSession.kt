package me.desidubanime.tv.player

import me.desidubanime.tv.data.model.Episode

/**
 * Holds the currently-open playlist so the Player screen can navigate the queue
 * (next / previous episode) without re-passing the whole list through nav args.
 */
object PlaybackSession {
    var animeId: Int = 0
    var animeTitle: String = ""
    var posterUrl: String = ""
    var episodes: List<Episode> = emptyList()
    var startIndex: Int = 0

    /**
     * Label of the last server that actually played (e.g. "CLOUD · SERVER 1").
     * The next episode tries this one first instead of re-racing everything.
     */
    var preferredServerLabel: String? = null

    // ---- track preferences, applied to every new ExoPlayer instance ----
    // Each episode builds a fresh player, so without these the user's picks
    // (Hindi audio, subtitles off, 720p) reset on every "next episode".

    /** Audio language code/label picked in the menu; null = player default. */
    var preferredAudioLang: String? = null

    /** Subtitle language; null = player default, "" = subtitles OFF. */
    var preferredSubLang: String? = null

    /** Video height picked in the menu (720, 1080…); null = auto/highest. */
    var preferredVideoHeight: Int? = null

    /**
     * Playback speed picked in the controls (0.5x…2x); null = 1x.
     * Episodes each build a fresh ExoPlayer, so without this every "next
     * episode" silently dropped back to normal speed mid-binge.
     */
    var preferredSpeed: Float? = null

    /** One-shot: seek here once the next stream opens (Continue Watching). */
    var resumePositionMs: Long = 0

    fun set(id: Int, title: String, poster: String, eps: List<Episode>, index: Int) {
        animeId = id
        animeTitle = title
        posterUrl = poster
        episodes = eps
        startIndex = index
    }
}
