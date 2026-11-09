package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.plugin.domain.model.PluginManifest
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 把已安装插件里声明了 `mcpEntry` 的那些派生成 MCP server，让插件里的工具直接进 Agent 的工具表。
 *
 * 派生项**不落盘**：清单是唯一事实源，插件被删或改了清单，下一次重载就自然消失，
 * 不必维护第二份配置，也不会出现「用户删了插件但 mcp.json 里还留着一条连不上的」。
 *
 * 直接读插件目录而不经 PluginManager：只为了绕开「插件管理器 ← MCP 配置」的反向依赖。
 * 目录布局与 [com.aharou.feature.plugin.domain.PluginManager] 保持一致，改那边要同步这里。
 */
@Singleton
class McpPluginServers @Inject constructor(
    private val containerInstaller: ContainerInstaller,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun servers(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        val root = File(containerInstaller.aharouDir, PLUGINS_DIR)
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.mapNotNull { dir ->
            val manifest = readManifest(dir) ?: return@mapNotNull null
            val entry = manifest.mcpEntry.trim()
            // 没声明入口的插件只给用户手动用，保持原来的行为
            if (entry.isEmpty()) return@mapNotNull null
            val id = manifest.id.ifBlank { dir.name }
            McpServerConfig(
                name = serverName(id),
                command = if (entry.startsWith("/")) entry else "$CONTAINER_ROOT/$id/$entry",
                args = manifest.mcpArgs,
                env = manifest.environment,
                // 进程由插件管理器与健康巡检管；MCP 侧再按空闲回收会绕开那套状态
                keepAlive = true,
            )
        }
    }

    private fun readManifest(dir: File): PluginManifest? {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<PluginManifest>(file.readText(Charsets.UTF_8))
        }.onFailure {
            FileLogger.w(TAG, "读取插件清单失败：${dir.name}", it)
        }.getOrNull()
    }

    /**
     * server 名会拼进工具名（`mcp__名称__工具名`），只接受 ASCII 字母、数字、下划线与连字符。
     * 插件 id 合规就原样用，否则净化后补 id 哈希，免得 `a.b` 与 `a-b` 撞成同一个命名空间。
     */
    private fun serverName(id: String): String {
        val lowered = id.lowercase()
        if (SAFE_ID.matches(lowered)) return "$NAME_PREFIX$lowered"
        val sanitized = lowered.replace(Regex("[^a-z0-9_-]"), "-").take(28).trim('-').ifEmpty { "p" }
        val suffix = (id.hashCode() and 0xFFFF).toString(16).padStart(4, '0')
        return "$NAME_PREFIX$sanitized-$suffix"
    }

    private companion object {
        const val TAG = "McpPluginServers"
        const val MANIFEST_FILE = "manifest.json"
        const val PLUGINS_DIR = "plugins"
        const val NAME_PREFIX = "plugin-"

        /** 宿主 `filesDir/aharou/plugins/<id>` 经 PRoot 绑定成这个容器路径。 */
        const val CONTAINER_ROOT = "/root/.aharou/plugins"
        val SAFE_ID = Regex("[a-z0-9_-]{1,32}")
    }
}
