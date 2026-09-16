package com.callerid.numberlookup.home.permission

/**
 * An ordered, drainable queue of the permissions to ask for on one Activity.
 *
 * Rules sort by [PermissionRule.priority] ascending, lowest first, and ties hold
 * their incoming order because the sort is stable. The engine takes one rule at a
 * time and only moves on once the previous request has completed, which is what
 * produces the required sequential flow.
 */
class PermissionRequestQueue(rules: List<PermissionRule>) {

    private val items: ArrayDeque<PermissionRule> =
        ArrayDeque(rules.sortedBy { it.priority })

    /** Removes and returns the next rule, or null when the queue is drained. */
    fun poll(): PermissionRule? = items.removeFirstOrNull()

    fun isEmpty(): Boolean = items.isEmpty()

    fun size(): Int = items.size
}
