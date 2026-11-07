package com.aharou.feature.editor.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EditorSessionManager {
    data class Snapshot(
        val content: String,
        val dirty: Boolean,
        val cursorLine: Int,
        val cursorColumn: Int,
        val scrollY: Int,
        val baselineContent: String = content
    )

    private val sessions = mutableMapOf<String, Session>()

    @Synchronized
    fun forWorkspace(identity: String): Session {
        require(identity.isNotBlank())
        return sessions.getOrPut(identity) { Session() }
    }

    class Session internal constructor() {
        private val _tabs = MutableStateFlow<List<String>>(emptyList())
        val tabs: StateFlow<List<String>> = _tabs.asStateFlow()
        private val _activePath = MutableStateFlow("")
        val activePath: StateFlow<String> = _activePath.asStateFlow()
        private val _snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
        val snapshots: StateFlow<Map<String, Snapshot>> = _snapshots.asStateFlow()

        fun open(path: String) {
            if (path.isBlank()) return
            if (path !in _tabs.value) _tabs.value = _tabs.value + path
            _activePath.value = path
        }

        fun activate(path: String) {
            if (path in _tabs.value && _activePath.value != path) _activePath.value = path
        }

        fun close(path: String) {
            val remaining = _tabs.value.filterNot { it == path }
            _tabs.value = remaining
            _snapshots.value = _snapshots.value - path
            if (_activePath.value == path) _activePath.value = remaining.lastOrNull() ?: ""
        }

        fun snapshot(path: String): Snapshot? = _snapshots.value[path]

        fun saveSnapshot(path: String, snapshot: Snapshot) {
            _snapshots.value = _snapshots.value + (path to snapshot)
        }

        fun forget(path: String) {
            _snapshots.value = _snapshots.value - path
        }

        fun markSaved(path: String, content: String) {
            val snapshot = _snapshots.value[path] ?: return
            _snapshots.value = _snapshots.value + (path to snapshot.copy(
                baselineContent = content,
                dirty = snapshot.content != content
            ))
        }
    }
}
