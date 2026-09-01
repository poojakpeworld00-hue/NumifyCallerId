package com.numify.callerid.lookup.feature.blocklist

import com.numify.callerid.lookup.repository.BlockedNumber

/**
 * One blocklist row, ready to display.
 *
 * @param entry  the underlying blocked number and its timestamp.
 * @param label  the resolved contact name, or a friendly fallback.
 * @param isSpam when true, the row takes the red-tinted "spam" treatment.
 */
data class BlockedNumberState(
    val entry: BlockedNumber,
    val label: String,
    val isSpam: Boolean,
)
