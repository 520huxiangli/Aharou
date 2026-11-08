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
import kotlinx.serialization.json.put

@Singleton
class NapCatTool @Inject constructor(
    private val runtime: Provider<NapCatRuntimeManager>,
    private val repository: NapCatSettingsRepository,
    @param:ApplicationContext private val context: Context,
) : AgentTool() {
    override val name = "napcat"
    override val description = "管理个人 QQ NapCat：状态、安装更新、启停、登录入口、刷新二维码、退出登录与脱敏日志。登录密码及验证码只能在设置页 WebUI 中填写，不接受密码或登录凭据参数。"
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作", enum = ACTIONS),
    )
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> = when (action(args)) {
        "status", "logs", "webui" -> capabilities
        "install", "update" -> setOf(ToolCapability.MODIFY_CONTAINER_ENV, ToolCapability.EXECUTE_COMMANDS)
        else -> setOf(ToolCapability.MODIFY_AGENT_CONFIG, ToolCapability.EXECUTE_COMMANDS)
    }

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = action(args)
        if (action !in ACTIONS) return ToolResult.Error("Unknown NapCat action", "INVALID_ARGUMENT")
        if (args.keys.any { it != "action" && it != "tool_title" }) {
            return ToolResult.Error("Only action is accepted; enter credentials in the settings WebUI", "INVALID_ARGUMENT")
        }
        if (context.mode == AgentMode.PLAN && action !in READ_ACTIONS) {
            return ToolResult.Error("NapCat write operations are unavailable in PLAN mode", "READ_ONLY")
        }
        return try {
            val manager = runtime.get()
            when (action) {
                "install" -> { manager.install(); requireRuntimeSuccess(); status() }
                "update" -> { manager.update(); requireRuntimeSuccess(); status() }
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

    private companion object {
        val READ_ACTIONS = setOf("status", "logs", "webui")
        val ACTIONS = listOf("status", "install", "update", "start", "stop", "login", "refresh_qr", "logout", "logs", "webui")
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
