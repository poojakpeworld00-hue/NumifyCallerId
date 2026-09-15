package com.contacts.callerid.number.lookup.monetize.delivery.engagement

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
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.databinding.ActivityCallReturnBinding
import com.contacts.callerid.number.lookup.common.triggerClick
import com.contacts.callerid.number.lookup.monetize.delivery.AppOpenAdManager
import com.contacts.callerid.number.lookup.monetize.delivery.BottomSheetNativeAds
import com.contacts.callerid.number.lookup.monetize.delivery.SystemDialogHelper
import com.contacts.callerid.number.lookup.monetize.delivery.getHD_VBC_Type
import com.contacts.callerid.number.lookup.monetize.delivery.engagement.sections.AnnouncementFragment
import com.contacts.callerid.number.lookup.monetize.delivery.engagement.sections.CallTimelineFragment
import com.contacts.callerid.number.lookup.monetize.delivery.engagement.sections.AlertFeedFragment
import com.contacts.callerid.number.lookup.foundation.BaseActivity
import com.contacts.callerid.number.lookup.repository.ContactRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.contacts.callerid.number.lookup.repository.CallLogRepository
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.repository.assistant.AiFeatureConfig
import com.contacts.callerid.number.lookup.repository.assistant.CallSummary
import com.contacts.callerid.number.lookup.resolver.telephony.CallStateReceiver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The post-call screen, shown after an incoming, outgoing or missed call. It
 * hosts three tabs - Message, AlertEntry and WhatsApp - plus an ad slot, and
 * extends the project's [BaseActivity] so it inherits the standard DataBinding
 * and locale/theme plumbing.
 *
 * Note that the consent and Mobile Ads initialisation which once lived here, by
 * way of the `getData(...)` call inherited from `AdAwareActivity`, is expected to
 * run once during app startup. This screen only triggers ad rendering; it does
 * not initialise the SDK.
 */
class EngagementHubActivity : BaseActivity<ActivityCallReturnBinding>() {

    override val layoutId: Int = R.layout.activity_call_return
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        if (getHD_VBC_Type() == "n") {
            Log.w("987654321", "Native called")
            BottomSheetNativeAds().showSheetLargeNative(this, binding.adContainer)
        } else {
            Log.w("987654321", "Banner called")
            BottomSheetNativeAds().displayBannerAd(this, binding.adContainer)
        }

        val phone = intent.getStringExtra("phone") ?: CallStateReceiver.PRIVATE_NUMBER
        val startTimeMillis = intent.getLongExtra("start_time", 0L)
        val endTimeMillis = intent.getLongExtra("end_time", 0L)
        val callType = intent.getStringExtra("call_type") ?: "UNKNOWN"

        // Resolve the contact name for this number; fall back to the raw number.
        val withheld = phone.equals(CallStateReceiver.PRIVATE_NUMBER, ignoreCase = true)
        val callerName = if (phone.isNotBlank() && !withheld)
            ContactRepository(this).lookupNameByNumber(phone)?.takeIf { it.isNotBlank() }
        else null
        // PRIVATE_NUMBER is an internal token, so it is swapped for localized copy
        // rather than printed — otherwise every locale sees English here.
        binding.labelCallerName.text = callerName
            ?: if (withheld) getString(R.string.caller_private_number) else phone
        binding.labelCallType.text = getCallTypeText(callType)

        // Duration — format as MM:SS
        val durationSec = if (startTimeMillis > 0 && endTimeMillis > startTimeMillis)
            ((endTimeMillis - startTimeMillis) / 1000).toInt() else 0
        val minutes = durationSec / 60
        val seconds = durationSec % 60
        binding.labelDuration.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        bindAiSummary(phone, durationSec.toLong())

        // Time — show end time if available
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        binding.labelTime.text = timeFormat.format(
            if (endTimeMillis > 0) Date(endTimeMillis) else Date()
        )

        // Recent-call list is the default ("first") tab of the post-call screen.
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, CallTimelineFragment())
            .commit()
        selectTab(binding.imageRecent, getAllTabs())

        binding.callIcon.triggerClick {
            val number = callerNumber
            if (!number.isNullOrBlank()) callNumber(number)
            else Toast.makeText(this, R.string.toast_no_number, Toast.LENGTH_SHORT).show()
        }

        setupClickListeners()

        onBackPressedDispatcher.addCallback(this) {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            when (currentFragment) {
                // The recents list is "home" — back from it closes the screen.
                is CallTimelineFragment -> finish()
                else -> {
                    if (!isFinishing && !isDestroyed) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, CallTimelineFragment())
                            .commitAllowingStateLoss()
                    }
                    selectTab(binding.imageRecent, getAllTabs())
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
        binding.imageRecent, binding.imageMes, binding.imageReminder, binding.imageWhatsapp
    )

    /** The caller's number from the launching intent, or null for private/unknown. */
    private val callerNumber: String? by lazy {
        intent.getStringExtra("phone")?.trim()
            ?.takeIf {
                it.isNotEmpty() && !it.equals(CallStateReceiver.PRIVATE_NUMBER, ignoreCase = true)
            }
    }

    /**
     * Places a direct outgoing call through the shared CALL_PHONE flow: it
     * requests the permission where needed, dials directly with ACTION_CALL once
     * granted, and drops back to the dialer only when it is not.
     */
    private fun callNumber(number: String) = placeCall(number)

    private fun setupClickListeners() {
        val allTabs = getAllTabs()

        val fragmentTabs = listOf(
            binding.imageRecent to { CallTimelineFragment() as Fragment },
            binding.imageMes to { AnnouncementFragment.newInstance(callerNumber) as Fragment },
            binding.imageReminder to { AlertFeedFragment() as Fragment }
        )

        fragmentTabs.forEach { (tab, fragmentFactory) ->
            tab.triggerClick {
                if (isFinishing || isDestroyed) return@triggerClick
                selectTab(tab, allTabs)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragmentFactory())
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
        }

        // WhatsApp tab — opens a chat directly with the caller's number.
        binding.imageWhatsapp.triggerClick {
            if (isFinishing || isDestroyed) return@triggerClick
            selectTab(binding.imageWhatsapp, allTabs)
            openWhatsApp(callerNumber)
        }
    }

    /**
     * Opens a WhatsApp chat with [number], digits only. It tries WhatsApp, then
     * WhatsApp Business, then the wa.me web redirect, and falls back to WhatsApp's
     * main screen when the number is private or unknown.
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
     * Opens WhatsApp's main screen without aiming at any contact. It tries
     * `com.whatsapp` first and then `com.whatsapp.w4b` for WhatsApp Business,
     * surfacing a one-line toast when neither is installed.
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
            binding.imageRecent to Pair(
                R.drawable.callback_recent_selected,
                R.drawable.callback_recent_unselected
            ),
            binding.imageMes to Pair(
                R.drawable.callback_message_selected,
                R.drawable.callback_message_unselected
            ),
            binding.imageReminder to Pair(
                R.drawable.callback_reminder_selected,
                R.drawable.callback_reminder_unselected
            ),
            binding.imageWhatsapp to Pair(
                R.drawable.callback_wa_selected,
                R.drawable.callback_wa_unselected
            )
        )
    }

    private val tabImageViews by lazy {
        mapOf(
            binding.imageRecent to binding.imageTabRecent,
            binding.imageMes to binding.imageTabMessage,
            binding.imageReminder to binding.imageTabReminder,
            binding.imageWhatsapp to binding.imageTabWhatsapp
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
     * Mounts the LightHouse rich-push ad overlay when this launch originated from
     * a rich push, returning true once handled. Shared by both the cold path in
     * initView and the warm path in onNewIntent.
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
        /** Below this, "you have called twice" is not an insight worth a card. */
        private const val SUMMARY_MIN_CALLS = 3
        private const val SUMMARY_MIN_UNANSWERED = 2

        /**
         * True while this post-call screen is in the foreground — CallStateReceiver
         * checks it to suppress a duplicate post-call notification (B2).
         */
        @Volatile
        var isActive = false
    }

    /**
     * The Ask AI summary card.
     *
     * Reads the call log, so it runs off the main thread and fills the card in
     * when it lands — the post-call screen has to appear immediately and cannot
     * wait on a content-provider query.
     *
     * The card stays hidden unless there is something worth saying: on an
     * unremarkable second call to a familiar number, silence is the right output.
     */
    private fun bindAiSummary(phone: String, durationSec: Long) {
        if (!AiFeatureConfig.isEnabled(this)) return
        if (!SettingsRepository(this).aiCallSummaryEnabled) return

        lifecycleScope.launch {
            val line = withContext(Dispatchers.IO) {
                runCatching {
                    val tail = phone.filter(Char::isDigit).takeLast(9)
                    if (tail.isEmpty()) return@runCatching null

                    val history = CallLogRepository(this@EngagementHubActivity)
                        .getCalls(limit = 2000)
                        .filter { it.number.filter(Char::isDigit).takeLast(9) == tail }

                    val now = System.currentTimeMillis()
                    val facts = CallSummary.facts(
                        history = history,
                        durationSec = durationSec,
                        monthStartMs = now - TimeUnit.DAYS.toMillis(30),
                        nowMs = now
                    )
                    summaryText(facts)
                }.getOrNull()
            }

            if (!line.isNullOrBlank()) {
                binding.textCallSummary.text = line
                binding.cardCallSummary.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Turns the facts into a sentence, strongest first, and returns null when
     * none of them clear the bar for being worth a card.
     */
    private fun summaryText(facts: CallSummary.Facts): String? {
        val name = binding.labelCallerName.text?.toString()?.takeIf { it.isNotBlank() }
            ?: return null

        val parts = buildList {
            when {
                facts.firstEverCall -> add(getString(R.string.ai_summary_first, name))
                facts.callsThisMonth >= SUMMARY_MIN_CALLS ->
                    add(getString(R.string.ai_summary_frequency, name, facts.callsThisMonth))
            }
            if (facts.longestYet) add(getString(R.string.ai_summary_longest))
            if (facts.unanswered >= SUMMARY_MIN_UNANSWERED) {
                add(getString(R.string.ai_summary_unanswered, facts.unanswered))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
