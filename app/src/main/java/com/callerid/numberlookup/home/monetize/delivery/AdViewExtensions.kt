package com.callerid.numberlookup.home.monetize.delivery

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.viewbinding.ViewBinding
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.delivery.fullpage.TransitionInterstitialAd
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.DialogAppRedirectBinding
import kotlin.apply
import kotlin.let
import kotlin.text.isNullOrEmpty

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetworkInfo
    return network != null && network.isConnected
}

fun View.visible() {
    this.visibility = View.VISIBLE
}

fun View.gone() {
    this.visibility = View.GONE
}

fun View.invisible() {
    this.visibility = View.GONE
}

fun Directlink(context: Context?) {

    Log.e("===>","bhbjhb")
    val activity = context as? Activity ?: return

    // Activity lifecycle safety
    if (activity.isFinishing || activity.isDestroyed) return

    val adsPref = AdPreferenceStore.getInstance(activity)
    if (!adsPref.getBoolean("IsCustomADS")) return

    val url = adsPref.getString("DirectLink")
    if (url.isNullOrBlank()) return

    val uri = try {
        Uri.parse(url).takeIf { it.scheme != null } ?: return
    } catch (e: Exception) {
        return
    }

    try {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setToolbarColor(ContextCompat.getColor(activity, R.color.black))
            .build()

        // ❌ DO NOT force Chrome
        customTabsIntent.launchUrl(activity, uri)

    } catch (e: Exception) {
        // Fallback to system browser
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
            activity.startActivity(browserIntent)
        } catch (_: Exception) {}
    }
}

/** helper to check if package is installed */
private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}


fun Activity.showAppRedirectPopup(onDismiss: (() -> Unit)? = null) {
    val appUrl = AdPreferenceStore(this).getString("In_App_Update_Link")
    if (appUrl.isNullOrEmpty()) return

    val dialogBinding = DialogAppRedirectBinding.inflate(layoutInflater)

    val dialog = showDialog(
        activity = this,
        viewBinding = dialogBinding,
        cancelable = false
    )

    dialogBinding.apply {
        buttonContinue.setOnClickListener {
            try {
                appUrl.toUri().let { uri ->
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        buttonCancel.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }
    }
}

fun showDialog(
    activity: Activity,
    viewBinding: ViewBinding,
    cancelable: Boolean = false,
    onDismiss: (() -> Unit)? = null
): Dialog {
    val dialog = Dialog(activity)
    dialog.setContentView(viewBinding.root)

    dialog.window?.apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // ⭐ KEEP DIM BEHIND (default 0.5f)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Optional: control dim amount
        setDimAmount(0.6f)
    }

    dialog.setCancelable(cancelable)
    dialog.setCanceledOnTouchOutside(cancelable)

    dialog.setOnDismissListener {
        onDismiss?.invoke()
    }

    if (!activity.isFinishing && !activity.isDestroyed) {
        dialog.show()
    }

    return dialog
}

fun Context.getLast_Result_HD_VBC_Type() = AdPreferenceStore.getInstance(this).result_HD_VBC_Type

fun Context.getHD_VBC_Type(): String {
    val type = AdPreferenceStore.getInstance(this).getString("HD_VBC_Type") ?: "nb"

    return when (type) {
        "b" -> "b"
        "n" -> "n"
        else -> {
            if (getLast_Result_HD_VBC_Type() == "b") "b" else "n"
        }
    }
}

fun Context.setLast_Result_HD_VBC_Type() {
    val pref = AdPreferenceStore.getInstance(this)
    pref.result_HD_VBC_Type = if (pref.result_HD_VBC_Type == "b") "n" else "b"
}

/**
 * Opens an Activity, optionally showing an interstitial ad before navigating.
 *
 * @param isNeedToClearTop  clear the back stack first (FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK)
 * @param isAdd             true shows the interstitial and then opens the Activity;
 *                          false opens it directly, with no ad
 * @param extras            optional Bundle handed to the target Activity
 * @param launcher          optional ActivityResultLauncher; supplying one skips the ad
 */
inline fun <reified T : Activity> Context.openActivity(
    isNeedToClearTop: Boolean = false,
    isAdd: Boolean = true,
    extras: Bundle? = null,
    launcher: ActivityResultLauncher<Intent>? = null
) {
    val intent = Intent(this, T::class.java)
    extras?.let { intent.putExtras(it) }

    if (isNeedToClearTop) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }

    val activity = this as? Activity

    // ActivityResultLauncher path — skip ad
    if (launcher != null) {
        launcher.launch(intent)
        return
    }

    // No-ad path (e.g. splash → next screen)
    if (!isAdd || activity == null) {
        startActivity(intent)
        return
    }

    // Show interstitial first, open activity in the close callback
    TransitionInterstitialAd().showInterstitial(activity) {
        activity.startActivity(intent)
    }
}