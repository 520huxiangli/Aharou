package com.aharou.feature.plugin.export

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把一个 MCP server 配置导成可直接转发的 JSON。
 *
 * 结构对齐 `manage_mcp` 的 add_stdio / add_http 入参：对方把 `action` 与其余字段照抄给
 * 工具调用即可落地，不用自己猜字段名。
 */
@Singleton
class McpServerExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val json = Json { prettyPrint = true }

    fun export(server: McpServerConfig): File? {
        val target = ExportFiles.newFile(
            context,
            "mcp-${ExportFiles.safeName(server.name)}",
            "json"
        )
        return try {
            target.writeText(
                json.encodeToString(JsonObject.serializer(), buildMcpExportJson(server)),
                Charsets.UTF_8
            )
            target
        } catch (e: Exception) {
            FileLogger.e(TAG, "导出 MCP 配置失败：${server.name}", e)
            target.delete()
            null
        }
    }

    private companion object {
        const val TAG = "McpServerExporter"
    }
}

/**
 * 配置 → 可分享 JSON 的纯变换，单独拎出来是为了能离线核对脱敏规则（这是安全面，不能只靠看）。
 *
 * **硬约束：绝不带出凭据。** headers / env 的值、URL 里的密钥参数、args 里的密钥值一律清空，
 * 只保留字段名——导出包是要发给别人的，用户自己的 token 一旦混进去就收不回来了。
 * OAuth 块整块不导出（里面有 access/refresh token），仅提示对方重新授权。
 */
internal fun buildMcpExportJson(server: McpServerConfig): JsonObject = buildJsonObject {
    put("_guide", buildGuide(server))
    put("action", if (server.isStdio) "add_stdio" else "add_http")
    put("server_name", server.name)
    if (server.isStdio) {
        put("command", server.command.orEmpty())
        putJsonArray("args") { scrubArgs(server.args).forEach { add(it) } }
        putJsonObject("env") { server.env.keys.sorted().forEach { put(it, "") } }
    } else {
        put("url", scrubUrl(server.url.orEmpty()))
        putJsonObject("headers") { server.headers.keys.sorted().forEach { put(it, "") } }
    }
    put("keep_alive", server.keepAlive)
    // 分享场景默认落全局：对方拿去多半想跨项目用，怎么改成项目级写进了 _guide
    put("scope", "global")
}

private fun buildGuide(server: McpServerConfig): JsonObject = buildJsonObject {
    put(
        "import",
        "把 action / server_name 及后续字段照抄给 Agent 的 manage_mcp 工具（action 已填好），" +
            "或在「设置 → MCP」里新建同名 server 后逐项填写。"
    )
    put(
        "credentials",
        "headers / env 的值已全部清空，URL 查询参数与 args 里的密钥值也已清空——此处需填你自己的密钥" +
            "（manage_mcp 工具不接收 headers/env，需在「设置 → MCP」编辑页补填）。导出不会带上任何凭据。"
    )
    val blank = blankFieldNames(server)
    if (blank.isNotEmpty()) {
        put("blank_fields", buildJsonArray { blank.forEach { add(it) } })
    }
    if (server.oauth != null) {
        put(
            "oauth",
            "该 server 使用 OAuth 授权：导入后需要在 MCP 编辑页重新登录授权，本文件不包含任何令牌。"
        )
    }
    put("scope", "scope=global 表示对所有项目生效；只想在当前项目用就改成 project（manage_mcp 同名入参）。")
}

/** 只报字段名，不报值——对方要知道填哪几个框，不需要知道原来填的是什么。 */
private fun blankFieldNames(server: McpServerConfig): List<String> = buildList {
    if (server.isStdio) {
        server.env.keys.sorted().forEach { add("env.$it") }
        // 与整表遮罩后的结果逐位比对：`--token xxx` 这种值写在下一个参数里的也能抓出来
        server.args.zip(scrubArgs(server.args)).forEachIndexed { index, (raw, masked) ->
            if (raw != masked) add("args[$index]")
        }
    } else {
        server.headers.keys.sorted().forEach { add("headers.$it") }
        if (scrubUrl(server.url.orEmpty()) != server.url.orEmpty()) add("url")
    }
}

/**
 * URL 里藏密钥的两种常见写法：`user:pass@host` 与 `?apiKey=xxx`。
 * 这两处不清空的话，光清 headers 等于没清。
 */
private fun scrubUrl(url: String): String {
    if (url.isBlank()) return url
    val withoutUserInfo = url.replace(USER_INFO, "$1")
    val queryStart = withoutUserInfo.indexOf('?')
    if (queryStart < 0) return withoutUserInfo
    val base = withoutUserInfo.substring(0, queryStart)
    val query = withoutUserInfo.substring(queryStart + 1)
    val scrubbed = query.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) pair
        else {
            val key = pair.substring(0, eq)
            if (CREDENTIAL_KEY.containsMatchIn(key)) "$key=" else pair
        }
    }
    return "$base?$scrubbed"
}

/**
 * args 里的密钥值：`--api-key=xxx`、`--token xxx`、`--header=Authorization: Bearer xxx`
 * 三种写法都要遮，且只遮值不丢参数名，否则对方不知道这条是干什么的。
 */
private fun scrubArgs(args: List<String>): List<String> {
    val result = ArrayList<String>(args.size)
    var maskNext = false
    for (arg in args) {
        val isFlag = arg.startsWith("-")
        if (maskNext && !isFlag) {
            result += ""
            maskNext = false
            continue
        }
        result += scrubArg(arg)
        maskNext = isFlag && !arg.contains('=') && CREDENTIAL_KEY.containsMatchIn(arg)
    }
    return result
}

private fun scrubArg(arg: String): String {
    val eq = arg.indexOf('=')
    if (eq <= 0) return arg
    val flag = arg.substring(0, eq)
    val value = arg.substring(eq + 1)
    // `--header=Authorization: Bearer xxx`：参数名不带凭据味，值里裹着整条凭据
    val leaky = CREDENTIAL_KEY.containsMatchIn(flag) || BEARER_VALUE.containsMatchIn(value)
    return if (leaky) "$flag=" else arg
}

private val CREDENTIAL_KEY = Regex(
    "(?i)(token|secret|passw|api[-_]?key|authorization|\\bauth\\b|cookie|session|bearer|signature|\\bsig\\b)"
)
private val BEARER_VALUE = Regex("(?i)(bearer\\s+\\S+|(token|key|secret|password)\\s*=\\s*\\S+)")
private val USER_INFO = Regex("^(https?://)[^/@\\s]+@")
