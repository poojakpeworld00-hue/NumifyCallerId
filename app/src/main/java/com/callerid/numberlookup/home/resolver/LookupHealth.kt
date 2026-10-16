package com.callerid.numberlookup.home.resolver

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.monetize.delivery.isNetworkAvailable
import com.callerid.numberlookup.home.entity.LookupPayload
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why a caller-ID lookup did or did not produce an answer.
 *
 * The screens used to get either data or an empty list, so "nobody has named
 * this number" and "the service is down" both read as "Unknown caller". This
 * keeps them apart so each screen can say which it is.
 */
enum class LookupStatus {
    /** The API answered. Whether it had a name is [LookupResult.hasName]. */
    OK,
    /** No network on the device - nothing was sent. */
    OFFLINE,
    /** Timeout, connection error or a 5xx - worth trying again later. */
    SERVER_ERROR,
    /** 429 - too many searches. */
    RATE_LIMITED,
    /**
     * 401 / 403, any other unexpected code, or no API key built in: a problem on
     * our side the user cannot fix (the current invalid-key case).
     */
    UNAVAILABLE,
}

/** A lookup's answer ([payload], when [status] is OK) and why it ended as it did. */
data class LookupResult(val payload: LookupPayload?, val status: LookupStatus, val code: Int? = null) {
    val hasName: Boolean get() = payload?.primaryName() != null
}

/**
 * The one place a caller-ID lookup is made and its outcome judged, shared by the
 * Lookup screen, the incoming-call card and the post-call screen.
 *
 * - One automatic retry after [RETRY_DELAY_MS] for a timeout or a 5xx.
 * - Every failure is logged as `lookup_api_error` (`status`, `code`, `screen`),
 *   and 401 / 403 also go to Crashlytics: a broken key shows up on the
 *   dashboard before users have to report it.
 * - Consecutive service failures (not offline) are counted per session; the
 *   Lookup screen shows the outage dialog after [OUTAGE_THRESHOLD] of them, at
 *   most once a day.
 * - `api_config.outage_message` in Remote Config, when set, replaces the
 *   service-failure copy, so a known outage can be explained without a release.
 */
object LookupHealth {

    private const val TAG = "LookupHealth"
    private const val RETRY_DELAY_MS = 2_000L
    private const val OUTAGE_THRESHOLD = 3
    private const val PREFS = "lookup_health"
    private const val KEY_DIALOG_DAY = "outage_dialog_day"

    /** Service failures in a row this session; reset by any successful answer. */
    @Volatile
    private var consecutiveFailures = 0

    /**
     * Looks [phone] up and classifies the outcome. [screen] tags the analytics
     * event: `lookup`, `caller_card` or `post_call`.
     */
    suspend fun lookup(context: Context, phone: String, screen: String): LookupResult {
        val result = attempt(context, phone).let { first ->
            if (first.status == LookupStatus.SERVER_ERROR) {
                Log.d(TAG, "lookup: server error (${first.code}) — retrying once")
                delay(RETRY_DELAY_MS)
                attempt(context, phone)
            } else first
        }
        record(context, result, screen)
        return result
    }

    private suspend fun attempt(context: Context, phone: String): LookupResult {
        if (CredentialProvider.CONTACTS_API_KEY.isBlank()) {
            return LookupResult(null, LookupStatus.UNAVAILABLE)
        }
        if (!isNetworkAvailableSafe(context)) return LookupResult(null, LookupStatus.OFFLINE)
        return try {
            val response = NetworkClientFactory.api.checkPhoneNumber(
                url = EndpointConfig.similarPhonePath(context),
                phone = phone
            )
            val code = response.code()
            when {
                response.isSuccessful -> LookupResult(response.body(), LookupStatus.OK, code)
                code == 429 -> LookupResult(null, LookupStatus.RATE_LIMITED, code)
                code >= 500 -> LookupResult(null, LookupStatus.SERVER_ERROR, code)
                else -> LookupResult(null, LookupStatus.UNAVAILABLE, code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The connection dropped or timed out; offline was ruled out above,
            // unless it went down mid-request.
            val status = if (isNetworkAvailableSafe(context)) LookupStatus.SERVER_ERROR else LookupStatus.OFFLINE
            LookupResult(null, status)
        } catch (e: Exception) {
            Log.e(TAG, "lookup failed", e)
            LookupResult(null, LookupStatus.UNAVAILABLE)
        }
    }

    private fun record(context: Context, result: LookupResult, screen: String) {
        when (result.status) {
            LookupStatus.OK -> {
                consecutiveFailures = 0
                return
            }
            LookupStatus.OFFLINE -> Unit // the user knows; not a service problem
            else -> consecutiveFailures++
        }
        val code = result.code?.toString() ?: if (result.status == LookupStatus.OFFLINE) "offline" else "none"
        Log.w(TAG, "lookup_api_error status=${result.status} code=$code screen=$screen")
        runCatching {
            context.recordEvent(
                "lookup_api_error",
                mapOf("status" to result.status.name.lowercase(), "code" to code, "screen" to screen)
            )
        }
        if (result.status == LookupStatus.UNAVAILABLE) {
            runCatching {
                FirebaseCrashlytics.getInstance().recordException(
                    IllegalStateException("Caller-ID lookup unavailable: HTTP $code on $screen")
                )
            }
        }
    }

    /** Status line for the Lookup card: null when there is nothing to say. */
    fun statusMessage(context: Context, result: LookupResult): String? = when (result.status) {
        LookupStatus.OK -> if (result.hasName) null else context.getString(R.string.lookup_status_no_match)
        LookupStatus.OFFLINE -> context.getString(R.string.lookup_status_offline)
        LookupStatus.RATE_LIMITED -> context.getString(R.string.lookup_status_rate_limited)
        LookupStatus.SERVER_ERROR -> outageMessage(context) ?: context.getString(R.string.lookup_status_server)
        LookupStatus.UNAVAILABLE -> outageMessage(context) ?: context.getString(R.string.lookup_status_unavailable)
    }

    /** Whether the line is a problem (warning colour) rather than plain information. */
    fun isProblem(status: LookupStatus) = status != LookupStatus.OK

    /** Short label for the incoming-call and post-call cards; null when all is well. */
    @StringRes
    fun cardLabel(status: LookupStatus): Int? = when (status) {
        LookupStatus.OK -> null
        LookupStatus.OFFLINE -> R.string.caller_label_offline
        else -> R.string.caller_label_unavailable
    }

    /**
     * True once the service has failed [OUTAGE_THRESHOLD] times in a row this
     * session and the dialog has not been shown today. Marks it shown.
     */
    fun takeOutageDialog(context: Context): Boolean {
        if (consecutiveFailures < OUTAGE_THRESHOLD) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (prefs.getString(KEY_DIALOG_DAY, null) == today) return false
        prefs.edit().putString(KEY_DIALOG_DAY, today).apply()
        return true
    }

    /** The outage dialog's body: the Remote Config message when set, else the default. */
    fun outageDialogBody(context: Context): String =
        outageMessage(context) ?: context.getString(R.string.lookup_outage_body)

    private fun isNetworkAvailableSafe(context: Context): Boolean =
        runCatching { isNetworkAvailable(context) }.getOrDefault(true)

    /** `api_config.outage_message`, when Remote Config carries a non-blank one. */
    private fun outageMessage(context: Context): String? = runCatching {
        JSONObject(AdPreferenceStore.getInstance(context).getString("api_config", "{}") ?: "{}")
            .optString("outage_message").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
