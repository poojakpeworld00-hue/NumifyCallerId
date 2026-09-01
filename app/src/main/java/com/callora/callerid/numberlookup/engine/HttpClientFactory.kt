package com.callora.callerid.numberlookup.engine

import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.callora.callerid.numberlookup.BuildConfig
import com.callora.callerid.numberlookup.CalloraApplication
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object HttpClientFactory {

    /**
     * API base URL, from the `api_config` Remote Config block with the historical
     * value as fallback (see [ApiConfig]).
     *
     * Read once, when [api] is first touched — Retrofit fixes its base URL at
     * build time. In practice Remote Config has landed by then (the splash
     * fetches it before Home), and if it hasn't, the fallback is the URL this
     * app has always used. A base-URL change therefore applies from the next
     * cold start, not mid-session.
     */
    val BASE_URL: String get() = ApiConfig.baseUrl(CalloraApplication.appContext)

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .addInterceptor(TokenInterceptor())
            // On-device HTTP inspector. Real in debug (captures + shows a Chucker
            // notification/UI); the release no-op variant is a pass-through, so
            // nothing is captured or shown to users.
            .addInterceptor(ChuckerInterceptor.Builder(CalloraApplication.appContext).build())

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor { message ->
                android.util.Log.d("OkHttp", message)
            }.apply { level = HttpLoggingInterceptor.Level.BODY }
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    val api: LookupApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LookupApi::class.java)
    }
}
