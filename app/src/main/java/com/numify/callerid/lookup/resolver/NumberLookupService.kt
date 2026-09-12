package com.numify.callerid.lookup.resolver

import com.google.gson.JsonObject
import com.numify.callerid.lookup.entity.LookupPayload
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * The contact-saver API.
 *
 * Paths arrive as `@Url` instead of being baked into `@GET` or `@POST`, because
 * annotation arguments have to be compile-time constants and these are driven by
 * the `api_config` Remote Config block. Pass a **relative** URL from
 * [EndpointConfig] and Retrofit resolves it against the client's base URL.
 *
 * Neither call carries its credential in its signature: [ApiKeyInterceptor]
 * attaches `x-api-key` to every request bound for the API host. Keeping it out of
 * the interface is what stops the key from drifting back into a query parameter
 * the next time an endpoint is added.
 */
interface NumberLookupService {

    /** `GET /similar-phone-number?phone=…` */
    @GET
    suspend fun checkPhoneNumber(
        @Url url: String,
        @Query("phone") phone: String,
    ): Response<LookupPayload>

    /** `POST /upload/contacts` — multipart, one CSV part named `file`. */
    @Multipart
    @POST
    fun uploadContacts(
        @Url url: String,
        @Part file: MultipartBody.Part,
    ): Call<JsonObject>
}
