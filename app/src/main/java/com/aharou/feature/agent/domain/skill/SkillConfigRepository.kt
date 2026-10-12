package com.aharou.feature.agent.domain.skill

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
 * 技能启停配置持久化，支持全局 + 项目级两级：
 * - 全局：当前执行环境的 `~/.aharou/skills.json`（跨项目保留）；
 * - 项目级：`workspacePath/.aharou/skills.json`（随工作区走，可 git 追踪）。
 *
 * 格式：`{"disabled": ["skill-a", "skill-b"]}`，仅存「禁用名单」这一个事实；
 * 生效禁用集合 = 全局 + 项目并集。每次读取都从磁盘加载，外部手工编辑即时生效；
 * 名单中不存在的技能名在过滤时天然被忽略，无需清理。
 */
@Singleton
class SkillConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAharouRoot,
    private val fileChangeHub: FileChangeHub,
    private val fileAccess: FileAccessProvider
) {
    /** 本地全局配置文件，仅用于宿主文件变更监听。 */
    private fun globalFile(): File = File(containerInstaller.aharouDir, CONFIG_FILE)

    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/skills.json`。 */
    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    /** 当前生效的禁用技能名集合（全局 + 项目并集，归一化为小写）。 */
    fun disabledNames(): Set<String> {
        val global = readGlobalDisabled()
        val project = readDisabled(projectFile())
        return (global + project).map { it.lowercase() }.toSet()
    }

    /** 在指定作用域的配置中启用/禁用某个技能。 */
    fun setDisabled(name: String, disabled: Boolean, scope: SkillScope) {
        val names = (if (scope == SkillScope.GLOBAL) readGlobalDisabled() else readDisabled(projectFile()))
            .toMutableSet()
        if (disabled) names.add(name) else names.remove(name)
        if (scope == SkillScope.GLOBAL) {
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

    // ── 外部变更监听：容器内/手工直接增删改技能目录或 skills.json 后，数秒内通知 UI 刷新 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 技能目录或配置文件被外部修改时广播一次。订阅驱动：只在有订阅者（设置页）期间才由
     * [FileChangeHub] 监听技能目录与两个 skills.json，无人订阅时零开销。
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(SKILLS_DIR, recursive = true),
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AHAROU_DIR/$SKILLS_DIR", recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AHAROU_DIR")
    ).mapNotNull { batch ->
        if (!batch.changes.any(::isSkillChange)) return@mapNotNull null
        FileLogger.i(TAG, "检测到技能目录或配置变化，已通知刷新")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /** 技能相关变更：两个 skills.json，或全局/项目技能目录自身及其下的任何文件。 */
    private fun isSkillChange(change: FileChange): Boolean {
        val path = change.hostPath
        if (path == globalFile().absolutePath) return true
        if (path == projectFile().absolutePath) return true
        val globalSkills = File(containerInstaller.aharouDir, SKILLS_DIR).absolutePath
        if (path == globalSkills || path.startsWith("$globalSkills/")) return true
        val projectSkills = File(projectAicodeRoot.current(), SKILLS_DIR).absolutePath
        return path == projectSkills || path.startsWith("$projectSkills/")
    }

    companion object {
        private const val TAG = "SkillConfigRepository"
        private const val CONFIG_FILE = "skills.json"
        private const val GLOBAL_CONFIG_PATH = "~/.aharou/$CONFIG_FILE"
        private const val AHAROU_DIR = ".aharou"
        private const val SKILLS_DIR = "skills"

        fun parseDisabled(raw: String): Set<String> = DisabledNamesStore.parse(raw, TAG, "技能配置")

        fun serializeDisabled(names: Set<String>): String = DisabledNamesStore.serialize(names)

        fun readDisabled(file: File): Set<String> = DisabledNamesStore.read(file, TAG, "技能配置")

        fun writeDisabled(file: File, names: Set<String>) = DisabledNamesStore.write(file, names, TAG)
    }
}
