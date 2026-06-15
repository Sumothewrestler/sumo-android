package `in`.rfidpro.sumo.api

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import `in`.rfidpro.sumo.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton API gateway. `init(ctx)` is called once from SumoApp;
 * everything else lazily reads through `api` / `tokens`.
 *
 * The OkHttpClient is shared across Retrofit calls AND the audio
 * streaming download in AudioPlayback, so cookies + auth headers stay
 * consistent.
 */
object PentadClient {

    private lateinit var appCtx: Context

    val tokens: TokenStore by lazy { TokenStore(appCtx) }

    val cookieJar: PersistentCookieJar by lazy {
        PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(appCtx))
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(AuthInterceptor(tokens))
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG)
                        HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
                }
            )
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)   // Sumo turns can take ~8s
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val moshi by lazy {
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    }

    val api: PentadApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PentadApi::class.java)
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Build a direct URL string for non-Retrofit consumers (e.g. the
     *  audio playback layer that streams /api/agent/speak/ via
     *  OkHttp directly). */
    fun urlFor(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + path

    /** Logout — clears tokens + cookies. */
    fun logout() {
        tokens.clearAuth()
        cookieJar.clear()
    }
}
