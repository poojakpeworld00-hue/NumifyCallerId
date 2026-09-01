package com.numify.callerid.numberlookup.engine

import com.google.gson.JsonObject
import com.numify.callerid.numberlookup.schema.PhonePayload
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Paths come in as `@Url` rather than being baked into `@GET`/`@POST`, because
 * annotation arguments are compile-time constants and these are driven by the
 * `api_config` Remote Config block. Pass a **relative** URL from [ApiConfig] —
 * Retrofit resolves it against the client's base URL.
 */
interface LookupApi {

    @GET
    suspend fun checkPhoneNumber(
        @Url url: String,
        @Query("phone") phone: String,
        @Query("hash_key") hashKey: String,
        @Header("Authorization") token: String
    ): Response<PhonePayload>

    @Multipart
    @POST
    fun saveContact(
        @Url url: String,
        @Query("hash_key") apiKey: String,
        @Part file: MultipartBody.Part,
    ): Call<JsonObject>
}
