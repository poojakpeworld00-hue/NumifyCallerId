package com.contacts.callerid.number.lookup.feature.calllog

import androidx.annotation.StringRes
import com.contacts.callerid.number.lookup.repository.CallRecord

/** Top filter tabs. */
enum class CallLogFilter { ALL, INCOMING, OUTGOING, MISSED }

/**
 * Sort order applied by the toolbar sort button. Date sorts keep the
 * Today/Yesterday/… grouping; name sorts flatten the list (no date headers).
 */
enum class CallLogSort { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

/** A row in the recents list: either a date section header or a call. */
sealed interface HistoryRowUi {
    data class Header(@param:StringRes val titleRes: Int) : HistoryRowUi
    data class Call(val entry: CallRecord) : HistoryRowUi
}
