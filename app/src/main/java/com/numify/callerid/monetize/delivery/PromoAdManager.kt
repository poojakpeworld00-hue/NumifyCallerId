package com.numify.callerid.monetize.delivery
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.numify.callerid.monetize.model.PromoBanner
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.lookup.R

class PromoAdManager {

    companion object {
        private var cachedAds: List<PromoBanner>? = null
        
        fun clearCache() {
            cachedAds = null
        }
    }

    enum class CustomAdType {
        BIG_NATIVE,
        MID_NATIVE,
        BANNER,
        FULLSCREEN_NATIVE
    }

    fun loadPromoAd(
        context: Context,
        container: FrameLayout,
        type: CustomAdType,
        backupImage: ImageView? = null,
        backupLayout: LinearLayout? = null,
        onFail: (() -> Unit)? = null
    ) {
        if (context.isHostActivityDead()) {
            fallback(container, backupImage, backupLayout, onFail)
            return
        }

        val pref = AdPreferenceStore.getInstance(context)

        // Custom Ads OFF → fallback
        if (!pref.getBoolean("IsCustomADS")) {
            fallback(container, backupImage, backupLayout, onFail)
            return
        }

        val ads: List<PromoBanner> = if (cachedAds != null) {
            cachedAds!!
        } else {
            val json = pref.getString("CUSTOM_ADS", null)
            if (json.isNullOrEmpty()) {
                fallback(container, backupImage, backupLayout, onFail)
                return
            }
            try {
                val parsed: List<PromoBanner> = Gson().fromJson(json, object : TypeToken<List<PromoBanner>>() {}.type)
                cachedAds = parsed
                parsed
            } catch (e: Exception) {
                fallback(container, backupImage, backupLayout, onFail)
                return
            }
        }

        if (ads.isEmpty()) {
            fallback(container, backupImage, backupLayout, onFail)
            return
        }

        val ad = ads.random()

        val layoutId = when (type) {
            CustomAdType.BIG_NATIVE -> R.layout.promo_native_one
            CustomAdType.MID_NATIVE -> R.layout.promo_native_two
            CustomAdType.BANNER -> R.layout.promo_banner_ad
            CustomAdType.FULLSCREEN_NATIVE -> R.layout.promo_native_full
        }
        val view = try {
            LayoutInflater.from(container.context).inflate(layoutId, container, false)
        } catch (e: Exception) {
            fallback(container, backupImage, backupLayout, onFail)
            return
        }

        applyTheme(context, view, pref)
        bindData( view, ad)
        setClickListeners(view)

        // Final UI setup (defer to next frame to avoid NestedScrollView layout issues)
        container.post {
            if (context.isHostActivityDead()) {
                container.visibility = View.GONE
                return@post
            }

            container.findFocus()?.clearFocus()
            container.removeAllViews()
            container.addView(view)
            container.visibility = View.VISIBLE

            backupImage?.visibility = View.GONE
            backupLayout?.visibility = View.GONE
        }
    }

    // --------------------------------------------------------
    // THEME APPLY
    // --------------------------------------------------------
    private fun applyTheme(context: Context, view: View, pref: AdPreferenceStore) {
        try {

            val bgColor = color(pref.getString("NativeBgColor"), "#FFFFFF")
            val btnColor = color(pref.getString("NativebtnColor"), "#000000")
            val txtColor = color(pref.getString("NativetxtColor"), "#000000")
            val btnTxtColor = color(pref.getString("NativebtntxtColor"), "#FFFFFF")

            val main = view.findViewById<LinearLayout?>(R.id.mainlnr)
            main?.backgroundTintList = ColorStateList.valueOf(bgColor)

            val button = view.findViewById<TextView?>(R.id.btntext)
            button?.backgroundTintList = ColorStateList.valueOf(btnColor)
            button?.setTextColor(btnTxtColor)

            val bannerButton = view.findViewById<TextView?>(R.id.only_banner_button)
            bannerButton?.backgroundTintList = ColorStateList.valueOf(btnColor)
            bannerButton?.setTextColor(btnTxtColor)

            view.findViewById<TextView?>(R.id.titileText)?.setTextColor(txtColor)
            view.findViewById<TextView?>(R.id.Texttext)?.setTextColor(txtColor)

            view.findViewById<TextView?>(R.id.only_banner_title)?.setTextColor(txtColor)
            view.findViewById<TextView?>(R.id.only_banner_desc)?.setTextColor(txtColor)

        } catch (_: Exception) {
            // Silent fail → do not crash
        }
    }

    // --------------------------------------------------------
    // DATA BINDING
    // --------------------------------------------------------
    private fun bindData(view: View, ad: PromoBanner) {
        // Big / Mid Native
        view.findViewById<TextView?>(R.id.titileText)?.text = ad.title
        view.findViewById<TextView?>(R.id.Texttext)?.text = ad.description
        view.findViewById<TextView?>(R.id.btntext)?.text = ad.buttonText

        // Banner Native
        view.findViewById<TextView?>(R.id.only_banner_title)?.text = ad.title
        view.findViewById<TextView?>(R.id.only_banner_desc)?.text = ad.description
        view.findViewById<TextView?>(R.id.only_banner_button)?.text = ad.buttonText

        // Images
        view.findViewById<ImageView?>(R.id.gif_image)?.let { bindGlideSafe(it, ad.icon) }
        view.findViewById<ImageView?>(R.id.only_banner_logo)?.let { bindGlideSafe(it, ad.icon) }
        view.findViewById<ImageView?>(R.id.custom_native)?.let { bindGlideSafe(it, ad.bannerImage) }
    }

    private fun Context.findHostActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun Context.isHostActivityDead(): Boolean {
        val act = this as? Activity ?: findHostActivity() ?: return false
        if (act.isFinishing) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && act.isDestroyed
    }

    private fun bindGlideSafe(imageView: ImageView, url: String?) {
        if (url.isNullOrEmpty()) return
        if (imageView.context.isHostActivityDead()) return
        try {
            Glide.with(imageView).load(url).into(imageView)
        } catch (_: IllegalArgumentException) {
        }
    }

    // --------------------------------------------------------
    // CLICK ACTIONS
    // --------------------------------------------------------
    private fun setClickListeners(view: View) {
        val click = View.OnClickListener {
            Directlink(view.context)
        }

        view.setOnClickListener(click)
        view.findViewById<View?>(R.id.btntext)?.setOnClickListener(click)
        view.findViewById<View?>(R.id.only_banner_button)?.setOnClickListener(click)
    }

    // --------------------------------------------------------
    // FALLBACK
    // --------------------------------------------------------
    private fun fallback(
        container: FrameLayout,
        img: ImageView?,
        layout: LinearLayout?,
        onFail: (() -> Unit)?
    ) {
        container.post {
            if (container.context.isHostActivityDead()) {
                container.visibility = View.GONE
                return@post
            }

            container.findFocus()?.clearFocus()
            container.removeAllViews()
            container.visibility = View.GONE

            img?.visibility = View.VISIBLE
            layout?.visibility = View.VISIBLE

            onFail?.invoke()
        }
    }

    // Safe color parser
    private fun color(value: String?, default: String): Int {
        return try {
            Color.parseColor(value ?: default)
        } catch (_: Exception) {
            Color.parseColor(default)
        }
    }
}
