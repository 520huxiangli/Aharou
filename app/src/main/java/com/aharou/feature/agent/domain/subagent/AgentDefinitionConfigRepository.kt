package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.core.watch.FileChange
import com.aharou.core.watch.FileChangeHub
import com.aharou.feature.agent.domain.common.DisabledNamesStore
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn

/**
 * 子代理启停配置持久化，与技能的 `skills.json` 同一套约定，支持全局 + 项目级两级：
 * - 全局：当前执行环境的 `~/.aharou/agents.json`（跨项目保留）；
 * - 项目级：`workspacePath/.aharou/agents.json`（随工作区走，可 git 追踪）。
 *
 * 格式 `{"disabled": ["name-a"]}`，只存禁用名单；生效禁用集合 = 全局 + 项目并集。
 * 每次读取都从磁盘加载，外部手工编辑即时生效；名单里已不存在的子代理名在过滤时天然被忽略。
 */
@Singleton
class AgentDefinitionConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAharouRoot,
    private val fileChangeHub: FileChangeHub,
    private val fileAccess: FileAccessProvider
) {
    /** 本地全局配置文件，仅用于宿主文件变更监听。 */
    private fun globalFile(): File = File(containerInstaller.aharouDir, CONFIG_FILE)

    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    // ── 外部变更监听：容器内/手工直接增删改子代理目录或 agents.json 后，数秒内通知 UI 刷新 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 子代理目录或配置文件被外部修改时广播一次。订阅驱动：只在有订阅者（设置页）期间才由
     * [FileChangeHub] 监听子代理目录与两个 agents.json，无人订阅时零开销。
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(AGENTS_DIR, recursive = true),
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AHAROU_DIR/$AGENTS_DIR", recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AHAROU_DIR")
    ).mapNotNull { batch ->
        if (!batch.changes.any(::isAgentChange)) return@mapNotNull null
        FileLogger.i(TAG, "检测到子代理目录或配置变化，已通知刷新")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /** 子代理相关变更：两个 agents.json，或全局/项目子代理目录自身及其下的任何文件。 */
    private fun isAgentChange(change: FileChange): Boolean {
        val path = change.hostPath
        if (path == globalFile().absolutePath) return true
        if (path == projectFile().absolutePath) return true
        val globalAgents = File(containerInstaller.aharouDir, AGENTS_DIR).absolutePath
        if (path == globalAgents || path.startsWith("$globalAgents/")) return true
        val projectAgents = File(projectAicodeRoot.current(), AGENTS_DIR).absolutePath
        return path == projectAgents || path.startsWith("$projectAgents/")
    }

    /** 当前生效的禁用子代理名集合（全局 + 项目并集，归一化为小写）。 */
    fun disabledNames(): Set<String> =
        (readGlobalDisabled() + readDisabled(projectFile())).map { it.lowercase() }.toSet()

    /** 在指定作用域的配置中启用/禁用某个子代理。 */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) {
        val names = (if (scope == AgentDefinitionScope.GLOBAL) readGlobalDisabled() else readDisabled(projectFile()))
            .toMutableSet()
        if (disabled) names.add(name) else names.removeAll { it.equals(name, ignoreCase = true) }
        if (scope == AgentDefinitionScope.GLOBAL) {
            fileAccess.writeFile(GLOBAL_CONFIG_PATH, serializeDisabled(names))
        } else {
            writeDisabled(projectFile(), names)
        }
    }

    private fun readGlobalDisabled(): Set<String> = runCatching {
        if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) parseDisabled(fileAccess.readFile(GLOBAL_CONFIG_PATH))
        else emptySet()
    }.getOrElse {
        FileLogger.w(TAG, "读取 $CONFIG_FILE 失败: ${it.message}")
        emptySet()
    }

    companion object {
        private const val TAG = "AgentDefinitionConfigRepository"
        private const val CONFIG_FILE = "agents.json"
        private const val GLOBAL_CONFIG_PATH = "~/.aharou/$CONFIG_FILE"
        private const val AHAROU_DIR = ".aharou"
        private const val AGENTS_DIR = "agents"

        internal fun parseDisabled(raw: String): Set<String> = DisabledNamesStore.parse(raw, TAG, "子代理配置")

        internal fun serializeDisabled(names: Set<String>): String = DisabledNamesStore.serialize(names)

        private fun readDisabled(file: File): Set<String> = DisabledNamesStore.read(file, TAG, "子代理配置")

        private fun writeDisabled(file: File, names: Set<String>) = DisabledNamesStore.write(file, names, TAG)
    }
}
