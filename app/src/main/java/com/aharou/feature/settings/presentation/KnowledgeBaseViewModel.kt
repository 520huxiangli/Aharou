package com.aharou.feature.settings.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.knowledge.KnowledgeCatalog
import com.aharou.feature.agent.domain.knowledge.KnowledgeRepository
import com.aharou.feature.agent.domain.knowledge.KnowledgeSourceDef
import com.aharou.feature.agent.domain.knowledge.KnowledgeSourceRepository
import com.aharou.feature.agent.domain.skill.market.SkillRepoAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** 知识库页的状态：只在有动作时才有意义，[Idle] 表示界面不提示任何结果。 */
enum class KnowledgeBaseStatus { Idle, Installing, Installed, Failed, InvalidRepo }

/**
 * 共享知识库页的状态与动作。装与卸都是单个源自己的事——没有「一次全装」这种入口，
 * 用户装哪个就下哪个，没装的源不占本地空间。
 *
 * 单独一个 ViewModel 而不是并进 SettingsViewModel：那边已经贴着文件行数上限，
 * 这一页的读写（扫本地目录、下载、改自定义源）与设置项本身也没有共用状态。
 */
@HiltViewModel
class KnowledgeBaseViewModel @Inject constructor(
    private val catalog: KnowledgeCatalog,
    private val repository: KnowledgeRepository,
    private val sourceRepository: KnowledgeSourceRepository
) : ViewModel() {

    /**
     * 界面上的一个源。[definition] 原样带出来，让界面按当前语言取展示名——
     * ViewModel 拿不到界面语言，别在这儿把名字定死。
     */
    data class SourceUi(
        val id: String,
        val definition: KnowledgeSourceDef,
        val custom: Boolean,
        val installed: Boolean,
        val docCount: Int
    )

    /**
     * 界面上的一个分类。[name] 是语言 → 文案的映射，由界面按当前语言取，
     * 顺序就是源清单里声明的顺序——界面不自己排。
     */
    data class CategoryUi(val id: String, val name: Map<String, String>)

    var sources by mutableStateOf<List<SourceUi>>(emptyList())
        private set

    var categories by mutableStateOf<List<CategoryUi>>(emptyList())
        private set

    var busyId by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf(KnowledgeBaseStatus.Idle)
        private set

    init {
        reload()
    }

    /** 重新读源清单与本地安装情况。 */
    fun reload() {
        val data = catalog.load()
        val customIds = sourceRepository.all().keys
        sources = data.sources.map { (id, definition) ->
            SourceUi(
                id = id,
                definition = definition,
                custom = id in customIds,
                installed = repository.isInstalled(id),
                docCount = repository.documents(id).size
            )
        }
        // 只列真正有源的分类；清单没声明分类时按源里出现的顺序兜底
        val used = sources.map { it.definition.category }.filter { it.isNotBlank() }.toSet()
        val declared = data.categories.filterKeys { it in used }
        categories = if (declared.isNotEmpty()) {
            declared.map { (id, name) -> CategoryUi(id, name) }
        } else {
            used.map { CategoryUi(it, emptyMap()) }
        }
    }

    /** 装一个源：把它的 Markdown 拉到本地。装完界面立刻反映篇数。 */
    fun install(id: String) {
        if (busyId != null) return
        busyId = id
        status = KnowledgeBaseStatus.Installing
        viewModelScope.launch {
            status = runCatching { repository.sync(id) }
                .fold(
                    { result -> if (result == null) KnowledgeBaseStatus.Failed else KnowledgeBaseStatus.Installed },
                    { KnowledgeBaseStatus.Failed }
                )
            busyId = null
            reload()
        }
    }

    /** 卸一个源：只删本地副本，源仍在列表里，随时能再装。 */
    fun uninstall(id: String) {
        repository.uninstall(id)
        reload()
    }

    /**
     * 加一个自定义源。地址认不出来返回 false，界面据此提示——校验放这儿是因为
     * 「什么算合法仓库地址」的判据在 [SkillRepoAccess] 里，UI 不该复制一份。
     */
    fun addSource(input: String): Boolean {
        val parsed = SkillRepoAccess.parse(input.trim())
        if (parsed == null || parsed.coord.repo.isBlank()) {
            status = KnowledgeBaseStatus.InvalidRepo
            return false
        }
        val coord = parsed.coord
        // id 取自仓库名：同名仓库再加一次就是覆盖，不会堆出一串重复条目
        val id = "user-" + coord.repo.substringAfterLast('/')
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
        sourceRepository.add(
            id,
            KnowledgeSourceDef(
                repo = coord.repo,
                branch = coord.ref,
                path = parsed.subPath,
                host = coord.host
            )
        )
        status = KnowledgeBaseStatus.Idle
        reload()
        return true
    }

    /** 把用户自己加的源从列表里删掉（顺带清掉它的本地副本）。 */
    fun removeSource(id: String) {
        repository.uninstall(id)
        sourceRepository.remove(id)
        reload()
    }
}
