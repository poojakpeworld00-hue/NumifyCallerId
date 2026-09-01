package com.numify.callerid.lookup.repository.assistant

import android.content.Context
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.entity.AiMessage
import com.numify.callerid.lookup.entity.AiSource
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
     * Quota is charged only for [AiSource.REMOTE] answers — see
     * [AiFeatureConfig.freeQueryLimit] for why the client count is advisory.
     */
    suspend fun ask(question: String): AiMessage.Answer = withContext(Dispatchers.IO) {
        local.resolve(question)?.let { return@withContext it }

        val endpoint = AiFeatureConfig.endpoint(context)
        if (endpoint.isBlank()) return@withContext unconfigured()

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

    private fun unconfigured(): AiMessage.Answer = AiMessage.Answer(
        text = context.getString(R.string.ai_ans_local_only),
        followUps = listOf(
            context.getString(R.string.ai_follow_top_caller),
            context.getString(R.string.ai_follow_missed)
        ),
        source = AiSource.LOCAL
    )
}
