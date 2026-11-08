package com.aharou.feature.im.data

import android.content.Context
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.domain.napcat.NapCatRuntimeManager
import com.aharou.feature.im.redactNapCatText
import com.aharou.feature.im.service.NapCatBotService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

@Singleton
class ConfigNapCatFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: NapCatSettingsRepository,
    private val runtime: Provider<NapCatRuntimeManager>,
    @param:Named("qq_napcat") private val bridge: Provider<ImBotBridge>,
) {
    fun registerInto(registry: ConfigRegistry) = with(registry) {
        register(DataStoreBoolField(
            "$PREFIX.enabled", "启用个人 QQ", "保存后立即启动或停止个人 QQ 服务。",
            repository.configFlow.map { it.enabled },
            setter = {
                repository.setEnabled(it)
                if (it) NapCatBotService.start(context) else NapCatBotService.stop(context)
            },
        ))
        stringField(this, "proxy", "代理", { it.proxy }) {
            validateUrl(it, setOf("http", "socks", "socks5"))
            require(it.isEmpty() || URI(it).port in 1..65535) { "Proxy requires an explicit port between 1 and 65535" }
            repository.setProxy(it)
        }
        stringField(this, "base_url", "OneBot 地址", { it.baseUrl }) {
            validateUrl(it); runtime.get().updateConnection("base_url", it)
        }
        stringField(this, "websocket_url", "OneBot WebSocket 地址", { it.websocketUrl }) {
            validateUrl(it, setOf("ws", "wss")); runtime.get().updateConnection("websocket_url", it)
        }
        stringField(this, "web_ui_url", "WebUI 地址", { it.webUiUrl }) {
            validateUrl(it); runtime.get().updateConnection("web_ui_url", it)
        }
        stringField(this, "token", "OneBot Token", { it.token }, ConfigRisk.SENSITIVE) {
            runtime.get().updateConnection("token", it)
        }
        stringField(this, "web_ui_token", "WebUI Token", { it.webUiToken }, ConfigRisk.SENSITIVE) {
            runtime.get().updateConnection("web_ui_token", it)
        }
        register(ReadOnlyField("$PREFIX.account_id", "QQ 账号", "运行时确认的实际登录账号；请在登录页面切换账号。", ConfigSchema.Str()) {
            ConfigValue.Str(runBlocking { repository.current().accountId })
        })
        policyField(this, "respond_private", "响应私聊", { it.respondPrivate }) { c, v -> c.copy(respondPrivate = v) }
        policyField(this, "respond_groups", "响应群聊", { it.respondGroups }) { c, v -> c.copy(respondGroups = v) }
        policyField(this, "require_mention", "群聊需要提及", { it.requireMention }) { c, v -> c.copy(requireMention = v) }
        policyField(this, "respond_self", "响应自己发送的消息", { it.respondSelf }) { c, v -> c.copy(respondSelf = v) }
        register(IdCollection(false))
        register(IdCollection(true))
        register(ClosureField(
            "$PREFIX.session_defaults", "会话默认配置", "对象：workspace_path、provider_id、model；提供商和模型必须同时填写或同时清空。",
            ConfigSchema.Json,
            reader = {
                val c = runBlocking { repository.current() }
                ConfigValue.Obj(mapOf(
                    "workspace_path" to ConfigValue.Str(c.workspacePath),
                    "provider_id" to ConfigValue.Str(c.providerId), "model" to ConfigValue.Str(c.model),
                ))
            },
            writer = { value ->
                val obj = (value as? ConfigValue.Obj)?.value ?: error("Expected an object")
                require(obj.keys == setOf("workspace_path", "provider_id", "model")) {
                    "Expected workspace_path, provider_id and model"
                }
                fun string(key: String) = (obj[key] as? ConfigValue.Str)?.value?.trim()
                    ?: error("$key must be a string")
                val workspace = string("workspace_path")
                val provider = string("provider_id")
                val model = string("model")
                require(workspace.isEmpty() || workspace.startsWith('/')) {
                    "Workspace must be an absolute path"
                }
                require(provider.isEmpty() == model.isEmpty()) { "Provider and model must be set together" }
                runBlocking { repository.setSessionDefaults(workspace, provider, model) }
            },
        ))
        register(ReadOnlyField("$PREFIX.runtime", "运行环境", "安装运行阶段与进度，不含登录凭据。", ConfigSchema.Json) {
            val s = runtime.get().state.value
            ConfigValue.Obj(mapOf("phase" to ConfigValue.Str(s.phase.toString()),
                "progress" to ConfigValue.Double(s.progress.toDouble()), "stage" to ConfigValue.Str(s.stage)))
        })
        register(ReadOnlyField("$PREFIX.login", "登录状态", "只返回登录状态与账号，不返回二维码。", ConfigSchema.Json) {
            val s = runtime.get().login.value
            ConfigValue.Obj(mapOf("logged_in" to ConfigValue.Bool(s.loggedIn), "status" to ConfigValue.Str(s.status),
                "account_id" to ConfigValue.Str(s.accountId.orEmpty()),
                "last_account_id" to ConfigValue.Str(runBlocking { repository.current().lastAccountId })))
        })
        register(ReadOnlyField("$PREFIX.state", "连接状态", "个人 QQ 消息桥实际状态。", ConfigSchema.Str()) {
            ConfigValue.Str(when (bridge.get().state.value) {
                ImChannelState.Stopped -> "stopped"
                ImChannelState.Connecting -> "connecting"
                is ImChannelState.Connected -> "connected"
                is ImChannelState.Error -> "error"
            })
        })
        register(ReadOnlyField("$PREFIX.version", "运行环境版本", "当前安装版本。", ConfigSchema.Str()) {
            ConfigValue.Str(redactNapCatText(runtime.get().state.value.version, runBlocking { repository.current() }))
        })
        register(ReadOnlyField("$PREFIX.error", "运行错误", "脱敏后的安装、登录或连接错误。", ConfigSchema.Str()) {
            val error = runtime.get().state.value.error.ifEmpty { runtime.get().login.value.error }
                .ifEmpty { (bridge.get().state.value as? ImChannelState.Error)?.message.orEmpty() }
            val qr = runtime.get().login.value.qrCode
            ConfigValue.Str(redactNapCatText(if (qr.isEmpty()) error else error.replace(qr, "[QR REDACTED]"), runBlocking { repository.current() }))
        })
    }

    private fun stringField(
        registry: ConfigRegistry, key: String, title: String, get: (NapCatConfig) -> String,
        risk: ConfigRisk = ConfigRisk.NORMAL, set: suspend (String) -> Unit,
    ) {
        registry.register(DataStoreStrField(
            "$PREFIX.$key", title, "个人 QQ 设置；修改连接参数时会同步运行环境，并重启已启动的实例。", repository.configFlow.map(get),
            setter = { set(it.trim()) }, risk = risk,
        ))
    }

    private fun policyField(
        registry: ConfigRegistry, key: String, title: String, get: (NapCatConfig) -> Boolean,
        change: (NapCatConfig, Boolean) -> NapCatConfig,
    ) {
        registry.register(DataStoreBoolField(
            "$PREFIX.$key", title, "修改当前响应策略并保留其他策略字段。", repository.configFlow.map(get),
            setter = { savePolicy(change(repository.current(), it)) },
        ))
    }

    private suspend fun savePolicy(c: NapCatConfig) = repository.updatePolicy(
        c.respondPrivate, c.respondGroups, c.requireMention, c.respondSelf, c.allowedUsers, c.allowedGroups,
    )

    private inner class IdCollection(private val groups: Boolean) : ConfigCollection {
        override val basePath = "$PREFIX.${if (groups) "allowed_groups" else "allowed_users"}"
        override val displayName = if (groups) "允许的 QQ 群" else "允许的 QQ 用户"
        override val description = "add 使用 {\"id\":\"数字QQ号或群号\"}；remove 使用原字符串 ID；空集合不限制。"
        private fun ids(c: NapCatConfig) = if (groups) c.allowedGroups else c.allowedUsers
        override fun childIds(): List<String> = runBlocking { ids(repository.current()).sorted() }
        override fun fields(forId: String): List<ConfigField> = if (forId !in childIds()) emptyList() else listOf(
            ReadOnlyField("$basePath.$forId.id", "QQ ID", "数字字符串，不转换为整数。", ConfigSchema.Str()) { ConfigValue.Str(forId) },
        )
        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value ?: error("Expected {id: string}")
            require(obj.keys == setOf("id")) { "Expected only id" }
            val id = (obj["id"] as? ConfigValue.Str)?.value ?: error("ID must be a string")
            require(id.matches(Regex("[0-9]+"))) { "ID must be a numeric string" }
            runBlocking {
                val c = repository.current()
                require(id !in ids(c)) { "ID already exists" }
                savePolicy(if (groups) c.copy(allowedGroups = c.allowedGroups + id) else c.copy(allowedUsers = c.allowedUsers + id))
            }
            return id
        }
        override fun remove(id: String) {
            runBlocking {
                val c = repository.current()
                require(id in ids(c)) { "ID does not exist" }
                savePolicy(if (groups) c.copy(allowedGroups = c.allowedGroups - id) else c.copy(allowedUsers = c.allowedUsers - id))
            }
        }
    }

    private fun validateUrl(value: String, schemes: Set<String> = setOf("http", "https")) {
        if (value.isEmpty()) return
        val uri = URI(value)
        require(uri.scheme in schemes && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Expected a URL without credentials, query or fragment"
        }
    }

    private companion object { const val PREFIX = "im.qq_napcat" }
}
