package com.contacts.callerid.number.lookup.repository.assistant

import android.content.Context
import com.contacts.callerid.number.lookup.BuildConfig
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore

/**
 * Remote-Config view of the `ai_assistant` block, read the same way
 * [com.contacts.callerid.number.lookup.resolver.EndpointConfig] reads `api_config`:
 * stored as text in [AdPreferenceStore], parsed with `org.json`.
 *
 * The feature ships dark: [isEnabled] is false in release until Remote Config
 * says otherwise, so the build can go out with everything in place and be
 * switched on per audience without a release. Every other field falls back to a
 * value that works, so a malformed or not-yet-fetched config degrades rather
 * than crashing.
 */
object AiFeatureConfig {

    private const val BLOCK = "ai_assistant"

    /**
     * Master switch.
     *
     * Release builds default to **off**, so the feature ships dark and is turned
     * on per audience from Remote Config without a release. Debug builds default
     * to **on**, because a feature nobody can reach is a feature nobody tests.
     * An explicit Remote Config value overrides either default.
     */
    fun isEnabled(context: Context): Boolean =
        AdPreferenceStore.getInstance(context).getBoolean("${BLOCK}_enabled", BuildConfig.DEBUG)

    /**
     * Free questions before the paywall. Only REMOTE answers count — anything
     * the on-device resolver handles is free, because it costs nothing to serve.
     *
     * The client enforces this for the UI only. The real cap belongs to the
     * backend: a SharedPreferences edit resets anything held here.
     */
    fun freeQueryLimit(context: Context): Int =
        AdPreferenceStore.getInstance(context)
            .getInt("${BLOCK}_free_queries", DEFAULT_FREE_QUERIES)

    /**
     * Base URL of the AI proxy. Blank until the endpoint exists, which is the
     * signal [AiAssistantRepository] uses to stay on-device instead of failing.
     */
    fun endpoint(context: Context): String =
        AdPreferenceStore.getInstance(context).getString("${BLOCK}_endpoint", "").orEmpty()

    /** Whether the first-run tooltip on Home should appear at all. */
    fun showHomeTooltip(context: Context): Boolean =
        AdPreferenceStore.getInstance(context).getBoolean("${BLOCK}_home_tooltip", true)

    private const val DEFAULT_FREE_QUERIES = 5
}
