package com.numify.callerid.monetize.strategy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import com.google.android.gms.ads.AdValue
import com.google.firebase.analytics.FirebaseAnalytics
import  com.numify.callerid.lookup.BuildConfig

const val TAG_EVENT = "AdEvents"

/**
 * Universal Ad Revenue Tracker
 * Works for: Interstitial, AppOpen, Banner, Native, Rewarded, Rewarded-Interstitial
 */
object RevenueMonitor {

    private const val TAG = "RevenueMonitor"

    /** Real revenue handler */
    fun reportPaidEvent(context: Context, adValue: AdValue?) {
        if (adValue == null) {
            Log.e(TAG, "🔥 REAL PAID EVENT → $adValue ")
            return
        }
        val revenue = adValue.valueMicros / 1_000_000.0
        val currency = adValue.currencyCode ?: "USD"

        context.logAdRevenue(revenue, currency)

        Log.e(TAG, "🔥 REAL PAID EVENT → $revenue $currency (${adValue.valueMicros})")
    }

    /** Debug/Test mode revenue simulation */
    fun logDebugRevenue(context: Context) {
        if (!BuildConfig.DEBUG) return   // 🚫 safety

        val revenue = 1.00
        val currency = "USD"

        context.logAdRevenue(revenue, currency)

        Log.e(TAG, "🧪 DEBUG SIM → $revenue $currency")
    }

}

/* -------------------------------------------------------------
   EXTENSION FUNCTIONS (Correct placement)
--------------------------------------------------------------*/

/** Log general key events */
fun Context.recordEvent(key: String) {
    val bundle = Bundle().apply { putString(key, key) }

    if (isDebuggable()) {
        Log.w(TAG_EVENT, "📌 KeyEvent (Debug): $key")
    } else {
        FirebaseAnalytics.getInstance(this)
            .logEvent(key, bundle)
    }
}

/**
 * Logs a runtime-permission outcome as `Permission_<NAME>_Allow` / `_Deny`,
 * e.g. `Permission_READ_CALL_LOG_Allow`. [permission] is a full
 * `android.permission.*` string; only the short name is used in the event.
 */
fun Context.recordPermissionOutcome(permission: String, granted: Boolean) {
    val shortName = permission.substringAfterLast('.')
    recordEvent("Permission_${shortName}_${if (granted) "Allow" else "Deny"}")
}

/** Check debug mode */
fun Context.isDebuggable(): Boolean {
    return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/** Main Firebase revenue logger */
fun Context.logAdRevenue(revenue: Double, currency: String) {
    val params = Bundle().apply {
        putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob") // correct for AdMob
        putString(FirebaseAnalytics.Param.CURRENCY, currency)
        putDouble(FirebaseAnalytics.Param.VALUE, revenue)
    }

    if (BuildConfig.DEBUG) {
        Log.d("AdPaid", "🧪 DEBUG AD_IMPRESSION → $params")
    } else {
        FirebaseAnalytics.getInstance(this)
            .logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, params)
    }


}
