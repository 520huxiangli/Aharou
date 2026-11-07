package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val restoreLock = Any()
    private val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    private val _ignoredPatterns = MutableStateFlow(
        prefs.getString("ignored_patterns", ".git,node_modules,build,dist,.gradle,.idea,.cxx,.vscode,tmp")!!
    )
    val ignoredPatterns: StateFlow<String> = _ignoredPatterns.asStateFlow()

    private val _useGitIgnore = MutableStateFlow(
        prefs.getBoolean("use_gitignore", true)
    )
    val useGitIgnore: StateFlow<Boolean> = _useGitIgnore.asStateFlow()

    private val _maxSyncBatchSize = MutableStateFlow(
        prefs.getInt("max_sync_batch_size", 5)
    )
    val maxSyncBatchSize: StateFlow<Int> = _maxSyncBatchSize.asStateFlow()

    fun setIgnoredPatterns(patterns: String) = synchronized(restoreLock) {
        prefs.edit { putString("ignored_patterns", patterns) }
        _ignoredPatterns.value = patterns
    }

    fun setUseGitIgnore(use: Boolean) = synchronized(restoreLock) {
        prefs.edit { putBoolean("use_gitignore", use) }
        _useGitIgnore.value = use
    }

    fun setMaxSyncBatchSize(size: Int) = synchronized(restoreLock) {
        prefs.edit { putInt("max_sync_batch_size", size) }
        _maxSyncBatchSize.value = size
    }

    /** 备份快照：同步偏好三键。 */
    fun snapshot(): SyncSettingsSnapshot = SyncSettingsSnapshot(
        ignoredPatterns = _ignoredPatterns.value,
        useGitIgnore = _useGitIgnore.value,
        maxSyncBatchSize = _maxSyncBatchSize.value
    )

    internal fun restoreBackupState(snapshot: SyncSettingsSnapshot, recovery: java.io.File): suspend () -> Unit = synchronized(restoreLock) {
        val names = listOf("ignored_patterns", "use_gitignore", "max_sync_batch_size")
        val before = names.associateWith { prefs.all[it] }
        val expected = mapOf<String, Any?>(
            names[0] to snapshot.ignoredPatterns, names[1] to snapshot.useGitIgnore,
            names[2] to snapshot.maxSyncBatchSize
        )
        fun write(values: Map<String, Any?>): Boolean {
            val edit = prefs.edit()
            values.forEach { (key, value) ->
                when (value) {
                    null -> edit.remove(key)
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                }
            }
            return edit.commit()
        }
        fun refresh() {
            _ignoredPatterns.value = prefs.getString(names[0], ".git,node_modules,build,dist,.gradle,.idea,.cxx,.vscode,tmp")!!
            _useGitIgnore.value = prefs.getBoolean(names[1], true)
            _maxSyncBatchSize.value = prefs.getInt(names[2], 5)
        }
        writeBackupPreferenceRecovery(recovery, before, expected)
        if (!write(expected)) {
            val restored = write(before)
            refresh()
            val failure = java.io.IOException("Cannot persist sync restore")
            if (!restored) failure.addSuppressed(java.io.IOException("Sync settings rollback failed"))
            throw failure
        }
        refresh()
        val rollback: suspend () -> Unit = {
            synchronized(restoreLock) {
                val eligible = before.filterKeys { prefs.all[it] == expected[it] }
                val written = write(eligible)
                refresh()
                if (!written || eligible.size != before.size) throw java.io.IOException("Sync settings rollback failed or conflicted")
            }
        }
        rollback
    }

    /** 从备份还原同步偏好。 */
    fun restore(snapshot: SyncSettingsSnapshot) {
        setIgnoredPatterns(snapshot.ignoredPatterns)
        setUseGitIgnore(snapshot.useGitIgnore)
        setMaxSyncBatchSize(snapshot.maxSyncBatchSize)
    }
}

/** 同步偏好的可序列化快照。 */
@kotlinx.serialization.Serializable
data class SyncSettingsSnapshot(
    val ignoredPatterns: String,
    val useGitIgnore: Boolean,
    val maxSyncBatchSize: Int
)
