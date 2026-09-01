package com.numify.callerid.numberlookup.screen.spamlist

import com.numify.callerid.numberlookup.store.BlockedNumber

/**
 * A blocklist row ready for display.
 *
 * @param entry  the underlying blocked number + timestamp.
 * @param label  resolved contact name, or a friendly fallback.
 * @param isSpam when true the row uses the red-tinted "spam" treatment.
 */
data class BlockedRowState(
    val entry: BlockedNumber,
    val label: String,
    val isSpam: Boolean,
)
