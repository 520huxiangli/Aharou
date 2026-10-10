package com.aharou.feature.agent.domain.dependency

import com.aharou.core.net.AharouMavenIndexClient
import com.aharou.core.net.MavenMetadataParser
import com.aharou.core.net.MavenRepositoryClient
import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 编排「依赖版本检查」：扫描 → 解析（catalog + 构建脚本）→ 去重坐标 → 并发查询 → 比对 → 生成编辑建议。
 *
 * 设计约束：
 * - 同一坐标只查一次网络；
 * - 网络全挂时不整体失败，仍返回解析结果（`networkOk=false`，各项降级为 UNKNOWN_VERSION）；
 * - 动态版本（`1.+`）不联网。
 */
@Singleton
class DependencyChecker @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val scanner: GradleProjectScanner,
    private val repositoryClient: MavenRepositoryClient,
    private val indexClient: AharouMavenIndexClient
) {

    private val catalogParser = VersionCatalogParser()
    private val scriptParser = GradleScriptParser()

    private data class Coordinate(val group: String, val artifact: String)

    private data class FetchOutcome(
        val ok: Boolean,
        val metadata: MavenMetadataParser.Metadata?,
        val repository: String?,
        val reachedNetwork: Boolean,
        val error: String?
    )

    suspend fun check(
        projectRoot: String,
        subPath: String?,
        refresh: Boolean
    ): DependencyScanReport = withContext(Dispatchers.IO) {
        val files = scanner.scan(projectRoot, subPath)
        val access = fileAccess.forWorkspace(projectRoot)

        val declarations = ArrayList<DependencyDeclaration>()
        val unparsed = ArrayList<UnparsedLine>()
        val gradleContents = ArrayList<String>()

        files.catalogPath?.let { path ->
            val content = readText(access, path)
            if (content == null) {
                unparsed.add(UnparsedLine(path, 0, "", "无法读取版本目录文件"))
            } else {
                val model = catalogParser.parse(content, path)
                unparsed.addAll(model.unparsed)
                declarations.addAll(catalogDeclarations(model, path))
            }
        }

        files.gradleFiles.forEach { path ->
            val content = readText(access, path)
            if (content == null) {
                unparsed.add(UnparsedLine(path, 0, "", "无法读取构建脚本"))
                return@forEach
            }
            gradleContents.add(content)
            val parsed = scriptParser.parse(content, path)
            declarations.addAll(parsed.declarations)
            unparsed.addAll(parsed.unparsed)
        }

        val repositories = GradleRepositoryParser.collect(gradleContents)

        // 去重坐标：同一坐标只查一次
        val byCoordinate = LinkedHashMap<Coordinate, MutableList<DependencyDeclaration>>()
        declarations.forEach { declaration ->
            byCoordinate.getOrPut(Coordinate(declaration.group, declaration.artifact)) { ArrayList() }
                .add(declaration)
        }
        val coordinates = byCoordinate.keys.toList()

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val outcomes: List<FetchOutcome> = coroutineScope {
            coordinates.map { coordinate ->
                async {
                    semaphore.withPermit { fetchCoordinate(coordinate, repositories, refresh) }
                }
            }.awaitAll()
        }
        val networkOk = coordinates.isEmpty() || outcomes.any { it.reachedNetwork }

        val results = ArrayList<DependencyUpdateResult>()
        coordinates.forEachIndexed { index, coordinate ->
            val outcome = outcomes[index]
            byCoordinate[coordinate].orEmpty().forEach { declaration ->
                results.add(buildResult(declaration, outcome, networkOk))
            }
        }
        results.sortWith(RESULT_ORDER)

        FileLogger.d(
            TAG,
            "检查完成 root=${files.rootPath} 声明=${declarations.size} 坐标=${coordinates.size} " +
                "可升级=${results.count { it.status == UpdateStatus.UPDATE_AVAILABLE }} networkOk=$networkOk"
        )

        DependencyScanReport(
            rootPath = files.rootPath,
            scannedFiles = files.scannedFiles,
            repositories = repositories,
            results = results,
            unparsed = unparsed,
            networkOk = networkOk,
            scannedAt = System.currentTimeMillis()
        )
    }

    private suspend fun fetchCoordinate(
        coordinate: Coordinate,
        repositories: List<String>,
        refresh: Boolean
    ): FetchOutcome {
        var sawNotFound = false
        var lastError: String? = null
        for (repository in repositories) {
            when (val result = repositoryClient.fetch(repository, coordinate.group, coordinate.artifact, refresh)) {
                is MavenRepositoryClient.MetadataResult.Success ->
                    return FetchOutcome(true, result.metadata, result.repository, true, null)

                is MavenRepositoryClient.MetadataResult.NotFound -> {
                    sawNotFound = true
                    lastError = "仓库中不存在该坐标：${result.repository}"
                }

                is MavenRepositoryClient.MetadataResult.Failure -> {
                    lastError = result.error
                }
            }
        }
        // 实时仓库全部不可达时回退自建离线索引。索引是快照、会滞后，所以只做兜底不当主源：
        // 若把它排在前面，会把「其实有新版本」误判成已是最新。
        indexClient.lookup(coordinate.group, coordinate.artifact, refresh)?.let { metadata ->
            FileLogger.d(TAG, "命中离线索引：${coordinate.group}:${coordinate.artifact}")
            return FetchOutcome(true, metadata, INDEX_REPOSITORY, true, null)
        }
        val reachedNetwork = sawNotFound
        return FetchOutcome(false, null, null, reachedNetwork, lastError)
    }

    private fun buildResult(
        declaration: DependencyDeclaration,
        outcome: FetchOutcome,
        networkOk: Boolean
    ): DependencyUpdateResult {
        val current = declaration.currentVersion
        if (current.isNullOrBlank()) {
            return DependencyUpdateResult(declaration, UpdateStatus.UNKNOWN_VERSION, error = "无法解析当前版本")
        }
        if (isDynamicVersion(current)) {
            return DependencyUpdateResult(declaration, UpdateStatus.DYNAMIC_VERSION)
        }

        if (!outcome.ok) {
            if (outcome.reachedNetwork) {
                return DependencyUpdateResult(
                    declaration,
                    UpdateStatus.NOT_FOUND_IN_REPO,
                    error = outcome.error
                )
            }
            val status = if (networkOk) UpdateStatus.FAILED_TO_RESOLVE else UpdateStatus.UNKNOWN_VERSION
            return DependencyUpdateResult(declaration, status, error = outcome.error ?: "网络不可用")
        }

        val metadata = outcome.metadata ?: return DependencyUpdateResult(
            declaration, UpdateStatus.NOT_FOUND_IN_REPO, repository = outcome.repository, error = "元数据为空"
        )
        val allVersions = (metadata.versions + listOfNotNull(metadata.latest, metadata.release)).distinct()
        if (allVersions.isEmpty()) {
            return DependencyUpdateResult(
                declaration, UpdateStatus.NOT_FOUND_IN_REPO, repository = outcome.repository, error = "仓库未返回任何版本"
            )
        }

        val latestStable = MavenVersionComparator.highestStable(allVersions)
        val latestOverall = MavenVersionComparator.latest(allVersions)

        return if (latestStable != null && MavenVersionComparator.compare(latestStable, current) > 0) {
            DependencyUpdateResult(
                declaration = declaration,
                status = UpdateStatus.UPDATE_AVAILABLE,
                latestVersion = latestOverall,
                releaseVersion = metadata.release,
                latestStable = latestStable,
                repository = outcome.repository,
                edit = buildEdit(declaration, latestStable)
            )
        } else {
            DependencyUpdateResult(
                declaration = declaration,
                status = UpdateStatus.UP_TO_DATE,
                latestVersion = latestOverall,
                releaseVersion = metadata.release,
                latestStable = latestStable,
                repository = outcome.repository
            )
        }
    }

    private fun buildEdit(declaration: DependencyDeclaration, newVersion: String): SuggestedEdit? {
        val current = declaration.currentVersion ?: return null
        val old = declaration.originalText
        if (old.isBlank()) return null

        val newString = if (declaration.columnStart in 0 until declaration.columnEnd &&
            declaration.columnEnd <= old.length
        ) {
            old.substring(0, declaration.columnStart) + newVersion + old.substring(declaration.columnEnd)
        } else {
            val index = old.indexOf(current)
            if (index < 0) return null
            old.substring(0, index) + newVersion + old.substring(index + current.length)
        }
        if (newString == old) return null
        return SuggestedEdit(file = declaration.filePath, oldString = old, newString = newString)
    }

    private fun catalogDeclarations(
        model: VersionCatalogParser.CatalogModel,
        filePath: String
    ): List<DependencyDeclaration> {
        val result = ArrayList<DependencyDeclaration>()
        val usedVersionLines = HashSet<Int>()

        model.libraries.forEach { library ->
            val group = library.group
            val artifact = library.artifact
            if (group.isNullOrBlank() || artifact.isNullOrBlank()) return@forEach
            val ref = library.versionRef
            if (!ref.isNullOrBlank()) {
                val version = model.versions[ref] ?: return@forEach
                if (usedVersionLines.add(version.line)) {
                    result.add(
                        DependencyDeclaration(
                            group = group,
                            artifact = artifact,
                            currentVersion = version.version,
                            versionRef = ref,
                            catalogAlias = version.alias,
                            declarationType = DeclarationType.CATALOG_VERSION,
                            filePath = filePath,
                            line = version.line,
                            columnStart = tokenIndex(version.rawLine, version.version),
                            columnEnd = tokenEnd(version.rawLine, version.version),
                            originalText = version.rawLine
                        )
                    )
                }
            } else {
                val inline = library.inlineVersion ?: return@forEach
                result.add(
                    DependencyDeclaration(
                        group = group,
                        artifact = artifact,
                        currentVersion = inline,
                        catalogAlias = library.alias,
                        declarationType = DeclarationType.CATALOG_LIBRARY,
                        filePath = filePath,
                        line = library.line,
                        columnStart = tokenIndex(library.rawLine, inline),
                        columnEnd = tokenEnd(library.rawLine, inline),
                        originalText = library.rawLine
                    )
                )
            }
        }

        model.plugins.forEach { plugin ->
            val id = plugin.id ?: return@forEach
            val ref = plugin.versionRef
            if (!ref.isNullOrBlank()) {
                val version = model.versions[ref] ?: return@forEach
                if (usedVersionLines.add(version.line)) {
                    result.add(
                        DependencyDeclaration(
                            group = id,
                            artifact = "$id.gradle.plugin",
                            currentVersion = version.version,
                            versionRef = ref,
                            catalogAlias = version.alias,
                            declarationType = DeclarationType.CATALOG_VERSION,
                            filePath = filePath,
                            line = version.line,
                            columnStart = tokenIndex(version.rawLine, version.version),
                            columnEnd = tokenEnd(version.rawLine, version.version),
                            originalText = version.rawLine
                        )
                    )
                }
            } else {
                val inline = plugin.inlineVersion ?: return@forEach
                result.add(
                    DependencyDeclaration(
                        group = id,
                        artifact = "$id.gradle.plugin",
                        currentVersion = inline,
                        catalogAlias = plugin.alias,
                        declarationType = DeclarationType.CATALOG_PLUGIN,
                        filePath = filePath,
                        line = plugin.line,
                        columnStart = tokenIndex(plugin.rawLine, inline),
                        columnEnd = tokenEnd(plugin.rawLine, inline),
                        originalText = plugin.rawLine
                    )
                )
            }
        }

        return result
    }

    private fun tokenIndex(rawLine: String, token: String): Int {
        if (token.isEmpty()) return 0
        val anchor = rawLine.indexOf('=').takeIf { it >= 0 } ?: 0
        val index = rawLine.indexOf(token, anchor)
        return if (index >= 0) index else rawLine.indexOf(token)
    }

    private fun tokenEnd(rawLine: String, token: String): Int {
        val start = tokenIndex(rawLine, token)
        return if (start >= 0) start + token.length else 0
    }

    private fun readText(access: FileAccessProvider, path: String): String? =
        runCatching { access.readFile(path) }.getOrNull()

    private fun isDynamicVersion(version: String): Boolean {
        val v = version.trim()
        return v == "latest.release" || v == "latest.integration" || v == "latest.milestone" ||
            v == "+" || v.endsWith("+") || (v.endsWith("+") && v.contains('.'))
    }

    private companion object {
        const val TAG = "DependencyChecker"
        const val MAX_CONCURRENCY = 6

        /** 结果 [DependencyUpdateResult.repository] 填这个值，表明版本来自自建离线索引。 */
        const val INDEX_REPOSITORY = "aharou-index"

        val RESULT_ORDER = compareBy<DependencyUpdateResult>(
            { it.status != UpdateStatus.UPDATE_AVAILABLE },
            { it.status.ordinal },
            { it.declaration.filePath },
            { it.declaration.line }
        )
    }
}
