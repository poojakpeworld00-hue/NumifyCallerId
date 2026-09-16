package com.callerid.numberlookup.home.permission

/**
 * Immutable data models for the global permission engine.
 *
 * A [PermissionRule] is a single entry parsed from the `permission_engine`
 * Firebase Remote Config object. A [PermissionSpec] is the engine's static
 * knowledge of *how* a given OS permission is requested: its Android permission
 * string, and the SDK level below which the system grants it implicitly.
 *
 * Supporting an entirely new permission later means adding a [PermissionSpec] to
 * [PermissionUtils.CATALOG] and referencing its key from Remote Config. No other
 * code has to change.
 */

/** One permission requirement, exactly as declared in Remote Config. */
data class PermissionRule(
    /** Config key, e.g. `"notification"` / `"phone_state"`. Maps to a [PermissionSpec]. */
    val key: String,
    /** Master on/off switch for this rule. */
    val enabled: Boolean,
    /** Simple class names of the Activities this permission targets. */
    val activities: List<String>,
    /** Delay (ms) to wait before showing the request once the screen opens. */
    val delayMs: Long,
    /** Lower value = shown first when several rules match the same Activity. */
    val priority: Int,
    /** When true the request is offered at most once for the whole install. */
    val showOnce: Boolean,
)

/** Static description of an OS permission the engine knows how to request. */
data class PermissionSpec(
    /** Config key this spec is bound to. */
    val key: String,
    /** The `android.permission.*` string handed to the OS. */
    val androidPermission: String,
    /** Runtime permission is only required on this SDK level and above. */
    val minSdk: Int,
    /**
     * An optional business gate: the `AdPreferenceStore` boolean key that has to
     * be `true` before this permission may be requested at all. Null means no
     * gate, which is the case for every spec today. Reserve it for permissions
     * serving a flag-controlled surface and nothing else - never for one the OS
     * needs in order to deliver a core signal (see the `phone_state` note in
     * [PermissionUtils]).
     */
    val enabledPrefGate: String? = null,
)
