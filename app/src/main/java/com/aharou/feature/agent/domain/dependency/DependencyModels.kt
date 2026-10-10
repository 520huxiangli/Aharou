package com.aharou.feature.agent.domain.dependency

/**
 * 依赖版本声明的来源形态，决定「改哪一行、怎么改」。
 *
 * 同一个坐标可能以多种形态出现（catalog 的 `[versions]` + `[libraries]`、或构建脚本里的内联字符串、
 * 或 `libs.foo` 别名），解析器统一产出 [DependencyDeclaration]，由 [DependencyChecker] 去重后查询。
 */
enum class DeclarationType {
    /** `libs.versions.toml` 的 `[versions]` 条目（被 libraries/plugins 以 version.ref 引用）。 */
    CATALOG_VERSION,

    /** `[libraries]` 条目自带内联版本（字符串 `"g:a:v"` 或 `version = "v"`）。 */
    CATALOG_LIBRARY,

    /** `[plugins]` 条目自带内联版本。 */
    CATALOG_PLUGIN,

    /** `[bundles]` 里的成员（仅展开记录，版本仍由其引用的 library 条目承载）。 */
    BUNDLE_MEMBER,

    /** 构建脚本里的内联坐标字符串（`implementation("g:a:v")`）。 */
    BUILD_STRING,

    /** 构建脚本里被坐标字符串引用的版本变量（`val ver = "1.2.3"`）。 */
    BUILD_STRING_VAR,

    /** 构建脚本里的 `libs.foo` 别名引用（版本在 catalog 里，不在此处编辑）。 */
    BUILD_ALIAS,

    /** 构建脚本里的 `id("x") version "1.2.3"`。 */
    PLUGIN_ID_VERSION,

    UNKNOWN
}

/**
 * 一条「可升级的版本声明」。位置信息用于生成 [SuggestedEdit]；[currentVersion] 是解析出的字面量，
 * 动态版本（`1.+`）或变量解析失败时为 null。
 */
data class DependencyDeclaration(
    val group: String,
    val artifact: String,
    val currentVersion: String?,
    /** catalog 的 `version.ref` 别名，仅 catalog 形态非空。 */
    val versionRef: String? = null,
    /** catalog 里该条目的别名（versions key 或 library alias）。 */
    val catalogAlias: String? = null,
    /** 所在依赖配置（如 `implementation`），catalog 形态为空。 */
    val configuration: String? = null,
    val declarationType: DeclarationType,
    val filePath: String,
    /** 1 基行号。 */
    val line: Int,
    /** 版本字面量在该行内的起止列（0 基，闭开区间），用于 UI 高亮；解析不到为 0。 */
    val columnStart: Int = 0,
    val columnEnd: Int = 0,
    /** 整行原文（不含行尾换行）。生成编辑时作为 old_string 的基底。 */
    val originalText: String = ""
)

/** 单条依赖的检查结论。 */
enum class UpdateStatus {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    /** 版本为变量/别名但解析不出字面量，无法查询。 */
    UNKNOWN_VERSION,
    /** 已联网查询但所有仓库都不可达。 */
    FAILED_TO_RESOLVE,
    /** 动态版本（`1.+`、`latest.release` 等），不联网查询。 */
    DYNAMIC_VERSION,
    /** 仓库可达但没有该坐标的元数据（404 / 无版本列表）。 */
    NOT_FOUND_IN_REPO
}

/** 建议的编辑：交给现有 `editFile` 工具写回（old_string/new_string 精确匹配）。 */
data class SuggestedEdit(
    val file: String,
    val oldString: String,
    val newString: String
)

/** 一条解析不了、但看起来像依赖声明的行，必须回传给模型/用户，不得静默吞掉。 */
data class UnparsedLine(
    val filePath: String,
    val line: Int,
    val text: String,
    val reason: String
)

/** 单条声明的检查结果。 */
data class DependencyUpdateResult(
    val declaration: DependencyDeclaration,
    val status: UpdateStatus,
    /** 仓库返回的最新版本（含预发布）。 */
    val latestVersion: String? = null,
    /** 仓库元数据里的 `<release>`。 */
    val releaseVersion: String? = null,
    /** 比对后推荐的目标版本（>= 当前且为正式版）。 */
    val latestStable: String? = null,
    /** 命中该版本的仓库 URL。 */
    val repository: String? = null,
    val edit: SuggestedEdit? = null,
    val error: String? = null
)

/** 一次扫描的完整报告。 */
data class DependencyScanReport(
    val rootPath: String,
    val scannedFiles: List<String>,
    val repositories: List<String>,
    val results: List<DependencyUpdateResult>,
    val unparsed: List<UnparsedLine>,
    /** 是否至少有一个仓库查询成功（无查询时视为 true）。 */
    val networkOk: Boolean,
    val scannedAt: Long
)
