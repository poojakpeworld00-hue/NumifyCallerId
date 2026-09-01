package com.callora.callerid.numberlookup.access

/**
 * Decides which [AccessRule]s apply to a given Activity.
 *
 * Matching is done on the Activity's *simple class name* (e.g. `HomeActivity`),
 * case-insensitively, so Remote Config stays readable and refactors that only
 * move a class between packages don't break targeting.
 */
object ScreenMatcher {

    /**
     * Returns the enabled rules that target [activitySimpleName], preserving
     * the caller's ordering (the queue applies priority afterwards).
     */
    fun rulesFor(
        activitySimpleName: String,
        allRules: List<AccessRule>,
    ): List<AccessRule> = allRules.filter { rule ->
        rule.enabled && rule.activities.any { it.equals(activitySimpleName, ignoreCase = true) }
    }
}
