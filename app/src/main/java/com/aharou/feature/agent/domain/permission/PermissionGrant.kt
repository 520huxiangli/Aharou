package com.aharou.feature.agent.domain.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 授权档位（一次授权的作用域与时效）。移植自 nanoMuse 的 grant scope：
 * - [ONCE]：仅本次调用，不记忆；
 * - [SESSION]：本次会话内有效，内存态、随会话隔离、重启即失；
 * - [TIME_24H]：24 小时后失效（落盘为带过期时刻的规则）；
 * - [ALWAYS]：长期有效（落盘为规则，直到用户撤销）。
 *
 * 与 [PermissionChoice] 一一对应但不共用：Choice 是 UI 的按钮语义，Scope 是落地存储语义。
 */
enum class ApprovalScope {
    ONCE, SESSION, TIME_24H, ALWAYS;

    /** 是否需要落盘（长期/24 小时档位）；会话档只存内存。 */
    val persistent: Boolean get() = this == TIME_24H || this == ALWAYS

    companion object {
        /** 从弹窗选择解析落地档位；[PermissionChoice.REJECT]/[ONCE] 都视为不记忆。 */
        fun from(choice: PermissionChoice): ApprovalScope = when (choice) {
            PermissionChoice.ALWAYS -> ALWAYS
            PermissionChoice.SESSION -> SESSION
            PermissionChoice.TIME_24H -> TIME_24H
            PermissionChoice.ONCE, PermissionChoice.REJECT -> ONCE
        }
    }
}

/**
 * 授权键：把「一次授权」绑定到「工具 + 目标」而不是「整个工具」。语义对齐 nanoMuse 的
 * `grant_key(tool, target)`：`shell:git`、`web_fetch:example.com`、`send:someone@x.com`。
 *
 * 目标提取（[targetsFor]）：
 * - shell 类工具：命令里用到的每个程序名（子命令分发器带子命令，复用 [ShellCommandParser.rememberablePrefix]），
 *   例如 `git status | head` → `["git status", "head"]`，每个都要被授权才算命中；
 * - 网络类工具（webfetch 等）：URL 的主机名；
 * - sendFile：要外发的文件路径；
 * - 其它无可绑定目标的工具（MCP、python 之类）：整工具（target 为空）。
 */
object GrantKey {

    /** 组装单个目标键：无目标退化为裸工具名。 */
    fun of(tool: String, target: String?): String =
        if (target.isNullOrBlank()) tool else "$tool:$target"

    /**
     * 某次调用的目标列表（用于「始终允许」绑定与命中判定）。空列表表示该工具没有可绑定的目标，
     * 授权粒度退化为整工具。
     */
    fun targetsFor(toolName: String, args: Map<String, JsonElement>): List<String> {
        if (isShellTool(toolName, args)) {
            val command = commandOf(args) ?: return emptyList()
            return ShellCommandParser.analyze(command).segments
                .mapNotNull { ShellCommandParser.rememberablePrefix(it) }
                .distinct()
        }
        if (isHostTool(toolName)) {
            val url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return hostOf(url)?.let { listOf(it) } ?: emptyList()
        }
        if (toolName == SEND_FILE_TOOL) {
            return fileTargets(args)
        }
        return emptyList()
    }

    /** 该工具是否为「按程序名绑定」的 shell 类工具（与策略引擎的 shell 判定一致）。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == "start" || action == "send"
        }
        return false
    }

    /** 从 url 参数解析主机名（小写、去尾部点）；解析不出返回 null。 */
    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val host = runCatching { URI(trimmed).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: HOST_FALLBACK.find(trimmed)?.groupValues?.get(1)
        return host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
    }

    private fun commandOf(args: Map<String, JsonElement>): String? =
        ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content

    private fun fileTargets(args: Map<String, JsonElement>): List<String> {
        val array = args["paths"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { p -> p.isNotBlank() } }
    }

    // 与 ToolPermissionPolicyEngine 保持一致的常量；重复声明以免反向依赖工作流层。
    private const val SHIZUKU_TOOL = "Shizuku"
    private const val TERMINAL_TOOL = "terminal"
    private const val SEND_FILE_TOOL = "sendFile"
    private val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)
    private val HOST_TOOLS = setOf("webfetch")
    private val HOST_FALLBACK = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE)

    private fun isHostTool(toolName: String): Boolean = toolName in HOST_TOOLS
}

/**
 * 会话档（[ApprovalScope.SESSION]）授权的内存登记表：按「会话 id + 授权键」隔离，重启即失。
 *
 * 刻意不落盘——「本次会话内有效」的语义就是内存态；落盘的 24 小时/长期档由权限规则文件承载。
 * 用 [ConcurrentHashMap.newKeySet] 支撑多会话并行下的并发读写（评估线程与用户交互线程可能同时触碰）。
 */
object SessionGrantStore {
    private val grants: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 记录一条会话档授权。会话 id 为空时用全局空键（进程级生效）。 */
    fun grant(sessionId: String, key: String) {
        grants.add(compositeKey(sessionId, key))
    }

    /** 该会话下 [key] 是否已被会话档授权覆盖。 */
    fun isGranted(sessionId: String, key: String): Boolean =
        grants.contains(compositeKey(sessionId, key))

    /**
     * 复合键 [key]（可能逗号拼接多个目标）是否被完全覆盖：先看整键，再看每个单目标。
     * 覆盖判定对齐 nanoMuse——「多个目标时每个都要被授权」。
     */
    fun covers(sessionId: String, key: String): Boolean {
        if (grants.contains(compositeKey(sessionId, key))) return true
        val parts = key.split(',').filter { it.isNotBlank() }
        return parts.isNotEmpty() && parts.all { grants.contains(compositeKey(sessionId, it)) }
    }

    /** 会话结束时清掉它的全部授权（内存态，供未来会话清理钩子调用）。 */
    fun clearSession(sessionId: String) {
        val prefix = "$sessionId\u0000"
        grants.removeIf { it.startsWith(prefix) }
    }

    private fun compositeKey(sessionId: String, key: String) = "$sessionId\u0000$key"
}
