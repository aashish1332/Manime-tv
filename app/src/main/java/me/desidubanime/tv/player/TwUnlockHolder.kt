package me.desidubanime.tv.player

/**
 * Hand-off point for the phone-assisted TW4A flow: the phone publishes the
 * FINAL working drive URL (via ntfy.sh), the TV stores it here, and the next
 * TW option resolution plays it directly instead of hitting the ad-wall.
 * Fresh for [TTL_MS] — these signed URLs expire quickly.
 */
object TwUnlockHolder {
    @Volatile var url: String? = null
    @Volatile var at: Long = 0
    @Volatile var animeId: Int = 0
    private const val TTL_MS = 15 * 60_000L

    fun put(u: String, forAnimeId: Int) {
        url = u
        animeId = forAnimeId
        at = System.currentTimeMillis()
    }

    /** Valid for this anime only — cleared when the anime id does not match. */
    fun take(forAnimeId: Int): String? {
        val u = url ?: return null
        if (System.currentTimeMillis() - at > TTL_MS || animeId != forAnimeId) {
            url = null
            return null
        }
        return u
    }
}
