package com.aicode.feature.settings.data.repository

/**
 * 模型组解析器：把「组选择」翻译成具体的（providerId, model）。
 *
 * 各角色的冷读路径（getXxxProviderId / getXxxModel）都会经此解析——选了模型组时，
 * 取组内首个成员执行（按成员顺序，后续成员留作故障转移扩展点）。
 */
object ModelGroupResolver {

    @Volatile
    private var repository: ModelGroupRepository? = null

    fun install(repo: ModelGroupRepository) {
        repository = repo
    }

    /** providerId 以 "group:" 开头 → 组内首个成员；否则原样返回。 */
    fun resolveSelection(providerId: String, model: String): Pair<String, String> {
        if (!providerId.startsWith(ModelGroupRepository.GROUP_PREFIX)) return providerId to model
        val id = providerId.removePrefix(ModelGroupRepository.GROUP_PREFIX)
        val member = repository?.findById(id)?.members?.firstOrNull() ?: return providerId to model
        return member.providerId to member.model
    }

    /** 选择值是否为模型组。 */
    fun isGroupSelection(providerId: String): Boolean =
        providerId.startsWith(ModelGroupRepository.GROUP_PREFIX)

    /** 组名（供 UI 展示选择状态）；找不到返回 null。 */
    fun groupName(providerId: String): String? {
        if (!isGroupSelection(providerId)) return null
        return repository?.findById(providerId.removePrefix(ModelGroupRepository.GROUP_PREFIX))?.name
    }
}
