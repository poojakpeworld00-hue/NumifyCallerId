package com.numify.callerid.numberlookup.engine

import android.content.Context
import android.content.res.ColorStateList
import android.telephony.TelephonyManager
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.store.CallLogSource
import com.numify.callerid.numberlookup.store.PhonebookSource
import com.numify.callerid.numberlookup.screen.shared.CallPresenter

/**
 * Resolves caller details and renders them into [R.layout.bubble_caller_id].
 *
 * Shared by [com.numify.callerid.numberlookup.engine.incoming.CallerBubbleService] (floating window, device unlocked) and
 * RingScreenActivity (full screen, device locked) so the card looks and reads
 * identically in both states.
 */
object CallerBadge {

    /** The bits we surface on the card; resolved off the main thread. */
    data class Info(
        val name: String?,
        val known: Boolean,
        val callCount: Int,
        val network: String?
    )

    /**
     * Blocking lookup — call from a background thread.
     * Combines the contact name, how many times this number appears in the call
     * log, and the SIM operator name.
     */
    fun resolve(context: Context, number: String): Info {
        val name = runCatching { PhonebookSource(context).lookupNameByNumber(number) }.getOrNull()

        val callCount = runCatching {
            val target = digitsTail(number)
            CallLogSource(context).getCalls(limit = 2000)
                .count { digitsTail(it.number) == target }
        }.getOrDefault(0)

        val network = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        }.getOrNull()

        return Info(name = name, known = !name.isNullOrBlank(), callCount = callCount, network = network)
    }

    /** Binds [number] + resolved [info] into an inflated overlay card [root]. */
    fun bind(context: Context, root: View, number: String, info: Info) {
        val displayName = info.name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.incall_unknown)

        root.findViewById<TextView>(R.id.lblIncallAvatar).text =
            CallPresenter.initials(info.name, number)
        root.findViewById<TextView>(R.id.lblIncallName).text = displayName
        root.findViewById<TextView>(R.id.lblIncallNumber).text = number

        bindStatusPill(context, root.findViewById(R.id.lblIncallStatus), info.known)

        root.findViewById<TextView>(R.id.lblIncallWhen).text =
            context.getString(R.string.incall_now)
        root.findViewById<TextView>(R.id.lblIncallCalls).text =
            context.getString(R.string.incall_calls, info.callCount)
        root.findViewById<TextView>(R.id.lblIncallNetwork).text =
            info.network?.takeIf { it.isNotBlank() } ?: "—"
    }

    /** Green "Known Contact" vs neutral "Unknown" pill. */
    private fun bindStatusPill(context: Context, pill: TextView, known: Boolean) {
        val textRes = if (known) R.string.incall_known else R.string.incall_unknown
        val fgRes = if (known) R.color.success else R.color.on_surface_variant
        val bgRes = if (known) R.color.success_soft else R.color.neutral_soft
        val iconRes = if (known) R.drawable.glyph_verified else R.drawable.glyph_info

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
