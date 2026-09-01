package com.numify.callerid.lookup.feature.blocklist

import com.numify.callerid.lookup.repository.BlockedNumber

/**
 * A blocklist row ready for display.
 *
 * @param entry  the underlying blocked number + timestamp.
 * @param label  resolved contact name, or a friendly fallback.
 * @param isSpam when true the row uses the red-tinted "spam" treatment.
 */
data class BlockedNumberState(
    val entry: BlockedNumber,
    val label: String,
    val isSpam: Boolean,
)
