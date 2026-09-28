package com.aharou.core.config

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for every configurable setting in the app.
 *
 * Add a new setting in three steps:
 *   1. Pick a dot-path id (`appearance.theme`, `browser.uaProfile`, …).
 *   2. Construct a [ConfigField] (or a [ConfigCollection] for dynamic
 *      children) and register it in `ConfigBuiltins`.
 *   3. Done — the offload bridge, confirmation gate, audit log, revert
 *      flow, and topic-help output all derive from this registry.
 *
 * Mirrors iOS `ConfigRegistry`. The Android side is plain mutable maps
 * (no actor isolation needed — handler threads are the writers and the
 * registry is initialized once at boot before any reads).
 */
class ConfigRegistry private constructor() {
    private val fields = LinkedHashMap<String, ConfigField>()
    private val collections = LinkedHashMap<String, ConfigCollection>()
    private val initialized = AtomicBoolean(false)

    fun register(field: ConfigField) {
        fields[field.path] = field
    }

    fun register(collection: ConfigCollection) {
        collections[collection.basePath] = collection
    }

    /**
     * Look up a field by path. Handles both flat fields and collection
     * children (`<base>.<id>.<sub>`). Returns null for unknown paths.
     */
    fun resolveField(path: String): ConfigField? {
        fields[path]?.let { return it }
        // Collection child lookup. Collection base paths are not always a
        // single segment (`remote.connections`, `modelgroup.groups`), so
        // match the longest registered base that prefixes `path` instead
        // of assuming the first dot-delimited segment is the base. The id
        // is the segment right after the base; the leaf may itself contain
        // dots (e.g. `models.<uuid>.modality.video`).
        val base = collections.keys
            .filter { path.startsWith("$it.") }
            .maxByOrNull { it.length }
            ?: return null
        val rest = path.substring(base.length + 1)
        val id = rest.substringBefore('.')
        if (id.isEmpty()) return null
        return collections.getValue(base).fields(forId = id).firstOrNull { it.path == path }
    }

    fun collection(basePath: String): ConfigCollection? = collections[basePath]

    /**
     * If [path] addresses a whole record inside a collection (`<base>.<id>`)
     * rather than a leaf field, return that collection and the id. Used by
     * `config get` so `<base>.<id>` lists the record's fields instead of
     * failing — `resolveField` only ever resolves leaves.
     */
    fun collectionRecord(path: String): Pair<ConfigCollection, String>? {
        if (fields.containsKey(path)) return null
        val base = collections.keys
            .filter { path.startsWith("$it.") }
            .maxByOrNull { it.length }
            ?: return null
        val rest = path.substring(base.length + 1)
        // 多一段就是叶子（`<base>.<id>.<sub>`），交给 resolveField。
        if (rest.isEmpty() || rest.contains('.')) return null
        val coll = collections.getValue(base)
        if (rest !in coll.childIds()) return null
        return coll to rest
    }

    /** All registered top-level field paths (excluding hidden), sorted. */
    fun allVisibleFieldPaths(): List<String> =
        fields.values
            .filter { it.access != ConfigAccess.HIDDEN }
            .map { it.path }
            .sorted()

    /** Topic names = unique first segments of every visible path / collection base. Sorted. */
    fun topics(): List<String> {
        val set = LinkedHashSet<String>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            val head = f.path.substringBefore('.', missingDelimiterValue = "")
            if (head.isNotEmpty()) set.add(head)
        }
        for (c in collections.values) set.add(c.basePath)
        return set.sorted()
    }

    /**
     * All visible fields whose path equals `<topic>` (the bare topic
     * name — e.g. an aggregate `providers` summary) or starts with
     * `<topic>.`. When [topic] matches a registered collection, a
     * representative child's fields (using the first child id) are
     * also included so `topic-help <collection>` surfaces the per-
     * child schema instead of an empty list. Mirrors iOS.
     */
    fun fields(topic: String): List<ConfigField> {
        val out = ArrayList<ConfigField>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            if (f.path == topic || f.path.startsWith("$topic.")) out.add(f)
        }
        val coll = collections[topic]
        if (coll != null) {
            val firstId = coll.childIds().firstOrNull()
            if (firstId != null) {
                out.addAll(coll.fields(forId = firstId).filter { it.access != ConfigAccess.HIDDEN })
            }
        }
        return out.sortedBy { it.path }
    }

    companion object {
        /**
         * Process-wide singleton. The first caller to invoke [init] wins;
         * subsequent calls are no-ops so idempotent registration is safe.
         */
        @Volatile private var INSTANCE: ConfigRegistry? = null

        fun get(): ConfigRegistry =
            INSTANCE ?: error("ConfigRegistry not initialized; call init() from Application.onCreate")

        fun init(context: Context): ConfigRegistry {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val r = ConfigRegistry()
                if (r.initialized.compareAndSet(false, true)) {
                    ConfigBuiltins.registerInto(r, context)
                }
                INSTANCE = r
                return r
            }
        }
    }
}
