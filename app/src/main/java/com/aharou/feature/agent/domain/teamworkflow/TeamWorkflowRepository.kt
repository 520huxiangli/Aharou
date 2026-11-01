package com.aharou.feature.agent.domain.teamworkflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作流预设仓库，聚合全局与项目级两级目录；同名预设项目级覆盖全局（与团队预设、子代理定义一致）。
 *
 * 全局 `~/.aharou/workflows`、项目级 `<workspace>/.aharou/workflows`，都以容器路径经
 * [FileAccessProvider] 访问，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 *
 * 与团队仓库分开不合并：工作流引用团队，团队不引用工作流，拆成两个目录才能让「只有单个团队」
 * 的环境完全不出现工作流文件。
 */
@Singleton
class TeamWorkflowRepository @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val teamRepository: AgentTeamRepository
) {
    val globalRoot: String = "~/.aharou/workflows"
    val projectRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/workflows"

    /** 全部预设（含来源作用域），按名称排序。 */
    fun listAll(): List<TeamWorkflowEntry> = mergeAll(scan(globalRoot), scan(projectRoot))

    /** 按名称查找（忽略大小写）。 */
    fun find(name: String): TeamWorkflow? {
        val target = name.trim()
        if (target.isEmpty()) return null
        return listAll().firstOrNull { it.workflow.name.equals(target, ignoreCase = true) }?.workflow
    }

    /** 删除指定作用域的预设文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.scope == scope && it.workflow.name.equals(name.trim(), ignoreCase = true)
        } ?: return false
        val filePath = entry.workflow.filePath ?: return false
        if (!fileAccess.isFile(filePath)) return false
        return runCatching { fileAccess.delete(filePath); true }.getOrDefault(false)
    }

    /** 指定作用域的预设目录（容器路径）。 */
    fun workflowsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) globalRoot else projectRoot

    /**
     * 写入预设文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；
     * 改了名就写新文件再删旧文件，等价于重命名。返回 null 表示成功。
     *
     * 团队引用在这里一次性校验：工作流引用了不存在或已禁用的团队，跑起来第一阶段就会
     * `TEAM_NOT_FOUND` 全链失败，放在保存时就挡住。
     */
    fun save(form: TeamWorkflowForm, originalName: String? = null): TeamWorkflowSaveError? {
        val name = form.name.trim()
        if (!AgentDefinitionRepository.isValidName(name)) return TeamWorkflowSaveError.INVALID_NAME

        val refs = form.teams.filter { it.team.isNotBlank() }
        if (refs.size < 2) return TeamWorkflowSaveError.TOO_FEW_TEAMS
        if (form.teams.any { it.team.isBlank() }) return TeamWorkflowSaveError.EMPTY_TEAM_REF

        val seen = HashSet<String>()
        refs.forEach { ref ->
            if (!seen.add(ref.team.trim().lowercase())) return TeamWorkflowSaveError.DUPLICATE_TEAM
        }

        val available = runCatching { teamRepository.listAll() }.getOrElse { e ->
            FileLogger.e(TAG, "读取团队预设失败，无法校验工作流引用", e)
            return TeamWorkflowSaveError.IO_FAILED
        }
        val availableNames = available.map { it.team.name.lowercase() }.toSet()
        if (refs.any { it.team.trim().lowercase() !in availableNames }) {
            return TeamWorkflowSaveError.UNKNOWN_TEAM
        }

        val scope = form.scope
        // 作用域是文件所在目录，编辑时不允许换：改名但换了目录等于搬文件，界面已按新建时锁住。
        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = listAll().any {
                it.scope == scope && it.workflow.name.equals(name, ignoreCase = true)
            }
            if (taken) return TeamWorkflowSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            listAll().firstOrNull {
                it.scope == scope && it.workflow.name.equals(old, ignoreCase = true)
            }?.workflow?.filePath
        }

        return try {
            val root = workflowsRoot(scope).trimEnd('/')
            // 名字未改时写回原文件，不能按 name 重拼：文件名可能与 frontmatter 的 name 大小写不同。
            val target = existingFile?.takeIf { overwritingSelf && fileAccess.isFile(it) }
                ?: "$root/$name.md"
            fileAccess.writeFile(target, TeamWorkflowParser.serialize(form), overwrite = true)
            if (!overwritingSelf && existingFile != null && existingFile != target && fileAccess.isFile(existingFile)) {
                runCatching { fileAccess.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "重命名后删除旧工作流预设失败: $existingFile")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存工作流预设失败: $name", e)
            TeamWorkflowSaveError.IO_FAILED
        }
    }

    /**
     * 目录扫描：只取顶层 `*.md`；目录不存在或不可读（远程断连）降级为「没有预设」，
     * 不让 IOException 冒泡成未捕获异常。
     */
    private fun scan(root: String): List<TeamWorkflow> = runCatching {
        if (!fileAccess.isDirectory(root)) {
            emptyList()
        } else {
            val base = root.trimEnd('/')
            fileAccess.listFiles(root)
                .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                .mapNotNull { entry -> TeamWorkflowParser.parse(fileAccess, "$base/${entry.name}") }
                .sortedBy { it.name.lowercase() }
        }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描工作流预设目录失败（$root）：${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "TeamWorkflowRepository"

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(
            global: List<TeamWorkflow>,
            project: List<TeamWorkflow>
        ): List<TeamWorkflowEntry> {
            val byName = LinkedHashMap<String, TeamWorkflowEntry>()
            global.forEach { byName[it.name.lowercase()] = TeamWorkflowEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = TeamWorkflowEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.workflow.name.lowercase() }
        }
    }
}
