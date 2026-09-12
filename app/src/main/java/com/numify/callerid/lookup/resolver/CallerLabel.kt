package com.numify.callerid.lookup.resolver

import android.content.Context
import android.content.res.ColorStateList
import android.telephony.TelephonyManager
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallType
import com.numify.callerid.lookup.repository.ContactRepository
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.repository.assistant.AiFeatureConfig
import com.numify.callerid.lookup.repository.assistant.CallerInsight
import com.facebook.shimmer.ShimmerFrameLayout
import com.numify.callerid.lookup.entity.CallerFacts
import com.numify.callerid.lookup.repository.assistant.CallerRisk
import kotlinx.coroutines.withTimeoutOrNull
import com.numify.callerid.lookup.feature.widgets.CallActionHandler

/**
 * Resolves caller details and renders them into [R.layout.overlay_caller_id].
 *
 * It is shared between
 * [com.numify.callerid.lookup.resolver.telephony.CallerOverlayService], the
 * floating window used while the device is unlocked, and IncomingCallActivity,
 * the full screen used while it is locked, so the card looks and reads the same
 * in both states.
 */
object CallerLabel {

    /** The bits we surface on the card; resolved off the main thread. */
    data class Info(
        val name: String?,
        val known: Boolean,
        val callCount: Int,
        val network: String?,
        /** How many of [callCount] were never picked up — drives [risk]. */
        val unanswered: Int = 0,
        val risk: CallerRisk = CallerRisk.ORDINARY,
        val insight: CallerInsight = CallerInsight.None
    )

    /**
     * Blocking lookup — call from a background thread.
     * Combines the contact name, how many times this number appears in the call
     * log, and the SIM operator name.
     */
    fun resolve(context: Context, number: String): Info {
        val name = runCatching { ContactRepository(context).lookupNameByNumber(number) }.getOrNull()

        val history = runCatching {
            val target = digitsTail(number)
            CallLogRepository(context).getCalls(limit = 2000)
                .filter { digitsTail(it.number) == target }
        }.getOrDefault(emptyList())
        val callCount = history.size
        val unanswered = history.count { it.type == CallType.MISSED }
        val blocked = runCatching {
            BlocklistRepository(context).isNumberBlocked(number)
        }.getOrDefault(false)

        val network = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val known = !name.isNullOrBlank()
        return Info(
            name = name,
            known = known,
            callCount = callCount,
            network = network,
            unanswered = unanswered,
            risk = CallerRisk.assess(blocked, known, callCount, unanswered),
            insight = CallerInsight.of(history, blocked, known)
        )
    }

    /**
     * Binds [number] + resolved [info] into an inflated overlay card [root].
     *
     * [onBlocked] runs after the user blocks from the card, so the host can
     * dismiss itself — the service tears down the window, the locked-screen
     * activity finishes.
     */
    fun bind(
        context: Context,
        root: View,
        number: String,
        info: Info,
        onBlocked: (() -> Unit)? = null
    ) {
        val displayName = info.name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.incall_unknown)

        root.findViewById<TextView>(R.id.textIncallAvatar).text =
            CallActionHandler.initials(info.name, number)
        root.findViewById<TextView>(R.id.textIncallName).text = displayName
        root.findViewById<TextView>(R.id.textIncallNumber).text = number

        bindStatusPill(context, root.findViewById(R.id.textIncallStatus), info.known)

        // A number the address book cannot name is about to be looked up, so the
        // name and the pill are held back rather than shown as a confident
        // "Unknown" that a network answer would then contradict. Callers that
        // start a lookup must pair this with applyNetworkFacts, which clears it
        // on every outcome including failure.
        if (!info.known) showNameLoading(root)

        root.findViewById<TextView>(R.id.textIncallWhen).text =
            context.getString(R.string.incall_now)
        root.findViewById<TextView>(R.id.textIncallCalls).text =
            context.getString(R.string.incall_calls, info.callCount)
        root.findViewById<TextView>(R.id.textIncallNetwork).text =
            info.network?.takeIf { it.isNotBlank() } ?: "—"

        bindVerdict(context, root, number, info, onBlocked)
    }

    /**
     * The Ask AI line. Silent for ordinary and known callers: a verdict on every
     * call is one nobody reads, and the whole value here is that the line only
     * appears when there is something to say.
     */
    private fun bindVerdict(
        context: Context,
        root: View,
        number: String,
        info: Info,
        onBlocked: (() -> Unit)?
    ) {
        val row = root.findViewById<View>(R.id.rowIncallVerdict)
        if (!AiFeatureConfig.isEnabled(context) ||
            !SettingsRepository(context).aiCallerVerdictEnabled
        ) {
            row.visibility = View.GONE
            return
        }
        val label = root.findViewById<TextView>(R.id.textIncallVerdict)
        val icon = root.findViewById<ImageView>(R.id.imageIncallVerdict)
        val block = root.findViewById<TextView>(R.id.buttonIncallBlock)

        val text = when (val insight = info.insight) {
            CallerInsight.Blocked -> context.getString(R.string.ai_verdict_blocked)
            is CallerInsight.Nuisance ->
                context.getString(R.string.ai_verdict_nuisance, insight.calls)
            is CallerInsight.MissedStreak ->
                context.getString(R.string.ai_verdict_missed_streak, insight.count)
            CallerInsight.FirstTime -> context.getString(R.string.ai_verdict_first_time)
            is CallerInsight.AnswerRate ->
                context.getString(R.string.ai_verdict_answer_rate, insight.total, insight.answered)
            is CallerInsight.Frequent ->
                context.getString(R.string.ai_verdict_frequent, insight.callsThisMonth)
            CallerInsight.None -> null
        }
        if (text == null) {
            row.visibility = View.GONE
            return
        }

        row.visibility = View.VISIBLE
        label.text = text

        // Only the nuisance verdict is coloured. Tinting "first time this number
        // has called" red would make every new caller look dangerous.
        val alarming = info.insight is CallerInsight.Nuisance
        val tint = ContextCompat.getColor(
            context,
            if (alarming) R.color.danger else R.color.on_surface_variant
        )
        label.setTextColor(tint)
        icon.imageTintList = ColorStateList.valueOf(tint)

        // Blocking is offered exactly where it is warranted, and never for a
        // number that is already blocked.
        //
        // This one block is deliberately outside BlockReward's rewarded-ad gate.
        // The card is bound by CallerOverlayService as well as by
        // IncomingCallActivity, and a rewarded ad needs an Activity to show in —
        // there is none in the service. Putting a full-screen ad over a ringing
        // spam call would be the wrong moment for one regardless. Blocks made
        // here still occupy a free slot; they just never ask for an ad.
        block.visibility = if (alarming) View.VISIBLE else View.GONE
        block.setOnClickListener {
            runCatching { BlocklistRepository(context).add(number) }
            block.visibility = View.GONE
            label.setText(R.string.ai_verdict_blocked)
            onBlocked?.invoke()
        }
    }

    /** Green "Known Contact" vs neutral "Unknown" pill. */
    private fun bindStatusPill(context: Context, pill: TextView, known: Boolean) {
        if (known) {
            stylePill(context, pill, R.string.incall_known, R.color.success,
                R.color.success_soft, R.drawable.ic_verified)
        } else {
            stylePill(context, pill, R.string.incall_unknown, R.color.on_surface_variant,
                R.color.neutral_soft, R.drawable.ic_info)
        }
    }

    /** Every state of the status pill differs only in text, colours and icon. */
    private fun stylePill(
        context: Context,
        pill: TextView,
        textRes: Int,
        fgRes: Int,
        bgRes: Int,
        iconRes: Int
    ) {
        val fg = ContextCompat.getColor(context, fgRes)
        pill.setText(textRes)
        pill.setTextColor(fg)
        pill.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bgRes))
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(pill, ColorStateList.valueOf(fg))
    }

    /**
     * Asks the caller-ID API who this number belongs to.
     *
     * Deliberately separate from [resolve] and never on its critical path: the
     * phone is ringing, so the card goes up immediately from local data and this
     * fills in afterwards if it arrives. A slow or unreachable API costs the user
     * nothing but a card that keeps saying "Unknown".
     *
     * Returns the whole [CallerFacts] rather than just the name. The response
     * already carries the spam verdict, the caller's own carrier and their city,
     * and throwing all of that away to keep one string was the reason an
     * identified spammer still showed up as an anonymous "Unknown" card.
     *
     * Null when the network knows nothing, the credentials are unset, or the call
     * does not finish inside [TIMEOUT_MS]. Callers must treat null as "leave what
     * is already on screen".
     */
    suspend fun lookupNetworkFacts(context: Context, number: String): CallerFacts? {
        if (CredentialProvider.CONTACTS_API_KEY.isBlank()) {
            return null
        }
        return runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                val response = NetworkClientFactory.api.checkPhoneNumber(
                    url = EndpointConfig.similarPhonePath(context),
                    phone = number
                )
                if (!response.isSuccessful) return@withTimeoutOrNull null
                // The endpoint is "similar-phone-number", so it can answer with
                // several rows. Prefer the first that actually carries a name;
                // fall back to the first row so a nameless spam verdict is still
                // applied rather than silently dropped.
                val rows = response.body()?.data.orEmpty()
                rows.firstOrNull { !it.name.isNullOrBlank() } ?: rows.firstOrNull()
            }
        }.getOrNull()
    }

    /**
     * Puts network-resolved facts on an already-bound card.
     *
     * The user's own contact name always wins — replacing "Mum" with whatever the
     * network calls that number would be a downgrade, however authoritative it
     * is. This only fills the gap where the card is showing "Unknown".
     */
    fun applyNetworkFacts(context: Context, root: View, info: Info, facts: CallerFacts?) {
        if (info.known) return

        // Cleared first and unconditionally. An unreachable API, a timeout and a
        // number nobody knows all land here with a null, and a shimmer left
        // running forever is a worse card than the "Unknown" it replaced.
        val named = !facts?.name?.trim().isNullOrBlank()
        clearNameLoading(context, root, revealName = !named)
        if (facts == null) return

        val networkName = facts.name?.trim()?.takeIf(String::isNotBlank)
        if (networkName != null) {
            val nameView = root.findViewById<TextView>(R.id.textIncallName)
            nameView.text = networkName
            nameView.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.textIncallAvatar).text =
                CallActionHandler.initials(networkName, "")
        }

        // The caller's own carrier, when the network knows it, in place of the
        // SIM operator this device happens to be camped on — which said nothing
        // about the person calling.
        facts.carrierOrNull?.let {
            root.findViewById<TextView>(R.id.textIncallNetwork).text = it
        }

        val spam = facts.is_spam || facts.is_user_spam
        when {
            spam -> bindSpamPill(context, root.findViewById(R.id.textIncallStatus))
            networkName != null -> bindIdentifiedPill(context, root.findViewById(R.id.textIncallStatus))
        }
        if (spam) {
            applyNetworkSpamVerdict(context, root, facts)
        } else if (networkName != null) {
            relaxNuisanceVerdict(context, root, info)
        }
    }

    /**
     * A network spam verdict overrides whatever the on-device insight said.
     *
     * Local history only knows this phone; "first time this number has called
     * you" is true and useless next to other people having reported the number.
     * The row is forced visible because the insight may have hidden it.
     */
    private fun applyNetworkSpamVerdict(context: Context, root: View, facts: CallerFacts) {
        val label = root.findViewById<TextView>(R.id.textIncallVerdict) ?: return
        val row = root.findViewById<View>(R.id.rowIncallVerdict) ?: return
        if (row.visibility != View.VISIBLE && !AiFeatureConfig.isEnabled(context)) return

        val reports = facts.spamReportCounter
        label.text = if (reports > 0) {
            context.getString(R.string.ai_verdict_network_spam_count, reports)
        } else {
            context.getString(R.string.ai_verdict_network_spam)
        }
        val danger = ContextCompat.getColor(context, R.color.danger)
        label.setTextColor(danger)
        root.findViewById<ImageView>(R.id.imageIncallVerdict)?.imageTintList =
            ColorStateList.valueOf(danger)
        root.findViewById<TextView>(R.id.buttonIncallBlock)?.visibility = View.VISIBLE
        row.visibility = View.VISIBLE
    }

    /** Swaps the name line for a shimmering bar and hides the status pill. */
    private fun showNameLoading(root: View) {
        val shimmer = root.findViewById<ShimmerFrameLayout>(R.id.shimmerIncallName) ?: return
        root.findViewById<TextView>(R.id.textIncallName).visibility = View.GONE
        root.findViewById<TextView>(R.id.textIncallStatus).visibility = View.INVISIBLE
        shimmer.visibility = View.VISIBLE
        shimmer.startShimmer()
    }

    /**
     * Ends the loading state.
     *
     * [revealName] puts back whatever [bind] had already written — the local
     * "Unknown" — for the case where the lookup came back with nothing. When the
     * network did supply a name the caller writes it and reveals the view itself,
     * so the old text is never shown even for a frame.
     */
    private fun clearNameLoading(context: Context, root: View, revealName: Boolean) {
        root.findViewById<ShimmerFrameLayout>(R.id.shimmerIncallName)?.let {
            it.stopShimmer()
            it.visibility = View.GONE
        }
        root.findViewById<TextView>(R.id.textIncallStatus)?.visibility = View.VISIBLE
        if (revealName) {
            root.findViewById<TextView>(R.id.textIncallName)?.visibility = View.VISIBLE
        }
    }

    /**
     * Drops the local spam accusation once the network has named the caller.
     *
     * [CallerInsight.Nuisance] only exempts numbers in the address book, so a
     * caller the network identifies by name was still being accused purely for
     * going unanswered three times — the card read "Kp Jiya Bharti · Identified"
     * directly above "Likely spam", offering to block them. The network returned
     * no spam flag in that case, so the accusation was the app's alone.
     *
     * The underlying facts are kept, just stated rather than judged: the same
     * counts, in neutral colour, with no Block button. A number the network
     * *does* flag never reaches here — that goes to [applyNetworkSpamVerdict].
     */
    private fun relaxNuisanceVerdict(context: Context, root: View, info: Info) {
        val insight = info.insight as? CallerInsight.Nuisance ?: return
        val label = root.findViewById<TextView>(R.id.textIncallVerdict) ?: return

        label.text = context.getString(
            R.string.ai_verdict_answer_rate,
            insight.calls,
            insight.calls - info.unanswered
        )
        val neutral = ContextCompat.getColor(context, R.color.on_surface_variant)
        label.setTextColor(neutral)
        root.findViewById<ImageView>(R.id.imageIncallVerdict)?.imageTintList =
            ColorStateList.valueOf(neutral)
        root.findViewById<TextView>(R.id.buttonIncallBlock)?.visibility = View.GONE
    }

    /** Neutral "Identified" pill — named by the network, not by the address book. */
    private fun bindIdentifiedPill(context: Context, pill: TextView) =
        stylePill(context, pill, R.string.incall_identified, R.color.success,
            R.color.success_soft, R.drawable.ic_verified)

    /** Red "Spam" pill for a number the network has flagged. */
    private fun bindSpamPill(context: Context, pill: TextView) =
        stylePill(context, pill, R.string.incall_spam, R.color.danger,
            R.color.danger_soft, R.drawable.ic_info)

    /** Short enough that the name lands while the phone is still ringing. */
    private const val TIMEOUT_MS = 2_500L

    /** Last 9 digits — tolerant comparison that ignores country code / formatting. */
    private fun digitsTail(number: String): String =
        number.filter { it.isDigit() }.takeLast(9)
}
