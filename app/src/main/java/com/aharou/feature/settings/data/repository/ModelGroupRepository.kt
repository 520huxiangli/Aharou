package com.aharou.feature.settings.data.repository

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型组仓库（自 上游项目 的 ProviderModelGroup 移植·适配）。
 *
 * 「模型组」= 具名的有序模型集合（成员 = 供应商 + 模型）；在需要选模型的地方可以
 * 整组选择（如「默认模型」页的识图 / 压缩 / 标题 / 生图角色），执行时经
 * [ModelGroupResolver] 解析为组内首个成员（按成员顺序，后续成员可扩展为故障转移）。
 *
 * 存储：SharedPreferences（JSON），与 EnvVarRepository 同风格，不动 Room 主库。
 */
@Singleton
class ModelGroupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 组内成员：绑定某个供应商的某个模型。 */
    data class Member(
        val providerId: String,
        val providerName: String,
        val model: String,
    )

    data class ModelGroup(
        val id: String,
        val name: String,
        val members: List<Member> = emptyList(),
        /** 预留：组级默认思考档位（v1 仅存储）。 */
        val thinkingLevel: String? = null,
        /** 预留：组级上下文上限（v1 仅存储）。 */
        val contextLimitTokens: Int? = null,
        val sortOrder: Long = 0L,
    )

    companion object {
        private const val TAG = "ModelGroupRepository"

        /** 选择值以该前缀开头 = 选择的是一整个模型组。 */
        const val GROUP_PREFIX = "group:"

        private const val PREFS = "aharou_model_groups"
        private const val KEY_GROUPS = "groups"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _groups = MutableStateFlow(loadAll())
    val groups: StateFlow<List<ModelGroup>> = _groups.asStateFlow()

    init {
        ModelGroupResolver.install(this)
    }

    fun findById(id: String): ModelGroup? = _groups.value.firstOrNull { it.id == id }

    fun create(name: String): ModelGroup {
        val group = ModelGroup(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            sortOrder = (_groups.value.maxOfOrNull { it.sortOrder } ?: -1L) + 1L,
        )
        _groups.value = _groups.value + group
        persist()
        return group
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim()) }

    fun delete(id: String) {
        _groups.value = _groups.value.filterNot { it.id == id }
        persist()
    }

    fun addMember(id: String, providerId: String, providerName: String, model: String) {
        update(id) { group ->
            if (group.members.any { it.providerId == providerId && it.model == model }) {
                group
            } else {
                group.copy(members = group.members + Member(providerId, providerName, model))
            }
        }
    }

    fun removeMember(id: String, index: Int) = update(id) { group ->
        if (index !in group.members.indices) group
        else group.copy(members = group.members.filterIndexed { i, _ -> i != index })
    }

    /** [delta] = -1 上移 / +1 下移（首成员优先执行，顺序有意义）。 */
    fun moveMember(id: String, index: Int, delta: Int) = update(id) { group ->
        val to = index + delta
        if (index !in group.members.indices || to !in group.members.indices) {
            group
        } else {
            val list = group.members.toMutableList()
            val item = list.removeAt(index)
            list.add(to, item)
            group.copy(members = list)
        }
    }

    private fun update(id: String, transform: (ModelGroup) -> ModelGroup) {
        _groups.value = _groups.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            _groups.value.forEach { group ->
                val members = JSONArray()
                group.members.forEach { member ->
                    members.put(
                        JSONObject()
                            .put("pid", member.providerId)
                            .put("pname", member.providerName)
                            .put("model", member.model)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", group.id)
                        .put("name", group.name)
                        .put("members", members)
                        .put("thinking", group.thinkingLevel)
                        .put("ctx", group.contextLimitTokens)
                        .put("order", group.sortOrder)
                )
            }
            prefs.edit().putString(KEY_GROUPS, arr.toString()).apply()
        }.onFailure { FileLogger.w(TAG, "persist failed: ${it.message}") }
    }

    private fun loadAll(): List<ModelGroup> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                val membersArr = obj.optJSONArray("members") ?: JSONArray()
                val members = (0 until membersArr.length()).mapNotNull { j ->
                    val mo = membersArr.optJSONObject(j) ?: return@mapNotNull null
                    Member(mo.optString("pid"), mo.optString("pname"), mo.optString("model"))
                }
                ModelGroup(
                    id = id,
                    name = obj.optString("name"),
                    members = members,
                    thinkingLevel = obj.optString("thinking").takeIf { it.isNotEmpty() },
                    contextLimitTokens = if (obj.isNull("ctx")) null else obj.optInt("ctx"),
                    sortOrder = obj.optLong("order", 0L),
                )
            }.sortedBy { it.sortOrder }
        }.onFailure { FileLogger.w(TAG, "load failed: ${it.message}") }.getOrDefault(emptyList())
    }
}
