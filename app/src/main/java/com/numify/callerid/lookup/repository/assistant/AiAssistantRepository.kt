package com.numify.callerid.lookup.repository.assistant

import android.content.Context
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.entity.AiMessage
import com.numify.callerid.lookup.entity.AiSource
import com.numify.callerid.lookup.entity.AiSuggestion
import com.numify.callerid.lookup.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single entry point behind the Ask AI screen.
 *
 * Questions are settled on-device first and only escalated to the model when
 * the local resolver has nothing — which keeps the common cases free, instant
 * and offline, and means the paid quota is spent on genuinely open questions.
 *
 * The remote half is deliberately not wired to a provider yet. It routes through
 * a backend proxy identified by [AiFeatureConfig.endpoint]; until that endpoint
 * is configured, [ask] returns a stated limitation rather than pretending, and
 * nothing is billed.
 */
class AiAssistantRepository(private val context: Context) {

    private val local = LocalIntentResolver(context)
    private val settings = SettingsRepository(context)

    /**
     * Answers [question]. Runs off the main thread: the local path reads the
     * call-log content provider, which is not a main-thread operation.
     *
     * [intent] and [subject] are carried by every prompt the app offers, so a
     * chip is never re-parsed from its own localized label — see [AskIntent].
     * Typed text arrives with both empty and is classified from the words.
     *
     * Quota is charged only for [AiSource.REMOTE] answers — see
     * [AiFeatureConfig.freeQueryLimit] for why the client count is advisory.
     */
    suspend fun ask(
        question: String,
        intent: AskIntent? = null,
        subject: String = ""
    ): AiMessage.Answer = withContext(Dispatchers.IO) {
        local.resolve(question, intent, subject)?.let { return@withContext it }

        val endpoint = AiFeatureConfig.endpoint(context)
        if (endpoint.isBlank()) return@withContext unconfigured(question)

        remote(question, endpoint)
    }

    /** True once the free allowance is spent, so the caller can raise the paywall. */
    fun hasQuotaLeft(): Boolean =
        settings.aiQueryCount < AiFeatureConfig.freeQueryLimit(context)

    fun queriesUsed(): Int = settings.aiQueryCount

    fun freeLimit(): Int = AiFeatureConfig.freeQueryLimit(context)

    // --- remote -------------------------------------------------------------

    /**
     * Placeholder for the `/ai/query` call.
     *
     * Not implemented against a provider on purpose. The model key must never
     * reach the APK — the same reasoning already written on
     * [com.numify.callerid.lookup.resolver.CredentialProvider], only sharper
     * here, because a leaked model key is spent money rather than a rate-limited
     * lookup. When the backend exists this posts the question plus the minimum
     * context to that proxy, and the key stays server-side.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun remote(question: String, endpoint: String): AiMessage.Answer {
        settings.aiQueryCount = settings.aiQueryCount + 1
        return AiMessage.Answer(
            text = context.getString(R.string.ai_ans_not_configured),
            source = AiSource.REMOTE
        )
    }

    /**
     * Reached only by a typed question the on-device resolver cannot place —
     * never by one of the app's own prompts, which all carry an intent it can
     * answer. The follow-ups offered here therefore carry theirs as well, so
     * the way out of this message is not itself a dead end.
     *
     * When the question named a number, the first of them is about that number
     * rather than a generic prompt: someone who typed a number wants something
     * about it, and the verdict is the nearest answerable reading.
     */
    private fun unconfigured(question: String): AiMessage.Answer {
        val number = NumberInText.find(question)
        val first = if (number != null) {
            followUp(R.string.ai_chip_is_spam, AskIntent.SPAM, number, number)
        } else {
            followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER)
        }

        return AiMessage.Answer(
            text = context.getString(R.string.ai_ans_local_only),
            followUps = listOf(first, followUp(R.string.ai_follow_missed, AskIntent.MISSED)),
            source = AiSource.LOCAL
        )
    }

    private fun followUp(
        resId: Int,
        intent: AskIntent,
        subject: String = "",
        vararg formatArgs: Any
    ): AiSuggestion {
        val text = if (formatArgs.isEmpty()) {
            context.getString(resId)
        } else {
            context.getString(resId, *formatArgs)
        }
        return AiSuggestion(
            label = text,
            query = text,
            priority = 0,
            intent = intent,
            subject = subject
        )
    }
}
