package com.aharou.feature.settings.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.skill.SkillConfigRepository
import com.aharou.feature.agent.domain.skill.SkillForm
import com.aharou.feature.agent.domain.skill.SkillImportError
import com.aharou.feature.agent.domain.skill.SkillImportReport
import com.aharou.feature.agent.domain.skill.SkillMoveResult
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.market.MarketSkill
import com.aharou.feature.agent.domain.skill.market.SkillCategoryTagger
import com.aharou.feature.agent.domain.skill.market.SkillMarketRepository
import com.aharou.feature.agent.domain.skill.market.SkillMarketTranslator
import com.aharou.feature.agent.domain.skill.market.SkillRepoAccess
import com.aharou.feature.agent.domain.skill.market.SkillTranslation
import com.aharou.feature.agent.domain.skill.market.needsTranslation
import com.aharou.feature.agent.domain.skill.market.translationKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 技能与技能市场：本地技能的增删改导入导出，以及市场源的列表、检索、翻译、分类、详情与安装。
 *
 * 从 `SettingsViewModel` 整块搬出——这块自带两个后台任务（译文回流、分类打标），
 * 与设置页其余开关没有耦合。搬走后主 ViewModel 少 30 多个符号。
 *
 * 生命周期：单例常驻，[ensureStarted] 后才开始拉列表（设置页首次进技能页时调用）。
 */
@Singleton
class SkillSettingsController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val skillConfigRepository: SkillConfigRepository,
    private val skillMarketRepository: SkillMarketRepository,
    private val skillMarketTranslator: SkillMarketTranslator,
    private val skillCategoryTagger: SkillCategoryTagger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _skills = MutableStateFlow<List<SkillUiEntry>>(emptyList())
    val skills: StateFlow<List<SkillUiEntry>> = _skills.asStateFlow()

    /** 技能配置变更信号（含外部改动）：主 ViewModel 据此刷新列表。 */
    val changes: Flow<Unit> = skillConfigRepository.changes

    private val _saveState = MutableStateFlow<SkillSaveState>(SkillSaveState.Idle)
    val saveState: StateFlow<SkillSaveState> = _saveState.asStateFlow()

    private val _importState = MutableStateFlow<SkillImportState>(SkillImportState.Idle)
    val importState: StateFlow<SkillImportState> = _importState.asStateFlow()

    /** 正在删除的技能名（非 null 表示删除进行中，用于弹窗转圈与禁用按钮）。 */
    private val _deleting = MutableStateFlow<String?>(null)
    val deleting: StateFlow<String?> = _deleting.asStateFlow()

    /** 正搬动的技能名（防重复点）；结果经 [moveResult] 交给界面提示一次。 */
    private val _moving = MutableStateFlow<String?>(null)
    val moving: StateFlow<String?> = _moving.asStateFlow()

    private val _moveResult = MutableStateFlow<SkillMoveResult?>(null)
    val moveResult: StateFlow<SkillMoveResult?> = _moveResult.asStateFlow()

    private val _exportState = MutableStateFlow<SkillExportState>(SkillExportState.Idle)
    val exportState: StateFlow<SkillExportState> = _exportState.asStateFlow()

    // ── 市场 ──

    private val _marketSources = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val marketSources: StateFlow<List<Pair<String, String>>> = _marketSources.asStateFlow()

    private val _marketSourceId = MutableStateFlow("")
    val marketSourceId: StateFlow<String> = _marketSourceId.asStateFlow()

    private val _marketSkills = MutableStateFlow<List<MarketSkillUi>>(emptyList())
    val marketSkills: StateFlow<List<MarketSkillUi>> = _marketSkills.asStateFlow()

    /** 市场条目的中文译文（键＝仓库@技能目录）：仓库没给中文的由模型后台翻，翻好一批刷新一批。 */
    private val _marketTranslations = MutableStateFlow<Map<String, SkillTranslation>>(emptyMap())
    val marketTranslations: StateFlow<Map<String, SkillTranslation>> = _marketTranslations.asStateFlow()

    /** 当前源的后台翻译任务：换源时取消上一轮，免得旧源的译文回来刷到新列表上。 */
    private var marketTranslateJob: Job? = null

    /** 最近一次搜索的关键词；译文逐批回流时用它重筛，中文关键词才搜得出来。 */
    private var lastMarketQuery: String = ""

    /** 市场条目的分类（键＝仓库@技能目录）：技能元数据里没有，交给模型按描述打标。 */
    private val _marketCategories = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val marketCategories: StateFlow<Map<String, List<String>>> = _marketCategories.asStateFlow()

    /** 当前源的分类打标任务：换源时取消上一轮。 */
    private var marketCategoryJob: Job? = null

    private val _marketLoading = MutableStateFlow(false)
    val marketLoading: StateFlow<Boolean> = _marketLoading.asStateFlow()

    private val _marketAlert = MutableStateFlow<MarketAlert?>(null)
    val marketAlert: StateFlow<MarketAlert?> = _marketAlert.asStateFlow()

    private val _marketDetail = MutableStateFlow<MarketDetailUi?>(null)
    val marketDetail: StateFlow<MarketDetailUi?> = _marketDetail.asStateFlow()

    /** 列表当前显示的是关键词检索结果（不是某个源/仓库的完整列表）。 */
    private val _marketSearching = MutableStateFlow(false)
    val marketSearching: StateFlow<Boolean> = _marketSearching.asStateFlow()

    /** 上次按地址列出的仓库与它的完整技能列表：同一地址再搜就在这份缓存里筛，不重复拉。 */
    private var marketAdhocInput = ""
    private var marketAdhocSkills: List<MarketSkill> = emptyList()

    /** 「粘贴仓库地址」加载出来的仓库（owner/name），作为临时源挂在源列表末尾。 */
    private var marketAdhocRepo: String? = null

    /** 当前源拉到的完整列表（不随搜索变）：关键词搜索在它上面筛，不是筛上一次的结果。 */
    private var marketSourceSkills: List<MarketSkill> = emptyList()

    fun ensureStarted() {
        if (started) return
        started = true
        refreshSkills()
    }

    // ── 本地技能 ──

    /** 重新扫描技能（进入设置页 / 启停切换后调用，反映磁盘上增删改）。 */
    fun refreshSkills() {
        scope.launch {
            _skills.value = withContext(Dispatchers.IO) {
                val disabled = skillConfigRepository.disabledNames()
                skillRepository.listAllSkills().map { entry ->
                    SkillUiEntry(
                        name = entry.skill.name,
                        description = entry.skill.description,
                        scope = entry.scope,
                        disabled = entry.skill.name.lowercase() in disabled,
                        instructions = entry.skill.instructions,
                        requiredTools = entry.skill.requiredTools,
                        builtin = entry.scope == SkillScope.BUILTIN
                    )
                }
            }
        }
    }

    /** 切换技能的启用/禁用状态（写入对应作用域的 skills.json）。 */
    fun setSkillEnabled(name: String, enabled: Boolean, scope: SkillScope) {
        this.scope.launch {
            withContext(Dispatchers.IO) { skillRepository.setSkillDisabled(name, !enabled, scope) }
            refreshSkills()
        }
    }

    /**
     * 删除指定作用域的技能（删除其目录，不可恢复），完成后刷新列表。
     * 删除本体在 IO 线程跑（远程模式经 SFTP 递归删，不能在主线程）；期间 [deleting] 置名供 UI 转圈。
     */
    fun deleteSkill(name: String, scope: SkillScope) {
        if (_deleting.value != null) return
        _deleting.value = name
        this.scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { skillRepository.deleteSkill(name, scope) }
                // 市场装来的技能删掉后要一并清安装记录，否则市场一直显示「已安装」。
                // 安装记录只按名字存、不分作用域，所以同名技能在别的地方还在时要留着。
                if (deleted) {
                    val stillPresent = withContext(Dispatchers.IO) {
                        skillRepository.listAllSkills().any { it.skill.name.equals(name, ignoreCase = true) }
                    }
                    if (!stillPresent) {
                        withContext(Dispatchers.IO) { skillMarketRepository.forgetInstall(name) }
                    }
                }
                refreshSkills()
            } finally {
                _deleting.value = null
            }
        }
    }

    /**
     * 把技能在全局与当前项目之间搬（整包导出 → 导入 → 删源，见 [SkillRepository.moveSkill]）。
     * 本体在 IO 线程跑，期间 [moving] 置名供 UI 转圈；结果写进 [moveResult]。
     */
    fun moveSkill(name: String, fromScope: SkillScope, toScope: SkillScope) {
        if (_moving.value != null || fromScope == toScope) return
        _moving.value = name
        scope.launch {
            try {
                _moveResult.value = withContext(Dispatchers.IO) {
                    skillRepository.moveSkill(name, fromScope, toScope)
                }
                refreshSkills()
            } finally {
                _moving.value = null
            }
        }
    }

    /** 界面提示过之后清掉，避免重组时又弹一次。 */
    fun clearMoveResult() {
        _moveResult.value = null
    }

    /**
     * 保存技能。[originalName] 为编辑前的名称，新建时为 null；
     * 结果转成 [saveState]，编辑页据此退回列表或就地报错。
     */
    fun saveSkill(form: SkillForm, skillScope: SkillScope, originalName: String? = null) {
        if (_saveState.value is SkillSaveState.Saving) return
        _saveState.value = SkillSaveState.Saving
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                skillRepository.save(form, skillScope, originalName)
            }
            _saveState.value = if (error == null) {
                SkillSaveState.Saved
            } else {
                SkillSaveState.Failed(error)
            }
            if (error == null) refreshSkills()
        }
    }

    fun clearSaveState() {
        _saveState.value = SkillSaveState.Idle
    }

    /**
     * 从所选 Markdown 文件导入技能：读取文本并写入指定作用域。
     * 扩展名不在白名单内直接报「类型不支持」，不落盘。
     */
    fun importSkillFromMarkdown(uri: Uri, skillScope: SkillScope) {
        if (_importState.value is SkillImportState.Running) return
        _importState.value = SkillImportState.Running
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(MARKDOWN_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                            ?: throw java.io.IOException("openInputStream returned null")
                        skillRepository.importMarkdown(text, name.substringBeforeLast('.'), skillScope)
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED) }
                }
            }
            finishSkillImport(report)
        }
    }

    /** 从所选 zip 压缩包导入技能（可含多个技能），写入指定作用域。 */
    fun importSkillsFromZip(uri: Uri, skillScope: SkillScope) {
        if (_importState.value is SkillImportState.Running) return
        _importState.value = SkillImportState.Running
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(ZIP_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            skillRepository.importZip(input, name.substringBeforeLast('.'), skillScope)
                        } ?: throw java.io.IOException("openInputStream returned null")
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.INVALID_ARCHIVE) }
                }
            }
            finishSkillImport(report)
        }
    }

    private suspend fun finishSkillImport(report: SkillImportReport) {
        _importState.value = SkillImportState.Done(report)
        if (report.imported.isNotEmpty()) refreshSkills()
    }

    fun clearImportState() {
        _importState.value = SkillImportState.Idle
    }

    /**
     * 导出技能：在缓存目录打一个 zip，成功后由 UI 弹系统分享。
     * 内置技能也能导——正文从 assets 现攒一份 SKILL.md 打进包里，导出的东西同样能导回来。
     */
    fun exportSkill(entry: SkillUiEntry) {
        if (_exportState.value is SkillExportState.Running) return
        _exportState.value = SkillExportState.Running
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val bytes = runCatching { skillRepository.exportZip(entry.name, entry.scope) }.getOrNull()
                if (bytes == null) return@withContext null
                runCatching {
                    // 缓存目录已在 FileProvider 的授权范围里（见 res/xml/file_paths.xml 的 cache-path）
                    val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                    File(dir, "${entry.name}.zip").apply { writeBytes(bytes) }
                }.getOrNull()
            }
            _exportState.value = if (file == null) {
                SkillExportState.Failed
            } else {
                SkillExportState.Ready(file, entry.name)
            }
        }
    }

    /** UI 弹过分享面板后调，避免重组时重复弹。 */
    fun clearExportState() {
        _exportState.value = SkillExportState.Idle
    }

    /** 查询所选文件的显示名（含扩展名）；取不到时回退到 URI 末段。 */
    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    // ── 市场 ──

    /** 市场源列表（id → 显示名），并在当前选中源失效时重置为第一个。 */
    fun refreshMarketSources() {
        // 用 App 内语言（per-app 语言下进程 Locale 未必跟着 App 走，详见 SkillMarketRepository.appLanguage）
        val lang = context.resources.configuration.locales[0].language
        val sources = skillMarketRepository.sources()
            // 检索型源不进源列表：它没有自己的技能清单，只是给关键词搜索提供接口
            .filterValues { !it.isSearch }
            .map { (id, def) -> id to def.displayName(lang) }
        // 自己贴的仓库排第一个：刚加载完就在最显眼处，不会被挤到滑动条的末尾看不见
        _marketSources.value = adhocSourceEntry() + sources
        if (_marketSources.value.none { it.first == _marketSourceId.value }) {
            _marketSourceId.value = _marketSources.value.firstOrNull()?.first.orEmpty()
        }
    }

    private fun isSearchSource(sourceId: String) =
        skillMarketRepository.sources()[sourceId]?.isSearch == true

    /** 切换市场源并重新拉取其技能列表。 */
    fun selectMarketSource(sourceId: String) {
        if (_marketSourceId.value == sourceId) {
            when {
                // 搜过之后点回当前源＝把完整列表拿回来（否则停在筛过的结果上出不去）
                _marketSearching.value -> loadMarketSkills()
                // 没搜过就是手动刷新：列表可能来自 12 小时缓存，用户要的是现在重拉
                !isSearchSource(sourceId) -> loadMarketSkills(force = true)
            }
            return
        }
        _marketSourceId.value = sourceId
        _marketSearching.value = false
        loadMarketSkills()
    }

    /** 重新拉取当前源（进入市场页时调用）。 */
    fun refreshMarket() {
        val sourceId = _marketSourceId.value
        when {
            sourceId.isBlank() || sourceId == ADHOC_MARKET_ID ->
                marketAdhocInput.takeIf { it.isNotBlank() }?.let { loadMarketFromRepo(it) }
            !isSearchSource(sourceId) -> loadMarketSkills(force = true)
        }
    }

    /**
     * 关键词搜索：地址框填了仓库地址就在那个仓库里筛，没填就在当前选中的源里筛。
     */
    fun searchMarket(query: String, address: String) {
        val q = query.trim()
        if (q.isBlank()) return
        lastMarketQuery = q
        val addr = address.trim()
        when {
            addr.isNotEmpty() -> searchMarketInRepo(addr, q)
            _marketSourceId.value == ADHOC_MARKET_ID ->
                marketAdhocInput.takeIf { it.isNotBlank() }?.let { searchMarketInRepo(it, q) }
            else -> searchMarketInSource(q)
        }
    }

    /**
     * 在当前源的完整列表里就地筛，不发网络请求（进市场页时已经拉过）。
     * 结果是列表的视图，点回当前源名就能回到完整列表。
     */
    private fun searchMarketInSource(query: String) {
        val all = marketSourceSkills
        if (all.isEmpty()) {
            _marketAlert.value = MarketAlert.MarketNotLoaded
            return
        }
        val hits = all.filter { it.matchesQuery(query, _marketTranslations.value[it.translationKey()]) }
        _marketSearching.value = true
        publishMarket(hits)
        _marketAlert.value = if (hits.isEmpty()) MarketAlert.NoMatchInRepo else null
        // 用户可能在译文还没翻出来时就搜中文：把整个源送去翻，翻好一批会自动重筛一次
        translateMarket(all)
    }

    /** 在指定地址的仓库里筛技能：同一地址已经列过就直接在缓存里筛，不重复拉。 */
    private fun searchMarketInRepo(address: String, query: String) {
        scope.launch {
            _marketLoading.value = true
            _marketAlert.value = null
            val listing = if (address == marketAdhocInput && marketAdhocSkills.isNotEmpty()) {
                SkillMarketRepository.RepoListing(marketAdhocRepo.orEmpty(), "", marketAdhocSkills)
            } else {
                withContext(Dispatchers.IO) { skillMarketRepository.listFromRepo(address) }
                    ?.also { marketAdhocInput = address; marketAdhocSkills = it.skills }
            }
            if (listing == null) {
                // 与「加载」一致：技能站的目录页和认不出的地址分开提示
                _marketAlert.value = if (SkillRepoAccess.isSkillSite(address)) {
                    MarketAlert.SkillSiteNeedsDetail
                } else {
                    MarketAlert.InvalidAddress
                }
            } else {
                marketAdhocRepo = listing.repo
                _marketSourceId.value = ADHOC_MARKET_ID
                refreshAdhocSourceEntry()
                val hits = listing.skills.filter { it.matchesQuery(query, _marketTranslations.value[it.translationKey()]) }
                _marketSearching.value = true
                publishMarket(hits)
                // 翻整个仓库而不是只翻命中的：中文关键词可能只存在于还没翻过的条目里
                translateMarket(listing.skills)
                _marketAlert.value = when {
                    listing.failed -> MarketAlert.LoadFailed
                    hits.isEmpty() -> MarketAlert.NoMatchInRepo
                    else -> null
                }
            }
            _marketLoading.value = false
        }
    }

    /**
     * 技能名、显示名、描述，以及模型翻好的中文译文，任一命中即算匹配。
     *
     * 译文必须算进来：仓库元数据基本都是英文，用户输入中文时只有译文里才有可匹配的文字。
     */
    private fun MarketSkill.matchesQuery(query: String, translation: SkillTranslation?): Boolean {
        val q = query.lowercase()
        return name.lowercase().contains(q) ||
            displayName.lowercase().contains(q) ||
            description.lowercase().contains(q) ||
            translation?.name?.lowercase()?.contains(q) == true ||
            translation?.description?.lowercase()?.contains(q) == true
    }

    /**
     * 按用户粘贴的仓库地址列出技能：地址认不出来或仓库里没技能时给一句提示，不清空现有列表。
     * 支持 GitHub / Gitee / GitLab / Gitea 系（带 `tree/分支/子目录` 的链接也能直接贴）。
     */
    fun loadMarketFromRepo(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            _marketLoading.value = true
            _marketAlert.value = null
            val listing = withContext(Dispatchers.IO) { skillMarketRepository.listFromRepo(trimmed) }
            if (listing == null) {
                // 技能网站的目录页拿不到仓库信息，直接说清楚要贴到哪一级
                _marketAlert.value = if (SkillRepoAccess.isSkillSite(trimmed)) {
                    MarketAlert.SkillSiteNeedsDetail
                } else {
                    MarketAlert.InvalidAddress
                }
            } else {
                marketAdhocRepo = listing.repo
                marketAdhocInput = trimmed
                marketAdhocSkills = listing.skills
                _marketSourceId.value = ADHOC_MARKET_ID
                _marketSearching.value = false
                refreshAdhocSourceEntry()
                publishMarket(listing.skills)
                translateMarket(listing.skills)
                _marketAlert.value = when {
                    listing.failed -> MarketAlert.LoadFailed
                    listing.skills.isEmpty() -> MarketAlert.NoSkills
                    else -> null
                }
            }
            _marketLoading.value = false
        }
    }

    private fun adhocSourceEntry(): List<Pair<String, String>> =
        marketAdhocRepo?.let { listOf(ADHOC_MARKET_ID to it) }.orEmpty()

    /** 把「粘贴地址」那个源插到源列表最前面（没加载过仓库时就相当于没这一项）。 */
    private fun refreshAdhocSourceEntry() {
        _marketSources.value =
            adhocSourceEntry() + _marketSources.value.filterNot { it.first == ADHOC_MARKET_ID }
    }

    /**
     * 拉取当前源的技能列表。列表先铺出来、描述随后流式补上，所以不等全部读完就能看到内容；
     * 真取不到时给「加载失败」提示，而不是当作「这个源没技能」（那是两回事）。
     */
    fun loadMarketSkills(force: Boolean = false) {
        val sourceId = _marketSourceId.value
        if (sourceId.isBlank()) return
        scope.launch {
            _marketLoading.value = true
            _marketAlert.value = null
            _marketSearching.value = false
            val listing = withContext(Dispatchers.IO) {
                // 先对账：清掉已不存在的安装记录，否则列表会把删掉的技能显示成「已安装」
                skillMarketRepository.pruneOrphanInstalls()
                skillMarketRepository.listSkills(
                    sourceId = sourceId,
                    onUpdate = { partial ->
                        marketSourceSkills = partial
                        publishMarket(partial)
                    },
                    useCache = !force
                )
            }
            marketSourceSkills = listing.skills
            publishMarket(listing.skills)
            translateMarket(listing.skills)
            if (listing.failed) _marketAlert.value = MarketAlert.LoadFailed
            _marketLoading.value = false
        }
    }

    /**
     * 把列表里没有中文的条目丢给模型翻（后台、逐批回流）。
     * 换源/换仓库会取消上一轮：译文按「仓库@技能目录」归属，回来也不会串到别的源上。
     */
    private fun translateMarket(skills: List<MarketSkill>) {
        marketTranslateJob?.cancel()
        if (skills.isEmpty()) return
        marketTranslateJob = scope.launch {
            val cached = skillMarketTranslator.cached()
            _marketTranslations.value = cached
            val pending = skills.count { it.needsTranslation() && it.translationKey() !in cached }
            if (pending == 0) return@launch
            var translated = 0
            skillMarketTranslator.translate(skills) { batch ->
                translated += batch.size
                _marketTranslations.value = _marketTranslations.value + batch
                // 正在搜索的话，新译文可能带来新命中（中文关键词尤其吃这个）
                if (_marketSearching.value) refilterMarket()
            }
            // 该翻的一条都没翻出来（多半是供应商没配好）：把原因说出来，别让人以为没这功能
            if (translated == 0) _marketAlert.value = MarketAlert.TranslateUnavailable
        }
    }

    /** 用 [lastMarketQuery] 重新筛一遍当前列表（译文回流后调用）；不在搜索态就直接返回。 */
    private fun refilterMarket() {
        val q = lastMarketQuery
        if (q.isBlank()) return
        val source = if (_marketSourceId.value == ADHOC_MARKET_ID) marketAdhocSkills else marketSourceSkills
        if (source.isEmpty()) return
        val hits = source.filter { it.matchesQuery(q, _marketTranslations.value[it.translationKey()]) }
        publishMarket(hits)
        _marketAlert.value = if (hits.isEmpty()) MarketAlert.NoMatchInRepo else null
    }

    /**
     * 给列表里的技能打分类（后台、逐批回流）。换源/换仓库会取消上一轮：
     * 分类按「仓库@技能目录」归属，回来也不会串到别的源上。
     */
    private fun categorizeMarket(skills: List<MarketSkill>) {
        marketCategoryJob?.cancel()
        if (skills.isEmpty()) return
        marketCategoryJob = scope.launch {
            _marketCategories.value = skillCategoryTagger.cached()
            if (skills.all { it.translationKey() in _marketCategories.value }) return@launch
            skillCategoryTagger.categorize(skills) { batch ->
                _marketCategories.value = _marketCategories.value + batch
            }
        }
    }

    /**
     * 用户主动按分类筛时才去算分类（默认不跑）。
     *
     * 分类是要花 token 的打标，而它只是个筛选项——进市场就自作主张跑一遍，
     * 对不关心分类的人就是白花钱。已经有任务在跑时直接返回，不打断已有的进度。
     */
    fun ensureMarketCategories() {
        if (marketCategoryJob?.isActive == true) return
        val skills = if (_marketSourceId.value == ADHOC_MARKET_ID) marketAdhocSkills else marketSourceSkills
        if (skills.isEmpty()) return
        if (skills.all { it.translationKey() in _marketCategories.value }) return
        categorizeMarket(skills)
    }

    private fun toMarketUi(skill: MarketSkill) = MarketSkillUi(
        skill = skill,
        installed = skillMarketRepository.isInstalled(skill),
        hasUpdate = skillMarketRepository.hasUpdate(skill)
    )

    /** 列表的统一出口：把领域模型映射成 UI 模型（已安装 / 有无更新）。 */
    private fun publishMarket(skills: List<MarketSkill>) {
        _marketSkills.value = skills.map { toMarketUi(it) }
    }

    /** 打开市场详情页并拉取详情（文件清单 + SKILL.md）。拉不到也进页面，只是内容为空。 */
    fun openMarketDetail(skill: MarketSkill) {
        _marketDetail.value = MarketDetailUi(toMarketUi(skill), loading = true, detail = null)
        scope.launch {
            val detail = withContext(Dispatchers.IO) {
                runCatching { skillMarketRepository.loadDetail(skill) }.getOrNull()
            }
            // 拉的过程中用户可能已经返回，或点了另一个技能
            _marketDetail.value = _marketDetail.value
                ?.takeIf { it.ui.skill == skill }
                ?.copy(loading = false, detail = detail)
        }
    }

    /** 从市场安装（已存在则覆盖更新）一个技能到指定作用域，完成后刷新市场与本地列表。 */
    fun installFromMarket(skill: MarketSkill, skillScope: SkillScope) {
        if (_importState.value is SkillImportState.Running) return
        _importState.value = SkillImportState.Running
        scope.launch {
            val report = withContext(Dispatchers.IO) { skillMarketRepository.install(skill, skillScope) }
            finishSkillImport(report)
            // 检索结果（全局检索或仓库内筛选）是一次性查询出来的，装完只刷新安装状态，
            // 不能重新「列源」把列表换掉
            if (_marketSearching.value) {
                _marketSkills.value = _marketSkills.value.map { toMarketUi(it.skill) }
            } else if (!isSearchSource(_marketSourceId.value)) {
                loadMarketSkills()
            }
            refreshSkills()
        }
    }

    private companion object {
        const val TAG = "SkillSettingsController"
    }
}
