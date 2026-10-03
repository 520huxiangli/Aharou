package com.aharou.feature.agent.domain.skill.market

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.skill.SkillImportReport
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 技能市场的读写：列技能、下载技能包、安装、查更新。
 *
 * 两类源的差别只在「怎么拿到技能列表」：
 * - `index` 型：拉源里的 JSON 索引，元数据直接可读；
 * - `directory` 型：先用平台的目录接口拿整棵文件树，筛出含 SKILL.md 的目录，
 *   再逐个读 SKILL.md 的 frontmatter 补元数据。
 *
 * 平台差异全部交给 [SkillRepoAccess]，`directory` 型每个技能要读一次 SKILL.md，
 * 所以列表结果按源缓存在磁盘上——不然每进一次市场都要重跑几十个请求。
 */
@Singleton
class SkillMarketRepository @Inject constructor(
    private val catalog: SkillMarketCatalog,
    private val installRepository: SkillInstallRepository,
    private val skillRepository: SkillRepository,
    @param:ApplicationContext private val context: Context
) {
    // 与 RepoDataFetcher.DEFAULT_CLIENT 保持一致的代理与超时配置：
    // 用户配了上游代理时，裸 client 会连不通（缺 proxyAuthenticator）。
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .build()

    /** 并发上限：CDN 对同 IP 并发请求会限流，一次打几十个会集体超时。 */
    private val fetchLimiter = Semaphore(permits = 5)

    /** 已配置的源：id → 定义（保持 JSON 顺序）。 */
    fun sources(): Map<String, SkillMarketSourceDef> = catalog.load().sources

    /**
     * App 内当前语言（跟随系统或用户在设置里选的）。
     * 不能用 [java.util.Locale.getDefault]：per-app 语言下进程 Locale 未必跟着 App 走，
     * 系统英文 + App 选中文时会挑到英文那份描述。
     */
    private val appLanguage: String
        get() = context.resources.configuration.locales[0].language

    /**
     * 列出某个源下可安装的全部技能。
     * 12 小时内的结果直接读磁盘缓存（列表要读几十个 SKILL.md，重跑一次很慢），
     * 缓存过期或没有才走网络；网络失败返回空列表，不抛。
     */
    /**
     * 列出某个源下可安装的全部技能。
     *
     * 目录型源要逐个读 SKILL.md 才拿得到描述，像 WorkBuddy 这种 295 个技能的源要等很久。
     * 所以先把技能名铺出来（只需一次目录请求），再在后台逐个补描述，
     * 每补一批就通过 [onUpdate] 回调一次，UI 可以边看边填。
     *
     * 结果按源缓存 12 小时；缓存命中时直接返回，不回调。[useCache] 为 false 时跳过缓存重新拉（手动刷新）。
     */
    suspend fun listSkills(
        sourceId: String,
        onUpdate: ((List<MarketSkill>) -> Unit)? = null,
        useCache: Boolean = true
    ): MarketListing {
        val source = sources()[sourceId] ?: return MarketListing(emptyList())
        if (useCache) readCache(sourceId)?.let { return MarketListing(it) }

        val skills = runCatching { listFrom(sourceId, source, onUpdate) }.getOrElse {
            FileLogger.w(TAG, "列技能失败（$sourceId）：${it.message}")
            return MarketListing(emptyList(), failed = true)
        }
        writeCache(sourceId, skills)
        return MarketListing(skills)
    }

    /**
     * 列出任意仓库的技能（「粘贴仓库地址」入口）。
     * 地址认不出来返回 null，由调用方提示用户；识别出来但没技能则返回空列表。
     */
    suspend fun listFromRepo(input: String): RepoListing? {
        val address = resolveSkillSite(input.trim()) ?: return null
        val parsed = SkillRepoAccess.parse(address) ?: return null
        val coord = parsed.coord
        // 地址里带子目录（.../tree/main/skills/docx）时只扫那一层，否则扫整仓。
        val source = SkillMarketSourceDef(
            kind = "directory",
            repo = coord.repo,
            branch = coord.ref,
            path = parsed.subPath,
            host = coord.host
        )
        val found = runCatching { listFrom(ADHOC_SOURCE_ID, source, null) }.getOrElse {
            FileLogger.w(TAG, "列仓库失败（${coord.repo}）：${it.message}")
            return RepoListing(coord.repo, coord.ref, emptyList(), failed = true)
        }
        if (found.isNotEmpty() || parsed.subPath.isEmpty()) {
            return RepoListing(coord.repo, coord.ref, found)
        }
        // 技能网站给的技能名未必等于仓库里的目录名（skills.sh 的 `/anthropics/skills/pdf`
        // 在仓库里其实是 `skills/pdf`），按子目录扫不到就退回整仓，让用户从列表里挑。
        val wholeRepo = runCatching { listFrom(ADHOC_SOURCE_ID, source.copy(path = ""), null) }
            .getOrDefault(emptyList())
        return RepoListing(coord.repo, coord.ref, wholeRepo)
    }

    /**
     * 技能网站的地址先读一次页面、换算成它对应的 GitHub 仓库地址；
     * 普通仓库地址原样返回，认不出来返回 null。
     */
    private suspend fun resolveSkillSite(input: String): String? {
        if (input.isBlank()) return null
        val page = SkillRepoAccess.siteLookupUrl(input) ?: return input
        val repo = httpGet(page)?.let { SkillRepoAccess.repoFromSitePage(it) } ?: return null
        FileLogger.d(TAG, "技能网站地址换算为仓库：$input -> $repo")
        return "https://github.com/$repo"
    }

    /**
     * 检索型源：拿关键词打站点的检索接口。
     * 接口是关键词驱动的，同一个源不同词的结果互不相同，所以不走列表缓存。
     */
    suspend fun searchSkills(sourceId: String, query: String): MarketListing {
        val source = sources()[sourceId] ?: return MarketListing(emptyList())
        val url = source.searchUrlFor(query) ?: return MarketListing(emptyList())
        val body = httpGet(url) ?: return MarketListing(emptyList(), failed = true)
        val parsed = runCatching { json.decodeFromString<SkillSearchResponse>(body) }.getOrNull()
            ?: return MarketListing(emptyList(), failed = true)
        val skills = parsed.skills
            .filter { it.source.contains('/') && it.skillId.isNotBlank() }
            .map { item ->
                MarketSkill(
                    sourceId = sourceId,
                    host = SkillRepoAccess.GITHUB,
                    repo = item.source,
                    branch = SkillRepoAccess.HEAD,
                    isDirectory = true,
                    // 目录与文件清单要等安装时拉一次文件树才知道
                    dir = "",
                    name = item.skillId,
                    description = "",
                    needsLocate = true,
                    installs = item.installs
                )
            }
        return MarketListing(enrichSearchResults(skills))
    }

    /**
     * 检索型源只给「技能名 + 所在仓库」，列表上就一行光名字，没法挑。
     * 这里按仓库分组拉一次文件树定位目录，再读各技能的 SKILL.md 补出描述与元信息，
     * 顺便把 needsLocate 消掉（安装时不用再定位一次）。
     *
     * 只补前 [MAX_SEARCH_ENRICH] 条：一次检索几十条全补要发上百个请求，而用户看的就是前几条。
     */
    private suspend fun enrichSearchResults(skills: List<MarketSkill>): List<MarketSkill> {
        if (skills.isEmpty()) return skills
        val limit = minOf(skills.size, MAX_SEARCH_ENRICH)
        val targets = skills.take(limit)

        // 同一个仓库的多个技能共用一次文件树请求
        val trees = coroutineScope {
            targets.map { it.repo }.distinct().map { repo ->
                async(Dispatchers.IO) {
                    val sample = targets.first { it.repo == repo }
                    repo to fetchTree(SkillRepoAccess.Coord(sample.host, repo, sample.branch))
                }
            }.awaitAll().toMap()
        }

        val fixed = coroutineScope {
            targets.map { skill ->
                async(Dispatchers.IO) {
                    val tree = trees[skill.repo] ?: return@async skill
                    val dir = findSkillDir(tree, skill.name) ?: return@async skill
                    val coord = SkillRepoAccess.Coord(skill.host, skill.repo, skill.branch)
                    val relative = if (dir.isEmpty()) SKILL_FILE else "$dir/$SKILL_FILE"
                    val text = fetchLimiter.withPermit {
                        httpGetFirst(SkillRepoAccess.fileUrls(coord, relative))
                    }
                    val meta = text?.let { SkillFrontmatter.parse(it, skill.name, appLanguage) }
                    val files = tree
                        .filter { dir.isEmpty() || it.startsWith("$dir/") }
                        .map { if (dir.isEmpty()) it else it.removePrefix("$dir/") }
                    skill.copy(
                        dir = dir,
                        files = files,
                        name = meta?.name ?: skill.name,
                        displayName = meta?.displayName.orEmpty(),
                        description = meta?.description.orEmpty(),
                        version = meta?.version.orEmpty(),
                        author = meta?.author.orEmpty(),
                        license = meta?.license.orEmpty(),
                        needsLocate = false,
                        safety = SkillSafetyScan.scan(files, text)
                    )
                }
            }.awaitAll()
        }
        return fixed + skills.drop(limit)
    }

    /** 在一棵文件树里按目录名找技能所在目录（不区分大小写）；找不到返回 null。 */
    private fun findSkillDir(tree: List<String>, name: String): String? =
        tree.filter { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
            .map { it.substringBeforeLast('/', "") }
            .firstOrNull { it.substringAfterLast('/').equals(name, ignoreCase = true) }

    /**
     * 检索型源的结果落在某个仓库里，但只知道技能名：拉一次文件树，按目录名（不区分大小写）
     * 找到含 SKILL.md 的那一层，再补全它自己的文件清单，之后与普通目录型技能同路。
     */
    private suspend fun locateInRepo(skill: MarketSkill): MarketSkill? {
        val coord = SkillRepoAccess.Coord(skill.host, skill.repo, skill.branch)
        val files = fetchTree(coord) ?: return null
        val dir = findSkillDir(files, skill.name) ?: return null
        return skill.copy(
            dir = dir,
            files = files.filter { dir.isEmpty() || it.startsWith("$dir/") }
                .map { if (dir.isEmpty()) it else it.removePrefix("$dir/") },
            needsLocate = false
        )
    }

    /**
     * 详情页要的数据：文件清单 + SKILL.md 原文 + 风险提示。
     *
     * 文件清单优先用列表阶段已经拿到的；没有时（索引型源只给了个 zip 路径）按目录名补拉一次文件树。
     * 拉不到 SKILL.md 不算失败——[SkillDetail.skillMd] 为 null，由 UI 说明该源不提供在线预览。
     */
    suspend fun loadDetail(skill: MarketSkill): SkillDetail = withContext(Dispatchers.IO) {
        val coord = SkillRepoAccess.Coord(skill.host, skill.repo, skill.branch)
        val files = skill.files.ifEmpty {
            fetchTree(coord)
                ?.filter { skill.dir.isEmpty() || it.startsWith("${skill.dir}/") }
                ?.map { if (skill.dir.isEmpty()) it else it.removePrefix("${skill.dir}/") }
                .orEmpty()
        }
        val path = listOf(skill.dir, SKILL_FILE).filter { it.isNotBlank() }.joinToString("/")
        val text = fetchLimiter.withPermit { httpGetFirst(SkillRepoAccess.fileUrls(coord, path)) }
        SkillDetail(files = files, skillMd = text, safety = SkillSafetyScan.scan(files, text))
    }

    /** 详情页的数据。[skillMd] 为 null = 这个技能在源里没有可读取的 SKILL.md（如只提供 zip 包）。 */
    data class SkillDetail(
        val files: List<String>,
        val skillMd: String?,
        val safety: SkillSafety
    )

    /**
     * 安装（或更新）一个技能：[scope] 决定装到全局还是当前项目。
     * 已在同作用域存在同名技能时直接覆盖——市场里点「安装」就是对「更新」的语义。
     */
    suspend fun install(skill: MarketSkill, scope: SkillScope): SkillImportReport {
        // 检索型源只给了技能名与所在仓库，不知道它在仓库里的哪个目录
        // （skills.sh 的 `pdf` 在 `anthropics/skills` 里其实是 `skills/pdf`），先定位再下载。
        val resolved = if (skill.needsLocate) locateInRepo(skill) ?: return SkillImportReport(emptyList()) else skill
        return installResolved(resolved, scope)
    }

    private suspend fun installResolved(skill: MarketSkill, scope: SkillScope): SkillImportReport {
        val coord = SkillRepoAccess.Coord(skill.host, skill.repo, skill.branch)

        // 两种源只是技能包的取得方式不同，拿到 zip 字节后完全同路。
        val zipBytes: ByteArray? = if (skill.isDirectory) {
            val bytes = coroutineScope {
                skill.files.map { relative ->
                    async(Dispatchers.IO) {
                        relative to fetchLimiter.withPermit {
                            fetchBytesFirst(SkillRepoAccess.fileUrls(coord, "${skill.dir}/$relative"))
                        }
                    }
                }.awaitAll()
            }
            if (bytes.any { it.second == null }) null
            else zipOf(bytes.map { it.first to it.second!! })
        } else {
            fetchBytesFirst(SkillRepoAccess.fileUrls(coord, skill.archivePath))
        }
        if (zipBytes == null || zipBytes.isEmpty()) {
            FileLogger.w(TAG, "技能包下载失败：${skill.name}")
            return SkillImportReport(emptyList())
        }

        val report = withContext(Dispatchers.IO) {
            skillRepository.importZip(
                input = zipBytes.inputStream(),
                fallbackName = skill.name,
                scope = scope,
                overwrite = true
            )
        }
        if (report.imported.isNotEmpty()) {
            installRepository.save(
                skill.name,
                SkillInstallRecord(
                    source = skill.sourceId,
                    version = skill.version,
                    installedAt = System.currentTimeMillis()
                )
            )
        }
        return report
    }

    /** 市场里的 [skill] 相对已安装记录是否有新版（两边都有非空版本号且不同）。 */
    fun hasUpdate(skill: MarketSkill): Boolean {
        val installed = installRepository.record(skill.name) ?: return false
        if (skill.version.isBlank() || installed.version.isBlank()) return false
        return skill.version != installed.version
    }

    /** 某技能已从市场安装过吗。 */
    fun isInstalled(skill: MarketSkill): Boolean = installRepository.record(skill.name) != null

    /**
     * 忘掉一个技能的安装记录。用户把技能删了之后要调它，
     * 否则市场会一直把已删的技能显示成「已安装」。
     */
    fun forgetInstall(name: String) = installRepository.remove(name)

    /**
     * 清掉安装记录里当前环境已不存在的技能。技能被删掉但没走到清理、装在别的工作区、
     * 或旧版本留下的孤儿记录，都会让市场误显示「已安装」——市场加载前对一次账。
     */
    fun pruneOrphanInstalls() {
        val present = skillRepository.listAllSkills().mapTo(HashSet()) { it.skill.name.lowercase() }
        installRepository.all().keys
            .filterNot { it in present }
            .forEach { installRepository.remove(it) }
    }

    // ── 列表 ──

    private suspend fun listFrom(
        sourceId: String,
        source: SkillMarketSourceDef,
        onUpdate: ((List<MarketSkill>) -> Unit)?
    ): List<MarketSkill> =
        if (source.isDirectory) listFromDirectory(sourceId, source, onUpdate)
        else listFromIndex(sourceId, source)

    private suspend fun listFromIndex(sourceId: String, source: SkillMarketSourceDef): List<MarketSkill> {
        val coord = coordOf(source)
        // 取不到就抛：让上层区分「源里确实一个技能都没有」与「没取到」
        val raw = httpGetFirst(SkillRepoAccess.fileUrls(coord, source.path))
            ?: error("索引文件获取失败：${source.repo}/${source.path}")
        val parsed = runCatching { json.decodeFromString<SkillPackData>(raw) }.getOrNull()
            ?: error("索引文件解析失败：${source.repo}/${source.path}")
        return parsed.skills
            .filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .map { entry ->
                MarketSkill(
                    sourceId = sourceId,
                    host = source.host,
                    repo = source.repo,
                    branch = source.branch,
                    isDirectory = false,
                    dir = entry.path.substringBeforeLast('/', ""),
                    name = entry.name,
                    description = entry.description,
                    version = entry.version,
                    author = entry.author,
                    license = entry.license,
                    archivePath = entry.path
                )
            }
            .distinctBy { it.name.lowercase() }
    }

    private suspend fun listFromDirectory(
        sourceId: String,
        source: SkillMarketSourceDef,
        onUpdate: ((List<MarketSkill>) -> Unit)?
    ): List<MarketSkill> = coroutineScope {
        val coord = coordOf(source)
        val prefix = source.path.trim('/')
        // 取不到就抛：让上层区分「源里确实一个技能都没有」与「没取到」
        val files = fetchTree(coord) ?: error("目录获取失败：${source.repo}")
        val dirs = files
            .filter { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
            // 根目录的 SKILL.md（单技能仓库）目录名是空串；不传第二参时 substringBeforeLast
            // 会把 "SKILL.md" 整个当目录名，拼出 SKILL.md/SKILL.md 这种错误路径
            .map { it.substringBeforeLast('/', "") }
            // prefix 为空 = 扫整仓；否则只要该目录本身或其子目录（地址直接指向技能目录时命中的是相等）
            .filter { prefix.isEmpty() || it == prefix || it.startsWith("$prefix/") }
            // 跳过 `_template` 这类以短横线/下划线开头的脚手架目录
            .filterNot { it.substringAfterLast('/').startsWith("_") }
            .distinct()
            // 同一技能可能在多个镜像目录里各存一份（如整批复制到 .gemini/skills 下）：
            // 按目录名去重，优先保留不在隐藏目录里的那份
            .sortedBy { d -> d.split('/').count { it.startsWith(".") } }
            .distinctBy { it.substringAfterLast('/').lowercase() }
        if (dirs.isEmpty()) return@coroutineScope emptyList()

        // 第一步：只用目录名把列表铺出来，不用等任何 SKILL.md
        val base = dirs.map { dir -> MarketSkill(
            sourceId = sourceId,
            host = source.host,
            repo = source.repo,
            branch = source.branch,
            isDirectory = true,
            dir = dir,
            name = dir.substringAfterLast('/').ifEmpty { source.repo.substringAfterLast('/') },
            description = "",
            // dir 为空串 = 整个仓库就是这一个技能，它的附属文件也就是仓库全部文件
            files = files.filter { dir.isEmpty() || it.startsWith("$dir/") }
                .map { if (dir.isEmpty()) it else it.removePrefix("$dir/") }
        ) }
        onUpdate?.invoke(base)

        // 第二步：补描述。只补前 [MAX_ENRICH] 个——列表里看得见的就那么几十条，
        // 几百个技能全读完只是白耗流量。
        val enrichDirs = dirs.take(MAX_ENRICH)
        val enriched = enrichDirs.map { dir ->
            async(Dispatchers.IO) {
                val relative = "$dir/$SKILL_FILE"
                val text = fetchLimiter.withPermit {
                    httpGetFirst(SkillRepoAccess.fileUrls(coord, relative))
                }
                val fallback = dir.substringAfterLast('/')
                val meta = text?.let { SkillFrontmatter.parse(it, fallback, appLanguage) }
                dir to (text to meta)
            }
        }.awaitAll().toMap()
        if (enriched.isEmpty()) return@coroutineScope base

        val full = base.map { skill ->
            val (text, meta) = enriched[skill.dir] ?: return@map skill
            skill.copy(
                name = meta?.name ?: skill.name,
                displayName = meta?.displayName.orEmpty(),
                description = meta?.description.orEmpty(),
                version = meta?.version.orEmpty(),
                author = meta?.author.orEmpty(),
                license = meta?.license.orEmpty(),
                safety = SkillSafetyScan.scan(skill.files, text)
            )
        }
        onUpdate?.invoke(full)
        full
    }

    private fun coordOf(source: SkillMarketSourceDef) =
        SkillRepoAccess.Coord(source.host, source.repo, source.branch)

    /** 平台的文件树接口：一次拿回整棵树的文件路径（相对仓库根）。 */
    private suspend fun fetchTree(coord: SkillRepoAccess.Coord): List<String>? {
        val body = httpGetFastest(SkillRepoAccess.treeUrls(coord)) ?: return null
        return SkillRepoAccess.parseTree(coord.host, body)
    }

    // ── 缓存 ──

    private fun cacheFile(sourceId: String) = File(File(context.filesDir, CACHE_DIR), "$sourceId.json")

    private fun readCache(sourceId: String): List<MarketSkill>? {
        val file = cacheFile(sourceId)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
        return runCatching { json.decodeFromString<List<MarketSkill>>(file.readText(Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun writeCache(sourceId: String, skills: List<MarketSkill>) = withContext(Dispatchers.IO) {
        if (skills.isEmpty()) return@withContext
        runCatching {
            cacheFile(sourceId).writeTextSafely(json.encodeToString(skills), TAG)
        }
    }

    // ── 网络 ──

    /** 依次尝试候选节点，返回第一个成功的结果；全失败返回 null。 */
    private suspend fun httpGetFirst(urls: List<String>): String? {
        urls.forEach { url -> httpGet(url)?.let { return it } }
        return null
    }

    /**
     * 并发请求候选地址，返回最先成功的那一个；全失败返回 null。
     *
     * 只在目录接口上用：通路有好几条（直连 api.github.com、两个反代、jsDelivr），
     * 哪条通取决于当前网络与第三方状态，串行降级一旦踩到不通的那条就要白等一整个连接超时。
     * 代价是多发一两个请求，换列表不受任何单点影响。
     *
     * 逐个下载技能文件时不用这套——那是几十个请求，翻倍发没意义。
     */
    private suspend fun httpGetFastest(urls: List<String>): String? = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<String?>()
        val jobs = urls.map { url -> launch { httpGet(url)?.let { winner.complete(it) } } }
        // 全军覆没时给 winner 一个了结，否则 await 会一直挂着
        launch { jobs.joinAll(); winner.complete(null) }
        val body = winner.await()
        jobs.forEach { it.cancel() }
        body
    }

    private suspend fun fetchBytesFirst(urls: List<String>): ByteArray? {
        urls.forEach { url -> fetchBytes(url)?.let { return it } }
        return null
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrElse { e ->
            FileLogger.d(TAG, "请求失败 $url：${e.message}")
            null
        }
    }

    private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrElse { null }
    }

    /** 把内存里的文件打成一个 zip，供 [SkillRepository.importZip] 解析。 */
    private fun zipOf(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 「粘贴仓库地址」的结果。[failed] 区分「仓库里没技能」与「没取到」。 */
    data class RepoListing(
        val repo: String,
        val ref: String,
        val skills: List<MarketSkill>,
        val failed: Boolean = false
    )

    /** 列技能的结果。[failed] 用于区分「源里真没技能」与「没取到」（网络不通 / 被拒）。 */
    data class MarketListing(
        val skills: List<MarketSkill>,
        val failed: Boolean = false
    )

    private companion object {
        const val TAG = "SkillMarketRepository"
        const val USER_AGENT = "aharou-android"
        const val SKILL_FILE = "SKILL.md"
        const val CACHE_DIR = "aharou/skill-market-cache"
        const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L

        /** 最多给多少个技能补读 SKILL.md。列表看得见的就几十条，再多是白耗流量。 */
        const val MAX_ENRICH = 120

        /** 检索结果最多补几条描述——一次检索几十条全补要发上百个请求。 */
        const val MAX_SEARCH_ENRICH = 12

        /** 「粘贴地址」这类一次性源共用的 id，不写缓存（不同仓库会互相覆盖）。 */
        const val ADHOC_SOURCE_ID = "adhoc"

        val json = Json { ignoreUnknownKeys = true }
    }
}
