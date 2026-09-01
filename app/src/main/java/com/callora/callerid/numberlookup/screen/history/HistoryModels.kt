package com.callora.callerid.numberlookup.screen.history

import androidx.annotation.StringRes
import com.callora.callerid.numberlookup.store.CallRecord

/** Top filter tabs. */
enum class CallScope { ALL, INCOMING, OUTGOING, MISSED }

/**
 * Sort order applied by the toolbar sort button. Date sorts keep the
 * Today/Yesterday/… grouping; name sorts flatten the list (no date headers).
 */
enum class CallOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

/** A row in the recents list: either a date section header or a call. */
sealed interface HistoryRowUi {
    data class Header(@param:StringRes val titleRes: Int) : HistoryRowUi
    data class Call(val entry: CallRecord) : HistoryRowUi
}
