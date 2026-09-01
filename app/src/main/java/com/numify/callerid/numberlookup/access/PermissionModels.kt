package com.numify.callerid.numberlookup.access

/**
 * Immutable data models for the global Permission Engine.
 *
 * A [PermissionRule] is one entry parsed out of the `permission_engine`
 * Firebase Remote Config object. A [PermissionSpec] is the engine's static
 * knowledge of *how* to request a given OS permission (its Android permission
 * string and the SDK level below which it is implicitly granted).
 *
 * To support a brand-new permission in the future you only add a
 * [PermissionSpec] to [PermissionUtils.CATALOG] and reference its key from
 * Remote Config — no other code changes are required.
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
     * Optional business gate: an `AdPreferenceStore` boolean key that must be `true`
     * for this permission to ever be requested. Null = no gate, which is the
     * case for every spec today. Use it only for permissions that serve a
     * flag-controlled surface exclusively — never for one the OS requires to
     * deliver a core signal (see the note on `phone_state` in [PermissionUtils]).
     */
    val enabledPrefGate: String? = null,
)
