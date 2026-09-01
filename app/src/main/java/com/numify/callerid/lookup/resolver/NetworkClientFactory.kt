package com.numify.callerid.lookup.resolver

import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.NumifyApplication
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClientFactory {

    /**
     * The API base URL, taken from the `api_config` Remote Config block with the
     * historical value as its fallback (see [EndpointConfig]).
     *
     * It is read once, the first time [api] is touched, because Retrofit fixes its
     * base URL at build time. In practice Remote Config has already landed by
     * then, since the splash fetches it before Home, and if it has not, the
     * fallback is the URL this app has always used. A base-URL change therefore
     * takes effect from the next cold start rather than mid-session.
     */
    val BASE_URL: String get() = EndpointConfig.baseUrl(NumifyApplication.appContext)

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor())
            // On-device HTTP inspector. Real in debug (captures + shows a Chucker
            // notification/UI); the release no-op variant is a pass-through, so
            // nothing is captured or shown to users.
            .addInterceptor(ChuckerInterceptor.Builder(NumifyApplication.appContext).build())

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor { message ->
                android.util.Log.d("OkHttp", message)
            }.apply { level = HttpLoggingInterceptor.Level.BODY }
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    val api: NumberLookupService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NumberLookupService::class.java)
    }
}
