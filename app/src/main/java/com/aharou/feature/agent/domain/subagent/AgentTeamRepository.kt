package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 团队预设仓库，聚合全局与项目级两级目录；同名预设项目级覆盖全局（与子代理定义、技能一致）。
 *
 * 全局 `~/.aharou/teams`、项目级 `<workspace>/.aharou/teams`，都以容器路径经 [FileAccessProvider] 访问，
 * 跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 */
@Singleton
class AgentTeamRepository @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    val globalRoot: String = "~/.aharou/teams"
    val projectRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/teams"

    /** 全部预设（含来源作用域），按名称排序。 */
    fun listAll(): List<AgentTeamEntry> = mergeAll(scan(globalRoot), scan(projectRoot))

    /** 按名称查找（忽略大小写）。 */
    fun find(name: String): AgentTeam? {
        val target = name.trim()
        if (target.isEmpty()) return null
        return listAll().firstOrNull { it.team.name.equals(target, ignoreCase = true) }?.team
    }

    /** 删除指定作用域的预设文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.scope == scope && it.team.name.equals(name.trim(), ignoreCase = true)
        } ?: return false
        val filePath = entry.team.filePath ?: return false
        if (!fileAccess.isFile(filePath)) return false
        return runCatching { fileAccess.delete(filePath); true }.getOrDefault(false)
    }

    /** 指定作用域的预设目录（容器路径）。 */
    fun teamsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) globalRoot else projectRoot

    /**
     * 写入预设文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；
     * 改了名就写新文件再删旧文件，等价于重命名。返回 null 表示成功。
     */
    fun save(form: AgentTeamForm, originalName: String? = null): AgentTeamSaveError? {
        val name = form.name.trim()
        if (!AgentDefinitionRepository.isValidName(name)) return AgentTeamSaveError.INVALID_NAME

        val validStages = form.stages.filter { it.tasks.isNotEmpty() }
        if (validStages.isEmpty()) return AgentTeamSaveError.EMPTY_STAGES
        form.stages.forEach { stage ->
            stage.tasks.forEach { task ->
                val hasMember = task.agent.isNotBlank() || task.department.isNotBlank() || task.agentQuery.isNotBlank()
                if (task.prompt.isBlank() || !hasMember) return AgentTeamSaveError.INCOMPLETE_TASK
            }
        }

        val scope = form.scope
        // 作用域是文件所在目录，编辑时不允许换：改名但换了目录等于搬文件，界面已按新建时锁住。
        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = listAll().any {
                it.scope == scope && it.team.name.equals(name, ignoreCase = true)
            }
            if (taken) return AgentTeamSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            listAll().firstOrNull {
                it.scope == scope && it.team.name.equals(old, ignoreCase = true)
            }?.team?.filePath
        }

        return try {
            val root = teamsRoot(scope).trimEnd('/')
            // 名字未改时写回原文件，不能按 name 重拼：文件名可能与 frontmatter 的 name 大小写不同。
            val target = existingFile?.takeIf { overwritingSelf && fileAccess.isFile(it) }
                ?: "$root/$name.md"
            fileAccess.writeFile(target, AgentTeamParser.serialize(form), overwrite = true)
            if (!overwritingSelf && existingFile != null && existingFile != target && fileAccess.isFile(existingFile)) {
                runCatching { fileAccess.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "重命名后删除旧团队预设失败: $existingFile")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存团队预设失败: $name", e)
            AgentTeamSaveError.IO_FAILED
        }
    }

    /**
     * 目录扫描：只取顶层 `*.md`；目录不存在或不可读（远程断连）降级为「没有预设」，
     * 不让 IOException 冒泡成未捕获异常。
     */
    private fun scan(root: String): List<AgentTeam> = runCatching {
        if (!fileAccess.isDirectory(root)) {
            emptyList()
        } else {
            val base = root.trimEnd('/')
            fileAccess.listFiles(root)
                .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                .mapNotNull { entry -> AgentTeamParser.parse(fileAccess, "$base/${entry.name}") }
                .sortedBy { it.name.lowercase() }
        }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描团队预设目录失败（$root）：${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "AgentTeamRepository"

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(
            global: List<AgentTeam>,
            project: List<AgentTeam>
        ): List<AgentTeamEntry> {
            val byName = LinkedHashMap<String, AgentTeamEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentTeamEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentTeamEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.team.name.lowercase() }
        }
    }
}
