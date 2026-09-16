package com.callerid.numberlookup.home.permission

/**
 * Works out which [PermissionRule]s apply to a given Activity.
 *
 * Matching runs against the Activity's *simple class name*, `HomeActivity` for
 * instance, case-insensitively. That keeps Remote Config readable and means a
 * refactor which merely moves a class between packages does not break targeting.
 */
object ScreenRouteMatcher {

    /**
     * Returns the enabled rules that target [activitySimpleName], preserving
     * the caller's ordering (the queue applies priority afterwards).
     */
    fun rulesFor(
        activitySimpleName: String,
        allRules: List<PermissionRule>,
    ): List<PermissionRule> = allRules.filter { rule ->
        rule.enabled && rule.activities.any { it.equals(activitySimpleName, ignoreCase = true) }
    }
}
