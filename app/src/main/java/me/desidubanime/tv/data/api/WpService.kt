package me.desidubanime.tv.data.api

import me.desidubanime.tv.data.model.GetEpisodesResponse
import me.desidubanime.tv.data.model.WpAnime
import me.desidubanime.tv.data.model.WpEpisodePost
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

interface WpService {

    @GET("wp-json/wp/v2/anime")
    suspend fun listAnime(
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1,
        @Query("_embed") embed: Int = 1,
        @Query("orderby") orderBy: String = "date",
        @Query("order") order: String = "desc",
    ): List<WpAnime>

    @GET("wp-json/wp/v2/anime")
    suspend fun searchAnime(
        @Query("search") query: String,
        @Query("per_page") perPage: Int = 40,
        @Query("_embed") embed: Int = 1,
    ): List<WpAnime>

    @GET("wp-json/wp/v2/anime/{id}")
    suspend fun anime(
        @Path("id") id: Int,
        @Query("_embed") embed: Int = 1,
    ): WpAnime

    /** Slug lookup — resolves ADH "Watch online" links to anime not in the feed window. */
    @GET("wp-json/wp/v2/anime")
    suspend fun animeBySlug(
        @Query("slug") slug: String,
        @Query("_embed") embed: Int = 1,
    ): List<WpAnime>

    /** Global latest-episodes feed — tells us which anime got fresh uploads. */
    @GET("wp-json/wp/v2/episode")
    suspend fun listEpisodes(
        @Query("per_page") perPage: Int = 100,
        @Query("orderby") orderBy: String = "date",
        @Query("order") order: String = "desc",
        @Query("_fields") fields: String = "id,slug,date,link",
    ): List<WpEpisodePost>

    /** The real endpoint the site JS uses to page episodes for an anime. */
    @GET("wp-admin/admin-ajax.php?action=get_episodes")
    suspend fun getEpisodes(
        @Query("anime_id") animeId: Int,
        @Query("page") page: Int = 1,
        @Query("order") order: String = "asc",
    ): GetEpisodesResponse

    companion object {
        /**
         * Retrofit bakes its base URL at build time, which froze the DDA domain
         * to whatever was set at app start — a Settings change demanded a
         * restart to take effect. Fix: build against a placeholder base and let
         * an OkHttp interceptor rewrite scheme/host/port on EVERY request to the
         * current [Api.HOST]. Editing the DDA URL in Settings now applies
         * immediately (the caller calls [Api.setHost] on save); no restart.
         */
        fun create(client: OkHttpClient = Network.client): WpService {
            val contentType = "application/json".toMediaType()
            val dynamicHostClient = client.newBuilder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val rewritten = try {
                        val base = Api.HOST.trimEnd('/').toHttpUrl()
                        req.url.newBuilder()
                            .scheme(base.scheme)
                            .host(base.host)
                            .port(base.port)
                            .build()
                    } catch (e: Exception) {
                        req.url
                    }
                    chain.proceed(req.newBuilder().url(rewritten).build())
                }
                .build()
            return Retrofit.Builder()
                .baseUrl("https://placeholder.in/")
                .client(dynamicHostClient)
                .addConverterFactory(Network.json.asConverterFactory(contentType))
                .build()
                .create(WpService::class.java)
        }
    }
}
