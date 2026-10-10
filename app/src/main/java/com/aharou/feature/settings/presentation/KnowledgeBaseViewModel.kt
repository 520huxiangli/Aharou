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
enum class KnowledgeBaseStatus { Idle, Syncing, Synced, Failed, InvalidRepo }

/**
 * 共享知识库设置页的状态与动作。
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
        val docCount: Int
    )

    var sources by mutableStateOf<List<SourceUi>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf(KnowledgeBaseStatus.Idle)
        private set

    init {
        reload()
    }

    /** 重新读源清单与本地文档数。 */
    fun reload() {
        val customIds = sourceRepository.all().keys
        sources = catalog.load().sources.map { (id, definition) ->
            SourceUi(
                id = id,
                definition = definition,
                custom = id in customIds,
                docCount = repository.documents(id).size
            )
        }
    }

    fun syncAll() {
        runSync { repository.syncAll() }
    }

    fun sync(id: String) {
        runSync { repository.sync(id) }
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

    fun removeSource(id: String) {
        sourceRepository.remove(id)
        reload()
    }

    private fun runSync(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = KnowledgeBaseStatus.Syncing
        viewModelScope.launch {
            status = runCatching { block() }
                .fold({ KnowledgeBaseStatus.Synced }, { KnowledgeBaseStatus.Failed })
            busy = false
            reload()
        }
    }
}
