package me.desidubanime.tv.data.api

/**
 * Constants shared by every request the app makes.
 *
 * DESIDUBANIME (desidubanime.me) USED TO LIVE HERE AND IS GONE.
 *
 * Measured 2026-11-27: `https://desidubanime.me/`, `https://www.desidubanime.me/`
 * and both `/wp-json/wp/v2/posts` endpoints answer `403` with
 * `cf-mitigated: challenge` and the "Just a moment…" Cloudflare interstitial.
 * That is a JavaScript challenge, so OkHttp can never read the site — and the
 * REST API it was the sole source of (catalogue, episodes, servers) always came
 * back empty while still costing a request on every launch and every detail
 * open. The three live sources are AnimeDubHindi, AnimeDrive and
 * ToonWorld4All; each has its own editable base URL in [AppSettings].
 */
object Api {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
