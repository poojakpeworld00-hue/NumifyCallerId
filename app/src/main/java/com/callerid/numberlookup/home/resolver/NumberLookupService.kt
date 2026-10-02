package com.callerid.numberlookup.home.resolver

import com.google.gson.JsonObject
import com.callerid.numberlookup.home.entity.LookupPayload
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.DELETE
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

    /**
     * `POST /android/upload/contacts` — multipart: the CSV as `file`, and this
     * install's `deviceId` as a text field. The server rejects any other text
     * field, so nothing else goes in the form.
     */
    @Multipart
    @POST
    fun uploadContacts(
        @Url url: String,
        @Part file: MultipartBody.Part,
        @Part("deviceId") deviceId: RequestBody,
    ): Call<JsonObject>

    /**
     * `DELETE /android/upload/contacts?deviceId=…` — removes everything this
     * install uploaded; 200 with `deleted` (0 when nothing was stored). Any extra
     * query parameter is a 400, so this takes the device id and nothing else.
     */
    @DELETE
    fun deleteUploadedContacts(
        @Url url: String,
        @Query("deviceId") deviceId: String,
    ): Call<JsonObject>
}
