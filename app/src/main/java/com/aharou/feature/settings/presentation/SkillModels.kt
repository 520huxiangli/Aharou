package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.skill.SkillImportReport
import com.aharou.feature.agent.domain.skill.SkillSaveError
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.market.MarketSkill
import com.aharou.feature.agent.domain.skill.market.SkillMarketRepository
import java.io.File

/** 技能列表页的 UI 状态：技能 + 来源作用域 + 启停状态。 */
data class SkillUiEntry(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val disabled: Boolean,
    val instructions: String,
    /** 手写技能里的 `required_tools`，编辑页不展示，保存时原样写回。 */
    val requiredTools: List<String> = emptyList(),
    /** 内置技能：随 App 打包，不可编辑、删除或停用。 */
    val builtin: Boolean = false
)

/** 技能编辑页的保存结果：UI 据此决定是退回列表还是就地报错。 */
sealed interface SkillSaveState {
    data object Idle : SkillSaveState
    data object Saving : SkillSaveState
    data object Saved : SkillSaveState
    data class Failed(val error: SkillSaveError) : SkillSaveState
}

/** 技能导入的 UI 状态：空闲 / 导入中 / 完成（含结果报告）。 */
sealed interface SkillImportState {
    data object Idle : SkillImportState
    data object Running : SkillImportState
    data class Done(val report: SkillImportReport) : SkillImportState
}

/** 技能导出的 UI 状态：空闲 / 打包中 / 就绪（UI 拿文件弹系统分享）/ 失败。 */
sealed interface SkillExportState {
    data object Idle : SkillExportState
    data object Running : SkillExportState
    data class Ready(val file: File, val skillName: String) : SkillExportState
    data object Failed : SkillExportState
}

/** 市场列表里的一条技能：技能本体 + 已装 / 可更新标记。 */
data class MarketSkillUi(
    val skill: MarketSkill,
    val installed: Boolean,
    val hasUpdate: Boolean
)

/** 市场页的提示；文案由 UI 层映射成资源串。 */
enum class MarketAlert {
    InvalidAddress,
    NoSkills,
    NoMatchInRepo,
    LoadFailed,
    SkillSiteNeedsDetail,
    MarketNotLoaded,
    TranslateUnavailable,
}

/** 市场详情页的状态。[detail] 为 null 表示还在拉或没拉到。 */
data class MarketDetailUi(
    val ui: MarketSkillUi,
    val loading: Boolean,
    val detail: SkillMarketRepository.SkillDetail?
)

/** 「粘贴地址」临时源在源列表里的 id。 */
internal const val ADHOC_MARKET_ID = "adhoc"

/** 技能导出 zip 落在缓存目录下的子目录名。 */
internal const val EXPORT_DIR = "skill-export"

/** 导入时认的文件扩展名。 */
internal val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "txt")

/** 压缩包导入认的扩展名。 */
internal val ZIP_EXTENSIONS = setOf("zip")

/** 文件名是否带白名单里的扩展名（大小写不敏感）。 */
internal fun String.hasExtension(exts: Set<String>): Boolean {
    val dot = lastIndexOf('.')
    if (dot <= 0 || dot == lastIndex) return false
    return substring(dot + 1).lowercase() in exts
}
