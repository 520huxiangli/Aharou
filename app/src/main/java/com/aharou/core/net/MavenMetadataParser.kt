package com.aharou.core.net

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * 解析 Maven 仓库的 `maven-metadata.xml`。
 *
 * 用已在依赖里的 jsoup（xmlParser）解析，不引入新的 XML 库：
 * ```xml
 * <metadata>
 *   <versioning>
 *     <latest>2.0.0</latest>
 *     <release>2.0.0</release>
 *     <versions><version>1.0.0</version><version>2.0.0-rc1</version></versions>
 *   </versioning>
 * </metadata>
 * ```
 * 三个字段都可能缺失（部分仓库只给 `<release>` 或只给 `<versions>`），缺一个不算解析失败；
 * 全空才返回 null。
 */
object MavenMetadataParser {

    data class Metadata(
        val latest: String?,
        val release: String?,
        val versions: List<String>
    )

    fun parse(xml: String): Metadata? {
        val doc = try {
            Jsoup.parse(xml, "", Parser.xmlParser())
        } catch (e: Exception) {
            return null
        }
        val latest = doc.selectFirst("metadata > versioning > latest")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val release = doc.selectFirst("metadata > versioning > release")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val versions = doc.select("metadata > versioning > versions > version")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        if (latest == null && release == null && versions.isEmpty()) return null
        return Metadata(latest, release, versions)
    }
}
