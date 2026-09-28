package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpServerConfig
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
 * 集合 id 是 server 名（受名称校验约束，仅 ASCII 字母数字下划线连字符），
 * 但 `setGlobalServers` 自身不校验重名，因此 add 用未知 id 会与既有 server 冲突，
 * 这里显式查重。
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
            "全局 mcp.json 里的 server。子字段：url / headers / command / args / env / enabled / disabled_tools。"

        override fun childIds(): List<String> = servers().map { it.name }

        override fun fields(forId: String): List<ConfigField> {
            if (server(forId) == null) return emptyList()
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
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("mcp.servers 的新项需为 JSON 对象")
            val name = (obj["name"] as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("缺少 name")
            if (!McpServerConfig.isValidName(name)) {
                throw ConfigError.InvalidValue("server 名只能含 ASCII 字母、数字、下划线与连字符：$name")
            }
            if (server(name) != null) throw ConfigError.InvalidValue("MCP server 已存在：$name")
            val created = McpServerConfig(
                name = name,
                url = (obj["url"] as? ConfigValue.Str)?.value?.ifBlank { null },
                command = (obj["command"] as? ConfigValue.Str)?.value?.ifBlank { null },
                args = (obj["args"] as? ConfigValue.Str)?.value
                    ?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
            saveAll(servers() + created)
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
