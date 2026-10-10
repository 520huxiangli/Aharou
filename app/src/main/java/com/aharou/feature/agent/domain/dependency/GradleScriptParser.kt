package com.aharou.feature.agent.domain.dependency

/**
 * `build.gradle(.kts)` / `settings.gradle(.kts)` 的依赖声明解析器（手写行级）。
 *
 * 覆盖实际会写到的形态：
 *   - `implementation("g:a:v")` 等依赖配置调用（implementation/api/compileOnly/runtimeOnly/ksp/kapt/
 *     classpath/debugImplementation/testImplementation/androidTestImplementation/...）
 *   - `implementation(platform("g:a:v"))`
 *   - `id("x") version "1.2.3"`
 *   - `implementation(libs.foo)` / `libs.bundles.foo`（版本在 catalog 里，此处不处理）
 *   - 坐标里带变量 `"g:a:$ver"` / `"g:a:${versions.foo}"`，解同文件的 `val` / `def` / `extra` 变量
 *   - `implementation("g:a") { version { strictly("1.2.3") } }`
 *   - 动态版本（`1.+` / `latest.release`）标记为 DYNAMIC_VERSION，不联网
 *
 * 看起来像依赖声明却解析不出的行进 [ParseResult.unparsed]，不静默吞掉。
 */
class GradleScriptParser {

    data class ParseResult(
        val declarations: List<DependencyDeclaration>,
        val unparsed: List<UnparsedLine>
    )

    private data class Variable(val value: String, val line: Int, val rawLine: String)

    fun parse(content: String, filePath: String): ParseResult {
        val lines = content.lines()
        val variables = collectVariables(lines)
        val declarations = ArrayList<DependencyDeclaration>()
        val unparsed = ArrayList<UnparsedLine>()

        var inBlockComment = false
        lines.forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            var text = rawLine
            if (inBlockComment) {
                val end = text.indexOf("*/")
                if (end < 0) return@forEachIndexed
                text = text.substring(end + 2)
                inBlockComment = false
            }
            if (text.contains("/*")) {
                val start = text.indexOf("/*")
                if (text.indexOf("*/", start) < 0) {
                    text = text.substring(0, start)
                    inBlockComment = true
                }
            }
            val stripped = stripLineComment(text).trim()
            if (stripped.isEmpty()) return@forEachIndexed

            val pluginMatch = PLUGIN_VERSION.find(stripped)
            if (pluginMatch != null) {
                val pluginId = pluginMatch.groupValues[1]
                val rawVersion = pluginMatch.groupValues[2]
                emit(
                    target = declarations,
                    unparsed = unparsed,
                    filePath = filePath,
                    line = lineNo,
                    rawLine = rawLine.trimEnd(),
                    group = pluginId,
                    artifact = "$pluginId.gradle.plugin",
                    configuration = "plugins",
                    type = DeclarationType.PLUGIN_ID_VERSION,
                    rawVersion = rawVersion,
                    variables = variables
                )
                return@forEachIndexed
            }

            val configMatch = CONFIG_CALL.find(stripped) ?: return@forEachIndexed
            val configuration = configMatch.groupValues[1]

            val strings = STRING_LITERAL.findAll(stripped).map { it.groupValues[1] }.toList()
            val coordinate = strings.firstOrNull { it.split(':').size >= 3 && !it.startsWith("project:") }
            if (coordinate != null) {
                val parts = coordinate.split(':')
                val rawVersion = parts.last()
                val strict = STRICTLY.find(stripped)?.groupValues?.get(1)
                emit(
                    target = declarations,
                    unparsed = unparsed,
                    filePath = filePath,
                    line = lineNo,
                    rawLine = rawLine.trimEnd(),
                    group = parts[0],
                    artifact = parts[1],
                    configuration = configuration,
                    type = DeclarationType.BUILD_STRING,
                    rawVersion = if (rawVersion.startsWith("$") && strict != null) strict else rawVersion,
                    variables = variables
                )
            } else if (stripped.contains("project(")) {
                // 模块依赖，无版本可查，正常跳过
            } else if (stripped.contains("libs.")) {
                // 版本目录别名，交由 catalog 解析，正常跳过
            } else {
                unparsed.add(
                    UnparsedLine(filePath, lineNo, stripped, "依赖配置 $configuration 中未找到可识别的坐标字符串")
                )
            }
        }

        return ParseResult(declarations, unparsed)
    }

    private fun collectVariables(lines: List<String>): Map<String, Variable> {
        val result = LinkedHashMap<String, Variable>()
        lines.forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val trimmed = stripLineComment(rawLine).trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            VAR_DEF.find(trimmed)?.let { m ->
                result[m.groupValues[1]] = Variable(m.groupValues[2], lineNo, rawLine.trimEnd())
                return@forEachIndexed
            }
            EXTRA_BY.find(trimmed)?.let { m ->
                result[m.groupValues[1]] = Variable(m.groupValues[2], lineNo, rawLine.trimEnd())
                return@forEachIndexed
            }
            EXTRA_ASSIGN.find(trimmed)?.let { m ->
                result[m.groupValues[1]] = Variable(m.groupValues[2], lineNo, rawLine.trimEnd())
                return@forEachIndexed
            }
            EXTRA_SET.find(trimmed)?.let { m ->
                result[m.groupValues[1]] = Variable(m.groupValues[2], lineNo, rawLine.trimEnd())
                return@forEachIndexed
            }
        }
        return result
    }

    private fun emit(
        target: MutableList<DependencyDeclaration>,
        unparsed: MutableList<UnparsedLine>,
        filePath: String,
        line: Int,
        rawLine: String,
        group: String,
        artifact: String,
        configuration: String?,
        type: DeclarationType,
        rawVersion: String,
        variables: Map<String, Variable>
    ) {
        if (rawVersion.isBlank() || rawVersion == group || rawVersion == artifact) {
            return
        }
        if (isDynamicVersion(rawVersion)) {
            target.add(
                DependencyDeclaration(
                    group = group,
                    artifact = artifact,
                    currentVersion = rawVersion,
                    configuration = configuration,
                    declarationType = DeclarationType.BUILD_STRING,
                    filePath = filePath,
                    line = line,
                    originalText = rawLine
                )
            )
            return
        }

        if (!rawVersion.contains('$')) {
            val start = indexOfToken(rawLine, rawVersion)
            target.add(
                DependencyDeclaration(
                    group = group,
                    artifact = artifact,
                    currentVersion = rawVersion,
                    configuration = configuration,
                    declarationType = type,
                    filePath = filePath,
                    line = line,
                    columnStart = start,
                    columnEnd = if (start >= 0) start + rawVersion.length else 0,
                    originalText = rawLine
                )
            )
            return
        }

        // 版本是变量引用：解出定义位置，编辑落在变量定义行
        val varName = extractVarName(rawVersion)
        val variable = varName?.let { variables[it] } ?: varName?.let { variables[it.substringAfterLast('.')] }
        if (varName == null || variable == null) {
            unparsed.add(
                UnparsedLine(filePath, line, rawLine, "版本变量 $rawVersion 未在文件中找到定义")
            )
            return
        }
        val start = indexOfToken(variable.rawLine, variable.value)
        target.add(
            DependencyDeclaration(
                group = group,
                artifact = artifact,
                currentVersion = variable.value,
                configuration = configuration,
                declarationType = DeclarationType.BUILD_STRING_VAR,
                filePath = filePath,
                line = variable.line,
                columnStart = start,
                columnEnd = if (start >= 0) start + variable.value.length else 0,
                originalText = variable.rawLine
            )
        )
    }

    private fun indexOfToken(line: String, token: String): Int {
        if (token.isEmpty()) return -1
        val eq = line.indexOf('=')
        val from = if (eq >= 0) eq else 0
        val idx = line.indexOf(token, from)
        return if (idx >= 0) idx else line.indexOf(token)
    }

    /** 提取 `$ver` 或 `${versions.foo}` 里的变量名。 */
    private fun extractVarName(ref: String): String? {
        val dollar = ref.indexOf('$')
        if (dollar < 0) return null
        if (ref.length > dollar + 1 && ref[dollar + 1] == '{') {
            val close = ref.indexOf('}', dollar + 2)
            if (close < 0) return null
            return ref.substring(dollar + 2, close).trim().takeIf { it.isNotEmpty() }
        }
        val tail = ref.substring(dollar + 1)
        val name = tail.takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        return name.takeIf { it.isNotEmpty() }
    }

    private fun isDynamicVersion(version: String): Boolean {
        val v = version.trim()
        return v.isEmpty() || v == "latest.release" || v == "latest.integration" || v == "+" ||
            v.endsWith("+") || v == "latest.milestone"
    }

    private fun stripLineComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { i, ch ->
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '/' && i + 1 < line.length && line[i + 1] == '/' -> return line.substring(0, i)
            }
        }
        return line
    }

    private companion object {
        val VAR_DEF = Regex("""(?:\bval|\bdef)\s+([A-Za-z_]\w*)\s*(?::\s*[A-Za-z_][\w<>?., ]*)?=\s*\x22([^\x22]*)\x22""")
        val EXTRA_ASSIGN = Regex("""extra(?:s)?\s*\[\s*\x22([^\x22]+)\x22\s*\]\s*=\s*\x22([^\x22]*)\x22""")
        val EXTRA_SET = Regex("""extra(?:s)?\.set\s*\(\s*\x22([^\x22]+)\x22\s*,\s*\x22([^\x22]*)\x22""")
        val EXTRA_BY = Regex("""\bval\s+([A-Za-z_]\w*)\s+by\s+extra\s*\(\s*\x22([^\x22]*)\x22""")
        val CONFIG_CALL = Regex(
            """\b(implementation|api|compileOnly|runtimeOnly|ksp|kapt|classpath|kaptTest|kaptAndroidTest|""" +
                """debugImplementation|releaseImplementation|testImplementation|androidTestImplementation|""" +
                """annotationProcessor|testCompileOnly|testRuntimeOnly|androidTestUtil|lintChecks)\s*\("""
        )
        val STRING_LITERAL = Regex("""\x22([^\x22]*)\x22""")
        val PLUGIN_VERSION = Regex("""\bid\s*\(\s*\x22([^\x22]+)\x22\s*\)\s*version\s*\(?\s*\x22([^\x22]+)\x22""")
        val STRICTLY = Regex("""strictly\s*\(\s*\x22([^\x22]+)\x22""")
    }
}
