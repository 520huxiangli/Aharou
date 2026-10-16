package com.aharou.feature.agent.domain.memory

internal enum class MemorySyncAction {
    KEEP, UPLOAD, DOWNLOAD, DELETE_LOCAL, DELETE_REMOTE, CONFLICT
}

internal data class MemorySyncChange(
    val path: String,
    val localHash: String?,
    val remoteHash: String?,
    val action: MemorySyncAction,
)

internal object MemorySyncReconciler {
    fun plan(
        baseline: Map<String, String>,
        local: Map<String, String>,
        remote: Map<String, String>,
    ): List<MemorySyncChange> = (baseline.keys + local.keys + remote.keys).sorted().map { path ->
        val before = baseline[path]
        val here = local[path]
        val there = remote[path]
        val action = when {
            here == there -> MemorySyncAction.KEEP
            before == null && here == null -> MemorySyncAction.DOWNLOAD
            before == null && there == null -> MemorySyncAction.UPLOAD
            before == null -> MemorySyncAction.CONFLICT
            here == before && there == null -> MemorySyncAction.DELETE_LOCAL
            there == before && here == null -> MemorySyncAction.DELETE_REMOTE
            here == before -> MemorySyncAction.DOWNLOAD
            there == before -> MemorySyncAction.UPLOAD
            else -> MemorySyncAction.CONFLICT
        }
        MemorySyncChange(path, here, there, action)
    }

    fun advance(
        previous: Map<String, String>,
        agreed: Map<String, String>,
        owned: (String) -> Boolean,
        completed: Boolean,
    ): Map<String, String> = if (!completed) previous.toMap() else {
        previous.filterKeys { !owned(it) } + agreed.filterKeys(owned)
    }
}
