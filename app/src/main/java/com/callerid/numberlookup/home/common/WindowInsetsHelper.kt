package com.callerid.numberlookup.home.common

import android.util.Log
import com.callerid.numberlookup.home.BuildConfig

object WindowInsetsHelper {

    private const val TAG = "WindowInsetsHelper"

    /** Logs only in Debug mode */
    fun log(tag: String = TAG, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    /** Logs an error (still safe in Release) */
    fun error(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
        } else {
            // Optionally send to Crashlytics or analytics here
        }
    }

    /** Executes a block only in Debug builds */
    inline fun runDebug(block: () -> Unit) {
        if (BuildConfig.DEBUG) block()
    }
}
