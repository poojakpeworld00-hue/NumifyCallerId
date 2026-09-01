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
import com.numify.callerid.lookup.repository.assistant.CallerRisk
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
        val risk: CallerRisk = CallerRisk.ORDINARY
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
            risk = CallerRisk.assess(blocked, known, callCount, unanswered)
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

        val text = when (info.risk) {
            CallerRisk.NUISANCE ->
                context.getString(R.string.ai_verdict_nuisance, info.callCount)
            CallerRisk.BLOCKED -> context.getString(R.string.ai_verdict_blocked)
            CallerRisk.FIRST_TIME -> context.getString(R.string.ai_verdict_first_time)
            CallerRisk.KNOWN, CallerRisk.ORDINARY -> null
        }
        if (text == null) {
            row.visibility = View.GONE
            return
        }

        row.visibility = View.VISIBLE
        label.text = text

        // Only the nuisance verdict is coloured. Tinting "first time this number
        // has called" red would make every new caller look dangerous.
        val alarming = info.risk == CallerRisk.NUISANCE
        val tint = ContextCompat.getColor(
            context,
            if (alarming) R.color.danger else R.color.on_surface_variant
        )
        label.setTextColor(tint)
        icon.imageTintList = ColorStateList.valueOf(tint)

        // Blocking is offered exactly where it is warranted, and never for a
        // number that is already blocked.
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
        val textRes = if (known) R.string.incall_known else R.string.incall_unknown
        val fgRes = if (known) R.color.success else R.color.on_surface_variant
        val bgRes = if (known) R.color.success_soft else R.color.neutral_soft
        val iconRes = if (known) R.drawable.ic_verified else R.drawable.ic_info

        val fg = ContextCompat.getColor(context, fgRes)
        pill.setText(textRes)
        pill.setTextColor(fg)
        pill.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bgRes))
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(pill, ColorStateList.valueOf(fg))
    }

    /** Last 9 digits — tolerant comparison that ignores country code / formatting. */
    private fun digitsTail(number: String): String =
        number.filter { it.isDigit() }.takeLast(9)
}
