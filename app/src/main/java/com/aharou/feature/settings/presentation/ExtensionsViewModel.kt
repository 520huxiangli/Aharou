package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.market.SkillInstallRepository
import com.aharou.feature.extension.domain.ExtensionEntry
import com.aharou.feature.extension.domain.ExtensionKind
import com.aharou.feature.extension.domain.ExtensionState
import com.aharou.feature.extension.domain.InstalledExtensionAggregator
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.export.ExportedFile
import com.aharou.feature.plugin.export.McpServerExporter
import com.aharou.feature.plugin.export.SkillPackageExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「扩展」统一入口页的状态。
 *
 * 只做发现与展示：技能 / 知识库 / 连接器各自的安装机制差别太大（见
 * [InstalledExtensionAggregator] 的说明），本页不碰安装，只让 UI 跳回它们各自的原页。
 * 插件自有一套完整面板（列表 / 商店 / 开发者侧载），不再在这里重复一份。
 */
@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val aggregator: InstalledExtensionAggregator,
    private val pluginManager: PluginManager,
    private val skillRepository: SkillRepository,
    private val skillInstalls: SkillInstallRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val skillExporter: SkillPackageExporter,
    private val mcpExporter: McpServerExporter,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<ExtensionEntry>>(emptyList())
    val entries: StateFlow<List<ExtensionEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null 表示「全部」。 */
    private val _kindFilter = MutableStateFlow<ExtensionKind?>(null)
    val kindFilter: StateFlow<ExtensionKind?> = _kindFilter.asStateFlow()

    /** 导出进行中：UI 用它禁掉导出按钮，避免连点打出好几个同名包。 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** 已落盘、等着 UI 弹分享面板的产物。 */
    private val _exportedFile = MutableStateFlow<ExportedFile?>(null)
    val exportedFile: StateFlow<ExportedFile?> = _exportedFile.asStateFlow()

    /** 导出失败：只提示，不影响列表里其它操作。 */
    private val _exportFailed = MutableStateFlow(false)
    val exportFailed: StateFlow<Boolean> = _exportFailed.asStateFlow()

    /** 有本地内容可导的条目 id：没装的、没有磁盘目录的技能不给导出按钮，点了也只会失败。 */
    private val _exportableIds = MutableStateFlow<Set<String>>(emptySet())
    val exportableIds: StateFlow<Set<String>> = _exportableIds.asStateFlow()

    /** 过滤后的列表：先按类型、再按关键词（名称 + 描述，忽略大小写）。 */
    val visibleEntries: StateFlow<List<ExtensionEntry>> =
        combine(_entries, _query, _kindFilter) { entries, query, kind ->
            val keyword = query.trim()
            entries.filter { entry ->
                (kind == null || entry.kind == kind) &&
                    (
                        keyword.isEmpty() ||
                            entry.name.contains(keyword, ignoreCase = true) ||
                            entry.description.contains(keyword, ignoreCase = true)
                        )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refresh()
        // 插件列表是异步装配的：扩展页先于它打开时，一次性聚合会一直停在空列表。
        // 订阅变化后重新聚合，免得用户看到「没有匹配的扩展」却不知道插件其实已经装上。
        viewModelScope.launch {
            pluginManager.plugins.drop(1).collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                withContext(Dispatchers.IO) {
                    // 导出资格得看技能目录/安装状态，跟列表一次算完，不让 UI 层再碰仓库
                    val entries = aggregator.installed()
                    entries to exportableIds(entries)
                }
            }
                .onSuccess { (entries, exportable) ->
                    _entries.value = entries
                    _exportableIds.value = exportable
                }
                .onFailure { _error.value = it.message ?: it::class.java.simpleName }
            _loading.value = false
        }
    }

    private fun exportableIds(entries: List<ExtensionEntry>): Set<String> {
        // 技能要查目录：随 App 打包、或只是引用远端仓库的技能没有本地文件可导
        val skillDirs = runCatching {
            skillRepository.listAllSkills().associate { it.skill.name.lowercase() to it.skill.dirPath }
        }.getOrDefault(emptyMap())
        return entries.filter { entry ->
            entry.state != ExtensionState.AVAILABLE && when (entry.kind) {
                ExtensionKind.SKILL -> !skillDirs[entry.name.lowercase()].isNullOrBlank()
                ExtensionKind.MCP -> true
                else -> false
            }
        }.map { it.id }.toSet()
    }

    fun search(query: String) {
        _query.value = query
    }

    fun setKindFilter(kind: ExtensionKind?) {
        _kindFilter.value = kind
    }

    /**
     * 插件的启用 / 停用与卸载。
     *
     * 只对 `plugin:` 前缀的条目生效：技能、MCP、知识库、连接器各有自己的安装形态，
     * 由 [onOpenOriginal] 跳回原页处理，本页不代劳。
     */
    fun setPluginEnabled(entryId: String, enabled: Boolean) {
        val pluginId = pluginIdOf(entryId) ?: return
        viewModelScope.launch {
            runCatching { pluginManager.setEnabled(pluginId, enabled) }
                .onFailure { _error.value = it.message ?: it::class.java.simpleName }
            _entries.value = runCatching { withContext(Dispatchers.IO) { aggregator.installed() } }
                .getOrElse { _entries.value }
        }
    }

    fun uninstallPlugin(entryId: String) {
        val pluginId = pluginIdOf(entryId) ?: return
        viewModelScope.launch {
            runCatching { pluginManager.uninstall(pluginId) }
                .onFailure { _error.value = it.message ?: it::class.java.simpleName }
            _entries.value = runCatching { withContext(Dispatchers.IO) { aggregator.installed() } }
                .getOrElse { _entries.value }
        }
    }

    private fun pluginIdOf(entryId: String): String? =
        entryId.takeIf { it.startsWith(PLUGIN_PREFIX) }?.removePrefix(PLUGIN_PREFIX)

    /**
     * 导出技能 / MCP 配置，成功后由 UI 弹系统分享。
     *
     * 只认 SKILL / MCP 两类：插件有自己的发布流程，知识库与连接器没有可打包的本地内容。
     * 失败只置一次标记（不抛、不改列表），导出不该把整页拖挂。
     */
    fun exportEntry(entryId: String) {
        if (_exporting.value) return
        val entry = _entries.value.firstOrNull { it.id == entryId } ?: return
        viewModelScope.launch {
            _exporting.value = true
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    when (entry.kind) {
                        ExtensionKind.SKILL -> exportSkill(entry)
                        ExtensionKind.MCP -> exportMcp(entry)
                        else -> null
                    }
                }.getOrNull()
            }
            _exporting.value = false
            if (exported == null) _exportFailed.value = true else _exportedFile.value = exported
        }
    }

    /** UI 弹过分享面板后调，避免重组时重复弹。 */
    fun clearExportedFile() {
        _exportedFile.value = null
    }

    fun clearExportFailure() {
        _exportFailed.value = false
    }

    private suspend fun exportSkill(entry: ExtensionEntry): ExportedFile? {
        val name = entry.id.removePrefix(SKILL_PREFIX)
        // 用仓库里的完整 Skill（带目录与正文）而不是行上的展示字段：导出要的是整目录
        val skill = skillRepository.listAllSkills()
            .firstOrNull { it.skill.name.equals(name, ignoreCase = true) }
            ?.skill ?: return null
        // 安装记录里的源比界面上的更可靠：本地手写的技能没有记录，退回行上的来源标签
        val source = skillInstalls.record(name)?.source?.takeIf { it.isNotBlank() } ?: entry.sourceLabel
        val file = skillExporter.export(skill, source) ?: return null
        return ExportedFile(file, "application/zip", file.name)
    }

    private suspend fun exportMcp(entry: ExtensionEntry): ExportedFile? {
        val name = entry.id.removePrefix(MCP_PREFIX)
        // 按名取生效配置（全局 + 项目合并后那份），导出的才是用户眼前这一条
        val server = mcpConfigRepository.findEntry(name)?.server ?: return null
        val file = mcpExporter.export(server) ?: return null
        return ExportedFile(file, "application/json", file.name)
    }

    private companion object {
        const val PLUGIN_PREFIX = "plugin:"
        const val SKILL_PREFIX = "skill:"
        const val MCP_PREFIX = "mcp:"
    }
}
