package com.numify.callerid.lookup.feature.blocklist

import com.numify.callerid.lookup.repository.BlockedNumber

/**
 * One blocklist row, ready to display.
 *
 * @param entry  the underlying blocked number and its timestamp.
 * @param label  the resolved contact name, or a friendly fallback.
 * @param isSpam when true, the row takes the red-tinted "spam" treatment.
 * @param attempts when this number tried to call **after** it was blocked,
 *   newest first. Calls from before the block are that number's history, not
 *   attempts to get past a block, so they are deliberately not counted here.
 */
data class BlockedNumberState(
    val entry: BlockedNumber,
    val label: String,
    val isSpam: Boolean,
    val attempts: List<Long> = emptyList(),
) {
    val attemptCount: Int get() = attempts.size

    /** When it last tried, or null if it has not tried since being blocked. */
    val lastAttemptMs: Long? get() = attempts.firstOrNull()
}
