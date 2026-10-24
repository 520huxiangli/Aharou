package com.aharou.core.config

import androidx.annotation.WorkerThread

/**
 * One configurable setting. Concrete kinds: [com.aharou.core.config.fields.DataStoreBoolField]
 * etc. for DataStore-backed scalars; [com.aharou.core.config.fields.ClosureField]
 * when the storage backend is something more bespoke (a repository, a DB row, an
 * in-process singleton). Mirrors iOS `ConfigField` (Shared/Config/ConfigField.swift).
 */
interface ConfigField {
    /** Dot-path id, e.g. `appearance.theme` or `models.<uuid>.maxOutputTokens`. */
    val path: String

    /** Human-readable label rendered in the confirm dialog and topic-help. */
    val displayName: String

    /** One-line description rendered in topic-help. */
    val description: String

    val valueSchema: ConfigSchema
    val access: ConfigAccess
    val risk: ConfigRisk

    /** Whether `audit-revert` may roll a write back. */
    val revertable: Boolean

    /** Read the current value. May throw [ConfigError] on backend failure. */
    @WorkerThread
    fun read(): ConfigValue

    /** Apply [value]. Schema validation runs in the bridge before this. */
    @WorkerThread
    fun write(value: ConfigValue)

    /** Top-level scope = first dot segment. Used for audit `--scope` filtering. */
    val scope: String
        get() = path.substringBefore('.', missingDelimiterValue = "unknown")
}
