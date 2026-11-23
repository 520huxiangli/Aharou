package com.aharou.feature.plugin.data.import

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 一个 Operit 调用在宿主上的可用程度。 */
enum class OperitCompatLevel {
    /** 有对应的宿主方法，语义基本一致。 */
    AVAILABLE,

    /** 能跑，但语义或参数有差异，或要绕道别的工具。 */
    DEGRADED,

    /** 宿主没有对应能力，调用必然失败。 */
    UNAVAILABLE
}

/**
 * 扫描结果的一条。[call] 是脚本里出现的原始写法（`Tools.Files.read`），
 * [replacement] 是宿主方法名或替代路径，[reason] 是写进来源文件的机器可读原因码。
 */
data class OperitCompatFinding(
    val call: String,
    val level: OperitCompatLevel,
    val replacement: String?,
    val reason: String?,
    val count: Int
)

data class OperitCompatReport(
    val findings: List<OperitCompatFinding>,
    val scannedFiles: Int
) {
    val available: List<OperitCompatFinding> get() = findings.filter { it.level == OperitCompatLevel.AVAILABLE }
    val degraded: List<OperitCompatFinding> get() = findings.filter { it.level == OperitCompatLevel.DEGRADED }
    val unavailable: List<OperitCompatFinding>
        get() = findings.filter { it.level == OperitCompatLevel.UNAVAILABLE }

    fun toJson(): JsonObject = buildJsonObject {
        put("scannedFiles", scannedFiles)
        putJsonArray("available") { available.forEach { add(it.call) } }
        putJsonArray("degraded") {
            degraded.forEach { finding ->
                addJsonObject {
                    put("call", finding.call)
                    finding.replacement?.let { put("replacement", it) }
                    finding.reason?.let { put("reason", it) }
                }
            }
        }
        putJsonArray("unavailable") {
            unavailable.forEach { finding ->
                addJsonObject {
                    put("call", finding.call)
                    finding.reason?.let { put("reason", it) }
                }
            }
        }
    }
}

/**
 * 静态扫描 Operit 脚本里用到的宿主能力。
 *
 * 只做正则级识别：Operit 的 `Tools.*` 是注入的全局对象，脚本本身不含类型信息，
 * 做完整 AST 分析收益有限。映射表按宿主方法表（`PluginHostApiCatalog`）逐条对齐，
 * 表里没有的域走默认结论。结果只用于提示（写入来源文件、导入页展示），不阻断导入。
 */
object OperitCompatScanner {

    private val DOT_CALL = Regex("Tools\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)")
    private val BRACKET_CALL = Regex(
        "Tools\\s*\\[\\s*['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]\\s*]\\s*\\[\\s*['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]\\s*]"
    )
    private val TOOLPKG_CALL = Regex("ToolPkg\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)")
    private val NAMED_FEATURE = Regex(
        "\\b(registerXmlRenderPlugin|ComposeUI|ComposeUi|renderCompose|createComposeView)\\b"
    )

    /** Compose DSL 面板写成 `ctx.UI.LazyColumn` 这种形式，按前缀整体识别。 */
    private val CTX_UI = Regex("\\bctx\\s*\\.\\s*UI\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)")

    private data class Rule(val level: OperitCompatLevel, val replacement: String? = null, val reason: String? = null)

    private fun available(method: String) = Rule(OperitCompatLevel.AVAILABLE, method)

    private fun degraded(method: String? = null, reason: String? = null) =
        Rule(OperitCompatLevel.DEGRADED, method, reason)

    private fun unavailable(reason: String) = Rule(OperitCompatLevel.UNAVAILABLE, null, reason)

    fun scan(scripts: List<OperitScript>): OperitCompatReport {
        val hits = LinkedHashMap<String, Int>()
        scripts.forEach { script ->
            DOT_CALL.findAll(script.text).forEach {
                val call = "Tools.${it.groupValues[1]}.${it.groupValues[2]}"
                hits[call] = (hits[call] ?: 0) + 1
            }
            BRACKET_CALL.findAll(script.text).forEach {
                val call = "Tools.${it.groupValues[1]}.${it.groupValues[2]}"
                hits[call] = (hits[call] ?: 0) + 1
            }
            TOOLPKG_CALL.findAll(script.text).forEach {
                val call = "ToolPkg.${it.groupValues[1]}"
                hits[call] = (hits[call] ?: 0) + 1
            }
            NAMED_FEATURE.findAll(script.text).forEach {
                val call = it.groupValues[1]
                hits[call] = (hits[call] ?: 0) + 1
            }
            CTX_UI.findAll(script.text).forEach {
                val call = "ctx.UI.${it.groupValues[1]}"
                hits[call] = (hits[call] ?: 0) + 1
            }
        }
        val findings = hits.map { (call, count) ->
            val rule = ruleFor(call)
            OperitCompatFinding(call, rule.level, rule.replacement, rule.reason, count)
        }.sortedWith(
            compareBy<OperitCompatFinding> { it.level.ordinal }.thenByDescending { it.count }.thenBy { it.call }
        )
        return OperitCompatReport(findings, scripts.size)
    }

    private fun ruleFor(call: String): Rule {
        if (call.startsWith("Tools.")) {
            val parts = call.split('.')
            val domain = parts.getOrNull(1).orEmpty()
            val method = parts.getOrNull(2).orEmpty()
            // 浏览器自动化是整套独立能力，单个方法无法映射，统一标降级避免逐个漏掉
            if (domain == "Net" && method.startsWith("browser")) {
                return degraded("http.request", "browser-automation")
            }
            METHOD_RULES["$domain.$method"]?.let { return it }
            return DOMAIN_RULES[domain] ?: unavailable("unknown-domain")
        }
        if (call.startsWith("ToolPkg.")) {
            return TOOLPKG_RULES[call.removePrefix("ToolPkg.")] ?: degraded(reason = "ipc-bus")
        }
        if (call.startsWith("ctx.UI.")) return available("compose-dsl")
        return NAMED_RULES[call] ?: unavailable("no-host-api")
    }

    private val DOMAIN_RULES: Map<String, Rule> = mapOf(
        "Files" to available("fs.*"),
        "Net" to degraded("http.request"),
        "System" to degraded("bash"),
        "SoftwareSettings" to unavailable("package-settings"),
        "Tasker" to unavailable("external-automation"),
        "UI" to degraded("device.*"),
        "Memory" to unavailable("memory-graph"),
        "FFmpeg" to degraded("bash", "needs-ffmpeg"),
        "Workflow" to available("workflow.*"),
        "Chat" to degraded("chat.*")
    )

    /** 与 `PluginHostApiCatalog` 逐条对齐；没列出的走域默认值。 */
    private val METHOD_RULES: Map<String, Rule> = mapOf(
        "Files.list" to available("fs.list"),
        "Files.read" to available("fs.read"),
        "Files.readBinary" to available("fs.readBinary"),
        "Files.readPart" to available("fs.readRange"),
        "Files.write" to available("fs.write"),
        "Files.writeBinary" to available("fs.writeBinary"),
        "Files.deleteFile" to available("fs.delete"),
        "Files.exists" to available("fs.stat"),
        "Files.move" to available("fs.rename"),
        "Files.copy" to available("fs.copy"),
        "Files.mkdir" to available("fs.mkdir"),
        "Files.find" to available("fs.find"),
        "Files.grep" to available("fs.grep"),
        "Files.grepContext" to available("fs.grep"),
        "Files.info" to available("fs.stat"),
        "Files.create" to available("fs.write"),
        "Files.edit" to available("fs.edit"),
        "Files.unzip" to available("fs.unzip"),
        "Files.apply" to degraded("fs.edit", "patch-diff"),
        "Files.zip" to degraded("bash", "needs-shell"),
        "Files.download" to degraded("http.request", "no-progress"),
        "Files.open" to degraded("ui.openUrl"),
        "Files.share" to degraded("share.text", "files-not-supported"),

        "Net.httpGet" to available("http.request"),
        "Net.httpPost" to available("http.request"),
        "Net.http" to available("http.request"),
        "Net.visit" to degraded("http.request", "no-html-render"),
        "Net.uploadFile" to degraded("http.request", "multipart-manual"),
        "Net.cookies" to degraded(reason = "browser-session"),
        // 工作流域默认可用，唯独增量 patch 没有移植；单方法条目盖过域默认值。
        "Workflow.patch" to unavailable("workflow-patch"),

        "System.sleep" to degraded("bash", "needs-shell"),
        "System.shell" to available("bash"),
        "System.terminal" to available("terminal.run"),
        "System.getDeviceInfo" to available("app.info"),
        "System.sendNotification" to available("notify"),
        "System.getSetting" to available("system.settingsGet"),
        "System.setSetting" to available("system.settingsSet"),
        "System.startApp" to available("app.start"),
        "System.stopApp" to available("app.stop"),
        "System.listApps" to available("app.list"),
        "System.getNotifications" to available("notification.list"),
        "System.intent" to available("intent.start"),
        "System.sendBroadcast" to available("intent.broadcast"),
        "System.toast" to unavailable("page-only-method"),
        "System.usePackage" to degraded("shizuku.exec", "needs-shizuku"),
        "System.installApp" to degraded("shizuku.exec", "needs-shizuku"),
        "System.uninstallApp" to degraded("shizuku.exec", "needs-shizuku"),
        "System.getAppUsageTime" to degraded("tools.execute", "usage-stats"),
        "System.getLocation" to degraded("tools.execute", "device-location"),
        "System.bluetooth" to unavailable("bluetooth"),
        "System.music" to unavailable("media-control"),

        "SoftwareSettings.readEnvironmentVariable" to available("env.get"),
        "SoftwareSettings.writeEnvironmentVariable" to available("env.set"),
        "SoftwareSettings.listModelConfigs" to degraded("model.listProviders"),
        "SoftwareSettings.listFunctionModelConfigs" to degraded("model.listModels"),
        "SoftwareSettings.getFunctionModelConfig" to degraded("model.listModels"),
        "SoftwareSettings.setFunctionModelConfig" to unavailable("model-settings"),
        "SoftwareSettings.createModelConfig" to unavailable("model-settings"),
        "SoftwareSettings.updateModelConfig" to unavailable("model-settings"),
        "SoftwareSettings.deleteModelConfig" to unavailable("model-settings"),
        "SoftwareSettings.testModelConfigConnection" to degraded("tools.execute", "config-tool"),
        "SoftwareSettings.executeSandboxScriptDirect" to degraded("bash"),
        "SoftwareSettings.listCharacterCards" to unavailable("character-cards"),
        "SoftwareSettings.getCharacterCard" to unavailable("character-cards"),
        "SoftwareSettings.createCharacterCard" to unavailable("character-cards"),
        "SoftwareSettings.updateCharacterCard" to unavailable("character-cards"),
        "SoftwareSettings.deleteCharacterCard" to unavailable("character-cards"),
        "SoftwareSettings.setActiveCharacterCard" to unavailable("character-cards"),
        "SoftwareSettings.clearActiveCharacterCard" to unavailable("character-cards"),
        "SoftwareSettings.importCharacterCardFromTavernJson" to unavailable("character-cards"),
        "SoftwareSettings.exportCharacterCardToTavernJson" to unavailable("character-cards"),

        "UI.getPageInfo" to available("device.pageInfo"),
        "UI.captureScreenshot" to available("device.screenshot"),
        "UI.tap" to available("device.tap"),
        "UI.longPress" to available("device.longPress"),
        "UI.setText" to available("device.setText"),
        "UI.swipe" to available("device.swipe"),
        "UI.pressKey" to available("device.key"),
        "UI.runSubAgent" to available("agent.runSubAgent"),
        "UI.clickElement" to degraded("device.tap", "needs-selector"),

        "FFmpeg.execute" to degraded("bash", "needs-ffmpeg"),
        "FFmpeg.convert" to degraded("bash", "needs-ffmpeg"),
        "FFmpeg.info" to degraded("bash", "needs-ffmpeg"),

        "Chat.createNew" to available("chat.createSession"),
        "Chat.sendMessage" to available("chat.sendMessage"),
        "Chat.sendMessageStreaming" to available("chat.sendMessageAndWait"),
        "Chat.call" to available("chat.sendMessage"),
        "Chat.listAll" to available("chat.listSessions"),
        "Chat.listChats" to available("chat.listSessions"),
        "Chat.getMessages" to available("chat.listMessages"),
        "Chat.getMessagesRange" to degraded("chat.listMessages", "no-range"),
        "Chat.deleteChat" to available("chat.deleteSession"),
        "Chat.updateTitle" to available("chat.renameSession"),
        "Chat.switchTo" to available("chat.openSession"),
        "Chat.agentStatus" to available("chat.getRunState"),
        "Chat.findChat" to degraded("chat.listSessions", "client-side-filter"),
        "Chat.startService" to unavailable("no-voice-service"),
        "Chat.listCharacterCards" to unavailable("character-cards")
    )

    private val TOOLPKG_RULES: Map<String, Rule> = mapOf(
        "ipc" to degraded(reason = "ipc-bus"),
        "wasm" to unavailable("wasm"),
        "registerXmlRenderPlugin" to unavailable("chat-inline-xml")
    )

    private val NAMED_RULES: Map<String, Rule> = mapOf(
        "registerXmlRenderPlugin" to unavailable("chat-inline-xml"),
        "ComposeUI" to available("compose-dsl"),
        "ComposeUi" to available("compose-dsl"),
        "renderCompose" to available("compose-dsl"),
        "createComposeView" to available("compose-dsl")
    )
}
