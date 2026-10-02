package com.callerid.numberlookup.home.resolver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.callerid.numberlookup.home.BuildConfig
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.repository.ContactPhone
import com.callerid.numberlookup.home.repository.ContactRepository
import com.callerid.numberlookup.home.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * The device contacts on the contact-saver server: uploaded once, under this
 * install's device id, and deletable on request.
 *
 * Upload is `POST /android/upload/contacts`: a CSV posted as the `file` part of
 * a multipart request, with [DeviceIdentity] as the `deviceId` field. It used to
 * go to the older `POST /upload/contacts`, which takes no device id - rows sent
 * there can never be deleted, which is why the delete needed this endpoint.
 *
 * Delete is `DELETE` on the same path with the same device id; see [deleteUploaded].
 * Once the user has deleted, [SettingsRepository.isContactsUploadOptedOut] keeps
 * this from uploading again.
 */
object ContactUploader {

    private const val TAG = "ContactUploader"

    /** Lowercase `.csv`: the server rejects `contacts.CSV`. */
    private const val FILE_NAME = "contacts.csv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var inProgress = false

    fun uploadOnceIfNeeded(context: Context) {
        // Only upload in release builds.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Skipping upload in debug build")
            return
        }
        val app = context.applicationContext
        val prefs = SettingsRepository(app)
        // Nothing is de-duplicated server-side, so this runs once, not per launch.
        if (prefs.isDeviceContactsUploaded || prefs.isContactsUploadOptedOut || inProgress) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Without a key the request goes out unauthenticated and comes back 401,
        // which would burn the one upload attempt and read as a server fault.
        if (CredentialProvider.CONTACTS_API_KEY.isBlank()) {
            Log.w(TAG, "No API key configured — skipping upload")
            return
        }

        inProgress = true
        scope.launch {
            var file: File? = null
            try {
                // One row per number, not per contact: someone with a mobile and
                // a work number is two rows, as the API asks.
                val phones = ContactRepository(app).phoneEntries()
                    .filter { !it.name.isNullOrBlank() && it.number.isNotBlank() }
                if (phones.isEmpty()) {
                    Log.w(TAG, "No contacts to upload")
                    return@launch
                }

                file = File(app.cacheDir, FILE_NAME).also { writeCsv(phones, it) }
                Log.d(TAG, "Uploading ${phones.size} numbers (${file.length()} bytes)…")

                val part = MultipartBody.Part.createFormData(
                    "file", file.name, file.asRequestBody(CSV_MEDIA_TYPE)
                )
                val deviceId = DeviceIdentity.id(app).toRequestBody(TEXT_MEDIA_TYPE)
                val response = NetworkClientFactory.api
                    .uploadContacts(EndpointConfig.deviceContactsPath(app), part, deviceId)
                    .execute()

                if (response.isSuccessful) {
                    prefs.isDeviceContactsUploaded = true
                    Log.i(TAG, "Upload SUCCESS (${response.code()}): ${response.body()}")
                } else {
                    val err = runCatching { response.errorBody()?.string() }.getOrNull()
                    Log.e(TAG, "Upload FAILED (${response.code()}): $err")
                }
            } catch (e: Exception) {
                // Network/IO failure — leave the flag unset so it retries next time.
                Log.e(TAG, "Upload ERROR: ${e.message}", e)
            } finally {
                // The whole address book sat in cacheDir as plain text. Android
                // will evict it eventually, but "eventually" is the wrong lifetime
                // for that, so it goes as soon as the request is done.
                runCatching { file?.delete() }
                inProgress = false
            }
        }
    }

    /**
     * Deletes everything this install uploaded (`DELETE /android/upload/contacts`)
     * and stops any further upload.
     *
     * Returns how many rows the server removed, or a failure. Nothing uploaded
     * is not a failure: the server answers 200 with 0, so a retry after a
     * timeout is safe. On success the opt-out is set before returning, so a
     * launch straight after cannot send the address book back.
     */
    suspend fun deleteUploaded(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching {
            check(CredentialProvider.CONTACTS_API_KEY.isNotBlank()) { "No API key configured" }
            val response = NetworkClientFactory.api
                .deleteUploadedContacts(EndpointConfig.deviceContactsPath(app), DeviceIdentity.id(app))
                .execute()
            if (!response.isSuccessful) {
                val err = runCatching { response.errorBody()?.string() }.getOrNull()
                error("Delete FAILED (${response.code()}): $err")
            }
            val deleted = response.body()?.get("deleted")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            SettingsRepository(app).apply {
                isContactsUploadOptedOut = true
                isDeviceContactsUploaded = false
            }
            Log.i(TAG, "Delete SUCCESS: $deleted rows")
            deleted
        }.onFailure { Log.e(TAG, "Delete ERROR: ${it.message}", it) }
    }

    /**
     * The numbers as the API's CSV, header row first, written straight to
     * [file] rather than built up as one string in memory.
     *
     * Every field is quoted, inner quotes doubled: contact names routinely
     * contain commas, quotes and newlines, any one of which would otherwise
     * shift every later column by one and corrupt the rest of the file.
     */
    internal fun writeCsv(phones: List<ContactPhone>, file: File) {
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("phoneNumber,displayName\n")
            phones.forEach { phone ->
                out.write(csvField(phone.number))
                out.write(",")
                out.write(csvField(phone.name))
                out.write("\n")
            }
        }
    }

    private fun csvField(value: String?): String =
        "\"" + value.orEmpty().replace("\"", "\"\"") + "\""

    private val CSV_MEDIA_TYPE = "text/csv".toMediaTypeOrNull()
    private val TEXT_MEDIA_TYPE = "text/plain".toMediaTypeOrNull()
}
