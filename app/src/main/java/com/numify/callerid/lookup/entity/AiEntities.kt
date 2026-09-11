package com.numify.callerid.lookup.entity

import com.numify.callerid.lookup.repository.assistant.AskIntent

/**
 * Everything the Ask AI surface exchanges. Kept free of Android types so the
 * intent resolver and the remote client can both be tested off-device.
 */

/** One row in the Ask AI transcript. */
sealed interface AiMessage {
    /** Something the user asked, echoed back into the transcript. */
    data class Question(val text: String) : AiMessage

    /** An answer, with whatever actions and follow-ups it earned. */
    data class Answer(
        val text: String,
        val actions: List<AiAction> = emptyList(),
        val followUps: List<AiSuggestion> = emptyList(),
        val source: AiSource = AiSource.LOCAL
    ) : AiMessage

    /** The three-dot placeholder held while a question is in flight. */
    data object Thinking : AiMessage
}

/**
 * A button under an answer. This is the conversion point, so the action carries
 * the number it operates on rather than relying on the screen to remember it.
 */
data class AiAction(
    val kind: AiActionKind,
    /** The number to act on; blank for actions that need no target. */
    val number: String = "",
    /** Pre-written text for [AiActionKind.MESSAGE]; ignored otherwise. */
    val messageBody: String = ""
)

enum class AiActionKind {
    /** Add [AiAction.number] to the blocklist. */
    BLOCK,

    /** Open the report screen for [AiAction.number]. */
    REPORT,

    /**
     * Hand [AiAction.messageBody] to the user's own SMS app, pre-filled.
     * Deliberately a hand-off and never a send: sending in-process would need
     * SEND_SMS, a restricted permission that would put the app's existing
     * READ_CALL_LOG declaration back under review.
     */
    MESSAGE,

    /** Dial [AiAction.number] through the shared CALL_PHONE flow. */
    CALL,

    /** Open the full details screen for [AiAction.number]. */
    DETAILS
}

/** Where an answer came from — shown in debug builds, and used to decide billing. */
enum class AiSource {
    /** Answered on-device from the call log and blocklist. Costs nothing. */
    LOCAL,

    /** Answered by the model behind the backend proxy. Counts against the quota. */
    REMOTE
}

/**
 * A prompt the app offers the user: a starter chip on the empty hub, or a
 * follow-up under an answer. [priority] is what ranks it — lower sorts first —
 * so a spam call that just landed outranks the generic prompts.
 */
data class AiSuggestion(
    val label: String,
    val query: String,
    val priority: Int,
    /** Highlighted chips are the context-derived ones, not the backfill. */
    val highlighted: Boolean = false,
    /**
     * What this prompt asks for, carried rather than re-read off [query].
     *
     * [label] and [query] are localized and [AskIntent.of] matches English, so a
     * chip whose own text had to be re-parsed answered nothing on a device set
     * to any other language. Null only for text the user typed, which has no
     * intent until it is parsed.
     */
    val intent: AskIntent? = null,
    /** The number or contact name the prompt is about; blank when it is about no one. */
    val subject: String = ""
)
