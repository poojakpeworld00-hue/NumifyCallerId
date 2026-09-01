package com.numify.callerid.numberlookup.engine

import okhttp3.Interceptor

class TokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder()
            // .addHeader("Authorization", "Bearer YOUR_TOKEN")
            .build()
        return chain.proceed(request)
    }
}
