package com.aharou.feature.plugin.data.market

/**
 * 分类聚合与本地过滤。
 *
 * 市场没有搜索接口（`/v2/search.json` 实测 404），按 Operit 的做法：搜索只过滤已加载的页；
 * 分类优先用 `manifest.json` 给出的完整分类，manifest 拿不到时退回已加载条目里出现过的分类。
 */
object OperitMarketCatalog {

    fun categories(items: List<OperitMarketListing>): List<OperitMarketCategoryOption> =
        items.mapNotNull { it.categoryId }
            .distinct()
            .map { OperitMarketCategoryOption(it, "") }

    /**
     * 匹配标题、描述、详情、分类与类型，与 Operit 的本地搜索口径一致；
     * 多带上作者与 ID，方便用包名片段也能找到。
     */
    fun filter(
        items: List<OperitMarketListing>,
        categoryId: String?,
        query: String
    ): List<OperitMarketListing> {
        val keyword = query.trim()
        return items.filter { item ->
            val inCategory = categoryId == null || item.categoryId == categoryId
            if (!inCategory) return@filter false
            keyword.isEmpty() ||
                (item.title.contains(keyword, ignoreCase = true) ||
                    item.description.contains(keyword, ignoreCase = true) ||
                    item.detail.contains(keyword, ignoreCase = true) ||
                    item.categoryId.orEmpty().contains(keyword, ignoreCase = true) ||
                    item.type.name.contains(keyword, ignoreCase = true) ||
                    item.author.contains(keyword, ignoreCase = true) ||
                    item.id.contains(keyword, ignoreCase = true))
        }
    }
}
