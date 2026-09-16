package com.contacts.callerid.number.lookup.repository.assistant

import android.content.Context
import com.contacts.callerid.number.lookup.monetize.billing.PremiumStore
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.entity.AiMessage
import com.contacts.callerid.number.lookup.entity.AiQueryRequest
import com.contacts.callerid.number.lookup.entity.AiSource
import com.contacts.callerid.number.lookup.entity.AiSuggestion
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The single entry point behind the Ask AI screen.
 *
 * Questions are settled on-device first and only escalated to the model when
 * the local resolver has nothing — which keeps the common cases free, instant
 * and offline, and means the paid quota is spent on genuinely open questions.
 *
 * The remote half goes to a backend proxy named by [AiFeatureConfig.endpoint] —
 * never to a provider directly, because the proxy is what holds the model key.
 * While that endpoint is blank the feature is simply on-device: [ask] says so
 * rather than pretending, and nothing is billed.
 */
class AiAssistantRepository(private val context: Context) {

    private val local = LocalIntentResolver(context)
    private val settings = SettingsRepository(context)
    private val contextBuilder = AiContextBuilder(context)

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

    /**
     * True once the free allowance is spent, so the caller can raise the paywall.
     *
     * Premium has no allowance to spend. This is the one gate in the app that
     * does not route through `IsAdsON` — it counts questions rather than showing
     * an ad — so it is the one that has to name Premium explicitly.
     */
    fun hasQuotaLeft(): Boolean =
        PremiumStore.isPremium(context) ||
            settings.aiQueryCount < AiFeatureConfig.freeQueryLimit(context)

    fun queriesUsed(): Int = settings.aiQueryCount

    fun freeLimit(): Int = AiFeatureConfig.freeQueryLimit(context)

    // --- remote -------------------------------------------------------------

    /**
     * The `/ai/query` call.
     *
     * It posts the question and the digest from [AiContextBuilder] to the proxy
     * named by Remote Config, and nothing else. **The model key is never in this
     * app** — the same reasoning already written on
     * [com.contacts.callerid.number.lookup.resolver.CredentialProvider], only sharper
     * here, because a leaked model key is spent money rather than a rate-limited
     * lookup. The proxy holds it and calls the provider server-side.
     *
     * The quota is charged on a delivered answer only. Charging on the attempt
     * would let a flaky network eat a user's free questions without ever
     * answering one.
     */
    private suspend fun remote(question: String, endpoint: String): AiMessage.Answer {
        if (!hasQuotaLeft()) return quotaSpent()

        val payload = AiQueryRequest(
            question = question,
            locale = Locale.getDefault().toLanguageTag(),
            context = contextBuilder.build()
        )

        val response = runCatching {
            AiProxyClient.service(endpoint).ask(endpoint, payload)
        }.getOrNull() ?: return unreachable()

        val body = response.body()?.takeIf { response.isSuccessful } ?: return unreachable()
        val text = body.answer?.takeIf { it.isNotBlank() } ?: return unreachable()

        settings.aiQueryCount = settings.aiQueryCount + 1
        return AiMessage.Answer(
            text = text,
            followUps = modelFollowUps(body.followUps),
            source = AiSource.REMOTE
        )
    }

    /**
     * Follow-ups the model proposed. They carry no [AskIntent] — unlike the app's
     * own prompts, nobody has checked that these can be answered, so they are
     * treated exactly like text the user typed: classified on the device first,
     * and escalated only if that finds nothing.
     *
     * Capped at two because the answer bubble renders two chips.
     */
    private fun modelFollowUps(proposed: List<String>?): List<AiSuggestion> =
        proposed.orEmpty()
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .take(MAX_FOLLOW_UPS)
            .map { AiSuggestion(label = it, query = it, priority = 0) }

    private fun unreachable(): AiMessage.Answer = AiMessage.Answer(
        text = context.getString(R.string.ai_ans_not_configured),
        source = AiSource.REMOTE
    )

    /**
     * The client-side end of the allowance. It exists to explain the stop, not
     * to enforce it — a SharedPreferences edit resets this, which is why
     * [AiFeatureConfig.freeQueryLimit] says the real cap belongs to the proxy.
     */
    private fun quotaSpent(): AiMessage.Answer = AiMessage.Answer(
        text = context.getString(R.string.ai_ans_quota_spent, freeLimit()),
        followUps = listOf(
            followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER),
            followUp(R.string.ai_follow_missed, AskIntent.MISSED)
        ),
        source = AiSource.LOCAL
    )

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

    private companion object {
        /** The answer bubble renders two follow-up chips; the rest are dropped. */
        const val MAX_FOLLOW_UPS = 2
    }
}
