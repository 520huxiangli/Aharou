package com.aharou.feature.plugin.data.import

/** 解析出的面板入口；[screen] 是包根相对路径，写清单时由转换器补 `operit/pkg/` 前缀。 */
internal data class OperitUiEntry(
    val id: String,
    val title: String,
    val screen: String
)

/**
 * 从 Operit 包的 JS 文本里还原面板入口。
 *
 * 入口面的来源按 Operit 自己的语义搬：`registerNavigationEntry` 声明的导航项
 * （`surface: main_sidebar_plugins` 即侧栏）+ `registerToolboxUiModule` 声明的工具箱面板；
 * `registerUiRoute` 只是页面，本身不是可见入口，只有在既无导航也无模块时才兜底取第一个。
 *
 * 为什么用正则加括号配对、不引 JS 解析器：真实包的注册都是普通过程代码，但写法差异很大
 * （TS 编译产物的 `__importDefault(require(...))`、`screen` 直接写字符串、`title` 先赋给变量、
 * 注册裹在 `try/catch` 里），引解析器会让导入器体积和失败面一起变大。静态扫描只求「大概找得到」：
 * 找不到 screen 就跳过，导入照常进行（面板入口只是附加信息，不能反过来拦住安装）。
 */
internal object OperitUiEntryParser {

    fun parse(scripts: Map<String, String>, fallbackTitle: String): List<OperitUiEntry> {
        if (scripts.isEmpty()) return emptyList()
        val registrations = mutableListOf<Registration>()
        val navigations = mutableListOf<Navigation>()
        scripts.forEach { (path, text) ->
            if (text.isEmpty()) return@forEach
            // 别名必须按调用点前的文本算：同名 `const ui` / `const Screen` 在不同 try 块里
            // 分别 require 不同页面（clawd-pet 就是这样），全文只建一张表会张冠李戴。
            callObjects(text, ROUTE_CALL).forEach { call ->
                registrations += registration(
                    call, text, path, scripts.keys, toolbox = false
                )
            }
            callObjects(text, MODULE_CALL).forEach { call ->
                registrations += registration(
                    call, text, path, scripts.keys, toolbox = true
                )
            }
            callObjects(text, NAV_CALL).forEach { call ->
                val variables = buildVariables(text, call.index)
                val fields = objectFields(call.body)
                navigations += Navigation(
                    route = fields["route"]?.let { literalText(it, variables) },
                    surface = fields["surface"]?.let { literalText(it, variables) },
                    title = fields["title"]?.let { readTitle(it, variables) }
                )
            }
        }

        // 宿主只支持 compose_dsl 运行时（清单里的 screen 由 Compose DSL 层渲染）
        val usable = registrations.filter {
            it.screen != null && (it.runtime == null || it.runtime == COMPOSE_DSL)
        }
        val displayTitle = cleanTitle(fallbackTitle).ifEmpty { DEFAULT_TITLE }
        if (usable.isEmpty()) {
            val fallback = firstUiScript(scripts.keys) ?: return emptyList()
            return listOf(OperitUiEntry(DEFAULT_ID, displayTitle, fallback))
        }

        val entries = mutableListOf<OperitUiEntry>()
        val usedScreens = HashSet<String>()
        val usedIds = HashSet<String>()

        fun add(target: Registration?, title: String?) {
            if (target == null || entries.size >= MAX_ENTRIES) return
            val screen = target.screen ?: return
            if (!usedScreens.add(screen)) return
            entries += OperitUiEntry(
                id = uniqueId(sanitizeId(target.id), usedIds),
                title = title ?: target.title ?: displayTitle,
                screen = screen
            )
        }

        // 侧栏导航排前面：多个导航项时用户从侧栏看到的是它们，其余排后面
        navigations.filter { it.route != null }
            .sortedBy { if (it.surface == SIDEBAR_SURFACE) 0 else 1 }
            .forEach { navigation ->
                add(usable.firstOrNull { navigation.matches(it) }, navigation.title)
            }
        // 工具箱 UI 模块在 Operit 里本身就是工具箱里的可见条目
        usable.filter { it.toolbox }.forEach { add(it, null) }
        // 既无导航也无模块的包：保留一个兜底入口，否则面板完全打不开
        if (entries.isEmpty()) add(usable.first(), null)
        return entries
    }

    private fun registration(
        call: Call,
        text: String,
        scriptPath: String,
        files: Set<String>,
        toolbox: Boolean
    ): Registration {
        val bindings = buildBindings(scriptPath, text, call.index)
        val variables = buildVariables(text, call.index)
        val fields = objectFields(call.body)
        return Registration(
            id = fields["id"]?.let { literalText(it, variables) },
            route = fields["route"]?.let { literalText(it, variables) },
            title = fields["title"]?.let { readTitle(it, variables) },
            runtime = fields["runtime"]?.let { literalText(it, variables) },
            screen = fields["screen"]?.let {
                resolveScreen(it, scriptPath, text, bindings, files, 0)
            },
            toolbox = toolbox
        )
    }

    /** [index] 是调用在文件里的起始下标，用来只扫描它前面的赋值。[body] 是第一个对象字面量。 */
    private class Call(val index: Int, val body: String)

    private class Registration(
        val id: String?,
        val route: String?,
        val title: String?,
        val runtime: String?,
        val screen: String?,
        val toolbox: Boolean
    )

    /** [route] 形如 `toolpkg:<包>:ui:<routeId>`，末段即注册项 ID。 */
    private class Navigation(val route: String?, val surface: String?, val title: String?) {
        val routeId: String? get() = route?.substringAfterLast(':')?.takeIf { it.isNotEmpty() }

        fun matches(candidate: Registration): Boolean =
            (routeId != null && candidate.id == routeId) ||
                (route != null && candidate.route == route) ||
                (routeId != null && candidate.route != null && candidate.route == route)
    }

    // ---- 别名：变量名 → 包内路径 ----

    /**
     * 把「变量名 → require 到的包内路径」连成一张表。
     *
     * 覆盖实测写法：`var ui = __importDefault(require("./x.js"))`、
     * `var Screen = ui.default`、`const S = ui && (ui.default || ui)`、
     * `const { default: X } = require("./x.js")`、压缩产物里的 `PanelScreen=mod?...:mod` 续赋值。
     */
    private fun buildBindings(scriptPath: String, text: String, limit: Int): Bindings {
        val scope = text.substring(0, limit.coerceIn(0, text.length))
        val aliases = HashMap<String, String>()
        val expressions = HashMap<String, String>()

        DESTRUCTURED_REQUIRE.findAll(scope).forEach { match ->
            val resolved = resolveRequire(scriptPath, match.groupValues[2]) ?: return@forEach
            match.groupValues[1].split(',').forEach { part ->
                val name = part.substringAfterLast(':').trim()
                if (IDENTIFIER.matches(name)) aliases[name] = resolved
            }
        }

        val assignments = mutableListOf<Assignment>()
        DECLARED_ASSIGN.findAll(scope).forEach { match ->
            assignments += Assignment(match.range.first, match.groupValues[1], match.groupValues[2])
        }
        BARE_ASSIGN.findAll(scope).forEach { match ->
            assignments += Assignment(match.range.first, match.groupValues[1], match.groupValues[2])
        }

        // 按出现顺序解析，后写覆盖前写：同一名字在不同 try 块里重新 require 时，
        // 调用点取到的就是最近一次赋值（与 JS 块作用域的直观结果一致）。
        assignments.distinctBy { it.name to it.index }.sortedBy { it.index }.forEach { assignment ->
            expressions[assignment.name] = assignment.expr
            val value = requireIn(assignment.expr)?.let { resolveRequire(scriptPath, it) }
                ?: headIdentifier(assignment.expr)?.let { aliases[it] }
            if (value != null) aliases[assignment.name] = value
        }
        return Bindings(aliases, expressions)
    }

    private class Assignment(val index: Int, val name: String, val expr: String)

    /** [aliases] 是名字 → 包内路径；[expressions] 是名字 → 原始赋值表达式（用于多跳解引用）。 */
    private class Bindings(val aliases: Map<String, String>, val expressions: Map<String, String>)

    // ---- 变量：标题等字面量先赋给变量再用 ----

    private fun buildVariables(text: String, limit: Int): Map<String, String> {
        val scope = text.substring(0, limit.coerceIn(0, text.length))
        val variables = HashMap<String, String>()
        VAR_OBJECT.findAll(scope).forEach { match ->
            val brace = match.range.last
            readBalanced(scope, brace)?.let { variables[match.groupValues[1]] = it }
        }
        VAR_STRING.findAll(scope).forEach { match ->
            val quote = match.range.last
            val end = skipString(scope, quote)
            if (end > quote) variables[match.groupValues[1]] = scope.substring(quote, end)
        }
        return variables
    }

    // ---- 注册调用 ----

    private fun callObjects(text: String, name: String): List<Call> {
        val result = mutableListOf<Call>()
        var from = 0
        while (true) {
            val at = text.indexOf(name, from)
            if (at < 0) break
            from = at + name.length
            val before = text.getOrNull(at - 1)
            if (before != null && isIdentifierChar(before)) continue
            var index = skipWhitespaceAndComments(text, from)
            if (text.getOrNull(index) != '(') continue
            index = skipWhitespaceAndComments(text, index + 1)
            if (text.getOrNull(index) != '{') continue
            readBalanced(text, index)?.let { result += Call(at, it) }
        }
        return result
    }

    private fun resolveScreen(
        expr: String,
        scriptPath: String,
        text: String,
        bindings: Bindings,
        files: Set<String>,
        depth: Int
    ): String? {
        // `A || B` 是带兜底的写法（实测 `EnhanceScreen || PanelScreen`），逐个试，取第一个能落地的
        return expr.split("||").firstNotNullOfOrNull {
            resolveScreenValue(it.trim(), scriptPath, text, bindings, files, depth)
        }
    }

    private fun resolveScreenValue(
        value: String,
        scriptPath: String,
        text: String,
        bindings: Bindings,
        files: Set<String>,
        depth: Int
    ): String? {
        if (value.isEmpty()) return null
        if (value.first() == '\'' || value.first() == '"') {
            // 字符串形式的 screen 是包根相对（实测 dist/ 布局的包也写成 dist/ui/...）
            return readQuoted(value, 0)?.let(::rootRelative)?.let { existingFile(it, files) }
        }
        requireIn(value)?.let { required ->
            return resolveRequire(scriptPath, required)?.let { existingFile(it, files) }
        }
        val head = headIdentifier(value) ?: return null
        bindings.aliases[head]?.let { resolved ->
            existingFile(resolved, files)?.let { return it }
        }
        if (depth < MAX_DEREF_DEPTH) {
            // `const screen = loadPanel()` 这层中转（实测 ajing）：顺着表达式再解一层
            val expression = bindings.expressions[head]
            if (expression != null && expression != value) {
                resolveScreen(expression, scriptPath, text, bindings, files, depth + 1)?.let { return it }
            }
        }
        // 惰性加载写法：`screen: loadPanel()` 里的 require 写在函数体内
        return functionRequire(value, text, scriptPath, files)
    }

    private fun functionRequire(
        value: String,
        text: String,
        scriptPath: String,
        files: Set<String>
    ): String? {
        val name = FUNCTION_CALL.find(value)?.groupValues?.get(1) ?: return null
        val declaration = Regex("function\\s+" + Regex.escape(name) + "\\s*\\(").find(text) ?: return null
        var index = text.indexOf('(', declaration.range.first)
        if (index < 0) return null
        var depth = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\'' || c == '"' || c == '`' -> index = skipString(text, index)
                c == '(' -> { depth++; index++ }
                c == ')' -> { depth--; index++; if (depth == 0) break }
                else -> index++
            }
        }
        while (index < text.length && text[index] != '{') index++
        val body = readBalanced(text, index) ?: return null
        return requireIn(body)?.let { resolveRequire(scriptPath, it) }?.let { existingFile(it, files) }
    }

    /** CommonJS 允许省扩展名（实测 `require("./ui/settings")`），按宿主能加载的脚本后缀补齐。 */
    private fun existingFile(path: String, files: Set<String>): String? {
        if (path in files) return path
        return SCRIPT_EXTENSIONS.firstNotNullOfOrNull { suffix ->
            (path + suffix).takeIf { it in files }
        }
    }

    private fun firstUiScript(files: Set<String>): String? =
        files.firstOrNull { it.endsWith(".ui.js", ignoreCase = true) }

    // ---- 对象字段 ----

    /** 取对象字面量的顶层字段；值保持原始文本（字符串 / 对象 / 表达式）。 */
    private fun objectFields(body: String): Map<String, String> {
        if (body.length < 2) return emptyMap()
        val fields = LinkedHashMap<String, String>()
        splitTopLevel(body.substring(1, body.length - 1)).forEach { part ->
            val colon = topLevelColon(part)
            if (colon == null) {
                // ES6 属性简写：`{ id: 'x', title }` 里的 title 就是同名变量
                val key = part.trim()
                if (IDENTIFIER.matches(key)) fields.putIfAbsent(key, key)
                return@forEach
            }
            val key = part.substring(0, colon).trim().trim('"', '\'')
            if (!IDENTIFIER.matches(key)) return@forEach
            fields.putIfAbsent(key, part.substring(colon + 1).trim())
        }
        return fields
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\'' || c == '"' || c == '`' -> index = skipString(text, index)
                c == '/' && text.getOrNull(index + 1) == '/' -> {
                    val newline = text.indexOf('\n', index)
                    index = if (newline < 0) text.length else newline
                }
                c == '/' && text.getOrNull(index + 1) == '*' -> {
                    val end = text.indexOf("*/", index + 2)
                    index = if (end < 0) text.length else end + 2
                }
                c == '{' || c == '[' || c == '(' -> { depth++; index++ }
                c == '}' || c == ']' || c == ')' -> { depth--; index++ }
                c == ',' && depth == 0 -> {
                    parts += text.substring(start, index)
                    start = index + 1
                    index++
                }
                else -> index++
            }
        }
        parts += text.substring(start)
        return parts
    }

    private fun topLevelColon(text: String): Int? {
        var depth = 0
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\'' || c == '"' || c == '`' -> index = skipString(text, index)
                c == '{' || c == '[' || c == '(' -> { depth++; index++ }
                c == '}' || c == ']' || c == ')' -> { depth--; index++ }
                c == ':' && depth == 0 -> return index
                else -> index++
            }
        }
        return null
    }

    // ---- 标题 ----

    private fun readTitle(value: String, variables: Map<String, String>): String? {
        val expr = value.trim()
        val source = when {
            expr.startsWith("{") -> expr
            expr.firstOrNull() == '\'' || expr.firstOrNull() == '"' -> return nonBlank(cleanTitle(readQuoted(expr, 0)))
            else -> variables[expr]
        } ?: return null
        if (!source.startsWith("{")) return nonBlank(cleanTitle(readQuoted(source, 0)))
        val fields = objectFields(source)
        val candidates = listOf("zh", "zh-cn", "zh-CN", "default", "en")
        candidates.forEach { key ->
            val text = fields[key]?.let { literalText(it, variables) }?.let(::cleanTitle)
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun nonBlank(text: String?): String? = text?.takeIf { it.isNotEmpty() }

    /** 取字符串字面量，或解引用一个存了字符串的变量。 */
    private fun literalText(value: String, variables: Map<String, String>): String? {
        val expr = value.trim()
        if (expr.firstOrNull() == '\'' || expr.firstOrNull() == '"') return readQuoted(expr, 0)
        return variables[expr]?.let { readQuoted(it, 0) }
    }

    private fun cleanTitle(raw: String?): String {
        val text = raw.orEmpty().replace('\r', ' ').replace('\n', ' ').trim()
        val filtered = text.filterNot { it.isISOControl() }.replace(WHITESPACE, " ").trim()
        return if (filtered.length > MAX_TITLE) filtered.take(MAX_TITLE) else filtered
    }

    // ---- 路径 ----

    private fun resolveRequire(scriptPath: String, raw: String): String? {
        val spec = raw.trim()
        if (spec.isEmpty() || !spec.startsWith(".") || spec.contains('\\')) return null
        return joinPath(scriptPath.substringBeforeLast('/', ""), spec)
    }

    private fun rootRelative(raw: String): String? {
        val spec = raw.trim()
        if (spec.isEmpty() || spec.startsWith("/") || spec.contains('\\')) return null
        return joinPath("", spec)
    }

    private fun joinPath(base: String, spec: String): String? {
        val segments = ArrayDeque<String>()
        (if (base.isEmpty()) emptyList() else base.split('/')).forEach { segments += it }
        spec.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments += part
            }
        }
        if (segments.isEmpty()) return null
        val joined = segments.joinToString("/")
        if (joined.startsWith("/") || joined.contains("..")) return null
        return joined
    }

    // ---- 字面量扫描 ----

    private fun readBalanced(text: String, start: Int): String? {
        if (text.getOrNull(start) != '{') return null
        var depth = 0
        var index = start
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\'' || c == '"' || c == '`' -> index = skipString(text, index)
                c == '/' && text.getOrNull(index + 1) == '/' -> {
                    val newline = text.indexOf('\n', index)
                    index = if (newline < 0) text.length else newline
                }
                c == '/' && text.getOrNull(index + 1) == '*' -> {
                    val end = text.indexOf("*/", index + 2)
                    index = if (end < 0) text.length else end + 2
                }
                c == '{' -> { depth++; index++ }
                c == '}' -> {
                    depth--
                    index++
                    if (depth == 0) return text.substring(start, index)
                }
                else -> index++
            }
        }
        return null
    }

    /** 返回字符串结束引号之后的下标；未闭合时返回文本末尾，避免整段扫描中断。 */
    private fun skipString(text: String, start: Int): Int {
        val quote = text.getOrNull(start) ?: return start + 1
        if (quote != '\'' && quote != '"' && quote != '`') return start + 1
        var index = start + 1
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\\' -> index += 2
                c == quote -> return index + 1
                c == '\n' && quote != '`' -> return index
                else -> index++
            }
        }
        return text.length
    }

    private fun readQuoted(text: String, start: Int): String? {
        val quote = text.getOrNull(start) ?: return null
        if (quote != '\'' && quote != '"') return null
        val builder = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\\' -> index = appendEscape(text, index, builder)
                c == quote -> return builder.toString()
                c == '\n' -> return builder.toString()
                else -> { builder.append(c); index++ }
            }
        }
        return builder.toString()
    }

    private fun appendEscape(text: String, start: Int, out: StringBuilder): Int {
        val next = text.getOrNull(start + 1) ?: return text.length
        return when (next) {
            'n' -> { out.append('\n'); start + 2 }
            'r' -> { out.append('\r'); start + 2 }
            't' -> { out.append('\t'); start + 2 }
            'b' -> { out.append('\b'); start + 2 }
            'f' -> { out.append('\u000C'); start + 2 }
            'v' -> { out.append('\u000B'); start + 2 }
            '0' -> { out.append('\u0000'); start + 2 }
            'u' -> {
                val hex = text.substring(start + 2, minOf(start + 6, text.length))
                if (hex.length == 4) {
                    hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                    start + 6
                } else {
                    start + 2
                }
            }
            'x' -> {
                val hex = text.substring(start + 2, minOf(start + 4, text.length))
                if (hex.length == 2) {
                    hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                    start + 4
                } else {
                    start + 2
                }
            }
            '\n' -> start + 2
            else -> { out.append(next); start + 2 }
        }
    }

    private fun skipWhitespaceAndComments(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val c = text[index]
            when {
                c.isWhitespace() -> index++
                c == '/' && text.getOrNull(index + 1) == '/' -> {
                    val newline = text.indexOf('\n', index)
                    index = if (newline < 0) text.length else newline + 1
                }
                c == '/' && text.getOrNull(index + 1) == '*' -> {
                    val end = text.indexOf("*/", index + 2)
                    index = if (end < 0) text.length else end + 2
                }
                else -> return index
            }
        }
        return index
    }

    private fun requireIn(expr: String): String? =
        REQUIRE_ARG.find(expr)?.groupValues?.get(1)

    private fun headIdentifier(expr: String): String? =
        HEAD_IDENTIFIER.find(expr.trim().trimStart('(', '!'))?.groupValues?.get(1)

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun sanitizeId(raw: String?): String {
        val base = raw?.trim().orEmpty()
        if (LOCAL_ID.matches(base)) return base
        val cleaned = buildString {
            base.forEach { c -> append(if ((c.code < 128 && c.isLetterOrDigit()) || c == '_' || c == '-') c else '_') }
        }.trim('_', '-')
        val head = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "panel"
        val candidate = head.take(48).trim('_', '-')
        return if (LOCAL_ID.matches(candidate)) candidate else DEFAULT_ID
    }

    /** 同一插件内入口 ID 不能重复（宿主按 ID 开页面），重名时加序号。 */
    private fun uniqueId(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var index = 2
        while (true) {
            val candidate = "${base}_$index"
            if (used.add(candidate)) return candidate
            index++
        }
    }

    private const val SIDEBAR_SURFACE = "main_sidebar_plugins"
    private const val COMPOSE_DSL = "compose_dsl"
    private const val DEFAULT_ID = "panel"
    private const val DEFAULT_TITLE = "Operit Plugin"
    private const val MAX_TITLE = 80
    /** 宿主每个槽位每个插件最多两个入口，超了整个 uiEntries 会被判无效。 */
    private const val MAX_ENTRIES = 2
    /** 解引用最多跟两层变量中转（`const s = load()` → 函数体内 require）。 */
    private const val MAX_DEREF_DEPTH = 2
    private const val ROUTE_CALL = "registerUiRoute"
    private const val MODULE_CALL = "registerToolboxUiModule"
    private const val NAV_CALL = "registerNavigationEntry"

    private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val LOCAL_ID = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val WHITESPACE = Regex("\\s+")
    private val HEAD_IDENTIFIER = Regex("^([A-Za-z_$][A-Za-z0-9_$]*)")
    private val FUNCTION_CALL = Regex("^([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(")
    private val SCRIPT_EXTENSIONS = listOf(".js", ".mjs", ".cjs")
    private val REQUIRE_ARG = Regex("require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
    private val DECLARED_ASSIGN = Regex(
        "(?:var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=(?![=>])\\s*([^;{}\\n]{1,240})"
    )
    private val BARE_ASSIGN = Regex(
        "(?<![A-Za-z0-9_$.])([A-Za-z_$][A-Za-z0-9_$]*)\\s*=(?![=>])\\s*([^;{}\\n]{1,240})"
    )
    private val DESTRUCTURED_REQUIRE = Regex(
        "(?:var|let|const)\\s*\\{([^}]{1,200})\\}\\s*=(?![=>])[^;\\n]{0,120}?require\\s*\\(\\s*['\"]([^'\"]+)['\"]"
    )
    private val VAR_OBJECT = Regex("(?:var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*\\{")
    private val VAR_STRING = Regex("(?:var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(['\"])")
}
