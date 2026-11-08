package com.aharou.feature.im

import android.content.Context
import com.aharou.core.util.SecretMasker
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.domain.NapCatChannelClient
import com.aharou.feature.im.domain.napcat.NapCatRuntimeManager
import com.aharou.feature.im.service.NapCatBotService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class NapCatTool @Inject constructor(
    private val runtime: Provider<NapCatRuntimeManager>,
    private val repository: NapCatSettingsRepository,
    private val channel: NapCatChannelClient,
    @param:ApplicationContext private val context: Context,
) : AgentTool() {
    override val name = "napcat"
    override val description = "管理个人 QQ NapCat：状态、安装更新、启停、登录入口、刷新二维码、退出登录与脱敏日志，只读查询群列表、群成员与好友，以及在该独立 Debian 环境里执行命令改配置。登录密码及验证码只能在设置页 WebUI 中填写，不接受密码或登录凭据参数。"
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作", enum = ACTIONS),
        "group_id" to ToolParameter("group_id", ParameterType.STRING, "群号，仅 group_members 使用", required = false),
        "command" to ToolParameter("command", ParameterType.STRING, "要在个人 QQ 环境里执行的命令，仅 exec 使用", required = false),
    )
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> = when (action(args)) {
        "status", "logs", "webui", "groups", "group_members", "friends" -> capabilities
        "install", "update", "cancel_install", "exec" -> setOf(ToolCapability.MODIFY_CONTAINER_ENV, ToolCapability.EXECUTE_COMMANDS)
        else -> setOf(ToolCapability.MODIFY_AGENT_CONFIG, ToolCapability.EXECUTE_COMMANDS)
    }

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = action(args)
        if (action !in ACTIONS) return ToolResult.Error("Unknown NapCat action", "INVALID_ARGUMENT")
        if (args.keys.any { it != "action" && it != "tool_title" && it != "group_id" && it != "command" }) {
            return ToolResult.Error("Only action is accepted; enter credentials in the settings WebUI", "INVALID_ARGUMENT")
        }
        if (args.containsKey("group_id") && action != "group_members") {
            return ToolResult.Error("group_id is only accepted by group_members", "INVALID_ARGUMENT")
        }
        if (args.containsKey("command") != (action == "exec")) {
            return ToolResult.Error("command is only accepted by exec", "INVALID_ARGUMENT")
        }
        if (context.mode == AgentMode.PLAN && action !in READ_ACTIONS) {
            return ToolResult.Error("NapCat write operations are unavailable in PLAN mode", "READ_ONLY")
        }
        return try {
            val manager = runtime.get()
            when (action) {
                "install" -> { manager.install(); requireRuntimeSuccess(); status() }
                "update" -> { manager.update(); requireRuntimeSuccess(); status() }
                "cancel_install" -> { manager.cancelInstallation(); status() }
                "groups" -> readGroups()
                "group_members" -> readGroupMembers(args["group_id"]?.jsonPrimitive?.contentOrNull)
                "friends" -> readFriends()
                "exec" -> runInEnvironment(args["command"]?.jsonPrimitive?.contentOrNull, manager)
                "start" -> {
                    manager.start()
                    requireRuntimeSuccess()
                    repository.setEnabled(true)
                    NapCatBotService.start(this.context)
                    status()
                }
                "stop" -> {
                    repository.setEnabled(false)
                    NapCatBotService.stop(this.context)
                    manager.stop()
                    requireRuntimeSuccess()
                    status()
                }
                "logout" -> {
                    NapCatBotService.logout(this.context)
                    ToolResult.Success(buildJsonObject {
                        put("requested", "logout")
                        put("message", "已请求退出账号；请查询登录状态确认结果。")
                    })
                }
                "refresh_qr" -> { manager.refreshQrCode(); requireLoginSuccess(); status() }
                "login", "webui" -> webUi()
                "logs" -> {
                    val config = repository.current()
                    ToolResult.Success(buildJsonObject {
                        put("logs", JsonArray(manager.logs.value.map { JsonPrimitive(redact(it, config)) }))
                    })
                }
                else -> status()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val config = try {
                repository.current()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            val message = if (config == null) "NapCat operation failed (${e.javaClass.simpleName})"
                else redact(e.message ?: e.javaClass.simpleName, config)
            ToolResult.Error(message, "NAPCAT_FAILED")
        }
    }

    private fun requireRuntimeSuccess() {
        val state = runtime.get().state.value
        check(state.phase != "error" && state.error.isEmpty()) { state.error.ifEmpty { "NapCat operation failed" } }
    }

    private fun requireLoginSuccess() {
        check(runtime.get().login.value.error.isEmpty()) { runtime.get().login.value.error }
    }

    private fun redact(text: String, config: NapCatConfig): String {
        val qr = runtime.get().login.value.qrCode
        return redactNapCatText(if (qr.isEmpty()) text else text.replace(qr, "[QR REDACTED]"), config)
    }

    private suspend fun status(): ToolResult {
        val manager = runtime.get()
        val config = repository.current()
        val state = manager.state.value
        val login = manager.login.value
        return ToolResult.Success(buildJsonObject {
            put("enabled", config.enabled)
            put("phase", state.phase.toString())
            put("progress", state.progress.toString())
            put("stage", state.stage)
            put("version", redact(state.version.orEmpty(), config))
            put("error", redact(state.error.orEmpty(), config))
            put("logged_in", login.loggedIn)
            put("login_status", login.status)
            put("last_account_id", config.lastAccountId)
            put("account_id", login.accountId.orEmpty())
            put("login_error", redact(login.error.orEmpty(), config))
        })
    }

    private suspend fun webUi(): ToolResult {
        val raw = runtime.get().webUiUrl()
            ?: return ToolResult.Error("NapCat WebUI is unavailable; start NapCat and open personal QQ settings", "WEBUI_UNAVAILABLE")
        val uri = URI(raw)
        require(uri.scheme in setOf("http", "https") && uri.host in setOf("127.0.0.1", "localhost", "::1", "[::1]")) {
            "NapCat WebUI must use a local HTTP entry"
        }
        val entry = URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString()
        return ToolResult.Success(buildJsonObject {
            put("url", entry)
            put("message", "从设置 → IM 接入 → 个人 QQ 打开 WebUI 完成扫码、密码或验证码交互；工具不返回 WebUI Token 或二维码登录凭据。")
        })
    }

    private fun action(args: Map<String, JsonElement>): String? = (args["action"] as? JsonPrimitive)?.contentOrNull

    /** 在个人 QQ 的独立环境里执行一条命令，输出做凭据脱敏。 */
    private suspend fun runInEnvironment(command: String?, manager: NapCatRuntimeManager): ToolResult {
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) return ToolResult.Error("exec 需要 command", "MISSING_COMMAND")
        val output = manager.exec(cmd)
        val config = repository.current()
        val limited = if (output.length > MAX_EXEC_CHARS) output.take(MAX_EXEC_CHARS) + "\n[输出已截断]" else output
        return ToolResult.Success(buildJsonObject {
            put("command", cmd)
            put("output", redactNapCatText(limited, config))
        })
    }

    private suspend fun readGroups(): ToolResult = readOnly {        val cfg = repository.current()
        val list = channel.query(cfg, "get_group_list").jsonArray.map { element ->
            val group = element.jsonObject
            buildJsonObject {
                put("group_id", group["group_id"]?.jsonPrimitive?.longOrNull ?: 0L)
                put("group_name", group["group_name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("member_count", group["member_count"]?.jsonPrimitive?.intOrNull ?: 0)
            }
        }
        ToolResult.Success(buildJsonObject { put("groups", JsonArray(list)) })
    }

    private suspend fun readGroupMembers(groupId: String?): ToolResult {
        val id = groupId?.trim()?.takeIf { it.matches(Regex("[0-9]{5,20}")) }
            ?: return ToolResult.Error("group_members 需要数字群号 group_id", "MISSING_GROUP_ID")
        return readOnly {
            val cfg = repository.current()
            val list = channel.query(cfg, "get_group_member_list", buildJsonObject { put("group_id", id.toLong()) })
                .jsonArray.map { element ->
                    val member = element.jsonObject
                    buildJsonObject {
                        put("user_id", member["user_id"]?.jsonPrimitive?.longOrNull ?: 0L)
                        put("nickname", member["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("card", member["card"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("role", member["role"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
            ToolResult.Success(buildJsonObject { put("group_id", id); put("members", JsonArray(list)) })
        }
    }

    /** 好友列表只给标识与备注昵称：手机号、邮箱等联系方式不提供给模型。 */
    private suspend fun readFriends(): ToolResult = readOnly {
        val cfg = repository.current()
        val list = channel.query(cfg, "get_friend_list").jsonArray.map { element ->
            val friend = element.jsonObject
            buildJsonObject {
                put("user_id", friend["user_id"]?.jsonPrimitive?.longOrNull ?: 0L)
                put("nickname", friend["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("remark", friend["remark"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
        ToolResult.Success(buildJsonObject { put("friends", JsonArray(list)) })
    }

    private inline fun readOnly(block: () -> ToolResult): ToolResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult.Error("读取个人 QQ 列表失败：${e.message ?: e.javaClass.simpleName}", "NAPCAT_READ_FAILED")
    }

    private companion object {
        val READ_ACTIONS = setOf("status", "logs", "webui", "groups", "group_members", "friends")
        val ACTIONS = listOf("status", "install", "update", "cancel_install", "start", "stop", "login", "refresh_qr", "logout", "logs", "webui", "groups", "group_members", "friends", "exec")
        /** 工具结果里单次带回的最大字符数。 */
        const val MAX_EXEC_CHARS = 12_000
    }
}

internal fun redactNapCatText(text: String, config: NapCatConfig): String {
    var safe = text
    listOf(config.token, config.webUiToken).filter(String::isNotEmpty).forEach {
        safe = safe.replace(it, "[REDACTED]")
    }
    safe = safe.replace(Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE), "[URL REDACTED]")
    safe = safe.replace(Regex("(?i)(?:qrcode|qr_code|二维码|password|passwd|token|authorization|cookie)\\s*[:=]\\s*[^\\s,;]+"), "[CREDENTIAL REDACTED]")
    safe = safe.replace(Regex("data:image/[^\\s]+", RegexOption.IGNORE_CASE), "[QR REDACTED]")
    return SecretMasker.mask(safe)
}
