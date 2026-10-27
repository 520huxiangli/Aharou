package com.aharou.feature.settings.data.catalog.recipe

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

// 从 assets/provider-recipes.json 读 provider recipe。解析宽松：未知字段忽略、缺省字段走默认值、
// 非法条目（结构坏掉 / providerId 为空 / protocol 不认识）跳过并记日志，绝不让整份文件失效。
@Singleton
class ProviderRecipeLoader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    @Volatile
    private var cached: Map<String, ProviderRecipe>? = null

    // 按 provider id（大小写不敏感，含 aliases）查 recipe；未知 provider 返回 null。
    fun recipeFor(providerId: String): ProviderRecipe? = lookup(load(), providerId)

    fun allRecipes(): Map<String, ProviderRecipe> = load()

    private fun load(): Map<String, ProviderRecipe> {
        cached?.let { return it }
        val parsed = runCatching {
            val raw = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
            parseRecipes(raw)
        }.onFailure { FileLogger.w(TAG, "读取 provider recipe 失败", it) }
            .getOrDefault(emptyMap())
        cached = parsed
        return parsed
    }

    companion object {
        const val TAG = "ProviderRecipeLoader"
        const val ASSET_FILE_NAME = "provider-recipes.json"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        internal fun lookup(recipes: Map<String, ProviderRecipe>, providerId: String): ProviderRecipe? {
            val key = providerId.trim().lowercase()
            if (key.isEmpty()) return null
            return recipes[key]
        }

        // 纯解析入口（不碰 assets）：JSON 数组或 { "recipes": [...] } 两种形态都接受。
        internal fun parseRecipes(raw: String): Map<String, ProviderRecipe> {
            val root = runCatching { json.parseToJsonElement(raw) }.getOrElse {
                FileLogger.w(TAG, "provider-recipes.json 不是合法 JSON")
                return emptyMap()
            }
            val array = when (root) {
                is JsonArray -> root
                is JsonObject -> root["recipes"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            val result = LinkedHashMap<String, ProviderRecipe>()
            var skipped = 0
            for (element in array) {
                val obj = element as? JsonObject
                if (obj == null) {
                    skipped++
                    continue
                }
                val recipe = runCatching { json.decodeFromJsonElement(ProviderRecipe.serializer(), obj) }
                    .getOrNull()
                if (recipe == null || recipe.providerId.isBlank() || recipe.protocol !in ProviderProtocol.ALL) {
                    skipped++
                    continue
                }
                val id = recipe.providerId.trim().lowercase()
                if (result.putIfAbsent(id, recipe) != null) skipped++
                for (alias in recipe.aliases) {
                    if (alias.isBlank()) continue
                    result.putIfAbsent(alias.trim().lowercase(), recipe)
                }
            }
            if (skipped > 0) FileLogger.w(TAG, "provider-recipes.json 跳过 $skipped 条非法记录")
            return result
        }
    }
}
