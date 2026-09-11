package com.numify.callerid.lookup.repository.assistant

import android.content.Context
import com.numify.callerid.lookup.entity.AiCallContext
import com.numify.callerid.lookup.entity.AiCallDigest
import com.numify.callerid.lookup.entity.AiCallerDigest
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType
import java.util.concurrent.TimeUnit

/**
 * Builds the smallest useful picture of the call log for the remote path.
 *
 * Two jobs, and the second is the important one. It keeps the request small, so
 * a question costs a few hundred tokens rather than a dump of the whole log; and
 * it decides what is allowed to leave the device at all.
 *
 * An unsaved caller is reduced to the last [MASK_DIGITS] digits of its number,
 * which is enough for the model to tell two callers apart in a sentence and not
 * enough to dial, look up, or match against anything. Saved contacts travel as
 * the name the user themselves gave them. Nothing here carries a full number.
 */
internal class AiContextBuilder(private val context: Context) {

    private val callLog = CallLogRepository(context)
    private val blocklist = BlocklistRepository(context)

    fun build(): AiCallContext {
        val all = runCatching { callLog.getCalls() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val week = all.filter { it.date >= now - TimeUnit.DAYS.toMillis(WEEK_DAYS) }

        val busiest = week.groupBy { it.number }.maxByOrNull { it.value.size }

        return AiCallContext(
            callsThisWeek = week.size,
            missedThisWeek = week.count { it.type == CallType.MISSED },
            unsavedThisWeek = week.count { it.name.isNullOrBlank() },
            blockedTotal = runCatching { blocklist.getEntries().size }.getOrDefault(0),
            topCaller = busiest?.let {
                AiCallerDigest(label = label(it.value.first()), calls = it.value.size)
            },
            recent = all.take(RECENT_CALLS).map { record ->
                AiCallDigest(
                    label = label(record),
                    type = record.type.name.lowercase(),
                    minutesAgo = TimeUnit.MILLISECONDS.toMinutes((now - record.date).coerceAtLeast(0)),
                    durationSec = record.durationSec
                )
            }
        )
    }

    /** The saved name, or a masked tail that identifies without disclosing. */
    private fun label(record: CallRecord): String =
        record.name?.takeIf(String::isNotBlank)
            ?: record.number.filter(Char::isDigit).takeLast(MASK_DIGITS).let { tail ->
                if (tail.isEmpty()) UNKNOWN_LABEL else "$MASK_PREFIX$tail"
            }

    private companion object {
        const val WEEK_DAYS = 7L

        /** Enough recent calls to answer "what happened", short enough to stay cheap. */
        const val RECENT_CALLS = 10

        /** Identifies a caller within one answer; useless for anything else. */
        const val MASK_DIGITS = 4
        const val MASK_PREFIX = "…"
        const val UNKNOWN_LABEL = "withheld"
    }
}
