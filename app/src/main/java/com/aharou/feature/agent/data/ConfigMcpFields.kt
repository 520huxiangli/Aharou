package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.oauthStatusOf
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 服务器 → 配置通道（集合式）。
 *
 * 只编辑全局作用域的 `~/.aharou/mcp.json`；项目级那份随工作区走，改动语义是
 * 「给这个仓库加 server」，不适合从全局 config 通道悄悄写。仓库接口是整表替换
 * （[McpConfigRepository.setGlobalServers]），所以单字段写回时先取全表、替换一项、
 * 再整体落盘。
 *
 * 集合 id 是 server 名（受名称校验约束，仅 ASCII 字母数字下划线连字符）。
 * `setGlobalServers` 是整表替换，add 同名会覆盖既有 server——覆盖时保留 payload
 * 表达不了的字段（headers / env / enabled / disabled_tools 及 oauth 令牌块），
 * 避免「同名覆盖」把用户的授权令牌冲掉。
 */
@Singleton
class ConfigMcpFields @Inject constructor(
    private val mcpConfig: McpConfigRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(ServerCollection())
    }

    private fun servers(): List<McpServerConfig> = runBlocking { mcpConfig.getGlobalServers() }

    private fun server(name: String) = servers().firstOrNull { it.name == name }

    private fun saveAll(updated: List<McpServerConfig>) {
        runBlocking { mcpConfig.setGlobalServers(updated) }
    }

    private fun mutate(name: String, transform: (McpServerConfig) -> McpServerConfig) {
        val all = servers()
        val index = all.indexOfFirst { it.name == name }
        if (index < 0) throw ConfigError.InvalidValue("MCP server 不存在：$name")
        saveAll(all.toMutableList().also { it[index] = transform(it[index]) })
    }

    private inner class ServerCollection : ConfigCollection {
        override val basePath = "mcp.servers"
        override val displayName = "MCP 服务器"
        override val description =
            "全局 mcp.json 里的 server。子字段：url / headers / command / args / env / enabled / disabled_tools / oauth（只读：status / client_id / authorization_server / 授权与令牌端点 / scope / expires_at，以及存在时才出现的 access_token / refresh_token / client_secret）。"

        override fun childIds(): List<String> = servers().map { it.name }

        override fun fields(forId: String): List<ConfigField> {
            val current = server(forId) ?: return emptyList()
            val oauthSnapshot = current.oauth
            val path = "$basePath.$forId"

            fun text(
                segment: String,
                label: String,
                desc: String,
                risk: ConfigRisk = ConfigRisk.NORMAL,
                read: (McpServerConfig) -> String,
                apply: (McpServerConfig, String) -> McpServerConfig,
            ) = ClosureField(
                path = "$path.$segment",
                displayName = "$label（$forId）",
                description = desc,
                valueSchema = ConfigSchema.Str(),
                risk = risk,
                revertable = true,
                reader = { ConfigValue.Str(server(forId)?.let(read).orEmpty()) },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    mutate(forId) { apply(it, s) }
                },
            )

            fun readonly(
                segment: String,
                label: String,
                desc: String,
                schema: ConfigSchema = ConfigSchema.Str(),
                risk: ConfigRisk = ConfigRisk.NORMAL,
                read: (McpServerConfig) -> String,
            ) = ReadOnlyField(
                path = "$path.$segment",
                displayName = "$label（$forId）",
                description = desc,
                valueSchema = schema,
                risk = risk,
                reader = { ConfigValue.Str(server(forId)?.let(read).orEmpty()) },
            )

            // OAuth 子字段全部只读：本通道只暴露授权状态，不提供写入口。未配置 oauth 块时
            // 仅保留 status（NOT_CONFIGURED），不展开明细，也不谎报为「未授权」。
            // 令牌类字段（access_token / refresh_token / client_secret）标 SENSITIVE，
            // 由 core.config 的打码机制在展示面遮住，绝不返回明文。
            val oauthFields = buildList<ConfigField> {
                add(
                    readonly(
                        "oauth.status",
                        "OAuth 状态",
                        "NOT_CONFIGURED（未配置 OAuth）/ NOT_AUTHORIZED（未授权）/ AUTHORIZED（已授权）/ EXPIRED（过期且无刷新令牌）。",
                        schema = ConfigSchema.StrEnum(
                            listOf("NOT_CONFIGURED", "NOT_AUTHORIZED", "AUTHORIZED", "EXPIRED"),
                        ),
                        read = { s -> s.oauth?.let { oauthStatusOf(it).name } ?: "NOT_CONFIGURED" },
                    ),
                )
                val o = oauthSnapshot ?: return@buildList
                add(readonly("oauth.client_id", "OAuth 客户端 ID", "授权服务器分配的 client_id（非机密）。", read = { it.oauth?.clientId.orEmpty() }))
                add(readonly("oauth.authorization_server", "授权服务器", "OAuth 授权服务器标识/地址（非机密）。", read = { it.oauth?.authorizationServer.orEmpty() }))
                add(readonly("oauth.authorization_endpoint", "授权端点", "authorization_endpoint（非机密）。", read = { it.oauth?.authorizationEndpoint.orEmpty() }))
                add(readonly("oauth.token_endpoint", "令牌端点", "token_endpoint（非机密）。", read = { it.oauth?.tokenEndpoint.orEmpty() }))
                add(readonly("oauth.redirect_uri", "回调地址", "OAuth 回调 URI（非机密）。", read = { it.oauth?.redirectUri.orEmpty() }))
                add(readonly("oauth.scope", "授权范围", "申请的 scope（非机密）。", read = { it.oauth?.scope.orEmpty() }))
                add(readonly("oauth.expires_at", "过期时间", "访问令牌过期时刻（epoch 毫秒）；0 表示未知。", read = { it.oauth?.expiresAt?.toString().orEmpty() }))
                // 令牌字段仅在确实存在时出现（避免空值时被遮成「已隐藏」而误导），且一律 SENSITIVE。
                if (o.hasAccessToken) add(readonly("oauth.access_token", "访问令牌", "accessToken（密文/密钥），展示面已打码。", risk = ConfigRisk.SENSITIVE, read = { it.oauth?.accessToken.orEmpty() }))
                if (o.hasRefreshToken) add(readonly("oauth.refresh_token", "刷新令牌", "refreshToken（密文/密钥），展示面已打码。", risk = ConfigRisk.SENSITIVE, read = { it.oauth?.refreshToken.orEmpty() }))
                if (o.clientSecret.isNotBlank()) add(readonly("oauth.client_secret", "客户端密钥", "clientSecret（密文/密钥），展示面已打码。", risk = ConfigRisk.SENSITIVE, read = { it.oauth?.clientSecret.orEmpty() }))
            }

            return listOf(
                text(
                    "url", "远程地址", "HTTP 型 server 的地址；与 command 二选一。",
                    read = { it.url.orEmpty() },
                    apply = { c, s -> c.copy(url = s.ifBlank { null }) },
                ),
                text(
                    "command", "启动命令", "stdio 型 server 的可执行文件；与 url 二选一。",
                    read = { it.command.orEmpty() },
                    apply = { c, s -> c.copy(command = s.ifBlank { null }) },
                ),
                text(
                    "args", "参数", "启动命令的参数，空格分隔。",
                    read = { it.args.joinToString(" ") },
                    apply = { c, s ->
                        c.copy(args = s.split(' ').map { it.trim() }.filter { it.isNotEmpty() })
                    },
                ),
                text(
                    "headers", "请求头", "HTTP 型 server 的请求头，每行一个「名: 值」。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" } },
                    apply = { c, s -> c.copy(headers = parsePairs(s)) },
                ),
                text(
                    "env", "环境变量", "stdio 型 server 的环境变量，每行一个「名: 值」。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.env.entries.joinToString("\n") { (k, v) -> "$k: $v" } },
                    apply = { c, s -> c.copy(env = parsePairs(s)) },
                ),
                text(
                    "disabled_tools", "停用工具", "这些工具不从该 server 注册，逗号分隔。",
                    read = { it.disabledTools.joinToString(",") },
                    apply = { c, s ->
                        c.copy(disabledTools = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    },
                ),
                ClosureField(
                    path = "$path.enabled",
                    displayName = "启用（$forId）",
                    description = "关闭后不连接该 server。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(server(forId)?.enabled ?: false) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        mutate(forId) { it.copy(enabled = b) }
                    },
                ),
            ) + oauthFields
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("mcp.servers 的新项需为 JSON 对象")
            val name = (obj["name"] as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("缺少 name")
            if (!McpServerConfig.isValidName(name)) {
                throw ConfigError.InvalidValue("server 名只能含 ASCII 字母、数字、下划线与连字符：$name")
            }
            // 同名覆盖：payload 只表达 name / url / command / args，其余字段（headers /
            // env / enabled / disabled_tools，以及最关键的 oauth 令牌块）一律从既有同名
            // server 继承——重建时丢掉 oauth 会让用户必须重新授权。无同名 server 时按
            // 新项处理（取默认值）。
            // 连接字段：payload 只要给了 url / command 之一，就按它重建（未给的那个清空，
            // 以支持 http↔stdio 切换）；两者都未给则保留旧值，避免空 payload 把 server 打废。
            val existing = server(name)
            val providesConnection = obj.containsKey("url") || obj.containsKey("command")
            val created = McpServerConfig(
                name = name,
                url = if (providesConnection) {
                    (obj["url"] as? ConfigValue.Str)?.value?.ifBlank { null }
                } else {
                    existing?.url
                },
                command = if (providesConnection) {
                    (obj["command"] as? ConfigValue.Str)?.value?.ifBlank { null }
                } else {
                    existing?.command
                },
                args = if (obj.containsKey("args")) {
                    (obj["args"] as? ConfigValue.Str)?.value
                        ?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                } else {
                    existing?.args.orEmpty()
                },
                headers = existing?.headers ?: emptyMap(),
                env = existing?.env ?: emptyMap(),
                enabled = existing?.enabled ?: true,
                disabledTools = existing?.disabledTools ?: emptySet(),
                oauth = existing?.oauth,
            )
            saveAll(servers().filterNot { it.name == name } + created)
            return name
        }

        override fun remove(id: String) {
            val all = servers()
            if (all.none { it.name == id }) throw ConfigError.InvalidValue("MCP server 不存在：$id")
            saveAll(all.filterNot { it.name == id })
        }
    }

    /** 解析「名: 值」逐行的键值对，忽略空行与没有冒号的行。 */
    private fun parsePairs(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(':') }
            .associate { line ->
                val sep = line.indexOf(':')
                line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }
}
