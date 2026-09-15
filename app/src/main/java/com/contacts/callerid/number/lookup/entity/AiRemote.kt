package com.contacts.callerid.number.lookup.entity

/**
 * The wire contract between the app and the `/ai/query` proxy.
 *
 * Serialized by Gson with these field names exactly, so the backend reads
 * camelCase. Kept free of Android types for the same reason as [AiMessage]: the
 * shape can be tested off-device.
 *
 * **What is deliberately not here: raw phone numbers.** The model is asked about
 * a pattern of calls, not about who the user knows, so a caller is identified by
 * its saved name or - when unsaved - by nothing more than its last four digits.
 * The verdict questions that genuinely need a full number are the ones
 * [com.contacts.callerid.number.lookup.repository.assistant.LocalIntentResolver] already
 * answers on the device, so the number never has to leave it.
 */
data class AiQueryRequest(
    /** What the user typed, verbatim. */
    val question: String,
    /** BCP-47 tag, so the proxy can ask for an answer in the user's language. */
    val locale: String,
    val context: AiCallContext
)

/** The digest of the call log the proxy is allowed to reason over. */
data class AiCallContext(
    val callsThisWeek: Int,
    val missedThisWeek: Int,
    val unsavedThisWeek: Int,
    val blockedTotal: Int,
    val topCaller: AiCallerDigest?,
    val recent: List<AiCallDigest>
)

data class AiCallerDigest(
    /** Saved name, or the masked tail of an unsaved number. */
    val label: String,
    val calls: Int
)

data class AiCallDigest(
    val label: String,
    /** incoming | outgoing | missed | blocked. */
    val type: String,
    val minutesAgo: Long,
    val durationSec: Long
)

/**
 * What the proxy answers with.
 *
 * Both fields are nullable on purpose: a proxy that returns an error, an empty
 * body, or a shape this version does not know about must degrade to "not
 * reachable" rather than putting a blank bubble on screen.
 */
data class AiQueryResponse(
    val answer: String?,
    val followUps: List<String>? = null
)
