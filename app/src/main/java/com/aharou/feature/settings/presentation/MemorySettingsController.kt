package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryArchiveInfo
import com.aharou.feature.agent.domain.memory.MemoryCompactReport
import com.aharou.feature.agent.domain.memory.MemoryCompactor
import com.aharou.feature.agent.domain.memory.MemoryDistiller
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 「立即蒸馏」的结果。 */
enum class DistillOutcome { WRITTEN, NOTHING }

/**
 * 记忆写操作（保存 / 删除 / 恢复归档）的失败原因；null = 没有待提示的失败。
 *
 * 给枚举而不是文案：界面文案在 strings.xml，这一层不硬编码中文。
 */
enum class MemoryActionError { NO_WORKSPACE, SAVE_FAILED, DELETE_FAILED, RESTORE_FAILED }

/**
 * 设置页「记忆」分区的状态与操作。
 *
 * 从 SettingsViewModel 抽出来：那个类逼近单文件行数门禁，而记忆这一块与其它设置项没有耦合。
 * 只在这里做数据读取与判定，界面负责展示与提示文案。
 */
@Singleton
class MemorySettingsController @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryCompactor: MemoryCompactor,
    private val memoryDistiller: MemoryDistiller,
    private val workspaceRepository: WorkspaceRepository,
) {
    /** 当前浏览的作用域：全局 / 项目。 */
    private val _scope = MutableStateFlow(MemoryScope.GLOBAL)
    val scope: StateFlow<MemoryScope> = _scope.asStateFlow()

    /** 当前作用域下的记忆条目；项目作用域无工作区时为空。 */
    private val _entries = MutableStateFlow<List<Memory>>(emptyList())
    val entries: StateFlow<List<Memory>> = _entries.asStateFlow()

    /** 当前作用域的归档旧版本（覆盖 / 合并前留的底）。 */
    private val _archives = MutableStateFlow<List<MemoryArchiveInfo>>(emptyList())
    val archives: StateFlow<List<MemoryArchiveInfo>> = _archives.asStateFlow()

    private val _actionError = MutableStateFlow<MemoryActionError?>(null)
    val actionError: StateFlow<MemoryActionError?> = _actionError.asStateFlow()

    /** 整理记忆是否在跑，以及本次结果（合并了几条）。 */
    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting.asStateFlow()

    private val _compactResult = MutableStateFlow<MemoryCompactReport?>(null)
    val compactResult: StateFlow<MemoryCompactReport?> = _compactResult.asStateFlow()

    /** 「立即蒸馏」是否正在跑，以及结果提示（消费后清空）。 */
    private val _distilling = MutableStateFlow(false)
    val distilling: StateFlow<Boolean> = _distilling.asStateFlow()

    private val _distillResult = MutableStateFlow<DistillOutcome?>(null)
    val distillResult: StateFlow<DistillOutcome?> = _distillResult.asStateFlow()

    /** 项目记忆所属工作区；null 表示当前没有工作区，项目作用域此时不可浏览。 */
    fun projectRoot(): String? = workspaceRepository.currentPath().takeIf { it.isNotBlank() }

    fun setScope(scope: MemoryScope) {
        if (_scope.value == scope) return
        _scope.value = scope
        refresh()
    }

    /** 重扫当前作用域的条目与归档：设置页每次回到前台、以及每次写操作之后都调。 */
    fun refresh() {
        val scope = _scope.value
        val projectRoot = projectRoot()
        _entries.value = if (scope == MemoryScope.PROJECT && projectRoot == null) {
            emptyList()
        } else {
            runCatching {
                // 按作用域严格取（同名不折叠）：另一个作用域的同名条目不该在这里冒出来。
                memoryRepository.listMemories(projectRoot, scope)
            }.getOrDefault(emptyList())
        }
        _archives.value = runCatching {
            memoryRepository.listArchive(scope, projectRoot)
        }.getOrDefault(emptyList())
    }

    /** 界面提示完写失败后调用。 */
    fun consumeActionError() {
        _actionError.value = null
    }

    suspend fun save(
        name: String,
        description: String,
        content: String,
        type: MemoryType?,
        pending: Boolean? = null
    ): Boolean {
        val scope = _scope.value
        val projectRoot = projectRoot()
        if (scope == MemoryScope.PROJECT && projectRoot == null) {
            _actionError.value = MemoryActionError.NO_WORKSPACE
            return false
        }
        val saved = withContext(Dispatchers.IO) {
            memoryRepository.saveMemory(name, description, content, scope, projectRoot, type, pending = pending)
        }
        _actionError.value = if (saved) null else MemoryActionError.SAVE_FAILED
        if (saved) refresh()
        return saved
    }

    suspend fun delete(name: String): Boolean {
        val scope = _scope.value
        val projectRoot = projectRoot()
        if (scope == MemoryScope.PROJECT && projectRoot == null) {
            _actionError.value = MemoryActionError.NO_WORKSPACE
            return false
        }
        val deleted = withContext(Dispatchers.IO) { memoryRepository.deleteMemory(name, scope, projectRoot) }
        _actionError.value = if (deleted) null else MemoryActionError.DELETE_FAILED
        if (deleted) refresh()
        return deleted
    }

    suspend fun restore(fileName: String): Boolean {
        val scope = _scope.value
        val projectRoot = projectRoot()
        if (scope == MemoryScope.PROJECT && projectRoot == null) {
            _actionError.value = MemoryActionError.NO_WORKSPACE
            return false
        }
        val restored = withContext(Dispatchers.IO) { memoryRepository.restoreArchive(fileName, scope, projectRoot) }
        _actionError.value = if (restored) null else MemoryActionError.RESTORE_FAILED
        if (restored) refresh()
        return restored
    }

    /**
     * 整理记忆：把同一主题的重复条目合并成一条。
     * 全局与项目各跑一遍——两个作用域之间不互相改写。
     */
    suspend fun compact() {
        _compacting.value = true
        val projectRoot = projectRoot()
        val report = runCatching {
            val global = memoryCompactor.compact(null, MemoryScope.GLOBAL)
            val project = if (projectRoot != null) {
                memoryCompactor.compact(projectRoot, MemoryScope.PROJECT)
            } else {
                MemoryCompactReport()
            }
            MemoryCompactReport(
                clusters = global.clusters + project.clusters,
                merged = global.merged + project.merged,
                archived = global.archived + project.archived,
                skipped = global.skipped + project.skipped
            )
        }.getOrDefault(MemoryCompactReport())
        _compacting.value = false
        _compactResult.value = report
        refresh()
    }

    fun consumeCompactResult() {
        _compactResult.value = null
    }

    /**
     * 手动跑一次记忆蒸馏，忽略 7 天间隔。
     *
     * 全程不抛异常：蒸馏失败不应该让设置页崩，失败与「没有新内容」都归于同一提示。
     */
    suspend fun distillNow() {
        _distilling.value = true
        val wrote = runCatching { memoryDistiller.distillIfDue(force = true) }.getOrDefault(false)
        _distilling.value = false
        _distillResult.value = if (wrote) DistillOutcome.WRITTEN else DistillOutcome.NOTHING
    }

    fun consumeDistillResult() {
        _distillResult.value = null
    }
}
