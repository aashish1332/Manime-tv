package me.desidubanime.tv.player

import me.desidubanime.tv.data.model.Playable

/**
 * Hand-off point for the phone-assisted ToonWorld4All unlock.
 *
 * T4A serves decoy file ids until its own shortener captcha has been solved
 * (measured: the props `hidden` value is random filler while the gate is up,
 * and the site's gate is a Cloudflare Turnstile that only a human can tick).
 * The phone solves it; the TV then gets the real hoster page and resolves it.
 *
 * The result is kept here, keyed to the anime, so every T4A row of the same
 * anime plays instantly afterwards instead of re-resolving. These are signed
 * CDN links, so the entry expires quickly.
 */
object TwUnlockHolder {

    private const val TTL_MS = 15 * 60_000L

    @Volatile
    private var stream: Playable.Stream? = null

    @Volatile
    private var at = 0L

    @Volatile
    private var animeId = 0

    fun put(value: Playable.Stream, forAnimeId: Int) {
        stream = value
        animeId = forAnimeId
        at = System.currentTimeMillis()
    }

    /** The unlocked stream for [forAnimeId], or null when absent/expired. */
    fun take(forAnimeId: Int): Playable.Stream? {
        val current = stream ?: return null
        if (System.currentTimeMillis() - at > TTL_MS || animeId != forAnimeId) {
            clear()
            return null
        }
        return current
    }

    fun clear() {
        stream = null
        at = 0L
        animeId = 0
    }
}
