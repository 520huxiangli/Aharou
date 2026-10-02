package com.aharou.feature.editor.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 编辑器标签页会话：记录打开过哪些文件、当前激活哪个，以及每个标签页的编辑快照。
 *
 * 进程内单例（与 [com.aharou.feature.terminal.domain.TerminalSessionManager] 同款做法）——
 * 编辑器页在窄屏走 NavHost 路由、大屏挂在右栏，两条路径都会重新组合 Screen，
 * 状态放 Screen 里会被清掉，放这里才能「从文件树再打开一个文件时，上一个文件还在」。
 *
 * [Snapshot.content] 是切走时的编辑器文本，**优先于磁盘内容**用于恢复：用户改了一半
 * 切去看别的文件，切回来改动不能消失。
 */
object EditorSessionManager {

    /** 单个标签页的编辑快照。 */
    data class Snapshot(
        val content: String,
        val dirty: Boolean,
        val cursorLine: Int,
        val cursorColumn: Int,
        val scrollY: Int
    )

    private val _tabs = MutableStateFlow<List<String>>(emptyList())
    val tabs: StateFlow<List<String>> = _tabs.asStateFlow()

    private val _activePath = MutableStateFlow("")
    val activePath: StateFlow<String> = _activePath.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, Snapshot>> = _snapshots.asStateFlow()

    /** 打开文件：已在标签栏里则只激活，否则追加到末尾后激活。 */
    fun open(path: String) {
        if (path.isBlank()) return
        if (path !in _tabs.value) {
            _tabs.value = _tabs.value + path
        }
        _activePath.value = path
    }

    fun activate(path: String) {
        if (path in _tabs.value && _activePath.value != path) {
            _activePath.value = path
        }
    }

    /** 关闭标签页：同时丢掉它的快照；关掉的是激活页时自动切到相邻一个。 */
    fun close(path: String) {
        val remaining = _tabs.value.filterNot { it == path }
        _tabs.value = remaining
        _snapshots.value = _snapshots.value - path
        if (_activePath.value == path) {
            _activePath.value = remaining.lastOrNull() ?: ""
        }
    }

    fun snapshot(path: String): Snapshot? = _snapshots.value[path]

    fun saveSnapshot(path: String, snapshot: Snapshot) {
        _snapshots.value = _snapshots.value + (path to snapshot)
    }

    fun forget(path: String) {
        if (_snapshots.value.containsKey(path)) {
            _snapshots.value = _snapshots.value - path
        }
    }
}
