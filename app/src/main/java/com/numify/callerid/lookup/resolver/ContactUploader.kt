package com.numify.callerid.lookup.resolver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.numify.callerid.lookup.BuildConfig
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.repository.ContactRecord
import com.numify.callerid.lookup.repository.ContactRepository
import com.numify.callerid.lookup.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Uploads the device contacts to the server exactly once (first time the
 * contacts permission is available). Guarded by [SettingsRepository.isContactsUploaded].
 *
 * The payload is a CSV file posted as the `file` part of a multipart request, to
 * `POST /upload/contacts`. It used to be JSON under a `contact_file` part, for a
 * different server.
 */
object ContactUploader {

    private const val TAG = "ContactUploader"
    private const val FILE_NAME = "contacts_upload.csv"
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
        if (prefs.isContactsUploaded || inProgress) return
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
                val contacts = ContactRepository(app).getContacts()
                if (contacts.isEmpty()) {
                    Log.w(TAG, "No contacts to upload")
                    return@launch
                }

                file = File(app.cacheDir, FILE_NAME).apply {
                    writeText(toCsv(contacts))
                }
                Log.d(TAG, "Uploading ${contacts.size} contacts (${file.length()} bytes)…")

                val part = MultipartBody.Part.createFormData(
                    "file", file.name, file.asRequestBody(CSV_MEDIA_TYPE)
                )
                val response = NetworkClientFactory.api
                    .uploadContacts(EndpointConfig.uploadContactsPath(app), part)
                    .execute()

                if (response.isSuccessful) {
                    prefs.isContactsUploaded = true
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
     * The address book as CSV, header row first.
     *
     * Quoting is RFC 4180 rather than a bare `join(",")`: contact names routinely
     * contain commas, quotes and newlines, any one of which would shift every
     * later column by one and corrupt the rest of the file.
     */
    internal fun toCsv(contacts: List<ContactRecord>): String = buildString {
        append("name,phone\n")
        contacts.forEach { contact ->
            append(csvField(contact.name)).append(',')
            append(csvField(contact.detail)).append('\n')
        }
    }

    private fun csvField(value: String?): String {
        val text = value.orEmpty()
        if (text.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return text
        return "\"" + text.replace("\"", "\"\"") + "\""
    }

    private val CSV_MEDIA_TYPE = "text/csv".toMediaTypeOrNull()
}
