package me.desidubanime.tv.data.api

object Api {
    const val DEFAULT_HOST = "https://www.desidubanime.me"

    /**
     * DDA base — editable in Settings (domain rotation, like ADH). Loaded from
     * AppSettings at app start; a change applies on next app launch because
     * Retrofit bakes the base URL in at creation.
     */
    @Volatile
    var HOST: String = DEFAULT_HOST
        private set

    val BASE: String get() = "$HOST/"
    val WP: String get() = "$HOST/wp-json/wp/v2/"
    val AJAX: String get() = "$HOST/wp-admin/admin-ajax.php"
    val REFERER: String get() = "$HOST/"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun setHost(host: String) {
        HOST = host.trim().trimEnd('/').ifBlank { DEFAULT_HOST }
    }
}
