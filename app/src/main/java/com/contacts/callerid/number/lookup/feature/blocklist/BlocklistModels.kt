package com.contacts.callerid.number.lookup.feature.blocklist

import com.contacts.callerid.number.lookup.repository.BlockedNumber
import com.contacts.callerid.number.lookup.repository.CallType

/**
 * One call this number made, as the blocklist needs it.
 *
 * @param at when it called.
 * @param type what the log recorded — a call from before the block is incoming,
 *   outgoing or missed; one the app turned away is [CallType.SPAM].
 * @param blockedAttempt true when it happened after the number was blocked, so
 *   the row can mark the calls that were actually turned away.
 */
data class BlockedCall(
    val at: Long,
    val type: CallType,
    val blockedAttempt: Boolean,
)

/**
 * One blocklist row, ready to display.
 *
 * @param entry  the underlying blocked number and its timestamp.
 * @param label  the resolved contact name, or a friendly fallback.
 * @param isSpam when true, the row takes the red-tinted "spam" treatment.
 * @param history every call logged for this number, newest first — before the
 *   block as well as after. A number blocked a minute ago has no attempts yet
 *   but usually has the history that got it blocked, and that history is the
 *   reason the user is looking at the row.
 */
data class BlockedNumberState(
    val entry: BlockedNumber,
    val label: String,
    val isSpam: Boolean,
    val history: List<BlockedCall> = emptyList(),
) {
    /** Every logged call, which is what the count badge reports. */
    val callCount: Int get() = history.size

    /** Only the calls the block turned away. */
    val attemptCount: Int get() = history.count { it.blockedAttempt }

    /** When it last called at all, or null if the log holds nothing for it. */
    val lastCallMs: Long? get() = history.firstOrNull()?.at
}
