package com.callerid.numberlookup.home.monetize.model

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.io.use

// Hard timeouts so a slow/dead geo endpoint can't stall the splash flow
// (this runs on the critical path before the runtime permission prompt).
// Worst case ~4s instead of OkHttp's 10s-per-stage default.
private val locationClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
}

fun lookupRegionByIp(): RegionDetails? {
    return try {
        val client = locationClient
        val request = Request.Builder()
            .url("http://ip-api.com/json/") // returns JSON with geo info
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body!!.string())
            RegionDetails(
                country = json.optString("country"),         // "India"
                countryCode = json.optString("countryCode"), // "IN"
                regionName = json.optString("regionName"),   // "Karnataka"
                city = json.optString("city")                // "Bengaluru"
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}