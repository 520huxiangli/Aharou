package com.aharou.feature.extension.domain

import android.content.Context
import com.aharou.feature.agent.domain.knowledge.KnowledgeCatalog
import com.aharou.feature.agent.domain.knowledge.KnowledgeSourceRepository
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.market.SkillInstallRepository
import com.aharou.feature.connectors.domain.ConnectorLibrary
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.model.PluginStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 四类扩展的汇总视图：给统一入口页的列表用。
 *
 * 「已安装」在四类里本来含义不同——插件是容器里跑的服务、技能是落在全局或项目目录下的一堆文件、
 * 知识库只是引用了一个远端仓库、连接器压根不装（内置模板）。所以这里只把它们渲染成同一张卡片的
 * 形状，状态与措辞仍按各自的口径换算，谁也不迁就谁。
 */
@Singleton
class InstalledExtensionAggregator @Inject constructor(
    private val pluginManager: PluginManager,
    private val skillRepository: SkillRepository,
    private val skillInstalls: SkillInstallRepository,
    private val knowledgeCatalog: KnowledgeCatalog,
    private val knowledgeSources: KnowledgeSourceRepository,
    @param:ApplicationContext private val context: Context,
) {

    /** 全部已有扩展：插件 + 技能 + 知识库源 + 内置连接器。UI 层按 [ExtensionEntry.kind] 分组。 */
    suspend fun installed(): List<ExtensionEntry> = buildList {
        addAll(pluginEntries())
        addAll(skillEntries())
        addAll(knowledgeEntries())
        addAll(connectorEntries())
    }

    private suspend fun pluginEntries(): List<ExtensionEntry> =
        pluginManager.listInstalled().map { plugin ->
            val manifest = plugin.manifest
            ExtensionEntry(
                id = "plugin:${manifest.id}",
                kind = ExtensionKind.PLUGIN,
                name = manifest.name,
                description = manifest.description,
                author = manifest.publisher,
                version = manifest.version,
                sourceLabel = manifest.homepage.ifBlank { manifest.id },
                state = when (plugin.status) {
                    PluginStatus.RUNNING -> ExtensionState.RUNNING
                    PluginStatus.FAILED -> ExtensionState.FAILED
                    else -> ExtensionState.INSTALLED
                },
                detail = plugin.detail,
            )
        }

    private suspend fun skillEntries(): List<ExtensionEntry> {
        // 版本与来源只有安装记录里有——技能文件本身不带这些，本地手写的技能就查不到记录
        val records = skillInstalls.all()
        return skillRepository.listAllSkills().map { entry ->
            val name = entry.skill.name
            val record = records[name.lowercase()]
            ExtensionEntry(
                id = "skill:$name",
                kind = ExtensionKind.SKILL,
                name = name,
                description = entry.skill.description,
                version = record?.version.orEmpty(),
                sourceLabel = record?.source.orEmpty(),
                state = ExtensionState.INSTALLED,
                detail = entry.scope.name,
            )
        }
    }

    private fun knowledgeEntries(): List<ExtensionEntry> {
        val custom = knowledgeSources.all().keys
        return knowledgeCatalog.load().sources.map { (id, def) ->
            ExtensionEntry(
                id = "knowledge:$id",
                kind = ExtensionKind.KNOWLEDGE,
                name = def.displayName(language),
                description = def.displayDescription(language),
                sourceLabel = def.repo,
                detail = def.branch,
                // 用户自己加的与出厂源分开标：出厂源删不掉，用户源可以
                state = if (id in custom) ExtensionState.INSTALLED else ExtensionState.AVAILABLE,
            )
        }
    }

    private fun connectorEntries(): List<ExtensionEntry> =
        ConnectorLibrary.load(context).connectors.map { connector ->
            ExtensionEntry(
                id = "connector:${connector.id}",
                kind = ExtensionKind.CONNECTOR,
                name = connector.name,
                description = connector.about.resolve(language),
                sourceLabel = connector.category,
                detail = connector.auth.kind,
            )
        }

    /**
     * 界面语言。不能用 `Locale.getDefault()`：per-app 语言下进程 Locale 未必跟着 App 走，
     * 系统英文 + App 选中文时会挑到英文那份名字（技能市场踩过同一个坑）。
     */
    private val language: String
        get() = context.resources.configuration.locales[0].language
}