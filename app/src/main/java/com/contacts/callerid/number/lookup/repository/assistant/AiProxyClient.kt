package com.contacts.callerid.number.lookup.repository.assistant

import com.contacts.callerid.number.lookup.entity.AiQueryRequest
import com.contacts.callerid.number.lookup.entity.AiQueryResponse
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The one call the app makes to the assistant proxy.
 *
 * `@Url` takes the whole endpoint because it comes from Remote Config
 * (`ai_assistant_endpoint`) and annotation arguments must be compile-time
 * constants — the same reason [com.contacts.callerid.number.lookup.resolver.NumberLookupService]
 * is written this way.
 */
internal interface AiProxyService {

    @POST
    suspend fun ask(@Url url: String, @Body body: AiQueryRequest): Response<AiQueryResponse>
}

/**
 * Retrofit for the assistant proxy, deliberately separate from
 * [com.contacts.callerid.number.lookup.resolver.NetworkClientFactory].
 *
 * Two reasons not to share that one. Its interceptor chain is built for the
 * lookup API, and an auth header meant for one host must never be attached to
 * requests bound for another. And a model answers in seconds rather than
 * milliseconds, so this client needs a read timeout the lookup API would not
 * want — long enough for a real answer, short enough that a hung proxy does not
 * leave the user watching a thinking indicator forever.
 */
internal object AiProxyClient {

    private val clients = ConcurrentHashMap<String, AiProxyService>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            // No retry: a repeated question is a second billed answer, and the
            // user can ask again themselves if they want one.
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * A service for [endpoint]. Retrofit demands a base URL even when every call
     * passes an absolute `@Url`, so the endpoint's own origin is used and the
     * instances are cached per origin rather than rebuilt for each question.
     */
    fun service(endpoint: String): AiProxyService = clients.getOrPut(origin(endpoint)) {
        Retrofit.Builder()
            .baseUrl(origin(endpoint))
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AiProxyService::class.java)
    }

    /**
     * Scheme and host of [endpoint], with a trailing slash.
     *
     * A malformed endpoint returns a host that cannot resolve rather than
     * throwing: a bad Remote Config value should surface as "not reachable" on
     * the screen, not as a crash on a background thread.
     */
    private fun origin(endpoint: String): String = runCatching {
        val url = URL(endpoint)
        "${url.protocol}://${url.authority}/"
    }.getOrDefault(UNRESOLVABLE)

    private const val CONNECT_SECONDS = 15L

    /** A model answer takes seconds; past this the user is owed an error instead. */
    private const val READ_SECONDS = 45L

    private const val UNRESOLVABLE = "https://endpoint.invalid/"
}
