package com.numify.callerid.lookup.resolver

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the contact-saver `x-api-key` header.
 *
 * Three deliberate choices here:
 *
 * 1. **A header, not a query parameter.** The previous API passed its credential
 *    as `?hash_key=`, which puts the secret into every server access log, every
 *    proxy log in between, and any `Referer` the server later emits. A header
 *    stays out of all of those.
 *
 * 2. **Scoped to the API host.** The base URL is steerable from Remote Config,
 *    and Remote Config is publicly readable. Without this check, anyone able to
 *    influence that value could point the app at a host of their choosing and be
 *    handed the key on the first request. The header is attached only when the
 *    request host matches the configured API host.
 *
 * 3. **HTTPS only.** A key on a cleartext request is a key handed to anyone on
 *    the network path. The scheme is checked rather than assumed, because
 *    `contacts_base_url` is remote-controlled and could arrive as `http://`.
 *
 * A request that fails either check is still sent — unauthenticated, so the
 * server rejects it — rather than being silently dropped, which would look like
 * a network fault.
 */
class ApiKeyInterceptor(context: Context) : Interceptor {

    private val appContext = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        val host = EndpointConfig.apiHost(appContext)
        val key = CredentialProvider.CONTACTS_API_KEY

        val send = key.isNotBlank() &&
            url.isHttps &&
            host.isNotBlank() &&
            url.host.equals(host, ignoreCase = true)

        if (!send) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header(HEADER_API_KEY, key).build()
        )
    }

    companion object {
        const val HEADER_API_KEY = "x-api-key"
    }
}
