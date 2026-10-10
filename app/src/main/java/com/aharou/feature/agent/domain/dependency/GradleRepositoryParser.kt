package com.aharou.feature.agent.domain.dependency

/**
 * 从 `settings.gradle(.kts)` / `build.gradle(.kts)` 里提取 Maven 仓库 URL。
 *
 * 覆盖 `google()` / `mavenCentral()` / `gradlePluginPortal()` / `maven { url = uri("...") }` /
 * `maven("...")`（含 groovy `maven { url "..." }`），并兜底公共源 + Google 国内镜像 + JitPack：
 * 项目没声明仓库、或声明的仓库里找不到坐标时，仍有机会从公共源拿到版本。
 */
object GradleRepositoryParser {

    const val GOOGLE = "https://maven.google.com"
    const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
    const val GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
    const val JITPACK = "https://jitpack.io"

    /** Google Maven 的国内镜像；官方源 maven.google.com 在国内不可达，靠它们取版本。 */
    val GOOGLE_MIRRORS = listOf(
        "https://maven.aliyun.com/repository/google",
        "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"
    )

    private val MAVEN_CALL = Regex("""maven\s*\(\s*\x22([^\x22]+)\x22""")
    private val URL_ASSIGN = Regex("""url\s*=\s*(?:uri\s*\(\s*)?\x22([^\x22]+)\x22""")
    private val URL_GROOVY = Regex("""url\s+\x22([^\x22]+)\x22""")

    /** 兜底仓库：公共源 + JitPack。Google 系让镜像先跑，官方源排在其后兜底。 */
    fun defaultRepositories(): List<String> =
        GOOGLE_MIRRORS + listOf(GOOGLE, MAVEN_CENTRAL, GRADLE_PLUGIN_PORTAL, JITPACK)

    /** 解析单个 gradle 脚本文本，返回其中显式声明的仓库 URL（按出现顺序、去重）。 */
    fun parse(content: String): List<String> {
        val found = LinkedHashSet<String>()
        val text = stripComments(content)

        if (Regex("""\bgoogle\s*\(\s*\)""").containsMatchIn(text)) found.add(GOOGLE)
        if (Regex("""\bmavenCentral\s*\(\s*\)""").containsMatchIn(text)) found.add(MAVEN_CENTRAL)
        if (Regex("""\bgradlePluginPortal\s*\(\s*\)""").containsMatchIn(text)) found.add(GRADLE_PLUGIN_PORTAL)

        MAVEN_CALL.findAll(text).forEach { found.add(normalize(it.groupValues[1])) }
        URL_ASSIGN.findAll(text).forEach { found.add(normalize(it.groupValues[1])) }
        URL_GROOVY.findAll(text).forEach { found.add(normalize(it.groupValues[1])) }

        return found.filter { it.isNotBlank() }
    }

    /** 汇总多个脚本文本的声明，并追加兜底仓库（去重，声明项在前）。 */
    fun collect(contents: List<String>): List<String> {
        val declared = LinkedHashSet<String>()
        contents.forEach { declared.addAll(parse(it)) }
        val result = LinkedHashSet<String>()
        declared.forEach { url ->
            // 显式声明的 google() 同样让镜像先跑：官方源不通时会吃满连接超时，顺序反了每次都要白等。
            if (url == GOOGLE) result.addAll(GOOGLE_MIRRORS)
            result.add(url)
        }
        result.addAll(defaultRepositories())
        return result.toList()
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')

    private fun stripComments(content: String): String {
        val sb = StringBuilder(content.length)
        var inBlock = false
        content.lines().forEach { line ->
            var text = line
            if (inBlock) {
                val end = text.indexOf("*/")
                if (end < 0) return@forEach
                text = text.substring(end + 2)
                inBlock = false
            }
            if (text.contains("/*")) {
                val start = text.indexOf("/*")
                if (text.indexOf("*/", start) < 0) {
                    text = text.substring(0, start)
                    inBlock = true
                }
            }
            val hash = indexOfLineComment(text)
            sb.append(if (hash >= 0) text.substring(0, hash) else text).append('\n')
        }
        return sb.toString()
    }

    private fun indexOfLineComment(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { i, ch ->
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '/' && i + 1 < line.length && line[i + 1] == '/' -> return i
            }
        }
        return -1
    }
}
