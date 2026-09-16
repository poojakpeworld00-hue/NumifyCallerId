package com.callerid.numberlookup.home.feature.calllog

import android.provider.CallLog
import androidx.annotation.StringRes
import com.callerid.numberlookup.home.repository.CallRecord

/** Top filter tabs. */
enum class CallLogFilter { ALL, INCOMING, OUTGOING, MISSED }

/**
 * The provider call types each tab asks for, or null for "everything".
 *
 * The tabs query the log rather than filtering a shared page of it, so each one
 * fills its own window — see [com.callerid.numberlookup.home.repository.CallLogRepository.getCalls].
 *
 * MISSED spans MISSED and REJECTED, matching how the repository folds both onto
 * one type and how the header's missed count is taken.
 */
val CallLogFilter.providerTypes: IntArray?
    get() = when (this) {
        CallLogFilter.ALL -> null
        CallLogFilter.INCOMING -> intArrayOf(CallLog.Calls.INCOMING_TYPE)
        CallLogFilter.OUTGOING -> intArrayOf(CallLog.Calls.OUTGOING_TYPE)
        CallLogFilter.MISSED ->
            intArrayOf(CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE)
    }

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
