package com.numify.callerid.numberlookup.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.numify.callerid.numberlookup.BuildConfig
import androidx.core.content.ContextCompat
import com.numify.callerid.numberlookup.store.PhonebookSource
import com.numify.callerid.numberlookup.store.SettingsVault
import com.numify.callerid.numberlookup.schema.toUploadList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileWriter

/**
 * Uploads the device contacts to the server exactly once (first time the
 * contacts permission is available). Guarded by [SettingsVault.isContactsUploaded].
 */
object ContactSyncer {

    private const val TAG = "ContactSyncer"
    private const val FILE_NAME = "contacts_upload.json"
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
        val prefs = SettingsVault(app)
        if (prefs.isContactsUploaded || inProgress) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        inProgress = true
        scope.launch {
            try {
                val contacts = PhonebookSource(app).getContacts()
                if (contacts.isEmpty()) {
                    Log.w(TAG, "No contacts to upload")
                    return@launch
                }

                val json = Gson().toJson(contacts.toUploadList())
                val file = File(app.cacheDir, FILE_NAME)
                FileWriter(file).use { it.write(json) }
                Log.d(TAG, "Uploading ${contacts.size} contacts (${file.length()} bytes)…")

                val part = MultipartBody.Part.createFormData(
                    "contact_file", file.name, file.asRequestBody("/".toMediaTypeOrNull())
                )
                val response = HttpClientFactory.api
                    .saveContact(ApiConfig.saveContactPath(app), ApiSecrets.API_HASH, part)
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
                inProgress = false
            }
        }
    }
}
