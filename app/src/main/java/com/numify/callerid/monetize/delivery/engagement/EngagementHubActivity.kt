package com.numify.callerid.monetize.delivery.engagement

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import io.lighthouse.push.extended.HandleOptions
import io.lighthouse.push.extended.LightHouseRichPush
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.databinding.ScreenCallBackScreenBinding
import com.numify.callerid.lookup.common.triggerClick
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.BottomSheetNativeAds
import com.numify.callerid.monetize.delivery.SystemDialogHelper
import com.numify.callerid.monetize.delivery.getHD_VBC_Type
import com.numify.callerid.monetize.delivery.engagement.sections.AnnouncementFragment
import com.numify.callerid.monetize.delivery.engagement.sections.CallTimelineFragment
import com.numify.callerid.monetize.delivery.engagement.sections.AlertFeedFragment
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.ContactRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-call screen shown after an incoming/outgoing/missed call. Hosts three
 * tabs (Message, AlertEntry, WhatsApp) and an ad slot. Extends the project's
 * [BaseActivity] so it picks up the standard DataBinding + locale/theme
 * plumbing.
 *
 * Note: the consent + Mobile Ads init that used to live here (via the
 * `getData(...)` call inherited from `AdAwareActivity`) is expected to run
 * once during app startup. This screen only triggers ad rendering, not SDK
 * initialization.
 */
class EngagementHubActivity : BaseActivity<ScreenCallBackScreenBinding>() {

    override val layoutId: Int = R.layout.screen_call_back_screen
    private val systemDialogHelper by lazy {
        SystemDialogHelper(this) {
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun initView() {
        lifecycle.addObserver(systemDialogHelper)
        if (handleRichPushIfQueued()) return
        AppOpenAdManager.callbackshow = true
        // Android 15+/16 forces edge-to-edge (no opt-out at targetSdk 35/36), so
        // the top bar and bottom ad would draw under the status/navigation bars.
        // Pad the root by the system-bar insets to keep all content visible, and
        // fold in the IME inset so the bottom message input rides above the
        // keyboard instead of being hidden behind it (with or without an ad).
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        if (getHD_VBC_Type() == "n") {
            Log.w("987654321", "Native called")
            BottomSheetNativeAds().BS_showBigNative(this, binding.adContainerVw)
        } else {
            Log.w("987654321", "Banner called")
            BottomSheetNativeAds().renderBannerAd(this, binding.adContainerVw)
        }

        val phone = intent.getStringExtra("phone") ?: "Private Number"
        val startTimeMillis = intent.getLongExtra("start_time", 0L)
        val endTimeMillis = intent.getLongExtra("end_time", 0L)
        val callType = intent.getStringExtra("call_type") ?: "UNKNOWN"

        // Resolve the contact name for this number; fall back to the raw number.
        val callerName = if (phone.isNotBlank() && !phone.equals("Private Number", ignoreCase = true))
            ContactRepository(this).lookupNameByNumber(phone)?.takeIf { it.isNotBlank() }
        else null
        binding.txtCallerNameVw.text = callerName ?: phone
        binding.txtCallTypeVw.text = getCallTypeText(callType)

        // Duration — format as MM:SS
        val durationSec = if (startTimeMillis > 0 && endTimeMillis > startTimeMillis)
            ((endTimeMillis - startTimeMillis) / 1000).toInt() else 0
        val minutes = durationSec / 60
        val seconds = durationSec % 60
        binding.txtDurationVw.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        // Time — show end time if available
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        binding.txtTimeVw.text = timeFormat.format(
            if (endTimeMillis > 0) Date(endTimeMillis) else Date()
        )

        // Recent-call list is the default ("first") tab of the post-call screen.
        supportFragmentManager.beginTransaction()
            .replace(R.id.frag_container, CallTimelineFragment())
            .commit()
        selectTab(binding.picRecent, getAllTabs())

        binding.callIconVw.triggerClick {
            val number = callerNumber
            if (!number.isNullOrBlank()) callNumber(number)
            else Toast.makeText(this, R.string.toast_no_number, Toast.LENGTH_SHORT).show()
        }

        setupClickListeners()

        onBackPressedDispatcher.addCallback(this) {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.frag_container)
            when (currentFragment) {
                // The recents list is "home" — back from it closes the screen.
                is CallTimelineFragment -> finish()
                else -> {
                    if (!isFinishing && !isDestroyed) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.frag_container, CallTimelineFragment())
                            .commitAllowingStateLoss()
                    }
                    selectTab(binding.picRecent, getAllTabs())
                }
            }
        }
    }

    private fun getCallTypeText(type: String): String = when (type.uppercase()) {
        "INCOMING" -> getString(R.string.incoming_call)
        "OUTGOING" -> getString(R.string.outgoing_call)
        "MISSED" -> getString(R.string.missed_call)
        else -> type
    }

    private fun getAllTabs() = listOf(
        binding.picRecent, binding.picMes, binding.picReminder, binding.picWhatsapp
    )

    /** The caller's number from the launching intent, or null for private/unknown. */
    private val callerNumber: String? by lazy {
        intent.getStringExtra("phone")?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("Private Number", ignoreCase = true) }
    }

    /**
     * Places a direct outgoing call via the shared CALL_PHONE flow: requests the
     * permission if needed, dials directly (ACTION_CALL) once granted, and falls
     * back to the dialer only if it isn't.
     */
    private fun callNumber(number: String) = placeCall(number)

    private fun setupClickListeners() {
        val allTabs = getAllTabs()

        val fragmentTabs = listOf(
            binding.picRecent to { CallTimelineFragment() as Fragment },
            binding.picMes to { AnnouncementFragment.newInstance(callerNumber) as Fragment },
            binding.picReminder to { AlertFeedFragment() as Fragment }
        )

        fragmentTabs.forEach { (tab, fragmentFactory) ->
            tab.triggerClick {
                if (isFinishing || isDestroyed) return@triggerClick
                selectTab(tab, allTabs)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frag_container, fragmentFactory())
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
        }

        // WhatsApp tab — opens a chat directly with the caller's number.
        binding.picWhatsapp.triggerClick {
            if (isFinishing || isDestroyed) return@triggerClick
            selectTab(binding.picWhatsapp, allTabs)
            openWhatsApp(callerNumber)
        }
    }

    /**
     * Opens a WhatsApp chat with [number] (digits only). Tries WhatsApp then
     * WhatsApp Business, then the wa.me web redirect. Falls back to WhatsApp's
     * main screen when the number is private/unknown.
     */
    private fun openWhatsApp(number: String?) {
        val digits = number?.filter { it.isDigit() }
        if (digits.isNullOrBlank()) {
            openWhatsAppApp()
            return
        }
        val uri = Uri.parse("https://wa.me/$digits")
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val ok = runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)); true
            }.getOrDefault(false)
            if (ok) return
        }
        // No WhatsApp app handled it directly — browser redirect, else main screen.
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri)); true
        }.getOrDefault(false)
        if (!opened) openWhatsAppApp()
    }

    /**
     * Launches WhatsApp's main screen without targeting any contact. Tries
     * `com.whatsapp` first, then `com.whatsapp.w4b` (WhatsApp Business). If
     * neither is installed, surfaces a one-line toast.
     */
    private fun openWhatsAppApp() {
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(launchIntent)
                return
            } catch (e: Exception) {
                Log.e("EngagementHubActivity", "Failed to launch $pkg", e)
                // try next package
            }
        }
        Toast.makeText(this, R.string.toast_no_whatsapp, Toast.LENGTH_SHORT).show()
    }

    private val tabIcons by lazy {
        mapOf(
            binding.picRecent to Pair(
                R.drawable.cbk_recent_selected,
                R.drawable.cbk_recent_unselected
            ),
            binding.picMes to Pair(
                R.drawable.cbk_message_selected,
                R.drawable.cbk_message_unselected
            ),
            binding.picReminder to Pair(
                R.drawable.cbk_reminder_selected,
                R.drawable.cbk_reminder_unselected
            ),
            binding.picWhatsapp to Pair(
                R.drawable.cbk_wa_selected,
                R.drawable.cbk_wa_unselected
            )
        )
    }

    private val tabImageViews by lazy {
        mapOf(
            binding.picRecent to binding.picTabRecent,
            binding.picMes to binding.picTabMessage,
            binding.picReminder to binding.picTabReminder,
            binding.picWhatsapp to binding.picTabWhatsapp
        )
    }

    private fun selectTab(selected: android.view.View, allTabs: List<android.view.View>) {
        val selectedBg = ColorStateList.valueOf(Color.parseColor("#FFE7DF"))

        allTabs.forEach { tab ->
            val isSelected = tab == selected
            tab.backgroundTintList = if (isSelected) selectedBg else null
            tab.alpha = 1.0f

            val icons = tabIcons[tab]
            val iv = tabImageViews[tab]
            if (icons != null && iv != null) {
                iv.setImageResource(if (isSelected) icons.first else icons.second)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        NotificationManagerCompat.from(this).cancelAll()
    }

    override fun onPause() {
        super.onPause()
        isActive = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRichPushIfQueued()
    }

    /**
     * Mounts the LightHouse rich-push ad overlay when this launch came from a
     * rich push. Returns true when handled. Shared by the cold path (initView)
     * and the warm path (onNewIntent).
     */
    private fun handleRichPushIfQueued(): Boolean {
        if (!LightHouseRichPush.shouldHandle(intent)) return false
        LightHouseRichPush.handle(
            activity = this,
            options = HandleOptions(
                finishHostOnClose = true,
                hideViews = listOf(binding.root),
            ),
        )
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        AppOpenAdManager.callbackshow = false
    }

    companion object {
        /**
         * True while this post-call screen is in the foreground — CallStateReceiver
         * checks it to suppress a duplicate post-call notification (B2).
         */
        @Volatile
        var isActive = false
    }
}
